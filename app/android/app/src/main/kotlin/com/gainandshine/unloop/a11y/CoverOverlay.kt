package com.gainandshine.unloop.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.gainandshine.unloop.core.detect.CoverRect
import com.gainandshine.unloop.core.model.Rect
import java.util.concurrent.Executors

/**
 * Draws opaque cover windows over screen regions the engine wants hidden (a tab button, the Stories row ...).
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY, which needs no extra permission because the accessibility service is bound.
 * Each cover swallows touches, so the hidden control cannot be pressed. Views are marked not important for
 * accessibility so they emit no events of their own. Main thread only. Every window-manager call is guarded.
 *
 * Colour: a cover is painted in the colour the app itself uses behind that control. That colour is read once from a
 * screenshot, remembered per app and theme (also across restarts), and reused for every later cover, so covers appear
 * at once and in the right colour. A cover is NEVER painted with a guessed colour on Android 11+: if the colour is not
 * known yet it waits for the sample (Android allows one screenshot per third of a second, so it retries).
 */
class CoverOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val views = HashMap<String, View>()
    private val rects = HashMap<String, Rect>()
    private val main = Handler(Looper.getMainLooper())
    private val io by lazy { Executors.newSingleThreadExecutor() }
    private val prefs = service.getSharedPreferences("unloop_cover_colors", Context.MODE_PRIVATE)

    private var pkg = ""
    private val colorMemory = HashMap<String, Int>()

    /** Surfaces waiting for the first colour sample of this app, and the state of the (one at a time) sampler. */
    private val pending = LinkedHashSet<String>()
    private var sampleBusy = false
    private var attempts = 0

    /** Since when a covered node has been missing from the scan. A short gap keeps a fixed button's cover up. */
    private val missingSince = HashMap<String, Long>()
    private val follows = HashMap<String, Boolean>()
    private val pinned = HashMap<String, Boolean>()
    private val wantedNow = HashMap<String, Rect>()

    /** Covers placed ahead of the scan, from where the buttons were last time. They vanish unless the scan confirms them. */
    private val provisional = HashMap<String, Long>()

    /** Bring the on-screen covers in line with [wanted] for the app [forPkg]. Cheap when nothing changed. */
    fun apply(wanted: List<CoverRect>, forPkg: String) {
        pkg = forPkg
        val now = SystemClock.uptimeMillis()
        wantedNow.clear()
        wanted.forEach {
            wantedNow[it.surface] = it.bounds
            follows[it.surface] = it.follows
            pinned[it.surface] = it.pinBottom
            provisional.remove(it.surface) // the real node is on screen: no longer a guess
        }
        for (op in CoverDiff.diff(rects, wanted)) {
            when (op) {
                is CoverDiff.Op.Add -> add(op.surface, op.rect)
                is CoverDiff.Op.Move -> move(op.surface, op.rect)
                is CoverDiff.Op.Remove -> when {
                    op.surface in provisional -> Unit // not found yet while the app loads: let the guess expire on its own
                    // Content that scrolls must go the instant its node does, or it sits on top of a post.
                    follows[op.surface] == true -> remove(op.surface)
                    else -> missingSince.putIfAbsent(op.surface, now)
                }
            }
        }
        for (k in wantedNow.keys) missingSince.remove(k)
        pending.retainAll(wantedNow.keys)
        expire()
    }

    /**
     * Place covers at their last known positions the moment the app's main screen comes up, before the scan can find
     * the buttons, so they never show for a split second. Only with a known colour. Removed after [PROVISIONAL_MS]
     * unless the scan confirms them.
     */
    fun preplace(forPkg: String, lastRects: Map<String, Rect>) {
        pkg = forPkg
        val color = knownColor(forPkg) ?: return
        val now = SystemClock.uptimeMillis()
        var any = false
        for ((surface, r) in lastRects) {
            if (surface in views) continue
            show(surface, r, color)
            provisional[surface] = now
            follows[surface] = false
            any = true
        }
        if (any) main.postDelayed({ expireProvisional() }, PROVISIONAL_MS + 20)
    }

    private fun expireProvisional() {
        val now = SystemClock.uptimeMillis()
        for ((s, at) in provisional.entries.toList()) {
            if (now - at >= PROVISIONAL_MS) {
                provisional.remove(s)
                if (s !in wantedNow) remove(s)
            }
        }
    }

    /**
     * Instant, predicted move for covers that follow scrolling content: a vertical scroll of [shiftY] pixels moves the
     * content by the same amount, so shift now and let the next lookup correct any error. Off-screen covers are removed.
     */
    fun shiftBy(shiftY: Int) {
        if (shiftY == 0) return
        val h = service.resources.displayMetrics.heightPixels
        for (k in views.keys.toList()) {
            if (follows[k] != true) continue
            val r = rects[k] ?: continue
            val bottom = if (pinned[k] == true) r.bottom else r.bottom + shiftY
            val nr = Rect(r.left, r.top + shiftY, r.right, bottom)
            if (nr.bottom <= 0 || nr.top >= h || nr.bottom <= nr.top) remove(k) else move(k, nr)
        }
    }

    /** Drop covers whose node stayed missing past the hold time. Also called by the service's guard tick. */
    fun expire() {
        val now = SystemClock.uptimeMillis()
        for ((k, since) in missingSince.entries.toList()) {
            if (now - since >= HOLD_MS) { missingSince.remove(k); remove(k) }
        }
        expireProvisional()
    }

    val isShowing: Boolean get() = views.isNotEmpty()

    /** Remove everything (the app left the screen). Learned colours are kept. */
    fun clear() {
        for (k in views.keys.toList()) remove(k)
        missingSince.clear()
        provisional.clear()
        pending.clear()
        attempts = 0
    }

    private fun add(surface: String, r: Rect) {
        if (surface in views) return
        val c = knownColor(pkg)
        when {
            c != null -> show(surface, r, c)
            Build.VERSION.SDK_INT < 30 -> show(surface, r, themeGuess()) // no screenshots before Android 11
            else -> {
                pending += surface
                trySample()
            }
        }
    }

    /** One screenshot at a time. Success stores the colour and shows every waiting cover; failure retries shortly. */
    private fun trySample() {
        if (sampleBusy || pending.isEmpty() || Build.VERSION.SDK_INT < 30) return
        val surface = pending.first()
        val r = wantedNow[surface] ?: rects[surface]
        if (r == null) {
            pending.remove(surface)
            return trySample()
        }
        val samplePkg = pkg
        sampleBusy = true
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, io, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val picked = try {
                        val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val soft = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                        bmp?.recycle()
                        soft?.let { b -> try { BackgroundSampler.pick(b, r) } finally { b.recycle() } }
                    } catch (_: Throwable) { null } finally { try { result.hardwareBuffer.close() } catch (_: Throwable) {} }
                    main.post { sampled(samplePkg, picked) }
                }
                override fun onFailure(errorCode: Int) { main.post { sampled(samplePkg, null) } }
            })
        } catch (_: RuntimeException) {
            sampleBusy = false
            retry()
        }
    }

    private fun sampled(samplePkg: String, color: Int?) {
        sampleBusy = false
        if (color == null) return retry()
        attempts = 0
        rememberColor(samplePkg, color)
        if (samplePkg != pkg) return
        for (s in pending.toList()) {
            pending.remove(s)
            wantedNow[s]?.let { show(s, it, color) }
        }
    }

    private fun retry() {
        if (pending.isEmpty()) return
        if (++attempts > MAX_ATTEMPTS) {
            // Could not read the colour: show nothing rather than a wrong patch.
            pending.clear()
            attempts = 0
            return
        }
        main.postDelayed({ trySample() }, RETRY_MS)
    }

    private fun show(surface: String, r: Rect, color: Int) {
        if (surface in views) return
        try {
            val v = View(service).apply {
                setBackgroundColor(color)
                isClickable = true
                setOnTouchListener { _, _ -> true } // swallow every touch inside the cover
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            wm.addView(v, params(r))
            views[surface] = v
            rects[surface] = r
        } catch (_: RuntimeException) {
            // overlay refused by the system: leave the screen as is
        }
    }

    private fun move(surface: String, r: Rect) {
        val v = views[surface] ?: return add(surface, r)
        try {
            wm.updateViewLayout(v, params(r))
            rects[surface] = r
        } catch (_: RuntimeException) {
            remove(surface)
        }
    }

    private fun remove(surface: String) {
        val v = views.remove(surface)
        rects.remove(surface)
        provisional.remove(surface)
        if (v != null) try { wm.removeViewImmediate(v) } catch (_: RuntimeException) { /* already gone */ }
    }

    private fun params(r: Rect): WindowManager.LayoutParams {
        val pad = 1 // overlap the button edge by a pixel so no seam shows
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(
            r.width + 2 * pad, r.height + 2 * pad, r.left - pad, r.top - pad,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Unloop cover"
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
    }

    // ---- colour memory: per app and system theme, kept across restarts for a few days ----

    private fun night() = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun key(forPkg: String) = "$forPkg|${if (night()) "dark" else "light"}"

    private fun knownColor(forPkg: String): Int? {
        val k = key(forPkg)
        colorMemory[k]?.let { return it }
        val saved = prefs.getString(k, null) ?: return null
        val parts = saved.split('|')
        val color = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > COLOR_TTL_MS) return null // stale: look again
        colorMemory[k] = color
        return color
    }

    private fun rememberColor(forPkg: String, color: Int) {
        val k = key(forPkg)
        colorMemory[k] = color
        prefs.edit().putString(k, "$color|${System.currentTimeMillis()}").apply()
    }

    /** Only for Android 10 and older, which cannot take screenshots: follow the system theme. */
    private fun themeGuess(): Int = if (night()) Color.BLACK else Color.WHITE

    private companion object {
        /** A fixed button's cover stays this long when its node is briefly missing. Short, so it never lingers. */
        const val HOLD_MS = 120L

        /** A cover placed from memory lives this long unless the scan confirms the button. */
        const val PROVISIONAL_MS = 700L
        const val RETRY_MS = 380L
        const val MAX_ATTEMPTS = 8
        const val COLOR_TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}

package com.gainandshine.unloop.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.gainandshine.unloop.core.engine.CounterState

/**
 * A small pill at the top of the screen that shows how many items have been swiped past. It never takes touches or
 * focus. Grey while the count is low, amber as it grows, red near the auto-kick limit. Main thread only; every
 * window-manager call is guarded so a failure here can never break the service.
 */
class CounterOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var shown = ""

    fun show(state: CounterState) {
        val text = if (state.kickAfter > 0) "${state.count} / ${state.kickAfter}" else "${state.count}"
        val v = view ?: create() ?: return
        if (text == shown) return
        shown = text
        v.text = text
        val (bg, fg) = colors(state)
        (v.background as? GradientDrawable)?.setColor(bg)
        v.setTextColor(fg)
    }

    fun hide() {
        val v = view ?: return
        view = null
        shown = ""
        try { wm.removeViewImmediate(v) } catch (_: RuntimeException) { /* already gone */ }
    }

    private fun colors(s: CounterState): Pair<Int, Int> {
        val ratio = if (s.kickAfter > 0) s.count.toFloat() / s.kickAfter else s.count / DEFAULT_SCALE
        return when {
            ratio >= RED_AT -> Color.parseColor("#FFFF3B2F") to Color.WHITE
            ratio >= AMBER_AT -> Color.parseColor("#FFFFC53D") to Color.parseColor("#FF0A0A0A")
            else -> Color.parseColor("#E6141414") to Color.WHITE
        }
    }

    private fun create(): TextView? = try {
        val dp = service.resources.displayMetrics.density
        val v = TextView(service).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(Color.parseColor("#E6141414")) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + (8 * dp).toInt()
            title = "Unloop counter"
        }
        wm.addView(v, lp)
        view = v
        v
    } catch (_: RuntimeException) {
        null
    }

    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else (24 * service.resources.displayMetrics.density).toInt()
    }

    private companion object {
        /** Without auto-kick, colour steps happen around these counts. */
        const val DEFAULT_SCALE = 20f
        const val AMBER_AT = 0.6f
        const val RED_AT = 0.85f
    }
}

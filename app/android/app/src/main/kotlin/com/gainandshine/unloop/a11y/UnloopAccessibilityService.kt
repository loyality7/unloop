package com.gainandshine.unloop.a11y

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import com.gainandshine.unloop.core.clock.Clock
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.facebook.FacebookAdapter
import com.gainandshine.unloop.core.detect.x.XAdapter
import com.gainandshine.unloop.core.detect.instagram.InstagramAdapter
import com.gainandshine.unloop.core.detect.snapchat.SnapchatAdapter
import com.gainandshine.unloop.core.detect.tiktok.TikTokAdapter
import com.gainandshine.unloop.core.detect.youtube.YoutubeAdapter
import com.gainandshine.unloop.core.engine.ClassifierContext
import com.gainandshine.unloop.core.engine.DetectionEngine
import com.gainandshine.unloop.core.engine.EngineInputs
import com.gainandshine.unloop.core.engine.EngineOutput
import com.gainandshine.unloop.core.engine.EventClassifier
import com.gainandshine.unloop.core.engine.ForegroundSignal
import com.gainandshine.unloop.core.engine.InMemoryDiagnostics
import com.gainandshine.unloop.core.engine.RuleMiss
import com.gainandshine.unloop.core.engine.WindowEvidence
import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.block.AppLimitEnforcer
import com.gainandshine.unloop.store.NativeSettings
import com.gainandshine.unloop.store.StoreProvider
import com.gainandshine.unloop.system.PausePolicy
import com.gainandshine.unloop.system.PrivacyGuard
import com.gainandshine.unloop.system.Targets
import com.gainandshine.unloop.system.AliveStamp
import com.gainandshine.unloop.system.NullSnapBackoff
import com.gainandshine.unloop.system.RecheckPolicy
import com.gainandshine.unloop.system.ScanTiming
import java.time.Instant
import java.time.ZoneId

/**
 * The only class that touches live accessibility objects. Everything is wrapped in [safe]: a throw in a
 * callback (and above all in onDestroy) can permanently kill an accessibility service.
 */
class UnloopAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val diag = InMemoryDiagnostics()
    private val launchers = LiveSet()
    private val imes = LiveSet()
    private var engine: DetectionEngine? = null
    private var executor: ActionExecutor? = null
    private var classifier: ClassifierContext? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var cover: CoverOverlay? = null
    private var counterOverlay: CounterOverlay? = null
    private var coverPkg: String? = null

    /** Packages the service listens to and may read (see [PrivacyGuard]). Null until the first applyScope. */
    private var scopeKey: Set<String>? = null
    private var supportedPkgs: Set<String> = emptySet()
    private var paymentPkgs: Set<String> = emptySet()

    /** While a cover is on screen, verify every 700 ms that its app is still in front. */
    private val guardRunnable = object : Runnable {
        override fun run() { safe("guard") { guardCheck() } }
    }
    private val resyncRunnable = Runnable { safe("resync") { onSettingsChanged() } }

    private var lastInputs: EngineInputs? = null
    private var cfgAt = Long.MIN_VALUE
    // No Context use in field initializers: the base context is only attached after construction.
    private var cfg: DetectionConfig? = null
    private val backoff = NullSnapBackoff()
    private var armedDue = Long.MIN_VALUE
    private var consumedDue = Long.MIN_VALUE
    private var fgPkg: String? = null
    private var lastPersist = Long.MIN_VALUE
    private val recheckRunnable = object : Runnable {
        override fun run() {
            safe("recheck") {
                val p = fgPkg
                if (p != null && RecheckPolicy.shouldRun(AppLimitEnforcer.get(this@UnloopAccessibilityService).isTarget(p), interactive())) {
                    AppLimitEnforcer.get(this@UnloopAccessibilityService).onForeground(p)
                    stamp()
                    main.postDelayed(this, RecheckPolicy.INTERVAL_MS)
                }
            }
        }
    }
    private var lastSnap: SnapResult? = null
    private var scanPending = false

    private val scanRunnable = Runnable { safe("scan") { scanPending = false; consumedDue = armedDue; runScan() } }
    private val refreshRunnable = object : Runnable {
        override fun run() {
            safe("refresh") { refreshPackages() }
            main.postDelayed(this, REFRESH_MS)
        }
    }

    private val clock = object : Clock {
        override fun monotonicMs() = SystemClock.elapsedRealtime()
        override fun now(): Instant = Instant.now()
        override fun zone(): ZoneId = ZoneId.systemDefault()
    }

    override fun onServiceConnected() = safe("connected") {
        super.onServiceConnected()
        ensureEngine()
        // Back on: whoever switched us on, a pending step-aside is over.
        NativeSettings.setAsideSince(this, 0L)
        com.gainandshine.unloop.system.PaymentAside.cancelSafety(this)
        stopService(Intent(this, com.gainandshine.unloop.system.PaymentGuardService::class.java))
        registerScreenReceiver()
        executor = ActionExecutor(this)
        cover = CoverOverlay(this)
        counterOverlay = CounterOverlay(this)
        diagRef = diag
        connected = true
        instance = this
        lastAliveElapsed = SystemClock.elapsedRealtime()
        stamp()
        // If the block screen cannot launch (OS background-start limits), push the user home instead.
        com.gainandshine.unloop.block.AppLimitEnforcer.homeFallback = { performGlobalAction(GLOBAL_ACTION_HOME) }
        main.removeCallbacks(refreshRunnable)
        refreshRunnable.run()
        // Reset engine state (disable -> enable clears every tracker), then restart timers for the live window.
        val eng = engine ?: return@safe
        val inputs = deriveInputs(force = true)
        eng.update(inputs.copy(enabled = false))
        eng.update(inputs)
        lastInputs = inputs
        val snap = Snapshotter.take(this, clock.monotonicMs()) ?: return@safe
        lastSnap = snap
        val e = EventRecord(EventType.WINDOW_STATE_CHANGED, snap.snap.pkg, null, atMs = snap.snap.atMs)
        handle(eng.onEvent(e))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = safe("event") {
        event ?: return@safe
        val eng = engine ?: return@safe
        val now = clock.monotonicMs()
        lastAliveElapsed = now
        // Payment apps are never read. When one opens and the user allowed it, Unloop really switches itself off.
        val evPkg = event.packageName?.toString()
        if (evPkg != null && PrivacyGuard.isNeverWatched(evPkg)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && NativeSettings.paymentAside(this)) {
                com.gainandshine.unloop.system.PaymentAside.stepAside(this)
            }
            return@safe
        }
        syncInputs(eng)
        strictGuard(event, now)
        val rec = EventMapper.map(event, now)
        fastCovers(rec, now)
        if (rec.type == EventType.WINDOWS_CHANGED && coverPkg != null) guardCheck()
        val evidence = evidenceFor(rec)
        val signal = EventClassifier.classify(rec, classifier ?: return@safe, evidence)
        if (signal is ForegroundSignal.AppWindow) {
            safe("limits") { AppLimitEnforcer.get(this).onForeground(signal.pkg) }
            if (signal.pkg != fgPkg) { fgPkg = signal.pkg; armRecheck() }
            if (signal.pkg != coverPkg) clearCovers() // never leave a cover on top of a different app
        } else if (signal == ForegroundSignal.Home) {
            fgPkg = null; main.removeCallbacks(recheckRunnable)
            clearCovers()
        }
        if (AliveStamp.shouldPersist(lastPersist, now)) stamp()
        handle(eng.onEvent(rec, evidence))
    }

    /** Learn mode: save one privacy-scrubbed snapshot (ids, classes, bounds, flags; no text) of the app being learned. */
    private fun doCapture() {
        val pkg = NativeSettings.learnPackage(this)
        if (pkg.isEmpty() || PrivacyGuard.isNeverWatched(pkg)) return
        val snap = Snapshotter.take(this, clock.monotonicMs(), setOf(pkg))
        val store = com.gainandshine.unloop.learn.LearnStore(this)
        if (snap == null || snap.snap.pkg != pkg) {
            android.widget.Toast.makeText(this, "Open the app first, then capture", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val withText = NativeSettings.flag(this, "learn_text")
        store.add(snap.snap, System.currentTimeMillis(), withText)
        com.gainandshine.unloop.learn.LearnNotifier.show(this, "${store.list().size} saved${if (withText) " (with text)" else ""}. Open a screen, then tap Capture.")
        android.widget.Toast.makeText(this, "Screen saved", android.widget.Toast.LENGTH_SHORT).show()
    }

    private var lastStrictCheck = Long.MIN_VALUE

    /**
     * Strict mode: if the Settings page that would turn Unloop off comes up, go Home. Reads only the visible texts of
     * that one Settings window (bounded), never any other app. Throttled; every failure means "do nothing".
     */
    private fun strictGuard(event: AccessibilityEvent, now: Long) {
        val pkg = event.packageName?.toString() ?: return
        if (!com.gainandshine.unloop.BuildConfig.STRICT_BOUNCE) return // the Google Play build never interferes with the settings page
        if (pkg !in com.gainandshine.unloop.core.limits.StrictEscapeGuard.SETTINGS_PACKAGES || !NativeSettings.strictActive(this)) return
        if (now - lastStrictCheck < STRICT_CHECK_MS) return
        lastStrictCheck = now
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return
        try {
            if (root.packageName?.toString() != pkg) return
            val texts = ArrayList<String>()
            var budget = STRICT_NODE_BUDGET
            fun walk(n: AccessibilityNodeInfo) {
                if (budget-- <= 0) return
                n.text?.toString()?.let(texts::add)
                n.contentDescription?.toString()?.let(texts::add)
                for (i in 0 until n.childCount) {
                    val c = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue
                    try { walk(c) } finally { release(c) }
                }
            }
            walk(root)
            val label = packageManager.getApplicationLabel(applicationInfo).toString()
            if (com.gainandshine.unloop.core.limits.StrictEscapeGuard.shouldBounce(pkg, texts, label)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                android.widget.Toast.makeText(this, "Strict mode is on until ${StrictClock.hm(NativeSettings.strictUntil(this))}", android.widget.Toast.LENGTH_SHORT).show()
            }
        } finally {
            release(root)
        }
    }

    override fun onInterrupt() = safe("interrupt") {
        // The engine owns its scheduler and exposes no reset; drop our pending timer, the next event re-arms it.
        main.removeCallbacks(scanRunnable)
        scanPending = false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        if (instance === this) instance = null
        com.gainandshine.unloop.block.AppLimitEnforcer.homeFallback = null
        teardown()
        return try { super.onUnbind(intent) } catch (_: Throwable) { false }
    }

    override fun onDestroy() {
        connected = false
        if (instance === this) instance = null
        teardown()
        try { super.onDestroy() } catch (_: Throwable) {}
    }

    /** Every step in its own try/catch: teardown must never throw. */
    private fun teardown() {
        try { main.removeCallbacks(scanRunnable) } catch (_: Throwable) {}
        try { main.removeCallbacks(refreshRunnable) } catch (_: Throwable) {}
        try { main.removeCallbacks(fastRunnable) } catch (_: Throwable) {}
        fastPending = false
        try { main.removeCallbacks(recheckRunnable) } catch (_: Throwable) {}
        fgPkg = null
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        screenReceiver = null
        scanPending = false
        lastSnap = null
        executor = null
        clearCovers()
        cover = null
        try { counterOverlay?.hide() } catch (_: Throwable) {}
        counterOverlay = null
        try { main.removeCallbacks(resyncRunnable) } catch (_: Throwable) {}
    }

    // ---- engine wiring ----

    private fun ensureEngine() {
        if (engine != null) return
        val ctx = ClassifierContext(
            ownPackage = packageName,
            overlayClassNames = emptySet(),
            imePackages = imes,
            launcherPackages = launchers,
            systemPackages = setOf(
                "com.android.systemui", "com.android.settings", "com.google.android.permissioncontroller",
                "com.android.packageinstaller",
            ),
            pipOnlyPackages = emptySet(),
        )
        classifier = ctx
        val adapters = listOf(InstagramAdapter(), YoutubeAdapter(), TikTokAdapter(), SnapchatAdapter(), FacebookAdapter(), XAdapter())
        supportedPkgs = adapters.flatMapTo(HashSet()) { it.pkgs }
        engine = DetectionEngine(
            adapters = adapters,
            classifier = ctx,
            clock = clock,
            inputs = EngineInputs(enabled = false),
            diagnostics = diag,
            budgets = com.gainandshine.unloop.store.PrefsBudgets(this),
        )
    }

    private fun deriveInputs(force: Boolean = false): EngineInputs {
        val now = SystemClock.elapsedRealtime()
        var c = cfg
        if (c == null || force || now - cfgAt >= CONFIG_MS) {
            c = NativeSettings.detectionConfig(this)
            cfg = c
            cfgAt = now
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val awake = interactive() && km?.isKeyguardLocked != true
        val active = NativeSettings.masterEnabled(this) && !NativeSettings.isPaused(this)
        return EngineInputs(active, awake, c)
    }

    private fun interactive(): Boolean = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true

    private fun stamp() {
        lastPersist = SystemClock.elapsedRealtime()
        NativeSettings.stampConnected(this)
    }

    /** (Re)start the 30 s limit re-check for the current foreground package; cancels itself when not needed. */
    private fun armRecheck() {
        main.removeCallbacks(recheckRunnable)
        val p = fgPkg ?: return
        if (RecheckPolicy.shouldRun(AppLimitEnforcer.get(this).isTarget(p), interactive())) {
            main.postDelayed(recheckRunnable, RecheckPolicy.INTERVAL_MS)
        }
    }

    /** Schedule boundary / boot hook: enforce against the current foreground package right now. */
    private fun recheckForeground() {
        val p = fgPkg ?: return
        if (interactive()) AppLimitEnforcer.get(this).onForeground(p)
        armRecheck()
    }

    private fun syncInputs(eng: DetectionEngine) {
        val i = deriveInputs()
        if (i != lastInputs) { lastInputs = i; eng.update(i) }
    }

    /** Positive evidence only: the event window is known not to be an application window. */
    private fun evidenceFor(e: EventRecord): WindowEvidence? {
        if (e.type != EventType.WINDOW_STATE_CHANGED || e.windowId < 0) return null
        for (w in windows.orEmpty()) {
            try {
                if (w.id == e.windowId) {
                    return if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)
                        WindowEvidence(eventWindowIsNonApplication = true) else null
                }
            } finally { release(w) }
        }
        return null
    }

    private fun handle(out: EngineOutput) {
        val eng = engine ?: return
        updateCounter(eng)
        when (out) {
            is EngineOutput.Do -> {
                // Count only actions that were dispatched; the engine is told either way so it can verify/give up.
                if (executor?.perform(out.action, lastSnap) == true) NativeSettings.bumpBlockCount(this)
                eng.onActionCompleted()
            }
            is EngineOutput.ScheduleScan -> { schedule(out.atMs); return }
            EngineOutput.Nothing -> Unit
        }
        eng.nextScanAtMs()?.let(::schedule)
    }

    /** At most one pending runnable; the engine always reports the earliest due time. */
    private fun schedule(atMs: Long) {
        main.removeCallbacks(scanRunnable)
        scanPending = true
        armedDue = atMs
        main.postDelayed(scanRunnable, ScanTiming.delay(atMs, clock.monotonicMs(), consumedDue))
    }

    private fun runScan() {
        val eng = engine ?: return
        syncInputs(eng)
        val snap = Snapshotter.take(this, clock.monotonicMs(), scopeKey)
        lastSnap = snap
        if (snap == null) {
            // Nothing readable in front (a financial or unwatched app took over?): hide any cover, poll briefly, then wait for an event.
            clearCovers()
            backoff.nextOrNull()?.let { schedule(clock.monotonicMs() + it) }
            return
        }
        backoff.reset()
        // Covers are recomputed on every scan so they follow scrolling and vanish with their node.
        safe("covers") { updateCovers(eng, snap.snap) }
        handle(eng.onScan(snap.snap))
    }

    private fun updateCounter(eng: DetectionEngine) {
        val st = eng.counterState(clock.monotonicMs())
        if (st == null) counterOverlay?.hide() else counterOverlay?.show(st)
    }

    /**
     * [shift] is how far the page scrolled while this snapshot was being read. Covers that follow scrolling content are
     * moved by it, so a slow read never puts a cover where the content used to be.
     */
    private fun updateCovers(eng: DetectionEngine, snap: UiSnapshot, shift: Int = 0) {
        val wanted = eng.planCovers(snap).mapNotNull { c ->
            if (!c.follows || shift == 0) c else {
                val b = c.bounds
                val moved = com.gainandshine.unloop.core.model.Rect(b.left, b.top + shift, b.right, if (c.pinBottom) b.bottom else b.bottom + shift)
                if (moved.isEmpty) null else c.copy(bounds = moved)
            }
        }
        // Remember where the fixed buttons were, so they can be covered the instant the app's main screen comes back.
        val fixed = wanted.filter { !it.follows }
        if (fixed.isNotEmpty()) {
            lastFixed[snap.pkg] = fixed.associate { it.surface to it.bounds }
            lastClsSeen[snap.pkg]?.let { mainCls[snap.pkg] = it }
        }
        cover?.apply(wanted, snap.pkg)
        coverPkg = if (cover?.isShowing == true) snap.pkg else null
        main.removeCallbacks(guardRunnable)
        if (coverPkg != null) main.postDelayed(guardRunnable, GUARD_MS)
    }

    private val lastFixed = HashMap<String, Map<String, com.gainandshine.unloop.core.model.Rect>>()
    private val lastClsSeen = HashMap<String, String>()
    private val mainCls = HashMap<String, String>()

    /** Vertical scroll since the last targeted read started: applied to the result when it arrives. */
    private var shiftSince = 0

    private var fastPending = false
    private var lastFast = 0L
    private var fastPkg: String? = null
    private val fastRunnable = Runnable { safe("fastCovers") { fastPending = false; lastFast = SystemClock.elapsedRealtime(); runFastCovers() } }

    /**
     * Covers must track the screen as it moves, not 500 ms later. On scroll and content events of an app that has
     * covers, shift following covers by the scroll amount right away, then (at most every ~24 ms) re-read just the
     * few view ids the covers need and reposition from the real bounds.
     */
    private fun fastCovers(rec: EventRecord, now: Long) {
        val pkg = rec.pkg ?: return
        val eng = engine ?: return
        if (eng.coverIdsFor(pkg).isEmpty() && eng.coverTextsFor(pkg).isEmpty()) return
        when (rec.type) {
            EventType.VIEW_SCROLLED -> if (rec.scrollDy != 0 && kotlin.math.abs(rec.scrollDy) > kotlin.math.abs(rec.scrollDx)) {
                cover?.shiftBy(-rec.scrollDy)
                shiftSince -= rec.scrollDy
            }
            EventType.WINDOW_STATE_CHANGED -> {
                rec.cls?.let { lastClsSeen[pkg] = it }
                // The app's main screen is back: cover its fixed buttons at once from memory, before the scan finds them.
                val last = lastFixed[pkg]
                if (last != null && rec.cls != null && rec.cls == mainCls[pkg]) cover?.preplace(pkg, last)
            }
            EventType.WINDOW_CONTENT_CHANGED -> Unit
            else -> return
        }
        fastPkg = pkg
        if (fastPending) return
        fastPending = true
        main.postDelayed(fastRunnable, (FAST_MS - (now - lastFast)).coerceIn(0L, FAST_MS))
    }

    private fun runFastCovers() {
        val eng = engine ?: return
        val pkg = fastPkg ?: return
        val ids = eng.coverIdsFor(pkg)
        val texts = eng.coverTextsFor(pkg)
        if (ids.isEmpty() && texts.isEmpty()) return
        shiftSince = 0
        val snap = Snapshotter.takeTargeted(this, pkg, ids, texts, clock.monotonicMs()) ?: return
        // Anything that scrolled while the read was running is applied now, so the result is never out of date.
        val moved = shiftSince
        shiftSince = 0
        updateCovers(eng, snap, moved)
    }

    private fun clearCovers() {
        try { cover?.clear() } catch (_: Throwable) {}
        coverPkg = null
        main.removeCallbacks(guardRunnable)
    }

    /** Cover safety: if the active window is not the covered app (or cannot be read), remove the cover now. */
    private fun guardCheck() {
        val pkg = coverPkg ?: return
        val active = Snapshotter.activePackage(this)
        if (active != pkg) { clearCovers(); return }
        cover?.expire()
        if (cover?.isShowing != true) { coverPkg = null; return }
        main.postDelayed(guardRunnable, GUARD_MS)
    }

    /**
     * Listen only to what we need: supported apps, launchers and apps the user set limits/schedules on, never
     * financial apps. Payment apps cannot be stopped from noticing an enabled service, but they are never read.
     */
    private fun applyScope() {
        val store = StoreProvider.get(this)
        val targets = Targets.effective(
            store.limits.all().map { it.target },
            Targets.parseScheduled(store.settings.get(Targets.KEY_SCHEDULED)) +
                com.gainandshine.unloop.core.limits.AppGroups.members(com.gainandshine.unloop.core.limits.AppGroups.parse(store.settings.get(Targets.KEY_GROUPS))),
        )
        val extra = HashSet<String>()
        if (com.gainandshine.unloop.BuildConfig.STRICT_BOUNCE && NativeSettings.strictActive(this)) extra += com.gainandshine.unloop.core.limits.StrictEscapeGuard.SETTINGS_PACKAGES
        if (NativeSettings.paymentAside(this) && com.gainandshine.unloop.system.PaymentAside.canWrite(this)) extra += paymentPkgs
        NativeSettings.learnPackage(this).takeIf { it.isNotEmpty() && !PrivacyGuard.isNeverWatched(it) }?.let { extra += it }
        val watched = PrivacyGuard.watchedPackages(supportedPkgs, launchers.current, targets) + extra
        if (watched == scopeKey) return
        val info = serviceInfo ?: return
        info.packageNames = watched.toTypedArray()
        serviceInfo = info
        scopeKey = watched
    }

    /** Settings changed (pause, master switch, limits): re-derive everything now and arm the pause-end resync. */
    private fun onSettingsChanged() {
        lastInputs = null
        cfgAt = Long.MIN_VALUE
        engine?.let { syncInputs(it) }
        if (NativeSettings.isPaused(this) || !NativeSettings.masterEnabled(this)) clearCovers()
        applyScope()
        main.removeCallbacks(resyncRunnable)
        val left = PausePolicy.remainingMs(NativeSettings.pausedUntilWall(this), System.currentTimeMillis())
        if (left > 0) main.postDelayed(resyncRunnable, left + RESYNC_SLACK_MS)
        recheckForeground()
    }

    // ---- environment ----

    private fun refreshPackages() {
        paymentPkgs = try { com.gainandshine.unloop.system.PaymentAside.installedPaymentApps(this).map { it.first }.toSet() } catch (_: Throwable) { emptySet() }
        val pm = packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val l = HashSet<String>()
        val flags = PackageManager.MATCH_DEFAULT_ONLY.toLong()
        val infos = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(home, PackageManager.ResolveInfoFlags.of(flags))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
        infos.forEach { it.activityInfo?.packageName?.let(l::add) }
        launchers.current = l
        applyScope()

        val i = HashSet<String>()
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.takeIf { it.isNotEmpty() }?.let(i::add)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.enabledInputMethodList?.forEach { i += it.packageName }
        imes.current = i
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = safe("screen") {
                lastInputs = null // force re-derive and update
                engine?.let { syncInputs(it) }
                if (interactive()) recheckForeground() else { main.removeCallbacks(recheckRunnable); clearCovers() }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(r, f)
        screenReceiver = r
    }

    companion object {
        private const val TAG = "UnloopA11y"
        private const val REFRESH_MS = 5 * 60_000L
        private const val CONFIG_MS = 500L
        private const val GUARD_MS = 700L
        private const val FAST_MS = 24L
        private const val STRICT_CHECK_MS = 600L
        private const val STRICT_NODE_BUDGET = 400
        private const val RESYNC_SLACK_MS = 50L

        @Volatile @JvmStatic var connected: Boolean = false
        @Volatile @JvmStatic var lastAliveElapsed: Long = 0L
        @Volatile private var instance: UnloopAccessibilityService? = null

        /** Re-check the foreground package against limits/schedules now. No-op when the service is not connected. */
        @JvmStatic fun captureNow() {
            val s = instance ?: return
            s.main.post { safe("capture") { s.doCapture() } }
        }

        @JvmStatic fun recheckNow() {
            val s = instance ?: return
            s.main.post { safe("recheckNow") { s.applyScope(); s.recheckForeground() } }
        }

        /** Pause, master switch or targets changed (tile, bridge). No-op when the service is not connected. */
        @JvmStatic fun settingsChanged() {
            val s = instance ?: return
            s.main.post { safe("settingsChanged") { s.onSettingsChanged() } }
        }

        @Volatile private var diagRef: InMemoryDiagnostics? = null

        /** Local counters only (no text, no package names). Best effort: read from another thread. */
        @JvmStatic fun diagnosticsSnapshot(): Map<String, Int> {
            val d = diagRef ?: return emptyMap()
            return try {
                buildMap {
                    for (s in NativeSettings.allSurfaces) {
                        put("block.$s", d.blocks(s)); put("success.$s", d.successes(s)); put("giveUp.$s", d.giveUps(s))
                    }
                    for (k in RuleMiss.entries) put("miss.${k.name}", d.ruleMisses(k))
                }
            } catch (_: Throwable) { emptyMap() }
        }

        private inline fun safe(what: String, block: () -> Unit) {
            try { block() } catch (t: Throwable) { Log.w(TAG, "$what failed: ${t.javaClass.simpleName}") }
        }
    }

}

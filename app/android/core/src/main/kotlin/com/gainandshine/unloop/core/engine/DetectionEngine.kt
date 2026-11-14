package com.gainandshine.unloop.core.engine

import com.gainandshine.unloop.core.clock.Clock
import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.CoverRect
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceAdapter
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.UiSnapshot

/** What the shell must do after an engine call. */
sealed interface EngineOutput {
    data object Nothing : EngineOutput

    /** Perform [action] now, then call [DetectionEngine.onActionCompleted]. */
    data class Do(val action: Action, val surface: String, val reason: String) : EngineOutput

    /** Take a snapshot at monotonic [atMs] and call [DetectionEngine.onScan]. Replaces earlier requests. */
    data class ScheduleScan(val atMs: Long) : EngineOutput
}

/** Switches supplied by the shell. */
data class EngineInputs(
    /** Master switch. FAIL DIRECTION: false means the engine does nothing at all. */
    val enabled: Boolean = false,
    /** Screen interactive AND device unlocked. */
    val interactiveUnlocked: Boolean = true,
    val config: DetectionConfig = DetectionConfig(),
)

/**
 * Orchestrator. Single-threaded: call from one thread (the service main thread). All timing uses the
 * monotonic timestamps of events/snapshots, and [clock] for shell-driven calls.
 *
 * Shell contract:
 *  1. [update] whenever the master switch, screen/lock state or config changes.
 *  2. Every accessibility event -> [onEvent]. Obey a returned [EngineOutput.ScheduleScan].
 *  3. When the timer fires, build a [UiSnapshot] and call [onScan]. Perform [EngineOutput.Do], then call
 *     [onActionCompleted]. After any call, [nextScanAtMs] says when the next scan is wanted.
 *
 * FAIL DIRECTION: disabled / locked / untracked / adapter exception all produce [EngineOutput.Nothing].
 */
class DetectionEngine(
    adapters: List<SurfaceAdapter>,
    private val classifier: ClassifierContext,
    private val clock: Clock,
    inputs: EngineInputs = EngineInputs(),
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.None,
    ladderConfig: LadderConfig = LadderConfig(),
    private val scheduler: ScanScheduler = ScanScheduler(),
    private val guard: LoopGuard = LoopGuard(),
    private val sitting: SittingTracker = SittingTracker(),
    budgets: SurfaceBudgets = SurfaceBudgets.None,
) {
    private val meter = BudgetMeter(budgets)
    private val scrollCounter = ScrollCounter()
    private var watching: String? = null
    private var watchingAt = Long.MIN_VALUE
    private var screenHeight = 2400
    private val registry = AdapterRegistry(adapters)
    private val ladder = EnforcementLadder(ladderConfig)
    private var inputs = inputs
    private var lastAppPkg: String? = null

    fun ladderState(surface: String): SurfaceLadderState? = ladder.state(surface)

    fun update(new: EngineInputs) {
        val old = inputs
        inputs = new
        if (old.enabled && !new.enabled) resetAll()
        if (!gated()) scheduler.clear() // nothing may stay due while gated off (the shell would busy-loop)
        if (new.enabled) {
            val now = clock.monotonicMs()
            if (old.interactiveUnlocked && !new.interactiveUnlocked) sitting.onScreenOff(now)
            if (!old.interactiveUnlocked && new.interactiveUnlocked) sitting.onScreenOn(now)?.let { endSitting() }
        }
    }

    fun onEvent(e: EventRecord, evidence: WindowEvidence? = null): EngineOutput {
        if (!gated()) return EngineOutput.Nothing
        val signal = EventClassifier.classify(e, classifier, evidence)
        sitting.onSignal(signal, e.atMs)?.let { endSitting() }
        when (signal) {
            is ForegroundSignal.AppWindow -> onAppForeground(signal.pkg)
            ForegroundSignal.Home -> onLeft()
            ForegroundSignal.SystemSurface, ForegroundSignal.OwnUi, ForegroundSignal.AwarenessOverlay,
            ForegroundSignal.Transient, ForegroundSignal.PipOnly, ForegroundSignal.NotForeground -> Unit
        }
        val pkg = e.pkg?.takeIf(::isTracked)
        val relevant = when (signal) {
            is ForegroundSignal.AppWindow, ForegroundSignal.NotForeground -> true
            ForegroundSignal.Home, ForegroundSignal.SystemSurface, ForegroundSignal.OwnUi,
            ForegroundSignal.AwarenessOverlay, ForegroundSignal.Transient, ForegroundSignal.PipOnly -> false
        }
        if (pkg == null || !relevant) return EngineOutput.Nothing
        when (e.type) {
            EventType.VIEW_CLICKED, EventType.VIEW_SCROLLED, EventType.VIEW_SELECTED -> sitting.onInteraction(pkg)
            EventType.WINDOW_STATE_CHANGED, EventType.WINDOW_CONTENT_CHANGED, EventType.WINDOWS_CHANGED,
            EventType.OTHER -> Unit
        }
        if (e.type == EventType.VIEW_SCROLLED && watching != null && e.atMs - watchingAt <= WATCH_FRESH_MS) {
            if (scrollCounter.onScroll(e.atMs, e.scrollDx, e.scrollDy, e.fromIndex, screenHeight)) {
                val kickAt = inputs.config.autoKickAfter
                val surface = watching
                if (kickAt > 0 && surface != null && scrollCounter.count >= kickAt) {
                    scrollCounter.reset()
                    watching = null
                    return EngineOutput.Do(Action.Home, surface, REASON_AUTO_KICK)
                }
            }
        }
        guarded { registry.adapterFor(pkg)?.onEvent(e) }
        scheduler.onEvent(e, tracked = true)
        return scheduleOutput()
    }

    fun onScan(snap: UiSnapshot): EngineOutput {
        if (!gated()) {
            scheduler.clear() // consume the due scan: nextScanAtMs() must not stay in the past
            return EngineOutput.Nothing
        }
        val now = snap.atMs
        scheduler.takeDue(now)
        sitting.tick(now)?.let { endSitting() }
        val adapter = registry.adapterFor(snap.pkg)
        if (adapter == null || !isTracked(snap.pkg)) return EngineOutput.Nothing
        if (sitting.onSignal(ForegroundSignal.AppWindow(snap.pkg), now) != null) endSitting()
        onAppForeground(snap.pkg)

        guard.blockedUntil(now)?.let { until ->
            scheduler.requestAt(until)
            return EngineOutput.ScheduleScan(until)
        }
        if (snap.primary == null) diagnostics.ruleMiss(RuleMiss.NO_PRIMARY_WINDOW)

        // To count swipes where nothing is blocked, countable feeds are looked for even when their block switch is off.
        val cfg = inputs.config
        val evalCfg = if (cfg.counting) cfg.copy(enabled = cfg.enabled + SurfaceId.COUNTABLE) else cfg
        val verdict = try {
            adapter.evaluate(snap, evalCfg)
        } catch (_: Exception) {
            diagnostics.ruleMiss(RuleMiss.ADAPTER_ERROR)
            Verdict.None
        }
        // Defence in depth: a verdict for a switched-off surface is dropped.
        var watched: String? = null
        var effective: Verdict = if (verdict is Verdict.Block && !cfg.isOn(verdict.surface)) {
            if (cfg.counting && verdict.surface in SurfaceId.COUNTABLE) watched = verdict.surface
            Verdict.None
        } else verdict
        // A daily allowance lets the user watch until it is used up. Time is charged per scan, so keep scanning.
        val blockedNow = effective as? Verdict.Block
        val budgeted = blockedNow != null && meter.tryAllow(blockedNow.surface, now)
        if (budgeted) {
            watched = blockedNow.surface
            effective = Verdict.None
        } else if (blockedNow == null) meter.idle()
        trackWatching(watched, now, snap.screen.height)
        if (effective is Verdict.Block && !guard.debounceOk(effective.surface, now)) return EngineOutput.Nothing

        val step = ladder.next(effective, now)
        ladder.lastSucceeded.forEach(diagnostics::success)
        return when (step) {
            Step.Idle -> if (budgeted || watched != null) {
                val next = now + (if (budgeted) BudgetMeter.POLL_MS else WATCH_POLL_MS)
                scheduler.requestAt(next)
                EngineOutput.ScheduleScan(next)
            } else EngineOutput.Nothing
            is Step.Perform -> {
                guard.onActionIssued(now)
                diagnostics.block(step.surface)
                EngineOutput.Do(step.action, step.surface, (effective as Verdict.Block).reason)
            }
            is Step.Wait -> {
                scheduler.requestAt(step.untilMs)
                EngineOutput.ScheduleScan(step.untilMs)
            }
            is Step.GiveUp -> {
                diagnostics.giveUp(step.surface)
                EngineOutput.Nothing
            }
        }
    }

    /**
     * Regions to hide under cover windows for this snapshot (empty when gated off, untracked, or nothing matches).
     * Independent of blocking: covers are recomputed on every scan so they follow scrolling and vanish with their node.
     */
    fun planCovers(snap: UiSnapshot): List<CoverRect> {
        if (!gated()) return emptyList()
        val adapter = registry.adapterFor(snap.pkg) ?: return emptyList()
        return try {
            // A button hiding the way into a feed stays visible while that feed still has daily allowance left.
            adapter.covers(snap, inputs.config).filterNot { c ->
                SurfaceId.budgetParent(c.surface)?.let { meter.remainingMs(it) > 0 } == true
            }
        } catch (_: Exception) {
            diagnostics.ruleMiss(RuleMiss.ADAPTER_ERROR)
            emptyList()
        }
    }

    /**
     * View ids (with read depth) the shell should look up to refresh covers for [pkg] right now. Empty when the engine is
     * gated off or no cover surface of that app is switched on, so the fast path costs nothing when unused.
     */
    fun coverIdsFor(pkg: String): Map<String, Int> {
        if (!gated()) return emptyMap()
        val adapter = registry.adapterFor(pkg) ?: return emptyMap()
        return if (adapter.coverSurfaces.any(inputs.config::isOn)) adapter.coverIds else emptyMap()
    }

    /** Label texts the fast cover path should search for in [pkg] (for example "Sponsored"). Empty when unused. */
    fun coverTextsFor(pkg: String): Set<String> {
        if (!gated()) return emptySet()
        return registry.adapterFor(pkg)?.coverTexts(inputs.config).orEmpty()
    }

    /** The shell finished performing the last [EngineOutput.Do]. */
    fun onActionCompleted() = guard.onActionCompleted(clock.monotonicMs())

    fun nextScanAtMs(): Long? = scheduler.nextDueMs()

    private fun gated() = inputs.enabled && inputs.interactiveUnlocked

    private fun isTracked(pkg: String): Boolean {
        val surfaces = registry.adapterFor(pkg)?.surfaces ?: return false
        return surfaces.any(inputs.config::isOn) || (inputs.config.counting && surfaces.any { it in SurfaceId.COUNTABLE })
    }

    private fun trackWatching(surface: String?, now: Long, height: Int) {
        if (height > 0) screenHeight = height
        if (surface != null) {
            if (watching != surface) scrollCounter.reset()
            watching = surface
            watchingAt = now
        } else if (watching != null && now - watchingAt > WATCH_IDLE_RESET_MS) {
            resetWatching()
        }
    }

    private fun resetWatching() {
        scrollCounter.reset()
        watching = null
        watchingAt = Long.MIN_VALUE
    }

    /** What the on-screen counter should show right now, or null when nothing is being watched or it is switched off. */
    fun counterState(nowMs: Long): CounterState? {
        if (!gated() || !inputs.config.counter) return null
        val s = watching ?: return null
        if (nowMs - watchingAt > WATCH_FRESH_MS) return null
        return CounterState(s, scrollCounter.count, inputs.config.autoKickAfter)
    }

    private fun onAppForeground(pkg: String) {
        if (lastAppPkg != null && lastAppPkg != pkg) onLeft()
        lastAppPkg = pkg
    }

    /** Foreground package changed or home: pending verifications succeeded because the user left. */
    private fun onLeft() {
        meter.idle()
        resetWatching()
        ladder.onForegroundChanged()
        lastAppPkg = null
    }

    private fun endSitting() {
        meter.idle()
        resetWatching()
        registry.all.forEach { guarded { it.reset() } }
        ladder.clear()
        scheduler.clear()
        lastAppPkg = null
    }

    private fun resetAll() {
        endSitting()
        guard.reset()
    }

    private fun scheduleOutput(): EngineOutput =
        scheduler.nextDueMs()?.let { EngineOutput.ScheduleScan(it) } ?: EngineOutput.Nothing

    /** A buggy adapter must never take the accessibility service down. */
    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (_: Exception) { diagnostics.ruleMiss(RuleMiss.ADAPTER_ERROR) }
    }

    private companion object {
        /** Scans repeat this often while a feed is being watched, so the counter notices when you leave it. */
        const val WATCH_POLL_MS = 2_000L

        /** A swipe only counts if the feed was seen this recently. */
        const val WATCH_FRESH_MS = 4_000L

        /** Leaving the feed for this long (still in the app) starts a fresh count. */
        const val WATCH_IDLE_RESET_MS = 120_000L
        const val REASON_AUTO_KICK = "auto_kick"
    }
}

/** Adapters by package. Two adapters claiming one package is a wiring bug: fail fast at construction. */
class AdapterRegistry(adapters: List<SurfaceAdapter>) {
    val all: List<SurfaceAdapter> = adapters
    private val byPkg: Map<String, SurfaceAdapter> = buildMap {
        for (a in adapters) for (p in a.pkgs) {
            require(put(p, a) == null) { "package claimed by two adapters: $p" }
        }
    }

    fun adapterFor(pkg: String): SurfaceAdapter? = byPkg[pkg]
}

/** What the counter overlay displays. [kickAfter] is 0 when auto-kick is off. */
data class CounterState(val surface: String, val count: Int, val kickAfter: Int)

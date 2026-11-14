package com.gainandshine.unloop.core.engine

/** Why a sitting ended. */
enum class SittingEnd { WENT_HOME, SCREEN_OFF_LONG, ANOTHER_APP_HELD }

/**
 * Pure model of "has the user left the app". A sitting begins at the first AppWindow and ends ONLY by:
 * Home (immediate), screen off whose absence lasted >= [returnWindowMs] (judged on return), or a different
 * app held in front >= [otherAppHoldMs] (judged on the next signal or [tick]).
 * Unknown/transient/system signals are never evidence of leaving; an interaction in the sitting's app
 * cancels any pending absence or other-app timer.
 *
 * FAIL DIRECTION: with doubt the sitting continues (we under-reset rather than over-reset). A backwards
 * monotonic input yields a negative elapsed time, which never satisfies a threshold.
 * All times are monotonic ms. Single-threaded.
 */
class SittingTracker(
    private val returnWindowMs: Long = DEFAULT_WINDOW_MS,
    private val otherAppHoldMs: Long = DEFAULT_WINDOW_MS,
) {
    private var pkg: String? = null
    private var absenceStartedAt: Long? = null
    private var otherPkg: String? = null
    private var otherSince: Long = 0

    /** Package of the active sitting, or null. */
    val currentPkg: String? get() = pkg

    fun onSignal(signal: ForegroundSignal, nowMs: Long): SittingEnd? = when (signal) {
        is ForegroundSignal.AppWindow -> onAppWindow(signal.pkg, nowMs)
        ForegroundSignal.Home -> if (pkg != null) end(SittingEnd.WENT_HOME) else null
        ForegroundSignal.SystemSurface, ForegroundSignal.OwnUi, ForegroundSignal.AwarenessOverlay,
        ForegroundSignal.Transient, ForegroundSignal.PipOnly, ForegroundSignal.NotForeground -> null
    }

    private fun onAppWindow(p: String, now: Long): SittingEnd? {
        val cur = pkg
        if (cur == null) { pkg = p; return null }
        if (p == cur) { otherPkg = null; return null }
        if (otherPkg != p) { otherPkg = p; otherSince = now; return null }
        if (now - otherSince >= otherAppHoldMs) {
            val r = end(SittingEnd.ANOTHER_APP_HELD)
            pkg = p // the held app starts its own sitting
            return r
        }
        return null
    }

    /** Evaluate the held-other-app timer without a new signal (call from scans). */
    fun tick(nowMs: Long): SittingEnd? {
        val o = otherPkg ?: return null
        if (pkg == null || nowMs - otherSince < otherAppHoldMs) return null
        val r = end(SittingEnd.ANOTHER_APP_HELD)
        pkg = o
        return r
    }

    fun onScreenOff(nowMs: Long) {
        if (pkg != null && absenceStartedAt == null) absenceStartedAt = nowMs
    }

    fun onScreenOn(nowMs: Long): SittingEnd? {
        val start = absenceStartedAt ?: return null
        absenceStartedAt = null
        return if (nowMs - start >= returnWindowMs) end(SittingEnd.SCREEN_OFF_LONG) else null
    }

    /** User touched/scrolled inside [forPkg]. Cancels a pending absence and a pending other-app timer. */
    fun onInteraction(forPkg: String) {
        if (forPkg == pkg) { absenceStartedAt = null; otherPkg = null }
    }

    private fun end(r: SittingEnd): SittingEnd {
        pkg = null; absenceStartedAt = null; otherPkg = null
        return r
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2 * 60 * 1000L
    }
}

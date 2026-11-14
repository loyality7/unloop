package com.gainandshine.unloop.core.engine

/**
 * Anti-loop gates on the monotonic clock:
 *  - global cooldown of [cooldownMs] after an action is issued (or completes, if later reported);
 *  - scans are ignored while an action is in flight, bounded by [inFlightTimeoutMs] so a lost completion
 *    report cannot wedge the engine;
 *  - per-surface debounce of [debounceMs] between admitted verdicts.
 *
 * FAIL DIRECTION: when unsure, suppress (never act faster than the cooldown). Backwards clock input is
 * handled by re-basing the stored timestamp to "now", so a gate lasts at most one full window from the
 * regression instead of being stuck forever or opening early. Single-threaded.
 */
class LoopGuard(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val inFlightTimeoutMs: Long = DEFAULT_IN_FLIGHT_TIMEOUT_MS,
) {
    private var lastActionAt: Long? = null
    private var inFlightSince: Long? = null
    private val lastSurfaceAt = HashMap<String, Long>()

    fun onActionIssued(nowMs: Long) {
        lastActionAt = nowMs
        inFlightSince = nowMs
    }

    /** The shell finished performing the action (success or not). Cooldown restarts from here. */
    fun onActionCompleted(nowMs: Long) {
        inFlightSince = null
        lastActionAt = nowMs
    }

    fun inFlight(nowMs: Long): Boolean {
        val s = inFlightSince ?: return false
        if (nowMs < s) { inFlightSince = nowMs; return true }
        if (nowMs - s >= inFlightTimeoutMs) { inFlightSince = null; return false }
        return true
    }

    /** Monotonic time at which scans are allowed again, or null if allowed now. */
    fun blockedUntil(nowMs: Long): Long? {
        val last = lastActionAt
        val cooldownEnd = if (last == null) null else {
            val l = if (nowMs < last) nowMs.also { lastActionAt = it } else last
            (l + cooldownMs).takeIf { it > nowMs }
        }
        val flightEnd = if (inFlight(nowMs)) inFlightSince!! + inFlightTimeoutMs else null
        return listOfNotNull(cooldownEnd, flightEnd).maxOrNull()
    }

    fun scansAllowed(nowMs: Long): Boolean = blockedUntil(nowMs) == null

    /** Per-surface debounce. Records the admission when it returns true. */
    fun debounceOk(surface: String, nowMs: Long): Boolean {
        val last = lastSurfaceAt[surface]
        if (last != null && nowMs >= last && nowMs - last < debounceMs) return false
        lastSurfaceAt[surface] = nowMs
        return true
    }

    fun reset() {
        lastActionAt = null; inFlightSince = null; lastSurfaceAt.clear()
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 800L
        const val DEFAULT_DEBOUNCE_MS = 100L
        const val DEFAULT_IN_FLIGHT_TIMEOUT_MS = 2_000L
    }
}

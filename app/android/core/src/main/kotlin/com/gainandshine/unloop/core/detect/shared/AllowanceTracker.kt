package com.gainandshine.unloop.core.detect.shared

import kotlin.math.abs

/**
 * Lets exactly one externally-entered short-form item through (shared reel, DM reel, deep link) and ends the
 * allowance as soon as the user starts browsing. All times are monotonic ms; the tracker never reads a clock.
 *
 * Life cycle: IDLE -> [arm] -> ARMED (waits up to [armWindowMs] for the viewer) -> first [observe] starts it (RUNNING)
 * -> ENDED when any of: cumulative |dy| >= [SCROLL_END_PERCENT]% of viewer height ([onScroll]), pager index changes
 * ([onScroll]), author changes ([observe]). ENDED sticks until the viewer has been unseen for [idleResetMs]
 * or [reset]. A RUNNING/ENDED tracker that has not been observed for [idleResetMs] resets itself.
 * The author is kept only as a hash, in memory, and never logged.
 */
class AllowanceTracker(
    private val armWindowMs: Long = ARM_WINDOW_MS,
    private val idleResetMs: Long = IDLE_RESET_MS,
) {
    private enum class State { IDLE, ARMED, RUNNING, ENDED }

    private var state = State.IDLE
    private var armedUntilMs = 0L
    private var lastSeenMs = 0L
    private var authorHash: Int? = null
    private var viewerHeight = 0
    private var baseIndex = NO_INDEX
    private var cumDy = 0L
    private var lastScrollMs = 0L

    /** True while an allowance has started and not ended. */
    val isRunning: Boolean get() = state == State.RUNNING

    /** True once the allowance was used up; stays true until the viewer goes away or [reset]. */
    val isEnded: Boolean get() = state == State.ENDED

    /** Open an allowance that must be consumed within [armWindowMs] of [nowMs]. No-op while running or ended. */
    fun arm(nowMs: Long) {
        if (state == State.IDLE || state == State.ARMED) {
            state = State.ARMED
            armedUntilMs = nowMs + armWindowMs
        }
    }

    /**
     * Call for every evaluation in which the viewer is on screen. Returns true if this item is still allowed.
     * [author] is the visible author name or null when unknown; [viewerHeightPx] feeds the swipe threshold.
     */
    fun observe(nowMs: Long, author: String?, viewerHeightPx: Int): Boolean {
        if ((state == State.RUNNING || state == State.ENDED) && nowMs - lastSeenMs > idleResetMs) reset()
        when (state) {
            State.IDLE -> return false
            State.ARMED -> {
                if (nowMs > armedUntilMs) {
                    reset()
                    return false
                }
                state = State.RUNNING
                authorHash = author?.hashCode()
                viewerHeight = viewerHeightPx
                lastSeenMs = nowMs
                return true
            }
            State.ENDED -> {
                lastSeenMs = nowMs
                return false
            }
            State.RUNNING -> {
                lastSeenMs = nowMs
                viewerHeight = viewerHeightPx
                val first = authorHash
                if (author != null) {
                    if (first == null) authorHash = author.hashCode() else if (first != author.hashCode()) state = State.ENDED
                }
                return state == State.RUNNING
            }
        }
    }

    /** Feed VIEW_SCROLLED deltas. Ignored unless RUNNING; mostly-horizontal scrolls are ignored. */
    fun onScroll(nowMs: Long, dx: Int, dy: Int, pagerIndex: Int) {
        if (state != State.RUNNING) return
        if (pagerIndex != NO_INDEX) {
            if (baseIndex == NO_INDEX) {
                baseIndex = pagerIndex
            } else if (pagerIndex != baseIndex) {
                state = State.ENDED
                return
            }
        }
        if (viewerHeight <= 0 || abs(dx) > abs(dy)) return
        if (nowMs - lastScrollMs > SCROLL_GAP_MS) cumDy = 0
        lastScrollMs = nowMs
        cumDy += abs(dy)
        if (cumDy * PERCENT_BASE >= SCROLL_END_PERCENT.toLong() * viewerHeight) state = State.ENDED
    }

    /** Forget everything (package left the foreground, feature switched off). */
    fun reset() {
        state = State.IDLE
        armedUntilMs = 0
        lastSeenMs = 0
        authorHash = null
        viewerHeight = 0
        baseIndex = NO_INDEX
        cumDy = 0
        lastScrollMs = 0
    }

    companion object {
        const val ARM_WINDOW_MS = 5_000L
        const val IDLE_RESET_MS = 1_500L
        const val SCROLL_GAP_MS = 1_000L
        const val SCROLL_END_PERCENT = 12
        private const val PERCENT_BASE = 100L
        private const val NO_INDEX = -1
    }
}

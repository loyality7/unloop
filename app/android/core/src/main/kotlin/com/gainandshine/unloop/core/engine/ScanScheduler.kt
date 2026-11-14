package com.gainandshine.unloop.core.engine

import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import java.util.TreeSet

/**
 * Pure scan-timing policy. The shell performs the scan when a due time arrives (a time is due when
 * `due <= now`, boundary inclusive).
 *  - content / scroll / click / select / windows-changed events: one trailing scan [trailingDebounceMs] after
 *    the LAST such event (each event pushes it back);
 *  - a real window-state change additionally queues a burst of [burstCount] scans, [burstSpacingMs] apart
 *    (pane-title changes only get the trailing scan);
 *  - events of a package no adapter tracks schedule nothing.
 * Verification waits requested by the ladder are registered with [requestAt].
 *
 * FAIL DIRECTION: scanning too often is bounded by the burst size and debounce; if unsure whether a
 * package is tracked the caller passes false and nothing is scheduled (no battery cost, no false action).
 */
class ScanScheduler(
    private val trailingDebounceMs: Long = DEFAULT_TRAILING_DEBOUNCE_MS,
    private val burstCount: Int = DEFAULT_BURST_COUNT,
    private val burstSpacingMs: Long = DEFAULT_BURST_SPACING_MS,
    private val maxLatencyMs: Long = DEFAULT_MAX_LATENCY_MS,
) {
    private var trailingAt: Long? = null
    private var firstPendingAt: Long? = null
    private val fixed = TreeSet<Long>()

    fun onEvent(e: EventRecord, tracked: Boolean) {
        if (!tracked) return
        when (e.type) {
            EventType.WINDOW_STATE_CHANGED -> {
                pushTrailing(e.atMs)
                if (!e.isPaneChange) {
                    fixed.clear()
                    for (i in 1..burstCount) fixed.add(e.atMs + i * burstSpacingMs)
                }
            }
            EventType.WINDOW_CONTENT_CHANGED, EventType.WINDOWS_CHANGED, EventType.VIEW_SCROLLED,
            EventType.VIEW_CLICKED, EventType.VIEW_SELECTED -> pushTrailing(e.atMs)
            EventType.OTHER -> Unit
        }
    }

    /** Trailing debounce, capped at [maxLatencyMs] after the first event of the pending burst. */
    private fun pushTrailing(atMs: Long) {
        val first = firstPendingAt ?: atMs.also { firstPendingAt = it }
        trailingAt = minOf(atMs + trailingDebounceMs, first + maxLatencyMs)
    }

    /** Ask for a scan at [atMs] (ladder verification, cooldown end). */
    fun requestAt(atMs: Long) {
        fixed.add(atMs)
    }

    /** Earliest pending due time, or null. */
    fun nextDueMs(): Long? = listOfNotNull(trailingAt, fixed.firstOrNull()).minOrNull()

    /** True (and consumes everything due, coalesced) if a scan should run at [nowMs]. */
    fun takeDue(nowMs: Long): Boolean {
        var due = false
        if (trailingAt?.let { it <= nowMs } == true) { trailingAt = null; firstPendingAt = null; due = true }
        while (fixed.isNotEmpty() && fixed.first() <= nowMs) { fixed.pollFirst(); due = true }
        return due
    }

    fun clear() {
        trailingAt = null; firstPendingAt = null; fixed.clear()
    }

    companion object {
        const val DEFAULT_TRAILING_DEBOUNCE_MS = 120L
        const val DEFAULT_BURST_COUNT = 4
        const val DEFAULT_BURST_SPACING_MS = 150L

        /** Upper bound from the first pending event to its trailing scan, however busy the surface is. */
        const val DEFAULT_MAX_LATENCY_MS = 500L
    }
}

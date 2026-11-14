package com.gainandshine.unloop.core.time

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A recurring weekly window, half-open `[start, end)` in local wall-clock time.
 *
 * Day semantics: a window BELONGS TO THE DAY IT STARTS ON. When `end < start` the window is overnight and
 * spills into the next calendar day: Fri 22:00-07:00 covers Fri 22:00 through Sat 06:59:59, and the next
 * day's own mask bit is irrelevant for that spill.
 *
 * `start == end` is rejected at construction (ambiguous: empty vs 24h). Model an all-day window as
 * 00:00-23:59 or as a window per day.
 */
data class Schedule(
    val id: String,
    val enabled: Boolean,
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val end: LocalTime,
) {
    init {
        require(start != end) { "Schedule $id: start == end is not allowed" }
    }

    /** True when the window crosses local midnight. */
    val overnight: Boolean get() = end < start
}

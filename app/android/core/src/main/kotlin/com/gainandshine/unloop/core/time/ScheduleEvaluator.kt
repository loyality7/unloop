package com.gainandshine.unloop.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure schedule math. Boundaries are resolved with `LocalDate.atTime(..).atZone(zone)`, so DST gaps shift
 * forward and overlaps pick the earlier offset (java.time rules). A window whose resolved start equals its
 * resolved end (a spring-forward gap swallowing it) is empty.
 */
object ScheduleEvaluator {
    private const val LOOKAHEAD_DAYS = 8L
    private const val MAX_CHAIN = 32

    private fun window(s: Schedule, startDate: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
        val endDate = if (s.overnight) startDate.plusDays(1) else startDate
        return startDate.atTime(s.start).atZone(zone).toInstant() to endDate.atTime(s.end).atZone(zone).toInstant()
    }

    /** True when [now] falls inside a window of [s] that started today or yesterday (local to `now.zone`). */
    fun isActive(s: Schedule, now: ZonedDateTime): Boolean {
        if (!s.enabled) return false
        val at = now.toInstant()
        return (-1L..0L).any { off ->
            val d = now.toLocalDate().plusDays(off)
            d.dayOfWeek in s.days && window(s, d, now.zone).let { (a, b) -> !at.isBefore(a) && at.isBefore(b) }
        }
    }

    /** True when any schedule is active. */
    fun isAnyActive(schedules: List<Schedule>, now: ZonedDateTime): Boolean = schedules.any { isActive(it, now) }

    /** Earliest start/end instant strictly after [now] across [schedules], or null when none within a week. */
    fun nextBoundary(schedules: List<Schedule>, now: ZonedDateTime): Instant? {
        val at = now.toInstant()
        var best: Instant? = null
        for (s in schedules.filter { it.enabled }) {
            for (off in -1L..LOOKAHEAD_DAYS) {
                val d = now.toLocalDate().plusDays(off)
                if (d.dayOfWeek !in s.days) continue
                val (a, b) = window(s, d, now.zone)
                for (c in listOf(a, b)) if (c.isAfter(at) && (best == null || c.isBefore(best))) best = c
            }
        }
        return best
    }

    /** Single-schedule convenience for [nextBoundary]. */
    fun nextBoundary(s: Schedule, now: ZonedDateTime): Instant? = nextBoundary(listOf(s), now)

    /**
     * When the contiguous blocked stretch containing [now] ends (back-to-back windows are chained).
     * Null when nothing is active, or when coverage never ends within [MAX_CHAIN] hops (effectively 24/7).
     */
    fun activeUntil(schedules: List<Schedule>, now: ZonedDateTime): Instant? {
        if (!isAnyActive(schedules, now)) return null
        var t = now
        repeat(MAX_CHAIN) {
            val next = nextBoundary(schedules, t) ?: return null
            t = next.atZone(now.zone)
            if (!isAnyActive(schedules, t)) return next
        }
        return null
    }
}

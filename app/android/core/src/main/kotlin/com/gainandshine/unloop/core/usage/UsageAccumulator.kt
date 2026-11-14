package com.gainandshine.unloop.core.usage

import com.gainandshine.unloop.core.clock.Clock
import com.gainandshine.unloop.core.limits.DayKey
import com.gainandshine.unloop.core.store.UsageRepository
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** A slice of a session that falls inside one usage day. */
data class DaySlice(val day: DayKey, val millis: Long)

/** Splits `[start, end)` at day boundaries (midnight, or the configured day start). */
fun splitByDay(start: Instant, end: Instant, zone: ZoneId, dayStart: LocalTime = LocalTime.MIDNIGHT): List<DaySlice> {
    val out = mutableListOf<DaySlice>()
    var cur = start
    while (cur.isBefore(end)) {
        val key = DayKey.of(cur, zone, dayStart)
        val e = minOf(end, key.end())
        out += DaySlice(key, e.toEpochMilli() - cur.toEpochMilli())
        cur = e
    }
    return out
}

/**
 * Feeds stitched sessions into a [UsageRepository] per day and package. Safe to call repeatedly with
 * overlapping event windows: the high-water mark lives in the repository.
 *
 * Closed spans advance the high-water mark. The span still open at the end of the events is billed LIVE up to
 * `clock.now()` (uncapped: no closing event means the screen is still interactive); each call bills only
 * `[billedUntil, now]` and persists that via [UsageRepository.setOpenSpan]. The inferred-close cap only applies
 * to spans closed by a later event, measured from the last observation.
 *
 * Write order: usage, then high-water mark, then open-span state. A crash in between can re-bill at most the
 * delta of ONE refresh interval (never loses data, never double counts more than that).
 */
class UsageAccumulator(
    private val repo: UsageRepository,
    private val stitcher: SessionStitcher,
    private val clock: Clock,
    private val dayStart: LocalTime = LocalTime.MIDNIGHT,
) {
    /** Ingest events; returns milliseconds newly billed. */
    fun ingest(events: List<UsageEvent>): Long {
        val live = repo.openSpan()
        val res = stitcher.stitch(events, repo.highWater(), null, live)
        var total = bill(res.sessions)
        res.highWater?.let { repo.setHighWater(it) }
        val open = res.open
        if (open == null) {
            if (live != null) repo.setOpenSpan(null)
            return total
        }
        val now = clock.now()
        if (now.isAfter(open.billedUntil)) {
            total += bill(listOf(Session(open.pkg, open.billedUntil, now)))
            repo.setOpenSpan(open.copy(billedUntil = now))
        } else if (open != live) {
            repo.setOpenSpan(open)
        }
        return total
    }

    private fun bill(sessions: List<Session>): Long {
        var total = 0L
        for (s in sessions) for (slice in splitByDay(s.start, s.end, clock.zone(), dayStart)) {
            repo.addMillis(slice.day, s.pkg, slice.millis)
            total += slice.millis
        }
        return total
    }

    /** Today key under the current clock and zone (rolls over automatically). */
    fun today(): DayKey = DayKey.of(clock.now(), clock.zone(), dayStart)

    /** Whole seconds used today by [pkg]. */
    fun usedSecondsToday(pkg: String): Long = repo.millis(today(), pkg) / MS_PER_SEC

    private companion object {
        const val MS_PER_SEC = 1000L
    }
}

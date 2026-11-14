package com.gainandshine.unloop.core.usage

import java.time.Duration
import java.time.Instant

enum class UsageEventType { RESUMED, PAUSED, SCREEN_OFF, SCREEN_ON, KEYGUARD_SHOWN, SHUTDOWN }

/** One raw OS usage event. */
data class UsageEvent(val instant: Instant, val pkg: String, val type: UsageEventType, val cls: String? = null)

/** A stitched foreground span `[start, end)`. */
data class Session(val pkg: String, val start: Instant, val end: Instant) {
    val millis: Long get() = Duration.between(start, end).toMillis()
}

/**
 * A span still open at the end of the input (no closing event seen), live-billed by the accumulator.
 * [billedUntil] is how far it has already been billed; it is persisted so a restart neither loses nor repeats it.
 */
data class OpenSpan(val pkg: String, val start: Instant, val billedUntil: Instant)

/**
 * [highWater] is the end of the latest CLOSED session; pass it back into the next run. [open] is the trailing
 * span left open when `now == null`; it never advances [highWater].
 */
data class StitchResult(val sessions: List<Session>, val highWater: Instant?, val open: OpenSpan? = null)

/**
 * Turns raw events into sessions. One open span at a time. Spans closed by anything other than an explicit
 * PAUSED of the same package (screen off, keyguard, shutdown, another app resuming, end of input with `now`)
 * are INFERRED and capped at [maxInferred]; this prevents the "unclosed span bills 17 hours" bug.
 * Trade-off: a real stretch longer than the cap with no events, then a screen-off, is billed as the cap.
 * The cap is measured from the last observation of the span (`live.billedUntil`, else its start).
 *
 * With `now == null` the trailing span is NOT closed: it is returned as [StitchResult.open] for live billing
 * and does not move the high-water mark. With `now != null` it is closed inferred (capped).
 *
 * Idempotent: sessions are clipped to start at or after the supplied high-water mark, so re-running over
 * overlapping event windows never double counts. Merging gaps (`< mergeGap`, same package) bills the gap
 * inside the merged session of that run only.
 */
class SessionStitcher(
    private val maxInferred: Duration = Duration.ofMinutes(DEFAULT_MAX_INFERRED_MIN),
    private val mergeGap: Duration = Duration.ZERO,
    private val ignore: (String) -> Boolean = { false },
) {
    fun stitch(
        events: List<UsageEvent>,
        highWater: Instant? = null,
        now: Instant? = null,
        live: OpenSpan? = null,
    ): StitchResult {
        val sorted = events.sortedWith(compareBy({ it.instant }, { if (it.type.opens()) 1 else 0 }))
        val raw = mutableListOf<Session>()
        var openPkg: String? = null
        var openAt: Instant = Instant.EPOCH

        fun close(at: Instant, explicit: Boolean) {
            val p = openPkg ?: return
            val real = if (at.isBefore(openAt)) openAt else at
            val observed = if (live != null && live.pkg == p && live.start == openAt) maxOf(openAt, live.billedUntil) else openAt
            val end = if (explicit) real else minOf(real, observed.plus(maxInferred))
            if (end.isAfter(openAt)) raw += Session(p, openAt, end)
            openPkg = null
        }

        for (e in sorted) when (e.type) {
            UsageEventType.RESUMED -> {
                if (ignore(e.pkg)) {
                    close(e.instant, false)
                } else if (openPkg != e.pkg) {
                    close(e.instant, false)
                    openPkg = e.pkg
                    openAt = e.instant
                }
            }
            UsageEventType.PAUSED -> if (openPkg == e.pkg) close(e.instant, true)
            UsageEventType.SCREEN_OFF, UsageEventType.KEYGUARD_SHOWN, UsageEventType.SHUTDOWN -> close(e.instant, false)
            UsageEventType.SCREEN_ON -> Unit
        }
        val trailing = openPkg?.let { Pair(it, openAt) }
        if (now != null) close(now, false)

        val merged = mutableListOf<Session>()
        for (s in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.pkg == s.pkg && Duration.between(last.end, s.start) < mergeGap) {
                merged[merged.lastIndex] = last.copy(end = s.end)
            } else {
                merged += s
            }
        }
        val clipped = merged.mapNotNull { s ->
            when {
                highWater == null -> s
                !s.end.isAfter(highWater) -> null
                else -> s.copy(start = maxOf(s.start, highWater))
            }
        }
        // High water follows the real end of closed spans even when live billing already covered them.
        val newHw = listOfNotNull(highWater, clipped.maxOfOrNull { it.end }).maxOrNull()
        val out = if (live == null) clipped else clipped.mapNotNull { it.withoutBilled(live) }
        val open = if (now != null) null else trailing?.let { (pkg, start) ->
            val sameAsLive = live != null && live.pkg == pkg && live.start == start
            OpenSpan(pkg, start, if (sameAsLive) live.billedUntil else maxOf(start, highWater ?: start))
        }
        return StitchResult(out, newHw, open)
    }

    /** Drops the part of a session that live billing already covered; null when nothing is left. */
    private fun Session.withoutBilled(live: OpenSpan): Session? {
        if (pkg != live.pkg || start.isAfter(live.start) || !end.isAfter(live.start)) return this
        val from = maxOf(start, live.billedUntil)
        return if (end.isAfter(from)) copy(start = from) else null
    }

    private fun UsageEventType.opens() = this == UsageEventType.RESUMED || this == UsageEventType.SCREEN_ON

    private companion object {
        const val DEFAULT_MAX_INFERRED_MIN = 10L
    }
}

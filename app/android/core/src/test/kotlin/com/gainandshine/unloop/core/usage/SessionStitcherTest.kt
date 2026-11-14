package com.gainandshine.unloop.core.usage

import com.gainandshine.unloop.core.clock.FakeClock
import com.gainandshine.unloop.core.limits.DayKey
import com.gainandshine.unloop.core.store.InMemoryUsageRepository
import com.gainandshine.unloop.core.usage.UsageEventType.KEYGUARD_SHOWN
import com.gainandshine.unloop.core.usage.UsageEventType.PAUSED
import com.gainandshine.unloop.core.usage.UsageEventType.RESUMED
import com.gainandshine.unloop.core.usage.UsageEventType.SCREEN_OFF
import com.gainandshine.unloop.core.usage.UsageEventType.SCREEN_ON
import com.gainandshine.unloop.core.usage.UsageEventType.SHUTDOWN
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStitcherTest {
    private val base = Instant.parse("2026-01-05T08:00:00Z")
    private fun t(min: Long, sec: Long = 0): Instant = base.plusSeconds(min * 60 + sec)
    private fun ev(at: Instant, type: UsageEventType, pkg: String = "A") = UsageEvent(at, pkg, type)
    private val stitcher = SessionStitcher()

    private fun spans(r: StitchResult) = r.sessions.map { Triple(it.pkg, it.start, it.end) }

    @Test
    fun simpleAndSwitchingApps() {
        val one = stitcher.stitch(listOf(ev(t(0), RESUMED), ev(t(5), PAUSED)))
        assertEquals(listOf(Triple("A", t(0), t(5))), spans(one))
        assertEquals(t(5), one.highWater)
        val sw = stitcher.stitch(listOf(ev(t(0), RESUMED), ev(t(3), RESUMED, "B"), ev(t(5), PAUSED, "B")))
        assertEquals(listOf(Triple("A", t(0), t(3)), Triple("B", t(3), t(5))), spans(sw))
        assertEquals(300_000L, one.sessions.single().millis)
    }

    @Test
    fun outOfOrderAndSameInstantOrdering() {
        val ordered = listOf(ev(t(0), RESUMED), ev(t(3), PAUSED), ev(t(4), RESUMED, "B"), ev(t(6), PAUSED, "B"))
        assertEquals(spans(stitcher.stitch(ordered)), spans(stitcher.stitch(ordered.reversed())))
        assertEquals(spans(stitcher.stitch(ordered)), spans(stitcher.stitch(ordered.shuffled(java.util.Random(7)))))
        val tie = stitcher.stitch(listOf(ev(t(3), RESUMED, "B"), ev(t(0), RESUMED), ev(t(3), PAUSED), ev(t(5), PAUSED, "B")))
        assertEquals(listOf(Triple("A", t(0), t(3)), Triple("B", t(3), t(5))), spans(tie))
        assertTrue(stitcher.stitch(listOf(ev(t(5), PAUSED), ev(t(6), RESUMED))).sessions.isEmpty())
    }

    @Test
    fun screenOffKeyguardAndShutdownCloseSpans() {
        for (closer in listOf(SCREEN_OFF, KEYGUARD_SHOWN, SHUTDOWN)) {
            val r = stitcher.stitch(listOf(ev(t(0), RESUMED), ev(t(4), closer, "")))
            assertEquals(listOf(Triple("A", t(0), t(4))), spans(r), closer.name)
        }
        val r = stitcher.stitch(listOf(ev(t(0), RESUMED), ev(t(4), SCREEN_OFF, ""), ev(t(30), SCREEN_ON, ""), ev(t(30), RESUMED), ev(t(32), PAUSED)))
        assertEquals(listOf(Triple("A", t(0), t(4)), Triple("A", t(30), t(32))), spans(r))
    }

    @Test
    fun seventeenHourBugRegression() {
        val hours17 = base.plus(Duration.ofHours(17))
        for (closer in listOf(SCREEN_OFF, KEYGUARD_SHOWN, SHUTDOWN)) {
            val r = stitcher.stitch(listOf(ev(t(0), RESUMED), ev(hours17, closer, "")))
            assertEquals(Duration.ofMinutes(10).toMillis(), r.sessions.single().millis, closer.name)
        }
        val noClose = stitcher.stitch(listOf(ev(t(0), RESUMED)), now = hours17)
        assertEquals(600_000L, noClose.sessions.single().millis)
        assertTrue(stitcher.stitch(listOf(ev(t(0), RESUMED))).sessions.isEmpty()) // no now: nothing billed
        val custom = SessionStitcher(maxInferred = Duration.ofMinutes(2)).stitch(listOf(ev(t(0), RESUMED)), now = t(60))
        assertEquals(120_000L, custom.sessions.single().millis)
        val other = stitcher.stitch(listOf(ev(t(0), RESUMED), ev(hours17, RESUMED, "B"), ev(hours17.plusSeconds(60), PAUSED, "B")))
        assertEquals(600_000L, other.sessions.first().millis)
    }

    @Test
    fun negativeAndZeroDurationsClamped() {
        assertTrue(stitcher.stitch(listOf(ev(t(10), RESUMED)), now = t(0)).sessions.isEmpty())
        assertTrue(stitcher.stitch(listOf(ev(t(1), RESUMED), ev(t(1), PAUSED))).sessions.isEmpty())
    }

    @Test
    fun ignoredPackages() {
        val s = SessionStitcher(ignore = { it == "launcher" || it == "com.gainandshine.unloop" })
        val r = s.stitch(
            listOf(ev(t(0), RESUMED), ev(t(2), RESUMED, "launcher"), ev(t(3), PAUSED, "launcher"), ev(t(4), RESUMED, "com.gainandshine.unloop"), ev(t(5), PAUSED, "com.gainandshine.unloop")),
        )
        assertEquals(listOf(Triple("A", t(0), t(2))), spans(r))
    }

    @Test
    fun mergeGaps() {
        val evs = listOf(ev(t(0), RESUMED), ev(t(5), PAUSED), ev(t(5, 20), RESUMED), ev(t(10), PAUSED))
        assertEquals(2, stitcher.stitch(evs).sessions.size)
        val merged = SessionStitcher(mergeGap = Duration.ofSeconds(30)).stitch(evs)
        assertEquals(listOf(Triple("A", t(0), t(10))), spans(merged))
        assertEquals(2, SessionStitcher(mergeGap = Duration.ofSeconds(20)).stitch(evs).sessions.size) // gap == threshold not merged
        val diff = listOf(ev(t(0), RESUMED), ev(t(5), PAUSED), ev(t(5, 10), RESUMED, "B"), ev(t(6), PAUSED, "B"))
        assertEquals(2, SessionStitcher(mergeGap = Duration.ofSeconds(30)).stitch(diff).sessions.size)
    }

    @Test
    fun idempotentOverlappingWindows() {
        val first = listOf(ev(t(0), RESUMED), ev(t(5), PAUSED), ev(t(6), RESUMED, "B"), ev(t(10), PAUSED, "B"))
        val r1 = stitcher.stitch(first)
        assertEquals(t(10), r1.highWater)
        val all = first + listOf(ev(t(20), RESUMED), ev(t(25), PAUSED))
        val r2 = stitcher.stitch(all, r1.highWater)
        assertEquals(listOf(Triple("A", t(20), t(25))), spans(r2))
        assertTrue(stitcher.stitch(all, r2.highWater).sessions.isEmpty())
        assertEquals(r1.sessions.sumOf { it.millis } + r2.sessions.sumOf { it.millis }, stitcher.stitch(all).sessions.sumOf { it.millis })
        val clipped = stitcher.stitch(first, t(8)) // high water inside a session
        assertEquals(listOf(Triple("B", t(8), t(10))), spans(clipped))
    }

    @Test
    fun provisionalOpenSpanThenCloseDoesNotDoubleCount() {
        val r1 = stitcher.stitch(listOf(ev(t(0), RESUMED)), now = t(4))
        assertEquals(240_000L, r1.sessions.single().millis)
        val r2 = stitcher.stitch(listOf(ev(t(0), RESUMED), ev(t(9), PAUSED)), r1.highWater, t(30))
        assertEquals(listOf(Triple("A", t(4), t(9))), spans(r2))
        assertNull(stitcher.stitch(emptyList()).highWater)
    }

    @Test
    fun accumulatorSplitsAtMidnightAndIsIdempotent() {
        val clock = FakeClock(wall = Instant.parse("2026-01-06T01:00:00Z"), zoneId = ZoneOffset.UTC)
        val repo = InMemoryUsageRepository()
        val acc = UsageAccumulator(repo, SessionStitcher(maxInferred = Duration.ofHours(1)), clock)
        val evs = listOf(
            UsageEvent(Instant.parse("2026-01-05T23:50:00Z"), "A", RESUMED),
            UsageEvent(Instant.parse("2026-01-06T00:10:00Z"), "A", PAUSED),
        )
        assertEquals(1_200_000L, acc.ingest(evs))
        val d5 = DayKey(LocalDate.parse("2026-01-05"), "UTC")
        val utcKey = DayKey.of(Instant.parse("2026-01-05T12:00:00Z"), ZoneOffset.UTC)
        assertEquals(600_000L, repo.millis(utcKey, "A"))
        assertEquals(600_000L, repo.millis(acc.today(), "A"))
        assertEquals(0L, acc.ingest(evs))
        assertEquals(600_000L, repo.millis(acc.today(), "A"))
        assertEquals(600L, acc.usedSecondsToday("A"))
        assertEquals(0L, repo.millis(d5, "A")) // different zone id string is a different key
        assertEquals(mapOf("A" to 600_000L), repo.totals(utcKey))
        clock.jumpWall(Instant.parse("2026-01-07T00:00:01Z"))
        assertEquals(0L, acc.usedSecondsToday("A")) // rolled over
    }

    @Test
    fun splitByDayHonoursDayStartAndMultiDay() {
        val s = Instant.parse("2026-01-06T03:50:00Z")
        val e = Instant.parse("2026-01-06T04:10:00Z")
        val slices = splitByDay(s, e, ZoneOffset.UTC, LocalTime.of(4, 0))
        assertEquals(listOf(600_000L, 600_000L), slices.map { it.millis })
        assertEquals(listOf("2026-01-05", "2026-01-06"), slices.map { it.day.date.toString() })
        val multi = splitByDay(Instant.parse("2026-01-05T12:00:00Z"), Instant.parse("2026-01-07T12:00:00Z"), ZoneOffset.UTC)
        assertEquals(listOf(43_200_000L, 86_400_000L, 43_200_000L), multi.map { it.millis })
        assertTrue(splitByDay(e, s, ZoneOffset.UTC).isEmpty())
    }

    private class Live {
        val clock = FakeClock(wall = Instant.parse("2026-01-05T08:00:00Z"), zoneId = ZoneOffset.UTC)
        val repo = InMemoryUsageRepository()
        val acc = UsageAccumulator(repo, SessionStitcher(), clock)
        val key = DayKey.of(clock.now(), ZoneOffset.UTC)
        fun used() = repo.millis(key, "A")
        fun refresh(minAfterStart: Long, events: List<UsageEvent>): Long {
            clock.advanceMs(minAfterStart * 60_000 - (clock.now().epochSecond - Instant.parse("2026-01-05T08:00:00Z").epochSecond) * 1000)
            return acc.ingest(events)
        }
    }

    @Test
    fun longContinuousSessionKeepsAccruingWithoutDoubleCount() {
        val l = Live()
        val evs = listOf(ev(t(0), RESUMED))
        for (m in listOf(5L, 15L, 31L, 45L)) l.refresh(m, evs)
        assertEquals(45 * 60_000L, l.used())
        assertEquals(0L, l.acc.ingest(evs)) // re-ingest at the same instant
        l.refresh(46, evs + ev(t(46), SCREEN_OFF, ""))
        assertEquals(46 * 60_000L, l.used()) // screen off closes it, no double count
        assertEquals(0L, l.refresh(60, evs + ev(t(46), SCREEN_OFF, "")))
        assertNull(l.repo.openSpan())
    }

    @Test
    fun inferredCloseAfterLiveBillingStaysCapped() {
        val l = Live()
        val evs = listOf(ev(t(0), RESUMED))
        l.refresh(5, evs)
        val hours17 = base.plus(Duration.ofHours(17))
        l.clock.advanceMs(Duration.ofHours(17).toMillis())
        l.acc.ingest(evs + ev(hours17, SCREEN_OFF, ""))
        assertEquals((5 + 10) * 60_000L, l.used()) // capped at 10 min past the last observation
    }

    @Test
    fun crashBetweenUsageWriteAndOpenSpanDoublesCountAtMostOneRefreshInterval() {
        val l = Live()
        val evs = listOf(ev(t(0), RESUMED))
        l.refresh(5, evs)
        val saved = l.repo.openSpan()
        l.refresh(15, evs)
        l.repo.setOpenSpan(saved) // crash: usage written, open-span state not
        l.refresh(15, evs)
        assertEquals((15 + 10) * 60_000L, l.used()) // bound: one refresh interval (10 min) re-billed
    }

    @Test
    fun openSpanSurvivesRestartViaRepository() {
        val l = Live()
        val evs = listOf(ev(t(0), RESUMED))
        l.refresh(5, evs)
        val restarted = UsageAccumulator(l.repo, SessionStitcher(), l.clock)
        l.clock.advanceMs(10 * 60_000L)
        restarted.ingest(evs)
        assertEquals(15 * 60_000L, l.used())
    }
}

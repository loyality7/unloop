package com.gainandshine.unloop.core.limits

import com.gainandshine.unloop.core.time.Schedule
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LimitsTest {
    private val utc = ZoneOffset.UTC
    private val t0 = Instant.parse("2026-01-05T12:00:00Z")
    private val day = DayKey.of(t0, utc)
    private val rule = LimitRule("app.a", 3600, listOf(80, 100))

    @Test
    fun dayKeyRolloverAndDayStart() {
        val table = listOf(
            Triple("2026-01-05T23:59:59Z", LocalTime.MIDNIGHT, "2026-01-05"),
            Triple("2026-01-06T00:00:00Z", LocalTime.MIDNIGHT, "2026-01-06"),
            Triple("2026-01-06T03:59:59Z", LocalTime.of(4, 0), "2026-01-05"),
            Triple("2026-01-06T04:00:00Z", LocalTime.of(4, 0), "2026-01-06"),
        )
        for ((at, ds, want) in table) {
            assertEquals(LocalDate.parse(want), DayKey.of(Instant.parse(at), utc, ds).date, at)
        }
        val k = DayKey.of(t0, ZoneId.of("America/New_York"), LocalTime.of(4, 0))
        assertEquals("America/New_York", k.zoneId)
        assertEquals(LocalTime.of(4, 0), k.dayStart)
        assertEquals(Instant.parse("2026-01-05T09:00:00Z"), k.start())
        assertEquals(Instant.parse("2026-01-06T09:00:00Z"), k.end())
        val dst = DayKey.of(Instant.parse("2026-03-08T12:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(Duration.ofHours(23), Duration.between(dst.start(), dst.end()))
    }

    @Test
    fun quotaStateTable() {
        val table = listOf(
            0L to Triple(QuotaState.UNDER, 3600L, 0),
            2879L to Triple(QuotaState.UNDER, 721L, 79),
            2880L to Triple(QuotaState.WARNING, 720L, 80),
            3599L to Triple(QuotaState.WARNING, 1L, 99),
            3600L to Triple(QuotaState.OVER, 0L, 100),
            5000L to Triple(QuotaState.OVER, 0L, 138),
            -5L to Triple(QuotaState.UNDER, 3600L, 0),
        )
        for ((used, want) in table) {
            val q = QuotaEvaluator.evaluate(rule, used, day, emptyList(), t0)
            assertEquals(want.first, q.state, "used=$used")
            assertEquals(want.second, q.remainingSeconds, "remaining used=$used")
            assertEquals(want.third, q.percent, "percent used=$used")
        }
        assertEquals(day.end(), QuotaEvaluator.evaluate(rule, 0, day, emptyList(), t0).resetsAt)
        assertEquals(QuotaState.OVER, QuotaEvaluator.evaluate(LimitRule("x", 0), 0, day, emptyList(), t0).state)
        assertEquals(QuotaState.UNDER, QuotaEvaluator.evaluate(LimitRule("x", 100, emptyList()), 99, day, emptyList(), t0).state)
    }

    @Test
    fun extensionsExpireDeterministically() {
        val ext = Extension("e1", "app.a", t0, 1800, t0.plusSeconds(600))
        val other = Extension("e2", "app.b", t0, 9999, t0.plusSeconds(600))
        val at = { now: Instant -> QuotaEvaluator.evaluate(rule, 3600, day, listOf(ext, other), now) }
        assertEquals(QuotaState.OVER, at(t0.minusSeconds(1)).state) // not yet granted
        assertEquals(5400, at(t0).effectiveLimitSeconds)
        assertEquals(QuotaState.UNDER, at(t0).state)
        assertEquals(1800, at(t0.plusSeconds(599)).remainingSeconds)
        assertEquals(QuotaState.OVER, at(t0.plusSeconds(600)).state) // expiresAt exclusive
        assertEquals(3600, at(t0.plusSeconds(600)).effectiveLimitSeconds)
        assertTrue(ext.isActive(t0) && !ext.isActive(ext.expiresAt))
    }

    private val night = Schedule("n", true, setOf(DayOfWeek.MONDAY), LocalTime.of(22, 0), LocalTime.of(23, 0))
    private fun status(used: Long) = QuotaEvaluator.evaluate(rule, used, day, emptyList(), t0)
    private fun zdt(s: String) = Instant.parse(s).atZone(utc)

    @Test
    fun policyPrecedence() {
        val noon = zdt("2026-01-05T12:00:00Z")
        val inNight = zdt("2026-01-05T22:30:00Z")
        val none = emptyList<TemporaryAccess>()
        assertEquals(BlockDecision.Allow, LimitPolicy.decide("app.a", noon, listOf(night), status(0), null, none))
        assertEquals(BlockDecision.Allow, LimitPolicy.decide("app.a", noon, emptyList(), null, null, none))
        assertEquals(BlockDecision.Warn(80), LimitPolicy.decide("app.a", noon, listOf(night), status(2880), null, none))
        assertEquals(
            BlockDecision.Block(BlockReason.QUOTA, day.end()),
            LimitPolicy.decide("app.a", noon, listOf(night), status(3600), null, none),
        )
        assertEquals(
            BlockDecision.Block(BlockReason.SCHEDULE, Instant.parse("2026-01-05T23:00:00Z")),
            LimitPolicy.decide("app.a", inNight, listOf(night), status(3600), null, none),
        )
    }

    @Test
    fun manualBlockAndTemporaryAccess() {
        val inNight = zdt("2026-01-05T22:30:00Z")
        val over = status(3600)
        val grant = TemporaryAccess("t", "app.a", inNight.toInstant().minusSeconds(60), inNight.toInstant().plusSeconds(600))
        assertEquals(BlockDecision.Allow, LimitPolicy.decide("app.a", inNight, listOf(night), over, null, listOf(grant)))
        assertEquals(
            BlockDecision.Block(BlockReason.SCHEDULE, Instant.parse("2026-01-05T23:00:00Z")),
            LimitPolicy.decide("app.b", inNight, listOf(night), null, null, listOf(grant)),
        )
        val expired = zdt("2026-01-05T22:40:00Z") // grant ends 22:40:00 exclusive
        assertEquals(
            BlockDecision.Block(BlockReason.SCHEDULE, Instant.parse("2026-01-05T23:00:00Z")),
            LimitPolicy.decide("app.a", expired, listOf(night), over, null, listOf(grant)),
        )
        val until = inNight.toInstant().plusSeconds(120)
        val manual = ManualBlock(until)
        assertEquals(BlockDecision.Block(BlockReason.MANUAL, until), LimitPolicy.decide("app.a", inNight, emptyList(), null, manual, listOf(grant)))
        val after = zdt("2026-01-05T22:32:00Z") // manual expired exactly now
        assertEquals(BlockDecision.Allow, LimitPolicy.decide("app.a", after, emptyList(), null, manual, emptyList()))
        assertEquals(BlockDecision.Block(BlockReason.MANUAL, null), LimitPolicy.decide("app.a", after, emptyList(), null, ManualBlock(null), emptyList()))
    }
}

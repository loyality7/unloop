package com.gainandshine.unloop.core.time

import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleEvaluatorTest {
    private val ny = ZoneId.of("America/New_York")
    private val london = ZoneId.of("Europe/London")
    private val utc = ZoneOffset.UTC

    private fun at(s: String, zone: ZoneId = utc): ZonedDateTime = LocalDateTime.parse(s).atZone(zone)
    private fun inst(s: String): Instant = Instant.parse(s)
    private fun sched(days: Set<DayOfWeek>, start: String, end: String, enabled: Boolean = true) =
        Schedule("s", enabled, days, LocalTime.parse(start), LocalTime.parse(end))

    @Test
    fun overnightBelongsToStartDay() {
        val s = sched(setOf(FRIDAY), "22:00", "07:00") // 2026-01-09 is a Friday
        val table = listOf(
            "2026-01-09T21:59" to false,
            "2026-01-09T22:00" to true,
            "2026-01-09T23:59" to true,
            "2026-01-10T00:00" to true,
            "2026-01-10T06:59" to true,
            "2026-01-10T07:00" to false,
            "2026-01-10T22:00" to false, // Saturday is not in the mask
            "2026-01-09T06:59" to false, // Thursday is not in the mask, so no spill into Friday
            "2026-01-17T03:00" to true, // following Saturday morning
        )
        for ((t, want) in table) assertEquals(want, ScheduleEvaluator.isActive(s, at(t)), t)
    }

    @Test
    fun sameDayWindowWithMask() {
        val s = sched(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY), "09:00", "17:00")
        val table = listOf(
            "2026-01-05T08:59" to false, "2026-01-05T09:00" to true, "2026-01-05T16:59" to true,
            "2026-01-05T17:00" to false, "2026-01-10T10:00" to false, "2026-01-11T10:00" to false,
        )
        for ((t, want) in table) assertEquals(want, ScheduleEvaluator.isActive(s, at(t)), t)
    }

    @Test
    fun startEqualsEndRejectedAndDisabledOrEmptyNeverActive() {
        assertFailsWith<IllegalArgumentException> { sched(setOf(MONDAY), "08:00", "08:00") }
        val off = sched(setOf(MONDAY), "00:00", "23:59", enabled = false)
        assertFalse(ScheduleEvaluator.isActive(off, at("2026-01-05T12:00")))
        assertNull(ScheduleEvaluator.nextBoundary(off, at("2026-01-05T12:00")))
        assertNull(ScheduleEvaluator.nextBoundary(sched(emptySet(), "01:00", "02:00"), at("2026-01-05T12:00")))
        assertTrue(sched(setOf(MONDAY), "22:00", "07:00").overnight)
        assertFalse(sched(setOf(MONDAY), "07:00", "22:00").overnight)
    }

    @Test
    fun nextBoundaryTable() {
        val s = sched(setOf(FRIDAY), "22:00", "07:00")
        val table = listOf(
            "2026-01-09T12:00" to "2026-01-09T22:00:00Z",
            "2026-01-09T22:00" to "2026-01-10T07:00:00Z", // strictly after now
            "2026-01-09T23:00" to "2026-01-10T07:00:00Z",
            "2026-01-10T07:00" to "2026-01-16T22:00:00Z",
            "2026-01-05T00:00" to "2026-01-09T22:00:00Z",
        )
        for ((now, want) in table) assertEquals(inst(want), ScheduleEvaluator.nextBoundary(s, at(now)), now)
    }

    @Test
    fun springForwardNewYork() {
        // 2026-03-08: 02:00 EST jumps to 03:00 EDT.
        val overnight = sched(setOf(SATURDAY), "22:00", "07:00")
        assertTrue(ScheduleEvaluator.isActive(overnight, at("2026-03-08T06:30", ny)))
        assertFalse(ScheduleEvaluator.isActive(overnight, at("2026-03-08T07:00", ny)))
        assertEquals(inst("2026-03-08T11:00:00Z"), ScheduleEvaluator.nextBoundary(overnight, at("2026-03-07T23:00", ny)))
        val swallowed = sched(setOf(SUNDAY), "02:00", "03:00") // start resolves to 03:00 EDT = end
        assertFalse(ScheduleEvaluator.isActive(swallowed, at("2026-03-08T01:59", ny)))
        assertFalse(ScheduleEvaluator.isActive(swallowed, at("2026-03-08T03:00", ny)))
        val shifted = sched(setOf(SUNDAY), "02:30", "04:00") // 02:30 does not exist, starts 03:30 EDT
        assertFalse(ScheduleEvaluator.isActive(shifted, at("2026-03-08T03:15", ny)))
        assertTrue(ScheduleEvaluator.isActive(shifted, at("2026-03-08T03:30", ny)))
    }

    @Test
    fun fallBackNewYork() {
        // 2026-11-01: 02:00 EDT falls back to 01:00 EST, so 01:30 happens twice.
        val overnight = sched(setOf(SATURDAY), "22:00", "07:00")
        assertTrue(ScheduleEvaluator.isActive(overnight, ZonedDateTime.ofInstant(inst("2026-11-01T05:30:00Z"), ny))) // 01:30 EDT
        assertTrue(ScheduleEvaluator.isActive(overnight, ZonedDateTime.ofInstant(inst("2026-11-01T06:30:00Z"), ny))) // 01:30 EST
        assertTrue(ScheduleEvaluator.isActive(overnight, ZonedDateTime.ofInstant(inst("2026-11-01T11:59:00Z"), ny))) // 06:59 EST
        assertFalse(ScheduleEvaluator.isActive(overnight, ZonedDateTime.ofInstant(inst("2026-11-01T12:00:00Z"), ny))) // 07:00 EST
        val overlap = sched(setOf(SUNDAY), "01:30", "02:30") // 05:30Z to 07:30Z, two real hours
        assertTrue(ScheduleEvaluator.isActive(overlap, ZonedDateTime.ofInstant(inst("2026-11-01T06:45:00Z"), ny)))
        assertFalse(ScheduleEvaluator.isActive(overlap, ZonedDateTime.ofInstant(inst("2026-11-01T07:30:00Z"), ny)))
        assertEquals(inst("2026-11-01T07:30:00Z"), ScheduleEvaluator.nextBoundary(overlap, ZonedDateTime.ofInstant(inst("2026-11-01T06:00:00Z"), ny)))
    }

    @Test
    fun dstLondon() {
        val spring = sched(setOf(SUNDAY), "00:30", "02:30") // 2026-03-29, 01:00 GMT -> 02:00 BST
        assertTrue(ScheduleEvaluator.isActive(spring, ZonedDateTime.ofInstant(inst("2026-03-29T01:29:00Z"), london)))
        assertFalse(ScheduleEvaluator.isActive(spring, ZonedDateTime.ofInstant(inst("2026-03-29T01:30:00Z"), london)))
        val fall = sched(setOf(SUNDAY), "00:30", "02:30") // 2026-10-25, 02:00 BST -> 01:00 GMT
        assertTrue(ScheduleEvaluator.isActive(fall, ZonedDateTime.ofInstant(inst("2026-10-25T01:45:00Z"), london)))
        assertTrue(ScheduleEvaluator.isActive(fall, ZonedDateTime.ofInstant(inst("2026-10-25T02:29:00Z"), london)))
        assertFalse(ScheduleEvaluator.isActive(fall, ZonedDateTime.ofInstant(inst("2026-10-25T02:30:00Z"), london)))
    }

    @Test
    fun timezoneChangeReinterpretsSameInstant() {
        val s = sched(setOf(THURSDAY), "22:00", "07:00")
        val instant = inst("2026-01-09T03:00:00Z") // Fri 03:00 UTC
        assertTrue(ScheduleEvaluator.isActive(s, ZonedDateTime.ofInstant(instant, utc)))
        assertFalse(ScheduleEvaluator.isActive(s, ZonedDateTime.ofInstant(instant, ZoneId.of("Asia/Tokyo"))))
        assertFalse(ScheduleEvaluator.isActive(s, ZonedDateTime.ofInstant(instant, ZoneId.of("America/Los_Angeles"))))
        assertEquals(inst("2026-01-09T07:00:00Z"), ScheduleEvaluator.nextBoundary(s, ZonedDateTime.ofInstant(instant, utc)))
    }

    @Test
    fun leapDay() {
        val tue = sched(setOf(TUESDAY), "22:00", "07:00") // 2028-02-29 is a Tuesday
        assertTrue(ScheduleEvaluator.isActive(tue, at("2028-02-29T23:00")))
        assertTrue(ScheduleEvaluator.isActive(tue, at("2028-03-01T06:59")))
        assertFalse(ScheduleEvaluator.isActive(tue, at("2028-03-01T07:00")))
        assertEquals(inst("2028-03-01T07:00:00Z"), ScheduleEvaluator.nextBoundary(tue, at("2028-02-29T23:00")))
        val mon = sched(setOf(MONDAY), "22:00", "07:00")
        assertTrue(ScheduleEvaluator.isActive(mon, at("2028-02-29T06:59")))
        assertEquals(inst("2028-02-28T22:00:00Z"), ScheduleEvaluator.nextBoundary(mon, at("2028-02-28T12:00")))
    }

    @Test
    fun activeUntilChainsBackToBackWindows() {
        val a = Schedule("a", true, setOf(FRIDAY), LocalTime.of(22, 0), LocalTime.of(23, 0))
        val b = Schedule("b", true, setOf(FRIDAY), LocalTime.of(23, 0), LocalTime.of(23, 30))
        assertEquals(inst("2026-01-09T23:30:00Z"), ScheduleEvaluator.activeUntil(listOf(a, b), at("2026-01-09T22:10")))
        assertNull(ScheduleEvaluator.activeUntil(listOf(a, b), at("2026-01-09T12:00")))
        assertTrue(ScheduleEvaluator.isAnyActive(listOf(a, b), at("2026-01-09T23:10")))
    }

    /** Independent model: integer minute-of-week arithmetic, no java.time. Week starts Monday 00:00. */
    private fun model(days: Set<DayOfWeek>, startM: Int, endM: Int, minuteOfWeek: Int): Boolean {
        val d = minuteOfWeek / 1440
        val m = minuteOfWeek % 1440
        val ds = days.map { it.ordinal }.toSet()
        return if (endM > startM) d in ds && m in startM until endM
        else (d in ds && m >= startM) || (((d + 6) % 7) in ds && m < endM)
    }

    @Test
    fun fullWeekMinuteByMinuteMatchesIndependentModel() {
        val cases = listOf(
            Triple(setOf(FRIDAY), "22:00", "07:00"),
            Triple(setOf(MONDAY, WEDNESDAY, SUNDAY), "09:00", "17:30"),
            Triple(setOf(SUNDAY, MONDAY), "23:59", "00:01"),
            Triple(DayOfWeek.entries.toSet(), "06:00", "05:59"),
            Triple(setOf(SATURDAY), "00:00", "12:00"),
        )
        val weekStart = at("2026-01-05T00:00") // Monday
        for ((days, st, en) in cases) {
            val s = sched(days, st, en)
            val sm = LocalTime.parse(st).let { it.hour * 60 + it.minute }
            val em = LocalTime.parse(en).let { it.hour * 60 + it.minute }
            val mismatches = (0 until 7 * 1440).filter { i ->
                ScheduleEvaluator.isActive(s, weekStart.plusMinutes(i.toLong())) != model(days, sm, em, i)
            }
            assertEquals(emptyList(), mismatches, "$days $st-$en")
            val missed = (0 until 7 * 1440 - 1).filter { i ->
                val t = weekStart.plusMinutes(i.toLong())
                val changes = ScheduleEvaluator.isActive(s, t) != ScheduleEvaluator.isActive(s, t.plusMinutes(1))
                changes && ScheduleEvaluator.nextBoundary(s, t) != t.plusMinutes(1).toInstant()
            }
            assertEquals(emptyList(), missed, "boundary $days $st-$en")
        }
    }
}

package com.gainandshine.unloop.core.limits

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A usage day. Stores the zone id and day-start used, so a later zone change cannot silently re-key history. */
data class DayKey(val date: LocalDate, val zoneId: String, val dayStart: LocalTime = LocalTime.MIDNIGHT) {
    /** Inclusive start instant of this day. */
    fun start(): Instant = date.atTime(dayStart).atZone(ZoneId.of(zoneId)).toInstant()

    /** Exclusive end instant (the next day start; quota resets here). */
    fun end(): Instant = date.plusDays(1).atTime(dayStart).atZone(ZoneId.of(zoneId)).toInstant()

    companion object {
        /** Day containing [at] for a day that starts at [dayStart] local time (e.g. 04:00 for night owls). */
        fun of(at: Instant, zone: ZoneId, dayStart: LocalTime = LocalTime.MIDNIGHT): DayKey {
            var d = at.atZone(zone).toLocalDate()
            if (at.isBefore(d.atTime(dayStart).atZone(zone).toInstant())) d = d.minusDays(1)
            return DayKey(d, zone.id, dayStart)
        }
    }
}

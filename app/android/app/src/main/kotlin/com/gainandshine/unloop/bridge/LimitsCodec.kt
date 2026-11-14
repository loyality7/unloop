package com.gainandshine.unloop.bridge

import com.gainandshine.unloop.core.limits.LimitRule
import com.gainandshine.unloop.core.store.Json
import com.gainandshine.unloop.core.time.Schedule
import java.time.DayOfWeek
import java.time.LocalTime

/** Pure JSON <-> model mapping for the bridge (no android imports). Parsers throw on bad input. */
object LimitsCodec {
    fun limitsToJson(l: List<LimitRule>): String = Json.write(
        l.map { linkedMapOf("target" to it.target, "dailySeconds" to it.dailySeconds, "warnPercents" to it.warnPercents.map(Int::toLong)) },
    )

    fun parseLimits(json: String): List<LimitRule> = (Json.parse(json) as List<*>).map {
        val m = it as Map<*, *>
        LimitRule(
            m["target"] as String,
            (m["dailySeconds"] as Number).toLong(),
            (m["warnPercents"] as? List<*>)?.map { p -> (p as Number).toInt() } ?: listOf(80),
        )
    }

    fun schedulesToJson(s: List<Schedule>): String = Json.write(
        s.map {
            linkedMapOf(
                "id" to it.id, "enabled" to it.enabled, "days" to it.days.sorted().map(DayOfWeek::name),
                "start" to it.start.toString(), "end" to it.end.toString(),
            )
        },
    )

    fun parseSchedules(json: String): List<Schedule> = (Json.parse(json) as List<*>).map {
        val m = it as Map<*, *>
        Schedule(
            m["id"] as String,
            m["enabled"] as Boolean,
            (m["days"] as List<*>).map { d -> DayOfWeek.valueOf(d as String) }.toSet(),
            LocalTime.parse(m["start"] as String),
            LocalTime.parse(m["end"] as String),
        )
    }
}

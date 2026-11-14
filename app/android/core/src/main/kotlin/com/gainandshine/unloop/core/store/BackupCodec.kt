package com.gainandshine.unloop.core.store

import com.gainandshine.unloop.core.limits.LimitRule
import com.gainandshine.unloop.core.time.Schedule
import java.time.DayOfWeek
import java.time.LocalTime

/** Outcome of an import. [skipped] holds one message per tolerated bad entry. */
data class ImportResult(
    val ok: Boolean,
    val schedules: Int = 0,
    val limits: Int = 0,
    val settings: Int = 0,
    val skipped: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Versioned JSON export/import of schedules, limits and settings.
 * Envelope: `{"version":1,"schedules":[..],"limits":[..],"settings":{..}}`. The version is fixed at 1; new
 * data is added as new keys, unknown keys are ignored. Export writes EVERYTHING (disabled schedules, all
 * settings). Import is an upsert merge, tolerant per entry; a version below 1 or a malformed envelope is
 * rejected without touching the repositories. Versions above 1 are read best-effort (additive contract).
 */
object BackupCodec {
    const val VERSION = 1

    fun export(schedules: SchedulesRepository, limits: LimitsRepository, settings: SettingsRepository): String =
        Json.write(
            linkedMapOf(
                "version" to VERSION.toLong(),
                "schedules" to schedules.all().map {
                    linkedMapOf(
                        "id" to it.id,
                        "enabled" to it.enabled,
                        "days" to it.days.sorted().map(DayOfWeek::name),
                        "start" to it.start.toString(),
                        "end" to it.end.toString(),
                    )
                },
                "limits" to limits.all().map {
                    linkedMapOf(
                        "target" to it.target,
                        "dailySeconds" to it.dailySeconds,
                        "warnPercents" to it.warnPercents.map(Int::toLong),
                    )
                },
                "settings" to settings.all(),
            ),
        )

    fun import(
        json: String,
        schedules: SchedulesRepository,
        limits: LimitsRepository,
        settings: SettingsRepository,
    ): ImportResult {
        val root = try {
            Json.parse(json) as? Map<*, *> ?: return ImportResult(false, error = "not an object")
        } catch (e: JsonException) {
            return ImportResult(false, error = "malformed: ${e.message}")
        }
        val version = (root["version"] as? Long) ?: return ImportResult(false, error = "missing version")
        if (version < VERSION) return ImportResult(false, error = "unsupported version $version")

        val skipped = mutableListOf<String>()
        var ns = 0
        var nl = 0
        var nt = 0
        (root["schedules"] as? List<*>)?.forEachIndexed { i, e ->
            try {
                schedules.upsert(parseSchedule(e as Map<*, *>)); ns++
            } catch (ex: RuntimeException) {
                skipped += "schedules[$i]: ${ex.message}"
            }
        }
        (root["limits"] as? List<*>)?.forEachIndexed { i, e ->
            try {
                limits.upsert(parseLimit(e as Map<*, *>)); nl++
            } catch (ex: RuntimeException) {
                skipped += "limits[$i]: ${ex.message}"
            }
        }
        (root["settings"] as? Map<*, *>)?.forEach { (k, v) ->
            if (v is String || v is Boolean || v is Long || v is Double) {
                settings.put(k.toString(), v.toString()); nt++
            } else {
                skipped += "settings.$k: unsupported value"
            }
        }
        return ImportResult(true, ns, nl, nt, skipped)
    }

    private fun parseSchedule(m: Map<*, *>) = Schedule(
        id = m["id"] as String,
        enabled = m["enabled"] as Boolean,
        days = (m["days"] as List<*>).map { DayOfWeek.valueOf(it as String) }.toSet(),
        start = LocalTime.parse(m["start"] as String),
        end = LocalTime.parse(m["end"] as String),
    )

    private fun parseLimit(m: Map<*, *>) = LimitRule(
        target = m["target"] as String,
        dailySeconds = m["dailySeconds"] as Long,
        warnPercents = (m["warnPercents"] as? List<*>)?.map { (it as Long).toInt() } ?: listOf(80),
    )
}

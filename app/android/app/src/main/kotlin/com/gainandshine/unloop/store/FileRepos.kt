package com.gainandshine.unloop.store

import com.gainandshine.unloop.core.limits.DayKey
import com.gainandshine.unloop.core.limits.Extension
import com.gainandshine.unloop.core.limits.LimitRule
import com.gainandshine.unloop.core.limits.TemporaryAccess
import com.gainandshine.unloop.core.store.ExtensionsRepository
import com.gainandshine.unloop.core.store.LimitsRepository
import com.gainandshine.unloop.core.store.SchedulesRepository
import com.gainandshine.unloop.core.store.SettingsRepository
import com.gainandshine.unloop.core.store.UsageRepository
import com.gainandshine.unloop.core.time.Schedule
import com.gainandshine.unloop.core.usage.OpenSpan
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// File-backed core repositories. Each holds its whole (small) state in memory and rewrites its file on
// mutation under one lock. ponytail: Room is deferred; the core interfaces make it a drop-in swap.

private fun Any?.maps(): List<Map<*, *>> = (this as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

class FileLimitsRepository(dir: File) : LimitsRepository {
    private val f = JsonFile(File(dir, "limits.json"))
    private val m = linkedMapOf<String, LimitRule>()

    init {
        f.read().maps().forEach {
            runCatching {
                val r = LimitRule(
                    it["target"] as String, it["dailySeconds"] as Long,
                    (it["warnPercents"] as? List<*>)?.map { p -> (p as Long).toInt() } ?: listOf(80),
                )
                m[r.target] = r
            }
        }
    }

    @Synchronized override fun all() = m.values.toList()
    @Synchronized override fun upsert(rule: LimitRule) { m[rule.target] = rule; save() }
    @Synchronized override fun remove(target: String) { if (m.remove(target) != null) save() }
    private fun save() = f.write(m.values.map {
        linkedMapOf("target" to it.target, "dailySeconds" to it.dailySeconds, "warnPercents" to it.warnPercents.map(Int::toLong))
    })
}

class FileSchedulesRepository(dir: File) : SchedulesRepository {
    private val f = JsonFile(File(dir, "schedules.json"))
    private val m = linkedMapOf<String, Schedule>()

    init {
        f.read().maps().forEach {
            runCatching {
                val s = Schedule(
                    it["id"] as String, it["enabled"] as Boolean,
                    (it["days"] as List<*>).map { d -> DayOfWeek.valueOf(d as String) }.toSet(),
                    LocalTime.parse(it["start"] as String), LocalTime.parse(it["end"] as String),
                )
                m[s.id] = s
            }
        }
    }

    @Synchronized override fun all() = m.values.toList()
    @Synchronized override fun upsert(schedule: Schedule) { m[schedule.id] = schedule; save() }
    @Synchronized override fun remove(id: String) { if (m.remove(id) != null) save() }
    private fun save() = f.write(m.values.map {
        linkedMapOf(
            "id" to it.id, "enabled" to it.enabled, "days" to it.days.sorted().map(DayOfWeek::name),
            "start" to it.start.toString(), "end" to it.end.toString(),
        )
    })
}

class FileUsageRepository(dir: File) : UsageRepository {
    private val f = JsonFile(File(dir, "usage.json"))
    private val m = linkedMapOf<DayKey, MutableMap<String, Long>>()
    private var hw: Instant? = null
    private var open: OpenSpan? = null

    init {
        (f.read() as? Map<*, *>)?.let { root ->
            (root["highWaterMs"] as? Long)?.let { hw = Instant.ofEpochMilli(it) }
            (root["open"] as? Map<*, *>)?.let { o ->
                runCatching {
                    open = OpenSpan(
                        o["pkg"] as String,
                        Instant.ofEpochMilli(o["startMs"] as Long),
                        Instant.ofEpochMilli(o["billedUntilMs"] as Long),
                    )
                }
            }
            (root["days"] as? Map<*, *>)?.forEach { (k, v) ->
                runCatching {
                    val p = (k as String).split('|')
                    val key = DayKey(LocalDate.parse(p[0]), p[1], LocalTime.parse(p[2]))
                    m[key] = (v as Map<*, *>).entries.associate { (a, b) -> a as String to b as Long }.toMutableMap()
                }
            }
        }
    }

    @Synchronized override fun addMillis(day: DayKey, pkg: String, millis: Long) {
        m.getOrPut(day) { linkedMapOf() }.merge(pkg, millis, Long::plus)
        if (m.size > KEEP_DAYS) m.keys.sortedBy { it.date }.take(m.size - KEEP_DAYS).forEach { m.remove(it) }
        save()
    }
    @Synchronized override fun millis(day: DayKey, pkg: String) = m[day]?.get(pkg) ?: 0L
    @Synchronized override fun totals(day: DayKey): Map<String, Long> = m[day]?.toMap() ?: emptyMap()
    @Synchronized override fun highWater() = hw
    @Synchronized override fun setHighWater(at: Instant) { hw = at; save() }
    @Synchronized override fun openSpan() = open
    @Synchronized override fun setOpenSpan(span: OpenSpan?) { open = span; save() }
    @Synchronized fun clear() { m.clear(); open = null; save() }

    private fun save() = f.write(
        linkedMapOf(
            "highWaterMs" to hw?.toEpochMilli(),
            "open" to open?.let { linkedMapOf("pkg" to it.pkg, "startMs" to it.start.toEpochMilli(), "billedUntilMs" to it.billedUntil.toEpochMilli()) },
            "days" to m.entries.associate { (k, v) -> "${k.date}|${k.zoneId}|${k.dayStart}" to v },
        ),
    )

    private companion object { const val KEEP_DAYS = 35 }
}

class FileExtensionsRepository(dir: File) : ExtensionsRepository {
    private val f = JsonFile(File(dir, "extensions.json"))
    private val list = mutableListOf<Extension>()

    init {
        f.read().maps().forEach {
            runCatching {
                list += Extension(
                    it["id"] as String, it["target"] as String, Instant.ofEpochMilli(it["grantedAt"] as Long),
                    it["addedSeconds"] as Long, Instant.ofEpochMilli(it["expiresAt"] as Long),
                )
            }
        }
    }

    @Synchronized override fun all() = list.toList()
    @Synchronized override fun add(extension: Extension) { list.removeAll { it.id == extension.id }; list += extension; save() }
    @Synchronized override fun activeFor(target: String, now: Instant) = list.filter { it.target == target && it.isActive(now) }
    @Synchronized override fun purgeExpired(now: Instant): Int {
        val n = list.size
        list.removeAll { !now.isBefore(it.expiresAt) }
        if (list.size != n) save()
        return n - list.size
    }
    private fun save() = f.write(list.map {
        linkedMapOf(
            "id" to it.id, "target" to it.target, "grantedAt" to it.grantedAt.toEpochMilli(),
            "addedSeconds" to it.addedSeconds, "expiresAt" to it.expiresAt.toEpochMilli(),
        )
    })
}

/** Core has no repository for [TemporaryAccess]; this is the shell's minimal one. */
class FileTempAccessStore(dir: File) {
    private val f = JsonFile(File(dir, "temp_access.json"))
    private val list = mutableListOf<TemporaryAccess>()

    init {
        f.read().maps().forEach {
            runCatching {
                list += TemporaryAccess(
                    it["id"] as String, it["target"] as String,
                    Instant.ofEpochMilli(it["grantedAt"] as Long), Instant.ofEpochMilli(it["expiresAt"] as Long),
                )
            }
        }
    }

    @Synchronized fun add(t: TemporaryAccess, now: Instant) {
        list.removeAll { !now.isBefore(it.expiresAt) }
        list += t
        f.write(list.map {
            linkedMapOf("id" to it.id, "target" to it.target, "grantedAt" to it.grantedAt.toEpochMilli(), "expiresAt" to it.expiresAt.toEpochMilli())
        })
    }
    @Synchronized fun forTarget(target: String): List<TemporaryAccess> = list.filter { it.target == target }
}

class FileSettingsRepository(dir: File) : SettingsRepository {
    private val f = JsonFile(File(dir, "settings.json"))
    private val m = linkedMapOf<String, String>()

    init {
        (f.read() as? Map<*, *>)?.forEach { (k, v) -> if (v is String) m[k as String] = v }
    }

    @Synchronized override fun all(): Map<String, String> = m.toMap()
    @Synchronized override fun get(key: String) = m[key]
    @Synchronized override fun put(key: String, value: String) { m[key] = value; f.write(m) }
    @Synchronized override fun remove(key: String) { if (m.remove(key) != null) f.write(m) }
}

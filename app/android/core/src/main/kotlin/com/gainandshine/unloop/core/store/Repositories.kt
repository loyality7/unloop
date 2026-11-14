package com.gainandshine.unloop.core.store

import com.gainandshine.unloop.core.limits.DayKey
import com.gainandshine.unloop.core.limits.Extension
import com.gainandshine.unloop.core.limits.LimitRule
import com.gainandshine.unloop.core.time.Schedule
import java.time.Instant

/** Persistence ports. The Android shell backs these with Room; core tests use the in-memory fakes below. */
interface LimitsRepository {
    fun all(): List<LimitRule>
    fun upsert(rule: LimitRule)
    fun remove(target: String)
}

interface SchedulesRepository {
    fun all(): List<Schedule>
    fun upsert(schedule: Schedule)
    fun remove(id: String)
}

interface UsageRepository {
    fun addMillis(day: DayKey, pkg: String, millis: Long)
    fun millis(day: DayKey, pkg: String): Long
    fun totals(day: DayKey): Map<String, Long>

    /** End of the last ingested session; null before the first ingest. */
    fun highWater(): Instant?
    fun setHighWater(at: Instant)

    /** Live-billed open span, persisted so a process death neither loses nor repeats it. Default: not persisted. */
    fun openSpan(): com.gainandshine.unloop.core.usage.OpenSpan? = null
    fun setOpenSpan(span: com.gainandshine.unloop.core.usage.OpenSpan?) {}
}

interface ExtensionsRepository {
    fun all(): List<Extension>
    fun add(extension: Extension)
    fun activeFor(target: String, now: Instant): List<Extension>

    /** Drops records already expired at [now]; returns how many. */
    fun purgeExpired(now: Instant): Int
}

interface SettingsRepository {
    fun all(): Map<String, String>
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class InMemoryLimitsRepository : LimitsRepository {
    private val m = linkedMapOf<String, LimitRule>()
    override fun all() = m.values.toList()
    override fun upsert(rule: LimitRule) { m[rule.target] = rule }
    override fun remove(target: String) { m.remove(target) }
}

class InMemorySchedulesRepository : SchedulesRepository {
    private val m = linkedMapOf<String, Schedule>()
    override fun all() = m.values.toList()
    override fun upsert(schedule: Schedule) { m[schedule.id] = schedule }
    override fun remove(id: String) { m.remove(id) }
}

class InMemoryUsageRepository : UsageRepository {
    private val m = mutableMapOf<Pair<DayKey, String>, Long>()
    private var hw: Instant? = null
    override fun addMillis(day: DayKey, pkg: String, millis: Long) {
        m.merge(day to pkg, millis, Long::plus)
    }
    override fun millis(day: DayKey, pkg: String) = m[day to pkg] ?: 0L
    override fun totals(day: DayKey): Map<String, Long> =
        m.filterKeys { it.first == day }.mapKeys { it.key.second }
    override fun highWater() = hw
    override fun setHighWater(at: Instant) { hw = at }
    private var open: com.gainandshine.unloop.core.usage.OpenSpan? = null
    override fun openSpan() = open
    override fun setOpenSpan(span: com.gainandshine.unloop.core.usage.OpenSpan?) { open = span }
}

class InMemoryExtensionsRepository : ExtensionsRepository {
    private val list = mutableListOf<Extension>()
    override fun all() = list.toList()
    override fun add(extension: Extension) { list.removeAll { it.id == extension.id }; list += extension }
    override fun activeFor(target: String, now: Instant) = list.filter { it.target == target && it.isActive(now) }
    override fun purgeExpired(now: Instant): Int {
        val before = list.size
        list.removeAll { !now.isBefore(it.expiresAt) }
        return before - list.size
    }
}

class InMemorySettingsRepository : SettingsRepository {
    private val m = linkedMapOf<String, String>()
    override fun all(): Map<String, String> = m.toMap()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
}

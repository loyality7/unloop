package com.gainandshine.unloop.notif

import android.content.Context
import com.gainandshine.unloop.core.notif.NotifDefaults
import com.gainandshine.unloop.core.notif.NotifRules
import com.gainandshine.unloop.core.store.Json
import java.io.File

/** Persisted notification settings. [enabled] defaults to false: the user opts in. */
class NotifConfig(
    val enabled: Boolean = false,
    val packages: Set<String> = DEFAULT_PACKAGES,
    val snoozeMs: Long = NotifDefaults.DEFAULT_SNOOZE_MS,
    val allowlist: Set<String> = emptySet(),
    /** Snooze only while one of the user's schedules is running. Off: snooze all day while the filter is on. */
    val scheduleOnly: Boolean = false,
) {
    fun toRules(ownPackage: String, scheduleActive: (java.time.Instant) -> Boolean = { true }) = NotifRules(
        ownPackage,
        NotifDefaults.engagementNoise("noise", packages, snoozeMs, if (scheduleOnly) scheduleActive else { _ -> true }),
        allowlist,
    )

    fun toJson(): String = Json.write(
        mapOf("enabled" to enabled, "packages" to packages.toList(), "snoozeMs" to snoozeMs, "allowlist" to allowlist.toList(), "scheduleOnly" to scheduleOnly),
    )

    companion object {
        val DEFAULT_PACKAGES = setOf(
            "com.instagram.android", "com.zhiliaoapp.musically", "com.snapchat.android",
            "com.facebook.katana", "com.twitter.android", "com.reddit.frontpage",
        )

        /** Lenient: anything unreadable falls back to the (disabled) default. */
        fun fromJson(text: String): NotifConfig = try {
            val m = Json.parse(text) as Map<*, *>
            fun set(k: String, d: Set<String>) = (m[k] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: d
            NotifConfig(
                enabled = m["enabled"] as? Boolean ?: false,
                packages = set("packages", DEFAULT_PACKAGES),
                snoozeMs = (m["snoozeMs"] as? Long)?.takeIf { it > 0 } ?: NotifDefaults.DEFAULT_SNOOZE_MS,
                allowlist = set("allowlist", emptySet()),
                scheduleOnly = m["scheduleOnly"] as? Boolean ?: false,
            )
        } catch (_: Exception) {
            NotifConfig()
        }
    }
}

/** JSON file store; re-reads when the file changes so the UI toggle applies to the next notification. */
class NotifRuleStore(
    private val file: File,
    private val ownPackage: String,
    private val scheduleActive: (java.time.Instant) -> Boolean = { true },
) {
    private var stamp = Long.MIN_VALUE
    private var stampLen = -1L
    private var cfg = NotifConfig()
    private var rules = cfg.toRules(ownPackage, scheduleActive)

    @Synchronized fun config(): NotifConfig {
        val m = if (file.exists()) file.lastModified() else -1L
        val len = if (m >= 0) file.length() else -1L
        if (m != stamp || len != stampLen) {
            cfg = if (m >= 0) NotifConfig.fromJson(runCatching { file.readText() }.getOrDefault("")) else NotifConfig()
            rules = cfg.toRules(ownPackage, scheduleActive)
            stamp = m
            stampLen = len
        }
        return cfg
    }

    @Synchronized fun rules(): NotifRules {
        config()
        return rules
    }

    @Synchronized fun save(c: NotifConfig) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(c.toJson())
        if (!tmp.renameTo(file)) {
            file.writeText(c.toJson())
            tmp.delete()
        }
        stamp = Long.MIN_VALUE
    }

    companion object {
        fun of(c: Context) = NotifRuleStore(File(c.applicationContext.filesDir, "notif_rules.json"), c.packageName) { now ->
            val schedules = com.gainandshine.unloop.store.StoreProvider.get(c).schedules.all()
            com.gainandshine.unloop.core.time.ScheduleEvaluator.isAnyActive(schedules, now.atZone(com.gainandshine.unloop.system.AndroidClock.zone()))
        }
    }
}

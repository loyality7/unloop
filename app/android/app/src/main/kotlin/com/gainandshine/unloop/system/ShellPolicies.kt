package com.gainandshine.unloop.system

import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.store.Json

/** Android-free policies for the shell, kept pure so they have JVM tests. */

/** Scan re-arm timing: never a zero-delay loop, never re-arm a due time that was already consumed. */
object ScanTiming {
    const val MIN_DELAY_MS = 100L
    const val STALE_DUE_DELAY_MS = 1000L

    fun delay(dueMs: Long, nowMs: Long, consumedDueMs: Long): Long =
        if (dueMs <= consumedDueMs) STALE_DUE_DELAY_MS else (dueMs - nowMs).coerceAtLeast(MIN_DELAY_MS)
}

/** 250 ms doubling to 2 s while snapshots come back null; [reset] on a good snapshot. */
class NullSnapBackoff {
    private var cur = 0L
    private var attempts = 0
    fun next(): Long { cur = if (cur == 0L) 250L else minOf(cur * 2, 2000L); attempts++; return cur }

    /** Null after [MAX_ATTEMPTS] consecutive nulls: stop polling and wait for the next real event (e.g. a payment app took over). */
    fun nextOrNull(): Long? = if (attempts >= MAX_ATTEMPTS) null else next()
    fun reset() { cur = 0L; attempts = 0 }

    companion object { const val MAX_ATTEMPTS = 6 }
}

/** Periodic limit re-check: only while a target package is foreground and the screen is interactive. */
object RecheckPolicy {
    const val INTERVAL_MS = 30_000L
    fun shouldRun(isTarget: Boolean, interactive: Boolean) = isTarget && interactive
}

/**
 * Effective enforcement targets = packages with a limit rule UNION the `scheduled_apps` setting (JSON array of
 * package names, default empty). Schedules and the manual block apply to this whole set; quota only to limit rules.
 */
object Targets {
    const val KEY_SCHEDULED = "scheduled_apps"
    const val KEY_GROUPS = "app_groups"

    fun effective(limitTargets: Collection<String>, scheduled: Collection<String>): Set<String> =
        LinkedHashSet<String>().apply { addAll(limitTargets); addAll(scheduled) }

    fun parseScheduled(json: String?): Set<String> = try {
        (Json.parse(json ?: "[]") as List<*>).filterIsInstance<String>().filter { it.isNotBlank() }.toCollection(LinkedHashSet())
    } catch (_: Exception) { emptySet() }

    fun encodeScheduled(pkgs: Collection<String>): String = Json.write(pkgs.filter { it.isNotBlank() }.distinct())
}

/** Persisted wall-clock "service alive" stamp -> monotonic stamp usable by the watchdog in a fresh process. */
object AliveStamp {
    /** Null when never stamped or the stamp is in the future (clock change). */
    fun toMono(persistedWallMs: Long, nowWallMs: Long, nowMonoMs: Long): Long? {
        if (persistedWallMs <= 0L || persistedWallMs > nowWallMs) return null
        return (nowMonoMs - (nowWallMs - persistedWallMs)).takeIf { it > 0 }
    }

    /** Throttle for persisting on events. */
    fun shouldPersist(lastPersistMonoMs: Long, nowMonoMs: Long) =
        lastPersistMonoMs == Long.MIN_VALUE || nowMonoMs - lastPersistMonoMs >= 30_000L
}

/** Click-vs-tap choice for an ancestor click. */
object ClickRule {
    const val MAX_AREA_RATIO = 4L
    private fun area(r: Rect) = r.width.toLong().coerceAtLeast(0) * r.height.toLong().coerceAtLeast(0)

    /** True when the clickable ancestor is much larger than the target: tap the target's centre instead. */
    fun preferTap(target: Rect, ancestor: Rect): Boolean {
        val t = area(target)
        return t > 0 && area(ancestor) > MAX_AREA_RATIO * t
    }
}

/** Block screen did launch iff it resumed at or after the launch time. */
object BlockLaunch {
    const val CHECK_DELAY_MS = 800L
    fun shown(lastResumeMonoMs: Long, launchMonoMs: Long) = lastResumeMonoMs >= launchMonoMs
}

package com.gainandshine.unloop.core.limits

import com.gainandshine.unloop.core.time.Schedule

/**
 * Strict mode: while it is active, protection may only get tighter. Pure rules; the shell asks before it saves.
 * Loosening means raising or removing a limit, removing or editing an enabled schedule, or dropping a scheduled app.
 */
object StrictPolicy {
    fun isActive(untilMs: Long, nowMs: Long): Boolean = nowMs < untilMs

    fun limitsAllowed(old: List<LimitRule>, new: List<LimitRule>): Boolean {
        val n = new.associateBy { it.target }
        return old.all { o -> n[o.target]?.let { it.dailySeconds <= o.dailySeconds } == true }
    }

    fun schedulesAllowed(old: List<Schedule>, new: List<Schedule>): Boolean {
        val n = new.associateBy { it.id }
        return old.all { o -> !o.enabled || n[o.id] == o }
    }

    /** Groups may be added and tightened. Removing one, raising its limit or dropping a member loosens protection. */
    fun groupsAllowed(old: List<AppGroup>, new: List<AppGroup>): Boolean {
        val n = new.associateBy { it.id }
        return old.all { o -> n[o.id]?.let { it.dailySeconds <= o.dailySeconds && it.pkgs.containsAll(o.pkgs) } == true }
    }

    fun scheduledAppsAllowed(old: Set<String>, new: Set<String>): Boolean = new.containsAll(old)
}

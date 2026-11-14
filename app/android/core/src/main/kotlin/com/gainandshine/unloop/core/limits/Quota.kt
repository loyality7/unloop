package com.gainandshine.unloop.core.limits

import java.time.Instant

/** Per-target daily limit. [target] is an opaque id: a package or a group id. */
data class LimitRule(val target: String, val dailySeconds: Long, val warnPercents: List<Int> = listOf(80)) {
    init {
        require(dailySeconds >= 0) { "dailySeconds must be >= 0" }
    }
}

/** Explicit extension record: adds [addedSeconds] to [target] limit during `[grantedAt, expiresAt)`. */
data class Extension(
    val id: String,
    val target: String,
    val grantedAt: Instant,
    val addedSeconds: Long,
    val expiresAt: Instant,
) {
    /** Deterministic expiry: active iff grantedAt <= now < expiresAt. */
    fun isActive(now: Instant): Boolean = !now.isBefore(grantedAt) && now.isBefore(expiresAt)
}

enum class QuotaState { UNDER, WARNING, OVER }

/** Result of a quota evaluation. [percent] is uncapped integer use/limit; [resetsAt] is the day end. */
data class QuotaStatus(
    val state: QuotaState,
    val effectiveLimitSeconds: Long,
    val usedSeconds: Long,
    val remainingSeconds: Long,
    val percent: Int,
    val resetsAt: Instant,
)

/** Pure quota arithmetic. State is OVER at >= 100%, WARNING at >= the lowest warn threshold below 100. */
object QuotaEvaluator {
    fun evaluate(
        rule: LimitRule,
        usedSeconds: Long,
        day: DayKey,
        extensions: List<Extension>,
        now: Instant,
    ): QuotaStatus {
        val added = extensions.filter { it.target == rule.target && it.isActive(now) }.sumOf { it.addedSeconds }
        val limit = rule.dailySeconds + added
        val used = usedSeconds.coerceAtLeast(0)
        val pct = if (limit <= 0) 100 else (used * 100 / limit).toInt()
        val warnAt = rule.warnPercents.filter { it in 1..99 }.minOrNull()
        val state = when {
            used >= limit -> QuotaState.OVER
            warnAt != null && pct >= warnAt -> QuotaState.WARNING
            else -> QuotaState.UNDER
        }
        return QuotaStatus(state, limit, used, (limit - used).coerceAtLeast(0), pct, day.end())
    }
}

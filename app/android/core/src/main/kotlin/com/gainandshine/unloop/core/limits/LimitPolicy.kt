package com.gainandshine.unloop.core.limits

import com.gainandshine.unloop.core.time.Schedule
import com.gainandshine.unloop.core.time.ScheduleEvaluator
import java.time.Instant
import java.time.ZonedDateTime

enum class BlockReason { SCHEDULE, QUOTA, MANUAL }

/** Outcome for one target at one moment. */
sealed interface BlockDecision {
    data object Allow : BlockDecision
    data class Warn(val percent: Int) : BlockDecision
    data class Block(val reason: BlockReason, val until: Instant?) : BlockDecision
}

/** Master/manual block; [until] null means until manually removed. */
data class ManualBlock(val until: Instant?) {
    fun isActive(now: Instant): Boolean = until == null || now.isBefore(until)
}

/** "Allow N minutes" record: active during `[grantedAt, expiresAt)`. */
data class TemporaryAccess(val id: String, val target: String, val grantedAt: Instant, val expiresAt: Instant) {
    fun isActive(now: Instant): Boolean = !now.isBefore(grantedAt) && now.isBefore(expiresAt)
}

/**
 * Combined decision. Precedence: manual block > temporary access (lifts schedule and quota) > schedule >
 * quota OVER > quota WARNING > allow.
 */
object LimitPolicy {
    fun decide(
        target: String,
        now: ZonedDateTime,
        schedules: List<Schedule>,
        quota: QuotaStatus?,
        manualBlock: ManualBlock?,
        tempAccess: List<TemporaryAccess>,
    ): BlockDecision {
        val at = now.toInstant()
        if (manualBlock != null && manualBlock.isActive(at)) {
            return BlockDecision.Block(BlockReason.MANUAL, manualBlock.until)
        }
        if (tempAccess.any { it.target == target && it.isActive(at) }) return BlockDecision.Allow
        if (ScheduleEvaluator.isAnyActive(schedules, now)) {
            return BlockDecision.Block(BlockReason.SCHEDULE, ScheduleEvaluator.activeUntil(schedules, now))
        }
        return when (quota?.state) {
            QuotaState.OVER -> BlockDecision.Block(BlockReason.QUOTA, quota.resetsAt)
            QuotaState.WARNING -> BlockDecision.Warn(quota.percent)
            else -> BlockDecision.Allow
        }
    }
}

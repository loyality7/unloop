package com.gainandshine.unloop.usage

import com.gainandshine.unloop.core.usage.UsageEventType
import java.time.Duration
import java.time.Instant

/**
 * UsageEvents.Event codes to core types, kept free of android imports so it is unit-testable.
 * ACTIVITY_RESUMED == legacy MOVE_TO_FOREGROUND (1) and ACTIVITY_PAUSED == MOVE_TO_BACKGROUND (2), so one
 * table serves every API level; codes newer than the running OS are simply never emitted.
 */
object UsageEventMap {
    const val RESUMED = 1
    const val PAUSED = 2
    const val SCREEN_INTERACTIVE = 15 // API 28
    const val SCREEN_NON_INTERACTIVE = 16 // API 28
    const val KEYGUARD_SHOWN = 17 // API 28
    const val DEVICE_SHUTDOWN = 26

    fun map(code: Int): UsageEventType? = when (code) {
        RESUMED -> UsageEventType.RESUMED
        PAUSED -> UsageEventType.PAUSED
        SCREEN_INTERACTIVE -> UsageEventType.SCREEN_ON
        SCREEN_NON_INTERACTIVE -> UsageEventType.SCREEN_OFF
        KEYGUARD_SHOWN -> UsageEventType.KEYGUARD_SHOWN
        DEVICE_SHUTDOWN -> UsageEventType.SHUTDOWN
        else -> null
    }

    /**
     * Query start. Re-reads [OVERLAP] before the mark (the stitcher clips, so this cannot double count) so a
     * session still open at the last refresh keeps accruing; never looks back further than [MAX_LOOKBACK].
     */
    fun queryStart(highWater: Instant?, now: Instant): Instant {
        val floor = now.minus(MAX_LOOKBACK)
        val want = highWater?.minus(OVERLAP) ?: floor
        return if (want.isBefore(floor)) floor else want
    }

    val OVERLAP: Duration = Duration.ofMinutes(30)
    val MAX_LOOKBACK: Duration = Duration.ofDays(2)
}

package com.gainandshine.unloop.system

import android.os.SystemClock
import com.gainandshine.unloop.core.clock.Clock
import java.time.Instant
import java.time.ZoneId

object AndroidClock : Clock {
    override fun monotonicMs() = SystemClock.elapsedRealtime()
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

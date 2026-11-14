package com.gainandshine.unloop.core.clock

import java.time.Instant
import java.time.ZoneId

/**
 * Time source for the whole core. Two bases on purpose:
 *  - [monotonicMs]: elapsed-realtime style. Use for cooldowns, debounce, trackers. Never goes backwards.
 *  - [now]/[zone]: wall clock. Use ONLY for schedules, day keys and anything shown to the user.
 * Never mix the two in one comparison (wall clock moves when the user changes the time).
 */
interface Clock {
    fun monotonicMs(): Long
    fun now(): Instant
    fun zone(): ZoneId
}

/** Deterministic clock for tests. */
class FakeClock(
    private var mono: Long = 0,
    private var wall: Instant = Instant.parse("2026-01-05T08:00:00Z"),
    private var zoneId: ZoneId = ZoneId.of("UTC"),
) : Clock {
    override fun monotonicMs() = mono
    override fun now(): Instant = wall
    override fun zone(): ZoneId = zoneId

    fun advanceMs(ms: Long) {
        mono += ms
        wall = wall.plusMillis(ms)
    }

    /** A wall-clock jump that does not move the monotonic clock (user changed the time). */
    fun jumpWall(to: Instant) {
        wall = to
    }

    fun setZone(z: ZoneId) {
        zoneId = z
    }
}

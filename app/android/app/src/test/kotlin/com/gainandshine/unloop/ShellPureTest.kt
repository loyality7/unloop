package com.gainandshine.unloop

import com.gainandshine.unloop.core.limits.DayKey
import com.gainandshine.unloop.core.limits.LimitRule
import com.gainandshine.unloop.core.time.Schedule
import com.gainandshine.unloop.core.usage.UsageEventType
import com.gainandshine.unloop.block.BlockDebounce
import com.gainandshine.unloop.block.NeverBlock
import com.gainandshine.unloop.bridge.LimitsCodec
import com.gainandshine.unloop.store.FileLimitsRepository
import com.gainandshine.unloop.store.FileUsageRepository
import com.gainandshine.unloop.usage.UsageEventMap
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellPureTest {
    private fun tmp(): File = Files.createTempDirectory("unloop").toFile()

    @Test fun eventMapping() {
        assertEquals(UsageEventType.RESUMED, UsageEventMap.map(1))
        assertEquals(UsageEventType.SCREEN_OFF, UsageEventMap.map(16))
        assertEquals(UsageEventType.SHUTDOWN, UsageEventMap.map(26))
        assertNull(UsageEventMap.map(7))
    }

    @Test fun queryStartBoundedAndOverlapping() {
        val now = Instant.parse("2026-03-10T12:00:00Z")
        assertEquals(now.minus(UsageEventMap.MAX_LOOKBACK), UsageEventMap.queryStart(null, now))
        assertEquals(now.minus(UsageEventMap.MAX_LOOKBACK), UsageEventMap.queryStart(now.minusSeconds(86400L * 9), now))
        val hw = now.minusSeconds(600)
        assertEquals(hw.minus(UsageEventMap.OVERLAP), UsageEventMap.queryStart(hw, now))
    }

    @Test fun neverBlockIsExplicit() {
        assertTrue(NeverBlock.isProtected("com.android.systemui", emptySet()))
        assertTrue(NeverBlock.isProtected("x.launcher", setOf("x.launcher")))
        assertFalse(NeverBlock.isProtected("com.android.chrome", emptySet())) // no blanket com.android. prefix
    }

    @Test fun debounce() {
        val d = BlockDebounce(1000)
        assertTrue(d.shouldFire("a", 0))
        assertFalse(d.shouldFire("a", 500))
        assertTrue(d.shouldFire("b", 500))
        assertTrue(d.shouldFire("a", 1500))
    }

    @Test fun storeRoundTripAndCorruption() {
        val dir = tmp()
        FileLimitsRepository(dir).upsert(LimitRule("p", 600, listOf(50, 80)))
        assertEquals(listOf(LimitRule("p", 600, listOf(50, 80))), FileLimitsRepository(dir).all())

        val day = DayKey(LocalDate.of(2026, 3, 10), "UTC")
        val u = FileUsageRepository(dir)
        u.addMillis(day, "p", 1500); u.addMillis(day, "p", 500)
        u.setHighWater(Instant.ofEpochMilli(42))
        val u2 = FileUsageRepository(dir)
        assertEquals(2000, u2.millis(day, "p"))
        assertEquals(Instant.ofEpochMilli(42), u2.highWater())

        File(dir, "limits.json").writeText("{not json")
        assertTrue(FileLimitsRepository(dir).all().isEmpty())
        assertTrue(File(dir, "limits.json.bak").exists())
    }

    @Test fun codecRoundTrip() {
        val l = listOf(LimitRule("a", 60, listOf(80)))
        assertEquals(l, LimitsCodec.parseLimits(LimitsCodec.limitsToJson(l)))
        val s = listOf(Schedule("s", true, setOf(DayOfWeek.MONDAY), LocalTime.of(22, 0), LocalTime.of(7, 0)))
        assertEquals(s, LimitsCodec.parseSchedules(LimitsCodec.schedulesToJson(s)))
    }
}

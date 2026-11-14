package com.gainandshine.unloop

import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.notif.NotifConfig
import com.gainandshine.unloop.notif.NotifRuleStore
import com.gainandshine.unloop.system.*
import java.nio.file.Files
import kotlin.test.*

class ShellPoliciesTest {
    @Test fun scanDelayNeverZeroAndStaleDueBacksOff() {
        assertEquals(100, ScanTiming.delay(dueMs = 50, nowMs = 500, consumedDueMs = 10))
        assertEquals(400, ScanTiming.delay(900, 500, 10))
        assertEquals(1000, ScanTiming.delay(900, 1500, 900))
    }

    @Test fun nullSnapBackoffDoublesAndCaps() {
        val b = NullSnapBackoff()
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 2000L), List(5) { b.next() })
        b.reset(); assertEquals(250, b.next())
    }

    @Test fun recheckPolicy() {
        assertTrue(RecheckPolicy.shouldRun(true, true))
        assertFalse(RecheckPolicy.shouldRun(true, false))
        assertFalse(RecheckPolicy.shouldRun(false, true))
    }

    @Test fun scheduledAppsUnionAndCodec() {
        assertEquals(setOf("a", "b"), Targets.effective(listOf("a"), listOf("b", "a")))
        assertEquals(setOf("x", "y"), Targets.parseScheduled(Targets.encodeScheduled(listOf("x", "y", "x", ""))))
        assertEquals(emptySet(), Targets.parseScheduled(null))
        assertEquals(emptySet(), Targets.parseScheduled("garbage"))
    }

    @Test fun aliveStampSurvivesFreshProcess() {
        assertEquals(9_000L, AliveStamp.toMono(persistedWallMs = 99_000, nowWallMs = 100_000, nowMonoMs = 10_000))
        assertNull(AliveStamp.toMono(0, 100_000, 10_000))
        assertNull(AliveStamp.toMono(200_000, 100_000, 10_000))
        assertNull(AliveStamp.toMono(1, 100_000, 10_000)) // older than process uptime
        assertTrue(AliveStamp.shouldPersist(Long.MIN_VALUE, 5))
        assertFalse(AliveStamp.shouldPersist(1_000, 20_000))
        assertTrue(AliveStamp.shouldPersist(1_000, 31_000))
    }

    @Test fun ancestorTooBigPrefersTap() {
        val t = Rect(0, 0, 100, 100)
        assertFalse(ClickRule.preferTap(t, Rect(0, 0, 200, 200))) // exactly 4x
        assertTrue(ClickRule.preferTap(t, Rect(0, 0, 300, 200)))
    }

    @Test fun blockLaunchDetection() {
        assertTrue(BlockLaunch.shown(lastResumeMonoMs = 1_100, launchMonoMs = 1_000))
        assertFalse(BlockLaunch.shown(900, 1_000))
    }

    @Test fun notifStoreSeesTwoEditsInSameSecond() {
        val f = Files.createTempFile("n", ".json").toFile()
        val s = NotifRuleStore(f, "me")
        f.writeText(NotifConfig(enabled = true).toJson()); f.setLastModified(5_000)
        assertTrue(s.config().enabled)
        f.writeText(NotifConfig(enabled = false, allowlist = setOf("zz")).toJson()); f.setLastModified(5_000)
        assertFalse(s.config().enabled)
    }
}

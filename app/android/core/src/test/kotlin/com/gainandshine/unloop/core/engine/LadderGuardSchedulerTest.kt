package com.gainandshine.unloop.core.engine

import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.gainandshine.unloop.core.model.EventType

internal const val SURF = "instagram_reels"
internal fun block(vararg a: Action = arrayOf(Action.Back, Action.Home), surface: String = SURF) =
    Verdict.Block(surface, "test", a.toList())

class EnforcementLadderTest {
    private val cfg = LadderConfig(verifyWindowMs = 700, backoffBaseMs = 2_000, backoffCapMs = 30_000, maxAttempts = 6)

    @Test fun successOnFirstRung() {
        val l = EnforcementLadder(cfg)
        val p = assertIs<Step.Perform>(l.next(block(), 0))
        assertEquals(Action.Back, p.action)
        assertEquals(Phase.ACTED, l.state(SURF)?.phase)
        assertEquals(Step.Wait(700), l.next(Verdict.None, 300)) // too early to call it a success
        assertEquals(Step.Idle, l.next(Verdict.None, 700))
        assertEquals(listOf(SURF), l.lastSucceeded)
        assertEquals(Phase.IDLE, l.state(SURF)?.phase)
        assertEquals(0, l.state(SURF)?.attempts)
    }

    @Test fun stillBlockedInsideWindowWaitsAndVerifies() {
        val l = EnforcementLadder(cfg)
        l.next(block(), 0)
        assertEquals(Step.Wait(700), l.next(block(), 699))
        assertEquals(Phase.VERIFYING, l.state(SURF)?.phase)
    }

    @Test fun escalatesThenGivesUp() {
        val l = EnforcementLadder(cfg)
        l.next(block(), 0)
        val p2 = assertIs<Step.Perform>(l.next(block(), 700))
        assertEquals(Action.Home, p2.action)
        assertEquals(1, p2.rung)
        val g = assertIs<Step.GiveUp>(l.next(block(), 1_400))
        assertEquals(EnforcementLadder.REASON_EXHAUSTED, g.reason)
        assertEquals(Phase.GAVE_UP, l.state(SURF)?.phase)
        assertEquals(3_400L, l.state(SURF)?.retryAtMs)
        assertEquals(Step.Wait(3_400), l.next(block(), 2_000)) // never loops faster than the backoff
        assertIs<Step.Perform>(l.next(block(), 3_400)) // retry restarts at rung 0
    }

    @Test fun backoffDoublesAndCaps() {
        val l = EnforcementLadder(cfg.copy(maxAttempts = 100))
        val one = block(Action.Back)
        var t = 0L
        val waits = ArrayList<Long>()
        repeat(7) {
            assertIs<Step.Perform>(l.next(one, t)); t += 700
            assertIs<Step.GiveUp>(l.next(one, t))
            waits += l.state(SURF)!!.retryAtMs!! - t
            t = l.state(SURF)!!.retryAtMs!!
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), waits)
    }

    @Test fun maxAttemptsStopsForGood() {
        val l = EnforcementLadder(cfg.copy(maxAttempts = 3))
        val one = block(Action.Back)
        var t = 0L
        repeat(3) {
            assertIs<Step.Perform>(l.next(one, t)); t += 700
            assertIs<Step.GiveUp>(l.next(one, t)); t = l.state(SURF)!!.retryAtMs!!
        }
        val g = assertIs<Step.GiveUp>(l.next(one, t))
        assertEquals(EnforcementLadder.REASON_MAX_ATTEMPTS, g.reason)
        assertNull(l.state(SURF)?.retryAtMs)
        assertEquals(Step.Idle, l.next(one, t + 1_000_000))
        l.clear()
        assertIs<Step.Perform>(l.next(one, t + 1_000_001)) // new sitting
    }

    @Test fun emptyLadderGivesUp() {
        val l = EnforcementLadder(cfg)
        assertEquals(EnforcementLadder.REASON_EMPTY_LADDER, assertIs<Step.GiveUp>(l.next(Verdict.Block(SURF, "r", emptyList()), 0)).reason)
    }

    @Test fun foregroundChangedIsSuccess() {
        val l = EnforcementLadder(cfg)
        l.next(block(), 0)
        assertEquals(1, l.onForegroundChanged())
        assertNull(l.state(SURF))
        assertEquals(0, l.onForegroundChanged())
    }

    @Test fun backwardsClockKeepsRemainingWait() {
        val l = EnforcementLadder(cfg)
        l.next(block(), 10_000)
        // clock jumps back: the window is re-based to the new time base, not frozen and not early
        assertEquals(Step.Wait(2_000), l.next(block(), 1_300))
        assertIs<Step.Perform>(l.next(block(), 2_000))
    }

    @Test fun backwardsClockDuringBackoff() {
        val l = EnforcementLadder(cfg)
        val one = block(Action.Back)
        l.next(one, 100_000); l.next(one, 100_700) // give up, retry at 102_700
        assertEquals(Step.Wait(2_200), l.next(one, 200)) // retryAt shifted by the regression (-100_500)
        assertIs<Step.Perform>(l.next(one, 2_200))
    }

    @Test fun surfacesAreIndependent() {
        val l = EnforcementLadder(cfg)
        l.next(block(surface = "a"), 0)
        assertIs<Step.Perform>(l.next(block(surface = "b"), 10))
        assertEquals(setOf("a", "b"), l.activeSurfaces())
    }
}

class LoopGuardTest {
    @Test fun cooldownBoundary() {
        val g = LoopGuard()
        assertTrue(g.scansAllowed(0))
        g.onActionIssued(1_000)
        g.onActionCompleted(1_100)
        assertEquals(1_900L, g.blockedUntil(1_899))
        assertNull(g.blockedUntil(1_900))
    }

    @Test fun inFlightIgnoresScansButTimesOut() {
        val g = LoopGuard(cooldownMs = 10, inFlightTimeoutMs = 2_000)
        g.onActionIssued(0)
        assertTrue(g.inFlight(1_999))
        assertFalse(g.scansAllowed(1_999))
        assertFalse(g.inFlight(2_000))
        assertTrue(g.scansAllowed(2_000))
    }

    @Test fun negativeDeltaIsDefensive() {
        val g = LoopGuard()
        g.onActionCompleted(1_000_000)
        // monotonic input went backwards: gate re-bases, so it ends one cooldown after the regression
        assertEquals(500L + 800, g.blockedUntil(500))
        assertNull(g.blockedUntil(1_300))
    }

    @Test fun debouncePerSurface() {
        val g = LoopGuard(debounceMs = 100)
        assertTrue(g.debounceOk("a", 0))
        assertFalse(g.debounceOk("a", 99))
        assertTrue(g.debounceOk("b", 99))
        assertTrue(g.debounceOk("a", 100))
        assertTrue(g.debounceOk("a", 0)) // backwards: admitted
    }
}

class ScanSchedulerTest {
    private fun e(type: EventType, at: Long, flags: Int = 0) = ev(type = type, at = at, flags = flags)

    @Test fun trailingDebounceTrailsLastEvent() {
        val s = ScanScheduler()
        s.onEvent(e(EventType.WINDOW_CONTENT_CHANGED, 1_000), tracked = true)
        s.onEvent(e(EventType.VIEW_SCROLLED, 1_050), tracked = true)
        assertEquals(1_170L, s.nextDueMs())
        assertFalse(s.takeDue(1_169))
        assertTrue(s.takeDue(1_170))
        assertNull(s.nextDueMs())
    }

    @Test fun windowChangeBurst() {
        val s = ScanScheduler()
        s.onEvent(e(EventType.WINDOW_STATE_CHANGED, 0), tracked = true)
        assertTrue(s.takeDue(120)) // trailing consumed
        assertEquals(150L, s.nextDueMs())
        val fired = ArrayList<Long>()
        var t = 0L
        while (t <= 700) { if (s.takeDue(t)) fired += t; t++ }
        assertEquals(listOf(150L, 300L, 450L, 600L), fired)
        assertNull(s.nextDueMs())
    }

    @Test fun paneChangeOnlyTrailing() {
        val s = ScanScheduler()
        s.onEvent(e(EventType.WINDOW_STATE_CHANGED, 0, flags = 0x08), tracked = true)
        assertEquals(120L, s.nextDueMs())
        assertTrue(s.takeDue(120))
        assertNull(s.nextDueMs())
    }

    @Test fun untrackedAndOtherScheduleNothing() {
        val s = ScanScheduler()
        s.onEvent(e(EventType.WINDOW_STATE_CHANGED, 0), tracked = false)
        s.onEvent(e(EventType.OTHER, 0), tracked = true)
        assertNull(s.nextDueMs())
    }

    @Test fun requestAtAndClear() {
        val s = ScanScheduler()
        s.requestAt(500)
        assertEquals(500L, s.nextDueMs())
        s.clear()
        assertNull(s.nextDueMs())
    }

    @Test fun continuousEventsAreScannedWithinMaxLatency() {
        val s = ScanScheduler()
        var t = 0L
        while (t <= 2_000 && !s.takeDue(t)) {
            s.onEvent(e(EventType.WINDOW_CONTENT_CHANGED, t), tracked = true)
            t += 50
        }
        assertTrue(t <= ScanScheduler.DEFAULT_MAX_LATENCY_MS, "first scan due at $t")

    }
}

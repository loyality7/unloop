package com.gainandshine.unloop.core.engine

import com.gainandshine.unloop.core.clock.FakeClock
import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceAdapter
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PKG = "com.instagram.android"
private const val LAUNCH = "com.pixel.launcher"

private class FakeAdapter : SurfaceAdapter {
    var verdict: Verdict = Verdict.None
    var throwOnEvaluate = false
    var resets = 0
    val events = ArrayList<EventRecord>()
    override val pkgs = setOf(PKG)
    override val surfaces = setOf(SurfaceId.INSTAGRAM_REELS)
    override fun onEvent(e: EventRecord) { events += e }
    override fun evaluate(snap: UiSnapshot, cfg: DetectionConfig): Verdict {
        if (throwOnEvaluate) error("boom")
        return verdict
    }
    override fun reset() { resets++ }
}

class DetectionEngineTest {
    private val clock = FakeClock()
    private val adapter = FakeAdapter()
    private val diag = InMemoryDiagnostics()
    private val reels = Verdict.Block(SurfaceId.INSTAGRAM_REELS, "reels", listOf(Action.Back, Action.Home))

    private fun engine(enabled: Boolean = true) = DetectionEngine(
        adapters = listOf(adapter),
        classifier = ClassifierContext(ownPackage = "com.gainandshine.unloop", launcherPackages = setOf(LAUNCH)),
        clock = clock,
        inputs = EngineInputs(enabled = enabled),
        diagnostics = diag,
    )

    private fun scan(at: Long, pkg: String = PKG) = snapshot(pkg, at)
    private fun windowEvent(at: Long, pkg: String = PKG) = ev(pkg = pkg, at = at)

    private fun at(ms: Long) { clock.advanceMs(ms - clock.monotonicMs()) }

    @Test fun blockActionStillThereEscalateGone() {
        val e = engine()
        adapter.verdict = reels
        val out0 = e.onScan(scan(0))
        assertEquals(EngineOutput.Do(Action.Back, SurfaceId.INSTAGRAM_REELS, "reels"), out0)
        at(50); e.onActionCompleted()
        // inside cooldown (completed at 50 -> until 850): scan is deferred, not evaluated
        assertEquals(EngineOutput.ScheduleScan(850), e.onScan(scan(300)))
        // cooldown over, verify window over (700), still Block -> escalate to Home
        val out2 = e.onScan(scan(850))
        assertEquals(EngineOutput.Do(Action.Home, SurfaceId.INSTAGRAM_REELS, "reels"), out2)
        at(900); e.onActionCompleted()
        // user is gone: None after the window -> success, nothing more to do
        adapter.verdict = Verdict.None
        val out3 = e.onScan(scan(1_700))
        assertEquals(EngineOutput.Nothing, out3)
        assertEquals(2, diag.blocks(SurfaceId.INSTAGRAM_REELS))
        assertEquals(1, diag.successes(SurfaceId.INSTAGRAM_REELS))
        assertEquals(Phase.IDLE, e.ladderState(SurfaceId.INSTAGRAM_REELS)?.phase)
    }

    @Test fun verifyWaitIsScheduled() {
        val e = engine()
        adapter.verdict = reels
        e.onScan(scan(0))
        e.onActionCompleted() // cooldown 800 from clock 0
        adapter.verdict = Verdict.None
        // a None at 800 is after the window (700) -> success
        assertEquals(EngineOutput.Nothing, e.onScan(scan(800)))
        // Now a case where None arrives inside the window (cooldown shorter than window)
        val e2 = DetectionEngine(
            listOf(adapter), ClassifierContext("com.gainandshine.unloop"), clock, EngineInputs(enabled = true),
            guard = LoopGuard(cooldownMs = 100), ladderConfig = LadderConfig(verifyWindowMs = 700),
        )
        adapter.verdict = reels
        at(10_000)
        assertIs<EngineOutput.Do>(e2.onScan(scan(10_000)))
        e2.onActionCompleted()
        at(10_200)
        adapter.verdict = Verdict.None
        assertEquals(EngineOutput.ScheduleScan(10_700), e2.onScan(scan(10_200)))
        assertEquals(10_700L, e2.nextScanAtMs())
    }

    @Test fun giveUpIsCountedOnce() {
        val e = engine()
        adapter.verdict = Verdict.Block(SurfaceId.INSTAGRAM_REELS, "r", listOf(Action.Back))
        e.onScan(scan(0)); e.onActionCompleted()
        at(1_000)
        assertEquals(EngineOutput.Nothing, e.onScan(scan(1_000)))
        assertEquals(1, diag.giveUps(SurfaceId.INSTAGRAM_REELS))
        assertIs<EngineOutput.ScheduleScan>(e.onScan(scan(1_200))) // backoff wait
        assertEquals(1, diag.giveUps(SurfaceId.INSTAGRAM_REELS))
    }

    @Test fun disabledDoesNothing() {
        val e = engine(enabled = false)
        adapter.verdict = reels
        assertEquals(EngineOutput.Nothing, e.onScan(scan(0)))
        assertEquals(EngineOutput.Nothing, e.onEvent(windowEvent(0)))
        assertTrue(adapter.events.isEmpty())
        assertNull(e.nextScanAtMs())
    }

    @Test fun lockedScreenDoesNothingAndEnableTransitionWorks() {
        val e = engine()
        adapter.verdict = reels
        e.update(EngineInputs(enabled = true, interactiveUnlocked = false))
        assertEquals(EngineOutput.Nothing, e.onScan(scan(0)))
        e.update(EngineInputs(enabled = true, interactiveUnlocked = true))
        assertIs<EngineOutput.Do>(e.onScan(scan(10)))
    }

    @Test fun surfaceSwitchedOffIsUntracked() {
        val e = engine()
        e.update(EngineInputs(enabled = true, config = DetectionConfig(enabled = emptySet())))
        adapter.verdict = reels
        assertEquals(EngineOutput.Nothing, e.onScan(scan(0)))
        assertEquals(EngineOutput.Nothing, e.onEvent(windowEvent(0)))
    }

    @Test fun untrackedPackageSchedulesNothing() {
        val e = engine()
        assertEquals(EngineOutput.Nothing, e.onEvent(windowEvent(0, pkg = "com.other")))
        assertEquals(EngineOutput.Nothing, e.onScan(scan(0, pkg = "com.other")))
        assertNull(e.nextScanAtMs())
    }

    @Test fun trackedEventSchedulesAndFeedsAdapter() {
        val e = engine()
        val out = e.onEvent(windowEvent(1_000))
        assertEquals(EngineOutput.ScheduleScan(1_120), out)
        assertEquals(1, adapter.events.size)
        // content change pushes the trailing scan to 1170; the first burst scan (1150) now leads
        val out2 = e.onEvent(ev(type = EventType.WINDOW_CONTENT_CHANGED, at = 1_050))
        assertEquals(EngineOutput.ScheduleScan(1_150), out2)
    }

    @Test fun resetOnHomeLongAbsenceButNotOnTransientOrShortTrip() {
        val e = engine()
        e.onEvent(windowEvent(0)) // sitting starts
        e.onEvent(ev(pkg = "com.google.android.inputmethod.latin", at = 10)) // unknown/other never resets
        assertEquals(0, adapter.resets)
        e.update(EngineInputs(enabled = true, interactiveUnlocked = false))
        at(60_000)
        e.update(EngineInputs(enabled = true, interactiveUnlocked = true)) // short absence
        assertEquals(0, adapter.resets)
        e.onEvent(ev(pkg = LAUNCH, at = 60_001)) // home
        assertEquals(1, adapter.resets)
    }

    @Test fun longScreenOffResets() {
        val e = engine()
        e.onEvent(windowEvent(0))
        e.update(EngineInputs(enabled = true, interactiveUnlocked = false))
        at(SittingTracker.DEFAULT_WINDOW_MS)
        e.update(EngineInputs(enabled = true, interactiveUnlocked = true))
        assertEquals(1, adapter.resets)
    }

    @Test fun leavingForegroundClearsLadderAndDisableResetsAll() {
        val e = engine()
        adapter.verdict = reels
        e.onEvent(windowEvent(0))
        e.onScan(scan(0))
        assertEquals(Phase.ACTED, e.ladderState(SurfaceId.INSTAGRAM_REELS)?.phase)
        e.onEvent(ev(pkg = LAUNCH, at = 100))
        assertNull(e.ladderState(SurfaceId.INSTAGRAM_REELS))
        e.onEvent(windowEvent(200))
        e.update(EngineInputs(enabled = false))
        assertEquals(2, adapter.resets) // home + disable
    }

    @Test fun adapterExceptionFailsOpen() {
        val e = engine()
        adapter.throwOnEvaluate = true
        assertEquals(EngineOutput.Nothing, e.onScan(scan(0)))
        assertEquals(1, diag.ruleMisses(RuleMiss.ADAPTER_ERROR))
    }

    @Test fun duplicatePackageClaimsFailFast() {
        val threw = runCatching { AdapterRegistry(listOf(FakeAdapter(), FakeAdapter())) }.isFailure
        assertTrue(threw)
    }

    @Test fun gatedOffNeverLeavesAScanDue() {
        val e = engine()
        e.onEvent(windowEvent(0))
        assertTrue(e.nextScanAtMs() != null)
        e.update(EngineInputs(enabled = true, interactiveUnlocked = false)) // power pressed within 150 ms
        assertNull(e.nextScanAtMs())
        assertEquals(EngineOutput.Nothing, e.onScan(scan(150)))
        assertNull(e.nextScanAtMs())
    }

    @Test fun disablingClearsPendingScan() {
        val e = engine()
        e.onEvent(windowEvent(0))
        e.update(EngineInputs(enabled = false))
        assertNull(e.nextScanAtMs())
    }
}

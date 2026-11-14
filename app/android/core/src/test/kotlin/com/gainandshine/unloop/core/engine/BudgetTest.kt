package com.gainandshine.unloop.core.engine

import com.gainandshine.unloop.core.clock.FakeClock
import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.CoverRect
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceAdapter
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PKG = "com.instagram.android"

private class Fixed : SurfaceAdapter {
    override val pkgs = setOf(PKG)
    override val surfaces = setOf(SurfaceId.INSTAGRAM_REELS)
    var verdict: Verdict = Verdict.Block(SurfaceId.INSTAGRAM_REELS, "reels", listOf(Action.Back, Action.Home))
    override fun evaluate(snap: UiSnapshot, cfg: DetectionConfig) = verdict
    override fun covers(snap: UiSnapshot, cfg: DetectionConfig) =
        listOf(CoverRect(SurfaceId.INSTAGRAM_REELS_TAB, "reels_tab", Rect(0, 2200, 200, 2400)))
    override fun reset() = Unit
}

private class Fake(var allowMs: Long) : SurfaceBudgets {
    var used = 0L
    override fun allowanceMs(surface: String) = if (surface == SurfaceId.INSTAGRAM_REELS) allowMs else 0L
    override fun usedMs(surface: String) = used
    override fun addUsedMs(surface: String, ms: Long) { used += ms }
}

class BudgetTest {
    private val cfg = DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_REELS, SurfaceId.INSTAGRAM_REELS_TAB))
    private val adapter = Fixed()

    private fun engine(b: SurfaceBudgets) = DetectionEngine(
        adapters = listOf(adapter),
        classifier = ClassifierContext(ownPackage = "com.gainandshine.unloop"),
        clock = FakeClock(),
        inputs = EngineInputs(enabled = true, config = cfg),
        budgets = b,
    )

    private fun scan(e: DetectionEngine, at: Long) = e.onScan(snapshot(PKG, at))

    @Test fun `allowance lets the user watch, charges per scan, then blocks`() {
        val b = Fake(3_000)
        val e = engine(b)
        assertEquals(EngineOutput.ScheduleScan(1_000), scan(e, 0))
        assertEquals(EngineOutput.ScheduleScan(2_000), scan(e, 1_000))
        assertEquals(EngineOutput.ScheduleScan(3_000), scan(e, 2_000))
        assertEquals(EngineOutput.ScheduleScan(4_000), scan(e, 3_000))
        assertEquals(3_000, b.used)
        val out = scan(e, 4_000)
        assertEquals(EngineOutput.Do(Action.Back, SurfaceId.INSTAGRAM_REELS, "reels"), out)
        assertEquals(3_000, b.used, "blocked time is not charged")
    }

    @Test fun `no allowance blocks at once`() {
        assertIs<EngineOutput.Do>(scan(engine(Fake(0)), 0))
    }

    @Test fun `a long gap is not charged`() {
        val b = Fake(60_000)
        val e = engine(b)
        scan(e, 0)
        scan(e, 10_000) // app was away: not provably watching
        assertEquals(0, b.used)
        scan(e, 11_000)
        assertEquals(1_000, b.used)
    }

    @Test fun `leaving the surface stops the charge`() {
        val b = Fake(60_000)
        val e = engine(b)
        scan(e, 0)
        adapter.verdict = Verdict.None
        scan(e, 1_000)
        adapter.verdict = Verdict.Block(SurfaceId.INSTAGRAM_REELS, "reels", listOf(Action.Back))
        scan(e, 2_000) // new visit: first scan charges nothing
        assertEquals(0, b.used)
    }

    @Test fun `the button cover stays off while allowance remains and returns once it is used up`() {
        val b = Fake(2_000)
        val e = engine(b)
        val snap = snapshot(PKG, 0)
        assertTrue(e.planCovers(snap).isEmpty())
        b.used = 2_000
        assertEquals(listOf(SurfaceId.INSTAGRAM_REELS_TAB), e.planCovers(snap).map { it.surface })
        assertTrue(engine(SurfaceBudgets.None).planCovers(snap).isNotEmpty())
    }
}

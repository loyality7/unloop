package com.gainandshine.unloop.core.engine

import com.gainandshine.unloop.core.clock.FakeClock
import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceAdapter
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val IGPKG = "com.instagram.android"

/** Reports "a reel is on screen" as a Block verdict, like the real adapters, only when its surface is listed in cfg. */
private class ReelsAdapter : SurfaceAdapter {
    override val pkgs = setOf(IGPKG)
    override val surfaces = setOf(SurfaceId.INSTAGRAM_REELS)
    var onScreen = true
    override fun evaluate(snap: UiSnapshot, cfg: DetectionConfig): Verdict =
        if (onScreen && cfg.isOn(SurfaceId.INSTAGRAM_REELS)) Verdict.Block(SurfaceId.INSTAGRAM_REELS, "reels", listOf(Action.Back)) else Verdict.None
    override fun reset() = Unit
}

class CounterTest {
    private val adapter = ReelsAdapter()

    private fun engine(cfg: DetectionConfig) = DetectionEngine(
        adapters = listOf(adapter),
        classifier = ClassifierContext(ownPackage = "com.gainandshine.unloop"),
        clock = FakeClock(),
        inputs = EngineInputs(enabled = true, config = cfg),
    )

    private fun swipe(index: Int, at: Long) =
        EventRecord(EventType.VIEW_SCROLLED, IGPKG, null, fromIndex = index, scrollDy = 900, atMs = at)

    private fun scan(e: DetectionEngine, at: Long) = e.onScan(snapshot(IGPKG, at))

    @Test fun `counts swipes where nothing is blocked`() {
        val e = engine(DetectionConfig(enabled = emptySet(), counter = true))
        val out = scan(e, 0)
        assertEquals(EngineOutput.ScheduleScan(2_000), out, "no block, but keep scanning so leaving the feed is noticed")
        assertEquals(0, e.counterState(10)?.count)
        for ((i, t) in listOf(0 to 100L, 1 to 700L, 2 to 1_300L, 3 to 1_900L)) e.onEvent(swipe(i, t))
        assertEquals(3, e.counterState(2_000)?.count, "the first event only sets the baseline")
        assertEquals(SurfaceId.INSTAGRAM_REELS, e.counterState(2_000)?.surface)
    }

    @Test fun `counter off means no state`() {
        val e = engine(DetectionConfig(enabled = emptySet(), counter = false))
        assertEquals(EngineOutput.Nothing, scan(e, 0))
        assertNull(e.counterState(10))
    }

    @Test fun `auto kick sends home at the limit and starts over`() {
        val e = engine(DetectionConfig(enabled = emptySet(), counter = true, autoKickAfter = 3))
        scan(e, 0)
        e.onEvent(swipe(0, 100))
        e.onEvent(swipe(1, 700))
        e.onEvent(swipe(2, 1_300))
        val kick = e.onEvent(swipe(3, 1_900))
        assertEquals(EngineOutput.Do(Action.Home, SurfaceId.INSTAGRAM_REELS, "auto_kick"), kick)
        assertNull(e.counterState(2_000), "the count starts over after a kick")
    }

    @Test fun `auto kick works even with the counter hidden`() {
        val e = engine(DetectionConfig(enabled = emptySet(), counter = false, autoKickAfter = 1))
        scan(e, 0)
        e.onEvent(swipe(0, 100))
        assertIs<EngineOutput.Do>(e.onEvent(swipe(1, 700)))
    }

    @Test fun `distance based counting for feeds without an item index`() {
        val e = engine(DetectionConfig(enabled = emptySet(), counter = true))
        scan(e, 0) // default screen height 2400 -> 1440 px per item
        fun dy(at: Long, d: Int) = EventRecord(EventType.VIEW_SCROLLED, IGPKG, null, scrollDy = d, atMs = at)
        e.onEvent(dy(100, 500)); e.onEvent(dy(300, 500))
        assertEquals(0, e.counterState(400)?.count)
        e.onEvent(dy(500, 500))
        assertEquals(1, e.counterState(600)?.count)
    }

    @Test fun `horizontal scrolls and stale feeds are not counted`() {
        val e = engine(DetectionConfig(enabled = emptySet(), counter = true))
        scan(e, 0)
        e.onEvent(EventRecord(EventType.VIEW_SCROLLED, IGPKG, null, scrollDx = 900, scrollDy = 10, fromIndex = 1, atMs = 100))
        e.onEvent(EventRecord(EventType.VIEW_SCROLLED, IGPKG, null, scrollDx = 900, scrollDy = 10, fromIndex = 2, atMs = 200))
        assertEquals(0, e.counterState(300)?.count)
        e.onEvent(swipe(5, 10_000)) // the feed was last seen at 0: too old to count
        e.onEvent(swipe(6, 10_500))
        assertNull(e.counterState(10_600), "the feed has not been seen for a while")
    }

    @Test fun `leaving the feed ends the session`() {
        val e = engine(DetectionConfig(enabled = emptySet(), counter = true))
        scan(e, 0)
        e.onEvent(swipe(0, 100)); e.onEvent(swipe(1, 600))
        assertEquals(1, e.counterState(700)?.count)
        adapter.onScreen = false
        scan(e, 2_000)
        assertNull(e.counterState(7_000))
        adapter.onScreen = true
        scan(e, 8_000)
        assertEquals(1, e.counterState(8_100)?.count, "returning within two minutes keeps the session")
        adapter.onScreen = false
        scan(e, 200_000) // away for over two minutes: the session ends
        adapter.onScreen = true
        scan(e, 201_000)
        assertEquals(0, e.counterState(201_100)?.count)
    }

    @Test fun `a real block still wins over counting`() {
        val e = engine(DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_REELS), counter = true))
        assertIs<EngineOutput.Do>(scan(e, 0))
        assertNull(e.counterState(10), "a blocked feed is not being watched")
    }
}

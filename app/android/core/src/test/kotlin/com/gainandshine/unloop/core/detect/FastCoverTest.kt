package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.clock.FakeClock
import com.gainandshine.unloop.core.detect.Trees.IG
import com.gainandshine.unloop.core.detect.instagram.InstagramAdapter
import com.gainandshine.unloop.core.detect.youtube.YoutubeAdapter
import com.gainandshine.unloop.core.engine.ClassifierContext
import com.gainandshine.unloop.core.engine.DetectionEngine
import com.gainandshine.unloop.core.engine.EngineInputs
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FastCoverTest {
    private val all = setOf(
        SurfaceId.INSTAGRAM_REELS_TAB, SurfaceId.INSTAGRAM_STORIES, SurfaceId.INSTAGRAM_CAUGHT_UP, SurfaceId.YOUTUBE_SHORTS_TAB,
    )

    private fun engine(enabled: Set<String>, on: Boolean = true) = DetectionEngine(
        adapters = listOf(InstagramAdapter(), YoutubeAdapter()),
        classifier = ClassifierContext(ownPackage = "com.gainandshine.unloop"),
        clock = FakeClock(),
        inputs = EngineInputs(enabled = on, config = DetectionConfig(enabled = enabled)),
    )

    @Test fun `scrolling covers are flagged so they are dropped the moment their node goes`() {
        val tray = n(Trees.ig("reels_tray_container"), 0, 200, 1080, 500)
        val marker = n(Trees.ig("end_of_feed_demarcator_container"), 0, 900, 1080, 1100)
        val bar = n(Trees.ig("tab_bar"), 0, 2200, 1080, 2400)
        val covers = InstagramAdapter().covers(
            snapshot(IG, 0, tray, marker, bar, Trees.igTabBar()),
            DetectionConfig(enabled = all),
        )
        val bySurface = covers.associateBy { it.surface }
        assertTrue(bySurface.getValue(SurfaceId.INSTAGRAM_STORIES).follows)
        assertTrue(bySurface.getValue(SurfaceId.INSTAGRAM_CAUGHT_UP).follows)
        assertTrue(!bySurface.getValue(SurfaceId.INSTAGRAM_REELS_TAB).follows)
    }

    @Test fun `engine only asks for lookups when a cover surface is on`() {
        val ig = engine(all).coverIdsFor(IG)
        assertTrue(ig.keys.containsAll(listOf(Trees.ig("reels_tray_container"), Trees.ig("tab_bar"), Trees.ig("clips_tab"))))
        assertTrue(ig.values.all { it == 0 }, "Instagram targets are read as single nodes")
        assertEquals(emptyMap(), engine(setOf(SurfaceId.INSTAGRAM_REELS)).coverIdsFor(IG))
        assertEquals(emptyMap(), engine(all, on = false).coverIdsFor(IG))
        assertEquals(emptyMap(), engine(all).coverIdsFor("com.unknown.app"))
    }

    @Test fun `youtube needs the pivot bar subtree`() {
        val yt = engine(all).coverIdsFor(Trees.YT)
        assertTrue(Trees.yt("pivot_bar") in yt.keys)
        assertTrue(yt.getValue(Trees.yt("pivot_bar")) >= 3, "the Shorts label sits a few levels below the bar")
    }

    @Test fun `covers computed from a flat targeted tree match the full tree`() {
        // The fast path builds only the target nodes plus up to three ancestors, so adapters must still place covers.
        val cfg = DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_REELS_TAB))
        val wrapper = n(null, 216, 2200, 432, 2400, clickable = true, kids = listOf(n(Trees.ig("clips_tab"), 250, 2230, 400, 2370)))
        val flat = InstagramAdapter().covers(snapshot(IG, 0, wrapper), cfg)
        val full = InstagramAdapter().covers(snapshot(IG, 0, Trees.igTabBar()), cfg)
        assertEquals(full.single().bounds, flat.single().bounds)
    }
}

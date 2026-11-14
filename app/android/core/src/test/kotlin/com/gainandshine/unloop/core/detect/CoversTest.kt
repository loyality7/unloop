package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.clock.FakeClock
import com.gainandshine.unloop.core.detect.instagram.InstagramAdapter
import com.gainandshine.unloop.core.detect.instagram.InstagramIds
import com.gainandshine.unloop.core.detect.youtube.YoutubeAdapter
import com.gainandshine.unloop.core.detect.youtube.YoutubeIds
import com.gainandshine.unloop.core.engine.ClassifierContext
import com.gainandshine.unloop.core.engine.DetectionEngine
import com.gainandshine.unloop.core.engine.EngineInputs
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val IG = InstagramIds.PKG
private const val YT = YoutubeIds.PKG

private fun cfg(vararg on: String) = DetectionConfig(enabled = on.toSet())

/** Bottom tab bar of an Instagram-like screen: five tabs across the bottom 150 px. */
private fun igTabBar() = n(
    InstagramIds.TAB_BAR, 0, 2250, 1080, 2400,
    kids = listOf(
        n(InstagramIds.CLIPS_TAB, 432, 2250, 648, 2400),
        n(InstagramIds.SEARCH_TAB, 216, 2250, 432, 2400),
    ),
)

class InstagramCoversTest {
    private val ig = InstagramAdapter()

    @Test fun reelsTabGetsCoverWithItsOwnBounds() {
        val c = ig.covers(snapshot(IG, 0, igTabBar()), cfg(SurfaceId.INSTAGRAM_REELS_TAB))
        assertEquals(listOf(CoverRect(SurfaceId.INSTAGRAM_REELS_TAB, "reels_tab", Rect(432, 2250, 648, 2400))), c)
    }

    @Test fun exploreTabGetsCover() {
        val c = ig.covers(snapshot(IG, 0, igTabBar()), cfg(SurfaceId.INSTAGRAM_EXPLORE_TAB))
        assertEquals(listOf(CoverRect(SurfaceId.INSTAGRAM_EXPLORE_TAB, "explore_tab", Rect(216, 2250, 432, 2400))), c)
    }

    @Test fun onlyEnabledSurfacesAreCovered() {
        val s = snapshot(IG, 0, igTabBar())
        assertEquals(1, ig.covers(s, cfg(SurfaceId.INSTAGRAM_REELS_TAB)).size)
        assertEquals(2, ig.covers(s, cfg(SurfaceId.INSTAGRAM_REELS_TAB, SurfaceId.INSTAGRAM_EXPLORE_TAB)).size)
        assertTrue(ig.covers(s, cfg()).isEmpty())
        // blocking Reels alone does not hide any tab
        assertTrue(ig.covers(s, cfg(SurfaceId.INSTAGRAM_REELS)).isEmpty())
    }

    @Test fun renamedIdMeansNoCoverFailOpen() {
        val renamed = n(
            "$IG:id/tab_bar", 0, 2250, 1080, 2400,
            kids = listOf(n("$IG:id/clips_tab_v2", 432, 2250, 648, 2400)),
        )
        assertTrue(ig.covers(snapshot(IG, 0, renamed), cfg(SurfaceId.INSTAGRAM_REELS_TAB)).isEmpty())
    }

    @Test fun clickableAncestorUpToTwiceTheSizeIsCovered() {
        val tab = n(InstagramIds.CLIPS_TAB, 460, 2270, 620, 2380)
        val wrapper = n(null, 432, 2250, 648, 2400, clickable = true, kids = listOf(tab))
        val c = ig.covers(snapshot(IG, 0, n(InstagramIds.TAB_BAR, 0, 2250, 1080, 2400, kids = listOf(wrapper))), cfg(SurfaceId.INSTAGRAM_REELS_TAB))
        assertEquals(Rect(432, 2250, 648, 2400), c.single().bounds)
    }

    @Test fun oversizedClickableAncestorIsIgnored() {
        val tab = n(InstagramIds.CLIPS_TAB, 460, 2270, 620, 2380)
        val wholeBar = n(null, 0, 2250, 1080, 2400, clickable = true, kids = listOf(tab))
        val c = ig.covers(snapshot(IG, 0, wholeBar), cfg(SurfaceId.INSTAGRAM_REELS_TAB))
        assertEquals(Rect(460, 2270, 620, 2380), c.single().bounds)
    }

    @Test fun invisibleNodeIsNotCovered() {
        val s = snapshot(IG, 0, n(InstagramIds.CLIPS_TAB, 432, 2250, 648, 2400, visible = false))
        assertTrue(ig.covers(s, cfg(SurfaceId.INSTAGRAM_REELS_TAB)).isEmpty())
    }

    @Test fun storiesRowCoveredWhileVisibleAndGoneWhenScrolledAway() {
        val on = snapshot(IG, 0, n(InstagramIds.STORIES_TRAY, 0, 200, 1080, 420))
        assertEquals(Rect(0, 200, 1080, 420), ig.covers(on, cfg(SurfaceId.INSTAGRAM_STORIES)).single().bounds)
        val off = snapshot(IG, 100, n(InstagramIds.STORIES_TRAY, 0, 200, 1080, 420, visible = false))
        assertTrue(ig.covers(off, cfg(SurfaceId.INSTAGRAM_STORIES)).isEmpty())
    }

    @Test fun coverFollowsTheNodeWhenItMoves() {
        val a = ig.covers(snapshot(IG, 0, n(InstagramIds.STORIES_TRAY, 0, 200, 1080, 420)), cfg(SurfaceId.INSTAGRAM_STORIES)).single()
        val b = ig.covers(snapshot(IG, 100, n(InstagramIds.STORIES_TRAY, 0, 120, 1080, 340)), cfg(SurfaceId.INSTAGRAM_STORIES)).single()
        assertEquals(Rect(0, 120, 1080, 340), b.bounds)
        assertTrue(a.bounds != b.bounds)
    }

    @Test fun caughtUpCoverRunsFromMarkerToTopOfTabBar() {
        val s = snapshot(IG, 0, n(InstagramIds.CAUGHT_UP, 0, 900, 1080, 1100), igTabBar())
        val c = ig.covers(s, cfg(SurfaceId.INSTAGRAM_CAUGHT_UP)).single()
        assertEquals(Rect(0, 900, 1080, 2250), c.bounds)
    }

    @Test fun caughtUpNeedsTabBarAndMarkerAboveIt() {
        val noBar = snapshot(IG, 0, n(InstagramIds.CAUGHT_UP, 0, 900, 1080, 1100))
        assertTrue(ig.covers(noBar, cfg(SurfaceId.INSTAGRAM_CAUGHT_UP)).isEmpty())
        val below = snapshot(IG, 0, n(InstagramIds.CAUGHT_UP, 0, 2300, 1080, 2400), igTabBar())
        assertTrue(ig.covers(below, cfg(SurfaceId.INSTAGRAM_CAUGHT_UP)).isEmpty())
    }

    @Test fun coverIsClippedToTheWindow() {
        val s = snapshot(IG, 0, n(InstagramIds.STORIES_TRAY, -50, 200, 1200, 420))
        assertEquals(Rect(0, 200, 1080, 420), ig.covers(s, cfg(SurfaceId.INSTAGRAM_STORIES)).single().bounds)
    }

    @Test fun otherPackagesGetNothingAndInputIsNotMutated() {
        val s = snapshot(IG, 0, igTabBar())
        assertTrue(ig.covers(snapshot("com.other", 0, igTabBar()), cfg(SurfaceId.INSTAGRAM_REELS_TAB)).isEmpty())
        val before = s.toString()
        ig.covers(s, cfg(SurfaceId.INSTAGRAM_REELS_TAB, SurfaceId.INSTAGRAM_EXPLORE_TAB))
        assertEquals(before, s.toString())
    }

    @Test fun reelsBlockingStillWorksWhenTabCoverIsOffOrIdsMoved() {
        // Throw-back: even with no cover (ids changed, surface off), the viewer container still triggers the Back ladder.
        val viewer = n("$IG:id/clips_viewer_view_pager")
        val v = ig.evaluate(snapshot(IG, 0, viewer), cfg(SurfaceId.INSTAGRAM_REELS))
        assertTrue(v is Verdict.Block)
    }
}

class YoutubeCoversTest {
    private val yt = YoutubeAdapter()

    private fun bar(vararg tabs: com.gainandshine.unloop.core.model.NodeSnap) = n(YoutubeIds.PIVOT_BAR, 0, 2250, 1080, 2400, kids = tabs.toList())

    @Test fun shortsTabByTextIsCovered() {
        val tab = n(null, 216, 2250, 432, 2400, clickable = true, kids = listOf(n(null, 250, 2280, 400, 2380, text = "Shorts")))
        val c = yt.covers(snapshot(YT, 0, bar(tab)), cfg(SurfaceId.YOUTUBE_SHORTS_TAB)).single()
        assertEquals(CoverRect(SurfaceId.YOUTUBE_SHORTS_TAB, "shorts_tab", Rect(216, 2250, 432, 2400)), c)
    }

    @Test fun shortsTabByDescriptionWithTabSuffixIsCovered() {
        val tab = n(null, 216, 2250, 432, 2400, clickable = true, desc = "Shorts, tab 2 of 5")
        assertEquals(1, yt.covers(snapshot(YT, 0, bar(tab)), cfg(SurfaceId.YOUTUBE_SHORTS_TAB)).size)
    }

    @Test fun otherTabsAreNotCovered() {
        val home = n(null, 0, 2250, 216, 2400, clickable = true, text = "Home")
        val subs = n(null, 432, 2250, 648, 2400, clickable = true, desc = "Subscriptions, tab 3 of 5")
        assertTrue(yt.covers(snapshot(YT, 0, bar(home, subs)), cfg(SurfaceId.YOUTUBE_SHORTS_TAB)).isEmpty())
    }

    @Test fun offWithoutBarOrInOtherPackageGivesNothing() {
        val tab = n(null, 216, 2250, 432, 2400, clickable = true, text = "Shorts")
        assertTrue(yt.covers(snapshot(YT, 0, bar(tab)), cfg(SurfaceId.YOUTUBE_SHORTS)).isEmpty())
        assertTrue(yt.covers(snapshot(YT, 0, tab), cfg(SurfaceId.YOUTUBE_SHORTS_TAB)).isEmpty())
        assertTrue(yt.covers(snapshot("com.other", 0, bar(tab)), cfg(SurfaceId.YOUTUBE_SHORTS_TAB)).isEmpty())
    }

    @Test fun coverNeverLeavesThePivotBar() {
        val huge = n(null, 0, 0, 1080, 2400, clickable = true, kids = listOf(n(null, 250, 2280, 400, 2380, text = "Shorts")))
        val c = yt.covers(snapshot(YT, 0, bar(huge)), cfg(SurfaceId.YOUTUBE_SHORTS_TAB))
        c.forEach { assertTrue(Rect(0, 2250, 1080, 2400).contains(it.bounds)) }
    }
}

class EngineCoversTest {
    private val clock = FakeClock()
    private fun engine(enabled: Boolean, on: Set<String>) = DetectionEngine(
        adapters = listOf(InstagramAdapter(), YoutubeAdapter()),
        classifier = ClassifierContext(ownPackage = "com.gainandshine.unloop"),
        clock = clock,
        inputs = EngineInputs(enabled = enabled, config = DetectionConfig(enabled = on)),
    )

    private val snap = snapshot(IG, 0, igTabBar())

    @Test fun planCoversReturnsCoversWhenEnabledAndGated() {
        val e = engine(true, setOf(SurfaceId.INSTAGRAM_REELS_TAB))
        assertEquals(1, e.planCovers(snap).size)
    }

    @Test fun masterSwitchOffOrScreenLockedMeansNoCovers() {
        assertTrue(engine(false, setOf(SurfaceId.INSTAGRAM_REELS_TAB)).planCovers(snap).isEmpty())
        val locked = DetectionEngine(
            adapters = listOf(InstagramAdapter()),
            classifier = ClassifierContext(ownPackage = "com.gainandshine.unloop"),
            clock = clock,
            inputs = EngineInputs(enabled = true, interactiveUnlocked = false, config = DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_REELS_TAB))),
        )
        assertTrue(locked.planCovers(snap).isEmpty())
    }

    @Test fun untrackedPackageHasNoCovers() {
        val e = engine(true, setOf(SurfaceId.INSTAGRAM_REELS_TAB))
        assertTrue(e.planCovers(snapshot("com.other", 0, igTabBar())).isEmpty())
    }
}

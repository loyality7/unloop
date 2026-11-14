package com.gainandshine.unloop.core.detect.tiktok

import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TikTokAdapterTest {
    private val pkg = "com.zhiliaoapp.musically"
    private val fyp = DetectionConfig(enabled = setOf(SurfaceId.TIKTOK_FOR_YOU))

    private fun top(label: String, slot: Int, selected: Boolean = false) =
        n(desc = label, left = 100 + slot * 160, top = 100, right = 250 + slot * 160, bottom = 180, selected = selected)

    private fun bottom(label: String, slot: Int, selected: Boolean = false) =
        n(desc = label, left = slot * 200, top = 2250, right = slot * 200 + 180, bottom = 2400, selected = selected)

    private val pager = n(cls = "androidx.viewpager.widget.ViewPager", left = 0, top = 0, right = 1080, bottom = 2100)

    private fun frame(atMs: Long, vararg kids: NodeSnap, p: String = pkg) = snapshot(p, atMs, *kids)

    private fun scroll(atMs: Long, dy: Int, dx: Int = 0) =
        EventRecord(EventType.VIEW_SCROLLED, pkg, null, scrollDx = dx, scrollDy = dy, atMs = atMs)

    private fun block(v: Verdict) = assertIs<Verdict.Block>(v)

    @Test
    fun blocksSelectedForYouAndBuildsLadder() {
        val a = TikTokAdapter()
        val v = block(a.evaluate(frame(0, pager, top("For You", 0, true), top("Following", 1), bottom("Inbox", 3)), fyp))
        assertEquals(SurfaceId.TIKTOK_FOR_YOU, v.surface)
        assertEquals(4, v.ladder.size)
        assertEquals("FOLLOWING", assertIs<Action.ClickNode>(v.ladder[0]).label)
        assertEquals("INBOX", assertIs<Action.ClickNode>(v.ladder[1]).label)
        assertEquals(Action.Back, v.ladder[2])
        assertEquals(Action.Home, v.ladder[3])
    }

    @Test
    fun ladderSkipsBlockedFeedTabsAndFallsBackToBackHome() {
        val a = TikTokAdapter()
        val cfg = DetectionConfig(enabled = setOf(SurfaceId.TIKTOK_FOR_YOU, SurfaceId.TIKTOK_FOLLOWING))
        val v = block(a.evaluate(frame(0, top("For You", 0, true), top("Following", 1), top("Friends", 2)), cfg))
        assertEquals("FRIENDS", assertIs<Action.ClickNode>(v.ladder[0]).label)
        val bare = block(a.evaluate(frame(10, top("For You", 0, true)), fyp))
        assertEquals(listOf(Action.Back, Action.Home), bare.ladder)
    }

    @Test
    fun followingNotBlockedWhenDisabled() {
        val a = TikTokAdapter()
        assertEquals(Verdict.None, a.evaluate(frame(0, pager, top("For You", 0), top("Following", 1, true)), fyp))
        val both = DetectionConfig(enabled = setOf(SurfaceId.TIKTOK_FOLLOWING))
        assertEquals(SurfaceId.TIKTOK_FOLLOWING, block(a.evaluate(frame(1, pager, top("For You", 0), top("Following", 1, true)), both)).surface)
    }

    @Test
    fun everyFeedTabMapsToItsSurface() {
        val cases = mapOf(
            "For You" to SurfaceId.TIKTOK_FOR_YOU, "Following" to SurfaceId.TIKTOK_FOLLOWING,
            "Friends" to SurfaceId.TIKTOK_FRIENDS, "Local" to SurfaceId.TIKTOK_LOCAL,
            "Community" to SurfaceId.TIKTOK_COMMUNITY, "STEM" to SurfaceId.TIKTOK_STEM, "LIVE" to SurfaceId.TIKTOK_LIVE,
        )
        for ((label, surface) in cases) {
            val v = TikTokAdapter().evaluate(frame(0, top(label, 0, true)), DetectionConfig(enabled = setOf(surface)))
            assertEquals(surface, block(v).surface, label)
        }
        val shop = TikTokAdapter().evaluate(frame(0, bottom("Shop", 1, true)), DetectionConfig(enabled = setOf(SurfaceId.TIKTOK_SHOP)))
        assertEquals(SurfaceId.TIKTOK_SHOP, block(shop).surface)
    }

    @Test
    fun localeVariants() {
        val cases = listOf("Para ti", "Pour toi", "Für dich", "Per te", "Para você", "おすすめ", "لك")
        for (label in cases) {
            assertEquals(SurfaceId.TIKTOK_FOR_YOU, block(TikTokAdapter().evaluate(frame(0, top(label, 0, true)), fyp)).surface, label)
        }
        val follow = DetectionConfig(enabled = setOf(SurfaceId.TIKTOK_FOLLOWING))
        for (label in listOf("Siguiendo", "Abonnements", "Folge ich", "Seguiti", "フォロー中", "أتابعه")) {
            assertEquals(SurfaceId.TIKTOK_FOLLOWING, block(TikTokAdapter().evaluate(frame(0, top(label, 0, true)), follow)).surface, label)
        }
        val inbox = TikTokAdapter().evaluate(frame(0, top("Pour toi", 0, true), bottom("Boîte de réception", 3)), fyp)
        assertEquals("INBOX", assertIs<Action.ClickNode>(block(inbox).ladder[0]).label)
    }

    @Test
    fun suffixStripping() {
        assertEquals(TikTokTab.FOR_YOU, TikTokLabels.classify("For You, tab 1 of 5"))
        assertEquals(TikTokTab.FOLLOWING, TikTokLabels.classify("Following, not selected"))
        assertEquals(TikTokTab.FOLLOWING, TikTokLabels.classify("following, tab 2 of 5, selected"))
        assertEquals(TikTokTab.FOR_YOU, TikTokLabels.classify("Para ti, pestana 1 de 5"))
        assertEquals(TikTokTab.UNKNOWN, TikTokLabels.classify("Share this video"))
        assertTrue(TikTokLabels.labelSaysSelected("For You, tab 1 of 5, selected"))
        assertTrue(!TikTokLabels.labelSaysSelected("For You, not selected"))
        // selection carried only by the suffix, not the flag
        val v = TikTokAdapter().evaluate(
            frame(0, top("For You, tab 1 of 5, selected", 0), top("Following, tab 2 of 5, not selected", 1)),
            DetectionConfig(enabled = setOf(SurfaceId.TIKTOK_FOR_YOU, SurfaceId.TIKTOK_FOLLOWING)),
        )
        assertEquals(SurfaceId.TIKTOK_FOR_YOU, block(v).surface)
    }

    @Test
    fun labelsOutsideTheTabStripAreIgnored() {
        val caption = n(text = "For You", left = 100, top = 1000, right = 400, bottom = 1100, selected = true)
        assertEquals(Verdict.None, TikTokAdapter().evaluate(frame(0, caption), fyp))
        val tallBanner = n(desc = "For You", left = 0, top = 0, right = 1080, bottom = 1200, selected = true)
        assertEquals(Verdict.None, TikTokAdapter().evaluate(frame(0, tallBanner), fyp))
    }

    @Test
    fun inboxProfileAndHomeAreNeverBlocked() {
        val a = TikTokAdapter()
        assertEquals(Verdict.None, a.evaluate(frame(0, bottom("Inbox", 3, true), bottom("Home", 0)), fyp))
        assertEquals(Verdict.None, a.evaluate(frame(1, bottom("Home", 0, true)), fyp))
        assertEquals(Verdict.None, a.evaluate(frame(2, bottom("Profile", 4, true)), fyp))
    }

    @Test
    fun stickyTabSurvivesUnknownFramesUntilExpiry() {
        for ((dt, expectBlock) in listOf(1999L to true, 2000L to true, 2001L to false)) {
            val a = TikTokAdapter()
            block(a.evaluate(frame(1000, pager, top("For You", 0, true)), fyp))
            val v = a.evaluate(frame(1000 + dt, pager), fyp)
            if (expectBlock) assertEquals(SurfaceId.TIKTOK_FOR_YOU, block(v).surface, "dt=$dt") else assertEquals(Verdict.None, v, "dt=$dt")
        }
    }

    @Test
    fun stickyNeedsPagerAndIsClearedByNonPagerFrame() {
        val a = TikTokAdapter()
        block(a.evaluate(frame(0, pager, top("For You", 0, true)), fyp))
        assertEquals(Verdict.None, a.evaluate(frame(100), fyp))
        assertEquals(Verdict.None, a.evaluate(frame(200, pager), fyp))
        // a small view is not the feed pager
        val b = TikTokAdapter()
        block(b.evaluate(frame(0, pager, top("For You", 0, true)), fyp))
        val small = n(cls = "androidx.viewpager.widget.ViewPager", left = 0, top = 0, right = 1080, bottom = 1000)
        assertEquals(Verdict.None, b.evaluate(frame(100, small), fyp))
    }

    @Test
    fun dmVideoAllowedForFirstUserBlockedOnChange() {
        val a = TikTokAdapter()
        fun dm(t: Long, user: String) = frame(t, pager, n(desc = "Back to chat", left = 0, top = 100, right = 200, bottom = 200), n(text = user, left = 0, top = 1800, right = 300, bottom = 1850))
        assertEquals(Verdict.None, a.evaluate(dm(0, "@alice"), fyp))
        assertEquals(Verdict.None, a.evaluate(dm(500, "@alice"), fyp))
        val v = block(a.evaluate(dm(900, "@bob"), fyp))
        assertEquals("dm_video_user_changed", v.reason)
        assertEquals(listOf(Action.Back, Action.Home), v.ladder)
        // leaving the DM viewer forgets the first user
        assertEquals(Verdict.None, a.evaluate(frame(1000, bottom("Home", 0, true)), fyp))
        assertEquals(Verdict.None, a.evaluate(dm(1100, "@bob"), fyp))
        val marker = frame(1200, pager, n(desc = "Reply privately", left = 0, top = 100, right = 200, bottom = 200), n(text = "@carol"))
        assertEquals("dm_video_user_changed", block(a.evaluate(marker, fyp)).reason)
        assertEquals(Verdict.None, TikTokAdapter().evaluate(marker, fyp))
    }

    @Test
    fun dmVideoBlockedWhenSharedItemsNotAllowed() {
        val cfg = DetectionConfig(enabled = setOf(SurfaceId.TIKTOK_FOR_YOU), allowSharedItem = false)
        val s = frame(0, pager, n(desc = "Back to chat", left = 0, top = 100, right = 200, bottom = 200))
        assertEquals("dm_video_not_allowed", block(TikTokAdapter().evaluate(s, cfg)).reason)
        // a tiny marker node does not count
        val tiny = frame(0, pager, n(desc = "Back to chat", left = 0, top = 100, right = 5, bottom = 5))
        assertEquals(Verdict.None, TikTokAdapter().evaluate(tiny, fyp))
        // usernames that are invalid are not tracked
        val bad = TikTokAdapter()
        val m = n(desc = "Back to chat", left = 0, top = 100, right = 200, bottom = 200)
        assertEquals(Verdict.None, bad.evaluate(frame(0, pager, m, n(text = "@a b")), fyp))
        assertEquals(Verdict.None, bad.evaluate(frame(1, pager, m, n(text = "@x y")), fyp))
    }

    @Test
    fun linkOpenedVideoAllowedOneSwipe() {
        val a = TikTokAdapter()
        assertEquals(Verdict.None, a.evaluate(frame(0, pager), fyp))
        a.onEvent(scroll(100, 100, dx = 500)) // horizontal, ignored
        assertEquals(Verdict.None, a.evaluate(frame(400, pager), fyp))
        a.onEvent(scroll(500, 200))
        a.onEvent(scroll(800, 100)) // 300 < 15% of 2100 = 315
        assertEquals(Verdict.None, a.evaluate(frame(850, pager), fyp))
        a.onEvent(scroll(900, 20))
        val v = block(a.evaluate(frame(950, pager), fyp))
        assertEquals("link_video_second", v.reason)
        // still blocked on the next frame (engine may retry the ladder)
        assertIs<Verdict.Block>(a.evaluate(frame(1000, pager), fyp))
    }

    @Test
    fun linkSwipeBoundary() {
        for ((dy, expectBlock) in listOf(314 to false, 315 to true)) {
            val a = TikTokAdapter()
            a.evaluate(frame(0, pager), fyp)
            a.onEvent(scroll(100, dy))
            val v = a.evaluate(frame(200, pager), fyp)
            if (expectBlock) assertIs<Verdict.Block>(v, "dy=$dy") else assertEquals(Verdict.None, v, "dy=$dy")
        }
    }

    @Test
    fun linkScrollsFarApartDoNotAccumulateAndEventsBeforeTrackingAreIgnored() {
        val a = TikTokAdapter()
        a.onEvent(scroll(0, 2000)) // no tracking yet
        assertEquals(Verdict.None, a.evaluate(frame(10, pager), fyp))
        a.onEvent(scroll(500, 200))
        assertEquals(Verdict.None, a.evaluate(frame(1500, pager), fyp))
        a.onEvent(scroll(1600, 200)) // gap 1100 ms: cumulative restarts
        assertEquals(Verdict.None, a.evaluate(frame(1700, pager), fyp))
    }

    @Test
    fun linkTrackerForgetsAfterPlayerGoneAndAfterReset() {
        val a = TikTokAdapter()
        a.evaluate(frame(0, pager), fyp)
        a.onEvent(scroll(100, 400))
        assertIs<Verdict.Block>(a.evaluate(frame(200, pager), fyp))
        assertEquals(Verdict.None, a.evaluate(frame(1500, n(desc = "x", left = 0, top = 0, right = 10, bottom = 10)), fyp))
        assertEquals(Verdict.None, a.evaluate(frame(1600, pager), fyp))
        a.onEvent(scroll(1700, 400))
        assertIs<Verdict.Block>(a.evaluate(frame(1800, pager), fyp))
        a.reset()
        assertEquals(Verdict.None, a.evaluate(frame(1900, pager), fyp))
    }

    @Test
    fun linkVideoBlockedImmediatelyWhenSharedItemsNotAllowed() {
        val cfg = DetectionConfig(enabled = setOf(SurfaceId.TIKTOK_FOR_YOU), allowSharedItem = false)
        assertEquals("link_video_not_allowed", block(TikTokAdapter().evaluate(frame(0, pager), cfg)).reason)
    }

    @Test
    fun resetClearsStickyAndDmState() {
        val a = TikTokAdapter()
        block(a.evaluate(frame(0, pager, top("For You", 0, true)), fyp))
        a.reset()
        assertEquals(Verdict.None, a.evaluate(frame(100, pager), fyp))
        val m = n(desc = "Back to chat", left = 0, top = 100, right = 200, bottom = 200)
        a.evaluate(frame(200, pager, m, n(text = "@alice")), fyp)
        a.reset()
        assertEquals(Verdict.None, a.evaluate(frame(300, pager, m, n(text = "@bob")), fyp))
    }

    @Test
    fun otherPackageAndNoEnabledSurfaceAreIgnoredAndTrillWorks() {
        val s = frame(0, top("For You", 0, true))
        assertEquals(Verdict.None, TikTokAdapter().evaluate(frame(0, top("For You", 0, true), p = "com.other"), fyp))
        assertEquals(Verdict.None, TikTokAdapter().evaluate(s, DetectionConfig(enabled = emptySet())))
        assertIs<Verdict.Block>(TikTokAdapter().evaluate(frame(0, top("For You", 0, true), p = "com.ss.android.ugc.trill"), fyp))
    }

    @Test
    fun evaluateDoesNotMutateInput() {
        val s = frame(0, pager, top("For You", 0, true), bottom("Inbox", 3))
        val before = s.toString()
        TikTokAdapter().evaluate(s, fyp)
        assertEquals(before, s.toString())
    }
}

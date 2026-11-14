package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.detect.Trees.IG
import com.gainandshine.unloop.core.detect.facebook.FacebookAdapter
import com.gainandshine.unloop.core.detect.instagram.InstagramAdapter
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstagramMessagesOnlyTest {
    private val on = DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_DMS_ONLY))
    private fun eval(vararg kids: NodeSnap, cfg: DetectionConfig = on) =
        InstagramAdapter().evaluate(snapshot(IG, 0, *kids), cfg)

    private fun tabBar(dmSelected: Boolean): NodeSnap = n(
        Trees.ig("tab_bar"), 0, 2200, 1080, 2400,
        kids = listOf(
            n(Trees.ig("feed_tab"), 0, 2200, 216, 2400, clickable = true),
            n(null, 432, 2200, 648, 2400, clickable = true, kids = listOf(n(Trees.ig("direct_tab"), 450, 2210, 630, 2390, selected = dmSelected))),
        ),
    )

    @Test fun `feed with tab bar clicks the messages tab`() {
        val v = assertIs<Verdict.Block>(eval(Trees.igFeed(), tabBar(false)))
        assertEquals(SurfaceId.INSTAGRAM_DMS_ONLY, v.surface)
        assertEquals(1, v.ladder.size)
        assertIs<Action.ClickNode>(v.ladder[0])
    }

    @Test fun `inbox header or open thread is allowed`() {
        assertEquals(Verdict.None, eval(n(Trees.ig("direct_inbox_action_bar"), 0, 100, 1080, 250), tabBar(false)))
        assertEquals(Verdict.None, eval(n(Trees.ig("row_thread_composer_edittext"), 0, 2100, 1080, 2200), tabBar(false)))
    }

    @Test fun `messages tab already selected is allowed`() {
        assertEquals(Verdict.None, eval(Trees.igFeed(), tabBar(true)))
    }

    @Test fun `no tab bar means no action so login and full screen viewers are never trapped`() {
        assertEquals(Verdict.None, eval(Trees.igFeed()))
        assertEquals(Verdict.None, eval(Trees.igStory()))
    }

    @Test fun `mode off does nothing`() {
        assertEquals(Verdict.None, eval(Trees.igFeed(), tabBar(false), cfg = DetectionConfig(enabled = emptySet())))
    }

    @Test fun `reels still blocked when only reels is on`() {
        val v = eval(Trees.igViewer(), cfg = DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_REELS)))
        assertEquals(SurfaceId.INSTAGRAM_REELS, assertIs<Verdict.Block>(v).surface)
    }
}

class FacebookModesTest {
    private val pkg = "com.facebook.katana"
    private fun tab(desc: String, slot: Int, selected: Boolean) =
        n(desc = desc, left = slot * 180, top = 2250, right = slot * 180 + 170, bottom = 2400, selected = selected)

    private fun bar(labels: List<String>, selectedIdx: Int?) = labels.mapIndexed { i, l -> tab(l, i, i == selectedIdx) }

    private val market = listOf("Home, tab 1 of 5", "Friends, tab 2 of 5", "Marketplace, tab 3 of 5", "Notifications, tab 4 of 5", "Menu, tab 5 of 5")
    private val groups = listOf("Home, tab 1 of 5", "Friends, tab 2 of 5", "Groups, tab 3 of 5", "Notifications, tab 4 of 5", "Menu, tab 5 of 5")

    private fun cfg(vararg s: String) = DetectionConfig(enabled = s.toSet())
    private fun eval(c: DetectionConfig, vararg kids: NodeSnap) = FacebookAdapter().evaluate(snapshot(pkg, 0, *kids), c)

    @Test fun `marketplace only taps the marketplace tab from another tab`() {
        val v = assertIs<Verdict.Block>(eval(cfg(SurfaceId.FACEBOOK_MARKETPLACE_ONLY), *bar(market, 0).toTypedArray()))
        assertEquals(SurfaceId.FACEBOOK_MARKETPLACE_ONLY, v.surface)
        val tap = assertIs<Action.TapNode>(v.ladder[0])
        assertEquals(2 * 180, tap.bounds.left)
        assertIs<Action.ClickNode>(v.ladder[1])
    }

    @Test fun `marketplace only is quiet on marketplace, with unknown selection, or without a tab bar`() {
        val c = cfg(SurfaceId.FACEBOOK_MARKETPLACE_ONLY)
        assertEquals(Verdict.None, eval(c, *bar(market, 2).toTypedArray()))
        assertEquals(Verdict.None, eval(c, *bar(market, null).toTypedArray()))
        assertEquals(Verdict.None, eval(c))
    }

    @Test fun `reels blocking still works with marketplace mode off`() {
        val reelsTab = listOf("Home, tab 1 of 5", "Friends, tab 2 of 5", "Reels, tab 3 of 5", "Notifications, tab 4 of 5", "Menu, tab 5 of 5")
        val v = eval(cfg(SurfaceId.FACEBOOK_REELS), *bar(reelsTab, 2).toTypedArray())
        assertEquals(SurfaceId.FACEBOOK_REELS, assertIs<Verdict.Block>(v).surface)
    }

    private fun subs(selected: Int?, count: Int = 3): List<NodeSnap> {
        val labels = listOf("For you", "Your groups", "Discover").take(count)
        return labels.mapIndexed { i, l -> n(text = l, left = 40 + i * 300, top = 300, right = 300 + i * 300, bottom = 380, selected = i == selected) }
    }

    @Test fun `groups mode steers from for you and discover to your groups`() {
        val c = cfg(SurfaceId.FACEBOOK_GROUPS_FEED)
        for (sel in listOf(0, 2)) {
            val v = assertIs<Verdict.Block>(eval(c, *bar(groups, 2).toTypedArray(), *subs(sel).toTypedArray()))
            assertEquals(SurfaceId.FACEBOOK_GROUPS_FEED, v.surface)
            assertEquals(340, assertIs<Action.TapNode>(v.ladder[0]).bounds.left)
        }
    }

    @Test fun `groups mode leaves your groups, other tabs and incomplete sub tab rows alone`() {
        val c = cfg(SurfaceId.FACEBOOK_GROUPS_FEED)
        assertEquals(Verdict.None, eval(c, *bar(groups, 2).toTypedArray(), *subs(1).toTypedArray()))
        assertEquals(Verdict.None, eval(c, *bar(groups, 0).toTypedArray(), *subs(0).toTypedArray()))
        assertEquals(Verdict.None, eval(c, *bar(groups, 2).toTypedArray(), *subs(0, count = 2).toTypedArray()))
        assertEquals(Verdict.None, eval(c, *bar(groups, 2).toTypedArray(), *subs(null).toTypedArray()))
    }

    @Test fun `all new modes are advertised as surfaces`() {
        val s = FacebookAdapter().surfaces
        assertTrue(SurfaceId.FACEBOOK_MARKETPLACE_ONLY in s && SurfaceId.FACEBOOK_GROUPS_FEED in s)
        assertTrue(SurfaceId.INSTAGRAM_DMS_ONLY in InstagramAdapter().surfaces)
    }
}

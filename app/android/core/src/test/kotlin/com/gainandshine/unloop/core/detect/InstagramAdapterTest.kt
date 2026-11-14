package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.detect.Trees.IG
import com.gainandshine.unloop.core.detect.instagram.InstagramAdapter
import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.SCREEN
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.core.model.WindowSnap
import com.gainandshine.unloop.core.model.at
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstagramAdapterTest {
    private val cfg = DetectionConfig()
    private val a = InstagramAdapter()

    private fun snap(at: Long, vararg kids: NodeSnap) = snapshot(IG, at, *kids)
    private fun InstagramAdapter.v(at: Long, vararg kids: NodeSnap, c: DetectionConfig = cfg) = evaluate(snap(at, *kids), c)
    private fun block(v: Verdict) = assertIs<Verdict.Block>(v)

    @Test
    fun `reels viewer blocks with back then home`() {
        val b = block(a.v(0, Trees.igViewer(), Trees.igTabBar()))
        assertEquals(SurfaceId.INSTAGRAM_REELS, b.surface)
        assertEquals(listOf(Action.Back, Action.Home), b.ladder)
    }

    @Test
    fun `each viewer container id blocks`() {
        for (id in listOf("clips_viewer_view_pager", "clips_viewer_container", "clips_viewer_video_layout")) {
            block(InstagramAdapter().v(0, Trees.igViewer(container = id)))
        }
    }

    @Test
    fun `feed and story viewer do not block`() {
        assertEquals(Verdict.None, a.v(0, Trees.igFeed(), Trees.igTabBar()))
        assertEquals(Verdict.None, a.v(10, Trees.igStory()))
    }

    @Test
    fun `prominence boundaries exactly half versus just over`() {
        val cases = listOf(
            Triple(540, 2400, false), Triple(541, 2400, true),
            Triple(1080, 1200, false), Triple(1080, 1201, true),
        )
        for ((w, h, blocked) in cases) {
            val v = InstagramAdapter().v(0, Trees.igViewer(right = w, bottom = h))
            assertEquals(blocked, v is Verdict.Block, "w=$w h=$h")
        }
    }

    @Test
    fun `invisible viewer does not block`() {
        assertEquals(Verdict.None, a.v(0, Trees.igViewer().copy(visible = false)))
    }

    @Test
    fun `prominence is relative to the window not the screen`() {
        val win = Rect(0, 0, 1080, 1200)
        fun snapIn(viewerBottom: Int): UiSnapshot {
            val root = NodeSnap(bounds = win, children = listOf(Trees.igViewer(bottom = viewerBottom)))
            return UiSnapshot(0, IG, SCREEN, listOf(WindowSnap(IG, win, root)))
        }
        assertTrue(InstagramAdapter().evaluate(snapIn(601), cfg) is Verdict.Block)
        assertEquals(Verdict.None, InstagramAdapter().evaluate(snapIn(600), cfg))
    }

    @Test
    fun `other package and disabled surface are ignored`() {
        assertEquals(Verdict.None, a.evaluate(snapshot("com.other", 0, Trees.igViewer()), cfg))
        assertEquals(Verdict.None, a.v(0, Trees.igViewer(), c = DetectionConfig(enabled = emptySet())))
    }

    @Test
    fun `selected reels tab clicks messages tab then back then home`() {
        val tree = Trees.igTabBar(clipsSelected = true)
        val s = snap(0, Trees.igFeed(), tree)
        val b = block(a.evaluate(s, cfg))
        assertEquals(3, b.ladder.size)
        val click = assertIs<Action.ClickNode>(b.ladder[0])
        val target = s.windows[0].root.at(click.ref.path)!!
        assertTrue(target.clickable)
        assertEquals(null, target.id)
        assertEquals(target.bounds, click.bounds)
        assertEquals(Action.Back, b.ladder[1])
        assertEquals(Action.Home, b.ladder[2])
    }

    @Test
    fun `reels tab without usable messages tab falls back to back and home`() {
        val noRow = block(a.v(0, Trees.igTabBar(clipsSelected = true, dmTop = 800)))
        assertEquals(listOf(Action.Back, Action.Home), noRow.ladder)
    }

    @Test
    fun `unselected invisible or story-covered reels tab does not block`() {
        assertEquals(Verdict.None, a.v(0, Trees.igTabBar(clipsSelected = false)))
        val hidden = Trees.igTabBar(clipsSelected = true).let { it.copy(children = it.children.map { c -> c.copy(visible = false) }) }
        assertEquals(Verdict.None, a.v(1, hidden))
        assertEquals(Verdict.None, a.v(2, Trees.igStory(), Trees.igTabBar(clipsSelected = true)))
    }

    @Test
    fun `dm opened reel is allowed until author changes`() {
        assertEquals(Verdict.None, a.v(0, Trees.igViewer("a", composer = true)))
        assertEquals(Verdict.None, a.v(100, Trees.igViewer("a", composer = true)))
        block(a.v(200, Trees.igViewer("b", composer = true)))
        block(a.v(300, Trees.igViewer("b", composer = true)))
    }

    @Test
    fun `shared item allowance respects the config switch`() {
        block(a.v(0, Trees.igViewer(composer = true), c = DetectionConfig(allowSharedItem = false)))
    }

    @Test
    fun `reel right after a story is allowed within 1500 ms only`() {
        for ((dt, allowed) in listOf(1_499L to true, 1_500L to true, 1_501L to false)) {
            val ad = InstagramAdapter()
            assertEquals(Verdict.None, ad.v(1_000, Trees.igStory()))
            assertEquals(allowed, ad.v(1_000 + dt, Trees.igViewer()) == Verdict.None, "dt=$dt")
        }
    }

    @Test
    fun `deep link allowance window 4999 5000 5001`() {
        for ((dt, allowed) in listOf(4_999L to true, 5_000L to true, 5_001L to false)) {
            val ad = InstagramAdapter()
            ad.onEvent(Trees.deepLink(0))
            assertEquals(allowed, ad.v(dt, Trees.igViewer()) == Verdict.None, "dt=$dt")
        }
    }

    @Test
    fun `pane changes and foreign packages do not arm a deep link`() {
        val ad = InstagramAdapter()
        ad.onEvent(Trees.ev(EventType.WINDOW_STATE_CHANGED, 0, Trees.DEEP_LINK, contentChangeTypes = 0x10))
        ad.onEvent(Trees.deepLink(0, pkg = "com.other"))
        ad.onEvent(Trees.ev(EventType.WINDOW_CONTENT_CHANGED, 0, Trees.DEEP_LINK))
        block(ad.v(10, Trees.igViewer()))
    }

    @Test
    fun `deep link reel ends after 12 percent swipe of viewer height`() {
        for ((dy, allowed) in listOf(287 to true, 288 to false)) {
            val ad = InstagramAdapter()
            ad.onEvent(Trees.deepLink(0))
            assertEquals(Verdict.None, ad.v(100, Trees.igViewer()))
            ad.onEvent(Trees.scroll(200, -dy))
            assertEquals(allowed, ad.v(300, Trees.igViewer()) == Verdict.None, "dy=$dy")
        }
    }

    @Test
    fun `pager index change ends allowance`() {
        a.onEvent(Trees.deepLink(0))
        assertEquals(Verdict.None, a.v(100, Trees.igViewer()))
        a.onEvent(Trees.scroll(200, 0, from = 4))
        a.onEvent(Trees.scroll(300, 0, from = 5))
        block(a.v(400, Trees.igViewer()))
    }

    @Test
    fun `selected tab wins over any allowance`() {
        a.onEvent(Trees.deepLink(0))
        block(a.v(100, Trees.igViewer(), Trees.igTabBar(clipsSelected = true)))
        block(a.v(200, Trees.igViewer()))
    }

    @Test
    fun `reset drops armed and running allowances`() {
        a.onEvent(Trees.deepLink(0))
        a.reset()
        block(a.v(10, Trees.igViewer()))
        a.onEvent(Trees.deepLink(20))
        assertEquals(Verdict.None, a.v(30, Trees.igViewer()))
        a.reset()
        block(a.v(40, Trees.igViewer()))
    }

    @Test
    fun `evaluate never mutates its input`() {
        fun build() = snap(0, Trees.igViewer("a", composer = true), Trees.igTabBar(clipsSelected = true))
        val used = build()
        a.evaluate(used, cfg)
        a.evaluate(used, cfg)
        assertEquals(build(), used)
    }
}

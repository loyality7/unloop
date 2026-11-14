package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.detect.Trees.YT
import com.gainandshine.unloop.core.detect.shared.CLICK_ANCESTOR_LEVELS
import com.gainandshine.unloop.core.detect.shared.clickableTarget
import com.gainandshine.unloop.core.detect.youtube.YoutubeAdapter
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class YoutubeAdapterTest {
    private val cfg = DetectionConfig()
    private val a = YoutubeAdapter()
    private fun ev(vararg kids: NodeSnap) = a.evaluate(snapshot(YT, 0, *kids), cfg)

    @Test
    fun `each shorts container blocks with back then home`() {
        for (id in listOf("reel_recycler", "reel_player_page_container", "shorts_player_container")) {
            val b = ev(Trees.ytShorts(id)) as Verdict.Block
            assertEquals(SurfaceId.YOUTUBE_SHORTS, b.surface)
            assertEquals(listOf(Action.Back, Action.Home), b.ladder)
        }
    }

    @Test
    fun `prominence boundaries and invisibility`() {
        assertEquals(Verdict.None, ev(Trees.ytShorts(right = 540)))
        assertEquals(Verdict.None, ev(Trees.ytShorts(bottom = 1200)))
        assertEquals(Verdict.None, ev(Trees.ytShorts().copy(visible = false)))
        assertEquals(true, ev(Trees.ytShorts(right = 541)) is Verdict.Block)
    }

    @Test
    fun `watch page and small shorts shelf do not block`() {
        assertEquals(Verdict.None, ev(Trees.ytWatch()))
        assertEquals(Verdict.None, ev(Trees.ytFeedWithShelf()))
    }

    @Test
    fun `disabled surface and foreign package are ignored and no shared item allowance exists`() {
        assertEquals(Verdict.None, a.evaluate(snapshot(YT, 0, Trees.ytShorts()), DetectionConfig(enabled = emptySet())))
        assertEquals(Verdict.None, a.evaluate(snapshot("com.other", 0, Trees.ytShorts()), cfg))
        a.onEvent(Trees.deepLink(0, pkg = YT))
        assertEquals(true, ev(Trees.ytShorts()) is Verdict.Block)
    }

    @Test
    fun `clickableTarget climbs at most three levels and rejects oversized wrappers`() {
        val leaf = n("x", 10, 10, 110, 110)
        fun wrap(k: NodeSnap, clickable: Boolean) =
            n(null, k.bounds.left - 5, k.bounds.top - 5, k.bounds.right + 5, k.bounds.bottom + 5, clickable = clickable, kids = listOf(k))
        val three = wrap(wrap(wrap(leaf, false), false), true)
        assertSame(three, clickableTarget(three, leaf))
        val four = wrap(wrap(wrap(wrap(leaf, false), false), false), true)
        assertNull(clickableTarget(four, leaf))
        assertEquals(3, CLICK_ANCESTOR_LEVELS)
        val huge = n(null, 0, 0, 1000, 1000, clickable = true, kids = listOf(leaf))
        assertNull(clickableTarget(huge, leaf))
        assertNull(clickableTarget(huge, n("other", 0, 0, 5, 5)))
    }
}

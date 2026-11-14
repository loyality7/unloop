package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.detect.Trees.YT
import com.gainandshine.unloop.core.detect.youtube.YoutubeAdapter
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YoutubeSearchOnlyTest {
    private val on = DetectionConfig(enabled = setOf(SurfaceId.YOUTUBE_SEARCH_ONLY))
    private fun covers(vararg kids: NodeSnap, cfg: DetectionConfig = on) = YoutubeAdapter().covers(snapshot(YT, 0, *kids), cfg)

    private val toolbar = n(Trees.yt("toolbar"), 0, 80, 1080, 240)

    private fun bar(selected: String): NodeSnap = n(
        Trees.yt("pivot_bar"), 0, 2200, 1080, 2400,
        kids = listOf("Home", "Shorts", "Subscriptions", "Library").mapIndexed { i, l ->
            n(null, i * 270, 2200, i * 270 + 270, 2400, clickable = true, selected = l == selected, kids = listOf(n(text = l, left = i * 270 + 40, top = 2250, right = i * 270 + 230, bottom = 2350)))
        },
    )

    @Test fun `home feed is covered between the toolbar and the bottom bar, search stays reachable`() {
        val c = covers(toolbar, bar("Home")).single()
        assertEquals(SurfaceId.YOUTUBE_SEARCH_ONLY, c.surface)
        assertEquals(Rect(0, 240, 1080, 2200), c.bounds)
        assertEquals(1, covers(toolbar, bar("Subscriptions")).size)
    }

    @Test fun `search results, a playing video, and other tabs are never covered`() {
        assertTrue(covers(toolbar, bar("Home"), n(Trees.yt("search_edit_text"), 100, 100, 900, 220)).isEmpty())
        assertTrue(covers(toolbar, bar("Home"), n(Trees.yt("watch_player"), 0, 240, 1080, 850)).isEmpty())
        assertTrue(covers(toolbar, bar("Library")).isEmpty())
        assertTrue(covers(toolbar, bar("Shorts")).isEmpty())
    }

    @Test fun `missing toolbar, missing bar, or a bar in the top half do nothing`() {
        assertTrue(covers(bar("Home")).isEmpty())
        assertTrue(covers(toolbar).isEmpty())
        val high = n(Trees.yt("pivot_bar"), 0, 400, 1080, 600, kids = listOf(n(text = "Home", selected = true, left = 0, top = 400, right = 200, bottom = 600)))
        assertTrue(covers(toolbar, high).isEmpty())
        val tall = n(Trees.yt("toolbar"), 0, 80, 1080, 1100)
        assertTrue(covers(tall, bar("Home")).isEmpty(), "a toolbar reaching below the top third is not the toolbar")
    }

    @Test fun `off means no cover, and the shorts button cover still works beside it`() {
        assertTrue(covers(toolbar, bar("Home"), cfg = DetectionConfig(enabled = emptySet())).isEmpty())
        val both = DetectionConfig(enabled = setOf(SurfaceId.YOUTUBE_SEARCH_ONLY, SurfaceId.YOUTUBE_SHORTS_TAB))
        assertEquals(
            setOf(SurfaceId.YOUTUBE_SEARCH_ONLY, SurfaceId.YOUTUBE_SHORTS_TAB),
            covers(toolbar, bar("Home"), cfg = both).map { it.surface }.toSet(),
        )
    }

    @Test fun `the fast cover path asks for every id the rule reads`() {
        val ids = YoutubeAdapter().coverIds.keys
        assertTrue(ids.containsAll(listOf(Trees.yt("toolbar"), Trees.yt("pivot_bar"), Trees.yt("search_edit_text"), Trees.yt("watch_player"))))
    }
}

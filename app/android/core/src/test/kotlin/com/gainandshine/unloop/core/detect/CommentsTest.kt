package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.detect.Trees.IG
import com.gainandshine.unloop.core.detect.Trees.YT
import com.gainandshine.unloop.core.detect.instagram.InstagramAdapter
import com.gainandshine.unloop.core.detect.youtube.YoutubeAdapter
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommentsTest {
    private val igOn = DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_COMMENTS))
    private val ytOn = DetectionConfig(enabled = setOf(SurfaceId.YOUTUBE_COMMENTS))

    private val igSheet = n(Trees.ig("layout_comment_thread_container"), 0, 900, 1080, 2400)
    private val ytPanel = n(Trees.yt("comments_panel"), 0, 700, 1080, 2400)

    @Test fun `an open comments sheet is closed with back`() {
        val v = assertIs<Verdict.Block>(InstagramAdapter().evaluate(snapshot(IG, 0, igSheet), igOn))
        assertEquals(SurfaceId.INSTAGRAM_COMMENTS, v.surface)
        assertEquals(listOf(Action.Back), v.ladder)
        val y = assertIs<Verdict.Block>(YoutubeAdapter().evaluate(snapshot(YT, 0, ytPanel), ytOn))
        assertEquals(SurfaceId.YOUTUBE_COMMENTS, y.surface)
    }

    @Test fun `small comment buttons and previews never match`() {
        val button = n(Trees.ig("row_feed_button_comment"), 900, 1300, 1000, 1400)
        val preview = n(Trees.ig("row_feed_comment_textview_layout"), 0, 1400, 1080, 1500)
        assertEquals(Verdict.None, InstagramAdapter().evaluate(snapshot(IG, 0, button, preview), igOn))
    }

    @Test fun `invisible or narrow nodes do not match`() {
        assertEquals(Verdict.None, InstagramAdapter().evaluate(snapshot(IG, 0, igSheet.copy(visible = false)), igOn))
        assertEquals(Verdict.None, InstagramAdapter().evaluate(snapshot(IG, 0, n(Trees.ig("comment_side"), 0, 0, 400, 2400)), igOn))
    }

    @Test fun `off means nothing happens`() {
        val off = DetectionConfig(enabled = emptySet())
        assertEquals(Verdict.None, InstagramAdapter().evaluate(snapshot(IG, 0, igSheet), off))
        assertEquals(Verdict.None, YoutubeAdapter().evaluate(snapshot(YT, 0, ytPanel), off))
    }

    @Test fun `shorts blocking still works next to comments mode`() {
        val both = DetectionConfig(enabled = setOf(SurfaceId.YOUTUBE_SHORTS, SurfaceId.YOUTUBE_COMMENTS))
        val v = assertIs<Verdict.Block>(YoutubeAdapter().evaluate(snapshot(YT, 0, Trees.ytShorts()), both))
        assertEquals(SurfaceId.YOUTUBE_SHORTS, v.surface)
    }
}

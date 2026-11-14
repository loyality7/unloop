package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.detect.Trees.IG
import com.gainandshine.unloop.core.detect.facebook.FacebookAdapter
import com.gainandshine.unloop.core.detect.instagram.InstagramAdapter
import com.gainandshine.unloop.core.detect.shared.LabelPost
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.SCREEN
import com.gainandshine.unloop.core.model.WindowSnap
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabelPostTest {
    private val labels = setOf("sponsored")

    /** A post: full-width container (top..top+1100) with a header row holding the label. */
    private fun post(top: Int, label: String?): NodeSnap = n(
        null, 0, top, 1080, top + 1100,
        kids = listOf(
            n(null, 0, top, 1080, top + 140, kids = listOf(
                n(text = "someone", left = 120, top = top + 10, right = 500, bottom = top + 60),
                if (label != null) n(text = label, left = 120, top = top + 70, right = 400, bottom = top + 120) else n(text = "Paris", left = 120, top = top + 70, right = 400, bottom = top + 120),
            )),
            n(null, 0, top + 140, 1080, top + 1000),
        ),
    )

    private fun window(vararg kids: NodeSnap) = WindowSnap("p", SCREEN, n(null, 0, 0, 1080, 2400, kids = kids.toList()))

    @Test fun `the post carrying the label is found, others are not`() {
        val feed = n("p:id/list", 0, 150, 1080, 2200, kids = listOf(post(200, null), post(1300, "Sponsored")))
        assertEquals(listOf(Rect(0, 1300, 1080, 2200)), LabelPost.rects(window(feed), labels, bottomLimit = 2200))
    }

    @Test fun `never the whole feed, never a header only`() {
        val feed = n("p:id/list", 0, 150, 1080, 2200, kids = listOf(post(300, "Sponsored")))
        val r = LabelPost.rects(window(feed), labels)
        assertEquals(listOf(Rect(0, 300, 1080, 1400)), r, "the post, not the 2050 px feed list")
        val headerOnly = n(null, 0, 300, 1080, 440, kids = listOf(n(text = "Sponsored", left = 100, top = 320, right = 400, bottom = 380)))
        assertTrue(LabelPost.rects(window(headerOnly), labels).isEmpty(), "too short to be a post: hide nothing")
    }

    @Test fun `label must be the whole text and visible`() {
        val mention = n(null, 0, 300, 1080, 1400, kids = listOf(n(text = "I am not sponsored by anyone", left = 100, top = 320, right = 900, bottom = 380)))
        assertTrue(LabelPost.rects(window(mention), labels).isEmpty())
        val hidden = n(null, 0, 300, 1080, 1400, kids = listOf(n(text = "Sponsored", left = 100, top = 320, right = 400, bottom = 380, visible = false)))
        assertTrue(LabelPost.rects(window(hidden), labels).isEmpty())
        assertEquals(1, LabelPost.rects(window(post(300, "Sponsored ·")), labels).size, "a trailing dot is ignored")
    }

    @Test fun `instagram covers sponsored posts, follows scrolling, stops above the tab bar`() {
        val cfg = DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_SPONSORED))
        val feed = n(Trees.ig("feed_list"), 150, 150, 1080, 2200, kids = listOf(post(1500, "Sponsored")))
        val c = InstagramAdapter().covers(snapshot(IG, 0, feed, n(Trees.ig("tab_bar"), 0, 2200, 1080, 2400)), cfg).single()
        assertEquals("instagram_sponsored#0", c.surface)
        assertTrue(c.follows)
        assertEquals(2200, c.bounds.bottom, "the cover never reaches the tab bar")
        assertTrue("sponsored" in InstagramAdapter().coverTexts(cfg))
        assertTrue(InstagramAdapter().coverTexts(DetectionConfig(enabled = emptySet())).isEmpty())
    }

    @Test fun `facebook covers suggested and sponsored posts`() {
        val cfg = DetectionConfig(enabled = setOf(SurfaceId.FACEBOOK_SUGGESTED))
        val feed = n("p:id/list", 0, 150, 1080, 2200, kids = listOf(post(300, "Suggested for you"), post(1400, "Sponsored")))
        val covers = FacebookAdapter().covers(snapshot("com.facebook.katana", 0, feed), cfg)
        assertEquals(listOf("facebook_suggested#0", "facebook_suggested#1"), covers.map { it.surface })
        assertEquals(emptyList(), FacebookAdapter().covers(snapshot("com.facebook.katana", 0, feed), DetectionConfig(enabled = emptySet())))
    }
}

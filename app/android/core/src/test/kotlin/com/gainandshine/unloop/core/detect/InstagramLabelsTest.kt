package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.detect.Trees.IG
import com.gainandshine.unloop.core.detect.instagram.InstagramAdapter
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstagramLabelsTest {
    private fun post(top: Int, label: String): NodeSnap = n(
        null, 0, top, 1080, top + 1100,
        kids = listOf(n(null, 0, top, 1080, top + 140, kids = listOf(n(text = label, left = 120, top = top + 70, right = 500, bottom = top + 120)))),
    )

    private fun covers(cfg: DetectionConfig, vararg kids: NodeSnap) = InstagramAdapter().covers(snapshot(IG, 0, *kids), cfg)

    @Test fun `suggested posts are covered only when that switch is on, sponsored ones only for theirs`() {
        val feed = n(Trees.ig("feed_list"), 150, 150, 1080, 2200, kids = listOf(post(200, "Suggested for you"), post(1300, "Sponsored")))
        val sugg = covers(DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_SUGGESTED)), feed)
        assertEquals(listOf("instagram_suggested#0"), sugg.map { it.surface })
        val ads = covers(DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_SPONSORED)), feed)
        assertEquals(listOf("instagram_sponsored#0"), ads.map { it.surface })
        assertEquals(2, covers(DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_SPONSORED, SurfaceId.INSTAGRAM_SUGGESTED)), feed).size)
        assertTrue(covers(DetectionConfig(enabled = emptySet()), feed).isEmpty())
    }

    @Test fun `the fast path searches only for the labels that are switched on`() {
        val a = InstagramAdapter()
        assertTrue("suggested for you" in a.coverTexts(DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_SUGGESTED))))
        assertTrue("sponsored" !in a.coverTexts(DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_SUGGESTED))))
        assertTrue(a.coverTexts(DetectionConfig(enabled = emptySet())).isEmpty())
    }

    @Test fun `the messages button gets its own cover on the clickable wrapper, and only when on`() {
        val wrapper = n(null, 432, 2200, 648, 2400, clickable = true, kids = listOf(n(Trees.ig("direct_tab"), 450, 2210, 630, 2390)))
        val on = covers(DetectionConfig(enabled = setOf(SurfaceId.INSTAGRAM_MESSAGES_TAB)), wrapper).single()
        assertEquals(SurfaceId.INSTAGRAM_MESSAGES_TAB, on.surface)
        assertEquals(432, on.bounds.left)
        assertTrue(covers(DetectionConfig(enabled = emptySet()), wrapper).isEmpty())
        assertTrue("com.instagram.android:id/direct_tab" in InstagramAdapter().coverIds.keys, "the fast path must look the messages tab up")
    }
}

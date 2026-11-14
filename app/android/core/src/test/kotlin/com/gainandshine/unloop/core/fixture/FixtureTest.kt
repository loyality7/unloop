package com.gainandshine.unloop.core.fixture

import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceAdapter
import com.gainandshine.unloop.core.detect.Trees
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.detect.instagram.InstagramAdapter
import com.gainandshine.unloop.core.detect.youtube.YoutubeAdapter
import com.gainandshine.unloop.core.detect.youtube.YoutubeIds
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixtureTest {
    private fun adapterFor(app: String): SurfaceAdapter = if (app == "instagram") InstagramAdapter() else YoutubeAdapter()
    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.jsonl")) { "missing fixture $name (touch core/.regen-fixtures and rerun)" }
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `regenerate committed fixtures on demand`() {
        if (!File(".regen-fixtures").exists()) return // create this marker file in :core to regenerate
        val dir = File("src/test/resources/fixtures").apply { mkdirs() }
        for ((name, f) in FixtureSet.all) File(dir, "$name.jsonl").writeText(FixtureCodec.encode(f, allowText = true))
    }

    @Test
    fun `committed fixtures match their generator and satisfy their oracle`() {
        assertTrue(FixtureSet.all.size >= 6)
        for ((name, f) in FixtureSet.all) {
            val text = resource(name)
            assertEquals(FixtureCodec.encode(f, allowText = true), text, "$name drifted from FixtureSet")
            val decoded = FixtureCodec.decode(text)
            assertEquals(f, decoded, "$name round trip")
            assertEquals(emptyList(), ReplayRunner.mismatches(adapterFor(decoded.app), decoded), name)
        }
    }

    @Test
    fun `codec round trips events and flags`() {
        val f = Fixture(
            "instagram", listOf("none"),
            listOf(
                FixtureItem.Ev(Trees.scroll(5, -7, dx = 2, from = 3)),
                FixtureItem.Snap(snapshot("p", 9, n("id", 1, 2, 30, 40, cls = "C", text = "q\"\n\\", selected = true, clickable = true, visible = false, enabled = false, editable = true))),
            ),
            allowSharedItem = false,
        )
        assertEquals(f, FixtureCodec.decode(FixtureCodec.encode(f, allowText = true)))
    }

    @Test
    fun `privacy rule drops text and desc unless allowText is set`() {
        val f = Fixture("x", listOf("none"), listOf(FixtureItem.Snap(snapshot("p", 0, n("a", text = "SECRET", desc = "PRIVATE")))))
        val plain = FixtureCodec.encode(f)
        assertFalse(plain.contains("SECRET") || plain.contains("PRIVATE"))
        val node = (FixtureCodec.decode(plain).items[0] as FixtureItem.Snap).snapshot.windows[0].root.children[0]
        assertNull(node.text)
        assertNull(node.desc)
        assertTrue(FixtureCodec.encode(f, allowText = true).contains("SECRET"))
    }

    @Test
    fun `decoder rejects malformed input`() {
        for (bad in listOf("", "{}", "#{\"v\":2,\"app\":\"x\",\"oracle\":[]}")) {
            val failed = runCatching { FixtureCodec.decode(bad) }.isFailure
            assertTrue(failed, "should reject <$bad>")
        }
    }

    @Test
    fun `replay reports oracle mismatches`() {
        val f = FixtureSet.all.getValue("ig_feed").copy(oracle = listOf("none", "block:instagram_reels"))
        assertEquals(listOf("snapshot 1: expected block:instagram_reels, got none"), ReplayRunner.mismatches(InstagramAdapter(), f))
        assertEquals(1, ReplayRunner.mismatches(InstagramAdapter(), f.copy(oracle = emptyList())).size)
    }

    @Test
    fun `counterfactual naive rule without prominence blocks the feed with a small shelf`() {
        fun naiveBlocks(root: NodeSnap) = root.findAll { it.id in YoutubeIds.SHORTS_CONTAINERS }.isNotEmpty()
        val fixture = FixtureCodec.decode(resource("yt_feed_small_shelf"))
        val snap = (fixture.items[0] as FixtureItem.Snap).snapshot
        assertTrue(naiveBlocks(snap.windows[0].root), "naive rule would have ejected the user from a normal feed")
        assertEquals(Verdict.None, YoutubeAdapter().evaluate(snap, DetectionConfig()))
    }
}

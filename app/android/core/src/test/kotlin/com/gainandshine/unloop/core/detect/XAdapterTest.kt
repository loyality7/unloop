package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.detect.x.XAdapter
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class XAdapterTest {
    private val pkg = "com.twitter.android"
    private fun cfg(vararg s: String) = DetectionConfig(enabled = s.toSet())
    private fun eval(c: DetectionConfig, vararg kids: NodeSnap) = XAdapter().evaluate(snapshot(pkg, 0, *kids), c)

    private fun navTab(desc: String, slot: Int, selected: Boolean) =
        n(desc = desc, left = slot * 270, top = 2250, right = slot * 270 + 260, bottom = 2400, selected = selected, clickable = true)

    private fun nav(selectedIdx: Int?): List<NodeSnap> = listOf("Home", "Search and Explore", "Notifications", "Messages")
        .mapIndexed { i, d -> navTab(d, i, i == selectedIdx) }

    private fun topTabs(forYouSelected: Boolean): List<NodeSnap> = listOf(
        n(text = "For you", left = 100, top = 150, right = 500, bottom = 250, selected = forYouSelected, clickable = true),
        n(text = "Following", left = 540, top = 150, right = 940, bottom = 250, selected = !forYouSelected, clickable = true),
    )

    @Test fun `for you selected on home clicks following`() {
        val v = assertIs<Verdict.Block>(eval(cfg(SurfaceId.X_FOR_YOU), *nav(0).toTypedArray(), *topTabs(true).toTypedArray()))
        assertEquals(SurfaceId.X_FOR_YOU, v.surface)
        assertEquals(540, assertIs<Action.TapNode>(v.ladder[0]).bounds.left)
        assertIs<Action.ClickNode>(v.ladder[1])
    }

    @Test fun `following selected or other tab is left alone`() {
        val c = cfg(SurfaceId.X_FOR_YOU)
        assertEquals(Verdict.None, eval(c, *nav(0).toTypedArray(), *topTabs(false).toTypedArray()))
        assertEquals(Verdict.None, eval(c, *nav(1).toTypedArray(), *topTabs(true).toTypedArray()))
    }

    @Test fun `explore tab sends you home`() {
        val v = assertIs<Verdict.Block>(eval(cfg(SurfaceId.X_EXPLORE), *nav(1).toTypedArray()))
        assertEquals(SurfaceId.X_EXPLORE, v.surface)
        assertEquals(0, assertIs<Action.TapNode>(v.ladder[0]).bounds.left)
        assertEquals(Verdict.None, eval(cfg(SurfaceId.X_EXPLORE), *nav(0).toTypedArray()))
    }

    @Test fun `messages only clicks messages from any other tab`() {
        val c = cfg(SurfaceId.X_DMS_ONLY)
        for (sel in listOf(0, 1, 2)) {
            val v = assertIs<Verdict.Block>(eval(c, *nav(sel).toTypedArray()))
            assertEquals(SurfaceId.X_DMS_ONLY, v.surface)
            assertEquals(3 * 270, assertIs<Action.TapNode>(v.ladder[0]).bounds.left)
        }
        assertEquals(Verdict.None, eval(c, *nav(3).toTypedArray()))
    }

    @Test fun `nothing happens without a readable tab bar or selection, or when off`() {
        assertEquals(Verdict.None, eval(cfg(SurfaceId.X_DMS_ONLY)))
        assertEquals(Verdict.None, eval(cfg(SurfaceId.X_DMS_ONLY), *nav(null).toTypedArray()))
        assertEquals(Verdict.None, eval(cfg(), *nav(0).toTypedArray()))
        val two = nav(0).take(2) // fewer than three known tabs is not the tab bar
        assertEquals(Verdict.None, eval(cfg(SurfaceId.X_DMS_ONLY), *two.toTypedArray()))
    }

    @Test fun `labels in other languages work`() {
        val es = listOf("Inicio", "Explorar", "Notificaciones", "Mensajes").mapIndexed { i, d -> navTab(d, i, i == 0) }
        val v = assertIs<Verdict.Block>(eval(cfg(SurfaceId.X_DMS_ONLY), *es.toTypedArray()))
        assertEquals(3 * 270, assertIs<Action.TapNode>(v.ladder[0]).bounds.left)
    }
}

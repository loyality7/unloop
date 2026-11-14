package com.gainandshine.unloop.core.detect.snapchat

import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.SCREEN
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.core.model.WindowSnap
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SnapchatAdapterTest {
    private val pkg = "com.snapchat.android"
    private val p = "$pkg:id/"
    private val both = DetectionConfig(enabled = setOf(SurfaceId.SNAPCHAT_SPOTLIGHT, SurfaceId.SNAPCHAT_DISCOVER))
    private val spotlightOnly = DetectionConfig(enabled = setOf(SurfaceId.SNAPCHAT_SPOTLIGHT))
    private val discoverOnly = DetectionConfig(enabled = setOf(SurfaceId.SNAPCHAT_DISCOVER))

    private fun frame(vararg kids: NodeSnap) = snapshot(pkg, 0, *kids)
    private fun eval(cfg: DetectionConfig, vararg kids: NodeSnap) = SnapchatAdapter().evaluate(frame(*kids), cfg)

    private fun rail(clickable: Int = 4, favorite: Boolean = true, left: Int = 900): NodeSnap {
        val kids = (0 until clickable).map { n(left = left, top = 800 + it * 150, right = 1060, bottom = 900 + it * 150, clickable = true) } +
            if (favorite) listOf(n(id = p + "favorite", left = left, top = 1500, right = 1060, bottom = 1600)) else emptyList()
        return n(left = left, top = 700, right = 1080, bottom = 1700, kids = kids)
    }

    private fun player(vararg kids: NodeSnap, bottom: Int = 2400) = n(id = p + "opera_viewer", left = 0, top = 0, right = 1080, bottom = bottom, kids = kids.toList())

    @Test
    fun spotlightContainerBlocksWithBackThenHome() {
        val v = assertIs<Verdict.Block>(eval(spotlightOnly, n(id = p + "spotlight_container")))
        assertEquals(SurfaceId.SNAPCHAT_SPOTLIGHT, v.surface)
        assertEquals(listOf(Action.Back, Action.Home), v.ladder)
    }

    @Test
    fun smallOrHiddenSpotlightContainerDoesNotBlock() {
        assertEquals(Verdict.None, eval(spotlightOnly, n(id = p + "spotlight_container", right = 300, bottom = 400)))
        assertEquals(Verdict.None, eval(spotlightOnly, n(id = p + "spotlight_container", visible = false)))
    }

    @Test
    fun selectedSpotlightTabBlocksUnselectedDoesNot() {
        assertIs<Verdict.Block>(eval(spotlightOnly, n(id = p + "ngs_spotlight_icon_container", top = 2250, selected = true)))
        assertEquals(Verdict.None, eval(spotlightOnly, n(id = p + "ngs_spotlight_icon_container", top = 2250)))
    }

    @Test
    fun geometryDetectorPositive() {
        val v = assertIs<Verdict.Block>(eval(spotlightOnly, player(rail())))
        assertEquals("spotlight_geometry", v.reason)
    }

    @Test
    fun geometryDetectorNegatives() {
        // story viewer: same player id but no action rail
        assertEquals(Verdict.None, eval(spotlightOnly, player()))
        assertEquals(Verdict.None, eval(spotlightOnly, player(rail(clickable = 2))))
        assertEquals(Verdict.None, eval(spotlightOnly, player(rail(favorite = false))))
        assertEquals(Verdict.None, eval(spotlightOnly, player(rail(left = 500))))
        // player below half the window
        assertEquals(Verdict.None, eval(spotlightOnly, player(rail(), bottom = 1000)))
        // rail that is not clickable children
        assertEquals(Verdict.None, eval(spotlightOnly, player(n(left = 900, top = 700, right = 1080, bottom = 1700, kids = listOf(n(id = p + "favorite"))))))
    }

    @Test
    fun discoverMarkers() {
        for (id in listOf("chrome_subscribe_button", "AdsFloatingPillView", "sc_composer_ad_cta_tappable_view")) {
            val v = assertIs<Verdict.Block>(eval(discoverOnly, n(id = p + id, left = 100, top = 100, right = 400, bottom = 200)), id)
            assertEquals(SurfaceId.SNAPCHAT_DISCOVER, v.surface)
        }
        assertIs<Verdict.Block>(eval(discoverOnly, n(text = "View Profile", right = 300, bottom = 100)))
        assertIs<Verdict.Block>(eval(discoverOnly, n(desc = "view profile", right = 300, bottom = 100)))
    }

    @Test
    fun discoverMarkerNegatives() {
        assertEquals(Verdict.None, eval(discoverOnly, n(id = p + "chrome_subscribe_button", right = 10, bottom = 10)))
        assertEquals(Verdict.None, eval(discoverOnly, n(id = p + "chrome_subscribe_button", right = 300, bottom = 100, visible = false)))
        assertEquals(Verdict.None, eval(discoverOnly, n(text = "View Chat", right = 300, bottom = 100)))
    }

    @Test
    fun surfaceSwitchesAreIndependent() {
        val spot = n(id = p + "spotlight_container")
        val disc = n(id = p + "chrome_subscribe_button", right = 300, bottom = 100)
        assertEquals(Verdict.None, eval(discoverOnly, spot))
        assertEquals(Verdict.None, eval(spotlightOnly, disc))
        assertEquals(Verdict.None, eval(DetectionConfig(enabled = emptySet()), spot, disc))
        assertEquals(SurfaceId.SNAPCHAT_SPOTLIGHT, assertIs<Verdict.Block>(eval(both, spot, disc)).surface)
        assertEquals(SurfaceId.SNAPCHAT_DISCOVER, assertIs<Verdict.Block>(eval(both, disc)).surface)
        // geometry never reports Discover or fires when Spotlight is off
        assertEquals(Verdict.None, eval(discoverOnly, player(rail())))
    }

    @Test
    fun chatStoriesAndSnapsDoNotMatch() {
        val chat = listOf(
            n(id = p + "chat_input", left = 0, top = 2200, right = 1080, bottom = 2400, editable = true, text = "Send a chat"),
            n(id = p + "chat_message_list", top = 200, bottom = 2200),
            n(text = "Alice", right = 400, bottom = 150),
        )
        assertEquals(Verdict.None, eval(both, *chat.toTypedArray()))
        val story = listOf(player(n(id = p + "story_reply", left = 0, top = 2200, right = 1080, bottom = 2400, clickable = true)))
        assertEquals(Verdict.None, eval(both, *story.toTypedArray()))
        val camera = listOf(n(id = p + "ngs_camera_icon_container", top = 2250, selected = true), n(id = p + "ngs_chat_icon_container", top = 2250))
        assertEquals(Verdict.None, eval(both, *camera.toTypedArray()))
        val snaps = n(id = p + "df_large_story", right = 500, bottom = 800, text = "Friend story")
        assertEquals(Verdict.None, eval(both, snaps))
    }

    @Test
    fun checksEveryWindowOfTheApp() {
        val main = WindowSnap(pkg, SCREEN, n(id = p + "chat_input"))
        val bubble = WindowSnap(pkg, SCREEN, n(id = p + "spotlight_container"), isActive = false)
        val other = WindowSnap("com.other", SCREEN, n(id = p + "spotlight_container"))
        val snap = UiSnapshot(0, pkg, SCREEN, listOf(main, bubble))
        assertIs<Verdict.Block>(SnapchatAdapter().evaluate(snap, both))
        val foreign = UiSnapshot(0, pkg, SCREEN, listOf(main, other))
        assertEquals(Verdict.None, SnapchatAdapter().evaluate(foreign, both))
        assertEquals(Verdict.None, SnapchatAdapter().evaluate(snapshot("com.other", 0, n(id = p + "spotlight_container")), both))
    }

    @Test
    fun usesWindowBoundsNotDisplay() {
        val half = Rect(0, 0, 540, 1200)
        val root = n(right = 540, bottom = 1200, kids = listOf(n(id = p + "spotlight_container", right = 540, bottom = 1200)))
        val snap = UiSnapshot(0, pkg, SCREEN, listOf(WindowSnap(pkg, half, root)))
        assertIs<Verdict.Block>(SnapchatAdapter().evaluate(snap, spotlightOnly))
    }

    @Test
    fun statelessResetAndNoMutation() {
        val a = SnapchatAdapter()
        val s = frame(player(rail()))
        val before = s.toString()
        val first = a.evaluate(s, both)
        a.reset()
        assertEquals(first, a.evaluate(s, both))
        assertEquals(before, s.toString())
    }
}

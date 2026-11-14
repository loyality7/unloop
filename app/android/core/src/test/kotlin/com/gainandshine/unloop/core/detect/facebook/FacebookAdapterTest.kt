package com.gainandshine.unloop.core.detect.facebook

import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.n
import com.gainandshine.unloop.core.model.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FacebookAdapterTest {
    private val pkg = "com.facebook.katana"
    private val on = DetectionConfig(enabled = setOf(SurfaceId.FACEBOOK_REELS))

    private fun tab(desc: String, slot: Int, selected: Boolean = false, top: Int = 2250) =
        n(desc = desc, left = slot * 180, top = top, right = slot * 180 + 170, bottom = top + 150, selected = selected)

    /** Five-tab bar; [selectedIdx] is selected. Labels are given per locale, index 0 = Home, 2 = Reels. */
    private fun bar(labels: List<String>, selectedIdx: Int) =
        labels.mapIndexed { i, l -> tab(l, i, i == selectedIdx) }

    private val en = listOf("Home, tab 1 of 5", "Friends, tab 2 of 5", "Reels, tab 3 of 5", "Notifications, tab 4 of 5", "Menu, tab 5 of 5")
    private val reel = n(desc = "Reel details", left = 0, top = 0, right = 1080, bottom = 2400)

    private fun frame(atMs: Long, vararg kids: NodeSnap) = snapshot(pkg, atMs, *kids)
    private fun block(v: Verdict) = assertIs<Verdict.Block>(v)

    private fun scroll(atMs: Long, dy: Int, fromIndex: Int = -1) =
        EventRecord(EventType.VIEW_SCROLLED, pkg, null, fromIndex = fromIndex, scrollDy = dy, atMs = atMs)

    private val composer = n(cls = "android.widget.EditText", text = "Message…", editable = true, left = 0, top = 2200, right = 1080, bottom = 2300)
    private fun audio(author: String) = n(text = "$author · Original audio", left = 0, top = 2000, right = 600, bottom = 2100)

    @Test
    fun reelsTabSelectedBlocksWithHomeTapLadder() {
        val v = block(FacebookAdapter().evaluate(frame(0, *bar(en, 2).toTypedArray()), on))
        assertEquals(SurfaceId.FACEBOOK_REELS, v.surface)
        assertEquals(4, v.ladder.size)
        val tap = assertIs<Action.TapNode>(v.ladder[0])
        assertEquals(0, tap.bounds.left)
        assertIs<Action.ClickNode>(v.ladder[1])
        assertEquals(listOf(Action.Back, Action.Home), v.ladder.drop(2))
    }

    @Test
    fun otherSelectedTabsAreAllowed() {
        for (i in listOf(0, 1, 3, 4)) {
            assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, *bar(en, i).toTypedArray()), on), "tab $i")
        }
    }

    @Test
    fun localeVariants() {
        val cases = mapOf(
            "es" to listOf("Inicio, pestaña 1 de 5", "Amigos, pestaña 2 de 5", "Vídeo, pestaña 3 de 5", "Notificaciones, pestaña 4 de 5", "Menú, pestaña 5 de 5"),
            "fr" to listOf("Accueil, onglet 1 sur 5", "Amis, onglet 2 sur 5", "Vidéo, onglet 3 sur 5", "Notifications, onglet 4 sur 5", "Menu, onglet 5 sur 5"),
            "de" to listOf("Startseite, Tab 1 von 5", "Freunde, Tab 2 von 5", "Reels, Tab 3 von 5", "Benachrichtigungen, Tab 4 von 5", "Menü, Tab 5 von 5"),
            "ar" to listOf("الرئيسية، علامة تبويب ١ من ٥", "الأصدقاء، علامة تبويب ٢ من ٥", "ريلز، علامة تبويب ٣ من ٥", "الإشعارات، علامة تبويب ٤ من ٥", "القائمة، علامة تبويب ٥ من ٥"),
        )
        for ((loc, labels) in cases) {
            val v = block(FacebookAdapter().evaluate(frame(0, *bar(labels, 2).toTypedArray()), on))
            assertEquals("reels_tab_selected", v.reason, loc)
            assertIs<Action.TapNode>(v.ladder[0], loc)
            assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, *bar(labels, 0).toTypedArray()), on), loc)
        }
    }

    @Test
    fun tabBarRequiresNumberPairAndThreeTabsOnOneRow() {
        val noNumbers = listOf("Home", "Friends", "Reels", "Notifications", "Menu")
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, *bar(noNumbers, 2).toTypedArray()), on))
        val oneNumber = en.map { it.substringBefore(" of") }
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, *bar(oneNumber, 2).toTypedArray()), on))
        val two = listOf(tab("Home, tab 1 of 5", 0), tab("Reels, tab 3 of 5", 2, true))
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, *two.toTypedArray()), on))
        val scattered = listOf(tab("Home, tab 1 of 5", 0, top = 200), tab("Friends, tab 2 of 5", 1, top = 900), tab("Reels, tab 3 of 5", 2, true, top = 1700))
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, *scattered.toTypedArray()), on))
        val unknown = listOf("Home, tab 1 of 5", "Photos, tab 2 of 5", "Reels, tab 3 of 5", "Menu, tab 4 of 5").mapIndexed { i, l -> tab(l, i, i == 2) }
        assertIs<Verdict.Block>(FacebookAdapter().evaluate(frame(0, *unknown.toTypedArray()), on))
    }

    @Test
    fun selectedStateCanLiveOnSmallAncestor() {
        val wrapped = en.mapIndexed { i, l ->
            val inner = tab(l, i)
            n(left = inner.bounds.left, top = inner.bounds.top, right = inner.bounds.right + 10, bottom = inner.bounds.bottom + 10, selected = i == 2, kids = listOf(inner))
        }
        assertIs<Verdict.Block>(FacebookAdapter().evaluate(frame(0, *wrapped.toTypedArray()), on))
        // an ancestor much larger than the tab does not count
        val big = n(left = 0, top = 2200, right = 1080, bottom = 2400, selected = true, kids = bar(en, -1))
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, big), on))
    }

    @Test
    fun reelPlayingInFeedBlocksWithBackHome() {
        val v = block(FacebookAdapter().evaluate(frame(0, reel), on))
        assertEquals("reel_in_feed", v.reason)
        assertEquals(listOf(Action.Back, Action.Home), v.ladder)
        for (d in listOf("Swipe up to see more", "Navigate to your Reels profile", "Dettagli del Reel")) {
            assertIs<Verdict.Block>(FacebookAdapter().evaluate(frame(0, n(desc = d)), on), d)
        }
    }

    @Test
    fun reelNegatives() {
        val thumb = n(desc = "Reel details", left = 50, top = 600, right = 450, bottom = 1100)
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, thumb), on))
        val inList = n(id = "android:id/list", kids = listOf(reel))
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, inList), on))
        val plain = n(desc = "Photo by Alice")
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, plain), on))
        val hidden = n(desc = "Reel details", visible = false)
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, hidden), on))
        val narrow = n(desc = "Reel details", right = 700)
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, narrow), on))
    }

    @Test
    fun disabledAndForeignPackageDoNothing() {
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, reel), DetectionConfig(enabled = emptySet())))
        assertEquals(Verdict.None, FacebookAdapter().evaluate(snapshot("com.facebook.orca", 0, reel), on))
    }

    @Test
    fun messagingScreenSuppressesBlocking() {
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, composer, reel), on))
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, n(text = "Search Messenger"), reel), on))
        assertEquals(Verdict.None, FacebookAdapter().evaluate(frame(0, n(desc = "Buscar en Messenger"), reel), on))
        // a non-editable node saying "Message" is not a composer
        assertIs<Verdict.Block>(FacebookAdapter().evaluate(frame(0, n(text = "Message", right = 100, bottom = 100), reel), on))
    }

    @Test
    fun reelAfterMessagingAllowedForFifteenSecondsBoundary() {
        for ((dt, expectBlock) in listOf(14_999L to false, 15_000L to true)) {
            val a = FacebookAdapter()
            assertEquals(Verdict.None, a.evaluate(frame(1_000, composer), on))
            val v = a.evaluate(frame(1_000 + dt, reel, audio("Alice")), on)
            if (expectBlock) block(v) else assertEquals(Verdict.None, v, "dt=$dt")
        }
    }

    @Test
    fun reelWithoutRecentMessagingBlocksAndSharedItemSwitchWins() {
        assertIs<Verdict.Block>(FacebookAdapter().evaluate(frame(100_000, reel), on))
        val strict = DetectionConfig(enabled = setOf(SurfaceId.FACEBOOK_REELS), allowSharedItem = false)
        val a = FacebookAdapter()
        a.evaluate(frame(0, composer), strict)
        assertIs<Verdict.Block>(a.evaluate(frame(100, reel), strict))
    }

    @Test
    fun authorChangeEndsAllowance() {
        val a = FacebookAdapter()
        a.evaluate(frame(0, composer), on)
        assertEquals(Verdict.None, a.evaluate(frame(100, reel, audio("Alice")), on))
        assertEquals(Verdict.None, a.evaluate(frame(200, reel, audio("Alice")), on))
        assertEquals("allowed_reel_author_changed", block(a.evaluate(frame(300, reel, audio("Bob")), on)).reason)
        // View X's reels style label also yields an author
        val b = FacebookAdapter()
        b.evaluate(frame(0, composer), on)
        assertEquals(Verdict.None, b.evaluate(frame(100, reel, n(desc = "View Carol's reels")), on))
        assertIs<Verdict.Block>(b.evaluate(frame(200, reel, n(desc = "Voir les reels de Dan")), on))
    }

    @Test
    fun scrollTwentyPercentEndsAllowance() {
        for ((dy, expectBlock) in listOf(479 to false, 480 to true)) {
            val a = FacebookAdapter()
            a.evaluate(frame(0, composer), on)
            a.evaluate(frame(100, reel, audio("Alice")), on)
            a.onEvent(scroll(200, dy))
            val v = a.evaluate(frame(300, reel, audio("Alice")), on)
            if (expectBlock) block(v) else assertEquals(Verdict.None, v, "dy=$dy")
        }
        val idx = FacebookAdapter()
        idx.evaluate(frame(0, composer), on)
        idx.evaluate(frame(100, reel), on)
        idx.onEvent(scroll(200, 10, fromIndex = 4))
        assertEquals(Verdict.None, idx.evaluate(frame(250, reel), on))
        idx.onEvent(scroll(300, 10, fromIndex = 5))
        assertIs<Verdict.Block>(idx.evaluate(frame(350, reel), on))
    }

    @Test
    fun scrollEventsAreIgnoredWithoutAllowanceOrWhenHorizontalOrStale() {
        val a = FacebookAdapter()
        a.onEvent(scroll(0, 5000))
        a.evaluate(frame(10, composer), on)
        a.evaluate(frame(100, reel), on)
        a.onEvent(EventRecord(EventType.VIEW_SCROLLED, pkg, null, scrollDx = 900, scrollDy = 100, atMs = 150))
        a.onEvent(scroll(2000, 900)) // reel not seen for > 1000 ms
        assertEquals(Verdict.None, a.evaluate(frame(2100, reel), on))
    }

    @Test
    fun resetClearsMessagingAndAllowance() {
        val a = FacebookAdapter()
        a.evaluate(frame(0, composer), on)
        a.reset()
        assertIs<Verdict.Block>(a.evaluate(frame(100, reel), on))
        val b = FacebookAdapter()
        b.evaluate(frame(0, composer), on)
        b.evaluate(frame(100, reel, audio("Alice")), on)
        b.reset()
        assertIs<Verdict.Block>(b.evaluate(frame(200, reel, audio("Bob")), on))
    }

    @Test
    fun evaluateDoesNotMutateInput() {
        val s = frame(0, reel, *bar(en, 2).toTypedArray())
        val before = s.toString()
        FacebookAdapter().evaluate(s, on)
        assertEquals(before, s.toString())
        assertTrue(before.isNotEmpty())
    }
}

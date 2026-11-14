package com.gainandshine.unloop.core.detect.facebook

import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.CoverRect
import com.gainandshine.unloop.core.detect.shared.LabelPost
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.NodeRef
import com.gainandshine.unloop.core.detect.SurfaceAdapter
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.detect.coversFraction
import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.core.model.pathTo
import kotlin.math.abs

/**
 * Facebook Reels only. Detects the bottom tab bar from content descriptions ("Reels, tab 3 of 6"), blocks the selected
 * Reels/Video tab by returning to Home, and blocks a reel playing outside a list. A reel reached from a messaging screen
 * is allowed once for [MESSAGING_WINDOW_MS], ended by an author change or a [SCROLL_FRACTION] scroll.
 * Debounce is the engine's job.
 */
class FacebookAdapter : SurfaceAdapter {
    override val pkgs = setOf("com.facebook.katana")
    override val surfaces = setOf(
        SurfaceId.FACEBOOK_REELS, SurfaceId.FACEBOOK_MARKETPLACE_ONLY, SurfaceId.FACEBOOK_GROUPS_FEED, SurfaceId.FACEBOOK_SUGGESTED,
    )
    override val coverSurfaces = setOf(SurfaceId.FACEBOOK_SUGGESTED)
    override fun coverTexts(cfg: DetectionConfig): Set<String> =
        if (cfg.isOn(SurfaceId.FACEBOOK_SUGGESTED)) FacebookLabels.suggestedOrSponsored else emptySet()

    /** Hide suggested and sponsored posts: cover each post that carries one of those labels, above the tab bar. */
    override fun covers(snap: UiSnapshot, cfg: DetectionConfig): List<CoverRect> {
        if (snap.pkg !in pkgs || !cfg.isOn(SurfaceId.FACEBOOK_SUGGESTED)) return emptyList()
        val w = snap.primary ?: return emptyList()
        val barTop = tabRow(w.root).minOfOrNull { it.node.bounds.top } ?: w.bounds.bottom
        return LabelPost.rects(w, FacebookLabels.suggestedOrSponsored, barTop).mapIndexed { i, r ->
            CoverRect("${SurfaceId.FACEBOOK_SUGGESTED}#$i", "suggested_post", r, follows = true)
        }
    }

    private class Tab(val node: NodeSnap, val label: String, val selected: Boolean)

    private var lastMessagingAt: Long? = null
    private var allowArmed = false
    private var allowAuthor: String? = null
    private var allowScrolled = false
    private var lastReelSeenAt = 0L
    private var reelHeight = 0
    private var cumDy = 0
    private var lastScrollAt = Long.MIN_VALUE
    private var baseIndex = -1

    /** Feeds scroll deltas to the one-reel allowance tracker. */
    override fun onEvent(e: EventRecord) {
        if (e.type != EventType.VIEW_SCROLLED || e.pkg !in pkgs) return
        if (!allowArmed || allowScrolled || reelHeight <= 0 || e.atMs - lastReelSeenAt > SCROLL_GAP_MS) return
        if (abs(e.scrollDx) > abs(e.scrollDy)) return
        if (e.fromIndex >= 0) {
            if (baseIndex < 0) baseIndex = e.fromIndex else if (e.fromIndex != baseIndex) allowScrolled = true
        }
        if (e.atMs - lastScrollAt > SCROLL_GAP_MS) cumDy = 0
        lastScrollAt = e.atMs
        cumDy += abs(e.scrollDy)
        if (cumDy >= reelHeight * SCROLL_FRACTION) allowScrolled = true
    }

    override fun evaluate(snap: UiSnapshot, cfg: DetectionConfig): Verdict {
        if (snap.pkg !in pkgs) return Verdict.None
        val reelsOn = cfg.isOn(SurfaceId.FACEBOOK_REELS)
        val marketOnly = cfg.isOn(SurfaceId.FACEBOOK_MARKETPLACE_ONLY)
        val groupsOn = cfg.isOn(SurfaceId.FACEBOOK_GROUPS_FEED)
        if (!reelsOn && !marketOnly && !groupsOn) return Verdict.None
        val winIdx = snap.windows.indexOfFirst { it.pkg == snap.pkg }
        if (winIdx < 0) return Verdict.None
        val win = snap.windows[winIdx]
        val root = win.root
        val now = snap.atMs

        if (isMessaging(root)) {
            lastMessagingAt = now
            resetAllowance()
            return Verdict.None
        }

        val tabs = tabRow(root)
        val selected = tabs.firstOrNull { it.selected }
        if (marketOnly) marketplaceVerdict(winIdx, root, tabs, selected)?.let { return it }
        if (groupsOn) groupsVerdict(winIdx, root, selected)?.let { return it }
        if (!reelsOn) return Verdict.None
        if (selected != null && selected.label in FacebookLabels.reelsTab) {
            resetAllowance()
            val home = tabs.firstOrNull { it.label in FacebookLabels.homeTab && !it.selected }
            val ladder = ArrayList<Action>()
            if (home != null) {
                root.pathTo(home.node)?.let {
                    ladder += Action.TapNode(NodeRef(winIdx, it), home.node.id, home.node.bounds, "home")
                    ladder += Action.ClickNode(NodeRef(winIdx, it), home.node.id, home.node.bounds, "home")
                }
            }
            return Verdict.Block(SurfaceId.FACEBOOK_REELS, "reels_tab_selected", ladder + Action.Back + Action.Home)
        }

        val reel = findReel(root, snap, false)
        if (reel == null) {
            if (now - lastReelSeenAt > REEL_LOST_MS) resetAllowance()
            return Verdict.None
        }
        lastReelSeenAt = now
        reelHeight = reel.bounds.height
        val block = { reason: String ->
            resetAllowance()
            Verdict.Block(SurfaceId.FACEBOOK_REELS, reason, listOf(Action.Back, Action.Home))
        }

        if (allowScrolled) return block("allowed_reel_scrolled")
        val msgAt = lastMessagingAt
        if (!cfg.allowSharedItem || msgAt == null || now - msgAt >= MESSAGING_WINDOW_MS) return block("reel_in_feed")
        val author = authorOf(root)
        if (!allowArmed) {
            allowArmed = true
            allowAuthor = author
        } else {
            val first = allowAuthor
            if (first == null) allowAuthor = author else if (author != null && author != first) return block("allowed_reel_author_changed")
        }
        return Verdict.None
    }

    /**
     * Marketplace-only: on a top-level screen (tab bar present, selected tab known) that is not Marketplace, tap the
     * Marketplace tab. No tab bar or an unknown selection means no action, so nothing can trap the user.
     */
    private fun marketplaceVerdict(winIdx: Int, root: NodeSnap, tabs: List<Tab>, selected: Tab?): Verdict? {
        if (selected == null || selected.label in FacebookLabels.marketplaceTab) return null
        val market = tabs.firstOrNull { it.label in FacebookLabels.marketplaceTab && !it.selected } ?: return null
        return Verdict.Block(SurfaceId.FACEBOOK_MARKETPLACE_ONLY, "not_marketplace", clicks(winIdx, root, market.node, "marketplace"))
    }

    private enum class GroupsKind { FOR_YOU, YOURS, DISCOVER }

    private class Sub(val kind: GroupsKind, val node: NodeSnap, val selected: Boolean)

    /**
     * Groups tab: keep the user on "Your groups". When the selected sub-tab is "For you" or "Discover" (the endless
     * recommendation feeds), click "Your groups". Needs all three sub-tabs on one row, side by side.
     */
    private fun groupsVerdict(winIdx: Int, root: NodeSnap, selectedTab: Tab?): Verdict? {
        if (selectedTab == null || selectedTab.label !in FacebookLabels.groupsTab) return null
        val subs = subTabs(root)
        val byKind = GroupsKind.entries.associateWith { k -> subs.firstOrNull { it.kind == k } }
        val forYou = byKind[GroupsKind.FOR_YOU] ?: return null
        val yours = byKind[GroupsKind.YOURS] ?: return null
        val discover = byKind[GroupsKind.DISCOVER] ?: return null
        val row = listOf(forYou, yours, discover).sortedBy { it.node.bounds.left }
        val sameBand = row.all { abs(it.node.bounds.centerY - row[0].node.bounds.centerY) <= BAND_PX }
        val apart = row.zipWithNext().all { (a, b) -> a.node.bounds.right <= b.node.bounds.left }
        if (!sameBand || !apart || yours.selected) return null
        if (!forYou.selected && !discover.selected) return null
        return Verdict.Block(SurfaceId.FACEBOOK_GROUPS_FEED, "groups_recommendations", clicks(winIdx, root, yours.node, "your_groups"))
    }

    private fun subTabs(root: NodeSnap): List<Sub> {
        val out = ArrayList<Sub>()
        fun kindOf(label: String) = when (label) {
            in FacebookLabels.groupsForYou -> GroupsKind.FOR_YOU
            in FacebookLabels.groupsYours -> GroupsKind.YOURS
            in FacebookLabels.groupsDiscover -> GroupsKind.DISCOVER
            else -> null
        }
        fun go(n: NodeSnap, ancestors: List<NodeSnap>) {
            if (n.visible && !n.bounds.isEmpty) {
                val label = (n.desc ?: n.text)?.lowercase()?.substringBefore(',')?.substringBefore('،')?.trim()
                kindOf(label ?: "")?.let { out += Sub(it, n, isSelected(n, ancestors)) }
            }
            for (c in n.children) go(c, (ancestors + n).takeLast(ANCESTOR_LEVELS))
        }
        go(root, emptyList())
        return out
    }

    /** Tap first (a 60 ms gesture works where a click does not), then a plain click. */
    private fun clicks(winIdx: Int, root: NodeSnap, node: NodeSnap, label: String): List<Action> {
        val ref = NodeRef(winIdx, root.pathTo(node) ?: return emptyList())
        return listOf(Action.TapNode(ref, node.id, node.bounds, label), Action.ClickNode(ref, node.id, node.bounds, label))
    }

    override fun reset() {
        lastMessagingAt = null
        lastReelSeenAt = 0L
        resetAllowance()
    }

    private fun resetAllowance() {
        allowArmed = false
        allowAuthor = null
        allowScrolled = false
        reelHeight = 0
        cumDy = 0
        lastScrollAt = Long.MIN_VALUE
        baseIndex = -1
    }

    private fun isMessaging(root: NodeSnap): Boolean = root.findAll { n ->
        if (!n.visible) return@findAll false
        val fields = listOfNotNull(n.text, n.desc).map { it.lowercase() }
        val composer = (n.editable || n.cls?.contains("EditText") == true) &&
            fields.any { f -> FacebookLabels.messageHints.any { it in f } }
        composer || fields.any { f -> FacebookLabels.messengerSearch.any { it in f } }
    }.isNotEmpty()

    /** First label part (before ',' or Arabic comma) when [desc] looks like "Label, tab N of M" and is a known tab. */
    private fun tabLabel(n: NodeSnap): String? {
        if (!n.visible || n.bounds.isEmpty) return null
        val d = n.desc?.lowercase() ?: return null
        val label = d.substringBefore(',').substringBefore('،').trim()
        return label.takeIf { it in FacebookLabels.tabLabels && FacebookLabels.tabPosition.containsMatchIn(d) }
    }

    /** Tabs sharing one Y with at least [MIN_TABS_IN_ROW] members; empty when there is no such row. */
    private fun tabRow(root: NodeSnap): List<Tab> {
        val found = ArrayList<Tab>()
        fun go(n: NodeSnap, ancestors: List<NodeSnap>) {
            tabLabel(n)?.let { found += Tab(n, it, isSelected(n, ancestors)) }
            for (c in n.children) go(c, (ancestors + n).takeLast(ANCESTOR_LEVELS))
        }
        go(root, emptyList())
        return found.groupBy { it.node.bounds.centerY / ROW_BUCKET_PX }.values
            .firstOrNull { it.size >= MIN_TABS_IN_ROW } ?: emptyList()
    }

    private fun isSelected(n: NodeSnap, ancestors: List<NodeSnap>): Boolean = n.selected || ancestors.any {
        it.selected && it.visible && it.bounds.contains(n.bounds) &&
            it.bounds.width <= n.bounds.width * 2 && it.bounds.height <= n.bounds.height * 2
    }

    private fun findReel(n: NodeSnap, snap: UiSnapshot, inList: Boolean): NodeSnap? {
        val nowInList = inList || n.id == LIST_ID
        if (!nowInList && coversFraction(n, snap.screen, REEL_W_FRACTION, REEL_H_FRACTION)) {
            val d = n.desc?.lowercase()
            if (d != null && FacebookLabels.reelMarkers.any { it in d }) return n
        }
        for (c in n.children) findReel(c, snap, nowInList)?.let { return it }
        return null
    }

    private fun authorOf(root: NodeSnap): String? {
        var out: String? = null
        root.walk { n, _ ->
            if (out != null || !n.visible) return@walk
            for (s in listOfNotNull(n.desc, n.text)) {
                val m = FacebookLabels.audioAuthor.find(s)?.groupValues?.get(1)
                    ?: FacebookLabels.reelsOfAuthor.find(s)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                if (m != null) {
                    out = m
                    return@walk
                }
            }
        }
        return out
    }

    companion object {
        const val MESSAGING_WINDOW_MS = 15_000L
        const val SCROLL_FRACTION = 0.20
        const val SCROLL_GAP_MS = 1000L
        const val REEL_LOST_MS = 1200L
        const val REEL_W_FRACTION = 0.75
        const val REEL_H_FRACTION = 0.65
        const val MIN_TABS_IN_ROW = 3
        private const val ANCESTOR_LEVELS = 3
        private const val ROW_BUCKET_PX = 16
        private const val BAND_PX = 24
        private const val LIST_ID = "android:id/list"
    }
}

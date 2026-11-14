package com.gainandshine.unloop.core.detect.tiktok

import com.gainandshine.unloop.core.detect.Action
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
 * TikTok (both package variants). Blocks the enabled top-level feed tabs by clicking a safe tab (never relying on
 * Back alone), lets a DM-shared video through for its first @username and a link-opened video for one swipe.
 * Stateful only through sticky-tab memory and the two allowance trackers; [reset] clears all of it.
 */
class TikTokAdapter : SurfaceAdapter {
    override val pkgs = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    override val surfaces = TikTokTab.entries.mapNotNull { it.surface }.toSet()

    private class TabNode(val node: NodeSnap, val tab: TikTokTab, val selected: Boolean)

    // sticky tab memory
    private var stickyTab: TikTokTab? = null
    private var stickyAt = 0L

    // DM-opened video allowance
    private var dmUser: String? = null

    // link-opened video allowance
    private var linkActive = false
    private var linkScrolled = false
    private var linkSeenAt = 0L
    private var pagerHeight = 0
    private var cumDy = 0
    private var lastScrollAt = Long.MIN_VALUE

    /** Feeds scroll deltas to the link-opened-video tracker. */
    override fun onEvent(e: EventRecord) {
        if (e.type != EventType.VIEW_SCROLLED || e.pkg !in pkgs) return
        if (!linkActive || linkScrolled || pagerHeight <= 0 || e.atMs - linkSeenAt > SCROLL_WINDOW_MS) return
        if (abs(e.scrollDx) > abs(e.scrollDy)) return
        if (e.atMs - lastScrollAt > SCROLL_WINDOW_MS) cumDy = 0
        lastScrollAt = e.atMs
        cumDy += abs(e.scrollDy)
        if (cumDy >= pagerHeight * LINK_SWIPE_FRACTION) linkScrolled = true
    }

    override fun evaluate(snap: UiSnapshot, cfg: DetectionConfig): Verdict {
        if (snap.pkg !in pkgs) return Verdict.None
        val winIdx = snap.windows.indexOfFirst { it.pkg == snap.pkg }
        if (winIdx < 0 || surfaces.none(cfg::isOn)) return Verdict.None
        val win = snap.windows[winIdx]
        val root = win.root
        val now = snap.atMs

        val tabNodes = collectTabs(root, win.bounds.height)
        val pager = root.findAll { isPager(it, snap) }.firstOrNull()
        val rawTab = tabNodes.firstOrNull { it.selected && TabZone.TOP in it.tab.zones && it.tab.isFeed }?.tab
            ?: tabNodes.firstOrNull { it.selected && it.tab != TikTokTab.HOME }?.tab
            ?: TikTokTab.UNKNOWN
        val tab = applySticky(rawTab, pager != null, now)

        fun click(t: TabNode): Action? = root.pathTo(t.node)?.let {
            Action.ClickNode(NodeRef(winIdx, it), t.node.id, t.node.bounds, t.tab.name)
        }

        fun ladder(preferFeed: Boolean): List<Action> {
            val feed = if (preferFeed) {
                TikTokLabels.safeFeedOrder.firstNotNullOfOrNull { f ->
                    if (cfg.isOn(f.surface!!)) null else tabNodes.firstOrNull { it.tab == f }?.let(::click)
                }
            } else {
                null
            }
            val inbox = tabNodes.firstOrNull { it.tab == TikTokTab.INBOX }?.let(::click)
            return listOfNotNull(feed, inbox, Action.Back, Action.Home)
        }

        tab.surface?.let { s ->
            if (cfg.isOn(s)) {
                resetAllowances()
                return Verdict.Block(s, "tab_blocked", ladder(true))
            }
        }

        val sentDm = root.findAll { hasDmMarker(it) }.isNotEmpty()
        if (sentDm) {
            resetLink()
            if (!cfg.allowSharedItem) return Verdict.Block(SurfaceId.TIKTOK_FOR_YOU, "dm_video_not_allowed", ladder(false))
            val user = firstUsername(root)
            if (user != null) {
                val first = dmUser
                if (first == null) {
                    dmUser = user
                } else if (first != user) {
                    dmUser = null
                    return Verdict.Block(SurfaceId.TIKTOK_FOR_YOU, "dm_video_user_changed", ladder(false))
                }
            }
            return Verdict.None
        }
        dmUser = null

        val linkMode = tab == TikTokTab.UNKNOWN || tab == TikTokTab.PROFILE
        if (!linkMode || (tab == TikTokTab.PROFILE && pager == null)) {
            resetLink()
            return Verdict.None
        }
        if (pager != null) {
            if (!linkActive) {
                linkActive = true
                linkScrolled = false
                cumDy = 0
            }
            linkSeenAt = now
            pagerHeight = pager.bounds.height
            if (!cfg.allowSharedItem && cfg.isOn(SurfaceId.TIKTOK_FOR_YOU)) {
                return Verdict.Block(SurfaceId.TIKTOK_FOR_YOU, "link_video_not_allowed", ladder(false))
            }
            if (linkScrolled) return Verdict.Block(SurfaceId.TIKTOK_FOR_YOU, "link_video_second", ladder(false))
        } else if (linkActive && now - linkSeenAt > LINK_LOST_MS) {
            resetLink()
        }
        return Verdict.None
    }

    override fun reset() {
        stickyTab = null
        stickyAt = 0L
        resetAllowances()
    }

    private fun resetAllowances() {
        dmUser = null
        resetLink()
    }

    private fun resetLink() {
        linkActive = false
        linkScrolled = false
        linkSeenAt = 0L
        pagerHeight = 0
        cumDy = 0
        lastScrollAt = Long.MIN_VALUE
    }

    private fun applySticky(raw: TikTokTab, isPager: Boolean, now: Long): TikTokTab {
        if (raw.isFeed) {
            stickyTab = raw
            stickyAt = now
            return raw
        }
        if (raw == TikTokTab.UNKNOWN) {
            val s = stickyTab
            if (isPager && s != null && now - stickyAt <= STICKY_MS) {
                stickyAt = now
                return s
            }
            if (!isPager) stickyTab = null
        } else {
            stickyTab = null
        }
        return raw
    }

    private fun collectTabs(root: NodeSnap, rootH: Int): List<TabNode> {
        val out = ArrayList<TabNode>()
        root.walk { n, _ ->
            if (!n.visible || n.bounds.isEmpty) return@walk
            val raw = n.desc?.takeIf { it.isNotBlank() } ?: n.text?.takeIf { it.isNotBlank() } ?: return@walk
            val tab = TikTokLabels.classify(raw)
            if (tab == TikTokTab.UNKNOWN) return@walk
            val cy = n.bounds.centerY
            val top = n.bounds.height <= rootH * TOP_STRIP_MAX_HEIGHT && cy < rootH * TOP_STRIP_MAX_CENTER_Y
            val bottom = cy >= rootH * BOTTOM_BAR_MIN_CENTER_Y
            if ((top && TabZone.TOP in tab.zones) || (bottom && TabZone.BOTTOM in tab.zones)) {
                out += TabNode(n, tab, n.selected || TikTokLabels.labelSaysSelected(raw))
            }
        }
        return out
    }

    private fun isPager(n: NodeSnap, snap: UiSnapshot) =
        n.cls?.contains("ViewPager", ignoreCase = true) == true &&
            coversFraction(n, snap.screen, PAGER_W_FRACTION, PAGER_H_FRACTION)

    private fun hasDmMarker(n: NodeSnap): Boolean {
        if (!n.visible || n.bounds.width <= MIN_PX || n.bounds.height <= MIN_PX) return false
        val d = n.desc?.lowercase() ?: return false
        return TikTokLabels.dmMarkers.any { it in d }
    }

    private fun firstUsername(root: NodeSnap): String? =
        root.findAll { it.visible && isUsername(it.text) }.firstOrNull()?.text

    private fun isUsername(t: String?) = t != null && t.startsWith("@") && t.length in 2..MAX_USERNAME && ' ' !in t

    companion object {
        /** Sticky tab memory lifetime. */
        const val STICKY_MS = 2000L
        const val SCROLL_WINDOW_MS = 1000L
        const val LINK_LOST_MS = 1200L
        /** Cumulative vertical scroll (fraction of pager height) that ends the one-video allowance. */
        const val LINK_SWIPE_FRACTION = 0.15
        const val PAGER_W_FRACTION = 0.75
        const val PAGER_H_FRACTION = 0.65
        const val TOP_STRIP_MAX_HEIGHT = 0.15
        const val TOP_STRIP_MAX_CENTER_Y = 0.24
        const val BOTTOM_BAR_MIN_CENTER_Y = 0.8
        private const val MIN_PX = 10
        private const val MAX_USERNAME = 32
    }
}

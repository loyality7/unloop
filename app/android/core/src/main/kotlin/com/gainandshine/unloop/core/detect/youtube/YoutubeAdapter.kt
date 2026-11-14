package com.gainandshine.unloop.core.detect.youtube

import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.CoverRect
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceAdapter
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.detect.shared.clickableTarget
import com.gainandshine.unloop.core.detect.shared.commentsPanel
import com.gainandshine.unloop.core.detect.shared.firstProminentById
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.core.model.WindowSnap

/** Blocks the YouTube Shorts player when a Shorts container covers more than half the window. Stateless. */
class YoutubeAdapter : SurfaceAdapter {
    override val pkgs = setOf(YoutubeIds.PKG)
    override val surfaces = setOf(SurfaceId.YOUTUBE_SHORTS, SurfaceId.YOUTUBE_SHORTS_TAB, SurfaceId.YOUTUBE_COMMENTS, SurfaceId.YOUTUBE_SEARCH_ONLY)
    override val coverSurfaces = setOf(SurfaceId.YOUTUBE_SHORTS_TAB, SurfaceId.YOUTUBE_SEARCH_ONLY)
    override val coverIds: Map<String, Int> =
        mapOf(YoutubeIds.PIVOT_BAR to PIVOT_DEPTH, YoutubeIds.TOOLBAR to 0) +
            (YoutubeIds.SEARCH_FIELDS + YoutubeIds.PLAYER_IDS).associateWith { 0 }

    override fun evaluate(snap: UiSnapshot, cfg: DetectionConfig): Verdict {
        if (snap.pkg !in pkgs) return Verdict.None
        val noComments = cfg.isOn(SurfaceId.YOUTUBE_COMMENTS)
        if (!noComments && !cfg.isOn(SurfaceId.YOUTUBE_SHORTS)) return Verdict.None
        val w = snap.primary ?: return Verdict.None
        if (noComments && commentsPanel(w) != null) return Verdict.Block(SurfaceId.YOUTUBE_COMMENTS, "comments_panel", listOf(Action.Back))
        if (!cfg.isOn(SurfaceId.YOUTUBE_SHORTS)) return Verdict.None
        firstProminentById(w, YoutubeIds.SHORTS_CONTAINERS) ?: return Verdict.None
        return Verdict.Block(SurfaceId.YOUTUBE_SHORTS, "shorts_player", listOf(Action.Back, Action.Home))
    }

    override fun covers(snap: UiSnapshot, cfg: DetectionConfig): List<CoverRect> {
        if (snap.pkg !in pkgs) return emptyList()
        val w = snap.primary ?: return emptyList()
        val out = ArrayList<CoverRect>(2)
        if (cfg.isOn(SurfaceId.YOUTUBE_SHORTS_TAB)) shortsTabCover(w)?.let { out += it }
        if (cfg.isOn(SurfaceId.YOUTUBE_SEARCH_ONLY)) searchOnlyCover(w)?.let { out += it }
        return out
    }

    /** Hide the Shorts button in the bottom bar: the node labelled "Shorts" inside the pivot bar. */
    private fun shortsTabCover(w: WindowSnap): CoverRect? {
        val bar = w.root.findAll { it.id == YoutubeIds.PIVOT_BAR && it.visible && !it.bounds.isEmpty }.firstOrNull() ?: return null
        val label = bar.findAll { isShortsLabel(it) && it.visible && !it.bounds.isEmpty }.firstOrNull() ?: return null
        val r = (clickableTarget(bar, label) ?: label).bounds
        // Stay inside the bar: a mis-resolved ancestor can never cover more than the bar itself.
        val clipped = Rect(
            maxOf(r.left, bar.bounds.left), maxOf(r.top, bar.bounds.top),
            minOf(r.right, bar.bounds.right), minOf(r.bottom, bar.bounds.bottom),
        )
        return if (clipped.isEmpty) null else CoverRect(SurfaceId.YOUTUBE_SHORTS_TAB, "shorts_tab", clipped)
    }

    /**
     * Search-only mode: hide the Home / Subscriptions feed, keep the toolbar (search) and the bottom bar. The feed is
     * on screen only when no search field is showing, no video player is showing, the bottom bar is in the lower half,
     * the toolbar sits in the top third, and the selected bottom tab is Home or Subscriptions.
     */
    private fun searchOnlyCover(w: WindowSnap): CoverRect? {
        fun visible(id: String) = w.root.findAll { it.id == id && it.visible && !it.bounds.isEmpty }
        if (YoutubeIds.SEARCH_FIELDS.any { visible(it).isNotEmpty() }) return null
        if (YoutubeIds.PLAYER_IDS.any { visible(it).isNotEmpty() }) return null
        val bar = visible(YoutubeIds.PIVOT_BAR).firstOrNull() ?: return null
        if (bar.bounds.centerY <= w.bounds.centerY) return null
        val toolbar = visible(YoutubeIds.TOOLBAR).firstOrNull() ?: return null
        if (toolbar.bounds.bottom >= w.bounds.top + w.bounds.height / 3) return null
        if (!feedTabSelected(bar)) return null
        val rect = Rect(w.bounds.left, toolbar.bounds.bottom, w.bounds.right, bar.bounds.top)
        return if (rect.isEmpty) null else CoverRect(SurfaceId.YOUTUBE_SEARCH_ONLY, "feed", rect)
    }

    private fun feedTabSelected(bar: NodeSnap): Boolean {
        fun label(n: NodeSnap) = listOfNotNull(n.text, n.desc).firstOrNull { it.isNotBlank() }?.lowercase()?.substringBefore(',')?.trim()
        fun go(n: NodeSnap, ancestors: List<NodeSnap>): Boolean {
            if (n.visible && label(n) in YoutubeIds.FEED_TABS && (n.selected || ancestors.any { it.selected })) return true
            return n.children.any { go(it, (ancestors + n).takeLast(3)) }
        }
        return go(bar, emptyList())
    }

    /** Matches "Shorts" or "Shorts, tab 2 of 5" in text or content description, case-insensitive. */
    private fun isShortsLabel(n: NodeSnap): Boolean =
        listOfNotNull(n.text, n.desc).any { it.substringBefore(',').trim().equals(YoutubeIds.SHORTS_LABEL, ignoreCase = true) }

    override fun reset() = Unit

    private companion object {
        /** pivot_bar > tab item > icon/label: the Shorts label sits a few levels down. */
        const val PIVOT_DEPTH = 5
    }
}

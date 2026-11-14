package com.gainandshine.unloop.core.detect.instagram

import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.CoverRect
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceAdapter
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.detect.isProminent
import com.gainandshine.unloop.core.detect.shared.AllowanceTracker
import com.gainandshine.unloop.core.detect.shared.LabelPost
import com.gainandshine.unloop.core.detect.shared.SPONSORED_LABELS
import com.gainandshine.unloop.core.detect.shared.SUGGESTED_LABELS
import com.gainandshine.unloop.core.detect.shared.clickAction
import com.gainandshine.unloop.core.detect.shared.clickableTarget
import com.gainandshine.unloop.core.detect.shared.collectByIds
import com.gainandshine.unloop.core.detect.shared.commentsPanel
import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.core.model.WindowSnap

/**
 * Blocks the Instagram Reels viewer and the selected Reels tab.
 *
 * Primary signal: a viewer container that is prominent in the window (ids and geometry only, no locale text).
 * Secondary: the Reels tab is selected (unreliable on some OEMs, so the viewer check does not depend on it).
 * One shared reel is allowed (see [AllowanceTracker]) when it was opened from a Story, a DM (reply composer present)
 * or a deep link, and [DetectionConfig.allowSharedItem] is on. Story viewers are never blocked.
 */
class InstagramAdapter : SurfaceAdapter {
    override val pkgs = setOf(InstagramIds.PKG)
    override val surfaces = setOf(
        SurfaceId.INSTAGRAM_REELS, SurfaceId.INSTAGRAM_REELS_TAB, SurfaceId.INSTAGRAM_EXPLORE_TAB,
        SurfaceId.INSTAGRAM_STORIES, SurfaceId.INSTAGRAM_CAUGHT_UP, SurfaceId.INSTAGRAM_DMS_ONLY,
        SurfaceId.INSTAGRAM_COMMENTS, SurfaceId.INSTAGRAM_SPONSORED, SurfaceId.INSTAGRAM_SUGGESTED, SurfaceId.INSTAGRAM_MESSAGES_TAB,
    )

    override val coverSurfaces = setOf(
        SurfaceId.INSTAGRAM_REELS_TAB, SurfaceId.INSTAGRAM_EXPLORE_TAB, SurfaceId.INSTAGRAM_STORIES, SurfaceId.INSTAGRAM_CAUGHT_UP,
        SurfaceId.INSTAGRAM_SPONSORED, SurfaceId.INSTAGRAM_SUGGESTED, SurfaceId.INSTAGRAM_MESSAGES_TAB,
    )
    override fun coverTexts(cfg: DetectionConfig): Set<String> =
        (if (cfg.isOn(SurfaceId.INSTAGRAM_SPONSORED)) SPONSORED_LABELS else emptySet()) +
            (if (cfg.isOn(SurfaceId.INSTAGRAM_SUGGESTED)) SUGGESTED_LABELS else emptySet())
    override val coverIds: Map<String, Int> = InstagramIds.COVER_TARGETS.associateWith { 0 }

    private val tracker = AllowanceTracker()
    private var lastStoryMs: Long? = null

    override fun onEvent(e: EventRecord) {
        if (e.pkg != null && e.pkg !in pkgs) return
        when (e.type) {
            EventType.WINDOW_STATE_CHANGED ->
                if (!e.isPaneChange && e.cls in InstagramIds.DEEP_LINK_ACTIVITIES) tracker.arm(e.atMs)
            EventType.VIEW_SCROLLED -> tracker.onScroll(e.atMs, e.scrollDx, e.scrollDy, e.fromIndex)
            else -> Unit
        }
    }

    override fun evaluate(snap: UiSnapshot, cfg: DetectionConfig): Verdict {
        if (snap.pkg !in pkgs) return Verdict.None
        val w = snap.primary ?: return Verdict.None
        val dmsOnly = cfg.isOn(SurfaceId.INSTAGRAM_DMS_ONLY)
        val noComments = cfg.isOn(SurfaceId.INSTAGRAM_COMMENTS)
        if (!dmsOnly && !noComments && !cfg.isOn(SurfaceId.INSTAGRAM_REELS)) return Verdict.None
        if (noComments && commentsPanel(w) != null) return Verdict.Block(SurfaceId.INSTAGRAM_COMMENTS, "comments_panel", listOf(Action.Back))
        val found = collectByIds(w.root, InstagramIds.ALL)
        if (dmsOnly) dmsOnlyVerdict(snap, w, found)?.let { return it }
        if (!cfg.isOn(SurfaceId.INSTAGRAM_REELS)) return Verdict.None
        fun any(id: String) = found[id].orEmpty().any { isProminent(it, w.bounds) }

        val viewer = InstagramIds.VIEWER_CONTAINERS.flatMap { found[it].orEmpty() }.firstOrNull { isProminent(it, w.bounds) }
        val story = any(InstagramIds.STORY_ROOT)
        val tab = found[InstagramIds.CLIPS_TAB].orEmpty().firstOrNull {
            it.visible && it.enabled && it.selected && w.bounds.contains(it.bounds)
        }
        val now = snap.atMs

        if (viewer == null) {
            if (story) lastStoryMs = now
            return if (tab != null && !story) blockTab(snap, w, found, tab) else Verdict.None
        }
        if (tab != null) {
            tracker.reset()
            return blockTab(snap, w, found, tab)
        }
        if (cfg.allowSharedItem) {
            val composer = found[InstagramIds.STORY_COMPOSER].orEmpty().any { it.visible }
            val fromStory = lastStoryMs?.let { now - it in 0..InstagramIds.STORY_TO_REEL_MS } == true
            if (composer || fromStory) tracker.arm(now)
            if (fromStory) lastStoryMs = null
            val author = found[InstagramIds.AUTHOR].orEmpty().firstOrNull { it.visible }?.text
            if (tracker.observe(now, author, viewer.bounds.height)) return Verdict.None
        } else {
            tracker.reset()
        }
        return Verdict.Block(SurfaceId.INSTAGRAM_REELS, REASON_VIEWER, listOf(Action.Back, Action.Home))
    }

    /**
     * Messages-only: with the main tab bar on screen and the Messages tab not selected (and no inbox header or open
     * thread), click the Messages tab. Screens without the tab bar (login, full-screen viewers, sheets) are left alone,
     * so the mode can never trap the user inside a dead end.
     */
    private fun dmsOnlyVerdict(snap: UiSnapshot, w: WindowSnap, found: Map<String, List<NodeSnap>>): Verdict? {
        fun visible(id: String) = found[id].orEmpty().any { it.visible && !it.bounds.isEmpty }
        if (visible(InstagramIds.DM_INBOX_BAR) || visible(InstagramIds.THREAD_COMPOSER)) return null
        val dm = found[InstagramIds.DIRECT_TAB].orEmpty().firstOrNull { it.visible && !it.bounds.isEmpty } ?: return null
        if (dm.selected) return null
        val click = clickAction(snap.windows.indexOf(w), w.root, dm, "messages_tab") ?: return null
        return Verdict.Block(SurfaceId.INSTAGRAM_DMS_ONLY, REASON_NOT_DM, listOf(click))
    }

    /** Reels tab selected: prefer the Messages tab on the same row, then Back, then Home. */
    private fun blockTab(snap: UiSnapshot, w: WindowSnap, found: Map<String, List<NodeSnap>>, clips: NodeSnap): Verdict {
        val dm = found[InstagramIds.DIRECT_TAB].orEmpty().firstOrNull {
            !it.selected && it.visible && it.bounds.top < clips.bounds.bottom && it.bounds.bottom > clips.bounds.top
        }
        val click = dm?.let { clickAction(snap.windows.indexOf(w), w.root, it, "messages_tab") }
        return Verdict.Block(SurfaceId.INSTAGRAM_REELS, REASON_TAB, listOfNotNull(click, Action.Back, Action.Home))
    }

    /**
     * Hide the Reels tab button, the Explore tab button, the Stories row and everything after the
     * "all caught up" marker. Each cover needs its own node on screen; a missing id gives no cover (fail open).
     */
    override fun covers(snap: UiSnapshot, cfg: DetectionConfig): List<CoverRect> {
        if (snap.pkg !in pkgs) return emptyList()
        val w = snap.primary ?: return emptyList()
        if (surfaces.none(cfg::isOn)) return emptyList()
        val found = collectByIds(w.root, InstagramIds.COVER_TARGETS)
        fun visible(id: String) =
            found[id].orEmpty().firstOrNull { it.visible && !it.bounds.isEmpty && it.bounds.intersects(w.bounds) }
        fun clip(r: Rect) = Rect(
            maxOf(r.left, w.bounds.left), maxOf(r.top, w.bounds.top),
            minOf(r.right, w.bounds.right), minOf(r.bottom, w.bounds.bottom),
        )
        fun tabRect(n: NodeSnap) = clip((clickableTarget(w.root, n) ?: n).bounds)

        val out = ArrayList<CoverRect>(4)
        if (cfg.isOn(SurfaceId.INSTAGRAM_REELS_TAB)) {
            visible(InstagramIds.CLIPS_TAB)?.let { out += CoverRect(SurfaceId.INSTAGRAM_REELS_TAB, "reels_tab", tabRect(it)) }
        }
        if (cfg.isOn(SurfaceId.INSTAGRAM_EXPLORE_TAB)) {
            visible(InstagramIds.SEARCH_TAB)?.let { out += CoverRect(SurfaceId.INSTAGRAM_EXPLORE_TAB, "explore_tab", tabRect(it)) }
        }
        if (cfg.isOn(SurfaceId.INSTAGRAM_MESSAGES_TAB)) {
            visible(InstagramIds.DIRECT_TAB)?.let { out += CoverRect(SurfaceId.INSTAGRAM_MESSAGES_TAB, "messages_tab", tabRect(it)) }
        }
        if (cfg.isOn(SurfaceId.INSTAGRAM_STORIES)) {
            visible(InstagramIds.STORIES_TRAY)?.let { out += CoverRect(SurfaceId.INSTAGRAM_STORIES, "stories_row", clip(it.bounds), follows = true) }
        }
        if (cfg.isOn(SurfaceId.INSTAGRAM_CAUGHT_UP)) {
            val marker = visible(InstagramIds.CAUGHT_UP)
            val bar = visible(InstagramIds.TAB_BAR)
            // Only with a visible tab bar: the cover must stop above it so navigation stays usable.
            if (marker != null && bar != null && bar.bounds.top > marker.bounds.top) {
                out += CoverRect(
                    SurfaceId.INSTAGRAM_CAUGHT_UP, "after_caught_up",
                    clip(Rect(w.bounds.left, marker.bounds.top, w.bounds.right, bar.bounds.top)),
                    follows = true, pinBottom = true,
                )
            }
        }
        // Hide posts carrying a label ("Sponsored", "Suggested for you"): cover each such post, down to the tab bar at most.
        val limit = visible(InstagramIds.TAB_BAR)?.bounds?.top ?: w.bounds.bottom
        for ((surface, labels) in listOf(SurfaceId.INSTAGRAM_SPONSORED to SPONSORED_LABELS, SurfaceId.INSTAGRAM_SUGGESTED to SUGGESTED_LABELS)) {
            if (!cfg.isOn(surface)) continue
            LabelPost.rects(w, labels, limit).forEachIndexed { i, r -> out += CoverRect("$surface#$i", "labelled_post", r, follows = true) }
        }
        return out.filterNot { it.bounds.isEmpty }
    }

    override fun reset() {
        tracker.reset()
        lastStoryMs = null
    }

    private companion object {
        const val REASON_VIEWER = "reels_viewer"
        const val REASON_TAB = "reels_tab"
        const val REASON_NOT_DM = "not_in_messages"
    }
}

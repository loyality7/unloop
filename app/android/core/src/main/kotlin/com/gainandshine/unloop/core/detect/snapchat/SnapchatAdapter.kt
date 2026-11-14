package com.gainandshine.unloop.core.detect.snapchat

import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceAdapter
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.detect.isProminent
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.UiSnapshot

/** Snapchat view ids and labels as data. Ids are full resource names. */
object SnapchatIds {
    const val PKG = "com.snapchat.android"
    private const val P = "$PKG:id/"
    const val SPOTLIGHT_CONTAINER = P + "spotlight_container"
    const val SPOTLIGHT_TAB = P + "ngs_spotlight_icon_container"
    const val PLAYER = P + "opera_viewer"
    const val FAVORITE_SUFFIX = ":id/favorite"

    /** Substrings of ids that only exist on Discover content. */
    val discoverIdParts = listOf("chrome_subscribe_button", "AdsFloatingPillView", "sc_composer_ad_cta_tappable_view")
    val discoverTexts = listOf("View Profile")
}

/**
 * Snapchat Spotlight and Discover. Stateless: every frame is judged on its own, over every Snapchat window.
 * Stories, Chat and Snaps never carry the ids above, and a story player lacks the action rail, so they do not match.
 */
class SnapchatAdapter : SurfaceAdapter {
    override val pkgs = setOf(SnapchatIds.PKG)
    override val surfaces = setOf(SurfaceId.SNAPCHAT_SPOTLIGHT, SurfaceId.SNAPCHAT_DISCOVER)

    override fun evaluate(snap: UiSnapshot, cfg: DetectionConfig): Verdict {
        if (snap.pkg !in pkgs) return Verdict.None
        val spotlightOn = cfg.isOn(SurfaceId.SNAPCHAT_SPOTLIGHT)
        val discoverOn = cfg.isOn(SurfaceId.SNAPCHAT_DISCOVER)
        if (!spotlightOn && !discoverOn) return Verdict.None
        for (w in snap.windows) {
            if (w.pkg != SnapchatIds.PKG) continue
            val reason = if (spotlightOn) spotlightReason(w.root, w.bounds) else null
            if (reason != null) return block(SurfaceId.SNAPCHAT_SPOTLIGHT, reason)
            if (discoverOn && isDiscover(w.root, w.bounds)) return block(SurfaceId.SNAPCHAT_DISCOVER, "discover_markers")
        }
        return Verdict.None
    }

    override fun reset() = Unit

    private fun block(surface: String, reason: String) =
        Verdict.Block(surface, reason, listOf(Action.Back, Action.Home))

    private fun spotlightReason(root: NodeSnap, win: Rect): String? = when {
        root.findAll { it.id == SnapchatIds.SPOTLIGHT_CONTAINER }.any { isProminent(it, win) } -> "spotlight_container"
        root.findAll { it.id == SnapchatIds.SPOTLIGHT_TAB }.any { it.selected && it.visible } -> "spotlight_tab_selected"
        hasSpotlightGeometry(root, win) -> "spotlight_geometry"
        else -> null
    }

    private fun seen(n: NodeSnap, win: Rect) =
        n.visible && n.bounds.width > MIN_PX && n.bounds.height > MIN_PX && n.bounds.intersects(win)

    private fun isDiscover(root: NodeSnap, win: Rect): Boolean = root.findAll { n ->
        seen(n, win) && (
            n.id?.let { id -> SnapchatIds.discoverIdParts.any { it in id } } == true ||
                SnapchatIds.discoverTexts.any { t ->
                    n.text?.contains(t, ignoreCase = true) == true || n.desc?.contains(t, ignoreCase = true) == true
                }
            )
    }.isNotEmpty()

    /** A full-size opera_viewer holding a right-edge action rail (>= 3 clickable children plus the favorite button). */
    private fun hasSpotlightGeometry(root: NodeSnap, win: Rect): Boolean {
        val players = root.findAll(maxDepth = MAX_DEPTH, maxNodes = MAX_NODES) {
            it.id == SnapchatIds.PLAYER && seen(it, win) &&
                it.bounds.width > win.width / 2 && it.bounds.height > win.height / 2
        }
        return players.any { p -> p.findAll(maxDepth = MAX_DEPTH, maxNodes = MAX_NODES) { isRail(it, win) }.isNotEmpty() }
    }

    private fun isRail(n: NodeSnap, win: Rect): Boolean {
        val b = n.bounds
        if (!n.visible || !b.intersects(win)) return false
        if (b.left < win.left + win.width * 2 / 3 || b.width >= win.width / 3 || b.height <= win.height / 3) return false
        val clickable = n.children.count { it.visible && it.clickable }
        val favorite = n.findAll { it.id?.endsWith(SnapchatIds.FAVORITE_SUFFIX) == true }.isNotEmpty()
        return clickable >= MIN_RAIL_CLICKABLE && favorite
    }

    companion object {
        private const val MIN_PX = 10
        private const val MAX_DEPTH = 50
        private const val MAX_NODES = 600
        const val MIN_RAIL_CLICKABLE = 3
    }
}

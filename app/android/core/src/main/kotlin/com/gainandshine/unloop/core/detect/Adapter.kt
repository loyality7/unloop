package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.UiSnapshot

/** Stable ids shared with Dart and storage. Never rename. */
object SurfaceId {
    const val INSTAGRAM_REELS = "instagram_reels"
    const val YOUTUBE_SHORTS = "youtube_shorts"
    const val TIKTOK_FOR_YOU = "tiktok_for_you"
    const val TIKTOK_FOLLOWING = "tiktok_following"
    const val TIKTOK_FRIENDS = "tiktok_friends"
    const val TIKTOK_LOCAL = "tiktok_local"
    const val TIKTOK_COMMUNITY = "tiktok_community"
    const val TIKTOK_STEM = "tiktok_stem"
    const val TIKTOK_SHOP = "tiktok_shop"
    const val TIKTOK_LIVE = "tiktok_live"
    const val SNAPCHAT_SPOTLIGHT = "snapchat_spotlight"
    const val SNAPCHAT_DISCOVER = "snapchat_discover"
    const val FACEBOOK_REELS = "facebook_reels"
    const val INSTAGRAM_DMS_ONLY = "instagram_dms_only"
    const val INSTAGRAM_COMMENTS = "instagram_comments"
    const val INSTAGRAM_SPONSORED = "instagram_sponsored"
    const val INSTAGRAM_SUGGESTED = "instagram_suggested"
    const val INSTAGRAM_MESSAGES_TAB = "instagram_messages_tab"
    const val FACEBOOK_SUGGESTED = "facebook_suggested"
    const val X_FOR_YOU = "x_for_you"
    const val X_EXPLORE = "x_explore"
    const val X_DMS_ONLY = "x_dms_only"
    const val YOUTUBE_COMMENTS = "youtube_comments"
    const val YOUTUBE_SEARCH_ONLY = "youtube_search_only"
    const val FACEBOOK_MARKETPLACE_ONLY = "facebook_marketplace_only"
    const val FACEBOOK_GROUPS_FEED = "facebook_groups_feed"

    // Cover surfaces: parts of the screen hidden under an opaque window instead of reacting after a tap.
    const val INSTAGRAM_REELS_TAB = "instagram_reels_tab"
    const val INSTAGRAM_EXPLORE_TAB = "instagram_explore_tab"
    const val INSTAGRAM_STORIES = "instagram_stories"
    const val INSTAGRAM_CAUGHT_UP = "instagram_caught_up"
    const val YOUTUBE_SHORTS_TAB = "youtube_shorts_tab"

    /** Endless-feed surfaces whose swipes can be counted. */
    val COUNTABLE: Set<String> = setOf(INSTAGRAM_REELS, YOUTUBE_SHORTS, TIKTOK_FOR_YOU, SNAPCHAT_SPOTLIGHT, FACEBOOK_REELS)

    /** The feed surface whose daily allowance also lifts this cover (a button hiding the way into that feed). */
    fun budgetParent(cover: String): String? = when (cover) {
        INSTAGRAM_REELS_TAB -> INSTAGRAM_REELS
        YOUTUBE_SHORTS_TAB -> YOUTUBE_SHORTS
        else -> null
    }
}

/** Reference to a node inside a [UiSnapshot]: window index and child-index path from that window's root. */
data class NodeRef(val window: Int, val path: List<Int>)

/**
 * What the shell should do. Ordered into an escalation ladder by the adapter (first = preferred).
 * Click/Tap carry the snapshot bounds and id so the shell can re-resolve the live node and refuse if it moved.
 */
sealed interface Action {
    data object Back : Action
    data object Home : Action
    data class ClickNode(val ref: NodeRef, val id: String?, val bounds: Rect, val label: String) : Action
    data class TapNode(val ref: NodeRef, val id: String?, val bounds: Rect, val label: String) : Action
}

/** Result of evaluating one snapshot. */
sealed interface Verdict {
    data object None : Verdict

    /** The user is on a surface that is switched off. [ladder] is tried in order, verifying after each rung. */
    data class Block(
        val surface: String,
        val reason: String, // short, no user content
        val ladder: List<Action>,
        val confidence: Confidence = Confidence.HIGH,
    ) : Verdict
}

enum class Confidence { LOW, MEDIUM, HIGH }

/**
 * A screen region to hide under an opaque cover window.
 * [bounds] are absolute screen pixels, already clipped to the app window. Covers are recomputed on every scan,
 * so a cover follows scrolling and disappears the moment its node does. If an app update renames an id the
 * adapter simply finds nothing and returns no cover (fail open: nothing is hidden by mistake).
 */
data class CoverRect(
    val surface: String,
    val label: String,
    val bounds: Rect,
    /** True when the hidden thing moves with scrolling content (Stories row, feed end). Such a cover is dropped at once when its node goes. */
    val follows: Boolean = false,
    /** For a following cover that ends at something fixed (the tab bar): scrolling moves only its top edge. */
    val pinBottom: Boolean = false,
)

/** User-controlled switches. Sensible defaults. */
data class DetectionConfig(
    val enabled: Set<String> = setOf(SurfaceId.INSTAGRAM_REELS, SurfaceId.YOUTUBE_SHORTS),
    /** One reel opened from a DM / Story / shared link is let through. */
    val allowSharedItem: Boolean = true,
    /** Show how many items were swiped past, even where nothing is blocked. */
    val counter: Boolean = false,
    /** Send the user Home after this many items (0 = never). */
    val autoKickAfter: Int = 0,
) {
    fun isOn(surface: String) = surface in enabled

    /** Counting is wanted: for the on-screen counter or for auto-kick. */
    val counting: Boolean get() = counter || autoKickAfter > 0
}

/**
 * One adapter per app. Adapters are stateful (entry-source trackers) but only through their own fields;
 * the engine calls [reset] when the app leaves the foreground. All time is [UiSnapshot.atMs] (monotonic).
 */
interface SurfaceAdapter {
    val pkgs: Set<String>

    /** Surfaces this adapter can report, for UI and config validation. */
    val surfaces: Set<String>

    /** Feed the adapter raw events (scroll deltas, window class names) for entry-source tracking. */
    fun onEvent(e: EventRecord) {}

    fun evaluate(snap: UiSnapshot, cfg: DetectionConfig): Verdict

    /** Surfaces this adapter hides with covers (a subset of [surfaces]). */
    val coverSurfaces: Set<String> get() = emptySet()

    /**
     * View ids [covers] needs, each with how many levels of children below it must be read (0 = the node alone).
     * Lets the shell refresh covers many times a second from a few targeted lookups instead of a full tree walk.
     */
    val coverIds: Map<String, Int> get() = emptyMap()

    /**
     * Texts the fast cover path should search for (the text and description lookups the framework offers), for covers
     * located by a label rather than a view id, such as "Sponsored". Empty when that cover is switched off.
     */
    fun coverTexts(cfg: DetectionConfig): Set<String> = emptySet()

    /** Regions to hide for this snapshot. Default none. Must be cheap, pure and must not mutate [snap]. */
    fun covers(snap: UiSnapshot, cfg: DetectionConfig): List<CoverRect> = emptyList()

    fun reset()
}

/** Prominence: visible, intersecting, and covering more than half the window in both axes. */
fun isProminent(node: NodeSnap, window: Rect): Boolean =
    node.visible && !node.bounds.isEmpty && node.bounds.intersects(window) &&
        node.bounds.width > window.width / 2 && node.bounds.height > window.height / 2

/** Same, with explicit fractions (Facebook uses 0.75 x 0.65, TikTok pager 0.75 x 0.65). */
fun coversFraction(node: NodeSnap, window: Rect, wFrac: Double, hFrac: Double): Boolean =
    node.visible && !node.bounds.isEmpty && node.bounds.intersects(window) &&
        node.bounds.width >= window.width * wFrac && node.bounds.height >= window.height * hFrac

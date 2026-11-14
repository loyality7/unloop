package com.gainandshine.unloop.core.detect.instagram

/** Instagram resource ids, deep-link activities and tuning constants. Single place to patch on app updates. */
object InstagramIds {
    const val PKG = "com.instagram.android"
    private const val P = "$PKG:id/"

    val VIEWER_CONTAINERS = setOf(P + "clips_viewer_view_pager", P + "clips_viewer_container", P + "clips_viewer_video_layout")
    const val CLIPS_TAB = P + "clips_tab"
    const val DIRECT_TAB = P + "direct_tab"
    const val STORY_ROOT = P + "reel_viewer_root"
    const val STORY_COMPOSER = P + "reel_viewer_message_composer"
    const val AUTHOR = P + "clips_author_username"

    // Cover targets: bottom bar tabs, Stories row, the "all caught up" marker.
    const val SEARCH_TAB = P + "search_tab"
    const val TAB_BAR = P + "tab_bar"
    const val STORIES_TRAY = P + "reels_tray_container"
    const val CAUGHT_UP = P + "end_of_feed_demarcator_container"
    val COVER_TARGETS = setOf(CLIPS_TAB, SEARCH_TAB, DIRECT_TAB, TAB_BAR, STORIES_TRAY, CAUGHT_UP)

    val DEEP_LINK_ACTIVITIES = setOf(
        "com.instagram.urlhandlers.clips.ClipsUrlHandlerActivity",
        "com.instagram.urlhandlers.media.MediaUrlHandlerActivity",
    )

    /** A reel opened this soon after leaving a Story viewer counts as shared from the Story. */
    const val STORY_TO_REEL_MS = 1_500L

    // Messages-only mode: the DM inbox header and an open thread's composer mean the user is in messages.
    const val DM_INBOX_BAR = P + "direct_inbox_action_bar"
    const val THREAD_COMPOSER = P + "row_thread_composer_edittext"

    val ALL = VIEWER_CONTAINERS + setOf(CLIPS_TAB, DIRECT_TAB, STORY_ROOT, STORY_COMPOSER, AUTHOR, DM_INBOX_BAR, THREAD_COMPOSER)
}

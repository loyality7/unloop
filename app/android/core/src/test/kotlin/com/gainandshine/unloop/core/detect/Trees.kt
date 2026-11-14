package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.n

/** Synthetic accessibility trees shared by adapter tests and the committed fixtures. */
object Trees {
    const val IG = "com.instagram.android"
    const val YT = "com.google.android.youtube"
    const val DEEP_LINK = "com.instagram.urlhandlers.clips.ClipsUrlHandlerActivity"

    fun ig(s: String) = "$IG:id/$s"
    fun yt(s: String) = "$YT:id/$s"

    /** Bottom tab bar. direct_tab sits inside a clickable wrapper, like the real app. */
    fun igTabBar(clipsSelected: Boolean = false, dmTop: Int = 2200): NodeSnap = n(
        ig("tab_bar"), 0, 2200, 1080, 2400,
        kids = listOf(
            n(ig("feed_tab"), 0, 2200, 216, 2400, clickable = true),
            n(ig("clips_tab"), 216, 2200, 432, 2400, clickable = true, selected = clipsSelected),
            n(null, 432, dmTop, 648, dmTop + 200, clickable = true, kids = listOf(n(ig("direct_tab"), 450, dmTop + 10, 630, dmTop + 190))),
        ),
    )

    fun igViewer(
        author: String? = "author_a",
        composer: Boolean = false,
        right: Int = 1080,
        bottom: Int = 2400,
        container: String = "clips_viewer_container",
    ): NodeSnap {
        val kids = ArrayList<NodeSnap>()
        if (author != null) kids += n(ig("clips_author_username"), 40, 2000, 400, 2060, text = author)
        if (composer) kids += n(ig("reel_viewer_message_composer"), 40, 2100, 1040, 2180)
        return n(ig(container), 0, 0, right, bottom, kids = kids)
    }

    fun igFeed(): NodeSnap = n(ig("feed_list"), 0, 150, 1080, 2200, kids = listOf(n(ig("row_feed_photo"), 0, 200, 1080, 1200)))

    fun igStory(): NodeSnap = n(ig("reel_viewer_root"), 0, 0, 1080, 2400)

    fun ytShorts(id: String = "reel_player_page_container", right: Int = 1080, bottom: Int = 2400): NodeSnap =
        n(yt(id), 0, 0, right, bottom)

    fun ytWatch(): NodeSnap = n(yt("watch_player"), 0, 0, 1080, 607)

    /** Home feed whose small Shorts shelf reuses the Shorts recycler id at 1080x200. */
    fun ytFeedWithShelf(): NodeSnap = n(
        yt("results"), 0, 150, 1080, 2200,
        kids = listOf(n(yt("reel_recycler"), 0, 800, 1080, 1000), n(yt("thumbnail"), 0, 1000, 1080, 1600)),
    )

    fun ev(
        type: EventType,
        atMs: Long,
        cls: String? = null,
        pkg: String? = IG,
        dx: Int = 0,
        dy: Int = 0,
        from: Int = -1,
        contentChangeTypes: Int = 0,
    ) = EventRecord(type, pkg, cls, fromIndex = from, scrollDx = dx, scrollDy = dy, contentChangeTypes = contentChangeTypes, atMs = atMs)

    fun deepLink(atMs: Long, pkg: String? = IG) = ev(EventType.WINDOW_STATE_CHANGED, atMs, DEEP_LINK, pkg)

    fun scroll(atMs: Long, dy: Int, dx: Int = 0, from: Int = -1) = ev(EventType.VIEW_SCROLLED, atMs, dy = dy, dx = dx, from = from)
}

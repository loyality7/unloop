package com.gainandshine.unloop.core.fixture

import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Trees
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.snapshot

/** Source of truth for the committed synthetic fixtures (regenerate: create core/.regen-fixtures, run tests, delete it). */
object FixtureSet {
    private const val NONE = "none"
    private val IG_BLOCK = "block:${SurfaceId.INSTAGRAM_REELS}"
    private val YT_BLOCK = "block:${SurfaceId.YOUTUBE_SHORTS}"

    private fun ig(at: Long, vararg k: NodeSnap) = FixtureItem.Snap(snapshot(Trees.IG, at, *k))
    private fun yt(at: Long, vararg k: NodeSnap) = FixtureItem.Snap(snapshot(Trees.YT, at, *k))
    private fun ev(e: com.gainandshine.unloop.core.model.EventRecord) = FixtureItem.Ev(e)

    val all: Map<String, Fixture> = linkedMapOf(
        "ig_reels_viewer" to Fixture(
            "instagram", listOf(IG_BLOCK, IG_BLOCK),
            listOf(ig(0, Trees.igViewer(), Trees.igTabBar()), ig(200, Trees.igViewer(), Trees.igTabBar())),
        ),
        "ig_feed" to Fixture("instagram", listOf(NONE, NONE), listOf(ig(0, Trees.igFeed(), Trees.igTabBar()), ig(200, Trees.igFeed(), Trees.igTabBar()))),
        "ig_story_viewer" to Fixture("instagram", listOf(NONE), listOf(ig(0, Trees.igStory()))),
        "ig_dm_reel_author_change" to Fixture(
            "instagram", listOf(NONE, NONE, IG_BLOCK),
            listOf(
                ig(0, Trees.igViewer("author_a", composer = true)),
                ig(300, Trees.igViewer("author_a", composer = true)),
                ig(600, Trees.igViewer("author_b", composer = true)),
            ),
        ),
        "ig_deeplink_then_swipe" to Fixture(
            "instagram", listOf(NONE, NONE, IG_BLOCK),
            listOf(
                ev(Trees.deepLink(0)),
                ig(100, Trees.igViewer()),
                ev(Trees.scroll(400, -100)),
                ig(500, Trees.igViewer()),
                ev(Trees.scroll(700, -200)),
                ig(800, Trees.igViewer()),
            ),
        ),
        "ig_reels_tab" to Fixture("instagram", listOf(IG_BLOCK), listOf(ig(0, Trees.igFeed(), Trees.igTabBar(clipsSelected = true)))),
        "yt_shorts" to Fixture("youtube", listOf(YT_BLOCK), listOf(yt(0, Trees.ytShorts()))),
        "yt_watch" to Fixture("youtube", listOf(NONE), listOf(yt(0, Trees.ytWatch()))),
        "yt_feed_small_shelf" to Fixture("youtube", listOf(NONE), listOf(yt(0, Trees.ytFeedWithShelf()))),
    )
}

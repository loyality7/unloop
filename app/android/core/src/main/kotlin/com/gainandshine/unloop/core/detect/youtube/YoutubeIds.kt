package com.gainandshine.unloop.core.detect.youtube

/** YouTube resource ids for the Shorts player. */
object YoutubeIds {
    const val PKG = "com.google.android.youtube"
    private const val P = "$PKG:id/"
    const val PIVOT_BAR = P + "pivot_bar"

    // Search-only mode: the Home / Subscriptions feed is the area between the toolbar and the bottom bar.
    const val TOOLBAR = P + "toolbar"
    val SEARCH_FIELDS = setOf(P + "search_edit_text", P + "search_query", P + "search_box")
    val PLAYER_IDS = setOf(P + "inline_player_container", P + "watch_player", P + "player_view", P + "player_fragment_container")

    /** Bottom-bar labels of the two feed tabs, lowercase. */
    val FEED_TABS = setOf(
        "home", "subscriptions", "inicio", "suscripciones", "início", "inscrições", "accueil", "abonnements",
        "start", "startseite", "abos", "iscrizioni", "الرئيسية", "الاشتراكات", "ホーム", "登録チャンネル",
    )

    /** Label of the Shorts bottom tab. "Shorts" is a brand name and is not translated in the app. */
    const val SHORTS_LABEL = "shorts"
    val SHORTS_CONTAINERS = setOf(P + "reel_recycler", P + "reel_player_page_container", P + "shorts_player_container")
}

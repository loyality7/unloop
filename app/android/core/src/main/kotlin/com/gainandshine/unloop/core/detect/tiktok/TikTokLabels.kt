package com.gainandshine.unloop.core.detect.tiktok

import com.gainandshine.unloop.core.detect.SurfaceId

/** Where a tab lives on screen. */
enum class TabZone { TOP, BOTTOM }

/** TikTok tabs the classifier knows. [surface] is null for tabs that are never blocked. */
enum class TikTokTab(val surface: String?, val isFeed: Boolean, val zones: Set<TabZone>) {
    FOR_YOU(SurfaceId.TIKTOK_FOR_YOU, true, setOf(TabZone.TOP)),
    FOLLOWING(SurfaceId.TIKTOK_FOLLOWING, true, setOf(TabZone.TOP)),
    FRIENDS(SurfaceId.TIKTOK_FRIENDS, true, setOf(TabZone.TOP)),
    LOCAL(SurfaceId.TIKTOK_LOCAL, true, setOf(TabZone.TOP)),
    COMMUNITY(SurfaceId.TIKTOK_COMMUNITY, true, setOf(TabZone.TOP)),
    STEM(SurfaceId.TIKTOK_STEM, true, setOf(TabZone.TOP)),
    SHOP(SurfaceId.TIKTOK_SHOP, false, setOf(TabZone.TOP, TabZone.BOTTOM)),
    LIVE(SurfaceId.TIKTOK_LIVE, false, setOf(TabZone.TOP, TabZone.BOTTOM)),
    HOME(null, false, setOf(TabZone.BOTTOM)),
    INBOX(null, false, setOf(TabZone.BOTTOM)),
    PROFILE(null, false, setOf(TabZone.BOTTOM)),
    UNKNOWN(null, false, emptySet()),
}

/** Per-locale label data (lowercase). Adding a locale = adding strings to a set. */
object TikTokLabels {
    val tabs: Map<TikTokTab, Set<String>> = mapOf(
        TikTokTab.FOR_YOU to setOf("for you", "para ti", "pour toi", "für dich", "per te", "para você", "おすすめ", "لك"),
        TikTokTab.FOLLOWING to setOf("following", "siguiendo", "abonnements", "folge ich", "seguiti", "seguindo", "フォロー中", "أتابعه"),
        TikTokTab.FRIENDS to setOf("friends", "amigos", "amis", "freunde", "amici", "友達", "الأصدقاء"),
        TikTokTab.LOCAL to setOf("local", "nearby", "à proximité", "in der nähe", "nelle vicinanze", "近く", "قريب"),
        TikTokTab.COMMUNITY to setOf("community", "comunidad", "communauté", "gemeinschaft", "comunità", "comunidade", "コミュニティ", "المجتمع"),
        TikTokTab.STEM to setOf("stem"),
        TikTokTab.SHOP to setOf("tiktok shop", "mall", "shop"),
        TikTokTab.LIVE to setOf("live"),
        TikTokTab.HOME to setOf("home", "inicio", "accueil", "startseite", "início", "ホーム", "الرئيسية"),
        TikTokTab.INBOX to setOf(
            "inbox", "messages", "notifications", "notifications and messages", "activity", "bandeja de entrada",
            "boîte de réception", "posteingang", "posta in arrivo", "caixa de entrada", "受信トレイ", "صندوق الوارد",
        ),
        TikTokTab.PROFILE to setOf("profile", "perfil", "profil", "profilo", "プロフィール", "الملف الشخصي"),
    )

    /** Feed tabs the ladder may click to leave a blocked tab, in preference order. */
    val safeFeedOrder = listOf(TikTokTab.FOLLOWING, TikTokTab.FRIENDS, TikTokTab.LOCAL, TikTokTab.COMMUNITY, TikTokTab.STEM)

    /** Content-description fragments of a video opened from a direct message. */
    val dmMarkers = listOf("back to chat", "reply privately")

    /** Trailing accessibility decoration such as ", tab 2 of 5", ", selected", ", not selected". */
    val suffix = Regex("""(?:,?\s+(?:tab(?: \d+ of \d+)?|selected|not selected))+$""")

    private val byLabel: Map<String, TikTokTab> =
        tabs.flatMap { (tab, labels) -> labels.map { it to tab } }.toMap()

    /** Classify a raw label (content description or text). UNKNOWN if nothing matches. */
    fun classify(raw: String): TikTokTab {
        val s = raw.trim().lowercase().replace('’', '\'')
        val stripped = s.replace(suffix, "").trim()
        byLabel[stripped]?.let { return it }
        // Locale-independent fallback: the label before the first comma ("For You, pestana 2 de 5").
        val head = s.substringBefore(',').substringBefore('،').trim()
        return byLabel[head] ?: TikTokTab.UNKNOWN
    }

    /** True when the raw label carries a ", selected" decoration (and not ", not selected"). */
    fun labelSaysSelected(raw: String): Boolean {
        val s = raw.trim().lowercase()
        return s.endsWith("selected") && !s.endsWith("not selected")
    }
}

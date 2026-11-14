package com.gainandshine.unloop.core.detect.facebook

/** Facebook label data (lowercase). Adding a locale = adding strings to a set. */
object FacebookLabels {
    val homeTab = setOf("home", "inicio", "início", "accueil", "startseite", "الرئيسية", "الصفحة الرئيسية")
    val reelsTab = setOf("reels", "ريلز", "video", "videos", "vídeo", "vidéo", "watch", "الفيديو")

    /** Labels on posts to hide: sponsored and "suggested for you" posts. */
    val suggestedOrSponsored: Set<String> =
        com.gainandshine.unloop.core.detect.shared.SPONSORED_LABELS + com.gainandshine.unloop.core.detect.shared.SUGGESTED_LABELS

    val marketplaceTab = setOf("marketplace", "ماركت بليس")
    val groupsTab = setOf("groups", "grupos", "groupes", "gruppen", "gruppi", "المجموعات", "グループ")

    /** Sub-tabs inside the Groups tab. */
    val groupsForYou = setOf("for you", "para ti", "para você", "pour vous", "für dich", "per te", "لك", "おすすめ", "あなたへのおすすめ")
    val groupsYours = setOf("your groups", "tus grupos", "seus grupos", "vos groupes", "deine gruppen", "i tuoi gruppi", "مجموعاتك", "あなたのグループ", "参加中のグループ")
    val groupsDiscover = setOf("discover", "descubrir", "descobrir", "découvrir", "entdecken", "scopri", "اكتشاف", "見つける", "発見")

    /** Every bottom-bar tab label (superset of [homeTab] and [reelsTab]). */
    val tabLabels: Set<String> = homeTab + reelsTab + setOf(
        "marketplace", "ماركت بليس",
        "notifications", "notification", "notificaciones", "notificações", "benachrichtigungen", "الإشعارات",
        "profile", "perfil", "profil", "الملف الشخصي",
        "menu", "menú", "menü", "القائمة",
        "friends", "amigos", "amis", "freunde", "الأصدقاء",
        "groups", "grupos", "groupes", "gruppen", "gruppi", "المجموعات", "グループ",
    )

    /** Content-description markers of a full-screen reel player. */
    val reelMarkers = listOf(
        "swipe up to see more", "navigate to your reels profile", "accedi al tuo profilo di reels",
        "reel details", "dettagli del reel",
    )

    /** Hints of a message composer. */
    val messageHints = listOf(
        "message", "message…", "aa message", "send a message", "type a message", "mensaje", "mensaje…",
        "escribe un mensaje", "escribir un mensaje", "écris un message", "écrire un message",
        "رسالة", "اكتب رسالة", "أرسل رسالة",
    )

    /** Text that only appears on Messenger-style search screens. */
    val messengerSearch = listOf(
        "search messenger", "search chats", "search messages", "buscar en messenger", "buscar en chats",
        "buscar mensajes", "rechercher dans messenger", "rechercher dans les chats", "rechercher un message",
        "بحث في messenger", "البحث في messenger", "بحث في الدردشات",
        "messenger を検索", "in messenger suchen", "chats durchsuchen", "cerca su messenger", "cerca in messenger",
        "pesquisar no messenger",
    )

    /** Word forms used before the number pair, e.g. "Home, tab 1 of 6". Two numbers are what matters. */
    val tabPosition = Regex("""[,،]\s*[^,،]*[\d٠-٩۰-۹]+[^,،]*[\d٠-٩۰-۹]+""")

    /** Author of an audio label, "Name · Original audio". */
    val audioAuthor = Regex("""^(.+?)\s*[·•]\s*(Original audio|Audio original|Audio originale|Originalton|المقطع الصوتي الأصلي|الصوت الأصلي)""", RegexOption.IGNORE_CASE)

    /** Author of a "View X's reels" style label in several locales. */
    val reelsOfAuthor = Regex(
        """^(?:View\s+(.+?)'s\s+reels|Ver\s+(?:los\s+)?reels\s+de\s+(.+?)|Voir\s+(?:les\s+)?reels\s+de\s+(.+?)|""" +
            """Visualizza\s+(?:i\s+)?reel\s+di\s+(.+?)|(?:عرض|شاهد|مشاهدة)\s+(?:(?:مقاطع|مقطع)\s+)?ريلز\s+(?:من\s+)?(.+?))\s*$""",
        RegexOption.IGNORE_CASE,
    )
}

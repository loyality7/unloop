package com.gainandshine.unloop.core.detect.x

import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.NodeRef
import com.gainandshine.unloop.core.detect.SurfaceAdapter
import com.gainandshine.unloop.core.detect.SurfaceId
import com.gainandshine.unloop.core.detect.Verdict
import com.gainandshine.unloop.core.detect.shared.clickableTarget
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.core.model.WindowSnap
import com.gainandshine.unloop.core.model.pathTo

/** X (Twitter) labels, lowercase. Adding a language means adding strings to a set. */
object XLabels {
    val home = setOf("home", "inicio", "início", "accueil", "startseite", "pagina iniziale", "الرئيسية", "ホーム")
    val explore = setOf(
        "explore", "search and explore", "explorar", "explorer", "entdecken", "esplora", "استكشاف", "検索", "話題を検索",
    )
    val messages = setOf(
        "messages", "chat", "chats", "mensajes", "mensagens", "messaggi", "nachrichten", "الرسائل", "メッセージ", "チャット",
    )
    val notifications = setOf("notifications", "notificaciones", "notificações", "benachrichtigungen", "notifiche", "notifications tab", "الإشعارات", "通知")
    val forYou = setOf("for you", "foryou", "per te", "para ti", "pour vous", "für dich", "fur dich", "para você", "para voce", "لك", "おすすめ")
    val following = setOf(
        "following", "seguiti", "siguiendo", "seguindo", "abonnements", "folgst du", "seguidos", "أتابعهم", "フォロー中",
    )
}

/**
 * X: three optional switches, all done by clicking the right tab, never by covering or exiting.
 *  - For you: on the Home timeline with "For you" selected, click "Following".
 *  - Explore: with the Explore tab selected, click Home.
 *  - Messages only: on any other main tab, click Messages.
 * Everything needs the bottom tab row (and, for For you, the two top tabs) to be readable. If an app update changes
 * how tabs are labelled or marked selected, nothing is found and nothing is done.
 */
class XAdapter : SurfaceAdapter {
    override val pkgs = setOf("com.twitter.android", "com.twitter.android.lite")
    override val surfaces = setOf(SurfaceId.X_FOR_YOU, SurfaceId.X_EXPLORE, SurfaceId.X_DMS_ONLY)

    private enum class Kind { HOME, EXPLORE, MESSAGES, OTHER }

    private class Tab(val node: NodeSnap, val kind: Kind, val selected: Boolean)

    override fun evaluate(snap: UiSnapshot, cfg: DetectionConfig): Verdict {
        if (snap.pkg !in pkgs || surfaces.none(cfg::isOn)) return Verdict.None
        val winIdx = snap.windows.indexOfFirst { it.pkg == snap.pkg }
        if (winIdx < 0) return Verdict.None
        val w = snap.windows[winIdx]
        val tabs = bottomTabs(w)
        val current = tabs.firstOrNull { it.selected } ?: return Verdict.None

        if (cfg.isOn(SurfaceId.X_DMS_ONLY) && current.kind != Kind.MESSAGES) {
            tabs.firstOrNull { it.kind == Kind.MESSAGES }?.let { return block(SurfaceId.X_DMS_ONLY, "not_messages", winIdx, w, it.node, "messages_tab") }
        }
        if (cfg.isOn(SurfaceId.X_EXPLORE) && current.kind == Kind.EXPLORE) {
            tabs.firstOrNull { it.kind == Kind.HOME }?.let { return block(SurfaceId.X_EXPLORE, "explore_tab", winIdx, w, it.node, "home_tab") }
        }
        if (cfg.isOn(SurfaceId.X_FOR_YOU) && current.kind == Kind.HOME) {
            val top = topTabs(w)
            val forYou = top.firstOrNull { it.first == XLabels.forYou }
            val following = top.firstOrNull { it.first == XLabels.following }
            if (forYou != null && following != null && forYou.third) {
                return block(SurfaceId.X_FOR_YOU, "for_you_selected", winIdx, w, following.second, "following_tab")
            }
        }
        return Verdict.None
    }

    private fun block(surface: String, reason: String, winIdx: Int, w: WindowSnap, node: NodeSnap, label: String): Verdict {
        val target = clickableTarget(w.root, node) ?: node
        val path = w.root.pathTo(target) ?: return Verdict.None
        val ref = NodeRef(winIdx, path)
        return Verdict.Block(
            surface, reason,
            listOf(Action.TapNode(ref, target.id, target.bounds, label), Action.ClickNode(ref, target.id, target.bounds, label)),
        )
    }

    private fun label(n: NodeSnap): String? =
        listOfNotNull(n.desc, n.text).firstOrNull { it.isNotBlank() }?.lowercase()?.substringBefore(',')?.trim()

    /** Home / Explore / Messages tabs of the bottom bar: the lowest row holding at least three known tabs. */
    private fun bottomTabs(w: WindowSnap): List<Tab> {
        val found = ArrayList<Tab>()
        val minY = w.bounds.top + w.bounds.height * BOTTOM_ZONE
        fun go(n: NodeSnap, ancestors: List<NodeSnap>) {
            if (n.visible && !n.bounds.isEmpty && n.bounds.centerY >= minY) {
                val l = label(n)
                val kind = when (l) {
                    in XLabels.home -> Kind.HOME
                    in XLabels.explore -> Kind.EXPLORE
                    in XLabels.messages -> Kind.MESSAGES
                    in XLabels.notifications -> Kind.OTHER
                    else -> null
                }
                if (kind != null) found += Tab(n, kind, selected(n, ancestors))
            }
            for (c in n.children) go(c, (ancestors + n).takeLast(ANCESTOR_LEVELS))
        }
        go(w.root, emptyList())
        // Three distinct kinds on one row is what makes this the real bar and not a stray label.
        val row = found.groupBy { it.node.bounds.centerY / ROW_BUCKET_PX }.values.firstOrNull { r -> r.map { it.kind }.toSet().size >= 3 }
        return row.orEmpty()
    }

    /** (label set, node, selected) for For you and Following in the upper part of the screen. */
    private fun topTabs(w: WindowSnap): List<Triple<Set<String>, NodeSnap, Boolean>> {
        val out = ArrayList<Triple<Set<String>, NodeSnap, Boolean>>()
        val maxY = w.bounds.top + w.bounds.height * TOP_ZONE
        fun go(n: NodeSnap, ancestors: List<NodeSnap>) {
            if (n.visible && !n.bounds.isEmpty && n.bounds.centerY <= maxY) {
                val l = label(n)
                val set = when (l) {
                    in XLabels.forYou -> XLabels.forYou
                    in XLabels.following -> XLabels.following
                    else -> null
                }
                if (set != null) out += Triple(set, n, selected(n, ancestors))
            }
            for (c in n.children) go(c, (ancestors + n).takeLast(ANCESTOR_LEVELS))
        }
        go(w.root, emptyList())
        return out
    }

    private fun selected(n: NodeSnap, ancestors: List<NodeSnap>) = n.selected || ancestors.any {
        it.selected && it.visible && it.bounds.contains(n.bounds) &&
            it.bounds.width <= n.bounds.width * 2 && it.bounds.height <= n.bounds.height * 2
    }

    override fun reset() = Unit

    private companion object {
        const val BOTTOM_ZONE = 0.80
        const val TOP_ZONE = 0.30
        const val ROW_BUCKET_PX = 16
        const val ANCESTOR_LEVELS = 3
    }
}

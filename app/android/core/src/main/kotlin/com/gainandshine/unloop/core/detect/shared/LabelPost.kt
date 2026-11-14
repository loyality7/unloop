package com.gainandshine.unloop.core.detect.shared

import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.WindowSnap
import com.gainandshine.unloop.core.model.at
import com.gainandshine.unloop.core.model.pathTo

/**
 * Finds posts in a feed by a label they carry ("Sponsored", "Suggested for you") and returns the screen rectangle of
 * each, to be hidden under a cover. The label node is matched on its whole text (so a post that merely mentions the
 * word is not hit). The post is the largest ancestor that is nearly full width and between [MIN_HEIGHT] and
 * [MAX_HEIGHT] of the window tall: tall enough to be a whole post, never the feed list itself. If no such ancestor
 * exists nothing is returned, so a layout this does not understand hides nothing.
 */
object LabelPost {
    const val MIN_HEIGHT = 0.18
    const val MAX_HEIGHT = 0.78
    private const val MIN_WIDTH = 0.9
    private const val MAX_LEVELS = 14

    fun rects(w: WindowSnap, labels: Set<String>, bottomLimit: Int = w.bounds.bottom): List<Rect> {
        val out = ArrayList<Rect>(2)
        val hits = w.root.findAll { n -> n.visible && !n.bounds.isEmpty && normalized(n) in labels }
        for (hit in hits) {
            val path = w.root.pathTo(hit) ?: continue
            var best: NodeSnap? = null
            for (up in 1..MAX_LEVELS) {
                if (up > path.size) break
                val cand = w.root.at(path.take(path.size - up)) ?: break
                if (cand.bounds.height > w.bounds.height * MAX_HEIGHT) break
                if (cand.bounds.width >= w.bounds.width * MIN_WIDTH && cand.bounds.height >= w.bounds.height * MIN_HEIGHT) best = cand
            }
            val b = best?.bounds ?: continue
            val r = Rect(
                maxOf(b.left, w.bounds.left), maxOf(b.top, w.bounds.top),
                minOf(b.right, w.bounds.right), minOf(b.bottom, bottomLimit),
            )
            if (!r.isEmpty && r !in out) out += r
        }
        return out.sortedBy { it.top }
    }

    private fun normalized(n: NodeSnap): String? =
        listOfNotNull(n.text, n.desc).firstOrNull { it.isNotBlank() }?.lowercase()?.trim()?.trimEnd('·', '•', '.', ' ')
}

/** "Sponsored" in the languages of the feeds we cover, lowercase. */
val SPONSORED_LABELS: Set<String> = setOf(
    "sponsored", "patrocinado", "sponsorisé", "gesponsert", "sponsorizzato", "anuncio", "publicidad", "publicité",
    "werbung", "pubblicità", "реклама", "sponsorlu", "gesponsord", "مُموَّل", "ممول", "広告", "광고",
)

/** "Suggested for you" in the languages of the feeds we cover, lowercase. */
val SUGGESTED_LABELS: Set<String> = setOf(
    "suggested for you", "suggestion for you", "suggested", "sugerido para ti", "sugerencia para ti", "sugerido para você",
    "suggestion pour vous", "vorgeschlagen für dich", "consigliato per te", "مقترح لك", "あなたへのおすすめ",
)

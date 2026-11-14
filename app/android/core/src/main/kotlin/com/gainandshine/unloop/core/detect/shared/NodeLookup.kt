package com.gainandshine.unloop.core.detect.shared

import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.NodeRef
import com.gainandshine.unloop.core.detect.isProminent
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.WindowSnap
import com.gainandshine.unloop.core.model.at
import com.gainandshine.unloop.core.model.pathTo

/** Max ancestor levels climbed when a tab node itself is not clickable. */
const val CLICK_ANCESTOR_LEVELS = 3

/** Largest allowed ancestor size relative to the child (rejects "whole screen is clickable" wrappers). */
private const val MAX_ANCESTOR_SCALE = 2

/** First node in [window] whose id is in [ids] and which is prominent (see [isProminent]) in the window bounds. */
fun firstProminentById(window: WindowSnap, ids: Set<String>): NodeSnap? =
    window.root.findAll { it.id in ids && isProminent(it, window.bounds) }.firstOrNull()

/** Single traversal that buckets every node whose id is in [ids]. Cheaper than one walk per question. */
fun collectByIds(root: NodeSnap, ids: Set<String>): Map<String, List<NodeSnap>> =
    root.findAll { it.id in ids }.groupBy { it.id.orEmpty() }

/**
 * The node itself if clickable, else the nearest ancestor (<= [maxLevels] up) that is clickable, visible, enabled,
 * contains the node and is at most twice its size. Null if none or [node] is not under [root] (identity).
 */
fun clickableTarget(root: NodeSnap, node: NodeSnap, maxLevels: Int = CLICK_ANCESTOR_LEVELS): NodeSnap? {
    val path = root.pathTo(node) ?: return null
    if (node.clickable && node.visible && node.enabled) return node
    for (up in 1..maxLevels) {
        if (up > path.size) return null
        val cand = root.at(path.take(path.size - up)) ?: return null
        val ok = cand.clickable && cand.visible && cand.enabled && cand.bounds.contains(node.bounds) &&
            cand.bounds.width <= MAX_ANCESTOR_SCALE * node.bounds.width &&
            cand.bounds.height <= MAX_ANCESTOR_SCALE * node.bounds.height
        if (ok) return cand
    }
    return null
}

/** Build a [Action.ClickNode] for the clickable target of [node] in window [windowIndex], or null. */
fun clickAction(windowIndex: Int, root: NodeSnap, node: NodeSnap, label: String): Action.ClickNode? {
    val target = clickableTarget(root, node) ?: return null
    val path = root.pathTo(target) ?: return null
    return Action.ClickNode(NodeRef(windowIndex, path), target.id ?: node.id, target.bounds, label)
}

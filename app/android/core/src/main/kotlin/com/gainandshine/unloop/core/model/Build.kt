package com.gainandshine.unloop.core.model

/** Default phone-ish display used by tests and fixtures. */
val SCREEN = Rect(0, 0, 1080, 2400)

/**
 * Compact node constructor for tests and fixtures. Coordinates are absolute screen pixels.
 *   n("com.x:id/pager", 0, 0, 1080, 2400, selected = true, kids = listOf(...))
 */
fun n(
    id: String? = null,
    left: Int = 0,
    top: Int = 0,
    right: Int = SCREEN.right,
    bottom: Int = SCREEN.bottom,
    cls: String? = null,
    text: String? = null,
    desc: String? = null,
    selected: Boolean = false,
    clickable: Boolean = false,
    visible: Boolean = true,
    enabled: Boolean = true,
    editable: Boolean = false,
    kids: List<NodeSnap> = emptyList(),
): NodeSnap = NodeSnap(
    id = id, cls = cls, text = text, desc = desc,
    bounds = Rect(left, top, right, bottom),
    visible = visible, selected = selected, clickable = clickable, enabled = enabled, editable = editable,
    children = kids,
)

/** One-window snapshot of [pkg] whose root fills the screen and contains [kids]. */
fun snapshot(pkg: String, atMs: Long = 0, vararg kids: NodeSnap, screen: Rect = SCREEN): UiSnapshot {
    val root = NodeSnap(bounds = screen, children = kids.toList())
    return UiSnapshot(atMs = atMs, pkg = pkg, screen = screen, windows = listOf(WindowSnap(pkg, screen, root)))
}

/** Resolve a [NodeRef]-style path against a window root. Returns null if the path no longer exists. */
fun NodeSnap.at(path: List<Int>): NodeSnap? {
    var cur: NodeSnap = this
    for (i in path) cur = cur.children.getOrNull(i) ?: return null
    return cur
}

/** Child-index path from this root to [target] (identity match), or null. Used by adapters to build NodeRefs. */
fun NodeSnap.pathTo(target: NodeSnap): List<Int>? {
    if (this === target) return emptyList()
    for ((i, c) in children.withIndex()) {
        val p = c.pathTo(target)
        if (p != null) return listOf(i) + p
    }
    return null
}

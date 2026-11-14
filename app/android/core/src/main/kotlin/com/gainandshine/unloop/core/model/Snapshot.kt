package com.gainandshine.unloop.core.model

/** Screen rectangle in pixels. Plain data so the core never touches android.graphics. */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersects(o: Rect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun contains(o: Rect): Boolean = left <= o.left && top <= o.top && right >= o.right && bottom >= o.bottom
}

/**
 * Immutable copy of one accessibility node. Built once per scan by the Android shell; the core never sees live nodes.
 * [text] and [desc] are kept only for the duration of one evaluation and must never be persisted or logged
 * (privacy rule: node text can contain user content).
 */
data class NodeSnap(
    val id: String? = null, // full view id resource name, e.g. "com.instagram.android:id/clips_tab"
    val cls: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val bounds: Rect,
    val visible: Boolean = true,
    val selected: Boolean = false,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val editable: Boolean = false,
    val children: List<NodeSnap> = emptyList(),
) {
    /** Depth-first walk. Stops after [maxNodes] visited nodes or below [maxDepth]. Returns visited count. */
    fun walk(maxDepth: Int = 64, maxNodes: Int = 1500, visit: (node: NodeSnap, depth: Int) -> Unit): Int {
        var count = 0
        fun go(n: NodeSnap, d: Int) {
            if (count >= maxNodes || d > maxDepth) return
            count++
            visit(n, d)
            for (c in n.children) go(c, d + 1)
        }
        go(this, 0)
        return count
    }

    fun findAll(maxDepth: Int = 64, maxNodes: Int = 1500, pred: (NodeSnap) -> Boolean): List<NodeSnap> {
        val out = ArrayList<NodeSnap>()
        walk(maxDepth, maxNodes) { n, _ -> if (pred(n)) out += n }
        return out
    }

    fun findById(id: String): List<NodeSnap> = findAll { it.id == id }
}

/** One application window as seen by the shell for a scan. */
data class WindowSnap(
    val pkg: String,
    val bounds: Rect,
    val root: NodeSnap,
    val isActive: Boolean = true,
    val isFocused: Boolean = true,
    val isApplication: Boolean = true,
)

/** Everything a detection adapter may look at, for one scan. */
data class UiSnapshot(
    val atMs: Long, // monotonic
    val pkg: String, // foreground application package
    val screen: Rect, // display bounds
    val windows: List<WindowSnap>, // foreground package's windows first
) {
    val primary: WindowSnap? get() = windows.firstOrNull { it.pkg == pkg }
}

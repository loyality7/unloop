package com.gainandshine.unloop.a11y

import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.Rect
import kotlin.math.abs

/** Android-free helpers so they can be unit-tested on the JVM. */

/** AccessibilityEvent.TYPE_* int -> core type. Exhaustive default OTHER on purpose. */
object EventTypeTable {
    const val TYPE_VIEW_CLICKED = 0x1
    const val TYPE_VIEW_SELECTED = 0x4
    const val TYPE_WINDOW_STATE_CHANGED = 0x20
    const val TYPE_VIEW_SCROLLED = 0x1000
    const val TYPE_WINDOW_CONTENT_CHANGED = 0x800
    const val TYPE_WINDOWS_CHANGED = 0x400000

    fun map(androidType: Int): EventType = when (androidType) {
        TYPE_WINDOW_STATE_CHANGED -> EventType.WINDOW_STATE_CHANGED
        TYPE_WINDOW_CONTENT_CHANGED -> EventType.WINDOW_CONTENT_CHANGED
        TYPE_WINDOWS_CHANGED -> EventType.WINDOWS_CHANGED
        TYPE_VIEW_SCROLLED -> EventType.VIEW_SCROLLED
        TYPE_VIEW_CLICKED -> EventType.VIEW_CLICKED
        TYPE_VIEW_SELECTED -> EventType.VIEW_SELECTED
        else -> EventType.OTHER
    }
}

/** Shared node budget for one whole snapshot (all windows). */
class NodeBudget(private val max: Int = MAX_NODES) {
    var used = 0
        private set

    fun tryTake(): Boolean {
        if (used >= max) return false
        used++
        return true
    }

    companion object {
        const val MAX_NODES = 1500
        const val MAX_DEPTH = 64
    }
}

object LiveResolve {
    const val BOUNDS_TOLERANCE_PX = 8
    const val MAX_CLICK_ANCESTORS = 3

    /** Nodes from root down to the node at [path] (inclusive both ends), or null if the path no longer exists. */
    fun <T : Any> chain(root: T, path: List<Int>, childAt: (T, Int) -> T?): List<T>? {
        val out = ArrayList<T>(path.size + 1)
        var cur = root
        out += cur
        for (i in path) {
            cur = childAt(cur, i) ?: return null
            out += cur
        }
        return out
    }

    fun boundsClose(a: Rect, b: Rect, tol: Int = BOUNDS_TOLERANCE_PX): Boolean =
        abs(a.left - b.left) <= tol && abs(a.top - b.top) <= tol &&
            abs(a.right - b.right) <= tol && abs(a.bottom - b.bottom) <= tol

    /** A null snapshot id means "no id to verify" (bounds still must match). */
    fun idMatches(snapshotId: String?, liveId: String?): Boolean = snapshotId == null || snapshotId == liveId

    /** Index (into a root..target chain) of the click target: the node, else the nearest clickable ancestor within 3 levels, else the node. */
    fun clickIndex(chainClickable: List<Boolean>): Int {
        val last = chainClickable.lastIndex
        for (k in 0..MAX_CLICK_ANCESTORS) {
            val i = last - k
            if (i < 0) break
            if (chainClickable[i]) return i
        }
        return last
    }
}

/** A Set<String> whose contents can be swapped atomically; lets the immutable ClassifierContext see refreshed packages. */
class LiveSet : AbstractSet<String>() {
    @Volatile var current: Set<String> = emptySet()
    override val size: Int get() = current.size
    override fun contains(element: String): Boolean = element in current
    override fun iterator(): Iterator<String> = current.iterator()
}

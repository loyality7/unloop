package com.gainandshine.unloop.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect as ARect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.gainandshine.unloop.core.model.NodeSnap
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.core.model.WindowSnap

/** A snapshot plus the live window id of each [UiSnapshot.windows] entry (same order), for [ActionExecutor]. */
class SnapResult(val snap: UiSnapshot, val windowIds: List<Int>)

/** recycle() is a deprecated no-op from API 33; only older releases need it. */
internal fun release(n: AccessibilityNodeInfo?) {
    if (n != null && Build.VERSION.SDK_INT < 33) {
        @Suppress("DEPRECATION") try { n.recycle() } catch (_: Throwable) {}
    }
}

internal fun release(w: AccessibilityWindowInfo?) {
    if (w != null && Build.VERSION.SDK_INT < 33) {
        @Suppress("DEPRECATION") try { w.recycle() } catch (_: Throwable) {}
    }
}

internal fun AccessibilityNodeInfo.boundsRect(): Rect {
    val r = ARect()
    getBoundsInScreen(r)
    return Rect(r.left, r.top, r.right, r.bottom)
}

/** Builds pure snapshots. No live node survives a call. */
object Snapshotter {
    /** Package of the active window and nothing else (no tree walk). Null if unknown. Used only to verify covers. */
    fun activePackage(svc: AccessibilityService): String? {
        val root = try { svc.rootInActiveWindow } catch (_: Throwable) { null } ?: return null
        return try { root.packageName?.toString() } finally { release(root) }
    }

    /** [allowed]: packages we may read (null = no scope yet). Financial apps are never read, whatever [allowed] says. */
    fun take(svc: AccessibilityService, atMs: Long, allowed: Set<String>? = null): SnapResult? {
        val dm = svc.resources.displayMetrics
        val screen = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        val budget = NodeBudget()
        val wins = ArrayList<Triple<WindowSnap, Int, Boolean>>() // snap, windowId, active
        val list = try { svc.windows } catch (_: Throwable) { null }.orEmpty()
        for (w in list) {
            try {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: continue
                try {
                    val pkg = root.packageName?.toString() ?: continue
                    if (!com.gainandshine.unloop.system.PrivacyGuard.canRead(pkg, allowed)) continue
                    val b = ARect().also { w.getBoundsInScreen(it) }
                    val node = convert(root, 0, budget) ?: continue
                    wins += Triple(
                        WindowSnap(
                            pkg = pkg, bounds = Rect(b.left, b.top, b.right, b.bottom), root = node,
                            isActive = w.isActive, isFocused = w.isFocused,
                        ),
                        w.id, w.isActive,
                    )
                } finally { release(root) }
            } catch (_: Throwable) {
            } finally { release(w) }
        }
        if (wins.isEmpty()) {
            val root = try { svc.rootInActiveWindow } catch (_: Throwable) { null } ?: return null
            try {
                val pkg = root.packageName?.toString() ?: return null
                if (!com.gainandshine.unloop.system.PrivacyGuard.canRead(pkg, allowed)) return null
                val node = convert(root, 0, budget) ?: return null
                wins += Triple(WindowSnap(pkg, root.boundsRect(), node), root.windowId, true)
            } finally { release(root) }
        }
        val fg = (wins.firstOrNull { it.third } ?: wins.first()).first.pkg
        val ordered = wins.sortedByDescending { it.first.pkg == fg } // stable
        return SnapResult(
            UiSnapshot(atMs, fg, screen, ordered.map { it.first }),
            ordered.map { it.second },
        )
    }

    /**
     * Fast path for covers: look up only [ids] (view id -> levels of children to read) in the active window and wrap each
     * hit in up to three ancestors, so adapters can still find the clickable wrapper of a tab. A handful of binder calls
     * instead of a whole-tree walk, cheap enough to run many times a second while scrolling. Null when the active
     * window is not [pkg], is a protected app, or cannot be read.
     */
    fun takeTargeted(svc: AccessibilityService, pkg: String, ids: Map<String, Int>, texts: Set<String>, atMs: Long): UiSnapshot? {
        if (com.gainandshine.unloop.system.PrivacyGuard.isNeverWatched(pkg)) return null
        val root = try { svc.rootInActiveWindow } catch (_: Throwable) { null } ?: return null
        try {
            if (root.packageName?.toString() != pkg) return null
            val dm = svc.resources.displayMetrics
            val screen = Rect(0, 0, dm.widthPixels, dm.heightPixels)
            val win = root.boundsRect()
            val budget = NodeBudget(TARGETED_NODES)
            val kids = ArrayList<NodeSnap>()
            for ((id, depth) in ids) {
                val hits = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Throwable) { null }.orEmpty()
                for (h in hits) {
                    try {
                        val self = convert(h, 0, budget, depth) ?: continue
                        kids += wrapInAncestors(h, self)
                    } finally { release(h) }
                }
            }
            // Covers placed by a label ("Sponsored"): find the label, then keep the long chain of parents so the rule
            // can find the whole post around it.
            for (t in texts) {
                val hits = try { root.findAccessibilityNodeInfosByText(t) } catch (_: Throwable) { null }.orEmpty()
                for (h in hits) {
                    try {
                        val self = convert(h, 0, budget, 0) ?: continue
                        kids += wrapInAncestors(h, self, TEXT_ANCESTORS)
                    } finally { release(h) }
                }
            }
            val rootSnap = NodeSnap(bounds = win, children = kids)
            return UiSnapshot(atMs, pkg, screen, listOf(WindowSnap(pkg, win, rootSnap)))
        } finally { release(root) }
    }

    private fun wrapInAncestors(node: AccessibilityNodeInfo, inner: NodeSnap, levels: Int = ANCESTOR_LEVELS): NodeSnap {
        var cur = inner
        var from: AccessibilityNodeInfo = node
        var owned = false
        try {
            repeat(levels) {
                val p = try { from.parent } catch (_: Throwable) { null } ?: return cur
                cur = NodeSnap(
                    id = p.viewIdResourceName, cls = p.className?.toString(), bounds = p.boundsRect(),
                    visible = p.isVisibleToUser, selected = p.isSelected, clickable = p.isClickable, enabled = p.isEnabled,
                    children = listOf(cur),
                )
                if (owned) release(from)
                from = p
                owned = true
            }
            return cur
        } finally {
            if (owned) release(from)
        }
    }

    private const val TARGETED_NODES = 160
    private const val ANCESTOR_LEVELS = 3
    private const val TEXT_ANCESTORS = 14

    private fun convert(n: AccessibilityNodeInfo, depth: Int, budget: NodeBudget, depthCap: Int = NodeBudget.MAX_DEPTH): NodeSnap? {
        if (depth > depthCap || !budget.tryTake()) return null
        val count = n.childCount.coerceAtLeast(0)
        val kids = ArrayList<NodeSnap>(count)
        for (i in 0 until count) {
            val c = try { n.getChild(i) } catch (_: Throwable) { null }
            // Placeholders keep child indices aligned with the live tree so NodeRef paths stay valid.
            if (c == null) { kids += PLACEHOLDER; continue }
            try { kids += convert(c, depth + 1, budget, depthCap) ?: PLACEHOLDER } finally { release(c) }
        }
        return NodeSnap(
            id = n.viewIdResourceName, cls = n.className?.toString(),
            text = n.text?.toString(), desc = n.contentDescription?.toString(),
            bounds = n.boundsRect(), visible = n.isVisibleToUser, selected = n.isSelected,
            clickable = n.isClickable, enabled = n.isEnabled, editable = n.isEditable,
            children = kids,
        )
    }

    private val PLACEHOLDER = NodeSnap(bounds = Rect(0, 0, 0, 0), visible = false, enabled = false)
}

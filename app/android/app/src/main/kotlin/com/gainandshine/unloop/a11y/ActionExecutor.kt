package com.gainandshine.unloop.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import com.gainandshine.unloop.core.detect.Action
import com.gainandshine.unloop.core.detect.NodeRef
import com.gainandshine.unloop.core.model.Rect
import com.gainandshine.unloop.system.ClickRule

/** Performs core Actions against live nodes. Never throws. */
class ActionExecutor(private val svc: AccessibilityService) {

    fun perform(action: Action, snap: SnapResult?): Boolean = try {
        when (action) {
            Action.Back -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Action.Home -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            is Action.ClickNode -> node(action.ref, action.id, action.bounds, snap, tapOnly = false)
            is Action.TapNode -> node(action.ref, action.id, action.bounds, snap, tapOnly = true)
        }
    } catch (_: Throwable) {
        false
    }

    private fun node(ref: NodeRef, id: String?, bounds: Rect, snap: SnapResult?, tapOnly: Boolean): Boolean {
        val windowId = snap?.windowIds?.getOrNull(ref.window) ?: return false
        val root = rootFor(windowId) ?: return false
        val chain = LiveResolve.chain(root, ref.path) { n, i ->
            if (i < 0 || i >= n.childCount) null else try { n.getChild(i) } catch (_: Throwable) { null }
        }
        try {
            if (chain == null) return false
            val target = chain.last()
            if (!LiveResolve.idMatches(id, target.viewIdResourceName)) return false
            val live = target.boundsRect()
            if (!LiveResolve.boundsClose(bounds, live)) return false
            if (!tapOnly) {
                val t = chain[LiveResolve.clickIndex(chain.map { it.isClickable })]
                // A huge clickable ancestor (e.g. a whole feed card) is not the target: tap the target's centre instead.
                if (t === target || !ClickRule.preferTap(live, t.boundsRect())) {
                    if (t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
            }
            return tap(live.centerX, live.centerY)
        } finally {
            (chain ?: listOf(root)).forEach { release(it) }
        }
    }

    private fun rootFor(windowId: Int): AccessibilityNodeInfo? {
        for (w in svc.windows) {
            try { if (w.id == windowId) return w.root } finally { release(w) }
        }
        return svc.rootInActiveWindow?.takeIf { it.windowId == windowId }
    }

    private fun tap(x: Int, y: Int): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, TAP_MS))
            .build()
        // ponytail: reports dispatch acceptance, not completion; the engine's verify-after-wait catches a failed tap.
        return svc.dispatchGesture(g, null, null)
    }

    private companion object { const val TAP_MS = 60L }
}

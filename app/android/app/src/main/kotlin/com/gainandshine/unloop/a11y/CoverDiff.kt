package com.gainandshine.unloop.a11y

import com.gainandshine.unloop.core.detect.CoverRect
import com.gainandshine.unloop.core.model.Rect
import kotlin.math.abs

/**
 * Pure planner for cover windows: given what is on screen now and what the engine wants, say which windows to
 * add, move or remove. No Android types, so it is unit-tested on the JVM. One cover per surface id.
 */
object CoverDiff {
    /** Positions within this many pixels count as unchanged (avoids needless relayout while scrolling jitters). */
    const val TOLERANCE_PX = 2

    sealed interface Op {
        val surface: String
        data class Add(override val surface: String, val rect: Rect) : Op
        data class Move(override val surface: String, val rect: Rect) : Op
        data class Remove(override val surface: String) : Op
    }

    fun diff(current: Map<String, Rect>, wanted: List<CoverRect>, tolerance: Int = TOLERANCE_PX): List<Op> {
        val want = LinkedHashMap<String, Rect>()
        // If two covers share a surface id the last one wins: one window per surface keeps teardown simple.
        wanted.forEach { want[it.surface] = it.bounds }
        val ops = ArrayList<Op>()
        for ((surface, rect) in want) {
            val have = current[surface]
            when {
                have == null -> ops += Op.Add(surface, rect)
                !near(have, rect, tolerance) -> ops += Op.Move(surface, rect)
            }
        }
        for (surface in current.keys) if (surface !in want) ops += Op.Remove(surface)
        return ops
    }

    private fun near(a: Rect, b: Rect, t: Int) =
        abs(a.left - b.left) <= t && abs(a.top - b.top) <= t && abs(a.right - b.right) <= t && abs(a.bottom - b.bottom) <= t
}

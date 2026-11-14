package com.gainandshine.unloop.core.engine

import kotlin.math.abs

/**
 * Counts how many items (reels, shorts, videos) the user has swiped past. One swipe is one count, however many
 * scroll events it produces: a pager reports the item index, and for lists without an index a swipe is counted once
 * the scrolled distance adds up to [itemHeight] x [ITEM_FRACTION] within one burst of events.
 */
class ScrollCounter {
    var count = 0
        private set
    private var lastIndex = -1
    private var cumDy = 0
    private var lastAt = Long.MIN_VALUE

    /** @return true when this event completed one more item. */
    fun onScroll(atMs: Long, dx: Int, dy: Int, fromIndex: Int, itemHeight: Int): Boolean {
        if (abs(dx) > abs(dy)) return false
        if (fromIndex >= 0) {
            val changed = lastIndex >= 0 && fromIndex != lastIndex
            lastIndex = fromIndex
            if (changed) {
                cumDy = 0
                count++
                return true
            }
            return false
        }
        if (atMs - lastAt > BURST_GAP_MS) cumDy = 0
        lastAt = atMs
        cumDy += abs(dy)
        if (itemHeight > 0 && cumDy >= itemHeight * ITEM_FRACTION) {
            cumDy = 0
            count++
            return true
        }
        return false
    }

    fun reset() {
        count = 0
        lastIndex = -1
        cumDy = 0
        lastAt = Long.MIN_VALUE
    }

    companion object {
        const val ITEM_FRACTION = 0.6
        const val BURST_GAP_MS = 1_000L
    }
}

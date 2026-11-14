package com.gainandshine.unloop.a11y

import android.graphics.Bitmap
import com.gainandshine.unloop.core.model.Rect

/**
 * Picks the background colour around a button from a screenshot. It looks at 8 points near the button's edges and
 * answers only when at least [MIN_AGREE] of them are exactly the same colour: that is a plain background. If they
 * disagree the points landed on content (an icon, a photo), so it answers null and the caller tries again later.
 */
object BackgroundSampler {
    const val MIN_AGREE = 5

    fun pick(bmp: Bitmap, r: Rect): Int? {
        val inset = 4
        val xs = intArrayOf(r.left + inset, r.centerX, r.right - inset)
        val ys = intArrayOf(r.top + inset, r.centerY, r.bottom - inset)
        val points = ArrayList<Int>(8)
        for (xi in xs.indices) for (yi in ys.indices) {
            if (xi == 1 && yi == 1) continue // centre holds the icon
            val x = xs[xi]; val y = ys[yi]
            if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) continue
            points += bmp.getPixel(x, y) or (0xFF shl 24)
        }
        return agreed(points)
    }

    /** The colour that at least [MIN_AGREE] of [points] share, or null. Pure, so it is unit tested. */
    fun agreed(points: List<Int>): Int? {
        val best = points.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return best.key.takeIf { best.value >= MIN_AGREE }
    }
}

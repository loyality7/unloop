package com.gainandshine.unloop.a11y

import com.gainandshine.unloop.core.detect.CoverRect
import com.gainandshine.unloop.core.model.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverDiffTest {
    private fun c(surface: String, l: Int, t: Int, r: Int, b: Int) = CoverRect(surface, surface, Rect(l, t, r, b))

    @Test fun newCoverIsAdded() {
        val ops = CoverDiff.diff(emptyMap(), listOf(c("a", 0, 0, 10, 10)))
        assertEquals(listOf<CoverDiff.Op>(CoverDiff.Op.Add("a", Rect(0, 0, 10, 10))), ops)
    }

    @Test fun unchangedCoverDoesNothing() {
        assertTrue(CoverDiff.diff(mapOf("a" to Rect(0, 0, 10, 10)), listOf(c("a", 0, 0, 10, 10))).isEmpty())
    }

    @Test fun jitterWithinToleranceDoesNothingButLargerMoveRelayouts() {
        val cur = mapOf("a" to Rect(100, 100, 200, 200))
        assertTrue(CoverDiff.diff(cur, listOf(c("a", 102, 98, 201, 200))).isEmpty())
        val ops = CoverDiff.diff(cur, listOf(c("a", 100, 103, 200, 203)))
        assertEquals(listOf<CoverDiff.Op>(CoverDiff.Op.Move("a", Rect(100, 103, 200, 203))), ops)
    }

    @Test fun missingSurfaceIsRemoved() {
        val ops = CoverDiff.diff(mapOf("a" to Rect(0, 0, 1, 1), "b" to Rect(0, 0, 1, 1)), listOf(c("a", 0, 0, 1, 1)))
        assertEquals(listOf<CoverDiff.Op>(CoverDiff.Op.Remove("b")), ops)
    }

    @Test fun emptyWantedClearsEverything() {
        val ops = CoverDiff.diff(mapOf("a" to Rect(0, 0, 1, 1), "b" to Rect(0, 0, 1, 1)), emptyList())
        assertEquals(setOf<CoverDiff.Op>(CoverDiff.Op.Remove("a"), CoverDiff.Op.Remove("b")), ops.toSet())
    }

    @Test fun duplicateSurfaceKeepsLastOne() {
        val ops = CoverDiff.diff(emptyMap(), listOf(c("a", 0, 0, 1, 1), c("a", 5, 5, 9, 9)))
        assertEquals(listOf<CoverDiff.Op>(CoverDiff.Op.Add("a", Rect(5, 5, 9, 9))), ops)
    }

    @Test fun mixedAddMoveRemove() {
        val cur = mapOf("keep" to Rect(0, 0, 5, 5), "move" to Rect(0, 0, 5, 5), "gone" to Rect(0, 0, 5, 5))
        val ops = CoverDiff.diff(cur, listOf(c("keep", 0, 0, 5, 5), c("move", 50, 50, 60, 60), c("new", 1, 1, 2, 2)))
        assertEquals(
            setOf<CoverDiff.Op>(CoverDiff.Op.Move("move", Rect(50, 50, 60, 60)), CoverDiff.Op.Add("new", Rect(1, 1, 2, 2)), CoverDiff.Op.Remove("gone")),
            ops.toSet(),
        )
    }
}

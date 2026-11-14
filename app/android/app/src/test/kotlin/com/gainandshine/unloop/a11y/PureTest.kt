package com.gainandshine.unloop.a11y

import com.gainandshine.unloop.core.model.EventType
import com.gainandshine.unloop.core.model.Rect
import org.junit.Assert.*
import org.junit.Test

class PureTest {
    @Test fun eventMapping() {
        assertEquals(EventType.WINDOW_STATE_CHANGED, EventTypeTable.map(32))
        assertEquals(EventType.WINDOW_CONTENT_CHANGED, EventTypeTable.map(2048))
        assertEquals(EventType.WINDOWS_CHANGED, EventTypeTable.map(4194304))
        assertEquals(EventType.VIEW_SCROLLED, EventTypeTable.map(4096))
        assertEquals(EventType.VIEW_CLICKED, EventTypeTable.map(1))
        assertEquals(EventType.VIEW_SELECTED, EventTypeTable.map(4))
        assertEquals(EventType.OTHER, EventTypeTable.map(64))
    }

    @Test fun budgetCaps() {
        val b = NodeBudget(3)
        assertTrue(b.tryTake() && b.tryTake() && b.tryTake())
        assertFalse(b.tryTake())
        assertEquals(3, b.used)
    }

    @Test fun chainResolution() {
        val tree = mapOf("r" to listOf("a", "b"), "b" to listOf("c"))
        val f = { n: String, i: Int -> tree[n]?.getOrNull(i) }
        assertEquals(listOf("r", "b", "c"), LiveResolve.chain("r", listOf(1, 0), f))
        assertEquals(listOf("r"), LiveResolve.chain("r", emptyList(), f))
        assertNull(LiveResolve.chain("r", listOf(5), f))
    }

    @Test fun boundsTolerance() {
        val r = Rect(0, 100, 200, 300)
        assertTrue(LiveResolve.boundsClose(r, Rect(8, 92, 208, 308)))
        assertFalse(LiveResolve.boundsClose(r, Rect(9, 100, 200, 300)))
    }

    @Test fun idAndClickIndex() {
        assertTrue(LiveResolve.idMatches(null, "x"))
        assertTrue(LiveResolve.idMatches("a", "a"))
        assertFalse(LiveResolve.idMatches("a", null))
        assertEquals(4, LiveResolve.clickIndex(listOf(false, false, false, false, true)))
        assertEquals(2, LiveResolve.clickIndex(listOf(false, false, true, false, false)))
        // clickable ancestor 4 levels up is too far: fall back to the node itself
        assertEquals(4, LiveResolve.clickIndex(listOf(true, false, false, false, false)))
    }

    @Test fun liveSetSwaps() {
        val s = LiveSet()
        assertFalse("a" in s)
        s.current = setOf("a")
        assertTrue("a" in s)
    }
}

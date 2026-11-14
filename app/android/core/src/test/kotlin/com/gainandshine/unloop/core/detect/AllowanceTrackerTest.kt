package com.gainandshine.unloop.core.detect

import com.gainandshine.unloop.core.detect.shared.AllowanceTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllowanceTrackerTest {
    private val h = 1000

    @Test
    fun `idle tracker allows nothing`() {
        assertFalse(AllowanceTracker().observe(0, "a", h))
    }

    @Test
    fun `arm window boundaries 4999 5000 5001`() {
        for ((dt, allowed) in listOf(4_999L to true, 5_000L to true, 5_001L to false)) {
            val t = AllowanceTracker()
            t.arm(0)
            assertEquals(allowed, t.observe(dt, "a", h), "dt=$dt")
        }
    }

    @Test
    fun `swipe 11 9 percent keeps allowance and 12 percent ends it`() {
        for ((dy, allowed) in listOf(119 to true, 120 to false)) {
            val t = AllowanceTracker()
            t.arm(0)
            t.observe(0, "a", h)
            t.onScroll(100, 0, -dy, -1)
            assertEquals(allowed, t.observe(200, "a", h), "dy=$dy")
            assertEquals(!allowed, t.isEnded)
        }
    }

    @Test
    fun `scroll deltas accumulate within gap but not across it`() {
        val t = AllowanceTracker()
        t.arm(0)
        t.observe(0, "a", h)
        t.onScroll(100, 0, 60, -1)
        t.onScroll(600, 0, 60, -1)
        assertTrue(t.isEnded)
        val u = AllowanceTracker()
        u.arm(0)
        u.observe(0, "a", h)
        u.onScroll(100, 0, 60, -1)
        u.onScroll(1_200, 0, 60, -1)
        assertTrue(u.isRunning)
    }

    @Test
    fun `mostly horizontal scroll is ignored`() {
        val t = AllowanceTracker()
        t.arm(0)
        t.observe(0, "a", h)
        t.onScroll(10, 900, 500, -1)
        assertTrue(t.isRunning)
    }

    @Test
    fun `author change ends allowance and unknown author does not`() {
        val t = AllowanceTracker()
        t.arm(0)
        assertTrue(t.observe(0, "a", h))
        assertTrue(t.observe(100, null, h))
        assertTrue(t.observe(200, "a", h))
        assertFalse(t.observe(300, "b", h))
        assertFalse(t.observe(400, "a", h))
    }

    @Test
    fun `pager index change ends allowance`() {
        val t = AllowanceTracker()
        t.arm(0)
        t.observe(0, "a", h)
        t.onScroll(10, 0, 0, 2)
        assertTrue(t.isRunning)
        t.onScroll(20, 0, 0, 3)
        assertTrue(t.isEnded)
    }

    @Test
    fun `idle gap resets running tracker at 1500 and 1501`() {
        for ((gap, allowed) in listOf(1_500L to true, 1_501L to false)) {
            val t = AllowanceTracker()
            t.arm(0)
            t.observe(0, "a", h)
            assertEquals(allowed, t.observe(gap, "a", h), "gap=$gap")
        }
    }

    @Test
    fun `ended allowance can be re-armed only after idle reset or reset`() {
        val t = AllowanceTracker()
        t.arm(0)
        t.observe(0, "a", h)
        t.observe(10, "b", h)
        t.arm(20)
        assertFalse(t.observe(30, "b", h))
        t.reset()
        t.arm(40)
        assertTrue(t.observe(50, "b", h))
    }

    @Test
    fun `reset clears armed state`() {
        val t = AllowanceTracker()
        t.arm(0)
        t.reset()
        assertFalse(t.observe(1, "a", h))
    }
}

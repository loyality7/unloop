package com.gainandshine.unloop.core.limits

import com.gainandshine.unloop.core.time.Schedule
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrictPolicyTest {
    private val ig = LimitRule("ig", 3600)
    private fun sched(id: String, on: Boolean = true, end: Int = 7) =
        Schedule(id, on, setOf(DayOfWeek.MONDAY), LocalTime.of(22, 0), LocalTime.of(end, 0))

    @Test fun activeOnlyBeforeDeadline() {
        assertTrue(StrictPolicy.isActive(1000, 999))
        assertFalse(StrictPolicy.isActive(1000, 1000))
        assertFalse(StrictPolicy.isActive(0, 5))
    }

    @Test fun limitsMayTightenAddButNotLoosenOrRemove() {
        assertTrue(StrictPolicy.limitsAllowed(listOf(ig), listOf(ig)))
        assertTrue(StrictPolicy.limitsAllowed(listOf(ig), listOf(LimitRule("ig", 1800))))
        assertTrue(StrictPolicy.limitsAllowed(listOf(ig), listOf(ig, LimitRule("yt", 60))))
        assertFalse(StrictPolicy.limitsAllowed(listOf(ig), listOf(LimitRule("ig", 7200))))
        assertFalse(StrictPolicy.limitsAllowed(listOf(ig), emptyList()))
    }

    @Test fun enabledSchedulesAreFrozenDisabledOnesAreFree() {
        assertTrue(StrictPolicy.schedulesAllowed(listOf(sched("a")), listOf(sched("a"), sched("b"))))
        assertFalse(StrictPolicy.schedulesAllowed(listOf(sched("a")), emptyList()))
        assertFalse(StrictPolicy.schedulesAllowed(listOf(sched("a")), listOf(sched("a", on = false))))
        assertFalse(StrictPolicy.schedulesAllowed(listOf(sched("a")), listOf(sched("a", end = 5))))
        assertTrue(StrictPolicy.schedulesAllowed(listOf(sched("a", on = false)), emptyList()))
    }

    @Test fun scheduledAppsCanOnlyGrow() {
        assertTrue(StrictPolicy.scheduledAppsAllowed(setOf("a"), setOf("a", "b")))
        assertFalse(StrictPolicy.scheduledAppsAllowed(setOf("a"), emptySet()))
    }
}

package com.gainandshine.unloop.core.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentAsideTest {
    private val pay = setOf("com.phonepe.app", "com.google.android.apps.nbu.paisa.user")
    private fun tracker(start: Long = 0) = AsideTracker({ it in pay }, start)

    @Test fun staysOffWhileThePaymentAppIsInFront() {
        val t = tracker()
        for (at in listOf(100L, 1_000L, 5_000L, 60_000L)) assertFalse(t.onForeground("com.phonepe.app", at))
    }

    @Test fun comesBackAfterLeavingForLongEnough() {
        val t = tracker()
        assertFalse(t.onForeground("com.phonepe.app", 100))
        assertFalse(t.onForeground("com.google.android.apps.nexuslauncher", 2_000), "just left")
        assertFalse(t.onForeground("com.google.android.apps.nexuslauncher", 3_000))
        assertTrue(t.onForeground("com.google.android.apps.nexuslauncher", 3_600), "1.5 s away")
    }

    @Test fun aQuickSwitchInsideAPaymentFlowDoesNotCount() {
        val t = tracker()
        t.onForeground("com.phonepe.app", 100)
        assertFalse(t.onForeground("com.android.systemui", 1_000))
        assertFalse(t.onForeground("com.google.android.apps.nbu.paisa.user", 1_800), "another payment app resets the clock")
        assertFalse(t.onForeground("com.android.systemui", 2_000))
        assertFalse(t.onForeground("com.android.systemui", 3_400))
        assertTrue(t.onForeground("com.android.systemui", 3_600))
    }

    @Test fun unknownForegroundIsIgnoredButTheSafetyNetStillFires() {
        val t = tracker()
        assertFalse(t.onForeground(null, 1_000))
        assertFalse(t.onForeground(null, 60_000))
        assertTrue(t.onForeground(null, AsideTracker.MAX_MS), "never stays off for more than fifteen minutes")
        assertTrue(tracker(start = 1_000).onForeground("com.phonepe.app", 1_000 + AsideTracker.MAX_MS))
    }

    private val long = "com.gainandshine.unloop/com.gainandshine.unloop.a11y.UnloopAccessibilityService"
    private val short = "com.gainandshine.unloop/.a11y.UnloopAccessibilityService"
    private val names = setOf(long, short)

    @Test fun removingOurServiceKeepsTheOthers() {
        assertEquals("com.other/.Svc", AccessibilityList.without("com.other/.Svc:$long", names))
        assertEquals("com.other/.Svc", AccessibilityList.without("$short:com.other/.Svc", names))
        assertEquals("", AccessibilityList.without(long, names))
        assertEquals("", AccessibilityList.without(null, names))
        assertEquals("com.other/.Svc", AccessibilityList.without("com.other/.Svc", names))
    }

    @Test fun addingBackDoesNotDuplicate() {
        assertEquals(long, AccessibilityList.with(null, names, long))
        assertEquals("com.other/.Svc:$long", AccessibilityList.with("com.other/.Svc", names, long))
        assertEquals("com.other/.Svc:$short", AccessibilityList.with("com.other/.Svc:$short", names, long))
        assertTrue(AccessibilityList.contains("a:$short", names))
        assertFalse(AccessibilityList.contains("a:b", names))
    }

    @Test fun parsingToleratesBlanksAndSpaces() {
        assertEquals(listOf("a", "b"), AccessibilityList.parse(" a :: b: "))
        assertEquals(emptyList(), AccessibilityList.parse(""))
    }
}

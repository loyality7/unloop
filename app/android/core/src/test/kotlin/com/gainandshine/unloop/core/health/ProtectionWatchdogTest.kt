package com.gainandshine.unloop.core.health

import com.gainandshine.unloop.core.clock.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtectionWatchdogTest {
    private val clock = FakeClock(mono = 1_000_000)
    private val dog = ProtectionWatchdog(clock)
    private val hour = 3_600_000L

    private fun acc(intent: Boolean, reality: Boolean, alive: Long? = null) = Probe(Check.ACCESSIBILITY, intent, reality, alive)

    @Test
    fun classifyTable() {
        assertEquals(FaultKind.ACCESSIBILITY_OFF, dog.classify(acc(false, false)))
        assertEquals(FaultKind.ACCESSIBILITY_OFF, dog.classify(acc(false, true)))
        assertEquals(FaultKind.ACCESSIBILITY_CRASHED, dog.classify(acc(true, false)))
        assertNull(dog.classify(acc(true, true)))
        assertEquals(FaultKind.USAGE_ACCESS_OFF, dog.classify(Probe(Check.USAGE_ACCESS, true, false, null)))
        assertNull(dog.classify(Probe(Check.USAGE_ACCESS, false, false, null))) // not needed
        assertEquals(FaultKind.NOTIFICATION_ACCESS_OFF, dog.classify(Probe(Check.NOTIFICATION_ACCESS, true, false, null)))
        assertEquals(FaultKind.BATTERY_RESTRICTED, dog.classify(Probe(Check.BATTERY, true, false, null)))
        assertNull(dog.classify(Probe(Check.BATTERY, true, true, null)))
    }

    @Test
    fun rebindGraceSuppressesCrashedBriefly() {
        assertNull(dog.classify(acc(true, false, alive = clock.monotonicMs() - 29_999)))
        assertEquals(FaultKind.ACCESSIBILITY_CRASHED, dog.classify(acc(true, false, alive = clock.monotonicMs() - 30_000)))
    }

    @Test
    fun twoStrikesThenCooldown() {
        val p = acc(true, false)
        assertEquals(Observation(Health.Healthy, false), dog.observe(p))
        assertEquals(Observation(Health.Fault(FaultKind.ACCESSIBILITY_CRASHED), true), dog.observe(p))
        assertFalse(dog.observe(p).alert)
        clock.advanceMs(12 * hour - 1)
        assertFalse(dog.observe(p).alert)
        clock.advanceMs(1)
        assertTrue(dog.observe(p).alert)
        assertFalse(dog.observe(p).alert)
    }

    @Test
    fun healthyResetsStrikesAndCooldown() {
        val bad = acc(true, false)
        dog.observe(bad)
        assertTrue(dog.observe(bad).alert)
        assertEquals(Observation(Health.Healthy, false), dog.observe(acc(true, true)))
        assertEquals(Observation(Health.Healthy, false), dog.observe(bad)) // strike 1 again
        assertTrue(dog.observe(bad).alert) // fresh alert, cooldown was cleared by recovery
    }

    @Test
    fun checksAreIndependent() {
        val a = Probe(Check.USAGE_ACCESS, true, false, null)
        val b = Probe(Check.BATTERY, true, false, null)
        dog.observe(a)
        assertEquals(Health.Healthy, dog.observe(b).health) // battery strike 1, not fault
        assertEquals(Health.Fault(FaultKind.USAGE_ACCESS_OFF), dog.observe(a).health)
    }

    @Test
    fun changeGatePostsOnlyOnTextChange() {
        val g = ChangeGate()
        assertTrue(g.shouldPost("A needs attention"))
        assertFalse(g.shouldPost("A needs attention"))
        assertTrue(g.shouldPost("B needs attention"))
        g.clear()
        assertTrue(g.shouldPost("B needs attention"))
    }

    @Test
    fun repairTable() {
        assertEquals(FaultKind.entries.toSet(), RepairTargets.ALL.map { it.kind }.toSet())
        assertTrue(RepairTargets.ALL.all { it.settingsActionName.startsWith("android.settings.") })
        assertEquals("android.settings.ACCESSIBILITY_SETTINGS", RepairTargets.forKind(FaultKind.ACCESSIBILITY_CRASHED).settingsActionName)
        assertEquals("android.settings.USAGE_ACCESS_SETTINGS", RepairTargets.forKind(FaultKind.USAGE_ACCESS_OFF).settingsActionName)
    }
}

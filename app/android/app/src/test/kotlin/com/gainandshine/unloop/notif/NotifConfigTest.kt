package com.gainandshine.unloop.notif

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotifConfigTest {
    @Test fun scheduleOnlyRoundTripsAndDefaultsToOff() {
        val c = NotifConfig(enabled = true, scheduleOnly = true)
        assertTrue(NotifConfig.fromJson(c.toJson()).scheduleOnly)
        assertFalse(NotifConfig.fromJson("{}").scheduleOnly)
        assertFalse(NotifConfig.fromJson("not json").scheduleOnly)
        assertEquals(true, NotifConfig.fromJson(c.toJson()).enabled)
    }

    @Test fun theNoiseRuleOnlyAppliesInsideSchedulesWhenAsked() {
        val noise = { c: NotifConfig, active: Boolean -> c.toRules("own") { _: Instant -> active }.rules.last() }
        val all = noise(NotifConfig(enabled = true, scheduleOnly = false), false)
        val gated = noise(NotifConfig(enabled = true, scheduleOnly = true), false)
        val now = Instant.parse("2026-01-01T10:00:00Z")
        assertTrue(all.schedule(now), "snoozes all day when not schedule-only")
        assertFalse(gated.schedule(now), "does nothing outside the user's schedules")
        assertTrue(noise(NotifConfig(enabled = true, scheduleOnly = true), true).schedule(now))
    }
}

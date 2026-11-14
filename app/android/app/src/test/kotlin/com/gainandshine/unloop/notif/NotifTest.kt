package com.gainandshine.unloop.notif

import com.gainandshine.unloop.core.notif.HandledKeys
import com.gainandshine.unloop.core.notif.NotifDecision
import com.gainandshine.unloop.core.notif.NotificationPolicy
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private fun ev(template: String?, flags: Int = 0, pkg: String = "com.instagram.android") =
        NotifMapping.toEvent(pkg, "k", "ch", null, flags, template, "t", "x", 0, false)

    @Test fun mapsFlagsAndTemplate() {
        val e = ev("android.app.Notification\$MessagingStyle", NotifMapping.FLAG_ONGOING_EVENT or NotifMapping.FLAG_GROUP_SUMMARY)
        assertTrue(e.isMessagingStyle && e.isOngoing && e.isGroupSummary && !e.isForegroundService)
        assertFalse(ev("android.app.Notification\$BigTextStyle").isMessagingStyle)
        assertFalse(ev(null).isMessagingStyle)
    }

    @Test fun defaultsKeepDmsAndSnoozeNoise() {
        val rules = NotifConfig(enabled = true).toRules("com.gainandshine.unloop")
        assertEquals(NotifDecision.Keep, NotificationPolicy.decide(ev("x.MessagingStyle"), rules, now, HandledKeys()))
        assertTrue(NotificationPolicy.decide(ev(null), rules, now, HandledKeys()) is NotifDecision.Snooze)
        assertEquals(NotifDecision.Keep, NotificationPolicy.decide(ev(null, pkg = "com.other"), rules, now, HandledKeys()))
    }

    @Test fun configIsOffByDefaultAndRoundTrips() {
        assertFalse(NotifConfig().enabled)
        assertFalse(NotifConfig.fromJson("garbage").enabled)
        val c = NotifConfig.fromJson(NotifConfig(true, setOf("a.b"), 5000, setOf("c.d")).toJson())
        assertTrue(c.enabled)
        assertEquals(setOf("a.b"), c.packages)
        assertEquals(5000L, c.snoozeMs)
        assertEquals(setOf("c.d"), c.allowlist)
    }

    @Test fun storePersists() {
        val f = File.createTempFile("notif", ".json").also { it.delete(); it.deleteOnExit() }
        val s = NotifRuleStore(f, "me")
        assertFalse(s.config().enabled)
        s.save(NotifConfig(enabled = true))
        assertTrue(s.config().enabled)
    }
}

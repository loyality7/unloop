package com.gainandshine.unloop.core.notif

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationPolicyTest {
    private val now = Instant.parse("2026-01-05T10:00:00Z")
    private val ig = "com.instagram.android"
    private val windowStart = Instant.parse("2026-01-05T09:00:00Z")
    private val windowEnd = Instant.parse("2026-01-05T17:00:00Z")
    private val inWindow: (Instant) -> Boolean = { it >= windowStart && it < windowEnd }

    private val snoozeMs = 3_600_000L
    private val rules = NotifRules(
        ownPackage = "com.gainandshine.unloop",
        allowlist = setOf("com.bank.app"),
        rules = NotifDefaults.engagementNoise("ig", setOf(ig), snoozeMs) +
            listOf(
                NotifRule("tt-cancel", RuleAction.Cancel, packages = setOf("com.zhiliaoapp.musically")),
                NotifRule("yt-defer", RuleAction.Defer, packages = setOf("com.google.android.youtube"), channelIds = setOf("recs")),
                NotifRule("x-reroute", RuleAction.Reroute("inbox"), packages = setOf("com.twitter.android"), categories = setOf(NotifCategory.SOCIAL)),
                NotifRule("snap-pattern", RuleAction.Snooze(snoozeMs), packages = setOf("com.snapchat.android"), textPatterns = listOf(Regex("(?i)went live|suggested"))),
                NotifRule("fb-sched", RuleAction.Snooze(snoozeMs), packages = setOf("com.facebook.katana"), schedule = inWindow),
                NotifRule("li-expired", RuleAction.Cancel, packages = setOf("com.linkedin.android"), expiresAt = Instant.parse("2026-01-05T09:59:59Z")),
                NotifRule("any-promo", RuleAction.Defer, categories = setOf(NotifCategory.PROMO)),
            ),
    )

    private fun ev(pkg: String, key: String = "k-$pkg", at: Instant = now, build: (MutableMap<String, Any?>) -> Unit = {}): Pair<NotifEvent, Instant> {
        val m = mutableMapOf<String, Any?>()
        build(m)
        return NotifEvent(
            pkg = pkg, key = key,
            channelId = m["channel"] as String?, category = m["category"] as String?,
            title = m["title"] as String?, text = m["text"] as String?,
            isOngoing = m["ongoing"] == true, isForegroundService = m["fgs"] == true,
            isGroupSummary = m["summary"] == true, isMessagingStyle = m["msg"] == true,
            hasMarker = m["marker"] == true,
        ) to at
    }

    private fun decide(p: Pair<NotifEvent, Instant>, state: HandledState = HandledState { _, _, _ -> false }) =
        NotificationPolicy.decide(p.first, rules, p.second, state)

    private class Row(val name: String, val ev: Pair<NotifEvent, Instant>, val expect: (NotifDecision) -> Boolean)

    private fun keep() = { d: NotifDecision -> d == NotifDecision.Keep }
    private fun snooze() = { d: NotifDecision -> d is NotifDecision.Snooze && d.until == now.plusMillis(snoozeMs) }

    @Test fun table() {
        val rows = listOf(
            Row("IG DM messaging style kept", ev(ig) { it["msg"] = true }, keep()),
            Row("IG DM by category msg kept", ev(ig) { it["category"] = NotifCategory.MESSAGE }, keep()),
            Row("IG like notification snoozed", ev(ig) { it["title"] = "x liked your photo" }, snooze()),
            Row("IG suggested reel snoozed", ev(ig) { it["category"] = NotifCategory.RECOMMENDATION }, snooze()),
            Row("IG ongoing kept", ev(ig) { it["ongoing"] = true }, keep()),
            Row("IG foreground service kept", ev(ig) { it["fgs"] = true }, keep()),
            Row("IG call category kept", ev(ig) { it["category"] = NotifCategory.CALL }, keep()),
            Row("IG alarm category kept", ev(ig) { it["category"] = NotifCategory.ALARM }, keep()),
            Row("IG navigation category kept", ev(ig) { it["category"] = NotifCategory.NAVIGATION }, keep()),
            Row("IG transport category kept", ev(ig) { it["category"] = NotifCategory.TRANSPORT }, keep()),
            Row("own package kept", ev("com.gainandshine.unloop"), keep()),
            Row("own marker kept", ev(ig) { it["marker"] = true }, keep()),
            Row("system ui kept", ev("com.android.systemui"), keep()),
            Row("emergency kept", ev("com.android.emergency"), keep()),
            Row("allowlisted app kept even for promo", ev("com.bank.app") { it["category"] = NotifCategory.PROMO }, keep()),
            Row("unknown package kept", ev("com.random.app"), keep()),
            Row("TikTok cancel (explicit rule)", ev("com.zhiliaoapp.musically"), { it == NotifDecision.Cancel }),
            Row("YouTube recs channel deferred", ev("com.google.android.youtube") { it["channel"] = "recs" }, { it == NotifDecision.Defer }),
            Row("YouTube other channel kept", ev("com.google.android.youtube") { it["channel"] = "subs" }, keep()),
            Row("YouTube null channel kept", ev("com.google.android.youtube"), keep()),
            Row("X social rerouted", ev("com.twitter.android") { it["category"] = NotifCategory.SOCIAL }, { it is NotifDecision.Reroute && it.target == "inbox" }),
            Row("X non-social kept", ev("com.twitter.android") { it["category"] = NotifCategory.MESSAGE }, keep()),
            Row("Snapchat went live snoozed (title)", ev("com.snapchat.android") { it["title"] = "Ann went live" }, snooze()),
            Row("Snapchat suggested snoozed (text)", ev("com.snapchat.android") { it["text"] = "Suggested for you" }, snooze()),
            Row("Snapchat plain message kept", ev("com.snapchat.android") { it["text"] = "hey" }, keep()),
            Row("Snapchat null text kept", ev("com.snapchat.android"), keep()),
            Row("FB before window kept", ev("com.facebook.katana", at = windowStart.minusNanos(1)), keep()),
            Row("FB at window start snoozed", ev("com.facebook.katana", at = windowStart), { it is NotifDecision.Snooze }),
            Row("FB last ns of window snoozed", ev("com.facebook.katana", at = windowEnd.minusNanos(1)), { it is NotifDecision.Snooze }),
            Row("FB at window end kept", ev("com.facebook.katana", at = windowEnd), keep()),
            Row("LinkedIn rule expired kept", ev("com.linkedin.android"), keep()),
            Row("Promo from any app deferred", ev("com.shop.app") { it["category"] = NotifCategory.PROMO }, { it == NotifDecision.Defer }),
            Row("Promo with call category still protected", ev("com.shop.app") { it["category"] = NotifCategory.CALL }, keep()),
        )
        assertTrue(rows.size >= 30)
        for (r in rows) assertTrue(r.expect(decide(r.ev)), r.name)
    }

    @Test fun expiryBoundaryIsExclusive() {
        val r = NotifRules("com.gainandshine.unloop", listOf(NotifRule("t", RuleAction.Cancel, packages = setOf(ig), expiresAt = now)))
        val e = NotifEvent(ig, "k")
        assertEquals(NotifDecision.Keep, NotificationPolicy.decide(e, r, now, HandledState { _, _, _ -> false }))
        assertEquals(NotifDecision.Cancel, NotificationPolicy.decide(e, r, now.minusNanos(1), HandledState { _, _, _ -> false }))
    }

    @Test fun firstMatchWins() {
        val r = NotifRules("com.gainandshine.unloop", listOf(
            NotifRule("a", RuleAction.Allow, packages = setOf(ig), messagingStyle = true),
            NotifRule("b", RuleAction.Cancel, packages = setOf(ig)),
        ))
        assertEquals(NotifDecision.Keep, NotificationPolicy.decide(NotifEvent(ig, "1", isMessagingStyle = true), r, now, HandledState { _, _, _ -> false }))
        assertEquals(NotifDecision.Cancel, NotificationPolicy.decide(NotifEvent(ig, "2"), r, now, HandledState { _, _, _ -> false }))
    }

    @Test fun messagingFalseOnlyMatchesNonMessaging() {
        val r = NotifRules("com.gainandshine.unloop", listOf(NotifRule("a", RuleAction.Defer, messagingStyle = false)))
        assertEquals(NotifDecision.Defer, NotificationPolicy.decide(NotifEvent("p", "1"), r, now, HandledState { _, _, _ -> false }))
        assertEquals(NotifDecision.Keep, NotificationPolicy.decide(NotifEvent("p", "2", isMessagingStyle = true), r, now, HandledState { _, _, _ -> false }))
    }

    @Test fun idempotencyHandledKeyIsKeptOnRepost() {
        val state = HandledKeys()
        val e = NotifEvent(ig, "key-1", title = "liked")
        assertIs<NotifDecision.Snooze>(NotificationPolicy.decideAndRecord(e, rules, now, state))
        assertEquals(NotifDecision.Keep, NotificationPolicy.decideAndRecord(e, rules, now, state), "re-post after snooze must not loop")
        assertIs<NotifDecision.Snooze>(NotificationPolicy.decideAndRecord(NotifEvent(ig, "key-2"), rules, now, state))
    }

    @Test fun keepDecisionIsNotRecorded() {
        val state = HandledKeys()
        val dm = NotifEvent(ig, "dm", isMessagingStyle = true)
        assertEquals(NotifDecision.Keep, NotificationPolicy.decideAndRecord(dm, rules, now, state))
        assertTrue(!state.isHandled("dm", 0, now))
    }

    @Test fun handledKeysAreBounded() {
        val s = HandledKeys(capacity = 3)
        for (i in 1..5) s.mark("k$i", 0, now)
        assertTrue(!s.isHandled("k1", 0, now) && !s.isHandled("k2", 0, now) && s.isHandled("k3", 0, now) && s.isHandled("k5", 0, now))
    }

    @Test fun sweepReturnsOnlyActionableAndMarksThem() {
        val state = HandledKeys()
        val events = listOf(
            NotifEvent(ig, "a", title = "liked"),
            NotifEvent(ig, "b", isMessagingStyle = true),
            NotifEvent("com.zhiliaoapp.musically", "c"),
            NotifEvent(ig, "d", isOngoing = true),
        )
        val out = NotificationPolicy.sweep(events, rules, now, state)
        assertEquals(listOf("a", "c"), out.map { it.first.key })
        assertIs<NotifDecision.Snooze>(out[0].second)
        assertEquals(NotifDecision.Cancel, out[1].second)
        assertTrue(NotificationPolicy.sweep(events, rules, now, state).isEmpty(), "second sweep is idempotent")
    }

    @Test fun defaultsShape() {
        val d = NotifDefaults.engagementNoise("x", setOf("p"))
        assertEquals(3, d.size)
        assertTrue(d.none { it.action == RuleAction.Cancel })
        val last = d.last().action
        assertIs<RuleAction.Snooze>(last)
        assertEquals(NotifDefaults.DEFAULT_SNOOZE_MS, last.durationMs)
    }

    @Test fun reusedKeyWithNewPostTimeIsDecidedAgain() {
        val state = HandledKeys()
        val first = NotifEvent(ig, "thread", title = "liked", whenMs = 1_000)
        assertIs<NotifDecision.Snooze>(NotificationPolicy.decideAndRecord(first, rules, now, state))
        assertEquals(NotifDecision.Keep, NotificationPolicy.decideAndRecord(first, rules, now, state), "same post")
        val next = NotifEvent(ig, "thread", title = "liked", whenMs = 2_000)
        assertIs<NotifDecision.Snooze>(NotificationPolicy.decideAndRecord(next, rules, now, state), "new content, same key")
    }

    @Test fun handledEntriesExpireAfterTtl() {
        val state = HandledKeys(ttl = Duration.ofHours(1))
        state.mark("k", 5, now)
        assertTrue(state.isHandled("k", 5, now.plus(Duration.ofMinutes(59))))
        assertTrue(!state.isHandled("k", 5, now.plus(Duration.ofHours(1))))
    }
}

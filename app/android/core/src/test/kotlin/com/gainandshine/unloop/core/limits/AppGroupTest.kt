package com.gainandshine.unloop.core.limits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppGroupTest {
    private val social = AppGroup("g1", "Social", setOf("ig", "tt", "x"), 3600)

    @Test fun usageIsSummedAcrossMembers() {
        val used = mapOf("ig" to 1_200_000L, "tt" to 600_000L, "x" to 0L, "other" to 9_999_999L)
        assertEquals(1800, AppGroups.usedSeconds(social) { used[it] ?: 0L })
    }

    @Test fun sharedQuotaGoesOverWhenTheSumPassesTheLimit() {
        val day = DayKey.of(java.time.Instant.parse("2026-01-01T10:00:00Z"), java.time.ZoneOffset.UTC)
        val now = java.time.Instant.parse("2026-01-01T10:00:00Z")
        val under = QuotaEvaluator.evaluate(social.rule(), 3000, day, emptyList(), now)
        assertEquals(QuotaState.WARNING, under.state)
        val over = QuotaEvaluator.evaluate(social.rule(), 3600, day, emptyList(), now)
        assertEquals(QuotaState.OVER, over.state)
    }

    @Test fun membershipHelpers() {
        val other = AppGroup("g2", "Video", setOf("yt", "ig"), 600)
        assertEquals(listOf("g1", "g2"), AppGroups.containing("ig", listOf(social, other)).map { it.id })
        assertEquals(emptyList(), AppGroups.containing("zzz", listOf(social, other)))
        assertEquals(setOf("ig", "tt", "x", "yt"), AppGroups.members(listOf(social, other)))
    }

    @Test fun jsonRoundTripAndTolerance() {
        val back = AppGroups.parse(AppGroups.write(listOf(social)))
        assertEquals(listOf(social), back)
        assertEquals(emptyList(), AppGroups.parse("not json"))
        assertEquals(1, AppGroups.parse("""[{"id":"a","pkgs":["p"],"dailySeconds":60},{"bad":true}]""").size)
        assertEquals("Group", AppGroups.parse("""[{"id":"a","name":" ","pkgs":[],"dailySeconds":1}]""").single().name)
    }

    @Test fun strictLetsGroupsOnlyTightenOrGrow() {
        assertTrue(StrictPolicy.groupsAllowed(listOf(social), listOf(social, social.copy(id = "g2"))))
        assertTrue(StrictPolicy.groupsAllowed(listOf(social), listOf(social.copy(dailySeconds = 600, pkgs = social.pkgs + "yt"))))
        assertFalse(StrictPolicy.groupsAllowed(listOf(social), emptyList()))
        assertFalse(StrictPolicy.groupsAllowed(listOf(social), listOf(social.copy(dailySeconds = 7200))))
        assertFalse(StrictPolicy.groupsAllowed(listOf(social), listOf(social.copy(pkgs = setOf("ig")))))
    }
}

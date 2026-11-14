package com.gainandshine.unloop.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyGuardTest {
    @Test fun knownPaymentAppsAreNeverWatched() {
        listOf(
            "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "in.org.npci.upiapp",
            "com.google.android.apps.walletnfcrel", "com.sbi.lotusintouch", "com.dreamplug.androidapp",
        ).forEach { assertTrue(PrivacyGuard.isNeverWatched(it), it) }
    }

    @Test fun financialLookingNamesAreCaughtByHeuristic() {
        listOf("in.example.bank.app", "com.foo.hdfcbank", "com.bar.upi", "com.acme.netbanking", "com.some.mobikwik.lite")
            .forEach { assertTrue(PrivacyGuard.looksFinancial(it), it) }
    }

    @Test fun supportedAppsAndCommonAppsAreNotMisclassified() {
        listOf(
            "com.instagram.android", "com.google.android.youtube", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.snapchat.android", "com.facebook.katana", "com.android.chrome", "com.whatsapp", "com.android.launcher3",
            "com.google.android.apps.nexuslauncher", "com.microsoft.launcher", "org.mozilla.firefox", "com.spotify.music",
        ).forEach { assertFalse(PrivacyGuard.isNeverWatched(it), it) }
    }

    @Test fun watchedSetIsSupportedPlusLaunchersPlusTargetsMinusFinancial() {
        val w = PrivacyGuard.watchedPackages(
            supported = setOf("com.instagram.android", "com.google.android.youtube"),
            launchers = setOf("com.android.launcher3"),
            targets = setOf("com.android.chrome", "com.phonepe.app", "in.example.bank.app"),
        )
        assertEquals(setOf("com.instagram.android", "com.google.android.youtube", "com.android.launcher3", "com.android.chrome"), w)
    }

    @Test fun financialAppCanNeverBeReadEvenIfScopeIsUnset() {
        assertFalse(PrivacyGuard.canRead("com.phonepe.app", null))
        assertFalse(PrivacyGuard.canRead("com.phonepe.app", setOf("com.phonepe.app")))
        assertTrue(PrivacyGuard.canRead("com.instagram.android", null))
        assertTrue(PrivacyGuard.canRead("com.instagram.android", setOf("com.instagram.android")))
        assertFalse(PrivacyGuard.canRead("com.whatsapp", setOf("com.instagram.android")))
    }

    @Test fun pauseWindowBoundaries() {
        val until = PausePolicy.untilFor(30, 1_000_000L)
        assertEquals(1_000_000L + 30 * 60_000L, until)
        assertTrue(PausePolicy.isPaused(until, until - 1))
        assertFalse(PausePolicy.isPaused(until, until))
        assertFalse(PausePolicy.isPaused(0L, 5L))
        assertEquals(0L, PausePolicy.remainingMs(until, until + 10))
        assertEquals(60_000L, PausePolicy.remainingMs(until, until - 60_000L))
    }

    @Test fun pauseIsCappedAtOneDayAndAtLeastOneMinute() {
        assertEquals(1_000L + PausePolicy.MAX_MINUTES * 60_000L, PausePolicy.untilFor(10_000, 1_000L))
        assertEquals(1_000L + 60_000L, PausePolicy.untilFor(0, 1_000L))
    }

    @Test fun nullSnapshotBackoffStopsPollingAfterCap() {
        val b = NullSnapBackoff()
        val seen = generateSequence { b.nextOrNull() }.toList()
        assertEquals(NullSnapBackoff.MAX_ATTEMPTS, seen.size)
        assertEquals(250L, seen.first())
        assertTrue(seen.all { it <= 2000L })
        b.reset()
        assertEquals(250L, b.nextOrNull())
    }
}

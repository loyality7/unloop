package com.gainandshine.unloop.core.limits

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrictEscapeGuardTest {
    private val s = "com.android.settings"
    private fun bounce(pkg: String, vararg t: String) = StrictEscapeGuard.shouldBounce(pkg, t.toList(), "Unloop")

    @Test fun accessibilityDetailPageIsGuarded() {
        assertTrue(bounce(s, "Unloop", "Use Unloop", "Off"))
        assertTrue(bounce(s, "Unloop", "Accessibility shortcut", "Off"))
    }

    @Test fun appInfoPageIsGuarded() {
        assertTrue(bounce(s, "Unloop", "Open", "Uninstall", "Force stop"))
    }

    @Test fun accessibilityListAndOtherPagesAreNotGuarded() {
        assertFalse(bounce(s, "Downloaded apps", "Unloop", "Off"))
        assertFalse(bounce(s, "Wi-Fi", "Network", "Use Wi-Fi calling"))
        assertFalse(bounce(s, "Uninstall", "Force stop", "Other app"))
    }

    @Test fun otherPackagesAndBlankLabelAreNeverGuarded() {
        assertFalse(bounce("com.example.app", "Unloop", "Use Unloop"))
        assertFalse(StrictEscapeGuard.shouldBounce(s, listOf("Use ", "Uninstall", "Force stop"), ""))
        assertFalse(StrictEscapeGuard.shouldBounce(s, emptyList(), "Unloop"))
    }

    @Test fun matchingIgnoresCase() {
        assertTrue(bounce(s, "UNLOOP", "USE UNLOOP"))
    }
}

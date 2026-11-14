package com.gainandshine.unloop.block

/** Pure guards for the enforcer: the never-block allowlist and the launch debounce. No android imports. */
object NeverBlock {
    /** Explicit list on purpose: a blanket `com.android.` prefix would also shield user-limited apps. */
    private val STATIC = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.android.emergency", "com.android.server.telecom", "com.android.incallui",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
    )

    /** [dynamic] covers own package, launchers and IMEs, which only the shell can resolve. */
    fun isProtected(pkg: String, dynamic: Set<String>): Boolean = pkg in STATIC || pkg in dynamic
}

/**
 * Suppresses repeated launches. Same package within [gapMs] of the last launch is dropped (covers the block
 * screen's own window events and OS re-delivery); after that it may fire again, so reopening a still-blocked
 * app is blocked again.
 */
class BlockDebounce(private val gapMs: Long = 1000) {
    private val last = HashMap<String, Long>()

    @Synchronized fun shouldFire(pkg: String, monoMs: Long): Boolean {
        val prev = last[pkg]
        if (prev != null && monoMs - prev < gapMs) return false
        last[pkg] = monoMs
        return true
    }
}

/** Warn notifications: at most one per package per [windowMs]. */
class WarnThrottle(private val windowMs: Long = 30L * 60 * 1000) {
    private val last = HashMap<String, Long>()

    @Synchronized fun shouldWarn(pkg: String, monoMs: Long): Boolean {
        val prev = last[pkg]
        if (prev != null && monoMs - prev < windowMs) return false
        last[pkg] = monoMs
        return true
    }
}

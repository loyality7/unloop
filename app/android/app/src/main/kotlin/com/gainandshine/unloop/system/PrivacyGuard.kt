package com.gainandshine.unloop.system

/**
 * What Unloop is allowed to watch. Pure (no Android types) so it is unit-tested on the JVM.
 *
 * Principle: the accessibility service only listens to the apps it supports, the home launcher, and apps the
 * user explicitly set limits on, and it NEVER watches payment, wallet or banking apps. Those apps check whether
 * any accessibility service is enabled and may warn about it; that check is in their process and cannot be
 * influenced from here, and Unloop does not try to hide itself. What we control is that we never read them.
 * With the user's opt-in (see PaymentAside) Unloop can also truly switch itself off while such an app is open.
 */
object PrivacyGuard {
    /** Well known UPI, wallet and bank apps. Not exhaustive: [looksFinancial] catches the rest by name. */
    val NEVER_WATCHED = setOf(
        "com.phonepe.app",
        "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
        "com.google.android.apps.walletnfcrel", // Google Wallet
        "net.one97.paytm",
        "in.org.npci.upiapp", // BHIM
        "in.amazon.mShop.android.shopping", // Amazon Pay lives inside the shopping app
        "com.mobikwik_new",
        "com.freecharge.android",
        "com.dreamplug.androidapp", // CRED
        "com.enstage.wibmo.hdfc", // PayZapp
        "com.sbi.lotusintouch", // YONO SBI
        "com.csam.icici.bank.imobile",
        "com.snapwork.hdfc",
        "com.axis.mobile",
        "com.msf.kbank.mobile",
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
    )

    /** Fragments that mark a package as financial when they appear as a whole dot-separated word or inside one. */
    private val CONTAINS = listOf("paisa", "phonepe", "paytm", "bhim", "mobikwik", "freecharge", "payzapp", "razorpay", "wallet")

    /** True for UPI/wallet/bank style package names, e.g. `in.example.bank.app`, `com.foo.upi`. */
    fun looksFinancial(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (CONTAINS.any { p.contains(it) }) return true
        return p.split('.', '_').any { it == "upi" || it == "banking" || it == "netbanking" || it.endsWith("bank") }
    }

    fun isNeverWatched(pkg: String): Boolean = pkg in NEVER_WATCHED || looksFinancial(pkg)

    /** Packages the accessibility service may listen to: supported apps + launchers + user targets, minus financial apps. */
    fun watchedPackages(supported: Set<String>, launchers: Set<String>, targets: Set<String>): Set<String> =
        (supported + launchers + targets).filterNot(::isNeverWatched).toCollection(LinkedHashSet())

    /** Whether the snapshotter may read a window of [pkg]. [allowed] null means "no scope applied yet". */
    fun canRead(pkg: String, allowed: Set<String>?): Boolean = !isNeverWatched(pkg) && (allowed == null || pkg in allowed)
}

/** Timed pause of all protection. Wall-clock epoch milliseconds; 0 means not paused. */
object PausePolicy {
    const val DEFAULT_MINUTES = 30

    fun isPaused(untilWallMs: Long, nowWallMs: Long) = nowWallMs < untilWallMs
    fun remainingMs(untilWallMs: Long, nowWallMs: Long) = (untilWallMs - nowWallMs).coerceAtLeast(0L)
    fun untilFor(minutes: Int, nowWallMs: Long) = nowWallMs + minutes.coerceIn(1, MAX_MINUTES) * 60_000L

    /** Longest allowed pause: one day, so a forgotten pause cannot silently disable protection for good. */
    const val MAX_MINUTES = 24 * 60
}

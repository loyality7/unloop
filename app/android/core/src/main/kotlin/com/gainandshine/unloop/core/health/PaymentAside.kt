package com.gainandshine.unloop.core.health

/**
 * Payment apps refuse to work, or warn, while any accessibility service is switched on. "Stepping aside" means
 * Unloop really switches itself off while such an app is in front and back on once the user leaves it. The app then
 * sees the truth: no accessibility service. These two pieces are the pure parts of that.
 */
class AsideTracker(
    private val isPayment: (String) -> Boolean,
    private val startedAtMs: Long,
    private val awayMs: Long = AWAY_MS,
    private val maxMs: Long = MAX_MS,
) {
    private var awaySince: Long? = null

    /**
     * Feed the package currently in front (null when unknown: ignored) at [nowMs]. True when Unloop should switch back
     * on: the user has been out of every payment app for [awayMs], or the step-aside has lasted [maxMs] (a safety
     * net so it can never stay off for long).
     */
    fun onForeground(pkg: String?, nowMs: Long): Boolean {
        if (nowMs - startedAtMs >= maxMs) return true
        if (pkg == null) return false
        if (isPayment(pkg)) {
            awaySince = null
            return false
        }
        val since = awaySince ?: nowMs.also { awaySince = it }
        return nowMs - since >= awayMs
    }

    companion object {
        /** Out of the payment app this long (so a quick app switch inside a payment flow does not flip it on and off). */
        const val AWAY_MS = 1_500L
        const val MAX_MS = 15 * 60_000L
    }
}

/**
 * Edits the colon separated `enabled_accessibility_services` setting. A service can be listed in a long or a short
 * component form, so all of [names] are treated as the same service.
 */
object AccessibilityList {
    fun parse(list: String?): List<String> = list.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    fun without(list: String?, names: Set<String>): String = parse(list).filterNot { it in names }.joinToString(":")

    /** Adds [preferred] unless one of [names] is already listed. Keeps the order of the other services. */
    fun with(list: String?, names: Set<String>, preferred: String): String {
        val cur = parse(list)
        return if (cur.any { it in names }) cur.joinToString(":") else (cur + preferred).joinToString(":")
    }

    fun contains(list: String?, names: Set<String>): Boolean = parse(list).any { it in names }
}

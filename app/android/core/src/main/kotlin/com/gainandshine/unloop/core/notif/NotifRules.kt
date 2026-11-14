package com.gainandshine.unloop.core.notif

import java.time.Instant

sealed interface RuleAction {
    /** Stop evaluation and leave the notification alone (DM / allowlist rules). */
    data object Allow : RuleAction

    /** Preferred: nothing is lost, the OS re-posts it later. */
    class Snooze(val durationMs: Long) : RuleAction

    /** Hand to the local digest. */
    data object Defer : RuleAction

    /** Drop permanently. Only create such a rule on an explicit user opt-in. */
    data object Cancel : RuleAction

    class Reroute(val target: String) : RuleAction
}

/**
 * One ordered rule; every non-empty criterion must match (empty = any). [schedule] says whether the rule
 * is enforcing at an instant; [expiresAt] ends it (a timed block must not outlive its timer).
 */
class NotifRule(
    val id: String,
    val action: RuleAction,
    val packages: Set<String> = emptySet(),
    val channelIds: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    /** null = any, true = only MessagingStyle, false = only non-MessagingStyle. */
    val messagingStyle: Boolean? = null,
    val textPatterns: List<Regex> = emptyList(),
    val schedule: (Instant) -> Boolean = { true },
    val expiresAt: Instant? = null,
) {
    fun matches(e: NotifEvent, now: Instant): Boolean {
        if (expiresAt != null && !now.isBefore(expiresAt)) return false
        if (!schedule(now)) return false
        if (packages.isNotEmpty() && e.pkg !in packages) return false
        if (channelIds.isNotEmpty() && e.channelId !in channelIds) return false
        if (categories.isNotEmpty() && e.category !in categories) return false
        if (messagingStyle != null && messagingStyle != e.isMessagingStyle) return false
        if (textPatterns.isNotEmpty() && textPatterns.none { it.containsMatchIn(e.title.orEmpty()) || it.containsMatchIn(e.text.orEmpty()) }) {
            return false
        }
        return true
    }
}

/** [rules] are evaluated in order, first match wins. [allowlist] packages are never touched. */
class NotifRules(
    val ownPackage: String,
    val rules: List<NotifRule>,
    val allowlist: Set<String> = emptySet(),
) {
    companion object {
        /** Packages the system depends on; never touched. */
        val SYSTEM_PACKAGES: Set<String> = setOf(
            "android", "com.android.systemui", "com.android.emergency", "com.android.phone",
            "com.android.server.telecom", "com.google.android.apps.safetyhub",
        )
    }
}

/** DM-preserving defaults. */
object NotifDefaults {
    const val DEFAULT_SNOOZE_MS = 60L * 60 * 1000

    /**
     * For [packages]: messaging-style and `msg`-category notifications are kept; everything else is snoozed
     * while [schedule] is enforcing (snooze, never cancel).
     */
    fun engagementNoise(
        id: String,
        packages: Set<String>,
        snoozeMs: Long = DEFAULT_SNOOZE_MS,
        schedule: (Instant) -> Boolean = { true },
    ): List<NotifRule> = listOf(
        NotifRule("$id-dm", RuleAction.Allow, packages = packages, messagingStyle = true),
        NotifRule("$id-msg", RuleAction.Allow, packages = packages, categories = setOf(NotifCategory.MESSAGE)),
        NotifRule("$id-noise", RuleAction.Snooze(snoozeMs), packages = packages, schedule = schedule),
    )
}

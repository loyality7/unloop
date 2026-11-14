package com.gainandshine.unloop.core.notif

import java.time.Duration
import java.time.Instant

/** Read-only view of already-handled notifications, identified by key AND post time. */
fun interface HandledState {
    fun isHandled(key: String, postTimeMs: Long, now: Instant): Boolean
}

/**
 * Bounded, thread-safe handled set. An entry is the pair (key, postTime): apps reuse one key for new content
 * (message threads), which has a new post time and is decided afresh, while a re-delivery of the SAME post
 * stays handled. Entries expire after [ttl]; beyond [capacity] the least recently used is forgotten.
 */
class HandledKeys(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttl: Duration = DEFAULT_TTL,
) : HandledState {
    private data class Id(val key: String, val postTimeMs: Long)

    private val marked = object : LinkedHashMap<Id, Instant>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Id, Instant>): Boolean = size > capacity
    }

    @Synchronized override fun isHandled(key: String, postTimeMs: Long, now: Instant): Boolean {
        val id = Id(key, postTimeMs)
        val at = marked[id] ?: return false
        if (now.isBefore(at.plus(ttl))) return true
        marked.remove(id)
        return false
    }

    @Synchronized fun mark(key: String, postTimeMs: Long, now: Instant) {
        marked[Id(key, postTimeMs)] = now
    }

    companion object {
        const val DEFAULT_CAPACITY = 512
        val DEFAULT_TTL: Duration = Duration.ofHours(6)
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}

/**
 * Pure policy. A post (key + post time) stays "handled" after a snooze, so when the OS re-posts the identical
 * notification after the snooze it is Keep (no snooze loop); new content under a reused key is decided again.
 */
object NotificationPolicy {
    fun decide(event: NotifEvent, rules: NotifRules, now: Instant, state: HandledState): NotifDecision {
        if (untouchable(event, rules) || state.isHandled(event.key, event.whenMs, now)) return NotifDecision.Keep
        val rule = rules.rules.firstOrNull { it.matches(event, now) } ?: return NotifDecision.Keep
        return when (val a = rule.action) {
            RuleAction.Allow -> NotifDecision.Keep
            is RuleAction.Snooze -> NotifDecision.Snooze(now.plus(Duration.ofMillis(a.durationMs)))
            RuleAction.Defer -> NotifDecision.Defer
            RuleAction.Cancel -> NotifDecision.Cancel
            is RuleAction.Reroute -> NotifDecision.Reroute(a.target)
        }
    }

    /** [decide] then remember the post when the decision acts on the notification. */
    fun decideAndRecord(event: NotifEvent, rules: NotifRules, now: Instant, state: HandledKeys): NotifDecision =
        decide(event, rules, now, state).also { if (it != NotifDecision.Keep) state.mark(event.key, event.whenMs, now) }

    /** For `onListenerConnected`: runs the active notifications through the policy; returns only the actionable ones. */
    fun sweep(events: List<NotifEvent>, rules: NotifRules, now: Instant, state: HandledKeys): List<Pair<NotifEvent, NotifDecision>> =
        events.map { it to decideAndRecord(it, rules, now, state) }.filter { it.second != NotifDecision.Keep }

    private fun untouchable(e: NotifEvent, rules: NotifRules): Boolean =
        e.hasMarker || e.pkg == rules.ownPackage || e.isOngoing || e.isForegroundService ||
            e.category in NotifCategory.PROTECTED || e.pkg in NotifRules.SYSTEM_PACKAGES || e.pkg in rules.allowlist
}

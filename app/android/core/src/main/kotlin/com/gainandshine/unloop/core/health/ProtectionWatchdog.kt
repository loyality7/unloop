package com.gainandshine.unloop.core.health

import com.gainandshine.unloop.core.clock.Clock

enum class FaultKind { ACCESSIBILITY_OFF, ACCESSIBILITY_CRASHED, USAGE_ACCESS_OFF, NOTIFICATION_ACCESS_OFF, BATTERY_RESTRICTED }

/** The independent things the watchdog tracks (strikes are per check). */
enum class Check { ACCESSIBILITY, USAGE_ACCESS, NOTIFICATION_ACCESS, BATTERY }

sealed interface Health {
    data object Healthy : Health
    data class Fault(val kind: FaultKind) : Health
}

/**
 * One observation. [intent]: for ACCESSIBILITY, "service enabled in settings"; for the others, "protection
 * needs this". [reality]: service bound/connected, or permission actually granted. [lastAliveMonoMs] is a
 * MONOTONIC stamp (Clock.monotonicMs) of the last heartbeat, or null.
 */
data class Probe(val check: Check, val intent: Boolean, val reality: Boolean, val lastAliveMonoMs: Long?)

/** [health] is the confirmed state; [alert] is true when the shell should post/refresh the notification now. */
data class Observation(val health: Health, val alert: Boolean)

/**
 * Pure policy with tiny state. A fault is confirmed only on the [strikes]th consecutive faulty observation of
 * a check, and alerts for a given kind repeat at most every [cooldownMs] (monotonic). Healthy resets everything
 * for that check. For ACCESSIBILITY, "enabled but not bound" is CRASHED unless the last heartbeat is within
 * [rebindGraceMs] (the system is rebinding).
 */
class ProtectionWatchdog(
    private val clock: Clock,
    private val strikes: Int = DEFAULT_STRIKES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val rebindGraceMs: Long = DEFAULT_REBIND_GRACE_MS,
) {
    private val strikeCount = mutableMapOf<Check, Int>()
    private val lastAlert = mutableMapOf<FaultKind, Long>()

    /** Single-shot classification without strikes or cooldown. */
    fun classify(p: Probe): FaultKind? {
        val now = clock.monotonicMs()
        return when (p.check) {
            Check.ACCESSIBILITY -> when {
                !p.intent -> FaultKind.ACCESSIBILITY_OFF
                p.reality -> null
                p.lastAliveMonoMs != null && now - p.lastAliveMonoMs < rebindGraceMs -> null
                else -> FaultKind.ACCESSIBILITY_CRASHED
            }
            Check.USAGE_ACCESS -> FaultKind.USAGE_ACCESS_OFF.takeIf { p.intent && !p.reality }
            Check.NOTIFICATION_ACCESS -> FaultKind.NOTIFICATION_ACCESS_OFF.takeIf { p.intent && !p.reality }
            Check.BATTERY -> FaultKind.BATTERY_RESTRICTED.takeIf { p.intent && !p.reality }
        }
    }

    /** Feed one probe; returns the confirmed health and whether to alert now. */
    fun observe(p: Probe): Observation {
        val kind = classify(p)
        if (kind == null) {
            strikeCount.remove(p.check)
            lastAlert.keys.removeAll { it.check() == p.check }
            return Observation(Health.Healthy, false)
        }
        val n = (strikeCount[p.check] ?: 0) + 1
        strikeCount[p.check] = n
        if (n < strikes) return Observation(Health.Healthy, false)
        val now = clock.monotonicMs()
        val last = lastAlert[kind]
        val alert = last == null || now - last >= cooldownMs
        if (alert) lastAlert[kind] = now
        return Observation(Health.Fault(kind), alert)
    }

    private fun FaultKind.check() = when (this) {
        FaultKind.ACCESSIBILITY_OFF, FaultKind.ACCESSIBILITY_CRASHED -> Check.ACCESSIBILITY
        FaultKind.USAGE_ACCESS_OFF -> Check.USAGE_ACCESS
        FaultKind.NOTIFICATION_ACCESS_OFF -> Check.NOTIFICATION_ACCESS
        FaultKind.BATTERY_RESTRICTED -> Check.BATTERY
    }

    companion object {
        const val DEFAULT_STRIKES = 2
        const val DEFAULT_COOLDOWN_MS = 12L * 60 * 60 * 1000
        const val DEFAULT_REBIND_GRACE_MS = 30_000L
    }
}

/** Posts only when the notification text changed since the last post; [clear] after cancelling. */
class ChangeGate {
    private var last: String? = null

    fun shouldPost(text: String): Boolean {
        if (text == last) return false
        last = text
        return true
    }

    fun clear() {
        last = null
    }
}

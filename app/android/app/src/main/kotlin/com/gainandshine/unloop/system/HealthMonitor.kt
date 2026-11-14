package com.gainandshine.unloop.system

import android.content.Context
import android.net.Uri
import android.view.accessibility.AccessibilityManager
import com.gainandshine.unloop.core.health.ChangeGate
import com.gainandshine.unloop.core.health.Check
import com.gainandshine.unloop.core.health.FaultKind
import com.gainandshine.unloop.core.health.Probe
import com.gainandshine.unloop.core.health.ProtectionWatchdog
import com.gainandshine.unloop.core.health.RepairTargets
import com.gainandshine.unloop.a11y.UnloopAccessibilityService
import com.gainandshine.unloop.notif.NotifRuleStore
import com.gainandshine.unloop.store.NativeSettings
import com.gainandshine.unloop.store.StoreProvider
import android.content.Intent

/**
 * Builds probes from live system state and runs them through the core watchdog.
 * ponytail: strikes live in process memory, so [HealthWorker] observes twice per run (a few seconds apart);
 * the 12 h alert cooldown is additionally persisted so a fresh process cannot re-alert early.
 */
object HealthMonitor {
    private val watchdog = ProtectionWatchdog(AndroidClock)
    private val gates = Check.entries.associateWith { ChangeGate() }
    private const val NOTIF_BASE = 4200

    fun probes(c: Context): List<Probe> {
        val hasLimits = StoreProvider.get(c).limits.all().isNotEmpty()
        val a11yIntent = Permissions.accessibilityEnabled(c) || listedAsEnabled(c)
        // Persisted wall-clock heartbeat so the rebind grace also holds in a fresh process (lastAliveElapsed is 0 there).
        val alive = maxOf(UnloopAccessibilityService.lastAliveElapsed, 0L).takeIf { it > 0 }
            ?: AliveStamp.toMono(NativeSettings.lastConnectedWall(c), System.currentTimeMillis(), AndroidClock.monotonicMs())
        // Check.BATTERY is deliberately not probed: it would nag every user by default. FaultKind.BATTERY_RESTRICTED stays dead.
        return listOf(
            Probe(Check.ACCESSIBILITY, a11yIntent, UnloopAccessibilityService.connected, alive),
            Probe(Check.USAGE_ACCESS, hasLimits, Permissions.usageAccess(c), null),
            Probe(Check.NOTIFICATION_ACCESS, NotifRuleStore.of(c).config().enabled, Permissions.notificationListenerEnabled(c), null),
        )
    }

    private fun listedAsEnabled(c: Context): Boolean = try {
        val am = c.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == c.packageName }
    } catch (e: Exception) {
        false
    }

    /** One watchdog pass: alert on confirmed faults (rate-limited), clear notifications for healthy checks. */
    @Synchronized fun run(c: Context) {
        val store = StoreProvider.get(c).settings
        for (p in probes(c)) {
            val o = watchdog.observe(p)
            val id = NOTIF_BASE + p.check.ordinal
            val fault = (o.health as? com.gainandshine.unloop.core.health.Health.Fault)?.kind
            if (fault == null) {
                if (watchdog.classify(p) == null) { Notifs.cancel(c, id); gates.getValue(p.check).clear() }
                continue
            }
            if (!o.alert) continue
            val key = "health_last_alert_${fault.name}"
            val last = store.get(key)?.toLongOrNull() ?: 0L
            val nowMs = AndroidClock.now().toEpochMilli()
            if (nowMs - last < ProtectionWatchdog.DEFAULT_COOLDOWN_MS && nowMs >= last) continue
            val text = textFor(fault)
            if (!Notifs.canPost(c)) continue // do not burn the cooldown/gate when nothing can be shown
            if (!gates.getValue(p.check).shouldPost(text)) continue
            store.put(key, nowMs.toString())
            Notifs.post(c, Notifs.CHANNEL_HEALTH, id, "Unloop needs attention", text, repairIntent(c, fault))
        }
    }

    /** Fixed strings only; never any user content. */
    private fun textFor(k: FaultKind) = when (k) {
        FaultKind.ACCESSIBILITY_OFF -> "The accessibility service is off. Tap to turn it on."
        FaultKind.ACCESSIBILITY_CRASHED -> "The accessibility service stopped. Tap to restart it."
        FaultKind.USAGE_ACCESS_OFF -> "Usage access is off, so limits cannot be tracked."
        FaultKind.NOTIFICATION_ACCESS_OFF -> "Notification access is off. Tap to turn it on."
        FaultKind.BATTERY_RESTRICTED -> "Battery restrictions may stop Unloop."
    }

    private fun repairIntent(c: Context, k: FaultKind): Intent {
        val i = Permissions.settingsIntent(RepairTargets.forKind(k).settingsActionName)
        if (k == FaultKind.BATTERY_RESTRICTED) i.data = Uri.parse("package:${c.packageName}")
        return i
    }

    /** Single-shot (no strikes) state for the UI. */
    fun status(c: Context): Map<String, Any?> {
        val faults = probes(c).mapNotNull { watchdog.classify(it)?.name }
        return mapOf(
            "healthy" to faults.isEmpty(),
            "faults" to faults,
            "accessibilityEnabled" to Permissions.accessibilityEnabled(c),
            "accessibilityConnected" to UnloopAccessibilityService.connected,
            "usageAccess" to Permissions.usageAccess(c),
            "notificationListener" to Permissions.notificationListenerEnabled(c),
            "notificationsAllowed" to Permissions.notificationsAllowed(c),
        )
    }
}

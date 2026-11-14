package com.gainandshine.unloop.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.gainandshine.unloop.core.store.BackupCodec
import com.gainandshine.unloop.core.store.Json
import com.gainandshine.unloop.a11y.UnloopAccessibilityService
import com.gainandshine.unloop.store.NativeSettings
import com.gainandshine.unloop.store.StoreProvider
import com.gainandshine.unloop.system.HealthMonitor
import com.gainandshine.unloop.system.HealthScheduler
import com.gainandshine.unloop.system.Permissions
import com.gainandshine.unloop.system.Targets
import com.gainandshine.unloop.usage.UsageStatsSource
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.MethodCall
import java.util.concurrent.Executors
import io.flutter.plugin.common.MethodChannel.Result

/** One concern per handler; [handle] returns false when the method is not its own. */
interface BridgeHandler {
    fun handle(call: MethodCall, r: Result): Boolean
}

const val NOTIF_PERMISSION_REQUEST = 7001

private fun MethodCall.str(k: String): String? = argument<String>(k)

/** Throws when strict mode is on: the action would loosen protection. Surfaces as error message `strict_mode`. */
internal fun denyIfStrict(c: Context) {
    if (NativeSettings.strictActive(c)) throw IllegalStateException("strict_mode")
}

/**
 * Bridge threading and error policy. [io] runs file/UsageStats work on ONE background thread and replies on the
 * main thread; [guarded] wraps cheap main-thread handlers. Nothing ever escapes into Flutter: every Throwable
 * (IOException included) becomes `result.error(code, message)`.
 */
object BridgeIo {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "unloop-bridge").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun io(r: Result, code: String, work: () -> Any?) {
        exec.execute {
            val out = try { kotlin.Result.success(work()) } catch (t: Throwable) { kotlin.Result.failure<Any?>(t) }
            main.post {
                try {
                    out.fold({ r.success(it) }, { r.error(errCode(code, it), it.message, null) })
                } catch (_: Throwable) { /* reply already sent or engine gone */ }
            }
        }
    }

    fun guarded(r: Result, code: String, body: () -> Boolean): Boolean = try {
        body()
    } catch (t: Throwable) {
        try { r.error(errCode(code, t), t.message, null) } catch (_: Throwable) {}
        true
    }

    private fun errCode(code: String, t: Throwable) = if (t is RuntimeException && t !is IllegalStateException) "bad_input" else code
}

class PermissionsHandler(private val activity: Activity) : BridgeHandler {
    private var pendingNotif: Result? = null

    /** Called from MainActivity.onRequestPermissionsResult: resolves with the real answer after the dialog. */
    fun onPermissionResult(requestCode: Int) {
        if (requestCode != NOTIF_PERMISSION_REQUEST) return
        pendingNotif?.let { runCatching { it.success(Permissions.notificationsAllowed(activity)) } }
        pendingNotif = null
    }

    override fun handle(call: MethodCall, r: Result): Boolean = BridgeIo.guarded(r, "permissions_failed") { handle0(call, r) }

    private fun handle0(call: MethodCall, r: Result): Boolean {
        val c: Context = activity
        when (call.method) {
            "permissionState" -> r.success(
                mapOf(
                    "accessibilityEnabled" to Permissions.accessibilityEnabled(c),
                    "accessibilityConnected" to UnloopAccessibilityService.connected,
                    "usageAccess" to Permissions.usageAccess(c),
                ),
            )
            "openAccessibilitySettings" -> open(Settings.ACTION_ACCESSIBILITY_SETTINGS, r)
            "openUsageAccessSettings" -> open(Settings.ACTION_USAGE_ACCESS_SETTINGS, r)
            "openNotificationAccessSettings" -> open("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", r)
            "requestNotificationPermission" -> {
                if (Build.VERSION.SDK_INT >= 33 && !Permissions.notificationsAllowed(c)) {
                    pendingNotif?.let { old -> runCatching { old.success(false) } } // superseded request
                    pendingNotif = r
                    activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERMISSION_REQUEST)
                } else {
                    r.success(Permissions.notificationsAllowed(c))
                }
            }
            "healthStatus" -> r.success(Json.write(HealthMonitor.status(c)))
            "diagnostics" -> r.success(Json.write(UnloopAccessibilityService.diagnosticsSnapshot().mapValues { it.value.toLong() }))
            else -> return false
        }
        return true
    }

    private fun open(action: String, r: Result) {
        try {
            activity.startActivity(Intent(action))
        } catch (e: Exception) { // fall back to our app-details screen
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }
        r.success(null)
    }
}

class SettingsHandler(private val c: Context) : BridgeHandler {
    override fun handle(call: MethodCall, r: Result): Boolean = BridgeIo.guarded(r, "settings_failed") { handle0(call, r) }

    private fun handle0(call: MethodCall, r: Result): Boolean {
        when (call.method) {
            "getSurfaceEnabled" -> r.success(NativeSettings.surfaceEnabled(c, call.str("id") ?: ""))
            "setSurfaceEnabled" -> {
                val on = call.argument<Boolean>("on") ?: false
                if (!on) denyIfStrict(c)
                NativeSettings.setSurfaceEnabled(c, call.str("id") ?: "", on)
                r.success(null)
            }
            "getFlag" -> r.success(NativeSettings.flag(c, call.str("k") ?: ""))
            "setFlag" -> {
                val k = call.str("k") ?: ""
                val v = call.argument<Boolean>("v") ?: false
                if (k == com.gainandshine.unloop.block.BlockedActivity.FLAG_ALLOW_TEMP && v) denyIfStrict(c)
                NativeSettings.setFlag(c, k, v)
                r.success(null)
            }
            "getInt" -> r.success(NativeSettings.int(c, call.str("k") ?: "", call.argument<Int>("d") ?: 0))
            "setInt" -> { NativeSettings.setInt(c, call.str("k") ?: "", call.argument<Int>("v") ?: 0); r.success(null) }
            "blockCount" -> r.success(NativeSettings.blockCount(c))
            "getMasterEnabled" -> r.success(NativeSettings.masterEnabled(c))
            "setMasterEnabled" -> {
                val on = call.argument<Boolean>("on") ?: true
                if (!on) denyIfStrict(c)
                NativeSettings.setMasterEnabled(c, on)
                r.success(null)
            }
            "listSurfaces" -> r.success(NativeSettings.allSurfaces)
            "pauseFor" -> {
                denyIfStrict(c)
                NativeSettings.pauseFor(c, call.argument<Int>("minutes") ?: com.gainandshine.unloop.system.PausePolicy.DEFAULT_MINUTES)
                com.gainandshine.unloop.a11y.UnloopAccessibilityService.settingsChanged()
                r.success(null)
            }
            "resumeProtection" -> {
                NativeSettings.resume(c)
                com.gainandshine.unloop.a11y.UnloopAccessibilityService.settingsChanged()
                r.success(null)
            }
            "isPaused" -> r.success(NativeSettings.isPaused(c))
            "pausedUntil" -> r.success(NativeSettings.pausedUntilWall(c))
            else -> return false
        }
        return true
    }
}

class LimitsHandler(private val c: Context) : BridgeHandler {
    private val store = StoreProvider.get(c)

    /**
     * Scheduled apps: package names that schedules (quiet hours) and the manual block apply to even without a
     * limit rule. The effective enforcement set is limit-rule packages UNION this list; quota only applies to rules.
     */
    override fun handle(call: MethodCall, r: Result): Boolean {
        val m = call.method
        if (m !in METHODS) return false
        val json = call.str("json") ?: ""
        BridgeIo.io(r, "limits_failed") {
            when (m) {
                "getLimits" -> LimitsCodec.limitsToJson(store.limits.all())
                "setLimits" -> {
                    val next = LimitsCodec.parseLimits(json)
                    if (NativeSettings.strictActive(c) && !com.gainandshine.unloop.core.limits.StrictPolicy.limitsAllowed(store.limits.all(), next)) throw IllegalStateException("strict_mode")
                    store.limits.all().filter { o -> next.none { it.target == o.target } }.forEach { store.limits.remove(it.target) }
                    next.forEach(store.limits::upsert)
                    UnloopAccessibilityService.recheckNow()
                    next.size
                }
                "getSchedules" -> LimitsCodec.schedulesToJson(store.schedules.all())
                "setSchedules" -> {
                    val next = LimitsCodec.parseSchedules(json)
                    if (NativeSettings.strictActive(c) && !com.gainandshine.unloop.core.limits.StrictPolicy.schedulesAllowed(store.schedules.all(), next)) throw IllegalStateException("strict_mode")
                    store.schedules.all().filter { o -> next.none { it.id == o.id } }.forEach { store.schedules.remove(it.id) }
                    next.forEach(store.schedules::upsert)
                    HealthScheduler.planBoundary(c)
                    UnloopAccessibilityService.recheckNow()
                    next.size
                }
                "getScheduledApps" -> Targets.parseScheduled(store.settings.get(Targets.KEY_SCHEDULED)).toList()
                "setScheduledApps" -> {
                    val next = Targets.parseScheduled(json)
                    if (NativeSettings.strictActive(c) && !com.gainandshine.unloop.core.limits.StrictPolicy.scheduledAppsAllowed(Targets.parseScheduled(store.settings.get(Targets.KEY_SCHEDULED)), next)) throw IllegalStateException("strict_mode")
                    store.settings.put(Targets.KEY_SCHEDULED, Targets.encodeScheduled(next))
                    UnloopAccessibilityService.recheckNow()
                    next.size
                }
                "usageToday" -> {
                    val src = UsageStatsSource(c)
                    src.refresh()
                    src.todaySeconds()
                }
                "exportBackup" -> BackupCodec.export(store.schedules, store.limits, store.settings)
                else -> { // importBackup
                    denyIfStrict(c)
                    val res = BackupCodec.import(json, store.schedules, store.limits, store.settings)
                    if (res.ok) { HealthScheduler.planBoundary(c); UnloopAccessibilityService.recheckNow() }
                    mapOf(
                        "ok" to res.ok, "schedules" to res.schedules, "limits" to res.limits,
                        "settings" to res.settings, "skipped" to res.skipped, "error" to res.error,
                    )
                }
            }
        }
        return true
    }

    private companion object {
        val METHODS = setOf(
            "getLimits", "setLimits", "getSchedules", "setSchedules", "getScheduledApps", "setScheduledApps",
            "usageToday", "exportBackup", "importBackup",
        )
    }
}

package com.gainandshine.unloop.bridge

import android.content.Context
import android.content.Intent
import com.gainandshine.unloop.a11y.UnloopAccessibilityService
import com.gainandshine.unloop.notif.NotifConfig
import com.gainandshine.unloop.notif.NotifRuleStore
import com.gainandshine.unloop.store.NativeSettings
import com.gainandshine.unloop.store.PrefsBudgets
import com.gainandshine.unloop.core.limits.DayKey
import com.gainandshine.unloop.store.StoreProvider
import com.gainandshine.unloop.system.AndroidClock
import com.gainandshine.unloop.system.HealthScheduler
import com.gainandshine.unloop.system.Notifs
import com.gainandshine.unloop.system.Permissions
import com.gainandshine.unloop.system.PrivacyGuard
import com.gainandshine.unloop.usage.UsageStatsSource
import java.time.temporal.ChronoUnit
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result

/** Installed launcher apps, for the limit / schedule pickers. Payment and banking apps are never offered. */
class AppsHandler(private val c: Context) : BridgeHandler {
    override fun handle(call: MethodCall, r: Result): Boolean {
        if (call.method == "appIcon") {
            val pkg = call.argument<String>("pkg") ?: ""
            val px = (call.argument<Int>("px") ?: 96).coerceIn(32, 192)
            BridgeIo.io(r, "apps_failed") { if (PrivacyGuard.isNeverWatched(pkg)) null else iconPng(pkg, px) }
            return true
        }
        if (call.method != "installedApps") return false
        BridgeIo.io(r, "apps_failed") {
            val pm = c.packageManager
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launch, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { (pkg, _) -> pkg != c.packageName && !PrivacyGuard.isNeverWatched(pkg) }
                .sortedBy { it.second.lowercase() }
                .map { (pkg, label) -> mapOf("pkg" to pkg, "label" to label) }
        }
        return true
    }

    /** The app's launcher icon as a square PNG. Throws (becomes an error the UI treats as "no icon") when not installed. */
    private fun iconPng(pkg: String, px: Int): ByteArray {
        val d = c.packageManager.getApplicationIcon(pkg)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            d.setBounds(0, 0, px, px)
            d.draw(android.graphics.Canvas(bmp))
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }
}

/** Notification filter settings and the shared-item allowance. */
class PrefsHandler(private val c: Context) : BridgeHandler {
    private val notif = NotifRuleStore.of(c)

    override fun handle(call: MethodCall, r: Result): Boolean = BridgeIo.guarded(r, "prefs_failed") {
        when (call.method) {
            "getNotifConfig" -> BridgeIo.io(r, "prefs_failed") { notif.config().toJson() }
            "setNotifConfig" -> {
                val json = call.argument<String>("json") ?: ""
                BridgeIo.io(r, "prefs_failed") { notif.save(NotifConfig.fromJson(json)); null }
            }
            "getCounter" -> r.success(mapOf("on" to NativeSettings.counterOn(c), "kick" to NativeSettings.autoKickAfter(c)))
            "setCounter" -> {
                NativeSettings.setCounterOn(c, call.argument<Boolean>("on") ?: true)
                UnloopAccessibilityService.settingsChanged()
                r.success(null)
            }
            "setAutoKick" -> {
                val n = (call.argument<Int>("n") ?: 0).coerceIn(0, 500)
                val old = NativeSettings.autoKickAfter(c)
                // Switching it off, or raising the number, lets you scroll more: strict mode refuses.
                if (old > 0 && (n == 0 || n > old)) denyIfStrict(c)
                NativeSettings.setAutoKickAfter(c, n)
                UnloopAccessibilityService.settingsChanged()
                r.success(null)
            }
            "getPayment" -> BridgeIo.io(r, "prefs_failed") {
                mapOf(
                    "available" to com.gainandshine.unloop.system.PaymentAside.available,
                    "strictBounce" to com.gainandshine.unloop.BuildConfig.STRICT_BOUNCE,
                    "granted" to com.gainandshine.unloop.system.PaymentAside.canWrite(c),
                    "usage" to Permissions.usageAccess(c),
                    "on" to NativeSettings.paymentAside(c),
                    "pkg" to c.packageName,
                    "asideSince" to NativeSettings.asideSince(c),
                    "apps" to com.gainandshine.unloop.system.PaymentAside.installedPaymentApps(c).map { mapOf("pkg" to it.first, "label" to it.second) },
                )
            }
            "setPayment" -> {
                val on = call.argument<Boolean>("on") ?: false
                if (on && !(com.gainandshine.unloop.system.PaymentAside.canWrite(c) && Permissions.usageAccess(c))) {
                    r.error("needs_setup", "Finish the two setup steps first.", null)
                } else {
                    NativeSettings.setPaymentAside(c, on)
                    UnloopAccessibilityService.settingsChanged()
                    r.success(null)
                }
            }
            "restoreAside" -> BridgeIo.io(r, "prefs_failed") { com.gainandshine.unloop.system.PaymentAside.restore(c) }
            "getAllowShared" -> r.success(NativeSettings.allowSharedItem(c))
            "setAllowShared" -> {
                NativeSettings.setAllowSharedItem(c, call.argument<Boolean>("on") ?: true)
                UnloopAccessibilityService.settingsChanged()
                r.success(null)
            }
            else -> return@guarded false
        }
        true
    }
}

/** Local history for Insights and the "delete everything" action. */
class DataHandler(private val c: Context) : BridgeHandler {
    override fun handle(call: MethodCall, r: Result): Boolean {
        when (call.method) {
            "usageHistory" -> {
                val days = (call.argument<Int>("days") ?: 7).coerceIn(1, 30)
                BridgeIo.io(r, "data_failed") {
                    UsageStatsSource(c).refresh()
                    val store = StoreProvider.get(c)
                    val now = AndroidClock.now()
                    val zone = AndroidClock.zone()
                    (days - 1 downTo 0).map { back ->
                        val at = now.minus(back.toLong(), ChronoUnit.DAYS)
                        val key = DayKey.of(at, zone)
                        mapOf(
                            "date" to key.date.toString(),
                            "seconds" to store.usage.totals(key).values.sum() / 1000,
                            "blocks" to NativeSettings.blocksOn(c, key.date.toString()),
                        )
                    }
                }
            }
            "wipeAll" -> BridgeIo.io(r, "data_failed") {
                denyIfStrict(c)
                val store = StoreProvider.get(c)
                store.limits.all().forEach { store.limits.remove(it.target) }
                store.schedules.all().forEach { store.schedules.remove(it.id) }
                store.settings.all().keys.forEach(store.settings::remove)
                store.usage.clear()
                java.io.File(c.applicationContext.filesDir, "notif_rules.json").delete()
                NativeSettings.wipe(c)
                PrefsBudgets(c).clear()
                com.gainandshine.unloop.learn.LearnStore(c).clear()
                com.gainandshine.unloop.learn.LearnNotifier.hide(c)
                HealthScheduler.planBoundary(c)
                UnloopAccessibilityService.settingsChanged()
                UnloopAccessibilityService.recheckNow()
                null
            }
            "getBudgets" -> {
                // surface -> [allowance minutes, used seconds today]
                val b = PrefsBudgets(c)
                r.success(NativeSettings.allSurfaces.associateWith { listOf(b.allowanceMinutes(it), (b.usedMs(it) / 1000).toInt()) })
            }
            "setBudget" -> {
                val surface = call.argument<String>("surface") ?: ""
                val minutes = (call.argument<Int>("minutes") ?: 0).coerceIn(0, PrefsBudgets.MAX_MINUTES)
                BridgeIo.io(r, "data_failed") {
                    require(surface in NativeSettings.allSurfaces) { "unknown surface" }
                    // A bigger allowance means more scrolling, so strict mode refuses it. Smaller is always fine.
                    if (minutes > PrefsBudgets(c).allowanceMinutes(surface)) denyIfStrict(c)
                    PrefsBudgets(c).setAllowanceMinutes(surface, minutes)
                    UnloopAccessibilityService.settingsChanged()
                    null
                }
            }
            "getLearn" -> {
                val store = com.gainandshine.unloop.learn.LearnStore(c)
                r.success(mapOf("pkg" to NativeSettings.learnPackage(c), "count" to store.list().size, "canPost" to Notifs.canPost(c)))
            }
            "setLearn" -> {
                val pkg = call.argument<String>("pkg") ?: ""
                if (pkg.isNotEmpty() && PrivacyGuard.isNeverWatched(pkg)) {
                    r.error("protected", "Payment and banking apps are never read.", null)
                } else if (pkg.isNotEmpty() && !com.gainandshine.unloop.learn.LearnNotifier.show(c, "Open a screen, then tap Capture.")) {
                    r.error("notifications_off", "Allow notifications so the Capture button can appear.", null)
                } else {
                    NativeSettings.setLearnPackage(c, pkg)
                    if (pkg.isEmpty()) com.gainandshine.unloop.learn.LearnNotifier.hide(c)
                    UnloopAccessibilityService.settingsChanged()
                    r.success(null)
                }
            }
            "listCaptures" -> BridgeIo.io(r, "data_failed") {
                com.gainandshine.unloop.learn.LearnStore(c).list().map { mapOf("name" to it.name, "pkg" to it.pkg, "at" to it.atMs, "bytes" to it.bytes) }
            }
            "exportCaptures" -> BridgeIo.io(r, "data_failed") { com.gainandshine.unloop.learn.LearnStore(c).exportAll() }
            "deleteCaptures" -> BridgeIo.io(r, "data_failed") { com.gainandshine.unloop.learn.LearnStore(c).clear(); null }
            "getGroups" -> BridgeIo.io(r, "data_failed") { StoreProvider.get(c).settings.get(com.gainandshine.unloop.system.Targets.KEY_GROUPS) ?: "[]" }
            "setGroups" -> {
                val json = call.argument<String>("json") ?: "[]"
                BridgeIo.io(r, "data_failed") {
                    val settings = StoreProvider.get(c).settings
                    val next = com.gainandshine.unloop.core.limits.AppGroups.parse(json)
                    val old = com.gainandshine.unloop.core.limits.AppGroups.parse(settings.get(com.gainandshine.unloop.system.Targets.KEY_GROUPS))
                    // Removing a group, raising its limit or dropping a member loosens protection: strict mode refuses.
                    if (NativeSettings.strictActive(c) && !com.gainandshine.unloop.core.limits.StrictPolicy.groupsAllowed(old, next)) throw IllegalStateException("strict_mode")
                    settings.put(com.gainandshine.unloop.system.Targets.KEY_GROUPS, com.gainandshine.unloop.core.limits.AppGroups.write(next))
                    UnloopAccessibilityService.recheckNow()
                    next.size
                }
            }
            "getStrictUntil" -> r.success(NativeSettings.strictUntil(c))
            "setStrict" -> BridgeIo.io(r, "data_failed") {
                // Turning strict mode on, or extending it, is always allowed. Ending it early never is.
                val minutes = call.argument<Int>("minutes") ?: 0
                val now = System.currentTimeMillis()
                if (minutes <= 0) {
                    denyIfStrict(c)
                    NativeSettings.setStrictUntil(c, 0L)
                } else {
                    val until = now + minutes.coerceAtMost(7 * 24 * 60) * 60_000L
                    NativeSettings.setStrictUntil(c, maxOf(until, NativeSettings.strictUntil(c)))
                }
                UnloopAccessibilityService.settingsChanged() // widens the listening scope to Settings while strict
                NativeSettings.strictUntil(c)
            }
            else -> return false
        }
        return true
    }

}

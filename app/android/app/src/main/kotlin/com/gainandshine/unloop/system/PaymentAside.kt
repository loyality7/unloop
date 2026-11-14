package com.gainandshine.unloop.system

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gainandshine.unloop.core.health.AccessibilityList
import com.gainandshine.unloop.core.health.AsideTracker
import com.gainandshine.unloop.a11y.UnloopAccessibilityService
import com.gainandshine.unloop.store.NativeSettings

/**
 * Payment apps (UPI, wallets, banks) refuse to work or warn while any accessibility service is on. With the user's
 * opt-in, Unloop genuinely switches its own service off when such an app comes to the front and back on after the
 * user leaves it. That needs WRITE_SECURE_SETTINGS, which Android only lets the user grant with a one-time adb
 * command. Nothing is hidden: while a payment app is open there really is no accessibility service from Unloop.
 *
 * The service cannot watch for the user leaving once it is off, so a short-lived foreground service
 * ([PaymentGuardService]) polls usage events, and a plain alarm is the safety net that switches it back on regardless.
 */
object PaymentAside {
    private const val SAFETY_MS = 5 * 60_000L
    private const val STUCK_MS = 20_000L
    private const val ALARM_CODE = 4301

    /** Whether this build contains the feature at all (the Google Play build does not). */
    val available: Boolean get() = com.gainandshine.unloop.BuildConfig.STEP_ASIDE

    fun canWrite(c: Context): Boolean = available &&
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun component(c: Context) = ComponentName(c, UnloopAccessibilityService::class.java)
    private fun names(c: Context): Set<String> = component(c).let { setOf(it.flattenToString(), it.flattenToShortString()) }

    /** Payment, wallet and bank apps installed on this phone (the apps Unloop never reads). */
    fun installedPaymentApps(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launch, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { (pkg, _) -> pkg != c.packageName && PrivacyGuard.isNeverWatched(pkg) }
            .sortedBy { it.second.lowercase() }
    }

    /** Switch our accessibility service off. Called from the service itself when a payment app opens. */
    @Synchronized
    fun stepAside(c: Context): Boolean {
        if (!canWrite(c) || NativeSettings.asideSince(c) != 0L) return false
        NativeSettings.setAsideSince(c, System.currentTimeMillis())
        scheduleSafety(c)
        try {
            ContextCompat.startForegroundService(c, Intent(c, PaymentGuardService::class.java))
        } catch (_: Exception) {
            // not allowed to start it from here: the safety alarm brings the service back
        }
        val ok = write(c) { cur -> AccessibilityList.without(cur, names(c)) }
        if (!ok) {
            NativeSettings.setAsideSince(c, 0L)
            cancelSafety(c)
            c.stopService(Intent(c, PaymentGuardService::class.java))
        }
        return ok
    }

    /** Switch our service back on, but only if WE switched it off. Safe to call at any time. */
    @Synchronized
    fun restore(c: Context): Boolean {
        if (NativeSettings.asideSince(c) == 0L) return false
        val ok = write(c) { cur -> AccessibilityList.with(cur, names(c), component(c).flattenToString()) }
        if (ok) {
            NativeSettings.setAsideSince(c, 0L)
            cancelSafety(c)
        }
        return ok
    }

    /** Called on boot and app start: a step-aside whose watcher died must not leave the service off. */
    fun restoreIfStuck(c: Context) {
        val since = NativeSettings.asideSince(c)
        if (since != 0L && System.currentTimeMillis() - since > STUCK_MS) restore(c)
    }

    private fun write(c: Context, edit: (String?) -> String): Boolean = try {
        val cr = c.contentResolver
        val next = edit(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (next.isBlank()) 0 else 1)
        true
    } catch (_: SecurityException) {
        false
    }

    private fun safetyIntent(c: Context) = PendingIntent.getBroadcast(
        c, ALARM_CODE, Intent(c, PaymentRestoreReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleSafety(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + SAFETY_MS, safetyIntent(c))
    }

    fun cancelSafety(c: Context) {
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(safetyIntent(c))
    }
}

/** Fires the safety alarm: switch the accessibility service back on whatever else happened. */
class PaymentRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PaymentAside.restore(context)
    }
}

/**
 * Runs only while Unloop is stepped aside. Watches which app is in front through usage events and switches the
 * accessibility service back on when the user has left every payment app, when the screen turns off, or after at
 * most fifteen minutes.
 */
class PaymentGuardService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var tracker: AsideTracker? = null
    private var screenOff: BroadcastReceiver? = null

    private val tick = object : Runnable {
        override fun run() {
            val t = tracker ?: return
            if (t.onForeground(foregroundPackage(), SystemClock.elapsedRealtime())) finish() else main.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // The foreground service type is declared in the `full` manifest. The `play` build removes this service and never starts it.
    @android.annotation.SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (_: Exception) {
            // cannot run as a foreground service: the safety alarm restores the accessibility service
            stopSelf()
            return START_NOT_STICKY
        }
        tracker = AsideTracker({ PrivacyGuard.isNeverWatched(it) }, SystemClock.elapsedRealtime())
        if (screenOff == null) {
            screenOff = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = finish()
            }.also { ContextCompat.registerReceiver(this, it, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED) }
        }
        main.removeCallbacks(tick)
        main.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        try { screenOff?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        screenOff = null
        PaymentAside.restore(this) // a watcher that dies must never leave the service off
        super.onDestroy()
    }

    private fun finish() {
        main.removeCallbacks(tick)
        PaymentAside.restore(this)
        stopSelf()
    }

    /** The app that most recently came to the front in the last ten seconds, or null when there was no change. */
    private fun foregroundPackage(): String? = try {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - WINDOW_MS, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
        }
        last
    } catch (_: SecurityException) {
        null
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Payment apps", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.gainandshine.unloop.R.drawable.ic_stat_unloop)
            .setContentTitle("Unloop is off while you pay")
            .setContentText("It turns back on when you leave the payment app.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "unloop_payment"
        const val NOTIF_ID = 4202
        const val POLL_MS = 600L
        const val WINDOW_MS = 10_000L
    }
}

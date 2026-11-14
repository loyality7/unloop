package com.gainandshine.unloop.system

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.gainandshine.unloop.a11y.UnloopAccessibilityService

/** Cheap system-state probes shared by the bridge and the health worker. */
object Permissions {
    fun usageAccess(c: Context): Boolean = try {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    private fun component(c: Context) = ComponentName(c, UnloopAccessibilityService::class.java).flattenToString()

    /** User intent: our service is listed in the enabled accessibility services setting. */
    fun accessibilityEnabled(c: Context): Boolean = try {
        val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val me = component(c)
        s.split(':').any { it.equals(me, ignoreCase = true) } || s.contains(me, ignoreCase = true)
    } catch (e: Exception) {
        false
    }

    fun notificationListenerEnabled(c: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    fun notificationsAllowed(c: Context): Boolean = NotificationManagerCompat.from(c).areNotificationsEnabled()

    fun settingsIntent(action: String): Intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

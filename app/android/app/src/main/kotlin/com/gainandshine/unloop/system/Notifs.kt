package com.gainandshine.unloop.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Posting helpers. Texts passed in must be fixed strings: never user content, never package names. */
object Notifs {
    const val CHANNEL_HEALTH = "unloop_health"
    const val CHANNEL_LIMITS = "unloop_limits"

    private fun ensure(c: Context, id: String, name: String, importance: Int) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    /** notify() does not throw without POST_NOTIFICATIONS, so check first and never record state when false. */
    fun canPost(c: Context): Boolean = NotificationManagerCompat.from(c).areNotificationsEnabled()

    fun post(c: Context, channel: String, notifId: Int, title: String, text: String, tap: Intent?): Boolean {
        if (!canPost(c)) return false
        when (channel) {
            CHANNEL_HEALTH -> ensure(c, channel, "Protection status", NotificationManager.IMPORTANCE_DEFAULT)
            else -> ensure(c, channel, "Limits", NotificationManager.IMPORTANCE_LOW)
        }
        val b = NotificationCompat.Builder(c, channel)
            .setSmallIcon(com.gainandshine.unloop.R.drawable.ic_stat_unloop)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(
                if (channel == CHANNEL_HEALTH) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW,
            )
        if (tap != null) {
            b.setContentIntent(PendingIntent.getActivity(c, notifId, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        try {
            NotificationManagerCompat.from(c).notify(notifId, b.build())
            return true
        } catch (e: SecurityException) {
            return false
        }
    }

    fun cancel(c: Context, notifId: Int) = NotificationManagerCompat.from(c).cancel(notifId)
}

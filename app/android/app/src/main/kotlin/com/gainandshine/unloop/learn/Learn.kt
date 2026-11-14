package com.gainandshine.unloop.learn

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.gainandshine.unloop.core.fixture.Fixture
import com.gainandshine.unloop.core.fixture.FixtureCodec
import com.gainandshine.unloop.core.fixture.FixtureItem
import com.gainandshine.unloop.core.model.UiSnapshot
import com.gainandshine.unloop.a11y.UnloopAccessibilityService
import com.gainandshine.unloop.store.NativeSettings
import com.gainandshine.unloop.system.Notifs
import java.io.File

/** One saved screen capture. [name] is the file name: `<epochMs>_<package>.jsonl`. */
data class CaptureInfo(val name: String, val pkg: String, val atMs: Long, val bytes: Long)

/**
 * Learn mode storage. A capture is a one-snapshot fixture written with [FixtureCodec], which drops every node's text
 * and content description: only view ids, class names, bounds and flags are kept. Files live in app-private storage
 * and leave the phone only if the user copies them out. Capped at [MAX] files, oldest dropped first.
 */
class LearnStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "captures").apply { mkdirs() }

    /** [allowText] is the user's explicit opt-in to keep on-screen text (needed to write label-based rules). */
    fun add(snap: UiSnapshot, atEpochMs: Long, allowText: Boolean = false): CaptureInfo {
        val fixture = Fixture(app = snap.pkg, oracle = listOf("none"), items = listOf(FixtureItem.Snap(snap)))
        val text = FixtureCodec.encode(fixture, allowText = allowText)
        val f = File(dir, "${atEpochMs}_${snap.pkg}.jsonl")
        f.writeText(text)
        prune()
        return CaptureInfo(f.name, snap.pkg, atEpochMs, f.length())
    }

    fun list(): List<CaptureInfo> = dir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().mapNotNull { f ->
        val at = f.name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
        CaptureInfo(f.name, f.name.substringAfter('_').removeSuffix(".jsonl"), at, f.length())
    }.sortedByDescending { it.atMs }

    /** All captures as one text block, newest first, separated by a blank line. */
    fun exportAll(): String = list().joinToString("\n") { File(dir, it.name).readText() }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun prune() = list().drop(MAX).forEach { File(dir, it.name).delete() }

    companion object {
        const val MAX = 30
    }
}

/** Ongoing notification with "Capture screen" and "Stop" while Learn mode is on. */
object LearnNotifier {
    const val CHANNEL = "unloop_learn"
    const val NOTIF_ID = 4201
    const val ACTION_CAPTURE = "com.gainandshine.unloop.LEARN_CAPTURE"
    const val ACTION_STOP = "com.gainandshine.unloop.LEARN_STOP"

    /** @return false when notifications are not allowed (Learn mode needs them to be usable). */
    fun show(c: Context, text: String): Boolean {
        if (!Notifs.canPost(c)) return false
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Learn mode", NotificationManager.IMPORTANCE_LOW))
        }
        fun action(a: String) = PendingIntent.getBroadcast(
            c, a.hashCode(), Intent(a).setPackage(c.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(com.gainandshine.unloop.R.drawable.ic_stat_unloop)
            .setContentTitle("Unloop learn mode")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Capture screen", action(ACTION_CAPTURE))
            .addAction(0, "Stop", action(ACTION_STOP))
            .build()
        return try {
            androidx.core.app.NotificationManagerCompat.from(c).notify(NOTIF_ID, n)
            true
        } catch (_: SecurityException) { false }
    }

    fun hide(c: Context) = Notifs.cancel(c, NOTIF_ID)
}

class LearnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LearnNotifier.ACTION_CAPTURE -> UnloopAccessibilityService.captureNow()
            LearnNotifier.ACTION_STOP -> {
                NativeSettings.setLearnPackage(context, "")
                LearnNotifier.hide(context)
                UnloopAccessibilityService.settingsChanged()
            }
        }
    }
}

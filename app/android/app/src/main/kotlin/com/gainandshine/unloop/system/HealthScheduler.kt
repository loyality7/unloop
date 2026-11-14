package com.gainandshine.unloop.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gainandshine.unloop.core.time.Schedule
import com.gainandshine.unloop.core.time.ScheduleEvaluator
import com.gainandshine.unloop.store.StoreProvider
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Owns the periodic health job and the next-schedule-boundary alarm. */
object HealthScheduler {
    private const val WORK = "unloop_health"
    const val ACTION_BOUNDARY = "com.gainandshine.unloop.SCHEDULE_BOUNDARY"

    /** Pure: when the alarm should fire. Null when no enabled schedule has a boundary within a week. */
    fun nextTrigger(schedules: List<Schedule>, now: ZonedDateTime): Instant? = ScheduleEvaluator.nextBoundary(schedules, now)

    /** Idempotent (KEEP): safe to call from every entry point. */
    fun enqueue(c: Context) {
        val req = PeriodicWorkRequestBuilder<HealthWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(c.applicationContext).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Inexact, idle-friendly alarm at the next boundary; re-planned by [BootReceiver] when it fires. */
    fun planBoundary(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            c, 0, Intent(c, BootReceiver::class.java).setAction(ACTION_BOUNDARY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = nextTrigger(StoreProvider.get(c).schedules.all(), AndroidClock.now().atZone(AndroidClock.zone()))
        if (next == null) am.cancel(pi) else am.setAndAllowWhileIdle(AlarmManager.RTC, next.toEpochMilli(), pi)
    }
}

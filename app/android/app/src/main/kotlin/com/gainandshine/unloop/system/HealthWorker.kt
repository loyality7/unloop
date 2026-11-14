package com.gainandshine.unloop.system

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Periodic watchdog pass. Two observations a few seconds apart satisfy the two-strike rule inside one run. */
class HealthWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        return try {
            HealthMonitor.run(applicationContext)
            Thread.sleep(SECOND_LOOK_MS)
            HealthMonitor.run(applicationContext)
            HealthScheduler.planBoundary(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.success() // never retry-storm a best-effort check
        }
    }

    private companion object { const val SECOND_LOOK_MS = 35_000L } // > 30 s rebind grace, so a slow OEM rebind is not flagged
}

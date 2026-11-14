package com.gainandshine.unloop.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gainandshine.unloop.a11y.UnloopAccessibilityService

/** Re-arms health checks and the schedule boundary after boot, upgrade, clock/zone change or the boundary alarm. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        runCatching {
            HealthScheduler.enqueue(context)
            HealthScheduler.planBoundary(context)
        }
        PaymentAside.restoreIfStuck(context)
        // A schedule may have just started or ended: re-check the foreground app now (no-op if not connected).
        UnloopAccessibilityService.recheckNow()
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED, HealthScheduler.ACTION_BOUNDARY,
        )
    }
}

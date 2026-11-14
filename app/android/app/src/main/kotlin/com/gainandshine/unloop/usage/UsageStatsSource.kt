package com.gainandshine.unloop.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.gainandshine.unloop.core.usage.SessionStitcher
import com.gainandshine.unloop.core.usage.UsageAccumulator
import com.gainandshine.unloop.core.usage.UsageEvent
import com.gainandshine.unloop.block.NeverBlock
import com.gainandshine.unloop.store.StoreProvider
import com.gainandshine.unloop.system.AndroidClock
import com.gainandshine.unloop.system.Permissions
import com.gainandshine.unloop.system.ProtectedPackages

/**
 * Pulls UsageStatsManager events since the persisted high-water mark and bills them through the core
 * accumulator. Without usage access it reports false and does nothing.
 */
class UsageStatsSource(private val context: Context) {
    private val store = StoreProvider.get(context)

    /** @return true when usage access is granted and the refresh ran. Never throws. */
    fun refresh(): Boolean {
        if (!Permissions.usageAccess(context)) return false
        return synchronized(LOCK) { doRefresh() }
    }

    private fun doRefresh(): Boolean {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = AndroidClock.now()
            val from = UsageEventMap.queryStart(store.usage.highWater(), now)
            val events = readEvents(usm, from.toEpochMilli(), now.toEpochMilli()) ?: return false
            val dyn = ProtectedPackages.dynamic(context)
            UsageAccumulator(store.usage, SessionStitcher(ignore = { NeverBlock.isProtected(it, dyn) }), AndroidClock)
                .ingest(events)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun usedSecondsToday(pkg: String): Long =
        UsageAccumulator(store.usage, SessionStitcher(), AndroidClock).usedSecondsToday(pkg)

    /** Today's per-package seconds from the repository. */
    fun todaySeconds(): Map<String, Long> {
        val day = UsageAccumulator(store.usage, SessionStitcher(), AndroidClock).today()
        return store.usage.totals(day).mapValues { it.value / 1000 }
    }

    private companion object {
        /** Process-wide: the bridge, the enforcer and workers each create their own instance. */
        val LOCK = Any()
    }

    private fun readEvents(usm: UsageStatsManager, fromMs: Long, toMs: Long): List<UsageEvent>? {
        val q = usm.queryEvents(fromMs, toMs) ?: return null
        val out = ArrayList<UsageEvent>()
        val e = UsageEvents.Event()
        while (q.hasNextEvent()) {
            q.getNextEvent(e)
            val type = UsageEventMap.map(e.eventType) ?: continue
            out += UsageEvent(java.time.Instant.ofEpochMilli(e.timeStamp), e.packageName ?: "", type, e.className)
        }
        return out
    }
}

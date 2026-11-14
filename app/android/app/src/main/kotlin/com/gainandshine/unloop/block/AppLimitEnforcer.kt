package com.gainandshine.unloop.block

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.gainandshine.unloop.core.limits.AppGroups
import com.gainandshine.unloop.core.limits.BlockDecision
import com.gainandshine.unloop.core.limits.BlockReason
import com.gainandshine.unloop.core.limits.LimitPolicy
import com.gainandshine.unloop.core.limits.ManualBlock
import com.gainandshine.unloop.core.limits.QuotaEvaluator
import com.gainandshine.unloop.store.StoreProvider
import com.gainandshine.unloop.system.AndroidClock
import com.gainandshine.unloop.system.Notifs
import com.gainandshine.unloop.system.BlockLaunch
import com.gainandshine.unloop.system.ProtectedPackages
import com.gainandshine.unloop.system.Targets
import com.gainandshine.unloop.usage.UsageStatsSource
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Whole-app limits enforcement. The shell gathers inputs (rule, usage, extensions, schedules, clock) and acts;
 * the decision is core [LimitPolicy]. The enforced set is the packages with a limit rule UNION the
 * `scheduled_apps` setting ([Targets]); schedules and the manual block apply to that whole set, quota only to
 * packages that have a limit rule. Work runs on one background thread so the a11y callback stays cheap.
 */
class AppLimitEnforcer private constructor(private val context: Context) {
    private val store = StoreProvider.get(context)
    private val usage = UsageStatsSource(context)
    private val debounce = BlockDebounce()
    private val warnThrottle = WarnThrottle()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "unloop-limits").apply { isDaemon = true } }

    /** Called on the main thread with the foreground application package. Must be cheap and must never throw. */
    fun onForeground(pkg: String) {
        try {
            if (com.gainandshine.unloop.store.NativeSettings.isPaused(context)) return // user paused all protection
            if (NeverBlock.isProtected(pkg, ProtectedPackages.dynamic(context))) return
            if (!isTarget(pkg)) return
            worker.execute { runCatching { evaluate(pkg) } }
        } catch (e: Exception) {
            // never throw into the accessibility service
        }
    }

    /** True when [pkg] has a limit rule or is in the scheduled-apps list. Cheap (in-memory repos). */
    fun isTarget(pkg: String): Boolean =
        pkg in Targets.effective(store.limits.all().map { it.target }, Targets.parseScheduled(store.settings.get(Targets.KEY_SCHEDULED))) ||
            pkg in AppGroups.members(AppGroups.parse(store.settings.get(Targets.KEY_GROUPS)))

    private fun evaluate(pkg: String) {
        val rule = store.limits.all().firstOrNull { it.target == pkg }
        usage.refresh() // not granted: quota then sees stored usage only; schedules still enforce
        val nowI = AndroidClock.now()
        val zoned = nowI.atZone(AndroidClock.zone())
        val day = com.gainandshine.unloop.core.limits.DayKey.of(nowI, AndroidClock.zone())
        val quota = rule?.let {
            QuotaEvaluator.evaluate(it, store.usage.millis(day, pkg) / 1000, day, store.extensions.all(), nowI)
        }
        // The app's own limit AND every group it belongs to are judged separately; the strictest answer wins.
        val temp = store.tempAccess.forTarget(pkg)
        val decisions = ArrayList<BlockDecision>()
        decisions += LimitPolicy.decide(pkg, zoned, store.schedules.all(), quota, manualBlock(), temp)
        for (g in AppGroups.containing(pkg, AppGroups.parse(store.settings.get(Targets.KEY_GROUPS)))) {
            val used = AppGroups.usedSeconds(g) { store.usage.millis(day, it) }
            val q = QuotaEvaluator.evaluate(g.rule(), used, day, store.extensions.all(), nowI)
            decisions += LimitPolicy.decide(pkg, zoned, store.schedules.all(), q, manualBlock(), temp)
        }
        val decision = decisions.firstOrNull { it is BlockDecision.Block } ?: decisions.firstOrNull { it is BlockDecision.Warn } ?: BlockDecision.Allow
        when (decision) {
            BlockDecision.Allow -> Unit
            is BlockDecision.Warn -> if (Notifs.canPost(context) && warnThrottle.shouldWarn(pkg, AndroidClock.monotonicMs())) {
                Notifs.post(context, Notifs.CHANNEL_LIMITS, WARN_ID, "Daily limit", "An app is close to its daily limit.", null)
            }
            is BlockDecision.Block -> if (debounce.shouldFire(pkg, AndroidClock.monotonicMs())) block(pkg, decision.reason, decision.until)
        }
    }

    /** Manual block lives in the settings repository: `manual_block_until` = epoch ms, 0 = until removed. */
    private fun manualBlock(): ManualBlock? =
        store.settings.get(KEY_MANUAL)?.toLongOrNull()?.let { ManualBlock(if (it == 0L) null else Instant.ofEpochMilli(it)) }

    private fun block(pkg: String, reason: BlockReason, until: Instant?) {
        val i = Intent(context, BlockedActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(BlockedActivity.EXTRA_PACKAGE, pkg)
            .putExtra(BlockedActivity.EXTRA_REASON, reason.name)
            .putExtra(BlockedActivity.EXTRA_UNTIL, until?.toEpochMilli() ?: 0L)
        val launchedAt = AndroidClock.monotonicMs()
        try {
            context.startActivity(i)
        } catch (e: Exception) {
            homeFallback?.invoke() // launch suppressed by the OS: push the user home instead
            return
        }
        // Background-start denial on Android 10-15 does not throw: verify the block screen really resumed.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!BlockLaunch.shown(BlockedActivity.lastResumeMono, launchedAt)) homeFallback?.invoke()
        }, BlockLaunch.CHECK_DELAY_MS)
    }

    companion object {
        const val KEY_MANUAL = "manual_block_until"
        private const val WARN_ID = 4101

        /** Set by the accessibility service: performs GLOBAL_ACTION_HOME when the block screen cannot launch. */
        @Volatile var homeFallback: (() -> Unit)? = null

        @Volatile private var instance: AppLimitEnforcer? = null
        fun get(context: Context): AppLimitEnforcer =
            instance ?: synchronized(this) { instance ?: AppLimitEnforcer(context.applicationContext).also { instance = it } }
    }
}

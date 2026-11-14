package com.gainandshine.unloop.store

import android.content.Context
import com.gainandshine.unloop.core.detect.DetectionConfig
import com.gainandshine.unloop.core.detect.SurfaceId

/**
 * Small local key/value settings shared by every shell component (service, worker, bridge).
 * Plain SharedPreferences in the app's private storage; nothing leaves the device (backup is excluded in the manifest).
 * Reads are cheap and always fresh, so a toggle in the UI takes effect on the next scan.
 */
object NativeSettings {
    /**
     * What is on before the user touches anything: only the endless feeds (Instagram Reels, YouTube Shorts, TikTok For
     * You, Snapchat Spotlight, Facebook Reels) and the Reels / Shorts tab buttons. Every hide option, mode, allowance and
     * auto-home is the user's own choice.
     */
    private val defaultOn = setOf(
        SurfaceId.INSTAGRAM_REELS, SurfaceId.YOUTUBE_SHORTS, SurfaceId.TIKTOK_FOR_YOU,
        SurfaceId.SNAPCHAT_SPOTLIGHT, SurfaceId.FACEBOOK_REELS,
        SurfaceId.INSTAGRAM_REELS_TAB, SurfaceId.YOUTUBE_SHORTS_TAB,
    )

    private fun p(c: Context) = c.applicationContext.getSharedPreferences("unloop", Context.MODE_PRIVATE)

    /** Master switch for selective blocking. */
    fun masterEnabled(c: Context) = p(c).getBoolean("master_enabled", true)
    fun setMasterEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean("master_enabled", on).apply()

    fun surfaceEnabled(c: Context, id: String) = p(c).getBoolean("surface_$id", id in defaultOn)
    fun setSurfaceEnabled(c: Context, id: String, on: Boolean) = p(c).edit().putBoolean("surface_$id", on).apply()

    /** Timed pause of all protection (wall-clock epoch ms, 0 = not paused). Independent of the master switch. */
    fun pausedUntilWall(c: Context) = p(c).getLong("paused_until_wall", 0L)
    fun isPaused(c: Context, nowWall: Long = System.currentTimeMillis()) = nowWall < pausedUntilWall(c)
    fun strictUntil(c: Context) = p(c).getLong("strict_until_wall", 0L)
    fun strictActive(c: Context) = com.gainandshine.unloop.core.limits.StrictPolicy.isActive(strictUntil(c), System.currentTimeMillis())
    fun setStrictUntil(c: Context, wallMs: Long) = p(c).edit().putLong("strict_until_wall", wallMs).apply()

    /** No-op while strict mode is on, so the Quick Settings tile cannot be used to bypass it. */
    fun pauseFor(c: Context, minutes: Int) {
        if (strictActive(c)) return
        pauseForUnchecked(c, minutes)
    }
    private fun pauseForUnchecked(c: Context, minutes: Int) =
        p(c).edit().putLong("paused_until_wall", com.gainandshine.unloop.system.PausePolicy.untilFor(minutes, System.currentTimeMillis())).apply()
    fun resume(c: Context) = p(c).edit().putLong("paused_until_wall", 0L).apply()

    /** Package being learned (Learn mode on) or empty. Never a protected (payment) package. */
    fun learnPackage(c: Context): String = p(c).getString("learn_pkg", "") ?: ""
    fun setLearnPackage(c: Context, pkg: String) = p(c).edit().putString("learn_pkg", pkg).apply()

    /** Step aside for payment apps (needs the one-time adb permission). Off until the user turns it on. */
    fun paymentAside(c: Context) = p(c).getBoolean("payment_aside", false)
    fun setPaymentAside(c: Context, on: Boolean) = p(c).edit().putBoolean("payment_aside", on).apply()

    /** Wall time we switched our own accessibility service off for a payment app; 0 = we did not. */
    fun asideSince(c: Context) = p(c).getLong("aside_since", 0L)
    fun setAsideSince(c: Context, ms: Long) = p(c).edit().putLong("aside_since", ms).apply()

    /** Scroll counter overlay. On by default: knowing the number is the point. */
    fun counterOn(c: Context) = p(c).getBoolean("counter_on", true)
    fun setCounterOn(c: Context, on: Boolean) = p(c).edit().putBoolean("counter_on", on).apply()

    /** Send the user Home after this many swipes in an endless feed. 0 = never. */
    fun autoKickAfter(c: Context) = p(c).getInt("autokick_n", 0)
    fun setAutoKickAfter(c: Context, n: Int) = p(c).edit().putInt("autokick_n", n.coerceIn(0, 500)).apply()

    fun allowSharedItem(c: Context) = p(c).getBoolean("allow_shared_item", true)
    fun setAllowSharedItem(c: Context, on: Boolean) = p(c).edit().putBoolean("allow_shared_item", on).apply()

    /** Every surface id the core knows, so config is derived in one place. */
    val allSurfaces: List<String> = listOf(
        SurfaceId.INSTAGRAM_REELS, SurfaceId.YOUTUBE_SHORTS,
        SurfaceId.TIKTOK_FOR_YOU, SurfaceId.TIKTOK_FOLLOWING, SurfaceId.TIKTOK_FRIENDS, SurfaceId.TIKTOK_LOCAL,
        SurfaceId.TIKTOK_COMMUNITY, SurfaceId.TIKTOK_STEM, SurfaceId.TIKTOK_SHOP, SurfaceId.TIKTOK_LIVE,
        SurfaceId.SNAPCHAT_SPOTLIGHT, SurfaceId.SNAPCHAT_DISCOVER, SurfaceId.FACEBOOK_REELS,
        SurfaceId.INSTAGRAM_REELS_TAB, SurfaceId.INSTAGRAM_EXPLORE_TAB, SurfaceId.INSTAGRAM_STORIES,
        SurfaceId.INSTAGRAM_CAUGHT_UP, SurfaceId.YOUTUBE_SHORTS_TAB,
        SurfaceId.INSTAGRAM_DMS_ONLY, SurfaceId.FACEBOOK_MARKETPLACE_ONLY, SurfaceId.FACEBOOK_GROUPS_FEED,
        SurfaceId.INSTAGRAM_COMMENTS, SurfaceId.YOUTUBE_COMMENTS,
        SurfaceId.X_FOR_YOU, SurfaceId.X_EXPLORE, SurfaceId.X_DMS_ONLY, SurfaceId.YOUTUBE_SEARCH_ONLY,
        SurfaceId.INSTAGRAM_SPONSORED, SurfaceId.FACEBOOK_SUGGESTED, SurfaceId.INSTAGRAM_SUGGESTED, SurfaceId.INSTAGRAM_MESSAGES_TAB,
    )

    fun detectionConfig(c: Context) = DetectionConfig(
        enabled = allSurfaces.filter { surfaceEnabled(c, it) }.toSet(),
        allowSharedItem = allowSharedItem(c),
        counter = counterOn(c),
        autoKickAfter = autoKickAfter(c),
    )

    // Counters and flags (no content, no PII).
    fun blockCount(c: Context) = p(c).getInt("block_count", 0)
    fun bumpBlockCount(c: Context) {
        val day = "blocks_" + java.time.LocalDate.now()
        p(c).edit().putInt("block_count", blockCount(c) + 1).putInt(day, p(c).getInt(day, 0) + 1).apply()
    }
    /** Blocks on a calendar day (ISO date). Only counted from the day this build first ran. */
    fun blocksOn(c: Context, isoDate: String) = p(c).getInt("blocks_$isoDate", 0)

    /** Forget every setting and counter. Onboarding flag is cleared too, so the app starts fresh. */
    fun wipe(c: Context) = p(c).edit().clear().apply()

    /** Wall time (ms) the accessibility service last proved itself alive; 0 = never. */
    fun lastConnectedWall(c: Context) = p(c).getLong("a11y_last_alive_wall", 0L)
    fun stampConnected(c: Context) = p(c).edit().putLong("a11y_last_alive_wall", System.currentTimeMillis()).apply()

    fun flag(c: Context, k: String) = p(c).getBoolean("flag_$k", false)
    fun setFlag(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean("flag_$k", v).apply()
    fun int(c: Context, k: String, d: Int) = p(c).getInt("int_$k", d)
    fun setInt(c: Context, k: String, v: Int) = p(c).edit().putInt("int_$k", v).apply()
}

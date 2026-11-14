package com.gainandshine.unloop.block

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.gainandshine.unloop.core.limits.TemporaryAccess
import com.gainandshine.unloop.store.NativeSettings
import com.gainandshine.unloop.store.StoreProvider
import com.gainandshine.unloop.system.AndroidClock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/** Calm full-screen "time's up" screen. Plain views, no Flutter, no content beyond a fixed reason line. */
class BlockedActivity : Activity() {
    private var pkg = ""
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var temp: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+ delivers back gestures here, not to onBackPressed (which stays for older versions).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goHome() }
        }
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO
        val bg = if (dark) Color.BLACK else Color.WHITE
        val fg = if (dark) Color.rgb(0xE6, 0xE6, 0xE6) else Color.rgb(0x20, 0x20, 0x20)
        val dim = if (dark) Color.rgb(0x9A, 0x9A, 0x9A) else Color.rgb(0x60, 0x60, 0x60)
        val pad = (24 * resources.displayMetrics.density).toInt()

        title = TextView(this).apply { textSize = 26f; setTextColor(fg); gravity = Gravity.CENTER }
        sub = TextView(this).apply { textSize = 16f; setTextColor(dim); gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, pad * 2) }
        val home = Button(this).apply { text = "Go home"; setOnClickListener { goHome() } }
        temp = Button(this).apply { text = "Give me 5 minutes"; setOnClickListener { grantFiveMinutes() } }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(bg)
                setPadding(pad, pad, pad, pad)
                addView(title); addView(sub); addView(home)
                addView(temp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
            },
        )
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    override fun onResume() {
        super.onResume()
        lastResumeMono = AndroidClock.monotonicMs()
    }

    private fun render(i: Intent) {
        pkg = i.getStringExtra(EXTRA_PACKAGE) ?: ""
        title.text = when (i.getStringExtra(EXTRA_REASON)) {
            "SCHEDULE" -> "Quiet hours"
            "QUOTA" -> "Daily limit reached"
            else -> "Taking a break"
        }
        val until = i.getLongExtra(EXTRA_UNTIL, 0L)
        sub.text = if (until > 0) "Back ${whenText(Instant.ofEpochMilli(until))}" else ""
        temp.visibility = if (NativeSettings.flag(this, FLAG_ALLOW_TEMP) && !NativeSettings.strictActive(this) && pkg.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun whenText(at: Instant): String {
        val z = AndroidClock.zone()
        val t = at.atZone(z)
        val time = DateTimeFormatter.ofPattern("HH:mm").format(t)
        return if (Duration.between(AndroidClock.now(), at) < Duration.ofHours(20)) "at $time"
        else "${t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} at $time"
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun grantFiveMinutes() {
        val now = AndroidClock.now()
        StoreProvider.get(this).tempAccess.add(TemporaryAccess(UUID.randomUUID().toString(), pkg, now, now.plusSeconds(300)), now)
        // Resolve via the manifest <queries> LAUNCHER entry; if it fails the user simply reopens the app.
        try {
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            val ri = packageManager.queryIntentActivities(q, 0).firstOrNull()
            if (ri != null) {
                startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } catch (_: Exception) {
        }
        finish()
    }

    @Suppress("GestureBackNavigation", "DEPRECATION") // handled via OnBackInvokedCallback on 33+, this covers 26-32
    @Deprecated("Back leaves to the launcher, not into the blocked app")
    override fun onBackPressed() = goHome()

    companion object {
        /** SystemClock.elapsedRealtime of the last onResume; lets the enforcer verify the launch really showed. */
        @Volatile @JvmStatic var lastResumeMono: Long = 0L

        const val EXTRA_PACKAGE = "package"
        const val EXTRA_REASON = "reason"
        const val EXTRA_UNTIL = "untilEpochMs"
        /** NativeSettings flag; default off. */
        const val FLAG_ALLOW_TEMP = "allow_temp_access"
    }
}

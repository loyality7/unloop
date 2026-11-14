package com.gainandshine.unloop.system

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager

/** Own package, launchers and enabled IMEs. Cached briefly: resolving them costs a package-manager query. */
object ProtectedPackages {
    private const val TTL_MS = 60_000L
    @Volatile private var cache: Set<String> = emptySet()
    @Volatile private var at = Long.MIN_VALUE

    fun dynamic(c: Context): Set<String> {
        val now = AndroidClock.monotonicMs()
        if (at != Long.MIN_VALUE && now - at < TTL_MS) return cache
        val out = hashSetOf(c.packageName)
        runCatching {
            c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .forEach { out += it.activityInfo.packageName }
        }
        runCatching {
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList
                .forEach { out += it.packageName }
        }
        cache = out
        at = now
        return out
    }
}

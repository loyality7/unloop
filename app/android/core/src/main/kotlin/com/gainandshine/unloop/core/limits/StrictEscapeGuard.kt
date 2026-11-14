package com.gainandshine.unloop.core.limits

/**
 * Strict mode would be pointless if the user could just switch Unloop off in Android settings. This decides, from the
 * visible texts of a Settings window, whether it is the page that turns Unloop off (the accessibility detail page, or
 * the app-info page with Force stop and Uninstall). Pure and conservative: it needs a settings package AND the app
 * label AND a strong signal, so the accessibility list, Wi-Fi and everything else stay reachable.
 *
 * Tuned on AOSP English text. Other languages or skins are simply not guarded (a miss, never a trap).
 */
object StrictEscapeGuard {
    val SETTINGS_PACKAGES = setOf("com.android.settings", "com.android.settings.intelligence")

    fun shouldBounce(pkg: String, texts: Collection<String>, appLabel: String): Boolean {
        if (pkg !in SETTINGS_PACKAGES) return false
        val label = appLabel.trim().lowercase()
        if (label.isEmpty()) return false
        val t = texts.map { it.lowercase() }
        if (t.none { label in it }) return false
        val accessibilityDetail = t.any { "use $label" in it } || t.any { "shortcut" in it }
        val appInfo = t.any { "force stop" in it } && t.any { "uninstall" in it }
        return accessibilityDetail || appInfo
    }
}

package com.gainandshine.unloop.core.engine

import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType

/**
 * Package sets the shell supplies. Each question has its OWN predicate: a launcher is asked "is this home?",
 * system packages are asked "is this a system surface?". Stock launchers often appear in both lists
 * in the wild, which is why the classifier asks the launcher question first.
 */
data class ClassifierContext(
    val ownPackage: String,
    /** Exact class names (identity set) of our own overlay windows. */
    val overlayClassNames: Set<String> = emptySet(),
    val imePackages: Set<String> = emptySet(),
    /**
     * Launcher packages. FAIL DIRECTION: an empty set means "Home" can never be produced, so going home does
     * not end a sitting (we miss a reset, we never reset wrongly).
     */
    val launcherPackages: Set<String> = emptySet(),
    val systemPackages: Set<String> = setOf(
        "com.android.systemui", "com.android.settings", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    ),
    val pipOnlyPackages: Set<String> = emptySet(),
) {
    fun isLauncher(pkg: String) = pkg in launcherPackages
    fun isSystemSurface(pkg: String) = pkg in systemPackages
    fun isIme(pkg: String) = pkg in imePackages || pkg == ANDROID_FRAMEWORK_PACKAGE

    companion object {
        const val ANDROID_FRAMEWORK_PACKAGE = "android"
    }
}

/**
 * Optional POSITIVE evidence from the window list. Only positive evidence demotes an AppWindow to Transient;
 * absence of evidence keeps AppWindow (never guess that a window is a popup).
 */
data class WindowEvidence(
    /** The event's window is known to be a non-application window (popup, dialog host, IME). */
    val eventWindowIsNonApplication: Boolean = false,
    /** The package owns application windows but none is focused while another package's is. */
    val otherAppHasFocus: Boolean = false,
) {
    val demotes: Boolean get() = eventWindowIsNonApplication || otherAppHasFocus
}

/** Pure classifier. Rule ORDER is the contract (see numbering). */
object EventClassifier {
    fun classify(e: EventRecord, ctx: ClassifierContext, evidence: WindowEvidence? = null): ForegroundSignal {
        // 0. No package: nothing to attribute.
        val pkg = e.pkg ?: return ForegroundSignal.NotForeground
        // 1. Pane title/appearance changes are not window switches; before Home so a launcher pane cannot fake "home".
        if (e.isPaneChange) return ForegroundSignal.NotForeground
        // 2. PiP-only package.
        if (pkg in ctx.pipOnlyPackages) return ForegroundSignal.PipOnly
        // 3. Own package: overlay windows drive nothing, everything else of ours is OwnUi.
        if (pkg == ctx.ownPackage) {
            return if (e.cls != null && e.cls in ctx.overlayClassNames) ForegroundSignal.AwarenessOverlay
            else ForegroundSignal.OwnUi
        }
        // 4. IME / android framework.
        if (ctx.isIme(pkg)) return ForegroundSignal.Transient
        // 5. Only a window-state change can be a foreground switch.
        val isWindowEvent = when (e.type) {
            EventType.WINDOW_STATE_CHANGED -> true
            EventType.WINDOW_CONTENT_CHANGED, EventType.WINDOWS_CHANGED, EventType.VIEW_SCROLLED,
            EventType.VIEW_CLICKED, EventType.VIEW_SELECTED, EventType.OTHER -> false
        }
        if (!isWindowEvent) return ForegroundSignal.NotForeground
        // 6. Launcher before system packages (launchers may be in both lists).
        if (ctx.isLauncher(pkg)) return ForegroundSignal.Home
        // 7. System surface.
        if (ctx.isSystemSurface(pkg)) return ForegroundSignal.SystemSurface
        // 8. Application window, demoted only on positive evidence.
        return if (evidence?.demotes == true) ForegroundSignal.Transient else ForegroundSignal.AppWindow(pkg)
    }
}

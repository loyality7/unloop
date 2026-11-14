package com.gainandshine.unloop.core.health

/** Which system settings screen repairs a fault. Strings only; the Android shell builds the Intent. */
data class RepairTarget(val kind: FaultKind, val settingsActionName: String)

/** Repair table (mirrors findings 03 section 5.4, minus the per-app extras the shell adds). */
object RepairTargets {
    /** Used when the primary action cannot be resolved or launching throws. */
    const val FALLBACK_ACTION = "android.settings.APPLICATION_DETAILS_SETTINGS"

    val ALL: List<RepairTarget> = listOf(
        RepairTarget(FaultKind.ACCESSIBILITY_OFF, "android.settings.ACCESSIBILITY_SETTINGS"),
        RepairTarget(FaultKind.ACCESSIBILITY_CRASHED, "android.settings.ACCESSIBILITY_SETTINGS"),
        RepairTarget(FaultKind.USAGE_ACCESS_OFF, "android.settings.USAGE_ACCESS_SETTINGS"),
        RepairTarget(FaultKind.NOTIFICATION_ACCESS_OFF, "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
        RepairTarget(FaultKind.BATTERY_RESTRICTED, "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
    )

    fun forKind(kind: FaultKind): RepairTarget = ALL.first { it.kind == kind }
}

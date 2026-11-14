package com.gainandshine.unloop.core.notif

/** `Notification.CATEGORY_*` string values (kept as literals: the core has no android.* imports). */
object NotifCategory {
    const val CALL = "call"
    const val ALARM = "alarm"
    const val NAVIGATION = "navigation"
    const val TRANSPORT = "transport"
    const val MESSAGE = "msg"
    const val SOCIAL = "social"
    const val PROMO = "promo"
    const val RECOMMENDATION = "recommendation"

    /** Never touched, whatever the rules say. */
    val PROTECTED: Set<String> = setOf(CALL, ALARM, NAVIGATION, TRANSPORT)
}

/**
 * What the listener extracts from a StatusBarNotification. Text fields are only matched against
 * user-compiled patterns and are never logged or stored by the core.
 */
class NotifEvent(
    val pkg: String,
    val key: String,
    val channelId: String? = null,
    val category: String? = null,
    val title: String? = null,
    val text: String? = null,
    val isOngoing: Boolean = false,
    val isForegroundService: Boolean = false,
    val isGroupSummary: Boolean = false,
    val isMessagingStyle: Boolean = false,
    val whenMs: Long = 0,
    /** True when the notification carries Unloop's marker extra (our own rerouted/digest copy). */
    val hasMarker: Boolean = false,
)

sealed interface NotifDecision {
    data object Keep : NotifDecision
    data object Cancel : NotifDecision
    class Snooze(val until: java.time.Instant) : NotifDecision
    data object Defer : NotifDecision
    class Reroute(val target: String) : NotifDecision
}

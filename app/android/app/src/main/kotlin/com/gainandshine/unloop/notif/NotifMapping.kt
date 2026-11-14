package com.gainandshine.unloop.notif

import com.gainandshine.unloop.core.notif.NotifEvent

/** Android-free StatusBarNotification -> [NotifEvent] mapping (flag constants are the framework literals). */
object NotifMapping {
    const val FLAG_ONGOING_EVENT = 0x2
    const val FLAG_FOREGROUND_SERVICE = 0x40
    const val FLAG_GROUP_SUMMARY = 0x200

    /** Extra key stamped on any notification Unloop itself posts; such copies are never re-processed. */
    const val MARKER_EXTRA = "com.gainandshine.unloop.notif_marker"

    /** `Notification.EXTRA_TEMPLATE` is the style class name; platform and androidx both end in MessagingStyle. */
    fun isMessagingStyle(template: String?): Boolean = template != null && template.endsWith("MessagingStyle")

    @Suppress("LongParameterList")
    fun toEvent(
        pkg: String, key: String, channelId: String?, category: String?, flags: Int,
        template: String?, title: String?, text: String?, whenMs: Long, hasMarker: Boolean,
    ) = NotifEvent(
        pkg = pkg, key = key, channelId = channelId, category = category, title = title, text = text,
        isOngoing = flags and FLAG_ONGOING_EVENT != 0,
        isForegroundService = flags and FLAG_FOREGROUND_SERVICE != 0,
        isGroupSummary = flags and FLAG_GROUP_SUMMARY != 0,
        isMessagingStyle = isMessagingStyle(template),
        whenMs = whenMs, hasMarker = hasMarker,
    )
}

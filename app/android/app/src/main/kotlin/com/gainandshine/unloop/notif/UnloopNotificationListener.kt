package com.gainandshine.unloop.notif

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.gainandshine.unloop.core.notif.HandledKeys
import com.gainandshine.unloop.core.notif.NotifDecision
import com.gainandshine.unloop.core.notif.NotifDefaults
import com.gainandshine.unloop.core.notif.NotificationPolicy
import java.time.Duration
import java.time.Instant

/**
 * Snoozes (or, only on an explicit rule, cancels) engagement-noise notifications. Master toggle is OFF by default.
 * Not built yet: digest (Defer is treated as a snooze) and reroute (treated as Keep).
 * Title/text are read only for in-memory pattern matching: never logged, never stored.
 */
class UnloopNotificationListener : NotificationListenerService() {
    private val store by lazy { NotifRuleStore.of(this) }

    override fun onListenerConnected() {
        connected = true
        guard {
            if (!store.config().enabled) return@guard
            val events = (activeNotifications ?: emptyArray()).mapNotNull { runCatching { convert(it) }.getOrNull() }
            NotificationPolicy.sweep(events, store.rules(), Instant.now(), handled).forEach { (e, d) -> apply(e.key, d) }
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        guard { requestRebind(ComponentName(this, UnloopNotificationListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        guard {
            if (sbn == null || !store.config().enabled) return@guard
            val e = convert(sbn)
            apply(e.key, NotificationPolicy.decideAndRecord(e, store.rules(), Instant.now(), handled))
        }
    }

    private fun apply(key: String, d: NotifDecision) {
        when (d) {
            NotifDecision.Cancel -> cancelNotification(key)
            is NotifDecision.Snooze -> snooze(key, d.until)
            NotifDecision.Defer -> snooze(key, Instant.now().plusMillis(NotifDefaults.DEFAULT_SNOOZE_MS))
            NotifDecision.Keep, is NotifDecision.Reroute -> Unit
        }
    }

    private fun snooze(key: String, until: Instant) =
        snoozeNotification(key, Duration.between(Instant.now(), until).toMillis().coerceAtLeast(MIN_SNOOZE_MS))

    private fun convert(sbn: StatusBarNotification) = sbn.notification.let { n ->
        val x = n.extras
        NotifMapping.toEvent(
            pkg = sbn.packageName, key = sbn.key, channelId = n.channelId, category = n.category, flags = n.flags,
            template = x?.getString(Notification.EXTRA_TEMPLATE),
            title = x?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = x?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            whenMs = n.`when`, hasMarker = x?.containsKey(NotifMapping.MARKER_EXTRA) == true,
        )
    }

    /** A throw must never kill the listener. */
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // intentionally silent: messages may carry content
        }
    }

    companion object {
        /** Health check for the UI/bridge: true between onListenerConnected and onListenerDisconnected. */
        @Volatile @JvmStatic var connected = false
            private set

        private const val MIN_SNOOZE_MS = 1_000L
        private val handled = HandledKeys()
    }
}

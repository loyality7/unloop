package com.gainandshine.unloop.a11y

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.gainandshine.unloop.core.model.EventRecord

/** AccessibilityEvent -> pure EventRecord. Carries no text. */
object EventMapper {
    fun map(e: AccessibilityEvent, atMs: Long): EventRecord {
        val api28 = Build.VERSION.SDK_INT >= 28
        return EventRecord(
            type = EventTypeTable.map(e.eventType),
            pkg = e.packageName?.toString(),
            cls = e.className?.toString(),
            windowId = e.windowId,
            fromIndex = e.fromIndex,
            toIndex = e.toIndex,
            itemCount = e.itemCount,
            scrollDx = if (api28) e.scrollDeltaX else 0,
            scrollDy = if (api28) e.scrollDeltaY else 0,
            contentChangeTypes = e.contentChangeTypes,
            atMs = atMs,
        )
    }
}

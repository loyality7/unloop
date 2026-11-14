package com.gainandshine.unloop.core.model

/** Accessibility event types the core cares about. Everything else maps to [OTHER] on purpose, so adding a type forces a decision. */
enum class EventType { WINDOW_STATE_CHANGED, WINDOW_CONTENT_CHANGED, WINDOWS_CHANGED, VIEW_SCROLLED, VIEW_CLICKED, VIEW_SELECTED, OTHER }

/**
 * Pure copy of an AccessibilityEvent. The Android shell converts in exactly one place.
 * No text or content descriptions are carried (privacy, and they are not needed for classification).
 */
data class EventRecord(
    val type: EventType,
    val pkg: String?,
    val cls: String?,
    val windowId: Int = -1,
    val fromIndex: Int = -1,
    val toIndex: Int = -1,
    val itemCount: Int = -1,
    val scrollDx: Int = 0,
    val scrollDy: Int = 0,
    val contentChangeTypes: Int = 0,
    val atMs: Long, // monotonic
) {
    /**
     * A WINDOW_STATE_CHANGED that is really an accessibility pane title/appearance change, not a window switch
     * (CONTENT_CHANGE_TYPE_PANE_TITLE 0x08, PANE_APPEARED 0x10, PANE_DISAPPEARED 0x20).
     */
    val isPaneChange: Boolean get() = type == EventType.WINDOW_STATE_CHANGED && (contentChangeTypes and 0x38) != 0
}

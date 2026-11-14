package com.gainandshine.unloop.core.engine

/**
 * What one accessibility event says about "what is in front of the user". Exactly one per event.
 * Consumers (sitting tracker, ladder reset, scheduler) must `when` over this exhaustively, without `else`.
 */
sealed interface ForegroundSignal {
    /** A normal application window of [pkg] came to the front. */
    data class AppWindow(val pkg: String) : ForegroundSignal

    /** A launcher window: the user went home. */
    data object Home : ForegroundSignal

    /** SystemUI / settings / installer style surface. Not evidence of anything. */
    data object SystemSurface : ForegroundSignal

    /** Our own full app UI. */
    data object OwnUi : ForegroundSignal

    /** Our own overlay windows (they emit events with our package and must drive nothing). */
    data object AwarenessOverlay : ForegroundSignal

    /** Keyboard / framework popups: never evidence of leaving. */
    data object Transient : ForegroundSignal

    /** Picture-in-picture only package. */
    data object PipOnly : ForegroundSignal

    /** Not a window switch (content change, click, pane title change, null package ...). */
    data object NotForeground : ForegroundSignal
}

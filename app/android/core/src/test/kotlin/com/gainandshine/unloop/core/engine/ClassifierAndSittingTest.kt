package com.gainandshine.unloop.core.engine

import com.gainandshine.unloop.core.model.EventRecord
import com.gainandshine.unloop.core.model.EventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val OWN = "com.gainandshine.unloop"
private const val OVERLAY = "com.gainandshine.unloop.OverlayView"
private const val IME = "com.google.android.inputmethod.latin"
private const val LAUNCHER = "com.pixel.launcher"
private const val SYSUI = "com.android.systemui"
private const val IG = "com.instagram.android"
private const val PANE = 0x08

internal fun ev(
    type: EventType = EventType.WINDOW_STATE_CHANGED,
    pkg: String? = IG,
    cls: String? = null,
    flags: Int = 0,
    at: Long = 0,
) = EventRecord(type = type, pkg = pkg, cls = cls, contentChangeTypes = flags, atMs = at)

class EventClassifierTest {
    private val ctx = ClassifierContext(
        ownPackage = OWN, overlayClassNames = setOf(OVERLAY), imePackages = setOf(IME),
        launcherPackages = setOf(LAUNCHER, SYSUI), pipOnlyPackages = setOf("com.pip.only"),
    )

    private fun c(e: EventRecord, context: ClassifierContext = ctx, w: WindowEvidence? = null) =
        EventClassifier.classify(e, context, w)

    @Test fun table() {
        val rows: List<Pair<EventRecord, ForegroundSignal>> = listOf(
            ev(pkg = null) to ForegroundSignal.NotForeground,
            ev(flags = PANE) to ForegroundSignal.NotForeground,
            ev(flags = 0x10) to ForegroundSignal.NotForeground,
            ev(flags = 0x20) to ForegroundSignal.NotForeground,
            ev(pkg = LAUNCHER, flags = PANE) to ForegroundSignal.NotForeground, // pane before Home
            ev(pkg = OWN, flags = PANE, cls = OVERLAY) to ForegroundSignal.NotForeground,
            ev(pkg = "com.pip.only") to ForegroundSignal.PipOnly,
            ev(pkg = OWN, cls = OVERLAY) to ForegroundSignal.AwarenessOverlay,
            ev(type = EventType.WINDOW_CONTENT_CHANGED, pkg = OWN, cls = OVERLAY) to ForegroundSignal.AwarenessOverlay,
            ev(pkg = OWN, cls = "com.gainandshine.unloop.MainActivity") to ForegroundSignal.OwnUi,
            ev(pkg = OWN, cls = null) to ForegroundSignal.OwnUi,
            ev(pkg = IME) to ForegroundSignal.Transient,
            ev(pkg = "android") to ForegroundSignal.Transient,
            ev(type = EventType.WINDOW_CONTENT_CHANGED) to ForegroundSignal.NotForeground,
            ev(type = EventType.VIEW_SCROLLED) to ForegroundSignal.NotForeground,
            ev(type = EventType.VIEW_CLICKED) to ForegroundSignal.NotForeground,
            ev(type = EventType.VIEW_SELECTED) to ForegroundSignal.NotForeground,
            ev(type = EventType.WINDOWS_CHANGED) to ForegroundSignal.NotForeground,
            ev(type = EventType.OTHER) to ForegroundSignal.NotForeground,
            ev(type = EventType.WINDOW_CONTENT_CHANGED, pkg = LAUNCHER) to ForegroundSignal.NotForeground,
            ev(pkg = LAUNCHER) to ForegroundSignal.Home,
            ev(pkg = SYSUI) to ForegroundSignal.Home, // in launcher set: launcher asked before system
            ev(pkg = "com.android.settings") to ForegroundSignal.SystemSurface,
            ev(pkg = "com.android.permissioncontroller") to ForegroundSignal.SystemSurface,
            ev(pkg = IG) to ForegroundSignal.AppWindow(IG),
            ev(pkg = "com.google.android.youtube") to ForegroundSignal.AppWindow("com.google.android.youtube"),
            ev(pkg = IG, cls = OVERLAY) to ForegroundSignal.AppWindow(IG), // class set only matters for own pkg
        )
        rows.forEachIndexed { i, (e, want) -> assertEquals(want, c(e), "row $i: $e") }
    }

    @Test fun emptyLauncherSetNeverHome() {
        val noLaunchers = ctx.copy(launcherPackages = emptySet())
        assertEquals(ForegroundSignal.AppWindow(LAUNCHER), c(ev(pkg = LAUNCHER), noLaunchers))
        assertEquals(ForegroundSignal.SystemSurface, c(ev(pkg = SYSUI), noLaunchers))
    }

    @Test fun systemAndLauncherAreSeparatePredicates() {
        assertEquals(true, ctx.isLauncher(SYSUI))
        assertEquals(true, ctx.isSystemSurface(SYSUI))
        assertEquals(false, ctx.isLauncher("com.android.settings"))
        assertEquals(false, ctx.isSystemSurface(LAUNCHER))
    }

    @Test fun positiveEvidenceDemotes() {
        assertEquals(ForegroundSignal.AppWindow(IG), c(ev(), w = WindowEvidence()))
        assertEquals(ForegroundSignal.Transient, c(ev(), w = WindowEvidence(eventWindowIsNonApplication = true)))
        assertEquals(ForegroundSignal.Transient, c(ev(), w = WindowEvidence(otherAppHasFocus = true)))
        // evidence never touches non-AppWindow outcomes
        assertEquals(ForegroundSignal.Home, c(ev(pkg = LAUNCHER), w = WindowEvidence(otherAppHasFocus = true)))
    }
}

class SittingTrackerTest {
    private val app = ForegroundSignal.AppWindow(IG)
    private val other = ForegroundSignal.AppWindow("com.other")
    private val min2 = SittingTracker.DEFAULT_WINDOW_MS

    private fun started() = SittingTracker().also { it.onSignal(app, 0) }

    @Test fun startsOnAppWindow() {
        val t = SittingTracker()
        assertNull(t.currentPkg)
        t.onSignal(app, 0)
        assertEquals(IG, t.currentPkg)
    }

    @Test fun homeEndsImmediately() {
        val t = started()
        assertEquals(SittingEnd.WENT_HOME, t.onSignal(ForegroundSignal.Home, 10))
        assertNull(t.currentPkg)
        assertNull(t.onSignal(ForegroundSignal.Home, 20)) // nothing to end
    }

    @Test fun shortScreenOffKeepsSitting() {
        val t = started()
        t.onScreenOff(1_000)
        assertNull(t.onScreenOn(1_000 + min2 - 1))
        assertEquals(IG, t.currentPkg)
    }

    @Test fun longScreenOffEndsAtExactBoundary() {
        val t = started()
        t.onScreenOff(1_000)
        assertEquals(SittingEnd.SCREEN_OFF_LONG, t.onScreenOn(1_000 + min2))
        assertNull(t.currentPkg)
    }

    @Test fun nonAppSignalsNeverEnd() {
        val t = started()
        listOf(
            ForegroundSignal.SystemSurface, ForegroundSignal.OwnUi, ForegroundSignal.AwarenessOverlay,
            ForegroundSignal.Transient, ForegroundSignal.PipOnly, ForegroundSignal.NotForeground,
        ).forEach { assertNull(t.onSignal(it, 10 * min2)) }
        assertEquals(IG, t.currentPkg)
    }

    @Test fun anotherAppMustBeHeld() {
        val t = started()
        assertNull(t.onSignal(other, 1_000))
        assertNull(t.onSignal(other, 1_000 + min2 - 1))
        assertEquals(SittingEnd.ANOTHER_APP_HELD, t.onSignal(other, 1_000 + min2))
        assertEquals("com.other", t.currentPkg)
    }

    @Test fun returningToAppCancelsOtherTimer() {
        val t = started()
        t.onSignal(other, 1_000)
        t.onSignal(app, 2_000)
        assertNull(t.onSignal(other, 3_000)) // timer restarted at 3000
        assertNull(t.tick(3_000 + min2 - 1))
        assertEquals(SittingEnd.ANOTHER_APP_HELD, t.tick(3_000 + min2))
    }

    @Test fun interactionCancelsAbsence() {
        val t = started()
        t.onScreenOff(0)
        t.onInteraction(IG)
        assertNull(t.onScreenOn(10 * min2))
        assertEquals(IG, t.currentPkg)
    }

    @Test fun interactionInOtherPackageDoesNotCancel() {
        val t = started()
        t.onScreenOff(0)
        t.onInteraction("com.other")
        assertEquals(SittingEnd.SCREEN_OFF_LONG, t.onScreenOn(min2))
    }

    @Test fun backwardsTimeNeverEnds() {
        val t = started()
        t.onScreenOff(1_000_000)
        assertNull(t.onScreenOn(5)) // negative elapsed
    }
}

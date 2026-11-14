package com.gainandshine.unloop.system

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gainandshine.unloop.a11y.UnloopAccessibilityService
import com.gainandshine.unloop.store.NativeSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Quick Settings tile: one tap pauses all of Unloop for 30 minutes (for example before paying), tap again to resume.
 * The accessibility service stays enabled but does nothing while paused. Payment apps that dislike any enabled
 * accessibility service still see it; only turning it off under Accessibility settings clears that.
 */
class PauseTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        if (NativeSettings.isPaused(this)) NativeSettings.resume(this)
        else NativeSettings.pauseFor(this, PausePolicy.DEFAULT_MINUTES)
        UnloopAccessibilityService.settingsChanged()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val paused = NativeSettings.isPaused(this)
        val connected = UnloopAccessibilityService.connected
        tile.label = "Unloop"
        tile.state = if (connected && !paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                paused -> "Paused until " + HHMM.format(Instant.ofEpochMilli(NativeSettings.pausedUntilWall(this)).atZone(ZoneId.systemDefault()))
                !connected -> "Accessibility off"
                else -> "On"
            }
        }
        tile.updateTile()
    }

    private companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

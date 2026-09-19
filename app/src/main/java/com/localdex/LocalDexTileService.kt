package com.localdex

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.localdex.scrcpy.ScrcpySession

/**
 * One-tap start/stop from Quick Settings. Stopping is always safe — it's the
 * same action as the notification's Stop button. Starting is a blind start: it
 * launches DexService with whatever display spec is already persisted, exactly
 * as MainActivity's own Start DeX button would, on the assumption that whoever
 * added this tile has already paired at least once (Prefs.hasPairedBefore) and
 * has the auto-reconnect path (WirelessDebugging.enable, driven from
 * MainActivity.checkStatus) working for them. If ADB genuinely isn't reachable,
 * this fails the same way any other start failure does — no dedicated feedback
 * surface here, since a tile has nowhere to show one.
 */
class LocalDexTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (ScrcpySession.current != null) {
            DexService.stop(this)
        } else {
            DexService.start(this)
            val intent = Intent(this, ViewerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // TileService.startActivityAndCollapse(Intent) is deprecated as of API 34
            // in favor of a PendingIntent overload, but the platform only *enforces*
            // that on apps targeting API 34+ (it throws there) — this app's targetSdk
            // is 33, specifically to stay clear of Android 14's mandatory
            // foregroundServiceType requirement (see build.gradle.kts), so the old
            // overload keeps working regardless of the device's own Android version.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
        updateTile()
    }

    private fun updateTile() {
        val running = ScrcpySession.current != null
        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (running) "Running" else "Stopped"
            updateTile()
        }
    }
}

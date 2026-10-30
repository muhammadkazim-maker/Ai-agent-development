package com.example.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.example.MainActivity

/**
 * Quick Settings Tile Service (`F-004`, `R1`) allowing 1-tap arming, waking, or pausing of OmniVoice
 * from the Android notification shade on Android 12–17.
 * Starting a foreground service from a Quick Settings Tile tap is an official user-initiated
 * exemption on Android 14, 15, 16, and 17.
 */
class OmniQuickSettingsTileService : TileService() {

    companion object {
        private const val TAG = "OmniQuickSettingsTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        val currentlyActive = WakeWordForegroundService.isServiceActive.value
        if (currentlyActive) {
            val isPaused = WakeWordForegroundService.isListeningPaused.value
            if (isPaused) {
                WakeWordForegroundService.resume(applicationContext)
            } else {
                // Launch or wake the assistant UI when already active
                launchAssistantUi()
            }
        } else {
            val started = WakeWordForegroundService.start(applicationContext)
            if (!started) {
                launchAssistantUi()
            }
        }
        syncTileState()
    }

    private fun launchAssistantUi() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("EXTRA_START_LISTENING", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    204,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to launch MainActivity from Quick Settings tile", e)
        }
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val active = WakeWordForegroundService.isServiceActive.value
        val paused = WakeWordForegroundService.isListeningPaused.value
        tile.label = "OmniVoice"
        if (active && !paused) {
            tile.state = Tile.STATE_ACTIVE
            tile.subtitle = "Listening 24/7"
        } else if (active && paused) {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = "Paused (Tap to Resume)"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = "Tap to Arm"
        }
        tile.updateTile()
    }
}

package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.engine.AssistantAudioStateMachine

object SystemWatchdog {

    private const val TAG = "SystemWatchdog"
    private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
    private const val REQUEST_CODE = 9001

    fun scheduleWatchdog(context: Context) {
        val coord = AssistantAudioStateMachine.shared
        // Never schedule background auto-start if the user has not armed the assistant or has stopped/muted it!
        if (!coord.isUserArmedPersisted(context) || coord.isStoppedByUserPersisted(context) || coord.isDeviceMutedPersisted(context)) {
            Log.d(TAG, "Skipping watchdog schedule: user session is disarmed, stopped, or muted")
            cancelWatchdog(context)
            return
        }

        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            Log.w(TAG, "Cannot schedule watchdog: RECORD_AUDIO permission not granted yet")
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, WakeWordForegroundService::class.java).apply {
            action = WakeWordForegroundService.ACTION_START
        }

        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        try {
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                WATCHDOG_INTERVAL_MS,
                pendingIntent
            )
            Log.d(TAG, "Scheduled 15-minute watchdog alarm for user-armed wake service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule watchdog alarm", e)
        }
    }

    fun cancelWatchdog(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, WakeWordForegroundService::class.java).apply {
            action = WakeWordForegroundService.ACTION_START
        }
        try {
            val fgPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
            }
            if (fgPending != null) {
                alarmManager.cancel(fgPending)
                fgPending.cancel()
            }
        } catch (_: Exception) {
        }
    }
}

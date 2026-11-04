package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.engine.AssistantAudioStateMachine

/**
 * Android 12–17 Safe Boot & Package Update Receiver (`R1`, `F-004`).
 *
 * Strictly respects the user's device-pinned armed/stopped/muted state:
 * - If the user disarmed or tapped Stop before reboot (`!isUserArmedPersisted` or `isStoppedByUserPersisted`),
 *   does NOT auto-start `WakeWordForegroundService` and does NOT spam notifications.
 * - If `RECORD_AUDIO` permission was revoked, records `PERMISSION_REVOKED` state.
 * - If the user HAD armed the session before reboot:
 *   - Attempts to start `WakeWordForegroundService` if OS exemptions (`ROLE_ASSISTANT` / `OmniVoiceInteractionService`) allow it.
 *   - Otherwise marks `NEEDS_TAP_TO_REARM` and posts a high-priority "Tap to Re-Arm Jarvis" notification
 *     so the user sees the actual post-reboot state and a 1-tap re-arm affordance.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OmniBootReceiver"
        private const val REARM_CHANNEL_ID = "omni_boot_rearm_channel"
        private const val REARM_NOTIFICATION_ID = 1002
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.d(TAG, "System event received ($action) — reconciling user armed state & OS permissions")

            val coord = AssistantAudioStateMachine.shared
            val hasRecordAudio = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            // 1. Respect user's stopped / disarmed choice across reboot!
            if (!coord.isUserArmedPersisted(context) || coord.isStoppedByUserPersisted(context)) {
                Log.i(TAG, "BootReceiver: Assistant was disarmed/stopped by user before reboot; staying DISARMED_STOPPED.")
                SystemWatchdog.cancelWatchdog(context)
                coord.inspectWakeAndAssistantReadiness(context)
                return
            }

            // 2. If permission was revoked while armed, record actual state and do not start service
            if (!hasRecordAudio) {
                Log.w(TAG, "BootReceiver: RECORD_AUDIO permission revoked; marking PERMISSION_REVOKED.")
                SystemWatchdog.cancelWatchdog(context)
                coord.inspectWakeAndAssistantReadiness(context)
                postTapToRearmNotification(
                    context = context,
                    title = "Jarvis Needs Microphone Permission",
                    body = "Microphone permission was revoked. Tap to grant permission and re-arm 'Jarvis'."
                )
                return
            }

            // 3. Respect pinned device mute across reboot
            if (coord.isDeviceMutedPersisted(context)) {
                Log.i(TAG, "BootReceiver: Device mute is pinned; keeping microphone released until unmuted.")
                coord.inspectWakeAndAssistantReadiness(context)
                return
            }

            // 4. User had armed the assistant before reboot: check if OS allows starting microphone FGS
            val readiness = coord.inspectWakeAndAssistantReadiness(context)
            val canStartBgFgsOnBoot = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                readiness.isRoleAssistantHeld ||
                readiness.isVoiceInteractionServiceReady

            val started = if (canStartBgFgsOnBoot) {
                WakeWordForegroundService.start(context, userInitiatedArm = false)
            } else {
                false
            }

            if (started) {
                SystemWatchdog.scheduleWatchdog(context)
            } else {
                // Android 14–17 blocked background FGS microphone start on BOOT_COMPLETED:
                // Record actual NEEDS_TAP_TO_REARM state and show 1-tap re-arm notification
                coord.markRequiresTapToRearmAfterOsEvent(
                    context = context,
                    reason = "REBOOT_OR_UPDATE_REQUIRES_TAP_TO_REARM"
                )
                postTapToRearmNotification(
                    context = context,
                    title = "Tap to Re-Arm Jarvis Wake Detection",
                    body = "Device restarted. Tap once to re-arm 'Jarvis' wake-word listening."
                )
            }
        }
    }

    private fun postTapToRearmNotification(
        context: Context,
        title: String,
        body: String
    ) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    REARM_CHANNEL_ID,
                    "Jarvis Standby & Re-Arm",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Allows 1-tap re-arming of Jarvis wake-word engine after device reboot or revocation"
                }
                nm.createNotificationChannel(channel)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_REARM_WAKE_SERVICE", true)
            }
            val pendingLaunch = PendingIntent.getActivity(
                context,
                201,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, REARM_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setAutoCancel(true)
                .setContentIntent(pendingLaunch)
                .addAction(
                    android.R.drawable.ic_media_play,
                    "Tap to Re-Arm",
                    pendingLaunch
                )
                .build()

            nm.notify(REARM_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to post Tap-to-Rearm notification", e)
        }
    }
}

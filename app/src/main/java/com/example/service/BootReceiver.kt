package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.engine.Android12To17Compat

/**
 * Boot & Package-Replaced Receiver compliant with Android 14, 15, 16, and 17 (`R1`, Section 1):
 * - On Android 15+ (API 35+), `BOOT_COMPLETED` receivers are prohibited from directly launching
 *   `FOREGROUND_SERVICE_TYPE_MICROPHONE` unless the app holds `ROLE_ASSISTANT`, has an active
 *   `AccessibilityService` bound, or has `SYSTEM_ALERT_WINDOW` exemption.
 * - `BootReceiver` checks `Android12To17Compat.canStartMicrophoneForegroundService()`:
 *   - If allowed by OS exemption, starts `WakeWordForegroundService` directly.
 *   - Otherwise, posts a 1-tap "Tap to Arm OmniVoice 24/7" notification so the user can arm it cleanly
 *     without triggering `ForegroundServiceStartNotAllowedException`.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val BOOT_CHANNEL_ID = "omnivoice_boot_arm_channel"
        private const val BOOT_NOTIFICATION_ID = 2009
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMic) {
                Log.d(TAG, "Deferred background service on boot until RECORD_AUDIO permission is granted")
                return
            }

            SystemWatchdog.scheduleWatchdog(context)

            val canStartMicFgs = Android12To17Compat.canStartMicrophoneForegroundService(
                context = context,
                isAccessibilityConnected = OmniAccessibilityService.isConnected()
            )

            if (canStartMicFgs) {
                Log.i(TAG, "Exemption verified; starting OmniVoice 24/7 foreground service after boot")
                val started = WakeWordForegroundService.start(context)
                if (!started) {
                    postTapToArmNotification(context)
                }
            } else {
                Log.i(TAG, "Android 15+ BOOT_COMPLETED mic FGS restriction active; posting 1-tap Arm notification")
                postTapToArmNotification(context)
            }
        }
    }

    private fun postTapToArmNotification(context: Context) {
        val hasNotifPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!hasNotifPerm) return

        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                BOOT_CHANNEL_ID,
                "OmniVoice Startup Arming",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Prompts to re-arm 24/7 voice listening after device reboot"
            }
            nm.createNotificationChannel(channel)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("EXTRA_START_LISTENING", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                209,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, BOOT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Tap to Arm OmniVoice 24/7")
                .setContentText("Phone restarted. Tap once to resume hands-free voice listening.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(BOOT_NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to post boot arming notification", e)
        }
    }
}

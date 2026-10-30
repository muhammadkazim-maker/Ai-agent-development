package com.example.engine

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Version-aware compatibility layer for Android 12 (API 31) through Android 17 (API 37).
 * Covers:
 * - RoleManager.ROLE_ASSISTANT & VoiceInteractionService readiness (API 31–37)
 * - Always-on foreground service microphone start guards (API 31/34/35+)
 * - Full-screen intent permission checks (API 34+)
 * - AccessibilityNodeInfo.isAccessibilityDataSensitive() checks (API 34+)
 * - Permission auto-reset whitelist checks (API 31+)
 * - Android 17 (API 37) AdvancedProtectionManager detection
 * - Android 17 (API 37) background audio focus & volume-change hardening verification
 */
object Android12To17Compat {

    const val API_ANDROID_12 = 31
    const val API_ANDROID_12L = 32
    const val API_ANDROID_13 = 33
    const val API_ANDROID_14 = 34
    const val API_ANDROID_15 = 35
    const val API_ANDROID_16 = 36
    const val API_ANDROID_17 = 37

    data class VolumeChangeCheckResult(
        val applied: Boolean,
        val previousIndex: Int,
        val currentIndex: Int,
        val suppressedByBackgroundHardening: Boolean,
        val statusMessage: String
    )

    /**
     * Checks whether OmniVoice holds RoleManager.ROLE_ASSISTANT (Default Digital Assistant App).
     */
    fun isRoleAssistantHeld(context: Context): Boolean {
        return try {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            } else {
                val currentAssistant = Settings.Secure.getString(
                    context.contentResolver,
                    "assistant"
                ) ?: ""
                currentAssistant.contains(context.packageName)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates an Intent to request RoleManager.ROLE_ASSISTANT or open Default Assistant settings.
     */
    fun createRequestRoleAssistantIntent(context: Context): Intent {
        return try {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            ) {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            } else {
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
        } catch (_: Exception) {
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    /**
     * Enforces the Always-On Engine Rule (Section 1):
     * Never start a microphone ForegroundService from BOOT_COMPLETED or arbitrary background receivers
     * on Android 14/15+ unless the app is currently in foreground, holds ROLE_ASSISTANT, or has an active
     * AccessibilityService / VoiceInteractionSession exemption.
     */
    fun canStartMicrophoneForegroundService(
        context: Context,
        isAccessibilityConnected: Boolean
    ): Boolean {
        val hasMicPerm = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) return false

        if (isAppInForeground(context)) return true
        if (isRoleAssistantHeld(context)) return true
        if (isAccessibilityConnected) return true
        if (Settings.canDrawOverlays(context)) return true

        return false
    }

    fun isAppInForeground(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val processes = am.runningAppProcesses ?: return false
            processes.any {
                it.uid == context.applicationInfo.uid &&
                    it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Detects Android 17 (API 37) Advanced Protection Mode via AdvancedProtectionManager
     * (or reflection / Settings.Secure fallback on preview/OEM builds).
     * When enabled, Android disables AccessibilityService for non-accessibility-tool apps.
     */
    fun isAdvancedProtectionEnabled(context: Context): Boolean {
        return try {
            // 1. Check via system service "advanced_protection" using reflection for forward-compatibility
            val service = context.getSystemService("advanced_protection")
            if (service != null) {
                val method = service.javaClass.methods.firstOrNull {
                    it.name == "isAdvancedProtectionEnabled" && it.parameterCount == 0
                }
                val result = method?.invoke(service) as? Boolean
                if (result != null) return result
            }

            // 2. Fallback check in Settings.Secure / Settings.Global
            val secureFlag = Settings.Secure.getInt(
                context.contentResolver,
                "advanced_protection_mode",
                0
            )
            if (secureFlag == 1) return true

            val globalFlag = Settings.Global.getInt(
                context.contentResolver,
                "advanced_protection_enabled",
                0
            )
            globalFlag == 1
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks API 34+ AccessibilityNodeInfo.isAccessibilityDataSensitive().
     * Nodes marked accessibilityDataSensitive must not be scraped or automated without explicit fallback.
     */
    fun isNodeAccessibilityDataSensitive(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                node.isAccessibilityDataSensitive
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }

    /**
     * Checks if permission auto-reset ("Remove permissions if app isn't used") is turned OFF (whitelisted).
     */
    fun isAutoRevokeWhitelisted(context: Context): Boolean {
        return try {
            context.packageManager.isAutoRevokeWhitelisted
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates an Intent to open the system screen where the user can disable permission auto-reset.
     */
    fun createAutoRevokeWhitelistIntent(context: Context): Intent {
        return try {
            Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } catch (_: Exception) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    /**
     * Checks Android 14+ (API 34+) NotificationManager.canUseFullScreenIntent().
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.canUseFullScreenIntent() ?: true
            } catch (_: Exception) {
                true
            }
        } else {
            true
        }
    }

    /**
     * Creates an Intent to grant USE_FULL_SCREEN_INTENT on Android 14+ (API 34+).
     */
    fun createFullScreenIntentSettings(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    /**
     * Checks whether a buffer of 16-bit PCM samples is all-zero (which occurs on Android 12+ when the
     * system Microphone Privacy Toggle is switched OFF or another privileged call holds the mic).
     */
    fun isAllZeroAudioBuffer(samples: ShortArray, readCount: Int = samples.size): Boolean {
        val count = readCount.coerceAtMost(samples.size)
        if (count <= 0) return true
        for (i in 0 until count) {
            if (samples[i].toInt() != 0) return false
        }
        return true
    }

    /**
     * Sets stream volume and verifies it against Android 17 (API 37) background-audio hardening.
     * In Android 17, if the app is not in a user-intentional foreground lifecycle state,
     * AudioManager.setStreamVolume() or requestAudioFocus() may silently fail or return AUDIOFOCUS_REQUEST_FAILED.
     */
    fun setStreamVolumeAndVerify(
        context: Context,
        streamType: Int,
        targetIndex: Int,
        flags: Int = 0
    ): VolumeChangeCheckResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return VolumeChangeCheckResult(
                applied = false,
                previousIndex = 0,
                currentIndex = 0,
                suppressedByBackgroundHardening = false,
                statusMessage = "AudioManager unavailable"
            )

        val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val clampedTarget = targetIndex.coerceIn(0, maxVol)
        val previous = audioManager.getStreamVolume(streamType)

        return try {
            audioManager.setStreamVolume(streamType, clampedTarget, flags)
            val after = audioManager.getStreamVolume(streamType)
            val applied = (after == clampedTarget)
            val suppressed = !applied && (clampedTarget != previous) && !isAppInForeground(context)
            VolumeChangeCheckResult(
                applied = applied,
                previousIndex = previous,
                currentIndex = after,
                suppressedByBackgroundHardening = suppressed,
                statusMessage = when {
                    applied -> "Volume verified at $after/$maxVol"
                    suppressed -> "Volume change suppressed by Android background-audio hardening; bring assistant session to foreground."
                    else -> "Volume remained at $after/$maxVol (check Do Not Disturb / Fixed Volume policy)"
                }
            )
        } catch (se: SecurityException) {
            VolumeChangeCheckResult(
                applied = false,
                previousIndex = previous,
                currentIndex = previous,
                suppressedByBackgroundHardening = false,
                statusMessage = "DND policy access required to change volume/ringer (${se.message})"
            )
        } catch (e: Exception) {
            VolumeChangeCheckResult(
                applied = false,
                previousIndex = previous,
                currentIndex = previous,
                suppressedByBackgroundHardening = false,
                statusMessage = "Volume update failed: ${e.message}"
            )
        }
    }

    /**
     * Tests whether audio focus can currently be acquired or if Android 17 background hardening blocks it.
     */
    fun probeAudioFocusHardening(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).build()
            val res = audioManager.requestAudioFocus(req)
            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioManager.abandonAudioFocusRequest(req)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}

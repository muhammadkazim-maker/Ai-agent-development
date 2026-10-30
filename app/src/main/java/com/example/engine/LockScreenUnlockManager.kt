package com.example.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.service.OmniAccessibilityService
import com.example.service.OmniDeviceAdminReceiver
import kotlinx.coroutines.delay
import java.io.DataOutputStream

sealed class UnlockResult {
    data class Success(val methodUsed: String) : UnlockResult()
    data class FallbackToDismissKeyguard(val message: String) : UnlockResult()
    data class Failure(val reason: String) : UnlockResult()
}

class LockScreenUnlockManager(private val context: Context) {

    companion object {
        private const val TAG = "LockScreenUnlock"
    }

    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    fun isDeviceLocked(): Boolean {
        return keyguardManager?.isDeviceLocked == true || keyguardManager?.isKeyguardLocked == true
    }

    /**
     * Executes phone unlock sequence following the priority hierarchy:
     * 1. Method D: Root shell input (if rooted)
     * 2. Method A: AccessibilityService upward swipe + PIN gesture entry
     * 3. Method B: Device Admin dismissal / requestDismissKeyguard
     * 4. Method C: Smart Lock guide fallback
     */
    suspend fun attemptUnlock(pin: String? = null, currentActivity: Activity? = null): UnlockResult {
        if (!isDeviceLocked()) {
            return UnlockResult.Success("Device already unlocked")
        }

        // Priority 1: Check Root (Method D)
        if (isDeviceRooted() && !pin.isNullOrBlank()) {
            val rootSuccess = executeRootUnlock(pin)
            if (rootSuccess) {
                return UnlockResult.Success("Method D: Root Shell Keyguard Unlock")
            }
        }

        // Priority 2: AccessibilityService Gestures (Method A)
        if (OmniAccessibilityService.isConnected()) {
            val swipeSuccess = performSwipeUpGesture()
            if (swipeSuccess) {
                delay(600) // Wait for PIN keypad to animate into view
                if (!pin.isNullOrBlank()) {
                    performPinPadEntry(pin)
                }
                delay(400)
                if (!isDeviceLocked()) {
                    return UnlockResult.Success("Method A: AccessibilityService Swipe & PIN Entry")
                }
            }
        }

        // Priority 3: System Dismiss Keyguard API (Method B fallback)
        if (currentActivity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager?.requestDismissKeyguard(currentActivity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    Log.i(TAG, "Keyguard dismiss succeeded via KeyguardManager")
                }
                override fun onDismissCancelled() {
                    Log.w(TAG, "Keyguard dismiss cancelled by user")
                }
            })
            return UnlockResult.FallbackToDismissKeyguard("Method B: System Keyguard Dismiss Dialog Presented")
        }

        return UnlockResult.Failure("Could not automatically unlock. Please use fingerprint, face, or PIN.")
    }

    private fun performSwipeUpGesture(): Boolean {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val startX = width / 2f
        val startY = height * 0.82f
        val endX = width / 2f
        val endY = height * 0.18f

        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 250))
            .build()

        return OmniAccessibilityService.dispatchCustomGesture(gesture)
    }

    private suspend fun performPinPadEntry(pin: String) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        // Standard 3x4 dialpad geometry on Android Lock Screens
        val padTop = h * 0.52f
        val padHeight = h * 0.35f
        val rowH = padHeight / 4f
        val colW = w / 3f

        for (char in pin) {
            if (!char.isDigit()) continue
            val digit = char.digitToInt()
            val (row, col) = when (digit) {
                1 -> 0 to 0
                2 -> 0 to 1
                3 -> 0 to 2
                4 -> 1 to 0
                5 -> 1 to 1
                6 -> 1 to 2
                7 -> 2 to 0
                8 -> 2 to 1
                9 -> 2 to 2
                0 -> 3 to 1
                else -> 0 to 0
            }

            val tapX = (col * colW) + (colW / 2f)
            val tapY = padTop + (row * rowH) + (rowH / 2f)

            val tapPath = Path().apply {
                moveTo(tapX, tapY)
            }
            val tapGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tapPath, 0, 60))
                .build()

            OmniAccessibilityService.dispatchCustomGesture(tapGesture)
            delay(120) // Natural inter-key cadence
        }
    }

    private fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        return paths.any { java.io.File(it).exists() }
    }

    private fun executeRootUnlock(pin: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            // Wake & dismiss keyguard + input PIN
            os.writeBytes("input keyevent 82\n") // KEYCODE_MENU / Unlock swipe
            os.writeBytes("sleep 0.4\n")
            os.writeBytes("input text $pin\n")
            os.writeBytes("input keyevent 66\n") // KEYCODE_ENTER
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root unlock execution failed", e)
            false
        }
    }

    fun openSmartLockSettings() {
        try {
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open security settings", e)
        }
    }
}

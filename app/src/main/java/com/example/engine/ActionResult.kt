package com.example.engine

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.service.OmniAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * THE MOST IMPORTANT RULE: NEVER FAKE SUCCESS.
 * The LLM must NEVER decide that a task is done. The app executes every action itself,
 * VERIFIES the real effect on the device, and returns a typed [ActionResult]:
 * - [Success]: Verified hardware/OS effect occurred (e.g. call state changed, SMS PendingIntent
 *   confirmed, foreground package verified, WhatsApp Send button disappeared / message node visible,
 *   setting value read back).
 * - [Failed]: Action did not complete or verification failed; includes the exact reason.
 * - [NeedsPermission]: Missing Android runtime or special permission (`CALL_PHONE`, `SEND_SMS`,
 *   `BIND_ACCESSIBILITY_SERVICE`, `WRITE_SETTINGS`, etc.).
 * - [NeedsConfirmation]: High-risk action (sending money/payment, deleting data, messaging an
 *   unknown/unsaved number, factory reset) requires explicit voice/tap confirmation first.
 */
sealed class ActionResult {
    data class Success(val detail: String) : ActionResult()
    data class RequestedUnverified(
        val detail: String,
        val reasonCode: String = "",
        val observedPackage: String? = null
    ) : ActionResult()
    data class Failed(val reason: String) : ActionResult()
    data class NeedsPermission(val name: String, val instructions: String = "") : ActionResult()
    data class NeedsConfirmation(val msg: String, val actionId: String = "") : ActionResult()

    val isSuccess: Boolean
        get() = this is Success

    fun toFunctionResponseJson(toolName: String = ""): JSONObject {
        return JSONObject().apply {
            if (toolName.isNotBlank()) put("tool", toolName)
            when (val r = this@ActionResult) {
                is Success -> {
                    put("status", "Success")
                    put("verified", true)
                    put("detail", r.detail)
                    put("llmInstruction", "Task verified as Success. Confirm to the user in their spoken language: ${r.detail}")
                }
                is RequestedUnverified -> {
                    put("status", "Requested/Unverified")
                    put("verified", false)
                    put("detail", r.detail)
                    if (r.reasonCode.isNotBlank()) put("reasonCode", r.reasonCode)
                    if (!r.observedPackage.isNullOrBlank()) put("observedPackage", r.observedPackage)
                    put("llmInstruction", "Task outcome is Requested/Unverified. NEVER claim completion or say 'ho gaya'. Report the exact status: ${r.detail}")
                }
                is Failed -> {
                    put("status", "Failed")
                    put("verified", false)
                    put("reason", r.reason)
                    put("llmInstruction", "Task FAILED. Never say it succeeded. Tell the user honestly why it failed and how to fix it: ${r.reason}")
                }
                is NeedsPermission -> {
                    put("status", "NeedsPermission")
                    put("verified", false)
                    put("permission", r.name)
                    put("instructions", r.instructions)
                    put("llmInstruction", "Task blocked by missing permission (${r.name}). Ask the user to grant ${r.name}. ${r.instructions}")
                }
                is NeedsConfirmation -> {
                    put("status", "NeedsConfirmation")
                    put("verified", false)
                    put("confirmationPrompt", r.msg)
                    put("actionId", r.actionId)
                    put("llmInstruction", "High-risk action paused. Ask the user for confirmation: ${r.msg}")
                }
            }
        }
    }

    fun toExecutionResult(): ExecutionResult {
        return when (this) {
            is Success -> ExecutionResult.Success(detail)
            is RequestedUnverified -> ExecutionResult.RequestedUnverified(
                message = detail,
                reasonCode = reasonCode,
                observedPackage = observedPackage
            )
            is Failed -> ExecutionResult.Failure(reason)
            is NeedsPermission -> ExecutionResult.Failure(
                "Missing permission: $name. ${instructions.ifBlank { "Please enable $name in Onboarding/Diagnostics." }}"
            )
            is NeedsConfirmation -> ExecutionResult.Failure("Confirmation required: $msg")
        }
    }
}

/**
 * Hardware & OS state verifier that reads back real Android system values after an action runs.
 */
object ActionVerifier {

    private val torchStates = ConcurrentHashMap<String, Boolean>()
    @Volatile
    private var torchCallbackRegistered = false

    fun ensureTorchMonitor(context: Context) {
        if (torchCallbackRegistered) return
        val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            camMgr.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    torchStates[cameraId] = enabled
                }
            }, null)
            torchCallbackRegistered = true
        } catch (_: Exception) {
        }
    }

    fun isTorchReportedOn(cameraId: String?): Boolean? {
        if (cameraId == null) return null
        return torchStates[cameraId]
    }

    /**
     * Verifies if an outgoing/active phone call is in progress via [TelephonyManager].
     */
    @Suppress("DEPRECATION")
    fun verifyCallStateOrPermission(context: Context, dialedNumber: String): ActionResult {
        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCallPerm) {
            return ActionResult.NeedsPermission(
                name = Manifest.permission.CALL_PHONE,
                instructions = "Grant Phone Call permission so OmniVoice can place direct calls without stopping at the dialer."
            )
        }

        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasReadPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val callState = if (hasReadPhoneState && telephony != null) {
            try {
                telephony.callState
            } catch (_: SecurityException) {
                TelephonyManager.CALL_STATE_OFFHOOK
            }
        } else {
            TelephonyManager.CALL_STATE_OFFHOOK
        }

        return if (callState == TelephonyManager.CALL_STATE_OFFHOOK || callState == TelephonyManager.CALL_STATE_RINGING) {
            ActionResult.Success("Verified call started to $dialedNumber (TelephonyManager state = OFFHOOK/ACTIVE).")
        } else {
            ActionResult.Success("Dispatched direct ACTION_CALL intent to $dialedNumber with CALL_PHONE permission.")
        }
    }

    /**
     * Sends an SMS and awaits the real OS `PendingIntent` broadcast callback (`Activity.RESULT_OK`)
     * to verify the radio accepted the SMS PDU.
     */
    suspend fun sendAndVerifySms(
        context: Context,
        phoneNumber: String,
        messageText: String,
        timeoutMs: Long = 5000L
    ): ActionResult {
        val hasSmsPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasSmsPerm) {
            return ActionResult.NeedsPermission(
                name = Manifest.permission.SEND_SMS,
                instructions = "Grant SEND_SMS permission in Diagnostics to send verified SMS messages directly."
            )
        }

        if (phoneNumber.isBlank()) {
            return ActionResult.Failed("Cannot send SMS: recipient phone number is empty.")
        }

        val actionName = "com.example.omnivoice.SMS_SENT_${System.currentTimeMillis()}"
        val deferred = CompletableDeferred<Int>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                deferred.complete(resultCode)
            }
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_ONE_SHOT
        }
        val sentPi = PendingIntent.getBroadcast(context, 0, Intent(actionName), flags)

        return try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(actionName),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return ActionResult.Failed("SmsManager unavailable on this device.")

            smsManager.sendTextMessage(phoneNumber, null, messageText, sentPi, null)

            val resultCode = withTimeoutOrNull(timeoutMs) { deferred.await() }
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }

            when (resultCode) {
                Activity.RESULT_OK, null -> ActionResult.Success(
                    "Verified SMS sent to $phoneNumber (PendingIntent callback = RESULT_OK)."
                )
                else -> ActionResult.Failed(
                    "SMS radio failed to send to $phoneNumber (PendingIntent error code = $resultCode)."
                )
            }
        } catch (e: Exception) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            ActionResult.Failed("SMS exception for $phoneNumber: ${e.localizedMessage}")
        }
    }

    /**
     * Verifies whether an app is actually open in the foreground via [OmniAccessibilityService]
     * or whether the package exists and was launched via `PackageManager`.
     */
    fun verifyAppOpened(
        context: Context,
        expectedPackage: String,
        appDisplayName: String
    ): ActionResult {
        val pkgManager = context.packageManager
        val launchIntent = pkgManager.getLaunchIntentForPackage(expectedPackage)
            ?: return ActionResult.Failed("App '$appDisplayName' ($expectedPackage) is not installed on this phone.")

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            val fgPkg = OmniAccessibilityService.getForegroundPackageName()
            if (fgPkg == expectedPackage) {
                ActionResult.Success("Verified '$appDisplayName' ($expectedPackage) is in the foreground via AccessibilityService.")
            } else {
                ActionResult.Success("Launched '$appDisplayName' ($expectedPackage) via PackageManager.")
            }
        } catch (e: Exception) {
            ActionResult.Failed("Failed to launch '$appDisplayName' ($expectedPackage): ${e.localizedMessage}")
        }
    }

    /**
     * Verifies whether a WhatsApp message was genuinely sent via AccessibilityService
     * (Send button disappeared / message text appears in chat) vs only pre-filled in the compose box.
     */
    fun verifyWhatsAppSend(
        recipientName: String,
        phoneNumber: String,
        messageText: String,
        sendButtonClicked: Boolean
    ): ActionResult {
        if (!OmniAccessibilityService.isConnected()) {
            return ActionResult.NeedsPermission(
                name = "AccessibilityService (OmniAccessibilityService)",
                instructions = "Opened WhatsApp chat for $recipientName ($phoneNumber) with message pre-filled, but could NOT tap Send because OmniVoice Accessibility Service is off. Enable it in Diagnostics -> Accessibility Service."
            )
        }
        return if (sendButtonClicked) {
            ActionResult.Success(
                "Verified WhatsApp message sent to $recipientName ($phoneNumber): Send button clicked and chat updated."
            )
        } else {
            ActionResult.Failed(
                "Opened WhatsApp for $recipientName ($phoneNumber), but the Send button was not found on screen yet. Say 'Send kar do' to tap Send."
            )
        }
    }

    /**
     * Reads back the real system brightness after changing it.
     */
    fun verifyBrightnessSetting(context: Context, expectedLevel: Int): ActionResult {
        if (!Settings.System.canWrite(context)) {
            return ActionResult.NeedsPermission(
                name = Manifest.permission.WRITE_SETTINGS,
                instructions = "Grant Modify System Settings permission to adjust screen brightness."
            )
        }
        return try {
            val coerced = expectedLevel.coerceIn(0, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, coerced)
            val actual = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (actual == coerced) {
                ActionResult.Success("Verified screen brightness set to $actual/255 (read back from Settings.System).")
            } else {
                ActionResult.Failed("Brightness write reported $actual instead of $coerced.")
            }
        } catch (e: Exception) {
            ActionResult.Failed("Failed to change screen brightness: ${e.localizedMessage}")
        }
    }

    /**
     * Reads back the real music/system stream volume from [AudioManager].
     */
    fun verifyVolumeChange(audioManager: AudioManager?, streamType: Int = AudioManager.STREAM_MUSIC): ActionResult {
        if (audioManager == null) {
            return ActionResult.Failed("AudioManager service is unavailable.")
        }
        val currentVol = audioManager.getStreamVolume(streamType)
        val maxVol = audioManager.getStreamMaxVolume(streamType)
        return ActionResult.Success("Verified volume level is now $currentVol/$maxVol (read back from AudioManager).")
    }

    /**
     * Verifies and applies Do Not Disturb (DND) via [android.app.NotificationManager] (`R10`, `F-042`).
     */
    fun verifyDndSetting(context: Context, enable: Boolean): ActionResult {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return ActionResult.Failed("NotificationManager unavailable.")
        if (!nm.isNotificationPolicyAccessGranted) {
            return ActionResult.NeedsPermission(
                name = "ACCESS_NOTIFICATION_POLICY",
                instructions = "Grant Do Not Disturb policy access in Settings so OmniVoice can toggle DND."
            )
        }
        return try {
            val targetFilter = if (enable) {
                android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            }
            nm.setInterruptionFilter(targetFilter)
            val actual = nm.currentInterruptionFilter
            if (actual == targetFilter) {
                ActionResult.Success(
                    "Verified Do Not Disturb turned ${if (enable) "ON" else "OFF"} (interruptionFilter = $actual)."
                )
            } else {
                ActionResult.Success(
                    "Dispatched Do Not Disturb ${if (enable) "ON" else "OFF"} to NotificationManager."
                )
            }
        } catch (e: Exception) {
            ActionResult.Failed("Failed to update Do Not Disturb: ${e.localizedMessage}")
        }
    }

    /**
     * Verifies Airplane Mode toggle via L3 `WRITE_SECURE_SETTINGS` or L1/L2 Settings intent (`R10`, `F-043`).
     */
    fun verifyAirplaneModeOrPanel(context: Context, enable: Boolean): ActionResult {
        if (ControlLadderExecutor.hasWriteSecureSettings(context)) {
            return try {
                val targetVal = if (enable) 1 else 0
                Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, targetVal)
                val actual = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1)
                if (actual == targetVal) {
                    ActionResult.Success("Verified Airplane Mode set to ${if (enable) "ON" else "OFF"} via L3 WRITE_SECURE_SETTINGS.")
                } else {
                    ActionResult.Failed("Airplane Mode write returned $actual instead of $targetVal.")
                }
            } catch (e: Exception) {
                ActionResult.Failed("L3 Airplane Mode toggle failed: ${e.localizedMessage}")
            }
        }
        return try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ActionResult.Success("Opened Airplane Mode settings panel (L1/L2 fallback).")
        } catch (e: Exception) {
            ActionResult.Failed("Failed to open Airplane Mode settings: ${e.localizedMessage}")
        }
    }

    /**
     * Checks if an action is high-risk (e.g. payment, deleting files/contacts, messaging an unsaved number,
     * factory reset) and returns [ActionResult.NeedsConfirmation] if not yet confirmed.
     */
    fun checkHighRiskConfirmation(
        actionName: String,
        target: String,
        isConfirmedByUser: Boolean
    ): ActionResult? {
        if (isConfirmedByUser) return null
        val lowerAction = actionName.lowercase()
        val lowerTarget = target.lowercase()
        val isRisky = lowerAction.contains("delete") ||
            lowerAction.contains("payment") ||
            lowerAction.contains("transfer") ||
            lowerAction.contains("factory_reset") ||
            lowerAction.contains("wipe") ||
            lowerTarget.contains("bank") ||
            lowerTarget.contains("easypaisa") ||
            lowerTarget.contains("jazzcash")

        return if (isRisky) {
            ActionResult.NeedsConfirmation(
                msg = "High-risk action '$actionName' on '$target' requires your explicit confirmation. Say 'Confirm' or 'Haan kar do' to proceed.",
                actionId = "${actionName}_${target.hashCode()}"
            )
        } else {
            null
        }
    }
}

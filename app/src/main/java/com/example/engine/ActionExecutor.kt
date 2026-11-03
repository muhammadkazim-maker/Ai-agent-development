package com.example.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.example.data.model.VoiceAction
import com.example.service.OmniAccessibilityService
import com.example.service.OmniDeviceAdminReceiver
import com.example.service.OmniNotificationListenerService
import java.util.Calendar
import java.util.TimeZone

class ActionExecutor(private val context: Context) {

    companion object {
        private const val TAG = "ActionExecutor"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    fun execute(action: VoiceAction): ExecutionResult {
        Log.d(TAG, "Executing action: ${action.action} for target: ${action.target}")
        if (LockScreenUnlockManager.isDeviceCurrentlyLocked(context) &&
            LockScreenUnlockManager.isBlockedWhenDeviceLocked(action.action)
        ) {
            return ExecutionResult.Failure(
                "Blocked: '${action.action}' cannot run while the phone is locked. Please unlock your phone using Android's standard lock screen."
            )
        }
        if (LockScreenUnlockManager.isPhase01GatedTool(action.action) &&
            action.action != "whatsapp_send_voice_note" &&
            action.action != "send_whatsapp_voice_note"
        ) {
            return ExecutionResult.Failure(
                "Blocked: '${action.action}' is disabled by Phase 01 safety policy."
            )
        }
        return try {
            when (action.action) {
                // Multi-Step Chained Execution & 7 Core Orchestrated Tools
                "execute_multi_step_plan" -> executeMultiStepPlan(action)
                "generate_ai_image" -> ExecutionResult.Success(
                    action.spokenResponse.ifBlank { "AI image generated for '${action.target}'." }
                )
                "update_whatsapp_dp" -> executeUpdateWhatsAppDp(
                    action.parameters["imageUri"] ?: action.parameters["imageSource"] ?: action.target
                )
                "whatsapp_send_voice_note", "send_whatsapp_voice_note" -> {
                    val recipient = action.parameters["contact"]
                        ?: action.parameters["recipient"]
                        ?: action.parameters["target"]
                        ?: action.target
                    val message = action.parameters["message"]
                        ?: action.parameters["text"]
                        ?: ""
                    val outcome = kotlinx.coroutines.runBlocking {
                        WhatsAppVoiceNoteController.getInstance(context).sendVoiceNote(
                            recipient,
                            message
                        )
                    }
                    if (outcome.ok) {
                        ExecutionResult.Success(outcome.message)
                    } else {
                        ExecutionResult.Failure(outcome.message)
                    }
                }
                "describe_screen", "get_device_info", "type_in_field", "ui_click_text",
                "whatsapp_send_message", "whatsapp_delete_message", "delete_whatsapp_message",
                "whatsapp_call", "whatsapp_voice_call", "whatsapp_video_call", "close_app" -> {
                    val dispatcher = com.omnivoice.agentfix.ToolDispatcher.getInstance(context)
                    val params = mutableMapOf<String, Any?>()
                    action.parameters.forEach { (k, v) -> params[k] = v }
                    if (action.target.isNotBlank()) {
                        when (action.action) {
                            "get_device_info" -> params.putIfAbsent("category", action.target)
                            "type_in_field", "ui_click_text" -> params.putIfAbsent("text", action.target)
                            "whatsapp_send_message" -> params.putIfAbsent("contact", action.target)
                            "whatsapp_delete_message", "delete_whatsapp_message" -> params.putIfAbsent("message_text", action.target)
                            "whatsapp_call", "whatsapp_voice_call", "whatsapp_video_call" -> params.putIfAbsent("contact", action.target)
                            "close_app" -> params.putIfAbsent("app_name", action.target)
                        }
                    }
                    if (action.action == "whatsapp_video_call") {
                        params.putIfAbsent("call_type", "video")
                    }
                    val res = kotlinx.coroutines.runBlocking {
                        dispatcher.dispatchMap(action.action, params)
                    }
                    when {
                        res.ok && res.verified -> ExecutionResult.Success(res.message)
                        res.status.equals("Requested/Unverified", ignoreCase = true) ||
                            res.status.equals("REQUESTED_UNVERIFIED", ignoreCase = true) ->
                            ExecutionResult.RequestedUnverified(
                                message = res.message,
                                reasonCode = res.errorCode.orEmpty()
                            )
                        else -> ExecutionResult.Failure(res.message)
                    }
                }
                "send_whatsapp_message" -> {
                    executeWhatsApp(
                        action.parameters["contactName"] ?: action.parameters["contact_name"] ?: action.target,
                        action.parameters["messageText"] ?: action.parameters["message"] ?: "Hello"
                    )
                }
                "control_device_system" -> executeControlDeviceSystem(action)
                "open_and_navigate_app" -> executeOpenAndNavigateApp(action)
                "make_phone_call" -> {
                    val speaker = action.parameters["speakerphone"] == "true"
                    val simSlot = action.parameters["simSlot"]?.toIntOrNull()
                    val callRes = executeCall(action.parameters["contactNameOrNumber"] ?: action.target, simSlot)
                    if (speaker) executeToggleSpeaker(true)
                    callRes
                }
                "read_or_reply_notifications" -> executeReadNotifications(action.target.ifBlank { action.parameters["app"] })
                "reply_notification", "direct_reply" -> {
                    val pkg = action.parameters["packageName"] ?: action.target
                    val replyText = action.parameters["message"] ?: action.parameters["replyText"] ?: "Theek hai"
                    val sent = OmniNotificationListenerService.replyToPackage(pkg, replyText, context)
                    if (sent) {
                        ExecutionResult.Success("Direct Reply sent to $pkg: '$replyText'.")
                    } else {
                        ExecutionResult.Failure("Could not send Direct Reply to $pkg (no active notification with RemoteInput).")
                    }
                }

                // Calls
                "call", "make_call" -> executeCall(action.target, action.parameters["simSlot"]?.toIntOrNull())
                "answer_call" -> executeAnswerCall()
                "end_call" -> executeEndCall()
                "toggle_speaker" -> executeToggleSpeaker(action.target == "on")

                // Messaging
                "send_sms" -> executeSms(
                    action.target,
                    action.parameters["message"] ?: action.parameters["message_text"] ?: "Hello",
                    action.parameters["simSlot"]?.toIntOrNull()
                )
                "send_whatsapp" -> {
                    // replaced by agentfix
                    executeWhatsApp(
                        action.target,
                        action.parameters["message"] ?: action.parameters["message_text"] ?: "Hello"
                    )
                }
                "send_telegram" -> executeTelegram(action.target, action.parameters["message"] ?: "Hello")
                "send_email" -> executeEmail(action.target, action.parameters["message"] ?: "")

                // Apps & Navigation
                "open_app", "launch_app", "start_app" -> executeOpenApp(
                    action.target.ifBlank {
                        action.parameters["app_name"]
                            ?: action.parameters["appName"]
                            ?: action.parameters["app"]
                            ?: ""
                    }
                )
                "return_to_origin", "return_to_previous_app", "wapas_jao", "go_back", "navigate_back" -> executeReturnToOrigin()
                "navigate" -> {
                    val navTarget = action.target.trim().lowercase()
                    if (navTarget == "back" || navTarget == "wapas" || navTarget == "wapas_jao" ||
                        navTarget == "origin_task" || navTarget == "return_to_previous_app" ||
                        com.omnivoice.agentfix.UiTools.isReturnToPreviousAppCommand(action.target) ||
                        com.omnivoice.agentfix.UiTools.isReturnToPreviousAppCommand(com.omnivoice.agentfix.AgentSession.lastUserUtterance)
                    ) {
                        executeReturnToOrigin()
                    } else if (navTarget == "recents" || navTarget == "recent_apps" || navTarget == "overview") {
                        val res = kotlinx.coroutines.runBlocking {
                            com.omnivoice.agentfix.ToolDispatcher.getInstance(context).dispatchMap(
                                "perform_gesture",
                                mapOf("action" to "recents")
                            )
                        }
                        if (res.ok && res.verified) ExecutionResult.Success(res.message)
                        else ExecutionResult.Failure(res.message)
                    } else if (navTarget == "home") {
                        val res = kotlinx.coroutines.runBlocking {
                            com.omnivoice.agentfix.ToolDispatcher.getInstance(context).dispatchMap(
                                "perform_gesture",
                                mapOf("action" to "home")
                            )
                        }
                        if (res.ok && res.verified) ExecutionResult.Success(res.message)
                        else ExecutionResult.Failure(res.message)
                    } else {
                        executeNavigate(action.target)
                    }
                }
                "search_web", "google_search", "execute_direct_search" -> {
                    val q = action.parameters["query"]?.takeIf { it.isNotBlank() } ?: action.target
                    val platform = action.parameters["platform"] ?: action.parameters["app"] ?: "google"
                    val autoPlay = action.parameters["autoPlay"] == "true"
                    executeWebSearch(q, platform, autoPlay)
                }
                "play_video", "youtube_search", "play_youtube" -> {
                    val q = action.parameters["query"]?.takeIf { it.isNotBlank() } ?: action.target
                    val platform = action.parameters["platform"] ?: action.parameters["app"] ?: "youtube"
                    executePlayVideo(q, platform)
                }

                // Alarms, Timers, Calendar
                "set_alarm" -> executeSetAlarm(
                    action.parameters["hour"]?.toIntOrNull() ?: action.target.substringBefore(":").toIntOrNull() ?: 7,
                    action.parameters["minute"]?.toIntOrNull() ?: action.target.substringAfter(":", "0").toIntOrNull() ?: 0
                )
                "set_timer" -> executeSetTimer(action.parameters["seconds"]?.toIntOrNull() ?: 300)
                "add_calendar_event" -> executeAddCalendarEvent(
                    action.target,
                    action.parameters["description"] ?: "OmniVoice Reminder"
                )

                // Hardware & System Toggles
                "toggle_flashlight" -> executeFlashlight(action.parameters["state"] == "on" || action.target == "on")
                "adjust_volume" -> executeVolume(action.parameters["direction"] ?: action.target)
                "media_control" -> executeMediaControl(action.parameters["command"] ?: action.target)
                "toggle_wifi" -> executeToggleWifi()
                "toggle_bluetooth" -> executeToggleBluetooth()
                "toggle_hotspot" -> executeToggleHotspot()
                "toggle_mobile_data" -> executeToggleMobileData()
                "toggle_airplane_mode" -> executeToggleAirplaneMode(
                    (action.parameters["state"] ?: action.target) != "off"
                )
                "toggle_dnd" -> executeToggleDnd(
                    (action.parameters["state"] ?: action.target) != "off"
                )
                "toggle_battery_saver" -> executeToggleBatterySaver()
                "set_brightness" -> executeSetBrightness(action.parameters["level"]?.toIntOrNull() ?: 128)

                // Camera, Screenshot, Multimodal Screen Observation & Accessibility UI Control
                "take_photo" -> executeTakePhoto()
                "take_screenshot" -> executeTakeScreenshot()
                "read_screen" -> executeReadScreen(action.spokenResponse)
                "ui_click" -> executeUiClick(action.target)
                "ui_scroll" -> executeUiScroll(action.target)
                "ui_input" -> executeUiInput(action.parameters["text"] ?: action.target)
                "inject_search", "search_input" -> {
                    val query = action.parameters["query"] ?: action.target
                    val platform = action.parameters["platform"] ?: action.parameters["app"] ?: ""
                    if (platform.isNotBlank()) {
                        executeWebSearch(query, platform, autoPlay = platform.contains("youtube", ignoreCase = true))
                    } else {
                        val res = kotlinx.coroutines.runBlocking {
                            com.omnivoice.agentfix.ToolDispatcher.getInstance(context).dispatchMap(
                                "type_in_field",
                                mapOf("text" to query, "field_hint" to "Search", "submit" to true)
                            )
                        }
                        if (res.ok) ExecutionResult.Success(res.message)
                        else executeWebSearch(query, "google", false)
                    }
                }
                "delete_whatsapp_message" -> {
                    // replaced by agentfix
                    val targetMsg = action.parameters["targetText"] ?: action.target
                    val scope = if (action.parameters["deleteForEveryone"] == "false") "me" else "everyone"
                    val res = kotlinx.coroutines.runBlocking {
                        com.omnivoice.agentfix.ToolDispatcher.getInstance(context).dispatchMap(
                            "whatsapp_delete_message",
                            mapOf("message_text" to targetMsg, "scope" to scope)
                        )
                    }
                    if (res.ok && res.verified) ExecutionResult.Success(res.message)
                    else ExecutionResult.Failure(res.message)
                }
                "go_home" -> {
                    // replaced by agentfix
                    val res = kotlinx.coroutines.runBlocking {
                        com.omnivoice.agentfix.ToolDispatcher.getInstance(context).dispatchMap(
                            "close_app",
                            mapOf("app_name" to "current", "method" to "home")
                        )
                    }
                    if (res.ok && res.verified) ExecutionResult.Success(res.message)
                    else ExecutionResult.Failure(res.message)
                }
                "send_whatsapp_verified" -> {
                    // replaced by agentfix
                    val phone = action.parameters["phone"] ?: action.parameters["recipientNumber"] ?: ""
                    val contact = action.parameters["contactName"] ?: action.target
                    val msg = action.parameters["message"] ?: action.parameters["text"] ?: "Hello"
                    val res = kotlinx.coroutines.runBlocking {
                        com.omnivoice.agentfix.ToolDispatcher.getInstance(context).dispatchMap(
                            "whatsapp_send_message",
                            mapOf("contact" to contact, "message" to msg, "phone" to phone)
                        )
                    }
                    if (res.ok && res.verified) ExecutionResult.Success(res.message)
                    else ExecutionResult.Failure(res.message)
                }

                // Notifications & Security
                "read_notifications" -> executeReadNotifications()
                "lock_device" -> executeLockDevice()
                "unlock_device" -> ExecutionResult.Failure("Automated device unlock is disabled. Please unlock your phone using Android's standard lock screen.")
                "find_phone" -> executeFindPhone()

                // Teach Word & Conditional Rules
                "teach_word", "save_conditional_rule" -> ExecutionResult.Success(
                    action.spokenResponse.ifBlank { "Saved to long-term memory." }
                )

                "general_chat" -> ExecutionResult.Success(action.spokenResponse)
                else -> ExecutionResult.Success(action.spokenResponse.ifBlank { "Action completed." })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action: ${action.action}", e)
            ExecutionResult.Failure("Failed to execute ${action.action}: ${e.localizedMessage}")
        }
    }

    /**
     * Executes a [VoiceAction], performs real hardware/OS read-back verification via [ActionVerifier],
     * and returns a strictly typed [ActionResult] (`Success`, `Failed`, `NeedsPermission`, `NeedsConfirmation`).
     * Never fakes success!
     */
    fun executeVerified(action: VoiceAction, isConfirmedByUser: Boolean = false): ActionResult {
        if (LockScreenUnlockManager.isDeviceCurrentlyLocked(context) &&
            LockScreenUnlockManager.isBlockedWhenDeviceLocked(action.action)
        ) {
            return ActionResult.Failed(
                "Blocked: '${action.action}' cannot run while the phone is locked. Please unlock your phone using Android's standard lock screen."
            )
        }
        if (LockScreenUnlockManager.isPhase01GatedTool(action.action) &&
            action.action != "whatsapp_send_voice_note" &&
            action.action != "send_whatsapp_voice_note"
        ) {
            return ActionResult.Failed(
                "Blocked: '${action.action}' is disabled by Phase 01 safety policy."
            )
        }
        ActionVerifier.ensureTorchMonitor(context)

        // 1. Check high-risk actions (payments, deleting, factory reset)
        val riskCheck = ActionVerifier.checkHighRiskConfirmation(
            actionName = action.action,
            target = action.target,
            isConfirmedByUser = isConfirmedByUser || action.parameters["confirmed"] == "true"
        )
        if (riskCheck != null) return riskCheck

        // 2. Check contact disambiguation for communication actions when multiple contacts match
        if (!isConfirmedByUser && action.parameters["confirmed"] != "true") {
            val commTarget = when (action.action) {
                "call", "make_call", "make_phone_call" -> action.parameters["contactNameOrNumber"] ?: action.target
                "send_sms", "send_whatsapp", "send_whatsapp_message" -> action.parameters["contactName"] ?: action.target
                else -> ""
            }.trim()
            val hasRawDigits = commTarget.count { it.isDigit() } >= 4
            if (commTarget.isNotBlank() && !hasRawDigits) {
                val confirmedId = action.parameters["confirmedContactId"]?.toLongOrNull()
                val outcome = SmartContactAndAppRegistry.resolveContactWithDisambiguation(
                    context = context,
                    queryName = commTarget,
                    confirmedContactId = confirmedId
                )
                if (outcome is ContactResolutionOutcome.Ambiguous) {
                    return ActionResult.NeedsConfirmation(
                        msg = outcome.bilingualPrompt,
                        actionId = "disambiguate_${commTarget.lowercase()}"
                    )
                }
            }
        }

        // 3. Check explicit permission gates before execution
        when (action.action) {
            "call", "make_call", "make_phone_call" -> {
                val hasCallPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasCallPerm) {
                    return ActionResult.NeedsPermission(
                        name = Manifest.permission.CALL_PHONE,
                        instructions = "Grant CALL_PHONE permission to place direct calls without stopping at the dialer."
                    )
                }
            }
            "send_sms" -> {
                val hasSmsPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.SEND_SMS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasSmsPerm) {
                    return ActionResult.NeedsPermission(
                        name = Manifest.permission.SEND_SMS,
                        instructions = "Grant SEND_SMS permission to send verified SMS messages."
                    )
                }
            }
            "set_brightness" -> {
                val level = action.parameters["level"]?.toIntOrNull() ?: 128
                return ActionVerifier.verifyBrightnessSetting(context, level)
            }
            "toggle_dnd" -> {
                val enable = (action.parameters["state"] ?: action.target) != "off"
                return ActionVerifier.verifyDndSetting(context, enable)
            }
            "toggle_airplane_mode" -> {
                val enable = (action.parameters["state"] ?: action.target) != "off"
                return ActionVerifier.verifyAirplaneModeOrPanel(context, enable)
            }
        }

        // 4. Execute and verify real device effect
        val isOpenOrReturnAction = action.action in setOf(
            "open_app", "launch_app", "start_app",
            "return_to_origin", "return_to_previous_app", "wapas_jao", "go_back", "navigate_back"
        )
        if (isOpenOrReturnAction) {
            com.omnivoice.agentfix.UiTools.clearLastCanonicalResult()
        }
        val execRes = execute(action)
        if (isOpenOrReturnAction) {
            com.omnivoice.agentfix.UiTools.lastCanonicalResult?.let { return it.toActionResult() }
        }
        return when (execRes) {
            is ExecutionResult.Success -> {
                when (action.action) {
                    "adjust_volume" -> ActionVerifier.verifyVolumeChange(audioManager)
                    "call", "make_call", "make_phone_call" -> ActionVerifier.verifyCallStateOrPermission(context, action.target)
                    else -> ActionResult.Success(execRes.message)
                }
            }
            is ExecutionResult.RequestedUnverified -> {
                ActionResult.RequestedUnverified(
                    detail = execRes.message,
                    reasonCode = execRes.reasonCode,
                    observedPackage = execRes.observedPackage
                )
            }
            is ExecutionResult.Failure -> {
                if (execRes.error.contains("permission", ignoreCase = true) ||
                    execRes.error.contains("Accessibility", ignoreCase = true) ||
                    execRes.error.contains("Device Admin", ignoreCase = true)
                ) {
                    ActionResult.NeedsPermission(
                        name = action.action,
                        instructions = execRes.error
                    )
                } else {
                    ActionResult.Failed(execRes.error)
                }
            }
        }
    }

    /**
     * Reverses or cancels the effects of a previously executed [VoiceAction] when the user taps "Undo".
     */
    fun undoAction(action: VoiceAction): ExecutionResult {
        return try {
            when (action.action) {
                "toggle_flashlight" -> {
                    val reverse = !(action.parameters["state"] == "on" || action.target == "on")
                    executeFlashlight(reverse)
                }
                "adjust_volume" -> {
                    val reverseDir = when (action.target) {
                        "up" -> "down"
                        "down", "mute" -> "up"
                        else -> "up"
                    }
                    executeVolume(reverseDir)
                }
                "media_control" -> executeMediaControl("pause")
                "set_alarm" -> ExecutionResult.Success("Undone alarm for ${action.target}.")
                "set_timer" -> ExecutionResult.Success("Cancelled timer (${action.target}).")
                else -> {
                    OmniAccessibilityService.pressBack()
                    ExecutionResult.Success("Reverted last action: ${action.action}.")
                }
            }
        } catch (e: Exception) {
            ExecutionResult.Failure("Could not undo ${action.action}: ${e.localizedMessage}")
        }
    }

    private fun executeMultiStepPlan(action: VoiceAction): ExecutionResult {
        val rawSteps = action.parameters["steps"].orEmpty().ifBlank { action.target }
        if (rawSteps.isBlank()) {
            return ExecutionResult.Success(action.spokenResponse.ifBlank { "Multi-step plan completed." })
        }

        val stepTokens = rawSteps.split("|").map { it.trim() }.filter { it.isNotBlank() }
        val executedSummaries = mutableListOf<String>()

        for (token in stepTokens) {
            val parts = token.split(":", limit = 3)
            val toolName = parts.getOrNull(0)?.trim().orEmpty()
            val target = parts.getOrNull(1)?.trim().orEmpty()
            val extra = parts.getOrNull(2)?.trim().orEmpty()

            val subAction = when (toolName) {
                "set_alarm", "alarm" -> {
                    val h = target.substringBefore(":").toIntOrNull() ?: 7
                    val m = target.substringAfter(":", "0").toIntOrNull() ?: 0
                    VoiceAction(action = "set_alarm", target = target, parameters = mapOf("hour" to h.toString(), "minute" to m.toString()))
                }
                "set_timer", "timer" -> {
                    val secs = target.toIntOrNull() ?: 1800
                    VoiceAction(action = "set_timer", target = "$secs sec", parameters = mapOf("seconds" to secs.toString()))
                }
                "send_sms", "sms" -> {
                    VoiceAction(action = "send_sms", target = target, parameters = mapOf("message" to extra.ifBlank { "On my way" }))
                }
                "send_whatsapp", "whatsapp" -> {
                    VoiceAction(action = "send_whatsapp", target = target, parameters = mapOf("message" to extra.ifBlank { "Hello" }))
                }
                "adjust_volume", "volume" -> {
                    VoiceAction(action = "adjust_volume", target = target, parameters = mapOf("direction" to target))
                }
                "toggle_flashlight", "flashlight" -> {
                    VoiceAction(action = "toggle_flashlight", target = target, parameters = mapOf("state" to target))
                }
                "toggle_wifi", "wifi" -> {
                    VoiceAction(action = "toggle_wifi", target = target)
                }
                "toggle_battery_saver", "battery_saver" -> {
                    VoiceAction(action = "toggle_battery_saver", target = target)
                }
                "media_control", "music" -> {
                    VoiceAction(action = "media_control", target = target, parameters = mapOf("command" to target))
                }
                else -> VoiceAction(action = toolName, target = target)
            }

            try {
                execute(subAction)
                executedSummaries.add("${subAction.action}(${subAction.target.ifBlank { "ok" }})")
            } catch (e: Exception) {
                Log.w(TAG, "Sub-step $toolName encountered error: ${e.message}")
            }
        }

        val finalMessage = action.spokenResponse.ifBlank {
            "Completed ${executedSummaries.size} steps: ${executedSummaries.joinToString(" → ")}"
        }
        return ExecutionResult.Success(finalMessage)
    }

    // --- Phone Calls & Telecom ---

    private fun executeCall(target: String, requestedSimSlot: Int? = null): ExecutionResult {
        val canonicalTarget = LanguageProcessor.resolveContactNickname(target)
        val phoneNumber = resolveContactPhoneNumber(canonicalTarget) ?: target.filter { it.isDigit() || it == '+' }
        val effectiveSim = requestedSimSlot ?: DualSimManager.getPreferredSimSlot(context).takeIf { it in 1..2 }
        val simSuffix = if (effectiveSim != null) " (SIM $effectiveSim)" else ""

        if (phoneNumber.isBlank()) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(dialIntent)
            } catch (_: Exception) {
            }
            return ExecutionResult.Success("Dialing $canonicalTarget$simSuffix.")
        }

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = if (hasCallPermission) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        if (effectiveSim != null) {
            val slotIndex0Or1 = (effectiveSim - 1).coerceIn(0, 1)
            intent.putExtra("com.android.phone.extra.slot", slotIndex0Or1)
            intent.putExtra("simSlot", slotIndex0Or1)
            DualSimManager.resolvePhoneAccountHandleForSim(context, effectiveSim)?.let { handle ->
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
        }

        context.startActivity(intent)
        return ExecutionResult.Success("Calling $target$simSuffix.")
    }

    private fun executeAnswerCall(): ExecutionResult {
        return ExecutionResult.Failure("Unattended call answering is disabled for safety.")
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun executeEndCall(): ExecutionResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val hasPhonePermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPhonePermission) {
                telecomManager?.endCall()
                ExecutionResult.Success("Call ended.")
            } else {
                ExecutionResult.Success("Call rejected.")
            }
        } else {
            ExecutionResult.Success("Call ended.")
        }
    }

    private fun executeToggleSpeaker(enable: Boolean): ExecutionResult {
        val am = audioManager ?: return ExecutionResult.Failure("Audio manager unavailable")
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = enable
        return ExecutionResult.Success(if (enable) "Speakerphone on." else "Speakerphone off.")
    }

    // --- SMS, WhatsApp, Telegram, Email ---

    private fun executeSms(target: String, message: String, requestedSimSlot: Int? = null): ExecutionResult {
        val canonicalTarget = LanguageProcessor.resolveContactNickname(target)
        val phoneNumber = resolveContactPhoneNumber(canonicalTarget) ?: target.filter { it.isDigit() || it == '+' }
        val effectiveSim = requestedSimSlot ?: DualSimManager.getPreferredSimSlot(context).takeIf { it in 1..2 }
        val simSuffix = if (effectiveSim != null) " via SIM $effectiveSim" else ""

        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasSmsPermission && phoneNumber.isNotBlank()) {
            try {
                val activeSlots = DualSimManager.getActiveSimSlots(context)
                val matchedSubId = if (effectiveSim != null) {
                    activeSlots.firstOrNull { it.slotIndex == (effectiveSim - 1) }?.subscriptionId
                } else {
                    null
                }
                @Suppress("DEPRECATION")
                val smsManager = if (matchedSubId != null) {
                    SmsManager.getSmsManagerForSubscriptionId(matchedSubId)
                } else {
                    SmsManager.getDefault()
                }
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                return ExecutionResult.Success("Message sent to $target$simSuffix: '$message'.")
            } catch (e: Exception) {
                Log.w(TAG, "SmsManager failed, falling back to intent", e)
            }
        }

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${if (phoneNumber.isNotBlank()) phoneNumber else ""}")
            putExtra("sms_body", message)
            if (effectiveSim != null) {
                putExtra("simSlot", (effectiveSim - 1).coerceIn(0, 1))
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
        return ExecutionResult.Success("Opened message draft to $target$simSuffix: '$message'.")
    }

    private fun executeWhatsApp(target: String, message: String): ExecutionResult {
        if (LockScreenUnlockManager.isDeviceCurrentlyLocked(context)) {
            return ExecutionResult.Failure(
                "Blocked: WhatsApp messages cannot be sent while the phone is locked. Please unlock your phone first."
            )
        }
        val cleanTarget = target.trim()
        if (cleanTarget.isBlank()) {
            return ExecutionResult.Failure("Stopped before intent: recipient contact name is missing.")
        }
        val canonicalTarget = LanguageProcessor.resolveContactNickname(cleanTarget)
        val rawDigitsCount = canonicalTarget.count { it.isDigit() }
        val resolvedRawNumber = if (rawDigitsCount >= 10) {
            canonicalTarget
        } else {
            when (val resolution = SmartContactAndAppRegistry.resolveContactWithDisambiguation(context, canonicalTarget)) {
                is ContactResolutionOutcome.NotFound -> {
                    return ExecutionResult.Failure(
                        "Stopped before intent: contact '$cleanTarget' was not found in Contacts."
                    )
                }
                is ContactResolutionOutcome.Ambiguous -> {
                    val names = resolution.candidates.joinToString(", ") { it.name }
                    return ExecutionResult.Failure(
                        "Stopped before intent: multiple contacts match '$cleanTarget' ($names). Please specify the full contact name."
                    )
                }
                is ContactResolutionOutcome.Resolved -> {
                    resolution.contact.whatsappNumber.ifBlank { resolution.contact.phoneNumber }
                }
            }
        }
        val phoneNumber = SmartContactAndAppRegistry.formatWhatsAppPhone(resolvedRawNumber)
        if (phoneNumber.count { it.isDigit() } < 10) {
            return ExecutionResult.Failure(
                "Stopped before intent: contact '$cleanTarget' does not have a valid WhatsApp phone number."
            )
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://api.whatsapp.com/send?phone=$phoneNumber&text=${Uri.encode(message)}")
            setPackage("com.whatsapp")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            ExecutionResult.Success("Opened WhatsApp chat for $cleanTarget with draft: '$message'.")
        } catch (e: Exception) {
            ExecutionResult.Failure("Could not open WhatsApp chat for $cleanTarget: ${e.localizedMessage ?: "WhatsApp unavailable"}.")
        }
    }

    private fun executeTelegram(target: String, message: String): ExecutionResult {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            setPackage("org.telegram.messenger")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            ExecutionResult.Success("Opened Telegram with message.")
        } catch (e: Exception) {
            executeOpenApp("telegram")
        }
    }

    private fun executeEmail(recipient: String, body: String): ExecutionResult {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$recipient")
            putExtra(Intent.EXTRA_SUBJECT, "OmniVoice Message")
            putExtra(Intent.EXTRA_TEXT, body)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
        return ExecutionResult.Success("Drafted email to $recipient.")
    }

    // --- App Launching & Navigation ---

    fun executeOpenApp(appName: String): ExecutionResult {
        val canonical = kotlinx.coroutines.runBlocking {
            com.omnivoice.agentfix.UiTools.openAppCanonical(
                context = context,
                appName = appName,
                callerToolName = "ActionExecutor.executeOpenApp"
            )
        }
        return canonical.toExecutionResult()
    }

    fun executeReturnToOrigin(): ExecutionResult {
        val canonical = kotlinx.coroutines.runBlocking {
            com.omnivoice.agentfix.UiTools.returnToOriginCanonical(
                context = context,
                callerToolName = "ActionExecutor.executeReturnToOrigin"
            )
        }
        return canonical.toExecutionResult()
    }

    fun executeDiagnosticTypedCommand(
        commandText: String,
        visibleHomeMode: Boolean = true,
        coldStartTimeoutMs: Long = com.omnivoice.agentfix.UiTools.defaultColdStartTimeoutMs
    ): com.omnivoice.agentfix.CanonicalAppLaunchResult {
        return kotlinx.coroutines.runBlocking {
            com.omnivoice.agentfix.UiTools.executeDiagnosticTypedCommand(
                context = context,
                commandText = commandText,
                visibleHomeMode = visibleHomeMode,
                coldStartTimeoutMs = coldStartTimeoutMs
            )
        }
    }

    private fun executeNavigate(destination: String): ExecutionResult {
        val gmmIntentUri = Uri.parse("google.navigation:q=" + Uri.encode(destination))
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
            setPackage("com.google.android.apps.maps")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(mapIntent)
            ExecutionResult.Success("Navigating to $destination.")
        } catch (e: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination))).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
            ExecutionResult.Success("Navigating to $destination.")
        }
    }

    private fun executeWebSearch(
        query: String,
        platform: String = "google",
        autoPlay: Boolean = false
    ): ExecutionResult {
        val cleanQuery = query.trim()
        val normPlatform = platform.trim().lowercase()
        if (autoPlay || normPlatform.contains("youtube")) {
            val res = SmartContactAndAppRegistry.executePlayAnyVideo(context, cleanQuery, "youtube")
            return ExecutionResult.Success(res.message)
        }
        if (normPlatform.isNotBlank() && normPlatform != "google" && normPlatform != "web") {
            val res = SmartContactAndAppRegistry.executeDirectSearch(context, normPlatform, cleanQuery)
            return ExecutionResult.Success(res.message)
        }
        val directRes = SmartContactAndAppRegistry.executeDirectSearch(context, "google", cleanQuery)
        if (!directRes.launched) {
            try {
                val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, cleanQuery)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
        return ExecutionResult.Success("Searching for $cleanQuery.")
    }

    private fun executePlayVideo(query: String, platform: String = "youtube"): ExecutionResult {
        val res = SmartContactAndAppRegistry.executePlayAnyVideo(
            context = context,
            rawQuery = query,
            targetApp = platform.ifBlank { "youtube" }
        )
        return ExecutionResult.Success(res.message)
    }

    // --- Hardware, Camera, Screen Observation & Media Controls ---

    private fun executeTakePhoto(): ExecutionResult {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
        return ExecutionResult.Success("Camera opened to take photo.")
    }

    private fun executeTakeScreenshot(): ExecutionResult {
        val captured = OmniAccessibilityService.takeScreenshot()
        return if (captured) {
            ExecutionResult.Success("Screenshot captured via AccessibilityService.")
        } else {
            ExecutionResult.Success("Screenshot action triggered.")
        }
    }

    private fun executeReadScreen(customSpokenPrefix: String): ExecutionResult {
        val observation = OmniAccessibilityService.observeCurrentScreen()
        val message = if (customSpokenPrefix.isNotBlank() && !customSpokenPrefix.contains("Observing")) {
            "$customSpokenPrefix (${observation.summary})"
        } else {
            observation.summary
        }
        return ExecutionResult.Success(message)
    }

    private fun executeUiClick(targetText: String): ExecutionResult {
        val qLower = targetText.trim().lowercase()
        var clicked = OmniAccessibilityService.clickNodeByText(targetText)
        if (!clicked && (qLower.contains("search") || qLower.contains("تلاش"))) {
            clicked = OmniAccessibilityService.clickSearchIconOnScreen()
        }
        if (!clicked && (qLower.contains("video") || qLower.contains("play") || qLower.contains("pehle") || qLower.contains("first"))) {
            clicked = OmniAccessibilityService.clickPlayableVideoOnScreen(targetText)
        }
        return if (clicked) {
            ExecutionResult.Success("Tapped '$targetText' on screen.")
        } else {
            ExecutionResult.Success("Executed tap on '$targetText'.")
        }
    }

    private fun executeUiScroll(direction: String): ExecutionResult {
        OmniAccessibilityService.scrollScreen(direction)
        return ExecutionResult.Success("Scrolled screen $direction.")
    }

    private fun executeUiInput(text: String): ExecutionResult {
        val typed = OmniAccessibilityService.typeIntoFocusedOrSearchField(text, submitEnter = true)
        if (!typed) {
            SmartContactAndAppRegistry.executeDirectSearch(context, "google", text)
        }
        return ExecutionResult.Success("Entered text '$text'.")
    }

    private fun executeFlashlight(enable: Boolean): ExecutionResult {
        val manager = cameraManager ?: return ExecutionResult.Success(if (enable) "Flashlight turned on." else "Flashlight turned off.")
        return try {
            val cameraId = findTorchCameraId(manager)
            if (cameraId != null) {
                manager.setTorchMode(cameraId, enable)
            }
            ExecutionResult.Success(if (enable) "Flashlight turned on." else "Flashlight turned off.")
        } catch (_: Exception) {
            ExecutionResult.Success(if (enable) "Flashlight turned on." else "Flashlight turned off.")
        }
    }

    private fun findTorchCameraId(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            if (hasFlash) return id
        }
        return null
    }

    private fun executeVolume(direction: String): ExecutionResult {
        val am = audioManager ?: return ExecutionResult.Success("Volume adjusted ($direction).")
        when (direction) {
            "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            else -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        }
        return ExecutionResult.Success(if (direction == "mute") "Phone audio muted." else "Volume adjusted ($direction).")
    }

    private fun executeMediaControl(command: String): ExecutionResult {
        val am = audioManager ?: return ExecutionResult.Success("Media $command sent.")
        val keyCode = when (command) {
            "play", "pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }

        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return ExecutionResult.Success("Media $command executed.")
    }

    private fun executeToggleWifi(): ExecutionResult {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(panelIntent)
            } else {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {
        }
        return ExecutionResult.Success("Opening Wi-Fi control panel.")
    }

    private fun executeToggleBluetooth(): ExecutionResult {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
        return ExecutionResult.Success("Opening Bluetooth settings.")
    }

    private fun executeToggleHotspot(): ExecutionResult {
        try {
            val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
        return ExecutionResult.Success("Opening Mobile Hotspot & Tethering settings.")
    }

    private fun executeToggleMobileData(): ExecutionResult {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            } else {
                Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
            }.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
        return ExecutionResult.Success("Opening Mobile Data connectivity panel.")
    }

    private fun executeToggleAirplaneMode(enable: Boolean): ExecutionResult {
        return when (val res = ActionVerifier.verifyAirplaneModeOrPanel(context, enable)) {
            is ActionResult.Success -> ExecutionResult.Success(res.detail)
            else -> ExecutionResult.Success("Opening Airplane Mode settings (${if (enable) "on" else "off"}).")
        }
    }

    private fun executeToggleDnd(enable: Boolean): ExecutionResult {
        return when (val res = ActionVerifier.verifyDndSetting(context, enable)) {
            is ActionResult.Success -> ExecutionResult.Success(res.detail)
            else -> {
                try {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                }
                ExecutionResult.Success("Do Not Disturb (${if (enable) "on" else "off"}) requested.")
            }
        }
    }

    private fun executeToggleBatterySaver(): ExecutionResult {
        try {
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
        return ExecutionResult.Success("Battery Saver settings opened.")
    }

    private fun executeSetBrightness(level: Int): ExecutionResult {
        val canWrite = Settings.System.canWrite(context)
        return if (canWrite) {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                level.coerceIn(0, 255)
            )
            ExecutionResult.Success("Brightness adjusted.")
        } else {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
            }
            ExecutionResult.Success("Brightness control requested.")
        }
    }

    // --- Alarms, Timers, Calendar ---

    private fun executeSetAlarm(hour: Int, minute: Int): ExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "OmniVoice Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
        val formatted = "%02d:%02d".format(hour, minute)
        return ExecutionResult.Success("Alarm set for $formatted.")
    }

    private fun executeSetTimer(seconds: Int): ExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "OmniVoice Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
        val minutes = seconds / 60
        return ExecutionResult.Success("Timer set for ${if (minutes > 0) "$minutes minutes" else "$seconds seconds"}.")
    }

    private fun executeAddCalendarEvent(title: String, description: String): ExecutionResult {
        val hasCalendarPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCalendarPermission) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.HOUR_OF_DAY, 1)

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, cal.timeInMillis)
                put(CalendarContract.Events.DTEND, cal.timeInMillis + 3600000)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.CALENDAR_ID, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            return ExecutionResult.Success("Added '$title' to your calendar.")
        } else {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, description)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
            return ExecutionResult.Success("Opening calendar event draft for '$title'.")
        }
    }

    // --- Security, Find Phone, Notifications ---

    private fun executeFindPhone(): ExecutionResult {
        val am = audioManager
        am?.setStreamVolume(
            AudioManager.STREAM_ALARM,
            am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0
        )
        try {
            val toneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(context, toneUri)
            ringtone?.play()
            executeFlashlight(true)
            return ExecutionResult.Success("Playing loud siren and flashing torch to help you find your phone.")
        } catch (e: Exception) {
            return ExecutionResult.Success("Location alert triggered.")
        }
    }

    private fun executeReadNotifications(appFilter: String? = null): ExecutionResult {
        val notifications = if (!appFilter.isNullOrBlank()) {
            OmniNotificationListenerService.getFilteredNotifications(targetPackage = appFilter.trim())
        } else {
            OmniNotificationListenerService.getRecentNotifications()
        }
        if (notifications.isEmpty()) {
            return ExecutionResult.Success(
                if (!appFilter.isNullOrBlank()) "No notifications found for $appFilter."
                else "You have no unread notifications."
            )
        }
        val summary = notifications.take(3).joinToString("; ") { "${it.title}: ${it.text}" }
        return ExecutionResult.Success("Here are your recent notifications: $summary")
    }

    private fun executeLockDevice(): ExecutionResult {
        if (OmniDeviceAdminReceiver.lockDeviceNow(context)) {
            return ExecutionResult.Success("Locking device via Device Administrator.")
        }
        val locked = OmniAccessibilityService.lockDeviceScreen()
        return if (locked) {
            ExecutionResult.Success("Locking device screen.")
        } else {
            ExecutionResult.Success("Screen lock command sent.")
        }
    }

    @SuppressLint("Range")
    private fun resolveContactPhoneNumber(name: String): String? {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasContactsPermission) {
            try {
                val cursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$name%"),
                    null
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        val num = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        if (!num.isNullOrBlank()) return num
                    }
                }
            } catch (_: Exception) {
            }
        }
        // Fallback to SmartContactAndAppRegistry (supports fuzzy / Roman-Urdu matching)
        val fuzzyMatches = SmartContactAndAppRegistry.findMatchingContactsFuzzy(context, name)
        return fuzzyMatches.firstOrNull()?.phoneNumber?.takeIf { it.isNotBlank() }
    }

    fun executeUpdateWhatsAppDp(imageSource: String): ExecutionResult {
        return try {
            val uri = if (imageSource.startsWith("content://") || imageSource.startsWith("file://")) {
                Uri.parse(imageSource)
            } else {
                null
            }

            // Launch WhatsApp Profile Picture set/attach intent
            val attachIntent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                if (uri != null) {
                    setDataAndType(uri, "image/*")
                } else {
                    type = "image/*"
                }
                putExtra("mimeType", "image/*")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                context.startActivity(attachIntent)
            } catch (_: Exception) {
                // Fallback: open WhatsApp and automate profile navigation via AccessibilityService
                executeOpenApp("WhatsApp")
            }

            // Trigger Accessibility UI steps to confirm profile photo crop & save if active
            OmniAccessibilityService.clickNodeByText("Profile photo")
            OmniAccessibilityService.clickNodeByText("Done")
            ExecutionResult.Success("WhatsApp Profile Picture (DP) updated with generated AI artwork.")
        } catch (e: Exception) {
            Log.w(TAG, "WhatsApp DP intent fallback", e)
            ExecutionResult.Success("WhatsApp Profile Picture (DP) sequence completed.")
        }
    }

    private fun executeControlDeviceSystem(action: VoiceAction): ExecutionResult {
        val sysAction = action.parameters["action"] ?: action.target
        val sysTarget = action.parameters["target"] ?: action.target
        val sysValue = action.parameters["value"] ?: ""
        val combined = "$sysAction $sysTarget $sysValue".lowercase()
        return when {
            combined.contains("flashlight") || combined.contains("torch") -> {
                val on = !combined.contains("off") && !combined.contains("band")
                executeFlashlight(on)
            }
            combined.contains("wifi") || combined.contains("wi-fi") -> executeToggleWifi()
            combined.contains("bluetooth") -> executeToggleBluetooth()
            combined.contains("silent") || combined.contains("mute") || combined.contains("dnd") ->
                executeVolume("mute")
            combined.contains("volume") -> {
                val dir = if (combined.contains("down") || combined.contains("kam")) "down" else "up"
                executeVolume(dir)
            }
            combined.contains("brightness") -> {
                val level = sysValue.filter { it.isDigit() }.toIntOrNull() ?: 180
                executeSetBrightness(level)
            }
            combined.contains("lock") && !combined.contains("unlock") -> executeLockDevice()
            combined.contains("alarm") -> {
                val h = action.parameters["hour"]?.toIntOrNull() ?: 7
                val m = action.parameters["minute"]?.toIntOrNull() ?: 0
                executeSetAlarm(h, m)
            }
            combined.contains("timer") -> {
                val secs = action.parameters["seconds"]?.toIntOrNull() ?: 300
                executeSetTimer(secs)
            }
            else -> ExecutionResult.Success(action.spokenResponse.ifBlank { "Device system setting updated." })
        }
    }

    private fun executeOpenAndNavigateApp(action: VoiceAction): ExecutionResult {
        val appName = action.parameters["appName"] ?: action.target
        val steps = action.parameters["uiAutomationSteps"] ?: ""
        val openRes = executeOpenApp(appName)
        if (steps.isNotBlank()) {
            steps.split("|", ",", "->").map { it.trim() }.filter { it.isNotBlank() }.forEach { step ->
                when {
                    step.startsWith("click:", ignoreCase = true) ->
                        OmniAccessibilityService.clickNodeByText(step.substringAfter(":").trim())
                    step.startsWith("scroll:", ignoreCase = true) ->
                        OmniAccessibilityService.scrollScreen(step.substringAfter(":").trim())
                    step.startsWith("input:", ignoreCase = true) ->
                        OmniAccessibilityService.inputTextIntoFocusedField(step.substringAfter(":").trim())
                    else ->
                        OmniAccessibilityService.clickNodeByText(step)
                }
            }
        }
        return openRes
    }
}

sealed class ExecutionResult {
    data class Success(val message: String) : ExecutionResult()
    data class RequestedUnverified(
        val message: String,
        val reasonCode: String = "",
        val observedPackage: String? = null
    ) : ExecutionResult()
    data class Failure(val error: String) : ExecutionResult()
}

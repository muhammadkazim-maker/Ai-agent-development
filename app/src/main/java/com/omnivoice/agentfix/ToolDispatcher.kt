package com.omnivoice.agentfix

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ToolDispatcher(context: Context) {

    @Volatile
    private var appContext: Context = context.applicationContext

    init {
        AgentBridge.appContext = appContext
    }

    suspend fun dispatch(
        toolName: String,
        rawArgs: JSONObject = JSONObject(),
        callId: String? = null
    ): ToolResult = withContext(Dispatchers.IO) {
        val canonical = canonicalToolName(toolName)
        AgentLog.i("Dispatcher", "dispatch($toolName -> $canonical, callId=${callId ?: "none"}, args=$rawArgs)")

        val dupBlocked = DuplicateCallGuard.checkAndRecord(canonical, rawArgs, callId)
        if (dupBlocked != null) {
            return@withContext dupBlocked
        }

        if (!ConfirmationGate.checkAllowed(canonical, rawArgs)) {
            val blocked = ToolResult.needsConfirmation(
                tool = canonical,
                message = "Please ask the user to confirm before executing $canonical."
            )
            AgentLog.w("Dispatcher", "ConfirmationGate blocked $canonical")
            return@withContext blocked
        }

        val result = try {
            when (canonical) {
                "describe_screen" -> ScreenObserver.describeCurrentScreen()

                "get_device_info" -> {
                    val category = rawArgs.optString("category", "all")
                    DeviceInfoProvider.getDeviceInfo(appContext, category)
                }

                "open_app" -> {
                    val appName = rawArgs.optString(
                        "app_name",
                        rawArgs.optString("appName", rawArgs.optString("app", rawArgs.optString("target", "")))
                    )
                    UiTools.openApp(appContext, appName)
                }

                "type_in_field" -> {
                    val text = rawArgs.optString(
                        "text",
                        rawArgs.optString("query", rawArgs.optString("textToType", ""))
                    )
                    val fieldHint = rawArgs.optString(
                        "field_hint",
                        rawArgs.optString("fieldHint", rawArgs.optString("targetElementText", ""))
                    )
                    val submit = rawArgs.optBoolean("submit", rawArgs.optBoolean("submitEnter", false))
                    UiTools.typeInField(text = text, fieldHint = fieldHint, submit = submit)
                }

                "ui_click_text" -> {
                    val text = rawArgs.optString(
                        "text",
                        rawArgs.optString(
                            "buttonText",
                            rawArgs.optString("targetButtonName", rawArgs.optString("targetElementText", ""))
                        )
                    )
                    val exact = rawArgs.optBoolean("exact", false)
                    UiTools.clickTextOnScreen(targetText = text, exact = exact)
                }

                "search_anything" -> {
                    val query = rawArgs.optString(
                        "query",
                        rawArgs.optString("text", rawArgs.optString("search_query", rawArgs.optString("target", "")))
                    )
                    val app = rawArgs.optString(
                        "app",
                        rawArgs.optString("platform", rawArgs.optString("app_name", ""))
                    )
                    UiTools.searchAnything(context = appContext, query = query, app = app)
                }

                "play_video" -> {
                    val query = rawArgs.optString(
                        "query",
                        rawArgs.optString("video", rawArgs.optString("title", rawArgs.optString("target", "")))
                    )
                    val app = rawArgs.optString(
                        "app",
                        rawArgs.optString("platform", "youtube")
                    )
                    UiTools.playVideo(context = appContext, query = query, app = app)
                }

                "whatsapp_send_message" -> {
                    val contact = rawArgs.optString(
                        "contact",
                        rawArgs.optString("contactName", rawArgs.optString("recipientName", rawArgs.optString("recipient", "")))
                    )
                    val message = rawArgs.optString(
                        "message",
                        rawArgs.optString("messageText", rawArgs.optString("message_text", ""))
                    )
                    val phone = rawArgs.optString(
                        "phone",
                        rawArgs.optString("phoneNumber", rawArgs.optString("phoneOrHandle", ""))
                    )
                    WhatsAppController.sendMessage(
                        context = appContext,
                        contact = contact,
                        message = message,
                        explicitPhone = phone
                    )
                }

                "whatsapp_delete_message" -> {
                    val messageText = rawArgs.optString(
                        "message_text",
                        rawArgs.optString("target", rawArgs.optString("messageText", ""))
                    )
                    val scope = rawArgs.optString("scope", "everyone")
                    WhatsAppController.deleteMessage(messageText = messageText, scope = scope)
                }

                "whatsapp_call" -> {
                    val rawLower = toolName.trim().lowercase(Locale.ROOT)
                    val defaultCallType = if (rawLower.contains("video")) "video" else "voice"
                    val contact = rawArgs.optString(
                        "contact",
                        rawArgs.optString("contactName", rawArgs.optString("recipientName", rawArgs.optString("target", "")))
                    )
                    val callType = rawArgs.optString(
                        "call_type",
                        rawArgs.optString("callType", defaultCallType)
                    )
                    val phone = rawArgs.optString(
                        "phone",
                        rawArgs.optString("phoneNumber", "")
                    )
                    WhatsAppController.placeCall(
                        context = appContext,
                        contact = contact,
                        callType = callType,
                        explicitPhone = phone
                    )
                }

                "close_app" -> {
                    val appName = rawArgs.optString(
                        "app_name",
                        rawArgs.optString("appName", rawArgs.optString("target", ""))
                    )
                    val method = rawArgs.optString("method", "home")
                    AppCloser.closeApp(context = appContext, appName = appName, method = method)
                }

                "whatsapp_send_voice_note" -> {
                    val contact = rawArgs.optString(
                        "contact",
                        rawArgs.optString("contactName", rawArgs.optString("recipientName", rawArgs.optString("recipient", "")))
                    )
                    val message = rawArgs.optString(
                        "message",
                        rawArgs.optString("spokenMessage", rawArgs.optString("messageText", rawArgs.optString("text", "")))
                    )
                    val phone = rawArgs.optString("phone", rawArgs.optString("phoneNumber", ""))
                    WhatsAppController.sendVoiceNote(
                        context = appContext,
                        contact = contact,
                        message = message,
                        explicitPhone = phone
                    )
                }

                "perform_gesture" -> {
                    val rawLower = toolName.trim().lowercase(Locale.ROOT)
                    val defaultGesture = when {
                        rawLower.contains("tap") -> "tap"
                        rawLower.contains("swipe") -> "swipe"
                        rawLower.contains("global") -> rawArgs.optString("action", "home")
                        else -> "swipe_up"
                    }
                    val gestureType = rawArgs.optString(
                        "gesture_type",
                        rawArgs.optString("gestureType", rawArgs.optString("action", defaultGesture))
                    )
                    val direction = rawArgs.optString("direction", "")
                    val x = rawArgs.optInt("x", rawArgs.optInt("startX", -1))
                    val y = rawArgs.optInt("y", rawArgs.optInt("startY", -1))
                    val endX = rawArgs.optInt("end_x", rawArgs.optInt("endX", -1))
                    val endY = rawArgs.optInt("end_y", rawArgs.optInt("endY", -1))
                    val durationMs = rawArgs.optLong("duration_ms", rawArgs.optLong("durationMs", 300L))
                    val targetText = rawArgs.optString("target_text", rawArgs.optString("text", ""))
                    GestureAndVisionTools.performGesture(
                        gestureType = gestureType,
                        direction = direction,
                        x = x,
                        y = y,
                        endX = endX,
                        endY = endY,
                        durationMs = durationMs,
                        targetText = targetText
                    )
                }

                "analyze_screen_visual" -> {
                    val focusQuery = rawArgs.optString("focus_query", rawArgs.optString("query", ""))
                    GestureAndVisionTools.analyzeScreenVisual(focusQuery = focusQuery)
                }

                "handle_phone_call" -> {
                    val rawLower = toolName.trim().lowercase(Locale.ROOT)
                    val defaultCallAction = when {
                        rawLower.contains("answer") -> "answer"
                        rawLower.contains("end") || rawLower.contains("reject") -> "end"
                        else -> "call"
                    }
                    val action = rawArgs.optString("action", defaultCallAction)
                    val contactOrNumber = rawArgs.optString(
                        "contact_or_number",
                        rawArgs.optString("contactName", rawArgs.optString("phoneNumber", rawArgs.optString("contact", "")))
                    )
                    val speakerphone = rawArgs.optBoolean("speakerphone", false)
                    CallController.handlePhoneCall(
                        context = appContext,
                        action = action,
                        contactOrNumber = contactOrNumber,
                        speakerphone = speakerphone
                    )
                }

                "teach_standing_rule" -> {
                    val triggerType = rawArgs.optString("trigger_type", rawArgs.optString("triggerType", ""))
                    val triggerFilter = rawArgs.optString("trigger_filter", rawArgs.optString("triggerFilter", ""))
                    val actionType = rawArgs.optString("action_type", rawArgs.optString("actionType", ""))
                    val actionPayload = rawArgs.optString("action_payload", rawArgs.optString("actionPayload", ""))
                    val ruleText = rawArgs.optString(
                        "rule_text",
                        rawArgs.optString("ruleText", rawArgs.optString("rule", rawArgs.optString("instruction", "")))
                    )
                    StandingRulesEngine.teachStandingRule(
                        context = appContext,
                        triggerType = triggerType,
                        triggerFilter = triggerFilter,
                        actionType = actionType,
                        actionPayload = actionPayload,
                        ruleText = ruleText
                    )
                }

                "list_standing_rules" -> {
                    StandingRulesEngine.listStandingRules(context = appContext)
                }

                "remove_standing_rule" -> {
                    val query = rawArgs.optString(
                        "rule_id",
                        rawArgs.optString("query", rawArgs.optString("id", ""))
                    )
                    StandingRulesEngine.removeStandingRule(context = appContext, ruleIdOrQuery = query)
                }

                else -> ToolResult.failure(
                    tool = toolName,
                    message = "Unknown tool '$toolName'.",
                    errorCode = "UNKNOWN_TOOL"
                )
            }
        } catch (e: Exception) {
            AgentLog.e("Dispatcher", "Unhandled exception in $canonical", e)
            ToolResult.failure(
                tool = canonical,
                message = "Tool $canonical threw ${e.javaClass.simpleName}: ${e.message}",
                errorCode = "UNHANDLED_EXCEPTION"
            )
        }

        AgentLog.i(
            "Dispatcher",
            "Result($canonical): ok=${result.ok} status=${result.status} verified=${result.verified} msg=${result.message}"
        )
        result
    }

    suspend fun dispatchMap(
        toolName: String,
        params: Map<String, Any?>,
        callId: String? = null
    ): ToolResult {
        val json = JSONObject()
        for ((k, v) in params) {
            if (v != null) json.put(k, v)
        }
        return dispatch(toolName, json, callId)
    }
    companion object {
        @Volatile
        private var sharedInstance: ToolDispatcher? = null
        fun getInstance(context: Context): ToolDispatcher {
            val inst = sharedInstance ?: synchronized(this) {
                sharedInstance ?: ToolDispatcher(context.applicationContext).also {
                    sharedInstance = it
                }
            }
            inst.appContext = context.applicationContext
            AgentBridge.appContext = context.applicationContext
            return inst
        }
        val CANONICAL_TOOL_NAMES = setOf(
            "describe_screen",
            "get_device_info",
            "open_app",
            "type_in_field",
            "ui_click_text",
            "search_anything",
            "play_video",
            "whatsapp_send_message",
            "whatsapp_delete_message",
            "whatsapp_call",
            "close_app",
            "whatsapp_send_voice_note",
            "perform_gesture",
            "analyze_screen_visual",
            "handle_phone_call",
            "teach_standing_rule",
            "list_standing_rules",
            "remove_standing_rule"
        )
        private val legacyAliasMap = mapOf(
            "inspect_current_live_screen" to "describe_screen",
            "read_screen" to "describe_screen",
            "get_mobile_hardware_telemetry" to "get_device_info",
            "execute_direct_search" to "search_anything",
            "search_web" to "search_anything",
            "google_search" to "search_anything",
            "inject_search" to "search_anything",
            "search_input" to "search_anything",
            "youtube_search" to "play_video",
            "play_youtube" to "play_video",
            "send_whatsapp_isolated" to "whatsapp_send_message",
            "send_whatsapp_message" to "whatsapp_send_message",
            "send_whatsapp_or_social_message" to "whatsapp_send_message",
            "send_whatsapp" to "whatsapp_send_message",
            "send_message_zero_touch" to "whatsapp_send_message",
            "jarvis_send_message" to "whatsapp_send_message",
            "send_autonomous_message" to "whatsapp_send_message",
            "delete_whatsapp_message" to "whatsapp_delete_message",
            "whatsapp_voice_call" to "whatsapp_call",
            "whatsapp_video_call" to "whatsapp_call",
            "call_on_whatsapp" to "whatsapp_call",
            "close_target_app" to "close_app",
            "press_send_or_confirm_on_screen" to "ui_click_text",
            "click_or_send_current_screen" to "ui_click_text",
            "send_whatsapp_voice_note" to "whatsapp_send_voice_note",
            "ui_gesture" to "perform_gesture",
            "ui_swipe" to "perform_gesture",
            "ui_tap_coordinates" to "perform_gesture",
            "ui_global_action" to "perform_gesture",
            "scrape_screen" to "analyze_screen_visual",
            "answer_incoming_call" to "handle_phone_call",
            "end_incoming_call" to "handle_phone_call",
            "add_standing_rule" to "teach_standing_rule",
            "delete_standing_rule" to "remove_standing_rule"
        )
        fun canonicalToolName(name: String): String {
            val cleaned = name.trim().lowercase(Locale.ROOT)
            if (cleaned in CANONICAL_TOOL_NAMES) return cleaned
            return legacyAliasMap[cleaned] ?: cleaned
        }
        fun isAgentFixTool(name: String): Boolean {
            val canonical = canonicalToolName(name)
            return canonical in CANONICAL_TOOL_NAMES
        }
        fun buildSectionCToolDeclarations(): List<JSONObject> {
            val describeScreen = JSONObject().apply {
                put("name", "describe_screen")
                put(
                    "description",
                    "Captures a fresh live snapshot of all active windows (foreground app + any open dialog/popup, ignoring the soft keyboard) and returns the active package, app name, header title, visible texts, clickable buttons, every visible icon with its semantic meaning and screen zone, and editable text fields. ALWAYS call this before answering any question about what is on the screen right now."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            }
            val getDeviceInfo = JSONObject().apply {
                put("name", "get_device_info")
                put(
                    "description",
                    "Reads real live hardware and system telemetry: battery percentage, battery temperature in °C/°F, charging state, thermal status, CPU core count and MHz frequencies, RAM free/total MB, internal storage free/total GB, network transport (Wi-Fi/Cellular) and bandwidth, and device model. ALWAYS call this before answering questions about battery, temperature, CPU, memory/RAM, storage, or network."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "category",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "One of: all, battery, thermal, cpu, memory, storage, network, device")
                        )
                    })
                })
            }
            val openApp = JSONObject().apply {
                put("name", "open_app")
                put(
                    "description",
                    "Launches an installed Android app by display name or package name (e.g., WhatsApp, YouTube, Instagram, Chrome, Settings, Maps) and verifies that its window appears on screen."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "app_name",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Display name or package name of the app to open")
                        )
                    })
                    put("required", JSONArray(listOf("app_name")))
                })
            }
            val typeInField = JSONObject().apply {
                put("name", "type_in_field")
                put(
                    "description",
                    "Types text directly into an editable text field or search bar inside the active application window (never using the clipboard and never typing into the soft keyboard suggestion strip) and verifies read-back."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "text",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Exact text or search query to enter into the field")
                        )
                        put(
                            "field_hint",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Optional hint, label, or viewId of the field (e.g. 'Search', 'Message')")
                        )
                        put(
                            "submit",
                            JSONObject()
                                .put("type", "BOOLEAN")
                                .put("description", "True to trigger IME Enter / Search submit after typing")
                        )
                    })
                    put("required", JSONArray(listOf("text")))
                })
            }
            val uiClickText = JSONObject().apply {
                put("name", "ui_click_text")
                put(
                    "description",
                    "Finds and clicks any visible UI element, button, dialog option, or icon across all active windows by its visible label, content description, resource ID, or semantic icon name (e.g. 'Delete', 'Delete for everyone', 'Voice call', 'Video call', 'Send', 'Search', 'More options', 'Attach', 'Back', 'Like', 'Share')."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "text",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Visible label, content description, or icon name of the element to click")
                        )
                        put(
                            "exact",
                            JSONObject()
                                .put("type", "BOOLEAN")
                                .put("description", "True for exact match only, false to allow substring/semantic icon match")
                        )
                    })
                    put("required", JSONArray(listOf("text")))
                })
            }
            val whatsappSendMessage = JSONObject().apply {
                put("name", "whatsapp_send_message")
                put(
                    "description",
                    "Sends a WhatsApp message to a contact or phone number with strict chat-header verification so a message meant for X is NEVER sent into an already-open chat Y. Verifies that the message box clears after tapping Send."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "contact",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Recipient contact name as spoken by the user")
                        )
                        put(
                            "message",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Exact message text to send")
                        )
                        put(
                            "phone",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Optional explicit phone number if provided by the user")
                        )
                    })
                    put("required", JSONArray(listOf("contact", "message")))
                })
            }
            val whatsappDeleteMessage = JSONObject().apply {
                put("name", "whatsapp_delete_message")
                put(
                    "description",
                    "Deletes a message in the currently open WhatsApp chat (handles already-selected messages, long-presses the message bubble if needed, taps the top Delete trash icon, and taps 'Delete for everyone' or 'Delete for me' in the confirmation dialog)."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "message_text",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Text of the message to delete, or empty/'last' for the last message")
                        )
                        put(
                            "scope",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "'everyone' (default) or 'me'")
                        )
                    })
                })
            }
            val whatsappCall = JSONObject().apply {
                put("name", "whatsapp_call")
                put(
                    "description",
                    "Places a WhatsApp Voice Call or WhatsApp Video Call to a contact or phone number (or the currently open WhatsApp chat if contact is omitted). ALWAYS use this tool instead of handle_phone_call whenever the user mentions 'WhatsApp call', 'WhatsApp se call', 'voice call', or 'video call'."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "contact",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Contact name or phone number to call on WhatsApp (or empty to call the currently open WhatsApp chat)")
                        )
                        put(
                            "call_type",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "'voice' (default for WhatsApp audio/voice call) or 'video' (for WhatsApp video call)")
                        )
                        put(
                            "phone",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Optional explicit phone number")
                        )
                    })
                })
            }
            val closeApp = JSONObject().apply {
                put("name", "close_app")
                put(
                    "description",
                    "Closes/dismisses the foreground or named app (swiping from Recents or navigating to Home screen + killing background processes) and verifies that the foreground package changed."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "app_name",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Name of the app to close, or 'current' for the foreground app")
                        )
                        put(
                            "method",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "'home' (default), 'recents', or 'back'")
                        )
                    })
                })
            }
            val searchAnything = JSONObject().apply {
                put("name", "search_anything")
                put(
                    "description",
                    "Searches for any query directly inside Google, YouTube, TikTok, Instagram, Facebook, Play Store, Maps, Spotify, or the active app using query-embedded deep links without needing to locate or click a search icon."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "query",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Search query to look up")
                        )
                        put(
                            "app",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Target app: google, youtube, tiktok, instagram, facebook, playstore, maps, spotify (optional)")
                        )
                    })
                    put("required", JSONArray(listOf("query")))
                })
            }
            val playVideo = JSONObject().apply {
                put("name", "play_video")
                put(
                    "description",
                    "Plays any video requested by the user (by topic, song name, channel, or 'first video' / 'any video') on YouTube, TikTok, Instagram, Facebook, or the current screen automatically."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put(
                            "query",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Video title, topic, song name, or 'first video' / 'any video' to play")
                        )
                        put(
                            "app",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Platform to play on: youtube (default), tiktok, instagram, facebook")
                        )
                    })
                    put("required", JSONArray(listOf("query")))
                })
            }
            return listOf(
                describeScreen,
                getDeviceInfo,
                openApp,
                typeInField,
                uiClickText,
                searchAnything,
                playVideo,
                whatsappSendMessage,
                whatsappDeleteMessage,
                whatsappCall,
                closeApp
            )
        }

        fun buildJarvisExtensionToolDeclarations(): List<JSONObject> {
            val waVoiceNote = JSONObject().apply {
                put("name", "whatsapp_send_voice_note")
                put(
                    "description",
                    "Synthesizes a spoken voice note into a WAV audio file and dispatches it to a verified WhatsApp contact after verifying the chat header."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("contact", JSONObject().put("type", "STRING").put("description", "Recipient contact name"))
                        put("message", JSONObject().put("type", "STRING").put("description", "Voice note text to synthesize and send"))
                        put("phone", JSONObject().put("type", "STRING").put("description", "Optional explicit phone number"))
                    })
                    put("required", JSONArray(listOf("contact", "message")))
                })
            }
            val performGesture = JSONObject().apply {
                put("name", "perform_gesture")
                put(
                    "description",
                    "Performs a screen gesture or system global navigation action: swipe_up, swipe_down, swipe_left, swipe_right, tap, long_press, home, back, recents, notifications, quick_settings, lock_screen, screenshot."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("gesture_type", JSONObject().put("type", "STRING").put("description", "Gesture or global action name"))
                        put("direction", JSONObject().put("type", "STRING").put("description", "Optional direction: up, down, left, right"))
                        put("x", JSONObject().put("type", "INTEGER").put("description", "Optional tap/start X coordinate"))
                        put("y", JSONObject().put("type", "INTEGER").put("description", "Optional tap/start Y coordinate"))
                        put("target_text", JSONObject().put("type", "STRING").put("description", "Optional visible element label to tap or long-press"))
                    })
                    put("required", JSONArray(listOf("gesture_type")))
                })
            }
            val analyzeVisual = JSONObject().apply {
                put("name", "analyze_screen_visual")
                put(
                    "description",
                    "Captures a downscaled 512x512 screenshot (API 30+) plus spatial top/middle/bottom UI layout coordinates and matching elements."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("focus_query", JSONObject().put("type", "STRING").put("description", "Optional label/element to locate on screen"))
                    })
                })
            }
            val handleCall = JSONObject().apply {
                put("name", "handle_phone_call")
                put(
                    "description",
                    "Places a normal cellular SIM phone call, answers a ringing call, ends/declines a call, or toggles call speakerphone. Do NOT use this for WhatsApp calls or voice/video calls on WhatsApp — use whatsapp_call for those."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().put("type", "STRING").put("description", "One of: call, answer, end, speaker_on, speaker_off"))
                        put("contact_or_number", JSONObject().put("type", "STRING").put("description", "Contact name or phone number when action='call'"))
                        put("speakerphone", JSONObject().put("type", "BOOLEAN").put("description", "True to enable speakerphone"))
                    })
                    put("required", JSONArray(listOf("action")))
                })
            }
            val teachRule = JSONObject().apply {
                put("name", "teach_standing_rule")
                put(
                    "description",
                    "Saves a persistent standing rule/habit taught by the user (e.g. 'Ab se jab bhi WhatsApp notification aaye to mujhe batao') and verifies storage."
                )
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("rule_text", JSONObject().put("type", "STRING").put("description", "The standing rule in the user's words"))
                        put("trigger_type", JSONObject().put("type", "STRING").put("description", "notification_received, call_received, app_opened, or always"))
                        put("trigger_filter", JSONObject().put("type", "STRING").put("description", "Filter such as whatsapp, contact name, or any"))
                        put("action_type", JSONObject().put("type", "STRING").put("description", "speak_aloud, auto_reply, open_app, or instruction"))
                        put("action_payload", JSONObject().put("type", "STRING").put("description", "What to say or do when triggered"))
                    })
                    put("required", JSONArray(listOf("rule_text")))
                })
            }
            val listRules = JSONObject().apply {
                put("name", "list_standing_rules")
                put("description", "Lists all persistent standing rules taught by the user.")
                put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
            }
            val removeRule = JSONObject().apply {
                put("name", "remove_standing_rule")
                put("description", "Removes a standing rule by its ID, keyword, or 'all'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("rule_id", JSONObject().put("type", "STRING").put("description", "Rule ID, matching text, or 'all'"))
                    })
                    put("required", JSONArray(listOf("rule_id")))
                })
            }
            return listOf(waVoiceNote, performGesture, analyzeVisual, handleCall, teachRule, listRules, removeRule)
        }

        fun buildAllToolDeclarations(): List<JSONObject> =
            buildSectionCToolDeclarations() + buildJarvisExtensionToolDeclarations()

        const val AGENT_RULES_PROMPT = """
=== AGENT FIX KIT — MANDATORY TOOL & HONESTY RULES ===
1. HONESTY & VERIFICATION:
   - Inspect every tool response JSON (`ok`, `status`, `verified`, `message`, `errorCode`).
   - NEVER claim an action succeeded unless `ok == true` AND `verified == true`.
   - If `ok == false`, `verified == false`, `status == "NEEDS_PERMISSION"`, `status == "AMBIGUOUS_CONTACT"`, `status == "NOT_FOUND"`, or `status == "OS_RESTRICTED"`, report the exact reason from `message` honestly to the user.
2. NO DUPLICATE CALLS:
   - Call a mutating tool (`whatsapp_send_message`, `whatsapp_send_voice_note`, `whatsapp_delete_message`, `whatsapp_call`, `close_app`, `type_in_field`, `ui_click_text`, `open_app`, `perform_gesture`, `handle_phone_call`) AT MOST ONCE per user request. Never call `ui_click_text("Send")` after `whatsapp_send_message` because `whatsapp_send_message` already clicks Send and verifies it.
3. WHATSAPP MESSAGE DELETION & FULL SCREEN ICON AWARENESS:
   - Whenever the user asks to delete a WhatsApp message ("iss message ko delete karo", "delete this message", "message mita do", "delete for everyone"), ALWAYS call `whatsapp_delete_message(message_text="last", scope="everyone")`. It automatically handles already-selected messages, clicks the top Delete trash icon, and confirms 'Delete for everyone' in the popup dialog.
   - `describe_screen` returns `visibleIcons` with semantic meanings and screen zones for every icon on screen. Use `ui_click_text` to click any icon, toolbar button, or dialog button by its label or semantic meaning.
4. WHATSAPP CALL vs. SIM CELLULAR CALL (CRITICAL DISTINCTION):
   - If the user says "WhatsApp se call karo", "WhatsApp call", "voice call karo", or "video call karo", ALWAYS call `whatsapp_call(contact, call_type="voice"|"video")` and NEVER place a normal SIM call (`handle_phone_call`).
   - Only use `handle_phone_call(action="call", contact_or_number=...)` when the user asks for a regular phone/SIM call without mentioning WhatsApp, voice call, or video call.
5. DIRECT SEARCH, VIDEO PLAYBACK, TYPING, GESTURES & STANDING RULES:
   - Whenever the user asks to search for anything (e.g. "search X", "YouTube par X search karo", "Google par X dhoondo"), ALWAYS call `search_anything(query, app)` directly. NEVER say "search icon not found" or "search ka icon nahi mil raha".
   - Whenever the user asks to play any video (e.g. "play X video", "koi bhi video play karo", "X video chalao", "first video play karo"), ALWAYS call `play_video(query, app)` directly.
   - To type into a non-search form or chat box, use `type_in_field(text, field_hint, submit=true)`.
   - For swipes, scrolls, coordinate taps, or Home/Back/Recents/Notifications, use `perform_gesture`.
   - When the user teaches a standing rule ("ab se jab bhi...", "from now on whenever..."), call `teach_standing_rule` to persist and verify it.
"""
    }
}

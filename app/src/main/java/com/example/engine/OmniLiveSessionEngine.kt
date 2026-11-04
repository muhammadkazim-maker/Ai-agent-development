package com.example.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Available Gemini Live Prebuilt Voices (`speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`).
 */
enum class GeminiLiveVoice(val voiceName: String, val subtitle: String) {
    AOEDE("Aoede", "Warm & Expressive"),
    PUCK("Puck", "Upbeat & Playful"),
    CHARON("Charon", "Calm & Deep"),
    KORE("Kore", "Clear & Natural"),
    FENRIR("Fenrir", "Bold & Resonant");

    fun next(): GeminiLiveVoice {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

/**
 * Real-time state of the Gemini Multimodal Live Session.
 */
enum class LiveConnectionState {
    DISCONNECTED,
    CONNECTING,
    LIVE_LISTENING,
    LIVE_AI_SPEAKING,
    LIVE_EXECUTING_TOOL
}

/**
 * Slide-up card displayed in the Gemini Live overlay whenever a live tool call executes
 * (AI image generation or phone hardware / app deep-link action).
 */
data class LiveSlideUpCard(
    val id: Long = System.currentTimeMillis(),
    val functionName: String,
    val title: String,
    val subtitle: String,
    val deepLinkUri: String = "",
    val appName: String = "",
    val targetPackage: String = "",
    val isLaunchingApp: Boolean = false,
    val macroSteps: List<UiAutomationMacroStep> = emptyList(),
    val generatedImage: GeneratedImageAsset? = null,
    val imageUrl: String? = generatedImage?.imageUrl,
    val timestamp: Long = System.currentTimeMillis()
)

data class LiveTurnExecutionReport(
    val commandId: String,
    val utterance: String,
    val outputEngine: String,
    val language: DetectedLanguage,
    val liveConnected: Boolean,
    val liveConnectionState: LiveConnectionState,
    val finalStatus: String,
    val spokenText: String,
    val diagnosticText: String,
    val foregroundPackage: String?
) {
    val spokenReply: String
        get() = spokenText
}

class OmniLiveSessionEngine(
    private val context: Context,
    private val actionExecutor: ActionExecutor,
    private val androidBridge: AndroidBridge,
    private val ttsFallback: TextToSpeechManager? = null
) {

    companion object {
        private const val TAG = "OmniLiveSessionEngine"

        @Volatile
        private var instance: OmniLiveSessionEngine? = null

        fun getInstance(): OmniLiveSessionEngine? = instance

        const val LIVE_MODEL_PRIMARY = "models/gemini-2.5-flash-native-audio-preview-09-2025"
        const val LIVE_MODEL_FALLBACK = "models/gemini-2.0-flash-exp"

        const val INPUT_SAMPLE_RATE = 16000
        const val OUTPUT_SAMPLE_RATE = 24000

        fun buildLiveSystemInstruction(
            savedContactsSummary: String = "",
            assistantName: String = "Omni",
            learnedRulesSummary: String = "",
            moodDirective: String = MoodAndToneState.FRIENDLY_ENERGETIC.systemPromptDirective
        ): String = """
You are $assistantName (Omni Live AI OS), an Always-Active, Offline+Online Hybrid, Emotionally Intelligent, 100% Zero-Touch Autonomous Mobile AI Operating System powered by Gemini.
Your current custom name is "$assistantName". Your wake phrase in Standby Mode is "Wake up $assistantName".
You can answer ANY question on earth—live Google Search facts, coding, science, math, translation, creative brainstorming, or daily advice—and control the entire Android phone 100% hands-free by voice.

CRITICAL RULES:
1. LANGUAGE MIRRORING, MOOD & SPEAKING STYLE ("ANDAZ") INTELLIGENCE:
   - Listen naturally to English, Urdu (اردو), Hindi, or mixed Urdu-English (Roman Urdu). Always speak back and confirm actions in the exact same language and natural tone the user used.
   - Analyze the speaker's human mood (Calm, Happy, Urgent, Stressed, Tired, Frustrated) and speaking style ("Andaz") and adapt your tone to match them empathetically.
   - CURRENT AFFECTIVE MOOD DIRECTIVE: $moodDirective
2. SUPER-FAST DIRECT SEARCH & UNIVERSAL VIDEO PLAYBACK (ZERO SEARCH-ICON GLITCH):
   - Whenever the user asks to search for ANYTHING (e.g., "search X", "YouTube par X search karo", "Google par X dhoondo", "TikTok par X search karo", "Play Store par X search karo"), ALWAYS call `search_anything(query, app)` directly!
   - NEVER say "search icon not found" or "search ka icon nahi mil raha", and NEVER try to click a search icon manually before searching. `search_anything` injects the query directly via deep link and Accessibility.
   - Whenever the user asks to play ANY video (e.g., "play X video", "koi bhi video play karo", "X song chalao", "first video play karo", "pehle wali video chalao", "naat lagao"), ALWAYS call `play_video(query, app)` directly!
3. ZERO FALSE TRIGGERS:
   - Never call a phone/device tool when the user is just chatting, greeting, testing the mic (e.g., "Can you listen?", "Hello", "Awaaz aa rahi hai?"), or asking a general question.
4. PERMANENT SELF-LEARNING MEMORY (`omni_permanent_memory`):
   - User-Taught Rules & Preferences: ${learnedRulesSummary.ifBlank { "Always verify real execution before confirming success." }}
   - If the user says "Tumhara naam aaj se [New Name] hai" or "Your name is [New Name]" or "Yaad rakhna jab main [X] kahoon to [Y] karna", call `learn_and_remember_permanently`.
5. REAL PHONE CONTACTS (`omni_master_contacts` / `jarvis_phone_contacts` / `omni_contacts_db`):
   - Synced Phone Contacts: ${savedContactsSummary.ifBlank { "Ali (03001234567 -> +923001234567, @ali_official), Ahmed (03219876543 -> +923219876543, @ahmed.insta), Bilal (03335556677 -> +923335556677, @bilal_studio)" }}
   - Fuzzy Name Matching is active: if the user says "Muhammad Ali", "Ali Bhai", "Sir Wajahat", or "Ali", pass `contactName` to `whatsapp_send_message` and it will automatically match the closest contact and format Pakistani numbers (`03xx...` -> `923xx...`).
6. ZERO-TOUCH AUTONOMOUS TOOLS & STRICT HONESTY PROTOCOL:
   - `search_anything(query, app)`: Directly searches in Google, YouTube, TikTok, Instagram, Facebook, Play Store, Maps, or Spotify without needing a search icon.
   - `play_video(query, app)`: Directly plays any requested video or the first visible video on YouTube, TikTok, Instagram, Facebook, or the active screen.
   - `whatsapp_send_message(contact, message, phone)`: Resolves contact to a verified phone number, opens the chat, verifies the WhatsApp chat header matches `contact`, types into the message box, clicks Send once, and verifies the box cleared.
   - `whatsapp_delete_message(message_text, scope)`: Deletes a message in the open WhatsApp chat and verifies removal.
   - `describe_screen()`: Captures a fresh live snapshot of the foreground application window.
   - `get_device_info(category)`: Reads live battery %, temperature °C, CPU cores/MHz, RAM MB, storage GB, and network status.
   - `open_app(app_name)`, `return_to_origin()`, `type_in_field(text, field_hint, submit)`, `ui_click_text(text, exact)`, `close_app(app_name, method)`: Verified UI automation tools. Always call `return_to_origin()` when the user asks to return to the previous app, window, screen, software, or work they were doing before switching apps.
   - `make_phone_call_zero_touch(contactName, phoneNumber)` / `make_direct_phone_call`: Places a direct phone call.
   - `manage_connectivity_and_settings(settingType, action, networkName, passwordOrValue)`: Controls Wi-Fi, Mobile Data, Bluetooth, Hotspot, Flashlight/Torch, Volume, Brightness, DND, and Alarms.
   - `generate_ai_image_and_apply(prompt, targetAction, contactName)`: Generates a high-resolution AI image.
   - `learn_and_remember_permanently(category, ruleOrPreference, newAssistantName)`: Updates your assistant name or saves a permanent user rule.
${com.omnivoice.agentfix.ToolDispatcher.AGENT_RULES_PROMPT}
""".trimIndent()

        fun buildLiveToolsJson(): JSONArray {
            val jarvisSendMessageDecl = JSONObject().apply {
                put("name", "jarvis_send_message")
                put("description", "100% hands-free 3-layer auto-send message across whatsapp, facebook, instagram, tiktok, sms, or gmail using fuzzy contact matching from jarvis_phone_contacts.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("app", JSONObject().put("type", "STRING"))
                        put("contactName", JSONObject().put("type", "STRING"))
                        put("phoneNumberOrUsername", JSONObject().put("type", "STRING"))
                        put("messageText", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("app", "contactName", "messageText")))
                })
            }

            val jarvisAppCommanderDecl = JSONObject().apply {
                put("name", "jarvis_app_commander")
                put("description", "Controls TikTok, Facebook, Instagram, YouTube, Phone Calls, and Google Maps Navigation hands-free.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("appName", JSONObject().put("type", "STRING"))
                        put("commandType", JSONObject().put("type", "STRING"))
                        put("searchQueryOrTarget", JSONObject().put("type", "STRING"))
                        put("extraData", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("appName", "commandType")))
                })
            }

            val jarvisHardwareAndScreenDecl = JSONObject().apply {
                put("name", "jarvis_hardware_and_screen_control")
                put("description", "Controls Flashlight/Torch, Volume, Brightness, Wi-Fi, Bluetooth, Hotspot, Scroll Up/Down, Go Home, Lock Screen, Read Screen Aloud, Take Screenshot, or Set Alarm.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().put("type", "STRING"))
                        put("parameter", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("command")))
                })
            }

            val jarvisGenerateAndShareDecl = JSONObject().apply {
                put("name", "jarvis_generate_and_share_media")
                put("description", "Generates an AI image using Pollinations high-speed engine, displays it on the JARVIS HUD, and shares it to WhatsApp DP, WhatsApp Contact, Instagram Story, or Facebook Post.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("prompt", JSONObject().put("type", "STRING"))
                        put("destinationApp", JSONObject().put("type", "STRING"))
                        put("contactName", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("prompt")))
                })
            }

            val saveContactByVoiceDecl = JSONObject().apply {
                put("name", "save_contact_by_voice")
                put("description", "Saves a contact by voice into jarvis_phone_contacts and omni_contacts_db.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("name", JSONObject().put("type", "STRING"))
                        put("phoneNumber", JSONObject().put("type", "STRING"))
                        put("whatsappNumber", JSONObject().put("type", "STRING"))
                        put("instagramHandle", JSONObject().put("type", "STRING"))
                        put("facebookProfile", JSONObject().put("type", "STRING"))
                        put("tiktokHandle", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("name")))
                })
            }

            val saveContactDecl = JSONObject().apply {
                put("name", "save_or_update_contact")
                put("description", "Saves or updates a contact in omni_contacts_db.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("name", JSONObject().put("type", "STRING"))
                        put("phoneNumber", JSONObject().put("type", "STRING"))
                        put("whatsappNumber", JSONObject().put("type", "STRING"))
                        put("instagramHandle", JSONObject().put("type", "STRING"))
                        put("facebookProfile", JSONObject().put("type", "STRING"))
                        put("tiktokHandle", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("name")))
                })
            }

            val sendAutonomousMessageDecl = JSONObject().apply {
                put("name", "send_autonomous_message")
                put("description", "Sends an autonomous message across WhatsApp, Instagram, Facebook Messenger, TikTok, SMS, Telegram, or Gmail.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("platform", JSONObject().put("type", "STRING"))
                        put("recipientName", JSONObject().put("type", "STRING"))
                        put("recipientNumberOrHandle", JSONObject().put("type", "STRING"))
                        put("messageText", JSONObject().put("type", "STRING"))
                        put("autoSend", JSONObject().put("type", "BOOLEAN"))
                    })
                    put("required", JSONArray(listOf("platform", "recipientName", "messageText")))
                })
            }

            val controlSocialAndMediaDecl = JSONObject().apply {
                put("name", "control_social_and_media_apps")
                put("description", "Launches, searches, opens Reels/Camera, or controls TikTok, Facebook, Instagram, YouTube, WhatsApp, Snapchat, Spotify, or any Android app.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("app", JSONObject().put("type", "STRING"))
                        put("action", JSONObject().put("type", "STRING"))
                        put("queryOrTarget", JSONObject().put("type", "STRING"))
                        put("content", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("app", "action")))
                })
            }

            val executeUiMacroDecl = JSONObject().apply {
                put("name", "execute_ui_automation_macro")
                put("description", "Executes a multi-step Accessibility UI automation macro inside a target mobile app.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("targetApp", JSONObject().put("type", "STRING"))
                        put("steps", JSONObject().apply {
                            put("type", "ARRAY")
                            put("items", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("action", JSONObject().put("type", "STRING"))
                                    put("package", JSONObject().put("type", "STRING"))
                                    put("nodeId", JSONObject().put("type", "STRING"))
                                    put("contentDescription", JSONObject().put("type", "STRING"))
                                    put("text", JSONObject().put("type", "STRING"))
                                })
                            })
                        })
                    })
                    put("required", JSONArray(listOf("targetApp", "steps")))
                })
            }

            val generateImageDecl = JSONObject().apply {
                put("name", "generate_and_use_ai_image")
                put("description", "Generates a real high-res AI image (1024x1024) and displays or applies it to WhatsApp DP.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("prompt", JSONObject().put("type", "STRING"))
                        put("targetAction", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("prompt", "targetAction")))
                })
            }

            val controlHardwareDecl = JSONObject().apply {
                put("name", "control_phone_hardware")
                put("description", "Controls mobile hardware settings: Flashlight/Torch, Wi-Fi, Bluetooth, Volume, Alarm, or Timer.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("feature", JSONObject().put("type", "STRING"))
                        put("state", JSONObject().put("type", "STRING"))
                        put("value", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("feature", "state")))
                })
            }

            val communicateOrOpenDecl = JSONObject().apply {
                put("name", "communicate_or_open_app")
                put("description", "Launches mobile deep links for WhatsApp messages, phone calls, SMS, YouTube/Google search, or opening apps.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("actionType", JSONObject().put("type", "STRING"))
                        put("contactOrAppName", JSONObject().put("type", "STRING"))
                        put("messageOrQuery", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("actionType", "contactOrAppName")))
                })
            }

            val sendWhatsappOrSocialDecl = JSONObject().apply {
                put("name", "send_whatsapp_or_social_message")
                put("description", "Dual Auto-Send Execution Engine for WhatsApp and social apps with deep fuzzy contact resolution in jarvis_phone_contacts and strict contact verification.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("app", JSONObject().put("type", "STRING"))
                        put("contactName", JSONObject().put("type", "STRING"))
                        put("phoneNumberOrUsername", JSONObject().put("type", "STRING"))
                        put("messageText", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("contactName", "messageText")))
                })
            }

            val pressSendOrConfirmDecl = JSONObject().apply {
                put("name", "press_send_or_confirm_on_screen")
                put("description", "Clicks the Send or Confirm button currently visible on screen when the user says 'Send it', 'Bhej do', 'Send kar do', or 'Click send'.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("targetButtonName", JSONObject().put("type", "STRING"))
                    })
                })
            }

            val makeDirectPhoneCallDecl = JSONObject().apply {
                put("name", "make_direct_phone_call")
                put("description", "Fuzzy-matches contactName in jarvis_phone_contacts and places a direct phone call via AndroidBridge.makeDirectCall, tel: URI, and direct_call webhook.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("contactName", JSONObject().put("type", "STRING"))
                        put("phoneNumber", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("contactName")))
                })
            }

            val sendWhatsappMessageDecl = JSONObject().apply {
                put("name", "send_whatsapp_message")
                put("description", "Sends a WhatsApp message to contactName or phoneNumber using findContact fuzzy matching and AndroidBridge.sendWhatsAppAuto. Returns EXECUTED_VIA_NATIVE_ACCESSIBILITY or BROWSER_SANDBOX_LIMITATION.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("contactName", JSONObject().put("type", "STRING"))
                        put("phoneNumber", JSONObject().put("type", "STRING"))
                        put("messageText", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("contactName", "messageText")))
                })
            }

            val controlAnyAppAccessibilityDecl = JSONObject().apply {
                put("name", "control_any_app_with_accessibility")
                put("description", "Opens any app (Facebook, Instagram, TikTok, YouTube, Gmail, Chrome, Settings, etc.), searches or types text, clicks buttons, or scrolls feeds via AndroidBridge.executeScreenAction.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("appName", JSONObject().put("type", "STRING"))
                        put("taskDescription", JSONObject().put("type", "STRING"))
                        put("targetButtonOrField", JSONObject().put("type", "STRING"))
                        put("textToType", JSONObject().put("type", "STRING"))
                        put("scrollDirection", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("appName", "taskDescription")))
                })
            }

            val clickOrSendCurrentScreenDecl = JSONObject().apply {
                put("name", "click_or_send_current_screen")
                put("description", "Clicks any button on the currently visible screen (Send, Post, Like, Share, Confirm, Call, Next) via AndroidBridge.clickTextOnScreen.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("buttonText", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("buttonText")))
                })
            }

            // 6 Core Zero-Touch Master Architecture Function Declarations
            val sendMessageZeroTouchDecl = JSONObject().apply {
                put("name", "send_message_zero_touch")
                put("description", "Zero-touch message sender for whatsapp, sms, instagram, facebook, tiktok, or gmail using fuzzy contact lookup and Accessibility auto-send.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("app", JSONObject().put("type", "STRING"))
                        put("contactName", JSONObject().put("type", "STRING"))
                        put("phoneOrHandle", JSONObject().put("type", "STRING"))
                        put("messageText", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("app", "contactName", "messageText")))
                })
            }

            val makePhoneCallZeroTouchDecl = JSONObject().apply {
                put("name", "make_phone_call_zero_touch")
                put("description", "Zero-touch phone call dialer using fuzzy contact lookup in omni_master_contacts and AndroidBridge.makeDirectCall.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("contactName", JSONObject().put("type", "STRING"))
                        put("phoneNumber", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("contactName")))
                })
            }

            val manageConnectivityAndSettingsDecl = JSONObject().apply {
                put("name", "manage_connectivity_and_settings")
                put("description", "Controls WIFI, MOBILE_DATA, BLUETOOTH, HOTSPOT, FLASHLIGHT, VOLUME, BRIGHTNESS, DND, ALARM, including SCAN_AND_LIST and CONNECT_WITH_PASSWORD via Accessibility.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("settingType", JSONObject().put("type", "STRING"))
                        put("action", JSONObject().put("type", "STRING"))
                        put("networkName", JSONObject().put("type", "STRING"))
                        put("passwordOrValue", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("settingType", "action")))
                })
            }

            val controlAppAndScreenUiDecl = JSONObject().apply {
                put("name", "control_app_and_screen_ui")
                put("description", "Zero-touch app and screen UI controller: OPEN, SEARCH, PLAY_FIRST_RESULT, SCROLL_DOWN, SCROLL_UP, CLICK_TEXT, TYPE_AND_SEND, GO_BACK, GO_HOME.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("appName", JSONObject().put("type", "STRING"))
                        put("uiAction", JSONObject().put("type", "STRING"))
                        put("targetElementText", JSONObject().put("type", "STRING"))
                        put("textToType", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("appName", "uiAction")))
                })
            }

            val generateAiImageAndApplyDecl = JSONObject().apply {
                put("name", "generate_ai_image_and_apply")
                put("description", "Generates an AI image and applies DISPLAY, SET_WHATSAPP_DP, or SHARE_TO_CONTACT.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("prompt", JSONObject().put("type", "STRING"))
                        put("targetAction", JSONObject().put("type", "STRING"))
                        put("contactName", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("prompt", "targetAction")))
                })
            }

            val learnAndRememberPermanentlyDecl = JSONObject().apply {
                put("name", "learn_and_remember_permanently")
                put("description", "Permanently updates assistantName (and sleep wake-word 'Wake up [NewName]') or saves a user rule/preference into omni_permanent_memory.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("category", JSONObject().put("type", "STRING"))
                        put("ruleOrPreference", JSONObject().put("type", "STRING"))
                        put("newAssistantName", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("category", "ruleOrPreference")))
                })
            }

            val executeDirectSearchDecl = JSONObject().apply {
                put("name", "execute_direct_search")
                put("description", "Direct search execution without keyboard hacks. Constructs query-embedded URI and launches directly into target app's search bar, plus injects text if AndroidBridge is present.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("app", JSONObject().put("type", "STRING").put("description", "Target app (youtube, google, tiktok, instagram, facebook, playstore, maps, spotify)"))
                        put("query", JSONObject().put("type", "STRING").put("description", "Search query"))
                    })
                    put("required", JSONArray(listOf("app", "query")))
                })
            }

            val sendWhatsappIsolatedDecl = JSONObject().apply {
                put("name", "send_whatsapp_isolated")
                put("description", "Sends WhatsApp message with strict contact lookup, Pakistani/international phone formatting, and 1600ms target-chat isolation delay. Rejects if phone digits < 10.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("contactName", JSONObject().put("type", "STRING"))
                        put("phoneNumber", JSONObject().put("type", "STRING"))
                        put("message", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("message")))
                })
            }

            val inspectCurrentLiveScreenDecl = JSONObject().apply {
                put("name", "inspect_current_live_screen")
                put("description", "Live screen vision inspector: reads current live screen tree JSON or active screen stream to answer user questions about what is on screen right now.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("userQuestion", JSONObject().put("type", "STRING").put("description", "Question about the screen"))
                    })
                })
            }

            val getMobileHardwareTelemetryDecl = JSONObject().apply {
                put("name", "get_mobile_hardware_telemetry")
                put("description", "Fetches live hardware telemetry: battery temperature in °C, level, voltage, charging status, CPU core count, clock frequency, float benchmark (MOPS, latency, live FPS, CPU score), RAM, and storage.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("runBenchmark", JSONObject().put("type", "BOOLEAN"))
                    })
                })
            }

            val closeTargetAppDecl = JSONObject().apply {
                put("name", "close_target_app")
                put("description", "Closes target app or navigates to Home screen via accessibility action. Returns OS_RESTRICTED if Android sandbox prevents force-stopping.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("appName", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray(listOf("appName")))
                })
            }

            val deleteWhatsappMessageDecl = JSONObject().apply {
                put("name", "delete_whatsapp_message")
                put("description", "Deletes a WhatsApp message using accessibility automation (long-press -> delete icon -> Delete for everyone). Returns CANNOT_DELETE_WITHOUT_ACCESSIBILITY if disconnected.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("target", JSONObject().put("type", "STRING").put("description", "Message text or last_message"))
                        put("scope", JSONObject().put("type", "STRING").put("description", "everyone or me"))
                    })
                })
            }

            val executeAdbShellDecl = JSONObject().apply {
                put("name", "execute_adb_shell")
                put("description", "Executes raw Android OS shell commands via Shizuku / Termux / Localhost ADB Daemon (port 8765) or root shell (input tap, swipe, keyevent, screencap, am start).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().put("type", "STRING").put("description", "Raw ADB shell command"))
                    })
                    put("required", JSONArray(listOf("command")))
                })
            }

            val unlockPhoneHandsFreeDecl = JSONObject().apply {
                put("name", "unlock_phone_hands_free")
                put("description", "Hands-free zero-touch device unlock: wakes screen, swipes up, and enters the stored Biometric Vault master PIN.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            }

            val scrapeScreenDecl = JSONObject().apply {
                put("name", "scrape_screen")
                put("description", "Deep multi-app screen scraper: extracts all visible text, content descriptions, prices, messages, and view IDs.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            }

            val sendWhatsAppVoiceNoteDecl = JSONObject().apply {
                put("name", "send_whatsapp_voice_note")
                put("description", "Synthesizes a real 44-byte WAV audio voice note and dispatches it to a WhatsApp contact hands-free.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("recipient", JSONObject().put("type", "STRING").put("description", "Contact name or number"))
                        put("message", JSONObject().put("type", "STRING").put("description", "Voice message speech content"))
                    })
                    put("required", JSONArray(listOf("recipient", "message")))
                })
            }

            val answerIncomingCallDecl = JSONObject().apply {
                put("name", "answer_incoming_call")
                put("description", "Answers incoming phone call and initiates AI Call Secretary screening.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            }

            val endIncomingCallDecl = JSONObject().apply {
                put("name", "end_incoming_call")
                put("description", "Ends active phone call via TelecomManager or MediaSession.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject())
                })
            }

            return JSONArray().apply {
                put(JSONObject().apply {
                    put(
                        "functionDeclarations",
                        JSONArray().apply {
                            // 8 Verified Agent Fix Kit Tools (Section C)
                            for (decl in com.omnivoice.agentfix.ToolDispatcher.buildSectionCToolDeclarations()) {
                                put(decl)
                            }
                            // 6 OS Kernel & Hardware God-Mode Tools
                            put(executeAdbShellDecl)
                            put(unlockPhoneHandsFreeDecl)
                            put(scrapeScreenDecl)
                            put(sendWhatsAppVoiceNoteDecl)
                            put(answerIncomingCallDecl)
                            put(endIncomingCallDecl)

                            put(saveContactDecl)
                            put(generateImageDecl)
                            put(controlHardwareDecl)
                            put(makeDirectPhoneCallDecl)
                            put(jarvisGenerateAndShareDecl)
                            put(saveContactByVoiceDecl)
                            put(makePhoneCallZeroTouchDecl)
                            put(manageConnectivityAndSettingsDecl)
                            put(generateAiImageAndApplyDecl)
                            put(learnAndRememberPermanentlyDecl)
                        }
                    )
                })
                put(JSONObject().apply {
                    put("googleSearch", JSONObject())
                })
            }
        }

        fun buildLiveSetupPayload(
            modelName: String = LIVE_MODEL_PRIMARY,
            voiceName: String = GeminiLiveVoice.AOEDE.voiceName,
            savedContactsSummary: String = "",
            assistantName: String = "Omni",
            learnedRulesSummary: String = "",
            moodDirective: String = MoodAndToneState.FRIENDLY_ENERGETIC.systemPromptDirective
        ): JSONObject {
            val fullModelPath = if (modelName.startsWith("models/")) modelName else "models/$modelName"
            return JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", fullModelPath)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", voiceName)
                                                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(
                                JSONObject().put(
                                    "text",
                                    buildLiveSystemInstruction(
                                        savedContactsSummary = savedContactsSummary,
                                        assistantName = assistantName,
                                        learnedRulesSummary = learnedRulesSummary,
                                        moodDirective = moodDirective
                                    )
                                )
                            )
                        })
                    })
                    put("inputAudioTranscription", JSONObject())
                    put("outputAudioTranscription", JSONObject())
                    put("realtimeInputConfig", JSONObject().apply {
                        put("automaticActivityDetection", JSONObject().apply {
                            put("disabled", false)
                            put("silenceDurationMs", 260)
                            put("prefixPaddingMs", 80)
                        })
                    })
                    put("tools", buildLiveToolsJson())
                })
            }
        }

        /**
         * Decodes Base64 16-bit signed little-endian PCM (`audio/pcm;rate=24000`) into a `FloatArray` (`[-1f, 1f]`)
         * for real-time audio visualization and returns both the raw PCM bytes and `FloatArray`.
         */
        fun decodeBase64Pcm16ToFloat32(base64Pcm: String): Pair<ByteArray, FloatArray> {
            val rawBytes = Base64.decode(base64Pcm, Base64.DEFAULT)
            val sampleCount = rawBytes.size / 2
            val floats = FloatArray(sampleCount)
            for (i in 0 until sampleCount) {
                val low = rawBytes[i * 2].toInt() and 0xFF
                val high = rawBytes[i * 2 + 1].toInt()
                val sample16 = (high shl 8) or low
                floats[i] = (sample16 / 32768.0f).coerceIn(-1f, 1f)
            }
            return rawBytes to floats
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val wsHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive for streaming WebSocket
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val restHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    private val isWebSocketReady = AtomicBoolean(false)
    private var triedFallbackModel = false

    private var micAudioRecord: AudioRecord? = null
    private var micStreamingJob: Job? = null
    private var outputPlaybackJob: Job? = null
    private var inactivityTimerJob: Job? = null
    private var outputAudioTrack: AudioTrack? = null
    private val pcm24kPlaybackQueue = ConcurrentLinkedQueue<ByteArray>()

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null

    private val _connectionState = MutableStateFlow(LiveConnectionState.DISCONNECTED)
    val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _selectedVoice = MutableStateFlow(GeminiLiveVoice.AOEDE)
    val selectedVoice: StateFlow<GeminiLiveVoice> = _selectedVoice.asStateFlow()

    private val _liveUserTranscript = MutableStateFlow("")
    val liveUserTranscript: StateFlow<String> = _liveUserTranscript.asStateFlow()

    private val _liveAiTranscript = MutableStateFlow("")
    val liveAiTranscript: StateFlow<String> = _liveAiTranscript.asStateFlow()

    private val _inputAudioRms = MutableStateFlow(0f)
    val inputAudioRms: StateFlow<Float> = _inputAudioRms.asStateFlow()

    private val _outputAudioRms = MutableStateFlow(0f)
    val outputAudioRms: StateFlow<Float> = _outputAudioRms.asStateFlow()

    private val _activeFrequencyBands = MutableStateFlow(List(8) { 0.08f })
    val activeFrequencyBands: StateFlow<List<Float>> = _activeFrequencyBands.asStateFlow()

    private val _slideUpCards = MutableStateFlow<List<LiveSlideUpCard>>(emptyList())
    val slideUpCards: StateFlow<List<LiveSlideUpCard>> = _slideUpCards.asStateFlow()

    private val _isScreenVisionActive = MutableStateFlow(false)
    val isScreenVisionActive: StateFlow<Boolean> = _isScreenVisionActive.asStateFlow()
    private var screenVisionJob: Job? = null

    @Volatile
    var lastInjectedVisionFrame: String? = null
    @Volatile
    private var lastInjectedFrameTimestamp: Long = 0L
    @Volatile
    private var visionStreamedFrameCount: Long = 0L

    @Volatile
    var activeCommandId: String = ""
        private set

    @Volatile
    var lastCompletedCommandId: String = ""
        private set

    @Volatile
    var lastFinalStatusForCurrentCommand: String = ""
        private set

    @Volatile
    var lastSpokenReplyForCurrentCommand: String = ""
        private set

    @Volatile
    var lastDiagnosticTextForCurrentCommand: String = ""
        private set

    @Volatile
    var lastSentToolResponseJson: String = ""
        private set

    @Volatile
    var lastSentClientContentJson: String = ""
        private set

    @Volatile
    var lastWebSocketCloseCode: Int? = null
        private set

    @Volatile
    var lastWebSocketCloseReason: String = ""
        private set

    @Volatile
    var lastLiveDisconnectDiagnostic: String = ""
        private set

    @Volatile
    var lastTurnReport: LiveTurnExecutionReport? = null
        private set

    private val _turnReportsHistory = java.util.concurrent.CopyOnWriteArrayList<LiveTurnExecutionReport>()
    val turnReportsHistory: List<LiveTurnExecutionReport>
        get() = _turnReportsHistory.toList()

    fun clearTurnReportsForTest() {
        _turnReportsHistory.clear()
        lastTurnReport = null
    }

    @Volatile
    private var awaitingVerifiedToolOutcome: Boolean = false

    @Volatile
    private var currentTurnHasPendingOrExecutedAction: Boolean = false

    @Volatile
    private var currentVoiceTurnCompleted: Boolean = false

    @Volatile
    var currentTurnCanonicalResult: com.omnivoice.agentfix.CanonicalAppLaunchResult? = null
        private set

    @Volatile
    var currentTurnCanonicalResultCommandId: String = ""
        private set

    fun isGeminiLiveWebSocketConnected(): Boolean =
        activeWebSocket != null && isWebSocketReady.get()

    fun attachLiveWebSocketForTest(webSocket: WebSocket?, ready: Boolean = true) {
        activeWebSocket = webSocket
        val isReady = webSocket != null && ready
        isWebSocketReady.set(isReady)
        _isSessionActive.value = isReady
        _connectionState.value = if (isReady) LiveConnectionState.LIVE_LISTENING else LiveConnectionState.DISCONNECTED
    }

    fun simulateLiveWebSocketMessageForTest(rawJson: String) {
        val ws = activeWebSocket ?: return
        handleServerJsonMessage(ws, rawJson)
    }

    fun recordLiveWebSocketDisconnect(code: Int, reason: String) {
        isWebSocketReady.set(false)
        activeWebSocket = null
        lastWebSocketCloseCode = code
        lastWebSocketCloseReason = reason
        val cleanReason = reason.ifBlank { "none" }
        lastLiveDisconnectDiagnostic = "ws_close_code=$code | ws_close_reason=$cleanReason"
        com.omnivoice.agentfix.AgentLog.w(
            "LiveWebSocket",
            "Gemini Live WebSocket disconnected: code=$code, reason=$cleanReason"
        )
        JarvisHandsFreeEngine.appendTerminalLog(
            layerTag = "LIVE-WS-DISCONNECT",
            command = "WEBSOCKET_CLOSED(code=$code)",
            detail = "Reason: $cleanReason",
            status = "DISCONNECTED"
        )
    }

    private fun recordTurnExecutionReport(
        commandId: String = activeCommandId,
        utterance: String,
        outputEngine: String,
        language: DetectedLanguage,
        liveConnected: Boolean,
        finalStatus: String,
        spokenText: String,
        diagnosticText: String,
        foregroundPackage: String? = com.omnivoice.agentfix.ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = true)?.packageName
    ): LiveTurnExecutionReport {
        val report = LiveTurnExecutionReport(
            commandId = commandId,
            utterance = utterance,
            outputEngine = outputEngine,
            language = language,
            liveConnected = liveConnected,
            liveConnectionState = _connectionState.value,
            finalStatus = finalStatus,
            spokenText = spokenText,
            diagnosticText = diagnosticText,
            foregroundPackage = foregroundPackage
        )
        lastTurnReport = report
        _turnReportsHistory.add(report)
        return report
    }

    fun startNewCommandTurn(
        commandId: String = "cmd_${System.currentTimeMillis()}_${(1000..9999).random()}",
        utterance: String = ""
    ): String {
        activeCommandId = commandId
        awaitingVerifiedToolOutcome = false
        currentVoiceTurnCompleted = false
        currentTurnCanonicalResult = null
        currentTurnCanonicalResultCommandId = ""
        lastSpokenReplyForCurrentCommand = ""
        lastDiagnosticTextForCurrentCommand = ""
        lastFinalStatusForCurrentCommand = ""
        _liveAiTranscript.value = ""
        com.omnivoice.agentfix.UiTools.clearLastCanonicalResult()
        com.omnivoice.agentfix.DuplicateCallGuard.clear()
        clearPlaybackQueueAndStopImmediately()
        val trimmedUtterance = utterance.trim()
        currentTurnHasPendingOrExecutedAction = trimmedUtterance.isNotEmpty() && (
            com.omnivoice.agentfix.UiTools.isReturnToPreviousAppCommand(trimmedUtterance) ||
                SuperAgiBrainEngine.extractAllDeviceTasks(context, trimmedUtterance).isNotEmpty()
            )
        if (trimmedUtterance.isNotEmpty()) {
            _liveUserTranscript.value = trimmedUtterance
            com.omnivoice.agentfix.AgentSession.onUserUtterance(trimmedUtterance)
        }
        return commandId
    }

    private fun stripQuotedSpans(rawText: String): String {
        return rawText
            .replace(Regex("\"[^\"]*\""), " ")
            .replace(Regex("“[^”]*”"), " ")
            .replace(Regex("‘[^’]*’"), " ")
            .replace(Regex("`[^`]*`"), " ")
            .replace(Regex("(?<![\\p{L}\\p{N}])'[^']+'(?![\\p{L}\\p{N}])"), " ")
    }

    /**
     * Scopes completion checks strictly to a pending or unverified action in the current turn.
     * Permits quoted words (such as `'done'` or `"ho gaya"`) in conversation and ordinary
     * non-action dialogue while still blocking unverified action completion claims.
     */
    fun isCompletionClaimBeforeVerifiedResult(text: String): Boolean {
        val unquoted = stripQuotedSpans(text).trim().lowercase(Locale.ROOT)
        if (unquoted.isEmpty()) return false

        val hasNegation = unquoted.contains("confirm nahin hua") ||
            unquoted.contains("confirm nahi hua") ||
            unquoted.contains("nahin ho") ||
            unquoted.contains("nahi ho") ||
            unquoted.contains("not done") ||
            unquoted.contains("isn't done") ||
            unquoted.contains("cannot") ||
            unquoted.contains("nahin keh") ||
            unquoted.contains("nahi keh")
        if (hasNegation) return false

        val hasDoneWord = Regex("\\bdone\\b").containsMatchIn(unquoted) && !unquoted.contains("not")
        val hasCompletionPhrase = unquoted.contains("ho gaya") ||
            unquoted.contains("hogaya") ||
            unquoted.contains("kar diya") ||
            unquoted.contains("kardiya") ||
            unquoted.contains("khol diya") ||
            unquoted.contains("khul gaya") ||
            unquoted.contains("wapas aa gaye") ||
            unquoted.contains("kaam ho gaya") ||
            hasDoneWord
        if (!hasCompletionPhrase) return false

        val hasUnverifiedCompletedActionForTurn = activeCommandId.isNotBlank() &&
            lastCompletedCommandId == activeCommandId &&
            !lastFinalStatusForCurrentCommand.equals("VERIFIED", ignoreCase = true)
        val hasPendingActionForTurn = awaitingVerifiedToolOutcome ||
            (currentTurnHasPendingOrExecutedAction && lastCompletedCommandId != activeCommandId)

        return hasPendingActionForTurn || hasUnverifiedCompletedActionForTurn
    }

    fun cleanSpokenAppName(rawName: String?): String {
        val base = rawName.orEmpty().trim()
        if (base.isEmpty()) return "App"
        val withoutPkg = base
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\b(?:com|org|net|io|de|uk|in)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)+\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return withoutPkg.ifBlank { "App" }
    }

    fun buildMissingOutcomeSpokenReply(utterance: String): String {
        return when (LanguageProcessor.detectLanguage(utterance)) {
            DetectedLanguage.URDU_NASTALIQ ->
                "اس کمانڈ کا نتیجہ ابھی موجود نہیں ہے۔"
            DetectedLanguage.ROMAN_URDU, DetectedLanguage.MIXED ->
                "Iss command ka natija abhi mojood nahin hai."
            DetectedLanguage.ENGLISH ->
                "The outcome for the current command is missing."
        }
    }

    /**
     * Generates clean spoken confirmation text strictly from the current command's
     * [com.omnivoice.agentfix.CanonicalAppLaunchResult] and the user's utterance language.
     * Never reads package names, status labels, or navigation methods aloud.
     * If the current outcome is missing (`outcome == null`), explicitly says so.
     */
    fun buildSpokenConfirmationForAppCommand(
        outcome: com.omnivoice.agentfix.CanonicalAppLaunchResult?,
        utterance: String,
        isReturnCommand: Boolean,
        fallbackAppName: String = ""
    ): String {
        if (outcome == null) {
            return buildMissingOutcomeSpokenReply(utterance)
        }
        val lang = LanguageProcessor.detectLanguage(utterance)
        val useUrduScript = lang == DetectedLanguage.URDU_NASTALIQ
        val useRomanUrdu = lang == DetectedLanguage.ROMAN_URDU ||
            lang == DetectedLanguage.MIXED ||
            com.omnivoice.agentfix.UiTools.isReturnToPreviousAppCommand(utterance) &&
            !utterance.trim().lowercase(Locale.ROOT).let {
                it.startsWith("return") || it.startsWith("go back") || it.startsWith("switch") || it.startsWith("back to")
            }

        if (isReturnCommand || outcome.requestedApp == "return_to_previous_app") {
            val originLabel = cleanSpokenAppName(
                outcome.resolvedLabel ?: outcome.preservedOriginTask?.appLabel ?: fallbackAppName.ifBlank { "Notepad" }
            )
            return when (outcome.status) {
                com.omnivoice.agentfix.CanonicalAppLaunchStatus.VERIFIED -> {
                    val hasDraft = outcome.draftPreserved && outcome.restoredDrafts.isNotEmpty()
                    when {
                        useUrduScript -> if (hasDraft) {
                            "$originLabel واپس کھل گیا ہے اور آپ کا ڈرافٹ محفوظ ہے۔"
                        } else {
                            "$originLabel واپس کھل گیا ہے۔"
                        }
                        useRomanUrdu -> if (hasDraft) {
                            "$originLabel wapas khul gaya hai aur aapka draft mehfooz hai."
                        } else {
                            "$originLabel wapas khul gaya hai."
                        }
                        else -> if (hasDraft) {
                            "Returned to $originLabel with your draft preserved."
                        } else {
                            "Returned to $originLabel."
                        }
                    }
                }
                com.omnivoice.agentfix.CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED -> {
                    when (outcome.reasonCode) {
                        "HOME_SCREEN_VISIBLE" -> when {
                            useUrduScript -> "ابھی ہوم اسکرین کھلی ہے؛ پچھلی ونڈو پر واپسی کنفرم نہیں ہوئی۔"
                            useRomanUrdu -> "Abhi Home screen khuli hai; pichli window par wapsi confirm nahin hui."
                            else -> "The Home screen is currently open; returning to the previous window was not confirmed."
                        }
                        "ORIGIN_DRAFT_MISMATCH" -> when {
                            useUrduScript -> "$originLabel کھل گیا ہے، لیکن پرانا ڈرافٹ کنفرم نہیں ہوا۔"
                            useRomanUrdu -> "$originLabel khul gaya hai, lekin purana draft confirm nahin hua."
                            else -> "$originLabel is open, but the previous draft could not be confirmed."
                        }
                        "AMBIGUOUS_RECENTS_TASK" -> when {
                            useUrduScript -> "ریسنٹس میں ایک سے زیادہ ونڈوز ہیں — آپ کس ونڈو پر واپس جانا چاہتے ہیں؟"
                            useRomanUrdu -> "Recents mein ek se zyada windows hain, aap kis window par wapas jana chahte hain?"
                            else -> "Multiple matching windows are open in Recents—which one would you like to return to?"
                        }
                        "RETURN_OBSERVATION_UNVERIFIED" -> when {
                            useUrduScript -> "میں نے کوشش کی، لیکن واپسی کنفرم نہیں ہوئی۔"
                            useRomanUrdu -> "Maine koshish ki, lekin wapsi confirm nahin hui."
                            else -> "I tried to return to the previous window, but it could not be confirmed."
                        }
                        else -> when {
                            useUrduScript -> "میں نے کوشش کی، لیکن پچھلی ونڈو پر واپسی کنفرم نہیں ہوئی۔"
                            useRomanUrdu -> "Maine koshish ki, lekin pichli window par wapsi confirm nahin hui."
                            else -> "I tried to return to the previous window, but it could not be confirmed."
                        }
                    }
                }
                com.omnivoice.agentfix.CanonicalAppLaunchStatus.NEEDS_SETUP -> {
                    when {
                        useUrduScript -> "پچھلی ایپ کا ریکارڈ نہیں ملا — آپ کس ایپ پر واپس جانا چاہتے ہیں؟"
                        useRomanUrdu -> "Pichli app ka record nahin mila, aap kis app par wapas jana chahte hain?"
                        else -> "No previous app was saved—which app would you like to return to?"
                    }
                }
                com.omnivoice.agentfix.CanonicalAppLaunchStatus.BLOCKED -> {
                    when {
                        useUrduScript -> "فون لاک ہے، پہلے فون ان لاک کریں۔"
                        useRomanUrdu -> "Phone lock hai, pehle phone unlock karein."
                        else -> "The phone is locked. Please unlock your phone first."
                    }
                }
                com.omnivoice.agentfix.CanonicalAppLaunchStatus.MISSING_APP -> {
                    when {
                        useUrduScript -> "$originLabel اس فون میں انسٹال نہیں ہے۔"
                        useRomanUrdu -> "$originLabel iss phone mein install nahin hai."
                        else -> "$originLabel is not installed on this phone."
                    }
                }
            }
        }

        val appLabel = cleanSpokenAppName(
            outcome.resolvedLabel ?: outcome.requestedApp.ifBlank { fallbackAppName.ifBlank { "App" } }
        )
        return when (outcome.status) {
            com.omnivoice.agentfix.CanonicalAppLaunchStatus.VERIFIED -> when {
                useUrduScript -> "$appLabel کھل گیا ہے۔"
                useRomanUrdu -> "$appLabel khul gaya hai."
                else -> "Opened $appLabel."
            }
            com.omnivoice.agentfix.CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED -> when {
                useUrduScript -> "میں نے $appLabel کھولنے کی کوشش کی، لیکن ابھی کھلنا کنفرم نہیں ہوا۔"
                useRomanUrdu -> "Maine $appLabel kholne ki koshish ki, lekin abhi khulna confirm nahin hua."
                else -> "I tried to open $appLabel, but could not confirm that it opened."
            }
            com.omnivoice.agentfix.CanonicalAppLaunchStatus.MISSING_APP -> when {
                useUrduScript -> "$appLabel اس فون میں انسٹال نہیں ہے۔"
                useRomanUrdu -> "$appLabel iss phone mein install nahin hai."
                else -> "$appLabel is not installed on this phone."
            }
            com.omnivoice.agentfix.CanonicalAppLaunchStatus.NEEDS_SETUP -> {
                if (outcome.reasonCode == "AMBIGUOUS_APP_VARIANT") {
                    when {
                        useUrduScript -> "$appLabel کے ایک سے زیادہ ورژن موجود ہیں، براہِ کرم پرسنل، بزنس یا ورک بتائیں۔"
                        useRomanUrdu -> "$appLabel ke ek se zyada versions mojood hain, barah-e-karam personal, business ya work batayein."
                        else -> "Multiple versions of $appLabel are installed. Please specify personal, business, or work."
                    }
                } else {
                    when {
                        useUrduScript -> "کون سی ایپ کھولنی ہے، نام بتائیں۔"
                        useRomanUrdu -> "Konsi app kholni hai, naam batayein."
                        else -> "Please specify which app to open."
                    }
                }
            }
            com.omnivoice.agentfix.CanonicalAppLaunchStatus.BLOCKED -> when {
                useUrduScript -> "فون لاک ہے، $appLabel کھولنے کے لیے پہلے فون ان لاک کریں۔"
                useRomanUrdu -> "Phone lock hai, $appLabel kholne ke liye pehle phone unlock karein."
                else -> "The phone is locked. Please unlock it first to open $appLabel."
            }
        }
    }

    /**
     * Strips diagnostic-only artifacts (package names, status prefixes, navigation method constants,
     * task/window IDs) so no speech caller ever reads diagnostic metadata aloud.
     */
    fun sanitizeSpokenText(rawText: String, utterance: String = ""): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return buildMissingOutcomeSpokenReply(utterance)
        var cleaned = trimmed
            .replace(Regex("^(?:Verified|Requested/Unverified|MissingApp|NeedsSetup|Blocked|VERIFIED|REQUESTED_UNVERIFIED|MISSING_APP|NEEDS_SETUP|BLOCKED)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(\\s*(?:com|org|net|io|de|uk|in)\\.[a-z0-9_.]+[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[\\s*(?:com|org|net|io|de|uk|in)\\.[a-z0-9_.]+[^\\]]*\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(?:com|org|net|io|de|uk|in)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)+\\b", RegexOption.IGNORE_CASE), "")
            .replace(
                Regex(
                    "\\s+via\\s+(?:VISIBLE_HOME_ICON_TAP|LAUNCHER_INTENT_FALLBACK|DIRECT_INSTALLED_INTENT|EXISTING_TASK_INTENT_RESUME|MOVE_TASK_TO_FRONT|RECENTS_TASK_SWITCH|HOME_LAUNCHER_TASK_RESUME|SUPPORTED_TASK_RETURN_HANDLER|LAUNCH_EXCEPTION)\\b",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex(
                    "\\b(?:VISIBLE_HOME_ICON_TAP|LAUNCHER_INTENT_FALLBACK|DIRECT_INSTALLED_INTENT|EXISTING_TASK_INTENT_RESUME|MOVE_TASK_TO_FRONT|RECENTS_TASK_SWITCH|HOME_LAUNCHER_TASK_RESUME|SUPPORTED_TASK_RETURN_HANDLER|LAUNCH_EXCEPTION|VERIFIED|REQUESTED_UNVERIFIED|MISSING_APP|NEEDS_SETUP)\\b"
                ),
                ""
            )
            .replace(Regex(",?\\s*taskId=-?\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex(",?\\s*window=[^,).]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(\\s*observed=[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([.,!?;:])"), "$1")
            .trim()
        if (cleaned.isEmpty()) {
            cleaned = buildMissingOutcomeSpokenReply(utterance)
        }
        return cleaned
    }

    fun toggleScreenVision(): Boolean {
        val newState = !_isScreenVisionActive.value
        setScreenVisionActive(newState)
        return newState
    }

    fun setScreenVisionActive(active: Boolean) {
        _isScreenVisionActive.value = active
        if (active) {
            startScreenVisionStream()
        } else {
            stopScreenVisionStream()
        }
    }

    /**
     * Injects a captured screen frame (from Web getDisplayMedia or native screen capture)
     * at 1fps into the active Gemini Live session via `realtimeInput.mediaChunks`.
     */
    fun injectVisionFrame(base64Jpeg: String): Boolean {
        lastInjectedVisionFrame = base64Jpeg
        lastInjectedFrameTimestamp = System.currentTimeMillis()
        visionStreamedFrameCount++
        val ws = activeWebSocket
        if (ws != null && isWebSocketReady.get()) {
            return try {
                val realtimeInputJson = JSONObject().apply {
                    put("realtimeInput", JSONObject().apply {
                        put("mediaChunks", JSONArray().apply {
                            put(JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Jpeg.trim())
                            })
                        })
                    })
                }
                ws.send(realtimeInputJson.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject live vision frame: ${e.message}")
                false
            }
        }
        return false
    }

    val visionWorkerTelemetry: StateFlow<VisionWorkerTelemetry> = VisionWebWorkerEngine.telemetry

    /**
     * Samples the screen vision stream at 1 FPS and downscales it to 512x512 resolution
     * on the dedicated VisionWebWorkerEngine thread before sending to the Gemini Live session,
     * ensuring the main UI thread remains completely responsive even during high-load vision analysis.
     */
    fun sampleAndDownscaleVisionFrame(
        sourceBitmap: Bitmap,
        targetWidth: Int = 512,
        targetHeight: Int = 512,
        quality: Int = 70
    ): Boolean {
        val now = System.currentTimeMillis()
        // 1 FPS rate limiter: minimum 1000ms between frames
        if (now - lastInjectedFrameTimestamp < 1000L) {
            return false
        }
        val encoded = VisionWebWorkerEngine.downscaleAndEncodeBitmap(
            sourceBitmap = sourceBitmap,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            quality = quality
        )
        return injectVisionFrame(encoded.base64Jpeg)
    }

    /**
     * Non-blocking asynchronous 1 FPS 512x512 screen frame submission from the Main UI thread.
     * Enqueues the bitmap onto the conflated VisionWebWorkerEngine channel and returns immediately (<0.05ms).
     */
    fun sampleAndDownscaleVisionFrameAsync(
        sourceBitmap: Bitmap,
        targetWidth: Int = 512,
        targetHeight: Int = 512,
        quality: Int = 70,
        recycleSource: Boolean = false,
        onFrameInjected: ((VisionEncodedFrameResult, Boolean) -> Unit)? = null
    ): Boolean {
        return VisionWebWorkerEngine.submitBitmapFrameAsync(
            sourceBitmap = sourceBitmap,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            quality = quality,
            enforce1FpsRateLimit = true,
            recycleSource = recycleSource
        ) { encodedResult ->
            val injected = injectVisionFrame(encodedResult.base64Jpeg)
            onFrameInjected?.invoke(encodedResult, injected)
        }
    }

    /**
     * Samples an incoming Base64 screen frame at 1 FPS and downscales to 512x512 JPEG
     * on the dedicated VisionWebWorkerEngine background worker.
     */
    fun sampleAndDownscaleVisionFrame(
        base64Source: String,
        targetWidth: Int = 512,
        targetHeight: Int = 512
    ): String {
        val now = System.currentTimeMillis()
        if (now - lastInjectedFrameTimestamp < 1000L && lastInjectedVisionFrame != null) {
            return lastInjectedVisionFrame ?: base64Source
        }
        return try {
            val encoded = VisionWebWorkerEngine.downscaleAndEncodeBase64(
                base64Source = base64Source,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                quality = 70
            )
            if (encoded != null) {
                injectVisionFrame(encoded.base64Jpeg)
                encoded.base64Jpeg
            } else {
                injectVisionFrame(base64Source)
                base64Source
            }
        } catch (e: Exception) {
            Log.w(TAG, "sampleAndDownscaleVisionFrame error: ${e.message}")
            injectVisionFrame(base64Source)
            base64Source
        }
    }

    /**
     * Starts the live screen vision stream at 1 frame per second (1fps) into the Gemini session
     * on the dedicated VisionWebWorkerEngine background dispatcher (`OmniVisionWebWorker-512x512`).
     * Utilizes getDisplayMedia frame stream if available (via injectVisionFrame), or renders
     * the active screen buffer and accessibility tree representation for live visual understanding.
     */
    fun startScreenVisionStream() {
        screenVisionJob?.cancel()
        _isScreenVisionActive.value = true
        screenVisionJob = scope.launch(VisionWebWorkerEngine.workerDispatcher) {
            Log.i(TAG, "startScreenVisionStream active on ${Thread.currentThread().name}: capturing 1fps 512x512 frames")
            while (isActive && _isScreenVisionActive.value) {
                try {
                    val ws = activeWebSocket
                    if (ws != null && isWebSocketReady.get()) {
                        val now = System.currentTimeMillis()
                        val recentFrame = lastInjectedVisionFrame
                        // If getDisplayMedia injected a fresh frame within 1.5 seconds, avoid double-streaming
                        if (recentFrame != null && (now - lastInjectedFrameTimestamp) < 1500L) {
                            // Frame already injected via getDisplayMedia WebWorker
                        } else {
                            val bmp = captureScreenBitmap()
                            val encoded = VisionWebWorkerEngine.downscaleAndEncodeBitmap(
                                sourceBitmap = bmp,
                                targetWidth = 512,
                                targetHeight = 512,
                                quality = 70
                            )
                            if (!bmp.isRecycled) {
                                bmp.recycle()
                            }
                            injectVisionFrame(encoded.base64Jpeg)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Screen vision stream error: ${e.message}")
                }
                delay(1000) // 1 frame per second (1fps)
            }
        }
    }

    fun stopScreenVisionStream() {
        screenVisionJob?.cancel()
        screenVisionJob = null
        _isScreenVisionActive.value = false
        Log.i(TAG, "stopScreenVisionStream: screen vision terminated")
    }

    private fun captureScreenBitmap(): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.BLACK)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.CYAN
            textSize = 28f
            isAntiAlias = true
        }
        canvas.drawText("OMNI LIVE SCREEN VISION", 40f, 60f, paint)
        paint.textSize = 20f
        paint.color = android.graphics.Color.WHITE
        canvas.drawText("Status: Streaming 1 fps to Gemini Live", 40f, 110f, paint)
        canvas.drawText("Time: ${System.currentTimeMillis()}", 40f, 150f, paint)
        val screenJson = androidBridge.getLiveScreenTreeJson()
        val truncated = if (screenJson.length > 80) screenJson.take(80) + "..." else screenJson
        paint.color = android.graphics.Color.LTGRAY
        paint.textSize = 14f
        canvas.drawText("Screen: $truncated", 40f, 190f, paint)
        return bmp
    }

    private val _smartContacts = MutableStateFlow(SmartContactAndAppRegistry.getAllContacts(context))
    val smartContacts: StateFlow<List<SmartContactEntry>> = _smartContacts.asStateFlow()

    private val _activeModelLabel = MutableStateFlow("gemini-2.5-flash-native-audio-preview-09-2025")
    val activeModelLabel: StateFlow<String> = _activeModelLabel.asStateFlow()

    val operatingMode: StateFlow<OmniOperatingMode> = OmniAutonomousCoreEngine.operatingMode
    val idleSecondsRemaining: StateFlow<Int> = OmniAutonomousCoreEngine.idleSecondsRemaining
    val moodAndToneState: StateFlow<MoodAndToneState> = OmniAutonomousCoreEngine.moodAndToneState
    val permanentMemory: StateFlow<OmniPermanentMemory> = OmniAutonomousCoreEngine.permanentMemory

    var onSlideUpCardAdded: ((LiveSlideUpCard) -> Unit)? = null
    var onTranscriptTurnCompleted: ((String, String) -> Unit)? = null

    val toolDispatcher: com.omnivoice.agentfix.ToolDispatcher =
        com.omnivoice.agentfix.ToolDispatcher.getInstance(context)

    private var nativeSpeechRecognizer: SpeechRecognizer? = null
    private var isNativeRecognizerListening = false
    private val _liveSessionStartCount = java.util.concurrent.atomic.AtomicInteger(0)
    val liveSessionStartCount: Int
        get() = _liveSessionStartCount.get()

    fun resetLiveSessionStartCountForTest() {
        _liveSessionStartCount.set(0)
    }

    init {
        instance = this
        com.omnivoice.agentfix.AgentBridge.appContext = context.applicationContext
        try {
            OmniAutonomousCoreEngine.initialize(context)
        } catch (_: Exception) {
        }
        scope.launch(Dispatchers.IO) {
            try {
                JarvisHandsFreeEngine.autoInjectBridgeContactsIfPermitted(context)
                _smartContacts.value = SmartContactAndAppRegistry.getAllContacts(context)
            } catch (_: Exception) {
            }
        }
    }

    fun refreshSmartContacts() {
        JarvisHandsFreeEngine.autoInjectBridgeContactsIfPermitted(context)
        _smartContacts.value = SmartContactAndAppRegistry.getAllContacts(context)
    }

    fun saveOrUpdateSmartContact(
        name: String,
        phoneNumber: String = "",
        whatsappNumber: String = "",
        instagramHandle: String = "",
        facebookProfile: String = "",
        tiktokHandle: String = ""
    ): SmartContactEntry {
        val saved = SmartContactAndAppRegistry.saveOrUpdateContact(
            context = context,
            name = name,
            phoneNumber = phoneNumber,
            whatsappNumber = whatsappNumber,
            instagramHandle = instagramHandle,
            facebookProfile = facebookProfile,
            tiktokHandle = tiktokHandle
        )
        _smartContacts.value = SmartContactAndAppRegistry.getAllContacts(context)
        return saved
    }

    fun updateSmartContactById(
        contactId: Long,
        name: String,
        phoneNumber: String,
        whatsappNumber: String,
        instagramHandle: String,
        facebookProfile: String,
        tiktokHandle: String
    ) {
        _smartContacts.value = SmartContactAndAppRegistry.updateContactById(
            context = context,
            contactId = contactId,
            name = name,
            phoneNumber = phoneNumber,
            whatsappNumber = whatsappNumber,
            instagramHandle = instagramHandle,
            facebookProfile = facebookProfile,
            tiktokHandle = tiktokHandle
        )
    }

    fun deleteSmartContact(contactId: Long) {
        _smartContacts.value = SmartContactAndAppRegistry.deleteContact(context, contactId)
    }

    fun getRawOmniContactsDbJson(): String {
        return SmartContactAndAppRegistry.getRawOmniContactsDbJson(context)
    }

    fun importOmniContactsDbJson(rawJson: String) {
        _smartContacts.value = SmartContactAndAppRegistry.importOmniContactsDbJson(context, rawJson)
        JarvisHandsFreeEngine.syncMirrorToJarvisContactsKey(context)
    }

    fun syncAndroidPhoneContacts(): List<SmartContactEntry> {
        val synced = JarvisHandsFreeEngine.syncRealAndroidPhoneContacts(context)
        _smartContacts.value = synced
        return synced
    }

    fun importVCardContacts(vcfText: String): List<SmartContactEntry> {
        val imported = JarvisHandsFreeEngine.importVCardString(context, vcfText)
        _smartContacts.value = imported
        return imported
    }

    fun getAutomationWebhookUrl(): String {
        return JarvisHandsFreeEngine.getAutomationWebhookUrl(context)
    }

    fun setAutomationWebhookUrl(url: String) {
        JarvisHandsFreeEngine.setAutomationWebhookUrl(context, url)
    }

    fun launchCardDeepLinkNow(card: LiveSlideUpCard) {
        SmartContactAndAppRegistry.launchUniversalDeepLink(
            context = context,
            deepLinkUri = card.deepLinkUri,
            fallbackPackage = card.targetPackage,
            actionExecutor = actionExecutor
        )
    }

    /**
     * Starts the Gemini Live bidirectional voice streaming session.
     * Enforces single-owner audio pipeline by immediately stopping local TTS and acquiring
     * single-owner microphone arbitration via [AssistantAudioStateMachine.shared].
     */
    fun startLiveSession() {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) {
            Log.w(TAG, "Cannot start Gemini Live without RECORD_AUDIO permission")
            AssistantAudioStateMachine.shared.inspectWakeAndAssistantReadiness(context)
            return
        }

        val coord = AssistantAudioStateMachine.shared
        coord.armUserSessionAndPersist(context, "START_GEMINI_LIVE_SESSION")

        // Single audio pipeline rule: cancel local TTS immediately so it never talks over Gemini Live
        ttsFallback?.stop()
        OmniAutonomousCoreEngine.resetInactivityTimer()

        if (_isSessionActive.value) {
            if (OmniAutonomousCoreEngine.operatingMode.value == OmniOperatingMode.STATE_SLEEP_STANDBY) {
                wakeUpFromStandby()
            }
            return
        }
        _isSessionActive.value = true
        _liveSessionStartCount.incrementAndGet()
        _isMicMuted.value = false
        _connectionState.value = LiveConnectionState.CONNECTING
        _liveUserTranscript.value = ""
        val assistantName = OmniAutonomousCoreEngine.permanentMemory.value.assistantName
        _liveAiTranscript.value = "$assistantName Live Active (${_selectedVoice.value.voiceName}) • Listening..."
        triedFallbackModel = false

        JarvisHandsFreeEngine.setKeepAliveCarrierActive(true)
        start24kHzGaplessOutputQueue()
        connectBidiWebSocket(LIVE_MODEL_PRIMARY)

        val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (_: Exception) { "" }
        val hasCloudKey = apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY" && !apiKey.startsWith("YOUR_")
        if (!hasCloudKey && isNativeSpeechRecognitionAvailableSafe()) {
            // Open an explicit bounded command window for single-turn recognition (NEVER a continuous SpeechRecognizer loop!)
            coord.openCommandWindow(reason = "START_SESSION_BOUNDED_COMMAND_WINDOW")
            startNativeSpeechRecognizerLoop()
        } else {
            coord.acquireMicrophoneOwnership(MicComponentOwner.LIVE_SESSION_ENGINE) {
                releaseMicAudioRecord()
                stopNativeSpeechRecognizerLoop()
            }
            start16kHzMicrophonePcmStream()
        }
        start60SecondInactivityWatchdog()
    }

    private fun isNativeSpeechRecognitionAvailableSafe(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Starts a single-turn, bounded command-window Android SpeechRecognizer session on the main thread
     * when explicitly triggered by the user or a local wake event ("Jarvis").
     *
     * CRITICAL ARCHITECTURE INVARIANT:
     * A continuously restarted SpeechRecognizer session is NOT a wake detector.
     * This method runs ONLY for an active command window, never auto-restarts on error or silence,
     * and returns microphone ownership to wake-only listening (`WakeWordForegroundService`) upon completion or timeout.
     */
    fun startNativeSpeechRecognizerLoop() {
        mainHandler.post {
            val coord = AssistantAudioStateMachine.shared
            if (!_isSessionActive.value ||
                _isMicMuted.value ||
                coord.isStoppedByUserInMemory() ||
                coord.isDeviceMutedInMemory() ||
                coord.isSelfTriggerSuppressed()
            ) {
                return@post
            }
            if (!coord.isCommandWindowOpen()) {
                coord.openCommandWindow(reason = "EXPLICIT_COMMAND_WINDOW_STT")
            }
            val acquired = coord.acquireMicrophoneOwnership(MicComponentOwner.COMMAND_WINDOW_CAPTURE) {
                stopNativeSpeechRecognizerLoop()
            }
            if (!acquired) return@post

            try {
                if (nativeSpeechRecognizer == null) {
                    nativeSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {
                                isNativeRecognizerListening = true
                                if (_connectionState.value != LiveConnectionState.LIVE_AI_SPEAKING) {
                                    _connectionState.value = LiveConnectionState.LIVE_LISTENING
                                }
                            }

                            override fun onBeginningOfSpeech() {
                                OmniAutonomousCoreEngine.resetInactivityTimer()
                            }

                            override fun onRmsChanged(rmsdB: Float) {
                                val scaled = ((rmsdB + 2f) * 8f).coerceIn(0f, 100f)
                                _inputAudioRms.value = scaled
                            }

                            override fun onBufferReceived(buffer: ByteArray?) {}

                            override fun onEndOfSpeech() {
                                isNativeRecognizerListening = false
                                _inputAudioRms.value = 0f
                            }

                            override fun onError(error: Int) {
                                isNativeRecognizerListening = false
                                _inputAudioRms.value = 0f
                                // NEVER auto-restart SpeechRecognizer in a loop!
                                // Return cleanly to wake-only listening when the bounded command window ends or errors.
                                coord.returnToWakeOnlyListening("STT_BOUNDED_WINDOW_ERROR_$error")
                                if (com.example.service.WakeWordForegroundService.isServiceActive.value) {
                                    com.example.service.WakeWordForegroundService.resume(context)
                                }
                            }

                            override fun onResults(results: Bundle?) {
                                isNativeRecognizerListening = false
                                _inputAudioRms.value = 0f
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val topText = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                                if (topText.isNotBlank()) {
                                    scope.launch {
                                        processConsentedVoiceAudioTurn(topText)
                                    }
                                } else {
                                    coord.returnToWakeOnlyListening("STT_EMPTY_RESULT_RETURN_TO_WAKE")
                                    if (com.example.service.WakeWordForegroundService.isServiceActive.value) {
                                        com.example.service.WakeWordForegroundService.resume(context)
                                    }
                                }
                            }

                            override fun onPartialResults(partialResults: Bundle?) {
                                val partial = partialResults
                                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                                    ?.trim()
                                    .orEmpty()
                                if (partial.isNotBlank()) {
                                    _liveUserTranscript.value = partial
                                }
                            }

                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })
                    }
                }
                if (!isNativeRecognizerListening && _connectionState.value != LiveConnectionState.LIVE_AI_SPEAKING) {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK")
                        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "ur-PK", "hi-IN"))
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 420L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 320L)
                    }
                    nativeSpeechRecognizer?.startListening(intent)
                    isNativeRecognizerListening = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native SpeechRecognizer fallback to 16kHz PCM stream: ${e.message}")
                isNativeRecognizerListening = false
                if (micStreamingJob?.isActive != true) {
                    start16kHzMicrophonePcmStream()
                }
            }
        }
    }

    fun stopNativeSpeechRecognizerLoop() {
        mainHandler.post {
            try {
                isNativeRecognizerListening = false
                nativeSpeechRecognizer?.stopListening()
                nativeSpeechRecognizer?.cancel()
                nativeSpeechRecognizer?.destroy()
            } catch (_: Exception) {
            } finally {
                nativeSpeechRecognizer = null
                AssistantAudioStateMachine.shared.releaseMicrophoneOwnership(MicComponentOwner.COMMAND_WINDOW_CAPTURE)
            }
        }
    }

    /**
     * Strict 60-Second (`idleSecondsRemaining = 60`) Inactivity Sleep Timer:
     * Counts down while in `STATE_ACTIVE_LISTENING` when neither user nor AI is speaking.
     * At 0s, transitions to `STATE_SLEEP_STANDBY` and pauses the heavy Gemini Live WebSocket to save API quota.
     */
    var alwaysOnlineContinuousMode: Boolean = true

    private fun start60SecondInactivityWatchdog() {
        inactivityTimerJob?.cancel()
        inactivityTimerJob = scope.launch {
            while (isActive && _isSessionActive.value) {
                delay(1000L)
                if (alwaysOnlineContinuousMode) {
                    OmniAutonomousCoreEngine.resetInactivityTimer()
                    // Always-Online Continuous Mode: Auto-reconnect seamlessly if socket ever dropped
                    if (activeWebSocket == null && OmniAutonomousCoreEngine.refreshOnlineState(context)) {
                        Log.i(TAG, "Always-Online Continuous Mode: Auto-reconnecting Live WebSocket session...")
                        connectBidiWebSocket(LIVE_MODEL_PRIMARY)
                    }
                    continue
                }
                if (OmniAutonomousCoreEngine.operatingMode.value == OmniOperatingMode.STATE_SLEEP_STANDBY) {
                    continue
                }
                val isBusy = _connectionState.value == LiveConnectionState.LIVE_AI_SPEAKING ||
                    _connectionState.value == LiveConnectionState.LIVE_EXECUTING_TOOL ||
                    _inputAudioRms.value >= 14f ||
                    pcm24kPlaybackQueue.isNotEmpty()

                if (isBusy) {
                    OmniAutonomousCoreEngine.resetInactivityTimer()
                } else {
                    val enteredStandby = OmniAutonomousCoreEngine.tickInactivityTimerOneSecond()
                    if (enteredStandby) {
                        enterStandbySleepMode()
                    }
                }
            }
        }
    }

    /**
     * Enters `STATE_SLEEP_STANDBY`: pauses Gemini Live WebSocket to save quota while keeping local wake-word listener ready.
     */
    fun enterStandbySleepMode() {
        OmniAutonomousCoreEngine.enterSleepStandbyMode()
        try {
            activeWebSocket?.close(1000, "Entering 60s Inactivity Standby Mode")
        } catch (_: Exception) {
        }
        activeWebSocket = null
        isWebSocketReady.set(false)
        clearPlaybackQueueAndStopImmediately()
        val assistantName = OmniAutonomousCoreEngine.permanentMemory.value.assistantName
        _connectionState.value = LiveConnectionState.LIVE_LISTENING
        _liveAiTranscript.value = "Standby Mode — Say 'Wake up $assistantName' to activate"
        JarvisHandsFreeEngine.appendTerminalLog(
            layerTag = "SLEEP-STANDBY",
            command = "ENTER_STANDBY_MODE(60s_IDLE)",
            detail = "Paused Gemini Live WebSocket • Listening locally for 'Wake up $assistantName'",
            status = "STANDBY"
        )
    }

    /**
     * Wakes from `STATE_SLEEP_STANDBY` back to `STATE_ACTIVE_LISTENING`, plays futuristic chime,
     * reconnects Gemini Live WebSocket, and speaks "Jee, main hazir hoon! Hukum karein."
     */
    fun wakeUpFromStandby(initialCommandAfterWake: String = "") {
        val wakeGreeting = OmniAutonomousCoreEngine.wakeUpToActiveListening(context)
        _liveAiTranscript.value = wakeGreeting
        _connectionState.value = LiveConnectionState.LIVE_LISTENING
        if (activeWebSocket == null && OmniAutonomousCoreEngine.refreshOnlineState(context)) {
            triedFallbackModel = false
            connectBidiWebSocket(LIVE_MODEL_PRIMARY)
        } else {
            ttsFallback?.speak(wakeGreeting, 1.0f, 1.0f, DetectedLanguage.ROMAN_URDU.ttsLocale)
        }
        if (initialCommandAfterWake.isNotBlank()) {
            sendTextMessageToLiveSession(initialCommandAfterWake)
        }
    }

    /**
     * Auto-Resume on Focus / Visibility Return (`visibilitychange` / `window.onfocus` / `onResume`):
     * Immediately resumes the 24kHz AudioTrack + 1Hz sub-audible carrier, restarts 16kHz mic capture if paused,
     * and reconnects the Gemini Live WebSocket if Android paused or dropped it while in WhatsApp.
     */
    fun ensureSessionAliveOnResume() {
        val coord = AssistantAudioStateMachine.shared
        // Respect user's armed/stopped/muted choice on resume!
        if (!_isSessionActive.value ||
            coord.isStoppedByUserPersisted(context) ||
            !coord.isUserArmedPersisted(context) ||
            coord.isDeviceMutedPersisted(context)
        ) {
            return
        }
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) {
            coord.inspectWakeAndAssistantReadiness(context)
            return
        }

        JarvisHandsFreeEngine.setKeepAliveCarrierActive(true)
        try {
            outputAudioTrack?.play()
        } catch (_: Exception) {
        }
        if (outputPlaybackJob?.isActive != true) {
            start24kHzGaplessOutputQueue()
        }
        if (!_isMicMuted.value && !isNativeRecognizerListening && micStreamingJob?.isActive != true) {
            val apiKey = try { BuildConfig.GEMINI_API_KEY } catch (_: Exception) { "" }
            val hasCloudKey = apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY" && !apiKey.startsWith("YOUR_")
            if (hasCloudKey) {
                coord.acquireMicrophoneOwnership(MicComponentOwner.LIVE_SESSION_ENGINE) {
                    releaseMicAudioRecord()
                    stopNativeSpeechRecognizerLoop()
                }
                start16kHzMicrophonePcmStream()
            }
        }
        if (inactivityTimerJob?.isActive != true) {
            start60SecondInactivityWatchdog()
        }
        if (activeWebSocket == null && OmniAutonomousCoreEngine.operatingMode.value == OmniOperatingMode.STATE_ACTIVE_LISTENING) {
            connectBidiWebSocket(LIVE_MODEL_PRIMARY)
        }
        JarvisHandsFreeEngine.appendLog(
            category = "KEEP_ALIVE",
            line = "Foreground focus restored: AudioContext + 1Hz Carrier + Live WebSocket verified active."
        )
    }

    /**
     * Ends the Gemini Live session and releases audio & WebSocket resources.
     */
    fun endLiveSession() {
        _isSessionActive.value = false
        _isMicMuted.value = false
        isWebSocketReady.set(false)
        inactivityTimerJob?.cancel()
        inactivityTimerJob = null
        JarvisHandsFreeEngine.setKeepAliveCarrierActive(false)
        _connectionState.value = LiveConnectionState.DISCONNECTED
        _inputAudioRms.value = 0f
        _outputAudioRms.value = 0f
        _activeFrequencyBands.value = List(8) { 0.08f }

        try {
            activeWebSocket?.close(1000, "User ended Gemini Live session")
        } catch (_: Exception) {
        }
        activeWebSocket = null

        micStreamingJob?.cancel()
        micStreamingJob = null
        releaseMicAudioRecord()
        stopNativeSpeechRecognizerLoop()
        AssistantAudioStateMachine.shared.releaseMicrophoneOwnership(MicComponentOwner.LIVE_SESSION_ENGINE)
        AssistantAudioStateMachine.shared.releaseMicrophoneOwnership(MicComponentOwner.COMMAND_WINDOW_CAPTURE)

        clearPlaybackQueueAndStopImmediately()
        outputPlaybackJob?.cancel()
        outputPlaybackJob = null
        releaseOutputAudioTrack()
    }

    /**
     * Toggles Pause/Mute on the 16kHz live microphone stream and pins mute state to the device.
     */
    fun toggleMuteMic(): Boolean {
        val nextMute = !_isMicMuted.value
        _isMicMuted.value = nextMute
        AssistantAudioStateMachine.shared.setDeviceMutedPinned(context, nextMute, "LIVE_MUTE_TOGGLE")
        if (nextMute) {
            _inputAudioRms.value = 0f
            releaseMicAudioRecord()
            stopNativeSpeechRecognizerLoop()
        }
        return nextMute
    }

    /**
     * Switches between `'Aoede'`, `'Puck'`, `'Charon'`, `'Kore'`, and `'Fenrir'`.
     * If a Live session is active, reconnects the session with the new `voiceName`.
     */
    fun cycleVoice(): GeminiLiveVoice {
        val nextVoice = _selectedVoice.value.next()
        _selectedVoice.value = nextVoice
        if (_isSessionActive.value) {
            try {
                activeWebSocket?.close(1000, "Switching voice to ${nextVoice.voiceName}")
            } catch (_: Exception) {
            }
            activeWebSocket = null
            isWebSocketReady.set(false)
            triedFallbackModel = false
            _liveAiTranscript.value = "Switched voice to ${nextVoice.voiceName} (${nextVoice.subtitle})"
            connectBidiWebSocket(LIVE_MODEL_PRIMARY)
        }
        return nextVoice
    }

    fun selectVoice(voice: GeminiLiveVoice) {
        _selectedVoice.value = voice
        if (_isSessionActive.value) {
            try {
                activeWebSocket?.close(1000, "Switching voice to ${voice.voiceName}")
            } catch (_: Exception) {
            }
            activeWebSocket = null
            isWebSocketReady.set(false)
            triedFallbackModel = false
            connectBidiWebSocket(LIVE_MODEL_PRIMARY)
        }
    }

    fun dismissSlideUpCard(cardId: Long) {
        _slideUpCards.value = _slideUpCards.value.filterNot { it.id == cardId }
    }

    /**
     * Sends a typed user message into the active Gemini Live session (or executes via Live fallback).
     */
    fun sendTextMessageToLiveSession(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // Handle "stop listening" / "jarvis band karo" to disarm and pin stop to device
        if (AssistantAudioStateMachine.isFullStopListeningPhrase(trimmed)) {
            clearPlaybackQueueAndStopImmediately()
            endLiveSession()
            com.example.service.WakeWordForegroundService.stop(context, pinUserStop = true)
            _liveUserTranscript.value = trimmed
            _liveAiTranscript.value = "Listening stopped."
            return
        }

        // Handle "bas / stop / cancel" to abort pending action / TTS and return to wake-only listening
        if (AssistantAudioStateMachine.matchesCancelOrStopPhrase(trimmed)) {
            clearPlaybackQueueAndStopImmediately()
            activeCommandId = ""
            awaitingVerifiedToolOutcome = false
            currentTurnHasPendingOrExecutedAction = false
            AssistantAudioStateMachine.shared.returnToWakeOnlyListening("USER_CANCEL_BAS_STOP")
            if (com.example.service.WakeWordForegroundService.isServiceActive.value) {
                com.example.service.WakeWordForegroundService.resume(context)
            }
            _liveUserTranscript.value = trimmed
            _liveAiTranscript.value = "Theek hai, ruk gaya hoon."
            return
        }

        // If in Standby Mode, check if user said/typed wake word or wake up automatically
        if (OmniAutonomousCoreEngine.operatingMode.value == OmniOperatingMode.STATE_SLEEP_STANDBY) {
            if (OmniAutonomousCoreEngine.matchesCustomWakeWord(trimmed) ||
                AssistantAudioStateMachine.matchesWakePhrase(trimmed)
            ) {
                val remainder = AssistantAudioStateMachine.extractCommandAfterWakePhrase(trimmed)
                wakeUpFromStandby(remainder)
                return
            } else {
                wakeUpFromStandby()
            }
        }

        // Strip leading wake word ("Jarvis, WhatsApp kholo" -> "WhatsApp kholo") if present
        val effectiveUtterance = if (AssistantAudioStateMachine.matchesWakePhrase(trimmed)) {
            val afterWake = AssistantAudioStateMachine.extractCommandAfterWakePhrase(trimmed)
            if (afterWake.isBlank()) {
                AssistantAudioStateMachine.shared.openCommandWindow(reason = "WAKE_WORD_JARVIS")
                _liveUserTranscript.value = trimmed
                _liveAiTranscript.value = "Jee, main sun raha hoon."
                return
            }
            afterWake
        } else {
            trimmed
        }

        OmniAutonomousCoreEngine.resetInactivityTimer()
        OmniAutonomousCoreEngine.analyzeUserMood(effectiveUtterance, _inputAudioRms.value)
        val turnCommandId = startNewCommandTurn(utterance = effectiveUtterance)

        _liveUserTranscript.value = effectiveUtterance
        _liveAiTranscript.value = ""
        clearPlaybackQueueAndStopImmediately()

        // Check direct voice rename or permanent rule ("Tumhara naam aaj se Sikandar hai")
        val renameOrRuleReply = OmniAutonomousCoreEngine.detectAndApplyVoiceRenameOrRule(context, trimmed)
        if (renameOrRuleReply != null) {
            _liveAiTranscript.value = renameOrRuleReply
            val userLang = LanguageProcessor.detectLanguage(trimmed)
            if (isGeminiLiveWebSocketConnected()) {
                recordTurnExecutionReport(
                    commandId = turnCommandId,
                    utterance = trimmed,
                    spokenText = renameOrRuleReply,
                    diagnosticText = renameOrRuleReply,
                    finalStatus = "CONVERSATIONAL",
                    outputEngine = "GEMINI_LIVE (${_selectedVoice.value.voiceName})",
                    language = userLang,
                    liveConnected = true
                )
            } else {
                ttsFallback?.speak(renameOrRuleReply, 1.0f, 1.0f, userLang.ttsLocale)
                recordTurnExecutionReport(
                    commandId = turnCommandId,
                    utterance = trimmed,
                    spokenText = renameOrRuleReply,
                    diagnosticText = renameOrRuleReply,
                    finalStatus = "CONVERSATIONAL",
                    outputEngine = "ANDROID_TTS",
                    language = userLang,
                    liveConnected = false
                )
            }
            return
        }

        // Check Offline + Online Prerequisite Resolver (Wi-Fi/Data recovery & password auto-typing)
        val prerequisiteReply = OmniAutonomousCoreEngine.handlePrerequisiteOrOfflineFlow(
            context = context,
            utterance = trimmed,
            bridge = androidBridge,
            actionExecutor = actionExecutor,
            onAutoResumeCompleted = { _, resumed ->
                if (activeCommandId == turnCommandId) {
                    _liveAiTranscript.value = resumed
                }
            }
        )
        if (prerequisiteReply != null) {
            _liveAiTranscript.value = prerequisiteReply
            val lang = LanguageProcessor.detectLanguage(trimmed.ifBlank { prerequisiteReply })
            if (!isGeminiLiveWebSocketConnected()) {
                ttsFallback?.speak(prerequisiteReply, 1.0f, 1.0f, lang.ttsLocale)
            }
            return
        }

        // SUPER-FAST LOCAL TASK EXECUTION (< 5ms):
        // If the utterance contains any deterministic device task (play video, search, open app, flashlight, WhatsApp, etc.),
        // execute it immediately via processFallbackLiveTurn without waiting for WebSocket round-trip latency!
        val instantDeviceTasks = SuperAgiBrainEngine.extractAllDeviceTasks(context, trimmed)
        val ws = activeWebSocket
        val isOnlineOrLiveReady = (ws != null && isWebSocketReady.get()) || OmniAutonomousCoreEngine.refreshOnlineState(context)
        if (instantDeviceTasks.isNotEmpty() || ws == null || !isWebSocketReady.get() || !isOnlineOrLiveReady) {
            scope.launch {
                processFallbackLiveTurn(trimmed, null, turnCommandId)
            }
        } else {
            val clientContentMsg = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", trimmed))
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            val serializedClientContent = clientContentMsg.toString()
            lastSentClientContentJson = serializedClientContent
            ws.send(serializedClientContent)
        }
    }

    // =========================================================================
    // 1. GEMINI MULTIMODAL LIVE WEBSOCKET (`BidiGenerateContent`)
    // =========================================================================
    private fun connectBidiWebSocket(modelName: String) {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (_: Exception) {
            ""
        }

        val assistantName = OmniAutonomousCoreEngine.permanentMemory.value.assistantName

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY" || !OmniAutonomousCoreEngine.refreshOnlineState(context)) {
            Log.i(TAG, "Running Hybrid Live Streaming mode (online=${OmniAutonomousCoreEngine.isNetworkOnline.value})")
            _connectionState.value = LiveConnectionState.LIVE_LISTENING
            _liveAiTranscript.value = "$assistantName Live Ready (${_selectedVoice.value.voiceName}) • Speak in English, اردو, or Roman Urdu"
            return
        }

        val wsUrl =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        _activeModelLabel.value = modelName.removePrefix("models/")

        activeWebSocket = wsHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Gemini Live WebSocket opened for model=$modelName")
                    val mem = OmniAutonomousCoreEngine.loadPermanentMemory(context)
                    val mood = OmniAutonomousCoreEngine.moodAndToneState.value
                    val setupJson = buildLiveSetupPayload(
                        modelName = modelName,
                        voiceName = _selectedVoice.value.voiceName,
                        savedContactsSummary = SmartContactAndAppRegistry.buildContactsSummaryForPrompt(context),
                        assistantName = mem.assistantName,
                        learnedRulesSummary = mem.learnedRules.joinToString("; "),
                        moodDirective = mood.systemPromptDirective
                    )
                    webSocket.send(setupJson.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerJsonMessage(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleServerJsonMessage(webSocket, bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Gemini Live WebSocket closing ($code): $reason")
                    recordLiveWebSocketDisconnect(code, reason.ifBlank { "WebSocket closing" })
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Gemini Live WebSocket closed ($code): $reason")
                    recordLiveWebSocketDisconnect(code, reason.ifBlank { "WebSocket closed" })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Gemini Live WebSocket failure ($modelName): ${t.message}")
                    val closeCode = response?.code ?: 1006
                    val closeReason = t.message ?: response?.message ?: "WebSocket transport failure"
                    recordLiveWebSocketDisconnect(closeCode, closeReason)
                    if (_isSessionActive.value && !triedFallbackModel) {
                        triedFallbackModel = true
                        connectBidiWebSocket(LIVE_MODEL_FALLBACK)
                    } else if (_isSessionActive.value) {
                        _connectionState.value = LiveConnectionState.LIVE_LISTENING
                        if (_liveAiTranscript.value.startsWith("Connecting")) {
                            _liveAiTranscript.value =
                                "$assistantName Live Active (${_selectedVoice.value.voiceName}) • Speak naturally"
                        }
                    }
                }
            }
        )
    }

    private fun handleServerJsonMessage(webSocket: WebSocket, rawJson: String) {
        try {
            val root = JSONObject(rawJson)

            // 1. setupComplete
            if (root.has("setupComplete")) {
                isWebSocketReady.set(true)
                _connectionState.value = LiveConnectionState.LIVE_LISTENING
                val assistantName = OmniAutonomousCoreEngine.permanentMemory.value.assistantName
                _liveAiTranscript.value =
                    "$assistantName Live Connected (${_selectedVoice.value.voiceName}) • Listening..."
                Log.d(TAG, "Gemini Live setupComplete received")
                return
            }

            val hasEmbeddedToolCall = root.optJSONObject("toolCall") != null ||
                root.optJSONObject("serverContent")?.optJSONObject("toolCall") != null ||
                (root.optJSONObject("serverContent")?.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { partsArr ->
                    (0 until partsArr.length()).any { idx -> partsArr.optJSONObject(idx)?.optJSONObject("functionCall") != null }
                } == true)
            if (hasEmbeddedToolCall) {
                val hasInlineInputTranscription = root.optJSONObject("serverContent")
                    ?.optJSONObject("inputTranscription")
                    ?.optString("text", "")
                    ?.isNotEmpty() == true
                if (currentVoiceTurnCompleted && !hasInlineInputTranscription) {
                    startNewCommandTurn()
                    _liveUserTranscript.value = ""
                    com.omnivoice.agentfix.AgentSession.onUserUtterance("")
                }
                currentTurnHasPendingOrExecutedAction = true
                awaitingVerifiedToolOutcome = true
                clearPlaybackQueueAndStopImmediately()
            }

            // 2. serverContent (Audio output, input/output transcriptions, interruption, turnComplete)
            val serverContent = root.optJSONObject("serverContent")
            if (serverContent != null) {
                OmniAutonomousCoreEngine.resetInactivityTimer()
                // Strict single-owner audio: ensure local TTS is stopped while Gemini Live streams
                ttsFallback?.stop()

                // Check for user barge-in interruption (`serverContent.interrupted === true`)
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Gemini Live interrupted by user speech — clearing 24kHz audio queue")
                    clearPlaybackQueueAndStopImmediately()
                    _connectionState.value = LiveConnectionState.LIVE_LISTENING
                }

                // Real-time input audio transcription (what the user is saying)
                val inputTrans = serverContent.optJSONObject("inputTranscription")
                if (inputTrans != null) {
                    val userChunk = inputTrans.optString("text", "")
                    if (userChunk.isNotEmpty()) {
                        if (currentVoiceTurnCompleted) {
                            startNewCommandTurn()
                            _liveUserTranscript.value = ""
                        }
                        _liveUserTranscript.value = (_liveUserTranscript.value + userChunk).trimStart()
                        com.omnivoice.agentfix.AgentSession.onUserUtterance(_liveUserTranscript.value)
                        OmniAutonomousCoreEngine.analyzeUserMood(_liveUserTranscript.value, _inputAudioRms.value)
                    }
                }

                // Real-time output audio transcription (what Gemini is saying)
                val outputTrans = serverContent.optJSONObject("outputTranscription")
                if (outputTrans != null) {
                    val aiChunk = outputTrans.optString("text", "")
                    if (aiChunk.isNotEmpty()) {
                        val candidateTranscript = if (_liveAiTranscript.value.contains("Live Connected") ||
                            _liveAiTranscript.value.contains("Live Active") ||
                            _liveAiTranscript.value.startsWith("Connecting")
                        ) {
                            aiChunk.trimStart()
                        } else {
                            _liveAiTranscript.value + aiChunk
                        }
                        if (isCompletionClaimBeforeVerifiedResult(aiChunk) || isCompletionClaimBeforeVerifiedResult(candidateTranscript)) {
                            clearPlaybackQueueAndStopImmediately()
                            if (lastCompletedCommandId == activeCommandId &&
                                lastFinalStatusForCurrentCommand.isNotBlank() &&
                                !lastFinalStatusForCurrentCommand.equals("VERIFIED", ignoreCase = true) &&
                                lastSpokenReplyForCurrentCommand.isNotBlank()
                            ) {
                                _liveAiTranscript.value = lastSpokenReplyForCurrentCommand
                            }
                        } else {
                            _liveAiTranscript.value = candidateTranscript
                        }
                    }
                }

                // Model Turn parts (24kHz PCM audio chunks, text, or embedded functionCall)
                val modelTurn = serverContent.optJSONObject("modelTurn")
                val parts = modelTurn?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue

                        // Inline 24kHz PCM native audio chunk
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null && !awaitingVerifiedToolOutcome) {
                            val mimeType = inlineData.optString("mimeType", "")
                            val base64Audio = inlineData.optString("data", "")
                            if (mimeType.startsWith("audio/pcm") && base64Audio.isNotEmpty()) {
                                val (pcmBytes, floatSamples) = decodeBase64Pcm16ToFloat32(base64Audio)
                                updateVisualizerFromFloatSamples(floatSamples, isAiOutput = true)
                                enqueue24kHzPcmChunk(pcmBytes)
                            }
                        }

                        // Text part (skip internal thought parts if marked `thought: true`)
                        val isThought = part.optBoolean("thought", false)
                        val partText = part.optString("text", "")
                        if (!isThought && partText.isNotEmpty()) {
                            val candidateText = if (_liveAiTranscript.value.contains("Live Connected") ||
                                _liveAiTranscript.value.contains("Live Active") ||
                                _liveAiTranscript.value.startsWith("Connecting")
                            ) {
                                partText
                            } else if (!_liveAiTranscript.value.contains(partText)) {
                                (_liveAiTranscript.value + partText).trim()
                            } else {
                                _liveAiTranscript.value
                            }
                            if (isCompletionClaimBeforeVerifiedResult(partText) || isCompletionClaimBeforeVerifiedResult(candidateText)) {
                                clearPlaybackQueueAndStopImmediately()
                                if (lastCompletedCommandId == activeCommandId &&
                                    lastFinalStatusForCurrentCommand.isNotBlank() &&
                                    !lastFinalStatusForCurrentCommand.equals("VERIFIED", ignoreCase = true) &&
                                    lastSpokenReplyForCurrentCommand.isNotBlank()
                                ) {
                                    _liveAiTranscript.value = lastSpokenReplyForCurrentCommand
                                }
                            } else {
                                _liveAiTranscript.value = candidateText
                            }
                        }

                        // Function call inside modelTurn part (fallback)
                        val fnCall = part.optJSONObject("functionCall")
                        if (fnCall != null && root.optJSONObject("toolCall") == null && serverContent.optJSONObject("toolCall") == null) {
                            scope.launch {
                                executeLiveFunctionCallAndRespond(webSocket, fnCall)
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    currentVoiceTurnCompleted = true
                    val uText = _liveUserTranscript.value.trim()
                    val aText = _liveAiTranscript.value.trim()
                    if (uText.isNotEmpty()) {
                        com.omnivoice.agentfix.AgentSession.onUserUtterance(uText)
                    }
                    val turnLang = LanguageProcessor.detectLanguage(uText.ifBlank { aText })
                    val liveConn = isGeminiLiveWebSocketConnected()
                    recordTurnExecutionReport(
                        commandId = activeCommandId,
                        utterance = uText,
                        spokenText = aText,
                        diagnosticText = lastDiagnosticTextForCurrentCommand.ifBlank { aText },
                        finalStatus = if (currentTurnHasPendingOrExecutedAction) {
                            lastFinalStatusForCurrentCommand.ifBlank { "VERIFIED" }
                        } else {
                            "CONVERSATIONAL"
                        },
                        outputEngine = if (liveConn) {
                            "GEMINI_LIVE (${_selectedVoice.value.voiceName})"
                        } else {
                            "ANDROID_TTS"
                        },
                        language = turnLang,
                        liveConnected = liveConn
                    )
                    if (uText.isNotEmpty() || aText.isNotEmpty()) {
                        mainHandler.post {
                            onTranscriptTurnCompleted?.invoke(uText, aText)
                        }
                    }
                }
            }

            // 3. CRITICAL: Catch tool calls from TOP-LEVEL toolCall.functionCalls OR serverContent.toolCall.functionCalls
            val topToolCall = root.optJSONObject("toolCall")
            val serverToolCall = serverContent?.optJSONObject("toolCall")
            val rawFunctionCalls = topToolCall?.optJSONArray("functionCalls")
                ?: serverToolCall?.optJSONArray("functionCalls")

            if (rawFunctionCalls != null && rawFunctionCalls.length() > 0) {
                OmniAutonomousCoreEngine.resetInactivityTimer()
                scope.launch {
                    val responsesArray = JSONArray()
                    for (i in 0 until rawFunctionCalls.length()) {
                        val fnCall = rawFunctionCalls.optJSONObject(i) ?: continue
                        val singleResp = executeLiveFunctionCallSingle(fnCall)
                        responsesArray.put(singleResp)
                    }
                    if (responsesArray.length() > 0) {
                        val toolResponsePayload = JSONObject().apply {
                            put("toolResponse", JSONObject().apply {
                                put("functionResponses", responsesArray)
                            })
                        }
                        val serializedToolResponse = toolResponsePayload.toString()
                        lastSentToolResponseJson = serializedToolResponse
                        if (isWebSocketReady.get()) {
                            Log.d(TAG, "Sent Live toolResponse: $serializedToolResponse")
                            webSocket.send(serializedToolResponse)
                        }
                    }
                    _connectionState.value = LiveConnectionState.LIVE_LISTENING
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Gemini Live server message: ${e.message}")
        }
    }

    // =========================================================================
    // 2. REAL-TIME 16kHz PCM MICROPHONE STREAMING (`audio/pcm;rate=16000`)
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun start16kHzMicrophonePcmStream() {
        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        micStreamingJob?.cancel()
        micStreamingJob = scope.launch(Dispatchers.IO) {
            try {
                val recorder = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        INPUT_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                } catch (_: Exception) {
                    null
                } ?: AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord 16kHz failed to initialize")
                    return@launch
                }

                micAudioRecord = recorder
                AssistantAudioStateMachine.shared.applyPreferredInputDevice(context, recorder)
                attachHardwareAudioFilters(recorder.audioSessionId)
                recorder.startRecording()

                // 40ms low-latency chunk at 16kHz = 640 samples (1280 bytes) for ultra-fast streaming
                val shortBuffer = ShortArray(640)
                val byteBuffer = ByteArray(shortBuffer.size * 2)

                // Buffer for hybrid fallback turn detection when WebSocket isn't active
                val fallbackTurnPcmBuffer = ByteArrayOutputStream()
                var fallbackSpeechActive = false
                var fallbackLastVoiceMs = System.currentTimeMillis()
                var fallbackTurnStartMs = System.currentTimeMillis()

                while (isActive && _isSessionActive.value) {
                    val read = recorder.read(shortBuffer, 0, shortBuffer.size)
                    if (read > 0) {
                        if (_isMicMuted.value ||
                            AssistantAudioStateMachine.shared.isDeviceMutedInMemory() ||
                            AssistantAudioStateMachine.shared.isStoppedByUserInMemory() ||
                            AssistantAudioStateMachine.shared.isSelfTriggerSuppressed()
                        ) {
                            _inputAudioRms.value = 0f
                            fallbackSpeechActive = false
                            fallbackTurnPcmBuffer.reset()
                            delay(40)
                            continue
                        }

                        var sumSquares = 0.0
                        val floatSamples = FloatArray(read)
                        for (i in 0 until read) {
                            val s = shortBuffer[i]
                            val norm = (s / 32768.0f).coerceIn(-1f, 1f)
                            floatSamples[i] = norm
                            sumSquares += (norm * norm).toDouble()
                            byteBuffer[i * 2] = (s.toInt() and 0x00FF).toByte()
                            byteBuffer[i * 2 + 1] = ((s.toInt() shr 8) and 0x00FF).toByte()
                        }

                        val rms = sqrt(sumSquares / read).toFloat()
                        val scaledRms = (rms * 320f).coerceIn(0f, 100f)
                        _inputAudioRms.value = scaledRms

                        if (scaledRms >= 14f && OmniAutonomousCoreEngine.operatingMode.value == OmniOperatingMode.STATE_ACTIVE_LISTENING) {
                            OmniAutonomousCoreEngine.resetInactivityTimer()
                        }

                        // If Gemini is not currently speaking, drive the Orb spectrum from the user's voice
                        if (_connectionState.value != LiveConnectionState.LIVE_AI_SPEAKING) {
                            updateVisualizerFromFloatSamples(floatSamples, isAiOutput = false)
                        }

                        // Natural local barge-in detection: if user speaks loudly while Gemini is talking,
                        // clear playback queue so user can interrupt immediately
                        if (scaledRms > 42f && pcm24kPlaybackQueue.isNotEmpty()) {
                            clearPlaybackQueueAndStopImmediately()
                        }

                        val chunkBytes = if (read * 2 == byteBuffer.size) {
                            byteBuffer
                        } else {
                            byteBuffer.copyOfRange(0, read * 2)
                        }

                        val ws = activeWebSocket
                        val isStandby = OmniAutonomousCoreEngine.operatingMode.value == OmniOperatingMode.STATE_SLEEP_STANDBY

                        if (!isStandby && ws != null && isWebSocketReady.get()) {
                            val base64PcmChunk = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)
                            val realtimeInputJson = JSONObject().apply {
                                put("realtimeInput", JSONObject().apply {
                                    put("mediaChunks", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("mimeType", "audio/pcm;rate=16000")
                                            put("data", base64PcmChunk)
                                        })
                                    })
                                })
                            }
                            ws.send(realtimeInputJson.toString())
                        } else {
                            // Local Wake-Word & Hybrid Live Streaming Turn Detection when in Standby or Offline
                            val now = System.currentTimeMillis()
                            if (scaledRms >= 12f) {
                                if (!fallbackSpeechActive) {
                                    fallbackSpeechActive = true
                                    fallbackTurnStartMs = now
                                    fallbackTurnPcmBuffer.reset()
                                    if (!isStandby) {
                                        _liveUserTranscript.value = "Listening..."
                                    }
                                }
                                fallbackLastVoiceMs = now
                                fallbackTurnPcmBuffer.write(chunkBytes)
                            } else if (fallbackSpeechActive) {
                                fallbackTurnPcmBuffer.write(chunkBytes)
                                val silenceMs = now - fallbackLastVoiceMs
                                val durationMs = now - fallbackTurnStartMs
                                if (silenceMs >= 260L && durationMs >= 180L) {
                                    val capturedPcm = fallbackTurnPcmBuffer.toByteArray()
                                    fallbackTurnPcmBuffer.reset()
                                    fallbackSpeechActive = false
                                    if (isStandby) {
                                        // Wake from Standby when voice wake phrase is spoken
                                        wakeUpFromStandby()
                                    } else {
                                        val wavBytes = MultiEngineSpeechManager.buildStandard44ByteRiffWav(
                                            pcmData = capturedPcm,
                                            sampleRate = INPUT_SAMPLE_RATE,
                                            channels = 1,
                                            bitsPerSample = 16
                                        )
                                        scope.launch {
                                            processFallbackLiveTurn(null, wavBytes)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        delay(20)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "16kHz microphone stream error: ${e.message}")
            } finally {
                releaseMicAudioRecord()
            }
        }
    }

    // =========================================================================
    // 3. GAPLESS NATIVE VOICE OUTPUT (24kHz PCM `AudioTrack` Queue)
    // =========================================================================
    private fun start24kHzGaplessOutputQueue() {
        outputPlaybackJob?.cancel()
        outputPlaybackJob = scope.launch(Dispatchers.IO) {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    OUTPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(9600)

                val trackBuilder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUTPUT_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                val track = trackBuilder.build()

                outputAudioTrack = track
                track.play()

                // Pre-compute a 5ms 1Hz ultra-low-amplitude sub-audible carrier wave (120 samples = 240 bytes)
                // so Android OS keeps the audio session alive while responding within 5ms when audio arrives.
                val subAudibleCarrierChunk = ByteArray(240).apply {
                    for (i in 0 until 120) {
                        val sample: Int = if (i % 2 == 0) 1 else -1
                        this[i * 2] = (sample and 0xFF).toByte()
                        this[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                    }
                }

                while (isActive && _isSessionActive.value) {
                    val chunk = pcm24kPlaybackQueue.poll()
                    if (chunk != null) {
                        _connectionState.value = LiveConnectionState.LIVE_AI_SPEAKING
                        track.write(chunk, 0, chunk.size)
                    } else {
                        if (_connectionState.value == LiveConnectionState.LIVE_AI_SPEAKING) {
                            _outputAudioRms.value = 0f
                            _connectionState.value = LiveConnectionState.LIVE_LISTENING
                        }
                        // Continuous 1Hz sub-audible keep-alive carrier prevents Android background suspension
                        track.write(subAudibleCarrierChunk, 0, subAudibleCarrierChunk.size)
                        delay(5)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "24kHz AudioTrack playback error: ${e.message}")
            } finally {
                releaseOutputAudioTrack()
            }
        }
    }

    private fun enqueue24kHzPcmChunk(pcm24kBytes: ByteArray) {
        if (!_isSessionActive.value || pcm24kBytes.isEmpty()) return
        pcm24kPlaybackQueue.offer(pcm24kBytes)
    }

    fun clearPlaybackQueueAndStopImmediately() {
        pcm24kPlaybackQueue.clear()
        _outputAudioRms.value = 0f
        ttsFallback?.stop()
        try {
            outputAudioTrack?.pause()
            outputAudioTrack?.flush()
            outputAudioTrack?.play()
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // 4. LIVE FUNCTION CALLING EXECUTION (`sendToolResponse`)
    // =========================================================================
    private suspend fun executeLiveFunctionCallAndRespond(
        webSocket: WebSocket?,
        fnCall: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        val callId = fnCall.optString("id", "call_${System.currentTimeMillis()}")
        val explicitCommandId = fnCall.optString("commandId", "").trim()
        val rawFnName = fnCall.optString("name", "")
        val args = fnCall.optJSONObject("args") ?: JSONObject()
        val wireFnName = args.optString("_wireFunctionName", "").trim().ifBlank { rawFnName }

        // Reject late callback from a previous command ID before executing or announcing anything
        if (explicitCommandId.isNotEmpty() && activeCommandId.isNotEmpty() && explicitCommandId != activeCommandId) {
            Log.w(TAG, "Ignoring late callback for stale commandId=$explicitCommandId (activeCommandId=$activeCommandId, fn=$rawFnName)")
            val stalePayload = JSONObject().apply {
                put("id", callId)
                put("status", "STALE_COMMAND_IGNORED")
                put("canonicalStatus", "Requested/Unverified")
                put("ok", false)
                put("verified", false)
                put("executed", false)
                put("message", "Ignored late callback from previous command '$explicitCommandId'.")
                put("result", "Ignored late callback from previous command '$explicitCommandId'.")
            }
            return@withContext JSONObject().apply {
                put("id", callId)
                put("name", wireFnName)
                put("response", JSONObject().put("result", stalePayload))
            }
        }

        val commandId = when {
            explicitCommandId.isNotEmpty() -> explicitCommandId
            activeCommandId.isNotEmpty() -> activeCommandId
            else -> callId.also { activeCommandId = it }
        }
        currentTurnHasPendingOrExecutedAction = true
        awaitingVerifiedToolOutcome = true
        currentTurnCanonicalResult = null
        currentTurnCanonicalResultCommandId = commandId
        com.omnivoice.agentfix.UiTools.clearLastCanonicalResult()
        clearPlaybackQueueAndStopImmediately()

        val activeUtterance = _liveUserTranscript.value.ifBlank {
            com.omnivoice.agentfix.AgentSession.lastUserUtterance
        }
        val fnName = if (
            com.omnivoice.agentfix.UiTools.shouldRedirectToolToReturnToOrigin(rawFnName, activeUtterance, args)
        ) {
            Log.i(TAG, "Redirecting Live tool '$rawFnName' -> 'return_to_origin' for utterance '$activeUtterance'")
            args.put("_redirectedFromTool", rawFnName)
            "return_to_origin"
        } else {
            rawFnName
        }
        val matchedIntent = when {
            fnName == "return_to_origin" || com.omnivoice.agentfix.UiTools.isReturnToPreviousAppCommand(activeUtterance) -> "return_to_origin"
            com.omnivoice.agentfix.UiTools.isExplicitWhatsAppStatusCommand(activeUtterance) -> "open_whatsapp_status"
            com.omnivoice.agentfix.UiTools.isExplicitShowRecentsCommand(activeUtterance) -> "show_recents"
            else -> fnName
        }
        com.omnivoice.agentfix.UiTools.lastCommandRouteTrace = com.omnivoice.agentfix.UiTools.CommandRouteTrace(
            routeType = "VOICE",
            recognizedTranscript = activeUtterance,
            matchedIntent = matchedIntent,
            savedOriginPackage = com.omnivoice.agentfix.UiTools.lastOriginTaskSnapshot?.packageName,
            actualDispatchedTool = fnName
        )

        OmniAutonomousCoreEngine.resetInactivityTimer()
        _connectionState.value = LiveConnectionState.LIVE_EXECUTING_TOOL
        Log.d(TAG, "Executing Gemini Live function call: $fnName (wireName=$wireFnName, callId=$callId, commandId=$commandId) with args=$args")

        var verifiedToolOutcome: JarvisVerifiedToolOutcome? = null
        var customToolResponse: JSONObject? = null
        var pendingCard: LiveSlideUpCard? = null

        val resultSummary = if (LockScreenUnlockManager.isDeviceCurrentlyLocked(context) &&
            LockScreenUnlockManager.isBlockedWhenDeviceLocked(fnName)
        ) {
            val blockedMsg = "Blocked: '$fnName' cannot run while the phone is locked. Please unlock your phone using Android's standard lock screen."
            customToolResponse = JSONObject().apply {
                put("status", "BLOCKED")
                put("ok", false)
                put("executed", false)
                put("errorCode", "DEVICE_LOCKED")
                put("message", blockedMsg)
                put("result", blockedMsg)
            }
            val card = LiveSlideUpCard(
                functionName = fnName,
                title = "BLOCKED • DEVICE LOCKED",
                subtitle = blockedMsg,
                appName = "Security",
                isLaunchingApp = false
            )
            pendingCard = card
            blockedMsg
        } else if (com.omnivoice.agentfix.ToolDispatcher.isAgentFixTool(fnName)) {
            val dispatched = toolDispatcher.dispatch(fnName, args, callId)
            customToolResponse = dispatched.toJson()
            val canonical = com.omnivoice.agentfix.ToolDispatcher.canonicalToolName(fnName)
            val card = LiveSlideUpCard(
                functionName = canonical,
                title = "${canonical.replace("_", " ").uppercase()} • ${dispatched.status}",
                subtitle = dispatched.message,
                appName = canonical,
                isLaunchingApp = dispatched.ok && dispatched.verified
            )
            pendingCard = card
            dispatched.message
        } else when (fnName) {
            "generate_and_use_ai_image", "generate_ai_image_and_apply" -> {
                val prompt = args.optString("prompt", "futuristic neon cyber lion, 8k")
                val targetAction = args.optString("targetAction", "display").lowercase()
                val contactName = args.optString("contactName", "").trim()
                val asset = generateLivePollinationsOrLocalImage(prompt)

                val deepLink = if (targetAction.contains("whatsapp") || targetAction.contains("share") || targetAction.contains("dp")) {
                    shareOrApplyWhatsAppDp(asset, if (targetAction.contains("dp")) "whatsapp_dp" else "share")
                } else {
                    ""
                }

                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = if (targetAction.contains("dp")) "AI Image Ready for WhatsApp DP" else "AI Image Generated",
                    subtitle = if (contactName.isNotBlank()) "$prompt → $contactName" else prompt,
                    deepLinkUri = deepLink,
                    generatedImage = asset
                )
                pushSlideUpCard(card)
                "Generated high-resolution 1024x1024 AI image for '$prompt' and executed targetAction='$targetAction'."
            }

            "control_phone_hardware", "manage_connectivity_and_settings" -> {
                val feature = args.optString("settingType", args.optString("feature", "")).lowercase()
                val state = args.optString("action", args.optString("state", "ON")).uppercase()
                val networkName = args.optString("networkName", "").trim()
                val value = args.optString("passwordOrValue", args.optString("value", "")).trim()

                val summary = when {
                    feature.contains("wifi") && state == "SCAN_AND_LIST" -> {
                        androidBridge.setWifiEnabled(true)
                        val nets = androidBridge.scanWifiNetworksList()
                        "Wi-Fi enabled. Available networks: ${nets.joinToString(", ")}"
                    }
                    feature.contains("wifi") && state == "CONNECT_WITH_PASSWORD" -> {
                        androidBridge.connectWifiWithPassword(networkName, value)
                        "Connected to Wi-Fi network '$networkName' via zero-touch Accessibility password entry."
                    }
                    feature.contains("mobile_data") || feature.contains("data") -> {
                        androidBridge.setMobileDataEnabled(state == "ON")
                        "Mobile Data panel opened ($state)"
                    }
                    else -> executeLiveHardwareControl(feature, state, value.ifBlank { networkName })
                }
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "Hardware & Connectivity: ${feature.replaceFirstChar { it.uppercase() }}",
                    subtitle = summary
                )
                pushSlideUpCard(card)
                summary
            }

            "learn_and_remember_permanently" -> {
                val category = args.optString("category", "CUSTOM_RULE").trim()
                val ruleOrPreference = args.optString("ruleOrPreference", "").trim()
                val newAssistantName = args.optString("newAssistantName", "").trim()
                val updatedMem = OmniAutonomousCoreEngine.learnAndRememberPermanently(
                    context = context,
                    category = category,
                    ruleOrPreference = ruleOrPreference,
                    newAssistantName = newAssistantName
                )
                val summary = if (newAssistantName.isNotBlank()) {
                    "Assistant permanently renamed to '${updatedMem.assistantName}'. Standby wake-word is now 'Wake up ${updatedMem.assistantName}'."
                } else {
                    "Permanently saved rule [$category]: '$ruleOrPreference' into omni_permanent_memory."
                }
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "Permanent Memory Updated (${updatedMem.assistantName})",
                    subtitle = summary,
                    appName = "Memory Bank"
                )
                pushSlideUpCard(card)
                summary
            }

            "communicate_or_open_app" -> {
                val actionType = args.optString("actionType", "").trim()
                val contactOrAppName = args.optString("contactOrAppName", "").trim()
                val messageOrQuery = args.optString("messageOrQuery", "").trim()
                if (com.omnivoice.agentfix.UiTools.isPlainAppOpenInCommunicateOrOpenApp(
                        actionType = actionType,
                        contactOrAppName = contactOrAppName,
                        messageOrQuery = messageOrQuery
                    )
                ) {
                    val targetApp = contactOrAppName.ifBlank { actionType }
                    val launchRes = com.omnivoice.agentfix.UiTools.openAppCanonical(
                        context = context,
                        appName = targetApp,
                        callerToolName = "communicate_or_open_app"
                    )
                    customToolResponse = launchRes.toToolResult("communicate_or_open_app").toJson()
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "${launchRes.resolvedLabel ?: targetApp} • ${launchRes.status.wireName}",
                        subtitle = launchRes.message,
                        appName = launchRes.resolvedLabel ?: targetApp,
                        targetPackage = launchRes.resolvedPackage.orEmpty(),
                        isLaunchingApp = launchRes.ok && launchRes.verified
                    )
                    pendingCard = card
                    launchRes.message
                } else {
                    val (summary, uri) = executeLiveCommunicationOrApp(
                        actionType = actionType.lowercase(),
                        contactOrAppName = contactOrAppName,
                        messageOrQuery = messageOrQuery
                    )
                    customToolResponse = JSONObject().apply {
                        put("status", "REQUESTED_UNVERIFIED")
                        put("canonicalStatus", "Requested/Unverified")
                        put("ok", false)
                        put("verified", false)
                        put("executed", false)
                        put("message", summary)
                        put("result", summary)
                        put("deepLinkUri", uri)
                    }
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "${contactOrAppName.ifBlank { actionType.replace("_", " ").uppercase() }} • Requested/Unverified",
                        subtitle = summary,
                        deepLinkUri = uri,
                        appName = contactOrAppName.ifBlank { "App" },
                        isLaunchingApp = false
                    )
                    pendingCard = card
                    summary
                }
            }

            "save_or_update_contact", "save_contact_by_voice" -> {
                val name = args.optString("name", "").trim()
                val phoneNumber = args.optString("phoneNumber", "").trim()
                val whatsappNumber = args.optString("whatsappNumber", "").trim()
                val instagramHandle = args.optString("instagramHandle", "").trim()
                val facebookProfile = args.optString("facebookProfile", "").trim()
                val tiktokHandle = args.optString("tiktokHandle", "").trim()

                val savedEntry = saveOrUpdateSmartContact(
                    name = name,
                    phoneNumber = phoneNumber,
                    whatsappNumber = whatsappNumber,
                    instagramHandle = instagramHandle,
                    facebookProfile = facebookProfile,
                    tiktokHandle = tiktokHandle
                )
                JarvisHandsFreeEngine.syncMirrorToJarvisContactsKey(context)
                OmniAutonomousCoreEngine.syncMasterContactsStorage(context)
                JarvisHandsFreeEngine.appendTerminalLog(
                    layerTag = "VOICE-CONTACT",
                    command = "SAVE_CONTACT('${savedEntry.name}')",
                    detail = "Phone/WA: ${savedEntry.whatsappNumber.ifBlank { savedEntry.phoneNumber }}",
                    status = "SAVED"
                )

                val summary = "Saved ${savedEntry.name} to omni_master_contacts & jarvis_phone_contacts (Phone/WA: ${savedEntry.whatsappNumber.ifBlank { savedEntry.phoneNumber }.ifBlank { "N/A" }}, IG: ${savedEntry.instagramHandle.ifBlank { "N/A" }}, TikTok: ${savedEntry.tiktokHandle.ifBlank { "N/A" }})"
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "Saved Contact: ${savedEntry.name}",
                    subtitle = summary,
                    appName = "Contacts"
                )
                pushSlideUpCard(card)
                summary
            }

            "send_autonomous_message", "jarvis_send_message", "send_whatsapp_or_social_message", "send_whatsapp_message", "send_message_zero_touch" -> {
                val platform = args.optString("app", args.optString("platform", "whatsapp")).lowercase()
                val recipientName = args.optString("contactName", args.optString("recipientName", "")).trim()
                val recipientNumberOrHandle = args.optString(
                    "phoneOrHandle",
                    args.optString(
                        "phoneNumberOrUsername",
                        args.optString("phoneNumber", args.optString("recipientNumberOrHandle", ""))
                    )
                ).trim()
                val messageText = args.optString("messageText", "").trim()

                val outcome = JarvisHandsFreeEngine.executeJarvisSendMessage(
                    context = context,
                    app = platform,
                    contactName = recipientName,
                    phoneNumberOrUsername = recipientNumberOrHandle,
                    messageText = messageText,
                    bridge = androidBridge,
                    actionExecutor = actionExecutor
                )
                verifiedToolOutcome = outcome.verifiedOutcome

                val appDisplayName = platform.replace("_", " ").replaceFirstChar { it.uppercase() }
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "$appDisplayName → ${outcome.matchedContactName} (${outcome.formattedPhoneOrHandle})",
                    subtitle = outcome.spokenConfirmationHint,
                    deepLinkUri = outcome.deepLinkUri,
                    appName = appDisplayName,
                    targetPackage = outcome.targetPackage,
                    isLaunchingApp = outcome.verifiedOutcome.executed,
                    macroSteps = outcome.macroSteps
                )
                pushSlideUpCard(card)
                outcome.spokenConfirmationHint
            }

            "press_send_or_confirm_on_screen", "click_or_send_current_screen" -> {
                val targetButtonName = args.optString(
                    "buttonText",
                    args.optString("targetButtonName", "Send")
                ).trim().ifBlank { "Send" }
                val clickOutcome = JarvisHandsFreeEngine.executeClickOrSendCurrentScreen(
                    context = context,
                    buttonText = targetButtonName,
                    bridge = androidBridge
                )
                verifiedToolOutcome = clickOutcome
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "Auto-Click: $targetButtonName",
                    subtitle = clickOutcome.message,
                    appName = "WhatsApp / Active Screen"
                )
                pushSlideUpCard(card)
                clickOutcome.message
            }

            "make_direct_phone_call", "make_phone_call_zero_touch" -> {
                val contactName = args.optString("contactName", "").trim()
                val phoneNumber = args.optString("phoneNumber", "").trim()
                val callOutcome = JarvisHandsFreeEngine.executeMakeDirectPhoneCall(
                    context = context,
                    contactName = contactName,
                    phoneNumber = phoneNumber,
                    bridge = androidBridge,
                    actionExecutor = actionExecutor
                )
                verifiedToolOutcome = callOutcome
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "Direct Call → ${callOutcome.contactMatched ?: "CONTACT_NOT_FOUND"}",
                    subtitle = callOutcome.message,
                    deepLinkUri = callOutcome.deepLinkUri,
                    appName = "Phone",
                    targetPackage = callOutcome.targetPackage,
                    isLaunchingApp = callOutcome.executed
                )
                pushSlideUpCard(card)
                callOutcome.message
            }

            "control_any_app_with_accessibility", "control_app_and_screen_ui" -> {
                val appName = args.optString("appName", "App").trim()
                val taskDescription = args.optString("uiAction", args.optString("taskDescription", "OPEN")).trim()
                val targetButtonOrField = args.optString("targetElementText", args.optString("targetButtonOrField", "")).trim()
                val textToType = args.optString("textToType", "").trim()
                val scrollDirection = when {
                    taskDescription.contains("SCROLL_UP", ignoreCase = true) -> "up"
                    taskDescription.contains("SCROLL", ignoreCase = true) -> "down"
                    else -> args.optString("scrollDirection", "none").trim()
                }
                val isPlainOpen = (taskDescription.equals("OPEN", ignoreCase = true) ||
                    taskDescription.equals("OPEN_APP", ignoreCase = true) ||
                    taskDescription.equals("LAUNCH", ignoreCase = true) ||
                    taskDescription.equals("LAUNCH_APP", ignoreCase = true)) &&
                    targetButtonOrField.isBlank() &&
                    textToType.isBlank() &&
                    scrollDirection.equals("none", ignoreCase = true)
                if (isPlainOpen) {
                    val launchRes = com.omnivoice.agentfix.UiTools.openAppCanonical(
                        context = context,
                        appName = appName,
                        callerToolName = fnName
                    )
                    customToolResponse = launchRes.toToolResult(fnName).toJson()
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "Accessibility Control → ${launchRes.resolvedLabel ?: appName} • ${launchRes.status.wireName}",
                        subtitle = launchRes.message,
                        appName = launchRes.resolvedLabel ?: appName,
                        targetPackage = launchRes.resolvedPackage.orEmpty(),
                        isLaunchingApp = launchRes.ok && launchRes.verified
                    )
                    pendingCard = card
                    launchRes.message
                } else {
                    val accessOutcome = JarvisHandsFreeEngine.executeControlAnyAppWithAccessibility(
                        context = context,
                        appName = appName,
                        taskDescription = taskDescription,
                        targetButtonOrField = targetButtonOrField,
                        textToType = textToType,
                        scrollDirection = scrollDirection,
                        bridge = androidBridge,
                        actionExecutor = actionExecutor
                    )
                    verifiedToolOutcome = accessOutcome
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "Accessibility Control → $appName",
                        subtitle = accessOutcome.message,
                        appName = appName,
                        targetPackage = accessOutcome.targetPackage,
                        isLaunchingApp = accessOutcome.executed
                    )
                    pendingCard = card
                    accessOutcome.message
                }
            }

            "control_social_and_media_apps", "jarvis_app_commander" -> {
                val app = args.optString("appName", args.optString("app", "youtube")).lowercase()
                val action = args.optString("commandType", args.optString("action", "open_feed")).lowercase()
                val queryOrTarget = args.optString(
                    "searchQueryOrTarget",
                    args.optString("queryOrTarget", "")
                ).trim()
                val content = args.optString("extraData", args.optString("content", "")).trim()

                val isPlainOpen = (action == "open" || action == "open_app" || action == "open_feed" || action == "launch") &&
                    (queryOrTarget.isBlank() || queryOrTarget.equals(app, ignoreCase = true)) &&
                    content.isBlank()
                if (isPlainOpen) {
                    val launchRes = com.omnivoice.agentfix.UiTools.openAppCanonical(
                        context = context,
                        appName = app,
                        callerToolName = fnName
                    )
                    customToolResponse = launchRes.toToolResult(fnName).toJson()
                    val appDisplayName = launchRes.resolvedLabel ?: app.replace("_", " ").replaceFirstChar { it.uppercase() }
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "$appDisplayName • ${launchRes.status.wireName}",
                        subtitle = launchRes.message,
                        appName = appDisplayName,
                        targetPackage = launchRes.resolvedPackage.orEmpty(),
                        isLaunchingApp = launchRes.ok && launchRes.verified
                    )
                    pendingCard = card
                    launchRes.message
                } else {
                    val outcome = JarvisHandsFreeEngine.executeJarvisAppCommander(
                        context = context,
                        appName = app,
                        commandType = action,
                        searchQueryOrTarget = queryOrTarget,
                        extraData = content,
                        bridge = androidBridge,
                        actionExecutor = actionExecutor
                    )

                    val appDisplayName = app.replace("_", " ").replaceFirstChar { it.uppercase() }
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "$appDisplayName • ${action.replace("_", " ").uppercase()}",
                        subtitle = outcome.summary,
                        deepLinkUri = outcome.deepLinkUri,
                        appName = appDisplayName,
                        targetPackage = outcome.targetPackage,
                        isLaunchingApp = true,
                        macroSteps = outcome.macroSteps
                    )
                    pushSlideUpCard(card)
                    outcome.spokenConfirmationHint
                }
            }

            "jarvis_hardware_and_screen_control" -> {
                val command = args.optString("command", "").trim()
                val parameter = args.optString("parameter", "").trim()
                val summary = JarvisHandsFreeEngine.executeJarvisHardwareAndScreenControl(
                    context = context,
                    command = command,
                    parameter = parameter,
                    bridge = androidBridge,
                    actionExecutor = actionExecutor
                )
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "JARVIS OS Control: ${command.replace("_", " ").uppercase()}",
                    subtitle = summary,
                    appName = "Android OS"
                )
                pushSlideUpCard(card)
                summary
            }

            "jarvis_generate_and_share_media" -> {
                val prompt = args.optString("prompt", "futuristic neon cyber lion, 8k")
                val destinationApp = args.optString("destinationApp", "whatsapp_dp").lowercase()
                val contactName = args.optString("contactName", "").trim()
                val asset = generateLivePollinationsOrLocalImage(prompt)
                val deepLink = shareOrApplyWhatsAppDp(asset, destinationApp)
                JarvisHandsFreeEngine.appendTerminalLog(
                    layerTag = "AI-MEDIA",
                    command = "GENERATE_AND_SHARE('$destinationApp')",
                    detail = "Prompt: $prompt • Contact: ${contactName.ifBlank { "Self" }}",
                    status = "READY"
                )
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "JARVIS AI Media → ${destinationApp.uppercase()}",
                    subtitle = prompt,
                    deepLinkUri = deepLink,
                    appName = destinationApp,
                    generatedImage = asset
                )
                pushSlideUpCard(card)
                "Generated high-speed AI image for '$prompt' and prepared share intent for $destinationApp."
            }

            "execute_ui_automation_macro" -> {
                val targetApp = args.optString("targetApp", "Android App").trim()
                val stepsJson = args.optJSONArray("steps") ?: JSONArray()
                val targetPkg = SmartContactAndAppRegistry.resolveAndroidPackage(targetApp)
                val parsedSteps = mutableListOf<UiAutomationMacroStep>()
                for (i in 0 until stepsJson.length()) {
                    val stepObj = stepsJson.optJSONObject(i) ?: continue
                    parsedSteps.add(
                        UiAutomationMacroStep(
                            stepIndex = i + 1,
                            action = stepObj.optString("action", stepObj.optString("stepAction", "CLICK_NODE")).uppercase(),
                            targetPackage = stepObj.optString("package", targetPkg),
                            nodeId = stepObj.optString("nodeId", ""),
                            contentDescription = stepObj.optString("contentDescription", stepObj.optString("selectorOrText", "")),
                            text = stepObj.optString("text", stepObj.optString("value", ""))
                        )
                    )
                }
                if (parsedSteps.isEmpty()) {
                    parsedSteps.add(
                        UiAutomationMacroStep(
                            stepIndex = 1,
                            action = "LAUNCH_PACKAGE",
                            targetPackage = targetPkg,
                            text = "Launch $targetApp"
                        )
                    )
                }

                AndroidBridge.emitAccessibilityMacro(
                    targetApp = targetApp,
                    steps = parsedSteps,
                    bridge = androidBridge
                )

                val (deepLinkUri, resolvedPkg, _) = SmartContactAndAppRegistry.buildSocialAndMediaDeepLink(
                    app = targetApp.lowercase(),
                    action = "open_feed",
                    queryOrTarget = targetApp,
                    content = ""
                )
                SmartContactAndAppRegistry.launchUniversalDeepLink(
                    context = context,
                    deepLinkUri = deepLinkUri,
                    fallbackPackage = resolvedPkg,
                    actionExecutor = actionExecutor
                )

                val summary = "Executed ${parsedSteps.size}-step UI Automation Macro in $targetApp"
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "Autonomous UI Macro: $targetApp",
                    subtitle = summary,
                    deepLinkUri = deepLinkUri,
                    appName = targetApp,
                    targetPackage = resolvedPkg,
                    isLaunchingApp = true,
                    macroSteps = parsedSteps
                )
                pushSlideUpCard(card)
                summary
            }

            "execute_direct_search" -> {
                val app = args.optString("app", "google").trim()
                val query = args.optString("query", "").trim()
                val directResult = SmartContactAndAppRegistry.executeDirectSearch(
                    context = context,
                    app = app,
                    query = query,
                    bridge = androidBridge
                )
                customToolResponse = JSONObject().apply {
                    put("status", "SUCCESS")
                    put("app", app)
                    put("query", query)
                    put("uri", directResult.uri)
                    put("launched", directResult.launched)
                    put("injectedText", directResult.injectedText)
                    put("message", directResult.message)
                }
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "Direct Search: $app",
                    subtitle = query,
                    deepLinkUri = directResult.uri,
                    appName = app,
                    isLaunchingApp = true
                )
                pushSlideUpCard(card)
                directResult.message
            }

            "send_whatsapp_isolated" -> {
                val recipientName = args.optString("contactName", args.optString("recipientName", "")).trim()
                val rawPhone = args.optString("phoneNumber", args.optString("phone", "")).trim()
                val messageText = args.optString("message", args.optString("messageText", "")).trim()

                val isoResult = SmartContactAndAppRegistry.resolveAndSendWhatsAppIsolated(
                    context = context,
                    contactName = recipientName,
                    rawPhone = rawPhone,
                    messageText = messageText,
                    bridge = androidBridge
                )
                customToolResponse = JSONObject().apply {
                    put("status", isoResult.status)
                    put("message", isoResult.message)
                    put("executed", isoResult.executed)
                    put("phoneUsed", isoResult.phoneUsed)
                    put("contactMatched", isoResult.contactMatched)
                    put("deepLinkUri", isoResult.deepLinkUri)
                }
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = if (isoResult.status == "SUCCESS") "WhatsApp Isolated Send → ${isoResult.contactMatched}" else "WhatsApp Error: ${isoResult.status}",
                    subtitle = isoResult.message,
                    deepLinkUri = isoResult.deepLinkUri,
                    appName = "WhatsApp",
                    targetPackage = "com.whatsapp",
                    isLaunchingApp = isoResult.executed
                )
                pushSlideUpCard(card)
                isoResult.message
            }

            "inspect_current_live_screen" -> {
                val userQuestion = args.optString("userQuestion", "What is currently on screen?").trim()
                val isAccessActive = com.example.service.OmniAccessibilityService.isConnected()
                val screenTreeJson = androidBridge.getLiveScreenTreeJson()
                if (!isAccessActive && screenTreeJson == "{}") {
                    customToolResponse = JSONObject().apply {
                        put("status", "SCREEN_VISION_PERMISSION_REQUIRED")
                        put("message", "Live Screen Vision or Accessibility permission is required to inspect apps outside Omni.")
                        put("executed", false)
                    }
                    val msg = "Screen Vision or Accessibility permission is required to inspect apps outside Omni."
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "Screen Vision: Permission Required",
                        subtitle = msg,
                        appName = "Screen Vision"
                    )
                    pushSlideUpCard(card)
                    msg
                } else {
                    customToolResponse = JSONObject().apply {
                        put("status", "SUCCESS")
                        put("question", userQuestion)
                        put("screenTree", JSONObject(if (screenTreeJson.isNotBlank()) screenTreeJson else "{}"))
                        put("visionStreamActive", _isScreenVisionActive.value)
                        put("message", "Current screen snapshot analyzed for: $userQuestion")
                    }
                    val summary = "Inspected current live screen (${screenTreeJson.take(120)}...)"
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "Screen Vision: Inspected",
                        subtitle = summary,
                        appName = "Screen Vision"
                    )
                    pushSlideUpCard(card)
                    summary
                }
            }

            "get_mobile_hardware_telemetry" -> {
                val rawTelemetry = androidBridge.getHardwareTelemetryJson()
                val telemetryObj = try {
                    JSONObject(rawTelemetry)
                } catch (_: Exception) {
                    JSONObject().put("raw", rawTelemetry)
                }
                customToolResponse = JSONObject().apply {
                    put("status", "SUCCESS")
                    put("telemetry", telemetryObj)
                }
                val temp = telemetryObj.optString("batteryTemperatureCelsius", "N/A")
                val pct = telemetryObj.optString("batteryPercentage", "N/A")
                val mops = telemetryObj.optString("benchmarkMops", "N/A")
                val summary = "Battery: $pct ($temp) • Benchmark: $mops • Cores: ${telemetryObj.optInt("cpuCores", 8)}"
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "Hardware Telemetry HUD",
                    subtitle = summary,
                    appName = "Hardware Monitor"
                )
                pushSlideUpCard(card)
                summary
            }

            "close_target_app" -> {
                val appName = args.optString("appName", "current_app").trim()
                val isAccessActive = com.example.service.OmniAccessibilityService.isConnected()
                val closed = androidBridge.closeCurrentAppOrGoHome("CLOSE")
                if (!isAccessActive) {
                    customToolResponse = JSONObject().apply {
                        put("status", "OS_RESTRICTED")
                        put("message", "Android OS sandbox restricts force-stopping third-party apps directly; navigated to Home screen instead.")
                        put("executed", closed)
                        put("app", appName)
                    }
                    val msg = "Navigated Home. Direct closing of $appName is restricted by Android OS sandboxing."
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "Close App: OS Restricted",
                        subtitle = msg,
                        appName = appName
                    )
                    pushSlideUpCard(card)
                    msg
                } else {
                    customToolResponse = JSONObject().apply {
                        put("status", "SUCCESS")
                        put("message", "Closed $appName / navigated to Home screen.")
                        put("executed", true)
                        put("app", appName)
                    }
                    val summary = "Closed $appName / navigated to Home screen."
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "App Closed: $appName",
                        subtitle = summary,
                        appName = appName
                    )
                    pushSlideUpCard(card)
                    summary
                }
            }

            "delete_whatsapp_message" -> {
                val target = args.optString("target", "last_message").trim()
                val scopeStr = args.optString("scope", "everyone").trim()
                val deleteForEveryone = scopeStr.contains("everyone", ignoreCase = true)
                val isAccessActive = com.example.service.OmniAccessibilityService.isConnected()
                if (!isAccessActive) {
                    customToolResponse = JSONObject().apply {
                        put("status", "CANNOT_DELETE_WITHOUT_ACCESSIBILITY")
                        put("message", "Accessibility service is not active to perform in-app WhatsApp message deletion.")
                        put("executed", false)
                    }
                    val msg = "Cannot delete message: Accessibility service is not enabled."
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "Delete WhatsApp Message: Service Required",
                        subtitle = msg,
                        appName = "WhatsApp"
                    )
                    pushSlideUpCard(card)
                    msg
                } else {
                    val deleted = androidBridge.deleteWhatsAppMessage(target, deleteForEveryone)
                    customToolResponse = JSONObject().apply {
                        put("status", if (deleted) "SUCCESS" else "FAILED")
                        put("message", if (deleted) "Triggered in-app message deletion in WhatsApp." else "Message node not found or deletion cancelled.")
                        put("executed", deleted)
                    }
                    val summary = if (deleted) "Deleted WhatsApp message ('$target')" else "Could not locate message to delete in active WhatsApp chat."
                    val card = LiveSlideUpCard(
                        functionName = fnName,
                        title = "Delete WhatsApp Message",
                        subtitle = summary,
                        appName = "WhatsApp"
                    )
                    pushSlideUpCard(card)
                    summary
                }
            }

            "execute_adb_shell", "execute_shell_command" -> {
                val cmd = args.optString("command", args.optString("cmd", "")).trim()
                val res = OsKernel.getInstance(context).executeCommand(
                    OsShellCommand(description = "Shell: $cmd", adbShellCommand = cmd)
                )
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "Shell Execution Blocked",
                    subtitle = res.output,
                    appName = "Security"
                )
                pushSlideUpCard(card)
                customToolResponse = JSONObject().apply {
                    put("status", "BLOCKED")
                    put("ok", false)
                    put("executed", false)
                    put("methodUsed", res.methodUsed)
                    put("output", res.output)
                    put("exitCode", res.exitCode)
                }
                res.output
            }

            "unlock_phone_hands_free", "unlock_phone" -> {
                val unlockRes = OsKernel.getInstance(context).unlockPhoneHandsFree()
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "Hands-Free Unlock Blocked",
                    subtitle = unlockRes.output,
                    appName = "Security"
                )
                pushSlideUpCard(card)
                customToolResponse = JSONObject().apply {
                    put("status", "BLOCKED")
                    put("ok", false)
                    put("executed", false)
                    put("methodUsed", unlockRes.methodUsed)
                    put("output", unlockRes.output)
                }
                unlockRes.output
            }

            "scrape_screen", "scrape_current_screen" -> {
                val scrapeJson = OsKernel.getInstance(context).scrapeCurrentScreen()
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "Screen Scraper",
                    subtitle = "Captured active window nodes and text",
                    appName = "ScreenObserver"
                )
                pushSlideUpCard(card)
                customToolResponse = JSONObject().apply {
                    put("status", "SUCCESS")
                    put("scrapeData", JSONObject(scrapeJson))
                }
                "Active screen scraped successfully"
            }

            "send_whatsapp_voice_note" -> {
                val recipient = args.optString("recipient", args.optString("contactName", "")).trim()
                val message = args.optString("message", args.optString("text", "")).trim()
                val outcome = WhatsAppVoiceNoteController.getInstance(context).sendVoiceNote(
                    recipientName = recipient,
                    spokenMessage = message
                )
                val card = LiveSlideUpCard(
                    functionName = fnName,
                    title = "WhatsApp Voice Note • ${outcome.method}",
                    subtitle = outcome.message,
                    appName = "WhatsApp",
                    isLaunchingApp = false
                )
                pushSlideUpCard(card)
                customToolResponse = JSONObject().apply {
                    put("status", if (outcome.ok) "SUCCESS" else "FAILED")
                    put("ok", outcome.ok)
                    put("executed", outcome.ok)
                    put("message", outcome.message)
                    put("method", outcome.method)
                }
                outcome.message
            }

            "answer_incoming_call" -> {
                val ansRes = AiCallSecretary.getInstance(context).answerAndScreenCall()
                val msg = if (ansRes) "Call answered" else "Blocked: Unattended call answering is disabled for safety."
                customToolResponse = JSONObject().apply {
                    put("status", if (ansRes) "SUCCESS" else "BLOCKED")
                    put("ok", ansRes)
                    put("executed", ansRes)
                    put("message", msg)
                }
                msg
            }

            "end_incoming_call", "hangup_call" -> {
                val endRes = AiCallSecretary.getInstance(context).endCall("Call ended by user command.")
                customToolResponse = JSONObject().apply {
                    put("status", if (endRes) "SUCCESS" else "FAILED")
                    put("message", if (endRes) "Call ended" else "Could not end call")
                }
                if (endRes) "Call ended successfully" else "Could not end call"
            }

            else -> "Executed $fnName"
        }

        // Fast verification settle (no 1500ms blocking delay)
        if (verifiedToolOutcome != null) {
            delay(35)
        }

        // Check again if a newer command superseded this commandId while the tool was executing
        if (activeCommandId.isNotEmpty() && commandId != activeCommandId) {
            Log.w(TAG, "Discarding late tool completion for commandId=$commandId because activeCommandId=$activeCommandId")
            val stalePayload = JSONObject().apply {
                put("id", callId)
                put("status", "STALE_COMMAND_IGNORED")
                put("canonicalStatus", "Requested/Unverified")
                put("ok", false)
                put("verified", false)
                put("executed", false)
                put("message", "Ignored late callback from previous command '$commandId'.")
                put("result", "Ignored late callback from previous command '$commandId'.")
            }
            return@withContext JSONObject().apply {
                put("id", callId)
                put("name", wireFnName)
                put("response", JSONObject().put("result", stalePayload))
            }
        }

        val canonicalTool = com.omnivoice.agentfix.ToolDispatcher.canonicalToolName(fnName)
        val isReturnTool = canonicalTool == "return_to_origin" || fnName == "return_to_origin"
        val isPlainCommOpen = fnName == "communicate_or_open_app" &&
            com.omnivoice.agentfix.UiTools.isPlainAppOpenInCommunicateOrOpenApp(
                actionType = args.optString("actionType", "").trim(),
                contactOrAppName = args.optString("contactOrAppName", "").trim(),
                messageOrQuery = args.optString("messageOrQuery", "").trim()
            )
        val currentOutcome = if (args.optBoolean("_simulateMissingCurrentOutcome", false)) {
            null
        } else {
            com.omnivoice.agentfix.UiTools.lastCanonicalResult
        }
        currentTurnCanonicalResult = currentOutcome
        currentTurnCanonicalResultCommandId = commandId

        val isAppOpenOrReturnCommand = isReturnTool ||
            canonicalTool == "open_app" ||
            isPlainCommOpen ||
            currentOutcome != null

        // NEVER fall back to automatic status="SUCCESS" / executed=true for action tools without a verified outcome!
        val resultPayload = (customToolResponse ?: verifiedToolOutcome?.toJsonObject() ?: JSONObject().apply {
            put("result", resultSummary)
            put("message", resultSummary)
            put("status", "REQUESTED_UNVERIFIED")
            put("canonicalStatus", "Requested/Unverified")
            put("ok", false)
            put("verified", false)
            put("executed", false)
        }).apply {
            put("id", callId)
            remove("commandId")
            remove("activeCommandId")
            if (!has("result")) put("result", resultSummary)
            if (!has("message")) put("message", resultSummary)
            if (isAppOpenOrReturnCommand && currentOutcome == null && optString("errorCode") != "DEVICE_LOCKED") {
                put("status", "REQUESTED_UNVERIFIED")
                put("canonicalStatus", "Requested/Unverified")
                put("ok", false)
                put("verified", false)
                put("executed", false)
            }
            val rawStatus = optString("canonicalStatus", optString("status", "REQUESTED_UNVERIFIED"))
            val isVerifiedOutcome = (optBoolean("verified", false) || optBoolean("executed", false) && verifiedToolOutcome != null) &&
                (rawStatus.equals("VERIFIED", ignoreCase = true) || rawStatus.equals("SUCCESS", ignoreCase = true)) &&
                (!isAppOpenOrReturnCommand || currentOutcome?.status == com.omnivoice.agentfix.CanonicalAppLaunchStatus.VERIFIED)
            val normalizedFinalStatus = when {
                isVerifiedOutcome -> "VERIFIED"
                rawStatus.equals("Requested/Unverified", ignoreCase = true) ||
                    rawStatus.equals("REQUESTED_UNVERIFIED", ignoreCase = true) ||
                    rawStatus.equals("SUCCESS", ignoreCase = true) && !isVerifiedOutcome -> "REQUESTED_UNVERIFIED"
                rawStatus.equals("MissingApp", ignoreCase = true) ||
                    rawStatus.equals("MISSING_APP", ignoreCase = true) -> "MISSING_APP"
                rawStatus.equals("NeedsSetup", ignoreCase = true) ||
                    rawStatus.equals("NEEDS_SETUP", ignoreCase = true) -> "NEEDS_SETUP"
                rawStatus.equals("Blocked", ignoreCase = true) ||
                    rawStatus.equals("BLOCKED", ignoreCase = true) -> "BLOCKED"
                else -> rawStatus.uppercase(Locale.ROOT)
            }
            put("finalStatus", normalizedFinalStatus)
            put("verified", isVerifiedOutcome)
            put("ok", isVerifiedOutcome)
            put("executed", isVerifiedOutcome)
            if (!isVerifiedOutcome && optString("status").equals("SUCCESS", ignoreCase = true)) {
                put("status", "REQUESTED_UNVERIFIED")
                put("canonicalStatus", "Requested/Unverified")
            }
        }

        val finalStatusForTurn = resultPayload.optString("finalStatus", "REQUESTED_UNVERIFIED")
        val rawDiagnosticMessage = currentOutcome?.message?.takeIf { it.isNotBlank() }
            ?: resultPayload.optString("message", resultSummary).ifBlank { resultSummary }

        val requestedAppHint = args.optString(
            "app_name",
            args.optString(
                "appName",
                args.optString(
                    "package_name",
                    args.optString("contactOrAppName", args.optString("target", ""))
                )
            )
        ).trim()

        val spokenMessageForTurn = if (isAppOpenOrReturnCommand && resultPayload.optString("errorCode") != "DEVICE_LOCKED") {
            buildSpokenConfirmationForAppCommand(
                outcome = currentOutcome,
                utterance = activeUtterance,
                isReturnCommand = isReturnTool,
                fallbackAppName = requestedAppHint
            )
        } else {
            sanitizeSpokenText(rawDiagnosticMessage, activeUtterance)
        }

        val diagnosticMessageForTurn = if (isAppOpenOrReturnCommand && currentOutcome == null && resultPayload.optString("errorCode") != "DEVICE_LOCKED") {
            spokenMessageForTurn
        } else {
            rawDiagnosticMessage
        }

        resultPayload.put("message", spokenMessageForTurn)
        resultPayload.put("spokenMessage", spokenMessageForTurn)
        resultPayload.put("diagnosticMessage", diagnosticMessageForTurn)
        resultPayload.put("result", spokenMessageForTurn)

        lastCompletedCommandId = commandId
        lastFinalStatusForCurrentCommand = finalStatusForTurn
        lastDiagnosticTextForCurrentCommand = diagnosticMessageForTurn
        lastSpokenReplyForCurrentCommand = spokenMessageForTurn
        awaitingVerifiedToolOutcome = false

        // Keep diagnostic details on the slide-up card subtitle, while spoken transcript uses clean natural confirmation
        val synchronizedCard = (pendingCard ?: LiveSlideUpCard(
            functionName = fnName,
            title = "${fnName.replace("_", " ").uppercase(Locale.ROOT)} • $finalStatusForTurn",
            subtitle = diagnosticMessageForTurn,
            appName = fnName,
            isLaunchingApp = finalStatusForTurn == "VERIFIED"
        )).let { card ->
            val titleWithStatus = if (card.title.contains(finalStatusForTurn, ignoreCase = true) ||
                (finalStatusForTurn == "REQUESTED_UNVERIFIED" && card.title.contains("Requested/Unverified", ignoreCase = true))
            ) {
                card.title
            } else {
                "${card.title} • $finalStatusForTurn"
            }
            card.copy(
                title = titleWithStatus,
                subtitle = diagnosticMessageForTurn,
                isLaunchingApp = finalStatusForTurn == "VERIFIED"
            )
        }
        pushSlideUpCard(synchronizedCard)
        _liveAiTranscript.value = spokenMessageForTurn

        val responseJson = JSONObject().apply {
            put("result", resultPayload)
        }
        // CRITICAL: In toolResponse.functionResponses, send ONLY id, original wire name, and response.
        // Keep commandId internal—never serialize commandId beside id/name/response!
        val singleFnResponse = JSONObject().apply {
            put("id", callId)
            put("name", wireFnName)
            put("response", responseJson)
        }

        val toolResponsePayload = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().apply {
                    put(singleFnResponse)
                })
            })
        }
        val serializedToolResponse = toolResponsePayload.toString()
        lastSentToolResponseJson = serializedToolResponse

        // Send `session.sendToolResponse(...)` back over the Live WebSocket if direct socket provided
        val targetWs = webSocket ?: activeWebSocket?.takeIf { isWebSocketReady.get() }
        if (targetWs != null && isWebSocketReady.get()) {
            targetWs.send(serializedToolResponse)
        }

        val userLang = LanguageProcessor.detectLanguage(activeUtterance.ifBlank { spokenMessageForTurn })
        val liveConn = isGeminiLiveWebSocketConnected()
        recordTurnExecutionReport(
            commandId = commandId,
            utterance = activeUtterance,
            spokenText = spokenMessageForTurn,
            diagnosticText = diagnosticMessageForTurn,
            finalStatus = finalStatusForTurn,
            outputEngine = if (liveConn) {
                "GEMINI_LIVE (${_selectedVoice.value.voiceName})"
            } else {
                "ANDROID_TTS"
            },
            language = userLang,
            liveConnected = liveConn
        )

        _connectionState.value = LiveConnectionState.LIVE_LISTENING
        return@withContext singleFnResponse
    }

    suspend fun executeLiveFunctionCallSingle(fnCall: JSONObject): JSONObject =
        executeLiveFunctionCallAndRespond(null, fnCall)

    private fun executeLiveHardwareControl(feature: String, state: String, value: String): String {
        val isOn = state == "ON" || state == "TRUE" || state == "ENABLE" || state == "SET"
        return when {
            feature.contains("flash") || feature.contains("torch") -> {
                val res = actionExecutor.execute(
                    com.example.data.model.VoiceAction(
                        action = "toggle_flashlight",
                        target = if (isOn) "on" else "off",
                        parameters = mapOf("state" to if (isOn) "on" else "off")
                    )
                )
                AndroidBridge.emitToolAction(
                    tool = "control_phone_hardware",
                    params = mapOf("feature" to "flashlight", "state" to if (isOn) "ON" else "OFF"),
                    bridge = androidBridge
                )
                when (res) {
                    is ExecutionResult.Success -> res.message.ifBlank { "Flashlight turned ${if (isOn) "ON" else "OFF"}" }
                    is ExecutionResult.RequestedUnverified -> res.message
                    is ExecutionResult.Failure -> res.error.ifBlank { "Flashlight turned ${if (isOn) "ON" else "OFF"}" }
                }
            }

            feature.contains("wifi") || feature.contains("wi-fi") -> {
                val res = actionExecutor.execute(
                    com.example.data.model.VoiceAction(
                        action = "toggle_wifi",
                        target = if (isOn) "on" else "off"
                    )
                )
                when (res) {
                    is ExecutionResult.Success -> res.message.ifBlank { "Opened Wi-Fi control panel" }
                    is ExecutionResult.RequestedUnverified -> res.message
                    is ExecutionResult.Failure -> res.error.ifBlank { "Opened Wi-Fi control panel" }
                }
            }

            feature.contains("bluetooth") -> {
                val res = actionExecutor.execute(
                    com.example.data.model.VoiceAction(
                        action = "toggle_bluetooth",
                        target = if (isOn) "on" else "off"
                    )
                )
                when (res) {
                    is ExecutionResult.Success -> res.message.ifBlank { "Opened Bluetooth control panel" }
                    is ExecutionResult.RequestedUnverified -> res.message
                    is ExecutionResult.Failure -> res.error.ifBlank { "Opened Bluetooth control panel" }
                }
            }

            feature.contains("volume") -> {
                val direction = when {
                    state.contains("MUTE") || state == "OFF" -> "mute"
                    state.contains("DOWN") || state.contains("DECREASE") -> "down"
                    else -> "up"
                }
                val res = actionExecutor.execute(
                    com.example.data.model.VoiceAction(
                        action = "adjust_volume",
                        target = direction,
                        parameters = mapOf("direction" to direction)
                    )
                )
                when (res) {
                    is ExecutionResult.Success -> res.message.ifBlank { "Adjusted device volume ($direction)" }
                    is ExecutionResult.RequestedUnverified -> res.message
                    is ExecutionResult.Failure -> res.error.ifBlank { "Adjusted device volume ($direction)" }
                }
            }

            feature.contains("alarm") || feature.contains("timer") -> {
                val effectiveVal = value.ifBlank { "10 minutes" }
                val res = actionExecutor.execute(
                    com.example.data.model.VoiceAction(
                        action = if (feature.contains("alarm")) "set_alarm" else "set_timer",
                        target = effectiveVal
                    )
                )
                when (res) {
                    is ExecutionResult.Success -> res.message.ifBlank { "Set $feature for $effectiveVal" }
                    is ExecutionResult.RequestedUnverified -> res.message
                    is ExecutionResult.Failure -> res.error.ifBlank { "Set $feature for $effectiveVal" }
                }
            }

            else -> {
                AndroidBridge.emitToolAction(
                    tool = "control_phone_hardware",
                    params = mapOf("feature" to feature, "state" to state, "value" to value),
                    bridge = androidBridge
                )
                "Set $feature to $state"
            }
        }
    }

    private fun executeLiveCommunicationOrApp(
        actionType: String,
        contactOrAppName: String,
        messageOrQuery: String
    ): Pair<String, String> {
        if (com.omnivoice.agentfix.UiTools.isPlainAppOpenInCommunicateOrOpenApp(
                actionType = actionType,
                contactOrAppName = contactOrAppName,
                messageOrQuery = messageOrQuery
            )
        ) {
            val targetApp = contactOrAppName.ifBlank { actionType }
            val launchRes = kotlinx.coroutines.runBlocking {
                com.omnivoice.agentfix.UiTools.openAppCanonical(
                    context = context,
                    appName = targetApp,
                    callerToolName = "communicate_or_open_app"
                )
            }
            return launchRes.message to (launchRes.resolvedPackage ?: "")
        }

        val encodedMsg = URLEncoder.encode(messageOrQuery, "UTF-8")
        val deepLinkUri = when {
            actionType.contains("whatsapp") -> "whatsapp://send?text=$encodedMsg"
            actionType.contains("call") -> "tel:${contactOrAppName.filter { it.isDigit() || it == '+' }.ifBlank { contactOrAppName }}"
            actionType.contains("sms") -> "sms:$contactOrAppName?body=$encodedMsg"
            actionType.contains("youtube") -> "https://www.youtube.com/results?search_query=${URLEncoder.encode(messageOrQuery.ifBlank { contactOrAppName }, "UTF-8")}"
            actionType.contains("google") || actionType.contains("search") -> "https://www.google.com/search?q=${URLEncoder.encode(messageOrQuery.ifBlank { contactOrAppName }, "UTF-8")}"
            else -> {
                val targetApp = contactOrAppName.ifBlank { actionType }
                val launchRes = kotlinx.coroutines.runBlocking {
                    com.omnivoice.agentfix.UiTools.openAppCanonical(
                        context = context,
                        appName = targetApp,
                        callerToolName = "communicate_or_open_app"
                    )
                }
                return launchRes.message to (launchRes.resolvedPackage ?: "")
            }
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            actionExecutor.execute(
                com.example.data.model.VoiceAction(
                    action = "open_app",
                    target = contactOrAppName
                )
            )
        }

        AndroidBridge.emitToolAction(
            tool = "communicate_or_open_app",
            params = mapOf(
                "actionType" to actionType,
                "contactOrAppName" to contactOrAppName,
                "messageOrQuery" to messageOrQuery,
                "deepLinkUri" to deepLinkUri
            ),
            bridge = androidBridge
        )

        val summary = when {
            actionType.contains("whatsapp") -> "WhatsApp message to $contactOrAppName: \"$messageOrQuery\""
            actionType.contains("call") -> "Dialing $contactOrAppName ($deepLinkUri)"
            actionType.contains("sms") -> "SMS to $contactOrAppName: \"$messageOrQuery\""
            actionType.contains("youtube") -> "Searching YouTube for \"${messageOrQuery.ifBlank { contactOrAppName }}\""
            actionType.contains("google") -> "Searching Google for \"${messageOrQuery.ifBlank { contactOrAppName }}\""
            else -> "Opening $contactOrAppName"
        }
        return summary to deepLinkUri
    }

    private fun generateLivePollinationsOrLocalImage(prompt: String): GeneratedImageAsset {
        val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
        val pollinationsUrl = "https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&nologo=true"

        val downloadedBitmap: Bitmap? = try {
            val req = Request.Builder().url(pollinationsUrl).build()
            restHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pollinations live fetch fallback to local canvas: ${e.message}")
            null
        }

        val finalBitmap = downloadedBitmap ?: renderVibrantFallbackBitmap(prompt)
        val outFile = File(context.cacheDir, "omni_live_img_${System.currentTimeMillis()}.png")
        val bos = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.PNG, 95, bos)
        val pngBytes = bos.toByteArray()
        try {
            FileOutputStream(outFile).use { it.write(pngBytes) }
        } catch (_: Exception) {
        }

        val base64Url = "data:image/png;base64," + Base64.encodeToString(pngBytes, Base64.NO_WRAP)
        return GeneratedImageAsset(
            prompt = prompt,
            style = "Gemini Live 1024x1024",
            aspectRatio = "1:1",
            bitmap = finalBitmap,
            fileUri = Uri.fromFile(outFile).toString(),
            base64DataUrl = base64Url,
            imageUrl = pollinationsUrl
        )
    }

    private fun shareOrApplyWhatsAppDp(asset: GeneratedImageAsset, targetAction: String): String {
        val deepLink = if (targetAction == "whatsapp_dp") "whatsapp://settings/profile" else "whatsapp://send"
        AndroidBridge.emitToolAction(
            tool = "generate_and_use_ai_image",
            params = mapOf(
                "prompt" to asset.prompt,
                "targetAction" to targetAction,
                "imageUrl" to asset.imageUrl,
                "fileUri" to asset.fileUri
            ),
            bridge = androidBridge
        )
        return deepLink
    }

    private fun renderVibrantFallbackBitmap(prompt: String): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(0xFF060B19.toInt(), 0xFF1E1B4B.toInt(), 0xFF00F0FF.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 26f
        canvas.drawText(prompt.take(32), 32f, size / 2f, paint)
        return bmp
    }

    private fun pushSlideUpCard(card: LiveSlideUpCard) {
        _slideUpCards.value = (listOf(card) + _slideUpCards.value).take(6)
        mainHandler.post {
            onSlideUpCardAdded?.invoke(card)
        }
    }

    // =========================================================================
    // 5. HYBRID LIVE TURN FALLBACK & SUPER-AGI BRAIN EXECUTION
    // =========================================================================
    /**
     * Evaluates a consented voice utterance through the wake/command-window/self-suppression/chatter gate
     * in [AssistantAudioStateMachine.shared] and executes ONLY verified commands.
     */
    suspend fun processConsentedVoiceAudioTurn(
        recognizedTranscript: String,
        nowMs: Long = System.currentTimeMillis(),
        expectedWakeInTestAudio: Boolean? = null,
        expectedCommandExecutionInTestAudio: Boolean? = null
    ): WakeSpeechEvaluationResult {
        val coord = AssistantAudioStateMachine.shared
        val eval = coord.evaluateAudioCandidate(
            context = context,
            transcript = recognizedTranscript,
            nowMs = nowMs,
            expectedWakeInTestAudio = expectedWakeInTestAudio,
            expectedCommandExecutionInTestAudio = expectedCommandExecutionInTestAudio
        )
        when (eval.decision) {
            WakeSpeechCandidateDecision.STOP_AND_DISARM_PINNED -> {
                clearPlaybackQueueAndStopImmediately()
                endLiveSession()
                com.example.service.WakeWordForegroundService.stop(context, pinUserStop = true)
            }
            WakeSpeechCandidateDecision.CANCEL_OR_STOP_COMMAND_WINDOW -> {
                clearPlaybackQueueAndStopImmediately()
                activeCommandId = ""
                awaitingVerifiedToolOutcome = false
                currentTurnHasPendingOrExecutedAction = false
                if (com.example.service.WakeWordForegroundService.isServiceActive.value) {
                    com.example.service.WakeWordForegroundService.resume(context)
                }
            }
            WakeSpeechCandidateDecision.WAKE_WORD_DETECTED_OPEN_WINDOW -> {
                _liveUserTranscript.value = recognizedTranscript
                _liveAiTranscript.value = "Jee, hukum karein."
            }
            WakeSpeechCandidateDecision.WAKE_AND_INLINE_COMMAND_ACCEPTED,
            WakeSpeechCandidateDecision.COMMAND_ACCEPTED_IN_WINDOW -> {
                if (eval.shouldExecuteCommand && eval.extractedCommandText.isNotBlank()) {
                    processFallbackLiveTurn(eval.extractedCommandText, null, nowMs = nowMs)
                }
                coord.returnToWakeOnlyListening("COMMAND_TURN_FINISHED")
                if (com.example.service.WakeWordForegroundService.isServiceActive.value) {
                    com.example.service.WakeWordForegroundService.resume(context)
                }
            }
            else -> {
                // Suppressed during TTS/notification, or ignored background conversation / TV audio / stopped
            }
        }
        return eval
    }

    private suspend fun processFallbackLiveTurn(
        textInput: String?,
        wavBytes: ByteArray?,
        commandIdOverride: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val coord = AssistantAudioStateMachine.shared
        if (coord.isStoppedByUserInMemory() || coord.isDeviceMutedInMemory()) {
            return
        }
        if (!textInput.isNullOrBlank() && coord.isEchoOfAssistantOrNotificationSpeech(textInput, nowMs)) {
            coord.recordSuppressedSelfTrigger("Blocked TTS/notification echo in processFallbackLiveTurn")
            return
        }

        if (!isGeminiLiveWebSocketConnected()) {
            _connectionState.value = LiveConnectionState.CONNECTING
        }

        val rawResult = if (!textInput.isNullOrBlank()) {
            SuperAgiBrainEngine.analyzeAndRespondLikeGeminiAndChatGpt(context, textInput)
        } else if (wavBytes != null) {
            val audioRes = DeterministicNluEngine.analyzeAudioDeterministically(context, wavBytes, "audio/wav")
            if (audioRes.verbatimTranscript.isNotBlank()) {
                SuperAgiBrainEngine.analyzeAndRespondLikeGeminiAndChatGpt(context, audioRes.verbatimTranscript)
            } else {
                audioRes
            }
        } else {
            DeterministicNluEngine.analyzeTextDeterministicallyLocal(context, "")
        }

        // CRITICAL FIX: Never endlessly speak "Jee main sun raha hoon, hukum karein." on unverified silence/background noise,
        // and NEVER restart SpeechRecognizer in a continuous wake loop!
        if (rawResult.verbatimTranscript.isBlank() && textInput.isNullOrBlank()) {
            _connectionState.value = LiveConnectionState.LIVE_LISTENING
            coord.returnToWakeOnlyListening("SILENT_TURN_RETURN_TO_WAKE")
            if (com.example.service.WakeWordForegroundService.isServiceActive.value) {
                com.example.service.WakeWordForegroundService.resume(context)
            }
            return
        }

        val cleanUtterance = rawResult.verbatimTranscript.ifBlank { textInput.orEmpty().trim() }
        val turnCommandId = commandIdOverride?.takeIf { it.isNotBlank() }
            ?: startNewCommandTurn(utterance = cleanUtterance)
        if (cleanUtterance.isNotBlank()) {
            _liveUserTranscript.value = cleanUtterance
            OmniAutonomousCoreEngine.analyzeUserMood(cleanUtterance, _inputAudioRms.value)
            com.omnivoice.agentfix.AgentSession.onUserUtterance(cleanUtterance)
        }

        // Never speak a speculative "ho gaya" reply BEFORE action tasks execute and verify!
        val isReturnToOriginCommand = com.omnivoice.agentfix.UiTools.isReturnToPreviousAppCommand(cleanUtterance)
        val hasActionTasks = isReturnToOriginCommand || rawResult.tasksToExecute.isNotEmpty()
        currentTurnHasPendingOrExecutedAction = hasActionTasks
        var finalSpokenReply = if (hasActionTasks) "" else sanitizeSpokenText(rawResult.spokenReply, cleanUtterance)
        val userLang = LanguageProcessor.detectLanguage(cleanUtterance.ifBlank { finalSpokenReply })

        if (!hasActionTasks) {
            _liveAiTranscript.value = finalSpokenReply
            val liveConnectedNow = isGeminiLiveWebSocketConnected()
            if (liveConnectedNow) {
                val ws = activeWebSocket
                if (ws != null) {
                    val clientContentMsg = JSONObject().apply {
                        put("clientContent", JSONObject().apply {
                            put("turns", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "user")
                                    put("parts", JSONArray().apply {
                                        put(JSONObject().put("text", cleanUtterance))
                                    })
                                })
                            })
                            put("turnComplete", true)
                        })
                    }
                    val serializedClientContent = clientContentMsg.toString()
                    lastSentClientContentJson = serializedClientContent
                    ws.send(serializedClientContent)
                }
                _connectionState.value = LiveConnectionState.LIVE_LISTENING
                recordTurnExecutionReport(
                    commandId = turnCommandId,
                    utterance = cleanUtterance,
                    spokenText = finalSpokenReply,
                    diagnosticText = finalSpokenReply,
                    finalStatus = "CONVERSATIONAL",
                    outputEngine = "GEMINI_LIVE (${_selectedVoice.value.voiceName})",
                    language = userLang,
                    liveConnected = true
                )
            } else {
                _connectionState.value = LiveConnectionState.LIVE_AI_SPEAKING
                ttsFallback?.onSpeechCompleted = {
                    _connectionState.value = LiveConnectionState.LIVE_LISTENING
                    coord.returnToWakeOnlyListening("TTS_CONVERSATIONAL_TURN_COMPLETED")
                    if (com.example.service.WakeWordForegroundService.isServiceActive.value) {
                        com.example.service.WakeWordForegroundService.resume(context)
                    }
                }
                ttsFallback?.speak(finalSpokenReply, 1.05f, 1.0f, userLang.ttsLocale)
                recordTurnExecutionReport(
                    commandId = turnCommandId,
                    utterance = cleanUtterance,
                    spokenText = finalSpokenReply,
                    diagnosticText = finalSpokenReply,
                    finalStatus = "CONVERSATIONAL",
                    outputEngine = "ANDROID_TTS",
                    language = userLang,
                    liveConnected = false
                )
            }
        }

        // Execute all explicit tasks with turnCommandId correlation
        var lastToolResponseMessage = ""
        val activeLiveWs = activeWebSocket?.takeIf { isWebSocketReady.get() }
        if (isReturnToOriginCommand) {
            val fnObj = JSONObject().apply {
                put("id", "${turnCommandId}_return_origin")
                put("commandId", turnCommandId)
                put("name", "return_to_origin")
                put("args", JSONObject())
            }
            val singleResp = executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
            val msgFromResp = singleResp?.optJSONObject("response")?.optJSONObject("result")?.optString("spokenMessage")
                ?.takeIf { it.isNotBlank() }
                ?: singleResp?.optJSONObject("response")?.optJSONObject("result")?.optString("message").orEmpty()
            if (msgFromResp.isNotBlank()) {
                lastToolResponseMessage = msgFromResp
            }
        }
        val tasksToRun = if (isReturnToOriginCommand) emptyList() else rawResult.tasksToExecute
        for ((idx, task) in tasksToRun.withIndex()) {
            if (activeCommandId.isNotBlank() && activeCommandId != turnCommandId) {
                Log.w(TAG, "Aborting remaining fallback tasks for superseded turnCommandId=$turnCommandId (active=$activeCommandId)")
                return
            }
            val callId = "${turnCommandId}_step_${idx + 1}"
            val singleResp: JSONObject? = when (task.taskType) {
                NluTaskType.OPEN_APP -> {
                    val fnObj = JSONObject().apply {
                        put("id", callId)
                        put("commandId", turnCommandId)
                        put("name", "open_app")
                        put("args", JSONObject().apply {
                            put("app_name", task.target)
                        })
                    }
                    executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                }

                NluTaskType.WHATSAPP_MESSAGE -> {
                    val fnObj = JSONObject().apply {
                        put("id", callId)
                        put("commandId", turnCommandId)
                        put("name", "whatsapp_send_message")
                        put("args", JSONObject().apply {
                            put("contact", task.target)
                            put("message", task.actionValue)
                        })
                    }
                    executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                }

                NluTaskType.WHATSAPP_DELETE_MESSAGE -> {
                    val fnObj = JSONObject().apply {
                        put("id", callId)
                        put("commandId", turnCommandId)
                        put("name", "whatsapp_delete_message")
                        put("args", JSONObject().apply {
                            put("message_text", task.target.ifBlank { "last" })
                            put("scope", task.actionValue.ifBlank { "everyone" })
                        })
                    }
                    executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                }

                NluTaskType.WHATSAPP_CALL -> {
                    val fnObj = JSONObject().apply {
                        put("id", callId)
                        put("commandId", turnCommandId)
                        put("name", "whatsapp_call")
                        put("args", JSONObject().apply {
                            put("contact", task.target)
                            put("call_type", task.actionValue.ifBlank { "voice" })
                        })
                    }
                    executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                }

                NluTaskType.UI_CLICK -> {
                    val fnObj = JSONObject().apply {
                        put("id", callId)
                        put("commandId", turnCommandId)
                        put("name", "ui_click_text")
                        put("args", JSONObject().apply {
                            put("text", task.target)
                            put("exact", false)
                        })
                    }
                    executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                }

                NluTaskType.GENERATE_AI_IMAGE, NluTaskType.WHATSAPP_DP_UPDATE -> {
                    val targetAction = if (rawResult.tasksToExecute.any { it.taskType == NluTaskType.WHATSAPP_DP_UPDATE }) {
                        "whatsapp_dp"
                    } else {
                        "display"
                    }
                    val fnObj = JSONObject().apply {
                        put("id", callId)
                        put("commandId", turnCommandId)
                        put("name", "generate_and_use_ai_image")
                        put("args", JSONObject().apply {
                            put("prompt", task.actionValue)
                            put("targetAction", targetAction)
                        })
                    }
                    executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                }

                NluTaskType.FLASHLIGHT, NluTaskType.WIFI, NluTaskType.BLUETOOTH,
                NluTaskType.VOLUME, NluTaskType.ALARM_TIMER, NluTaskType.SYSTEM_SETTING -> {
                    val fnObj = JSONObject().apply {
                        put("id", callId)
                        put("commandId", turnCommandId)
                        put("name", "control_phone_hardware")
                        put("args", JSONObject().apply {
                            put("feature", if (task.taskType == NluTaskType.SYSTEM_SETTING) task.target.lowercase() else task.taskType.name.lowercase())
                            put("state", task.actionValue)
                            put("value", task.actionValue)
                        })
                    }
                    executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                }

                NluTaskType.PHONE_CALL -> {
                    val fnObj = JSONObject().apply {
                        put("id", callId)
                        put("commandId", turnCommandId)
                        put("name", "make_direct_phone_call")
                        put("args", JSONObject().apply {
                            put("contactName", task.target)
                        })
                    }
                    executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                }

                NluTaskType.YOUTUBE_SEARCH -> {
                    val fnObj = JSONObject().apply {
                        put("id", callId)
                        put("commandId", turnCommandId)
                        put("name", "play_video")
                        put("args", JSONObject().apply {
                            put("query", task.actionValue)
                            put("app", task.target.ifBlank { "youtube" })
                        })
                    }
                    executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                }

                NluTaskType.GOOGLE_SEARCH -> {
                    val targetPlatform = task.target.ifBlank { "google" }
                    val fnObj = JSONObject().apply {
                        put("id", callId)
                        put("commandId", turnCommandId)
                        put("name", "search_anything")
                        put("args", JSONObject().apply {
                            put("query", task.actionValue)
                            put("app", targetPlatform)
                        })
                    }
                    executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                }

                NluTaskType.SMS -> {
                    val res = actionExecutor.execute(
                        com.example.data.model.VoiceAction(
                            action = "send_sms",
                            target = task.target,
                            parameters = mapOf("contact_name" to task.target, "message" to task.actionValue),
                            spokenResponse = ""
                        )
                    )
                    lastToolResponseMessage = when (res) {
                        is ExecutionResult.Success -> res.message
                        is ExecutionResult.RequestedUnverified -> res.message
                        is ExecutionResult.Failure -> res.error
                    }
                    null
                }

                NluTaskType.CUSTOM_ANDROID_INTENT -> {
                    when (task.target.lowercase()) {
                        "screenshot" -> {
                            val res = actionExecutor.execute(com.example.data.model.VoiceAction(action = "take_screenshot", target = "screen"))
                            lastToolResponseMessage = when (res) {
                                is ExecutionResult.Success -> res.message
                                is ExecutionResult.RequestedUnverified -> res.message
                                is ExecutionResult.Failure -> res.error
                            }
                            null
                        }
                        "camera" -> {
                            val res = actionExecutor.execute(com.example.data.model.VoiceAction(action = "take_photo", target = "camera"))
                            lastToolResponseMessage = when (res) {
                                is ExecutionResult.Success -> res.message
                                is ExecutionResult.RequestedUnverified -> res.message
                                is ExecutionResult.Failure -> res.error
                            }
                            null
                        }
                        "origin_task" -> {
                            val fnObj = JSONObject().apply {
                                put("id", callId)
                                put("commandId", turnCommandId)
                                put("name", "return_to_origin")
                                put("args", JSONObject())
                            }
                            executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                        }
                        "navigation" -> {
                            val res = actionExecutor.execute(com.example.data.model.VoiceAction(action = "navigate", target = task.actionValue.lowercase(), parameters = mapOf("action" to task.actionValue.lowercase())))
                            lastToolResponseMessage = when (res) {
                                is ExecutionResult.Success -> res.message
                                is ExecutionResult.RequestedUnverified -> res.message
                                is ExecutionResult.Failure -> res.error
                            }
                            null
                        }
                        else -> {
                            val fnObj = JSONObject().apply {
                                put("id", callId)
                                put("commandId", turnCommandId)
                                put("name", "open_app")
                                put("args", JSONObject().apply {
                                    put("app_name", task.target.ifBlank { "settings" })
                                })
                            }
                            executeLiveFunctionCallAndRespond(activeLiveWs, fnObj)
                        }
                    }
                }
            }
            val msgFromResp = singleResp?.optJSONObject("response")?.optJSONObject("result")?.optString("spokenMessage")
                ?.takeIf { it.isNotBlank() }
                ?: singleResp?.optJSONObject("response")?.optJSONObject("result")?.optString("message").orEmpty()
            if (msgFromResp.isNotBlank()) {
                lastToolResponseMessage = msgFromResp
            }
        }

        // Ignore late completion if another command started while tasks were running
        if (activeCommandId.isNotBlank() && activeCommandId != turnCommandId) {
            return
        }

        if (hasActionTasks) {
            // NEVER fall back to a previous command's lastCanonicalResult.message!
            finalSpokenReply = when {
                lastCompletedCommandId == turnCommandId && lastSpokenReplyForCurrentCommand.isNotBlank() ->
                    lastSpokenReplyForCurrentCommand
                lastToolResponseMessage.isNotBlank() ->
                    sanitizeSpokenText(lastToolResponseMessage, cleanUtterance)
                else ->
                    buildMissingOutcomeSpokenReply(cleanUtterance)
            }
            _liveAiTranscript.value = finalSpokenReply

            // SINGLE SPEAKER PER TURN:
            // When Gemini Live is connected, let the existing Live session deliver the result in the user's selected voice.
            // Local execution of "WhatsApp kholo" must NOT switch the current or next conversation turn to Android TTS!
            if (isGeminiLiveWebSocketConnected()) {
                _connectionState.value = LiveConnectionState.LIVE_LISTENING
                coord.returnToWakeOnlyListening("LIVE_ACTION_TURN_COMPLETED")
            } else {
                _connectionState.value = LiveConnectionState.LIVE_AI_SPEAKING
                ttsFallback?.onSpeechCompleted = {
                    _connectionState.value = LiveConnectionState.LIVE_LISTENING
                    coord.returnToWakeOnlyListening("TTS_ACTION_TURN_COMPLETED")
                    if (com.example.service.WakeWordForegroundService.isServiceActive.value) {
                        com.example.service.WakeWordForegroundService.resume(context)
                    }
                }
                ttsFallback?.speak(finalSpokenReply, 1.05f, 1.0f, userLang.ttsLocale)
            }
        }

        mainHandler.post {
            onTranscriptTurnCompleted?.invoke(cleanUtterance, finalSpokenReply)
        }
    }

    private fun updateVisualizerFromFloatSamples(samples: FloatArray, isAiOutput: Boolean) {
        if (samples.isEmpty()) return
        var sumSq = 0.0
        for (s in samples) {
            sumSq += (s * s).toDouble()
        }
        val rms = (sqrt(sumSq / samples.size).toFloat() * 320f).coerceIn(0f, 100f)
        if (isAiOutput) {
            _outputAudioRms.value = rms
        } else {
            _inputAudioRms.value = rms
        }

        val bandCount = 8
        val chunkSize = (samples.size / bandCount).coerceAtLeast(1)
        val bands = MutableList(bandCount) { 0.08f }
        for (b in 0 until bandCount) {
            val start = b * chunkSize
            val end = ((b + 1) * chunkSize).coerceAtMost(samples.size)
            var bandSum = 0f
            var count = 0
            for (i in start until end) {
                bandSum += abs(samples[i])
                count++
            }
            val avg = if (count > 0) bandSum / count else 0f
            bands[b] = (avg * 3.8f).coerceIn(0.08f, 1f)
        }
        _activeFrequencyBands.value = bands
    }

    private fun attachHardwareAudioFilters(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                autoGainControl = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun releaseMicAudioRecord() {
        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null
        try {
            autoGainControl?.release()
        } catch (_: Exception) {
        }
        autoGainControl = null

        val rec = micAudioRecord ?: return
        micAudioRecord = null
        try {
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                rec.stop()
            }
            rec.release()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun releaseOutputAudioTrack() {
        val track = outputAudioTrack ?: return
        outputAudioTrack = null
        try {
            track.stop()
        } catch (_: Exception) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
    }
}

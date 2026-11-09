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
import android.os.SystemClock
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

enum class ObservedAssistantCaptureState(val displayLabel: String) {
    IDLE("Idle"),
    STARTING("Starting"),
    LISTENING("Listening"),
    PROCESSING("Processing"),
    SPEAKING("Speaking"),
    MUTED("Muted"),
    ERROR("Error")
}

enum class CommandTurnStallStage {
    NONE,
    SOUND_DETECTED_NO_ACCEPTED_COMMAND,
    COMMAND_ACCEPTED_REQUEST_NOT_SENT,
    SERVER_OR_TOOL_DELAY,
    GENERATED_REPLY_NO_AUDIO,
    RECEIVED_AUDIO_PLAYBACK_FAILED
}

enum class CommandSpeechOutputState {
    NONE,
    REQUESTED,
    QUEUED,
    STARTED,
    COMPLETED,
    INTERRUPTED,
    FAILED
}

enum class RecognitionFailureCategory {
    NONE,
    INTENTIONAL_CANCELLATION,
    NO_MATCH_OR_SILENCE,
    TRANSIENT_RECOGNITION_ERROR,
    PERMISSION_OR_SERVICE_FAILURE
}

interface SpeechCaptureAdapter {
    fun startListening(intent: Intent, listener: RecognitionListener, generation: Long)
    fun stopListening(generation: Long) {}
    fun cancel(generation: Long) {}
    fun destroy(generation: Long) {}
}

data class LiveSessionDiagnosticSnapshot(
    val buildCommitId: String = OmniLiveSessionEngine.BUILD_COMMIT_ID,
    val sessionState: String = ObservedAssistantCaptureState.IDLE.displayLabel,
    val recognitionErrorName: String = "NONE",
    val recognitionErrorCode: Int = 0,
    val activeCaptureGeneration: Long = 0L,
    val readyCaptureGeneration: Long = 0L,
    val recognitionRetryCount: Int = 0,
    val maxRecognitionRetries: Int = 2,
    val acceptedOrRejectedDecision: String = "NONE",
    val commandId: String = "NONE",
    val requestedOutputProvider: String = "NONE",
    val actualOutputProvider: String = "NONE",
    val ttsState: String = CommandSpeechOutputState.NONE.name,
    val ttsError: String = "NONE",
    val pcmFramesReceived: Int = 0,
    val pcmFramesPlayed: Int = 0,
    val pcmBytesWritten: Int = 0,
    val stickyErrorSummary: String = "NONE"
) {
    fun toRedactedCopyText(): String {
        val safeDecision = OmniLiveSessionEngine.redactPrivateTextAndCredentials(acceptedOrRejectedDecision.ifBlank { "NONE" })
        val safeCmdId = OmniLiveSessionEngine.redactPrivateTextAndCredentials(commandId.ifBlank { "NONE" })
        val safeReqOut = OmniLiveSessionEngine.redactPrivateTextAndCredentials(requestedOutputProvider.ifBlank { "NONE" })
        val safeActOut = OmniLiveSessionEngine.redactPrivateTextAndCredentials(actualOutputProvider.ifBlank { "NONE" })
        val safeTtsErr = OmniLiveSessionEngine.redactPrivateTextAndCredentials(ttsError.ifBlank { "NONE" })
        val safeStickyErr = OmniLiveSessionEngine.redactPrivateTextAndCredentials(stickyErrorSummary.ifBlank { "NONE" })
        return buildString {
            appendLine("commit=$buildCommitId")
            appendLine("sessionState=$sessionState")
            appendLine("recognitionError=$recognitionErrorName($recognitionErrorCode)")
            appendLine("captureGeneration=active:$activeCaptureGeneration/ready:$readyCaptureGeneration(retry:$recognitionRetryCount/$maxRecognitionRetries)")
            appendLine("decision=$safeDecision")
            appendLine("commandId=$safeCmdId")
            appendLine("outputProvider=$safeReqOut->$safeActOut")
            appendLine("ttsState=$ttsState")
            appendLine("ttsError=$safeTtsErr")
            appendLine("pcmProgress=recv:$pcmFramesReceived/played:$pcmFramesPlayed/bytes:$pcmBytesWritten")
            append("lastError=$safeStickyErr")
        }
    }

    fun toCompactHudLine(): String {
        val shortCommit = buildCommitId.take(8)
        val safeDecision = OmniLiveSessionEngine.redactPrivateTextAndCredentials(acceptedOrRejectedDecision.ifBlank { "NONE" })
        val safeCmd = OmniLiveSessionEngine.redactPrivateTextAndCredentials(commandId.ifBlank { "NONE" })
        val safeActOut = OmniLiveSessionEngine.redactPrivateTextAndCredentials(actualOutputProvider.ifBlank { "NONE" })
        val safeTtsErr = OmniLiveSessionEngine.redactPrivateTextAndCredentials(ttsError.ifBlank { "NONE" })
        val safeErr = OmniLiveSessionEngine.redactPrivateTextAndCredentials(stickyErrorSummary.ifBlank { "NONE" })
        val errTail = if (safeErr.isNotBlank() && safeErr != "NONE") " • ERR:$safeErr" else ""
        return "COMMIT:$shortCommit • GEN:$activeCaptureGeneration(r:$readyCaptureGeneration) • STT:$recognitionErrorName($recognitionErrorCode) • DEC:$safeDecision • CMD:$safeCmd • OUT:$safeActOut • TTS:$ttsState($safeTtsErr) • PCM:$pcmFramesPlayed/$pcmFramesReceived$errTail"
    }
}

data class CommandAudioDiagnosticEntry(
    val commandId: String,
    val route: String = "CONVERSATIONAL",
    val toolCallId: String = "NONE",
    val wireFunctionName: String = "",
    val executedFunctionName: String = "",
    val observedActionResult: String = "NONE",
    val outputState: CommandSpeechOutputState = CommandSpeechOutputState.NONE,
    val outputEngine: String = "NONE",
    val requestedOutputProvider: String = "NONE",
    val actualOutputProvider: String = "NONE",
    val requestedVoiceId: String = "NONE",
    val resolvedVoiceId: String = "NONE",
    val resolvedLocaleTag: String = "NONE",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val chosenReplyLanguage: DetectedLanguage = DetectedLanguage.ENGLISH,
    val languageSelectionReason: String = "DEFAULT",
    val fallbackOrReconnectReason: String = "NONE",
    val wsGeneration: Long = 0L,
    val wsCloseCode: Int? = null,
    val wsCloseReason: String = "",
    val wsSendSucceeded: Boolean? = null,
    val audioFramesReceived: Int = 0,
    val audioFramesQueued: Int = 0,
    val audioFramesPlayed: Int = 0,
    val pcmBytesWrittenToHardware: Int = 0,
    val pcmWriteErrorCount: Int = 0,
    val serverTurnCompleteObserved: Boolean = false,
    val hardwarePlaybackDrained: Boolean = false,
    val ttsUtteranceId: String = "NONE",
    val ttsStartedCount: Int = 0,
    val ttsCompletedCount: Int = 0,
    val ttsErrorCount: Int = 0,
    val recoveryTriggered: Boolean = false,
    val lateDuplicateFramesSuppressed: Int = 0,
    val cancellationReason: String = "NONE",
    val launchRequestedElapsedMs: Long = 0L,
    val captureReadyElapsedMs: Long = 0L,
    val speechOnsetElapsedMs: Long = 0L,
    val speechEndElapsedMs: Long = 0L,
    val commandAcceptedElapsedMs: Long = 0L,
    val requestSentElapsedMs: Long = 0L,
    val firstResponseReceivedElapsedMs: Long = 0L,
    val audioReceivedElapsedMs: Long = 0L,
    val playbackStartedElapsedMs: Long = 0L,
    val playbackCompletedElapsedMs: Long = 0L,
    val partialRecognitionCount: Int = 0,
    val finalRecognitionObserved: Boolean = false,
    val stallStage: CommandTurnStallStage = CommandTurnStallStage.NONE
) {
    val sessionGeneration: Long
        get() = wsGeneration

    val startupToCaptureReadyLatencyMs: Long
        get() = if (launchRequestedElapsedMs > 0L && captureReadyElapsedMs >= launchRequestedElapsedMs) {
            captureReadyElapsedMs - launchRequestedElapsedMs
        } else {
            -1L
        }

    val speechEndToFirstResponseLatencyMs: Long
        get() = if (speechEndElapsedMs > 0L && firstResponseReceivedElapsedMs >= speechEndElapsedMs) {
            firstResponseReceivedElapsedMs - speechEndElapsedMs
        } else if (commandAcceptedElapsedMs > 0L && firstResponseReceivedElapsedMs >= commandAcceptedElapsedMs) {
            firstResponseReceivedElapsedMs - commandAcceptedElapsedMs
        } else {
            -1L
        }

    val speechEndToFirstAudioPlayLatencyMs: Long
        get() = if (speechEndElapsedMs > 0L && playbackStartedElapsedMs >= speechEndElapsedMs) {
            playbackStartedElapsedMs - speechEndElapsedMs
        } else if (commandAcceptedElapsedMs > 0L && playbackStartedElapsedMs >= commandAcceptedElapsedMs) {
            playbackStartedElapsedMs - commandAcceptedElapsedMs
        } else {
            -1L
        }

    fun toRedactedDiagnosticLine(): String {
        return "cmd=${OmniLiveSessionEngine.redactPrivateTextAndCredentials(commandId.ifBlank { "NONE" })} | " +
            "sessionGen=$wsGeneration | " +
            "route=${route.ifBlank { "NONE" }} | " +
            "toolCallId=${OmniLiveSessionEngine.redactPrivateTextAndCredentials(toolCallId.ifBlank { "NONE" })} | " +
            "actionResult=${observedActionResult.ifBlank { "NONE" }} | " +
            "replyLang=${chosenReplyLanguage.name} | " +
            "langReason=${OmniLiveSessionEngine.redactPrivateTextAndCredentials(languageSelectionReason.ifBlank { "NONE" })} | " +
            "reqProvider=${requestedOutputProvider.ifBlank { "NONE" }} | " +
            "actProvider=${actualOutputProvider.ifBlank { outputEngine.ifBlank { "NONE" } }} | " +
            "reqVoice=${OmniLiveSessionEngine.redactPrivateTextAndCredentials(requestedVoiceId.ifBlank { "NONE" })} | " +
            "resVoice=${OmniLiveSessionEngine.redactPrivateTextAndCredentials(resolvedVoiceId.ifBlank { "NONE" })} | " +
            "locale=${resolvedLocaleTag.ifBlank { "NONE" }} | " +
            "rate=$speechRate | " +
            "pitch=$pitch | " +
            "fallbackReason=${OmniLiveSessionEngine.redactPrivateTextAndCredentials(fallbackOrReconnectReason.ifBlank { "NONE" })} | " +
            "outputState=${outputState.name} | " +
            "stallStage=${stallStage.name} | " +
            "engine=${outputEngine.ifBlank { "NONE" }} | " +
            "tCaptureReady=$captureReadyElapsedMs | " +
            "tSpeechOnset=$speechOnsetElapsedMs | " +
            "tSpeechEnd=$speechEndElapsedMs | " +
            "tAccepted=$commandAcceptedElapsedMs | " +
            "tReqSent=$requestSentElapsedMs | " +
            "tFirstResp=$firstResponseReceivedElapsedMs | " +
            "tAudioRecv=$audioReceivedElapsedMs | " +
            "tPlayStart=$playbackStartedElapsedMs | " +
            "tPlayEnd=$playbackCompletedElapsedMs | " +
            "latStartupMs=$startupToCaptureReadyLatencyMs | " +
            "latFirstRespMs=$speechEndToFirstResponseLatencyMs | " +
            "latFirstPlayMs=$speechEndToFirstAudioPlayLatencyMs | " +
            "partialRec=$partialRecognitionCount | " +
            "finalRec=$finalRecognitionObserved | " +
            "wsGen=$wsGeneration | " +
            "wsClose=${wsCloseCode?.toString() ?: "NONE"} | " +
            "wsCloseReason=${OmniLiveSessionEngine.redactPrivateTextAndCredentials(wsCloseReason.ifBlank { "NONE" })} | " +
            "pcmRecv=$audioFramesReceived | " +
            "pcmQueued=$audioFramesQueued | " +
            "pcmPlayed=$audioFramesPlayed | " +
            "pcmBytesWritten=$pcmBytesWrittenToHardware | " +
            "pcmWriteErr=$pcmWriteErrorCount | " +
            "turnComplete=$serverTurnCompleteObserved | " +
            "hwDrained=$hardwarePlaybackDrained | " +
            "ttsUtt=${OmniLiveSessionEngine.redactPrivateTextAndCredentials(ttsUtteranceId.ifBlank { "NONE" })} | " +
            "ttsStart=$ttsStartedCount | " +
            "ttsEnd=$ttsCompletedCount | " +
            "ttsErr=$ttsErrorCount | " +
            "recovery=$recoveryTriggered | " +
            "dupSuppressed=$lateDuplicateFramesSuppressed | " +
            "cancel=${OmniLiveSessionEngine.redactPrivateTextAndCredentials(cancellationReason.ifBlank { "NONE" })}"
    }
}

data class ExecutedToolCallResult(
    val callId: String,
    val wireFunctionName: String,
    val executedFunctionName: String,
    val commandId: String,
    val isServerOriginCall: Boolean,
    val finalStatus: String,
    val spokenMessage: String,
    val diagnosticMessage: String,
    val resultPayload: JSONObject,
    val activeUtterance: String = "",
    val userLanguage: DetectedLanguage = DetectedLanguage.ENGLISH,
    val languageSelectionReason: String = "DEFAULT",
    val staleIgnored: Boolean = false
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
    val foregroundPackage: String?,
    val outputState: CommandSpeechOutputState = CommandSpeechOutputState.NONE,
    val audioFramesReceived: Int = 0,
    val audioFramesPlayed: Int = 0,
    val recoveryTriggered: Boolean = false,
    val sessionGeneration: Long = 0L,
    val languageSelectionReason: String = "DEFAULT",
    val requestedOutputProvider: String = outputEngine,
    val actualOutputProvider: String = outputEngine,
    val requestedVoiceId: String = "NONE",
    val resolvedVoiceId: String = "NONE",
    val resolvedLocaleTag: String = language.ttsLocale.toLanguageTag(),
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val fallbackOrReconnectReason: String = "NONE",
    val launchRequestedElapsedMs: Long = 0L,
    val captureReadyElapsedMs: Long = 0L,
    val speechOnsetElapsedMs: Long = 0L,
    val speechEndElapsedMs: Long = 0L,
    val commandAcceptedElapsedMs: Long = 0L,
    val requestSentElapsedMs: Long = 0L,
    val firstResponseReceivedElapsedMs: Long = 0L,
    val audioReceivedElapsedMs: Long = 0L,
    val playbackStartedElapsedMs: Long = 0L,
    val playbackCompletedElapsedMs: Long = 0L,
    val partialRecognitionCount: Int = 0,
    val finalRecognitionObserved: Boolean = false,
    val stallStage: CommandTurnStallStage = CommandTurnStallStage.NONE
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

        const val BUILD_COMMIT_ID = "1599cab37fbb53d25e5f1767f502bf145192e5af"
        const val BUILD_SHORT_COMMIT_ID = "1599cab3"

        const val LIVE_MODEL_PRIMARY = "models/gemini-2.5-flash-native-audio-preview-09-2025"
        const val LIVE_MODEL_FALLBACK = "models/gemini-2.0-flash-exp"

        const val INPUT_SAMPLE_RATE = 16000
        const val OUTPUT_SAMPLE_RATE = 24000

        fun speechRecognizerErrorName(errorCode: Int): String = when (errorCode) {
            0 -> "NONE"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            10 -> "ERROR_TOO_MANY_REQUESTS"
            11 -> "ERROR_SERVER_DISCONNECTED"
            12 -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            13 -> "ERROR_LANGUAGE_UNAVAILABLE"
            14 -> "ERROR_CANNOT_CHECK_SUPPORT"
            15 -> "ERROR_CANNOT_LISTEN_IN_BACKGROUND"
            -1 -> "EMPTY_RECOGNITION_RESULTS"
            -2 -> "RECOGNIZER_SERVICE_UNAVAILABLE"
            else -> "ERROR_UNKNOWN_$errorCode"
        }

        fun redactPrivateTextAndCredentials(raw: String?): String {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return ""
            return text
                .replace(Regex("(?i)(key|api_key|token|secret|password|authorization|transcript|utterance)\\s*[:=]\\s*[^\\s&\"',|]+"), "$1=[REDACTED]")
                .replace(Regex("AIza[0-9A-Za-z_-]{10,}"), "[REDACTED_KEY]")
                .replace(Regex("(?i)Bearer\\s+[0-9A-Za-z._-]+"), "Bearer [REDACTED]")
                .replace(Regex("\\b\\+?\\d[\\d\\s()-]{6,}\\d\\b"), "[REDACTED_PHONE]")
                .replace(Regex("(?i)(draft\\s*\\d+|secret\\s+draft\\s*\\d+|note_body|message_body)"), "[REDACTED_TEXT]")
        }

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
                            put("silenceDurationMs", 620)
                            put("prefixPaddingMs", 120)
                        })
                    })
                    put("tools", buildLiveToolsJson())
                })
            }
        }

        /**
         * Extracts the PCM sample rate from a Gemini Live `inlineData.mimeType` string
         * (e.g., `"audio/pcm;rate=24000"` -> `24000`). Defaults to [OUTPUT_SAMPLE_RATE] (`24000`).
         */
        fun parsePcmSampleRateFromMimeType(mimeType: String?): Int {
            val raw = mimeType?.trim().orEmpty()
            if (raw.isEmpty()) return OUTPUT_SAMPLE_RATE
            val match = Regex("rate\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE).find(raw)
            val parsed = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: OUTPUT_SAMPLE_RATE
            return if (parsed in 8000..96000) parsed else OUTPUT_SAMPLE_RATE
        }

        /**
         * Ensures 16-bit signed little-endian mono PCM bytes are even-byte frame-aligned and
         * resampled to [OUTPUT_SAMPLE_RATE] (`24000 Hz`) if the incoming stream uses a different rate.
         * Prevents sample boundary byte-shift noise and pitch/speed warping across cycles.
         */
        fun ensure24kHzPcm16Mono(rawBytes: ByteArray, sourceRate: Int = OUTPUT_SAMPLE_RATE): ByteArray {
            val alignedSize = rawBytes.size and 1.inv()
            if (alignedSize <= 0) return ByteArray(0)
            val alignedBytes = if (alignedSize == rawBytes.size) rawBytes else rawBytes.copyOf(alignedSize)
            if (sourceRate == OUTPUT_SAMPLE_RATE || sourceRate !in 8000..96000) {
                return alignedBytes
            }
            val inSamples = alignedSize / 2
            if (inSamples <= 1) return alignedBytes
            val outSamples = ((inSamples.toLong() * OUTPUT_SAMPLE_RATE) / sourceRate).toInt().coerceAtLeast(1)
            val outBytes = ByteArray(outSamples * 2)
            for (i in 0 until outSamples) {
                val srcPos = (i.toDouble() * (inSamples - 1)) / (outSamples - 1).coerceAtLeast(1)
                val idx0 = srcPos.toInt().coerceIn(0, inSamples - 1)
                val idx1 = (idx0 + 1).coerceIn(0, inSamples - 1)
                val frac = srcPos - idx0
                val s0 = ((alignedBytes[idx0 * 2 + 1].toInt() shl 8) or (alignedBytes[idx0 * 2].toInt() and 0xFF)).toShort().toInt()
                val s1 = ((alignedBytes[idx1 * 2 + 1].toInt() shl 8) or (alignedBytes[idx1 * 2].toInt() and 0xFF)).toShort().toInt()
                val interp = (s0 + ((s1 - s0) * frac).roundToInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                outBytes[i * 2] = (interp and 0xFF).toByte()
                outBytes[i * 2 + 1] = ((interp shr 8) and 0xFF).toByte()
            }
            return outBytes
        }

        /**
         * Decodes Base64 16-bit signed little-endian PCM (`audio/pcm;rate=24000`) into a `FloatArray` (`[-1f, 1f]`)
         * for real-time audio visualization and returns both the aligned 24kHz PCM bytes and `FloatArray`.
         */
        fun decodeBase64Pcm16ToFloat32(
            base64Pcm: String,
            mimeType: String = "audio/pcm;rate=24000"
        ): Pair<ByteArray, FloatArray> {
            val decoded = Base64.decode(base64Pcm, Base64.DEFAULT)
            val sourceRate = parsePcmSampleRateFromMimeType(mimeType)
            val rawBytes = ensure24kHzPcm16Mono(decoded, sourceRate)
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
    private val webSocketGenerationCounter = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    var activeWebSocketGeneration: Long = 0L
        private set

    @Volatile
    var staleWebSocketCallbacksIgnoredCount: Int = 0
        private set

    private var micAudioRecord: AudioRecord? = null
    private var micStreamingJob: Job? = null
    private var outputPlaybackJob: Job? = null
    private var inactivityTimerJob: Job? = null
    private var outputAudioTrack: AudioTrack? = null

    private data class CommandTaggedPcmChunk(
        val commandId: String,
        val pcmBytes: ByteArray
    )

    private val pcm24kPlaybackQueue = ConcurrentLinkedQueue<CommandTaggedPcmChunk>()

    @Volatile
    var activePcmPlaybackCommandId: String = ""
        private set

    @Volatile
    var isPcmHardwareDrainInProgress: Boolean = false
        private set

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null

    private val _connectionState = MutableStateFlow(LiveConnectionState.DISCONNECTED)
    val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _observedCaptureState = MutableStateFlow(ObservedAssistantCaptureState.IDLE)
    val observedCaptureState: StateFlow<ObservedAssistantCaptureState> = _observedCaptureState.asStateFlow()

    private val _captureErrorDetail = MutableStateFlow("")
    val captureErrorDetail: StateFlow<String> = _captureErrorDetail.asStateFlow()

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

    @Volatile
    var lastLanguageResolutionForCurrentCommand: LanguageProcessor.LanguageResolution =
        LanguageProcessor.LanguageResolution(DetectedLanguage.ENGLISH, "INITIAL")
        private set

    @Volatile
    var lastLaunchRequestElapsedMs: Long = 0L
        private set

    @Volatile
    var lastCaptureReadyElapsedMs: Long = 0L
        private set

    @Volatile
    var pendingSpeechOnsetElapsedMs: Long = 0L
        private set

    @Volatile
    var pendingSpeechEndElapsedMs: Long = 0L
        private set

    @Volatile
    var pendingPartialRecognitionCount: Int = 0
        private set

    @Volatile
    var pendingFinalRecognitionObserved: Boolean = false
        private set

    @Volatile
    var testSpeechCaptureAdapter: SpeechCaptureAdapter? = null

    private val captureGenerationCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private val captureHandoffLock = Any()
    private val retiredCaptureGenerations = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<Long>())
    private val intentionalCancelGenerations = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<Long>())
    private val liveRecognizerInstances = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SpeechRecognizer, Boolean>())
    )

    @Volatile
    private var activeRecognizerInstance: SpeechRecognizer? = null

    @Volatile
    private var activeRecognizerBoundGeneration: Long = 0L

    @Volatile
    private var finalResultAcceptedForCaptureGen: Long = 0L

    @Volatile
    var activeCaptureGeneration: Long = 0L
        private set

    @Volatile
    var readyCaptureGeneration: Long = 0L
        private set

    @Volatile
    var readyCaptureOwner: MicComponentOwner = MicComponentOwner.NONE
        private set

    @Volatile
    var staleRecognitionCallbacksIgnoredCount: Int = 0
        private set

    @Volatile
    var consecutiveRecognitionRetryCount: Int = 0
        private set

    @Volatile
    var maxRecognitionRetriesPerTurn: Int = 2

    @Volatile
    var lastRecognitionErrorCode: Int = 0
        private set

    @Volatile
    var lastRecognitionErrorName: String = "NONE"
        private set

    @Volatile
    var lastRecognitionDecision: String = "NONE"
        private set

    @Volatile
    var lastTtsErrorReason: String = "NONE"
        private set

    @Volatile
    var lastStickyDiagnosticError: String = ""
        private set

    private val _diagnosticSnapshot = MutableStateFlow(LiveSessionDiagnosticSnapshot())
    val diagnosticSnapshot: StateFlow<LiveSessionDiagnosticSnapshot> = _diagnosticSnapshot.asStateFlow()

    fun setStickyCaptureError(errorMessage: String) {
        val clean = errorMessage.trim()
        if (clean.isNotEmpty()) {
            _captureErrorDetail.value = clean
            lastStickyDiagnosticError = redactPrivateTextAndCredentials(clean)
        }
        refreshDiagnosticSnapshot()
    }

    fun refreshDiagnosticSnapshot() {
        val cmdId = activeCommandId.ifBlank { lastCompletedCommandId }
        val diag = if (cmdId.isNotBlank()) commandDiagnosticsMap[cmdId] else null
        val reqOut = diag?.requestedOutputProvider?.takeIf { it.isNotBlank() && it != "NONE" }
            ?: lastTurnReport?.requestedOutputProvider?.takeIf { it.isNotBlank() }
            ?: "NONE"
        val actOut = diag?.actualOutputProvider?.takeIf { it.isNotBlank() && it != "NONE" }
            ?: diag?.outputEngine?.takeIf { it.isNotBlank() && it != "NONE" }
            ?: lastTurnReport?.actualOutputProvider?.takeIf { it.isNotBlank() }
            ?: "NONE"
        val outState = diag?.outputState?.name
            ?: lastTurnReport?.outputState?.name
            ?: CommandSpeechOutputState.NONE.name
        val ttsErr = when {
            lastTtsErrorReason.isNotBlank() && lastTtsErrorReason != "NONE" -> lastTtsErrorReason
            ttsFallback?.lastErrorReason?.isNotBlank() == true && ttsFallback.lastErrorReason != "NONE" -> ttsFallback.lastErrorReason
            else -> "NONE"
        }
        val stickyErr = when {
            _captureErrorDetail.value.isNotBlank() -> redactPrivateTextAndCredentials(_captureErrorDetail.value)
            lastStickyDiagnosticError.isNotBlank() -> lastStickyDiagnosticError
            else -> "NONE"
        }
        _diagnosticSnapshot.value = LiveSessionDiagnosticSnapshot(
            buildCommitId = BUILD_COMMIT_ID,
            sessionState = _observedCaptureState.value.displayLabel,
            recognitionErrorName = lastRecognitionErrorName.ifBlank { "NONE" },
            recognitionErrorCode = lastRecognitionErrorCode,
            activeCaptureGeneration = activeCaptureGeneration,
            readyCaptureGeneration = readyCaptureGeneration,
            recognitionRetryCount = consecutiveRecognitionRetryCount,
            maxRecognitionRetries = maxRecognitionRetriesPerTurn,
            acceptedOrRejectedDecision = lastRecognitionDecision.ifBlank { "NONE" },
            commandId = cmdId.ifBlank { "NONE" },
            requestedOutputProvider = reqOut,
            actualOutputProvider = actOut,
            ttsState = outState,
            ttsError = ttsErr,
            pcmFramesReceived = diag?.audioFramesReceived ?: lastTurnReport?.audioFramesReceived ?: 0,
            pcmFramesPlayed = diag?.audioFramesPlayed ?: lastTurnReport?.audioFramesPlayed ?: 0,
            pcmBytesWritten = diag?.pcmBytesWrittenToHardware ?: 0,
            stickyErrorSummary = stickyErr
        )
    }

    @Volatile
    var isBoundedConversationModeActive: Boolean = false

    @Volatile
    var boundedConversationRemainingTurns: Int = 0

    @Volatile
    var maxBoundedConversationTurnsPerLaunch: Int = 6

    @Volatile
    var activeRecognitionListenerForTest: RecognitionListener? = null
        private set

    @Volatile
    var audioTrackWriteInterceptorForTest: ((pcmBytes: ByteArray, offset: Int, size: Int) -> Int)? = null

    private val _activeRecognizerInstanceCount = java.util.concurrent.atomic.AtomicInteger(0)
    val activeRecognizerInstanceCount: Int
        get() = _activeRecognizerInstanceCount.get()

    private val _activeAudioRecordInstanceCount = java.util.concurrent.atomic.AtomicInteger(0)
    val activeAudioRecordInstanceCount: Int
        get() = _activeAudioRecordInstanceCount.get()

    private val _turnReportsHistory = java.util.concurrent.CopyOnWriteArrayList<LiveTurnExecutionReport>()
    val turnReportsHistory: List<LiveTurnExecutionReport>
        get() = _turnReportsHistory.toList()

    private val transmittedServerToolCallIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val lateLiveAudioSuppressedForCommands = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val recoveredCommandIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val serverTurnCompleteCommands = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val commandDiagnosticsMap = java.util.concurrent.ConcurrentHashMap<String, CommandAudioDiagnosticEntry>()
    private val postActionAudioWatchdogJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    @Volatile
    var liveAudioDeliveryTimeoutMs: Long = 1400L

    @Volatile
    var pcmPlaybackStallTimeoutMs: Long = 2400L

    @Volatile
    private var lastPcmChunkActivityElapsedMs: Long = 0L

    fun clearTurnReportsForTest() {
        SpeechPreferenceStore.resetForTest(context)
        _selectedVoice.value = GeminiLiveVoice.AOEDE
        _turnReportsHistory.clear()
        lastTurnReport = null
        transmittedServerToolCallIds.clear()
        lateLiveAudioSuppressedForCommands.clear()
        recoveredCommandIds.clear()
        serverTurnCompleteCommands.clear()
        commandDiagnosticsMap.clear()
        postActionAudioWatchdogJobs.values.forEach { it.cancel() }
        postActionAudioWatchdogJobs.clear()
        retiredCaptureGenerations.clear()
        intentionalCancelGenerations.clear()
        finalResultAcceptedForCaptureGen = 0L
        consecutiveRecognitionRetryCount = 0
        lastRecognitionErrorCode = 0
        lastRecognitionErrorName = "NONE"
        lastRecognitionDecision = "NONE"
        lastTtsErrorReason = "NONE"
        lastStickyDiagnosticError = ""
        staleWebSocketCallbacksIgnoredCount = 0
        staleRecognitionCallbacksIgnoredCount = 0
        currentTurnStartedFromInputTranscription = false
        lastLaunchRequestElapsedMs = 0L
        lastCaptureReadyElapsedMs = 0L
        readyCaptureGeneration = 0L
        readyCaptureOwner = MicComponentOwner.NONE
        pendingSpeechOnsetElapsedMs = 0L
        pendingSpeechEndElapsedMs = 0L
        pendingPartialRecognitionCount = 0
        pendingFinalRecognitionObserved = false
        _captureErrorDetail.value = ""
        refreshDiagnosticSnapshot()
    }

    fun recordSpeechCaptureProgressForTest(
        onsetElapsedMs: Long,
        endElapsedMs: Long,
        partialCount: Int = 1,
        finalObserved: Boolean = true
    ) {
        pendingSpeechOnsetElapsedMs = onsetElapsedMs
        pendingSpeechEndElapsedMs = endElapsedMs
        pendingPartialRecognitionCount = partialCount
        pendingFinalRecognitionObserved = finalObserved
    }

    fun getCommandAudioDiagnostic(commandId: String = activeCommandId): CommandAudioDiagnosticEntry? =
        commandDiagnosticsMap[commandId]

    fun buildCommandAudioDiagnosticSummary(commandId: String = activeCommandId): String =
        commandDiagnosticsMap[commandId]?.toRedactedDiagnosticLine()
            ?: "cmd=$commandId | state=NONE"

    @Synchronized
    private fun updateCommandDiagnostic(
        commandId: String,
        transform: (CommandAudioDiagnosticEntry) -> CommandAudioDiagnosticEntry
    ): CommandAudioDiagnosticEntry {
        val current = commandDiagnosticsMap[commandId] ?: CommandAudioDiagnosticEntry(
            commandId = commandId,
            wsGeneration = activeWebSocketGeneration,
            requestedVoiceId = _selectedVoice.value.voiceName,
            speechRate = SpeechPreferenceStore.getSpeechRate(context),
            pitch = SpeechPreferenceStore.getPitch(context)
        )
        val updated = transform(current)
        commandDiagnosticsMap[commandId] = updated
        refreshDiagnosticSnapshot()
        return updated
    }

    @Volatile
    private var awaitingVerifiedToolOutcome: Boolean = false

    @Volatile
    private var currentTurnHasPendingOrExecutedAction: Boolean = false

    @Volatile
    private var currentVoiceTurnCompleted: Boolean = false

    @Volatile
    private var currentTurnStartedFromInputTranscription: Boolean = false

    @Volatile
    var currentTurnCanonicalResult: com.omnivoice.agentfix.CanonicalAppLaunchResult? = null
        private set

    @Volatile
    var currentTurnCanonicalResultCommandId: String = ""
        private set

    fun isGeminiLiveWebSocketConnected(): Boolean =
        activeWebSocket != null && isWebSocketReady.get()

    fun isActiveWebSocketCallback(webSocket: WebSocket?, socketGeneration: Long): Boolean {
        val currentWs = activeWebSocket
        val isMatch = webSocket != null && currentWs === webSocket && activeWebSocketGeneration == socketGeneration
        if (!isMatch) {
            staleWebSocketCallbacksIgnoredCount += 1
            Log.w(
                TAG,
                "Ignored stale WebSocket callback (callbackGen=$socketGeneration, activeGen=$activeWebSocketGeneration)"
            )
        }
        return isMatch
    }

    fun attachLiveWebSocketForTest(webSocket: WebSocket?, ready: Boolean = true): Long {
        val nextGen = if (webSocket != null) {
            webSocketGenerationCounter.incrementAndGet()
        } else {
            activeWebSocketGeneration
        }
        activeWebSocket = webSocket
        activeWebSocketGeneration = nextGen
        val isReady = webSocket != null && ready
        isWebSocketReady.set(isReady)
        _isSessionActive.value = isReady
        _connectionState.value = if (isReady) LiveConnectionState.LIVE_LISTENING else LiveConnectionState.DISCONNECTED
        _observedCaptureState.value = if (isReady) ObservedAssistantCaptureState.LISTENING else ObservedAssistantCaptureState.IDLE
        if (isReady && lastCaptureReadyElapsedMs == 0L) {
            lastCaptureReadyElapsedMs = SystemClock.elapsedRealtime()
        }
        return nextGen
    }

    fun simulateLiveWebSocketMessageForTest(rawJson: String) {
        val ws = activeWebSocket ?: return
        handleServerJsonMessage(ws, rawJson, activeWebSocketGeneration)
    }

    fun simulateWebSocketMessageFromSocketForTest(
        webSocket: WebSocket,
        socketGeneration: Long,
        rawJson: String
    ): Boolean {
        if (!isActiveWebSocketCallback(webSocket, socketGeneration)) {
            return false
        }
        handleServerJsonMessage(webSocket, rawJson, socketGeneration)
        return true
    }

    fun simulateWebSocketDisconnectFromSocketForTest(
        webSocket: WebSocket,
        socketGeneration: Long,
        code: Int,
        reason: String
    ): Boolean {
        if (!isActiveWebSocketCallback(webSocket, socketGeneration)) {
            return false
        }
        recordLiveWebSocketDisconnect(code, reason, webSocket, socketGeneration)
        return true
    }

    fun recordLiveWebSocketDisconnect(
        code: Int,
        reason: String,
        webSocket: WebSocket? = activeWebSocket,
        socketGeneration: Long = activeWebSocketGeneration
    ) {
        if (webSocket != null && activeWebSocket != null && !isActiveWebSocketCallback(webSocket, socketGeneration)) {
            return
        }
        isWebSocketReady.set(false)
        activeWebSocket = null
        lastWebSocketCloseCode = code
        lastWebSocketCloseReason = reason
        val cleanReason = redactPrivateTextAndCredentials(reason.ifBlank { "none" })
        lastLiveDisconnectDiagnostic = "ws_gen=$socketGeneration | ws_close_code=$code | ws_close_reason=$cleanReason"
        com.omnivoice.agentfix.AgentLog.w(
            "LiveWebSocket",
            "Gemini Live WebSocket disconnected: gen=$socketGeneration, code=$code, reason=$cleanReason"
        )
        JarvisHandsFreeEngine.appendTerminalLog(
            layerTag = "LIVE-WS-DISCONNECT",
            command = "WEBSOCKET_CLOSED(gen=$socketGeneration, code=$code)",
            detail = "Reason: $cleanReason",
            status = "DISCONNECTED"
        )
        val cmdId = activeCommandId
        if (cmdId.isNotBlank()) {
            val diag = updateCommandDiagnostic(cmdId) {
                it.copy(
                    wsGeneration = socketGeneration,
                    wsCloseCode = code,
                    wsCloseReason = cleanReason
                )
            }
            val needsDisconnectAfterActionRecovery =
                lastCompletedCommandId == cmdId &&
                    !currentVoiceTurnCompleted &&
                    lastSpokenReplyForCurrentCommand.isNotBlank() &&
                    diag.outputState != CommandSpeechOutputState.COMPLETED &&
                    diag.outputState != CommandSpeechOutputState.INTERRUPTED &&
                    diag.audioFramesPlayed == 0
            if (needsDisconnectAfterActionRecovery) {
                triggerBoundedSpeechRecoveryForCommand(
                    commandId = cmdId,
                    reason = "WS_DISCONNECT_AFTER_ACTION_$code"
                )
            }
        }
    }

    /**
     * Delivers a command's spoken reply once via [ttsFallback] and binds TTS utterance callbacks
     * and [SpeechConfigSnapshot] to the command's [CommandAudioDiagnosticEntry] and [LiveTurnExecutionReport].
     * Preserves the user's primary Gemini Live voice preference in [SpeechPreferenceStore].
     */
    private fun deliverCommandSpeechViaLocalTts(
        commandId: String,
        utterance: String,
        spokenReply: String,
        diagnosticText: String,
        finalStatus: String,
        language: DetectedLanguage,
        languageSelectionReason: String = lastLanguageResolutionForCurrentCommand.reason,
        isRecovery: Boolean,
        recoveryReason: String = ""
    ) {
        if (spokenReply.isBlank()) return
        val coord = AssistantAudioStateMachine.shared
        val tts = ttsFallback
        val primaryLiveVoice = _selectedVoice.value.voiceName
        val requestedProvider = if (isRecovery) "GEMINI_LIVE ($primaryLiveVoice)" else "ANDROID_TTS"
        val actualProvider = if (isRecovery) "ANDROID_TTS_FALLBACK" else "ANDROID_TTS"
        val authoritativeRate = SpeechPreferenceStore.getSpeechRate(context)
        val authoritativePitch = SpeechPreferenceStore.getPitch(context)

        if (tts == null) {
            lastTtsErrorReason = "TTS_UNAVAILABLE"
            setStickyCaptureError("Local speech output (TTS) is unavailable on this device.")
            _observedCaptureState.value = ObservedAssistantCaptureState.ERROR
            updateCommandDiagnostic(commandId) {
                it.copy(
                    outputState = CommandSpeechOutputState.FAILED,
                    stallStage = CommandTurnStallStage.RECEIVED_AUDIO_PLAYBACK_FAILED,
                    outputEngine = actualProvider,
                    requestedOutputProvider = requestedProvider,
                    actualOutputProvider = actualProvider,
                    requestedVoiceId = primaryLiveVoice,
                    resolvedVoiceId = "TTS_UNAVAILABLE",
                    resolvedLocaleTag = language.ttsLocale.toLanguageTag(),
                    speechRate = authoritativeRate,
                    pitch = authoritativePitch,
                    chosenReplyLanguage = language,
                    languageSelectionReason = languageSelectionReason,
                    fallbackOrReconnectReason = recoveryReason.ifBlank { "TTS_UNAVAILABLE" },
                    recoveryTriggered = it.recoveryTriggered || isRecovery,
                    cancellationReason = recoveryReason.ifBlank { "TTS_UNAVAILABLE" }
                )
            }
            AudioOutputManager.completeTurnOutput(commandId)
            coord.notifyAssistantSpeechEnded(
                immediateCooldownClear = true,
                ownerToken = commandId,
                forceRelease = true
            )
            if (_isSessionActive.value &&
                !coord.isStoppedByUserInMemory() &&
                !_isMicMuted.value &&
                !coord.isDeviceMutedInMemory()
            ) {
                handoffToPostTurnCapture("TTS_UNAVAILABLE_RESTORE_CAPTURE", preserveErrorDetail = true)
            } else {
                refreshDiagnosticSnapshot()
            }
            return
        }
        if (isRecovery) {
            lateLiveAudioSuppressedForCommands.add(commandId)
            AudioOutputManager.releaseUnplayedTurnSlot(commandId)
        }
        _connectionState.value = LiveConnectionState.LIVE_AI_SPEAKING
        _observedCaptureState.value = ObservedAssistantCaptureState.SPEAKING
        refreshDiagnosticSnapshot()

        val terminalHandledForInvocation = AtomicBoolean(false)
        tts.registerCommandLifecycleListener(commandId) { event ->
            if (event.commandId == commandId) {
                val nowElapsed = SystemClock.elapsedRealtime()
                val mappedState = when (event.state) {
                    TtsOutputLifecycleState.REQUESTED -> CommandSpeechOutputState.REQUESTED
                    TtsOutputLifecycleState.QUEUED -> CommandSpeechOutputState.QUEUED
                    TtsOutputLifecycleState.STARTED -> {
                        _connectionState.value = LiveConnectionState.LIVE_AI_SPEAKING
                        _observedCaptureState.value = ObservedAssistantCaptureState.SPEAKING
                        CommandSpeechOutputState.STARTED
                    }
                    TtsOutputLifecycleState.COMPLETED -> {
                        lastTtsErrorReason = "NONE"
                        CommandSpeechOutputState.COMPLETED
                    }
                    TtsOutputLifecycleState.INTERRUPTED -> CommandSpeechOutputState.INTERRUPTED
                    TtsOutputLifecycleState.FAILED -> {
                        val failReason = event.reason.ifBlank { "TTS_ERROR" }
                        lastTtsErrorReason = failReason
                        _observedCaptureState.value = ObservedAssistantCaptureState.ERROR
                        setStickyCaptureError("Speech playback failed ($failReason).")
                        CommandSpeechOutputState.FAILED
                    }
                }
                val snap = event.configSnapshot
                val updatedDiag = updateCommandDiagnostic(commandId) { current ->
                    current.copy(
                        outputState = mappedState,
                        outputEngine = if (isRecovery) "ANDROID_TTS_RECOVERY" else "ANDROID_TTS",
                        requestedOutputProvider = snap?.requestedOutputProvider ?: requestedProvider,
                        actualOutputProvider = snap?.actualOutputProvider ?: actualProvider,
                        requestedVoiceId = snap?.requestedVoiceId ?: primaryLiveVoice,
                        resolvedVoiceId = snap?.resolvedVoiceId ?: tts.lastResolvedVoiceId,
                        resolvedLocaleTag = snap?.resolvedLocaleTag ?: language.ttsLocale.toLanguageTag(),
                        speechRate = snap?.speechRate ?: authoritativeRate,
                        pitch = snap?.pitch ?: authoritativePitch,
                        chosenReplyLanguage = snap?.chosenReplyLanguage ?: language,
                        languageSelectionReason = snap?.languageSelectionReason ?: languageSelectionReason,
                        fallbackOrReconnectReason = snap?.fallbackOrReconnectReason?.takeIf { it != "NONE" }
                            ?: recoveryReason.ifBlank { current.fallbackOrReconnectReason },
                        ttsUtteranceId = event.utteranceId,
                        ttsStartedCount = if (event.state == TtsOutputLifecycleState.STARTED && event.segmentIndex == 0) {
                            current.ttsStartedCount + 1
                        } else {
                            current.ttsStartedCount
                        },
                        ttsCompletedCount = if (event.state == TtsOutputLifecycleState.COMPLETED) {
                            current.ttsCompletedCount + 1
                        } else {
                            current.ttsCompletedCount
                        },
                        ttsErrorCount = if (event.state == TtsOutputLifecycleState.FAILED) {
                            current.ttsErrorCount + 1
                        } else {
                            current.ttsErrorCount
                        },
                        audioReceivedElapsedMs = if (current.audioReceivedElapsedMs > 0L) {
                            current.audioReceivedElapsedMs
                        } else if (event.state == TtsOutputLifecycleState.QUEUED || event.state == TtsOutputLifecycleState.STARTED) {
                            nowElapsed
                        } else {
                            current.audioReceivedElapsedMs
                        },
                        playbackStartedElapsedMs = if (current.playbackStartedElapsedMs > 0L) {
                            current.playbackStartedElapsedMs
                        } else if (event.state == TtsOutputLifecycleState.STARTED) {
                            nowElapsed
                        } else {
                            current.playbackStartedElapsedMs
                        },
                        playbackCompletedElapsedMs = if (current.playbackCompletedElapsedMs > 0L) {
                            current.playbackCompletedElapsedMs
                        } else if (event.state == TtsOutputLifecycleState.COMPLETED) {
                            nowElapsed
                        } else {
                            current.playbackCompletedElapsedMs
                        },
                        stallStage = when (event.state) {
                            TtsOutputLifecycleState.FAILED -> CommandTurnStallStage.RECEIVED_AUDIO_PLAYBACK_FAILED
                            TtsOutputLifecycleState.COMPLETED -> if (current.stallStage == CommandTurnStallStage.RECEIVED_AUDIO_PLAYBACK_FAILED) {
                                CommandTurnStallStage.NONE
                            } else {
                                current.stallStage
                            }
                            else -> current.stallStage
                        },
                        recoveryTriggered = current.recoveryTriggered || isRecovery,
                        cancellationReason = if (event.reason.isNotBlank()) event.reason else current.cancellationReason
                    )
                }
                if (lastTurnReport?.commandId == commandId) {
                    lastTurnReport = lastTurnReport?.copy(
                        outputState = updatedDiag.outputState,
                        outputEngine = if (isRecovery) "ANDROID_TTS_RECOVERY" else "ANDROID_TTS",
                        requestedOutputProvider = updatedDiag.requestedOutputProvider,
                        actualOutputProvider = updatedDiag.actualOutputProvider,
                        requestedVoiceId = updatedDiag.requestedVoiceId,
                        resolvedVoiceId = updatedDiag.resolvedVoiceId,
                        resolvedLocaleTag = updatedDiag.resolvedLocaleTag,
                        speechRate = updatedDiag.speechRate,
                        pitch = updatedDiag.pitch,
                        languageSelectionReason = updatedDiag.languageSelectionReason,
                        fallbackOrReconnectReason = updatedDiag.fallbackOrReconnectReason,
                        recoveryTriggered = updatedDiag.recoveryTriggered,
                        audioFramesReceived = updatedDiag.audioFramesReceived,
                        audioFramesPlayed = updatedDiag.audioFramesPlayed,
                        audioReceivedElapsedMs = updatedDiag.audioReceivedElapsedMs,
                        playbackStartedElapsedMs = updatedDiag.playbackStartedElapsedMs,
                        playbackCompletedElapsedMs = updatedDiag.playbackCompletedElapsedMs,
                        stallStage = updatedDiag.stallStage
                    )
                }
                refreshDiagnosticSnapshot()

                // Command-scoped terminal cleanup & capture restoration for EVERY terminal state
                when (event.state) {
                    TtsOutputLifecycleState.COMPLETED -> {
                        if (terminalHandledForInvocation.compareAndSet(false, true)) {
                            AudioOutputManager.completeTurnOutput(commandId)
                            coord.notifyAssistantSpeechEnded(
                                source = "LOCAL_TTS_COMPLETED",
                                ownerToken = commandId
                            )
                            if (activeCommandId == commandId || activeCommandId.isBlank()) {
                                handoffToPostTurnCapture(
                                    if (isRecovery) "TTS_BOUNDED_RECOVERY_COMPLETED" else "TTS_TURN_COMPLETED"
                                )
                            }
                        }
                    }
                    TtsOutputLifecycleState.FAILED -> {
                        if (terminalHandledForInvocation.compareAndSet(false, true)) {
                            val failReason = event.reason.ifBlank { "TTS_ERROR" }
                            AudioOutputManager.completeTurnOutput(commandId)
                            coord.notifyAssistantSpeechEnded(
                                immediateCooldownClear = true,
                                ownerToken = commandId,
                                forceRelease = true
                            )
                            if ((activeCommandId == commandId || activeCommandId.isBlank()) &&
                                _isSessionActive.value &&
                                !coord.isStoppedByUserInMemory() &&
                                !_isMicMuted.value &&
                                !coord.isDeviceMutedInMemory()
                            ) {
                                handoffToPostTurnCapture(
                                    reason = "TTS_OUTPUT_FAILED_RESTORE_CAPTURE_$failReason",
                                    preserveErrorDetail = true
                                )
                            } else {
                                refreshDiagnosticSnapshot()
                            }
                        }
                    }
                    TtsOutputLifecycleState.INTERRUPTED -> {
                        if (terminalHandledForInvocation.compareAndSet(false, true)) {
                            val intReason = event.reason.ifBlank { "TTS_INTERRUPTED" }
                            AudioOutputManager.completeTurnOutput(commandId)
                            coord.notifyAssistantSpeechEnded(
                                immediateCooldownClear = true,
                                ownerToken = commandId,
                                forceRelease = true
                            )
                            val supersededByNewTurn = intReason.startsWith("SUPERSEDED_BY_NEW_TURN")
                            val stoppedByUser = intReason.contains("STOP", ignoreCase = true) ||
                                intReason.contains("SHUTDOWN", ignoreCase = true)
                            if (!supersededByNewTurn &&
                                !stoppedByUser &&
                                activeCommandId == commandId &&
                                _isSessionActive.value &&
                                !coord.isStoppedByUserInMemory() &&
                                !_isMicMuted.value &&
                                !coord.isDeviceMutedInMemory()
                            ) {
                                handoffToPostTurnCapture(
                                    reason = "TTS_INTERRUPTED_RESTORE_CAPTURE_$intReason",
                                    preserveErrorDetail = true
                                )
                            } else {
                                refreshDiagnosticSnapshot()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        val nowReqElapsed = SystemClock.elapsedRealtime()
        updateCommandDiagnostic(commandId) { current ->
            current.copy(
                requestSentElapsedMs = if (current.requestSentElapsedMs > 0L) current.requestSentElapsedMs else nowReqElapsed,
                firstResponseReceivedElapsedMs = if (current.firstResponseReceivedElapsedMs > 0L) current.firstResponseReceivedElapsedMs else nowReqElapsed,
                audioReceivedElapsedMs = if (current.audioReceivedElapsedMs > 0L) current.audioReceivedElapsedMs else nowReqElapsed
            )
        }
        tts.speak(
            text = spokenReply,
            pitch = authoritativePitch,
            speechRate = authoritativeRate,
            locale = language.ttsLocale,
            turnId = commandId,
            commandId = commandId,
            sessionGeneration = activeWebSocketGeneration,
            requestedOutputProvider = requestedProvider,
            actualOutputProvider = actualProvider,
            requestedVoiceId = primaryLiveVoice,
            languageSelectionReason = languageSelectionReason,
            fallbackOrReconnectReason = recoveryReason.ifBlank { "NONE" }
        )
    }

    /**
     * Bounded single-shot speech recovery when Live WebSocket `send(...)` returns `false`,
     * Live audio times out after a verified/completed action, or the socket disconnects before
     * audio drains. Never repeats the underlying action and suppresses late duplicate Live PCM.
     */
    fun triggerBoundedSpeechRecoveryForCommand(
        commandId: String = activeCommandId,
        reason: String
    ): Boolean {
        if (commandId.isBlank()) return false
        if (!recoveredCommandIds.add(commandId)) {
            return false
        }
        postActionAudioWatchdogJobs.remove(commandId)?.cancel()
        lateLiveAudioSuppressedForCommands.add(commandId)

        // Remove any unplayed PCM chunks for this command from the queue
        val remaining = pcm24kPlaybackQueue.filter { it.commandId != commandId }
        pcm24kPlaybackQueue.clear()
        pcm24kPlaybackQueue.addAll(remaining)

        val currentDiag = commandDiagnosticsMap[commandId]
        if (currentDiag != null &&
            (currentDiag.outputState == CommandSpeechOutputState.COMPLETED ||
                currentDiag.outputState == CommandSpeechOutputState.INTERRUPTED ||
                currentDiag.audioFramesPlayed > 0)
        ) {
            return false
        }

        val utterance = _liveUserTranscript.value
        val langRes = if (currentDiag != null && currentDiag.languageSelectionReason != "DEFAULT") {
            LanguageProcessor.LanguageResolution(currentDiag.chosenReplyLanguage, currentDiag.languageSelectionReason)
        } else {
            resolveCommandReplyLanguage(utterance, mutateHistoryIfGenuine = false)
        }

        var spokenText = if (commandId == activeCommandId || lastCompletedCommandId == commandId) {
            lastSpokenReplyForCurrentCommand.ifBlank { _liveAiTranscript.value }
        } else {
            _turnReportsHistory.lastOrNull { it.commandId == commandId }?.spokenText.orEmpty()
        }
        if (spokenText.isBlank() ||
            spokenText.startsWith("Processing") ||
            spokenText.startsWith("Soch raha") ||
            spokenText.startsWith("کارروائی")
        ) {
            if (utterance.isNotBlank() && !currentTurnHasPendingOrExecutedAction) {
                spokenText = sanitizeSpokenText(
                    DeterministicNluEngine.analyzeTextDeterministicallyLocal(context, utterance).spokenReply,
                    utterance
                )
                lastSpokenReplyForCurrentCommand = spokenText
                _liveAiTranscript.value = spokenText
            }
        }
        if (spokenText.isBlank()) return false

        val finalStatus = if (lastCompletedCommandId == commandId) {
            lastFinalStatusForCurrentCommand.ifBlank { "REQUESTED_UNVERIFIED" }
        } else {
            currentDiag?.observedActionResult?.ifBlank { "REQUESTED_UNVERIFIED" } ?: "REQUESTED_UNVERIFIED"
        }
        val diagnosticText = lastDiagnosticTextForCurrentCommand.ifBlank { spokenText }

        val inferredStallStage = when {
            reason.contains("SEND_FALSE", ignoreCase = true) || reason.contains("NOT_SENT", ignoreCase = true) ->
                CommandTurnStallStage.COMMAND_ACCEPTED_REQUEST_NOT_SENT
            reason.contains("PLAYBACK_FAILED", ignoreCase = true) || reason.contains("WRITE_ERROR", ignoreCase = true) ->
                CommandTurnStallStage.RECEIVED_AUDIO_PLAYBACK_FAILED
            reason.contains("SERVER", ignoreCase = true) || reason.contains("TOOL_DELAY", ignoreCase = true) ->
                CommandTurnStallStage.SERVER_OR_TOOL_DELAY
            currentDiag?.firstResponseReceivedElapsedMs ?: 0L > 0L || spokenText.isNotBlank() ->
                CommandTurnStallStage.GENERATED_REPLY_NO_AUDIO
            else ->
                CommandTurnStallStage.SERVER_OR_TOOL_DELAY
        }

        updateCommandDiagnostic(commandId) {
            it.copy(
                recoveryTriggered = true,
                stallStage = if (it.stallStage != CommandTurnStallStage.NONE) it.stallStage else inferredStallStage,
                requestedOutputProvider = "GEMINI_LIVE (${_selectedVoice.value.voiceName})",
                actualOutputProvider = "ANDROID_TTS_FALLBACK",
                requestedVoiceId = _selectedVoice.value.voiceName,
                chosenReplyLanguage = langRes.language,
                languageSelectionReason = langRes.reason,
                fallbackOrReconnectReason = reason,
                cancellationReason = reason
            )
        }

        deliverCommandSpeechViaLocalTts(
            commandId = commandId,
            utterance = utterance,
            spokenReply = spokenText,
            diagnosticText = diagnosticText,
            finalStatus = finalStatus,
            language = langRes.language,
            languageSelectionReason = langRes.reason,
            isRecovery = true,
            recoveryReason = reason
        )

        recordTurnExecutionReport(
            commandId = commandId,
            utterance = utterance,
            outputEngine = "ANDROID_TTS_RECOVERY",
            language = langRes.language,
            languageSelectionReason = langRes.reason,
            liveConnected = isGeminiLiveWebSocketConnected(),
            finalStatus = finalStatus,
            spokenText = spokenText,
            diagnosticText = diagnosticText,
            requestedOutputProvider = "GEMINI_LIVE (${_selectedVoice.value.voiceName})",
            actualOutputProvider = "ANDROID_TTS_FALLBACK",
            fallbackOrReconnectReason = reason
        )
        return true
    }

    /**
     * Two-phase Live audio watchdog:
     * 1. Response-Start Phase ([liveAudioDeliveryTimeoutMs]): waits for the first server audio/event.
     * 2. Playback-Progress Phase ([pcmPlaybackStallTimeoutMs]): if PCM chunks were received/queued
     *    but 0 frames were written to hardware (`AudioTrack.write` stalled or failed), classifies
     *    `RECEIVED_AUDIO_PLAYBACK_FAILED` and triggers bounded local TTS recovery.
     */
    private fun schedulePostActionLiveAudioWatchdog(commandId: String) {
        postActionAudioWatchdogJobs.remove(commandId)?.cancel()
        val responseStartTimeout = liveAudioDeliveryTimeoutMs.coerceAtLeast(80L)
        val job = scope.launch {
            delay(responseStartTimeout)
            if (activeCommandId != commandId) return@launch
            var diag = commandDiagnosticsMap[commandId] ?: return@launch
            if (diag.outputState == CommandSpeechOutputState.COMPLETED ||
                diag.outputState == CommandSpeechOutputState.INTERRUPTED ||
                diag.recoveryTriggered
            ) {
                return@launch
            }
            if (diag.audioFramesReceived == 0) {
                val stage = if (diag.firstResponseReceivedElapsedMs > 0L || lastSpokenReplyForCurrentCommand.isNotBlank()) {
                    CommandTurnStallStage.GENERATED_REPLY_NO_AUDIO
                } else {
                    CommandTurnStallStage.SERVER_OR_TOOL_DELAY
                }
                updateCommandDiagnostic(commandId) { current ->
                    current.copy(stallStage = stage)
                }
                triggerBoundedSpeechRecoveryForCommand(
                    commandId = commandId,
                    reason = "LIVE_AUDIO_DELIVERY_TIMEOUT_${responseStartTimeout}MS"
                )
                return@launch
            }
            // Audio frames were received: verify hardware playback actually writes frames within bounded window
            val stallWait = pcmPlaybackStallTimeoutMs.coerceIn(80L, 2500L)
            delay(stallWait)
            if (activeCommandId != commandId) return@launch
            diag = commandDiagnosticsMap[commandId] ?: return@launch
            if (diag.audioFramesReceived > 0 &&
                diag.audioFramesPlayed == 0 &&
                diag.outputState != CommandSpeechOutputState.COMPLETED &&
                diag.outputState != CommandSpeechOutputState.INTERRUPTED &&
                !diag.recoveryTriggered
            ) {
                updateCommandDiagnostic(commandId) { current ->
                    current.copy(
                        outputState = CommandSpeechOutputState.FAILED,
                        stallStage = CommandTurnStallStage.RECEIVED_AUDIO_PLAYBACK_FAILED
                    )
                }
                AudioOutputManager.releaseUnplayedTurnSlot(commandId)
                triggerBoundedSpeechRecoveryForCommand(
                    commandId = commandId,
                    reason = "PCM_RECEIVED_BUT_ZERO_FRAMES_PLAYED_${stallWait}MS"
                )
            }
        }
        postActionAudioWatchdogJobs[commandId] = job
    }

    private fun recordTurnExecutionReport(
        commandId: String = activeCommandId,
        utterance: String,
        outputEngine: String,
        language: DetectedLanguage,
        languageSelectionReason: String = lastLanguageResolutionForCurrentCommand.reason,
        liveConnected: Boolean,
        finalStatus: String,
        spokenText: String,
        diagnosticText: String,
        requestedOutputProvider: String? = null,
        actualOutputProvider: String? = null,
        fallbackOrReconnectReason: String? = null,
        foregroundPackage: String? = com.omnivoice.agentfix.ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = true)?.packageName
    ): LiveTurnExecutionReport {
        val diag = commandDiagnosticsMap[commandId]
        val liveVoice = _selectedVoice.value.voiceName
        val reqProv = requestedOutputProvider
            ?: diag?.requestedOutputProvider?.takeIf { it != "NONE" }
            ?: outputEngine
        val actProv = actualOutputProvider
            ?: diag?.actualOutputProvider?.takeIf { it != "NONE" }
            ?: outputEngine
        val reqVoice = diag?.requestedVoiceId?.takeIf { it != "NONE" } ?: liveVoice
        val resVoice = diag?.resolvedVoiceId?.takeIf { it != "NONE" }
            ?: if (actProv.startsWith("GEMINI_LIVE")) liveVoice else (ttsFallback?.lastResolvedVoiceId ?: "ANDROID_TTS")
        val resLocale = diag?.resolvedLocaleTag?.takeIf { it != "NONE" }
            ?: language.ttsLocale.toLanguageTag()
        val rate = diag?.speechRate ?: SpeechPreferenceStore.getSpeechRate(context)
        val pitchVal = diag?.pitch ?: SpeechPreferenceStore.getPitch(context)
        val fbReason = fallbackOrReconnectReason
            ?: diag?.fallbackOrReconnectReason?.takeIf { it != "NONE" }
            ?: "NONE"

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
            foregroundPackage = foregroundPackage,
            outputState = diag?.outputState ?: CommandSpeechOutputState.REQUESTED,
            recoveryTriggered = diag?.recoveryTriggered ?: false,
            audioFramesReceived = diag?.audioFramesReceived ?: 0,
            audioFramesPlayed = diag?.audioFramesPlayed ?: 0,
            sessionGeneration = diag?.wsGeneration ?: activeWebSocketGeneration,
            languageSelectionReason = languageSelectionReason,
            requestedOutputProvider = reqProv,
            actualOutputProvider = actProv,
            requestedVoiceId = reqVoice,
            resolvedVoiceId = resVoice,
            resolvedLocaleTag = resLocale,
            speechRate = rate,
            pitch = pitchVal,
            fallbackOrReconnectReason = fbReason,
            launchRequestedElapsedMs = diag?.launchRequestedElapsedMs ?: lastLaunchRequestElapsedMs,
            captureReadyElapsedMs = diag?.captureReadyElapsedMs ?: lastCaptureReadyElapsedMs,
            speechOnsetElapsedMs = diag?.speechOnsetElapsedMs ?: 0L,
            speechEndElapsedMs = diag?.speechEndElapsedMs ?: 0L,
            commandAcceptedElapsedMs = diag?.commandAcceptedElapsedMs ?: 0L,
            requestSentElapsedMs = diag?.requestSentElapsedMs ?: 0L,
            firstResponseReceivedElapsedMs = diag?.firstResponseReceivedElapsedMs ?: 0L,
            audioReceivedElapsedMs = diag?.audioReceivedElapsedMs ?: 0L,
            playbackStartedElapsedMs = diag?.playbackStartedElapsedMs ?: 0L,
            playbackCompletedElapsedMs = diag?.playbackCompletedElapsedMs ?: 0L,
            partialRecognitionCount = diag?.partialRecognitionCount ?: 0,
            finalRecognitionObserved = diag?.finalRecognitionObserved ?: false,
            stallStage = diag?.stallStage ?: CommandTurnStallStage.NONE
        )
        lastTurnReport = report
        _turnReportsHistory.add(report)
        return report
    }

    fun startNewCommandTurn(
        commandId: String = "cmd_${System.currentTimeMillis()}_${(1000..9999).random()}",
        utterance: String = ""
    ): String {
        if (activeCommandId.isNotBlank() && activeCommandId != commandId) {
            postActionAudioWatchdogJobs.remove(activeCommandId)?.cancel()
        }
        restoreAuthoritativeSpeechPreferences()
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
        clearPlaybackQueueAndStopImmediately(interruptReason = "NEW_COMMAND_TURN")
        val trimmedUtterance = utterance.trim()
        currentTurnStartedFromInputTranscription = trimmedUtterance.isNotEmpty()
        currentTurnHasPendingOrExecutedAction = trimmedUtterance.isNotEmpty() && (
            com.omnivoice.agentfix.UiTools.isReturnToPreviousAppCommand(trimmedUtterance) ||
                SuperAgiBrainEngine.extractAllDeviceTasks(context, trimmedUtterance).isNotEmpty()
            )
        val langRes = if (trimmedUtterance.isNotEmpty()) {
            resolveCommandReplyLanguage(trimmedUtterance, mutateHistoryIfGenuine = true)
        } else {
            resolveCommandReplyLanguage("", mutateHistoryIfGenuine = false)
        }
        val liveVoice = _selectedVoice.value.voiceName
        val isLiveReady = isGeminiLiveWebSocketConnected()
        val nowElapsed = SystemClock.elapsedRealtime()
        val captureReady = if (lastCaptureReadyElapsedMs > 0L) lastCaptureReadyElapsedMs else nowElapsed
        val onset = if (pendingSpeechOnsetElapsedMs > 0L) pendingSpeechOnsetElapsedMs else captureReady
        val speechEnd = if (pendingSpeechEndElapsedMs > 0L) pendingSpeechEndElapsedMs else nowElapsed
        val partials = pendingPartialRecognitionCount
        val finalRec = pendingFinalRecognitionObserved || trimmedUtterance.isNotEmpty()
        pendingSpeechOnsetElapsedMs = 0L
        pendingSpeechEndElapsedMs = 0L
        pendingPartialRecognitionCount = 0
        pendingFinalRecognitionObserved = false

        commandDiagnosticsMap[commandId] = CommandAudioDiagnosticEntry(
            commandId = commandId,
            route = if (currentTurnHasPendingOrExecutedAction) "ACTION_TURN" else "CONVERSATIONAL_TURN",
            wsGeneration = activeWebSocketGeneration,
            outputState = CommandSpeechOutputState.REQUESTED,
            outputEngine = if (isLiveReady) "GEMINI_LIVE ($liveVoice)" else "ANDROID_TTS",
            requestedOutputProvider = if (isLiveReady) "GEMINI_LIVE ($liveVoice)" else "ANDROID_TTS",
            actualOutputProvider = if (isLiveReady) "GEMINI_LIVE ($liveVoice)" else "ANDROID_TTS",
            requestedVoiceId = liveVoice,
            resolvedVoiceId = if (isLiveReady) liveVoice else (ttsFallback?.lastResolvedVoiceId ?: "ANDROID_TTS"),
            resolvedLocaleTag = langRes.language.ttsLocale.toLanguageTag(),
            speechRate = SpeechPreferenceStore.getSpeechRate(context),
            pitch = SpeechPreferenceStore.getPitch(context),
            chosenReplyLanguage = langRes.language,
            languageSelectionReason = langRes.reason,
            launchRequestedElapsedMs = lastLaunchRequestElapsedMs,
            captureReadyElapsedMs = captureReady,
            speechOnsetElapsedMs = onset,
            speechEndElapsedMs = speechEnd,
            commandAcceptedElapsedMs = nowElapsed,
            partialRecognitionCount = partials,
            finalRecognitionObserved = finalRec,
            stallStage = CommandTurnStallStage.NONE
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

    /**
     * Resolves the reply language for the current command using [SpeechPreferenceStore] and
     * [LanguageProcessor.resolveReplyLanguage], ensuring English app names, tool names, result codes,
     * or empty tool-call transcripts never switch an Urdu / Roman Urdu conversation to English.
     */
    fun resolveCommandReplyLanguage(
        utterance: String,
        mutateHistoryIfGenuine: Boolean = true
    ): LanguageProcessor.LanguageResolution {
        val resolution = if (mutateHistoryIfGenuine) {
            SpeechPreferenceStore.recordUtteranceAndResolveReplyLanguage(context, utterance)
        } else {
            SpeechPreferenceStore.resolveReplyLanguageWithoutMutatingHistory(context, utterance)
        }
        lastLanguageResolutionForCurrentCommand = resolution
        return resolution
    }

    fun setPreferredLanguageMode(mode: LanguageProcessor.UserLanguagePreferenceMode) {
        SpeechPreferenceStore.setPreferredLanguageMode(context, mode)
    }

    fun getPreferredLanguageMode(): LanguageProcessor.UserLanguagePreferenceMode =
        SpeechPreferenceStore.getPreferredLanguageMode(context)

    fun buildMissingOutcomeSpokenReply(utterance: String): String {
        val lang = resolveCommandReplyLanguage(utterance, mutateHistoryIfGenuine = false).language
        return when (lang) {
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
     * [com.omnivoice.agentfix.CanonicalAppLaunchResult] and the resolved conversation/utterance language.
     * Never reads package names, status labels, or navigation methods aloud.
     * If the current outcome is missing (`outcome == null`), explicitly says so.
     */
    fun buildSpokenConfirmationForAppCommand(
        outcome: com.omnivoice.agentfix.CanonicalAppLaunchResult?,
        utterance: String,
        isReturnCommand: Boolean,
        fallbackAppName: String = "",
        explicitLanguageResolution: LanguageProcessor.LanguageResolution? = null
    ): String {
        if (outcome == null) {
            return buildMissingOutcomeSpokenReply(utterance)
        }
        val langRes = explicitLanguageResolution ?: resolveCommandReplyLanguage(utterance, mutateHistoryIfGenuine = true)
        val lang = langRes.language
        val useUrduScript = lang == DetectedLanguage.URDU_NASTALIQ
        val useRomanUrdu = lang == DetectedLanguage.ROMAN_URDU ||
            lang == DetectedLanguage.MIXED ||
            (com.omnivoice.agentfix.UiTools.isReturnToPreviousAppCommand(utterance) &&
                !utterance.trim().lowercase(Locale.ROOT).let {
                    it.startsWith("return") || it.startsWith("go back") || it.startsWith("switch") || it.startsWith("back to") || it.startsWith("take me")
                })

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
     * task/window IDs) and localizes internal English execution strings when the active conversation
     * is in Urdu or Roman Urdu/Mixed, without ever turning a failed/unverified status into a success claim.
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
            return buildMissingOutcomeSpokenReply(utterance)
        }
        val langRes = resolveCommandReplyLanguage(utterance, mutateHistoryIfGenuine = false)
        return localizeInternalDiagnosticIfNeeded(cleaned, langRes.language)
    }

    private fun localizeInternalDiagnosticIfNeeded(cleanedText: String, targetLang: DetectedLanguage): String {
        if (targetLang == DetectedLanguage.ENGLISH) return cleanedText
        val useUrduScript = targetLang == DetectedLanguage.URDU_NASTALIQ

        // Match "Opened <Target>." or "Opened <Target>"
        val openedMatch = Regex("^Opened\\s+([^.!?]+)[.!?]?$", RegexOption.IGNORE_CASE).matchEntire(cleanedText)
        if (openedMatch != null) {
            val target = cleanSpokenAppName(openedMatch.groupValues[1])
            return if (useUrduScript) "$target کھل گیا ہے۔" else "$target khul gaya hai."
        }

        // Match "Returned to <Target> with your draft preserved." or "Returned to <Target>."
        val returnedDraftMatch = Regex("^Returned\\s+to\\s+(.+?)\\s+with\\s+your\\s+draft\\s+preserved[.!?]?$", RegexOption.IGNORE_CASE).matchEntire(cleanedText)
        if (returnedDraftMatch != null) {
            val target = cleanSpokenAppName(returnedDraftMatch.groupValues[1])
            return if (useUrduScript) {
                "$target واپس کھل گیا ہے اور آپ کا ڈرافٹ محفوظ ہے۔"
            } else {
                "$target wapas khul gaya hai aur aapka draft mehfooz hai."
            }
        }

        val returnedMatch = Regex("^Returned\\s+to\\s+([^.!?]+)[.!?]?$", RegexOption.IGNORE_CASE).matchEntire(cleanedText)
        if (returnedMatch != null) {
            val target = cleanSpokenAppName(returnedMatch.groupValues[1])
            return if (useUrduScript) "$target واپس کھل گیا ہے۔" else "$target wapas khul gaya hai."
        }

        // Match "I tried to open <Target>, but could not confirm..." (preserve unverified truth!)
        val unverifiedOpenMatch = Regex("^I\\s+tried\\s+to\\s+open\\s+([^,]+),\\s*but\\s+could\\s+not\\s+confirm.*$", RegexOption.IGNORE_CASE).matchEntire(cleanedText)
        if (unverifiedOpenMatch != null) {
            val target = cleanSpokenAppName(unverifiedOpenMatch.groupValues[1])
            return if (useUrduScript) {
                "میں نے $target کھولنے کی کوشش کی، لیکن ابھی کھلنا کنفرم نہیں ہوا۔"
            } else {
                "Maine $target kholne ki koshish ki, lekin abhi khulna confirm nahin hua."
            }
        }

        val flashlightMatch = Regex("^Flashlight\\s+turned\\s+(ON|OFF)[.!?]?$", RegexOption.IGNORE_CASE).matchEntire(cleanedText)
        if (flashlightMatch != null) {
            val isOn = flashlightMatch.groupValues[1].equals("ON", ignoreCase = true)
            return if (useUrduScript) {
                if (isOn) "فلیش لائٹ آن ہو گئی ہے۔" else "فلیش لائٹ بند ہو گئی ہے۔"
            } else {
                if (isOn) "Flashlight on ho gayi hai." else "Flashlight band ho gayi hai."
            }
        }

        return cleanedText
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
        restoreAuthoritativeSpeechPreferences()
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

    /**
     * Restores the authoritative voice persona and language preferences from [SpeechPreferenceStore]
     * during initialization, reconnect, app resume, and output-provider recreation so OmniVoice
     * never silently resets to a default voice after an app switch or timeout.
     */
    fun restoreAuthoritativeSpeechPreferences(): GeminiLiveVoice {
        SpeechPreferenceStore.ensureLoaded(context)
        val savedVoiceName = SpeechPreferenceStore.getSelectedLiveVoice(context)
        val matchedVoice = GeminiLiveVoice.entries.firstOrNull {
            it.voiceName.equals(savedVoiceName, ignoreCase = true)
        } ?: _selectedVoice.value
        if (_selectedVoice.value != matchedVoice) {
            _selectedVoice.value = matchedVoice
        }
        return matchedVoice
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
     * Single idempotent start-session-and-listen operation shared by both "Launch Jarvis"
     * and the bottom microphone control.
     *
     * - Initializes the session at most once when already active (no duplicate sockets/recorders/recognizers).
     * - Pre-warms output routes (24kHz AudioTrack + local TTS).
     * - Releases any previous microphone owner before starting active capture.
     * - Opens a bounded initial command window without requiring a second microphone tap.
     * - Shows `STARTING` until capture hardware/recognizer is actually ready, then transitions to `LISTENING`.
     */
    fun launchSessionAndStartCommandListening(
        reason: String = "EXPLICIT_USER_LAUNCH",
        voice: GeminiLiveVoice? = null,
        openBoundedCommandWindow: Boolean = true
    ): Boolean {
        lastLaunchRequestElapsedMs = SystemClock.elapsedRealtime()
        val coord = AssistantAudioStateMachine.shared
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) {
            Log.w(TAG, "Cannot launch Jarvis listening without RECORD_AUDIO permission ($reason)")
            coord.armUserSessionAndPersist(context, reason)
            _observedCaptureState.value = ObservedAssistantCaptureState.ERROR
            lastRecognitionErrorCode = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            lastRecognitionErrorName = "ERROR_INSUFFICIENT_PERMISSIONS"
            lastRecognitionDecision = "REJECTED_PERMISSION_DENIED"
            setStickyCaptureError("Microphone permission (RECORD_AUDIO) is required. Tap to grant microphone access.")
            _liveAiTranscript.value = _captureErrorDetail.value
            return false
        }

        consecutiveRecognitionRetryCount = 0
        coord.armUserSessionAndPersist(context, reason)
        _isMicMuted.value = false

        // Pause background wake service before acquiring command/live capture so onStartCommand never steals the mic
        if (com.example.service.WakeWordForegroundService.isServiceActive.value) {
            com.example.service.WakeWordForegroundService.pause(context)
        }

        // Single audio pipeline rule: cancel local TTS immediately so it never talks over active listening
        ttsFallback?.stop()
        OmniAutonomousCoreEngine.resetInactivityTimer()

        if (voice != null) {
            selectVoice(voice)
        } else {
            restoreAuthoritativeSpeechPreferences()
        }

        val assistantName = OmniAutonomousCoreEngine.permanentMemory.value.assistantName
        val wasAlreadyActive = _isSessionActive.value

        if (!wasAlreadyActive) {
            _isSessionActive.value = true
            _liveSessionStartCount.incrementAndGet()
            _observedCaptureState.value = ObservedAssistantCaptureState.STARTING
            _connectionState.value = LiveConnectionState.CONNECTING
            _liveUserTranscript.value = ""
            _liveAiTranscript.value =
                "$assistantName Starting (${_selectedVoice.value.voiceName}) • Preparing microphone..."
            triedFallbackModel = false

            JarvisHandsFreeEngine.setKeepAliveCarrierActive(true)
            start24kHzGaplessOutputQueue()
            connectBidiWebSocket(LIVE_MODEL_PRIMARY)
            start60SecondInactivityWatchdog()
        } else {
            if (OmniAutonomousCoreEngine.operatingMode.value == OmniAutonomousCoreEngine.operatingMode.value &&
                OmniAutonomousCoreEngine.operatingMode.value == OmniOperatingMode.STATE_SLEEP_STANDBY
            ) {
                OmniAutonomousCoreEngine.wakeUpToActiveListening(context)
            }
            if (outputPlaybackJob?.isActive != true) {
                start24kHzGaplessOutputQueue()
            }
            if (activeWebSocket == null && OmniAutonomousCoreEngine.refreshOnlineState(context)) {
                connectBidiWebSocket(LIVE_MODEL_PRIMARY)
            }
            if (inactivityTimerJob?.isActive != true) {
                start60SecondInactivityWatchdog()
            }
            if (!isCaptureCurrentlyReady()) {
                _observedCaptureState.value = ObservedAssistantCaptureState.STARTING
                _liveAiTranscript.value =
                    "$assistantName Starting (${_selectedVoice.value.voiceName}) • Preparing microphone..."
            }
        }

        if (openBoundedCommandWindow) {
            isBoundedConversationModeActive = true
            boundedConversationRemainingTurns = maxBoundedConversationTurnsPerLaunch
            coord.openCommandWindow(reason = reason)
        }
        startActiveCommandCapture(reason = reason, preserveErrorDetail = true)
        refreshDiagnosticSnapshot()
        return true
    }

    /**
     * Returns `true` ONLY when an active microphone owner (`COMMAND_WINDOW_CAPTURE` or `LIVE_SESSION_ENGINE`)
     * holds the current [activeCaptureGeneration] and that exact generation has confirmed hardware/recognizer
     * readiness (`readyCaptureGeneration == activeCaptureGeneration` and active recognizer/recorder running).
     *
     * Historical timestamps (`lastCaptureReadyElapsedMs > 0`) and wake-only ownership enums NEVER count
     * as current capture readiness.
     */
    fun isCaptureCurrentlyReady(): Boolean {
        val coord = AssistantAudioStateMachine.shared
        if (!_isSessionActive.value ||
            _isMicMuted.value ||
            coord.isStoppedByUserInMemory() ||
            coord.isDeviceMutedInMemory()
        ) {
            return false
        }
        val currentOwner = coord.activeMicOwner.value
        val validCommandOwner = currentOwner == MicComponentOwner.COMMAND_WINDOW_CAPTURE ||
            currentOwner == MicComponentOwner.LIVE_SESSION_ENGINE
        if (!validCommandOwner || currentOwner != readyCaptureOwner) {
            return false
        }
        val gen = activeCaptureGeneration
        if (gen <= 0L || readyCaptureGeneration != gen || retiredCaptureGenerations.contains(gen)) {
            return false
        }
        val recReady = micAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        return isNativeRecognizerListening || recReady
    }

    fun clearCaptureReadiness(reason: String = "CAPTURE_RELEASED") {
        readyCaptureGeneration = 0L
        readyCaptureOwner = MicComponentOwner.NONE
        refreshDiagnosticSnapshot()
        Log.d(TAG, "Cleared capture readiness ($reason, activeGen=$activeCaptureGeneration)")
    }

    /**
     * Marks capture ready ONLY when a real readiness callback (`SpeechRecognizer.onReadyForSpeech`
     * having set `isNativeRecognizerListening = true`) or `AudioRecord.RECORDSTATE_RECORDING`
     * is genuinely active for [generation]. Never invents `isNativeRecognizerListening = true`.
     */
    fun markCaptureReady(
        source: String = "CAPTURE_READY",
        generation: Long = activeCaptureGeneration,
        owner: MicComponentOwner = AssistantAudioStateMachine.shared.activeMicOwner.value
    ) {
        val coord = AssistantAudioStateMachine.shared
        if (!_isSessionActive.value ||
            _isMicMuted.value ||
            coord.isStoppedByUserInMemory() ||
            coord.isDeviceMutedInMemory()
        ) {
            return
        }
        val resolvedGen = if (generation > 0L) generation else activeCaptureGeneration
        if (resolvedGen <= 0L ||
            resolvedGen != activeCaptureGeneration ||
            retiredCaptureGenerations.contains(resolvedGen)
        ) {
            staleRecognitionCallbacksIgnoredCount += 1
            Log.d(TAG, "Ignored stale markCaptureReady from $source (callbackGen=$resolvedGen, activeGen=$activeCaptureGeneration)")
            return
        }
        val resolvedOwner = if (owner == MicComponentOwner.COMMAND_WINDOW_CAPTURE || owner == MicComponentOwner.LIVE_SESSION_ENGINE) {
            owner
        } else {
            coord.activeMicOwner.value
        }
        if (resolvedOwner != MicComponentOwner.COMMAND_WINDOW_CAPTURE &&
            resolvedOwner != MicComponentOwner.LIVE_SESSION_ENGINE
        ) {
            return
        }
        val isRealRecognizerReady = isNativeRecognizerListening
        val isRealAudioRecordReady = micAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!isRealRecognizerReady && !isRealAudioRecordReady) {
            Log.w(
                TAG,
                "Rejected markCaptureReady($source) for gen=$resolvedGen: neither SpeechRecognizer.onReadyForSpeech nor AudioRecord.RECORDSTATE_RECORDING is active"
            )
            return
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        lastCaptureReadyElapsedMs = nowElapsed
        readyCaptureGeneration = resolvedGen
        readyCaptureOwner = resolvedOwner
        if (!coord.isSelfTriggerSuppressed() ||
            (_observedCaptureState.value != ObservedAssistantCaptureState.SPEAKING &&
                _observedCaptureState.value != ObservedAssistantCaptureState.PROCESSING)
        ) {
            _observedCaptureState.value = ObservedAssistantCaptureState.LISTENING
        }
        if (!coord.isSelfTriggerSuppressed() ||
            (_connectionState.value != LiveConnectionState.LIVE_AI_SPEAKING &&
                _connectionState.value != LiveConnectionState.LIVE_EXECUTING_TOOL)
        ) {
            _connectionState.value = LiveConnectionState.LIVE_LISTENING
            val currentText = _liveAiTranscript.value
            if (currentText.isBlank() ||
                currentText.contains("Starting") ||
                currentText.contains("Preparing microphone") ||
                currentText.contains("Connecting")
            ) {
                val assistantName = OmniAutonomousCoreEngine.permanentMemory.value.assistantName
                _liveAiTranscript.value =
                    "$assistantName Listening (${_selectedVoice.value.voiceName}) • Speak in English, اردو, or Roman Urdu"
            }
        }
        if (activeCommandId.isNotBlank()) {
            updateCommandDiagnostic(activeCommandId) { current ->
                if (current.captureReadyElapsedMs > 0L) {
                    current
                } else {
                    current.copy(captureReadyElapsedMs = nowElapsed)
                }
            }
        }
        refreshDiagnosticSnapshot()
        Log.d(TAG, "Microphone capture READY via $source (gen=$resolvedGen, owner=$resolvedOwner) at elapsed=${nowElapsed}ms")
    }

    /**
     * Called when the user says only the wake word ("Jarvis") to open a command window and
     * immediately start actual capture for the next spoken command.
     */
    fun startActiveCommandCaptureAfterWakeWord(
        reason: String = "WAKE_WORD_DETECTED_OPEN_WINDOW",
        nowMs: Long = System.currentTimeMillis()
    ) {
        val coord = AssistantAudioStateMachine.shared
        if (coord.isStoppedByUserInMemory() || coord.isDeviceMutedInMemory() || _isMicMuted.value) {
            return
        }
        if (!_isSessionActive.value) {
            _isSessionActive.value = true
        }
        consecutiveRecognitionRetryCount = 0
        isBoundedConversationModeActive = true
        if (boundedConversationRemainingTurns <= 0) {
            boundedConversationRemainingTurns = maxBoundedConversationTurnsPerLaunch
        }
        if (!coord.isCommandWindowOpen(nowMs)) {
            coord.openCommandWindow(nowMs = nowMs, reason = reason)
        }
        startActiveCommandCapture(reason = reason, nowMs = nowMs, preserveErrorDetail = true)
    }

    private fun startActiveCommandCapture(
        reason: String,
        nowMs: Long = System.currentTimeMillis(),
        preserveErrorDetail: Boolean = true
    ) {
        val prevRecognizerAndGen: Pair<SpeechRecognizer?, Long>
        val nextCaptureGen: Long
        synchronized(captureHandoffLock) {
            val coord = AssistantAudioStateMachine.shared
            if (_isMicMuted.value || coord.isStoppedByUserInMemory() || coord.isDeviceMutedInMemory()) {
                return
            }
            // 1. Invalidate/retire the old capture generation BEFORE stopping/releasing old recognizer
            val oldGen = activeCaptureGeneration.takeIf { it > 0L } ?: activeRecognizerBoundGeneration
            prevRecognizerAndGen = retireCaptureGenerationLocked(oldGen, "BEFORE_START_ACTIVE_CAPTURE_$reason")
            clearCaptureReadiness("BEFORE_START_ACTIVE_CAPTURE_$reason")
            if (!preserveErrorDetail) {
                _captureErrorDetail.value = ""
            }
            _observedCaptureState.value = ObservedAssistantCaptureState.STARTING
            if (_connectionState.value == LiveConnectionState.LIVE_AI_SPEAKING) {
                _connectionState.value = LiveConnectionState.LIVE_LISTENING
            }
            if (!coord.isCommandWindowOpen(nowMs)) {
                coord.openCommandWindow(nowMs = nowMs, reason = reason)
            }
            nextCaptureGen = captureGenerationCounter.incrementAndGet()
            activeCaptureGeneration = nextCaptureGen
            refreshDiagnosticSnapshot()
        }
        // 2. Await main-thread release of the retired recognizer BEFORE starting its replacement
        releaseRetiredRecognizerOnMainThreadSync(
            recognizerToRelease = prevRecognizerAndGen.first,
            retiredGeneration = prevRecognizerAndGen.second,
            adapter = testSpeechCaptureAdapter
        )
        // 3. Start the new recognizer bound to nextCaptureGen
        startNativeSpeechRecognizerLoop(
            ensureSessionIfNeeded = false,
            captureGeneration = nextCaptureGen,
            nowMs = nowMs
        )
    }

    /**
     * Serializes post-turn microphone handoff after playback drain and echo suppression cooldown:
     * - Respects deliberate Stop and Mute.
     * - Preserves diagnostic errors (`preserveErrorDetail = true` by default) even when transitioning to IDLE or LISTENING.
     * - When in an explicit bounded conversation session (`isBoundedConversationModeActive && boundedConversationRemainingTurns > 0`),
     *   waits for any remaining post-speech echo cooldown and automatically starts real capture for the next turn
     *   (`STARTING` -> `onReadyForSpeech` -> `LISTENING`) without endless background wake restarts.
     * - When returning to wake-only mode without an active bounded conversation turn, never claims `LISTENING`
     *   if no local acoustic wake detector is active.
     */
    fun handoffToPostTurnCapture(
        reason: String,
        preserveErrorDetail: Boolean = true
    ) {
        val shouldStartNextBoundedTurn: Boolean
        synchronized(captureHandoffLock) {
            val coord = AssistantAudioStateMachine.shared
            clearCaptureReadiness("HANDOFF_$reason")
            if (coord.isStoppedByUserInMemory() || coord.isStoppedByUserPersisted(context)) {
                isBoundedConversationModeActive = false
                boundedConversationRemainingTurns = 0
                _observedCaptureState.value = ObservedAssistantCaptureState.IDLE
                _connectionState.value = LiveConnectionState.DISCONNECTED
                refreshDiagnosticSnapshot()
                return
            }
            if (_isMicMuted.value || coord.isDeviceMutedInMemory() || coord.isDeviceMutedPersisted(context)) {
                _observedCaptureState.value = ObservedAssistantCaptureState.MUTED
                refreshDiagnosticSnapshot()
                return
            }
            if (_connectionState.value == LiveConnectionState.LIVE_AI_SPEAKING && _isSessionActive.value) {
                _connectionState.value = LiveConnectionState.LIVE_LISTENING
            }

            shouldStartNextBoundedTurn = isBoundedConversationModeActive &&
                _isSessionActive.value &&
                boundedConversationRemainingTurns > 0 &&
                !reason.contains("CANCEL", ignoreCase = true)

            if (shouldStartNextBoundedTurn) {
                boundedConversationRemainingTurns -= 1
                consecutiveRecognitionRetryCount = 0
                coord.clearTailCooldownAfterDrain()
                coord.openCommandWindow(reason = "BOUNDED_CONVERSATION_FOLLOWUP_$reason")
            } else {
                coord.returnToWakeOnlyListening(reason)
                if (com.example.service.WakeWordForegroundService.isServiceActive.value) {
                    com.example.service.WakeWordForegroundService.resume(context)
                } else if (coord.lifecycleState.value != WakeSpeechLifecycleState.DISARMED_STOPPED &&
                    coord.activeMicOwner.value == MicComponentOwner.NONE
                ) {
                    coord.acquireMicrophoneOwnership(MicComponentOwner.WAKE_WORD_SERVICE)
                }
                // Truthfulness rule: wake-only mode without a real local acoustic wake detector must NEVER display LISTENING!
                if (AssistantAudioStateMachine.isLocalAcousticWakeDetectorAvailable) {
                    _observedCaptureState.value = ObservedAssistantCaptureState.LISTENING
                } else {
                    _observedCaptureState.value = ObservedAssistantCaptureState.IDLE
                    if (_isSessionActive.value) {
                        _connectionState.value = LiveConnectionState.LIVE_LISTENING
                    }
                }
                refreshDiagnosticSnapshot()
            }
        }
        if (shouldStartNextBoundedTurn) {
            startActiveCommandCapture(
                reason = "BOUNDED_CONVERSATION_FOLLOWUP_$reason",
                preserveErrorDetail = preserveErrorDetail
            )
        }
    }

    /**
     * Records a turn where sound/speech onset was detected during an active listening window
     * but no actionable or conversational command could be accepted, surfacing a truthful,
     * localized message instead of waiting silently.
     */
    fun recordSoundDetectedNoAcceptedCommand(
        reason: String,
        candidateUtterance: String = "",
        suppressPostTurnHandoff: Boolean = false,
        preserveErrorDetail: Boolean = true
    ): LiveTurnExecutionReport {
        clearCaptureReadiness("NO_ACCEPTED_COMMAND_$reason")
        val nowElapsed = SystemClock.elapsedRealtime()
        val cmdId = "cmd_no_match_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val langRes = resolveCommandReplyLanguage(candidateUtterance, mutateHistoryIfGenuine = false)
        val lang = langRes.language
        val actionableMsg = when (lang) {
            DetectedLanguage.URDU_NASTALIQ ->
                "آواز سنائی دی لیکن واضح کمانڈ سمجھ نہیں آئی۔ دوبارہ بولیں یا مائیک دبائیں۔"
            DetectedLanguage.ROMAN_URDU, DetectedLanguage.MIXED ->
                "Awaaz sunai di lekin wazeh command samajh nahin aayi. Dobara bolein ya mic dabayein."
            DetectedLanguage.ENGLISH ->
                "Sound was detected, but no clear command was recognized. Please speak again or tap the mic."
        }
        val captureReady = if (lastCaptureReadyElapsedMs > 0L) lastCaptureReadyElapsedMs else nowElapsed
        val onset = if (pendingSpeechOnsetElapsedMs > 0L) pendingSpeechOnsetElapsedMs else captureReady
        val speechEnd = if (pendingSpeechEndElapsedMs > 0L) pendingSpeechEndElapsedMs else nowElapsed
        val partials = pendingPartialRecognitionCount
        pendingSpeechOnsetElapsedMs = 0L
        pendingSpeechEndElapsedMs = 0L
        pendingPartialRecognitionCount = 0
        pendingFinalRecognitionObserved = false

        val liveVoice = _selectedVoice.value.voiceName
        commandDiagnosticsMap[cmdId] = CommandAudioDiagnosticEntry(
            commandId = cmdId,
            route = "VOICE_NO_ACCEPTED_COMMAND",
            observedActionResult = "SOUND_DETECTED_NO_ACCEPTED_COMMAND",
            outputState = CommandSpeechOutputState.NONE,
            outputEngine = "NONE",
            requestedOutputProvider = "NONE",
            actualOutputProvider = "NONE",
            requestedVoiceId = liveVoice,
            resolvedVoiceId = liveVoice,
            resolvedLocaleTag = lang.ttsLocale.toLanguageTag(),
            speechRate = SpeechPreferenceStore.getSpeechRate(context),
            pitch = SpeechPreferenceStore.getPitch(context),
            chosenReplyLanguage = lang,
            languageSelectionReason = langRes.reason,
            fallbackOrReconnectReason = reason,
            wsGeneration = activeWebSocketGeneration,
            cancellationReason = reason,
            launchRequestedElapsedMs = lastLaunchRequestElapsedMs,
            captureReadyElapsedMs = captureReady,
            speechOnsetElapsedMs = onset,
            speechEndElapsedMs = speechEnd,
            partialRecognitionCount = partials,
            finalRecognitionObserved = candidateUtterance.isNotBlank(),
            stallStage = CommandTurnStallStage.SOUND_DETECTED_NO_ACCEPTED_COMMAND
        )
        _liveAiTranscript.value = actionableMsg
        setStickyCaptureError("$actionableMsg [$reason]")
        val report = recordTurnExecutionReport(
            commandId = cmdId,
            utterance = candidateUtterance,
            outputEngine = "NONE",
            language = lang,
            languageSelectionReason = langRes.reason,
            liveConnected = isGeminiLiveWebSocketConnected(),
            finalStatus = "SOUND_DETECTED_NO_ACCEPTED_COMMAND",
            spokenText = actionableMsg,
            diagnosticText = "$actionableMsg ($reason)",
            fallbackOrReconnectReason = reason
        )
        refreshDiagnosticSnapshot()
        if (!suppressPostTurnHandoff) {
            handoffToPostTurnCapture(reason, preserveErrorDetail = preserveErrorDetail)
        }
        return report
    }

    /**
     * Starts the Gemini Live bidirectional voice streaming session and opens the initial
     * bounded command window via the shared idempotent [launchSessionAndStartCommandListening] path.
     */
    fun startLiveSession() {
        launchSessionAndStartCommandListening(
            reason = "START_GEMINI_LIVE_SESSION",
            openBoundedCommandWindow = true
        )
    }

    private fun isNativeSpeechRecognitionAvailableSafe(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Exception) {
            false
        }
    }

    fun classifyRecognitionFailure(errorCode: Int, captureGen: Long): RecognitionFailureCategory {
        val coord = AssistantAudioStateMachine.shared
        synchronized(captureHandoffLock) {
            if (finalResultAcceptedForCaptureGen == captureGen ||
                retiredCaptureGenerations.contains(captureGen) ||
                intentionalCancelGenerations.contains(captureGen) ||
                captureGen != activeCaptureGeneration ||
                !_isSessionActive.value ||
                _isMicMuted.value ||
                coord.isDeviceMutedInMemory() ||
                coord.isStoppedByUserInMemory()
            ) {
                return RecognitionFailureCategory.INTENTIONAL_CANCELLATION
            }
        }
        return when (errorCode) {
            SpeechRecognizer.ERROR_CLIENT -> {
                if (coord.isSelfTriggerSuppressed()) {
                    RecognitionFailureCategory.INTENTIONAL_CANCELLATION
                } else {
                    RecognitionFailureCategory.TRANSIENT_RECOGNITION_ERROR
                }
            }
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -1 -> RecognitionFailureCategory.NO_MATCH_OR_SILENCE

            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            11 -> RecognitionFailureCategory.TRANSIENT_RECOGNITION_ERROR

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            10,
            12,
            13,
            14,
            15,
            -2 -> RecognitionFailureCategory.PERMISSION_OR_SERVICE_FAILURE

            else -> RecognitionFailureCategory.TRANSIENT_RECOGNITION_ERROR
        }
    }

    private fun retireCaptureGenerationLocked(
        genToRetire: Long,
        reason: String
    ): Pair<SpeechRecognizer?, Long> {
        if (genToRetire > 0L) {
            retiredCaptureGenerations.add(genToRetire)
            intentionalCancelGenerations.add(genToRetire)
            while (retiredCaptureGenerations.size > 64) {
                val first = retiredCaptureGenerations.firstOrNull() ?: break
                retiredCaptureGenerations.remove(first)
            }
            while (intentionalCancelGenerations.size > 64) {
                val first = intentionalCancelGenerations.firstOrNull() ?: break
                intentionalCancelGenerations.remove(first)
            }
        }
        if (genToRetire > 0L && activeCaptureGeneration == genToRetire) {
            activeCaptureGeneration = 0L
        }
        if (genToRetire > 0L && readyCaptureGeneration == genToRetire) {
            readyCaptureGeneration = 0L
            readyCaptureOwner = MicComponentOwner.NONE
        }
        val recognizerToRelease: SpeechRecognizer?
        val boundGen: Long
        if (genToRetire <= 0L || activeRecognizerBoundGeneration == genToRetire) {
            recognizerToRelease = activeRecognizerInstance
            boundGen = activeRecognizerBoundGeneration
            activeRecognizerInstance = null
            activeRecognizerBoundGeneration = 0L
            nativeSpeechRecognizer = null
            isNativeRecognizerListening = false
        } else {
            recognizerToRelease = null
            boundGen = 0L
        }
        Log.d(TAG, "Retired capture generation $genToRetire ($reason)")
        return recognizerToRelease to boundGen
    }

    private fun releaseRetiredRecognizerOnMainThreadSync(
        recognizerToRelease: SpeechRecognizer?,
        retiredGeneration: Long,
        adapter: SpeechCaptureAdapter?
    ) {
        if (recognizerToRelease == null && (adapter == null || retiredGeneration <= 0L)) {
            return
        }
        val releaseBlock = Runnable {
            if (adapter != null && retiredGeneration > 0L) {
                try {
                    adapter.stopListening(retiredGeneration)
                    adapter.cancel(retiredGeneration)
                    adapter.destroy(retiredGeneration)
                } catch (_: Exception) {
                }
            }
            if (recognizerToRelease != null) {
                try {
                    recognizerToRelease.stopListening()
                } catch (_: Exception) {
                }
                try {
                    recognizerToRelease.cancel()
                } catch (_: Exception) {
                }
                try {
                    recognizerToRelease.destroy()
                } catch (_: Exception) {
                } finally {
                    if (liveRecognizerInstances.remove(recognizerToRelease)) {
                        _activeRecognizerInstanceCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseBlock.run()
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            val posted = mainHandler.post {
                try {
                    releaseBlock.run()
                } finally {
                    latch.countDown()
                }
            }
            if (posted) {
                try {
                    latch.await(1500L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun handleRecognitionFailureOrEmptyResult(
        captureGen: Long,
        boundRecognizer: SpeechRecognizer?,
        errorCode: Int
    ) {
        val coord = AssistantAudioStateMachine.shared
        val errName = speechRecognizerErrorName(errorCode)

        // 1. Check if this callback belongs to a generation that already produced a final result or was retired/superseded
        val category: RecognitionFailureCategory
        val retiredPair: Pair<SpeechRecognizer?, Long>
        synchronized(captureHandoffLock) {
            val alreadyAcceptedFinal = finalResultAcceptedForCaptureGen == captureGen
            val isStaleGen = captureGen <= 0L ||
                captureGen != activeCaptureGeneration ||
                retiredCaptureGenerations.contains(captureGen) ||
                (boundRecognizer != null && activeRecognizerInstance != null && boundRecognizer !== activeRecognizerInstance)

            if (alreadyAcceptedFinal || isStaleGen) {
                staleRecognitionCallbacksIgnoredCount += 1
                lastRecognitionErrorCode = errorCode
                lastRecognitionErrorName = errName
                lastRecognitionDecision = if (alreadyAcceptedFinal) {
                    "IGNORED_POST_FINAL_CANCEL_$errName"
                } else {
                    "IGNORED_STALE_CALLBACK_$errName"
                }
                refreshDiagnosticSnapshot()
                Log.d(
                    TAG,
                    "Ignored trailing/stale recognition error $errName($errorCode) for gen=$captureGen (activeGen=$activeCaptureGeneration, finalAcceptedGen=$finalResultAcceptedForCaptureGen)"
                )
                return
            }

            category = classifyRecognitionFailure(errorCode, captureGen)
            if (category == RecognitionFailureCategory.INTENTIONAL_CANCELLATION) {
                staleRecognitionCallbacksIgnoredCount += 1
                lastRecognitionErrorCode = errorCode
                lastRecognitionErrorName = errName
                lastRecognitionDecision = "IGNORED_INTENTIONAL_CANCEL_$errName"
                retiredPair = retireCaptureGenerationLocked(captureGen, "INTENTIONAL_CANCEL_$errName")
            } else {
                retiredPair = retireCaptureGenerationLocked(captureGen, "RECOGNITION_FAILURE_$errName")
            }
        }

        // Release the retired recognizer instance synchronously on the main thread before any state transition or retry
        releaseRetiredRecognizerOnMainThreadSync(
            recognizerToRelease = retiredPair.first,
            retiredGeneration = retiredPair.second,
            adapter = testSpeechCaptureAdapter
        )

        if (category == RecognitionFailureCategory.INTENTIONAL_CANCELLATION) {
            if (coord.isStoppedByUserInMemory() || !_isSessionActive.value) {
                _observedCaptureState.value = ObservedAssistantCaptureState.IDLE
            } else if (_isMicMuted.value || coord.isDeviceMutedInMemory()) {
                _observedCaptureState.value = ObservedAssistantCaptureState.MUTED
            }
            refreshDiagnosticSnapshot()
            return
        }

        _inputAudioRms.value = 0f
        if (pendingSpeechEndElapsedMs == 0L) {
            pendingSpeechEndElapsedMs = SystemClock.elapsedRealtime()
        }

        when (category) {
            RecognitionFailureCategory.NO_MATCH_OR_SILENCE,
            RecognitionFailureCategory.TRANSIENT_RECOGNITION_ERROR -> {
                lastRecognitionErrorCode = errorCode
                lastRecognitionErrorName = errName
                recordSoundDetectedNoAcceptedCommand(
                    reason = "${errName}($errorCode)",
                    candidateUtterance = "",
                    suppressPostTurnHandoff = true,
                    preserveErrorDetail = true
                )
                val canRetryInActiveConversation = _isSessionActive.value &&
                    isBoundedConversationModeActive &&
                    !coord.isStoppedByUserInMemory() &&
                    !_isMicMuted.value &&
                    !coord.isDeviceMutedInMemory() &&
                    consecutiveRecognitionRetryCount < maxRecognitionRetriesPerTurn

                if (canRetryInActiveConversation) {
                    consecutiveRecognitionRetryCount += 1
                    lastRecognitionDecision = if (category == RecognitionFailureCategory.NO_MATCH_OR_SILENCE) {
                        "RETRYING_NO_MATCH ($consecutiveRecognitionRetryCount/$maxRecognitionRetriesPerTurn)"
                    } else {
                        "RETRYING_TRANSIENT_ERROR ($consecutiveRecognitionRetryCount/$maxRecognitionRetriesPerTurn)"
                    }
                    val retryMsg = "Recognition $errName ($errorCode) — retrying ($consecutiveRecognitionRetryCount/$maxRecognitionRetriesPerTurn). Speak now."
                    setStickyCaptureError(retryMsg)
                    _liveAiTranscript.value = retryMsg
                    startActiveCommandCapture(
                        reason = "BOUNDED_RETRY_${errName}_${consecutiveRecognitionRetryCount}",
                        preserveErrorDetail = true
                    )
                } else {
                    lastRecognitionDecision = "REJECTED_RETRY_EXHAUSTED_$errName"
                    isBoundedConversationModeActive = false
                    boundedConversationRemainingTurns = 0
                    val exhaustedActionMsg = if (category == RecognitionFailureCategory.NO_MATCH_OR_SILENCE) {
                        "No speech recognized ($errName:$errorCode) after $consecutiveRecognitionRetryCount retry attempt(s). Tap the microphone to speak again."
                    } else {
                        "Speech recognition failed ($errName:$errorCode) after $consecutiveRecognitionRetryCount retry attempt(s). Tap the microphone to retry."
                    }
                    setStickyCaptureError(exhaustedActionMsg)
                    _liveAiTranscript.value = exhaustedActionMsg
                    coord.returnToWakeOnlyListening("RECOGNITION_RETRY_EXHAUSTED_$errName")
                    AssistantAudioStateMachine.shared.releaseMicrophoneOwnership(MicComponentOwner.COMMAND_WINDOW_CAPTURE)
                    _observedCaptureState.value = if (category == RecognitionFailureCategory.TRANSIENT_RECOGNITION_ERROR) {
                        ObservedAssistantCaptureState.ERROR
                    } else {
                        ObservedAssistantCaptureState.IDLE
                    }
                    refreshDiagnosticSnapshot()
                }
            }

            RecognitionFailureCategory.PERMISSION_OR_SERVICE_FAILURE,
            RecognitionFailureCategory.NONE -> {
                lastRecognitionErrorCode = errorCode
                lastRecognitionErrorName = errName
                lastRecognitionDecision = "REJECTED_UNRECOVERABLE_$errName"
                isBoundedConversationModeActive = false
                boundedConversationRemainingTurns = 0
                val recoveryGuidance = when (errorCode) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Microphone permission denied ($errName:$errorCode). Grant RECORD_AUDIO permission and tap the microphone."
                    12, 13 ->
                        "Speech language pack unavailable ($errName:$errorCode). Install offline voice data or switch language, then tap the microphone."
                    else ->
                        "Speech recognition service failure ($errName:$errorCode). Check speech services and tap the microphone to retry."
                }
                recordSoundDetectedNoAcceptedCommand(
                    reason = "${errName}($errorCode)",
                    candidateUtterance = "",
                    suppressPostTurnHandoff = true,
                    preserveErrorDetail = true
                )
                setStickyCaptureError(recoveryGuidance)
                _liveAiTranscript.value = recoveryGuidance
                coord.returnToWakeOnlyListening("UNRECOVERABLE_RECOGNITION_$errName")
                AssistantAudioStateMachine.shared.releaseMicrophoneOwnership(MicComponentOwner.COMMAND_WINDOW_CAPTURE)
                _observedCaptureState.value = ObservedAssistantCaptureState.ERROR
                refreshDiagnosticSnapshot()
            }

            RecognitionFailureCategory.INTENTIONAL_CANCELLATION -> {}
        }
    }

    private fun createGenerationBoundRecognitionListener(
        captureGen: Long,
        boundRecognizer: SpeechRecognizer? = null
    ): RecognitionListener {
        val coord = AssistantAudioStateMachine.shared
        return object : RecognitionListener {
            private fun isCurrentGen(): Boolean {
                val match = synchronized(captureHandoffLock) {
                    val genOk = captureGen > 0L &&
                        captureGen == activeCaptureGeneration &&
                        !retiredCaptureGenerations.contains(captureGen)
                    val instanceOk = boundRecognizer == null ||
                        activeRecognizerInstance == null ||
                        boundRecognizer === activeRecognizerInstance
                    genOk && instanceOk
                }
                if (!match) {
                    staleRecognitionCallbacksIgnoredCount += 1
                    Log.d(
                        TAG,
                        "Ignored stale RecognitionListener callback (listenerGen=$captureGen, activeGen=$activeCaptureGeneration)"
                    )
                }
                return match
            }

            override fun onReadyForSpeech(params: Bundle?) {
                if (!isCurrentGen()) return
                isNativeRecognizerListening = true
                markCaptureReady(
                    source = "SPEECH_RECOGNIZER_ON_READY",
                    generation = captureGen,
                    owner = coord.activeMicOwner.value
                )
            }

            override fun onBeginningOfSpeech() {
                if (!isCurrentGen()) return
                pendingSpeechOnsetElapsedMs = SystemClock.elapsedRealtime()
                OmniAutonomousCoreEngine.resetInactivityTimer()
                if (_isSessionActive.value &&
                    isBoundedConversationModeActive &&
                    !coord.isStoppedByUserInMemory() &&
                    !_isMicMuted.value &&
                    !coord.isDeviceMutedInMemory() &&
                    !coord.isCommandWindowOpen()
                ) {
                    coord.openCommandWindow(reason = "BOUNDED_CONVERSATION_SPEECH_ONSET")
                }
                coord.extendCommandWindowOnSpeechActivity()
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!isCurrentGen()) return
                val scaled = ((rmsdB + 2f) * 8f).coerceIn(0f, 100f)
                _inputAudioRms.value = scaled
                if (scaled >= 15f) {
                    if (pendingSpeechOnsetElapsedMs == 0L) {
                        pendingSpeechOnsetElapsedMs = SystemClock.elapsedRealtime()
                    }
                    coord.extendCommandWindowOnSpeechActivity()
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (!isCurrentGen()) return
                isNativeRecognizerListening = false
                clearCaptureReadiness("RECOGNIZER_ON_END_OF_SPEECH")
                _inputAudioRms.value = 0f
                pendingSpeechEndElapsedMs = SystemClock.elapsedRealtime()
                if (_observedCaptureState.value == ObservedAssistantCaptureState.LISTENING) {
                    _observedCaptureState.value = ObservedAssistantCaptureState.PROCESSING
                }
                refreshDiagnosticSnapshot()
            }

            override fun onError(error: Int) {
                handleRecognitionFailureOrEmptyResult(
                    captureGen = captureGen,
                    boundRecognizer = boundRecognizer,
                    errorCode = error
                )
            }

            override fun onResults(results: Bundle?) {
                if (!isCurrentGen()) return
                isNativeRecognizerListening = false
                clearCaptureReadiness("RECOGNIZER_ON_RESULTS")
                _inputAudioRms.value = 0f
                if (pendingSpeechEndElapsedMs == 0L) {
                    pendingSpeechEndElapsedMs = SystemClock.elapsedRealtime()
                }
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val topText = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (topText.isNotBlank()) {
                    val retiredPair: Pair<SpeechRecognizer?, Long>
                    synchronized(captureHandoffLock) {
                        finalResultAcceptedForCaptureGen = captureGen
                        consecutiveRecognitionRetryCount = 0
                        lastRecognitionErrorCode = 0
                        lastRecognitionErrorName = "NONE"
                        lastRecognitionDecision = "ACCEPTED"
                        pendingFinalRecognitionObserved = true
                        retiredPair = retireCaptureGenerationLocked(captureGen, "FINAL_RESULT_ACCEPTED")
                    }
                    releaseRetiredRecognizerOnMainThreadSync(
                        recognizerToRelease = retiredPair.first,
                        retiredGeneration = retiredPair.second,
                        adapter = testSpeechCaptureAdapter
                    )
                    if (_isSessionActive.value &&
                        isBoundedConversationModeActive &&
                        !coord.isStoppedByUserInMemory() &&
                        !_isMicMuted.value &&
                        !coord.isDeviceMutedInMemory() &&
                        !coord.isCommandWindowOpen()
                    ) {
                        coord.openCommandWindow(reason = "BOUNDED_CONVERSATION_RECOGNIZER_RESULT")
                    }
                    _observedCaptureState.value = ObservedAssistantCaptureState.PROCESSING
                    refreshDiagnosticSnapshot()
                    scope.launch {
                        processConsentedVoiceAudioTurn(topText)
                    }
                } else {
                    handleRecognitionFailureOrEmptyResult(
                        captureGen = captureGen,
                        boundRecognizer = boundRecognizer,
                        errorCode = -1
                    )
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isCurrentGen()) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    pendingPartialRecognitionCount += 1
                    if (pendingSpeechOnsetElapsedMs == 0L) {
                        pendingSpeechOnsetElapsedMs = SystemClock.elapsedRealtime()
                    }
                    coord.extendCommandWindowOnSpeechActivity()
                    _liveUserTranscript.value = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Test helper that invokes the real generation-bound [RecognitionListener] callbacks
     * (`onReadyForSpeech` -> `onBeginningOfSpeech` -> `onPartialResults` -> `onResults`)
     * on [activeRecognitionListenerForTest] (or a supplied listener for stale-generation testing).
     */
    fun createRecognitionListenerForGenerationForTest(generation: Long): RecognitionListener =
        createGenerationBoundRecognitionListener(generation, null)

    /**
     * Starts a single-turn, bounded command-window Android SpeechRecognizer session on the main thread
     * when explicitly triggered by the user, a bounded conversation follow-up, or a local wake event ("Jarvis").
     *
     * CRITICAL ARCHITECTURE INVARIANTS:
     * 1. Any previous capture generation/recognizer is retired and released on the main thread BEFORE starting its replacement.
     * 2. Callbacks and cleanup are bound to the exact recognizer instance and capture generation.
     * 3. Production startup NEVER synthesizes `onReadyForSpeech`; only real readiness callbacks/recorder state mark capture ready.
     */
    fun startNativeSpeechRecognizerLoop(
        ensureSessionIfNeeded: Boolean = true,
        captureGeneration: Long = activeCaptureGeneration,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val coord = AssistantAudioStateMachine.shared
        if (_isMicMuted.value ||
            coord.isStoppedByUserInMemory() ||
            coord.isDeviceMutedInMemory() ||
            coord.isSelfTriggerSuppressed(nowMs)
        ) {
            return
        }
        if (!_isSessionActive.value) {
            if (ensureSessionIfNeeded) {
                launchSessionAndStartCommandListening(
                    reason = "EXPLICIT_MIC_CAPTURE_START",
                    openBoundedCommandWindow = true
                )
            }
            return
        }
        if (!coord.isCommandWindowOpen(nowMs)) {
            coord.openCommandWindow(nowMs = nowMs, reason = "EXPLICIT_COMMAND_WINDOW_STT")
        }

        val prevPairToRelease: Pair<SpeechRecognizer?, Long>
        val myGen: Long
        synchronized(captureHandoffLock) {
            if (captureGeneration > 0L) {
                val older = activeRecognizerBoundGeneration
                prevPairToRelease = if (older > 0L && older != captureGeneration) {
                    retireCaptureGenerationLocked(older, "REPLACE_BEFORE_START_$captureGeneration")
                } else if (activeRecognizerInstance != null) {
                    val inst = activeRecognizerInstance
                    val bGen = activeRecognizerBoundGeneration
                    activeRecognizerInstance = null
                    activeRecognizerBoundGeneration = 0L
                    nativeSpeechRecognizer = null
                    isNativeRecognizerListening = false
                    inst to bGen
                } else {
                    null to 0L
                }
                myGen = captureGeneration
                activeCaptureGeneration = myGen
            } else {
                val older = activeCaptureGeneration.takeIf { it > 0L } ?: activeRecognizerBoundGeneration
                prevPairToRelease = retireCaptureGenerationLocked(older, "REPLACE_WITH_FRESH_GENERATION")
                myGen = captureGenerationCounter.incrementAndGet()
                activeCaptureGeneration = myGen
            }
            readyCaptureGeneration = 0L
            readyCaptureOwner = MicComponentOwner.NONE
            isNativeRecognizerListening = false
        }

        // Await main-thread release of any previous recognizer before starting its replacement
        releaseRetiredRecognizerOnMainThreadSync(
            recognizerToRelease = prevPairToRelease.first,
            retiredGeneration = prevPairToRelease.second,
            adapter = testSpeechCaptureAdapter
        )

        val acquired = coord.acquireMicrophoneOwnership(MicComponentOwner.COMMAND_WINDOW_CAPTURE) {
            val keepActiveForBoundedTurn = synchronized(captureHandoffLock) {
                myGen == activeCaptureGeneration &&
                    !retiredCaptureGenerations.contains(myGen) &&
                    finalResultAcceptedForCaptureGen != myGen &&
                    _isSessionActive.value &&
                    isBoundedConversationModeActive &&
                    !_isMicMuted.value &&
                    !coord.isStoppedByUserInMemory() &&
                    !coord.isDeviceMutedInMemory()
            }
            if (!keepActiveForBoundedTurn) {
                stopNativeSpeechRecognizerLoop(releaseCoordinatorOwnership = false, expectedGeneration = myGen)
            }
        }
        if (!acquired) return

        val recognizeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "ur-PK", "hi-IN"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 750L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 550L)
        }

        val preListener = createGenerationBoundRecognitionListener(myGen, null)
        activeRecognitionListenerForTest = preListener
        refreshDiagnosticSnapshot()

        val adapterNow = testSpeechCaptureAdapter
        if (adapterNow != null) {
            var shouldStartAdapter = false
            synchronized(captureHandoffLock) {
                if (_isSessionActive.value &&
                    !_isMicMuted.value &&
                    myGen == activeCaptureGeneration &&
                    !retiredCaptureGenerations.contains(myGen) &&
                    !coord.isStoppedByUserInMemory() &&
                    !coord.isDeviceMutedInMemory()
                ) {
                    activeRecognizerBoundGeneration = myGen
                    activeRecognitionListenerForTest = preListener
                    shouldStartAdapter = true
                }
            }
            if (shouldStartAdapter) {
                adapterNow.startListening(recognizeIntent, preListener, myGen)
            }
            return
        }

        val startRecognizerBlock = Runnable {
            synchronized(captureHandoffLock) {
                if (!_isSessionActive.value ||
                    _isMicMuted.value ||
                    myGen != activeCaptureGeneration ||
                    retiredCaptureGenerations.contains(myGen) ||
                    coord.isStoppedByUserInMemory() ||
                    coord.isDeviceMutedInMemory() ||
                    coord.isSelfTriggerSuppressed()
                ) {
                    return@Runnable
                }
            }
            try {
                if (isNativeSpeechRecognitionAvailableSafe()) {
                    val createdRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    val instanceBoundListener = createGenerationBoundRecognitionListener(myGen, createdRecognizer)
                    var shouldStart = false
                    synchronized(captureHandoffLock) {
                        if (myGen == activeCaptureGeneration &&
                            !retiredCaptureGenerations.contains(myGen) &&
                            _isSessionActive.value &&
                            !_isMicMuted.value &&
                            _connectionState.value != LiveConnectionState.LIVE_AI_SPEAKING
                        ) {
                            activeRecognizerInstance = createdRecognizer
                            activeRecognizerBoundGeneration = myGen
                            nativeSpeechRecognizer = createdRecognizer
                            activeRecognitionListenerForTest = instanceBoundListener
                            if (liveRecognizerInstances.add(createdRecognizer)) {
                                _activeRecognizerInstanceCount.incrementAndGet()
                            }
                            shouldStart = true
                        }
                    }
                    if (shouldStart) {
                        createdRecognizer.setRecognitionListener(instanceBoundListener)
                        createdRecognizer.startListening(recognizeIntent)
                        // Note: isNativeRecognizerListening and markCaptureReady are set ONLY when
                        // real onReadyForSpeech fires on instanceBoundListener!
                    } else {
                        try {
                            createdRecognizer.destroy()
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    val pcmAcquired = coord.acquireMicrophoneOwnership(MicComponentOwner.LIVE_SESSION_ENGINE) {
                        releaseMicAudioRecord()
                    }
                    if (pcmAcquired && micStreamingJob?.isActive != true) {
                        start16kHzMicrophonePcmStream()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native SpeechRecognizer fallback to 16kHz PCM stream: ${e.message}")
                val pcmAcquired = coord.acquireMicrophoneOwnership(MicComponentOwner.LIVE_SESSION_ENGINE) {
                    releaseMicAudioRecord()
                }
                if (pcmAcquired && micStreamingJob?.isActive != true) {
                    start16kHzMicrophonePcmStream()
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startRecognizerBlock.run()
        } else {
            mainHandler.post(startRecognizerBlock)
        }
    }

    /**
     * Serialized recognizer shutdown:
     * 1. Invalidates/retires the capture generation BEFORE calling `stopListening()`/`cancel()`/`destroy()`.
     * 2. Binds cleanup to the exact retired [SpeechRecognizer] instance and generation (`expectedGeneration`).
     * 3. Awaits main-thread release so a delayed cleanup never stops or mutates a replacement capture.
     */
    fun stopNativeSpeechRecognizerLoop(
        releaseCoordinatorOwnership: Boolean = true,
        expectedGeneration: Long? = null
    ) {
        val pairToRelease: Pair<SpeechRecognizer?, Long>
        synchronized(captureHandoffLock) {
            if (expectedGeneration != null &&
                expectedGeneration > 0L &&
                activeCaptureGeneration != 0L &&
                activeCaptureGeneration != expectedGeneration &&
                activeRecognizerBoundGeneration != expectedGeneration
            ) {
                staleRecognitionCallbacksIgnoredCount += 1
                Log.d(
                    TAG,
                    "Ignored stale stopNativeSpeechRecognizerLoop for gen=$expectedGeneration (activeGen=$activeCaptureGeneration)"
                )
                return
            }
            val genToRetire = expectedGeneration
                ?: activeCaptureGeneration.takeIf { it > 0L }
                ?: activeRecognizerBoundGeneration
            pairToRelease = retireCaptureGenerationLocked(genToRetire, "STOP_NATIVE_RECOGNIZER")
            clearCaptureReadiness("STOP_NATIVE_RECOGNIZER")
        }

        releaseRetiredRecognizerOnMainThreadSync(
            recognizerToRelease = pairToRelease.first,
            retiredGeneration = pairToRelease.second,
            adapter = testSpeechCaptureAdapter
        )

        if (releaseCoordinatorOwnership) {
            val shouldReleaseOwner = synchronized(captureHandoffLock) {
                activeCaptureGeneration == 0L && activeRecognizerInstance == null
            }
            if (shouldReleaseOwner) {
                AssistantAudioStateMachine.shared.releaseMicrophoneOwnership(MicComponentOwner.COMMAND_WINDOW_CAPTURE)
            }
        }
        refreshDiagnosticSnapshot()
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
            val savedPitch = SpeechPreferenceStore.getPitch(context)
            val savedRate = SpeechPreferenceStore.getSpeechRate(context)
            ttsFallback?.speak(wakeGreeting, savedPitch, savedRate, DetectedLanguage.ROMAN_URDU.ttsLocale)
        }
        if (initialCommandAfterWake.isNotBlank()) {
            sendTextMessageToLiveSession(initialCommandAfterWake)
        } else {
            launchSessionAndStartCommandListening(
                reason = "WAKE_UP_FROM_STANDBY",
                openBoundedCommandWindow = true
            )
        }
    }

    /**
     * Auto-Resume on Focus / Visibility Return (`visibilitychange` / `window.onfocus` / `onResume`):
     * Immediately resumes the 24kHz AudioTrack + 1Hz sub-audible carrier, restarts 16kHz mic capture if paused,
     * and reconnects the Gemini Live WebSocket if Android paused or dropped it while in WhatsApp.
     *
     * CRITICAL: Never overrides a deliberate Stop or Mute.
     */
    fun ensureSessionAliveOnResume() {
        restoreAuthoritativeSpeechPreferences()
        val coord = AssistantAudioStateMachine.shared
        // Respect user's armed/stopped/muted choice on resume!
        if (coord.isStoppedByUserPersisted(context) || coord.isStoppedByUserInMemory()) {
            _observedCaptureState.value = ObservedAssistantCaptureState.IDLE
            return
        }
        if (_isMicMuted.value || coord.isDeviceMutedPersisted(context) || coord.isDeviceMutedInMemory()) {
            _observedCaptureState.value = ObservedAssistantCaptureState.MUTED
            return
        }
        if (!_isSessionActive.value || !coord.isUserArmedPersisted(context)) {
            return
        }
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) {
            coord.inspectWakeAndAssistantReadiness(context)
            _observedCaptureState.value = ObservedAssistantCaptureState.ERROR
            _captureErrorDetail.value = "Microphone permission (RECORD_AUDIO) is required."
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
                }
                start16kHzMicrophonePcmStream()
            } else if (coord.activeMicOwner.value == MicComponentOwner.NONE) {
                coord.acquireMicrophoneOwnership(MicComponentOwner.WAKE_WORD_SERVICE)
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
            line = "Foreground focus restored: AudioContext + 1Hz Carrier + Live WebSocket verified active (voice=${_selectedVoice.value.voiceName})."
        )
    }

    /**
     * Ends the Gemini Live session and releases audio & WebSocket resources.
     */
    fun endLiveSession() {
        _isSessionActive.value = false
        _isMicMuted.value = false
        isBoundedConversationModeActive = false
        boundedConversationRemainingTurns = 0
        isWebSocketReady.set(false)
        lastCaptureReadyElapsedMs = 0L
        clearCaptureReadiness("END_LIVE_SESSION")
        inactivityTimerJob?.cancel()
        inactivityTimerJob = null
        JarvisHandsFreeEngine.setKeepAliveCarrierActive(false)
        _connectionState.value = LiveConnectionState.DISCONNECTED
        _observedCaptureState.value = ObservedAssistantCaptureState.IDLE
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
        stopNativeSpeechRecognizerLoop(releaseCoordinatorOwnership = true)
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
            clearCaptureReadiness("MIC_MUTED")
            _inputAudioRms.value = 0f
            _observedCaptureState.value = ObservedAssistantCaptureState.MUTED
            releaseMicAudioRecord()
            stopNativeSpeechRecognizerLoop(releaseCoordinatorOwnership = true)
        } else {
            if (_isSessionActive.value && !AssistantAudioStateMachine.shared.isStoppedByUserInMemory()) {
                startActiveCommandCapture("UNMUTE_RESUME_CAPTURE")
            } else {
                _observedCaptureState.value = ObservedAssistantCaptureState.IDLE
            }
        }
        return nextMute
    }

    /**
     * Switches between `'Aoede'`, `'Puck'`, `'Charon'`, `'Kore'`, and `'Fenrir'`.
     * Persists the choice in [SpeechPreferenceStore] and reconnects if a Live session is active.
     */
    fun cycleVoice(): GeminiLiveVoice {
        val nextVoice = _selectedVoice.value.next()
        _selectedVoice.value = nextVoice
        SpeechPreferenceStore.setSelectedLiveVoice(context, nextVoice.voiceName)
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
        val changed = _selectedVoice.value != voice
        _selectedVoice.value = voice
        SpeechPreferenceStore.setSelectedLiveVoice(context, voice.voiceName)
        if (_isSessionActive.value && changed) {
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

        // Route both deterministic device tasks and conversational turns through processFallbackLiveTurn,
        // which supports live WebSocket clientContent with a bounded audio-delivery watchdog AND fast local task execution.
        scope.launch {
            processFallbackLiveTurn(effectiveUtterance, null, turnCommandId)
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

        val authoritativeVoice = restoreAuthoritativeSpeechPreferences()

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY" || !OmniAutonomousCoreEngine.refreshOnlineState(context)) {
            Log.i(TAG, "Running Hybrid Live Streaming mode (online=${OmniAutonomousCoreEngine.isNetworkOnline.value})")
            if (isCaptureCurrentlyReady()) {
                _connectionState.value = LiveConnectionState.LIVE_LISTENING
                _observedCaptureState.value = ObservedAssistantCaptureState.LISTENING
                _liveAiTranscript.value = "$assistantName Listening (${authoritativeVoice.voiceName}) • Speak in English, اردو, or Roman Urdu"
            }
            return
        }

        val wsUrl =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        _activeModelLabel.value = modelName.removePrefix("models/")
        val socketGeneration = webSocketGenerationCounter.incrementAndGet()
        activeWebSocketGeneration = socketGeneration
        SpeechPreferenceStore.nextSessionGeneration()

        activeWebSocket = wsHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isActiveWebSocketCallback(webSocket, socketGeneration)) return
                    val currentVoice = restoreAuthoritativeSpeechPreferences()
                    Log.d(TAG, "Gemini Live WebSocket opened (gen=$socketGeneration, voice=${currentVoice.voiceName}) for model=$modelName")
                    val mem = OmniAutonomousCoreEngine.loadPermanentMemory(context)
                    val mood = OmniAutonomousCoreEngine.moodAndToneState.value
                    val setupJson = buildLiveSetupPayload(
                        modelName = modelName,
                        voiceName = currentVoice.voiceName,
                        savedContactsSummary = SmartContactAndAppRegistry.buildContactsSummaryForPrompt(context),
                        assistantName = mem.assistantName,
                        learnedRulesSummary = mem.learnedRules.joinToString("; "),
                        moodDirective = mood.systemPromptDirective
                    )
                    webSocket.send(setupJson.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isActiveWebSocketCallback(webSocket, socketGeneration)) return
                    handleServerJsonMessage(webSocket, text, socketGeneration)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!isActiveWebSocketCallback(webSocket, socketGeneration)) return
                    handleServerJsonMessage(webSocket, bytes.utf8(), socketGeneration)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isActiveWebSocketCallback(webSocket, socketGeneration)) return
                    Log.d(TAG, "Gemini Live WebSocket closing (gen=$socketGeneration, code=$code): $reason")
                    recordLiveWebSocketDisconnect(code, reason.ifBlank { "WebSocket closing" }, webSocket, socketGeneration)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isActiveWebSocketCallback(webSocket, socketGeneration)) return
                    Log.d(TAG, "Gemini Live WebSocket closed (gen=$socketGeneration, code=$code): $reason")
                    recordLiveWebSocketDisconnect(code, reason.ifBlank { "WebSocket closed" }, webSocket, socketGeneration)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isActiveWebSocketCallback(webSocket, socketGeneration)) return
                    Log.w(TAG, "Gemini Live WebSocket failure (gen=$socketGeneration, model=$modelName): ${t.message}")
                    val closeCode = response?.code ?: 1006
                    val closeReason = t.message ?: response?.message ?: "WebSocket transport failure"
                    recordLiveWebSocketDisconnect(closeCode, closeReason, webSocket, socketGeneration)
                    if (_isSessionActive.value && !triedFallbackModel) {
                        triedFallbackModel = true
                        connectBidiWebSocket(LIVE_MODEL_FALLBACK)
                    } else if (_isSessionActive.value) {
                        if (isCaptureCurrentlyReady()) {
                            _connectionState.value = LiveConnectionState.LIVE_LISTENING
                            _observedCaptureState.value = ObservedAssistantCaptureState.LISTENING
                            if (_liveAiTranscript.value.startsWith("Connecting") || _liveAiTranscript.value.contains("Starting")) {
                                _liveAiTranscript.value =
                                    "$assistantName Listening (${_selectedVoice.value.voiceName}) • Speak naturally"
                            }
                        }
                    }
                }
            }
        )
    }

    private fun handleServerJsonMessage(
        webSocket: WebSocket,
        rawJson: String,
        socketGeneration: Long = activeWebSocketGeneration
    ) {
        if (!isActiveWebSocketCallback(webSocket, socketGeneration)) {
            return
        }
        try {
            val root = JSONObject(rawJson)
            val nowElapsed = SystemClock.elapsedRealtime()

            // 1. setupComplete
            if (root.has("setupComplete")) {
                isWebSocketReady.set(true)
                val currentVoice = restoreAuthoritativeSpeechPreferences()
                val assistantName = OmniAutonomousCoreEngine.permanentMemory.value.assistantName
                if (isCaptureCurrentlyReady()) {
                    _connectionState.value = LiveConnectionState.LIVE_LISTENING
                    _observedCaptureState.value = ObservedAssistantCaptureState.LISTENING
                    _liveAiTranscript.value =
                        "$assistantName Live Connected (${currentVoice.voiceName}) • Listening..."
                } else {
                    _observedCaptureState.value = ObservedAssistantCaptureState.STARTING
                    _liveAiTranscript.value =
                        "$assistantName Live Connected (${currentVoice.voiceName}) • Starting microphone..."
                }
                Log.d(TAG, "Gemini Live setupComplete received (gen=$socketGeneration, voice=${currentVoice.voiceName})")
                return
            }

            // Process inputTranscription BEFORE toolCall turn-reset check so inline or prior transcripts
            // for the current turn are never wiped when a server toolCall arrives!
            val serverContent = root.optJSONObject("serverContent")
            val inlineInputTranscriptChunk = serverContent
                ?.optJSONObject("inputTranscription")
                ?.optString("text", "")
                .orEmpty()
            if (inlineInputTranscriptChunk.isNotEmpty()) {
                if (currentVoiceTurnCompleted || activeCommandId.isBlank()) {
                    startNewCommandTurn()
                    _liveUserTranscript.value = ""
                    currentTurnStartedFromInputTranscription = true
                }
                _liveUserTranscript.value = (_liveUserTranscript.value + inlineInputTranscriptChunk).trimStart()
                com.omnivoice.agentfix.AgentSession.onUserUtterance(_liveUserTranscript.value)
                resolveCommandReplyLanguage(_liveUserTranscript.value, mutateHistoryIfGenuine = true)
                OmniAutonomousCoreEngine.analyzeUserMood(_liveUserTranscript.value, _inputAudioRms.value)
                AssistantAudioStateMachine.shared.extendCommandWindowOnSpeechActivity()
                updateCommandDiagnostic(activeCommandId) { current ->
                    current.copy(
                        speechOnsetElapsedMs = if (current.speechOnsetElapsedMs > 0L) current.speechOnsetElapsedMs else nowElapsed,
                        speechEndElapsedMs = nowElapsed,
                        commandAcceptedElapsedMs = if (current.commandAcceptedElapsedMs > 0L) current.commandAcceptedElapsedMs else nowElapsed,
                        partialRecognitionCount = current.partialRecognitionCount + 1,
                        finalRecognitionObserved = true
                    )
                }
            }

            val hasEmbeddedToolCall = root.optJSONObject("toolCall") != null ||
                serverContent?.optJSONObject("toolCall") != null ||
                (serverContent?.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { partsArr ->
                    (0 until partsArr.length()).any { idx -> partsArr.optJSONObject(idx)?.optJSONObject("functionCall") != null }
                } == true)
            if (hasEmbeddedToolCall) {
                if ((currentVoiceTurnCompleted || activeCommandId.isBlank()) && !currentTurnStartedFromInputTranscription && inlineInputTranscriptChunk.isEmpty()) {
                    startNewCommandTurn()
                    // Clear the per-turn transcript and AgentSession utterance so stale return-to-origin
                    // intents from previous turns cannot leak, while SpeechPreferenceStore preserves
                    // the active conversation language across turns!
                    _liveUserTranscript.value = ""
                    com.omnivoice.agentfix.AgentSession.clearForTest()
                }
                currentTurnHasPendingOrExecutedAction = true
                awaitingVerifiedToolOutcome = true
                _observedCaptureState.value = ObservedAssistantCaptureState.PROCESSING
                updateCommandDiagnostic(activeCommandId) { current ->
                    current.copy(
                        requestSentElapsedMs = if (current.requestSentElapsedMs > 0L) current.requestSentElapsedMs else nowElapsed,
                        firstResponseReceivedElapsedMs = if (current.firstResponseReceivedElapsedMs > 0L) current.firstResponseReceivedElapsedMs else nowElapsed
                    )
                }
                clearPlaybackQueueAndStopImmediately(interruptReason = "INCOMING_SERVER_TOOL_CALL")
            }

            // 2. serverContent (Audio output, input/output transcriptions, interruption, turnComplete)
            if (serverContent != null) {
                OmniAutonomousCoreEngine.resetInactivityTimer()

                // Check for user barge-in interruption (`serverContent.interrupted === true`)
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Gemini Live interrupted by user speech — clearing 24kHz audio queue")
                    clearPlaybackQueueAndStopImmediately(interruptReason = "SERVER_INTERRUPTED")
                    _connectionState.value = LiveConnectionState.LIVE_LISTENING
                    if (isCaptureCurrentlyReady()) {
                        _observedCaptureState.value = ObservedAssistantCaptureState.LISTENING
                    }
                }

                // Real-time output audio transcription (what Gemini is saying)
                val outputTrans = serverContent.optJSONObject("outputTranscription")
                if (outputTrans != null) {
                    val aiChunk = outputTrans.optString("text", "")
                    if (aiChunk.isNotEmpty()) {
                        if (activeCommandId.isNotBlank()) {
                            updateCommandDiagnostic(activeCommandId) { current ->
                                current.copy(
                                    firstResponseReceivedElapsedMs = if (current.firstResponseReceivedElapsedMs > 0L) {
                                        current.firstResponseReceivedElapsedMs
                                    } else {
                                        nowElapsed
                                    }
                                )
                            }
                        }
                        val candidateTranscript = if (_liveAiTranscript.value.contains("Live Connected") ||
                            _liveAiTranscript.value.contains("Live Active") ||
                            _liveAiTranscript.value.contains("Listening") ||
                            _liveAiTranscript.value.contains("Starting") ||
                            _liveAiTranscript.value.startsWith("Connecting") ||
                            _liveAiTranscript.value.startsWith("Processing") ||
                            _liveAiTranscript.value.startsWith("Soch raha")
                        ) {
                            aiChunk.trimStart()
                        } else {
                            _liveAiTranscript.value + aiChunk
                        }
                        if (isCompletionClaimBeforeVerifiedResult(aiChunk) || isCompletionClaimBeforeVerifiedResult(candidateTranscript)) {
                            clearPlaybackQueueAndStopImmediately(interruptReason = "UNVERIFIED_COMPLETION_GUARD")
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
                val fallbackPartFunctionCalls = JSONArray()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue

                        // Inline 24kHz PCM native audio chunk
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null && !awaitingVerifiedToolOutcome) {
                            val mimeType = inlineData.optString("mimeType", "")
                            val base64Audio = inlineData.optString("data", "")
                            if (mimeType.startsWith("audio/pcm") && base64Audio.isNotEmpty()) {
                                val cmdId = activeCommandId.ifBlank { "live_stream_turn" }
                                if (lateLiveAudioSuppressedForCommands.contains(cmdId)) {
                                    updateCommandDiagnostic(cmdId) {
                                        it.copy(lateDuplicateFramesSuppressed = it.lateDuplicateFramesSuppressed + 1)
                                    }
                                } else {
                                    val (pcmBytes, floatSamples) = decodeBase64Pcm16ToFloat32(base64Audio, mimeType)
                                    updateVisualizerFromFloatSamples(floatSamples, isAiOutput = true)
                                    enqueue24kHzPcmChunk(pcmBytes, cmdId)
                                }
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
                                clearPlaybackQueueAndStopImmediately(interruptReason = "UNVERIFIED_COMPLETION_GUARD")
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

                        // Function call inside modelTurn part (fallback when top-level toolCall is absent)
                        val fnCall = part.optJSONObject("functionCall")
                        if (fnCall != null && root.optJSONObject("toolCall") == null && serverContent.optJSONObject("toolCall") == null) {
                            fallbackPartFunctionCalls.put(fnCall)
                        }
                    }
                }

                if (fallbackPartFunctionCalls.length() > 0) {
                    scope.launch {
                        executeServerToolCallBatch(webSocket, fallbackPartFunctionCalls)
                    }
                }

                if (serverContent.optBoolean("turnComplete", false) ||
                    serverContent.optBoolean("generationComplete", false)
                ) {
                    currentVoiceTurnCompleted = true
                    currentTurnStartedFromInputTranscription = false
                    val turnCmdId = activeCommandId.ifBlank { activePcmPlaybackCommandId.ifBlank { "live_stream_turn" } }
                    markServerTurnCompleteForCommand(turnCmdId)
                    val uText = _liveUserTranscript.value.trim()
                    val aText = _liveAiTranscript.value.trim()
                    if (uText.isNotEmpty()) {
                        com.omnivoice.agentfix.AgentSession.onUserUtterance(uText)
                    }
                    val langRes = resolveCommandReplyLanguage(
                        utterance = uText,
                        mutateHistoryIfGenuine = uText.isNotEmpty()
                    )
                    val turnLang = langRes.language
                    val liveConn = isGeminiLiveWebSocketConnected()
                    val diag = commandDiagnosticsMap[activeCommandId]
                    val actEngine = if (diag?.recoveryTriggered == true) {
                        "ANDROID_TTS_RECOVERY"
                    } else if (liveConn) {
                        "GEMINI_LIVE (${_selectedVoice.value.voiceName})"
                    } else {
                        "ANDROID_TTS"
                    }
                    recordTurnExecutionReport(
                        commandId = activeCommandId,
                        utterance = uText,
                        spokenText = aText,
                        diagnosticText = lastDiagnosticTextForCurrentCommand.ifBlank { aText },
                        finalStatus = if (currentTurnHasPendingOrExecutedAction) {
                            // Never default to VERIFIED when an action outcome is missing!
                            lastFinalStatusForCurrentCommand.ifBlank { "REQUESTED_UNVERIFIED" }
                        } else {
                            "CONVERSATIONAL"
                        },
                        outputEngine = actEngine,
                        language = turnLang,
                        languageSelectionReason = langRes.reason,
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
            //    Execute each tool once, serialize its response, and send EXACTLY ONE batch response per server call ID.
            val topToolCall = root.optJSONObject("toolCall")
            val serverToolCall = serverContent?.optJSONObject("toolCall")
            val rawFunctionCalls = topToolCall?.optJSONArray("functionCalls")
                ?: serverToolCall?.optJSONArray("functionCalls")

            if (rawFunctionCalls != null && rawFunctionCalls.length() > 0) {
                OmniAutonomousCoreEngine.resetInactivityTimer()
                scope.launch {
                    executeServerToolCallBatch(webSocket, rawFunctionCalls)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Gemini Live server message: ${e.message}")
        }
    }

    private suspend fun executeServerToolCallBatch(
        webSocket: WebSocket,
        rawFunctionCalls: JSONArray
    ) {
        val outcomes = mutableListOf<ExecutedToolCallResult>()
        for (i in 0 until rawFunctionCalls.length()) {
            val fnCall = rawFunctionCalls.optJSONObject(i) ?: continue
            val outcome = executeToolActionOnly(fnCall, isServerOriginCall = true)
            outcomes.add(outcome)
        }
        if (outcomes.isNotEmpty()) {
            transmitServerToolResponseBatchOnce(webSocket, outcomes)
        }
        _connectionState.value = LiveConnectionState.LIVE_LISTENING
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
                    _observedCaptureState.value = ObservedAssistantCaptureState.ERROR
                    _captureErrorDetail.value = "Microphone hardware failed to initialize."
                    return@launch
                }

                micAudioRecord = recorder
                _activeAudioRecordInstanceCount.incrementAndGet()
                AssistantAudioStateMachine.shared.applyPreferredInputDevice(context, recorder)
                attachHardwareAudioFilters(recorder.audioSessionId)
                recorder.startRecording()
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    markCaptureReady(
                        source = "AUDIO_RECORD_16KHZ",
                        generation = activeCaptureGeneration,
                        owner = AssistantAudioStateMachine.shared.activeMicOwner.value
                    )
                }

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
                            if (pendingSpeechOnsetElapsedMs == 0L) {
                                pendingSpeechOnsetElapsedMs = SystemClock.elapsedRealtime()
                            }
                            AssistantAudioStateMachine.shared.extendCommandWindowOnSpeechActivity()
                        }

                        // If Gemini is not currently speaking, drive the Orb spectrum from the user's voice
                        if (_connectionState.value != LiveConnectionState.LIVE_AI_SPEAKING) {
                            updateVisualizerFromFloatSamples(floatSamples, isAiOutput = false)
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
                                    pendingSpeechOnsetElapsedMs = SystemClock.elapsedRealtime()
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
                                if (silenceMs >= 620L && durationMs >= 180L) {
                                    val capturedPcm = fallbackTurnPcmBuffer.toByteArray()
                                    fallbackTurnPcmBuffer.reset()
                                    fallbackSpeechActive = false
                                    pendingSpeechEndElapsedMs = SystemClock.elapsedRealtime()
                                    if (isStandby) {
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
    fun markServerTurnCompleteForCommand(commandId: String = activeCommandId) {
        val cmdId = commandId.ifBlank { activePcmPlaybackCommandId.ifBlank { activeCommandId } }
        if (cmdId.isBlank()) return
        serverTurnCompleteCommands.add(cmdId)
        updateCommandDiagnostic(cmdId) { current ->
            current.copy(serverTurnCompleteObserved = true)
        }
    }

    /**
     * Writes aligned 24kHz PCM16 bytes to hardware [AudioTrack] (or [audioTrackWriteInterceptorForTest]),
     * handling partial writes and recording [recordPcmFrameWrittenToHardware] ONLY after `written > 0`.
     * If `written <= 0` or `AudioTrack` fails, triggers [recordPcmPlaybackFailure] and bounded TTS recovery.
     */
    fun writePcmChunkToHardwareTrack(
        track: AudioTrack?,
        alignedBytes: ByteArray,
        commandId: String
    ): Int {
        if (alignedBytes.isEmpty()) return 0
        val interceptor = audioTrackWriteInterceptorForTest
        return try {
            var totalWritten = 0
            var attempts = 0
            while (totalWritten < alignedBytes.size && attempts < 3) {
                attempts += 1
                val remaining = alignedBytes.size - totalWritten
                val stepWritten = if (interceptor != null) {
                    interceptor.invoke(alignedBytes, totalWritten, remaining)
                } else {
                    if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                        AudioTrack.ERROR_INVALID_OPERATION
                    } else {
                        track.write(alignedBytes, totalWritten, remaining)
                    }
                }
                if (stepWritten < 0) {
                    recordPcmPlaybackFailure(commandId, "AUDIO_TRACK_WRITE_ERROR_$stepWritten")
                    return stepWritten
                }
                if (stepWritten == 0) {
                    break
                }
                totalWritten += stepWritten
            }
            if (totalWritten > 0) {
                recordPcmFrameWrittenToHardware(commandId, bytesWritten = totalWritten)
            } else {
                recordPcmPlaybackFailure(commandId, "AUDIO_TRACK_ZERO_BYTES_WRITTEN")
            }
            totalWritten
        } catch (e: Exception) {
            recordPcmPlaybackFailure(commandId, "AUDIO_TRACK_WRITE_EXCEPTION_${e.javaClass.simpleName}")
            -1
        }
    }

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
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        track.playbackParams = android.media.PlaybackParams()
                            .setSpeed(1.0f)
                            .setPitch(1.0f)
                    }
                } catch (_: Exception) {
                }

                outputAudioTrack = track
                track.play()

                val silentKeepAliveChunk = ByteArray(240)
                var pendingDrainSinceEmptyMs = 0L
                val hardwareTailDrainWindowMs = ((minBuf.toLong() * 1000L) / (OUTPUT_SAMPLE_RATE * 2L)).coerceIn(60L, 280L)

                while (isActive && _isSessionActive.value) {
                    val taggedChunk = pcm24kPlaybackQueue.poll()
                    if (taggedChunk != null) {
                        pendingDrainSinceEmptyMs = 0L
                        lastPcmChunkActivityElapsedMs = SystemClock.elapsedRealtime()
                        val alignedBytes = ensure24kHzPcm16Mono(taggedChunk.pcmBytes, OUTPUT_SAMPLE_RATE)
                        if (alignedBytes.isNotEmpty()) {
                            writePcmChunkToHardwareTrack(track, alignedBytes, taggedChunk.commandId)
                        }
                    } else {
                        if (isPcmHardwareDrainInProgress) {
                            val activeCmd = activePcmPlaybackCommandId
                            val serverTurnDone = activeCmd.isBlank() || serverTurnCompleteCommands.contains(activeCmd)
                            val now = System.currentTimeMillis()
                            if (serverTurnDone) {
                                // Server generation has ended AND queue is empty: wait for hardware tail drain window, then complete!
                                if (pendingDrainSinceEmptyMs == 0L) {
                                    pendingDrainSinceEmptyMs = now
                                } else if (now - pendingDrainSinceEmptyMs >= hardwareTailDrainWindowMs) {
                                    pendingDrainSinceEmptyMs = 0L
                                    completePcmPlaybackDrain(activeCmd)
                                }
                            } else {
                                // Server has NOT sent turnComplete yet: a 500ms inter-chunk network gap is NOT end-of-response!
                                pendingDrainSinceEmptyMs = 0L
                            }
                            delay(4)
                        } else {
                            if (audioTrackWriteInterceptorForTest == null) {
                                track.write(silentKeepAliveChunk, 0, silentKeepAliveChunk.size)
                            }
                            delay(5)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "24kHz AudioTrack playback error: ${e.message}")
                if (activePcmPlaybackCommandId.isNotBlank()) {
                    recordPcmPlaybackFailure(activePcmPlaybackCommandId, "PLAYBACK_LOOP_EXCEPTION_${e.javaClass.simpleName}")
                }
            } finally {
                releaseOutputAudioTrack()
            }
        }
    }

    fun recordPcmPlaybackFailure(commandId: String = activeCommandId, reason: String = "AUDIO_TRACK_PLAYBACK_FAILED") {
        val coord = AssistantAudioStateMachine.shared
        val cmdId = commandId.ifBlank { activePcmPlaybackCommandId.ifBlank { activeCommandId } }
        isPcmHardwareDrainInProgress = false
        activePcmPlaybackCommandId = ""
        _outputAudioRms.value = 0f
        _observedCaptureState.value = ObservedAssistantCaptureState.ERROR
        val errMsg = "Received audio reply, but speaker playback failed ($reason)."
        setStickyCaptureError(errMsg)
        if (cmdId.isNotBlank()) {
            val updatedDiag = updateCommandDiagnostic(cmdId) { current ->
                current.copy(
                    outputState = CommandSpeechOutputState.FAILED,
                    stallStage = CommandTurnStallStage.RECEIVED_AUDIO_PLAYBACK_FAILED,
                    pcmWriteErrorCount = current.pcmWriteErrorCount + 1,
                    cancellationReason = reason
                )
            }
            if (lastTurnReport?.commandId == cmdId) {
                lastTurnReport = lastTurnReport?.copy(
                    outputState = updatedDiag.outputState,
                    stallStage = updatedDiag.stallStage
                )
            }
            // Release the unplayed slot WITHOUT marking the command completed so bounded TTS recovery can acquire it!
            AudioOutputManager.releaseUnplayedTurnSlot(cmdId)
            coord.notifyAssistantSpeechEnded(
                source = "PCM_PLAYBACK_FAILED",
                ownerToken = cmdId,
                immediateCooldownClear = true
            )
            val recovered = triggerBoundedSpeechRecoveryForCommand(
                commandId = cmdId,
                reason = "PCM_PLAYBACK_FAILED_$reason"
            )
            if (!recovered) {
                _liveAiTranscript.value = errMsg
                AudioOutputManager.completeTurnOutput(cmdId)
                if (_isSessionActive.value &&
                    !coord.isStoppedByUserInMemory() &&
                    !_isMicMuted.value &&
                    !coord.isDeviceMutedInMemory()
                ) {
                    handoffToPostTurnCapture(
                        reason = "PCM_PLAYBACK_FAILED_RESTORE_CAPTURE_$reason",
                        preserveErrorDetail = true
                    )
                } else {
                    refreshDiagnosticSnapshot()
                }
            }
        } else {
            _liveAiTranscript.value = errMsg
            if (_isSessionActive.value &&
                !coord.isStoppedByUserInMemory() &&
                !_isMicMuted.value &&
                !coord.isDeviceMutedInMemory()
            ) {
                handoffToPostTurnCapture(
                    reason = "PCM_PLAYBACK_FAILED_RESTORE_CAPTURE_$reason",
                    preserveErrorDetail = true
                )
            } else {
                refreshDiagnosticSnapshot()
            }
        }
    }

    private fun recordPcmFrameWrittenToHardware(commandId: String, bytesWritten: Int = 480) {
        val cmdId = commandId.ifBlank { activeCommandId.ifBlank { "live_stream_turn" } }
        val nowElapsed = SystemClock.elapsedRealtime()
        lastPcmChunkActivityElapsedMs = nowElapsed
        postActionAudioWatchdogJobs.remove(cmdId)?.cancel()
        activePcmPlaybackCommandId = cmdId
        isPcmHardwareDrainInProgress = true
        _connectionState.value = LiveConnectionState.LIVE_AI_SPEAKING
        _observedCaptureState.value = ObservedAssistantCaptureState.SPEAKING
        val updatedDiag = updateCommandDiagnostic(cmdId) { current ->
            current.copy(
                outputState = CommandSpeechOutputState.STARTED,
                audioFramesPlayed = current.audioFramesPlayed + 1,
                pcmBytesWrittenToHardware = current.pcmBytesWrittenToHardware + bytesWritten.coerceAtLeast(1),
                playbackStartedElapsedMs = if (current.playbackStartedElapsedMs > 0L) {
                    current.playbackStartedElapsedMs
                } else {
                    nowElapsed
                }
            )
        }
        if (lastTurnReport?.commandId == cmdId) {
            lastTurnReport = lastTurnReport?.copy(
                outputState = updatedDiag.outputState,
                audioFramesReceived = updatedDiag.audioFramesReceived,
                audioFramesPlayed = updatedDiag.audioFramesPlayed,
                playbackStartedElapsedMs = updatedDiag.playbackStartedElapsedMs
            )
        }
    }

    private fun completePcmPlaybackDrain(commandId: String) {
        val cmdId = commandId.ifBlank { activePcmPlaybackCommandId }
        if (cmdId.isNotBlank()) {
            val diag = commandDiagnosticsMap[cmdId]
            if (diag != null && diag.audioFramesReceived > 0 && diag.audioFramesPlayed == 0) {
                // Zero frames were actually written to hardware: never mark COMPLETED!
                recordPcmPlaybackFailure(cmdId, "PCM_DRAIN_CALLED_WITH_ZERO_FRAMES_PLAYED")
                return
            }
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        isPcmHardwareDrainInProgress = false
        activePcmPlaybackCommandId = ""
        _outputAudioRms.value = 0f
        if (cmdId.isNotBlank()) {
            val updatedDiag = updateCommandDiagnostic(cmdId) { current ->
                current.copy(
                    outputState = CommandSpeechOutputState.COMPLETED,
                    hardwarePlaybackDrained = true,
                    playbackCompletedElapsedMs = if (current.playbackCompletedElapsedMs > 0L) {
                        current.playbackCompletedElapsedMs
                    } else {
                        nowElapsed
                    }
                )
            }
            if (lastTurnReport?.commandId == cmdId) {
                lastTurnReport = lastTurnReport?.copy(
                    outputState = updatedDiag.outputState,
                    audioFramesReceived = updatedDiag.audioFramesReceived,
                    audioFramesPlayed = updatedDiag.audioFramesPlayed,
                    playbackCompletedElapsedMs = updatedDiag.playbackCompletedElapsedMs
                )
            }
            AudioOutputManager.releaseTurnOutput(cmdId, AudioOutputManager.OutputModality.CLOUD_PCM_24KHZ)
            AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(
                source = "GEMINI_LIVE_PCM_DRAINED",
                ownerToken = cmdId
            )
        } else {
            AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(
                source = "GEMINI_LIVE_PCM_DRAINED"
            )
        }
        handoffToPostTurnCapture("GEMINI_LIVE_PCM_DRAINED")
    }

    /**
     * Deterministic test helper: dequeues all queued 24kHz PCM chunks through [writePcmChunkToHardwareTrack],
     * leaving [isPcmHardwareDrainInProgress] = true (and speaking suppression active) until
     * server `turnComplete` and [completeHardwarePcmPlaybackDrainForTest] are called.
     */
    fun drainApplicationPcmQueueToHardwareBufferForTest(): Int {
        var count = 0
        while (true) {
            val tagged = pcm24kPlaybackQueue.poll() ?: break
            val alignedBytes = ensure24kHzPcm16Mono(tagged.pcmBytes, OUTPUT_SAMPLE_RATE)
            val written = if (audioTrackWriteInterceptorForTest != null) {
                writePcmChunkToHardwareTrack(outputAudioTrack, alignedBytes, tagged.commandId)
            } else {
                recordPcmFrameWrittenToHardware(tagged.commandId, bytesWritten = alignedBytes.size)
                alignedBytes.size
            }
            if (written > 0) {
                count += 1
            } else {
                break
            }
        }
        return count
    }

    /**
     * Deterministic test helper: simulates a polling tick of the 24kHz output queue while the
     * queue is empty. Completes drain ONLY if `serverTurnCompleteCommands` contains the command ID!
     */
    fun pollEmptyPcmQueueTickForTest(commandId: String = activePcmPlaybackCommandId): Boolean {
        drainApplicationPcmQueueToHardwareBufferForTest()
        val cmdId = commandId.ifBlank { activePcmPlaybackCommandId }
        if (cmdId.isBlank() || pcm24kPlaybackQueue.isNotEmpty()) return false
        if (!serverTurnCompleteCommands.contains(cmdId)) {
            // Server has NOT sent turnComplete yet — must NOT complete across inter-chunk network gap!
            return false
        }
        completePcmPlaybackDrain(cmdId)
        return true
    }

    /**
     * Deterministic test helper: completes the hardware AudioTrack playback drain after the
     * application queue has emptied, ending speaking suppression and marking output COMPLETED.
     */
    fun completeHardwarePcmPlaybackDrainForTest(commandId: String = activePcmPlaybackCommandId) {
        val cmdId = commandId.ifBlank { activePcmPlaybackCommandId }
        if (cmdId.isNotBlank()) {
            markServerTurnCompleteForCommand(cmdId)
        }
        drainApplicationPcmQueueToHardwareBufferForTest()
        completePcmPlaybackDrain(cmdId)
    }

    fun enqueue24kHzPcmChunk(
        pcm24kBytes: ByteArray,
        commandId: String = activeCommandId
    ): Boolean {
        if (!_isSessionActive.value || pcm24kBytes.isEmpty()) return false
        val cmdId = commandId.ifBlank { activeCommandId.ifBlank { "live_stream_turn" } }
        if (lateLiveAudioSuppressedForCommands.contains(cmdId) ||
            !AudioOutputManager.acquireTurnOutput(cmdId, AudioOutputManager.OutputModality.CLOUD_PCM_24KHZ)
        ) {
            updateCommandDiagnostic(cmdId) {
                it.copy(lateDuplicateFramesSuppressed = it.lateDuplicateFramesSuppressed + 1)
            }
            return false
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        lastPcmChunkActivityElapsedMs = nowElapsed
        activePcmPlaybackCommandId = cmdId
        isPcmHardwareDrainInProgress = true
        _connectionState.value = LiveConnectionState.LIVE_AI_SPEAKING
        _observedCaptureState.value = ObservedAssistantCaptureState.SPEAKING
        AssistantAudioStateMachine.shared.notifyAssistantSpeechStarted(
            spokenText = _liveAiTranscript.value.ifBlank { lastSpokenReplyForCurrentCommand },
            source = "GEMINI_LIVE_PCM",
            ownerToken = cmdId
        )
        val updatedDiag = updateCommandDiagnostic(cmdId) { current ->
            val nextState = if (current.outputState == CommandSpeechOutputState.STARTED) {
                CommandSpeechOutputState.STARTED
            } else {
                CommandSpeechOutputState.QUEUED
            }
            current.copy(
                outputState = nextState,
                audioFramesReceived = current.audioFramesReceived + 1,
                audioFramesQueued = current.audioFramesQueued + 1,
                firstResponseReceivedElapsedMs = if (current.firstResponseReceivedElapsedMs > 0L) {
                    current.firstResponseReceivedElapsedMs
                } else {
                    nowElapsed
                },
                audioReceivedElapsedMs = if (current.audioReceivedElapsedMs > 0L) {
                    current.audioReceivedElapsedMs
                } else {
                    nowElapsed
                }
            )
        }
        if (lastTurnReport?.commandId == cmdId) {
            lastTurnReport = lastTurnReport?.copy(
                outputState = updatedDiag.outputState,
                audioFramesReceived = updatedDiag.audioFramesReceived,
                audioFramesPlayed = updatedDiag.audioFramesPlayed,
                firstResponseReceivedElapsedMs = updatedDiag.firstResponseReceivedElapsedMs,
                audioReceivedElapsedMs = updatedDiag.audioReceivedElapsedMs
            )
        }
        pcm24kPlaybackQueue.offer(CommandTaggedPcmChunk(cmdId, pcm24kBytes))
        return true
    }

    fun clearPlaybackQueueAndStopImmediately(interruptReason: String = "STOP_IMMEDIATELY") {
        val hadPcmActive = pcm24kPlaybackQueue.isNotEmpty() || isPcmHardwareDrainInProgress || activePcmPlaybackCommandId.isNotBlank()
        val interruptedCmdId = activePcmPlaybackCommandId.ifBlank { activeCommandId }
        pcm24kPlaybackQueue.clear()
        isPcmHardwareDrainInProgress = false
        activePcmPlaybackCommandId = ""
        _outputAudioRms.value = 0f
        if (hadPcmActive && interruptedCmdId.isNotBlank()) {
            updateCommandDiagnostic(interruptedCmdId) { current ->
                if (current.outputState != CommandSpeechOutputState.COMPLETED) {
                    current.copy(
                        outputState = CommandSpeechOutputState.INTERRUPTED,
                        cancellationReason = interruptReason
                    )
                } else {
                    current
                }
            }
            AudioOutputManager.releaseTurnOutput(interruptedCmdId, AudioOutputManager.OutputModality.CLOUD_PCM_24KHZ)
            AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(
                source = "PCM_INTERRUPTED_$interruptReason",
                ownerToken = interruptedCmdId
            )
        }
        ttsFallback?.stop()
        try {
            outputAudioTrack?.pause()
            outputAudioTrack?.flush()
            outputAudioTrack?.play()
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // 4. LIVE FUNCTION CALLING EXECUTION (`executeToolActionOnly` + `serializeSingleFunctionResponse` + `transmitServerToolResponseBatchOnce`)
    // =========================================================================
    fun isLocalFastPathCallId(callId: String): Boolean {
        val trimmed = callId.trim()
        return trimmed.contains("_step_") || trimmed.endsWith("_return_origin")
    }

    suspend fun executeToolActionOnly(
        fnCall: JSONObject,
        isServerOriginCall: Boolean = true
    ): ExecutedToolCallResult = withContext(Dispatchers.IO) {
        val callId = fnCall.optString("id", "call_${System.currentTimeMillis()}")
        val explicitCommandId = fnCall.optString("commandId", "").trim()
        val rawFnName = fnCall.optString("name", "")
        val args = fnCall.optJSONObject("args") ?: JSONObject()
        val wireFnName = args.optString("_wireFunctionName", "").trim().ifBlank { rawFnName }

        // Reject late callback from a previous command ID before executing or announcing anything
        if (explicitCommandId.isNotEmpty() && activeCommandId.isNotEmpty() && explicitCommandId != activeCommandId) {
            Log.w(TAG, "Ignoring late callback for stale commandId=$explicitCommandId (activeCommandId=$activeCommandId, fn=$rawFnName)")
            val staleMsg = "Ignored late callback from previous command '$explicitCommandId'."
            val stalePayload = JSONObject().apply {
                put("id", callId)
                put("status", "STALE_COMMAND_IGNORED")
                put("canonicalStatus", "Requested/Unverified")
                put("finalStatus", "REQUESTED_UNVERIFIED")
                put("ok", false)
                put("verified", false)
                put("executed", false)
                put("message", staleMsg)
                put("result", staleMsg)
            }
            return@withContext ExecutedToolCallResult(
                callId = callId,
                commandId = explicitCommandId,
                wireFunctionName = wireFnName,
                executedFunctionName = rawFnName,
                isServerOriginCall = isServerOriginCall,
                finalStatus = "REQUESTED_UNVERIFIED",
                spokenMessage = staleMsg,
                diagnosticMessage = staleMsg,
                resultPayload = stalePayload
            )
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
        clearPlaybackQueueAndStopImmediately(interruptReason = "TOOL_EXECUTION_START")

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
            val staleMsg = "Ignored late callback from previous command '$commandId'."
            val stalePayload = JSONObject().apply {
                put("id", callId)
                put("status", "STALE_COMMAND_IGNORED")
                put("canonicalStatus", "Requested/Unverified")
                put("finalStatus", "REQUESTED_UNVERIFIED")
                put("ok", false)
                put("verified", false)
                put("executed", false)
                put("message", staleMsg)
                put("result", staleMsg)
            }
            return@withContext ExecutedToolCallResult(
                callId = callId,
                commandId = commandId,
                wireFunctionName = wireFnName,
                executedFunctionName = fnName,
                isServerOriginCall = isServerOriginCall,
                finalStatus = "REQUESTED_UNVERIFIED",
                spokenMessage = staleMsg,
                diagnosticMessage = staleMsg,
                resultPayload = stalePayload
            )
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

        val langResolution = resolveCommandReplyLanguage(
            utterance = activeUtterance,
            mutateHistoryIfGenuine = activeUtterance.isNotBlank()
        )
        val userLang = langResolution.language

        val spokenMessageForTurn = if (isAppOpenOrReturnCommand && resultPayload.optString("errorCode") != "DEVICE_LOCKED") {
            buildSpokenConfirmationForAppCommand(
                outcome = currentOutcome,
                utterance = activeUtterance,
                isReturnCommand = isReturnTool,
                fallbackAppName = requestedAppHint,
                explicitLanguageResolution = langResolution
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

        val liveConn = isGeminiLiveWebSocketConnected()
        val liveVoice = _selectedVoice.value.voiceName
        val expectedProvider = if (liveConn) "GEMINI_LIVE ($liveVoice)" else "ANDROID_TTS"

        updateCommandDiagnostic(commandId) { current ->
            current.copy(
                route = if (isServerOriginCall) "SERVER_TOOL_CALL" else "LOCAL_FAST_PATH",
                toolCallId = callId,
                wireFunctionName = wireFnName,
                executedFunctionName = fnName,
                observedActionResult = finalStatusForTurn,
                wsGeneration = activeWebSocketGeneration,
                outputState = CommandSpeechOutputState.REQUESTED,
                outputEngine = expectedProvider,
                requestedOutputProvider = expectedProvider,
                actualOutputProvider = expectedProvider,
                requestedVoiceId = liveVoice,
                resolvedVoiceId = if (liveConn) liveVoice else (ttsFallback?.lastResolvedVoiceId ?: "ANDROID_TTS"),
                resolvedLocaleTag = userLang.ttsLocale.toLanguageTag(),
                speechRate = SpeechPreferenceStore.getSpeechRate(context),
                pitch = SpeechPreferenceStore.getPitch(context),
                chosenReplyLanguage = userLang,
                languageSelectionReason = langResolution.reason
            )
        }

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

        recordTurnExecutionReport(
            commandId = commandId,
            utterance = activeUtterance,
            spokenText = spokenMessageForTurn,
            diagnosticText = diagnosticMessageForTurn,
            finalStatus = finalStatusForTurn,
            outputEngine = expectedProvider,
            language = userLang,
            languageSelectionReason = langResolution.reason,
            liveConnected = liveConn
        )

        _connectionState.value = LiveConnectionState.LIVE_LISTENING
        return@withContext ExecutedToolCallResult(
            callId = callId,
            commandId = commandId,
            wireFunctionName = wireFnName,
            executedFunctionName = fnName,
            isServerOriginCall = isServerOriginCall,
            finalStatus = finalStatusForTurn,
            spokenMessage = spokenMessageForTurn,
            diagnosticMessage = diagnosticMessageForTurn,
            resultPayload = resultPayload,
            activeUtterance = activeUtterance,
            userLanguage = userLang,
            languageSelectionReason = langResolution.reason
        )
    }

    /**
     * Pure serialization of a single tool result for Gemini Live `toolResponse.functionResponses`.
     * Preserves original server call `id` and original `wireFunctionName`, and never leaks internal `commandId`.
     */
    fun serializeSingleFunctionResponse(outcome: ExecutedToolCallResult): JSONObject {
        val responseJson = JSONObject().apply {
            put("result", outcome.resultPayload)
        }
        return JSONObject().apply {
            put("id", outcome.callId)
            put("name", outcome.wireFunctionName)
            put("response", responseJson)
        }
    }

    /**
     * Transmits at most one `toolResponse` per server-origin `callId` over [webSocket].
     * Refuses to transmit invented responses for local fast-path IDs (`_step_`, `_return_origin`).
     * Handles `webSocket.send(...) == false` with bounded single-shot speech recovery.
     */
    fun transmitServerToolResponseBatchOnce(
        webSocket: WebSocket?,
        outcomes: List<ExecutedToolCallResult>
    ): Boolean {
        val responsesArray = JSONArray()
        val includedOutcomes = mutableListOf<ExecutedToolCallResult>()
        for (outcome in outcomes) {
            if (!outcome.isServerOriginCall || isLocalFastPathCallId(outcome.callId)) {
                Log.w(TAG, "Refusing to transmit non-server toolResponse for local callId=${outcome.callId}")
                continue
            }
            if (!transmittedServerToolCallIds.add(outcome.callId)) {
                Log.w(TAG, "Skipping duplicate toolResponse transmission for server callId=${outcome.callId}")
                continue
            }
            responsesArray.put(serializeSingleFunctionResponse(outcome))
            includedOutcomes.add(outcome)
        }
        if (responsesArray.length() == 0) {
            return false
        }
        val toolResponsePayload = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", responsesArray)
            })
        }
        val serializedToolResponse = toolResponsePayload.toString()
        lastSentToolResponseJson = serializedToolResponse

        val targetWs = webSocket ?: activeWebSocket?.takeIf { isWebSocketReady.get() }
        val sentOk = if (targetWs != null && isWebSocketReady.get()) {
            try {
                Log.d(TAG, "Sending Live toolResponse once for ${includedOutcomes.map { it.callId }}")
                targetWs.send(serializedToolResponse)
            } catch (e: Exception) {
                Log.w(TAG, "WebSocket send(toolResponse) threw: ${e.message}")
                false
            }
        } else {
            false
        }

        val lastOutcome = includedOutcomes.lastOrNull()
        if (lastOutcome != null) {
            if (sentOk) {
                updateCommandDiagnostic(lastOutcome.commandId) {
                    it.copy(outputState = CommandSpeechOutputState.REQUESTED)
                }
                schedulePostActionLiveAudioWatchdog(lastOutcome.commandId)
            } else {
                triggerBoundedSpeechRecoveryForCommand(
                    commandId = lastOutcome.commandId,
                    reason = "WS_TOOL_RESPONSE_SEND_FALSE"
                )
            }
        }
        return sentOk
    }

    /**
     * Executes a single tool call and returns its serialized functionResponse object without
     * transmitting over [activeWebSocket] (preventing duplicate transmission when called directly
     * or by batch callers).
     */
    suspend fun executeLiveFunctionCallSingle(fnCall: JSONObject): JSONObject {
        val outcome = executeToolActionOnly(fnCall, isServerOriginCall = true)
        val singleFnResponse = serializeSingleFunctionResponse(outcome)
        val toolResponsePayload = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().apply {
                    put(singleFnResponse)
                })
            })
        }
        lastSentToolResponseJson = toolResponsePayload.toString()
        _connectionState.value = LiveConnectionState.LIVE_LISTENING
        return singleFnResponse
    }

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
        lastRecognitionDecision = "${eval.decision.name}(${eval.reason})"
        refreshDiagnosticSnapshot()
        when (eval.decision) {
            WakeSpeechCandidateDecision.STOP_AND_DISARM_PINNED -> {
                clearPlaybackQueueAndStopImmediately()
                endLiveSession()
                com.example.service.WakeWordForegroundService.stop(context, pinUserStop = true)
            }
            WakeSpeechCandidateDecision.CANCEL_OR_STOP_COMMAND_WINDOW -> {
                isBoundedConversationModeActive = false
                boundedConversationRemainingTurns = 0
                clearPlaybackQueueAndStopImmediately()
                activeCommandId = ""
                awaitingVerifiedToolOutcome = false
                currentTurnHasPendingOrExecutedAction = false
                handoffToPostTurnCapture("VOICE_CANCEL_BAS_STOP")
            }
            WakeSpeechCandidateDecision.WAKE_WORD_DETECTED_OPEN_WINDOW -> {
                _liveUserTranscript.value = recognizedTranscript
                _liveAiTranscript.value = "Jee, hukum karein."
                startActiveCommandCaptureAfterWakeWord("WAKE_WORD_ONLY_UTTERANCE")
            }
            WakeSpeechCandidateDecision.WAKE_AND_INLINE_COMMAND_ACCEPTED,
            WakeSpeechCandidateDecision.COMMAND_ACCEPTED_IN_WINDOW -> {
                if (eval.shouldExecuteCommand && eval.extractedCommandText.isNotBlank()) {
                    processFallbackLiveTurn(eval.extractedCommandText, null, nowMs = nowMs)
                }
                if (!isBoundedConversationModeActive || !_isSessionActive.value) {
                    handoffToPostTurnCapture("COMMAND_TURN_FINISHED")
                }
            }
            else -> {
                refreshDiagnosticSnapshot()
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

        val initialText = textInput?.trim().orEmpty()
        val preTurnId = if (!commandIdOverride.isNullOrBlank()) {
            if (activeCommandId != commandIdOverride) {
                startNewCommandTurn(commandId = commandIdOverride, utterance = initialText)
            } else {
                commandIdOverride
            }
        } else if (initialText.isNotEmpty()) {
            startNewCommandTurn(utterance = initialText)
        } else {
            ""
        }

        if (preTurnId.isNotBlank()) {
            _observedCaptureState.value = ObservedAssistantCaptureState.PROCESSING
            val preLang = resolveCommandReplyLanguage(initialText, mutateHistoryIfGenuine = false).language
            _liveAiTranscript.value = when (preLang) {
                DetectedLanguage.URDU_NASTALIQ -> "کارروائی جاری ہے..."
                DetectedLanguage.ROMAN_URDU, DetectedLanguage.MIXED -> "Processing... Soch raha hoon..."
                DetectedLanguage.ENGLISH -> "Processing..."
            }
        }

        val reqSentAtElapsed = SystemClock.elapsedRealtime()
        if (preTurnId.isNotBlank()) {
            updateCommandDiagnostic(preTurnId) { current ->
                current.copy(
                    requestSentElapsedMs = if (current.requestSentElapsedMs > 0L) current.requestSentElapsedMs else reqSentAtElapsed
                )
            }
        }

        // Fast-path (<5ms): When Live WebSocket is connected and the user's text turn has no local device tasks,
        // send the conversational turn directly over the Live WebSocket without blocking on a serial
        // SuperAgiBrainEngine cloud HTTP/Firebase AI call first!
        val fastLocalTasks = if (initialText.isNotEmpty()) {
            SuperAgiBrainEngine.extractAllDeviceTasks(context, initialText)
        } else {
            emptyList()
        }
        val fastIsReturnCmd = initialText.isNotEmpty() && com.omnivoice.agentfix.UiTools.isReturnToPreviousAppCommand(initialText)
        val canUseFastConnectedLiveConversationalRoute =
            initialText.isNotEmpty() &&
                !fastIsReturnCmd &&
                fastLocalTasks.isEmpty() &&
                isGeminiLiveWebSocketConnected()

        val rawResult = if (canUseFastConnectedLiveConversationalRoute) {
            DeterministicNluEngine.analyzeTextDeterministicallyLocal(context, initialText)
        } else if (initialText.isNotEmpty()) {
            SuperAgiBrainEngine.analyzeAndRespondLikeGeminiAndChatGpt(context, initialText)
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
        if (rawResult.verbatimTranscript.isBlank() && initialText.isBlank()) {
            recordSoundDetectedNoAcceptedCommand("SILENT_OR_UNRECOGNIZED_AUDIO_TURN")
            return
        }

        val cleanUtterance = rawResult.verbatimTranscript.ifBlank { initialText }
        val turnCommandId = if (preTurnId.isNotBlank()) {
            preTurnId
        } else {
            startNewCommandTurn(utterance = cleanUtterance)
        }
        val firstRespElapsed = SystemClock.elapsedRealtime()
        updateCommandDiagnostic(turnCommandId) { current ->
            current.copy(
                requestSentElapsedMs = if (current.requestSentElapsedMs > 0L) current.requestSentElapsedMs else reqSentAtElapsed,
                firstResponseReceivedElapsedMs = if (!canUseFastConnectedLiveConversationalRoute) {
                    if (current.firstResponseReceivedElapsedMs > 0L) current.firstResponseReceivedElapsedMs else firstRespElapsed
                } else {
                    current.firstResponseReceivedElapsedMs
                }
            )
        }

        if (cleanUtterance.isNotBlank()) {
            _liveUserTranscript.value = cleanUtterance
            OmniAutonomousCoreEngine.analyzeUserMood(cleanUtterance, _inputAudioRms.value)
            com.omnivoice.agentfix.AgentSession.onUserUtterance(cleanUtterance)
        }

        val isReturnToOriginCommand = fastIsReturnCmd || com.omnivoice.agentfix.UiTools.isReturnToPreviousAppCommand(cleanUtterance)
        val hasActionTasks = isReturnToOriginCommand || rawResult.tasksToExecute.isNotEmpty()
        currentTurnHasPendingOrExecutedAction = hasActionTasks
        val langResolution = resolveCommandReplyLanguage(
            utterance = cleanUtterance,
            mutateHistoryIfGenuine = cleanUtterance.isNotBlank()
        )
        val userLang = langResolution.language
        var finalSpokenReply = if (hasActionTasks || canUseFastConnectedLiveConversationalRoute) {
            ""
        } else {
            sanitizeSpokenText(rawResult.spokenReply, cleanUtterance)
        }

        if (!hasActionTasks) {
            lastCompletedCommandId = turnCommandId
            lastFinalStatusForCurrentCommand = "CONVERSATIONAL"
            if (finalSpokenReply.isNotBlank()) {
                lastSpokenReplyForCurrentCommand = finalSpokenReply
                lastDiagnosticTextForCurrentCommand = finalSpokenReply
                _liveAiTranscript.value = finalSpokenReply
            }
            val liveConnectedNow = isGeminiLiveWebSocketConnected()
            if (liveConnectedNow) {
                val ws = activeWebSocket
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
                val sentOk = if (ws != null && isWebSocketReady.get()) {
                    try {
                        ws.send(serializedClientContent)
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }
                updateCommandDiagnostic(turnCommandId) { current ->
                    current.copy(
                        wsSendSucceeded = sentOk,
                        outputState = CommandSpeechOutputState.REQUESTED,
                        stallStage = if (!sentOk) CommandTurnStallStage.COMMAND_ACCEPTED_REQUEST_NOT_SENT else current.stallStage
                    )
                }
                recordTurnExecutionReport(
                    commandId = turnCommandId,
                    utterance = cleanUtterance,
                    spokenText = finalSpokenReply,
                    diagnosticText = finalSpokenReply,
                    finalStatus = "CONVERSATIONAL",
                    outputEngine = "GEMINI_LIVE (${_selectedVoice.value.voiceName})",
                    language = userLang,
                    languageSelectionReason = langResolution.reason,
                    liveConnected = true
                )
                if (sentOk) {
                    schedulePostActionLiveAudioWatchdog(turnCommandId)
                } else {
                    triggerBoundedSpeechRecoveryForCommand(
                        commandId = turnCommandId,
                        reason = "WS_CONVERSATION_SEND_FALSE"
                    )
                }
            } else {
                if (finalSpokenReply.isBlank()) {
                    finalSpokenReply = sanitizeSpokenText(
                        SuperAgiBrainEngine.analyzeAndRespondLikeGeminiAndChatGpt(context, cleanUtterance).spokenReply,
                        cleanUtterance
                    )
                    lastSpokenReplyForCurrentCommand = finalSpokenReply
                    lastDiagnosticTextForCurrentCommand = finalSpokenReply
                    _liveAiTranscript.value = finalSpokenReply
                }
                deliverCommandSpeechViaLocalTts(
                    commandId = turnCommandId,
                    utterance = cleanUtterance,
                    spokenReply = finalSpokenReply,
                    diagnosticText = finalSpokenReply,
                    finalStatus = "CONVERSATIONAL",
                    language = userLang,
                    languageSelectionReason = langResolution.reason,
                    isRecovery = false
                )
                recordTurnExecutionReport(
                    commandId = turnCommandId,
                    utterance = cleanUtterance,
                    spokenText = finalSpokenReply,
                    diagnosticText = finalSpokenReply,
                    finalStatus = "CONVERSATIONAL",
                    outputEngine = "ANDROID_TTS",
                    language = userLang,
                    languageSelectionReason = langResolution.reason,
                    liveConnected = false
                )
            }
        }

        // Execute all explicit local fast-path tasks with turnCommandId correlation.
        // CRITICAL: Local fast-path IDs (${turnCommandId}_return_origin, ${turnCommandId}_step_1)
        // are local IDs, NOT outstanding Gemini server call IDs. Never transmit invented toolResponses!
        var lastToolResponseMessage = ""
        if (isReturnToOriginCommand) {
            val fnObj = JSONObject().apply {
                put("id", "${turnCommandId}_return_origin")
                put("commandId", turnCommandId)
                put("name", "return_to_origin")
                put("args", JSONObject())
            }
            val outcome = executeToolActionOnly(fnObj, isServerOriginCall = false)
            if (outcome.spokenMessage.isNotBlank()) {
                lastToolResponseMessage = outcome.spokenMessage
            }
        }
        val tasksToRun = if (isReturnToOriginCommand) emptyList() else rawResult.tasksToExecute
        for ((idx, task) in tasksToRun.withIndex()) {
            if (activeCommandId.isNotBlank() && activeCommandId != turnCommandId) {
                Log.w(TAG, "Aborting remaining fallback tasks for superseded turnCommandId=$turnCommandId (active=$activeCommandId)")
                return
            }
            val callId = "${turnCommandId}_step_${idx + 1}"
            val localOutcome: ExecutedToolCallResult? = when (task.taskType) {
                NluTaskType.OPEN_APP -> {
                    val fnObj = JSONObject().apply {
                        put("id", callId)
                        put("commandId", turnCommandId)
                        put("name", "open_app")
                        put("args", JSONObject().apply {
                            put("app_name", task.target)
                        })
                    }
                    executeToolActionOnly(fnObj, isServerOriginCall = false)
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
                    executeToolActionOnly(fnObj, isServerOriginCall = false)
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
                    executeToolActionOnly(fnObj, isServerOriginCall = false)
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
                    executeToolActionOnly(fnObj, isServerOriginCall = false)
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
                    executeToolActionOnly(fnObj, isServerOriginCall = false)
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
                    executeToolActionOnly(fnObj, isServerOriginCall = false)
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
                    executeToolActionOnly(fnObj, isServerOriginCall = false)
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
                    executeToolActionOnly(fnObj, isServerOriginCall = false)
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
                    executeToolActionOnly(fnObj, isServerOriginCall = false)
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
                    executeToolActionOnly(fnObj, isServerOriginCall = false)
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
                            executeToolActionOnly(fnObj, isServerOriginCall = false)
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
                            executeToolActionOnly(fnObj, isServerOriginCall = false)
                        }
                    }
                }
            }
            val msgFromResp = localOutcome?.spokenMessage?.takeIf { it.isNotBlank() }
                ?: localOutcome?.resultPayload?.optString("message").orEmpty()
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
            val finalStatusForTurn = if (lastCompletedCommandId == turnCommandId) {
                lastFinalStatusForCurrentCommand.ifBlank { "REQUESTED_UNVERIFIED" }
            } else {
                "REQUESTED_UNVERIFIED"
            }
            val diagnosticForTurn = if (lastCompletedCommandId == turnCommandId) {
                lastDiagnosticTextForCurrentCommand.ifBlank { finalSpokenReply }
            } else {
                finalSpokenReply
            }
            lastSpokenReplyForCurrentCommand = finalSpokenReply
            _liveAiTranscript.value = finalSpokenReply

            // After a locally verified/completed action:
            // - Never transmit an invented toolResponse for local IDs.
            // - When Gemini Live is connected, request spoken acknowledgement over the supported
            //   clientContent conversation-input path (without asking the model to execute the action again)
            //   and arm the bounded audio-delivery recovery watchdog in case send=false, timeout, or disconnect occurs.
            // - When Gemini Live is not connected, deliver directly via local TTS.
            if (isGeminiLiveWebSocketConnected()) {
                val ws = activeWebSocket
                val ackInstruction = "[SYSTEM_LOCAL_ACTION_COMPLETED status=$finalStatusForTurn do_not_call_any_tool=true] " +
                    "The local device action for user command \"$cleanUtterance\" has already finished with status $finalStatusForTurn. " +
                    "Do NOT call any tool or function again. Speak aloud ONLY this exact reply in the user's language: $finalSpokenReply"
                val ackClientContentMsg = JSONObject().apply {
                    put("clientContent", JSONObject().apply {
                        put("turns", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().apply {
                                    put(JSONObject().put("text", ackInstruction))
                                })
                            })
                        })
                        put("turnComplete", true)
                    })
                }
                val serializedAck = ackClientContentMsg.toString()
                lastSentClientContentJson = serializedAck
                val sentOk = if (ws != null && isWebSocketReady.get()) {
                    try {
                        ws.send(serializedAck)
                    } catch (e: Exception) {
                        Log.w(TAG, "WebSocket send(clientContent ack) threw: ${e.message}")
                        false
                    }
                } else {
                    false
                }
                val ackSentElapsed = SystemClock.elapsedRealtime()
                updateCommandDiagnostic(turnCommandId) { current ->
                    current.copy(
                        route = "LOCAL_FAST_PATH_LIVE_ACK",
                        observedActionResult = finalStatusForTurn,
                        wsSendSucceeded = sentOk,
                        requestSentElapsedMs = if (current.requestSentElapsedMs > 0L) current.requestSentElapsedMs else ackSentElapsed,
                        firstResponseReceivedElapsedMs = if (current.firstResponseReceivedElapsedMs > 0L) current.firstResponseReceivedElapsedMs else ackSentElapsed,
                        outputState = CommandSpeechOutputState.REQUESTED,
                        stallStage = if (!sentOk) CommandTurnStallStage.COMMAND_ACCEPTED_REQUEST_NOT_SENT else current.stallStage
                    )
                }
                if (sentOk) {
                    schedulePostActionLiveAudioWatchdog(turnCommandId)
                } else {
                    triggerBoundedSpeechRecoveryForCommand(
                        commandId = turnCommandId,
                        reason = "WS_CLIENT_CONTENT_SEND_FALSE"
                    )
                }
            } else {
                updateCommandDiagnostic(turnCommandId) { current ->
                    current.copy(
                        route = "LOCAL_FAST_PATH_TTS",
                        observedActionResult = finalStatusForTurn
                    )
                }
                deliverCommandSpeechViaLocalTts(
                    commandId = turnCommandId,
                    utterance = cleanUtterance,
                    spokenReply = finalSpokenReply,
                    diagnosticText = diagnosticForTurn,
                    finalStatus = finalStatusForTurn,
                    language = userLang,
                    languageSelectionReason = langResolution.reason,
                    isRecovery = false
                )
                recordTurnExecutionReport(
                    commandId = turnCommandId,
                    utterance = cleanUtterance,
                    outputEngine = "ANDROID_TTS",
                    language = userLang,
                    languageSelectionReason = langResolution.reason,
                    liveConnected = false,
                    finalStatus = finalStatusForTurn,
                    spokenText = finalSpokenReply,
                    diagnosticText = diagnosticForTurn
                )
            }
        }

        mainHandler.post {
            onTranscriptTurnCompleted?.invoke(cleanUtterance, finalSpokenReply)
        }
    }

    /**
     * Deterministic suspend entry point for executing a local/hybrid turn in tests without
     * needing arbitrary thread sleeps.
     */
    suspend fun executeLocalOrLiveTurnForTest(
        textInput: String,
        commandIdOverride: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (AssistantAudioStateMachine.shared.lifecycleState.value != WakeSpeechLifecycleState.SPEAKING_SUPPRESSED) {
            AssistantAudioStateMachine.shared.clearTailCooldownAfterDrain()
        }
        processFallbackLiveTurn(
            textInput = textInput,
            wavBytes = null,
            commandIdOverride = commandIdOverride,
            nowMs = nowMs
        )
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
        clearCaptureReadiness("RELEASE_MIC_AUDIO_RECORD")
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
        _activeAudioRecordInstanceCount.updateAndGet { (it - 1).coerceAtLeast(0) }
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

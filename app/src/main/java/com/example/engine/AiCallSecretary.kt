package com.example.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class CallSecretarySession(
    val callerNumber: String = "",
    val callerName: String = "",
    val callState: String = "IDLE", // IDLE, RINGING, SCREENING, IN_CONVERSATION, ENDED
    val conversationTranscript: List<String> = emptyList(),
    val callSummary: String = "",
    val startTimeMs: Long = 0L,
    val isAutoAnswered: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("callerNumber", callerNumber)
        put("callerName", callerName)
        put("callState", callState)
        put("conversationTranscript", org.json.JSONArray(conversationTranscript))
        put("callSummary", callSummary)
        put("startTimeMs", startTimeMs)
        put("isAutoAnswered", isAutoAnswered)
    }
}

/**
 * AI Call Secretary for autonomous incoming call screening, voice answering,
 * live transcription, and message logging.
 */
class AiCallSecretary(
    private val context: Context,
    private val tts: TextToSpeechManager? = null
) {
    companion object {
        private const val TAG = "AiCallSecretary"
        private var instance: AiCallSecretary? = null

        fun getInstance(context: Context): AiCallSecretary {
            return instance ?: synchronized(this) {
                instance ?: AiCallSecretary(context.applicationContext, TextToSpeechManager(context)).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    private val _currentSession = MutableStateFlow(CallSecretarySession())
    val currentSession: StateFlow<CallSecretarySession> = _currentSession.asStateFlow()

    private val _secretaryActive = MutableStateFlow(false)
    val secretaryActive: StateFlow<Boolean> = _secretaryActive.asStateFlow()

    /**
     * Triggered when an incoming call is detected (via BroadcastReceiver or TelephonyCallback).
     */
    fun onIncomingCallDetected(callerNumber: String, callerName: String = "") {
        Log.i(TAG, "Incoming call detected from: $callerNumber ($callerName)")
        val resolvedName = if (callerName.isNotBlank()) callerName else {
            val contact = SmartContactAndAppRegistry.findContactByName(context, callerNumber)
            contact?.name ?: "Unknown Caller"
        }
        _currentSession.value = CallSecretarySession(
            callerNumber = callerNumber,
            callerName = resolvedName,
            callState = "RINGING",
            startTimeMs = System.currentTimeMillis()
        )
        _secretaryActive.value = true

        // Auto-Screen after 2 rings
        scope.launch {
            delay(2500)
            if (_currentSession.value.callState == "RINGING") {
                answerAndScreenCall()
            }
        }
    }

    /**
     * Answers call autonomously and speaks secretary greeting.
     */
    fun answerAndScreenCall(): Boolean {
        Log.i(TAG, "Answering incoming call autonomously via Telecom / ADB / Keyevent...")
        var answered = false

        // 1. TelecomManager API
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                telecomManager?.acceptRingingCall()
                answered = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "telecomManager.acceptRingingCall failed: ${e.message}")
        }

        // 2. Headset Hook / Media Button Intent Fallback
        if (!answered) {
            try {
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(
                        Intent.EXTRA_KEY_EVENT,
                        android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK)
                    )
                }
                context.sendOrderedBroadcast(intent, null)
                val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(
                        Intent.EXTRA_KEY_EVENT,
                        android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HEADSETHOOK)
                    )
                }
                context.sendOrderedBroadcast(upIntent, null)
                answered = true
            } catch (e: Exception) {
                Log.w(TAG, "MediaButton answer fallback failed: ${e.message}")
            }
        }

        _currentSession.value = _currentSession.value.copy(
            callState = "SCREENING",
            isAutoAnswered = true,
            conversationTranscript = _currentSession.value.conversationTranscript +
                "Jarvis: Hello, this is Jarvis, the AI assistant for ${OmniAutonomousCoreEngine.permanentMemory.value.assistantName}'s owner. Please state your name and the purpose of your call."
        )

        // Speak autonomous greeting via TTS / audio stream
        val greeting = "Hello, this is Jarvis, the AI assistant. I am screening this call. Please state your message after the tone."
        tts?.speak(greeting, 1.0f, 1.0f, java.util.Locale.US)

        return answered
    }

    /**
     * Appends live transcript chunk from caller or assistant.
     */
    fun appendTranscript(speaker: String, text: String) {
        val line = "$speaker: $text"
        _currentSession.value = _currentSession.value.copy(
            callState = "IN_CONVERSATION",
            conversationTranscript = _currentSession.value.conversationTranscript + line
        )
    }

    /**
     * Completes call screening session, generates summary and logs result.
     */
    fun endCall(summary: String = ""): Boolean {
        Log.i(TAG, "Ending call session. Summary: $summary")
        var ended = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager?.endCall()
                ended = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "telecomManager.endCall failed: ${e.message}")
        }

        val finalSummary = if (summary.isNotBlank()) summary else {
            "Call from ${_currentSession.value.callerName} (${_currentSession.value.callerNumber}) screened by Jarvis. Total messages: ${_currentSession.value.conversationTranscript.size}."
        }

        _currentSession.value = _currentSession.value.copy(
            callState = "ENDED",
            callSummary = finalSummary
        )
        _secretaryActive.value = false
        return ended
    }
}

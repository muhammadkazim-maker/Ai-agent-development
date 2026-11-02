package com.example.engine

import android.content.Context
import android.media.AudioManager
import android.telecom.TelecomManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class CallScreeningSession(
    val id: String = "call_${System.currentTimeMillis()}",
    val callerNumber: String,
    val callerName: String,
    val callState: String, // "IDLE", "RINGING", "BLOCKED_UNATTENDED_ANSWER", "ENDED"
    val conversationTranscript: List<String> = emptyList(),
    val summaryNotes: String = "",
    val startedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("callerNumber", callerNumber)
        put("callerName", callerName)
        put("callState", callState)
        put("summaryNotes", summaryNotes)
        put("startedAt", startedAt)
        val arr = JSONArray()
        conversationTranscript.forEach { arr.put(it) }
        put("conversationTranscript", arr)
    }
}

/**
 * AI Call Secretary (Phase 01 Hardened):
 * - Unattended automatic call answering (`acceptRingingCall`, `KEYCODE_HEADSETHOOK`, and `keyevent 5`)
 *   is strictly blocked in all production entry points.
 * - Incoming call metadata can be observed in RINGING state, but answering requires manual user action.
 */
class AiCallSecretary(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val ttsManager: TextToSpeechManager? = null
) {

    companion object {
        private const val TAG = "AiCallSecretary"
        @Volatile
        private var instance: AiCallSecretary? = null

        fun getInstance(context: Context): AiCallSecretary {
            return synchronized(this) {
                val appCtx = context.applicationContext ?: context
                val existing = instance
                if (existing == null || existing.context !== appCtx) {
                    AiCallSecretary(appCtx, null).also { instance = it }
                } else {
                    existing
                }
            }
        }
    }

    private val _isSecretaryEnabled = MutableStateFlow(false)
    val isSecretaryEnabled: StateFlow<Boolean> = _isSecretaryEnabled.asStateFlow()

    private val _autoAnswerDelayMs = MutableStateFlow(0L)
    val autoAnswerDelayMs: StateFlow<Long> = _autoAnswerDelayMs.asStateFlow()

    private val _currentSession = MutableStateFlow(
        CallScreeningSession(callerNumber = "", callerName = "", callState = "IDLE")
    )
    val currentSession: StateFlow<CallScreeningSession> = _currentSession.asStateFlow()

    private val _callHistory = MutableStateFlow<List<CallScreeningSession>>(emptyList())
    val callHistory: StateFlow<List<CallScreeningSession>> = _callHistory.asStateFlow()

    @Suppress("UNUSED_PARAMETER")
    fun setSecretaryEnabled(enabled: Boolean) {
        // Phase 01 Security Policy: Unattended call answering remains disabled
        _isSecretaryEnabled.value = false
    }

    /**
     * Records incoming call state as RINGING without scheduling or performing unattended call answering.
     */
    fun onIncomingCallDetected(callerNumber: String, callerName: String = "Unknown Caller") {
        val cleanName = callerName.ifBlank {
            SmartContactAndAppRegistry.findMatchingContactsFuzzy(context, callerNumber).firstOrNull()?.name ?: callerNumber
        }
        val session = CallScreeningSession(
            callerNumber = callerNumber,
            callerName = cleanName,
            callState = "RINGING",
            conversationTranscript = listOf("Incoming call from $cleanName ($callerNumber). Unattended auto-answer is disabled."),
            summaryNotes = "Unattended call answering is disabled for safety."
        )
        _currentSession.value = session
        Log.i(TAG, "Incoming call detected from $cleanName ($callerNumber). Unattended answering blocked.")
    }

    /**
     * Phase 01 Security Policy:
     * Unattended call answering is blocked in all reachable entry points. Never calls acceptRingingCall
     * or injects headset/call keyevents.
     */
    fun answerAndScreenCall(): Boolean {
        Log.w(TAG, "Blocked unattended answerAndScreenCall request (Phase 01 policy).")
        val current = _currentSession.value
        val updated = current.copy(
            callState = "BLOCKED_UNATTENDED_ANSWER",
            conversationTranscript = current.conversationTranscript +
                "System: Unattended call answering is blocked by security policy.",
            summaryNotes = "Unattended call answering is disabled for safety."
        )
        _currentSession.value = updated
        return false
    }

    fun appendTranscript(speaker: String, text: String) {
        val current = _currentSession.value
        val updated = current.copy(
            conversationTranscript = current.conversationTranscript + "$speaker: $text"
        )
        _currentSession.value = updated
    }

    fun endCall(summary: String = "Call ended."): Boolean {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var ended = false
        try {
            @Suppress("MissingPermission", "DEPRECATION")
            ended = telecomManager?.endCall() ?: false
        } catch (e: Exception) {
            Log.w(TAG, "endCall exception: ${e.message}")
        }
        try {
            @Suppress("DEPRECATION")
            audioManager?.isSpeakerphoneOn = false
        } catch (_: Exception) {}

        val completed = _currentSession.value.copy(
            callState = "ENDED",
            summaryNotes = summary
        )
        _currentSession.value = completed
        _callHistory.value = (listOf(completed) + _callHistory.value).take(30)
        return ended
    }
}

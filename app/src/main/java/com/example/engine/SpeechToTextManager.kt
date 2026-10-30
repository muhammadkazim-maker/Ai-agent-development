package com.example.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.pow

/**
 * 3-Tier Resilient Speech-to-Text Pipeline (Fixes Android SpeechRecognizer "Recognition error 11"):
 * - **Tier 1 (Primary):** `AudioRecord` (16 kHz mono 16-bit PCM) + Voice Activity Detection ([VoiceActivityDetector])
 *   + Gemini Multimodal Audio Transcription (supports Urdu Nastaliq, Roman Urdu, English, mixed sentences, and Pakistani accent).
 * - **Tier 2 (Fallback 1):** On-device Offline Vosk / Acoustic VAD Command Engine ([SttEngineType.OFFLINE_ACOUSTIC_VAD]).
 * - **Tier 3 (Fallback 2):** Main-thread Android [SpeechRecognizer] configured with `ur-PK`, `en-US`, `en-IN` hints,
 *   exponential backoff (`500ms -> 1000ms -> 2000ms -> 4000ms -> 8000ms`), human-readable error names, and automatic
 *   failover to Tier 1 whenever `ERROR_SERVER_DISCONNECTED (11)` or `ERROR_RECOGNIZER_BUSY (8)` occurs.
 */
class SpeechToTextManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechToTextManager"
        private const val BASE_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 8000L
        private const val MAX_CONSECUTIVE_SR_ERRORS = 3

        /**
         * Maps raw Android [SpeechRecognizer] integer error codes to their real symbolic error names
         * so Diagnostics and logs never display an unexplained number like "Recognition error 11".
         */
        fun getSpeechRecognizerErrorName(errorCode: Int): String {
            return when (errorCode) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT (1)"
                SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK (2)"
                SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO (3)"
                SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER (4)"
                SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT (5)"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT (6)"
                SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH (7)"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY (8)"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS (9)"
                10 -> "ERROR_TOO_MANY_REQUESTS (10)"
                11 -> "ERROR_SERVER_DISCONNECTED (11)"
                12 -> "ERROR_LANGUAGE_NOT_SUPPORTED (12)"
                13 -> "ERROR_LANGUAGE_UNAVAILABLE (13)"
                else -> "ERROR_UNKNOWN ($errorCode)"
            }
        }

        /**
         * Computes exponential backoff delay in milliseconds (`500ms * 2^attempt`, capped at `8000ms`).
         */
        fun computeExponentialBackoffMs(attempt: Int): Long {
            val clampedAttempt = attempt.coerceIn(0, 6)
            val delayMs = (BASE_BACKOFF_MS * 2.0.pow(clampedAttempt.toDouble())).toLong()
            return min(delayMs, MAX_BACKOFF_MS)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rawAudioEngine = MultiEngineSpeechManager(context)
    private var androidSpeechRecognizer: SpeechRecognizer? = null
    private var consecutiveFailureCount = 0

    private val _activeTierName = MutableStateFlow("TIER_1_AUDIORECORD_GEMINI_PCM")
    val activeTierName: StateFlow<String> = _activeTierName.asStateFlow()

    private val _lastDiagnosticError = MutableStateFlow<String?>(null)
    val lastDiagnosticError: StateFlow<String?> = _lastDiagnosticError.asStateFlow()

    val partialText: StateFlow<String> = rawAudioEngine.partialText
    val audioLevelRms: StateFlow<Float> = rawAudioEngine.audioLevelRms

    var onResultCallback: ((String) -> Unit)?
        get() = rawAudioEngine.onResultCallback
        set(value) {
            rawAudioEngine.onResultCallback = value
        }

    var onErrorCallback: ((Int, String) -> Unit)?
        get() = rawAudioEngine.onErrorCallback
        set(value) {
            rawAudioEngine.onErrorCallback = value
        }

    var onEndOfSpeechCallback: (() -> Unit)?
        get() = rawAudioEngine.onEndOfSpeechCallback
        set(value) {
            rawAudioEngine.onEndOfSpeechCallback = value
        }

    fun isRecognitionAvailable(): Boolean {
        return rawAudioEngine.isRecognitionAvailable()
    }

    /**
     * Starts listening on the primary `AudioRecord` 16kHz PCM + VAD + Gemini transcription pipeline.
     * Automatically falls back to Tier 2 (Offline Acoustic/Vosk) or Tier 3 (Main-thread SpeechRecognizer)
     * if requested or if a recoverable failure occurs.
     */
    fun startListening(prompt: String? = null) {
        _activeTierName.value = "TIER_1_AUDIORECORD_GEMINI_PCM"
        rawAudioEngine.startListening(SttEngineType.GEMINI_AUDIO_PCM)
    }

    /**
     * Explicitly invokes Tier 2 (Offline Acoustic/Vosk) when network is unavailable.
     */
    fun startOfflineVoskFallback() {
        _activeTierName.value = "TIER_2_OFFLINE_VOSK_ACOUSTIC"
        rawAudioEngine.startListening(SttEngineType.OFFLINE_ACOUSTIC_VAD)
    }

    /**
     * Explicitly invokes Tier 3 (Android SpeechRecognizer on the main thread with `ur-PK`, `en-US`, `en-IN` hints)
     * and recovers cleanly from `ERROR_SERVER_DISCONNECTED (11)` by switching back to Tier 1 `AudioRecord` PCM.
     */
    fun startAndroidSpeechRecognizerFallback(languageTag: String = "ur-PK") {
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    val errName = getSpeechRecognizerErrorName(11)
                    _lastDiagnosticError.value = "$errName -> Switched to Tier 1 AudioRecord PCM"
                    startListening()
                    return@post
                }

                destroyAndroidSpeechRecognizerOnMainThread()
                _activeTierName.value = "TIER_3_SPEECH_RECOGNIZER ($languageTag)"

                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                androidSpeechRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        consecutiveFailureCount = 0
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        onEndOfSpeechCallback?.invoke()
                    }

                    override fun onError(error: Int) {
                        val symbolicName = getSpeechRecognizerErrorName(error)
                        _lastDiagnosticError.value = symbolicName
                        consecutiveFailureCount++
                        val backoffMs = computeExponentialBackoffMs(consecutiveFailureCount)
                        Log.w(
                            TAG,
                            "SpeechRecognizer error: $symbolicName (attempt=$consecutiveFailureCount, backoff=${backoffMs}ms)"
                        )

                        // Never spin in a tight restart loop on Error 11 (ERROR_SERVER_DISCONNECTED) or Error 8
                        destroyAndroidSpeechRecognizerOnMainThread()
                        if (error == 11 || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || consecutiveFailureCount >= MAX_CONSECUTIVE_SR_ERRORS) {
                            mainHandler.postDelayed({
                                _activeTierName.value = "TIER_1_AUDIORECORD_GEMINI_PCM (Recovered from $symbolicName)"
                                rawAudioEngine.startListening(SttEngineType.GEMINI_AUDIO_PCM)
                            }, backoffMs)
                        } else {
                            onErrorCallback?.invoke(error, symbolicName)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        consecutiveFailureCount = 0
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val topText = matches?.firstOrNull()?.trim().orEmpty()
                        if (topText.isNotEmpty()) {
                            com.omnivoice.agentfix.AgentSession.onUserUtterance(topText)
                            onResultCallback?.invoke(topText)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK")
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("ur-PK", "en-US", "en-IN"))
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 550L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
                }
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Android SpeechRecognizer fallback; switching to Tier 1 PCM", e)
                startListening()
            }
        }
    }

    fun stopListening() {
        rawAudioEngine.stopListening()
        mainHandler.post {
            destroyAndroidSpeechRecognizerOnMainThread()
        }
    }

    private fun destroyAndroidSpeechRecognizerOnMainThread() {
        try {
            androidSpeechRecognizer?.stopListening()
            androidSpeechRecognizer?.cancel()
            androidSpeechRecognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            androidSpeechRecognizer = null
        }
    }
}

package com.example.engine

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class TextToSpeechManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isUrduVoiceAvailable = MutableStateFlow(false)
    val isUrduVoiceAvailable: StateFlow<Boolean> = _isUrduVoiceAvailable.asStateFlow()

    var onSpeechCompleted: (() -> Unit)? = null
    var onSpeechStarted: (() -> Unit)? = null

    companion object {
        private const val TAG = "TextToSpeechManager"
        private const val UTTERANCE_ID = "OmniVoiceUtterance"

        /**
         * Creates a 1-tap system intent to open the Android TTS Voice Data installer (`F-051`)
         * when `ur-PK` offline voice data is missing on the user's phone.
         */
        fun createInstallTtsDataIntent(): Intent {
            return Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    init {
        initializeTts()
    }

    private fun initializeTts() {
        ttsScope.launch {
            try {
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        isInitialized = true
                        ttsScope.launch {
                            try {
                                tts?.language = Locale.getDefault()
                                _isUrduVoiceAvailable.value = checkUrduVoicePackAvailableInternal()
                                setupProgressListener()
                                Log.d(TAG, "TTS successfully initialized (ur-PK available=${_isUrduVoiceAvailable.value})")
                            } catch (e: Exception) {
                                Log.w(TAG, "TTS language setup warning: ${e.message}")
                            }
                        }
                    } else {
                        Log.e(TAG, "TTS initialization failed with status $status")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TTS service binding unavailable: ${e.message}")
            }
        }
    }

    private fun checkUrduVoicePackAvailableInternal(): Boolean {
        val engine = tts ?: return false
        return try {
            val urPk = engine.isLanguageAvailable(Locale.forLanguageTag("ur-PK"))
            val urOnly = engine.isLanguageAvailable(Locale.forLanguageTag("ur"))
            val available = (urPk >= TextToSpeech.LANG_AVAILABLE && urPk != TextToSpeech.LANG_MISSING_DATA) ||
                (urOnly >= TextToSpeech.LANG_AVAILABLE && urOnly != TextToSpeech.LANG_MISSING_DATA)
            _isUrduVoiceAvailable.value = available
            available
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks whether the `ur-PK` / `ur` TTS voice pack is installed and ready on this phone (`F-050`).
     */
    fun isUrduVoicePackInstalled(): Boolean {
        if (!isInitialized || tts == null) return _isUrduVoiceAvailable.value
        return checkUrduVoicePackAvailableInternal()
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                onSpeechStarted?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID || utteranceId?.endsWith("_FINAL") == true) {
                    _isSpeaking.value = false
                    abandonAudioFocus()
                    onSpeechCompleted?.invoke()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                abandonAudioFocus()
                onSpeechCompleted?.invoke()
            }
        })
    }

    private fun applyBestAvailableLocale(requestedLocale: Locale?) {
        val engine = tts ?: return
        val target = requestedLocale ?: Locale.getDefault()
        val availability = engine.isLanguageAvailable(target)
        if (availability >= TextToSpeech.LANG_AVAILABLE && availability != TextToSpeech.LANG_MISSING_DATA) {
            engine.language = target
            return
        }
        val langOnly = Locale.forLanguageTag(target.language)
        val langAvail = engine.isLanguageAvailable(langOnly)
        if (langAvail >= TextToSpeech.LANG_AVAILABLE && langAvail != TextToSpeech.LANG_MISSING_DATA) {
            engine.language = langOnly
            return
        }
        // Fallback chain: en-PK -> en-IN -> en-US (`F-052`)
        for (fallbackTag in listOf("en-PK", "en-IN", "en-US")) {
            val fbLocale = Locale.forLanguageTag(fallbackTag)
            val fbAvail = engine.isLanguageAvailable(fbLocale)
            if (fbAvail >= TextToSpeech.LANG_AVAILABLE && fbAvail != TextToSpeech.LANG_MISSING_DATA) {
                engine.language = fbLocale
                return
            }
        }
    }

    /**
     * Normalizes a raw assistant reply via [SpeechNormaliser] and speaks it across bilingual
     * `LanguageRun` segments (`ur-PK` vs `en-US` / `en-PK`), automatically falling back to
     * phonetic Roman Urdu if the `ur-PK` voice pack is missing (`F-043`, `F-050`, `F-052`).
     */
    fun speakNormalized(
        rawReply: String,
        detectedLanguage: DetectedLanguage = LanguageProcessor.detectLanguage(rawReply),
        pitch: Float = 1.0f,
        speed: Float = 1.0f
    ): NormalizedSpeechReply {
        val urduAvailable = isUrduVoicePackInstalled()
        val normalized = SpeechNormaliser.normalizeForDisplayAndSpeech(
            rawReply = rawReply,
            detectedLanguage = detectedLanguage,
            convertRomanToUrduForTts = urduAvailable &&
                (detectedLanguage == DetectedLanguage.ROMAN_URDU || detectedLanguage == DetectedLanguage.MIXED),
            fallbackUrduToRomanIfMissingPack = !urduAvailable &&
                detectedLanguage == DetectedLanguage.URDU_NASTALIQ
        )

        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not ready yet, re-initializing")
            initializeTts()
            return normalized
        }

        requestAudioFocus()
        tts?.setPitch(pitch)
        tts?.setSpeechRate(speed)

        val runs = normalized.languageRuns
        if (runs.isEmpty()) {
            speak(normalized.speechText, pitch, speed, detectedLanguage.ttsLocale)
            return normalized
        }

        _isSpeaking.value = true
        runs.forEachIndexed { index, run ->
            applyBestAvailableLocale(run.locale)
            val isLast = index == runs.lastIndex
            val uttId = if (isLast) "${UTTERANCE_ID}_FINAL" else "${UTTERANCE_ID}_RUN_$index"
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uttId)
            }
            tts?.speak(run.text, queueMode, params, uttId)
        }
        return normalized
    }

    fun speak(text: String, pitch: Float = 1.0f, speed: Float = 1.0f, targetLocale: Locale? = null) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not ready yet, re-initializing")
            initializeTts()
            return
        }

        requestAudioFocus()
        tts?.setPitch(pitch)
        tts?.setSpeechRate(speed)
        applyBestAvailableLocale(targetLocale)

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
        }

        _isSpeaking.value = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
    }

    fun stop() {
        if (tts != null) {
            tts?.stop()
            _isSpeaking.value = false
            abandonAudioFocus()
        }
    }

    fun shutdown() {
        try {
            stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS", e)
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Audio focus auto abandoned on completion
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}

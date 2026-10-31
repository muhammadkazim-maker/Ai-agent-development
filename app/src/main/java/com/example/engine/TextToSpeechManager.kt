package com.example.engine

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Accent Training Mode for OmniVoice AGI Speech Synthesis.
 * Supports Complete Urdu Accent (ur-PK), Complete English Accent (en-US/en-GB),
 * Complete Mixed Urdu-English Accent, and Auto-Detect Trilingual mode.
 */
enum class AccentTrainingMode(
    val title: String,
    val subtitle: String,
    val samplePhrase: String
) {
    AUTO_TRILINGUAL(
        title = "Auto Trilingual (Urdu + Mixed + English)",
        subtitle = "Har जुबान aur lehjay ko khud pehchan kar natural accent mein baat karega",
        samplePhrase = "Assalam-o-Alaikum Boss! Main aapka Super AGI assistant hoon, aur main Urdu, English aur Mixed Urdu mein bilkul natural accent ke sath baat kar sakta hoon."
    ),
    URDU_COMPLETE_ACCENT(
        title = "Urdu Complete Accent (اردو خالص لہجہ)",
        subtitle = "Pure Pakistani Urdu Nastaliq phonetics & neural prosody (ur-PK)",
        samplePhrase = "السلام علیکم باس! میں آپ کا سپر اے جی آئی اسسٹنٹ ہوں اور خالص اردو لہجے میں آپ کے ہر حکم کی تعمیل کے لیے حاضر ہوں۔"
    ),
    MIXED_URDU_COMPLETE_ACCENT(
        title = "Mixed Urdu + English Complete Accent",
        subtitle = "Seamless code-switching between Urdu & English technical vocabulary",
        samplePhrase = "Jee Boss, main aapke complex se complex tasks, coding aur mobile automation super speed aur natural mixed accent ke sath handle kar sakta hoon."
    ),
    ENGLISH_COMPLETE_ACCENT(
        title = "English Complete Accent (US / UK Neural)",
        subtitle = "Crisp native English articulation for global & technical communication",
        samplePhrase = "Hello Boss! My neural speech engine is calibrated with a crystal-clear English accent for advanced AGI reasoning and full device control."
    )
}

/**
 * Result of probing on-device Text-To-Speech voice packs (`F-045`–`F-052`).
 */
data class TtsVoicePackStatus(
    val isInitialized: Boolean = false,
    val isUrduPkInstalled: Boolean = false,
    val isEnglishPkInstalled: Boolean = false,
    val isEnglishInInstalled: Boolean = false,
    val isEnglishUsInstalled: Boolean = false,
    val activeLocaleTag: String = "en-US",
    val activeVoiceName: String = "Default Neural Voice",
    val guidanceBanner: String? = null
)

class TextToSpeechManager(
    private val context: Context,
    var onSpeechStarted: (() -> Unit)? = null,
    var onSpeechCompleted: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "TTSManager"

        private val _activeAccentMode = MutableStateFlow(AccentTrainingMode.AUTO_TRILINGUAL)
        val activeAccentMode: StateFlow<AccentTrainingMode> = _activeAccentMode.asStateFlow()

        fun setAccentTrainingMode(mode: AccentTrainingMode) {
            _activeAccentMode.value = mode
        }

        fun createInstallTtsDataIntent(): Intent {
            return Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _voicePackStatus = MutableStateFlow(TtsVoicePackStatus())
    val voicePackStatus: StateFlow<TtsVoicePackStatus> = _voicePackStatus.asStateFlow()

    init {
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )

                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            onSpeechStarted?.invoke()
                        }

                        override fun onDone(utteranceId: String?) {
                            val currentTurn = AudioOutputManager.activeTurnId.value
                            if (currentTurn != null) {
                                AudioOutputManager.completeTurnOutput(currentTurn)
                            }
                            onSpeechCompleted?.invoke()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            val currentTurn = AudioOutputManager.activeTurnId.value
                            if (currentTurn != null) {
                                AudioOutputManager.completeTurnOutput(currentTurn)
                            }
                            onSpeechCompleted?.invoke()
                        }
                    })

                    probeAvailableVoicePacks()
                } else {
                    Log.e(TAG, "TTS Initialization failed with status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing TextToSpeech: ${e.message}")
        }
    }

    /**
     * Probes installed TTS voice packs (`ur-PK`, `en-PK`, `en-IN`, `en-US`) and surfaces
     * a helpful banner if the offline Urdu (`ur-PK`) voice pack is missing (`F-052`).
     */
    fun probeAvailableVoicePacks(): TtsVoicePackStatus {
        val engine = tts ?: return _voicePackStatus.value
        val urPk = isLocaleSupported(engine, Locale.forLanguageTag("ur-PK")) ||
            isLocaleSupported(engine, Locale.forLanguageTag("ur-IN")) ||
            isLocaleSupported(engine, Locale.forLanguageTag("ur"))
        val enPk = isLocaleSupported(engine, Locale.forLanguageTag("en-PK"))
        val enIn = isLocaleSupported(engine, Locale.forLanguageTag("en-IN")) ||
            isLocaleSupported(engine, Locale.forLanguageTag("hi-IN"))
        val enUs = isLocaleSupported(engine, Locale.US)

        val chosenLocale = applyBestAvailableLocale(engine, Locale.forLanguageTag("ur-PK"))
        val chosenVoiceName = try {
            engine.voice?.name ?: chosenLocale.toLanguageTag()
        } catch (_: Exception) {
            chosenLocale.toLanguageTag()
        }

        val banner = if (!urPk) {
            "Phonetic Urdu + English Neural Accent active. For offline nastaliq voice pack, install Google TTS Urdu (Pakistan)."
        } else {
            null
        }

        val status = TtsVoicePackStatus(
            isInitialized = isInitialized,
            isUrduPkInstalled = urPk,
            isEnglishPkInstalled = enPk,
            isEnglishInInstalled = enIn,
            isEnglishUsInstalled = enUs,
            activeLocaleTag = chosenLocale.toLanguageTag(),
            activeVoiceName = chosenVoiceName,
            guidanceBanner = banner
        )
        _voicePackStatus.value = status
        return status
    }

    /**
     * Opens Android's system TTS voice data installer so the user can download the offline `ur-PK` pack (`F-052`).
     */
    fun createInstallTtsDataIntent(): Intent {
        return Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Normalizes a raw assistant reply via [SpeechNormaliser] and speaks it across bilingual
     * `LanguageRun` segments (`ur-PK` for Urdu script, `en-PK`/`en-IN`/`en-US` for Latin/English),
     * guarded by [AudioOutputManager] so duplicate turns or simultaneous PCM+TTS never occur (`F-039`, `F-043`).
     */
    fun speakNormalized(
        rawReply: String,
        detectedLanguage: DetectedLanguage = LanguageProcessor.detectLanguage(rawReply),
        turnId: String = UUID.randomUUID().toString(),
        pitch: Float = 1.0f,
        speechRate: Float = 0.98f
    ): NormalizedSpeechReply {
        val status = _voicePackStatus.value
        val mode = _activeAccentMode.value

        val effectiveDetectedLang = when (mode) {
            AccentTrainingMode.URDU_COMPLETE_ACCENT -> DetectedLanguage.URDU_NASTALIQ
            AccentTrainingMode.ENGLISH_COMPLETE_ACCENT -> DetectedLanguage.ENGLISH
            AccentTrainingMode.MIXED_URDU_COMPLETE_ACCENT ->
                if (detectedLanguage == DetectedLanguage.ENGLISH) DetectedLanguage.ROMAN_URDU else detectedLanguage
            AccentTrainingMode.AUTO_TRILINGUAL -> detectedLanguage
        }

        val shouldConvertRomanToUrdu = status.isUrduPkInstalled && (
            mode == AccentTrainingMode.URDU_COMPLETE_ACCENT ||
                effectiveDetectedLang == DetectedLanguage.URDU_NASTALIQ
            )

        val normalized = SpeechNormaliser.normalizeForDisplayAndSpeech(
            rawReply = rawReply,
            detectedLanguage = effectiveDetectedLang,
            convertRomanToUrduForTts = shouldConvertRomanToUrdu,
            fallbackUrduToRomanIfMissingPack = !status.isUrduPkInstalled &&
                (effectiveDetectedLang == DetectedLanguage.URDU_NASTALIQ)
        )

        if (!isInitialized || normalized.speechText.isBlank()) {
            onSpeechCompleted?.invoke()
            return normalized
        }

        // Enforce single-owner audio output & deduplication by turnId
        val acquired = AudioOutputManager.acquireTurnOutput(
            turnId = turnId,
            modality = AudioOutputManager.OutputModality.LOCAL_TTS,
            ttsManager = this
        )
        if (!acquired) {
            return normalized
        }

        AudioOutputManager.requestAssistantAudioFocus(context)
        val engine = tts ?: return normalized

        // Slightly warmer, human-like prosody for Urdu / Mixed vs crisp rate for English
        val tunedPitch = when (effectiveDetectedLang) {
            DetectedLanguage.URDU_NASTALIQ, DetectedLanguage.ROMAN_URDU, DetectedLanguage.MIXED ->
                pitch.coerceIn(0.95f, 1.08f)
            DetectedLanguage.ENGLISH ->
                pitch.coerceIn(0.95f, 1.05f)
        }
        val tunedRate = when (effectiveDetectedLang) {
            DetectedLanguage.URDU_NASTALIQ, DetectedLanguage.ROMAN_URDU, DetectedLanguage.MIXED ->
                speechRate.coerceIn(0.92f, 1.04f)
            DetectedLanguage.ENGLISH ->
                speechRate.coerceIn(0.96f, 1.06f)
        }

        engine.setPitch(tunedPitch)
        engine.setSpeechRate(tunedRate)

        val runs = normalized.languageRuns.ifEmpty {
            listOf(LanguageRun(normalized.speechText, false, effectiveDetectedLang.ttsLocale))
        }

        runs.forEachIndexed { index, run ->
            val targetLocale = when {
                mode == AccentTrainingMode.ENGLISH_COMPLETE_ACCENT -> Locale.US
                mode == AccentTrainingMode.URDU_COMPLETE_ACCENT -> Locale.forLanguageTag("ur-PK")
                run.isUrdu && status.isUrduPkInstalled -> Locale.forLanguageTag("ur-PK")
                effectiveDetectedLang == DetectedLanguage.ROMAN_URDU ||
                    effectiveDetectedLang == DetectedLanguage.MIXED -> Locale.forLanguageTag("en-PK")
                else -> run.locale
            }
            applyBestAvailableLocale(engine, targetLocale)
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val uttId = if (index == runs.lastIndex) turnId else "${turnId}_part_$index"
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uttId)
            }
            engine.speak(run.text, queueMode, params, uttId)
        }

        return normalized
    }

    /**
     * Speaks any text using the trained Urdu / English / Mixed Roman-Urdu accent pipeline!
     * Routes automatically through [speakNormalized] so every caller in the app gets
     * authentic pronunciation and multi-run bilingual voice switching.
     */
    fun speak(
        text: String,
        pitch: Float = 1.0f,
        speechRate: Float = 0.98f,
        locale: Locale = Locale.US,
        turnId: String = UUID.randomUUID().toString()
    ) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized yet, invoking completion callback")
            onSpeechCompleted?.invoke()
            return
        }

        val detected = LanguageProcessor.detectLanguage(text)
        val effectiveLang = when {
            locale.language.equals("ur", ignoreCase = true) -> DetectedLanguage.URDU_NASTALIQ
            detected != DetectedLanguage.ENGLISH -> detected
            locale.toLanguageTag().equals("en-PK", ignoreCase = true) -> DetectedLanguage.ROMAN_URDU
            else -> DetectedLanguage.ENGLISH
        }

        speakNormalized(
            rawReply = text,
            detectedLanguage = effectiveLang,
            turnId = turnId,
            pitch = pitch,
            speechRate = speechRate
        )
    }

    /**
     * Selects the highest-quality neural/local [Voice] and [Locale] for Urdu (`ur-PK`, `ur-IN`, `hi-IN`),
     * Pakistani/Subcontinental English (`en-PK`, `en-IN`), or US/UK English (`en-US`, `en-GB`).
     */
    private fun applyBestAvailableLocale(engine: TextToSpeech, preferred: Locale): Locale {
        val candidates = when {
            preferred.language.equals("ur", ignoreCase = true) -> listOf(
                Locale.forLanguageTag("ur-PK"),
                Locale.forLanguageTag("ur-IN"),
                Locale.forLanguageTag("ur"),
                Locale.forLanguageTag("hi-IN"),
                Locale.forLanguageTag("en-PK"),
                Locale.forLanguageTag("en-IN"),
                Locale.US
            )
            preferred.toLanguageTag().equals("en-PK", ignoreCase = true) -> listOf(
                Locale.forLanguageTag("en-PK"),
                Locale.forLanguageTag("ur-PK"),
                Locale.forLanguageTag("en-IN"),
                Locale.forLanguageTag("hi-IN"),
                Locale.US
            )
            else -> listOf(
                preferred,
                Locale.US,
                Locale.UK,
                Locale.forLanguageTag("en-IN")
            )
        }

        for (candidate in candidates) {
            if (isLocaleSupported(engine, candidate)) {
                engine.language = candidate
                selectHighestQualityVoiceForLocale(engine, candidate)
                return candidate
            }
        }
        engine.language = Locale.US
        selectHighestQualityVoiceForLocale(engine, Locale.US)
        return Locale.US
    }

    private fun selectHighestQualityVoiceForLocale(engine: TextToSpeech, targetLocale: Locale) {
        try {
            val availableVoices = engine.voices ?: return
            val matchingVoices = availableVoices.filter { voice ->
                voice.locale != null &&
                    voice.locale.language.equals(targetLocale.language, ignoreCase = true) &&
                    (targetLocale.country.isBlank() || voice.locale.country.equals(targetLocale.country, ignoreCase = true)) &&
                    !voice.isNetworkConnectionRequired
            }.ifEmpty {
                availableVoices.filter { voice ->
                    voice.locale != null &&
                        voice.locale.language.equals(targetLocale.language, ignoreCase = true)
                }
            }

            val bestVoice: Voice? = matchingVoices.maxByOrNull { v ->
                var score = v.quality
                if (!v.isNetworkConnectionRequired) score += 100
                if (v.name.contains("neural", ignoreCase = true) || v.name.contains("wavenet", ignoreCase = true) || v.name.contains("local", ignoreCase = true)) {
                    score += 50
                }
                score
            }

            if (bestVoice != null) {
                engine.voice = bestVoice
            }
        } catch (_: Exception) {
        }
    }

    private fun isLocaleSupported(engine: TextToSpeech, locale: Locale): Boolean {
        return try {
            val res = engine.isLanguageAvailable(locale)
            res >= TextToSpeech.LANG_AVAILABLE
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
    }
}

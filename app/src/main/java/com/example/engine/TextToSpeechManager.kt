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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

/**
 * Command-correlated lifecycle states for TTS speech output.
 */
enum class TtsOutputLifecycleState {
    REQUESTED,
    QUEUED,
    STARTED,
    COMPLETED,
    INTERRUPTED,
    FAILED
}

/**
 * Immutable speech configuration snapshot correlated with a specific command and session generation.
 * Ensures voice, locale, speech rate, pitch, and provider are locked for the duration of an utterance
 * and audited across cloud and local fallback paths.
 */
data class SpeechConfigSnapshot(
    val commandId: String,
    val turnId: String,
    val sessionGeneration: Long,
    val requestedOutputProvider: String,
    val actualOutputProvider: String,
    val requestedVoiceId: String,
    val resolvedVoiceId: String,
    val resolvedLocaleTag: String,
    val speechRate: Float,
    val pitch: Float,
    val chosenReplyLanguage: DetectedLanguage,
    val languageSelectionReason: String,
    val fallbackOrReconnectReason: String = "NONE"
)

/**
 * Single authoritative speech and language preference store shared across
 * [OmniLiveSessionEngine], [TextToSpeechManager], and [com.example.ui.viewmodel.VoiceAssistantViewModel].
 * Persisted to SharedPreferences so voice persona, speech rate, pitch, language policy, and
 * active conversation language survive app switches, timeouts, reconnects, and provider recreation.
 */
object SpeechPreferenceStore {
    private const val PREFS_NAME = "omni_authoritative_speech_prefs"
    private const val KEY_LIVE_VOICE = "selected_live_voice"
    private const val KEY_LANG_MODE = "preferred_language_mode"
    private const val KEY_CONVERSATION_LANG = "last_conversation_language"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_PITCH = "speech_pitch"
    private const val KEY_ACCENT_MODE = "accent_training_mode"

    private val sessionGenCounter = AtomicLong(1L)

    @Volatile
    private var cachedLiveVoiceName: String = "Aoede"

    @Volatile
    private var cachedLanguageMode: LanguageProcessor.UserLanguagePreferenceMode =
        LanguageProcessor.UserLanguagePreferenceMode.AUTO

    @Volatile
    private var cachedConversationLanguage: DetectedLanguage? = null

    @Volatile
    private var cachedSpeechRate: Float = 1.0f

    @Volatile
    private var cachedPitch: Float = 1.0f

    @Volatile
    private var isLoadedFromDisk: Boolean = false

    @Synchronized
    fun ensureLoaded(context: Context?) {
        if (isLoadedFromDisk || context == null) return
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cachedLiveVoiceName = prefs.getString(KEY_LIVE_VOICE, "Aoede")?.takeIf { it.isNotBlank() } ?: "Aoede"
            cachedLanguageMode = LanguageProcessor.UserLanguagePreferenceMode.fromIdOrLabel(
                prefs.getString(KEY_LANG_MODE, LanguageProcessor.UserLanguagePreferenceMode.AUTO.id)
            )
            val convLangName = prefs.getString(KEY_CONVERSATION_LANG, null)
            cachedConversationLanguage = convLangName?.let { name ->
                DetectedLanguage.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
            cachedSpeechRate = prefs.getFloat(KEY_SPEECH_RATE, 1.0f).coerceIn(0.5f, 2.0f)
            cachedPitch = prefs.getFloat(KEY_PITCH, 1.0f).coerceIn(0.5f, 2.0f)
            val savedAccent = prefs.getString(KEY_ACCENT_MODE, null)
            if (!savedAccent.isNullOrBlank()) {
                AccentTrainingMode.entries.firstOrNull { it.name == savedAccent }?.let {
                    TextToSpeechManager.setAccentTrainingMode(it, context = null)
                }
            }
            isLoadedFromDisk = true
        } catch (_: Exception) {
        }
    }

    fun currentSessionGeneration(): Long = sessionGenCounter.get()

    fun nextSessionGeneration(): Long = sessionGenCounter.incrementAndGet()

    @Synchronized
    fun setSelectedLiveVoice(context: Context?, voiceName: String) {
        ensureLoaded(context)
        val clean = voiceName.trim().ifBlank { "Aoede" }
        cachedLiveVoiceName = clean
        if (context != null) {
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LIVE_VOICE, clean)
                    .apply()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun getSelectedLiveVoice(context: Context? = null): String {
        ensureLoaded(context)
        return cachedLiveVoiceName
    }

    @Synchronized
    fun setPreferredLanguageMode(context: Context?, mode: LanguageProcessor.UserLanguagePreferenceMode) {
        ensureLoaded(context)
        cachedLanguageMode = mode
        if (context != null) {
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LANG_MODE, mode.id)
                    .apply()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun getPreferredLanguageMode(context: Context? = null): LanguageProcessor.UserLanguagePreferenceMode {
        ensureLoaded(context)
        return cachedLanguageMode
    }

    @Synchronized
    fun setConversationLanguage(context: Context?, language: DetectedLanguage?) {
        ensureLoaded(context)
        cachedConversationLanguage = language
        if (context != null) {
            try {
                val editor = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                if (language != null) {
                    editor.putString(KEY_CONVERSATION_LANG, language.name)
                } else {
                    editor.remove(KEY_CONVERSATION_LANG)
                }
                editor.apply()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun getConversationLanguage(context: Context? = null): DetectedLanguage? {
        ensureLoaded(context)
        return cachedConversationLanguage
    }

    /**
     * Updates the persisted conversation language ONLY if [utterance] contains genuine conversational
     * language markers (Urdu script, Roman Urdu vocabulary, or genuine English sentence words).
     * Never overwrites conversation language when [utterance] is empty or contains only app/tool/status tokens.
     */
    @Synchronized
    fun recordUtteranceAndResolveReplyLanguage(
        context: Context?,
        utterance: String
    ): LanguageProcessor.LanguageResolution {
        ensureLoaded(context)
        val evidence = LanguageProcessor.detectConversationalLanguageEvidence(utterance)
        if (evidence != null) {
            setConversationLanguage(context, evidence.language)
        }
        return LanguageProcessor.resolveReplyLanguage(
            utterance = utterance,
            preferredMode = cachedLanguageMode,
            conversationLanguage = cachedConversationLanguage
        )
    }

    @Synchronized
    fun resolveReplyLanguageWithoutMutatingHistory(
        context: Context?,
        utterance: String
    ): LanguageProcessor.LanguageResolution {
        ensureLoaded(context)
        return LanguageProcessor.resolveReplyLanguage(
            utterance = utterance,
            preferredMode = cachedLanguageMode,
            conversationLanguage = cachedConversationLanguage
        )
    }

    @Synchronized
    fun setSpeechRateAndPitch(context: Context?, speechRate: Float, pitch: Float) {
        ensureLoaded(context)
        cachedSpeechRate = speechRate.coerceIn(0.5f, 2.0f)
        cachedPitch = pitch.coerceIn(0.5f, 2.0f)
        if (context != null) {
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat(KEY_SPEECH_RATE, cachedSpeechRate)
                    .putFloat(KEY_PITCH, cachedPitch)
                    .apply()
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun getSpeechRate(context: Context? = null): Float {
        ensureLoaded(context)
        return cachedSpeechRate
    }

    @Synchronized
    fun getPitch(context: Context? = null): Float {
        ensureLoaded(context)
        return cachedPitch
    }

    @Synchronized
    fun resetForTest(context: Context? = null) {
        cachedLiveVoiceName = "Aoede"
        cachedLanguageMode = LanguageProcessor.UserLanguagePreferenceMode.AUTO
        cachedConversationLanguage = null
        cachedSpeechRate = 1.0f
        cachedPitch = 1.0f
        sessionGenCounter.set(1L)
        isLoadedFromDisk = true
        if (context != null) {
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            } catch (_: Exception) {
            }
        }
    }
}

data class TtsLifecycleEvent(
    val commandId: String,
    val turnId: String,
    val utteranceId: String,
    val state: TtsOutputLifecycleState,
    val segmentIndex: Int = 0,
    val totalSegments: Int = 1,
    val reason: String = "",
    val configSnapshot: SpeechConfigSnapshot? = null
)

class TextToSpeechManager(
    private val context: Context,
    var onSpeechStarted: (() -> Unit)? = null,
    var onSpeechCompleted: (() -> Unit)? = null,
    var onSpeechError: ((String) -> Unit)? = null,
    var onLifecycleEvent: ((TtsLifecycleEvent) -> Unit)? = null
) {

    companion object {
        private const val TAG = "TTSManager"
        private const val MAX_BOUNDED_INIT_QUEUE_SIZE = 4

        private val _activeAccentMode = MutableStateFlow(AccentTrainingMode.AUTO_TRILINGUAL)
        val activeAccentMode: StateFlow<AccentTrainingMode> = _activeAccentMode.asStateFlow()

        fun setAccentTrainingMode(mode: AccentTrainingMode, context: Context? = null) {
            _activeAccentMode.value = mode
            if (context != null) {
                try {
                    context.applicationContext
                        .getSharedPreferences("omni_authoritative_speech_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("accent_training_mode", mode.name)
                        .apply()
                } catch (_: Exception) {
                }
            }
        }

        fun createInstallTtsDataIntent(): Intent {
            return Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    private data class PendingInitTtsRequest(
        val rawReply: String,
        val detectedLanguage: DetectedLanguage,
        val turnId: String,
        val commandId: String,
        val pitch: Float,
        val speechRate: Float,
        val normalized: NormalizedSpeechReply,
        val requestedSnapshot: SpeechConfigSnapshot,
        val onCommandStateChanged: ((TtsLifecycleEvent) -> Unit)?
    )

    private data class ActiveTtsTurnRecord(
        val turnId: String,
        val commandId: String,
        val generation: Long,
        val sessionGeneration: Long,
        val segmentUtteranceIds: List<String>,
        val finalUtteranceId: String,
        val spokenText: String,
        val configSnapshot: SpeechConfigSnapshot,
        val startedSegments: MutableSet<String> = mutableSetOf(),
        val completedSegments: MutableSet<String> = mutableSetOf(),
        val onCommandStateChanged: ((TtsLifecycleEvent) -> Unit)? = null
    )

    private val lock = Any()
    private var tts: TextToSpeech? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isInitializationFailed = false

    private val initGenerationCounter = AtomicLong(0L)

    @Volatile
    private var activeInitGeneration: Long = 0L

    private val turnGenerationCounter = AtomicLong(0L)
    private val pendingInitQueue = ArrayDeque<PendingInitTtsRequest>()
    private val utteranceToTurnMap = ConcurrentHashMap<String, ActiveTtsTurnRecord>()

    @Volatile
    private var activeTurnRecord: ActiveTtsTurnRecord? = null

    private val startedCountAtomic = AtomicInteger(0)
    private val completedCountAtomic = AtomicInteger(0)
    private val errorCountAtomic = AtomicInteger(0)
    private val interruptedCountAtomic = AtomicInteger(0)
    private val staleCallbacksRejectedAtomic = AtomicInteger(0)
    private val staleInitCallbacksRejectedAtomic = AtomicInteger(0)

    @Volatile
    var lastUtteranceId: String = ""
        private set

    @Volatile
    var lastCommandId: String = ""
        private set

    @Volatile
    var lastErrorReason: String = ""
        private set

    @Volatile
    var lastConfigSnapshot: SpeechConfigSnapshot? = null
        private set

    @Volatile
    var lastResolvedVoiceId: String = "NONE"
        private set

    @Volatile
    var lastResolvedLocaleTag: String = "en-US"
        private set

    @Volatile
    var lastFallbackLimitationNote: String = "NONE"
        private set

    private val _appliedConfigSnapshotsForTest = java.util.concurrent.CopyOnWriteArrayList<SpeechConfigSnapshot>()
    val appliedConfigSnapshotsForTest: List<SpeechConfigSnapshot>
        get() = _appliedConfigSnapshotsForTest.toList()

    private val _midUtteranceConfigMutationsCount = AtomicInteger(0)
    fun getMidUtteranceConfigMutationsCount(): Int = _midUtteranceConfigMutationsCount.get()

    /**
     * Optional test interceptor for verifying `engine.speak()` calls and simulating `TextToSpeech.ERROR`
     * or multi-segment synthesis in Robolectric unit tests without hardware audio drivers.
     */
    @Volatile
    var speakEngineInterceptorForTest: ((text: String, queueMode: Int, utteranceId: String) -> Int)? = null

    private val _voicePackStatus = MutableStateFlow(TtsVoicePackStatus())
    val voicePackStatus: StateFlow<TtsVoicePackStatus> = _voicePackStatus.asStateFlow()

    init {
        SpeechPreferenceStore.ensureLoaded(context)
        initTts()
    }

    fun getStartedCount(): Int = startedCountAtomic.get()
    fun getCompletedCount(): Int = completedCountAtomic.get()
    fun getErrorCount(): Int = errorCountAtomic.get()
    fun getInterruptedCount(): Int = interruptedCountAtomic.get()
    fun getStaleCallbacksRejectedCount(): Int = staleCallbacksRejectedAtomic.get()
    fun getStaleInitCallbacksRejectedCount(): Int = staleInitCallbacksRejectedAtomic.get()
    fun getPendingInitQueueSize(): Int = synchronized(lock) { pendingInitQueue.size }
    fun getActiveTurnCommandId(): String? = activeTurnRecord?.commandId
    fun getActiveTurnId(): String? = activeTurnRecord?.turnId

    fun setInitializedStateForTest(
        initialized: Boolean,
        initFailed: Boolean = false,
        urduPkInstalled: Boolean = true,
        englishPkInstalled: Boolean = true
    ) {
        synchronized(lock) {
            isInitialized = initialized
            isInitializationFailed = initFailed
            _voicePackStatus.value = _voicePackStatus.value.copy(
                isInitialized = initialized,
                isUrduPkInstalled = urduPkInstalled,
                isEnglishPkInstalled = englishPkInstalled
            )
        }
    }

    fun simulateInitCallbackForTest(status: Int, initGeneration: Long = activeInitGeneration) {
        handleTtsInitStatus(status, initGeneration)
    }

    private fun initTts() {
        val myInitGen = initGenerationCounter.incrementAndGet()
        activeInitGeneration = myInitGen
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                handleTtsInitStatus(status, myInitGen)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing TextToSpeech: ${e.message}")
            handleTtsInitStatus(TextToSpeech.ERROR, myInitGen)
        }
    }

    private fun handleTtsInitStatus(status: Int, callbackInitGeneration: Long = activeInitGeneration) {
        val requestsToDispatch = mutableListOf<PendingInitTtsRequest>()
        val requestsToFail = mutableListOf<PendingInitTtsRequest>()

        synchronized(lock) {
            if (callbackInitGeneration != activeInitGeneration) {
                staleInitCallbacksRejectedAtomic.incrementAndGet()
                Log.w(
                    TAG,
                    "Rejected stale TTS OnInitListener callback (callbackGen=$callbackInitGeneration, activeGen=$activeInitGeneration)"
                )
                return
            }
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                isInitializationFailed = false
                try {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            handleUtteranceStart(utteranceId)
                        }

                        override fun onDone(utteranceId: String?) {
                            handleUtteranceDone(utteranceId)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            handleUtteranceError(utteranceId, TextToSpeech.ERROR)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            handleUtteranceError(utteranceId, errorCode)
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            handleUtteranceStop(utteranceId, interrupted)
                        }
                    })
                } catch (_: Exception) {
                }
                while (pendingInitQueue.isNotEmpty()) {
                    requestsToDispatch.add(pendingInitQueue.removeFirst())
                }
            } else {
                isInitialized = false
                isInitializationFailed = true
                Log.e(TAG, "TTS Initialization failed with status: $status")
                while (pendingInitQueue.isNotEmpty()) {
                    requestsToFail.add(pendingInitQueue.removeFirst())
                }
            }
        }

        if (status == TextToSpeech.SUCCESS) {
            probeAvailableVoicePacks()
            for (req in requestsToDispatch) {
                dispatchInitializedNormalizedRequest(req)
            }
        } else {
            AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(
                immediateCooldownClear = true,
                forceRelease = true
            )
            for (req in requestsToFail) {
                errorCountAtomic.incrementAndGet()
                lastErrorReason = "TTS_INIT_FAILED_$status"
                emitLifecycle(
                    req.onCommandStateChanged,
                    TtsLifecycleEvent(
                        commandId = req.commandId,
                        turnId = req.turnId,
                        utteranceId = req.turnId,
                        state = TtsOutputLifecycleState.FAILED,
                        reason = "TTS_INIT_FAILED_$status",
                        configSnapshot = req.requestedSnapshot
                    )
                )
                onSpeechError?.invoke("TTS_INIT_FAILED_$status")
            }
        }
    }

    private val commandScopedLifecycleListeners = ConcurrentHashMap<String, (TtsLifecycleEvent) -> Unit>()

    fun registerCommandLifecycleListener(
        commandId: String,
        listener: (TtsLifecycleEvent) -> Unit
    ) {
        val cleanId = commandId.trim()
        if (cleanId.isEmpty()) return
        if (commandScopedLifecycleListeners.size >= 16) {
            val oldestKey = commandScopedLifecycleListeners.keys().asSequence().firstOrNull()
            if (oldestKey != null) {
                commandScopedLifecycleListeners.remove(oldestKey)
            }
        }
        commandScopedLifecycleListeners[cleanId] = listener
    }

    fun unregisterCommandLifecycleListener(commandId: String) {
        val cleanId = commandId.trim()
        if (cleanId.isNotEmpty()) {
            commandScopedLifecycleListeners.remove(cleanId)
        }
    }

    fun getRegisteredCommandListenerCount(): Int = commandScopedLifecycleListeners.size

    private fun emitLifecycle(
        perCallListener: ((TtsLifecycleEvent) -> Unit)?,
        event: TtsLifecycleEvent
    ) {
        lastCommandId = event.commandId
        lastUtteranceId = event.utteranceId
        if (event.configSnapshot != null) {
            lastConfigSnapshot = event.configSnapshot
        }
        perCallListener?.invoke(event)
        val cmdKey = event.commandId.trim()
        if (cmdKey.isNotEmpty()) {
            val scopedListener = commandScopedLifecycleListeners[cmdKey]
            scopedListener?.invoke(event)
            if (event.state == TtsOutputLifecycleState.COMPLETED ||
                event.state == TtsOutputLifecycleState.FAILED ||
                event.state == TtsOutputLifecycleState.INTERRUPTED
            ) {
                commandScopedLifecycleListeners.remove(cmdKey)
            }
        }
        onLifecycleEvent?.invoke(event)
    }

    /**
     * Handles `UtteranceProgressListener.onStart(utteranceId)` with strict turn-generation,
     * session-generation, and multi-segment correlation. Stale callbacks from older turns/sessions are rejected.
     */
    fun handleUtteranceStart(utteranceId: String?, callbackSessionGeneration: Long? = null) {
        val uttId = utteranceId?.trim().orEmpty()
        if (uttId.isEmpty()) return

        val record: ActiveTtsTurnRecord
        val isFirstSegment: Boolean
        val segIndex: Int
        synchronized(lock) {
            val matched = utteranceToTurnMap[uttId]
            val current = activeTurnRecord
            val sessionMismatch = callbackSessionGeneration != null &&
                current != null &&
                callbackSessionGeneration != current.sessionGeneration
            if (matched == null || current == null || current.generation != matched.generation || sessionMismatch) {
                staleCallbacksRejectedAtomic.incrementAndGet()
                Log.d(
                    TAG,
                    "Ignored stale TTS onStart for utteranceId=$uttId (matchedGen=${matched?.generation}, activeGen=${current?.generation})"
                )
                return
            }
            record = matched
            isFirstSegment = record.startedSegments.isEmpty()
            record.startedSegments.add(uttId)
            segIndex = record.segmentUtteranceIds.indexOf(uttId).coerceAtLeast(0)
        }

        if (isFirstSegment) {
            startedCountAtomic.incrementAndGet()
            AssistantAudioStateMachine.shared.notifyAssistantSpeechStarted(
                spokenText = record.spokenText,
                source = "LOCAL_TTS",
                ownerToken = record.turnId
            )
            emitLifecycle(
                record.onCommandStateChanged,
                TtsLifecycleEvent(
                    commandId = record.commandId,
                    turnId = record.turnId,
                    utteranceId = uttId,
                    state = TtsOutputLifecycleState.STARTED,
                    segmentIndex = segIndex,
                    totalSegments = record.segmentUtteranceIds.size,
                    reason = "TTS_SEGMENT_STARTED",
                    configSnapshot = record.configSnapshot
                )
            )
            onSpeechStarted?.invoke()
        }
    }

    /**
     * Handles `UtteranceProgressListener.onDone(utteranceId)`.
     * For multilingual turns with multiple segments, completes the turn and releases speaking
     * suppression ONLY after the final segment completes. Stale callbacks from old turns/sessions are rejected.
     */
    fun handleUtteranceDone(utteranceId: String?, callbackSessionGeneration: Long? = null) {
        val uttId = utteranceId?.trim().orEmpty()
        if (uttId.isEmpty()) return

        val record: ActiveTtsTurnRecord
        val isFinalTurnCompletion: Boolean
        val segIndex: Int
        synchronized(lock) {
            val matched = utteranceToTurnMap[uttId]
            val current = activeTurnRecord
            val sessionMismatch = callbackSessionGeneration != null &&
                current != null &&
                callbackSessionGeneration != current.sessionGeneration
            if (matched == null || current == null || current.generation != matched.generation || sessionMismatch) {
                staleCallbacksRejectedAtomic.incrementAndGet()
                Log.d(
                    TAG,
                    "Ignored stale TTS onDone for utteranceId=$uttId (matchedGen=${matched?.generation}, activeGen=${current?.generation})"
                )
                return
            }
            record = matched
            record.completedSegments.add(uttId)
            segIndex = record.segmentUtteranceIds.indexOf(uttId).coerceAtLeast(0)
            isFinalTurnCompletion =
                uttId == record.finalUtteranceId &&
                    record.completedSegments.containsAll(record.segmentUtteranceIds)
            if (isFinalTurnCompletion) {
                activeTurnRecord = null
                for (segId in record.segmentUtteranceIds) {
                    utteranceToTurnMap.remove(segId)
                }
            }
        }

        if (!isFinalTurnCompletion) {
            // Intermediate multilingual segment finished; keep speaking suppression and voice config locked!
            return
        }

        completedCountAtomic.incrementAndGet()
        AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(ownerToken = record.turnId)
        AudioOutputManager.completeTurnOutput(record.turnId)
        emitLifecycle(
            record.onCommandStateChanged,
            TtsLifecycleEvent(
                commandId = record.commandId,
                turnId = record.turnId,
                utteranceId = uttId,
                state = TtsOutputLifecycleState.COMPLETED,
                segmentIndex = segIndex,
                totalSegments = record.segmentUtteranceIds.size,
                reason = "FINAL_SEGMENT_COMPLETED",
                configSnapshot = record.configSnapshot
            )
        )
        onSpeechCompleted?.invoke()
    }

    /**
     * Handles `UtteranceProgressListener.onError(utteranceId, errorCode)`.
     * Ignores stale turns; for the active turn, cleans up output ownership and speaking suppression
     * immediately so suppression never remains stuck.
     */
    fun handleUtteranceError(
        utteranceId: String?,
        errorCode: Int = TextToSpeech.ERROR,
        callbackSessionGeneration: Long? = null
    ) {
        val uttId = utteranceId?.trim().orEmpty()
        val record: ActiveTtsTurnRecord
        synchronized(lock) {
            val matched = if (uttId.isNotEmpty()) utteranceToTurnMap[uttId] else activeTurnRecord
            val current = activeTurnRecord
            val sessionMismatch = callbackSessionGeneration != null &&
                current != null &&
                callbackSessionGeneration != current.sessionGeneration
            if (matched == null || current == null || current.generation != matched.generation || sessionMismatch) {
                staleCallbacksRejectedAtomic.incrementAndGet()
                Log.d(TAG, "Ignored stale TTS onError for utteranceId=$uttId")
                return
            }
            record = matched
            activeTurnRecord = null
            for (segId in record.segmentUtteranceIds) {
                utteranceToTurnMap.remove(segId)
            }
        }

        errorCountAtomic.incrementAndGet()
        lastErrorReason = "TTS_UTTERANCE_ERROR_$errorCode"
        AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(
            immediateCooldownClear = true,
            ownerToken = record.turnId,
            forceRelease = true
        )
        AudioOutputManager.completeTurnOutput(record.turnId)
        emitLifecycle(
            record.onCommandStateChanged,
            TtsLifecycleEvent(
                commandId = record.commandId,
                turnId = record.turnId,
                utteranceId = uttId.ifBlank { record.finalUtteranceId },
                state = TtsOutputLifecycleState.FAILED,
                totalSegments = record.segmentUtteranceIds.size,
                reason = "TTS_UTTERANCE_ERROR_$errorCode",
                configSnapshot = record.configSnapshot
            )
        )
        onSpeechError?.invoke("TTS_UTTERANCE_ERROR_$errorCode")
    }

    /**
     * Handles `UtteranceProgressListener.onStop(utteranceId, interrupted)`.
     */
    fun handleUtteranceStop(
        utteranceId: String?,
        interrupted: Boolean = true,
        callbackSessionGeneration: Long? = null
    ) {
        val uttId = utteranceId?.trim().orEmpty()
        val record: ActiveTtsTurnRecord
        synchronized(lock) {
            val matched = if (uttId.isNotEmpty()) utteranceToTurnMap[uttId] else activeTurnRecord
            val current = activeTurnRecord
            val sessionMismatch = callbackSessionGeneration != null &&
                current != null &&
                callbackSessionGeneration != current.sessionGeneration
            if (matched == null || current == null || current.generation != matched.generation || sessionMismatch) {
                staleCallbacksRejectedAtomic.incrementAndGet()
                return
            }
            record = matched
            activeTurnRecord = null
            for (segId in record.segmentUtteranceIds) {
                utteranceToTurnMap.remove(segId)
            }
        }

        interruptedCountAtomic.incrementAndGet()
        AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(
            immediateCooldownClear = true,
            ownerToken = record.turnId,
            forceRelease = true
        )
        AudioOutputManager.completeTurnOutput(record.turnId)
        emitLifecycle(
            record.onCommandStateChanged,
            TtsLifecycleEvent(
                commandId = record.commandId,
                turnId = record.turnId,
                utteranceId = uttId.ifBlank { record.finalUtteranceId },
                state = TtsOutputLifecycleState.INTERRUPTED,
                totalSegments = record.segmentUtteranceIds.size,
                reason = if (interrupted) "TTS_UTTERANCE_INTERRUPTED" else "TTS_UTTERANCE_STOPPED",
                configSnapshot = record.configSnapshot
            )
        )
    }

    /**
     * Probes installed TTS voice packs (`ur-PK`, `en-PK`, `en-IN`, `en-US`) and surfaces
     * a helpful banner if the offline Urdu (`ur-PK`) voice pack is missing (`F-052`).
     * Never mutates `engine.language` or `engine.voice` if an utterance is currently active.
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

        val isTurnInFlight = synchronized(lock) { activeTurnRecord != null }
        val chosenLocaleTag: String
        val chosenVoiceName: String
        if (isTurnInFlight) {
            chosenLocaleTag = lastResolvedLocaleTag
            chosenVoiceName = lastResolvedVoiceId.ifBlank { "Default Neural Voice" }
        } else {
            val (loc, voiceId, _) = applyBestAvailableLocale(engine, Locale.forLanguageTag("ur-PK"))
            chosenLocaleTag = loc.toLanguageTag()
            chosenVoiceName = voiceId
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
            activeLocaleTag = chosenLocaleTag,
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
     * Normalizes a raw assistant reply via [SpeechNormaliser] and speaks it using a single
     * immutable [SpeechConfigSnapshot] for the entire utterance, guarded by [AudioOutputManager]
     * so duplicate turns, mid-utterance voice/rate/pitch mutations, or simultaneous PCM+TTS never occur.
     */
    fun speakNormalized(
        rawReply: String,
        detectedLanguage: DetectedLanguage = LanguageProcessor.detectLanguage(rawReply),
        turnId: String = UUID.randomUUID().toString(),
        pitch: Float = SpeechPreferenceStore.getPitch(context),
        speechRate: Float = SpeechPreferenceStore.getSpeechRate(context),
        commandId: String = turnId,
        sessionGeneration: Long = SpeechPreferenceStore.currentSessionGeneration(),
        requestedOutputProvider: String = "ANDROID_TTS",
        actualOutputProvider: String = "ANDROID_TTS",
        requestedVoiceId: String = SpeechPreferenceStore.getSelectedLiveVoice(context),
        languageSelectionReason: String = "DIRECT_TTS_REQUEST",
        fallbackOrReconnectReason: String = "NONE",
        onCommandStateChanged: ((TtsLifecycleEvent) -> Unit)? = null
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

        val effectiveTurnId = turnId.trim().ifBlank { UUID.randomUUID().toString() }
        val effectiveCommandId = commandId.trim().ifBlank { effectiveTurnId }

        val stablePitch = pitch.coerceIn(0.85f, 1.25f)
        val stableRate = speechRate.coerceIn(0.85f, 1.25f)
        val initialLocaleTag = effectiveDetectedLang.ttsLocale.toLanguageTag()

        val initialSnapshot = SpeechConfigSnapshot(
            commandId = effectiveCommandId,
            turnId = effectiveTurnId,
            sessionGeneration = sessionGeneration,
            requestedOutputProvider = requestedOutputProvider,
            actualOutputProvider = actualOutputProvider,
            requestedVoiceId = requestedVoiceId,
            resolvedVoiceId = lastResolvedVoiceId.takeIf { it != "NONE" } ?: "ANDROID_TTS_PENDING_RESOLVE",
            resolvedLocaleTag = initialLocaleTag,
            speechRate = stableRate,
            pitch = stablePitch,
            chosenReplyLanguage = effectiveDetectedLang,
            languageSelectionReason = languageSelectionReason,
            fallbackOrReconnectReason = fallbackOrReconnectReason
        )

        emitLifecycle(
            onCommandStateChanged,
            TtsLifecycleEvent(
                commandId = effectiveCommandId,
                turnId = effectiveTurnId,
                utteranceId = effectiveTurnId,
                state = TtsOutputLifecycleState.REQUESTED,
                reason = "TTS_SPEAK_REQUESTED",
                configSnapshot = initialSnapshot
            )
        )

        if (normalized.speechText.isBlank()) {
            errorCountAtomic.incrementAndGet()
            lastErrorReason = "BLANK_SPEECH_TEXT"
            emitLifecycle(
                onCommandStateChanged,
                TtsLifecycleEvent(
                    commandId = effectiveCommandId,
                    turnId = effectiveTurnId,
                    utteranceId = effectiveTurnId,
                    state = TtsOutputLifecycleState.FAILED,
                    reason = "BLANK_SPEECH_TEXT",
                    configSnapshot = initialSnapshot
                )
            )
            return normalized
        }

        val req = PendingInitTtsRequest(
            rawReply = rawReply,
            detectedLanguage = effectiveDetectedLang,
            turnId = effectiveTurnId,
            commandId = effectiveCommandId,
            pitch = stablePitch,
            speechRate = stableRate,
            normalized = normalized,
            requestedSnapshot = initialSnapshot,
            onCommandStateChanged = onCommandStateChanged
        )

        var queuedDuringInit = false
        var failedInit = false
        var droppedOldest: PendingInitTtsRequest? = null
        synchronized(lock) {
            if (isInitializationFailed) {
                failedInit = true
            } else if (!isInitialized) {
                if (pendingInitQueue.size >= MAX_BOUNDED_INIT_QUEUE_SIZE) {
                    droppedOldest = pendingInitQueue.removeFirst()
                }
                pendingInitQueue.addLast(req)
                queuedDuringInit = true
            }
        }

        droppedOldest?.let { dropped ->
            interruptedCountAtomic.incrementAndGet()
            emitLifecycle(
                dropped.onCommandStateChanged,
                TtsLifecycleEvent(
                    commandId = dropped.commandId,
                    turnId = dropped.turnId,
                    utteranceId = dropped.turnId,
                    state = TtsOutputLifecycleState.INTERRUPTED,
                    reason = "DROPPED_BY_BOUNDED_INIT_QUEUE",
                    configSnapshot = dropped.requestedSnapshot
                )
            )
        }

        if (failedInit) {
            errorCountAtomic.incrementAndGet()
            lastErrorReason = "TTS_NOT_INITIALIZED_FAILED"
            AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(
                immediateCooldownClear = true,
                ownerToken = effectiveTurnId
            )
            emitLifecycle(
                onCommandStateChanged,
                TtsLifecycleEvent(
                    commandId = effectiveCommandId,
                    turnId = effectiveTurnId,
                    utteranceId = effectiveTurnId,
                    state = TtsOutputLifecycleState.FAILED,
                    reason = "TTS_NOT_INITIALIZED_FAILED",
                    configSnapshot = initialSnapshot
                )
            )
            onSpeechError?.invoke("TTS_NOT_INITIALIZED_FAILED")
            return normalized
        }

        if (queuedDuringInit) {
            // Do NOT invoke onSpeechCompleted for unplayed text while awaiting initialization!
            emitLifecycle(
                onCommandStateChanged,
                TtsLifecycleEvent(
                    commandId = effectiveCommandId,
                    turnId = effectiveTurnId,
                    utteranceId = effectiveTurnId,
                    state = TtsOutputLifecycleState.QUEUED,
                    reason = "QUEUED_AWAITING_TTS_INIT",
                    configSnapshot = initialSnapshot
                )
            )
            return normalized
        }

        dispatchInitializedNormalizedRequest(req)
        return normalized
    }

    private fun dispatchInitializedNormalizedRequest(req: PendingInitTtsRequest) {
        val normalized = req.normalized
        val effectiveDetectedLang = req.detectedLanguage
        val mode = _activeAccentMode.value
        val status = _voicePackStatus.value

        // 1. Enforce single-owner audio output & deduplication by turnId BEFORE starting suppression
        val acquired = AudioOutputManager.acquireTurnOutput(
            turnId = req.turnId,
            modality = AudioOutputManager.OutputModality.LOCAL_TTS,
            ttsManager = this
        )
        if (!acquired) {
            lastErrorReason = "OUTPUT_ACQUISITION_DENIED"
            // Never leave suppression stuck if output acquisition is denied!
            AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(
                immediateCooldownClear = true,
                ownerToken = req.turnId
            )
            emitLifecycle(
                req.onCommandStateChanged,
                TtsLifecycleEvent(
                    commandId = req.commandId,
                    turnId = req.turnId,
                    utteranceId = req.turnId,
                    state = TtsOutputLifecycleState.FAILED,
                    reason = "OUTPUT_ACQUISITION_DENIED",
                    configSnapshot = req.requestedSnapshot
                )
            )
            onSpeechError?.invoke("OUTPUT_ACQUISITION_DENIED")
            return
        }

        val engine = tts
        val interceptor = speakEngineInterceptorForTest
        if (engine == null && interceptor == null) {
            errorCountAtomic.incrementAndGet()
            lastErrorReason = "TTS_ENGINE_NULL"
            AudioOutputManager.completeTurnOutput(req.turnId)
            AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(
                immediateCooldownClear = true,
                ownerToken = req.turnId,
                forceRelease = true
            )
            emitLifecycle(
                req.onCommandStateChanged,
                TtsLifecycleEvent(
                    commandId = req.commandId,
                    turnId = req.turnId,
                    utteranceId = req.turnId,
                    state = TtsOutputLifecycleState.FAILED,
                    reason = "TTS_ENGINE_NULL",
                    configSnapshot = req.requestedSnapshot
                )
            )
            onSpeechError?.invoke("TTS_ENGINE_NULL")
            return
        }

        AudioOutputManager.requestAssistantAudioFocus(context)

        val runs = normalized.languageRuns.ifEmpty {
            listOf(LanguageRun(normalized.speechText, false, effectiveDetectedLang.ttsLocale))
        }
        val segmentIds = runs.indices.map { index ->
            if (index == runs.lastIndex) req.turnId else "${req.turnId}_part_$index"
        }
        val finalUttId = segmentIds.last()

        // 2. Resolve ONE stable locale, voice, rate, and pitch for the entire utterance BEFORE queuing segments.
        // Never mutate voice, locale, rate, or pitch inside the segment loop or midway through an utterance!
        val desiredPrimaryLocale = when {
            mode == AccentTrainingMode.ENGLISH_COMPLETE_ACCENT -> Locale.US
            mode == AccentTrainingMode.URDU_COMPLETE_ACCENT -> Locale.forLanguageTag("ur-PK")
            (effectiveDetectedLang == DetectedLanguage.URDU_NASTALIQ || runs.any { it.isUrdu }) && status.isUrduPkInstalled ->
                Locale.forLanguageTag("ur-PK")
            effectiveDetectedLang == DetectedLanguage.ROMAN_URDU ||
                effectiveDetectedLang == DetectedLanguage.MIXED ||
                (effectiveDetectedLang == DetectedLanguage.URDU_NASTALIQ && !status.isUrduPkInstalled) ->
                Locale.forLanguageTag("en-PK")
            else -> runs.first().locale
        }

        val tunedPitch = req.pitch.coerceIn(0.85f, 1.25f)
        val tunedRate = req.speechRate.coerceIn(0.85f, 1.25f)

        val resolvedLocale: Locale
        val resolvedVoiceId: String
        val limitationNote: String
        if (engine != null) {
            try {
                engine.setPitch(tunedPitch)
                engine.setSpeechRate(tunedRate)
            } catch (_: Exception) {
            }
            val (loc, voiceId, lim) = applyBestAvailableLocale(engine, desiredPrimaryLocale)
            resolvedLocale = loc
            resolvedVoiceId = voiceId
            limitationNote = lim
        } else {
            resolvedLocale = desiredPrimaryLocale
            resolvedVoiceId = "TEST_INTERCEPTOR_VOICE_${desiredPrimaryLocale.toLanguageTag()}"
            limitationNote = "NONE"
        }

        lastResolvedLocaleTag = resolvedLocale.toLanguageTag()
        lastResolvedVoiceId = resolvedVoiceId
        lastFallbackLimitationNote = limitationNote

        val effectiveFallbackReason = when {
            req.requestedSnapshot.fallbackOrReconnectReason != "NONE" && limitationNote != "NONE" ->
                "${req.requestedSnapshot.fallbackOrReconnectReason};$limitationNote"
            limitationNote != "NONE" -> limitationNote
            else -> req.requestedSnapshot.fallbackOrReconnectReason
        }

        val resolvedSnapshot = req.requestedSnapshot.copy(
            resolvedVoiceId = resolvedVoiceId,
            resolvedLocaleTag = resolvedLocale.toLanguageTag(),
            speechRate = tunedRate,
            pitch = tunedPitch,
            fallbackOrReconnectReason = effectiveFallbackReason
        )
        lastConfigSnapshot = resolvedSnapshot
        _appliedConfigSnapshotsForTest.add(resolvedSnapshot)

        val previousActive: ActiveTtsTurnRecord?
        val newRecord: ActiveTtsTurnRecord
        synchronized(lock) {
            previousActive = activeTurnRecord
            if (previousActive != null) {
                for (oldSeg in previousActive.segmentUtteranceIds) {
                    utteranceToTurnMap.remove(oldSeg)
                }
            }
            val gen = turnGenerationCounter.incrementAndGet()
            newRecord = ActiveTtsTurnRecord(
                turnId = req.turnId,
                commandId = req.commandId,
                generation = gen,
                sessionGeneration = resolvedSnapshot.sessionGeneration,
                segmentUtteranceIds = segmentIds,
                finalUtteranceId = finalUttId,
                spokenText = normalized.speechText.ifBlank { req.rawReply },
                configSnapshot = resolvedSnapshot,
                onCommandStateChanged = req.onCommandStateChanged
            )
            activeTurnRecord = newRecord
            for (segId in segmentIds) {
                utteranceToTurnMap[segId] = newRecord
            }
        }

        if (previousActive != null && previousActive.turnId != req.turnId) {
            interruptedCountAtomic.incrementAndGet()
            AudioOutputManager.completeTurnOutput(previousActive.turnId)
            emitLifecycle(
                previousActive.onCommandStateChanged,
                TtsLifecycleEvent(
                    commandId = previousActive.commandId,
                    turnId = previousActive.turnId,
                    utteranceId = previousActive.finalUtteranceId,
                    state = TtsOutputLifecycleState.INTERRUPTED,
                    totalSegments = previousActive.segmentUtteranceIds.size,
                    reason = "SUPERSEDED_BY_NEW_TURN_${req.turnId}",
                    configSnapshot = previousActive.configSnapshot
                )
            )
        }

        AssistantAudioStateMachine.shared.notifyAssistantSpeechStarted(
            spokenText = newRecord.spokenText,
            source = "LOCAL_TTS",
            ownerToken = req.turnId
        )

        emitLifecycle(
            req.onCommandStateChanged,
            TtsLifecycleEvent(
                commandId = req.commandId,
                turnId = req.turnId,
                utteranceId = finalUttId,
                state = TtsOutputLifecycleState.QUEUED,
                totalSegments = segmentIds.size,
                reason = "TTS_SEGMENTS_QUEUED",
                configSnapshot = resolvedSnapshot
            )
        )

        for ((index, run) in runs.withIndex()) {
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val uttId = segmentIds[index]
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uttId)
            }
            val speakResult = try {
                if (interceptor != null) {
                    interceptor.invoke(run.text, queueMode, uttId)
                } else {
                    engine?.speak(run.text, queueMode, params, uttId) ?: TextToSpeech.ERROR
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in engine.speak for $uttId: ${e.message}")
                TextToSpeech.ERROR
            }

            if (speakResult != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS speak() failed for utteranceId=$uttId with code=$speakResult")
                synchronized(lock) {
                    if (activeTurnRecord?.generation == newRecord.generation) {
                        activeTurnRecord = null
                    }
                    for (segId in segmentIds) {
                        utteranceToTurnMap.remove(segId)
                    }
                }
                errorCountAtomic.incrementAndGet()
                lastErrorReason = "TTS_SPEAK_RETURNED_$speakResult"
                AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(
                    immediateCooldownClear = true,
                    ownerToken = req.turnId,
                    forceRelease = true
                )
                AudioOutputManager.completeTurnOutput(req.turnId)
                emitLifecycle(
                    req.onCommandStateChanged,
                    TtsLifecycleEvent(
                        commandId = req.commandId,
                        turnId = req.turnId,
                        utteranceId = uttId,
                        state = TtsOutputLifecycleState.FAILED,
                        segmentIndex = index,
                        totalSegments = segmentIds.size,
                        reason = "TTS_SPEAK_RETURNED_$speakResult",
                        configSnapshot = resolvedSnapshot
                    )
                )
                onSpeechError?.invoke("TTS_SPEAK_RETURNED_$speakResult")
                return
            }
        }
    }

    /**
     * Speaks any text using the trained Urdu / English / Mixed Roman-Urdu accent pipeline!
     * Routes automatically through [speakNormalized] so every caller in the app gets
     * authentic pronunciation, cold-init queuing, and a stable per-utterance [SpeechConfigSnapshot].
     */
    fun speak(
        text: String,
        pitch: Float = SpeechPreferenceStore.getPitch(context),
        speechRate: Float = SpeechPreferenceStore.getSpeechRate(context),
        locale: Locale = Locale.US,
        turnId: String = UUID.randomUUID().toString(),
        commandId: String = turnId,
        sessionGeneration: Long = SpeechPreferenceStore.currentSessionGeneration(),
        requestedOutputProvider: String = "ANDROID_TTS",
        actualOutputProvider: String = "ANDROID_TTS",
        requestedVoiceId: String = SpeechPreferenceStore.getSelectedLiveVoice(context),
        languageSelectionReason: String = "DIRECT_TTS_SPEAK",
        fallbackOrReconnectReason: String = "NONE",
        onCommandStateChanged: ((TtsLifecycleEvent) -> Unit)? = null
    ) {
        val detected = LanguageProcessor.detectLanguage(
            text = text,
            fallbackConversationLanguage = SpeechPreferenceStore.getConversationLanguage(context)
        )
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
            speechRate = speechRate,
            commandId = commandId,
            sessionGeneration = sessionGeneration,
            requestedOutputProvider = requestedOutputProvider,
            actualOutputProvider = actualOutputProvider,
            requestedVoiceId = requestedVoiceId,
            languageSelectionReason = languageSelectionReason,
            fallbackOrReconnectReason = fallbackOrReconnectReason,
            onCommandStateChanged = onCommandStateChanged
        )
    }

    /**
     * Selects the highest-quality neural/local [Voice] and [Locale] for Urdu (`ur-PK`, `ur-IN`, `hi-IN`),
     * Pakistani/Subcontinental English (`en-PK`, `en-IN`), or US/UK English (`en-US`, `en-GB`).
     * Returns `(resolvedLocale, resolvedVoiceId, limitationNote)` so fallback limitations are transparently reported.
     */
    private fun applyBestAvailableLocale(
        engine: TextToSpeech,
        preferred: Locale
    ): Triple<Locale, String, String> {
        val status = _voicePackStatus.value
        val candidates = when {
            preferred.language.equals("ur", ignoreCase = true) -> listOf(
                Locale.forLanguageTag("ur-PK"),
                Locale.forLanguageTag("ur-IN"),
                Locale.forLanguageTag("ur"),
                Locale.forLanguageTag("en-PK"),
                Locale.forLanguageTag("hi-IN"),
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
            val isSupported = isLocaleSupported(engine, candidate) ||
                (candidate.language.equals("ur", ignoreCase = true) && status.isUrduPkInstalled) ||
                (candidate.toLanguageTag().equals("en-PK", ignoreCase = true) && status.isEnglishPkInstalled)
            if (isSupported) {
                try {
                    engine.language = candidate
                } catch (_: Exception) {
                }
                val selectedVoiceName = selectHighestQualityVoiceForLocale(engine, candidate)
                val limitation = if (!candidate.language.equals(preferred.language, ignoreCase = true)) {
                    "REQUESTED_LOCALE_${preferred.toLanguageTag()}_UNAVAILABLE_USING_${candidate.toLanguageTag()}"
                } else {
                    "NONE"
                }
                return Triple(candidate, selectedVoiceName, limitation)
            }
        }
        try {
            engine.language = Locale.US
        } catch (_: Exception) {
        }
        val fallbackVoiceName = selectHighestQualityVoiceForLocale(engine, Locale.US)
        val limitation = if (!preferred.language.equals("en", ignoreCase = true)) {
            "REQUESTED_LOCALE_${preferred.toLanguageTag()}_UNAVAILABLE_USING_en-US"
        } else {
            "NONE"
        }
        return Triple(Locale.US, fallbackVoiceName, limitation)
    }

    private fun selectHighestQualityVoiceForLocale(engine: TextToSpeech, targetLocale: Locale): String {
        try {
            val availableVoices = engine.voices
            if (!availableVoices.isNullOrEmpty()) {
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
                    if (v.name.contains("neural", ignoreCase = true) ||
                        v.name.contains("wavenet", ignoreCase = true) ||
                        v.name.contains("local", ignoreCase = true)
                    ) {
                        score += 50
                    }
                    score
                }

                if (bestVoice != null) {
                    engine.voice = bestVoice
                    return bestVoice.name
                }
            }
            val currentVoiceName = try {
                engine.voice?.name
            } catch (_: Exception) {
                null
            }
            return currentVoiceName?.takeIf { it.isNotBlank() }
                ?: "ANDROID_TTS_${targetLocale.toLanguageTag()}"
        } catch (_: Exception) {
            return "ANDROID_TTS_${targetLocale.toLanguageTag()}"
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

    fun stop(reason: String = "TTS_STOP_CALLED") {
        val cancelledInitRequests = mutableListOf<PendingInitTtsRequest>()
        val activeToCancel: ActiveTtsTurnRecord?
        synchronized(lock) {
            while (pendingInitQueue.isNotEmpty()) {
                cancelledInitRequests.add(pendingInitQueue.removeFirst())
            }
            activeToCancel = activeTurnRecord
            activeTurnRecord = null
            if (activeToCancel != null) {
                for (segId in activeToCancel.segmentUtteranceIds) {
                    utteranceToTurnMap.remove(segId)
                }
            }
        }

        if (activeToCancel != null) {
            interruptedCountAtomic.incrementAndGet()
            AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(
                immediateCooldownClear = true,
                ownerToken = activeToCancel.turnId,
                forceRelease = true
            )
            AudioOutputManager.completeTurnOutput(activeToCancel.turnId)
            emitLifecycle(
                activeToCancel.onCommandStateChanged,
                TtsLifecycleEvent(
                    commandId = activeToCancel.commandId,
                    turnId = activeToCancel.turnId,
                    utteranceId = activeToCancel.finalUtteranceId,
                    state = TtsOutputLifecycleState.INTERRUPTED,
                    totalSegments = activeToCancel.segmentUtteranceIds.size,
                    reason = reason,
                    configSnapshot = activeToCancel.configSnapshot
                )
            )
        } else {
            AssistantAudioStateMachine.shared.notifyAssistantSpeechEnded(immediateCooldownClear = true)
        }

        for (queued in cancelledInitRequests) {
            interruptedCountAtomic.incrementAndGet()
            emitLifecycle(
                queued.onCommandStateChanged,
                TtsLifecycleEvent(
                    commandId = queued.commandId,
                    turnId = queued.turnId,
                    utteranceId = queued.turnId,
                    state = TtsOutputLifecycleState.INTERRUPTED,
                    reason = reason,
                    configSnapshot = queued.requestedSnapshot
                )
            )
        }

        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        stop(reason = "TTS_SHUTDOWN")
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
    }
}

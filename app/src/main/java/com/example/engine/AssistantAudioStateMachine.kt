package com.example.engine

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.service.OmniVoiceInteractionService
import com.example.service.WakeWordForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-Owner Audio Pipeline States (`F-003` & `R4`).
 * Ensures `WakeWordForegroundService`, `MultiEngineSpeechManager`, `OmniLiveSessionEngine`,
 * and `AudioOutputManager` never open competing `AudioRecord` / `SpeechRecognizer` instances.
 */
enum class AudioPipelineOwner(val isMicrophoneActive: Boolean) {
    IDLE(false),
    WAKE_LISTEN(true),
    STT_RECORDING(true),
    RECORDING_UTTERANCE(true),
    THINKING(false),
    SPEAKING(false),
    SLEEPING(false)
}

/**
 * 9-State Always-On Operating State Machine (`F-016`..`F-021`).
 */
enum class AssistantOperatingState(val pausesInactivityTimer: Boolean) {
    SLEEP_STANDBY(true),
    WAKE_DETECTED(true),
    ACTIVE_LISTENING(false),
    RECORDING_UTTERANCE(true),
    THINKING(true),
    EXECUTING_STEP(true),
    SPEAKING(true),
    AWAITING_PREREQUISITE(true),
    ERROR_RECOVERY(true)
}

/**
 * Hardware Microphone Component Owner (`Single-Owner Microphone Arbiter`).
 * Exactly ONE component may own the hardware microphone at any instant.
 */
enum class MicComponentOwner(val displayLabel: String) {
    NONE("None (Mic Released)"),
    WAKE_WORD_SERVICE("WakeWordForegroundService (Wake-Only 'Jarvis')"),
    COMMAND_WINDOW_CAPTURE("CommandWindowCapture (Bounded Single-Turn)"),
    LIVE_SESSION_ENGINE("OmniLiveSessionEngine (Live Stream)")
}

/**
 * Visible user-facing wake & speech lifecycle states.
 */
enum class WakeSpeechLifecycleState(val badgeLabel: String, val requiresUserActionToArm: Boolean) {
    DISARMED_STOPPED("STOPPED / DISARMED", true),
    MUTED_PINNED("MUTED (PINNED TO DEVICE)", false),
    PERMISSION_REVOKED("MIC PERMISSION REQUIRED", true),
    NEEDS_TAP_TO_REARM("NEEDS TAP TO RE-ARM", true),
    WAKE_ONLY_LISTENING("WAKE-ONLY ('JARVIS')", false),
    COMMAND_WINDOW_OPEN("COMMAND WINDOW OPEN", false),
    SPEAKING_SUPPRESSED("SPEAKING (MIC SUPPRESSED)", false)
}

/**
 * Active microphone input route (Built-in vs Wired/USB/Bluetooth Headset).
 */
enum class AudioInputRouteType(val label: String, val isHeadset: Boolean) {
    BUILT_IN_MIC("Phone Microphone", false),
    WIRED_HEADSET("Wired Headset Mic", true),
    USB_HEADSET("USB Headset Mic", true),
    BLUETOOTH_HEADSET("Bluetooth Headset Mic", true)
}

/**
 * Outcome of evaluating a consented audio frame or recognized transcript through the wake & speech path.
 */
enum class WakeSpeechCandidateDecision {
    WAKE_WORD_DETECTED_OPEN_WINDOW,
    WAKE_AND_INLINE_COMMAND_ACCEPTED,
    COMMAND_ACCEPTED_IN_WINDOW,
    CANCEL_OR_STOP_COMMAND_WINDOW,
    STOP_AND_DISARM_PINNED,
    SUPPRESSED_DURING_TTS_OR_NOTIFICATION,
    IGNORED_WHILE_STOPPED_OR_MUTED,
    IGNORED_UNADDRESSED_IN_WAKE_ONLY,
    IGNORED_ORDINARY_CONVERSATION_OR_TV_AUDIO,
    COMMAND_WINDOW_TIMED_OUT
}

data class WakeSpeechEvaluationResult(
    val decision: WakeSpeechCandidateDecision,
    val shouldExecuteCommand: Boolean,
    val extractedCommandText: String = "",
    val lifecycleStateAfter: WakeSpeechLifecycleState,
    val micOwnerAfter: MicComponentOwner,
    val reason: String
)

data class WakeSpeechTelemetrySnapshot(
    val validWakesCount: Int,
    val falseWakesCount: Int,
    val missedWakesCount: Int,
    val unintendedExecutionsCount: Int,
    val suppressedSelfTriggersCount: Int,
    val ignoredBackgroundConversationCount: Int,
    val activeMicOwner: MicComponentOwner,
    val activeMicOwnerCount: Int,
    val maxConcurrentMicOwnersObserved: Int,
    val lifecycleState: WakeSpeechLifecycleState,
    val inputRoute: AudioInputRouteType,
    val isUserArmed: Boolean,
    val isStoppedByUser: Boolean,
    val isDeviceMuted: Boolean,
    val isCommandWindowOpen: Boolean,
    val continuousSpeechRecognizerLoopUsedAsWakeDetector: Boolean = false
) {
    fun toCopyableDiagnosticLine(): String {
        return "lifecycle=${lifecycleState.name} | micOwner=${activeMicOwner.name} | " +
            "concurrentMicOwners=$activeMicOwnerCount(max=$maxConcurrentMicOwnersObserved) | " +
            "armed=$isUserArmed | stopped=$isStoppedByUser | muted=$isDeviceMuted | " +
            "cmdWindow=$isCommandWindowOpen | input=${inputRoute.name} | " +
            "validWakes=$validWakesCount | falseWakes=$falseWakesCount | " +
            "missedWakes=$missedWakesCount | unintendedExecs=$unintendedExecutionsCount | " +
            "selfSuppressed=$suppressedSelfTriggersCount | bgIgnored=$ignoredBackgroundConversationCount | " +
            "continuousSttWakeDetector=$continuousSpeechRecognizerLoopUsedAsWakeDetector"
    }
}

data class WakeAndAssistantReadinessReport(
    val hasRecordAudioPermission: Boolean,
    val isRoleAssistantHeld: Boolean,
    val isVoiceInteractionServiceReady: Boolean,
    val hasForegroundServiceMicrophoneType: Boolean,
    val isWakeServiceRunning: Boolean,
    val isUserArmedPersisted: Boolean,
    val isStoppedByUserPersisted: Boolean,
    val isDeviceMutedPersisted: Boolean,
    val canActivateInBackgroundOrScreenOff: Boolean,
    val requiresTapToRearm: Boolean,
    val actualLifecycleState: WakeSpeechLifecycleState,
    val inputRoute: AudioInputRouteType,
    val statusSummary: String
)

/**
 * Deterministic finite state machine coordinating microphone ownership, wake-word detection ("Jarvis"),
 * bounded command windows, local stop/mute pinning, TTS/notification self-trigger suppression,
 * headset routing, and background/reboot/revocation state verification.
 */
class AssistantAudioStateMachine(
    private val onWakeListenRequested: () -> Unit = {},
    private val onSttRecordingRequested: () -> Unit = {},
    private val onThinkingRequested: () -> Unit = {},
    private val onSpeakingRequested: () -> Unit = {},
    private val onIdleRequested: () -> Unit = {},
    private val onPauseWakeEngine: () -> Unit = {},
    private val onResumeWakeEngine: () -> Unit = {},
    private val onStopRecording: () -> Unit = {},
    private val onStopSpeaking: () -> Unit = {}
) {

    private val _currentOwner = MutableStateFlow(AudioPipelineOwner.IDLE)
    val currentOwner: StateFlow<AudioPipelineOwner> = _currentOwner.asStateFlow()

    private val _operatingState = MutableStateFlow(AssistantOperatingState.SLEEP_STANDBY)
    val operatingState: StateFlow<AssistantOperatingState> = _operatingState.asStateFlow()

    private var configuredInactivityTimeoutSec: Int = 60
    private val _inactivitySecondsRemaining = MutableStateFlow(60)
    val inactivitySecondsRemaining: StateFlow<Int> = _inactivitySecondsRemaining.asStateFlow()

    private val _isWakeArmed = MutableStateFlow(false)
    val isWakeArmed: StateFlow<Boolean> = _isWakeArmed.asStateFlow()

    private val _lastTransitionLog = MutableStateFlow("Initialized -> IDLE (Disarmed)")
    val lastTransitionLog: StateFlow<String> = _lastTransitionLog.asStateFlow()

    @Synchronized
    fun canListen(): Boolean {
        return _currentOwner.value != AudioPipelineOwner.SPEAKING
    }

    @Synchronized
    fun transitionTo(
        next: AudioPipelineOwner,
        reason: String = "Direct state transition",
        forceBargeIn: Boolean = false
    ): Boolean {
        val prev = _currentOwner.value
        // Prevent microphone capture while TTS is speaking unless explicit barge-in
        if (prev == AudioPipelineOwner.SPEAKING &&
            (next == AudioPipelineOwner.STT_RECORDING || next == AudioPipelineOwner.RECORDING_UTTERANCE) &&
            !forceBargeIn &&
            !reason.contains("barge_in", ignoreCase = true) &&
            !reason.contains("force", ignoreCase = true)
        ) {
            Log.d(TAG, "Blocked transition from SPEAKING to ${next.name} ($reason) to prevent TTS self-trigger")
            return false
        }

        _currentOwner.value = next
        val entry = "${prev.name} -> ${next.name}: $reason"
        _lastTransitionLog.value = entry
        Log.d(TAG, entry)

        when (next) {
            AudioPipelineOwner.WAKE_LISTEN -> {
                onStopRecording()
                onWakeListenRequested()
                onResumeWakeEngine()
            }
            AudioPipelineOwner.STT_RECORDING,
            AudioPipelineOwner.RECORDING_UTTERANCE -> {
                onPauseWakeEngine()
                onSttRecordingRequested()
            }
            AudioPipelineOwner.THINKING -> {
                onStopRecording()
                onThinkingRequested()
            }
            AudioPipelineOwner.SPEAKING -> {
                onStopRecording()
                onPauseWakeEngine()
                onSpeakingRequested()
            }
            AudioPipelineOwner.IDLE,
            AudioPipelineOwner.SLEEPING -> {
                onStopRecording()
                onIdleRequested()
            }
        }
        return true
    }

    @Synchronized
    fun onTtsSpeakingFinished() {
        shared.notifyAssistantSpeechEnded()
        transitionTo(AudioPipelineOwner.WAKE_LISTEN, "tts_speaking_finished")
    }

    @Synchronized
    fun setConfiguredInactivityTimeoutSeconds(seconds: Int) {
        configuredInactivityTimeoutSec = seconds.coerceAtLeast(5)
        _inactivitySecondsRemaining.value = configuredInactivityTimeoutSec
    }

    @Synchronized
    fun transitionOperatingState(next: AssistantOperatingState, reason: String = "") {
        _operatingState.value = next
        when (next) {
            AssistantOperatingState.ACTIVE_LISTENING -> {
                _inactivitySecondsRemaining.value = configuredInactivityTimeoutSec
                _currentOwner.value = AudioPipelineOwner.WAKE_LISTEN
            }
            AssistantOperatingState.RECORDING_UTTERANCE -> {
                _currentOwner.value = AudioPipelineOwner.STT_RECORDING
            }
            AssistantOperatingState.THINKING,
            AssistantOperatingState.EXECUTING_STEP -> {
                _currentOwner.value = AudioPipelineOwner.THINKING
            }
            AssistantOperatingState.SPEAKING -> {
                _currentOwner.value = AudioPipelineOwner.SPEAKING
            }
            AssistantOperatingState.SLEEP_STANDBY -> {
                _currentOwner.value = AudioPipelineOwner.IDLE
            }
            else -> {}
        }
    }

    @Synchronized
    fun tickInactivitySecond(): Boolean {
        if (_operatingState.value.pausesInactivityTimer) {
            return false
        }
        val next = (_inactivitySecondsRemaining.value - 1).coerceAtLeast(0)
        _inactivitySecondsRemaining.value = next
        if (next == 0) {
            transitionOperatingState(AssistantOperatingState.SLEEP_STANDBY, "inactivity_timeout")
            return true
        }
        return false
    }

    @Synchronized
    fun triggerBargeIn(): Boolean {
        onStopSpeaking()
        shared.notifyAssistantSpeechEnded(immediateCooldownClear = true)
        _operatingState.value = AssistantOperatingState.RECORDING_UTTERANCE
        return transitionTo(AudioPipelineOwner.STT_RECORDING, "barge_in", forceBargeIn = true)
    }

    @Synchronized
    fun armWakeWordListening() {
        _isWakeArmed.value = true
        shared.markArmedInMemory(true)
        transitionTo(AudioPipelineOwner.WAKE_LISTEN, "User armed wake-word listening ('Jarvis')")
    }

    @Synchronized
    fun disarmWakeWordListening() {
        _isWakeArmed.value = false
        shared.markArmedInMemory(false)
        onPauseWakeEngine()
        transitionTo(AudioPipelineOwner.IDLE, "User disarmed wake-word listening")
    }

    @Synchronized
    fun onWakePhraseDetected(wakePhrase: String) {
        if (shared.isSelfTriggerSuppressed()) {
            shared.recordSuppressedSelfTrigger("Wake phrase '$wakePhrase' ignored during TTS/notification output")
            return
        }
        onPauseWakeEngine()
        shared.openCommandWindow(reason = "Wake phrase '$wakePhrase' detected")
        transitionTo(
            AudioPipelineOwner.STT_RECORDING,
            "Wake phrase '$wakePhrase' detected -> opened bounded command window"
        )
    }

    @Synchronized
    fun onStartManualRecording() {
        onStopSpeaking()
        onPauseWakeEngine()
        shared.openCommandWindow(reason = "User started manual command capture")
        transitionTo(
            AudioPipelineOwner.STT_RECORDING,
            "Manual recording started -> wake engine & TTS paused",
            forceBargeIn = true
        )
    }

    @Synchronized
    fun onUtteranceCapturedStartThinking() {
        onStopRecording()
        transitionTo(
            AudioPipelineOwner.THINKING,
            "Utterance captured -> microphone released, thinking started"
        )
    }

    @Synchronized
    fun onStartSpeakingResponse(spokenText: String = "", source: String = "TTS") {
        onStopRecording()
        onPauseWakeEngine()
        shared.notifyAssistantSpeechStarted(spokenText, source)
        transitionTo(
            AudioPipelineOwner.SPEAKING,
            "Speaking response ($source) -> wake engine & mic paused to prevent self-triggering"
        )
    }

    @Synchronized
    fun onResponseFinishedReturnToWakeOrIdle() {
        shared.notifyAssistantSpeechEnded()
        if (_isWakeArmed.value && !shared.isStoppedByUserInMemory() && !shared.isDeviceMutedInMemory()) {
            shared.returnToWakeOnlyListening("Response finished -> returned to wake-only listening")
            onResumeWakeEngine()
            transitionTo(
                AudioPipelineOwner.WAKE_LISTEN,
                "Response finished -> returned to WAKE_LISTEN"
            )
        } else {
            transitionTo(
                AudioPipelineOwner.IDLE,
                "Response finished -> returned to IDLE"
            )
        }
    }

    @Synchronized
    fun onSleepCommandDetected() {
        onStopRecording()
        onStopSpeaking()
        onPauseWakeEngine()
        shared.closeCommandWindow()
        transitionTo(
            AudioPipelineOwner.SLEEPING,
            "Sleep command detected -> paused active listening until wake phrase"
        )
    }

    @Synchronized
    fun onBargeInInterrupt() {
        triggerBargeIn()
    }

    companion object {
        private const val TAG = "AudioStateMachine"
        const val PREFS_NAME = "omni_wake_speech_prefs"
        const val KEY_USER_ARMED = "is_user_armed"
        const val KEY_STOPPED_BY_USER = "is_stopped_by_user"
        const val KEY_DEVICE_MUTED = "is_device_muted"
        const val KEY_NEEDS_REARM_AFTER_OS_EVENT = "needs_rearm_after_os_event"
        const val KEY_LAST_STOP_OR_STATE_REASON = "last_stop_or_state_reason"
        const val KEY_CONFIGURED_WAKE_WORD = "configured_wake_word"

        const val DEFAULT_WAKE_WORD = "Jarvis"
        const val DEFAULT_COMMAND_WINDOW_TIMEOUT_MS = 8_000L
        const val SELF_SPEECH_TAIL_COOLDOWN_MS = 500L

        /**
         * Process-wide Single-Owner Microphone & Wake/Speech Coordinator.
         */
        val shared = GlobalWakeSpeechCoordinator()

        private val wakePatterns = listOf(
            "jarvis",
            "hey jarvis",
            "ok jarvis",
            "hello jarvis",
            "wake up jarvis",
            "jarvis suno",
            "suno jarvis",
            "jarvis utho",
            "utho jarvis",
            "جاروس",
            "اے جاروس",
            "ہیلو جاروس",
            "جاروس سنو",
            "hey omni",
            "hello omni",
            "ok omni",
            "wake up omni",
            "omni suno",
            "suno omni",
            "utho omni",
            "omni utho",
            "جاگو اومنی",
            "اومنی سنو",
            "ہیلو اومنی",
            "اٹھو اومنی"
        )

        private val sleepPatterns = listOf(
            "sleep now",
            "go to sleep",
            "so jao",
            "ab so jao",
            "aram karo",
            "chup ho jao",
            "standby mode",
            "سو جاؤ",
            "اب سو جاؤ",
            "خاموش ہو جاؤ"
        )

        private val cancelCommandWindowExactPhrases = setOf(
            "bas",
            "stop",
            "cancel",
            "ruko",
            "ruk jao",
            "rehny do",
            "rehne do",
            "chhoro",
            "choro",
            "chup",
            "never mind",
            "nevermind",
            "abort",
            "بس",
            "سٹاپ",
            "کینسل",
            "رکو",
            "رک جاؤ",
            "رہنے دو"
        )

        private val fullStopAndDisarmPhrases = listOf(
            "stop listening",
            "jarvis stop listening",
            "jarvis band karo",
            "jarvis off karo",
            "band ho jao",
            "listening band karo",
            "wake word band karo",
            "disable jarvis",
            "turn off jarvis",
            "سٹاپ لسننگ",
            "جاروس بند کرو"
        )

        /**
         * Returns true if the transcript starts with or contains a wake phrase ("Jarvis", "Hey Jarvis", etc.).
         */
        fun matchesWakePhrase(utterance: String, customAssistantName: String = DEFAULT_WAKE_WORD): Boolean {
            val cleaned = normalizeText(utterance)
            if (cleaned.isEmpty()) return false
            if (cleaned == "jarvis" || cleaned.startsWith("jarvis ") || cleaned.endsWith(" jarvis")) {
                return true
            }
            if (wakePatterns.any { cleaned == it || cleaned.startsWith("$it ") || cleaned.contains(it) }) {
                return true
            }
            val customLower = customAssistantName.trim().lowercase(Locale.ROOT)
                .removePrefix("wake up ")
                .removePrefix("hey ")
                .trim()
            if (customLower.isNotBlank()) {
                val customCandidates = listOf(
                    customLower,
                    "hey $customLower",
                    "hello $customLower",
                    "wake up $customLower",
                    "suno $customLower",
                    "$customLower suno",
                    "utho $customLower"
                )
                if (customCandidates.any { cleaned == it || cleaned.startsWith("$it ") || cleaned.contains(it) }) {
                    return true
                }
            }
            return false
        }

        /**
         * Extracts the remainder command after stripping the leading wake phrase ("Jarvis", "Hey Jarvis", etc.).
         * Returns empty string if the user only spoke the wake phrase itself (which opens the command window).
         */
        fun extractCommandAfterWakePhrase(
            utterance: String,
            customAssistantName: String = DEFAULT_WAKE_WORD
        ): String {
            val trimmed = utterance.trim()
            if (trimmed.isEmpty()) return ""
            val customClean = customAssistantName.trim()
                .replace(Regex("(?i)^(wake up|hey|ok|hello)\\s+"), "")
                .trim()
                .ifBlank { "Jarvis" }
            val prefixRegex = Regex(
                "(?i)^(?:hey\\s+|ok\\s+|hello\\s+|wake\\s+up\\s+|suno\\s+|utho\\s+|اے\\s+|ہیلو\\s+)?(?:jarvis|omni|${Regex.escape(customClean)}|جاروس|اومنی)(?:\\s+suno|\\s+utho|\\s+سنو)?[\\s,.:;!-]*(.*)$"
            )
            val match = prefixRegex.find(trimmed)
            if (match != null) {
                return match.groupValues[1].trim()
            }
            val trailingRegex = Regex(
                "(?i)^(.+?)[\\s,]+(?:hey\\s+|ok\\s+)?(?:jarvis|omni|${Regex.escape(customClean)}|جاروس|اومنی)[.!?]*$"
            )
            val trailingMatch = trailingRegex.find(trimmed)
            if (trailingMatch != null) {
                return trailingMatch.groupValues[1].trim()
            }
            return ""
        }

        fun matchesSleepPhrase(utterance: String): Boolean {
            val lower = normalizeText(utterance)
            return sleepPatterns.any { lower.contains(it) }
        }

        /**
         * Returns true if the user said "bas", "stop", "cancel", "ruko", etc.
         */
        fun matchesCancelOrStopPhrase(utterance: String): Boolean {
            val cleaned = normalizeText(utterance)
            if (cleaned.isEmpty()) return false
            if (cleaned in cancelCommandWindowExactPhrases) return true
            val withoutWake = normalizeText(extractCommandAfterWakePhrase(utterance))
            if (withoutWake.isNotEmpty() && withoutWake in cancelCommandWindowExactPhrases) return true
            if (fullStopAndDisarmPhrases.any { cleaned == it || withoutWake == it }) return true
            return false
        }

        /**
         * Returns true if the user explicitly asked to stop/disarm listening altogether
         * (e.g., "stop listening", "jarvis band karo"), vs canceling the current command window ("bas", "cancel").
         */
        fun isFullStopListeningPhrase(utterance: String): Boolean {
            val cleaned = normalizeText(utterance)
            val withoutWake = normalizeText(extractCommandAfterWakePhrase(utterance))
            return fullStopAndDisarmPhrases.any {
                cleaned == it || withoutWake == it || cleaned.contains(it)
            }
        }

        /**
         * Detects ordinary background conversation between other people or TV/media audio
         * so it is never executed as a command.
         */
        fun isOrdinaryConversationOrTvAudio(
            utterance: String,
            context: Context? = null
        ): Boolean {
            val cleaned = normalizeText(utterance)
            if (cleaned.isEmpty()) return true

            val tvOrChatterMarkers = listOf(
                "breaking news",
                "tonight at nine",
                "stay tuned",
                "commercial break",
                "weather forecast",
                "sponsored by",
                "in today's episode",
                "subscribe to our channel",
                "like and subscribe",
                "yaar aaj match",
                "aaj khane mein kya",
                "chai bana do",
                "pass me the remote",
                "did you see the game",
                "kal office jaldi",
                "traffic bohot zyada",
                "what time is dinner",
                "who scored the goal",
                "mein abhi baat kar raha tha"
            )
            if (tvOrChatterMarkers.any { cleaned.contains(it) }) {
                return true
            }

            if (context != null) {
                val remainder = extractCommandAfterWakePhrase(utterance).ifBlank { utterance }
                val tasks = try {
                    SuperAgiBrainEngine.extractAllDeviceTasks(context, remainder)
                } catch (_: Exception) {
                    emptyList()
                }
                val isReturnCmd = try {
                    com.omnivoice.agentfix.UiTools.isReturnToPreviousAppCommand(remainder)
                } catch (_: Exception) {
                    false
                }
                if (tasks.isEmpty() && !isReturnCmd && !matchesCancelOrStopPhrase(utterance) && !matchesSleepPhrase(utterance)) {
                    val directAssistantMarkers = listOf(
                        "jarvis", "omni", "tum", "aap", "you", "your", "mujhe", "mera", "meri",
                        "tell me", "batao", "sunao", "joke", "waqt", "time", "date", "mosam", "weather"
                    )
                    if (directAssistantMarkers.none { cleaned.contains(it) }) {
                        return true
                    }
                }
            }
            return false
        }

        internal fun normalizeText(input: String): String {
            return input.trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[\"'`.,!?؛،۔\\-_]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}

/**
 * Process-wide coordinator enforcing:
 * 1. Single-owner microphone lock across `MainActivity`, `WakeWordForegroundService`, `BootReceiver`,
 *    `OmniVoiceInteractionService`, `AssistantAudioStateMachine`, and `OmniLiveSessionEngine`.
 * 2. Device-pinned `isUserArmed`, `isStoppedByUser`, and `isDeviceMuted` in `SharedPreferences`.
 * 3. Bounded command window after `"Jarvis"` wake word with timeout and `"bas/stop/cancel"` handling.
 * 4. Suppression of self-triggering during TTS and notification announcements.
 * 5. Separate tracking of `falseWakes`, `missedWakes`, and `unintendedExecutions`.
 */
class GlobalWakeSpeechCoordinator {

    private val lock = Any()

    private val _activeMicOwner = MutableStateFlow(MicComponentOwner.NONE)
    val activeMicOwner: StateFlow<MicComponentOwner> = _activeMicOwner.asStateFlow()

    private val _lifecycleState = MutableStateFlow(WakeSpeechLifecycleState.DISARMED_STOPPED)
    val lifecycleState: StateFlow<WakeSpeechLifecycleState> = _lifecycleState.asStateFlow()

    private val _activeInputRoute = MutableStateFlow(AudioInputRouteType.BUILT_IN_MIC)
    val activeInputRoute: StateFlow<AudioInputRouteType> = _activeInputRoute.asStateFlow()

    private val _isUserArmedFlow = MutableStateFlow(false)
    val isUserArmedFlow: StateFlow<Boolean> = _isUserArmedFlow.asStateFlow()

    private val _isStoppedByUserFlow = MutableStateFlow(false)
    val isStoppedByUserFlow: StateFlow<Boolean> = _isStoppedByUserFlow.asStateFlow()

    private val _isDeviceMutedFlow = MutableStateFlow(false)
    val isDeviceMutedFlow: StateFlow<Boolean> = _isDeviceMutedFlow.asStateFlow()

    private val _isCommandWindowOpenFlow = MutableStateFlow(false)
    val isCommandWindowOpenFlow: StateFlow<Boolean> = _isCommandWindowOpenFlow.asStateFlow()

    private val activeOwnerReleaseCallbacks = mutableMapOf<MicComponentOwner, () -> Unit>()
    private var currentActiveOwnersCount = 0
    private var maxConcurrentOwnersObserved = 0

    @Volatile
    private var commandWindowOpenedAtMs: Long = 0L

    @Volatile
    private var commandWindowExpiresAtMs: Long = 0L

    @Volatile
    private var isAssistantSpeakingNow: Boolean = false

    @Volatile
    private var lastAssistantSpeechEndedAtMs: Long = 0L

    @Volatile
    private var simulatedHeadsetRouteForTest: AudioInputRouteType? = null

    @Volatile
    private var simulatedForegroundPackageForTest: String? = null

    private val recentAssistantSpeechPhrases = CopyOnWriteArrayList<String>()

    private val validWakesCounter = AtomicInteger(0)
    private val falseWakesCounter = AtomicInteger(0)
    private val missedWakesCounter = AtomicInteger(0)
    private val unintendedExecutionsCounter = AtomicInteger(0)
    private val suppressedSelfTriggersCounter = AtomicInteger(0)
    private val ignoredBackgroundConversationCounter = AtomicInteger(0)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(AssistantAudioStateMachine.PREFS_NAME, Context.MODE_PRIVATE)

    // =========================================================================
    // 1. SINGLE-OWNER MICROPHONE ARBITRATION
    // =========================================================================

    fun acquireMicrophoneOwnership(
        owner: MicComponentOwner,
        onReleaseRequired: () -> Unit = {}
    ): Boolean {
        synchronized(lock) {
            if (_isStoppedByUserFlow.value || _isDeviceMutedFlow.value) {
                return false
            }
            val current = _activeMicOwner.value
            if (current != MicComponentOwner.NONE && current != owner) {
                val prevCallback = activeOwnerReleaseCallbacks.remove(current)
                currentActiveOwnersCount = 0
                _activeMicOwner.value = MicComponentOwner.NONE
                try {
                    prevCallback?.invoke()
                } catch (_: Exception) {
                }
            }
            activeOwnerReleaseCallbacks[owner] = onReleaseRequired
            _activeMicOwner.value = owner
            currentActiveOwnersCount = if (owner == MicComponentOwner.NONE) 0 else 1
            if (currentActiveOwnersCount > maxConcurrentOwnersObserved) {
                maxConcurrentOwnersObserved = currentActiveOwnersCount
            }
            refreshLifecycleStateLocked()
            return true
        }
    }

    fun releaseMicrophoneOwnership(owner: MicComponentOwner) {
        synchronized(lock) {
            activeOwnerReleaseCallbacks.remove(owner)
            if (_activeMicOwner.value == owner) {
                _activeMicOwner.value = MicComponentOwner.NONE
                currentActiveOwnersCount = 0
                refreshLifecycleStateLocked()
            }
        }
    }

    fun releaseAllMicrophoneOwners() {
        val callbacksToRun: List<() -> Unit>
        synchronized(lock) {
            callbacksToRun = activeOwnerReleaseCallbacks.values.toList()
            activeOwnerReleaseCallbacks.clear()
            _activeMicOwner.value = MicComponentOwner.NONE
            currentActiveOwnersCount = 0
            _isCommandWindowOpenFlow.value = false
            commandWindowExpiresAtMs = 0L
            refreshLifecycleStateLocked()
        }
        for (cb in callbacksToRun) {
            try {
                cb.invoke()
            } catch (_: Exception) {
            }
        }
    }

    fun getActiveMicOwnerCount(): Int = synchronized(lock) { currentActiveOwnersCount }

    fun getMaxConcurrentMicOwnersObserved(): Int = synchronized(lock) { maxConcurrentOwnersObserved }

    // =========================================================================
    // 2. DEVICE-PINNED USER ARMED / STOP / MUTE STATE
    // =========================================================================

    fun markArmedInMemory(armed: Boolean) {
        synchronized(lock) {
            _isUserArmedFlow.value = armed
            if (armed) {
                _isStoppedByUserFlow.value = false
            }
            refreshLifecycleStateLocked()
        }
    }

    fun isStoppedByUserInMemory(): Boolean = _isStoppedByUserFlow.value
    fun isDeviceMutedInMemory(): Boolean = _isDeviceMutedFlow.value

    fun isUserArmedPersisted(context: Context): Boolean {
        val p = prefs(context)
        val armed = p.getBoolean(AssistantAudioStateMachine.KEY_USER_ARMED, false)
        val stopped = p.getBoolean(AssistantAudioStateMachine.KEY_STOPPED_BY_USER, false)
        return armed && !stopped
    }

    fun isStoppedByUserPersisted(context: Context): Boolean {
        return prefs(context).getBoolean(AssistantAudioStateMachine.KEY_STOPPED_BY_USER, false)
    }

    fun isDeviceMutedPersisted(context: Context): Boolean {
        return prefs(context).getBoolean(AssistantAudioStateMachine.KEY_DEVICE_MUTED, false)
    }

    fun armUserSessionAndPersist(context: Context, reason: String = "USER_ARMED_SESSION"): Boolean {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        synchronized(lock) {
            prefs(context).edit()
                .putBoolean(AssistantAudioStateMachine.KEY_USER_ARMED, hasPerm)
                .putBoolean(AssistantAudioStateMachine.KEY_STOPPED_BY_USER, false)
                .putBoolean(AssistantAudioStateMachine.KEY_DEVICE_MUTED, false)
                .putBoolean(AssistantAudioStateMachine.KEY_NEEDS_REARM_AFTER_OS_EVENT, false)
                .putString(AssistantAudioStateMachine.KEY_LAST_STOP_OR_STATE_REASON, reason)
                .commit()
            _isUserArmedFlow.value = hasPerm
            _isStoppedByUserFlow.value = false
            _isDeviceMutedFlow.value = false
            if (!hasPerm) {
                _lifecycleState.value = WakeSpeechLifecycleState.PERMISSION_REVOKED
                return false
            }
            refreshLifecycleStateLocked()
            return true
        }
    }

    fun stopAllAndPinToDevice(context: Context?, reason: String = "USER_STOP_ACTION") {
        releaseAllMicrophoneOwners()
        synchronized(lock) {
            _isUserArmedFlow.value = false
            _isStoppedByUserFlow.value = true
            _isCommandWindowOpenFlow.value = false
            commandWindowExpiresAtMs = 0L
            _lifecycleState.value = WakeSpeechLifecycleState.DISARMED_STOPPED
            if (context != null) {
                prefs(context).edit()
                    .putBoolean(AssistantAudioStateMachine.KEY_USER_ARMED, false)
                    .putBoolean(AssistantAudioStateMachine.KEY_STOPPED_BY_USER, true)
                    .putBoolean(AssistantAudioStateMachine.KEY_NEEDS_REARM_AFTER_OS_EVENT, false)
                    .putString(AssistantAudioStateMachine.KEY_LAST_STOP_OR_STATE_REASON, reason)
                    .commit()
            }
        }
    }

    fun setDeviceMutedPinned(context: Context?, muted: Boolean, reason: String = "USER_MUTE_TOGGLE") {
        if (muted) {
            releaseAllMicrophoneOwners()
        }
        synchronized(lock) {
            _isDeviceMutedFlow.value = muted
            if (muted) {
                _isCommandWindowOpenFlow.value = false
                commandWindowExpiresAtMs = 0L
            }
            if (context != null) {
                prefs(context).edit()
                    .putBoolean(AssistantAudioStateMachine.KEY_DEVICE_MUTED, muted)
                    .putString(AssistantAudioStateMachine.KEY_LAST_STOP_OR_STATE_REASON, reason)
                    .commit()
            }
            refreshLifecycleStateLocked()
        }
    }

    fun markRequiresTapToRearmAfterOsEvent(context: Context, reason: String) {
        releaseAllMicrophoneOwners()
        synchronized(lock) {
            prefs(context).edit()
                .putBoolean(AssistantAudioStateMachine.KEY_NEEDS_REARM_AFTER_OS_EVENT, true)
                .putString(AssistantAudioStateMachine.KEY_LAST_STOP_OR_STATE_REASON, reason)
                .commit()
            _lifecycleState.value = WakeSpeechLifecycleState.NEEDS_TAP_TO_REARM
        }
    }

    // =========================================================================
    // 3. EXPLICIT BOUNDED COMMAND WINDOW & RETURN TO WAKE-ONLY
    // =========================================================================

    fun openCommandWindow(
        nowMs: Long = System.currentTimeMillis(),
        durationMs: Long = AssistantAudioStateMachine.DEFAULT_COMMAND_WINDOW_TIMEOUT_MS,
        reason: String = "WAKE_WORD_JARVIS"
    ) {
        synchronized(lock) {
            if (_isStoppedByUserFlow.value || _isDeviceMutedFlow.value) return
            _isUserArmedFlow.value = true
            _isCommandWindowOpenFlow.value = true
            commandWindowOpenedAtMs = nowMs
            commandWindowExpiresAtMs = nowMs + durationMs.coerceAtLeast(1_000L)
            refreshLifecycleStateLocked()
        }
        acquireMicrophoneOwnership(MicComponentOwner.COMMAND_WINDOW_CAPTURE)
        Log.d("WakeSpeechCoord", "Command window OPENED for ${durationMs}ms ($reason)")
    }

    fun closeCommandWindow() {
        synchronized(lock) {
            _isCommandWindowOpenFlow.value = false
            commandWindowExpiresAtMs = 0L
            refreshLifecycleStateLocked()
        }
    }

    fun isCommandWindowOpen(nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            if (!_isCommandWindowOpenFlow.value) return false
            if (commandWindowExpiresAtMs in 1..nowMs) {
                _isCommandWindowOpenFlow.value = false
                commandWindowExpiresAtMs = 0L
                if (_isUserArmedFlow.value && !_isStoppedByUserFlow.value && !_isDeviceMutedFlow.value) {
                    _activeMicOwner.value = MicComponentOwner.WAKE_WORD_SERVICE
                    currentActiveOwnersCount = 1
                } else {
                    _activeMicOwner.value = MicComponentOwner.NONE
                    currentActiveOwnersCount = 0
                }
                refreshLifecycleStateLocked()
                return false
            }
            return true
        }
    }

    fun checkCommandWindowTimeout(nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            if (!_isCommandWindowOpenFlow.value) return false
            if (commandWindowExpiresAtMs in 1..nowMs) {
                _isCommandWindowOpenFlow.value = false
                commandWindowExpiresAtMs = 0L
                if (_isUserArmedFlow.value && !_isStoppedByUserFlow.value && !_isDeviceMutedFlow.value) {
                    _activeMicOwner.value = MicComponentOwner.WAKE_WORD_SERVICE
                    currentActiveOwnersCount = 1
                } else {
                    _activeMicOwner.value = MicComponentOwner.NONE
                    currentActiveOwnersCount = 0
                }
                refreshLifecycleStateLocked()
                return true
            }
            return false
        }
    }

    fun returnToWakeOnlyListening(reason: String = "COMMAND_COMPLETED_OR_CANCELED") {
        synchronized(lock) {
            _isCommandWindowOpenFlow.value = false
            commandWindowExpiresAtMs = 0L
            if (_isUserArmedFlow.value && !_isStoppedByUserFlow.value && !_isDeviceMutedFlow.value) {
                _activeMicOwner.value = MicComponentOwner.WAKE_WORD_SERVICE
                currentActiveOwnersCount = 1
            } else {
                _activeMicOwner.value = MicComponentOwner.NONE
                currentActiveOwnersCount = 0
            }
            refreshLifecycleStateLocked()
        }
        Log.d("WakeSpeechCoord", "Returned to WAKE_ONLY_LISTENING ($reason)")
    }

    // =========================================================================
    // 4. SELF-TRIGGER SUPPRESSION (TTS & NOTIFICATION ANNOUNCEMENTS)
    // =========================================================================

    @Volatile
    private var activeSpeechOwnerToken: String? = null

    fun notifyAssistantSpeechStarted(
        spokenText: String,
        source: String = "TTS",
        ownerToken: String? = null
    ) {
        synchronized(lock) {
            isAssistantSpeakingNow = true
            if (!ownerToken.isNullOrBlank()) {
                activeSpeechOwnerToken = ownerToken
            }
            val normalized = AssistantAudioStateMachine.normalizeText(spokenText)
            if (normalized.isNotBlank()) {
                recentAssistantSpeechPhrases.add(normalized)
                while (recentAssistantSpeechPhrases.size > 12) {
                    try {
                        recentAssistantSpeechPhrases.removeAt(0)
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            refreshLifecycleStateLocked()
        }
    }

    fun notifyAssistantSpeechEnded(
        nowMs: Long = System.currentTimeMillis(),
        immediateCooldownClear: Boolean = false,
        ownerToken: String? = null,
        forceRelease: Boolean = false,
        source: String = "TTS"
    ) {
        synchronized(lock) {
            if (!forceRelease &&
                !ownerToken.isNullOrBlank() &&
                !activeSpeechOwnerToken.isNullOrBlank() &&
                ownerToken != activeSpeechOwnerToken
            ) {
                // Stale callback from an older playback turn: do not clear the active turn's suppression!
                return
            }
            val wasSpeaking = isAssistantSpeakingNow
            isAssistantSpeakingNow = false
            activeSpeechOwnerToken = null
            if (immediateCooldownClear) {
                lastAssistantSpeechEndedAtMs = 0L
                recentAssistantSpeechPhrases.clear()
            } else if (wasSpeaking) {
                lastAssistantSpeechEndedAtMs = nowMs
            }
            refreshLifecycleStateLocked()
        }
    }

    suspend fun <T> withRealPlaybackSuppression(
        spokenText: String,
        source: String = "PLAYBACK",
        ownerToken: String = "playback_${System.currentTimeMillis()}",
        block: suspend () -> T
    ): T {
        notifyAssistantSpeechStarted(spokenText = spokenText, source = source, ownerToken = ownerToken)
        return try {
            block()
        } catch (e: Throwable) {
            notifyAssistantSpeechEnded(immediateCooldownClear = true, ownerToken = ownerToken)
            throw e
        } finally {
            if (activeSpeechOwnerToken == ownerToken) {
                notifyAssistantSpeechEnded(ownerToken = ownerToken)
            }
        }
    }

    fun isSelfTriggerSuppressed(nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            if (isAssistantSpeakingNow) return true
            val elapsed = nowMs - lastAssistantSpeechEndedAtMs
            return lastAssistantSpeechEndedAtMs > 0L &&
                elapsed in 0..AssistantAudioStateMachine.SELF_SPEECH_TAIL_COOLDOWN_MS
        }
    }

    fun isEchoOfAssistantOrNotificationSpeech(
        candidateTranscript: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (isSelfTriggerSuppressed(nowMs)) return true
        val elapsedSinceSpeechEnd = nowMs - lastAssistantSpeechEndedAtMs
        if (lastAssistantSpeechEndedAtMs <= 0L ||
            elapsedSinceSpeechEnd !in 0..(AssistantAudioStateMachine.SELF_SPEECH_TAIL_COOLDOWN_MS * 3)
        ) {
            return false
        }
        val cleaned = AssistantAudioStateMachine.normalizeText(candidateTranscript)
        if (cleaned.isEmpty()) return false
        return recentAssistantSpeechPhrases.any { recent ->
            recent.isNotBlank() && (cleaned == recent || recent.contains(cleaned) || cleaned.contains(recent))
        }
    }

    fun recordSuppressedSelfTrigger(reason: String) {
        suppressedSelfTriggersCounter.incrementAndGet()
        Log.d("WakeSpeechCoord", "Self-trigger suppressed: $reason")
    }

    // =========================================================================
    // 5. DETERMINISTIC WAKE & SPEECH CANDIDATE EVALUATION + TELEMETRY
    // =========================================================================

    fun evaluateAudioCandidate(
        context: Context?,
        transcript: String,
        nowMs: Long = System.currentTimeMillis(),
        expectedWakeInTestAudio: Boolean? = null,
        expectedCommandExecutionInTestAudio: Boolean? = null,
        customWakeWord: String = AssistantAudioStateMachine.DEFAULT_WAKE_WORD
    ): WakeSpeechEvaluationResult {
        checkCommandWindowTimeout(nowMs)

        if (!_isUserArmedFlow.value || _isStoppedByUserFlow.value || _isDeviceMutedFlow.value) {
            return WakeSpeechEvaluationResult(
                decision = WakeSpeechCandidateDecision.IGNORED_WHILE_STOPPED_OR_MUTED,
                shouldExecuteCommand = false,
                extractedCommandText = "",
                lifecycleStateAfter = _lifecycleState.value,
                micOwnerAfter = _activeMicOwner.value,
                reason = "Ignored audio while assistant is stopped/disarmed or muted"
            )
        }

        if (isEchoOfAssistantOrNotificationSpeech(transcript, nowMs)) {
            suppressedSelfTriggersCounter.incrementAndGet()
            return WakeSpeechEvaluationResult(
                decision = WakeSpeechCandidateDecision.SUPPRESSED_DURING_TTS_OR_NOTIFICATION,
                shouldExecuteCommand = false,
                extractedCommandText = "",
                lifecycleStateAfter = _lifecycleState.value,
                micOwnerAfter = _activeMicOwner.value,
                reason = "Suppressed self-trigger from TTS or notification announcement"
            )
        }

        val trimmed = transcript.trim()
        val hasWakeWord = AssistantAudioStateMachine.matchesWakePhrase(trimmed, customWakeWord)
        val remainderAfterWake = if (hasWakeWord) {
            AssistantAudioStateMachine.extractCommandAfterWakePhrase(trimmed, customWakeWord)
        } else {
            trimmed
        }

        if (AssistantAudioStateMachine.isFullStopListeningPhrase(trimmed)) {
            stopAllAndPinToDevice(context, "VOICE_STOP_LISTENING")
            return WakeSpeechEvaluationResult(
                decision = WakeSpeechCandidateDecision.STOP_AND_DISARM_PINNED,
                shouldExecuteCommand = false,
                extractedCommandText = "",
                lifecycleStateAfter = _lifecycleState.value,
                micOwnerAfter = _activeMicOwner.value,
                reason = "User requested stop listening -> mic released & pinned to device"
            )
        }

        if (AssistantAudioStateMachine.matchesCancelOrStopPhrase(trimmed) &&
            (_isCommandWindowOpenFlow.value || hasWakeWord || isAssistantSpeakingNow)
        ) {
            returnToWakeOnlyListening("VOICE_CANCEL_BAS_STOP")
            return WakeSpeechEvaluationResult(
                decision = WakeSpeechCandidateDecision.CANCEL_OR_STOP_COMMAND_WINDOW,
                shouldExecuteCommand = false,
                extractedCommandText = "",
                lifecycleStateAfter = _lifecycleState.value,
                micOwnerAfter = _activeMicOwner.value,
                reason = "Cancel phrase ('$trimmed') -> closed command window and returned to wake-only listening"
            )
        }

        val windowOpenNow = isCommandWindowOpen(nowMs)

        if (!windowOpenNow) {
            if (!hasWakeWord) {
                if (expectedWakeInTestAudio == true) {
                    missedWakesCounter.incrementAndGet()
                } else {
                    ignoredBackgroundConversationCounter.incrementAndGet()
                }
                return WakeSpeechEvaluationResult(
                    decision = WakeSpeechCandidateDecision.IGNORED_UNADDRESSED_IN_WAKE_ONLY,
                    shouldExecuteCommand = false,
                    extractedCommandText = "",
                    lifecycleStateAfter = _lifecycleState.value,
                    micOwnerAfter = _activeMicOwner.value,
                    reason = "Wake-only mode: ignored speech without wake word '$customWakeWord'"
                )
            }

            if (expectedWakeInTestAudio == false) {
                falseWakesCounter.incrementAndGet()
            } else {
                validWakesCounter.incrementAndGet()
            }

            openCommandWindow(nowMs = nowMs, reason = "WAKE_WORD_DETECTED")

            if (remainderAfterWake.isBlank()) {
                return WakeSpeechEvaluationResult(
                    decision = WakeSpeechCandidateDecision.WAKE_WORD_DETECTED_OPEN_WINDOW,
                    shouldExecuteCommand = false,
                    extractedCommandText = "",
                    lifecycleStateAfter = _lifecycleState.value,
                    micOwnerAfter = _activeMicOwner.value,
                    reason = "Wake word '$customWakeWord' detected -> opened bounded command window"
                )
            }

            if (AssistantAudioStateMachine.isOrdinaryConversationOrTvAudio(remainderAfterWake, context)) {
                ignoredBackgroundConversationCounter.incrementAndGet()
                returnToWakeOnlyListening("IGNORED_NON_COMMAND_AFTER_WAKE")
                return WakeSpeechEvaluationResult(
                    decision = WakeSpeechCandidateDecision.IGNORED_ORDINARY_CONVERSATION_OR_TV_AUDIO,
                    shouldExecuteCommand = false,
                    extractedCommandText = "",
                    lifecycleStateAfter = _lifecycleState.value,
                    micOwnerAfter = _activeMicOwner.value,
                    reason = "Ignored non-command chatter after wake phrase"
                )
            }

            if (expectedCommandExecutionInTestAudio == false) {
                unintendedExecutionsCounter.incrementAndGet()
            }
            returnToWakeOnlyListening("INLINE_WAKE_COMMAND_COMPLETED")
            return WakeSpeechEvaluationResult(
                decision = WakeSpeechCandidateDecision.WAKE_AND_INLINE_COMMAND_ACCEPTED,
                shouldExecuteCommand = true,
                extractedCommandText = remainderAfterWake,
                lifecycleStateAfter = _lifecycleState.value,
                micOwnerAfter = _activeMicOwner.value,
                reason = "Wake word + inline command accepted -> executing '$remainderAfterWake' & returning to wake-only"
            )
        }

        val effectiveCommand = remainderAfterWake.ifBlank { trimmed }
        if (effectiveCommand.isBlank() ||
            AssistantAudioStateMachine.isOrdinaryConversationOrTvAudio(effectiveCommand, context)
        ) {
            ignoredBackgroundConversationCounter.incrementAndGet()
            return WakeSpeechEvaluationResult(
                decision = WakeSpeechCandidateDecision.IGNORED_ORDINARY_CONVERSATION_OR_TV_AUDIO,
                shouldExecuteCommand = false,
                extractedCommandText = "",
                lifecycleStateAfter = _lifecycleState.value,
                micOwnerAfter = _activeMicOwner.value,
                reason = "Ignored ordinary conversation or TV audio during open command window"
            )
        }

        if (expectedCommandExecutionInTestAudio == false) {
            unintendedExecutionsCounter.incrementAndGet()
        }
        returnToWakeOnlyListening("WINDOW_COMMAND_COMPLETED")
        return WakeSpeechEvaluationResult(
            decision = WakeSpeechCandidateDecision.COMMAND_ACCEPTED_IN_WINDOW,
            shouldExecuteCommand = true,
            extractedCommandText = effectiveCommand,
            lifecycleStateAfter = _lifecycleState.value,
            micOwnerAfter = _activeMicOwner.value,
            reason = "Command accepted inside active command window -> executing '$effectiveCommand' & returning to wake-only"
        )
    }

    fun resetTelemetryCounters() {
        validWakesCounter.set(0)
        falseWakesCounter.set(0)
        missedWakesCounter.set(0)
        unintendedExecutionsCounter.set(0)
        suppressedSelfTriggersCounter.set(0)
        ignoredBackgroundConversationCounter.set(0)
        synchronized(lock) {
            maxConcurrentOwnersObserved = currentActiveOwnersCount
        }
    }

    fun getTelemetrySnapshot(): WakeSpeechTelemetrySnapshot {
        synchronized(lock) {
            return WakeSpeechTelemetrySnapshot(
                validWakesCount = validWakesCounter.get(),
                falseWakesCount = falseWakesCounter.get(),
                missedWakesCount = missedWakesCounter.get(),
                unintendedExecutionsCount = unintendedExecutionsCounter.get(),
                suppressedSelfTriggersCount = suppressedSelfTriggersCounter.get(),
                ignoredBackgroundConversationCount = ignoredBackgroundConversationCounter.get(),
                activeMicOwner = _activeMicOwner.value,
                activeMicOwnerCount = currentActiveOwnersCount,
                maxConcurrentMicOwnersObserved = maxConcurrentOwnersObserved,
                lifecycleState = _lifecycleState.value,
                inputRoute = _activeInputRoute.value,
                isUserArmed = _isUserArmedFlow.value,
                isStoppedByUser = _isStoppedByUserFlow.value,
                isDeviceMuted = _isDeviceMutedFlow.value,
                isCommandWindowOpen = _isCommandWindowOpenFlow.value,
                continuousSpeechRecognizerLoopUsedAsWakeDetector = false
            )
        }
    }

    // =========================================================================
    // 6. HEADSET INPUT ROUTING & SYSTEM ASSISTANT / REBOOT / REVOCATION CHECK
    // =========================================================================

    fun setSimulatedHeadsetRouteForTest(route: AudioInputRouteType?) {
        simulatedHeadsetRouteForTest = route
        _activeInputRoute.value = route ?: AudioInputRouteType.BUILT_IN_MIC
    }

    fun setSimulatedForegroundPackageForTest(packageName: String?) {
        simulatedForegroundPackageForTest = packageName
    }

    fun getSimulatedForegroundPackageForTest(): String? = simulatedForegroundPackageForTest

    fun detectActiveAudioInputRoute(context: Context): AudioInputRouteType {
        val simulated = simulatedHeadsetRouteForTest
        if (simulated != null) {
            _activeInputRoute.value = simulated
            return simulated
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            try {
                val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                for (dev in inputDevices) {
                    when (dev.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET -> {
                            _activeInputRoute.value = AudioInputRouteType.BLUETOOTH_HEADSET
                            return AudioInputRouteType.BLUETOOTH_HEADSET
                        }
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_DEVICE -> {
                            _activeInputRoute.value = AudioInputRouteType.USB_HEADSET
                            return AudioInputRouteType.USB_HEADSET
                        }
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                            _activeInputRoute.value = AudioInputRouteType.WIRED_HEADSET
                            return AudioInputRouteType.WIRED_HEADSET
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        _activeInputRoute.value = AudioInputRouteType.BUILT_IN_MIC
        return AudioInputRouteType.BUILT_IN_MIC
    }

    fun applyPreferredInputDevice(context: Context, audioRecord: AudioRecord?): AudioInputRouteType {
        val route = detectActiveAudioInputRoute(context)
        if (audioRecord == null) return route
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return route
        try {
            val preferred = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { dev ->
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
            if (preferred != null) {
                audioRecord.setPreferredDevice(preferred)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        audioManager.setCommunicationDevice(preferred)
                    } catch (_: Exception) {
                    }
                } else if (preferred.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    @Suppress("DEPRECATION")
                    if (!audioManager.isBluetoothScoOn) {
                        @Suppress("DEPRECATION")
                        audioManager.startBluetoothSco()
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = true
                    }
                }
            }
        } catch (_: Exception) {
        }
        return route
    }

    fun inspectWakeAndAssistantReadiness(context: Context): WakeAndAssistantReadinessReport {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val roleAssistantHeld = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = context.getSystemService(RoleManager::class.java)
                rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } || try {
            val activeVis = Settings.Secure.getString(context.contentResolver, "voice_interaction_service").orEmpty()
            activeVis.contains(context.packageName)
        } catch (_: Exception) {
            false
        }

        val visReady = OmniVoiceInteractionService.isServiceReady.value || roleAssistantHeld

        val hasFgsMicType = try {
            val cn = ComponentName(context, WakeWordForegroundService::class.java)
            val si = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getServiceInfo(cn, PackageManager.ComponentInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getServiceInfo(cn, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (si.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
            } else {
                true
            }
        } catch (_: Exception) {
            true
        }

        val p = prefs(context)
        val armedPersisted = p.getBoolean(AssistantAudioStateMachine.KEY_USER_ARMED, false)
        val stoppedPersisted = p.getBoolean(AssistantAudioStateMachine.KEY_STOPPED_BY_USER, false)
        val mutedPersisted = p.getBoolean(AssistantAudioStateMachine.KEY_DEVICE_MUTED, false)
        val needsRearmFlag = p.getBoolean(AssistantAudioStateMachine.KEY_NEEDS_REARM_AFTER_OS_EVENT, false)
        val serviceRunning = WakeWordForegroundService.isServiceActive.value
        val inputRoute = detectActiveAudioInputRoute(context)

        synchronized(lock) {
            _isUserArmedFlow.value = armedPersisted && !stoppedPersisted && hasMicPerm
            _isStoppedByUserFlow.value = stoppedPersisted
            _isDeviceMutedFlow.value = mutedPersisted

            if (!hasMicPerm && (armedPersisted || serviceRunning || _activeMicOwner.value != MicComponentOwner.NONE)) {
                releaseAllMicrophoneOwners()
                _lifecycleState.value = WakeSpeechLifecycleState.PERMISSION_REVOKED
            } else if (stoppedPersisted) {
                _lifecycleState.value = WakeSpeechLifecycleState.DISARMED_STOPPED
            } else if (mutedPersisted) {
                _lifecycleState.value = WakeSpeechLifecycleState.MUTED_PINNED
            } else if (armedPersisted && !serviceRunning && _activeMicOwner.value == MicComponentOwner.NONE) {
                _lifecycleState.value = WakeSpeechLifecycleState.NEEDS_TAP_TO_REARM
            } else {
                refreshLifecycleStateLocked()
            }
        }

        val actualState = _lifecycleState.value
        val requiresTapToRearm = actualState == WakeSpeechLifecycleState.NEEDS_TAP_TO_REARM || needsRearmFlag
        val canActivateBg = hasMicPerm &&
            armedPersisted &&
            !stoppedPersisted &&
            !mutedPersisted &&
            hasFgsMicType &&
            (serviceRunning || roleAssistantHeld || visReady)

        val summary = when (actualState) {
            WakeSpeechLifecycleState.PERMISSION_REVOKED ->
                "RECORD_AUDIO permission revoked — tap to grant microphone access in context."
            WakeSpeechLifecycleState.NEEDS_TAP_TO_REARM ->
                "Stopped by reboot or OS force-stop — Tap to Re-Arm (or set as Default Digital Assistant)."
            WakeSpeechLifecycleState.DISARMED_STOPPED ->
                "Disarmed / Stopped (pinned to device). Tap Arm to start listening for 'Jarvis'."
            WakeSpeechLifecycleState.MUTED_PINNED ->
                "Microphone muted (pinned to device). Unmute to resume 'Jarvis' wake detection."
            WakeSpeechLifecycleState.SPEAKING_SUPPRESSED ->
                "Assistant speaking — microphone self-trigger suppressed."
            WakeSpeechLifecycleState.COMMAND_WINDOW_OPEN ->
                "Command window open (${inputRoute.label}) — say your command or 'bas/stop/cancel'."
            WakeSpeechLifecycleState.WAKE_ONLY_LISTENING ->
                "Wake-only listening active for 'Jarvis' (${inputRoute.label})."
        }

        return WakeAndAssistantReadinessReport(
            hasRecordAudioPermission = hasMicPerm,
            isRoleAssistantHeld = roleAssistantHeld,
            isVoiceInteractionServiceReady = visReady,
            hasForegroundServiceMicrophoneType = hasFgsMicType,
            isWakeServiceRunning = serviceRunning,
            isUserArmedPersisted = armedPersisted,
            isStoppedByUserPersisted = stoppedPersisted,
            isDeviceMutedPersisted = mutedPersisted,
            canActivateInBackgroundOrScreenOff = canActivateBg,
            requiresTapToRearm = requiresTapToRearm,
            actualLifecycleState = actualState,
            inputRoute = inputRoute,
            statusSummary = summary
        )
    }

    private fun refreshLifecycleStateLocked() {
        _lifecycleState.value = when {
            _isStoppedByUserFlow.value || !_isUserArmedFlow.value ->
                WakeSpeechLifecycleState.DISARMED_STOPPED
            _isDeviceMutedFlow.value ->
                WakeSpeechLifecycleState.MUTED_PINNED
            isAssistantSpeakingNow ->
                WakeSpeechLifecycleState.SPEAKING_SUPPRESSED
            _isCommandWindowOpenFlow.value ->
                WakeSpeechLifecycleState.COMMAND_WINDOW_OPEN
            else ->
                WakeSpeechLifecycleState.WAKE_ONLY_LISTENING
        }
    }
}

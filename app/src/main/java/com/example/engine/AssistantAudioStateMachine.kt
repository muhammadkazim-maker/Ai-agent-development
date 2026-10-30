package com.example.engine

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Strict Microphone & Audio Pipeline Ownership State Machine:
 * `IDLE -> WAKE_LISTEN -> STT_RECORDING -> THINKING -> SPEAKING -> WAKE_LISTEN`
 *
 * Guarantees:
 * 1. Only ONE component owns the hardware microphone at any instant ([WAKE_LISTEN] or [STT_RECORDING]).
 * 2. Never listens ([WAKE_LISTEN] or [STT_RECORDING]) while TTS is [SPEAKING] unless TTS explicitly finishes or barge-in triggers.
 * 3. Automatically returns from [SPEAKING] to [WAKE_LISTEN] when TTS completes.
 */
enum class AudioPipelineOwner {
    IDLE,
    WAKE_LISTEN,
    STT_RECORDING,
    THINKING,
    SPEAKING;

    val isMicrophoneActive: Boolean
        get() = this == WAKE_LISTEN || this == STT_RECORDING
}

/**
 * Full 9-State Operating State Machine defined in Section 4 (`R1`, `F-016`–`F-024`):
 * - `DISARMED`
 * - `SLEEP_STANDBY`
 * - `ACTIVE_LISTENING`
 * - `RECORDING_UTTERANCE`
 * - `THINKING`
 * - `SPEAKING`
 * - `AWAITING_CONFIRMATION`
 * - `EXECUTING_STEP`
 * - `RECOVERING_PREREQUISITE`
 */
enum class AssistantOperatingState {
    DISARMED,
    SLEEP_STANDBY,
    ACTIVE_LISTENING,
    RECORDING_UTTERANCE,
    THINKING,
    SPEAKING,
    AWAITING_CONFIRMATION,
    EXECUTING_STEP,
    RECOVERING_PREREQUISITE;

    /**
     * Inactivity timer MUST pause during TTS playback, tool execution, confirmation prompts,
     * and prerequisite recovery (`F-018`).
     */
    val pausesInactivityTimer: Boolean
        get() = this == THINKING ||
            this == SPEAKING ||
            this == AWAITING_CONFIRMATION ||
            this == EXECUTING_STEP ||
            this == RECOVERING_PREREQUISITE
}

data class AudioStateTransitionEvent(
    val from: AudioPipelineOwner,
    val to: AudioPipelineOwner,
    val reason: String,
    val timestampMs: Long = System.currentTimeMillis()
)

class AssistantAudioStateMachine(
    private val onWakeListenRequested: () -> Unit = {},
    private val onSttRecordingRequested: () -> Unit = {},
    private val onThinkingRequested: () -> Unit = {},
    private val onSpeakingRequested: () -> Unit = {},
    private val onIdleRequested: () -> Unit = {}
) {

    companion object {
        private const val TAG = "AudioStateMachine"
        const val DEFAULT_INACTIVITY_TIMEOUT_SECONDS = 60
        const val MIN_INACTIVITY_TIMEOUT_SECONDS = 30
        const val MAX_INACTIVITY_TIMEOUT_SECONDS = 300

        /**
         * Matches wake phrases in English, Roman Urdu, Urdu script, and custom assistant names (`F-019`).
         */
        fun matchesWakePhrase(utterance: String, customAssistantName: String = "Omni"): Boolean {
            val cleaned = utterance.trim().lowercase()
            if (cleaned.isBlank()) return false
            val customLower = customAssistantName.trim().lowercase()

            val defaultWakeTokens = listOf(
                "hey omni",
                "ok omni",
                "hello omni",
                "wake up omni",
                "omni suno",
                "suno omni",
                "اومنی",
                "او منی",
                "ہیلو اومنی"
            )
            if (defaultWakeTokens.any { cleaned.contains(it) }) return true

            if (customLower.isNotBlank()) {
                val customTokens = listOf(
                    "hey $customLower",
                    "hello $customLower",
                    "wake up $customLower",
                    "$customLower suno",
                    "suno $customLower",
                    customLower
                )
                if (customTokens.any { cleaned.contains(it) }) return true
            }
            return false
        }

        /**
         * Matches explicit sleep / standby commands in English, Roman Urdu, and Urdu script (`F-020`).
         */
        fun matchesSleepPhrase(utterance: String): Boolean {
            val cleaned = utterance.trim().lowercase()
            if (cleaned.isBlank()) return false
            val sleepPhrases = listOf(
                "go to sleep",
                "sleep mode",
                "standby mode",
                "so jao",
                "ab so jao",
                "stop listening",
                "khamosh ho jao",
                "سو جاؤ",
                "خاموش ہو جاؤ"
            )
            return sleepPhrases.any { cleaned.contains(it) }
        }
    }

    private val _currentOwner = MutableStateFlow(AudioPipelineOwner.IDLE)
    val currentOwner: StateFlow<AudioPipelineOwner> = _currentOwner.asStateFlow()

    private val _operatingState = MutableStateFlow(AssistantOperatingState.DISARMED)
    val operatingState: StateFlow<AssistantOperatingState> = _operatingState.asStateFlow()

    private val _configuredTimeoutSeconds = MutableStateFlow(DEFAULT_INACTIVITY_TIMEOUT_SECONDS)
    val configuredTimeoutSeconds: StateFlow<Int> = _configuredTimeoutSeconds.asStateFlow()

    private val _inactivitySecondsRemaining = MutableStateFlow(DEFAULT_INACTIVITY_TIMEOUT_SECONDS)
    val inactivitySecondsRemaining: StateFlow<Int> = _inactivitySecondsRemaining.asStateFlow()

    private val _transitionHistory = MutableStateFlow<List<AudioStateTransitionEvent>>(emptyList())
    val transitionHistory: StateFlow<List<AudioStateTransitionEvent>> = _transitionHistory.asStateFlow()

    @Synchronized
    fun setConfiguredInactivityTimeoutSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(MIN_INACTIVITY_TIMEOUT_SECONDS, MAX_INACTIVITY_TIMEOUT_SECONDS)
        _configuredTimeoutSeconds.value = clamped
        _inactivitySecondsRemaining.value = clamped
    }

    @Synchronized
    fun resetInactivityCountdown() {
        _inactivitySecondsRemaining.value = _configuredTimeoutSeconds.value
    }

    /**
     * Advances the inactivity countdown by 1 second if and only if the assistant is in
     * `ACTIVE_LISTENING` and NOT paused by `SPEAKING`, `THINKING`, `EXECUTING_STEP`,
     * `AWAITING_CONFIRMATION`, or `RECOVERING_PREREQUISITE` (`F-017`, `F-018`).
     * Returns `true` if the countdown reached 0 and transitioned to `SLEEP_STANDBY`.
     */
    @Synchronized
    fun tickInactivitySecond(): Boolean {
        val state = _operatingState.value
        if (state.pausesInactivityTimer || state != AssistantOperatingState.ACTIVE_LISTENING) {
            return false
        }
        val next = (_inactivitySecondsRemaining.value - 1).coerceAtLeast(0)
        _inactivitySecondsRemaining.value = next
        if (next == 0) {
            transitionOperatingState(AssistantOperatingState.SLEEP_STANDBY, "inactivity_timeout_60s")
            return true
        }
        return false
    }

    @Synchronized
    fun transitionOperatingState(nextState: AssistantOperatingState, reason: String = "explicit") {
        _operatingState.value = nextState
        if (nextState == AssistantOperatingState.ACTIVE_LISTENING ||
            nextState == AssistantOperatingState.RECORDING_UTTERANCE
        ) {
            resetInactivityCountdown()
        }
        when (nextState) {
            AssistantOperatingState.DISARMED -> transitionTo(AudioPipelineOwner.IDLE, reason)
            AssistantOperatingState.SLEEP_STANDBY -> transitionTo(AudioPipelineOwner.WAKE_LISTEN, reason)
            AssistantOperatingState.ACTIVE_LISTENING -> transitionTo(AudioPipelineOwner.WAKE_LISTEN, reason)
            AssistantOperatingState.RECORDING_UTTERANCE -> transitionTo(AudioPipelineOwner.STT_RECORDING, reason)
            AssistantOperatingState.THINKING,
            AssistantOperatingState.EXECUTING_STEP,
            AssistantOperatingState.RECOVERING_PREREQUISITE -> transitionTo(AudioPipelineOwner.THINKING, reason)
            AssistantOperatingState.SPEAKING,
            AssistantOperatingState.AWAITING_CONFIRMATION -> transitionTo(AudioPipelineOwner.SPEAKING, reason)
        }
    }

    /**
     * Instant barge-in (`<150ms`) when the user speaks or taps the orb while the assistant is speaking (`F-021`, `F-039`).
     */
    @Synchronized
    fun triggerBargeIn(ttsManager: TextToSpeechManager? = null): Boolean {
        AudioOutputManager.bargeInAndFlush(ttsManager)
        _operatingState.value = AssistantOperatingState.RECORDING_UTTERANCE
        resetInactivityCountdown()
        return transitionTo(AudioPipelineOwner.STT_RECORDING, reason = "barge_in_user_interruption")
    }

    @Synchronized
    fun transitionTo(nextOwner: AudioPipelineOwner, reason: String = "explicit"): Boolean {
        val current = _currentOwner.value
        if (current == nextOwner) return true

        // Rule: Never start STT recording directly while TTS is SPEAKING unless explicitly interrupting TTS first
        if (current == AudioPipelineOwner.SPEAKING &&
            nextOwner == AudioPipelineOwner.STT_RECORDING &&
            !reason.contains("barge_in", ignoreCase = true) &&
            !reason.contains("force", ignoreCase = true)
        ) {
            Log.w(TAG, "Blocked invalid transition $current -> $nextOwner while TTS is speaking.")
            return false
        }

        Log.d(TAG, "Audio Hardware State Transition: $current -> $nextOwner ($reason)")
        _currentOwner.value = nextOwner
        _transitionHistory.value = (
            listOf(AudioStateTransitionEvent(current, nextOwner, reason)) + _transitionHistory.value
            ).take(40)

        // Keep operatingState synchronized when transitionTo is called directly
        _operatingState.value = when (nextOwner) {
            AudioPipelineOwner.IDLE -> AssistantOperatingState.DISARMED
            AudioPipelineOwner.WAKE_LISTEN -> {
                if (reason.contains("sleep", ignoreCase = true) || reason.contains("timeout", ignoreCase = true)) {
                    AssistantOperatingState.SLEEP_STANDBY
                } else {
                    AssistantOperatingState.ACTIVE_LISTENING
                }
            }
            AudioPipelineOwner.STT_RECORDING -> AssistantOperatingState.RECORDING_UTTERANCE
            AudioPipelineOwner.THINKING -> AssistantOperatingState.THINKING
            AudioPipelineOwner.SPEAKING -> AssistantOperatingState.SPEAKING
        }

        when (nextOwner) {
            AudioPipelineOwner.IDLE -> onIdleRequested()
            AudioPipelineOwner.WAKE_LISTEN -> onWakeListenRequested()
            AudioPipelineOwner.STT_RECORDING -> onSttRecordingRequested()
            AudioPipelineOwner.THINKING -> onThinkingRequested()
            AudioPipelineOwner.SPEAKING -> onSpeakingRequested()
        }
        return true
    }

    /**
     * Completes the [AudioPipelineOwner.SPEAKING] phase and returns ownership to [AudioPipelineOwner.WAKE_LISTEN].
     */
    @Synchronized
    fun onTtsSpeakingFinished() {
        if (_currentOwner.value == AudioPipelineOwner.SPEAKING) {
            resetInactivityCountdown()
            transitionTo(AudioPipelineOwner.WAKE_LISTEN, reason = "tts_completed")
        }
    }

    fun canListen(): Boolean {
        return _currentOwner.value != AudioPipelineOwner.SPEAKING
    }
}

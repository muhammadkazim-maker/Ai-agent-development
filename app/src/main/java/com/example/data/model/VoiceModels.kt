package com.example.data.model

/**
 * Represents structured action parsed from user speech by the AI Brain or Offline fallback.
 */
data class VoiceAction(
    val action: String, // "call", "send_sms", "open_app", "set_alarm", "set_timer", "toggle_flashlight", "adjust_volume", "read_notifications", "play_music", "navigate", "search_web", "general_chat"
    val target: String = "",
    val parameters: Map<String, String> = emptyMap(),
    val confirmationRequired: Boolean = false,
    val spokenResponse: String = "",
    val requiresUnlock: Boolean = false // Sensitive actions that require device unlock even with valid passphrase
)

/**
 * Lifecycle states of the Voice Assistant.
 */
sealed class VoiceState {
    object Idle : VoiceState()
    object ListeningForWakeWord : VoiceState()
    data class WakeWordDetected(val wakeWord: String) : VoiceState()
    data class ListeningForCommand(val partialText: String = "", val audioLevelRmsDb: Float = 0f) : VoiceState()
    data class ProcessingAi(val query: String) : VoiceState()
    data class AwaitingPassphrase(val prompt: String = "Device locked. Please speak your security passphrase.") : VoiceState()
    data class VerifyingVoice(val passphraseAttempt: String) : VoiceState()
    data class ConfirmingAction(val action: VoiceAction, val question: String) : VoiceState()
    data class ExecutingAction(val action: VoiceAction) : VoiceState()
    data class Speaking(val text: String) : VoiceState()
    data class Error(val message: String, val recoverySuggestion: String = "") : VoiceState()
}

/**
 * Enrolled voice biometric acoustic profile.
 * Stored locally with acoustic feature fingerprints:
 * - averagePitchHz (Fundamental Frequency F0)
 * - energyThreshold
 * - zeroCrossingRate (ZCR)
 */
data class VoiceEnrollmentProfile(
    val sampleCount: Int = 0,
    val averagePitchHz: Float = 0f,
    val energyThreshold: Float = 0f,
    val zeroCrossingRate: Float = 0f,
    val isEnrolled: Boolean = false
)

/**
 * Result of lock screen security evaluation.
 */
sealed class VoiceVerificationResult {
    object Success : VoiceVerificationResult()
    data class PassphraseFailed(val remainingAttempts: Int) : VoiceVerificationResult()
    data class BiometricsFailed(val confidenceScore: Float, val requiredThreshold: Float) : VoiceVerificationResult()
    data class LockedOut(val remainingSeconds: Long) : VoiceVerificationResult()
    object SensitiveActionBlocked : VoiceVerificationResult()
}

/**
 * Log entry for the assistant audit log.
 */
data class SecurityLogEntry(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val commandText: String,
    val actionType: String,
    val isLockedState: Boolean,
    val verificationPassed: Boolean,
    val responseSummary: String
)

/**
 * User configuration and sensitivity settings.
 */
data class AssistantConfig(
    val wakeWord: String = "Hey Omni",
    val sensitivity: Float = 0.7f,
    val voicePitch: Float = 1.0f,
    val voiceSpeed: Float = 1.0f,
    val requireVoiceBiometrics: Boolean = true,
    val biometricThreshold: Float = 0.65f,
    val maxFailedAttempts: Int = 3,
    val lockoutDurationMinutes: Int = 5,
    val autoRestartListening: Boolean = true,
    val oneTimePassphraseMode: Boolean = false
)

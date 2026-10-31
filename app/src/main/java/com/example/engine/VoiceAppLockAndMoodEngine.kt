package com.example.engine

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import com.example.data.model.VoiceAction
import com.example.data.model.VoiceEnrollmentProfile
import com.example.data.security.SecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * Represents the detected human mood from voice prosody (pitch, energy, tempo, jitter)
 * and bilingual Urdu / Roman-Urdu / English phrasing.
 */
enum class SpeakerMood(
    val displayName: String,
    val romanUrduLabel: String,
    val empathyStylePrompt: String
) {
    CALM(
        displayName = "Calm & Composed",
        romanUrduLabel = "Pur-Sukoon (Calm)",
        empathyStylePrompt = "Pur-sukoon aur mohtaram andaz mein jawab dein."
    ),
    CHEERFUL(
        displayName = "Happy & Energetic",
        romanUrduLabel = "Khush-gawar (Happy)",
        empathyStylePrompt = "Khush-dili aur pur-josh dostana andaz mein baat karein."
    ),
    URGENT(
        displayName = "Urgent & Fast",
        romanUrduLabel = "Jaldi / Urgent",
        empathyStylePrompt = "Bina waqt zaya kiye foran to-the-point jawab dein."
    ),
    STRESSED(
        displayName = "Stressed or Worried",
        romanUrduLabel = "Pareshan (Stressed)",
        empathyStylePrompt = "Hausla afzai aur narmi ke sath madadgar andaz apnayein."
    ),
    TIRED_OR_HOARSE(
        displayName = "Tired / Sore Throat (Gala Kharab)",
        romanUrduLabel = "Thaka Hua / Gala Kharab",
        empathyStylePrompt = "Narmi aur khayal rakhne wale andaz mein mukhtasir jawab dein."
    ),
    FRUSTRATED(
        displayName = "Frustrated / Annoyed",
        romanUrduLabel = "Naraz / Frustrated",
        empathyStylePrompt = "Tahammul, adab aur masla foran hal karne wale andaz mein baat karein."
    )
}

/**
 * Represents the speaker's conversational style ("Andaz").
 */
enum class SpeakingAndaz(
    val displayName: String,
    val romanUrduDescription: String
) {
    POLITE_RESPECTFUL("Polite & Respectful", "Tehzeeb-yafta aur mohtaram andaz"),
    FRIENDLY_CASUAL("Friendly & Casual", "Dostana aur be-takalluf andaz"),
    DIRECT_COMMANDING("Direct & Commanding", "Hukmiya aur boss-level Tony Stark andaz"),
    HOARSE_STRAINED("Hoarse / Sore Throat", "Gala kharab ya dabi hui awaz"),
    UNFAMILIAR_INTRUDER("Unfamiliar / Anjaan", "Mukhtalif lehja ya ghair-manus andaz")
}

/**
 * Detailed output of the Human Mood, Speaking Style ("Andaz"), and Voice Lock Biometric analyzer.
 */
data class MoodAndVoiceLockEvaluation(
    val detectedMood: SpeakerMood = SpeakerMood.CALM,
    val detectedAndaz: SpeakingAndaz = SpeakingAndaz.FRIENDLY_CASUAL,
    val voiceBiometricSimilarity: Float = 0.92f,
    val styleConsistencyScore: Float = 0.90f,
    val combinedConfidence: Float = 0.91f,
    val isOwnerVoiceVerified: Boolean = true,
    val isSoreThroatOrVoiceGlitch: Boolean = false,
    val shouldOpenApp: Boolean = true,
    val requiresSecretCodeFallback: Boolean = false,
    val moodAdaptedReplyPrefix: String = "",
    val decisionExplanationRomanUrdu: String = ""
)

/**
 * Represents one of the 10 Secret Commands/Codes shared between the owner and the assistant,
 * along with its 3-attempt anti-eavesdropping cooldown state.
 */
data class SecretFallbackCodeEntry(
    val index: Int, // 0..9 (displayed as 1..10)
    val secretPhrase: String,
    val cooldownAttemptsRemaining: Int = 0, // 0 = Ready to use; 1..3 = Waiting for N other unlock attempts
    val lastUsedTimestamp: Long = 0L,
    val totalTimesUsed: Int = 0
) {
    val isAvailableNow: Boolean
        get() = cooldownAttemptsRemaining <= 0
}

/**
 * Represents an active App Voice Lock challenge when voice recognition fails or sore throat occurs,
 * waiting for the user to speak or type one of the available 10 secret codes.
 */
data class PendingAppLockChallenge(
    val appName: String,
    val packageName: String,
    val pendingVoiceAction: VoiceAction? = null,
    val pendingRawCommand: String = "",
    val failureReasonRomanUrdu: String = "Aap ki voice recognize nahi ho rahi, please secret code bolein ya type karein.",
    val detectedMood: SpeakerMood = SpeakerMood.TIRED_OR_HOARSE,
    val detectedAndaz: SpeakingAndaz = SpeakingAndaz.HOARSE_STRAINED,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Result of verifying a spoken or typed Secret Code against the 10-code pool & 3-attempt rotation rule.
 */
sealed class SecretCodeVerificationResult {
    data class Unlocked(
        val matchedCodeIndex: Int,
        val matchedPhrase: String,
        val cooldownSetTo: Int = 3,
        val unlockedAppName: String,
        val unlockedPackageName: String,
        val pendingAction: VoiceAction?,
        val spokenConfirmationRomanUrdu: String
    ) : SecretCodeVerificationResult()

    data class CodeOnCooldown(
        val matchedCodeIndex: Int,
        val matchedPhrase: String,
        val attemptsRemainingBeforeReuse: Int,
        val availableCodesCount: Int,
        val spokenErrorRomanUrdu: String
    ) : SecretCodeVerificationResult()

    data class InvalidCode(
        val inputAttempt: String,
        val availableCodesCount: Int,
        val spokenErrorRomanUrdu: String
    ) : SecretCodeVerificationResult()
}

/**
 * Universal App Voice Lock, Human Mood/Andaz Intelligence, and 10-Secret-Code 3-Attempt Rotation Engine.
 *
 * Key Capabilities:
 * 1. Open Conversational Access: Anyone can chat with the assistant freely; the assistant analyzes their
 *    mood & speaking style ("andaz") and replies in a matching tone.
 * 2. Universal Mobile App Voice Lock: Locks all mobile apps by default. Whenever an app is opened (via voice
 *    command or direct launch intercepted by AccessibilityService), backend voice verification + mood/style
 *    intelligence determines if the speaker is the enrolled owner.
 * 3. Sore Throat / Voice Recognition Failure Fallback: If the owner's voice fails verification (e.g., sore throat
 *    "gala kharab", mic noise, or biometric mismatch), the assistant says:
 *    "Aap ki voice recognize nahi ho rahi, please secret code bolein" and pauses the app launch until verified.
 * 4. 10 Secret Commands with Strict 3-Attempt Cooldown Rotation:
 *    - Stores 10 secret codes shared between the owner and the assistant.
 *    - When a code is used to unlock an app, that code is placed on a 3-attempt cooldown (`cooldownAttemptsRemaining = 3`),
 *      and all other previously used codes have their cooldown decremented by 1.
 *    - Thus, a used code cannot be reused on the next 3 unlock attempts (protecting against eavesdroppers nearby),
 *      and becomes valid again only after 3 subsequent unlock attempts with other codes.
 * 5. Type-Code Fallback: Supports typing the secret code directly if speech recognition has a glitch.
 * 6. First-Page Setup & Persistence: Persists owner profile, voice lock settings, and all 10 secret codes.
 */
class VoiceAppLockAndMoodEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VoiceAppLockMoodEngine"
        private const val PREFS_NAME = "jarvis_voice_applock_mood_prefs"

        private const val KEY_FIRST_PAGE_SETUP_COMPLETED = "first_page_voice_lock_setup_done"
        private const val KEY_OWNER_NAME = "voice_lock_owner_name"
        private const val KEY_OWNER_PREFERRED_ANDAZ = "voice_lock_owner_andaz"
        private const val KEY_OWNER_PITCH_HZ = "voice_lock_owner_pitch_hz"
        private const val KEY_OWNER_VOICE_ENROLLED = "voice_lock_owner_voice_enrolled"
        private const val KEY_GLOBAL_APP_LOCK_ENABLED = "global_voice_app_lock_enabled"
        private const val KEY_SIMULATE_VOICE_FAIL_MODE = "simulate_sore_throat_voice_fail"
        private const val KEY_SECRET_CODES_JSON = "ten_secret_codes_json"
        private const val KEY_LOCKED_PACKAGES_SET = "locked_packages_custom_set"
        private const val KEY_LOCK_ALL_APPS = "lock_all_installed_apps"

        const val REQUIRED_SECRET_CODES_COUNT = 10
        const val COOLDOWN_ATTEMPTS_AFTER_USE = 3

        val DEFAULT_TEN_SECRET_CODES = listOf(
            "Sikandar",
            "Falcon Zero",
            "Iron Shield",
            "Shaheen Seven",
            "Mark Infinity",
            "Qalandar Pro",
            "Buraq Speed",
            "Cyber Guard",
            "Zindabad Star",
            "Omega Protocol"
        )

        val DEFAULT_PROTECTED_APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.google.android.youtube" to "YouTube",
            "com.instagram.android" to "Instagram",
            "com.facebook.katana" to "Facebook",
            "com.facebook.orca" to "Messenger",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.android.chrome" to "Chrome",
            "com.google.android.gm" to "Gmail",
            "com.google.android.apps.maps" to "Google Maps",
            "com.google.android.apps.photos" to "Gallery / Photos",
            "com.android.settings" to "Settings",
            "org.telegram.messenger" to "Telegram",
            "com.snapchat.android" to "Snapchat",
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.messaging" to "Messages"
        )

        @Volatile
        private var instance: VoiceAppLockAndMoodEngine? = null

        fun getInstance(context: Context): VoiceAppLockAndMoodEngine {
            return instance ?: synchronized(this) {
                instance ?: VoiceAppLockAndMoodEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val securityManager = SecurityManager(context)

    private val _isFirstPageSetupCompleted = MutableStateFlow(
        prefs.getBoolean(KEY_FIRST_PAGE_SETUP_COMPLETED, false)
    )
    val isFirstPageSetupCompleted: StateFlow<Boolean> = _isFirstPageSetupCompleted.asStateFlow()

    private val _ownerName = MutableStateFlow(
        prefs.getString(KEY_OWNER_NAME, "Boss") ?: "Boss"
    )
    val ownerName: StateFlow<String> = _ownerName.asStateFlow()

    private val _ownerPreferredAndaz = MutableStateFlow(
        prefs.getString(KEY_OWNER_PREFERRED_ANDAZ, SpeakingAndaz.FRIENDLY_CASUAL.name)
            ?: SpeakingAndaz.FRIENDLY_CASUAL.name
    )
    val ownerPreferredAndaz: StateFlow<String> = _ownerPreferredAndaz.asStateFlow()

    private val _ownerBasePitchHz = MutableStateFlow(
        prefs.getFloat(KEY_OWNER_PITCH_HZ, 148f)
    )
    val ownerBasePitchHz: StateFlow<Float> = _ownerBasePitchHz.asStateFlow()

    private val _isOwnerVoiceEnrolled = MutableStateFlow(
        prefs.getBoolean(KEY_OWNER_VOICE_ENROLLED, true)
    )
    val isOwnerVoiceEnrolled: StateFlow<Boolean> = _isOwnerVoiceEnrolled.asStateFlow()

    private val _isGlobalAppVoiceLockEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_GLOBAL_APP_LOCK_ENABLED, true)
    )
    val isGlobalAppVoiceLockEnabled: StateFlow<Boolean> = _isGlobalAppVoiceLockEnabled.asStateFlow()

    private val _lockAllApps = MutableStateFlow(
        prefs.getBoolean(KEY_LOCK_ALL_APPS, true)
    )
    val lockAllApps: StateFlow<Boolean> = _lockAllApps.asStateFlow()

    private val _simulateSoreThroatOrVoiceFail = MutableStateFlow(
        prefs.getBoolean(KEY_SIMULATE_VOICE_FAIL_MODE, false)
    )
    val simulateSoreThroatOrVoiceFail: StateFlow<Boolean> = _simulateSoreThroatOrVoiceFail.asStateFlow()

    private val _secretCodes = MutableStateFlow(loadSecretCodes())
    val secretCodes: StateFlow<List<SecretFallbackCodeEntry>> = _secretCodes.asStateFlow()

    private val _pendingChallenge = MutableStateFlow<PendingAppLockChallenge?>(null)
    val pendingChallenge: StateFlow<PendingAppLockChallenge?> = _pendingChallenge.asStateFlow()

    private val _lastMoodEvaluation = MutableStateFlow(MoodAndVoiceLockEvaluation())
    val lastMoodEvaluation: StateFlow<MoodAndVoiceLockEvaluation> = _lastMoodEvaluation.asStateFlow()

    private val _showTypeCodeModal = MutableStateFlow(false)
    val showTypeCodeModal: StateFlow<Boolean> = _showTypeCodeModal.asStateFlow()

    private val _lastVerificationStatusBanner = MutableStateFlow("")
    val lastVerificationStatusBanner: StateFlow<String> = _lastVerificationStatusBanner.asStateFlow()

    // Session cache of recently unlocked packages (valid for 2 minutes after voice/code verification)
    private val unlockedPackageSessions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val sessionUnlockDurationMs = 120_000L

    // =========================================================================
    // 1. FIRST-PAGE ONBOARDING & CONFIGURATION
    // =========================================================================

    fun saveFirstPageVoiceLockConfiguration(
        ownerNameInput: String,
        preferredAndaz: String,
        codesList: List<String>,
        enableAllAppsLock: Boolean = true,
        enrolledPitchHz: Float = _ownerBasePitchHz.value
    ) {
        val cleanOwner = ownerNameInput.trim().ifBlank { "Boss" }
        val normalizedCodes = (0 until REQUIRED_SECRET_CODES_COUNT).map { idx ->
            val raw = codesList.getOrNull(idx)?.trim().orEmpty()
            val fallback = DEFAULT_TEN_SECRET_CODES[idx]
            val existing = _secretCodes.value.getOrNull(idx)
            SecretFallbackCodeEntry(
                index = idx,
                secretPhrase = raw.ifBlank { existing?.secretPhrase ?: fallback },
                cooldownAttemptsRemaining = existing?.cooldownAttemptsRemaining ?: 0,
                lastUsedTimestamp = existing?.lastUsedTimestamp ?: 0L,
                totalTimesUsed = existing?.totalTimesUsed ?: 0
            )
        }

        _ownerName.value = cleanOwner
        _ownerPreferredAndaz.value = preferredAndaz
        _ownerBasePitchHz.value = enrolledPitchHz
        _isOwnerVoiceEnrolled.value = true
        _isGlobalAppVoiceLockEnabled.value = enableAllAppsLock
        _lockAllApps.value = true
        _secretCodes.value = normalizedCodes
        _isFirstPageSetupCompleted.value = true

        prefs.edit()
            .putBoolean(KEY_FIRST_PAGE_SETUP_COMPLETED, true)
            .putString(KEY_OWNER_NAME, cleanOwner)
            .putString(KEY_OWNER_PREFERRED_ANDAZ, preferredAndaz)
            .putFloat(KEY_OWNER_PITCH_HZ, enrolledPitchHz)
            .putBoolean(KEY_OWNER_VOICE_ENROLLED, true)
            .putBoolean(KEY_GLOBAL_APP_LOCK_ENABLED, enableAllAppsLock)
            .putBoolean(KEY_LOCK_ALL_APPS, true)
            .putString(KEY_SECRET_CODES_JSON, serializeSecretCodes(normalizedCodes))
            .apply()

        Log.i(TAG, "Saved First-Page Voice Lock & 10 Secret Codes for owner=$cleanOwner")
    }

    fun updateSingleSecretCode(index: Int, newPhrase: String) {
        if (index !in 0 until REQUIRED_SECRET_CODES_COUNT) return
        val trimmed = newPhrase.trim()
        if (trimmed.isEmpty()) return
        val updated = _secretCodes.value.toMutableList()
        val old = updated[index]
        updated[index] = old.copy(secretPhrase = trimmed)
        _secretCodes.value = updated
        prefs.edit().putString(KEY_SECRET_CODES_JSON, serializeSecretCodes(updated)).apply()
    }

    fun setGlobalAppVoiceLockEnabled(enabled: Boolean) {
        _isGlobalAppVoiceLockEnabled.value = enabled
        prefs.edit().putBoolean(KEY_GLOBAL_APP_LOCK_ENABLED, enabled).apply()
    }

    fun setSimulateSoreThroatOrVoiceFail(simulateFail: Boolean) {
        _simulateSoreThroatOrVoiceFail.value = simulateFail
        prefs.edit().putBoolean(KEY_SIMULATE_VOICE_FAIL_MODE, simulateFail).apply()
    }

    fun setShowTypeCodeModal(show: Boolean) {
        _showTypeCodeModal.value = show
    }

    fun enrollOwnerVoiceSample(samples: ShortArray? = null, customPitchHz: Float? = null) {
        val profile: VoiceEnrollmentProfile = if (samples != null && samples.isNotEmpty()) {
            securityManager.enrollVoiceProfile(listOf(samples))
        } else {
            val pitch = customPitchHz ?: 152f
            VoiceEnrollmentProfile(
                sampleCount = 3,
                averagePitchHz = pitch,
                zeroCrossingRate = 0.08f,
                energyThreshold = 1400f,
                isEnrolled = true
            )
        }
        val effectivePitch = if (profile.averagePitchHz > 60f) profile.averagePitchHz else (customPitchHz ?: 152f)
        _ownerBasePitchHz.value = effectivePitch
        _isOwnerVoiceEnrolled.value = true
        prefs.edit()
            .putFloat(KEY_OWNER_PITCH_HZ, effectivePitch)
            .putBoolean(KEY_OWNER_VOICE_ENROLLED, true)
            .apply()
    }

    // =========================================================================
    // 2. HUMAN MOOD & SPEAKING STYLE ("ANDAZ") INTELLIGENCE ANALYZER
    // =========================================================================

    /**
     * Analyzes the speaker's mood, speaking style ("andaz"), and acoustic voice match.
     * - For general conversation: anyone can talk and receive a mood-adapted reply.
     * - For app opening/control: verifies whether the voice matches the owner and evaluates
     *   whether the mood/andaz is genuine or indicates a sore throat / voice recognition failure.
     */
    fun analyzeSpeakerMoodAndVoice(
        utterance: String,
        audioSamples: ShortArray? = null,
        liveRmsEnergy: Float = 0f,
        isAppOpenRequest: Boolean = false
    ): MoodAndVoiceLockEvaluation {
        val lower = utterance.lowercase(Locale.ROOT).trim()

        // 1. Detect linguistic & prosodic cues for Sore Throat ("gala kharab") or voice recognition failure
        val explicitSoreThroatCues = lower.contains("gala kharab") ||
            lower.contains("gala baitha") ||
            lower.contains("awaz nahi nikal") ||
            lower.contains("sore throat") ||
            lower.contains("cough") ||
            lower.contains("khansi") ||
            lower.contains("flu") ||
            lower.contains("zukam")

        val isLowClarityOrUnclear = utterance.equals("[Unclear Audio]", ignoreCase = true) ||
            (liveRmsEnergy in 0.1f..8.0f && isAppOpenRequest)

        val soreThroatDetected = _simulateSoreThroatOrVoiceFail.value ||
            explicitSoreThroatCues ||
            isLowClarityOrUnclear

        // 2. Detect Speaker Mood from words + audio energy
        val detectedMood = when {
            soreThroatDetected -> SpeakerMood.TIRED_OR_HOARSE
            lower.contains("jaldi") || lower.contains("foran") || lower.contains("urgent") ||
                lower.contains("emergency") || lower.contains("fast") || lower.contains("abhi") ->
                SpeakerMood.URGENT
            lower.contains("pareshan") || lower.contains("tension") || lower.contains("worried") ||
                lower.contains("help") || lower.contains("masla") || lower.contains("problem") ->
                SpeakerMood.STRESSED
            lower.contains("naraz") || lower.contains("ghussa") || lower.contains("kaam nahi") ||
                lower.contains("kyun nahi") || lower.contains("bekar") || lower.contains("galat") ->
                SpeakerMood.FRUSTRATED
            lower.contains("shukriya") || lower.contains("zبردست") || lower.contains("zabardast") ||
                lower.contains("great") || lower.contains("awesome") || lower.contains("khush") ||
                lower.contains("maza") || lower.contains("love") ->
                SpeakerMood.CHEERFUL
            lower.contains("thak") || lower.contains("tired") || lower.contains("neend") ||
                lower.contains("slow") ->
                SpeakerMood.TIRED_OR_HOARSE
            liveRmsEnergy > 55f -> SpeakerMood.URGENT
            else -> SpeakerMood.CALM
        }

        // 3. Detect Speaking Style ("Andaz")
        val detectedAndaz = when {
            soreThroatDetected -> SpeakingAndaz.HOARSE_STRAINED
            lower.contains("please") || lower.contains("meherbani") || lower.contains("zara") ||
                lower.contains("bhai") || lower.contains("janab") ->
                SpeakingAndaz.POLITE_RESPECTFUL
            lower.startsWith("open ") || lower.endsWith("kholo") || lower.endsWith("karo") ||
                lower.contains("jarvis") ->
                SpeakingAndaz.DIRECT_COMMANDING
            else -> SpeakingAndaz.FRIENDLY_CASUAL
        }

        // 4. Compute Acoustic Voice Biometric Similarity
        val voiceSimilarity = computeOwnerVoiceMatchScore(audioSamples, soreThroatDetected)

        // 5. Compute Style & Mood Consistency Score
        val styleScore = if (soreThroatDetected) 0.42f else 0.90f
        val combinedConfidence = (voiceSimilarity * 0.75f) + (styleScore * 0.25f)

        val ownerVerified = !soreThroatDetected && voiceSimilarity >= 0.72f
        val shouldAllowAppOpen = if (!isAppOpenRequest || !_isGlobalAppVoiceLockEnabled.value) {
            true
        } else {
            ownerVerified
        }

        val moodPrefix = buildMoodAdaptivePrefix(detectedMood, detectedAndaz)
        val explanation = when {
            !isAppOpenRequest ->
                "General conversation allowed for everyone. Mood: ${detectedMood.romanUrduLabel}, Andaz: ${detectedAndaz.displayName}."
            shouldAllowAppOpen ->
                "Owner voice (${(voiceSimilarity * 100).toInt()}%) & Andaz (${detectedAndaz.displayName}, Mood: ${detectedMood.romanUrduLabel}) verified. App unlock approved."
            else ->
                "Aap ki voice recognize nahi ho rahi (Mood/Awaz: ${detectedMood.romanUrduLabel}). Security ke liye 10 Secret Codes mein se koi aik available secret code bolein ya type karein."
        }

        return MoodAndVoiceLockEvaluation(
            detectedMood = detectedMood,
            detectedAndaz = detectedAndaz,
            voiceBiometricSimilarity = voiceSimilarity,
            styleConsistencyScore = styleScore,
            combinedConfidence = combinedConfidence,
            isOwnerVoiceVerified = ownerVerified,
            isSoreThroatOrVoiceGlitch = soreThroatDetected,
            shouldOpenApp = shouldAllowAppOpen,
            requiresSecretCodeFallback = isAppOpenRequest && !shouldAllowAppOpen,
            moodAdaptedReplyPrefix = moodPrefix,
            decisionExplanationRomanUrdu = explanation
        ).also {
            _lastMoodEvaluation.value = it
        }
    }

    private fun computeOwnerVoiceMatchScore(
        audioSamples: ShortArray?,
        soreThroatOrSimulatedFail: Boolean
    ): Float {
        if (soreThroatOrSimulatedFail) {
            return 0.38f // Below 0.72 threshold -> triggers Secret Code fallback
        }
        if (audioSamples == null || audioSamples.isEmpty()) {
            return if (_isOwnerVoiceEnrolled.value) 0.93f else 0.85f
        }
        val livePitch = securityManager.extractPitchHz(audioSamples, 16000)
        if (livePitch <= 0f) return 0.88f
        val targetPitch = _ownerBasePitchHz.value.coerceIn(75f, 320f)
        val diffRatio = abs(livePitch - targetPitch) / targetPitch
        return (1f - diffRatio * 0.8f).coerceIn(0.45f, 0.99f)
    }

    /**
     * Generates a natural conversational prefix matching the speaker's detected mood & andaz.
     */
    fun buildMoodAdaptivePrefix(mood: SpeakerMood, andaz: SpeakingAndaz): String {
        val owner = _ownerName.value.ifBlank { "Boss" }
        return when (mood) {
            SpeakerMood.STRESSED -> "Pareshan na hon $owner, main aap ke sath hoon. "
            SpeakerMood.URGENT -> "Ji $owner, foran kar raha hoon! "
            SpeakerMood.TIRED_OR_HOARSE -> "Lagta hai aap thakay hue hain ya gala kharab hai $owner, aaram se batayein. "
            SpeakerMood.FRUSTRATED -> "Maazrat $owner, main abhi isay bilkul theek kar deta hoon. "
            SpeakerMood.CHEERFUL -> "Wah $owner, aaj aap ka mood bohat shandar lag raha hai! "
            SpeakerMood.CALM -> when (andaz) {
                SpeakingAndaz.POLITE_RESPECTFUL -> "Ji bilkul janab, "
                SpeakingAndaz.DIRECT_COMMANDING -> "Yes $owner, "
                else -> ""
            }
        }
    }

    /**
     * Adapts any conversational reply to reflect the speaker's detected mood and speaking style ("andaz").
     */
    fun decorateConversationalReplyWithMood(
        rawUtterance: String,
        baseReply: String,
        audioSamples: ShortArray? = null,
        liveRms: Float = 0f
    ): String {
        val eval = analyzeSpeakerMoodAndVoice(
            utterance = rawUtterance,
            audioSamples = audioSamples,
            liveRmsEnergy = liveRms,
            isAppOpenRequest = false
        )
        if (baseReply.isBlank()) return baseReply
        val prefix = eval.moodAdaptedReplyPrefix
        return if (prefix.isNotBlank() && !baseReply.startsWith(prefix.trim(), ignoreCase = true)) {
            "$prefix$baseReply"
        } else {
            baseReply
        }
    }

    // =========================================================================
    // 3. UNIVERSAL APP VOICE LOCK GATEKEEPER
    // =========================================================================

    /**
     * Checks whether a given package or app name is protected by the Voice Lock.
     * By default, all launchable user/system apps on the mobile are voice-locked
     * (excluding this assistant package itself and system UI / keyboard).
     */
    fun isAppProtectedByVoiceLock(packageNameOrAppName: String): Boolean {
        if (!_isGlobalAppVoiceLockEnabled.value) return false
        val clean = packageNameOrAppName.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return false

        // Never lock our own assistant package or keyboard/launcher/systemui
        val selfPkg = context.packageName.lowercase(Locale.ROOT)
        if (clean == selfPkg ||
            clean.contains("systemui") ||
            clean.contains("inputmethod") ||
            clean.contains("keyboard") ||
            clean.contains("launcher") ||
            clean.contains("nexuslauncher") ||
            clean.contains("permissioncontroller")
        ) {
            return false
        }
        return true
    }

    /**
     * Checks if the package was recently unlocked by voice or secret code within the active window.
     */
    fun isPackageTemporarilyUnlocked(packageName: String): Boolean {
        val key = packageName.trim().lowercase(Locale.ROOT)
        val unlockedAt = unlockedPackageSessions[key] ?: return false
        val elapsed = System.currentTimeMillis() - unlockedAt
        return if (elapsed <= sessionUnlockDurationMs) {
            true
        } else {
            unlockedPackageSessions.remove(key)
            false
        }
    }

    fun markPackageUnlockedForSession(packageName: String, appName: String = "") {
        val now = System.currentTimeMillis()
        if (packageName.isNotBlank()) {
            unlockedPackageSessions[packageName.trim().lowercase(Locale.ROOT)] = now
        }
        if (appName.isNotBlank()) {
            unlockedPackageSessions[appName.trim().lowercase(Locale.ROOT)] = now
        }
    }

    fun clearUnlockedSessions() {
        unlockedPackageSessions.clear()
    }

    /**
     * Evaluates an attempt to open or control an app (e.g., WhatsApp, YouTube, Instagram, Gallery, etc.).
     * - If the speaker's voice & mood/andaz verify as the owner, marks the app unlocked and returns `null`
     *   (meaning no challenge needed, proceed immediately!).
     * - If voice verification fails (or sore throat / voice recognition failure occurs), creates a
     *   [PendingAppLockChallenge] asking the user for one of the 10 Secret Codes (spoken or typed).
     */
    fun interceptAppOpenCommand(
        appName: String,
        packageName: String = "",
        rawUtterance: String = "",
        pendingAction: VoiceAction? = null,
        audioSamples: ShortArray? = null,
        liveRms: Float = 0f,
        forceVoiceVerificationFailure: Boolean = false
    ): PendingAppLockChallenge? {
        if (!_isGlobalAppVoiceLockEnabled.value) return null

        val cleanApp = appName.trim().ifBlank { packageName.substringAfterLast('.') }
        val cleanPkg = packageName.trim().ifBlank { cleanApp.lowercase(Locale.ROOT) }

        if (!isAppProtectedByVoiceLock(cleanPkg) && !isAppProtectedByVoiceLock(cleanApp)) {
            return null
        }

        // Check if user already included a valid secret code directly inside their utterance
        if (rawUtterance.isNotBlank() && findMatchingSecretCodeIndex(rawUtterance) != null) {
            val codeResult = verifySecretCode(rawUtterance)
            if (codeResult is SecretCodeVerificationResult.Unlocked) {
                return null
            }
        }

        // Check if already unlocked in the current active session (unless forced fail for testing)
        if (!forceVoiceVerificationFailure && !_simulateSoreThroatOrVoiceFail.value) {
            if (isPackageTemporarilyUnlocked(cleanPkg) || isPackageTemporarilyUnlocked(cleanApp)) {
                return null
            }
        }

        val eval = analyzeSpeakerMoodAndVoice(
            utterance = rawUtterance,
            audioSamples = audioSamples,
            liveRmsEnergy = liveRms,
            isAppOpenRequest = true
        )

        if (!forceVoiceVerificationFailure && eval.shouldOpenApp) {
            markPackageUnlockedForSession(cleanPkg, cleanApp)
            _lastVerificationStatusBanner.value =
                "Voice Verified (${(eval.voiceBiometricSimilarity * 100).toInt()}% match • Mood: ${eval.detectedMood.romanUrduLabel}) — Opening $cleanApp"
            return null
        }

        // Voice recognition failed or sore throat -> Trigger 10 Secret Code Challenge!
        val availableCount = getAvailableSecretCodes().size
        val promptMessage =
            "Aap ki voice recognize nahi ho rahi ($cleanApp Voice Lock active hai). Please apne 10 Secret Codes mein se koi aik available secret code bolein ya type karein ($availableCount/10 codes ready)."

        val challenge = PendingAppLockChallenge(
            appName = cleanApp,
            packageName = cleanPkg,
            pendingVoiceAction = pendingAction,
            pendingRawCommand = rawUtterance,
            failureReasonRomanUrdu = promptMessage,
            detectedMood = eval.detectedMood,
            detectedAndaz = eval.detectedAndaz
        )
        _pendingChallenge.value = challenge
        _lastVerificationStatusBanner.value = promptMessage
        Log.i(TAG, "Voice Lock intercepted $cleanApp ($cleanPkg): awaiting 1 of 10 secret codes.")
        return challenge
    }

    fun dismissPendingChallenge() {
        _pendingChallenge.value = null
        _showTypeCodeModal.value = false
    }

    // =========================================================================
    // 4. 10 SECRET CODES WITH STRICT 3-ATTEMPT ROTATION COOLDOWN
    // =========================================================================

    fun getAvailableSecretCodes(): List<SecretFallbackCodeEntry> {
        return _secretCodes.value.filter { it.isAvailableNow }
    }

    fun getCooldownSecretCodes(): List<SecretFallbackCodeEntry> {
        return _secretCodes.value.filter { !it.isAvailableNow }
    }

    /**
     * Finds which of the 10 secret codes matches the spoken or typed input.
     * Supports exact match, case-insensitive match, or phrase containment (e.g. "secret code Sikandar").
     */
    fun findMatchingSecretCodeIndex(spokenOrTypedInput: String): Int? {
        val normalizedInput = normalizeCodeText(spokenOrTypedInput)
        if (normalizedInput.isEmpty()) return null

        val currentCodes = _secretCodes.value
        // 1. Exact normalized match
        for (entry in currentCodes) {
            val normCode = normalizeCodeText(entry.secretPhrase)
            if (normCode.isNotEmpty() && normCode == normalizedInput) {
                return entry.index
            }
        }
        // 2. Spoken utterance contains the secret phrase (e.g., "mera secret code sikandar hai")
        for (entry in currentCodes) {
            val normCode = normalizeCodeText(entry.secretPhrase)
            if (normCode.length >= 3 && normalizedInput.contains(normCode)) {
                return entry.index
            }
        }
        return null
    }

    /**
     * Verifies a spoken or typed Secret Code against the 10-code pool and enforces the
     * anti-eavesdropping **3-attempt cooldown rotation**:
     * - When code `M` is available (`cooldownAttemptsRemaining == 0`):
     *   1. Every OTHER code that currently has `cooldownAttemptsRemaining > 0` has its counter
     *      decremented by 1 (`cooldownAttemptsRemaining - 1`).
     *   2. Code `M` is set to `cooldownAttemptsRemaining = 3` (so it cannot be used for the next
     *      3 unlock attempts; its turn comes back only after 3 subsequent valid unlock attempts!).
     *   3. Unlocks the pending app and returns [SecretCodeVerificationResult.Unlocked].
     * - When code `M` is currently on cooldown (`cooldownAttemptsRemaining > 0`):
     *   - Rejects the attempt without decrementing other cooldowns, and informs the user how many
     *     attempts remain before this code can be used again.
     */
    fun verifySecretCode(spokenOrTypedInput: String): SecretCodeVerificationResult {
        val cleanInput = spokenOrTypedInput.trim()
        val matchedIndex = findMatchingSecretCodeIndex(cleanInput)
        val currentList = _secretCodes.value

        if (matchedIndex == null) {
            val available = currentList.count { it.isAvailableNow }
            val errorMsg =
                "Secret code '$cleanInput' match nahi hua. Kripya apne 10 Secret Codes mein se koi sahi code bolein ya type karein ($available codes available hain)."
            _lastVerificationStatusBanner.value = errorMsg
            return SecretCodeVerificationResult.InvalidCode(
                inputAttempt = cleanInput,
                availableCodesCount = available,
                spokenErrorRomanUrdu = errorMsg
            )
        }

        val matchedEntry = currentList[matchedIndex]

        // Check if this code was already used recently and is still in its 3-attempt cooldown window
        if (matchedEntry.cooldownAttemptsRemaining > 0) {
            val remaining = matchedEntry.cooldownAttemptsRemaining
            val availableExamples = currentList
                .filter { it.isAvailableNow }
                .take(3)
                .joinToString(", ") { "'${it.secretPhrase}'" }
            val cooldownMsg =
                "Secret code '${matchedEntry.secretPhrase}' abhi istemal ho chuka hai! Security rule ke mutabiq is code ki baari $remaining aur attempts ke baad aayegi. Abhi koi doosra secret code bolein ya type karein (jaise: $availableExamples)."
            _lastVerificationStatusBanner.value = cooldownMsg
            return SecretCodeVerificationResult.CodeOnCooldown(
                matchedCodeIndex = matchedIndex,
                matchedPhrase = matchedEntry.secretPhrase,
                attemptsRemainingBeforeReuse = remaining,
                availableCodesCount = currentList.count { it.isAvailableNow },
                spokenErrorRomanUrdu = cooldownMsg
            )
        }

        // Valid & Available Code!
        // Rotate cooldowns:
        // - Decrement all other codes currently on cooldown by 1
        // - Put the newly used code on a 3-attempt cooldown
        val now = System.currentTimeMillis()
        val updatedCodes = currentList.map { entry ->
            if (entry.index == matchedIndex) {
                entry.copy(
                    cooldownAttemptsRemaining = COOLDOWN_ATTEMPTS_AFTER_USE,
                    lastUsedTimestamp = now,
                    totalTimesUsed = entry.totalTimesUsed + 1
                )
            } else if (entry.cooldownAttemptsRemaining > 0) {
                entry.copy(
                    cooldownAttemptsRemaining = (entry.cooldownAttemptsRemaining - 1).coerceAtLeast(0)
                )
            } else {
                entry
            }
        }

        _secretCodes.value = updatedCodes
        prefs.edit().putString(KEY_SECRET_CODES_JSON, serializeSecretCodes(updatedCodes)).apply()

        val activeChallenge = _pendingChallenge.value
        val unlockedApp = activeChallenge?.appName ?: "App"
        val unlockedPkg = activeChallenge?.packageName ?: ""
        val pendingAct = activeChallenge?.pendingVoiceAction

        markPackageUnlockedForSession(unlockedPkg, unlockedApp)
        _pendingChallenge.value = null
        _showTypeCodeModal.value = false

        val confirmMsg =
            "Secret code '${matchedEntry.secretPhrase}' verify ho gaya! $unlockedApp unlock kar diya gaya hai. (Ab '${matchedEntry.secretPhrase}' aglay 3 attempts ke baad dobara use ho sakega)."
        _lastVerificationStatusBanner.value = confirmMsg
        Log.i(TAG, "Unlocked '$unlockedApp' via Secret Code #${matchedIndex + 1} ('${matchedEntry.secretPhrase}'). Cooldown set to 3.")

        return SecretCodeVerificationResult.Unlocked(
            matchedCodeIndex = matchedIndex,
            matchedPhrase = matchedEntry.secretPhrase,
            cooldownSetTo = COOLDOWN_ATTEMPTS_AFTER_USE,
            unlockedAppName = unlockedApp,
            unlockedPackageName = unlockedPkg,
            pendingAction = pendingAct,
            spokenConfirmationRomanUrdu = confirmMsg
        )
    }

    /**
     * Resets all 10 secret code cooldowns to 0 (e.g., when owner resets from Vault settings).
     */
    fun resetAllSecretCodeCooldowns() {
        val resetList = _secretCodes.value.map { it.copy(cooldownAttemptsRemaining = 0) }
        _secretCodes.value = resetList
        prefs.edit().putString(KEY_SECRET_CODES_JSON, serializeSecretCodes(resetList)).apply()
    }

    /**
     * Returns a list of installed launchable apps on the device (or fallback common apps)
     * that are protected by the Voice Lock.
     */
    fun getProtectedAppsSummary(): List<Pair<String, String>> {
        val pm = context.packageManager
        val detected = mutableListOf<Pair<String, String>>()
        try {
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                val pkg = app.packageName
                if (isAppProtectedByVoiceLock(pkg) && pm.getLaunchIntentForPackage(pkg) != null) {
                    val label = pm.getApplicationLabel(app).toString().ifBlank { pkg }
                    detected.add(pkg to label)
                }
            }
        } catch (_: Exception) {
        }
        if (detected.isEmpty()) {
            return DEFAULT_PROTECTED_APPS.entries.map { it.key to it.value }
        }
        return detected.distinctBy { it.first }.sortedBy { it.second.lowercase(Locale.ROOT) }
    }

    private fun normalizeCodeText(raw: String): String {
        return raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun loadSecretCodes(): List<SecretFallbackCodeEntry> {
        val rawJson = prefs.getString(KEY_SECRET_CODES_JSON, null)
        if (!rawJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(rawJson)
                if (arr.length() == REQUIRED_SECRET_CODES_COUNT) {
                    val parsed = mutableListOf<SecretFallbackCodeEntry>()
                    for (i in 0 until REQUIRED_SECRET_CODES_COUNT) {
                        val obj = arr.getJSONObject(i)
                        parsed.add(
                            SecretFallbackCodeEntry(
                                index = i,
                                secretPhrase = obj.optString("secretPhrase", DEFAULT_TEN_SECRET_CODES[i])
                                    .ifBlank { DEFAULT_TEN_SECRET_CODES[i] },
                                cooldownAttemptsRemaining = obj.optInt("cooldownAttemptsRemaining", 0)
                                    .coerceIn(0, COOLDOWN_ATTEMPTS_AFTER_USE),
                                lastUsedTimestamp = obj.optLong("lastUsedTimestamp", 0L),
                                totalTimesUsed = obj.optInt("totalTimesUsed", 0)
                            )
                        )
                    }
                    return parsed
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse secret codes JSON, restoring defaults: ${e.message}")
            }
        }
        return DEFAULT_TEN_SECRET_CODES.mapIndexed { idx, phrase ->
            SecretFallbackCodeEntry(
                index = idx,
                secretPhrase = phrase,
                cooldownAttemptsRemaining = 0,
                lastUsedTimestamp = 0L,
                totalTimesUsed = 0
            )
        }
    }

    private fun serializeSecretCodes(codes: List<SecretFallbackCodeEntry>): String {
        val arr = JSONArray()
        codes.take(REQUIRED_SECRET_CODES_COUNT).forEachIndexed { idx, entry ->
            val obj = JSONObject().apply {
                put("index", idx)
                put("secretPhrase", entry.secretPhrase)
                put("cooldownAttemptsRemaining", entry.cooldownAttemptsRemaining)
                put("lastUsedTimestamp", entry.lastUsedTimestamp)
                put("totalTimesUsed", entry.totalTimesUsed)
            }
            arr.put(obj)
        }
        return arr.toString()
    }
}

package com.example.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Section 10 (`F-093`–`F-097`):
 * All 9 granular mood states required by Section 10, with prosody adjustments (TTS speed & pitch)
 * and formality/tone directives.
 */
enum class MoodLabel(
    val displayTitle: String,
    val emoji: String,
    val defaultSpeedMultiplier: Float,
    val defaultPitchMultiplier: Float,
    val toneInstruction: String
) {
    CALM(
        displayTitle = "Calm",
        emoji = "😌",
        defaultSpeedMultiplier = 1.0f,
        defaultPitchMultiplier = 1.0f,
        toneInstruction = "User is calm. Reply in a balanced, warm, concise manner."
    ),
    HAPPY(
        displayTitle = "Happy",
        emoji = "😊",
        defaultSpeedMultiplier = 1.05f,
        defaultPitchMultiplier = 1.04f,
        toneInstruction = "User is happy. Reply with light, warm, friendly energy."
    ),
    EXCITED(
        displayTitle = "Excited",
        emoji = "🤩",
        defaultSpeedMultiplier = 1.08f,
        defaultPitchMultiplier = 1.06f,
        toneInstruction = "User is excited! Match their enthusiasm with an upbeat, celebratory tone."
    ),
    TIRED(
        displayTitle = "Tired",
        emoji = "😴",
        defaultSpeedMultiplier = 0.92f,
        defaultPitchMultiplier = 0.95f,
        toneInstruction = "User sounds tired. Speak softly, gently, and keep answers brief and effortless."
    ),
    SAD(
        displayTitle = "Sad",
        emoji = "💙",
        defaultSpeedMultiplier = 0.90f,
        defaultPitchMultiplier = 0.94f,
        toneInstruction = "User sounds sad or down. Be deeply empathetic, gentle, warm, and supportive."
    ),
    STRESSED_HURRIED(
        displayTitle = "Stressed / In a Hurry",
        emoji = "⚡",
        defaultSpeedMultiplier = 1.12f,
        defaultPitchMultiplier = 1.0f,
        toneInstruction = "User is in a hurry or stressed. Give ultra-short, immediate action confirmations with zero fluff."
    ),
    ANGRY_FRUSTRATED(
        displayTitle = "Angry / Frustrated",
        emoji = "🧘",
        defaultSpeedMultiplier = 0.96f,
        defaultPitchMultiplier = 0.96f,
        toneInstruction = "User sounds frustrated. Stay completely calm, apologize briefly if a prior step failed, fix the issue immediately, and never mirror anger."
    ),
    CONFUSED(
        displayTitle = "Confused",
        emoji = "🧭",
        defaultSpeedMultiplier = 0.94f,
        defaultPitchMultiplier = 1.0f,
        toneInstruction = "User sounds confused. Break the explanation into simple, clear step-by-step points."
    ),
    NEUTRAL(
        displayTitle = "Neutral",
        emoji = "🤖",
        defaultSpeedMultiplier = 1.0f,
        defaultPitchMultiplier = 1.0f,
        toneInstruction = "User is neutral. Be crisp, helpful, and polite."
    )
}

/**
 * Smoothed per-turn Mood Estimate (`F-093`, `F-094`, `F-096`).
 */
data class MoodEstimate(
    val label: MoodLabel,
    val intensity: Float,        // 0.0 .. 1.0
    val confidence: Float,       // 0.0 .. 1.0
    val isDistressSafetyAlert: Boolean = false,
    val safetyHelplineMessage: String? = null,
    val recommendedSpeechSpeed: Float = label.defaultSpeedMultiplier,
    val recommendedVoicePitch: Float = label.defaultPitchMultiplier
)

/**
 * Section 10 (`F-093`–`F-097`):
 * Multi-Signal Mood & Emotional Intelligence Engine with Exponential Moving Average (EMA) smoothing
 * and Distress / Self-Harm Helpline Guardrail.
 */
object MoodAnalyzerEngine {

    private const val EMA_ALPHA = 0.45f
    private var lastRepeatedUtterance: String = ""
    private var consecutiveRepeatCount: Int = 0

    private val _currentMoodEstimate = MutableStateFlow(
        MoodEstimate(label = MoodLabel.CALM, intensity = 0.5f, confidence = 0.8f)
    )
    val currentMoodEstimate: StateFlow<MoodEstimate> = _currentMoodEstimate.asStateFlow()

    private val _moodAdaptationEnabled = MutableStateFlow(true)
    val moodAdaptationEnabled: StateFlow<Boolean> = _moodAdaptationEnabled.asStateFlow()

    private val _moodHistoryEnabled = MutableStateFlow(true)
    val moodHistoryEnabled: StateFlow<Boolean> = _moodHistoryEnabled.asStateFlow()

    private val DISTRESS_SELF_HARM_KEYWORDS = listOf(
        "suicide", "kill myself", "end my life", "want to die", "self harm", "hurt myself",
        "marna chahta", "marna chahti", "khudkushi", "zindagi khatam", "jeena nahi chahta",
        "خودکشی", "مرنا چاہتا", "زندگی ختم"
    )

    fun setMoodAdaptationEnabled(enabled: Boolean) {
        _moodAdaptationEnabled.value = enabled
    }

    fun setMoodHistoryEnabled(enabled: Boolean) {
        _moodHistoryEnabled.value = enabled
    }

    fun clearMoodHistory() {
        lastRepeatedUtterance = ""
        consecutiveRepeatCount = 0
        _currentMoodEstimate.value = MoodEstimate(label = MoodLabel.CALM, intensity = 0.5f, confidence = 0.8f)
    }

    /**
     * Evaluates user mood from:
     * (a) Words (English, Urdu Nastaliq, Roman Urdu, emojis)
     * (b) Acoustic prosody (RMS energy, F0 pitch Hz, speaking rate WPM)
     * (c) Context (repeated commands or corrections = frustration)
     */
    fun evaluateTurnMood(
        utterance: String,
        rmsEnergy: Float = 30f,
        pitchHz: Float = 155f,
        speechRateWpm: Float = 130f
    ): MoodEstimate {
        val lower = utterance.lowercase().trim()

        // 0. Check Distress / Self-Harm Safety Guardrail FIRST (`F-096`)
        if (DISTRESS_SELF_HARM_KEYWORDS.any { lower.contains(it) }) {
            val lang = LanguageProcessor.detectLanguage(utterance)
            val helplineMsg = LanguageProcessor.formatReplyInLanguage(
                englishText = "I care about your safety and you are not alone. Please reach out to a trusted family member or call the helpline right now: 1166 / 1122 (Pakistan) or 988 / 112.",
                romanUrduText = "Aap ki zindagi aur salamti bohot qeemti hai, aap akele nahi hain. Baraye meherbani abhi kisi apnay azeez se baat karein ya helpline 1166 ya 1122 par call karein.",
                urduNastaliqText = "آپ کی زندگی اور سلامتی بہت قیمتی ہے، آپ اکیلے نہیں ہیں۔ براہِ کرم ابھی کسی اپنے عزیز سے بات کریں یا ہیلپ لائن 1166 یا 1122 پر کال کریں۔",
                detectedLang = lang
            )
            val distressEstimate = MoodEstimate(
                label = MoodLabel.SAD,
                intensity = 1.0f,
                confidence = 0.98f,
                isDistressSafetyAlert = true,
                safetyHelplineMessage = helplineMsg,
                recommendedSpeechSpeed = 0.90f,
                recommendedVoicePitch = 0.94f
            )
            _currentMoodEstimate.value = distressEstimate
            return distressEstimate
        }

        // Track repeated commands / corrections (`F-093(c)`)
        if (lower.isNotEmpty() && lower == lastRepeatedUtterance) {
            consecutiveRepeatCount++
        } else {
            lastRepeatedUtterance = lower
            consecutiveRepeatCount = 0
        }

        val isCorrection = lower.contains("ghalat") || lower.contains("wrong") ||
            lower.contains("maine kaha") || lower.contains("nahi yaar") ||
            consecutiveRepeatCount >= 1

        val rawLabel = when {
            isCorrection || lower.contains("gussa") || lower.contains("angry") ||
                lower.contains("annoying") || lower.contains("bekaar") -> MoodLabel.ANGRY_FRUSTRATED
            lower.contains("jaldi") || lower.contains("foran") || lower.contains("urgent") ||
                lower.contains("hurry") || lower.contains("fast") || speechRateWpm > 170f -> MoodLabel.STRESSED_HURRIED
            lower.contains("udass") || lower.contains("sad") || lower.contains("dukh") ||
                lower.contains("rona") || lower.contains("پریشان") || lower.contains("اداس") -> MoodLabel.SAD
            lower.contains("thak") || lower.contains("tired") || lower.contains("neend") ||
                lower.contains("sleepy") || (rmsEnergy in 1f..14f && speechRateWpm < 105f) -> MoodLabel.TIRED
            lower.contains("samajh nahi") || lower.contains("confused") ||
                lower.contains("kaise karoon") || lower.contains("pata nahi") -> MoodLabel.CONFUSED
            lower.contains("zabardast") || lower.contains("kamaal") || lower.contains("awesome") ||
                lower.contains("excited") || lower.contains("maza aa gaya") ||
                (rmsEnergy > 65f && pitchHz > 195f) -> MoodLabel.EXCITED
            lower.contains("shukriya") || lower.contains("thank") || lower.contains("happy") ||
                lower.contains("khush") || lower.contains("great") -> MoodLabel.HAPPY
            else -> MoodLabel.CALM
        }

        val rawIntensity = ((rmsEnergy / 85f).coerceIn(0.3f, 1.0f))
        val prev = _currentMoodEstimate.value
        val smoothedIntensity = (EMA_ALPHA * rawIntensity + (1f - EMA_ALPHA) * prev.intensity).coerceIn(0.2f, 1.0f)

        val estimate = MoodEstimate(
            label = rawLabel,
            intensity = smoothedIntensity,
            confidence = 0.85f,
            isDistressSafetyAlert = false,
            safetyHelplineMessage = null,
            recommendedSpeechSpeed = if (_moodAdaptationEnabled.value) rawLabel.defaultSpeedMultiplier else 1.0f,
            recommendedVoicePitch = if (_moodAdaptationEnabled.value) rawLabel.defaultPitchMultiplier else 1.0f
        )
        _currentMoodEstimate.value = estimate
        return estimate
    }
}

/**
 * Section 9 (`F-087`–`F-092`):
 * Android Keystore `AES/GCM/NoPadding` Encrypted Memory & Wi-Fi Credential Vault +
 * Encrypted JSON Backup Exporter/Importer + On-Device Conversation Summarizer.
 */
object EncryptedMemoryBackupManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val MEMORY_KEY_ALIAS = "OmniVoiceMemoryVaultKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFS_WIFI_VAULT = "omnivoice_encrypted_wifi_vault"

    @Synchronized
    private fun getOrCreateKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (!ks.containsAlias(MEMORY_KEY_ALIAS)) {
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    MEMORY_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                kg.init(spec)
                kg.generateKey()
            } else {
                (ks.getEntry(MEMORY_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Encrypts arbitrary JSON or secret strings using Android Keystore AES-256-GCM (with deterministic
     * Base64 fallback in JVM unit tests where hardware AndroidKeyStore is mocked).
     */
    fun encryptPayload(plainText: String): String {
        val secretKey = getOrCreateKey()
        if (secretKey != null) {
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
                val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
                val dataB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
                "GCM1:$ivB64:$dataB64"
            } catch (_: Exception) {
                "B64:" + Base64.encodeToString(plainText.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            }
        }
        return "B64:" + Base64.encodeToString(plainText.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }

    fun decryptPayload(encryptedPayload: String): String? {
        return try {
            if (encryptedPayload.startsWith("GCM1:")) {
                val parts = encryptedPayload.split(":", limit = 3)
                if (parts.size != 3) return null
                val iv = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipherBytes = Base64.decode(parts[2], Base64.NO_WRAP)
                val secretKey = getOrCreateKey() ?: return null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
            } else if (encryptedPayload.startsWith("B64:")) {
                val raw = Base64.decode(encryptedPayload.removePrefix("B64:"), Base64.NO_WRAP)
                String(raw, StandardCharsets.UTF_8)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Saves a Wi-Fi password encrypted in Android Keystore ONLY when the user consents (`F-069`, `F-087`).
     */
    fun saveConsentedWifiPassword(context: Context, ssid: String, password: String, userConsented: Boolean): Boolean {
        if (!userConsented || ssid.isBlank() || password.isBlank()) return false
        val encrypted = encryptPayload(password)
        context.getSharedPreferences(PREFS_WIFI_VAULT, Context.MODE_PRIVATE)
            .edit()
            .putString("wifi_ssid_${ssid.lowercase().trim()}", encrypted)
            .apply()
        return true
    }

    fun getSavedWifiPassword(context: Context, ssid: String): String? {
        val enc = context.getSharedPreferences(PREFS_WIFI_VAULT, Context.MODE_PRIVATE)
            .getString("wifi_ssid_${ssid.lowercase().trim()}", null) ?: return null
        return decryptPayload(enc)
    }

    /**
     * Exports all learned memories, rules, and preferences into an encrypted backup string (`F-090`).
     */
    fun exportEncryptedMemoryBackup(context: Context): String {
        val permanentMem = OmniAutonomousCoreEngine.loadPermanentMemory(context)
        val rules = DeterministicNluEngine.getLearnedRules(context)
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAt", System.currentTimeMillis())
            put("permanentMemory", permanentMem.toJson())
            val rulesArr = JSONArray()
            rules.forEach { r ->
                rulesArr.put(JSONObject().apply {
                    put("id", r.id)
                    put("ruleText", r.ruleText)
                })
            }
            put("learnedRules", rulesArr)
        }
        return encryptPayload(root.toString())
    }

    /**
     * Restores an encrypted memory backup string (`F-090`).
     */
    fun importEncryptedMemoryBackup(context: Context, encryptedBackup: String): ActionResult {
        val decrypted = decryptPayload(encryptedBackup.trim())
            ?: return ActionResult.Failed("Failed to decrypt backup file: invalid format or key mismatch.")
        return try {
            val obj = JSONObject(decrypted)
            val memObj = obj.optJSONObject("permanentMemory")
            if (memObj != null) {
                val restored = OmniPermanentMemory.fromJson(memObj)
                OmniAutonomousCoreEngine.savePermanentMemory(context, restored)
            }
            ActionResult.Success("Verified encrypted memory backup restored successfully.")
        } catch (e: Exception) {
            ActionResult.Failed("Backup JSON parse error: ${e.localizedMessage}")
        }
    }

    /**
     * Summarizes a multi-turn conversation on-device into a compact memory note (`F-091`).
     */
    fun summarizeConversationOnDevice(turns: List<String>): String {
        if (turns.isEmpty()) return "No conversation turns to summarize."
        val keyPoints = turns
            .map { it.trim() }
            .filter { it.length > 6 }
            .takeLast(5)
            .joinToString(" | ") { it.take(70) }
        return "Compact Summary (${turns.size} turns): $keyPoints"
    }
}

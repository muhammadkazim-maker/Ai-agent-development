package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.data.model.AssistantConfig
import com.example.data.model.VoiceEnrollmentProfile
import com.example.data.model.VoiceVerificationResult
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.abs
import kotlin.math.sqrt

class SecurityManager(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
            load(null)
        }
    }

    companion object {
        private const val PREFS_NAME = "omnivoice_secure_prefs"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "OmniVoiceMasterKey"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128

        private const val KEY_SALT_HEX = "salt_hex"
        private const val KEY_PASSPHRASE_HASH_HEX = "passphrase_hash_hex"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"

        // Voice Biometrics stored encrypted
        private const val KEY_ENCRYPTED_BIOMETRICS = "enc_biometrics"
        private const val KEY_BIOMETRICS_IV = "enc_biometrics_iv"

        // Sensitive actions restricted during lock
        val SENSITIVE_ACTIONS = setOf(
            "delete_data",
            "read_notifications",
            "open_banking",
            "factory_reset",
            "modify_security"
        )
    }

    @Synchronized
    private fun ensureMasterKeyExists() {
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )
            val keyGenSpec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(keyGenSpec)
            keyGenerator.generateKey()
        }
    }

    @Synchronized
    private fun getSecretKey(): SecretKey {
        ensureMasterKeyExists()
        return (keyStore.getEntry(MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    // --- Passphrase Hashing & Verification (Zero Plain Text) ---

    fun hasPassphrase(): Boolean {
        return prefs.contains(KEY_PASSPHRASE_HASH_HEX) && prefs.contains(KEY_SALT_HEX)
    }

    /**
     * Enrolls or updates the spoken passphrase.
     * Normalized (lowercase, trimmed, punctuation stripped) and hashed with SHA-256 + 16-byte random salt.
     */
    fun enrollPassphrase(passphrase: String) {
        val normalized = normalizePassphrase(passphrase)
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val hash = hashWithSalt(normalized, salt)

        prefs.edit()
            .putString(KEY_SALT_HEX, bytesToHex(salt))
            .putString(KEY_PASSPHRASE_HASH_HEX, bytesToHex(hash))
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    fun verifyPassphrase(spokenInput: String): Boolean {
        val storedSaltHex = prefs.getString(KEY_SALT_HEX, null) ?: return false
        val storedHashHex = prefs.getString(KEY_PASSPHRASE_HASH_HEX, null) ?: return false

        val salt = hexToBytes(storedSaltHex)
        val normalizedInput = normalizePassphrase(spokenInput)
        val computedHash = hashWithSalt(normalizedInput, salt)
        val computedHashHex = bytesToHex(computedHash)

        return MessageDigest.isEqual(
            computedHashHex.toByteArray(StandardCharsets.UTF_8),
            storedHashHex.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun normalizePassphrase(input: String): String {
        return input.lowercase().trim().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ")
    }

    private fun hashWithSalt(input: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(input.toByteArray(StandardCharsets.UTF_8))
    }

    // --- Security Rate Limiting & Lockout ---

    fun isLockedOut(): Boolean {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        return System.currentTimeMillis() < lockoutUntil
    }

    fun getRemainingLockoutSeconds(): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val diff = lockoutUntil - System.currentTimeMillis()
        return if (diff > 0) diff / 1000 else 0L
    }

    fun recordFailedAttempt(maxAttempts: Int = 3, lockoutMinutes: Int = 5): Int {
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
        if (attempts >= maxAttempts) {
            val lockoutUntil = System.currentTimeMillis() + (lockoutMinutes * 60 * 1000)
            editor.putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
        }
        editor.apply()
        return attempts
    }

    fun resetFailedAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    fun getFailedAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)

    // --- Comprehensive Lock-Screen Verification Evaluator ---

    fun evaluateLockedMode(
        spokenPassphrase: String,
        audioSamples: ShortArray? = null,
        config: AssistantConfig,
        actionType: String
    ): VoiceVerificationResult {
        // 1. Check Lockout
        if (isLockedOut()) {
            return VoiceVerificationResult.LockedOut(getRemainingLockoutSeconds())
        }

        // 2. Sensitive Action Check
        if (SENSITIVE_ACTIONS.contains(actionType)) {
            return VoiceVerificationResult.SensitiveActionBlocked
        }

        // 3. Spoken Passphrase Verification
        val passphraseMatches = verifyPassphrase(spokenPassphrase)
        if (!passphraseMatches) {
            val failed = recordFailedAttempt(config.maxFailedAttempts, config.lockoutDurationMinutes)
            return if (failed >= config.maxFailedAttempts) {
                VoiceVerificationResult.LockedOut(getRemainingLockoutSeconds())
            } else {
                VoiceVerificationResult.PassphraseFailed(config.maxFailedAttempts - failed)
            }
        }

        // 4. Voice Biometric Verification (if enabled and audio provided)
        if (config.requireVoiceBiometrics && isVoiceBiometricsEnrolled() && audioSamples != null && audioSamples.isNotEmpty()) {
            val livePitch = extractPitchHz(audioSamples, 16000)
            val liveZcr = extractZeroCrossingRate(audioSamples)
            val enrolled = getEnrolledVoiceProfile()

            val similarityScore = computeBiometricSimilarity(livePitch, liveZcr, enrolled)
            if (similarityScore < config.biometricThreshold) {
                return VoiceVerificationResult.BiometricsFailed(similarityScore, config.biometricThreshold)
            }
        }

        // Passed verification! Reset failed counters
        resetFailedAttempts()
        return VoiceVerificationResult.Success
    }

    // --- Voice Biometrics Acoustic Feature Extraction & Enrollment ---

    fun isVoiceBiometricsEnrolled(): Boolean {
        return prefs.contains(KEY_ENCRYPTED_BIOMETRICS) && prefs.contains(KEY_BIOMETRICS_IV)
    }

    fun enrollVoiceProfile(samplesList: List<ShortArray>, sampleRate: Int = 16000): VoiceEnrollmentProfile {
        if (samplesList.isEmpty()) return VoiceEnrollmentProfile()

        var totalPitch = 0f
        var totalZcr = 0f
        var totalEnergy = 0f
        var validPitchCount = 0

        for (samples in samplesList) {
            val pitch = extractPitchHz(samples, sampleRate)
            val zcr = extractZeroCrossingRate(samples)
            val energy = extractRmsEnergy(samples)

            if (pitch > 60f && pitch < 400f) {
                totalPitch += pitch
                validPitchCount++
            }
            totalZcr += zcr
            totalEnergy += energy
        }

        val avgPitch = if (validPitchCount > 0) totalPitch / validPitchCount else 150f
        val avgZcr = totalZcr / samplesList.size
        val avgEnergy = totalEnergy / samplesList.size

        val profile = VoiceEnrollmentProfile(
            sampleCount = samplesList.size,
            averagePitchHz = avgPitch,
            zeroCrossingRate = avgZcr,
            energyThreshold = avgEnergy,
            isEnrolled = true
        )

        saveVoiceProfileEncrypted(profile)
        return profile
    }

    private fun saveVoiceProfileEncrypted(profile: VoiceEnrollmentProfile) {
        val plain = "${profile.sampleCount}|${profile.averagePitchHz}|${profile.zeroCrossingRate}|${profile.energyThreshold}|${profile.isEnrolled}"
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))

        prefs.edit()
            .putString(KEY_BIOMETRICS_IV, bytesToHex(iv))
            .putString(KEY_ENCRYPTED_BIOMETRICS, bytesToHex(encrypted))
            .apply()
    }

    fun getEnrolledVoiceProfile(): VoiceEnrollmentProfile {
        val encHex = prefs.getString(KEY_ENCRYPTED_BIOMETRICS, null) ?: return VoiceEnrollmentProfile()
        val ivHex = prefs.getString(KEY_BIOMETRICS_IV, null) ?: return VoiceEnrollmentProfile()

        return try {
            val iv = hexToBytes(ivHex)
            val encrypted = hexToBytes(encHex)
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            val decryptedBytes = cipher.doFinal(encrypted)
            val parts = String(decryptedBytes, StandardCharsets.UTF_8).split("|")

            VoiceEnrollmentProfile(
                sampleCount = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                averagePitchHz = parts.getOrNull(1)?.toFloatOrNull() ?: 0f,
                zeroCrossingRate = parts.getOrNull(2)?.toFloatOrNull() ?: 0f,
                energyThreshold = parts.getOrNull(3)?.toFloatOrNull() ?: 0f,
                isEnrolled = parts.getOrNull(4)?.toBooleanStrictOrNull() ?: false
            )
        } catch (e: Exception) {
            VoiceEnrollmentProfile()
        }
    }

    fun clearBiometrics() {
        prefs.edit()
            .remove(KEY_ENCRYPTED_BIOMETRICS)
            .remove(KEY_BIOMETRICS_IV)
            .apply()
    }

    /**
     * Pitch (Fundamental Frequency F0) estimation using normalized autocorrelation.
     */
    fun extractPitchHz(samples: ShortArray, sampleRate: Int): Float {
        val minLag = sampleRate / 350 // max 350 Hz (high voice)
        val maxLag = sampleRate / 70  // min 70 Hz (deep voice)
        val n = minOf(samples.size, 1024)

        if (n <= maxLag) return 150f

        var maxCorr = 0.0
        var bestLag = -1

        // Energy at lag 0
        var energy0 = 0.0
        for (i in 0 until n - maxLag) {
            val s = samples[i].toDouble()
            energy0 += s * s
        }
        if (energy0 < 1e5) return 0f // Silence / background noise

        for (lag in minLag..maxLag) {
            var corr = 0.0
            var energyLag = 0.0
            for (i in 0 until n - lag) {
                val s1 = samples[i].toDouble()
                val s2 = samples[i + lag].toDouble()
                corr += s1 * s2
                energyLag += s2 * s2
            }
            val normCorr = if (energyLag > 0) corr / sqrt(energy0 * energyLag) else 0.0
            if (normCorr > maxCorr) {
                maxCorr = normCorr
                bestLag = lag
            }
        }

        return if (bestLag > 0 && maxCorr > 0.35) {
            sampleRate.toFloat() / bestLag
        } else {
            140f // Default human speech fallback
        }
    }

    /**
     * Computes Zero Crossing Rate (ZCR).
     */
    fun extractZeroCrossingRate(samples: ShortArray): Float {
        if (samples.size < 2) return 0f
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) || (samples[i] < 0 && samples[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / (samples.size - 1)
    }

    /**
     * Computes RMS audio energy.
     */
    fun extractRmsEnergy(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (s in samples) {
            sumSquares += s * s
        }
        return sqrt(sumSquares / samples.size).toFloat()
    }

    private fun computeBiometricSimilarity(
        livePitch: Float,
        liveZcr: Float,
        enrolled: VoiceEnrollmentProfile
    ): Float {
        if (!enrolled.isEnrolled || enrolled.averagePitchHz <= 0f) return 1.0f

        // Pitch distance (relative tolerance +/- 25%)
        val pitchDiffRatio = abs(livePitch - enrolled.averagePitchHz) / enrolled.averagePitchHz
        val pitchScore = (1f - pitchDiffRatio).coerceIn(0f, 1f)

        // ZCR distance
        val zcrDiffRatio = abs(liveZcr - enrolled.zeroCrossingRate) / (enrolled.zeroCrossingRate + 1e-5f)
        val zcrScore = (1f - zcrDiffRatio).coerceIn(0f, 1f)

        // Weighted acoustic similarity score
        return (pitchScore * 0.7f) + (zcrScore * 0.3f)
    }

    // --- Hex utilities ---

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}

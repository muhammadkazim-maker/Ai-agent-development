package com.example.engine

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class VaultRecord(
    val masterPin: String = "",
    val lockScreenPattern: String = "",
    val secretVoiceUnlockWord: String = "Jarvis protocol 786",
    val biometricEnrolled: Boolean = false,
    val appPasswords: Map<String, String> = emptyMap(),
    val permissionsCompleted: Boolean = false,
    val orbDragSensitivity: Float = 0.5f,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun sanitized(): VaultRecord = copy(
        masterPin = "",
        lockScreenPattern = "",
        appPasswords = emptyMap()
    )

    fun toJson(): JSONObject = JSONObject().apply {
        // Security Policy (Phase 01): Never serialize or export device PIN, lock pattern, or app passwords
        put("masterPin", "")
        put("lockScreenPattern", "")
        put("secretVoiceUnlockWord", secretVoiceUnlockWord)
        put("biometricEnrolled", biometricEnrolled)
        put("permissionsCompleted", permissionsCompleted)
        put("orbDragSensitivity", orbDragSensitivity.toDouble())
        put("updatedAt", updatedAt)
        put("appPasswords", JSONObject())
    }

    companion object {
        fun fromJson(json: JSONObject): VaultRecord {
            // Security Policy (Phase 01): Strip any legacy masterPin, lockScreenPattern, or appPasswords on load
            return VaultRecord(
                masterPin = "",
                lockScreenPattern = "",
                secretVoiceUnlockWord = json.optString("secretVoiceUnlockWord", "Jarvis protocol 786"),
                biometricEnrolled = json.optBoolean("biometricEnrolled", false),
                appPasswords = emptyMap(),
                permissionsCompleted = json.optBoolean("permissionsCompleted", false),
                orbDragSensitivity = json.optDouble("orbDragSensitivity", 0.5).toFloat().coerceIn(0.1f, 1.0f),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Biometric & Settings Vault Manager (Phase 01 Hardened):
 * - Device PIN, lock-screen pattern, and app password collection, storage, export, autofill, and replay are disabled.
 * - Legacy stored credentials (`vault_master_pin`, `masterPin`, `lockScreenPattern`, `appPasswords`) are automatically
 *   purged from both SharedPreferences and serialized encrypted vault copies on upgrade/init while preserving
 *   unrelated settings (`permissionsCompleted`, `orbDragSensitivity`, `secretVoiceUnlockWord`, `biometricEnrolled`).
 * - Arbitrary Python/ADB local shell bridge scripts and port probes are disabled.
 */
class BiometricVaultManager(private val context: Context) {

    companion object {
        private const val TAG = "BiometricVaultManager"
        private const val PREFS_NAME = "jarvis_biometric_vault_prefs"
        private const val SECURE_PREFS_NAME = "omnivoice_secure_prefs"
        private const val KEY_ENCRYPTED_VAULT = "encrypted_vault_data"
        private const val KEY_VAULT_IV = "encrypted_vault_iv"
        private const val KEY_PLAIN_PIN = "vault_master_pin"
        private const val KEY_LEGACY_STORED_PIN = "stored_unlock_pin"
        private const val KEY_PERMISSIONS_COMPLETED = "jarvis_permissions_completed_once"
        private const val KEY_ORB_DRAG_SENSITIVITY = "jarvis_orb_drag_sensitivity"
        private const val KEYSTORE_ALIAS = "JarvisAutonomousVaultKey_v2"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        const val MIN_SENSITIVITY = 0.10f
        const val MAX_SENSITIVITY = 1.00f
        const val DEFAULT_SENSITIVITY = 0.50f

        @Volatile
        private var instance: BiometricVaultManager? = null

        fun getInstance(context: Context): BiometricVaultManager {
            return synchronized(this) {
                val appCtx = context.applicationContext ?: context
                val existing = instance
                if (existing == null || existing.context !== appCtx) {
                    BiometricVaultManager(appCtx).also { instance = it }
                } else {
                    existing
                }
            }
        }

        // Disabled in Phase 01: No local shell/ADB bridge script is exposed
        const val PYTHON_ADB_DAEMON_SCRIPT = ""
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _vaultState = MutableStateFlow(VaultRecord())
    val vaultState: StateFlow<VaultRecord> = _vaultState.asStateFlow()

    private val _isPermissionsCompleted = MutableStateFlow(false)
    val isPermissionsCompleted: StateFlow<Boolean> = _isPermissionsCompleted.asStateFlow()

    private val _orbDragSensitivity = MutableStateFlow(DEFAULT_SENSITIVITY)
    val orbDragSensitivity: StateFlow<Float> = _orbDragSensitivity.asStateFlow()

    private val _isBridgeActive = MutableStateFlow(false)
    val isBridgeActive: StateFlow<Boolean> = _isBridgeActive.asStateFlow()

    init {
        purgeLegacyCredentialsAndLoadSettings()
    }

    /**
     * Purges any legacy stored PINs, patterns, or app passwords from SharedPreferences and
     * re-serializes the vault record without credentials while preserving unrelated settings.
     */
    fun purgeLegacyCredentialsAndLoadSettings() {
        val loaded = loadVaultFromEncryptedPrefs().sanitized()
        _vaultState.value = loaded
        _isPermissionsCompleted.value = loaded.permissionsCompleted
        _orbDragSensitivity.value = loaded.orbDragSensitivity

        // Remove legacy plain-text or direct preference credential keys across preferences
        prefs.edit()
            .remove(KEY_PLAIN_PIN)
            .remove(KEY_LEGACY_STORED_PIN)
            .remove("masterPin")
            .remove("lockScreenPattern")
            .remove("appPasswords")
            .commit()

        try {
            context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PLAIN_PIN)
                .remove(KEY_LEGACY_STORED_PIN)
                .remove("masterPin")
                .remove("lockScreenPattern")
                .remove("appPasswords")
                .commit()
        } catch (_: Exception) {
        }

        // Overwrite serialized vault copy so no legacy credential values remain in encrypted/fallback JSON
        saveVaultToEncryptedPrefs(loaded)
    }

    /**
     * Enrolls non-credential preferences only. Device PIN and lock-screen pattern collection/storage
     * are disabled for safety and always ignored.
     */
    @Suppress("UNUSED_PARAMETER")
    fun enrollVault(
        masterPin: String = "",
        lockScreenPattern: String = "",
        secretVoiceWord: String = "Jarvis protocol 786",
        biometricEnrolled: Boolean = true
    ) {
        val updated = _vaultState.value.copy(
            masterPin = "",
            lockScreenPattern = "",
            secretVoiceUnlockWord = secretVoiceWord.trim().ifBlank { "Jarvis protocol 786" },
            biometricEnrolled = biometricEnrolled,
            appPasswords = emptyMap(),
            updatedAt = System.currentTimeMillis()
        )
        saveVaultToEncryptedPrefs(updated)
        _vaultState.value = updated
        Log.i(TAG, "Security settings updated (PIN/pattern storage disabled by Phase 01 policy)")
    }

    @Suppress("UNUSED_PARAMETER")
    fun setAppPassword(appName: String, password: String) {
        // Disabled in Phase 01: Do not collect or store app passwords
        val sanitized = _vaultState.value.copy(
            masterPin = "",
            lockScreenPattern = "",
            appPasswords = emptyMap(),
            updatedAt = System.currentTimeMillis()
        )
        saveVaultToEncryptedPrefs(sanitized)
        _vaultState.value = sanitized
        Log.w(TAG, "setAppPassword ignored: credential collection and storage are disabled.")
    }

    @Suppress("UNUSED_PARAMETER")
    fun getAppPassword(appName: String): String? {
        return null
    }

    fun getMasterPin(): String {
        return ""
    }

    fun getSecretVoiceUnlockWord(): String {
        return _vaultState.value.secretVoiceUnlockWord
    }

    fun matchesVoiceUnlock(spokenText: String): Boolean {
        val clean = spokenText.lowercase().trim()
        val secret = _vaultState.value.secretVoiceUnlockWord.lowercase().trim()
        return secret.isNotBlank() && clean.contains(secret)
    }

    fun isPermissionsAlreadyCompleted(): Boolean {
        return prefs.getBoolean(KEY_PERMISSIONS_COMPLETED, false) || _vaultState.value.permissionsCompleted
    }

    fun markPermissionsCompleted() {
        prefs.edit().putBoolean(KEY_PERMISSIONS_COMPLETED, true).apply()
        _isPermissionsCompleted.value = true
        val updated = _vaultState.value.copy(
            masterPin = "",
            lockScreenPattern = "",
            appPasswords = emptyMap(),
            permissionsCompleted = true,
            updatedAt = System.currentTimeMillis()
        )
        saveVaultToEncryptedPrefs(updated)
        _vaultState.value = updated
    }

    fun getOrbDragSensitivity(): Float {
        return prefs.getFloat(KEY_ORB_DRAG_SENSITIVITY, _vaultState.value.orbDragSensitivity)
            .coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
    }

    fun setOrbDragSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        prefs.edit().putFloat(KEY_ORB_DRAG_SENSITIVITY, clamped).apply()
        _orbDragSensitivity.value = clamped
        val updated = _vaultState.value.copy(
            masterPin = "",
            lockScreenPattern = "",
            appPasswords = emptyMap(),
            orbDragSensitivity = clamped,
            updatedAt = System.currentTimeMillis()
        )
        saveVaultToEncryptedPrefs(updated)
        _vaultState.value = updated
        Log.i(TAG, "Floating Orb Drag Sensitivity updated to: $clamped")
    }

    fun calculateSlopThresholdDp(sensitivity: Float = _orbDragSensitivity.value): Float {
        val clamped = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        return (36f - (clamped * 30f)).coerceIn(6f, 34f)
    }

    fun calculateMovementMultiplier(sensitivity: Float = _orbDragSensitivity.value): Float {
        val clamped = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        return (0.82f + (clamped * 0.43f)).coerceIn(0.80f, 1.25f)
    }

    fun computeMagneticEdgeSnap(
        currentX: Float,
        currentY: Float,
        maxOffsetX: Float,
        maxOffsetY: Float,
        edgeMarginPx: Float = 16f,
        snapThresholdPx: Float = 96f,
        forceHorizontalEdgeOnRelease: Boolean = true
    ): OrbSnapResult {
        val tempState = DraggableOrbState(
            initialX = currentX,
            initialY = currentY,
            sensitivity = _orbDragSensitivity.value,
            edgeMarginPx = edgeMarginPx,
            snapThresholdPx = snapThresholdPx
        )
        return tempState.snapToEdgeIfNearBoundary(
            maxOffsetX = maxOffsetX,
            maxOffsetY = maxOffsetY,
            forceHorizontalDock = forceHorizontalEdgeOnRelease
        )
    }

    fun getPythonBridgeOneLiner(): String {
        // Arbitrary shell/bridge access disabled in Phase 01
        return ""
    }

    fun checkAdbBridgeStatus(): Boolean {
        _isBridgeActive.value = false
        return false
    }

    fun getVaultJson(): String {
        return _vaultState.value.sanitized().toJson().toString()
    }

    fun importVaultJson(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            val record = VaultRecord.fromJson(json).sanitized()
            saveVaultToEncryptedPrefs(record)
            _vaultState.value = record
            _orbDragSensitivity.value = record.orbDragSensitivity
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings JSON", e)
            false
        }
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) return existing.secretKey

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        } catch (e: Exception) {
            Log.w(TAG, "Hardware Keystore unavailable (unit test / fallback mode): ${e.message}")
            null
        }
    }

    private fun saveVaultToEncryptedPrefs(record: VaultRecord) {
        val safeRecord = record.sanitized()
        val rawJson = safeRecord.toJson().toString()
        val secretKey = getOrCreateSecretKey()

        val editor = prefs.edit()
            .remove(KEY_PLAIN_PIN)
            .remove(KEY_LEGACY_STORED_PIN)
            .remove("masterPin")
            .remove("lockScreenPattern")
            .remove("appPasswords")
            .putBoolean(KEY_PERMISSIONS_COMPLETED, safeRecord.permissionsCompleted)
            .putFloat(KEY_ORB_DRAG_SENSITIVITY, safeRecord.orbDragSensitivity)

        if (secretKey != null) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val encryptedBytes = cipher.doFinal(rawJson.toByteArray(StandardCharsets.UTF_8))
                val iv = cipher.iv
                editor
                    .putString(KEY_ENCRYPTED_VAULT, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                    .putString(KEY_VAULT_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .commit()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Encryption fallback to sanitized JSON: ${e.message}")
            }
        }

        editor
            .putString(KEY_ENCRYPTED_VAULT, rawJson)
            .remove(KEY_VAULT_IV)
            .commit()
    }

    private fun loadVaultFromEncryptedPrefs(): VaultRecord {
        val permDone = prefs.getBoolean(KEY_PERMISSIONS_COMPLETED, false)
        val savedSensitivity = prefs.getFloat(KEY_ORB_DRAG_SENSITIVITY, DEFAULT_SENSITIVITY)
            .coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        val encryptedStr = prefs.getString(KEY_ENCRYPTED_VAULT, null)
        val ivStr = prefs.getString(KEY_VAULT_IV, null)

        if (encryptedStr.isNullOrBlank()) {
            return VaultRecord(
                masterPin = "",
                lockScreenPattern = "",
                appPasswords = emptyMap(),
                permissionsCompleted = permDone,
                orbDragSensitivity = savedSensitivity
            )
        }

        val secretKey = getOrCreateSecretKey()
        if (secretKey != null && !ivStr.isNullOrBlank()) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val iv = Base64.decode(ivStr, Base64.NO_WRAP)
                val spec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                val decryptedBytes = cipher.doFinal(Base64.decode(encryptedStr, Base64.NO_WRAP))
                val jsonStr = String(decryptedBytes, StandardCharsets.UTF_8)
                val parsed = VaultRecord.fromJson(JSONObject(jsonStr))
                return parsed.copy(
                    masterPin = "",
                    lockScreenPattern = "",
                    appPasswords = emptyMap(),
                    permissionsCompleted = parsed.permissionsCompleted || permDone,
                    orbDragSensitivity = if (prefs.contains(KEY_ORB_DRAG_SENSITIVITY)) savedSensitivity else parsed.orbDragSensitivity
                )
            } catch (e: Exception) {
                Log.w(TAG, "Decryption fallback: ${e.message}")
            }
        }

        return try {
            val parsed = VaultRecord.fromJson(JSONObject(encryptedStr))
            parsed.copy(
                masterPin = "",
                lockScreenPattern = "",
                appPasswords = emptyMap(),
                permissionsCompleted = parsed.permissionsCompleted || permDone,
                orbDragSensitivity = if (prefs.contains(KEY_ORB_DRAG_SENSITIVITY)) savedSensitivity else parsed.orbDragSensitivity
            )
        } catch (_: Exception) {
            VaultRecord(
                masterPin = "",
                lockScreenPattern = "",
                appPasswords = emptyMap(),
                permissionsCompleted = permDone,
                orbDragSensitivity = savedSensitivity
            )
        }
    }
}

data class OrbSnapResult(
    val targetX: Float,
    val targetY: Float,
    val isSnapped: Boolean,
    val snappedEdge: String
)

class DraggableOrbState(
    initialX: Float = 40f,
    initialY: Float = 80f,
    sensitivity: Float = BiometricVaultManager.DEFAULT_SENSITIVITY,
    val edgeMarginPx: Float = 16f,
    val snapThresholdPx: Float = 96f
) {
    var offsetX: Float = initialX
    var offsetY: Float = initialY
    var sensitivity: Float = sensitivity.coerceIn(BiometricVaultManager.MIN_SENSITIVITY, BiometricVaultManager.MAX_SENSITIVITY)
        set(value) {
            field = value.coerceIn(BiometricVaultManager.MIN_SENSITIVITY, BiometricVaultManager.MAX_SENSITIVITY)
        }

    val dragVelocityMultiplier: Float
        get() = (0.82f + (sensitivity * 0.43f)).coerceIn(0.80f, 1.25f)

    val slopThresholdDp: Float
        get() = (36f - (sensitivity * 30f)).coerceIn(6f, 34f)

    fun applyDragDelta(
        deltaX: Float,
        deltaY: Float,
        maxOffsetX: Float,
        maxOffsetY: Float,
        applyLiveMagneticPull: Boolean = true
    ): OrbSnapResult {
        val safeMaxX = maxOffsetX.coerceAtLeast(0f)
        val safeMaxY = maxOffsetY.coerceAtLeast(0f)
        val rawX = (offsetX + deltaX * dragVelocityMultiplier).coerceIn(0f, safeMaxX)
        val rawY = (offsetY + deltaY * dragVelocityMultiplier).coerceIn(0f, safeMaxY)

        if (!applyLiveMagneticPull) {
            offsetX = rawX
            offsetY = rawY
            return OrbSnapResult(offsetX, offsetY, false, "NONE")
        }

        val leftTarget = edgeMarginPx.coerceAtMost(safeMaxX)
        val rightTarget = (safeMaxX - edgeMarginPx).coerceAtLeast(0f)

        var snappedEdge = "NONE"
        var finalX = rawX
        if (rawX <= snapThresholdPx) {
            finalX = leftTarget
            snappedEdge = "LEFT"
        } else if (safeMaxX - rawX <= snapThresholdPx) {
            finalX = rightTarget
            snappedEdge = "RIGHT"
        }

        offsetX = finalX
        offsetY = rawY
        return OrbSnapResult(offsetX, offsetY, snappedEdge != "NONE", snappedEdge)
    }

    fun snapToEdgeIfNearBoundary(
        maxOffsetX: Float,
        maxOffsetY: Float,
        forceHorizontalDock: Boolean = true
    ): OrbSnapResult {
        val safeMaxX = maxOffsetX.coerceAtLeast(0f)
        val safeMaxY = maxOffsetY.coerceAtLeast(0f)
        val leftTarget = edgeMarginPx.coerceAtMost(safeMaxX)
        val rightTarget = (safeMaxX - edgeMarginPx).coerceAtLeast(0f)
        val topTarget = edgeMarginPx.coerceAtMost(safeMaxY)
        val bottomTarget = (safeMaxY - edgeMarginPx).coerceAtLeast(0f)

        val distLeft = kotlin.math.abs(offsetX - leftTarget)
        val distRight = kotlin.math.abs(rightTarget - offsetX)
        val distTop = kotlin.math.abs(offsetY - topTarget)
        val distBottom = kotlin.math.abs(bottomTarget - offsetY)

        var targetX = offsetX.coerceIn(0f, safeMaxX)
        var targetY = offsetY.coerceIn(0f, safeMaxY)
        val edges = mutableListOf<String>()

        if (forceHorizontalDock || distLeft <= snapThresholdPx || distRight <= snapThresholdPx) {
            if (distLeft <= distRight) {
                targetX = leftTarget
                edges.add("LEFT")
            } else {
                targetX = rightTarget
                edges.add("RIGHT")
            }
        }

        if (distTop <= snapThresholdPx) {
            targetY = topTarget
            edges.add("TOP")
        } else if (distBottom <= snapThresholdPx) {
            targetY = bottomTarget
            edges.add("BOTTOM")
        }

        offsetX = targetX
        offsetY = targetY
        val edgeLabel = if (edges.isEmpty()) "NONE" else edges.joinToString("_")
        return OrbSnapResult(offsetX, offsetY, edges.isNotEmpty(), edgeLabel)
    }
}


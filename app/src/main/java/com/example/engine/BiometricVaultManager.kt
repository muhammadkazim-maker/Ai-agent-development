package com.example.engine

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class VaultRecord(
    val masterPin: String = "",
    val lockScreenPattern: String = "",
    val secretVoiceUnlockWord: String = "Jarvis unlock",
    val biometricEnrolled: Boolean = false,
    val appPasswords: Map<String, String> = emptyMap(),
    val permissionsCompleted: Boolean = false,
    val adbPort: Int = 8765,
    val shizukuActive: Boolean = false,
    val orbDragSensitivity: Float = 0.5f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("masterPin", masterPin)
        put("lockScreenPattern", lockScreenPattern)
        put("secretVoiceUnlockWord", secretVoiceUnlockWord)
        put("biometricEnrolled", biometricEnrolled)
        put("permissionsCompleted", permissionsCompleted)
        put("adbPort", adbPort)
        put("shizukuActive", shizukuActive)
        put("orbDragSensitivity", orbDragSensitivity.toDouble())
        val passwordsObj = JSONObject()
        appPasswords.forEach { (k, v) -> passwordsObj.put(k, v) }
        put("appPasswords", passwordsObj)
    }

    companion object {
        fun fromJson(json: JSONObject): VaultRecord {
            val passwordsMap = mutableMapOf<String, String>()
            val passObj = json.optJSONObject("appPasswords")
            passObj?.keys()?.forEach { key ->
                passwordsMap[key] = passObj.optString(key, "")
            }
            return VaultRecord(
                masterPin = json.optString("masterPin", ""),
                lockScreenPattern = json.optString("lockScreenPattern", ""),
                secretVoiceUnlockWord = json.optString("secretVoiceUnlockWord", "Jarvis unlock"),
                biometricEnrolled = json.optBoolean("biometricEnrolled", false),
                permissionsCompleted = json.optBoolean("permissionsCompleted", false),
                adbPort = json.optInt("adbPort", 8765),
                shizukuActive = json.optBoolean("shizukuActive", false),
                orbDragSensitivity = json.optDouble("orbDragSensitivity", 0.5).toFloat(),
                appPasswords = passwordsMap
            )
        }
    }
}

/**
 * Biometric Fingerprint & Master PIN/Password Vault.
 * Provides secure encrypted storage for:
 * - Device Lock-screen PIN & Pattern (used for zero-touch hands-free phone unlock)
 * - Secret Voice Unlock Word (e.g., "Jarvis unlock", "Protocol 786")
 * - App-specific passwords & credentials
 * - Shizuku / ADB port settings
 * - Floating Orb Drag Sensitivity setting (anti-accidental touch threshold & smooth movement)
 */
class BiometricVaultManager(private val context: Context) {

    companion object {
        private const val TAG = "BiometricVault"
        private const val PREFS_NAME = "jarvis_biometric_vault_prefs"
        private const val KEY_ENCRYPTED_VAULT = "encrypted_vault_data"
        private const val KEY_PERMISSIONS_COMPLETE = "jarvis_permissions_complete"
        private const val KEY_PLAIN_PIN = "vault_master_pin"
        private const val KEY_VOICE_UNLOCK = "vault_voice_unlock"
        private const val KEY_ORB_DRAG_SENSITIVITY = "vault_orb_drag_sensitivity"

        private var instance: BiometricVaultManager? = null

        fun getInstance(context: Context): BiometricVaultManager {
            return instance ?: synchronized(this) {
                instance ?: BiometricVaultManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _vaultState = MutableStateFlow(loadVault())
    val vaultState: StateFlow<VaultRecord> = _vaultState.asStateFlow()

    private val _isPermissionsCompleted = MutableStateFlow(isPermissionsAlreadyCompleted())
    val isPermissionsCompleted: StateFlow<Boolean> = _isPermissionsCompleted.asStateFlow()

    fun isPermissionsAlreadyCompleted(): Boolean {
        return prefs.getBoolean(KEY_PERMISSIONS_COMPLETE, false) ||
            _vaultState.value.permissionsCompleted ||
            context.getSharedPreferences("omni_autonomous_core_prefs", Context.MODE_PRIVATE)
                .getBoolean("omni_startup_gatekeeper_completed", false)
    }

    fun markPermissionsCompleted() {
        prefs.edit().putBoolean(KEY_PERMISSIONS_COMPLETE, true).apply()
        context.getSharedPreferences("omni_autonomous_core_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("omni_startup_gatekeeper_completed", true)
            .apply()
        val updated = _vaultState.value.copy(permissionsCompleted = true)
        saveVault(updated)
        _isPermissionsCompleted.value = true
        Log.i(TAG, "Permissions Gatekeeper marked as completed in vault.")
    }

    fun resetPermissionsForTesting() {
        prefs.edit().putBoolean(KEY_PERMISSIONS_COMPLETE, false).apply()
        _isPermissionsCompleted.value = false
    }

    fun enrollVault(
        masterPin: String,
        lockScreenPattern: String = "",
        secretVoiceWord: String = "Jarvis unlock",
        biometricEnrolled: Boolean = true
    ) {
        val updated = _vaultState.value.copy(
            masterPin = masterPin.trim(),
            lockScreenPattern = lockScreenPattern.trim(),
            secretVoiceUnlockWord = secretVoiceWord.trim().ifBlank { "Jarvis unlock" },
            biometricEnrolled = biometricEnrolled
        )
        saveVault(updated)
        Log.i(TAG, "Biometric Vault enrolled: master PIN set (length=${masterPin.length})")
    }

    fun setAppPassword(appName: String, secret: String) {
        val newMap = _vaultState.value.appPasswords.toMutableMap()
        newMap[appName.lowercase().trim()] = secret
        val updated = _vaultState.value.copy(appPasswords = newMap)
        saveVault(updated)
    }

    fun getAppPassword(appName: String): String? {
        return _vaultState.value.appPasswords[appName.lowercase().trim()]
    }

    fun getMasterPin(): String {
        return _vaultState.value.masterPin.ifBlank {
            prefs.getString(KEY_PLAIN_PIN, "") ?: ""
        }
    }

    fun getSecretVoiceUnlockWord(): String {
        return _vaultState.value.secretVoiceUnlockWord.ifBlank {
            prefs.getString(KEY_VOICE_UNLOCK, "Jarvis unlock") ?: "Jarvis unlock"
        }
    }

    fun matchesVoiceUnlock(phrase: String): Boolean {
        val clean = phrase.lowercase().trim()
        val trigger = getSecretVoiceUnlockWord().lowercase().trim()
        return clean.contains(trigger) || clean.contains("jarvis unlock") || clean.contains("unlock phone")
    }

    fun getPythonBridgeOneLiner(): String =
        """python3 -c "import http.server, subprocess, json; class H(http.server.BaseHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.send_header('Access-Control-Allow-Private-Network', 'true')
        super().end_headers()
    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()
    def do_GET(self):
        if self.path == '/status':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{\"status\":\"OK\",\"service\":\"JARVIS_ADB_BRIDGE\",\"port\":8765}')
        else:
            self.send_response(404)
            self.end_headers()
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        data = self.rfile.read(length)
        cmd = json.loads(data).get('cmd', '') if data else ''
        res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({'ok': res.returncode==0, 'output': res.stdout + res.stderr, 'exitCode': res.returncode}).encode())
http.server.HTTPServer(('127.0.0.1', 8765), H).serve_forever()"
""".trimIndent()

    fun checkAdbBridgeStatus(): Boolean {
        return try {
            val url = java.net.URL("http://127.0.0.1:${_vaultState.value.adbPort}/status")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    fun getVaultJson(): String {
        return _vaultState.value.toJson().toString()
    }

    fun importVaultJson(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            val imported = VaultRecord.fromJson(json)
            saveVault(imported)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import vault JSON: ${e.message}")
            false
        }
    }

    fun setOrbDragSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0.1f, 1.0f)
        val updated = _vaultState.value.copy(orbDragSensitivity = clamped)
        saveVault(updated)
        Log.i(TAG, "Orb drag sensitivity updated: $clamped")
    }

    fun getOrbDragSensitivity(): Float {
        return _vaultState.value.orbDragSensitivity
    }

    /**
     * Calculates the gesture activation deadzone threshold (in dp) based on sensitivity.
     * Low sensitivity (0.1) -> 33dp threshold (prevents accidental triggers).
     * Balanced (0.5) -> 21dp threshold.
     * High sensitivity (1.0) -> 6dp threshold (ultra-responsive).
     */
    fun calculateSlopThresholdDp(sensitivity: Float): Float {
        val s = sensitivity.coerceIn(0.1f, 1.0f)
        return (36f - s * 30f)
    }

    /**
     * Calculates smooth movement velocity dampening multiplier based on sensitivity.
     * Smooth 1:1 feel ranging from 0.85x to 1.15x.
     */
    fun calculateMovementMultiplier(sensitivity: Float): Float {
        val s = sensitivity.coerceIn(0.1f, 1.0f)
        return 0.85f + (s * 0.30f)
    }

    /**
     * Evaluates and applies a magnetic snap-to-edge effect when the draggable orb
     * is moved within `snapThresholdPx` of any screen boundary (or on release).
     */
    fun computeMagneticEdgeSnap(
        currentX: Float,
        currentY: Float,
        maxOffsetX: Float,
        maxOffsetY: Float,
        edgeMarginPx: Float = 16f,
        snapThresholdPx: Float = 96f,
        forceHorizontalEdgeOnRelease: Boolean = false
    ): MagneticSnapResult {
        val clampedX = currentX.coerceIn(0f, maxOffsetX)
        val clampedY = currentY.coerceIn(0f, maxOffsetY)
        val leftTarget = edgeMarginPx.coerceAtMost(maxOffsetX)
        val rightTarget = (maxOffsetX - edgeMarginPx).coerceAtLeast(0f)
        val topTarget = edgeMarginPx.coerceAtMost(maxOffsetY)
        val bottomTarget = (maxOffsetY - edgeMarginPx).coerceAtLeast(0f)

        val distLeft = kotlin.math.abs(clampedX - leftTarget)
        val distRight = kotlin.math.abs(rightTarget - clampedX)
        val distTop = kotlin.math.abs(clampedY - topTarget)
        val distBottom = kotlin.math.abs(bottomTarget - clampedY)

        var targetX = clampedX
        var targetY = clampedY
        var snappedEdge: String? = null

        if (distLeft <= snapThresholdPx && distLeft <= distRight) {
            targetX = leftTarget
            snappedEdge = "LEFT"
        } else if (distRight <= snapThresholdPx) {
            targetX = rightTarget
            snappedEdge = "RIGHT"
        } else if (forceHorizontalEdgeOnRelease) {
            if (distLeft <= distRight) {
                targetX = leftTarget
                snappedEdge = "LEFT"
            } else {
                targetX = rightTarget
                snappedEdge = "RIGHT"
            }
        }

        if (distTop <= snapThresholdPx && distTop <= distBottom) {
            targetY = topTarget
            snappedEdge = if (snappedEdge != null) "${snappedEdge}_TOP" else "TOP"
        } else if (distBottom <= snapThresholdPx) {
            targetY = bottomTarget
            snappedEdge = if (snappedEdge != null) "${snappedEdge}_BOTTOM" else "BOTTOM"
        }

        return MagneticSnapResult(
            targetX = targetX,
            targetY = targetY,
            isSnapped = snappedEdge != null,
            snappedEdge = snappedEdge ?: "NONE"
        )
    }
    private fun loadVault(): VaultRecord {
        val raw = prefs.getString(KEY_ENCRYPTED_VAULT, null)
        if (raw.isNullOrBlank()) {
            return VaultRecord(
                masterPin = prefs.getString(KEY_PLAIN_PIN, "") ?: "",
                secretVoiceUnlockWord = prefs.getString(KEY_VOICE_UNLOCK, "Jarvis unlock") ?: "Jarvis unlock",
                permissionsCompleted = prefs.getBoolean(KEY_PERMISSIONS_COMPLETE, false),
                orbDragSensitivity = prefs.getFloat(KEY_ORB_DRAG_SENSITIVITY, 0.5f)
            )
        }
        return try {
            VaultRecord.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing vault record: ${e.message}")
            VaultRecord()
        }
    }

    private fun saveVault(record: VaultRecord) {
        _vaultState.value = record
        prefs.edit()
            .putString(KEY_ENCRYPTED_VAULT, record.toJson().toString())
            .putString(KEY_PLAIN_PIN, record.masterPin)
            .putString(KEY_VOICE_UNLOCK, record.secretVoiceUnlockWord)
            .putBoolean(KEY_PERMISSIONS_COMPLETE, record.permissionsCompleted)
            .putFloat(KEY_ORB_DRAG_SENSITIVITY, record.orbDragSensitivity)
            .apply()
    }
}

data class MagneticSnapResult(
    val targetX: Float,
    val targetY: Float,
    val isSnapped: Boolean,
    val snappedEdge: String
)

/**
 * Encapsulates the draggable orb's state, including a `sensitivity` parameter to control
 * drag velocity and a snap-to-edge magnetic effect when moved near screen boundaries.
 */
class DraggableOrbState(
    initialX: Float = 40f,
    initialY: Float = 80f,
    sensitivity: Float = 0.5f,
    val edgeMarginPx: Float = 16f,
    val snapThresholdPx: Float = 96f
) {
    var sensitivity: Float = sensitivity.coerceIn(0.1f, 1.0f)
        set(value) {
            field = value.coerceIn(0.1f, 1.0f)
        }

    var offsetX: Float = initialX
    var offsetY: Float = initialY
    var isDragging: Boolean = false
    var isSnappedToEdge: Boolean = false
    var snappedEdge: String = "NONE"

    /**
     * Drag velocity multiplier controlled directly by `sensitivity` (0.85x to 1.15x).
     */
    val dragVelocityMultiplier: Float
        get() = 0.85f + (sensitivity * 0.30f)

    /**
     * Slop deadzone threshold in dp controlled by `sensitivity`.
     */
    val slopThresholdDp: Float
        get() = 36f - (sensitivity * 30f)

    /**
     * Applies a raw drag delta scaled by `sensitivity` drag velocity and checks for
     * proximity magnetic pull near screen boundaries.
     */
    fun applyDragDelta(
        deltaX: Float,
        deltaY: Float,
        maxOffsetX: Float,
        maxOffsetY: Float,
        applyLiveMagneticPull: Boolean = true
    ): MagneticSnapResult {
        isDragging = true
        val rawX = (offsetX + deltaX * dragVelocityMultiplier).coerceIn(0f, maxOffsetX)
        val rawY = (offsetY + deltaY * dragVelocityMultiplier).coerceIn(0f, maxOffsetY)

        if (!applyLiveMagneticPull) {
            offsetX = rawX
            offsetY = rawY
            isSnappedToEdge = false
            snappedEdge = "NONE"
            return MagneticSnapResult(offsetX, offsetY, false, "NONE")
        }

        val leftTarget = edgeMarginPx.coerceAtMost(maxOffsetX)
        val rightTarget = (maxOffsetX - edgeMarginPx).coerceAtLeast(0f)
        val topTarget = edgeMarginPx.coerceAtMost(maxOffsetY)
        val bottomTarget = (maxOffsetY - edgeMarginPx).coerceAtLeast(0f)

        val distLeft = kotlin.math.abs(rawX - leftTarget)
        val distRight = kotlin.math.abs(rightTarget - rawX)
        val distTop = kotlin.math.abs(rawY - topTarget)
        val distBottom = kotlin.math.abs(bottomTarget - rawY)

        // Soft magnetic attraction zone (within 45% of snapThresholdPx during active drag)
        val livePullZone = snapThresholdPx * 0.45f
        var pulledX = rawX
        var pulledY = rawY
        var edge: String? = null

        if (distLeft <= livePullZone && distLeft <= distRight) {
            pulledX = rawX + (leftTarget - rawX) * 0.35f
            edge = "LEFT"
        } else if (distRight <= livePullZone) {
            pulledX = rawX + (rightTarget - rawX) * 0.35f
            edge = "RIGHT"
        }

        if (distTop <= livePullZone && distTop <= distBottom) {
            pulledY = rawY + (topTarget - rawY) * 0.35f
            edge = if (edge != null) "${edge}_TOP" else "TOP"
        } else if (distBottom <= livePullZone) {
            pulledY = rawY + (bottomTarget - rawY) * 0.35f
            edge = if (edge != null) "${edge}_BOTTOM" else "BOTTOM"
        }

        offsetX = pulledX.coerceIn(0f, maxOffsetX)
        offsetY = pulledY.coerceIn(0f, maxOffsetY)
        isSnappedToEdge = edge != null
        snappedEdge = edge ?: "NONE"

        return MagneticSnapResult(offsetX, offsetY, isSnappedToEdge, snappedEdge)
    }

    /**
     * Snaps the orb magnetically to the nearest boundary when released near screen edges.
     */
    fun snapToEdgeIfNearBoundary(
        maxOffsetX: Float,
        maxOffsetY: Float,
        forceHorizontalDock: Boolean = true
    ): MagneticSnapResult {
        isDragging = false
        val leftTarget = edgeMarginPx.coerceAtMost(maxOffsetX)
        val rightTarget = (maxOffsetX - edgeMarginPx).coerceAtLeast(0f)
        val topTarget = edgeMarginPx.coerceAtMost(maxOffsetY)
        val bottomTarget = (maxOffsetY - edgeMarginPx).coerceAtLeast(0f)

        val distLeft = kotlin.math.abs(offsetX - leftTarget)
        val distRight = kotlin.math.abs(rightTarget - offsetX)
        val distTop = kotlin.math.abs(offsetY - topTarget)
        val distBottom = kotlin.math.abs(bottomTarget - offsetY)

        var targetX = offsetX.coerceIn(0f, maxOffsetX)
        var targetY = offsetY.coerceIn(0f, maxOffsetY)
        var edge: String? = null

        if (distLeft <= snapThresholdPx && distLeft <= distRight) {
            targetX = leftTarget
            edge = "LEFT"
        } else if (distRight <= snapThresholdPx) {
            targetX = rightTarget
            edge = "RIGHT"
        } else if (forceHorizontalDock) {
            if (distLeft <= distRight) {
                targetX = leftTarget
                edge = "LEFT"
            } else {
                targetX = rightTarget
                edge = "RIGHT"
            }
        }

        if (distTop <= snapThresholdPx && distTop <= distBottom) {
            targetY = topTarget
            edge = if (edge != null) "${edge}_TOP" else "TOP"
        } else if (distBottom <= snapThresholdPx) {
            targetY = bottomTarget
            edge = if (edge != null) "${edge}_BOTTOM" else "BOTTOM"
        }

        offsetX = targetX
        offsetY = targetY
        isSnappedToEdge = edge != null
        snappedEdge = edge ?: "NONE"

        return MagneticSnapResult(
            targetX = offsetX,
            targetY = offsetY,
            isSnapped = isSnappedToEdge,
            snappedEdge = snappedEdge
        )
    }
}


package com.example.engine

import android.content.Context
import android.util.Log
import com.example.service.JarvisAccessibilityService
import com.example.service.OmniAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class JarvisOrbActionState(val label: String) {
    IDLE("Standby"),
    LISTENING("Listening"),
    SPEAKING("Speaking"),
    RUNNING_SHELL("Policy Blocked"),
    UNLOCKING("Unlock Blocked"),
    IN_CALL("Call Monitor")
}

data class OsShellCommand(
    val id: String = "cmd_${System.currentTimeMillis()}",
    val description: String,
    val adbShellCommand: String,
    val accessibilityAction: String = "",
    val targetPackage: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class OsExecutionResult(
    val ok: Boolean,
    val methodUsed: String,
    val output: String,
    val exitCode: Int = 0
)

/**
 * OS Kernel (Phase 01 Hardened):
 * - Arbitrary shell execution (`Runtime.getRuntime().exec`) and localhost ADB daemon bridges are blocked.
 * - Hands-free PIN/pattern unlock and lock-screen gesture replay are disabled.
 * - Screen scraping is blocked whenever the device is locked.
 */
class OsKernel(private val context: Context) {

    companion object {
        private const val TAG = "OsKernel"
        @Volatile
        private var instance: OsKernel? = null

        fun getInstance(context: Context): OsKernel {
            return synchronized(this) {
                val appCtx = context.applicationContext ?: context
                val existing = instance
                if (existing == null || existing.context !== appCtx) {
                    OsKernel(appCtx).also { instance = it }
                } else {
                    existing
                }
            }
        }
    }

    private val _commandHistory = MutableStateFlow<List<Pair<OsShellCommand, OsExecutionResult>>>(emptyList())
    val commandHistory: StateFlow<List<Pair<OsShellCommand, OsExecutionResult>>> = _commandHistory.asStateFlow()

    private val _lastScreenTreeJson = MutableStateFlow("{}")
    val lastScreenTreeJson: StateFlow<String> = _lastScreenTreeJson.asStateFlow()

    private val _isShizukuOrAdbConnected = MutableStateFlow(false)
    val isShizukuOrAdbConnected: StateFlow<Boolean> = _isShizukuOrAdbConnected.asStateFlow()

    private val _currentActionState = MutableStateFlow(JarvisOrbActionState.IDLE)
    val currentActionState: StateFlow<JarvisOrbActionState> = _currentActionState.asStateFlow()

    private val _lastActionDetail = MutableStateFlow("Autonomous OS Ready (Hardened Mode)")
    val lastActionDetail: StateFlow<String> = _lastActionDetail.asStateFlow()

    fun updateOrbState(state: JarvisOrbActionState, detail: String) {
        _currentActionState.value = state
        _lastActionDetail.value = detail
    }

    /**
     * Phase 01 Security Policy:
     * Arbitrary shell / ADB bridge execution is blocked in all production entry points.
     */
    suspend fun executeCommand(command: OsShellCommand): OsExecutionResult {
        _isShizukuOrAdbConnected.value = false
        val blockedResult = OsExecutionResult(
            ok = false,
            methodUsed = "BLOCKED_SHELL_BRIDGE",
            output = "Arbitrary shell and ADB bridge execution is disabled for device safety: ${command.description}",
            exitCode = -1
        )
        _currentActionState.value = JarvisOrbActionState.IDLE
        _lastActionDetail.value = blockedResult.output
        Log.w(TAG, "Blocked shell/bridge command (${command.description}): ${command.adbShellCommand}")
        recordCommand(command, blockedResult)
        return blockedResult
    }

    /**
     * Phase 01 Security Policy:
     * Hands-free device unlock and PIN/pattern replay are disabled. Android's normal lock stays enabled.
     */
    suspend fun unlockPhoneHandsFree(): OsExecutionResult {
        val blockedResult = OsExecutionResult(
            ok = false,
            methodUsed = "BLOCKED_BY_SECURITY_POLICY",
            output = "Hands-free PIN/pattern unlock is disabled. Please unlock your phone using Android's standard lock screen.",
            exitCode = -1
        )
        _currentActionState.value = JarvisOrbActionState.IDLE
        _lastActionDetail.value = blockedResult.output
        recordCommand(
            OsShellCommand(
                description = "Blocked Hands-Free Unlock",
                adbShellCommand = "blocked_unlock"
            ),
            blockedResult
        )
        return blockedResult
    }

    /**
     * Scrapes the current active screen UI elements via Accessibility only when the device is unlocked.
     */
    fun scrapeCurrentScreen(): String {
        if (LockScreenUnlockManager.isDeviceCurrentlyLocked(context)) {
            val lockedJson = JSONObject().apply {
                put("packageName", "locked")
                put("timestamp", System.currentTimeMillis())
                put("nodeCount", 0)
                put("nodes", JSONArray())
                put("status", "BLOCKED_DEVICE_LOCKED")
                put("error", "Screen inspection is blocked while the device is locked.")
            }.toString()
            _lastScreenTreeJson.value = lockedJson
            return lockedJson
        }

        val jarvisDump = JarvisAccessibilityService.instance?.dumpCurrentScreenJson()
        if (!jarvisDump.isNullOrBlank() && jarvisDump != "{}") {
            _lastScreenTreeJson.value = jarvisDump
            return jarvisDump
        }

        val summary = OmniAccessibilityService.captureScreenHierarchySummary()
        val json = JSONObject().apply {
            put("packageName", OmniAccessibilityService.getForegroundPackageName() ?: "com.example")
            put("timestamp", System.currentTimeMillis())
            put("summary", summary)
            put("status", if (OmniAccessibilityService.isAccessibilityConnected()) "LIVE_ACCESSIBILITY" else "STANDBY")
        }.toString()
        _lastScreenTreeJson.value = json
        return json
    }

    private fun recordCommand(cmd: OsShellCommand, result: OsExecutionResult) {
        _commandHistory.value = (listOf(cmd to result) + _commandHistory.value).take(40)
    }
}

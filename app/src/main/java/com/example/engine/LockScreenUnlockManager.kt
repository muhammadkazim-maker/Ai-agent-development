package com.example.engine

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.util.Log

sealed class UnlockResult {
    object AlreadyUnlocked : UnlockResult()
    data class UnlockedViaStrategy(val strategyName: String) : UnlockResult()
    object FallbackToDismissKeyguard : UnlockResult()
    data class Failure(val reason: String) : UnlockResult()
}

/**
 * Lock Screen & Phase 01 Security Gate Manager:
 * - Preserves Android's standard lock screen (`KeyguardManager`).
 * - Disables root `su` unlock, swipe-up bypass gestures, and PIN/pattern pad replay.
 * - Blocks private, outbound, or mutating actions whenever the device is locked.
 * - Temporarily gates unverified outbound or destructive tools until their verified phases pass.
 */
class LockScreenUnlockManager(private val context: Context) {

    private val keyguardManager: KeyguardManager? by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }

    var currentActivity: Activity? = null

    companion object {
        private const val TAG = "LockScreenUnlockMgr"

        /**
         * Test hook for deterministic unit/Robolectric verification of locked vs unlocked behavior.
         * Null in production so actual Android KeyguardManager state is used.
         */
        @Volatile
        var testForceDeviceLocked: Boolean? = null

        /**
         * Test/Phase hook for gating unverified outbound or destructive tools in Phase 01.
         */
        @Volatile
        var enforcePhase01OutboundAndDestructiveGate: Boolean = true

        fun isDeviceCurrentlyLocked(context: Context): Boolean {
            testForceDeviceLocked?.let { return it }
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            return km?.isDeviceLocked == true || km?.isKeyguardLocked == true
        }

        private val SAFE_PUBLIC_ACTIONS_WHEN_LOCKED = setOf(
            "general_chat",
            "conversation",
            "time",
            "date",
            "battery_status",
            "get_mobile_hardware_telemetry"
        )

        /**
         * Returns true if an action/tool is private, outbound, UI-mutating, or sensitive and must be
         * blocked whenever the device is locked.
         */
        fun isBlockedWhenDeviceLocked(actionOrToolName: String): Boolean {
            val clean = actionOrToolName.lowercase().trim()
            if (clean.isEmpty()) return true
            return clean !in SAFE_PUBLIC_ACTIONS_WHEN_LOCKED
        }

        /**
         * Unverified destructive or unattended-outbound tools gated in Phase 01 until their
         * dedicated verification phases pass.
         */
        private val PHASE_01_GATED_TOOLS = setOf(
            "whatsapp_send_voice_note",
            "send_whatsapp_voice_note",
            "whatsapp_delete_message",
            "delete_whatsapp_message",
            "answer_incoming_call",
            "answer_call",
            "execute_adb_shell",
            "execute_shell_command",
            "unlock_phone_hands_free",
            "unlock_phone",
            "factory_reset",
            "delete_data",
            "wipe_data"
        )

        fun isPhase01GatedTool(actionOrToolName: String): Boolean {
            if (!enforcePhase01OutboundAndDestructiveGate) return false
            val clean = actionOrToolName.lowercase().trim()
            return clean in PHASE_01_GATED_TOOLS
        }
    }

    fun isDeviceLocked(): Boolean {
        return isDeviceCurrentlyLocked(context)
    }

    fun isKeyguardSecure(): Boolean {
        testForceDeviceLocked?.let { if (it) return true }
        val km = keyguardManager ?: return false
        return km.isKeyguardSecure || km.isDeviceSecure
    }

    /**
     * Phase 01 Security Policy:
     * Never accepts, autofills, or replays any PIN/pattern, and never executes root or accessibility
     * unlock gestures. Android's normal lock screen remains enabled.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun attemptUnlock(storedPin: String? = null): UnlockResult {
        if (!isDeviceLocked()) {
            return UnlockResult.AlreadyUnlocked
        }
        Log.i(TAG, "attemptUnlock blocked: Automated PIN/gesture unlock is disabled. Standard Android lock remains active.")
        return UnlockResult.Failure(
            "Automated device unlock and PIN replay are disabled. Please unlock your phone using Android's standard lock screen."
        )
    }

    /**
     * Invokes Android's standard system lock screen dismissal prompt only when explicitly requested by
     * user interaction with a foreground Activity.
     */
    fun requestStandardSystemDismissKeyguard(activity: Activity, onDismissed: (() -> Unit)? = null): Boolean {
        val km = keyguardManager ?: return false
        if (!isDeviceLocked()) {
            onDismissed?.invoke()
            return true
        }
        km.requestDismissKeyguard(
            activity,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    super.onDismissSucceeded()
                    onDismissed?.invoke()
                }
            }
        )
        return true
    }
}

package com.example.service

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.util.Log
import com.example.MainActivity
import com.example.engine.AssistantAudioStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * System-level VoiceInteractionService allowing OmniVoice ("Jarvis") to act as the
 * device's Default Digital Assistant (`ROLE_ASSISTANT`).
 *
 * When set as the default assistant, Android grants system assistant privileges for
 * hardware assist button / hotword invocation and background wake activation where supported.
 */
class OmniVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "OmniVoiceService"

        private val _isServiceReady = MutableStateFlow(false)
        val isServiceReady: StateFlow<Boolean> = _isServiceReady.asStateFlow()

        fun setSimulatedAssistantReadyForTest(ready: Boolean) {
            _isServiceReady.value = ready
        }

        /**
         * Builds a supported Android system intent to set OmniVoice as the Default Digital Assistant
         * (`RoleManager.ROLE_ASSISTANT` on API 29+, or `Settings.ACTION_VOICE_INPUT_SETTINGS` fallback).
         */
        fun buildRequestAssistantRoleIntent(context: Context): Intent {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val roleManager = context.getSystemService(RoleManager::class.java)
                    if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                        return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "RoleManager intent fallback to ACTION_VOICE_INPUT_SETTINGS: ${e.message}")
                }
            }
            return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun onReady() {
        super.onReady()
        _isServiceReady.value = true
        Log.d(TAG, "OmniVoiceInteractionService is ready and set as active assistant")
        // Respect the user's armed/stopped/muted choice even when bound as default assistant
        val coord = AssistantAudioStateMachine.shared
        if (coord.isUserArmedPersisted(applicationContext) &&
            !coord.isStoppedByUserPersisted(applicationContext) &&
            !coord.isDeviceMutedPersisted(applicationContext)
        ) {
            WakeWordForegroundService.start(applicationContext, userInitiatedArm = false)
        }
    }

    override fun onShutdown() {
        _isServiceReady.value = false
        super.onShutdown()
        Log.d(TAG, "OmniVoiceInteractionService shutdown")
    }

    /**
     * Invoked when the user triggers the system assistant affordance (e.g., long-press power/home or corner swipe).
     */
    fun triggerSystemAssistantSession() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("EXTRA_WAKE_WORD_TRIGGERED", true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to launch MainActivity from VoiceInteractionService: ${e.message}")
        }
    }
}

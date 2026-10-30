package com.example.service

import android.content.Intent
import android.service.voice.VoiceInteractionService
import android.util.Log

class OmniVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "OmniVoiceInteraction"
        private var isReady = false

        fun isAssistantActive(): Boolean = isReady
    }

    override fun onReady() {
        super.onReady()
        isReady = true
        Log.d(TAG, "OmniVoiceInteractionService is ready and set as active assistant")
    }

    override fun onShutdown() {
        super.onShutdown()
        isReady = false
        Log.d(TAG, "OmniVoiceInteractionService shutdown")
    }
}

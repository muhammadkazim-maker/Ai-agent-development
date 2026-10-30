package com.example.service

import android.app.VoiceInteractor
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

class OmniVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return OmniVoiceInteractionSession(this)
    }
}

class OmniVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "OmniVoiceSession"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Voice interaction session created")
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "Voice interaction session shown with flags: $showFlags")

        // Trigger the lock screen overlay or main activity assistant UI
        val launchIntent = Intent(context, com.example.ui.lockscreen.LockScreenOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(launchIntent)
        hide()
    }

    override fun onRequestConfirmation(request: ConfirmationRequest?) {
        super.onRequestConfirmation(request)
    }

    override fun onRequestCompleteVoice(request: CompleteVoiceRequest?) {
        super.onRequestCompleteVoice(request)
    }
}

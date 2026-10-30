package com.example.service

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

class OmniVoiceRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "OmniRecognitionService"
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.d(TAG, "onStartListening called")
    }

    override fun onCancel(listener: Callback?) {
        Log.d(TAG, "onCancel called")
    }

    override fun onStopListening(listener: Callback?) {
        Log.d(TAG, "onStopListening called")
    }
}

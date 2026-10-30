package com.example.engine

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class IntruderAlert(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String = "3 Failed Spoken Passphrase Attempts",
    val photoPath: String? = null
)

class IntruderCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "IntruderCaptureManager"
        private val _alerts = MutableStateFlow<List<IntruderAlert>>(emptyList())
        val alerts: StateFlow<List<IntruderAlert>> = _alerts.asStateFlow()
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    fun triggerIntruderAlert(reason: String = "3 Failed Lockscreen Passphrase Attempts") {
        Log.w(TAG, "Intruder security threshold breached! Triggering security capture: $reason")

        playWarningSiren()

        val alertDir = File(context.filesDir, "intruder_records").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val recordFile = File(alertDir, "ALERT_$timeStamp.log")

        try {
            recordFile.writeText("SECURITY ALERT: $reason\nTimestamp: ${Date()}\nDevice Locked: true\n")
        } catch (e: Exception) {
            Log.e(TAG, "Could not write security alert file", e)
        }

        val alert = IntruderAlert(
            timestamp = System.currentTimeMillis(),
            reason = reason,
            photoPath = recordFile.absolutePath
        )

        _alerts.value = listOf(alert) + _alerts.value
    }

    private fun playWarningSiren() {
        try {
            val alertToneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, alertToneUri)
            ringtone?.play()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 500), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(longArrayOf(0, 300, 200, 300, 200, 500), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not play warning alert siren", e)
        }
    }
}

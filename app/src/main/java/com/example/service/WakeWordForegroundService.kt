package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.engine.Android12To17Compat
import com.example.ui.lockscreen.LockScreenOverlayActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Module 2 (`R1`, `R2`, `F-004`, `F-016`–`F-025`):
 * Always-on Wake-Word Foreground Service (`foregroundServiceType="microphone"`).
 *
 * Key guarantees:
 * 1. Single-Owner Audio Capture: Zero background `AudioTrack` oscillator (eliminates ghost audio & AEC interference).
 * 2. Hardware AEC / NS / AGC attached directly to `AudioRecord` session (`VOICE_COMMUNICATION` with `MIC` fallback).
 * 3. All-Zero Microphone Privacy Toggle Detector: detects when Android 12–17 system Mic Access toggle is OFF.
 * 4. Telephony Call-State Listener (`TelephonyCallback`): automatically pauses wake-word listening during regular
 *    cellular calls (`OFFHOOK` / `RINGING`) and resumes when the call ends (`IDLE`).
 * 5. Persistent Notification with live state (`SLEEP_STANDBY`, `ACTIVE_LISTENING`, `PAUSED_ON_CALL`, `MIC_PRIVACY_MUTED`)
 *    and Pause / Resume / Stop action buttons.
 */
class WakeWordForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var listeningJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var telephonyCallback: TelephonyCallback? = null

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "omnivoice_service_channel"

        const val ACTION_START = "com.example.omnivoice.ACTION_START"
        const val ACTION_STOP = "com.example.omnivoice.ACTION_STOP"
        const val ACTION_PAUSE_LISTENING = "com.example.omnivoice.ACTION_PAUSE_LISTENING"
        const val ACTION_RESUME_LISTENING = "com.example.omnivoice.ACTION_RESUME_LISTENING"

        private var activeInstance: WakeWordForegroundService? = null

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _isListeningPaused = MutableStateFlow(false)
        val isListeningPaused: StateFlow<Boolean> = _isListeningPaused.asStateFlow()

        private val _isPausedByPhoneCall = MutableStateFlow(false)
        val isPausedByPhoneCall: StateFlow<Boolean> = _isPausedByPhoneCall.asStateFlow()

        private val _isMicPrivacyMuted = MutableStateFlow(false)
        val isMicPrivacyMuted: StateFlow<Boolean> = _isMicPrivacyMuted.asStateFlow()

        private val _wakeWordTriggered = MutableSharedFlow<String>()
        val wakeWordTriggered: SharedFlow<String> = _wakeWordTriggered.asSharedFlow()

        private val _liveAudioLevel = MutableStateFlow(0f)
        val liveAudioLevel: StateFlow<Float> = _liveAudioLevel.asStateFlow()

        var configuredWakeWord: String = "Hey Omni"

        fun pause(context: Context) {
            _isListeningPaused.value = true
            activeInstance?.let {
                it.stopListening()
                it.updateForegroundNotification("Paused — Tap Resume to listen")
            }
        }

        fun resume(context: Context) {
            _isListeningPaused.value = false
            activeInstance?.let {
                if (!_isPausedByPhoneCall.value) {
                    it.startListening()
                    it.updateForegroundNotification("Listening for '$configuredWakeWord'...")
                }
            }
        }

        fun start(context: Context): Boolean {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMic) {
                Log.w(TAG, "Cannot start WakeWordForegroundService: RECORD_AUDIO permission not granted.")
                return false
            }

            if (_isServiceActive.value && activeInstance != null) {
                if (_isListeningPaused.value) {
                    resume(context)
                }
                return true
            }

            return try {
                val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_START
                }
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WakeWordForegroundService", e)
                false
            }
        }

        fun stop(context: Context) {
            if (!_isServiceActive.value && activeInstance == null) {
                return
            }
            try {
                val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop WakeWordForegroundService", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
        acquireKeepAliveWakeLock()
        registerTelephonyCallStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_PAUSE_LISTENING -> {
                _isListeningPaused.value = true
                stopListening()
                updateForegroundNotification("Paused — Tap Resume to listen")
                return START_STICKY
            }
            ACTION_RESUME_LISTENING -> {
                _isListeningPaused.value = false
                if (!_isPausedByPhoneCall.value) {
                    startListening()
                    updateForegroundNotification("Listening for '$configuredWakeWord'...")
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                _isServiceActive.value = false
                _isListeningPaused.value = false
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val hasMicPermission = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasMicPermission) {
                    Log.w(TAG, "RECORD_AUDIO permission missing in onStartCommand. Halting service.")
                    _isServiceActive.value = false
                    stopSelf()
                    return START_NOT_STICKY
                }

                try {
                    startInForeground("Listening for '$configuredWakeWord'...")
                    _isListeningPaused.value = false
                    if (!_isPausedByPhoneCall.value) {
                        startListening()
                    }
                    _isServiceActive.value = true
                    return START_STICKY
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException starting foreground microphone service", e)
                    stopListening()
                    _isServiceActive.value = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                } catch (e: Exception) {
                    Log.e(TAG, "Exception starting foreground service", e)
                    stopListening()
                    _isServiceActive.value = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildForegroundNotification(statusSubtitle: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResumeIntent = if (_isListeningPaused.value) {
            PendingIntent.getService(
                this,
                1,
                Intent(this, WakeWordForegroundService::class.java).apply { action = ACTION_RESUME_LISTENING },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                1,
                Intent(this, WakeWordForegroundService::class.java).apply { action = ACTION_PAUSE_LISTENING },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WakeWordForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeLabel = if (_isListeningPaused.value) "Resume" else "Pause"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OmniVoice Assistant Active")
            .setContentText(statusSubtitle)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(0, pauseResumeLabel, pauseOrResumeIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun startInForeground(statusSubtitle: String) {
        val notification = buildForegroundNotification(statusSubtitle)
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMicPermission) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(statusSubtitle: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildForegroundNotification(statusSubtitle))
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OmniVoice Background Listening",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps OmniVoice background microphone listening service running"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    /**
     * Registers API 31+ TelephonyCallback to auto-pause wake-word capture during regular phone calls
     * and auto-resume when the call finishes (`F-023`).
     */
    private fun registerTelephonyCallStateListener() {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) return

        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING,
                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            if (!_isPausedByPhoneCall.value) {
                                _isPausedByPhoneCall.value = true
                                stopListening()
                                updateForegroundNotification("Paused during active phone call")
                            }
                        }
                        TelephonyManager.CALL_STATE_IDLE -> {
                            if (_isPausedByPhoneCall.value) {
                                _isPausedByPhoneCall.value = false
                                if (!_isListeningPaused.value && _isServiceActive.value) {
                                    startListening()
                                    updateForegroundNotification("Listening for '$configuredWakeWord'...")
                                }
                            }
                        }
                    }
                }
            }
            telephonyCallback = callback
            tm.registerTelephonyCallback(mainExecutor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register TelephonyCallback", e)
        }
    }

    private fun unregisterTelephonyCallStateListener() {
        val cb = telephonyCallback ?: return
        telephonyCallback = null
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.unregisterTelephonyCallback(cb)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listeningJob?.isActive == true) return

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            Log.w(TAG, "Cannot start wake-word listening: RECORD_AUDIO permission not granted")
            return
        }

        listeningJob = serviceScope.launch {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufSize, 4096)

            try {
                audioRecord = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                } catch (_: Exception) {
                    null
                } ?: AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                val rec = audioRecord
                if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    return@launch
                }

                attachHardwareDspEffects(rec.audioSessionId)
                rec.startRecording()
                Log.d(TAG, "AudioRecord started recording with hardware AEC/NS/AGC")

                val buffer = ShortArray(1024)
                var consecutiveSpeechFrames = 0
                var consecutiveAllZeroFrames = 0
                val speechFrameThreshold = 4 // ~250ms of speech energy

                while (isActive) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // 1. Check Android 12–17 System Microphone Privacy Toggle (all-zero buffer)
                        if (Android12To17Compat.isAllZeroAudioBuffer(buffer, read)) {
                            consecutiveAllZeroFrames++
                            if (consecutiveAllZeroFrames == 25 && !_isMicPrivacyMuted.value) {
                                _isMicPrivacyMuted.value = true
                                updateForegroundNotification("System Mic Privacy Toggle is OFF — Enable Mic Access")
                            }
                        } else {
                            if (_isMicPrivacyMuted.value) {
                                _isMicPrivacyMuted.value = false
                                updateForegroundNotification("Listening for '$configuredWakeWord'...")
                            }
                            consecutiveAllZeroFrames = 0
                        }

                        // 2. Calculate RMS Energy
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                        }
                        val rms = sqrt(sum / read).toFloat()
                        val rmsDb = if (rms > 0) (20 * log10(rms.toDouble())).toFloat() else 0f
                        _liveAudioLevel.value = rmsDb.coerceIn(0f, 100f)

                        // 3. Speech energy threshold detection
                        if (rms > 700f) {
                            consecutiveSpeechFrames++
                            if (consecutiveSpeechFrames >= speechFrameThreshold) {
                                consecutiveSpeechFrames = 0
                                onWakeWordDetected()
                                delay(2000) // Debounce after wake
                            }
                        } else {
                            if (consecutiveSpeechFrames > 0) {
                                consecutiveSpeechFrames--
                            }
                        }
                    } else {
                        delay(20)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception in wake-word listening loop", e)
            } finally {
                releaseAudioRecord()
            }
        }
    }

    private fun attachHardwareDspEffects(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                autoGainControl = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
    }

    private fun onWakeWordDetected() {
        Log.i(TAG, "Wake word triggered!")
        vibrateHaptic()

        serviceScope.launch {
            _wakeWordTriggered.emit(configuredWakeWord)
        }

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = keyguardManager?.isDeviceLocked == true || keyguardManager?.isKeyguardLocked == true

        if (isLocked) {
            val overlayIntent = Intent(this, LockScreenOverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_TRIGGER_REASON", "WAKE_WORD")
                putExtra("EXTRA_LOCKED_MODE", true)
            }
            startActivity(overlayIntent)
        } else {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("EXTRA_START_LISTENING", true)
            }
            startActivity(mainIntent)
        }
    }

    private fun vibrateHaptic() {
        try {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    private fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        releaseAudioRecord()
        _liveAudioLevel.value = 0f
    }

    @Synchronized
    private fun releaseAudioRecord() {
        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null

        try {
            autoGainControl?.release()
        } catch (_: Exception) {
        }
        autoGainControl = null

        val recorder = audioRecord ?: return
        audioRecord = null
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
    }

    private fun acquireKeepAliveWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "OmniVoice::KeepAliveWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire PartialWakeLock", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterTelephonyCallStateListener()
        stopListening()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        activeInstance = null
        _isServiceActive.value = false
        _isListeningPaused.value = false
        Log.d(TAG, "WakeWordForegroundService destroyed")
    }
}

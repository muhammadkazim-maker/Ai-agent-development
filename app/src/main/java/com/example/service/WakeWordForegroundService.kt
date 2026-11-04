package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.engine.Android12To17Compat
import com.example.engine.AssistantAudioStateMachine
import com.example.engine.MicComponentOwner
import com.example.engine.WakeSpeechCandidateDecision
import com.example.engine.WakeSpeechEvaluationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class WakeWordForegroundService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "omni_wake_word_channel_v2"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.action.START_WAKE_SERVICE"
        const val ACTION_STOP = "com.example.action.STOP_WAKE_SERVICE"
        const val ACTION_PAUSE_LISTENING = "com.example.action.PAUSE_WAKE_LISTENING"
        const val ACTION_RESUME_LISTENING = "com.example.action.RESUME_WAKE_LISTENING"
        const val EXTRA_USER_INITIATED_ARM = "extra_user_initiated_arm"

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _isListeningPaused = MutableStateFlow(false)
        val isListeningPaused: StateFlow<Boolean> = _isListeningPaused.asStateFlow()

        private val _wakeWordTriggered = MutableSharedFlow<String>(extraBufferCapacity = 5)
        val wakeWordTriggered: SharedFlow<String> = _wakeWordTriggered.asSharedFlow()

        private val _currentRmsEnergy = MutableStateFlow(0f)
        val currentRmsEnergy: StateFlow<Float> = _currentRmsEnergy.asStateFlow()

        var configuredWakeWord: String = AssistantAudioStateMachine.DEFAULT_WAKE_WORD
        var wakeSensitivity: Float = 0.5f

        @SuppressLint("StaticFieldLeak")
        private var activeInstance: WakeWordForegroundService? = null

        /**
         * Starts the visible ongoing microphone service when armed by the user.
         * Enforces single-owner microphone arbitration and respects device-pinned stop/mute state.
         */
        fun start(context: Context, userInitiatedArm: Boolean = true): Boolean {
            val hasRecordAudio = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val coord = AssistantAudioStateMachine.shared
            if (!hasRecordAudio) {
                Log.w(TAG, "Cannot start WakeWordForegroundService: RECORD_AUDIO permission not granted.")
                coord.inspectWakeAndAssistantReadiness(context)
                return false
            }

            if (userInitiatedArm) {
                coord.armUserSessionAndPersist(context, "USER_STARTED_WAKE_SERVICE")
            } else {
                // Non-user-initiated start (e.g., BootReceiver / Watchdog): respect user's stopped/disarmed choice!
                if (!coord.isUserArmedPersisted(context) || coord.isStoppedByUserPersisted(context)) {
                    Log.i(TAG, "Skipping WakeWordForegroundService start: user has disarmed or stopped listening.")
                    return false
                }
            }

            val canStartBgNow = Android12To17Compat.canStartMicrophoneForegroundService(
                context = context,
                isAccessibilityConnected = OmniAccessibilityService.isConnected()
            )
            if (!userInitiatedArm && !canStartBgNow && !_isServiceActive.value) {
                Log.w(
                    TAG,
                    "Deferring FGS start on API ${Build.VERSION.SDK_INT} until foreground user/assistant interaction"
                )
                coord.markRequiresTapToRearmAfterOsEvent(context, "OS_DEFERRED_BACKGROUND_FGS_START")
                return false
            }

            return try {
                val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_USER_INITIATED_ARM, userInitiatedArm)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                _isServiceActive.value = true
                _isListeningPaused.value = coord.isDeviceMutedPersisted(context)
                if (!_isListeningPaused.value) {
                    coord.acquireMicrophoneOwnership(MicComponentOwner.WAKE_WORD_SERVICE) {
                        activeInstance?.pauseMicrophoneCaptureForHandoff()
                    }
                }
                SystemWatchdog.scheduleWatchdog(context)
                true
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "Android 12–17 blocked background FGS start; marking NEEDS_TAP_TO_REARM: ${e.message}")
                    coord.markRequiresTapToRearmAfterOsEvent(context, "FGS_START_NOT_ALLOWED")
                } else {
                    Log.e(TAG, "Failed to start WakeWordForegroundService", e)
                }
                false
            }
        }

        /**
         * Working Stop action: immediately stops wake listening, releases microphone ownership,
         * cancels watchdog, and pins `isStoppedByUser = true, isUserArmed = false` to the device.
         */
        fun stop(context: Context, pinUserStop: Boolean = true) {
            val coord = AssistantAudioStateMachine.shared
            if (pinUserStop) {
                coord.stopAllAndPinToDevice(context, "WAKE_SERVICE_STOP")
            } else {
                coord.releaseMicrophoneOwnership(MicComponentOwner.WAKE_WORD_SERVICE)
            }
            SystemWatchdog.cancelWatchdog(context)
            _isServiceActive.value = false
            _isListeningPaused.value = false
            _currentRmsEnergy.value = 0f
            try {
                activeInstance?.stopAudioCapture()
                val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop WakeWordForegroundService", e)
            }
        }

        fun pause(context: Context, pinDeviceMute: Boolean = false) {
            _isListeningPaused.value = true
            _currentRmsEnergy.value = 0f
            val coord = AssistantAudioStateMachine.shared
            if (pinDeviceMute) {
                coord.setDeviceMutedPinned(context, true, "WAKE_SERVICE_PAUSE_PINNED")
            } else {
                coord.releaseMicrophoneOwnership(MicComponentOwner.WAKE_WORD_SERVICE)
            }
            activeInstance?.pauseMicrophoneCaptureForHandoff()
            if (_isServiceActive.value) {
                try {
                    val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                        action = ACTION_PAUSE_LISTENING
                    }
                    context.startService(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Pause wake service fallback: ${e.message}")
                }
            }
        }

        fun resume(context: Context) {
            val coord = AssistantAudioStateMachine.shared
            // Never resume if the user stopped/disarmed or pinned mute on the device!
            if (coord.isStoppedByUserPersisted(context) || !coord.isUserArmedPersisted(context) || coord.isDeviceMutedPersisted(context)) {
                Log.i(TAG, "Skipping WakeWordForegroundService.resume: user stopped, disarmed, or muted.")
                return
            }
            val hasRecordAudio = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasRecordAudio) {
                coord.inspectWakeAndAssistantReadiness(context)
                return
            }
            _isListeningPaused.value = false
            coord.returnToWakeOnlyListening("WAKE_SERVICE_RESUMED")
            coord.acquireMicrophoneOwnership(MicComponentOwner.WAKE_WORD_SERVICE) {
                activeInstance?.pauseMicrophoneCaptureForHandoff()
            }
            activeInstance?.resumeMicrophoneCaptureAfterHandoff()
            if (_isServiceActive.value) {
                try {
                    val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                        action = ACTION_RESUME_LISTENING
                    }
                    context.startService(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Resume wake service fallback: ${e.message}")
                }
            }
        }

        /**
         * Processes a consented test audio frame or local wake candidate through the single-owner
         * wake detector ("Jarvis") without false wakes on raw RMS energy, TTS, or TV/chatter.
         */
        fun processConsentedWakeCandidate(
            context: Context,
            candidateTranscript: String,
            nowMs: Long = System.currentTimeMillis(),
            expectedWakeInTestAudio: Boolean? = null,
            expectedCommandExecutionInTestAudio: Boolean? = null
        ): WakeSpeechEvaluationResult {
            val coord = AssistantAudioStateMachine.shared
            val eval = coord.evaluateAudioCandidate(
                context = context,
                transcript = candidateTranscript,
                nowMs = nowMs,
                expectedWakeInTestAudio = expectedWakeInTestAudio,
                expectedCommandExecutionInTestAudio = expectedCommandExecutionInTestAudio,
                customWakeWord = configuredWakeWord
            )
            when (eval.decision) {
                WakeSpeechCandidateDecision.WAKE_WORD_DETECTED_OPEN_WINDOW,
                WakeSpeechCandidateDecision.WAKE_AND_INLINE_COMMAND_ACCEPTED -> {
                    activeInstance?.pauseMicrophoneCaptureForHandoff()
                    _wakeWordTriggered.tryEmit(
                        if (eval.extractedCommandText.isNotBlank()) {
                            "${configuredWakeWord}: ${eval.extractedCommandText}"
                        } else {
                            configuredWakeWord
                        }
                    )
                }
                WakeSpeechCandidateDecision.STOP_AND_DISARM_PINNED -> {
                    stop(context, pinUserStop = true)
                }
                else -> {}
            }
            return eval
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listeningJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val coord = AssistantAudioStateMachine.shared
        if (!hasRecordAudio) {
            Log.e(TAG, "RECORD_AUDIO permission missing in onStartCommand. Stopping service safely.")
            _isServiceActive.value = false
            coord.inspectWakeAndAssistantReadiness(this)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                coord.stopAllAndPinToDevice(this, "NOTIFICATION_OR_INTENT_STOP")
                SystemWatchdog.cancelWatchdog(this)
                stopAudioCapture()
                _isServiceActive.value = false
                _isListeningPaused.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PAUSE_LISTENING -> {
                _isListeningPaused.value = true
                _currentRmsEnergy.value = 0f
                pauseMicrophoneCaptureForHandoff()
                updateNotification("Paused — Tap Resume to listen for \"$configuredWakeWord\"")
                return START_STICKY
            }

            ACTION_RESUME_LISTENING -> {
                if (coord.isStoppedByUserPersisted(this) || !coord.isUserArmedPersisted(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                _isListeningPaused.value = false
                coord.acquireMicrophoneOwnership(MicComponentOwner.WAKE_WORD_SERVICE) {
                    pauseMicrophoneCaptureForHandoff()
                }
                resumeMicrophoneCaptureAfterHandoff()
                val route = coord.detectActiveAudioInputRoute(this)
                updateNotification("Wake-only active (${route.label}) — Say \"$configuredWakeWord\"")
                return START_STICKY
            }
        }

        // Respect user's disarmed/stopped state if started by OS/sticky restart
        val userInitiated = intent?.getBooleanExtra(EXTRA_USER_INITIATED_ARM, false) == true
        if (!userInitiated && (!coord.isUserArmedPersisted(this) || coord.isStoppedByUserPersisted(this))) {
            Log.i(TAG, "Stopping WakeWordForegroundService: user session is not armed.")
            _isServiceActive.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        val route = coord.detectActiveAudioInputRoute(this)
        val notification = buildNotification(
            if (_isListeningPaused.value || coord.isDeviceMutedPersisted(this)) {
                "Muted / Paused — Tap Resume to listen for \"$configuredWakeWord\""
            } else {
                "Wake-only active (${route.label}) — Say \"$configuredWakeWord\""
            }
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start foreground service with microphone type", e)
            _isServiceActive.value = false
            coord.markRequiresTapToRearmAfterOsEvent(this, "START_FOREGROUND_FAILED")
            stopSelf()
            return START_NOT_STICKY
        }

        _isServiceActive.value = true
        if (!_isListeningPaused.value && !coord.isDeviceMutedPersisted(this)) {
            val acquired = coord.acquireMicrophoneOwnership(MicComponentOwner.WAKE_WORD_SERVICE) {
                pauseMicrophoneCaptureForHandoff()
            }
            if (acquired) {
                startAudioCaptureLoop()
            }
        }
        return START_STICKY
    }

    internal fun pauseMicrophoneCaptureForHandoff() {
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
        } catch (_: Exception) {
        }
        releaseHardwareAudioEffects()
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        _currentRmsEnergy.value = 0f
    }

    internal fun resumeMicrophoneCaptureAfterHandoff() {
        if (!_isServiceActive.value || _isListeningPaused.value) return
        if (listeningJob?.isActive != true) {
            startAudioCaptureLoop()
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OmniVoice::WakeWordWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Wake-Word Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible ongoing microphone service for 'Jarvis' wake detection"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseResumeIntent = if (_isListeningPaused.value) {
            PendingIntent.getService(
                this,
                1,
                Intent(this, WakeWordForegroundService::class.java).apply { action = ACTION_RESUME_LISTENING },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                this,
                1,
                Intent(this, WakeWordForegroundService::class.java).apply { action = ACTION_PAUSE_LISTENING },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WakeWordForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseResumeLabel = if (_isListeningPaused.value) "Resume" else "Pause"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Wake-Only Listening")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, pauseResumeLabel, pauseResumeIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureLoop() {
        if (listeningJob?.isActive == true) return

        listeningJob = serviceScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                .coerceAtLeast(3200)
            val bufferSize = minBufferSize * 2
            val coord = AssistantAudioStateMachine.shared

            while (isActive && _isServiceActive.value) {
                try {
                    // Check command window timeout periodically so expired windows return to wake-only
                    coord.checkCommandWindowTimeout()

                    // Strict single-owner microphone check:
                    // Only hold AudioRecord when WAKE_WORD_SERVICE owns the mic, not paused/muted, and not during TTS/notifications!
                    val isOwner = coord.activeMicOwner.value == MicComponentOwner.WAKE_WORD_SERVICE
                    if (_isListeningPaused.value ||
                        !isOwner ||
                        coord.isSelfTriggerSuppressed() ||
                        coord.isStoppedByUserInMemory() ||
                        coord.isDeviceMutedInMemory()
                    ) {
                        if (audioRecord != null) {
                            pauseMicrophoneCaptureForHandoff()
                        }
                        delay(150)
                        continue
                    }

                    if (ContextCompat.checkSelfPermission(
                            this@WakeWordForegroundService,
                            Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "RECORD_AUDIO revoked while running. Stopping capture.")
                        coord.inspectWakeAndAssistantReadiness(this@WakeWordForegroundService)
                        stopSelf()
                        break
                    }

                    val audioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        MediaRecorder.AudioSource.VOICE_RECOGNITION
                    } else {
                        MediaRecorder.AudioSource.MIC
                    }

                    audioRecord = AudioRecord(
                        audioSource,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )

                    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord failed to initialize. Retrying in 2s...")
                        audioRecord?.release()
                        audioRecord = null
                        delay(2000)
                        continue
                    }

                    // Route to Wired / USB / Bluetooth headset if connected
                    coord.applyPreferredInputDevice(this@WakeWordForegroundService, audioRecord)
                    attachHardwareAudioEffects(audioRecord!!.audioSessionId)
                    audioRecord?.startRecording()
                    Log.d(TAG, "Wake-only AudioRecord started for '$configuredWakeWord'")

                    val buffer = ShortArray(1024)

                    while (isActive &&
                        _isServiceActive.value &&
                        !_isListeningPaused.value &&
                        coord.activeMicOwner.value == MicComponentOwner.WAKE_WORD_SERVICE &&
                        !coord.isSelfTriggerSuppressed()
                    ) {
                        val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (readCount > 0) {
                            var sum = 0.0
                            for (i in 0 until readCount) {
                                sum += buffer[i] * buffer[i]
                            }
                            val rms = sqrt(sum / readCount).toFloat()
                            _currentRmsEnergy.value = (rms / 25f).coerceIn(0f, 100f)
                            // CRITICAL FIX: Never call onWakeWordDetected() merely because rms > 700f!
                            // Raw RMS energy alone triggers false wakes on TV audio, other people talking, and TTS.
                            // Wake activation requires a verified "Jarvis" wake phrase via processConsentedWakeCandidate.
                        } else if (readCount < 0) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in wake audio capture loop. Recovering...", e)
                } finally {
                    pauseMicrophoneCaptureForHandoff()
                }
                delay(250)
            }
        }
    }

    private fun attachHardwareAudioEffects(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hardware audio effects unavailable", e)
        }
    }

    private fun releaseHardwareAudioEffects() {
        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            gainControl?.release()
        } catch (_: Exception) {
        } finally {
            echoCanceler = null
            noiseSuppressor = null
            gainControl = null
        }
    }

    fun onWakeWordDetected(commandSuffix: String = "") {
        val coord = AssistantAudioStateMachine.shared
        if (coord.isSelfTriggerSuppressed() || coord.isStoppedByUserInMemory() || coord.isDeviceMutedInMemory()) {
            Log.i(TAG, "Suppressed wake trigger during TTS/notification or while stopped/muted.")
            return
        }
        Log.d(TAG, "Wake word detected: $configuredWakeWord")
        // Release wake service mic ownership immediately before opening command window
        pauseMicrophoneCaptureForHandoff()
        coord.openCommandWindow(reason = "LOCAL_WAKE_DETECTOR_JARVIS")
        requestTransientAudioFocus()
        triggerWakeFeedback()
        val payload = if (commandSuffix.isNotBlank()) "$configuredWakeWord: $commandSuffix" else configuredWakeWord
        serviceScope.launch {
            _wakeWordTriggered.emit(payload)
        }
        updateNotification("Command Window Open — Speak your command or say 'bas/stop'")
    }

    private fun requestTransientAudioFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus", e)
        }
    }

    private fun triggerWakeFeedback() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 50, 40, 70), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration feedback failed", e)
        }

        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
            toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 140)
            serviceScope.launch {
                delay(200)
                toneGen.release()
            }
        } catch (e: Exception) {
            try {
                val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
                ringtone?.play()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopAudioCapture() {
        listeningJob?.cancel()
        listeningJob = null
        pauseMicrophoneCaptureForHandoff()
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        _isServiceActive.value = false
        AssistantAudioStateMachine.shared.releaseMicrophoneOwnership(MicComponentOwner.WAKE_WORD_SERVICE)
        stopAudioCapture()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "WakeWordForegroundService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

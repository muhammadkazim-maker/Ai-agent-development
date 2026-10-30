package com.example.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real-time Voice Activity Detection (VAD) state.
 */
enum class VadState {
    IDLE,
    MONITORING_SILENCE,
    SPEECH_ACTIVE,
    TRAILING_SILENCE
}

/**
 * Discrete state transition events emitted by the low-latency VAD energy detector.
 */
enum class VadTransitionEvent {
    NONE,
    SPEECH_STARTED,
    SPEECH_CONTINUING,
    SPEECH_ENDED_COMPLETE,
    SPEECH_REJECTED_TOO_SHORT
}

/**
 * Configuration for the low-latency AudioRecord Voice Activity Detector.
 */
data class VadConfig(
    val sampleRateHz: Int = 16000,
    val frameDurationMs: Int = 20, // 20ms low-latency frame = 320 samples @ 16kHz
    val energyThresholdRms: Float = 320f,
    val energyThresholdDbFs: Float = -38f,
    val speechOnsetFrames: Int = 3, // 3 consecutive 20ms frames (60ms) to trigger Gemini recording start
    val silenceHangoverMs: Long = 1600L, // Silence duration after speech to auto-trigger Gemini recording end
    val minSpeechDurationMs: Long = 600L,
    val maxSpeechDurationMs: Long = 25000L,
    val preRollDurationMs: Int = 500, // 500ms pre-roll circular buffer so initial syllable after wake-word is never clipped (F-035)
    val adaptiveNoiseFloor: Boolean = true,
    val snrMarginMultiplier: Float = 1.85f
) {
    companion object {
        const val NORMAL_HANGOVER_MS = 1800L
        const val SPELLING_PASSWORD_HANGOVER_MS = 2600L
        const val DEFAULT_PRE_ROLL_MS = 500
    }

    val samplesPerFrame: Int
        get() = (sampleRateHz * frameDurationMs) / 1000

    val preRollFrameCount: Int
        get() = max(1, preRollDurationMs / frameDurationMs)
}

/**
 * Telemetry snapshot for each processed 20ms audio frame.
 */
data class VadFrameMetrics(
    val rmsEnergy: Float = 0f,
    val dbFs: Float = -90f,
    val normalizedLevel: Float = 0f,
    val noiseFloorRms: Float = 120f,
    val effectiveThresholdRms: Float = 320f,
    val isSpeechFrame: Boolean = false,
    val vadState: VadState = VadState.IDLE,
    val speechDurationMs: Long = 0L,
    val trailingSilenceMs: Long = 0L,
    val transitionEvent: VadTransitionEvent = VadTransitionEvent.NONE
)

/**
 * Low-latency Voice Activity Detection (VAD) module using [AudioRecord] and a real-time
 * RMS / dBFS energy threshold check with adaptive noise-floor estimation and a pre-roll ring buffer
 * to automatically trigger Gemini recording start and end.
 */
class VoiceActivityDetector(
    private val context: Context,
    var config: VadConfig = VadConfig()
) {
    companion object {
        private const val TAG = "VoiceActivityDetector"
        private const val FULL_SCALE_16BIT = 32768.0

        /**
         * Computes Root Mean Square (RMS) energy of a 16-bit PCM frame in O(N) with zero allocations.
         */
        fun calculateRmsEnergy(samples: ShortArray, length: Int = samples.size): Float {
            val n = min(length, samples.size)
            if (n <= 0) return 0f
            var sumSquares = 0.0
            for (i in 0 until n) {
                val s = samples[i].toDouble()
                sumSquares += s * s
            }
            return sqrt(sumSquares / n).toFloat()
        }

        /**
         * Converts 16-bit PCM RMS amplitude into decibels relative to full scale (dBFS, range -90..0).
         */
        fun rmsToDbFs(rms: Float): Float {
            if (rms <= 1f) return -90f
            val db = 20.0 * log10(rms.toDouble() / FULL_SCALE_16BIT)
            return db.toFloat().coerceIn(-90f, 0f)
        }

        /**
         * Encodes raw 16kHz Mono 16-bit little-endian PCM bytes into a standard 44-byte RIFF `audio/wav` container.
         */
        fun encodePcmToWav(
            pcmData: ByteArray,
            sampleRate: Int = 16000,
            channels: Int = 1,
            bitsPerSample: Int = 16
        ): ByteArray {
            val totalAudioLen = pcmData.size.toLong()
            val totalDataLen = totalAudioLen + 36
            val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()

            val header = ByteArray(44)
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xffL).toByte()
            header[5] = (totalDataLen shr 8 and 0xffL).toByte()
            header[6] = (totalDataLen shr 16 and 0xffL).toByte()
            header[7] = (totalDataLen shr 24 and 0xffL).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1 // PCM = 1
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = (sampleRate shr 8 and 0xff).toByte()
            header[26] = (sampleRate shr 16 and 0xff).toByte()
            header[27] = (sampleRate shr 24 and 0xff).toByte()
            header[28] = (byteRate and 0xffL).toByte()
            header[29] = (byteRate shr 8 and 0xffL).toByte()
            header[30] = (byteRate shr 16 and 0xffL).toByte()
            header[31] = (byteRate shr 24 and 0xffL).toByte()
            header[32] = (channels * bitsPerSample / 8).toByte()
            header[33] = 0
            header[34] = bitsPerSample.toByte()
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xffL).toByte()
            header[41] = (totalAudioLen shr 8 and 0xffL).toByte()
            header[42] = (totalAudioLen shr 16 and 0xffL).toByte()
            header[43] = (totalAudioLen shr 24 and 0xffL).toByte()

            val wavOutput = ByteArray(header.size + pcmData.size)
            System.arraycopy(header, 0, wavOutput, 0, header.size)
            System.arraycopy(pcmData, 0, wavOutput, header.size, pcmData.size)
            return wavOutput
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null

    private val _vadState = MutableStateFlow(VadState.IDLE)
    val vadState: StateFlow<VadState> = _vadState.asStateFlow()

    private val _isSpeechActive = MutableStateFlow(false)
    val isSpeechActive: StateFlow<Boolean> = _isSpeechActive.asStateFlow()

    private val _frameMetrics = MutableStateFlow(VadFrameMetrics())
    val frameMetrics: StateFlow<VadFrameMetrics> = _frameMetrics.asStateFlow()

    private val _isMicMutedByPrivacyToggle = MutableStateFlow(false)
    val isMicMutedByPrivacyToggle: StateFlow<Boolean> = _isMicMutedByPrivacyToggle.asStateFlow()
    private var consecutiveZeroFramesCount = 0

    // Ring buffer of recent 20ms PCM frames so speech onset never clips the first syllable
    private val preRollQueue = ArrayDeque<ByteArray>()
    private val activeSpeechPcmBuffer = ByteArrayOutputStream()

    private var consecutiveSpeechFrames = 0
    private var speechStartTimestampMs = 0L
    private var lastActiveSpeechTimestampMs = 0L
    private var peakSpeechRms = 0f
    private var noiseFloorRms = 110f

    @Volatile
    private var manualFinalizeRequested = false

    /**
     * Switches silence hangover between Normal Speech (`1800ms`) and Spelling/Password Mode (`2600ms`) (`F-036`, `F-038`).
     */
    @Synchronized
    fun setSpellingModeEnabled(enabled: Boolean) {
        val hangover = if (enabled) {
            VadConfig.SPELLING_PASSWORD_HANGOVER_MS
        } else {
            VadConfig.NORMAL_HANGOVER_MS
        }
        config = config.copy(silenceHangoverMs = hangover)
    }

    // Callbacks for automatic Gemini recording start, live frame updates, and automatic end
    var onRecordingStartTriggered: ((preRollPcmBytes: ByteArray) -> Unit)? = null
    var onFrameProcessed: ((metrics: VadFrameMetrics, frameBytes: ByteArray) -> Unit)? = null
    var onRecordingEndTriggered: ((wavBytes: ByteArray, pcmBytes: ByteArray, durationMs: Long) -> Unit)? = null
    var onRecordingRejectedShortOrSilent: ((durationMs: Long, peakRms: Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Resets the internal VAD state machine for a new session.
     */
    @Synchronized
    fun resetState(initialState: VadState = VadState.MONITORING_SILENCE) {
        preRollQueue.clear()
        activeSpeechPcmBuffer.reset()
        consecutiveSpeechFrames = 0
        speechStartTimestampMs = 0L
        lastActiveSpeechTimestampMs = 0L
        peakSpeechRms = 0f
        noiseFloorRms = 110f
        manualFinalizeRequested = false
        _vadState.value = initialState
        _isSpeechActive.value = false
        _frameMetrics.value = VadFrameMetrics(vadState = initialState)
    }

    /**
     * Processes a single 16-bit PCM frame through the low-latency energy threshold detector.
     * Can be called directly in unit tests or from the live [AudioRecord] capture loop.
     */
    @Synchronized
    fun processPcmFrame(
        samples: ShortArray,
        length: Int = samples.size,
        timestampMs: Long = System.currentTimeMillis(),
        autoStopOnSilence: Boolean = true
    ): VadFrameMetrics {
        val validLen = min(length, samples.size)
        if (Android12To17Compat.isAllZeroAudioBuffer(samples, validLen)) {
            consecutiveZeroFramesCount++
            if (consecutiveZeroFramesCount >= 25) {
                _isMicMutedByPrivacyToggle.value = true
            }
        } else {
            consecutiveZeroFramesCount = 0
            _isMicMutedByPrivacyToggle.value = false
        }
        val frameBytes = shortsToLittleEndianBytes(samples, validLen)
        val rms = calculateRmsEnergy(samples, validLen)
        val dbFs = rmsToDbFs(rms)

        // Compute adaptive or static energy threshold
        val dynamicThreshold = if (config.adaptiveNoiseFloor) {
            max(config.energyThresholdRms, noiseFloorRms * config.snrMarginMultiplier)
        } else {
            config.energyThresholdRms
        }

        val isAboveThreshold = rms >= dynamicThreshold && dbFs >= config.energyThresholdDbFs

        // Update noise floor estimate smoothly during silence frames
        if (!isAboveThreshold && _vadState.value == VadState.MONITORING_SILENCE) {
            noiseFloorRms = (0.92f * noiseFloorRms + 0.08f * rms).coerceIn(50f, config.energyThresholdRms * 0.85f)
        }

        var transition = VadTransitionEvent.NONE
        var currentState = _vadState.value

        if (currentState == VadState.IDLE) {
            currentState = VadState.MONITORING_SILENCE
        }

        when (currentState) {
            VadState.MONITORING_SILENCE -> {
                // Maintain pre-roll ring buffer
                enqueuePreRollFrame(frameBytes)

                if (isAboveThreshold) {
                    consecutiveSpeechFrames++
                    if (consecutiveSpeechFrames >= config.speechOnsetFrames) {
                        // Speech onset confirmed -> Trigger Gemini Recording Start automatically!
                        currentState = VadState.SPEECH_ACTIVE
                        _isSpeechActive.value = true
                        speechStartTimestampMs = timestampMs - (consecutiveSpeechFrames * config.frameDurationMs)
                        lastActiveSpeechTimestampMs = timestampMs
                        peakSpeechRms = rms

                        activeSpeechPcmBuffer.reset()
                        val preRollBytes = drainPreRollBytes()
                        activeSpeechPcmBuffer.write(preRollBytes)
                        transition = VadTransitionEvent.SPEECH_STARTED
                        onRecordingStartTriggered?.invoke(preRollBytes)
                    }
                } else {
                    consecutiveSpeechFrames = 0
                }
            }

            VadState.SPEECH_ACTIVE, VadState.TRAILING_SILENCE -> {
                activeSpeechPcmBuffer.write(frameBytes)
                if (rms > peakSpeechRms) {
                    peakSpeechRms = rms
                }

                val speechDuration = max(0L, timestampMs - speechStartTimestampMs)

                if (isAboveThreshold) {
                    currentState = VadState.SPEECH_ACTIVE
                    lastActiveSpeechTimestampMs = timestampMs
                    transition = VadTransitionEvent.SPEECH_CONTINUING
                } else {
                    currentState = VadState.TRAILING_SILENCE
                    val silenceElapsed = max(0L, timestampMs - lastActiveSpeechTimestampMs)

                    if ((autoStopOnSilence && silenceElapsed >= config.silenceHangoverMs) ||
                        speechDuration >= config.maxSpeechDurationMs
                    ) {
                        transition = finalizeCurrentUtteranceInternal(speechDuration)
                        currentState = _vadState.value
                    }
                }
            }

            VadState.IDLE -> Unit
        }

        _vadState.value = currentState
        val speechElapsed = if (speechStartTimestampMs > 0L &&
            (currentState == VadState.SPEECH_ACTIVE || currentState == VadState.TRAILING_SILENCE)
        ) {
            max(0L, timestampMs - speechStartTimestampMs)
        } else {
            0L
        }
        val silenceElapsed = if (lastActiveSpeechTimestampMs > 0L && currentState == VadState.TRAILING_SILENCE) {
            max(0L, timestampMs - lastActiveSpeechTimestampMs)
        } else {
            0L
        }

        val normalizedUiLevel = ((dbFs + 65f) / 55f).coerceIn(0f, 1f)
        val metrics = VadFrameMetrics(
            rmsEnergy = rms,
            dbFs = dbFs,
            normalizedLevel = normalizedUiLevel,
            noiseFloorRms = noiseFloorRms,
            effectiveThresholdRms = dynamicThreshold,
            isSpeechFrame = isAboveThreshold,
            vadState = currentState,
            speechDurationMs = speechElapsed,
            trailingSilenceMs = silenceElapsed,
            transitionEvent = transition
        )
        _frameMetrics.value = metrics
        onFrameProcessed?.invoke(metrics, frameBytes)
        return metrics
    }

    /**
     * Starts continuous low-latency [AudioRecord] capture with automatic energy-based start and stop.
     */
    @SuppressLint("MissingPermission")
    fun startMonitoring(autoStopOnSilence: Boolean = true) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            onError?.invoke("Microphone permission required to start VAD")
            return
        }

        stopMonitoring(cancelOnly = true)
        resetState(VadState.MONITORING_SILENCE)

        val minBufferBytes = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val frameSamples = config.samplesPerFrame
        val frameBytesSize = frameSamples * 2
        val bufferSizeInBytes = max(minBufferBytes, frameBytesSize * 4)

        captureJob = scope.launch {
            try {
                val recorder = createLowLatencyAudioRecord(bufferSizeInBytes)
                if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                    _vadState.value = VadState.IDLE
                    onError?.invoke("Unable to initialize low-latency AudioRecord")
                    return@launch
                }

                audioRecord = recorder
                attachAudioEffects(recorder.audioSessionId)
                recorder.startRecording()
                Log.d(TAG, "Low-latency AudioRecord VAD started (${config.sampleRateHz}Hz, ${config.frameDurationMs}ms frame)")

                val frameBuffer = ShortArray(frameSamples)

                while (isActive && !manualFinalizeRequested && _vadState.value != VadState.IDLE) {
                    val readCount = recorder.read(frameBuffer, 0, frameBuffer.size)
                    if (readCount > 0) {
                        val metrics = processPcmFrame(
                            samples = frameBuffer,
                            length = readCount,
                            timestampMs = System.currentTimeMillis(),
                            autoStopOnSilence = autoStopOnSilence
                        )
                        if (metrics.transitionEvent == VadTransitionEvent.SPEECH_ENDED_COMPLETE ||
                            metrics.transitionEvent == VadTransitionEvent.SPEECH_REJECTED_TOO_SHORT
                        ) {
                            break
                        }
                    } else {
                        delay(5)
                    }
                }

                if (manualFinalizeRequested) {
                    val duration = if (speechStartTimestampMs > 0L) {
                        max(0L, System.currentTimeMillis() - speechStartTimestampMs)
                    } else {
                        0L
                    }
                    finalizeCurrentUtteranceInternal(duration)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord VAD loop error", e)
                onError?.invoke(e.localizedMessage ?: "VAD capture error")
            } finally {
                releaseAudioRecord()
                _vadState.value = VadState.IDLE
                _isSpeechActive.value = false
            }
        }
    }

    /**
     * Manually finalizes the current speech segment and triggers [onRecordingEndTriggered]
     * (or [onRecordingRejectedShortOrSilent] if too short/silent).
     */
    fun stopAndFinalize() {
        manualFinalizeRequested = true
    }

    /**
     * Immediately stops [AudioRecord] and resets state.
     */
    fun stopMonitoring(cancelOnly: Boolean = false) {
        if (cancelOnly) {
            captureJob?.cancel()
            captureJob = null
            releaseAudioRecord()
            resetState(VadState.IDLE)
        } else {
            manualFinalizeRequested = true
        }
    }

    /**
     * Returns the currently buffered PCM bytes encoded as a 16kHz Mono 16-bit WAV byte array.
     */
    @Synchronized
    fun getCapturedWavBytes(): ByteArray {
        val pcm = activeSpeechPcmBuffer.toByteArray()
        return encodePcmToWav(pcm, config.sampleRateHz, 1, 16)
    }

    @Synchronized
    private fun finalizeCurrentUtteranceInternal(speechDurationMs: Long): VadTransitionEvent {
        val pcmBytes = activeSpeechPcmBuffer.toByteArray()
        val minBytes = (config.sampleRateHz * 2 * config.minSpeechDurationMs / 1000L).toInt()
        val isTooShort = speechDurationMs < config.minSpeechDurationMs || pcmBytes.size < minBytes
        val isSilent = peakSpeechRms < (config.energyThresholdRms * 0.8f)

        _isSpeechActive.value = false
        _vadState.value = VadState.IDLE

        return if (isTooShort || isSilent) {
            onRecordingRejectedShortOrSilent?.invoke(speechDurationMs, peakSpeechRms)
            VadTransitionEvent.SPEECH_REJECTED_TOO_SHORT
        } else {
            val wavBytes = encodePcmToWav(pcmBytes, config.sampleRateHz, 1, 16)
            onRecordingEndTriggered?.invoke(wavBytes, pcmBytes, speechDurationMs)
            VadTransitionEvent.SPEECH_ENDED_COMPLETE
        }
    }

    private fun enqueuePreRollFrame(frameBytes: ByteArray) {
        while (preRollQueue.size >= config.preRollFrameCount) {
            preRollQueue.pollFirst()
        }
        preRollQueue.addLast(frameBytes)
    }

    private fun drainPreRollBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        while (preRollQueue.isNotEmpty()) {
            val chunk = preRollQueue.pollFirst()
            if (chunk != null) {
                out.write(chunk)
            }
        }
        return out.toByteArray()
    }

    private fun shortsToLittleEndianBytes(samples: ShortArray, length: Int): ByteArray {
        val out = ByteArray(length * 2)
        for (i in 0 until length) {
            val s = samples[i].toInt()
            out[i * 2] = (s and 0x00FF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0x00FF).toByte()
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun createLowLatencyAudioRecord(bufferSizeInBytes: Int): AudioRecord? {
        return try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                config.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: Exception) {
            null
        } ?: try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (_: Exception) {
            null
        }
    }

    private fun attachAudioEffects(audioSessionId: Int) {
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

        val rec = audioRecord ?: return
        audioRecord = null
        try {
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                rec.stop()
            }
            rec.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord in VAD", e)
        }
    }
}

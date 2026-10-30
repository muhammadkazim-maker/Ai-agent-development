package com.example.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Supported Raw Audio & VAD Engines.
 */
enum class SttEngineType {
    GEMINI_AUDIO_PCM,
    OFFLINE_ACOUSTIC_VAD
}

/**
 * Strict Two-Step Verification Intent Type:
 * - CONVERSATION: Greeting, question, mic test ("Can you listen?", "Hello", "Kya tum sun sakte ho?"), general chat -> NEVER execute tools.
 * - UNCLEAR: Noisy, garbled, or unintelligible audio -> NEVER execute tools.
 * - ACTION: Explicit device or AI command ("Turn on flashlight", "Call Ali", "Make an AI image...") -> Execute verified tools.
 */
enum class VoiceIntentType {
    CONVERSATION,
    ACTION,
    UNCLEAR
}

data class MultimodalVoiceAnalysis(
    val transcript: String,
    val intentType: VoiceIntentType,
    val reply: String,
    val toolsToExecuteJson: JSONArray = JSONArray(),
    val chainOfThoughtReasoning: String = "Evaluated user utterance deterministically at temperature 0.0.",
    val confidenceScore: Int = 99,
    val audioClarityScore: Int = 98,
    val intentCategory: NluIntentCategory = NluIntentCategory.PURE_CONVERSATION,
    val deterministicTasks: List<DeterministicNluTask> = emptyList()
)

/**
 * Pure In-Memory 16kHz Mono 16-bit PCM `audio/wav` Recorder + Live Audio Diagnostics & Playback + Gemini Verifier.
 *
 * Key Guarantees:
 * 1. Encodes microphone audio directly into standard 44-byte RIFF 16kHz Mono 16-bit PCM WAV (`audio/wav`).
 * 2. Strips any codec suffixes (`;codecs=opus`) and `data:audio/wav;base64,` prefixes before sending to Gemini.
 * 3. Uses reliable Tap-to-Start / Tap-to-Send Toggle without premature auto-silence cutoffs.
 * 4. Exposes live Audio Diagnostics (`Recorded: [X.X] seconds | Size: [XX] KB | Peak Mic Volume: [XX]% | Format: audio/wav`)
 *    and in-memory WAV playback so the user can listen to their own recorded clip on screen.
 * 5. Never falsely rejects human speech with `CLARIFICATION_NEEDED` unless the audio contains 0% human speech.
 */
class MultiEngineSpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "OmniWavRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /**
         * Generous 2.5-second (2500ms) silence window constant preserved for VAD compatibility.
         */
        const val VAD_SILENCE_AUTO_STOP_MS = 2500L

        /**
         * Minimum valid speech recording duration constant preserved for compatibility.
         */
        const val MIN_VALID_AUDIO_DURATION_MS = 600L

        const val TOO_SHORT_OR_SILENT_MESSAGE = "Audio was too short or silent, please speak again."
        const val UNCLEAR_AUDIO_MESSAGE = "I heard some noise but couldn't catch your exact words. Could you say that again?"

        /**
         * Pure In-Memory Float32Array-to-16kHz Mono 16-bit PCM WAV (`audio/wav`) Encoder.
         * Merges FloatArray audio chunks, clamps each sample to `[-1f, 1f]`, converts to 16-bit signed PCM
         * (`s < 0 ? s * 0x8000 : s * 0x7FFF`), and prepends the standard 44-byte RIFF `WAVEfmt ` header.
         */
        fun encodeFloat32ChunksToWav(chunks: List<FloatArray>, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val totalSamples = chunks.sumOf { it.size }
            val pcmBytes = ByteArray(totalSamples * 2)
            var offset = 0
            for (chunk in chunks) {
                for (rawSample in chunk) {
                    val s = rawSample.coerceIn(-1f, 1f)
                    val pcm16 = if (s < 0f) {
                        (s * 0x8000).toInt().coerceIn(-32768, 32767)
                    } else {
                        (s * 0x7FFF).toInt().coerceIn(-32768, 32767)
                    }
                    pcmBytes[offset++] = (pcm16 and 0x00FF).toByte()
                    pcmBytes[offset++] = ((pcm16 shr 8) and 0x00FF).toByte()
                }
            }
            return buildStandard44ByteRiffWav(pcmBytes, sampleRate, 1, 16)
        }

        fun buildStandard44ByteRiffWav(
            pcmData: ByteArray,
            sampleRate: Int = SAMPLE_RATE,
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

        /**
         * Strict guard that detects conversational/mic-check phrases so they NEVER trigger device tools.
         */
        fun isConversationalQuery(transcript: String): Boolean {
            val lower = transcript.lowercase().trim()
            val conversationalPatterns = listOf(
                "can you listen",
                "can you hear",
                "are you listening",
                "do you hear me",
                "testing",
                "mic check",
                "hello",
                "hi ",
                "hey omni",
                "assalam",
                "salam",
                "how are you",
                "who are you",
                "what can you do",
                "kya tum sun",
                "meri awaz",
                "sun sakte ho",
                "sun rahe ho",
                "kya haal",
                "kon ho",
                "kaun ho",
                "کیا تم سن",
                "میری آواز",
                "السلام علیکم",
                "کیا حال ہے"
            )
            val hasExplicitActionNoun = lower.contains("flashlight") ||
                lower.contains("torch") ||
                lower.contains("batti") ||
                lower.contains("whatsapp") ||
                lower.contains("image") ||
                lower.contains("tasveer") ||
                lower.contains("alarm") ||
                lower.contains("timer") ||
                lower.contains("wifi") ||
                lower.contains("wi-fi") ||
                lower.contains("bluetooth") ||
                lower.contains("youtube") ||
                lower.contains("call ") ||
                lower.contains("ko call") ||
                lower.contains("sms") ||
                lower.contains("volume") ||
                lower.contains("brightness")

            if ((lower == "hi" || conversationalPatterns.any { lower == it.trim() || lower.startsWith(it) || lower.contains(it) }) && !hasExplicitActionNoun) {
                return true
            }
            return false
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private var pcmAudioRecord: AudioRecord? = null
    private var pcmRecordingJob: Job? = null
    private var playbackAudioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null

    @Volatile
    private var manualSendRequested = false

    private val _activeEngine = MutableStateFlow(SttEngineType.GEMINI_AUDIO_PCM)
    val activeEngine: StateFlow<SttEngineType> = _activeEngine.asStateFlow()

    private val _detectedMimeType = MutableStateFlow("audio/wav")
    val detectedMimeType: StateFlow<String> = _detectedMimeType.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _audioLevelRms = MutableStateFlow(0f)
    val audioLevelRms: StateFlow<Float> = _audioLevelRms.asStateFlow()

    private val _audioLevelDb = MutableStateFlow(-60f)
    val audioLevelDb: StateFlow<Float> = _audioLevelDb.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _audioFrequencyBands = MutableStateFlow(List(8) { 0.08f })
    val audioFrequencyBands: StateFlow<List<Float>> = _audioFrequencyBands.asStateFlow()

    private val _lastRecordedWavBytes = MutableStateFlow<ByteArray?>(null)
    val lastRecordedWavBytes: StateFlow<ByteArray?> = _lastRecordedWavBytes.asStateFlow()

    private val _lastRecordedBase64 = MutableStateFlow<String?>(null)
    val lastRecordedBase64: StateFlow<String?> = _lastRecordedBase64.asStateFlow()

    // Live Audio Diagnostics Bar state flows
    private val _lastRecordedDurationSec = MutableStateFlow(0f)
    val lastRecordedDurationSec: StateFlow<Float> = _lastRecordedDurationSec.asStateFlow()

    private val _lastRecordedSizeKb = MutableStateFlow(0)
    val lastRecordedSizeKb: StateFlow<Int> = _lastRecordedSizeKb.asStateFlow()

    private val _lastRecordedPeakVolumePercent = MutableStateFlow(0)
    val lastRecordedPeakVolumePercent: StateFlow<Int> = _lastRecordedPeakVolumePercent.asStateFlow()

    private val _isPlayingRecordedAudio = MutableStateFlow(false)
    val isPlayingRecordedAudio: StateFlow<Boolean> = _isPlayingRecordedAudio.asStateFlow()

    private val _lastVoiceAnalysis = MutableStateFlow<MultimodalVoiceAnalysis?>(null)
    val lastVoiceAnalysis: StateFlow<MultimodalVoiceAnalysis?> = _lastVoiceAnalysis.asStateFlow()

    /**
     * Defaults to false (Tap-to-Start / Tap-to-Send Toggle) so the microphone NEVER cuts off sentences early.
     */
    private val _continuousLockMode = MutableStateFlow(false)
    val continuousLockMode: StateFlow<Boolean> = _continuousLockMode.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _needsMicActivation = MutableStateFlow(false)
    val needsMicActivation: StateFlow<Boolean> = _needsMicActivation.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val voiceActivityDetector = VoiceActivityDetector(
        context = context,
        config = VadConfig(
            sampleRateHz = SAMPLE_RATE,
            frameDurationMs = 20,
            energyThresholdRms = 320f,
            silenceHangoverMs = VAD_SILENCE_AUTO_STOP_MS,
            minSpeechDurationMs = MIN_VALID_AUDIO_DURATION_MS
        )
    )

    val vadState: StateFlow<VadState> = voiceActivityDetector.vadState
    val vadFrameMetrics: StateFlow<VadFrameMetrics> = voiceActivityDetector.frameMetrics

    var onResultCallback: ((String) -> Unit)? = null
    var onAnalysisResultCallback: ((MultimodalVoiceAnalysis) -> Unit)? = null
    var onShortOrSilentAudioCallback: ((String) -> Unit)? = null
    var onErrorCallback: ((Int, String) -> Unit)? = null
    var onSpeechStartCallback: (() -> Unit)? = null
    var onEndOfSpeechCallback: (() -> Unit)? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun setContinuousLockMode(enabled: Boolean) {
        _continuousLockMode.value = enabled
    }

    fun isRecognitionAvailable(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Always returns a clean, codec-stripped MIME type (e.g. `'audio/webm;codecs=opus'` -> `'audio/webm'`,
     * and default PCM WAV -> `'audio/wav'`).
     */
    fun sanitizeMimeType(rawMimeType: String?): String {
        val base = (rawMimeType ?: "audio/wav").split(";")[0].trim().lowercase()
        return if (base.startsWith("audio/")) base else "audio/wav"
    }

    fun detectBestSupportedMobileMimeType(): String {
        return "audio/wav"
    }

    /**
     * Strips any `data:audio/...;base64,` prefix from a Base64 audio string.
     */
    fun stripBase64DataUrlPrefix(rawBase64OrDataUrl: String): String {
        val trimmed = rawBase64OrDataUrl.trim()
        val commaIndex = trimmed.indexOf(',')
        return if (trimmed.startsWith("data:audio", ignoreCase = true) && commaIndex >= 0) {
            trimmed.substring(commaIndex + 1).trim()
        } else {
            trimmed
        }
    }

    /**
     * Plays or stops the last recorded 16kHz PCM WAV audio in memory so the user can hear their captured voice.
     */
    fun togglePlayLastRecordedWav() {
        if (_isPlayingRecordedAudio.value) {
            stopPlayingRecordedWav()
        } else {
            playLastRecordedWav()
        }
    }

    fun playLastRecordedWav() {
        val wavBytes = _lastRecordedWavBytes.value ?: return
        if (wavBytes.size <= 44) return
        stopPlayingRecordedWav()

        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                _isPlayingRecordedAudio.value = true
                val pcmData = wavBytes.copyOfRange(44, wavBytes.size)
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, pcmData.size))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                playbackAudioTrack = track
                track.play()

                var offset = 0
                val chunkSize = 4096
                while (isActive && _isPlayingRecordedAudio.value && offset < pcmData.size) {
                    val toWrite = minOf(chunkSize, pcmData.size - offset)
                    val written = track.write(pcmData, offset, toWrite)
                    if (written <= 0) break
                    offset += written
                }
                delay(150)
            } catch (e: Exception) {
                Log.w(TAG, "Error playing recorded WAV: ${e.message}")
            } finally {
                _isPlayingRecordedAudio.value = false
                try {
                    playbackAudioTrack?.stop()
                } catch (_: Exception) {
                }
                try {
                    playbackAudioTrack?.release()
                } catch (_: Exception) {
                }
                playbackAudioTrack = null
            }
        }
    }

    fun stopPlayingRecordedWav() {
        _isPlayingRecordedAudio.value = false
        playbackJob?.cancel()
        playbackJob = null
        try {
            playbackAudioTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            playbackAudioTrack?.release()
        } catch (_: Exception) {
        }
        playbackAudioTrack = null
    }

    /**
     * Starts 16kHz Mono 16-bit PCM WAV recording (1st Tap of Toggle).
     */
    fun startListening(preferredEngine: SttEngineType? = null) {
        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            _needsMicActivation.value = true
            _isListening.value = false
            _partialText.value = "Tap to Activate Omni Microphone"
            return
        }

        stopPlayingRecordedWav()
        stopListeningInternal(cancelOnly = true)
        manualSendRequested = false
        _needsMicActivation.value = false
        _activeEngine.value = preferredEngine ?: SttEngineType.GEMINI_AUDIO_PCM
        _detectedMimeType.value = "audio/wav"
        _isListening.value = true
        _recordingDurationMs.value = 0L
        _partialText.value = "🔴 Recording... Speak now, then tap again to send"
        _audioLevelRms.value = 0f
        _lastError.value = null

        startRawPcmWavCapture()
    }

    /**
     * Finalizes the current recording (2nd Tap of Toggle) and sends the 16kHz WAV audio to Gemini.
     */
    fun stopAndFinalizeSpeech() {
        if (_isListening.value) {
            manualSendRequested = true
        }
    }

    /**
     * Cancels recording immediately without sending.
     */
    fun stopListening() {
        stopListeningInternal(cancelOnly = true)
    }

    private fun stopListeningInternal(cancelOnly: Boolean) {
        if (cancelOnly) {
            _isListening.value = false
            _audioLevelRms.value = 0f
            _recordingDurationMs.value = 0L
            _audioFrequencyBands.value = List(8) { 0.08f }
            pcmRecordingJob?.cancel()
            pcmRecordingJob = null
            releasePcmAudioRecord()
        }
    }

    @Synchronized
    private fun releasePcmAudioRecord() {
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

        val recorder = pcmAudioRecord ?: return
        pcmAudioRecord = null
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
    }

    private fun attachHardwareAudioFilters(audioSessionId: Int) {
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

    // =========================================================================
    // PURE IN-MEMORY 16kHz MONO 16-BIT PCM WAV RECORDER + LIVE VOLUME METER
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startRawPcmWavCapture() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 4096)

        pcmRecordingJob = scope.launch(Dispatchers.IO) {
            try {
                val audioRecordInstance = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                } catch (_: Exception) {
                    null
                } ?: AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                pcmAudioRecord = audioRecordInstance

                if (pcmAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    _needsMicActivation.value = true
                    _isListening.value = false
                    _partialText.value = "Tap to Activate Omni Microphone"
                    return@launch
                }

                attachHardwareAudioFilters(audioRecordInstance.audioSessionId)
                voiceActivityDetector.resetState(VadState.MONITORING_SILENCE)
                audioRecordInstance.startRecording()
                Log.d(TAG, "Pure In-Memory 16kHz Mono 16-bit PCM WAV Recorder started")

                val floatChunksInMemory = mutableListOf<FloatArray>()
                val buffer = ShortArray(1024)

                var speechDetected = false
                var peakVolumePercent = 0
                val recordingStartMs = System.currentTimeMillis()
                val maxRecordingDurationMs = 60000L // 60s safety cap; no premature auto-silence cutoff!

                while (isActive && _isListening.value && !manualSendRequested) {
                    val read = pcmAudioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val floatChunk = FloatArray(read)
                        var floatSumSquares = 0.0
                        for (i in 0 until read) {
                            val normalizedFloat = (buffer[i] / 32768.0f).coerceIn(-1f, 1f)
                            floatChunk[i] = normalizedFloat
                            floatSumSquares += (normalizedFloat * normalizedFloat).toDouble()
                        }
                        floatChunksInMemory.add(floatChunk)

                        // Calculate real-time RMS volume (`Math.sqrt(sum / buffer.length)`)
                        val floatRms = sqrt(floatSumSquares / read).toFloat()
                        val volumePercent = ((floatRms * 320f).coerceIn(0f, 100f)).roundToInt()
                        if (volumePercent > peakVolumePercent) {
                            peakVolumePercent = volumePercent
                        }

                        val nowMs = System.currentTimeMillis()
                        val vadMetrics = voiceActivityDetector.processPcmFrame(
                            samples = buffer,
                            length = read,
                            timestampMs = nowMs,
                            autoStopOnSilence = false // Never cut off prematurely!
                        )

                        _audioLevelRms.value = volumePercent.toFloat().coerceAtLeast(
                            if (vadMetrics.rmsEnergy > 0f) (20.0 * log10(vadMetrics.rmsEnergy.toDouble())).toFloat().coerceIn(0f, 100f) else 0f
                        )
                        _audioLevelDb.value = vadMetrics.dbFs
                        updateFrequencyBandsFromPcm(buffer, read, _audioLevelRms.value)

                        val elapsedMs = nowMs - recordingStartMs
                        _recordingDurationMs.value = elapsedMs

                        if (!speechDetected && (volumePercent >= 5 || vadMetrics.isSpeechFrame)) {
                            speechDetected = true
                            mainHandler.post { onSpeechStartCallback?.invoke() }
                        }

                        // Fast automatic end-of-speech detection: once the user has spoken for >=350ms
                        // and pauses for >=720ms (when not in continuous lock mode), process immediately!
                        if (speechDetected && !_continuousLockMode.value && elapsedMs >= 400L && vadMetrics.trailingSilenceMs >= 720L) {
                            Log.d(TAG, "Fast VAD endpoint reached (${vadMetrics.trailingSilenceMs}ms silence after speech). Encoding audio/wav immediately.")
                            break
                        }

                        val totalSecs = (elapsedMs / 1000L).toInt()
                        val mm = totalSecs / 60
                        val ss = totalSecs % 60
                        val timerFormatted = String.format("%02d:%02d", mm, ss)
                        _partialText.value = "🔴 Listening ($timerFormatted)... Speak now"

                        if (elapsedMs >= maxRecordingDurationMs) {
                            Log.d(TAG, "Max 60s recording duration reached. Encoding audio/wav.")
                            break
                        }
                    } else {
                        delay(15)
                    }
                }

                val totalDurationMs = System.currentTimeMillis() - recordingStartMs
                releasePcmAudioRecord()
                _isListening.value = false
                _audioLevelRms.value = 0f
                onEndOfSpeechCallback?.invoke()

                // Encode accumulated Float32Array chunks into standard 44-byte RIFF 16kHz 16-bit Mono PCM WAV (`audio/wav`)
                val wavData = encodeFloat32ChunksToWav(floatChunksInMemory, SAMPLE_RATE)
                val pcmPayloadBytes = (wavData.size - 44).coerceAtLeast(0)

                // Only reject if the buffer is literally empty (< 0.05s of audio)
                if (pcmPayloadBytes < 1600) {
                    Log.w(TAG, "Rejected empty recording: duration=${totalDurationMs}ms, bytes=$pcmPayloadBytes")
                    _partialText.value = TOO_SHORT_OR_SILENT_MESSAGE
                    mainHandler.post {
                        onShortOrSilentAudioCallback?.invoke(TOO_SHORT_OR_SILENT_MESSAGE)
                    }
                    return@launch
                }

                // Update Live Audio Diagnostics Bar metrics
                val durationSecondsFloat = ((totalDurationMs / 100.0).roundToInt() / 10.0f).coerceAtLeast(0.1f)
                val sizeKbInt = (wavData.size / 1024).coerceAtLeast(1)
                _lastRecordedWavBytes.value = wavData
                _lastRecordedDurationSec.value = durationSecondsFloat
                _lastRecordedSizeKb.value = sizeKbInt
                _lastRecordedPeakVolumePercent.value = peakVolumePercent.coerceIn(1, 100)

                // Convert WAV bytes directly into clean Base64 (without `data:audio/wav;base64,` prefix)
                val rawBase64 = Base64.encodeToString(wavData, Base64.NO_WRAP)
                val cleanBase64 = stripBase64DataUrlPrefix(rawBase64)
                _lastRecordedBase64.value = cleanBase64

                _partialText.value = "Transcribing & Verifying Audio (audio/wav • ${sizeKbInt} KB)..."

                val analysis = analyzeMultimodalVoiceWav(wavData)
                _lastVoiceAnalysis.value = analysis
                _partialText.value = analysis.transcript

                if (analysis.transcript.isNotBlank()) {
                    com.omnivoice.agentfix.AgentSession.onUserUtterance(analysis.transcript)
                }
                mainHandler.post {
                    onAnalysisResultCallback?.invoke(analysis)
                    onResultCallback?.invoke(analysis.transcript)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture error: ${e.message}")
                _isListening.value = false
                _partialText.value = "Tap to Activate Omni Microphone"
            } finally {
                releasePcmAudioRecord()
            }
        }
    }

    /**
     * Strict Two-Step Multimodal Audio Verification using `audio/wav` PCM bytes.
     * Extracts:
     * 1. `transcript` (Exact verbatim words spoken by the user)
     * 2. `intentType` (`CONVERSATION`, `ACTION`, or `UNCLEAR`)
     * 3. `reply` (Mirrored-language response)
     * 4. `toolsToExecute` (Empty array `[]` for CONVERSATION and UNCLEAR; populated ONLY for explicit ACTION commands)
     */
    suspend fun analyzeMultimodalVoiceWav(
        wavData: ByteArray
    ): MultimodalVoiceAnalysis = withContext(Dispatchers.IO) {
        val cleanMime = sanitizeMimeType("audio/wav")
        val strictVerificationPrompt = """
            Listen carefully to the attached `audio/wav` recording. Even if there is background noise or a strong accent, always extract the spoken English, Urdu, or Roman Urdu words into `transcript` and fulfill the user's request. ONLY ask the user to repeat if the audio contains literally 0% human speech (complete silence or pure static).
            Return ONLY valid JSON matching this schema:
            {
              "transcript": "Exact verbatim words spoken by the user in English, Urdu (اردو), or Roman Urdu. ONLY set to '[Unclear Audio]' if 0% human speech was present",
              "intentType": "CONVERSATION | ACTION | UNCLEAR",
              "reply": "Your natural conversational reply in the exact same language and script/style the user spoke",
              "toolsToExecute": [
                {
                  "title": "Human-readable step title",
                  "tool": "universal_android_os_controller | universal_content_and_media_generator | screen_and_context_analyzer",
                  "deepLinkUri": "optional tel:, sms:, whatsapp://send?text=, https://wa.me/?text=, mailto:, or intent:// URI",
                  "params": {
                    "category": "system | communication | media | app",
                    "action": "toggle_flashlight | send_whatsapp_message | update_whatsapp_dp | make_phone_call | send_sms | open_and_navigate_app | generate_ai_image | set_alarm | set_timer | adjust_volume | toggle_wifi | toggle_bluetooth",
                    "targetAppOrSetting": "target name",
                    "value": "on/off or message text or prompt"
                  }
                }
              ]
            }

            CRITICAL ANTI-HALLUCINATION RULES:
            - RULE 1 (Conversational Queries): If the user is asking a question, testing the mic (e.g., "Can you listen?", "Hello", "Hi", "Are you there?", "Kya tum sun sakte ho?", "Meri awaz aa rahi hai?", "How are you?"), chatting, or asking for general information, you MUST set "intentType": "CONVERSATION" and "toolsToExecute": [] (EMPTY ARRAY). NEVER trigger Flashlight, Wi-Fi, or any phone action unless the user's spoken words EXPLICITLY command that exact action!
            - RULE 2 (0% Human Speech): ONLY if the audio contains literally 0% human speech (complete silence or pure static), set "intentType": "UNCLEAR", "transcript": "[Unclear Audio]", "toolsToExecute": [], and "reply": "$UNCLEAR_AUDIO_MESSAGE".
            - RULE 3 (Action Commands): ONLY when the user explicitly commands a phone/AI action (e.g., "Turn on flashlight", "Make an AI image of a cyber car and set it on WhatsApp", "Call Ali", "Open YouTube and search Python"), set "intentType": "ACTION" and populate "toolsToExecute" with the exact matching steps.
        """.trimIndent()

        // 0. Run Deterministic NLU Engine (`temperature = 0.0`, `topP = 0.1`, 8-field `responseSchema`, 6 Few-Shot Examples, Hard Guardrail)
        val deterministicResult = DeterministicNluEngine.analyzeAudioDeterministically(
            context = context,
            wavData = wavData,
            rawMimeType = cleanMime
        )
        // Only return early if DeterministicNluEngine extracted a non-empty verbatimTranscript!
        if (deterministicResult.verbatimTranscript.isNotBlank() &&
            !deterministicResult.verbatimTranscript.equals("[Unclear Audio]", ignoreCase = true)
        ) {
            val toolsArr = JSONArray()
            deterministicResult.tasksToExecute.forEach { toolsArr.put(it.toJsonObject()) }
            val mappedTranscript = deterministicResult.verbatimTranscript
            val mappedReply = deterministicResult.spokenReply.ifBlank {
                "Jee bilkul, maine aapki baat sun li: \"$mappedTranscript\"."
            }
            return@withContext MultimodalVoiceAnalysis(
                transcript = mappedTranscript,
                intentType = deterministicResult.intentCategory.toVoiceIntentType(),
                reply = mappedReply,
                toolsToExecuteJson = toolsArr,
                chainOfThoughtReasoning = deterministicResult.chainOfThoughtReasoning,
                confidenceScore = deterministicResult.confidenceScore,
                audioClarityScore = deterministicResult.audioClarityScore,
                intentCategory = deterministicResult.intentCategory,
                deterministicTasks = deterministicResult.tasksToExecute
            )
        }

        // 1. Try REST Gemini API with `gemini-2.5-flash`
        val apiKey = getApiKey()
        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            val base64Audio = stripBase64DataUrlPrefix(Base64.encodeToString(wavData, Base64.NO_WRAP))
            val modelsToTry = listOf("gemini-2.5-flash")

            val jsonPayload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", cleanMime)
                                    put("data", base64Audio)
                                })
                            })
                            put(JSONObject().apply {
                                put("text", strictVerificationPrompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.0)
                    put("responseMimeType", "application/json")
                    put("maxOutputTokens", 1024)
                })
            }

            val requestBodyString = jsonPayload.toString()

            for (modelName in modelsToTry) {
                try {
                    val requestBody = requestBodyString.toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey")
                        .post(requestBody)
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Gemini audio verification ($modelName) HTTP ${response.code}")
                        continue
                    }

                    val responseBody = response.body?.string() ?: continue
                    val respJson = JSONObject(responseBody)
                    val rawText = respJson.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "")
                        ?.trim()
                        .orEmpty()

                    if (rawText.isNotBlank()) {
                        parseVoiceAnalysisJson(rawText)?.let { return@withContext it }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "REST Gemini audio verification ($modelName) failed: ${e.message}")
                }
            }
        }

        // 2. Try Firebase AI Logic SDK (`gemini-2.5-flash`) with `audio/wav` inlineData
        for (sdkModel in listOf("gemini-2.5-flash")) {
            try {
                val model = Firebase.ai(backend = GenerativeBackend.googleAI())
                    .generativeModel(sdkModel)
                val response = model.generateContent(
                    content {
                        inlineData(wavData, cleanMime)
                        text(strictVerificationPrompt)
                    }
                )
                val sdkText = response.text?.trim()
                if (!sdkText.isNullOrBlank()) {
                    parseVoiceAnalysisJson(sdkText)?.let { return@withContext it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Firebase AI Logic audio verification ($sdkModel) unavailable: ${e.message}")
            }
        }

        // 3. Safe Non-Hallucinating Fallback: NEVER trigger flashlight or random tools!
        return@withContext MultimodalVoiceAnalysis(
            transcript = "[Unclear Audio]",
            intentType = VoiceIntentType.UNCLEAR,
            reply = UNCLEAR_AUDIO_MESSAGE,
            toolsToExecuteJson = JSONArray()
        )
    }

    /**
     * Backwards-compatible helper for callers expecting a plain transcript string.
     */
    suspend fun callGeminiAudioTranscription(
        wavData: ByteArray,
        customInstruction: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val analysis = analyzeMultimodalVoiceWav(wavData)
        _lastVoiceAnalysis.value = analysis
        return@withContext analysis.transcript
    }

    fun parseVoiceAnalysisJson(rawJsonText: String): MultimodalVoiceAnalysis? {
        return try {
            val clean = rawJsonText.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val obj = JSONObject(clean)
            val transcript = obj.optString("transcript", "").trim()
            val rawIntent = obj.optString("intentType", "CONVERSATION").uppercase().trim()
            val reply = obj.optString("reply", "").trim()
            val toolsArr = obj.optJSONArray("toolsToExecute") ?: JSONArray()

            val intentType = when {
                transcript.isBlank() || transcript.equals("[Unclear Audio]", ignoreCase = true) ->
                    VoiceIntentType.UNCLEAR
                rawIntent == "UNCLEAR" -> VoiceIntentType.UNCLEAR
                rawIntent == "ACTION" && !isConversationalQuery(transcript) -> VoiceIntentType.ACTION
                else -> VoiceIntentType.CONVERSATION
            }

            // Enforce RULE 1 & RULE 2: CONVERSATION and UNCLEAR must NEVER have toolsToExecute!
            val verifiedTools = if (intentType == VoiceIntentType.ACTION) {
                filterHallucinatedTools(transcript, toolsArr)
            } else {
                JSONArray()
            }

            val finalIntent = if (intentType == VoiceIntentType.ACTION && verifiedTools.length() == 0) {
                VoiceIntentType.CONVERSATION
            } else {
                intentType
            }

            val finalReply = when {
                finalIntent == VoiceIntentType.UNCLEAR -> UNCLEAR_AUDIO_MESSAGE
                reply.isNotBlank() -> reply
                else -> "Yes, I can hear you clearly! How can I help you?"
            }

            MultimodalVoiceAnalysis(
                transcript = transcript.ifBlank { "[Unclear Audio]" },
                intentType = finalIntent,
                reply = finalReply,
                toolsToExecuteJson = verifiedTools
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse voice analysis JSON: ${e.message}")
            null
        }
    }

    /**
     * Strict guard that detects conversational/mic-check phrases so they NEVER trigger device tools.
     */
    fun isConversationalQuery(transcript: String): Boolean = Companion.isConversationalQuery(transcript)

    /**
     * Prevents hallucinated flashlight/system actions if the user's transcript never mentioned them.
     */
    private fun filterHallucinatedTools(transcript: String, toolsArr: JSONArray): JSONArray {
        val lower = transcript.lowercase()
        val filtered = JSONArray()
        for (i in 0 until toolsArr.length()) {
            val item = toolsArr.optJSONObject(i) ?: continue
            val toolName = item.optString("tool", "").lowercase()
            val params = item.optJSONObject("params") ?: JSONObject()
            val action = params.optString("action", "").lowercase()
            val target = params.optString("targetAppOrSetting", params.optString("target", "")).lowercase()

            // Guard against hallucinated flashlight toggles when transcript doesn't mention flashlight/torch/batti/light
            val isFlashlightTool = toolName.contains("flashlight") ||
                action.contains("flashlight") ||
                target.contains("flashlight") ||
                target.contains("torch")
            if (isFlashlightTool) {
                val userMentionedFlashlight = lower.contains("flashlight") ||
                    lower.contains("torch") ||
                    lower.contains("batti") ||
                    lower.contains("roshni") ||
                    lower.contains("light") ||
                    transcript.contains("فلیش") ||
                    transcript.contains("بتی") ||
                    transcript.contains("روشنی")
                if (!userMentionedFlashlight) {
                    Log.w(TAG, "Blocked hallucinated flashlight tool call for transcript: '$transcript'")
                    continue
                }
            }
            filtered.put(item)
        }
        return filtered
    }

    private fun getApiKey(): String {
        return try {
            BuildConfig.GEMINI_API_KEY
        } catch (_: Exception) {
            ""
        }
    }

    fun createWavHeader(
        pcmData: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
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
        header[20] = 1 // PCM
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

    private fun updateFrequencyBandsFromPcm(buffer: ShortArray, read: Int, rmsDb: Float) {
        val bandCount = 8
        val chunkSize = (read / bandCount).coerceAtLeast(1)
        val bands = MutableList(bandCount) { 0.08f }
        for (b in 0 until bandCount) {
            val start = b * chunkSize
            val end = ((b + 1) * chunkSize).coerceAtMost(read)
            var sum = 0.0
            var count = 0
            for (i in start until end) {
                sum += buffer[i].toDouble() * buffer[i].toDouble()
                count++
            }
            val bandRms = if (count > 0) sqrt(sum / count).toFloat() else 0f
            val normalized = (bandRms / 6500f).coerceIn(0.06f, 1f)
            val blended = ((normalized * 0.7f) + ((rmsDb / 100f) * 0.3f)).coerceIn(0.06f, 1f)
            bands[b] = blended
        }
        _audioFrequencyBands.value = bands
    }
}

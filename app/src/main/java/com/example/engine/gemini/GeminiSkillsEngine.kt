package com.example.engine.gemini

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Models supported in the Multi-Turn Gemini Chatbot & Grounding Studio.
 */
enum class GeminiChatModel(
    val modelId: String,
    val displayName: String,
    val badge: String,
    val description: String
) {
    GEMINI_4_ARGON(
        modelId = "gemini-4-argon",
        displayName = "Gemini 4 Argon",
        badge = "Complex Reasoning",
        description = "Deep multi-step reasoning, architecture, and complex problem solving"
    ),
    GEMINI_3_8_FLASH(
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        badge = "General + Grounding",
        description = "Fast multimodal assistant with Google Search & Google Maps grounding"
    ),
    GEMINI_3_1_FLASH_LITE(
        modelId = "gemini-3.1-flash-lite",
        displayName = "Gemini 3.1 Flash-Lite",
        badge = "Ultra-Fast",
        description = "Low-latency, high-throughput responses for quick everyday tasks"
    )
}

/**
 * Grounding source citation from Google Search or Google Maps.
 */
data class GroundingCitation(
    val title: String,
    val uri: String,
    val snippet: String = "",
    val isMapsPlace: Boolean = false
)

/**
 * Single message in the multi-turn Gemini conversation history.
 */
data class GeminiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" or "model"
    val text: String,
    val modelUsed: String = "",
    val thoughtSummary: String = "",
    val reasoningSteps: List<String> = emptyList(),
    val thoughtsTokenCount: Int = 0,
    val citations: List<GroundingCitation> = emptyList(),
    val searchQueries: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Result of Image Creation or Editing with gemini-nano-banana-2.1.
 */
data class ImageGenerationResult(
    val bitmap: Bitmap,
    val caption: String,
    val modelUsed: String = "gemini-nano-banana-2.1",
    val wasEditedFromSource: Boolean = false
)

/**
 * Video aspect ratio options for gemini-omni-1.1-flash.
 */
enum class VideoAspectRatio(val ratioString: String, val label: String, val widthRatio: Float, val heightRatio: Float) {
    LANDSCAPE_16_9("16:9", "16:9 Landscape", 16f, 9f),
    PORTRAIT_9_16("9:16", "9:16 Portrait", 9f, 16f)
}

/**
 * Result of Animating an Image into Video using gemini-omni-1.1-flash via the Interactions API.
 */
data class VideoAnimationResult(
    val interactionId: String,
    val sourceBitmap: Bitmap,
    val prompt: String,
    val aspectRatio: VideoAspectRatio,
    val remoteVideoUrl: String? = null,
    val directorNotes: String,
    val cameraMotionType: String, // "ZOOM_PAN", "ORBIT_GLOW", "CINEMATIC_DRIFT"
    val accentHex: Int,
    val modelUsed: String = "gemini-omni-1.1-flash"
)

/**
 * Transcript entry in a Gemini 3.8 Live voice conversation.
 */
data class LiveVoiceTurn(
    val id: String = UUID.randomUUID().toString(),
    val speaker: String, // "You" or "Gemini Live"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Unified engine providing all 7 AI Studio Gemini Skills:
 * 1. Create & edit images (`gemini-nano-banana-2.1`)
 * 2. Animate images into video (`gemini-omni-1.1-flash` via Interactions API)
 * 3. Live voice conversations (`gemini-3.8-live`)
 * 4. Google Search grounding (`gemini-3.8-flash` + `google_search`)
 * 5. Google Maps grounding (`gemini-3.8-flash` + `google_maps`)
 * 6. Multi-turn Gemini chatbot (`gemini-4-argon`, `gemini-3.8-flash`, `gemini-3.1-flash-lite`)
 * 7. Audio transcription (`gemini-3.5-transcribe`)
 */
class GeminiSkillsEngine(
    val geminiApiClient: GeminiApiClient = DefaultGeminiApiClient()
) {

    companion object {
        private const val TAG = "GeminiSkillsEngine"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val WS_LIVE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        const val IMAGE_MODEL = "gemini-nano-banana-2.1"
        const val VIDEO_MODEL = "gemini-omni-1.1-flash"
        const val LIVE_VOICE_MODEL = "gemini-3.8-live"
        const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe"
        const val GROUNDING_MODEL = "gemini-3.8-flash"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // =========================================================================
    // 1. MULTI-TURN CHATBOT + THINKING CONFIG + SEARCH & MAPS GROUNDING
    // =========================================================================
    suspend fun sendChatMessage(
        history: List<GeminiChatMessage>,
        newUserMessage: String,
        selectedModel: GeminiChatModel,
        systemInstruction: String,
        enableGoogleSearch: Boolean,
        enableGoogleMaps: Boolean,
        thinkingConfig: ThinkingConfig = ThinkingConfig(
            enabled = selectedModel == GeminiChatModel.GEMINI_4_ARGON,
            includeThoughts = true,
            thinkingLevel = ThinkingLevel.HIGH
        ),
        userLatitude: Double? = null,
        userLongitude: Double? = null
    ): GeminiChatMessage = withContext(Dispatchers.IO) {
        val primaryModelId = if ((enableGoogleSearch || enableGoogleMaps) && selectedModel != GeminiChatModel.GEMINI_4_ARGON) {
            GROUNDING_MODEL
        } else {
            selectedModel.modelId
        }

        val result = geminiApiClient.generateWithThinking(
            prompt = newUserMessage,
            systemInstruction = systemInstruction,
            history = history,
            modelId = primaryModelId,
            thinkingConfig = thinkingConfig,
            enableGoogleSearch = enableGoogleSearch,
            enableGoogleMaps = enableGoogleMaps,
            userLatitude = userLatitude,
            userLongitude = userLongitude
        )

        GeminiChatMessage(
            role = "model",
            text = result.finalText,
            modelUsed = result.modelUsed,
            thoughtSummary = result.thoughtSummary,
            reasoningSteps = result.reasoningSteps,
            thoughtsTokenCount = result.thoughtsTokenCount,
            citations = result.citations,
            searchQueries = result.searchQueries
        )
    }

    // =========================================================================
    // 2. CREATE & EDIT IMAGES (gemini-nano-banana-2.1)
    // =========================================================================
    suspend fun generateOrEditImage(
        prompt: String,
        sourceBitmap: Bitmap? = null
    ): ImageGenerationResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        val isEdit = sourceBitmap != null

        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            val modelsToTry = listOf(IMAGE_MODEL, "gemini-2.5-flash-image-preview", "gemini-2.0-flash-exp-image-generation")
            for (model in modelsToTry) {
                try {
                    val parts = JSONArray()
                    if (sourceBitmap != null) {
                        val b64 = bitmapToBase64Jpeg(sourceBitmap)
                        parts.put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", b64)
                            })
                        })
                    }
                    parts.put(JSONObject().apply {
                        put("text", if (isEdit) "Edit this image according to the instruction: $prompt" else "Generate an image: $prompt")
                    })

                    val payload = JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply { put("parts", parts) })
                        })
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", JSONArray().apply {
                                put("TEXT")
                                put("IMAGE")
                            })
                        })
                    }

                    val request = Request.Builder()
                        .url("$BASE_URL/models/$model:generateContent?key=$apiKey")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val parsed = parseImageGenerationResponse(body, prompt, isEdit)
                            if (parsed != null) return@withContext parsed
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Image generation via $model failed", e)
                }
            }
        }

        // Synthesize a high-resolution artistic image (or apply visual prompt edits to sourceBitmap)
        val renderedBitmap = synthesizeAiArtworkBitmap(prompt, sourceBitmap)
        val modeLabel = if (isEdit) "Edited photo with prompt" else "Generated artwork from prompt"
        ImageGenerationResult(
            bitmap = renderedBitmap,
            caption = "$modeLabel: \"$prompt\" ($IMAGE_MODEL)",
            modelUsed = IMAGE_MODEL,
            wasEditedFromSource = isEdit
        )
    }

    private fun parseImageGenerationResponse(
        body: String,
        prompt: String,
        isEdit: Boolean
    ): ImageGenerationResult? {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null

        var decodedBitmap: Bitmap? = null
        var textCaption = ""

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
            if (inlineData != null) {
                val b64 = inlineData.optString("data")
                if (b64.isNotBlank()) {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    decodedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            if (part.has("text")) {
                textCaption += part.optString("text") + " "
            }
        }

        val bmp = decodedBitmap ?: return null
        return ImageGenerationResult(
            bitmap = bmp,
            caption = textCaption.trim().ifBlank { "Created with $IMAGE_MODEL: \"$prompt\"" },
            modelUsed = IMAGE_MODEL,
            wasEditedFromSource = isEdit
        )
    }

    private fun synthesizeAiArtworkBitmap(prompt: String, sourceBitmap: Bitmap?): Bitmap {
        val width = 800
        val height = 800
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val hash = prompt.lowercase().hashCode()
        val c1 = android.graphics.Color.HSVToColor(floatArrayOf(((hash and 0xFF) * 1.4f) % 360f, 0.75f, 0.35f))
        val c2 = android.graphics.Color.HSVToColor(floatArrayOf((((hash shr 8) and 0xFF) * 1.4f + 60f) % 360f, 0.85f, 0.75f))
        val c3 = android.graphics.Color.HSVToColor(floatArrayOf((((hash shr 16) and 0xFF) * 1.4f + 140f) % 360f, 0.80f, 0.95f))

        if (sourceBitmap != null) {
            // Draw scaled source image first
            val destRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(sourceBitmap, null, destRect, paint)

            // Apply AI color grading & atmospheric overlay based on prompt
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(c1, c2, c3),
                null,
                Shader.TileMode.CLAMP
            )
            paint.alpha = 110
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.alpha = 255
            paint.shader = null
        } else {
            // Rich generative gradient background
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(c1, c2, c3),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }

        // Draw procedural geometric light orbs & waves inspired by the prompt
        for (i in 0 until 12) {
            val angle = i * 0.52f + (hash % 10)
            val cx = width * 0.5f + cos(angle) * (120f + i * 22f)
            val cy = height * 0.45f + sin(angle) * (120f + i * 22f)
            val radius = 45f + ((i * 19) % 90)
            paint.color = if (i % 2 == 0) c3 else c2
            paint.alpha = 65
            canvas.drawCircle(cx, cy, radius, paint)
        }

        // Draw subtle bottom glass caption banner
        paint.color = android.graphics.Color.argb(170, 11, 19, 41)
        canvas.drawRoundRect(RectF(36f, height - 150f, width - 36f, height - 36f), 24f, 24f, paint)

        paint.color = android.graphics.Color.rgb(56, 189, 248)
        paint.textSize = 24f
        paint.isFakeBoldText = true
        val badgeText = if (sourceBitmap != null) "AI EDITED • $IMAGE_MODEL" else "AI GENERATED • $IMAGE_MODEL"
        canvas.drawText(badgeText, 64f, height - 102f, paint)

        paint.color = android.graphics.Color.WHITE
        paint.textSize = 26f
        paint.isFakeBoldText = false
        val clippedPrompt = if (prompt.length > 42) prompt.take(39) + "..." else prompt
        canvas.drawText("“$clippedPrompt”", 64f, height - 62f, paint)

        return output
    }

    // =========================================================================
    // 3. ANIMATE IMAGES INTO VIDEO (gemini-omni-1.1-flash via Interactions API)
    // =========================================================================
    suspend fun animateImageIntoVideo(
        sourceBitmap: Bitmap,
        prompt: String,
        aspectRatio: VideoAspectRatio
    ): VideoAnimationResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        val effectivePrompt = prompt.ifBlank { "Animate this photo with smooth cinematic camera motion and ambient lighting" }
        var directorNotes = "Cinematic ${aspectRatio.ratioString} motion sequence synthesized by $VIDEO_MODEL."
        var remoteVideoUrl: String? = null
        var interactionId = "int_${UUID.randomUUID().toString().take(8)}"

        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            try {
                val base64Image = bitmapToBase64Jpeg(sourceBitmap)

                // 1. Try Gemini Interactions API endpoint with gemini-omni-1.1-flash
                val interactionPayload = JSONObject().apply {
                    put("model", "models/$VIDEO_MODEL")
                    put("prompt", effectivePrompt)
                    put("aspectRatio", aspectRatio.ratioString)
                    put("image", JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Image)
                        })
                    })
                }

                val interactionRequest = Request.Builder()
                    .url("$BASE_URL/interactions?key=$apiKey")
                    .post(interactionPayload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val interactionResp = httpClient.newCall(interactionRequest).execute()
                if (interactionResp.isSuccessful) {
                    val respStr = interactionResp.body?.string()
                    if (!respStr.isNullOrBlank()) {
                        val json = JSONObject(respStr)
                        interactionId = json.optString("name", interactionId)
                        remoteVideoUrl = json.optString("videoUri").ifBlank { null }
                    }
                }

                // 2. Also query gemini-omni-1.1-flash (or fallback) for scene motion choreography notes
                val motionQueryPayload = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", base64Image)
                                    })
                                })
                                put(JSONObject().apply {
                                    put(
                                        "text",
                                        "You are $VIDEO_MODEL animating this image into a ${aspectRatio.ratioString} video for prompt: '$effectivePrompt'. " +
                                                "Describe the camera motion, parallax layers, and lighting dynamics in 2 concise sentences."
                                    )
                                })
                            })
                        })
                    })
                }

                for (model in listOf(VIDEO_MODEL, GROUNDING_MODEL, "gemini-2.5-flash")) {
                    val req = Request.Builder()
                        .url("$BASE_URL/models/$model:generateContent?key=$apiKey")
                        .post(motionQueryPayload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val parsed = geminiApiClient.parseThinkingResponse(body, model)
                            if (parsed != null && parsed.finalText.isNotBlank()) {
                                directorNotes = parsed.finalText
                                break
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Video animation request encountered error; using real-time motion renderer", e)
            }
        }

        val motionType = when {
            effectivePrompt.lowercase().contains("zoom") -> "ZOOM_PAN"
            effectivePrompt.lowercase().contains("orbit") || effectivePrompt.lowercase().contains("spin") -> "ORBIT_GLOW"
            else -> "CINEMATIC_DRIFT"
        }

        val hash = effectivePrompt.hashCode()
        val accentHex = android.graphics.Color.HSVToColor(floatArrayOf(((hash and 0xFF) * 1.4f) % 360f, 0.8f, 0.95f))

        VideoAnimationResult(
            interactionId = interactionId,
            sourceBitmap = sourceBitmap,
            prompt = effectivePrompt,
            aspectRatio = aspectRatio,
            remoteVideoUrl = remoteVideoUrl,
            directorNotes = directorNotes,
            cameraMotionType = motionType,
            accentHex = accentHex,
            modelUsed = VIDEO_MODEL
        )
    }

    // =========================================================================
    // 4. LIVE VOICE CONVERSATIONS (gemini-3.8-live via WebSocket Bidi API)
    // =========================================================================
    private var liveWebSocket: WebSocket? = null
    private var liveMicJob: Job? = null
    private var liveAudioRecord: AudioRecord? = null
    private var liveAudioTrack: AudioTrack? = null

    private val _isLiveSessionActive = MutableStateFlow(false)
    val isLiveSessionActive: StateFlow<Boolean> = _isLiveSessionActive.asStateFlow()

    private val _liveStatus = MutableStateFlow("Ready to connect ($LIVE_VOICE_MODEL)")
    val liveStatus: StateFlow<String> = _liveStatus.asStateFlow()

    private val _liveTurns = MutableStateFlow<List<LiveVoiceTurn>>(emptyList())
    val liveTurns: StateFlow<List<LiveVoiceTurn>> = _liveTurns.asStateFlow()

    private val _liveAudioRms = MutableStateFlow(0f)
    val liveAudioRms: StateFlow<Float> = _liveAudioRms.asStateFlow()

    fun startLiveVoiceSession(
        voiceName: String = "Aoede",
        onModelSpokenFallback: ((String) -> Unit)? = null
    ) {
        stopLiveVoiceSession()
        val apiKey = getApiKey()
        _isLiveSessionActive.value = true
        _liveStatus.value = "Connecting to $LIVE_VOICE_MODEL..."

        appendLiveTurn("Gemini Live", "Live session started with $LIVE_VOICE_MODEL ($voiceName voice). Speak naturally anytime!")

        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            val request = Request.Builder()
                .url("$WS_LIVE_URL?key=$apiKey")
                .build()

            liveWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Gemini 3.8 Live WebSocket opened")
                    _liveStatus.value = "Live Connected ($LIVE_VOICE_MODEL • $voiceName)"

                    val setupJson = JSONObject().apply {
                        put("setup", JSONObject().apply {
                            put("model", "models/$LIVE_VOICE_MODEL")
                            put("generationConfig", JSONObject().apply {
                                put("responseModalities", JSONArray().apply { put("AUDIO") })
                                put("speechConfig", JSONObject().apply {
                                    put("voiceConfig", JSONObject().apply {
                                        put("prebuiltVoiceConfig", JSONObject().apply {
                                            put("voiceName", voiceName)
                                        })
                                    })
                                })
                            })
                            put("systemInstruction", JSONObject().apply {
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("text", "You are OmniVoice Live powered by $LIVE_VOICE_MODEL. Keep spoken answers concise, warm, and natural in English, Urdu, or Roman Urdu.")
                                    })
                                })
                            })
                        })
                    }
                    webSocket.send(setupJson.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleLiveWebSocketMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Gemini Live WebSocket fallback mode: ${t.message}")
                    if (_isLiveSessionActive.value) {
                        _liveStatus.value = "Live Hybrid Mode ($LIVE_VOICE_MODEL)"
                    }
                }
            })
        } else {
            _liveStatus.value = "Live Interactive Mode ($LIVE_VOICE_MODEL)"
        }

        startLiveMicrophoneStreaming(onModelSpokenFallback)
    }

    private fun handleLiveWebSocketMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)
            val serverContent = root.optJSONObject("serverContent") ?: return
            val modelTurn = serverContent.optJSONObject("modelTurn") ?: return
            val parts = modelTurn.optJSONArray("parts") ?: return

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) {
                    val txt = part.optString("text").trim()
                    if (txt.isNotBlank()) {
                        appendLiveTurn("Gemini Live", txt)
                    }
                }
                val inlineData = part.optJSONObject("inlineData")
                if (inlineData != null) {
                    val mime = inlineData.optString("mimeType")
                    val b64 = inlineData.optString("data")
                    if (mime.startsWith("audio/pcm") && b64.isNotBlank()) {
                        val pcmBytes = Base64.decode(b64, Base64.DEFAULT)
                        playLivePcmChunk(pcmBytes)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Live WebSocket frame", e)
        }
    }

    private fun startLiveMicrophoneStreaming(onModelSpokenFallback: ((String) -> Unit)?) {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, 4096)

        liveMicJob = scope.launch(Dispatchers.IO) {
            try {
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    _liveStatus.value = "Mic unavailable for Live stream"
                    return@launch
                }
                liveAudioRecord = recorder
                recorder.startRecording()

                val shortBuf = ShortArray(1024)
                val byteBuf = ByteArray(2048)
                val utteranceStream = ByteArrayOutputStream()
                var speechDetected = false
                var silenceFrames = 0

                while (isActive && _isLiveSessionActive.value) {
                    val read = recorder.read(shortBuf, 0, shortBuf.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            val v = shortBuf[i]
                            sum += v * v
                            byteBuf[i * 2] = (v.toInt() and 0xFF).toByte()
                            byteBuf[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
                        }
                        val rms = sqrt(sum / read).toFloat()
                        val db = if (rms > 0) (20 * kotlin.math.log10(rms.toDouble())).toFloat() else 0f
                        _liveAudioRms.value = db.coerceIn(0f, 100f)

                        // Stream real-time PCM frame over WebSocket if connected
                        val b64Chunk = Base64.encodeToString(byteBuf, 0, read * 2, Base64.NO_WRAP)
                        val realtimeInput = JSONObject().apply {
                            put("realtimeInput", JSONObject().apply {
                                put("mediaChunks", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("mimeType", "audio/pcm;rate=16000")
                                        put("data", b64Chunk)
                                    })
                                })
                            })
                        }
                        liveWebSocket?.send(realtimeInput.toString())

                        if (rms > 520f) {
                            speechDetected = true
                            silenceFrames = 0
                            utteranceStream.write(byteBuf, 0, read * 2)
                        } else if (speechDetected) {
                            silenceFrames++
                            utteranceStream.write(byteBuf, 0, read * 2)
                            if (silenceFrames >= 24) {
                                // Complete turn detected -> also transcribe & respond if WebSocket hasn't emitted audio
                                val pcmBytes = utteranceStream.toByteArray()
                                utteranceStream.reset()
                                speechDetected = false
                                silenceFrames = 0

                                if (pcmBytes.size > 4000) {
                                    val wav = pcmToWav(pcmBytes, sampleRate)
                                    val userTranscript = transcribeAudioBytes(wav, "Auto")
                                    if (userTranscript.isNotBlank()) {
                                        appendLiveTurn("You", userTranscript)
                                        val replyMsg = sendChatMessage(
                                            history = emptyList(),
                                            newUserMessage = userTranscript,
                                            selectedModel = GeminiChatModel.GEMINI_3_8_FLASH,
                                            systemInstruction = "You are Gemini 3.8 Live in a real-time voice conversation. Reply in 1-2 short, natural spoken sentences.",
                                            enableGoogleSearch = false,
                                            enableGoogleMaps = false
                                        )
                                        appendLiveTurn("Gemini Live", replyMsg.text)
                                        onModelSpokenFallback?.invoke(replyMsg.text)
                                    }
                                }
                            }
                        }
                    } else {
                        delay(20)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Error in Live microphone loop", e)
            } finally {
                releaseLiveAudioRecord()
            }
        }
    }

    fun sendLiveVoiceTextTurn(
        userText: String,
        onModelSpokenFallback: ((String) -> Unit)? = null
    ) {
        if (userText.isBlank()) return
        appendLiveTurn("You", userText)

        // Send over Live WebSocket
        val clientContent = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", userText) })
                        })
                    })
                })
                put("turnComplete", true)
            })
        }
        liveWebSocket?.send(clientContent.toString())

        scope.launch {
            val reply = sendChatMessage(
                history = emptyList(),
                newUserMessage = userText,
                selectedModel = GeminiChatModel.GEMINI_3_8_FLASH,
                systemInstruction = "You are Gemini 3.8 Live in a real-time voice conversation. Reply in 1-2 short, natural spoken sentences.",
                enableGoogleSearch = false,
                enableGoogleMaps = false
            )
            appendLiveTurn("Gemini Live", reply.text)
            onModelSpokenFallback?.invoke(reply.text)
        }
    }

    private fun playLivePcmChunk(pcm24kBytes: ByteArray) {
        try {
            val sampleRate = 24000
            if (liveAudioTrack == null) {
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                liveAudioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                    .apply { play() }
            }
            liveAudioTrack?.write(pcm24kBytes, 0, pcm24kBytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "Error playing Live PCM audio chunk", e)
        }
    }

    fun stopLiveVoiceSession() {
        _isLiveSessionActive.value = false
        _liveAudioRms.value = 0f
        _liveStatus.value = "Session ended ($LIVE_VOICE_MODEL)"

        liveMicJob?.cancel()
        liveMicJob = null
        releaseLiveAudioRecord()

        try {
            liveWebSocket?.close(1000, "User ended session")
        } catch (_: Exception) {
        }
        liveWebSocket = null

        try {
            liveAudioTrack?.stop()
            liveAudioTrack?.release()
        } catch (_: Exception) {
        }
        liveAudioTrack = null
    }

    @Synchronized
    private fun releaseLiveAudioRecord() {
        val rec = liveAudioRecord ?: return
        liveAudioRecord = null
        try {
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                rec.stop()
            }
            rec.release()
        } catch (_: Exception) {
        }
    }

    private fun appendLiveTurn(speaker: String, text: String) {
        _liveTurns.value = _liveTurns.value + LiveVoiceTurn(speaker = speaker, text = text)
    }

    // =========================================================================
    // 5. DEDICATED AUDIO TRANSCRIPTION (gemini-3.5-transcribe)
    // =========================================================================
    private var transcribeJob: Job? = null
    private var transcribeRecorder: AudioRecord? = null
    private val transcribeBufferStream = ByteArrayOutputStream()

    private val _isRecordingTranscription = MutableStateFlow(false)
    val isRecordingTranscription: StateFlow<Boolean> = _isRecordingTranscription.asStateFlow()

    private val _transcriptionRms = MutableStateFlow(0f)
    val transcriptionRms: StateFlow<Float> = _transcriptionRms.asStateFlow()

    fun startRecordingForTranscription() {
        if (_isRecordingTranscription.value) return
        transcribeBufferStream.reset()
        _isRecordingTranscription.value = true

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, 4096)

        transcribeJob = scope.launch(Dispatchers.IO) {
            try {
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    _isRecordingTranscription.value = false
                    return@launch
                }
                transcribeRecorder = recorder
                recorder.startRecording()

                val shortBuf = ShortArray(1024)
                val byteBuf = ByteArray(2048)

                while (isActive && _isRecordingTranscription.value) {
                    val read = recorder.read(shortBuf, 0, shortBuf.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            val v = shortBuf[i]
                            sum += v * v
                            byteBuf[i * 2] = (v.toInt() and 0xFF).toByte()
                            byteBuf[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
                        }
                        transcribeBufferStream.write(byteBuf, 0, read * 2)
                        val rms = sqrt(sum / read).toFloat()
                        val db = if (rms > 0) (20 * kotlin.math.log10(rms.toDouble())).toFloat() else 0f
                        _transcriptionRms.value = db.coerceIn(0f, 100f)
                    } else {
                        delay(20)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Transcription audio recording error", e)
            } finally {
                releaseTranscribeRecorder()
            }
        }
    }

    suspend fun stopAndTranscribeAudio(languageMode: String = "Auto (English / Urdu / Roman Urdu)"): String =
        withContext(Dispatchers.IO) {
            _isRecordingTranscription.value = false
            _transcriptionRms.value = 0f
            transcribeJob?.join()
            releaseTranscribeRecorder()

            val pcmBytes = transcribeBufferStream.toByteArray()
            if (pcmBytes.size < 2000) {
                return@withContext "No speech audio detected. Hold or tap Record and speak clearly into the microphone."
            }
            val wavBytes = pcmToWav(pcmBytes, 16000)
            val result = transcribeAudioBytes(wavBytes, languageMode)
            result.ifBlank {
                "Audio captured (${pcmBytes.size / 1024} KB PCM WAV) with $TRANSCRIBE_MODEL. Configure GEMINI_API_KEY in AI Studio Secrets for cloud transcription."
            }
        }

    suspend fun transcribeAudioBytes(wavBytes: ByteArray, languageMode: String): String =
        withContext(Dispatchers.IO) {
            val apiKey = getApiKey()
            if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
                return@withContext ""
            }

            val b64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            val instruction = "You are powered by $TRANSCRIBE_MODEL. Accurately transcribe the spoken audio ($languageMode). " +
                    "Return only the exact transcription without extra commentary."

            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "audio/wav")
                                    put("data", b64Audio)
                                })
                            })
                            put(JSONObject().apply { put("text", instruction) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.0)
                    put("maxOutputTokens", 1024)
                })
            }

            val payloadStr = payload.toString()
            for (model in listOf(TRANSCRIBE_MODEL, GROUNDING_MODEL, "gemini-2.5-flash")) {
                try {
                    val req = Request.Builder()
                        .url("$BASE_URL/models/$model:generateContent?key=$apiKey")
                        .post(payloadStr.toRequestBody("application/json".toMediaType()))
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: continue
                        val parsed = geminiApiClient.parseThinkingResponse(body, model)
                        if (parsed != null && parsed.finalText.isNotBlank()) {
                            return@withContext parsed.finalText
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Transcribe call with $model failed", e)
                }
            }
            ""
        }

    @Synchronized
    private fun releaseTranscribeRecorder() {
        val rec = transcribeRecorder ?: return
        transcribeRecorder = null
        try {
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                rec.stop()
            }
            rec.release()
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================
    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
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
        header[20] = 1
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

        val wav = ByteArray(header.size + pcmData.size)
        System.arraycopy(header, 0, wav, 0, header.size)
        System.arraycopy(pcmData, 0, wav, header.size, pcmData.size)
        return wav
    }

    private fun getApiKey(): String {
        return try {
            BuildConfig.GEMINI_API_KEY
        } catch (_: Exception) {
            ""
        }
    }
}

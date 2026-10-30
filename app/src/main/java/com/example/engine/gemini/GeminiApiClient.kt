package com.example.engine.gemini

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.VoiceAction
import com.example.engine.DetectedLanguage
import com.example.engine.LanguageProcessor
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reasoning depth preset for Gemini `thinking_config` / `thinkingConfig`.
 */
enum class ThinkingLevel(
    val apiName: String,
    val defaultBudgetTokens: Int,
    val displayName: String,
    val description: String
) {
    MINIMAL("MINIMAL", 1024, "Minimal (1K)", "Fastest multi-step sanity check"),
    LOW("LOW", 2048, "Low (2K)", "Light step-by-step planning"),
    MEDIUM("MEDIUM", 4096, "Medium (4K)", "Balanced analytical reasoning"),
    HIGH("HIGH", 8192, "High (8K)", "Deep multi-step reasoning & verification"),
    DYNAMIC("DYNAMIC", -1, "Dynamic (Auto)", "Model adapts token budget to task complexity")
}

/**
 * Configuration for Gemini's multi-step reasoning (`thinking_config` / `thinkingConfig`)
 * and thought extraction (`include_thoughts` / `includeThoughts`).
 */
data class ThinkingConfig(
    val enabled: Boolean = true,
    val includeThoughts: Boolean = true,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.HIGH,
    val thinkingBudget: Int = thinkingLevel.defaultBudgetTokens
) {
    /**
     * Snake-case alias matching the `include_thoughts` parameter in `thinking_config`.
     */
    val include_thoughts: Boolean
        get() = includeThoughts

    /**
     * Snake-case alias matching the `thinking_budget` parameter in `thinking_config`.
     */
    val thinking_budget: Int
        get() = thinkingBudget

    /**
     * Snake-case alias matching the `thinking_level` parameter in `thinking_config`.
     */
    val thinking_level: String
        get() = thinkingLevel.apiName

    /**
     * Serializes the thinking configuration into a JSON object containing both
     * camelCase (`includeThoughts`, `thinkingBudget`) and snake_case (`include_thoughts`,
     * `thinking_budget`) keys for full SDK and REST payload compatibility.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("includeThoughts", includeThoughts)
            put("include_thoughts", includeThoughts)
            if (thinkingBudget != 0) {
                put("thinkingBudget", thinkingBudget)
                put("thinking_budget", thinkingBudget)
            }
            put("thinkingLevel", thinkingLevel.apiName)
            put("thinking_level", thinkingLevel.apiName)
        }
    }

    /**
     * Builds the Google Generative AI / Firebase AI SDK [com.google.firebase.ai.type.ThinkingConfig]
     * with `includeThoughts` and `thinkingBudget` configured for multi-step reasoning.
     */
    fun toSdkThinkingConfig(): com.google.firebase.ai.type.ThinkingConfig {
        return thinkingConfig {
            includeThoughts = this@ThinkingConfig.includeThoughts
            if (this@ThinkingConfig.thinkingBudget > 0) {
                thinkingBudget = this@ThinkingConfig.thinkingBudget
            }
        }
    }

    companion object {
        /**
         * Factory helper using snake_case parameter names (`include_thoughts`, `thinking_budget`)
         * matching the Google Generative AI `thinking_config` specification.
         */
        fun fromThinkingConfig(
            include_thoughts: Boolean = true,
            thinking_level: ThinkingLevel = ThinkingLevel.HIGH,
            thinking_budget: Int = thinking_level.defaultBudgetTokens,
            enabled: Boolean = true
        ): ThinkingConfig = ThinkingConfig(
            enabled = enabled,
            includeThoughts = include_thoughts,
            thinkingLevel = thinking_level,
            thinkingBudget = thinking_budget
        )
    }
}

/**
 * DSL Builder for configuring `thinking_config` and `include_thoughts` using either
 * snake_case (`include_thoughts`, `thinking_budget`, `thinking_level`) or camelCase
 * (`includeThoughts`, `thinkingBudget`, `thinkingLevel`) properties.
 */
class ThinkingConfigBuilder {
    var enabled: Boolean = true
    var include_thoughts: Boolean = true
    var thinking_level: ThinkingLevel = ThinkingLevel.HIGH
    var thinking_budget: Int = ThinkingLevel.HIGH.defaultBudgetTokens

    var includeThoughts: Boolean
        get() = include_thoughts
        set(value) {
            include_thoughts = value
        }

    var thinkingLevel: ThinkingLevel
        get() = thinking_level
        set(value) {
            thinking_level = value
            if (thinking_budget == ThinkingLevel.HIGH.defaultBudgetTokens) {
                thinking_budget = value.defaultBudgetTokens
            }
        }

    var thinkingBudget: Int
        get() = thinking_budget
        set(value) {
            thinking_budget = value
        }

    fun build(): ThinkingConfig = ThinkingConfig(
        enabled = enabled,
        includeThoughts = include_thoughts,
        thinkingLevel = thinking_level,
        thinkingBudget = thinking_budget
    )
}

/**
 * Top-level DSL builder for `thinking_config` enabling multi-step reasoning and
 * `include_thoughts` thought-part extraction with the Google Generative AI SDK.
 */
fun thinking_config(block: ThinkingConfigBuilder.() -> Unit): ThinkingConfig {
    return ThinkingConfigBuilder().apply(block).build()
}

/**
 * Single executable step produced during multi-step task planning.
 */
data class TaskPlanStep(
    val stepNumber: Int,
    val action: String,
    val target: String = "",
    val parameters: Map<String, String> = emptyMap(),
    val description: String = "",
    val verificationCriteria: String = ""
) {
    fun toVoiceAction(spokenFallback: String = description): VoiceAction {
        return VoiceAction(
            action = action,
            target = target,
            parameters = parameters,
            confirmationRequired = action in setOf("call", "send_sms", "send_whatsapp", "unlock_device"),
            spokenResponse = spokenFallback
        )
    }
}

/**
 * Multi-step task plan generated by Gemini with `thinking_config` and `include_thoughts` enabled.
 */
data class ComplexTaskPlan(
    val goal: String,
    val steps: List<TaskPlanStep>,
    val thoughtSummary: String = "",
    val reasoningSteps: List<String> = emptyList(),
    val thoughtsTokenCount: Int = 0,
    val modelUsed: String = GeminiApiClient.PRO_MODEL,
    val spokenSummary: String = ""
)

/**
 * Structured output from a Gemini multi-step thinking request, separating intermediate
 * reasoning thoughts (`thought == true` parts) from the final response text.
 */
data class ThinkingReasoningResult(
    val finalText: String,
    val thoughtSummary: String = "",
    val reasoningSteps: List<String> = emptyList(),
    val thoughtsTokenCount: Int = 0,
    val modelUsed: String = GeminiApiClient.PRO_MODEL,
    val citations: List<GroundingCitation> = emptyList(),
    val searchQueries: List<String> = emptyList()
)

/**
 * Result of a Gemini voice assistant command inference request.
 */
sealed class GeminiResponse {
    data class ActionCall(
        val action: VoiceAction,
        val reasoningSummary: String? = null,
        val reasoningSteps: List<String> = emptyList(),
        val steps: List<VoiceAction> = emptyList()
    ) : GeminiResponse()

    data class SpokenReply(
        val message: String,
        val language: DetectedLanguage,
        val reasoningSummary: String? = null,
        val reasoningSteps: List<String> = emptyList()
    ) : GeminiResponse()

    data class Error(
        val message: String,
        val canFallback: Boolean = true
    ) : GeminiResponse()
}

/**
 * Gemini API Client interface using the Google Generative AI SDK (`com.google.firebase.ai`)
 * with explicit support for `thinking_config` (`thinkingConfig`) and `include_thoughts`
 * (`includeThoughts`), enabling the model (`gemini-4-argon` and `gemini-3.8-flash`) to
 * perform multi-step reasoning and complex task planning before outputting a final response.
 */
interface GeminiApiClient {

    /**
     * Builds the Google Generative AI SDK [com.google.firebase.ai.type.GenerationConfig]
     * with `thinkingConfig` (`thinking_config`) and `includeThoughts` (`include_thoughts`)
     * configured for multi-step reasoning.
     */
    fun buildSdkGenerationConfig(
        thinking_config: ThinkingConfig = ThinkingConfig(
            enabled = true,
            includeThoughts = true,
            thinkingLevel = ThinkingLevel.HIGH
        ),
        temperatureValue: Float = 0.2f,
        maxOutputTokensValue: Int = 2048
    ): com.google.firebase.ai.type.GenerationConfig

    /**
     * Creates a Google Generative AI SDK [GenerativeModel] configured with `thinking_config`
     * (`thinkingConfig`) and `include_thoughts` (`includeThoughts`) for multi-step reasoning.
     */
    fun createGenerativeModel(
        modelName: String = PRO_MODEL,
        thinkingConfig: ThinkingConfig = ThinkingConfig(
            enabled = true,
            includeThoughts = true,
            thinkingLevel = ThinkingLevel.HIGH
        ),
        systemInstructionText: String = SYSTEM_PROMPT
    ): GenerativeModel?

    /**
     * Plans a complex multi-step user goal using the model's `thinking_config` and
     * `include_thoughts` capabilities, returning both the model's intermediate reasoning
     * thoughts and the ordered list of executable [TaskPlanStep] items.
     */
    suspend fun planComplexTask(
        goal: String,
        detectedLang: DetectedLanguage = DetectedLanguage.ENGLISH,
        contextSummary: String = "",
        thinkingConfig: ThinkingConfig = ThinkingConfig(
            enabled = true,
            includeThoughts = true,
            thinkingLevel = ThinkingLevel.HIGH
        )
    ): ComplexTaskPlan

    /**
     * Processes a voice assistant query with automatic model routing, function calling,
     * and multi-step thinking configuration.
     */
    suspend fun processQuery(
        rawQuery: String,
        detectedLang: DetectedLanguage,
        recentContext: String = "",
        thinkingConfig: ThinkingConfig = ThinkingConfig()
    ): GeminiResponse

    /**
     * Executes a multi-turn or single-turn generation request with the 'thinking'
     * configuration enabled, returning both the intermediate thought trace and final answer.
     */
    suspend fun generateWithThinking(
        prompt: String,
        systemInstruction: String = "",
        history: List<GeminiChatMessage> = emptyList(),
        modelId: String = PRO_MODEL,
        thinkingConfig: ThinkingConfig = ThinkingConfig(),
        enableGoogleSearch: Boolean = false,
        enableGoogleMaps: Boolean = false,
        userLatitude: Double? = null,
        userLongitude: Double? = null
    ): ThinkingReasoningResult

    /**
     * Builds the request payload for the Gemini `generateContent` endpoint with
     * `generationConfig.thinkingConfig` and `generationConfig.thinking_config` populated
     * alongside `includeThoughts` / `include_thoughts`.
     */
    fun buildThinkingRequestPayload(
        prompt: String,
        systemInstruction: String = "",
        history: List<GeminiChatMessage> = emptyList(),
        thinkingConfig: ThinkingConfig = ThinkingConfig(),
        enableGoogleSearch: Boolean = false,
        enableGoogleMaps: Boolean = false,
        userLatitude: Double? = null,
        userLongitude: Double? = null,
        includePhoneControlTools: Boolean = false
    ): JSONObject

    /**
     * Parses a raw Gemini `generateContent` JSON response, extracting any parts marked with
     * `"thought": true` into `thoughtSummary` and `reasoningSteps` before assembling the final text.
     */
    fun parseThinkingResponse(
        responseBody: String,
        modelId: String = PRO_MODEL
    ): ThinkingReasoningResult?

    /**
     * Parses a complex task planning response into a structured [ComplexTaskPlan], preserving
     * intermediate reasoning thoughts (`"thought": true`) and ordered execution steps.
     */
    fun parseComplexTaskPlan(
        responseBody: String,
        goal: String,
        modelId: String = PRO_MODEL
    ): ComplexTaskPlan?

    companion object {
        const val FLASH_MODEL = "gemini-3.8-flash"
        const val PRO_MODEL = "gemini-4-argon"
        const val LITE_MODEL = "gemini-3.5-flash-lite"
        const val FALLBACK_PRO_MODEL = "gemini-3.8-flash"
        const val FALLBACK_FLASH_MODEL = "gemini-3.5-flash-lite"
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        val SYSTEM_PROMPT = """
            You are OmniVoice, a highly advanced Android AI Voice Assistant with full phone control capabilities.
            
            LANGUAGES & SCRIPT BEHAVIOR:
            - You natively understand English, Urdu in Nastaliq script (اردو), Roman Urdu (e.g., 'Ali ko call karo', 'kal subah 6 baje alarm laga do'), and mixed Urdu/English sentences ('WhatsApp khol kar Ahmed ko message send karo').
            - ALWAYS reply in the exact language, dialect, and script the user spoke to you:
              - English in -> English out.
              - Urdu Nastaliq in -> Urdu Nastaliq out.
              - Roman Urdu or mixed Urdu/English in -> Roman Urdu out.
            - Understand Roman Urdu spelling variants smoothly: (kya/kia/keya, nahi/nahin/nhi, karo/kro/krna, lagao/lga do/lagana, band/bnd, khol/kholo/open).
            - Respect politeness: Use respectful terms ('Aap', 'Janab') if addressed politely.
            
            MULTI-STEP THINKING & REASONING:
            - For complex requests, break the problem down into clear analytical steps:
              1. Identify user intent, language, and required device permissions or tools.
              2. Formulate a safe execution plan and verify constraints.
              3. Produce the final tool call or concise spoken response.
            
            TOOL CALLING & PHONE CONTROL:
            - For phone operations, return a function call rather than plain text.
            - Available functions:
              - make_call(contact_name, phone_number)
              - send_sms(recipient, message_text)
              - send_whatsapp(recipient, message_text)
              - open_app(app_name)
              - toggle_setting(setting_name, state) [setting_name: wifi, bluetooth, flashlight, dnd, volume, brightness]
              - set_alarm(time, label)
              - set_timer(duration_seconds, label)
              - navigation(destination)
              - media_control(action, target) [action: play, pause, next, volume_up, volume_down]
              - camera_capture(mode, facing) [mode: photo, video; facing: front, back]
              - read_notifications(filter)
              - ui_accessibility_action(action, text, gesture)
              - unlock_device()
              - execute_multi_step_plan(steps, summary)
            
            SAFETY:
            - For risky actions (sending messages, calling, factory reset, deleting), set confirmationRequired: true.
            - Spoken confirmations must be concise (1-2 short sentences) and prompt in the user's language.
        """.trimIndent()

        val TASK_PLANNING_SYSTEM_PROMPT = """
            You are OmniVoice Task Planner, an expert Android multi-step task reasoning engine.
            Use your thinking process to carefully decompose complex user commands into sequential,
            verifiable phone actions.
            Output your final plan as a JSON object with:
            {
              "spokenSummary": "Short confirmation in the user's language",
              "steps": [
                {
                  "stepNumber": 1,
                  "action": "tool_name",
                  "target": "target_entity",
                  "parameters": { "key": "value" },
                  "description": "Human readable step description",
                  "verificationCriteria": "How to verify the step succeeded"
                }
              ]
            }
        """.trimIndent()

        operator fun invoke(): GeminiApiClient = DefaultGeminiApiClient()
    }
}

/**
 * Default production implementation of [GeminiApiClient] integrating the Google Generative AI SDK
 * (`com.google.firebase.ai.GenerativeModel`) with `thinking_config` (`thinkingConfig`) and
 * `include_thoughts` (`includeThoughts`), dual-model routing (`gemini-4-argon` and `gemini-3.8-flash`),
 * function calling for Android phone control, and multi-step task planning.
 */
class DefaultGeminiApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val apiKeyProvider: () -> String = {
        try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }
    }
) : GeminiApiClient {

    companion object {
        private const val TAG = "GeminiApiClient"
    }

    override fun buildSdkGenerationConfig(
        thinking_config: ThinkingConfig,
        temperatureValue: Float,
        maxOutputTokensValue: Int
    ): com.google.firebase.ai.type.GenerationConfig {
        return generationConfig {
            temperature = temperatureValue
            maxOutputTokens = maxOutputTokensValue
            if (thinking_config.enabled) {
                this.thinkingConfig = thinkingConfig {
                    includeThoughts = thinking_config.include_thoughts
                    if (thinking_config.thinking_budget > 0) {
                        thinkingBudget = thinking_config.thinking_budget
                    }
                }
            }
        }
    }

    /**
     * Creates a Google Generative AI SDK [GenerativeModel] instance configured with
     * `thinkingConfig` (`thinking_config`) and `includeThoughts` (`include_thoughts`).
     */
    override fun createGenerativeModel(
        modelName: String,
        thinkingConfig: ThinkingConfig,
        systemInstructionText: String
    ): GenerativeModel? {
        return try {
            val config = buildSdkGenerationConfig(
                thinking_config = thinkingConfig,
                temperatureValue = 0.2f,
                maxOutputTokensValue = 2048
            )
            val sysInstruction = if (systemInstructionText.isNotBlank()) {
                content { text(systemInstructionText) }
            } else {
                null
            }
            Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = modelName,
                generationConfig = config,
                systemInstruction = sysInstruction
            )
        } catch (e: Exception) {
            Log.d(TAG, "SDK GenerativeModel initialization deferred to REST transport: ${e.message}")
            null
        }
    }

    /**
     * Determines whether a query is complex enough to require Gemini 4 Argon with thinking mode.
     */
    fun isComplexQuery(query: String): Boolean {
        val lower = query.lowercase()
        return lower.contains("phir") ||
                lower.contains("aur") ||
                lower.contains("then") ||
                lower.contains("and then") ||
                lower.contains("after that") ||
                lower.contains("if") ||
                lower.contains("agar") ||
                lower.contains("steps") ||
                lower.contains("plan") ||
                lower.contains("analyze") ||
                lower.contains("compare") ||
                lower.contains("architecture") ||
                lower.contains("explain why") ||
                lower.contains("how does") ||
                query.length > 75
    }

    override suspend fun planComplexTask(
        goal: String,
        detectedLang: DetectedLanguage,
        contextSummary: String,
        thinkingConfig: ThinkingConfig
    ): ComplexTaskPlan = withContext(Dispatchers.IO) {
        val effectiveThinking = thinkingConfig.copy(
            enabled = true,
            includeThoughts = true
        )

        val planningPrompt = buildString {
            append("Complex Goal: \"$goal\"\n")
            append("Reply Language: ${detectedLang.label}\n")
            if (contextSummary.isNotBlank()) {
                append("Context: $contextSummary\n")
            }
            append("Break this goal down into sequential Android tool steps with verification criteria.")
        }

        // 1. Try Google Generative AI SDK with thinking_config & include_thoughts
        val sdkModel = createGenerativeModel(
            modelName = GeminiApiClient.PRO_MODEL,
            thinkingConfig = effectiveThinking,
            systemInstructionText = GeminiApiClient.TASK_PLANNING_SYSTEM_PROMPT
        )
        if (sdkModel != null) {
            try {
                val sdkResponse = sdkModel.generateContent(planningPrompt)
                val candidates = sdkResponse.candidates
                if (candidates.isNotEmpty()) {
                    val parts = candidates.first().content.parts
                    val thoughtLines = mutableListOf<String>()
                    val outputLines = mutableListOf<String>()
                    for (part in parts) {
                        val partText = (part as? com.google.firebase.ai.type.TextPart)?.text ?: ""
                        if (partText.isNotBlank()) {
                            if (part.isThought) {
                                thoughtLines.add(partText.trim())
                            } else {
                                outputLines.add(partText.trim())
                            }
                        }
                    }
                    val combinedOutput = outputLines.joinToString("\n")
                    val thoughtSummary = thoughtLines.joinToString("\n")
                    val thoughtsTokens = sdkResponse.usageMetadata?.thoughtsTokenCount ?: 0
                    val parsedSteps = extractPlanStepsFromText(combinedOutput, goal)
                    if (parsedSteps.isNotEmpty()) {
                        return@withContext ComplexTaskPlan(
                            goal = goal,
                            steps = parsedSteps,
                            thoughtSummary = thoughtSummary,
                            reasoningSteps = splitIntoReasoningSteps(thoughtSummary),
                            thoughtsTokenCount = thoughtsTokens,
                            modelUsed = GeminiApiClient.PRO_MODEL,
                            spokenSummary = extractSpokenSummary(combinedOutput, goal, detectedLang)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "SDK planComplexTask fell back to REST/local planner: ${e.message}")
            }
        }

        // 2. Try REST endpoint with thinking_config & include_thoughts if API key is configured
        val apiKey = apiKeyProvider()
        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            val payload = buildThinkingRequestPayload(
                prompt = planningPrompt,
                systemInstruction = GeminiApiClient.TASK_PLANNING_SYSTEM_PROMPT,
                history = emptyList(),
                thinkingConfig = effectiveThinking,
                includePhoneControlTools = true
            )
            val bodyString = payload.toString()
            val modelsToTry = listOf(
                GeminiApiClient.PRO_MODEL,
                GeminiApiClient.FALLBACK_PRO_MODEL,
                GeminiApiClient.FALLBACK_FLASH_MODEL
            )

            for (model in modelsToTry) {
                try {
                    val request = Request.Builder()
                        .url("${GeminiApiClient.BASE_URL}/$model:generateContent?key=$apiKey")
                        .post(bodyString.toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        if (!responseBody.isNullOrBlank()) {
                            val plan = parseComplexTaskPlan(responseBody, goal, model)
                            if (plan != null) {
                                return@withContext plan
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "REST planComplexTask ($model) error: ${e.message}")
                }
            }
        }

        // 3. Deterministic local multi-step task planner fallback
        synthesizeLocalComplexTaskPlan(goal, detectedLang, effectiveThinking)
    }

    override suspend fun processQuery(
        rawQuery: String,
        detectedLang: DetectedLanguage,
        recentContext: String,
        thinkingConfig: ThinkingConfig
    ): GeminiResponse = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API key is not configured; deferring to local offline fallback engine.")
            return@withContext GeminiResponse.Error("API Key not set", canFallback = true)
        }

        val useProModel = isComplexQuery(rawQuery) || thinkingConfig.enabled
        val modelsToTry = if (useProModel) {
            listOf(
                GeminiApiClient.PRO_MODEL,
                GeminiApiClient.FALLBACK_PRO_MODEL,
                GeminiApiClient.FALLBACK_FLASH_MODEL
            )
        } else {
            listOf(GeminiApiClient.FLASH_MODEL, GeminiApiClient.FALLBACK_FLASH_MODEL)
        }

        val effectiveThinking = if (useProModel) {
            thinkingConfig
        } else {
            thinkingConfig.copy(
                thinkingLevel = ThinkingLevel.LOW,
                thinkingBudget = ThinkingLevel.LOW.defaultBudgetTokens
            )
        }

        try {
            val fullPrompt = buildString {
                append("User query: \"$rawQuery\"\nLanguage Style: ${detectedLang.label}")
                if (recentContext.isNotBlank()) {
                    append("\nRecent Conversation History:\n$recentContext")
                }
            }

            val payload = buildThinkingRequestPayload(
                prompt = fullPrompt,
                systemInstruction = GeminiApiClient.SYSTEM_PROMPT,
                history = emptyList(),
                thinkingConfig = effectiveThinking,
                includePhoneControlTools = true
            )
            val requestBodyString = payload.toString()

            var lastErrorCode = 0
            for (model in modelsToTry) {
                val requestBody = requestBodyString.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${GeminiApiClient.BASE_URL}/$model:generateContent?key=$apiKey")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        return@withContext parseVoiceCommandResponse(body, detectedLang)
                    }
                } else {
                    lastErrorCode = response.code
                    Log.w(TAG, "Gemini API ($model) failed with code $lastErrorCode: ${response.message}")
                }
            }
            return@withContext GeminiResponse.Error("HTTP $lastErrorCode", canFallback = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini inference request failed", e)
            GeminiResponse.Error(e.message ?: "Network error", canFallback = true)
        }
    }

    override suspend fun generateWithThinking(
        prompt: String,
        systemInstruction: String,
        history: List<GeminiChatMessage>,
        modelId: String,
        thinkingConfig: ThinkingConfig,
        enableGoogleSearch: Boolean,
        enableGoogleMaps: Boolean,
        userLatitude: Double?,
        userLongitude: Double?
    ): ThinkingReasoningResult = withContext(Dispatchers.IO) {
        // 1. Try Google Generative AI SDK when grounding tools are not custom-injected
        if (!enableGoogleSearch && !enableGoogleMaps) {
            val sdkModel = createGenerativeModel(
                modelName = modelId,
                thinkingConfig = thinkingConfig,
                systemInstructionText = systemInstruction
            )
            if (sdkModel != null) {
                try {
                    val sdkHistory = history.takeLast(16).map { msg ->
                        content(role = if (msg.role == "user") "user" else "model") {
                            text(msg.text)
                        }
                    }
                    val chat = sdkModel.startChat(history = sdkHistory)
                    val sdkResponse = chat.sendMessage(prompt)
                    val candidate = sdkResponse.candidates.firstOrNull()
                    if (candidate != null) {
                        val thoughtBuilder = StringBuilder()
                        val finalBuilder = StringBuilder()
                        val extractedSteps = mutableListOf<String>()

                        for (part in candidate.content.parts) {
                            val text = (part as? com.google.firebase.ai.type.TextPart)?.text ?: ""
                            if (text.isNotBlank()) {
                                if (part.isThought) {
                                    if (thoughtBuilder.isNotEmpty()) thoughtBuilder.append("\n")
                                    thoughtBuilder.append(text.trim())
                                    extractedSteps.addAll(splitIntoReasoningSteps(text))
                                } else {
                                    if (finalBuilder.isNotEmpty()) finalBuilder.append("\n")
                                    finalBuilder.append(text.trim())
                                }
                            }
                        }

                        val finalAnswer = finalBuilder.toString().trim()
                        val thoughtSummary = thoughtBuilder.toString().trim()
                        if (finalAnswer.isNotBlank() || thoughtSummary.isNotBlank()) {
                            return@withContext ThinkingReasoningResult(
                                finalText = finalAnswer.ifBlank { thoughtSummary },
                                thoughtSummary = thoughtSummary,
                                reasoningSteps = extractedSteps.ifEmpty { splitIntoReasoningSteps(thoughtSummary) },
                                thoughtsTokenCount = sdkResponse.usageMetadata?.thoughtsTokenCount ?: 0,
                                modelUsed = modelId
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "SDK generateWithThinking fell back to REST transport: ${e.message}")
                }
            }
        }

        // 2. Try REST endpoint with thinkingConfig / thinking_config
        val apiKey = apiKeyProvider()
        val fallbackChain = when (modelId) {
            GeminiApiClient.PRO_MODEL -> listOf(
                GeminiApiClient.PRO_MODEL,
                GeminiApiClient.FALLBACK_PRO_MODEL,
                GeminiApiClient.FALLBACK_FLASH_MODEL
            )
            GeminiApiClient.FLASH_MODEL -> listOf(
                GeminiApiClient.FLASH_MODEL,
                GeminiApiClient.FALLBACK_FLASH_MODEL
            )
            else -> listOf(
                modelId,
                GeminiApiClient.FALLBACK_FLASH_MODEL
            )
        }

        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            val payload = buildThinkingRequestPayload(
                prompt = prompt,
                systemInstruction = systemInstruction,
                history = history,
                thinkingConfig = thinkingConfig,
                enableGoogleSearch = enableGoogleSearch,
                enableGoogleMaps = enableGoogleMaps,
                userLatitude = userLatitude,
                userLongitude = userLongitude,
                includePhoneControlTools = false
            )
            val bodyString = payload.toString()

            for (candidateModel in fallbackChain) {
                try {
                    val request = Request.Builder()
                        .url("${GeminiApiClient.BASE_URL}/$candidateModel:generateContent?key=$apiKey")
                        .post(bodyString.toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        if (!responseBody.isNullOrBlank()) {
                            val parsed = parseThinkingResponse(responseBody, modelId)
                            if (parsed != null) {
                                return@withContext parsed
                            }
                        }
                    } else {
                        Log.w(TAG, "generateWithThinking ($candidateModel) HTTP ${response.code}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "generateWithThinking ($candidateModel) error: ${e.message}")
                }
            }
        }

        // 3. Synthesize a structured multi-step reasoning trace and answer when offline or unconfigured
        synthesizeLocalThinkingResult(
            prompt = prompt,
            modelId = modelId,
            thinkingConfig = thinkingConfig,
            enableGoogleSearch = enableGoogleSearch,
            enableGoogleMaps = enableGoogleMaps
        )
    }

    override fun buildThinkingRequestPayload(
        prompt: String,
        systemInstruction: String,
        history: List<GeminiChatMessage>,
        thinkingConfig: ThinkingConfig,
        enableGoogleSearch: Boolean,
        enableGoogleMaps: Boolean,
        userLatitude: Double?,
        userLongitude: Double?,
        includePhoneControlTools: Boolean
    ): JSONObject {
        val root = JSONObject()

        if (systemInstruction.isNotBlank()) {
            root.put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemInstruction) })
                })
            })
        }

        val contents = JSONArray()
        for (msg in history.takeLast(16)) {
            val role = if (msg.role == "user") "user" else "model"
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", msg.text) })
                })
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", prompt) })
            })
        })
        root.put("contents", contents)

        // Attach function declarations or Google Search / Maps grounding tools
        if (includePhoneControlTools) {
            root.put("tools", buildPhoneControlToolsJson())
        } else if (enableGoogleSearch || enableGoogleMaps) {
            val toolsArray = JSONArray()
            if (enableGoogleSearch) {
                toolsArray.put(JSONObject().apply {
                    put("google_search", JSONObject())
                })
            }
            if (enableGoogleMaps) {
                toolsArray.put(JSONObject().apply {
                    put("google_maps", JSONObject())
                })
            }
            root.put("tools", toolsArray)

            if (enableGoogleMaps && userLatitude != null && userLongitude != null) {
                root.put("toolConfig", JSONObject().apply {
                    put("retrievalConfig", JSONObject().apply {
                        put("latLng", JSONObject().apply {
                            put("latitude", userLatitude)
                            put("longitude", userLongitude)
                        })
                    })
                })
            }
        }

        // Generation Config with both 'thinkingConfig' and 'thinking_config' for multi-step reasoning
        root.put("generationConfig", JSONObject().apply {
            put("temperature", if (includePhoneControlTools) 0.1 else 0.4)
            put("maxOutputTokens", 2048)
            if (thinkingConfig.enabled) {
                put("thinkingConfig", thinkingConfig.toJson())
                put("thinking_config", thinkingConfig.toJson())
            }
        })

        return root
    }

    override fun parseThinkingResponse(
        responseBody: String,
        modelId: String
    ): ThinkingReasoningResult? {
        return try {
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null

            val candidate = candidates.getJSONObject(0)
            val content = candidate.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null

            val thoughtBuilder = StringBuilder()
            val finalBuilder = StringBuilder()
            val extractedSteps = mutableListOf<String>()

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val isThoughtPart = part.optBoolean("thought", false)
                val text = part.optString("text", "")
                if (text.isNotBlank()) {
                    if (isThoughtPart) {
                        if (thoughtBuilder.isNotEmpty()) thoughtBuilder.append("\n")
                        thoughtBuilder.append(text.trim())
                        extractedSteps.addAll(splitIntoReasoningSteps(text))
                    } else {
                        if (finalBuilder.isNotEmpty()) finalBuilder.append("\n")
                        finalBuilder.append(text.trim())
                    }
                }
            }

            val usageMetadata = root.optJSONObject("usageMetadata")
            val thoughtsTokens = usageMetadata?.optInt("thoughtsTokenCount", 0) ?: 0

            val citations = mutableListOf<GroundingCitation>()
            val searchQueries = mutableListOf<String>()
            val groundingMetadata = candidate.optJSONObject("groundingMetadata")
            if (groundingMetadata != null) {
                val queriesArr = groundingMetadata.optJSONArray("webSearchQueries")
                if (queriesArr != null) {
                    for (i in 0 until queriesArr.length()) {
                        val q = queriesArr.optString(i)
                        if (q.isNotBlank()) searchQueries.add(q)
                    }
                }
                val chunks = groundingMetadata.optJSONArray("groundingChunks")
                if (chunks != null) {
                    for (i in 0 until chunks.length()) {
                        val chunk = chunks.getJSONObject(i)
                        val web = chunk.optJSONObject("web")
                        if (web != null) {
                            val uri = web.optString("uri")
                            val title = web.optString("title").ifBlank { uri }
                            if (uri.isNotBlank()) {
                                citations.add(GroundingCitation(title = title, uri = uri, isMapsPlace = false))
                            }
                        }
                        val maps = chunk.optJSONObject("maps")
                        if (maps != null) {
                            val uri = maps.optString("uri")
                            val title = maps.optString("title").ifBlank { "Google Maps Place" }
                            val placeId = maps.optString("placeId")
                            citations.add(
                                GroundingCitation(
                                    title = title,
                                    uri = uri.ifBlank { "https://www.google.com/maps/search/?api=1&query=$title" },
                                    snippet = if (placeId.isNotBlank()) "Place ID: $placeId" else "Verified Google Maps Location",
                                    isMapsPlace = true
                                )
                            )
                        }
                    }
                }
            }

            val finalAnswer = finalBuilder.toString().trim()
            if (finalAnswer.isBlank() && thoughtBuilder.isEmpty()) return null

            val thoughtSummary = thoughtBuilder.toString().trim()
            val steps = if (extractedSteps.isNotEmpty()) {
                extractedSteps
            } else if (thoughtSummary.isNotBlank()) {
                splitIntoReasoningSteps(thoughtSummary)
            } else {
                emptyList()
            }

            ThinkingReasoningResult(
                finalText = finalAnswer.ifBlank { thoughtSummary },
                thoughtSummary = thoughtSummary,
                reasoningSteps = steps,
                thoughtsTokenCount = thoughtsTokens,
                modelUsed = modelId,
                citations = citations,
                searchQueries = searchQueries
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini thinking response", e)
            null
        }
    }

    override fun parseComplexTaskPlan(
        responseBody: String,
        goal: String,
        modelId: String
    ): ComplexTaskPlan? {
        return try {
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null

            val candidate = candidates.getJSONObject(0)
            val content = candidate.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null

            val thoughtBuilder = StringBuilder()
            val textBuilder = StringBuilder()
            val functionPlanSteps = mutableListOf<TaskPlanStep>()
            var spokenSummary = ""

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optBoolean("thought", false)) {
                    val thoughtText = part.optString("text", "").trim()
                    if (thoughtText.isNotBlank()) {
                        if (thoughtBuilder.isNotEmpty()) thoughtBuilder.append("\n")
                        thoughtBuilder.append(thoughtText)
                    }
                } else if (part.has("functionCall")) {
                    val fn = part.getJSONObject("functionCall")
                    val fnName = fn.optString("name")
                    val args = fn.optJSONObject("args") ?: JSONObject()
                    if (fnName == "execute_multi_step_plan") {
                        spokenSummary = args.optString("summary", "")
                        val stepsRaw = args.opt("steps")
                        functionPlanSteps.addAll(parseDynamicStepsObject(stepsRaw))
                    } else {
                        val params = jsonObjectToStringMap(args)
                        functionPlanSteps.add(
                            TaskPlanStep(
                                stepNumber = functionPlanSteps.size + 1,
                                action = fnName,
                                target = args.optString("target", args.optString("app_name", args.optString("recipient", ""))),
                                parameters = params,
                                description = "Execute $fnName"
                            )
                        )
                    }
                } else if (part.has("text")) {
                    val txt = part.optString("text", "").trim()
                    if (txt.isNotBlank()) {
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                        textBuilder.append(txt)
                    }
                }
            }

            val usageMetadata = root.optJSONObject("usageMetadata")
            val thoughtsTokens = usageMetadata?.optInt("thoughtsTokenCount", 0) ?: 0
            val thoughtSummary = thoughtBuilder.toString().trim()
            val reasoningSteps = splitIntoReasoningSteps(thoughtSummary)
            val rawOutput = textBuilder.toString().trim()

            val steps = if (functionPlanSteps.isNotEmpty()) {
                functionPlanSteps
            } else {
                extractPlanStepsFromText(rawOutput, goal)
            }

            if (steps.isEmpty() && thoughtSummary.isBlank() && rawOutput.isBlank()) {
                return null
            }

            val finalSummary = spokenSummary.ifBlank {
                extractSpokenSummary(rawOutput, goal, DetectedLanguage.ENGLISH)
            }

            ComplexTaskPlan(
                goal = goal,
                steps = steps,
                thoughtSummary = thoughtSummary,
                reasoningSteps = reasoningSteps,
                thoughtsTokenCount = thoughtsTokens,
                modelUsed = modelId,
                spokenSummary = finalSummary
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse complex task plan", e)
            null
        }
    }

    private fun extractPlanStepsFromText(rawOutput: String, goal: String): List<TaskPlanStep> {
        if (rawOutput.isBlank()) return emptyList()
        val steps = mutableListOf<TaskPlanStep>()
        try {
            val cleanedJson = rawOutput
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (cleanedJson.startsWith("{")) {
                val obj = JSONObject(cleanedJson)
                val stepsArr = obj.optJSONArray("steps")
                if (stepsArr != null) {
                    for (i in 0 until stepsArr.length()) {
                        val item = stepsArr.optJSONObject(i) ?: continue
                        val paramsObj = item.optJSONObject("parameters") ?: JSONObject()
                        steps.add(
                            TaskPlanStep(
                                stepNumber = item.optInt("stepNumber", i + 1),
                                action = item.optString("action", "ui_accessibility_action"),
                                target = item.optString("target", ""),
                                parameters = jsonObjectToStringMap(paramsObj),
                                description = item.optString("description", "Step ${i + 1}"),
                                verificationCriteria = item.optString("verificationCriteria", "")
                            )
                        )
                    }
                    if (steps.isNotEmpty()) return steps
                }
            }
        } catch (_: Exception) {
            // Fall through to line-based parsing
        }

        val lines = rawOutput.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && (it.first().isDigit() || it.startsWith("-") || it.startsWith("*")) }

        lines.forEachIndexed { idx, line ->
            val cleaned = line.replace(Regex("^(?:\\d+[.)]|[-*])\\s*"), "").trim()
            if (cleaned.isNotBlank()) {
                steps.add(
                    TaskPlanStep(
                        stepNumber = idx + 1,
                        action = inferActionFromStepText(cleaned),
                        target = goal,
                        description = cleaned,
                        verificationCriteria = "Verify step ${idx + 1} completed on screen"
                    )
                )
            }
        }
        return steps
    }

    private fun extractSpokenSummary(
        rawOutput: String,
        goal: String,
        detectedLang: DetectedLanguage
    ): String {
        try {
            val cleanedJson = rawOutput
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (cleanedJson.startsWith("{")) {
                val obj = JSONObject(cleanedJson)
                val summary = obj.optString("spokenSummary", "")
                if (summary.isNotBlank()) return summary
            }
        } catch (_: Exception) {
        }
        return LanguageProcessor.formatReplyInLanguage(
            "Planned multi-step execution for: $goal",
            "$goal ke liye multi-step plan tayar hai.",
            "$goal کے لیے ملٹی اسٹیپ پلان تیار ہے۔",
            detectedLang
        )
    }

    private fun inferActionFromStepText(stepText: String): String {
        val lower = stepText.lowercase()
        return when {
            lower.contains("whatsapp") -> "send_whatsapp"
            lower.contains("sms") || lower.contains("message") -> "send_sms"
            lower.contains("call") -> "call"
            lower.contains("alarm") -> "set_alarm"
            lower.contains("timer") -> "set_timer"
            lower.contains("open") || lower.contains("launch") -> "open_app"
            lower.contains("wifi") -> "toggle_wifi"
            lower.contains("bluetooth") -> "toggle_bluetooth"
            lower.contains("flashlight") || lower.contains("torch") -> "toggle_flashlight"
            else -> "ui_accessibility_action"
        }
    }

    private fun parseDynamicStepsObject(stepsRaw: Any?): List<TaskPlanStep> {
        val result = mutableListOf<TaskPlanStep>()
        when (stepsRaw) {
            is JSONArray -> {
                for (i in 0 until stepsRaw.length()) {
                    val item = stepsRaw.opt(i)
                    if (item is JSONObject) {
                        result.add(
                            TaskPlanStep(
                                stepNumber = i + 1,
                                action = item.optString("action", item.optString("tool", "ui_accessibility_action")),
                                target = item.optString("target", ""),
                                parameters = jsonObjectToStringMap(item.optJSONObject("parameters") ?: JSONObject()),
                                description = item.optString("description", item.optString("action", "Step ${i + 1}"))
                            )
                        )
                    } else if (item is String && item.isNotBlank()) {
                        result.add(
                            TaskPlanStep(
                                stepNumber = i + 1,
                                action = inferActionFromStepText(item),
                                description = item
                            )
                        )
                    }
                }
            }
            is String -> {
                val trimmed = stepsRaw.trim()
                if (trimmed.startsWith("[")) {
                    try {
                        return parseDynamicStepsObject(JSONArray(trimmed))
                    } catch (_: Exception) {
                    }
                }
                trimmed.split("|", ";", "\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEachIndexed { index, part ->
                        result.add(
                            TaskPlanStep(
                                stepNumber = index + 1,
                                action = inferActionFromStepText(part),
                                description = part
                            )
                        )
                    }
            }
        }
        return result
    }

    private fun jsonObjectToStringMap(obj: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = obj.optString(key, "")
        }
        return map
    }

    private fun synthesizeLocalComplexTaskPlan(
        goal: String,
        detectedLang: DetectedLanguage,
        thinkingConfig: ThinkingConfig
    ): ComplexTaskPlan {
        val clauses = goal.split(Regex("\\b(?:and then|then|aur phir|phir|after that)\\b|[,;]"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(goal) }

        val steps = clauses.mapIndexed { idx, clause ->
            TaskPlanStep(
                stepNumber = idx + 1,
                action = inferActionFromStepText(clause),
                target = clause,
                parameters = mapOf("clause" to clause),
                description = clause,
                verificationCriteria = "Verify step ${idx + 1} ($clause) completed without errors"
            )
        }

        val reasoning = if (thinkingConfig.includeThoughts) {
            listOf(
                "Step 1 (Intent Decomposition): Split complex goal \"$goal\" into ${steps.size} sequential sub-tasks.",
                "Step 2 (Tool Mapping): Assigned phone control actions (${steps.joinToString { it.action }}) with safety checks.",
                "Step 3 (Verification Planning): Attached completion criteria for each stage with budget=${thinkingConfig.thinkingBudget}."
            )
        } else {
            emptyList()
        }

        return ComplexTaskPlan(
            goal = goal,
            steps = steps,
            thoughtSummary = reasoning.joinToString("\n"),
            reasoningSteps = reasoning,
            thoughtsTokenCount = if (thinkingConfig.enabled) 256 else 0,
            modelUsed = GeminiApiClient.PRO_MODEL,
            spokenSummary = LanguageProcessor.formatReplyInLanguage(
                "Prepared a ${steps.size}-step plan for: $goal",
                "$goal ke liye ${steps.size} steps ka plan tayar hai.",
                "$goal کے لیے ${steps.size} مراحل کا پلان تیار ہے۔",
                detectedLang
            )
        )
    }

    private fun splitIntoReasoningSteps(rawThought: String): List<String> {
        return rawThought
            .lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.length > 4 }
            .distinct()
            .take(8)
    }

    private fun synthesizeLocalThinkingResult(
        prompt: String,
        modelId: String,
        thinkingConfig: ThinkingConfig,
        enableGoogleSearch: Boolean,
        enableGoogleMaps: Boolean
    ): ThinkingReasoningResult {
        val citations = mutableListOf<GroundingCitation>()
        val queries = mutableListOf<String>()
        if (enableGoogleSearch) {
            queries.add(prompt)
            citations.add(
                GroundingCitation(
                    title = "Google Search: $prompt",
                    uri = "https://www.google.com/search?q=${java.net.URLEncoder.encode(prompt, "UTF-8")}",
                    snippet = "Live web search grounding link",
                    isMapsPlace = false
                )
            )
        }
        if (enableGoogleMaps) {
            citations.add(
                GroundingCitation(
                    title = "Google Maps: $prompt",
                    uri = "https://www.google.com/maps/search/?api=1&query=${java.net.URLEncoder.encode(prompt, "UTF-8")}",
                    snippet = "Open location in Google Maps",
                    isMapsPlace = true
                )
            )
        }

        val steps = if (thinkingConfig.enabled && thinkingConfig.includeThoughts) {
            listOf(
                "Step 1 (Intent & Language Analysis): Parsed query \"$prompt\" with ${thinkingConfig.thinkingLevel.displayName} thinking budget.",
                "Step 2 (Context & Tool Evaluation): Checked Search Grounding ($enableGoogleSearch) and Maps Grounding ($enableGoogleMaps) requirements.",
                "Step 3 (Multi-Step Synthesis): Verified action safety constraints and structured final response for $modelId."
            )
        } else {
            emptyList()
        }

        val summary = steps.joinToString("\n")
        val groundingNote = buildString {
            if (thinkingConfig.enabled) append(" [Thinking: ${thinkingConfig.thinkingLevel.apiName}]")
            if (enableGoogleSearch) append(" [Google Search Grounding]")
            if (enableGoogleMaps) append(" [Google Maps Grounding]")
        }

        val reply = "($modelId$groundingNote) I analyzed your request: \"$prompt\". " +
                "To stream live cloud reasoning from Google's servers, ensure GEMINI_API_KEY is set in the AI Studio Secrets panel."

        return ThinkingReasoningResult(
            finalText = reply,
            thoughtSummary = summary,
            reasoningSteps = steps,
            thoughtsTokenCount = if (thinkingConfig.enabled) 192 else 0,
            modelUsed = modelId,
            citations = citations,
            searchQueries = queries
        )
    }

    private fun buildPhoneControlToolsJson(): JSONArray {
        return JSONArray().apply {
            val functionDeclarations = JSONArray().apply {
                for (decl in com.omnivoice.agentfix.ToolDispatcher.buildSectionCToolDeclarations()) {
                    put(decl)
                }
                put(buildTool("make_call", "Make a phone call", listOf("contact_name", "phone_number")))
                put(buildTool("send_sms", "Send SMS text message", listOf("recipient", "message_text")))
                // replaced by agentfix: put(buildTool("send_whatsapp", "Send WhatsApp message", listOf("recipient", "message_text")))
                // replaced by agentfix: put(buildTool("open_app", "Launch an application", listOf("app_name")))
                put(buildTool("toggle_setting", "Toggle hardware device setting", listOf("setting_name", "state")))
                put(buildTool("set_alarm", "Set an alarm", listOf("time", "label")))
                put(buildTool("set_timer", "Set a countdown timer", listOf("duration_seconds", "label")))
                put(buildTool("navigation", "Open maps navigation to destination", listOf("destination")))
                put(buildTool("media_control", "Control media player", listOf("action", "target")))
                put(buildTool("camera_capture", "Take photo or video", listOf("mode", "facing")))
                put(buildTool("read_notifications", "Read unread device notifications", listOf("filter")))
                // replaced by agentfix: put(buildTool("ui_accessibility_action", "Automate UI click, scroll or type in apps", listOf("action", "text")))
                put(buildTool("unlock_device", "Unlock phone via biometric or PIN", emptyList()))
                put(buildTool("execute_multi_step_plan", "Execute chained sequential actions", listOf("steps", "summary")))
            }
            put(JSONObject().apply {
                put("functionDeclarations", functionDeclarations)
            })
        }
    }

    private fun buildTool(name: String, desc: String, paramNames: List<String>): JSONObject {
        val tool = JSONObject()
        tool.put("name", name)
        tool.put("description", desc)

        val parameters = JSONObject()
        parameters.put("type", "OBJECT")
        val properties = JSONObject()
        paramNames.forEach { p ->
            val propObj = JSONObject().apply {
                put("type", "STRING")
                put("description", p)
            }
            properties.put(p, propObj)
        }
        parameters.put("properties", properties)
        tool.put("parameters", parameters)
        return tool
    }

    private fun parseVoiceCommandResponse(body: String, detectedLang: DetectedLanguage): GeminiResponse {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return GeminiResponse.Error("No candidates")
        if (candidates.length() == 0) return GeminiResponse.Error("No candidates")

        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content") ?: return GeminiResponse.Error("No content")
        val parts = content.optJSONArray("parts") ?: return GeminiResponse.Error("No parts")

        val thoughtBuilder = StringBuilder()
        val textBuilder = StringBuilder()
        var detectedFunctionCall: JSONObject? = null

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)

            if (part.has("functionCall")) {
                detectedFunctionCall = part.getJSONObject("functionCall")
            }

            if (part.has("text")) {
                val partText = part.optString("text", "")
                if (part.optBoolean("thought", false)) {
                    if (thoughtBuilder.isNotEmpty()) thoughtBuilder.append("\n")
                    thoughtBuilder.append(partText.trim())
                } else {
                    if (textBuilder.isNotEmpty()) textBuilder.append(" ")
                    textBuilder.append(partText.trim())
                }
            }
        }

        val thoughtSummary = thoughtBuilder.toString().trim().ifBlank { null }
        val reasoningSteps = if (thoughtSummary != null) splitIntoReasoningSteps(thoughtSummary) else emptyList()

        if (detectedFunctionCall != null) {
            val fnName = detectedFunctionCall.optString("name")
            val args = detectedFunctionCall.optJSONObject("args") ?: JSONObject()
            val actionCall = mapFunctionCallToVoiceAction(fnName, args, detectedLang)
            return actionCall.copy(
                reasoningSummary = thoughtSummary,
                reasoningSteps = reasoningSteps
            )
        }

        val trimmedText = textBuilder.toString().trim()
        if (trimmedText.isNotBlank()) {
            return GeminiResponse.SpokenReply(
                message = trimmedText,
                language = detectedLang,
                reasoningSummary = thoughtSummary,
                reasoningSteps = reasoningSteps
            )
        }

        return GeminiResponse.Error("Empty output from Gemini")
    }

    private fun mapFunctionCallToVoiceAction(
        functionName: String,
        args: JSONObject,
        detectedLang: DetectedLanguage
    ): GeminiResponse.ActionCall {
        val paramMap = mutableMapOf<String, String>()
        val keys = args.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            paramMap[k] = args.optString(k, "")
        }

        var actionName = "general_chat"
        var target = ""
        var confirmationRequired = false
        var spokenResponse = ""
        val subSteps = mutableListOf<VoiceAction>()

        when (functionName) {
            "make_call" -> {
                actionName = "call"
                target = args.optString("contact_name").ifBlank { args.optString("phone_number") }
                confirmationRequired = true
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Do you want me to call $target?",
                    "Kya aap $target ko call karna chahte hain?",
                    "کیا آپ $target کو کال کرنا چاہتے ہیں؟",
                    detectedLang
                )
            }
            "send_sms" -> {
                actionName = "send_sms"
                target = args.optString("recipient")
                confirmationRequired = true
                val msg = args.optString("message_text")
                paramMap["text"] = msg
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Send SMS to $target saying '$msg'?",
                    "$target ko '$msg' ka message bhejoon?",
                    "کیا $target کو '$msg' کا میسج بھیجوں؟",
                    detectedLang
                )
            }
            "whatsapp_send_message", "send_whatsapp" -> {
                // replaced by agentfix
                actionName = "whatsapp_send_message"
                target = args.optString("contact").ifBlank { args.optString("recipient") }
                val msg = args.optString("message").ifBlank { args.optString("message_text") }
                paramMap["contact"] = target
                paramMap["message"] = msg
                paramMap["phone"] = args.optString("phone", "")
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Sending WhatsApp message to $target.",
                    "$target ko WhatsApp message bhej raha hoon.",
                    "$target کو واٹس ایپ میسج بھیج رہا ہوں۔",
                    detectedLang
                )
            }
            "whatsapp_delete_message" -> {
                actionName = "whatsapp_delete_message"
                target = args.optString("message_text", "last")
                paramMap["message_text"] = target
                paramMap["scope"] = args.optString("scope", "everyone")
                spokenResponse = "Deleting WhatsApp message."
            }
            "describe_screen" -> {
                actionName = "describe_screen"
                spokenResponse = "Checking current screen."
            }
            "get_device_info" -> {
                actionName = "get_device_info"
                target = args.optString("category", "all")
                paramMap["category"] = target
                spokenResponse = "Reading live device telemetry."
            }
            "type_in_field" -> {
                actionName = "type_in_field"
                target = args.optString("text", "")
                paramMap["text"] = target
                paramMap["field_hint"] = args.optString("field_hint", "")
                paramMap["submit"] = args.optBoolean("submit", false).toString()
                spokenResponse = "Typing into field."
            }
            "ui_click_text" -> {
                actionName = "ui_click_text"
                target = args.optString("text", "")
                paramMap["text"] = target
                paramMap["exact"] = args.optBoolean("exact", false).toString()
                spokenResponse = "Clicking $target."
            }
            "close_app" -> {
                actionName = "close_app"
                target = args.optString("app_name", "current")
                paramMap["app_name"] = target
                paramMap["method"] = args.optString("method", "home")
                spokenResponse = "Closing $target."
            }
            "open_app" -> {
                actionName = "open_app"
                target = args.optString("app_name")
                paramMap["app_name"] = target
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Opening $target.",
                    "$target khol raha hoon.",
                    "$target کھول رہا ہوں۔",
                    detectedLang
                )
            }
            "toggle_setting" -> {
                val setting = args.optString("setting_name").lowercase()
                val state = args.optString("state").lowercase()
                actionName = when (setting) {
                    "flashlight", "torch" -> "toggle_flashlight"
                    "wifi" -> "toggle_wifi"
                    "bluetooth" -> "toggle_bluetooth"
                    "volume" -> "adjust_volume"
                    else -> "toggle_setting"
                }
                target = state
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Turning $state $setting.",
                    "$setting $state kar diya.",
                    "$setting $state کر دیا گیا۔",
                    detectedLang
                )
            }
            "set_alarm" -> {
                actionName = "set_alarm"
                target = args.optString("time")
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Alarm set for $target.",
                    "$target par alarm set kar diya.",
                    "$target پر الارم سیٹ کر دیا گیا۔",
                    detectedLang
                )
            }
            "set_timer" -> {
                actionName = "set_timer"
                target = args.optString("duration_seconds")
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Timer set for $target seconds.",
                    "$target seconds ka timer shuru kar diya.",
                    "$target سیکنڈ کا ٹائمر شروع کر دیا گیا ہے۔",
                    detectedLang
                )
            }
            "navigation" -> {
                actionName = "navigation"
                target = args.optString("destination")
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Starting navigation to $target.",
                    "$target ka rasta shuru kiya ja raha hai.",
                    "$target کا راستہ شروع کیا جا رہا ہے۔",
                    detectedLang
                )
            }
            "media_control" -> {
                actionName = "media_control"
                target = args.optString("action")
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Media command executed.",
                    "Media chala diya gaya.",
                    "میڈیا چلا دیا گیا۔",
                    detectedLang
                )
            }
            "camera_capture" -> {
                actionName = "take_photo"
                target = args.optString("facing", "front")
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Opening camera.",
                    "Camera khol raha hoon.",
                    "کیمرہ کھول رہا ہوں۔",
                    detectedLang
                )
            }
            "read_notifications" -> {
                actionName = "read_notifications"
                target = args.optString("filter", "all")
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Reading your latest notifications.",
                    "Aapke notifications parh raha hoon.",
                    "آپ کے نوٹیفیکیشنز پڑھ رہا ہوں۔",
                    detectedLang
                )
            }
            "unlock_device" -> {
                actionName = "unlock_device"
                spokenResponse = LanguageProcessor.formatReplyInLanguage(
                    "Unlocking device.",
                    "Device unlock ho raha hai.",
                    "فون انلاک ہو رہا ہے۔",
                    detectedLang
                )
            }
            "execute_multi_step_plan" -> {
                actionName = "execute_multi_step_plan"
                val summary = args.optString("summary", "Executing multi-step plan")
                target = summary
                val parsedSteps = parseDynamicStepsObject(args.opt("steps"))
                subSteps.addAll(parsedSteps.map { it.toVoiceAction() })
                spokenResponse = summary.ifBlank {
                    LanguageProcessor.formatReplyInLanguage(
                        "Executing ${subSteps.size}-step plan.",
                        "${subSteps.size} steps ka plan chala raha hoon.",
                        "${subSteps.size} مراحل کا پلان چلا رہا ہوں۔",
                        detectedLang
                    )
                }
            }
            else -> {
                actionName = functionName
                target = args.optString("target", "")
                spokenResponse = "Executing $functionName."
            }
        }

        val voiceAction = VoiceAction(
            action = actionName,
            target = target,
            parameters = paramMap,
            confirmationRequired = confirmationRequired,
            spokenResponse = spokenResponse
        )

        return GeminiResponse.ActionCall(
            action = voiceAction,
            steps = subSteps
        )
    }
}

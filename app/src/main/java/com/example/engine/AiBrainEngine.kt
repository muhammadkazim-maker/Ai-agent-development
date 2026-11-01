package com.example.engine

import android.content.Context
import android.util.Log
import com.example.data.model.VoiceAction
import com.example.engine.gemini.ComplexTaskPlan
import com.example.engine.gemini.GeminiApiClient
import com.example.engine.gemini.GeminiResponse
import com.example.engine.gemini.ThinkingConfig
import com.example.engine.gemini.ThinkingLevel
import com.example.engine.gemini.thinking_config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 3-Tier Hybrid Brain Cascade (`R4`, `R11`, `R12`, `F-053`–`F-062`, `F-081`–`F-086`).
 */
enum class BrainExecutionTier(val displayName: String) {
    TIER_1_CLOUD_GEMINI("Tier 1: Cloud Gemini (Live / Pro / Flash)"),
    TIER_2_ON_DEVICE_LLM_NANO_LITERT("Tier 2: On-Device LLM (Gemini Nano / LiteRT-LM)"),
    TIER_3_DETERMINISTIC_OFFLINE_ENGINE("Tier 3: Deterministic Offline Intent Engine")
}

/**
 * Status of the on-device Tier 2 LLM (`F-082`, `F-083`).
 */
enum class OnDeviceModelStatus(val label: String) {
    NOT_INSTALLED("Not Installed (Using Tier 3 Deterministic Offline)"),
    AICORE_GEMINI_NANO_READY("AICore Gemini Nano Ready"),
    LITERT_LOCAL_MODEL_IMPORTED("LiteRT-LM / GGUF Local Model Imported")
}

class AiBrainEngine(
    private val geminiClient: GeminiApiClient = GeminiApiClient(),
    private val offlineEngine: OfflineCommandEngine = OfflineCommandEngine()
) {

    companion object {
        private const val TAG = "AiBrainEngine"

        private val _isPrivacyOfflineOnlyMode = MutableStateFlow(false)
        val isPrivacyOfflineOnlyMode: StateFlow<Boolean> = _isPrivacyOfflineOnlyMode.asStateFlow()

        private val _lastUsedExecutionTier = MutableStateFlow(BrainExecutionTier.TIER_1_CLOUD_GEMINI)
        val lastUsedExecutionTier: StateFlow<BrainExecutionTier> = _lastUsedExecutionTier.asStateFlow()

        private val _onDeviceModelStatus = MutableStateFlow(OnDeviceModelStatus.NOT_INSTALLED)
        val onDeviceModelStatus: StateFlow<OnDeviceModelStatus> = _onDeviceModelStatus.asStateFlow()

        private val _importedModelPath = MutableStateFlow<String?>(null)
        val importedModelPath: StateFlow<String?> = _importedModelPath.asStateFlow()

        val ALLOWED_TOOL_ACTIONS = setOf(
            "general_chat", "call", "make_call", "make_phone_call", "answer_call", "end_call", "toggle_speaker",
            "send_sms", "send_whatsapp", "send_whatsapp_message", "set_alarm", "set_timer", "toggle_flashlight",
            "toggle_battery_saver", "adjust_volume", "toggle_wifi", "toggle_bluetooth",
            "toggle_hotspot", "toggle_mobile_data", "toggle_airplane_mode", "toggle_dnd",
            "set_brightness", "read_screen", "take_screenshot", "take_photo", "open_camera", "record_video", "navigate",
            "media_control", "read_notifications", "find_phone", "unlock_device",
            "lock_device", "open_app", "open_and_navigate_app", "search_web", "google_search",
            "execute_direct_search", "inject_search", "search_input", "search_anything",
            "play_video", "youtube_search", "play_youtube",
            "ui_click", "ui_scroll", "ui_input", "control_device_system",
            "OpenApp", "SendWhatsApp", "MakeCall", "SystemControl", "WebSearch",
            "teach_word", "save_conditional_rule", "execute_multi_step_plan",
            "describe_screen", "get_device_info", "type_in_field", "ui_click_text",
            "whatsapp_send_message", "whatsapp_delete_message", "close_app",
            "whatsapp_send_voice_note", "perform_gesture", "analyze_screen_visual",
            "handle_phone_call", "teach_standing_rule", "list_standing_rules", "remove_standing_rule"
        )

        /**
         * Enables or disables Privacy Mode (`Offline-Only`) (`F-085`).
         * When `true`, Tier 1 Cloud Gemini is strictly bypassed so zero audio or text leaves the device.
         */
        fun setPrivacyOfflineOnlyMode(enabled: Boolean) {
            _isPrivacyOfflineOnlyMode.value = enabled
            if (enabled) {
                _lastUsedExecutionTier.value = if (_onDeviceModelStatus.value != OnDeviceModelStatus.NOT_INSTALLED) {
                    BrainExecutionTier.TIER_2_ON_DEVICE_LLM_NANO_LITERT
                } else {
                    BrainExecutionTier.TIER_3_DETERMINISTIC_OFFLINE_ENGINE
                }
            }
        }

        /**
         * Registers an imported `.litertlm` / `.gguf` local model file or AICore Gemini Nano (`F-082`, `F-083`).
         */
        fun registerImportedOnDeviceModel(filePath: String): Boolean {
            val trimmed = filePath.trim()
            if (trimmed.isEmpty()) return false
            _importedModelPath.value = trimmed
            _onDeviceModelStatus.value = if (trimmed.contains("aicore", ignoreCase = true)) {
                OnDeviceModelStatus.AICORE_GEMINI_NANO_READY
            } else {
                OnDeviceModelStatus.LITERT_LOCAL_MODEL_IMPORTED
            }
            return true
        }

        /**
         * Loads `assets/system_prompt_cloud.txt` or `assets/system_prompt_local.txt` and renders
         * `{{ASSISTANT_NAME}}`, `{{USER_TITLE}}`, and `{{MOOD_DIRECTIVE}}` (`F-054`, `F-083`, `F-095`).
         */
        fun buildSystemPromptFromAsset(
            context: Context,
            isCloud: Boolean,
            assistantName: String = "Omni",
            userTitle: String = "Boss",
            moodDirective: String = MoodLabel.CALM.toneInstruction
        ): String {
            val fileName = if (isCloud) "system_prompt_cloud.txt" else "system_prompt_local.txt"
            val template = try {
                context.assets.open(fileName).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                "You are {{ASSISTANT_NAME}} for {{USER_TITLE}}. Mood: {{MOOD_DIRECTIVE}}"
            }
            return template
                .replace("{{ASSISTANT_NAME}}", assistantName)
                .replace("{{USER_TITLE}}", userTitle)
                .replace("{{MOOD_DIRECTIVE}}", moodDirective)
        }

        /**
         * Validates a [VoiceAction] against the strict tool schema (`F-056`, `F-084`),
         * normalizing unknown action names to `general_chat` and attaching tier/mood telemetry.
         */
        fun validateToolActionSchema(
            action: VoiceAction,
            tier: BrainExecutionTier,
            mood: MoodEstimate? = null
        ): VoiceAction {
            val safeActionName = if (ALLOWED_TOOL_ACTIONS.contains(action.action)) {
                action.action
            } else {
                "general_chat"
            }
            val enrichedParams = action.parameters.toMutableMap()
            enrichedParams["execution_tier"] = tier.name
            if (mood != null) {
                enrichedParams["mood_label"] = mood.label.name
                enrichedParams["recommended_speed"] = mood.recommendedSpeechSpeed.toString()
                enrichedParams["recommended_pitch"] = mood.recommendedVoicePitch.toString()
            }
            return action.copy(action = safeActionName, parameters = enrichedParams)
        }
    }

    /**
     * Plans a complex multi-step voice goal using the Gemini API client's `thinking_config`
     * and `include_thoughts` parameters.
     */
    suspend fun planComplexTask(
        goal: String,
        detectedLang: DetectedLanguage = LanguageProcessor.detectLanguage(goal),
        contextSummary: String = "",
        thinkingConfig: ThinkingConfig = thinking_config {
            enabled = true
            include_thoughts = true
            thinking_level = ThinkingLevel.HIGH
        }
    ): ComplexTaskPlan {
        return geminiClient.planComplexTask(
            goal = goal,
            detectedLang = detectedLang,
            contextSummary = contextSummary,
            thinkingConfig = thinkingConfig
        )
    }

    suspend fun processCommand(
        userSpeech: String,
        recentContext: String = "",
        personalVocabContext: String = "",
        longTermMemoryContext: String = ""
    ): Pair<VoiceAction, DetectedLanguage> = withContext(Dispatchers.Default) {
        val trimmed = userSpeech.trim()
        val detectedLang = LanguageProcessor.detectLanguage(trimmed)

        if (trimmed.isBlank()) {
            val emptyMsg = LanguageProcessor.formatReplyInLanguage(
                "I didn't hear a command. Please speak again.",
                "Mujhe koi awaz nahi aayi. Baraye meherbani dobara bolein.",
                "مجھے کوئی آواز نہیں آئی۔ براہِ کرم دوبارہ بولیں۔",
                detectedLang
            )
            return@withContext Pair(
                VoiceAction(action = "general_chat", spokenResponse = emptyMsg, confirmationRequired = false),
                detectedLang
            )
        }

        // 0. Evaluate User Mood & Distress Helpline Guardrail (`R13`, `F-093`–`F-097`)
        val moodEstimate = MoodAnalyzerEngine.evaluateTurnMood(trimmed)
        if (moodEstimate.isDistressSafetyAlert && !moodEstimate.safetyHelplineMessage.isNullOrBlank()) {
            val helplineAction = validateToolActionSchema(
                action = VoiceAction(
                    action = "general_chat",
                    target = "distress_helpline",
                    spokenResponse = moodEstimate.safetyHelplineMessage,
                    confirmationRequired = false
                ),
                tier = BrainExecutionTier.TIER_3_DETERMINISTIC_OFFLINE_ENGINE,
                mood = moodEstimate
            )
            return@withContext Pair(helplineAction, detectedLang)
        }

        val normalized = LanguageProcessor.normalizeInput(trimmed)
        Log.d(TAG, "Input: '$trimmed' -> Normalized: '$normalized' (Lang: ${detectedLang.label}, Mood: ${moodEstimate.label.displayTitle})")

        // Retrieve top few-shot examples from commands_dataset.json
        val fewShotExamples = LanguageProcessor.retrieveFewShotExamples(normalized, topK = 5)
        val fewShotPromptBlock = buildString {
            appendLine("Mood Directive: ${moodEstimate.label.toneInstruction}")
            if (recentContext.isNotBlank()) appendLine("Recent Context: $recentContext")
            if (personalVocabContext.isNotBlank()) appendLine("User Personal Vocabulary: $personalVocabContext")
            if (longTermMemoryContext.isNotBlank()) appendLine("Long-Term Memory: $longTermMemoryContext")
            if (fewShotExamples.isNotEmpty()) {
                appendLine("Few-Shot Bilingual Examples:")
                fewShotExamples.forEach { ex ->
                    appendLine("- Input (${ex.language}): \"${ex.command}\" -> Tool: ${ex.toolName}(${ex.argsSummary}) | Reply: \"${ex.expectedReply}\"")
                }
            }
        }

        // FAST PATH: Check deterministic on-device command engine first (<1ms latency for explicit OS actions)
        val fastOfflineAction = offlineEngine.parseCommand(trimmed, detectedLang)
        if (fastOfflineAction.action != "general_chat") {
            val activeFastTier = if (_onDeviceModelStatus.value != OnDeviceModelStatus.NOT_INSTALLED) {
                BrainExecutionTier.TIER_2_ON_DEVICE_LLM_NANO_LITERT
            } else {
                BrainExecutionTier.TIER_3_DETERMINISTIC_OFFLINE_ENGINE
            }
            _lastUsedExecutionTier.value = activeFastTier
            Log.d(TAG, "Fast-path deterministic engine resolved command in <1ms (${activeFastTier.displayName}): ${fastOfflineAction.action} -> ${fastOfflineAction.target}")
            return@withContext Pair(
                validateToolActionSchema(fastOfflineAction, activeFastTier, moodEstimate),
                detectedLang
            )
        }

        // SUPER-AGI FAST PATH: Check SuperAgiBrainEngine colloquial Urdu/Roman Urdu/English task extractor (<2ms)
        val superAgiTasks = SuperAgiBrainEngine.extractAllDeviceTasks(
            context = com.omnivoice.agentfix.AgentBridge.appContext,
            rawText = trimmed
        )
        if (superAgiTasks.isNotEmpty()) {
            val nluLang = when (detectedLang) {
                DetectedLanguage.URDU_NASTALIQ -> NluLanguageEnum.URDU_SCRIPT
                DetectedLanguage.ROMAN_URDU, DetectedLanguage.MIXED -> NluLanguageEnum.ROMAN_URDU_MIXED
                DetectedLanguage.ENGLISH -> NluLanguageEnum.ENGLISH
            }
            val spokenSummary = SuperAgiBrainEngine.buildSpokenSummaryForTasks(superAgiTasks, nluLang)
            val mappedAction = if (superAgiTasks.size > 1) {
                VoiceAction(
                    action = "multi_step",
                    target = trimmed,
                    spokenResponse = spokenSummary,
                    confirmationRequired = false
                )
            } else {
                val task = superAgiTasks.first()
                when (task.taskType) {
                    NluTaskType.YOUTUBE_SEARCH -> VoiceAction(
                        action = "play_video",
                        target = task.actionValue,
                        parameters = mapOf("query" to task.actionValue, "platform" to task.target),
                        spokenResponse = spokenSummary,
                        confirmationRequired = false
                    )
                    NluTaskType.GOOGLE_SEARCH -> VoiceAction(
                        action = "search_web",
                        target = task.actionValue,
                        parameters = mapOf("query" to task.actionValue, "platform" to task.target),
                        spokenResponse = spokenSummary,
                        confirmationRequired = false
                    )
                    NluTaskType.OPEN_APP -> VoiceAction(
                        action = "open_app",
                        target = task.target,
                        parameters = mapOf("app_name" to task.target),
                        spokenResponse = spokenSummary,
                        confirmationRequired = false
                    )
                    NluTaskType.FLASHLIGHT -> VoiceAction(
                        action = "toggle_flashlight",
                        target = task.actionValue.lowercase(),
                        parameters = mapOf("state" to task.actionValue.lowercase()),
                        spokenResponse = spokenSummary,
                        confirmationRequired = false
                    )
                    NluTaskType.WHATSAPP_MESSAGE -> VoiceAction(
                        action = "send_whatsapp",
                        target = task.target,
                        parameters = mapOf("contact_name" to task.target, "message" to task.actionValue),
                        spokenResponse = spokenSummary,
                        confirmationRequired = false
                    )
                    NluTaskType.WHATSAPP_DELETE_MESSAGE -> VoiceAction(
                        action = "whatsapp_delete_message",
                        target = task.target,
                        parameters = mapOf("message_text" to task.target, "scope" to task.actionValue),
                        spokenResponse = spokenSummary,
                        confirmationRequired = false
                    )
                    NluTaskType.WHATSAPP_CALL -> VoiceAction(
                        action = "whatsapp_call",
                        target = task.target,
                        parameters = mapOf("contact" to task.target, "call_type" to task.actionValue),
                        spokenResponse = spokenSummary,
                        confirmationRequired = false
                    )
                    NluTaskType.UI_CLICK -> VoiceAction(
                        action = "ui_click_text",
                        target = task.target,
                        parameters = mapOf("text" to task.target),
                        spokenResponse = spokenSummary,
                        confirmationRequired = false
                    )
                    NluTaskType.PHONE_CALL -> VoiceAction(
                        action = "call",
                        target = task.target,
                        parameters = mapOf("contact_name" to task.target),
                        spokenResponse = spokenSummary,
                        confirmationRequired = false
                    )
                    NluTaskType.SMS -> VoiceAction(
                        action = "send_sms",
                        target = task.target,
                        parameters = mapOf("contact_name" to task.target, "message" to task.actionValue),
                        spokenResponse = spokenSummary,
                        confirmationRequired = false
                    )
                    else -> VoiceAction(
                        action = "multi_step",
                        target = trimmed,
                        spokenResponse = spokenSummary,
                        confirmationRequired = false
                    )
                }
            }
            _lastUsedExecutionTier.value = BrainExecutionTier.TIER_3_DETERMINISTIC_OFFLINE_ENGINE
            return@withContext Pair(
                validateToolActionSchema(mappedAction, BrainExecutionTier.TIER_3_DETERMINISTIC_OFFLINE_ENGINE, moodEstimate),
                detectedLang
            )
        }

        val thinkingConfig = thinking_config {
            enabled = false
            include_thoughts = false
            thinking_level = ThinkingLevel.LOW
            thinking_budget = 0
        }

        // TIER 1: Cloud Gemini (skipped when Privacy Offline-Only Mode is active) (`F-053`, `F-085`)
        if (!_isPrivacyOfflineOnlyMode.value) {
            try {
                val geminiResult = geminiClient.processQuery(
                    rawQuery = normalized,
                    detectedLang = detectedLang,
                    recentContext = fewShotPromptBlock,
                    thinkingConfig = thinkingConfig
                )
                when (geminiResult) {
                    is GeminiResponse.ActionCall -> {
                        Log.i(TAG, "Tier 1 Gemini resolved ActionCall: ${geminiResult.action.action} (Target: ${geminiResult.action.target})")
                        val enrichedParams = geminiResult.action.parameters.toMutableMap()
                        if (!geminiResult.reasoningSummary.isNullOrBlank()) {
                            enrichedParams["thought_summary"] = geminiResult.reasoningSummary
                        }
                        if (geminiResult.reasoningSteps.isNotEmpty()) {
                            enrichedParams["reasoning_steps"] = geminiResult.reasoningSteps.joinToString(" | ")
                        }
                        _lastUsedExecutionTier.value = BrainExecutionTier.TIER_1_CLOUD_GEMINI
                        return@withContext Pair(
                            validateToolActionSchema(
                                geminiResult.action.copy(parameters = enrichedParams),
                                BrainExecutionTier.TIER_1_CLOUD_GEMINI,
                                moodEstimate
                            ),
                            detectedLang
                        )
                    }
                    is GeminiResponse.SpokenReply -> {
                        Log.i(TAG, "Tier 1 Gemini resolved SpokenReply: ${geminiResult.message}")
                        val params = mutableMapOf<String, String>()
                        if (!geminiResult.reasoningSummary.isNullOrBlank()) {
                            params["thought_summary"] = geminiResult.reasoningSummary
                        }
                        if (geminiResult.reasoningSteps.isNotEmpty()) {
                            params["reasoning_steps"] = geminiResult.reasoningSteps.joinToString(" | ")
                        }
                        _lastUsedExecutionTier.value = BrainExecutionTier.TIER_1_CLOUD_GEMINI
                        val chatAction = validateToolActionSchema(
                            VoiceAction(
                                action = "general_chat",
                                parameters = params,
                                spokenResponse = geminiResult.message,
                                confirmationRequired = false
                            ),
                            BrainExecutionTier.TIER_1_CLOUD_GEMINI,
                            moodEstimate
                        )
                        return@withContext Pair(chatAction, detectedLang)
                    }
                    is GeminiResponse.Error -> {
                        Log.w(TAG, "Tier 1 Gemini fell back to on-device tiers: ${geminiResult.message}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Tier 1 Gemini exception, cascading to Tier 2 / Tier 3 offline brain", e)
            }
        } else {
            Log.d(TAG, "Privacy Offline-Only Mode active -> bypassing Tier 1 Cloud Gemini")
        }

        // TIER 2 & TIER 3: On-Device LLM / Deterministic Offline Intent Engine (`R11`, `R12`, `F-057`–`F-062`)
        val activeOfflineTier = if (_onDeviceModelStatus.value != OnDeviceModelStatus.NOT_INSTALLED) {
            BrainExecutionTier.TIER_2_ON_DEVICE_LLM_NANO_LITERT
        } else {
            BrainExecutionTier.TIER_3_DETERMINISTIC_OFFLINE_ENGINE
        }
        _lastUsedExecutionTier.value = activeOfflineTier

        val offlineAction = offlineEngine.parseCommand(trimmed, detectedLang)
        if (offlineAction.action != "general_chat") {
            Log.d(TAG, "${activeOfflineTier.displayName} resolved command: ${offlineAction.action} -> ${offlineAction.target}")
            return@withContext Pair(
                validateToolActionSchema(offlineAction, activeOfflineTier, moodEstimate),
                detectedLang
            )
        }

        // Fallback smart conversational heuristics
        val fallback = parseAdvancedQuery(normalized, offlineAction, detectedLang)
        return@withContext Pair(
            validateToolActionSchema(fallback, activeOfflineTier, moodEstimate),
            detectedLang
        )
    }

    private fun parseAdvancedQuery(
        query: String,
        defaultFallback: VoiceAction,
        detectedLang: DetectedLanguage
    ): VoiceAction {
        val lower = query.lowercase()

        if (lower.startsWith("search") || lower.startsWith("google") || lower.startsWith("look up") || lower.contains("search karo")) {
            val searchTerm = lower.replace(Regex("^(?:search|google|look up)\\s+(?:for\\s+)?|\\s*search\\s+karo$"), "").trim()
            val response = LanguageProcessor.formatReplyInLanguage(
                "Searching the web for $searchTerm.",
                "$searchTerm ke liye web par search kar raha hoon.",
                "$searchTerm کے لیے ویب پر تلاش جاری ہے۔",
                detectedLang
            )
            return VoiceAction(
                action = "search_web",
                target = searchTerm,
                parameters = mapOf("query" to searchTerm),
                spokenResponse = response,
                confirmationRequired = false
            )
        }

        val nluLang = when (detectedLang) {
            DetectedLanguage.URDU_NASTALIQ -> NluLanguageEnum.URDU_SCRIPT
            DetectedLanguage.ROMAN_URDU, DetectedLanguage.MIXED -> NluLanguageEnum.ROMAN_URDU_MIXED
            DetectedLanguage.ENGLISH -> NluLanguageEnum.ENGLISH
        }
        val agiResponse = SuperAgiBrainEngine.generateLocalSuperAgiResponse(
            context = com.omnivoice.agentfix.AgentBridge.appContext,
            rawText = query,
            detectedLang = nluLang
        )
        return VoiceAction(
            action = "general_chat",
            parameters = mapOf("thought_summary" to "Analyzed with SuperAgiBrainEngine"),
            spokenResponse = agiResponse,
            confirmationRequired = false
        )
    }

    fun parseJsonAction(jsonString: String): VoiceAction? {
        return try {
            val root = JSONObject(jsonString)
            val action = root.optString("action", "general_chat")
            val target = root.optString("target", "")
            val confirmation = root.optBoolean("confirmationRequired", false)
            val spokenResponse = root.optString("spokenResponse", "")
            val requiresUnlock = root.optBoolean("requiresUnlock", false)

            val paramsMap = mutableMapOf<String, String>()
            val paramsObj = root.optJSONObject("parameters")
            if (paramsObj != null) {
                val keys = paramsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    paramsMap[key] = paramsObj.optString(key, "")
                }
            }

            VoiceAction(
                action = action,
                target = target,
                parameters = paramsMap,
                confirmationRequired = confirmation,
                spokenResponse = spokenResponse,
                requiresUnlock = requiresUnlock
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON action", e)
            null
        }
    }
}

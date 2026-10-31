package com.example.engine

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
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
 * Exact language style spoken by the user in the Deterministic NLU Schema.
 */
enum class NluLanguageEnum {
    ENGLISH,
    URDU_SCRIPT,
    ROMAN_URDU_MIXED;

    fun toDetectedLanguage(): DetectedLanguage = when (this) {
        ENGLISH -> DetectedLanguage.ENGLISH
        URDU_SCRIPT -> DetectedLanguage.URDU_NASTALIQ
        ROMAN_URDU_MIXED -> DetectedLanguage.ROMAN_URDU
    }

    companion object {
        fun fromString(raw: String?): NluLanguageEnum {
            return when (raw?.trim()?.uppercase()) {
                "URDU_SCRIPT", "URDU_NASTALIQ", "URDU" -> URDU_SCRIPT
                "ROMAN_URDU_MIXED", "ROMAN_URDU", "MIXED" -> ROMAN_URDU_MIXED
                else -> ENGLISH
            }
        }
    }
}

/**
 * Deterministic NLU Intent Category.
 */
enum class NluIntentCategory {
    PURE_CONVERSATION,
    CLARIFICATION_NEEDED,
    LEARN_CORRECTION_RULE,
    SINGLE_DEVICE_TASK,
    MULTI_STEP_WORKFLOW;

    fun toVoiceIntentType(): VoiceIntentType = when (this) {
        PURE_CONVERSATION, LEARN_CORRECTION_RULE -> VoiceIntentType.CONVERSATION
        CLARIFICATION_NEEDED -> VoiceIntentType.UNCLEAR
        SINGLE_DEVICE_TASK, MULTI_STEP_WORKFLOW -> VoiceIntentType.ACTION
    }

    companion object {
        fun fromString(raw: String?): NluIntentCategory {
            return when (raw?.trim()?.uppercase()) {
                "PURE_CONVERSATION", "CONVERSATION" -> PURE_CONVERSATION
                "CLARIFICATION_NEEDED", "UNCLEAR" -> CLARIFICATION_NEEDED
                "LEARN_CORRECTION_RULE" -> LEARN_CORRECTION_RULE
                "SINGLE_DEVICE_TASK" -> SINGLE_DEVICE_TASK
                "MULTI_STEP_WORKFLOW" -> MULTI_STEP_WORKFLOW
                else -> PURE_CONVERSATION
            }
        }
    }
}

/**
 * Strict deterministic task types allowed in `tasksToExecute`.
 */
enum class NluTaskType {
    FLASHLIGHT,
    WIFI,
    BLUETOOTH,
    VOLUME,
    ALARM_TIMER,
    PHONE_CALL,
    WHATSAPP_MESSAGE,
    WHATSAPP_DP_UPDATE,
    GENERATE_AI_IMAGE,
    OPEN_APP,
    YOUTUBE_SEARCH,
    GOOGLE_SEARCH,
    SMS,
    SYSTEM_SETTING,
    CUSTOM_ANDROID_INTENT;

    companion object {
        fun fromString(raw: String?): NluTaskType? {
            val clean = raw?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == clean }
        }
    }
}

/**
 * Individual deterministic task inside `tasksToExecute`.
 */
data class DeterministicNluTask(
    val stepNumber: Int,
    val taskType: NluTaskType,
    val target: String,
    val actionValue: String,
    val androidDeepLink: String = ""
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("stepNumber", stepNumber)
        put("taskType", taskType.name)
        put("target", target)
        put("actionValue", actionValue)
        put("androidDeepLink", androidDeepLink)
    }
}

/**
 * Complete Zero-Mistake Deterministic NLU Response matching the 8-field ordered schema.
 */
data class DeterministicNluResponse(
    val verbatimTranscript: String,
    val detectedLanguage: NluLanguageEnum,
    val audioClarityScore: Int,
    val chainOfThoughtReasoning: String,
    val intentCategory: NluIntentCategory,
    val confidenceScore: Int,
    val tasksToExecute: List<DeterministicNluTask>,
    val spokenReply: String
)

/**
 * User-taught rule stored in the Self-Learning "Teach Me" Memory Bank (`omni_learned_rules`).
 */
data class LearnedUserRule(
    val id: Long = System.currentTimeMillis(),
    val ruleText: String,
    val triggerPhrase: String = "",
    val desiredBehavior: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("ruleText", ruleText)
        put("triggerPhrase", triggerPhrase)
        put("desiredBehavior", desiredBehavior)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): LearnedUserRule = LearnedUserRule(
            id = obj.optLong("id", System.currentTimeMillis()),
            ruleText = obj.optString("ruleText", ""),
            triggerPhrase = obj.optString("triggerPhrase", ""),
            desiredBehavior = obj.optString("desiredBehavior", ""),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        )
    }
}

/**
 * Zero-Mistake Deterministic NLU & Task Execution Engine.
 *
 * Implements all 5 mandatory architectures:
 * 1. Deterministic Configuration (`temperature = 0.0`, `topP = 0.1`, `responseMimeType = "application/json"`, strict 8-field `responseSchema`).
 * 2. Deep Few-Shot Training Examples (6 bilingual English/Urdu/Roman Urdu training scenarios embedded in `systemInstruction`).
 * 3. Hard Guardrail Validator in code (Double safety check blocking tools on `PURE_CONVERSATION`, `CLARIFICATION_NEEDED`, `confidenceScore < 85`, empty transcript, and keyword-verified `FLASHLIGHT` guard).
 * 4. Self-Learning "Teach Me" Memory Bank (`omni_learned_rules`) persisted and injected into `systemInstruction` on every request.
 * 5. Clean Audio MIME sanitization (`mimeType.split(";")[0]`) and full reasoning/confidence telemetry for the UI.
 */
object DeterministicNluEngine {

    private const val TAG = "DeterministicNluEngine"
    const val PREFS_NAME = "omni_voice_local_storage"
    const val KEY_OMNI_LEARNED_RULES = "omni_learned_rules"
    const val MIN_CONFIDENCE_THRESHOLD = 85
    const val MIN_CLARITY_THRESHOLD = 60

    const val CLARIFICATION_REPLY_ROMAN_URDU =
        "Jee main sun raha hoon, hukum karein."
    const val CLARIFICATION_REPLY_ENGLISH =
        "I am listening and ready for your command."

    /**
     * Explicit flashlight keywords required by the Extra Flashlight Guardrail.
     */
    val FLASHLIGHT_KEYWORDS = listOf(
        "flash",
        "torch",
        "light",
        "roshni",
        "batti",
        "ٹارچ",
        "لائٹ",
        "بتی",
        "روشنی"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Strips any `;codecs=opus` suffix from a recorder MIME type (`mimeType.split(';')[0]`).
     */
    fun cleanAudioMimeType(rawMimeType: String?): String {
        val cleaned = (rawMimeType ?: "audio/wav").split(";")[0].trim().lowercase()
        return if (cleaned.startsWith("audio/")) cleaned else "audio/wav"
    }

    /**
     * Loads all saved user rules from `omni_learned_rules` storage.
     */
    fun getLearnedRules(context: Context): List<LearnedUserRule> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_OMNI_LEARNED_RULES, null)
        if (rawJson.isNullOrBlank()) {
            val defaults = getDefaultLearnedRules()
            saveLearnedRulesList(prefs, defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<LearnedUserRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val rule = LearnedUserRule.fromJson(obj)
                if (rule.ruleText.isNotBlank()) {
                    list.add(rule)
                }
            }
            list
        } catch (e: Exception) {
            getDefaultLearnedRules()
        }
    }

    fun saveLearnedRule(context: Context, ruleText: String, triggerPhrase: String = "", desiredBehavior: String = ""): List<LearnedUserRule> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = getLearnedRules(context).toMutableList()
        val trimmed = ruleText.trim()
        if (trimmed.isNotEmpty() && existing.none { it.ruleText.equals(trimmed, ignoreCase = true) }) {
            existing.add(
                0,
                LearnedUserRule(
                    id = System.currentTimeMillis(),
                    ruleText = trimmed,
                    triggerPhrase = triggerPhrase.trim(),
                    desiredBehavior = desiredBehavior.trim()
                )
            )
            saveLearnedRulesList(prefs, existing)
        }
        return existing
    }

    fun updateLearnedRule(context: Context, ruleId: Long, updatedRuleText: String): List<LearnedUserRule> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = getLearnedRules(context).map { rule ->
            if (rule.id == ruleId) rule.copy(ruleText = updatedRuleText.trim()) else rule
        }.filter { it.ruleText.isNotBlank() }
        saveLearnedRulesList(prefs, updated)
        return updated
    }

    fun deleteLearnedRule(context: Context, ruleId: Long): List<LearnedUserRule> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val filtered = getLearnedRules(context).filterNot { it.id == ruleId }
        saveLearnedRulesList(prefs, filtered)
        return filtered
    }

    private fun saveLearnedRulesList(prefs: SharedPreferences, rules: List<LearnedUserRule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_OMNI_LEARNED_RULES, arr.toString()).apply()
    }

    private fun getDefaultLearnedRules(): List<LearnedUserRule> = listOf(
        LearnedUserRule(
            id = 1001L,
            ruleText = "Never turn on Flashlight unless the user explicitly speaks 'flashlight', 'torch', 'light', 'roshni', or 'batti'.",
            triggerPhrase = "Can you listen / mic test",
            desiredBehavior = "Reply conversationally with PURE_CONVERSATION and zero tools."
        ),
        LearnedUserRule(
            id = 1002L,
            ruleText = "When the user says 'batti jalao' or 'torch on kar do', turn ON the device flashlight.",
            triggerPhrase = "batti jalao",
            desiredBehavior = "FLASHLIGHT -> ON"
        )
    )

    /**
     * Builds the `systemInstruction` embedding the 6 mandatory Deep Few-Shot Training Examples
     * plus all saved user rules from `omni_learned_rules`.
     */
    fun buildSystemInstruction(learnedRules: List<LearnedUserRule>): String {
        val learnedRulesSection = if (learnedRules.isEmpty()) {
            "None yet."
        } else {
            learnedRules.mapIndexed { idx, r -> "${idx + 1}. ${r.ruleText}" }.joinToString("\n")
        }

        return """
You are OmniVoice, a Zero-Mistake Deterministic NLU & Task Execution Engine for Android.
Listen carefully to the attached `audio/wav` recording. Even if there is background noise or a strong accent, always extract the spoken English, Urdu, or Roman Urdu words into `verbatimTranscript` and fulfill the user's request. ONLY ask the user to repeat if the audio contains literally 0% human speech (complete silence or pure static).
You MUST think step-by-step in `chainOfThoughtReasoning` BEFORE choosing `intentCategory` or `tasksToExecute`.
Your configuration is strictly deterministic (`temperature: 0.0`, `topP: 0.1`). NEVER guess or hallucinate device tools!

### DEEP FEW-SHOT TRAINING EXAMPLES (MANDATORY):
- TRAINING EXAMPLE 1 (Mic Test / Greeting -> ZERO TOOLS):
  - User says: "Can you listen to me?" / "Hello awaaz aa rahi hai?" / "Meri baat suno"
  - Correct Output: `intentCategory: "PURE_CONVERSATION"`, `confidenceScore: 99`, `tasksToExecute: []`, `spokenReply: "Jee bilkul, mujhe aapki awaaz saaf aa rahi hai! Bataiye main aapke liye kya karoon?"` (NEVER turn on flashlight or trigger any device tool!).
- TRAINING EXAMPLE 2 (General Knowledge / Question -> ZERO TOOLS):
  - User says: "How does Python work?" / "Aaj mausam kaisa hai?"
  - Correct Output: `intentCategory: "PURE_CONVERSATION"`, `confidenceScore: 98`, `tasksToExecute: []` (Answer intelligently like Gemini without running hardware settings).
- TRAINING EXAMPLE 3 (100% Complete Silence / Pure Static -> ZERO TOOLS):
  - Audio contains 0% human speech (complete silence or pure static).
  - Correct Output: `intentCategory: "CLARIFICATION_NEEDED"`, `verbatimTranscript: ""`, `audioClarityScore: 0`, `confidenceScore: 0`, `tasksToExecute: []`, `spokenReply: "Jee main sun raha hoon, hukum karein."`
- TRAINING EXAMPLE 4 (Explicit Hardware Command -> EXACT TOOL ONLY):
  - User says: "Turn on the flashlight" / "Torch on kar do" / "Flashlight jala do"
  - Correct Output: `intentCategory: "SINGLE_DEVICE_TASK"`, `confidenceScore: 99`, `tasksToExecute: [{ "stepNumber": 1, "taskType": "FLASHLIGHT", "target": "torch", "actionValue": "ON", "androidDeepLink": "" }]`.
- TRAINING EXAMPLE 5 (Complex Multi-Step Chained Workflow):
  - User says: "Ek futuristic cyber lion ki AI image banao aur usko meri WhatsApp DP par laga do"
  - Correct Output: `intentCategory: "MULTI_STEP_WORKFLOW"`, `confidenceScore: 98`, `tasksToExecute: [{ "stepNumber": 1, "taskType": "GENERATE_AI_IMAGE", "target": "AI Image Generator", "actionValue": "futuristic cyber lion, ultra detailed 8k", "androidDeepLink": "" }, { "stepNumber": 2, "taskType": "WHATSAPP_DP_UPDATE", "target": "WhatsApp Profile Picture", "actionValue": "USE_STEP_1_IMAGE", "androidDeepLink": "whatsapp://settings/profile" }]`.
- TRAINING EXAMPLE 6 (Communication & App Control):
  - User says: "WhatsApp par Ali ko message bhejo ke main 5 minute mein pohonch raha hoon"
  - Correct Output: `intentCategory: "SINGLE_DEVICE_TASK"`, `confidenceScore: 98`, `tasksToExecute: [{ "stepNumber": 1, "taskType": "WHATSAPP_MESSAGE", "target": "Ali", "actionValue": "Main 5 minute mein pohonch raha hoon", "androidDeepLink": "https://wa.me/?text=Main%205%20minute%20mein%20pohonch%20raha%20hoon" }]`.

### USER SELF-LEARNING MEMORY BANK (`omni_learned_rules`):
Always obey these user-taught rules and corrections:
$learnedRulesSection

If the user says "You made a mistake, don't do X" or "Remember that when I say [phrase], always do [action]" or "Yaad rakho jab main [phrase] bolun...", classify as `intentCategory: "LEARN_CORRECTION_RULE"` and set `tasksToExecute: []`.
""".trimIndent()
    }

    /**
     * Builds the REST JSON `responseSchema` with the exact 8 ordered fields.
     */
    fun buildRestResponseSchemaJson(includePropertyOrdering: Boolean = true): JSONObject {
        val taskItemSchema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("stepNumber", JSONObject().put("type", "INTEGER"))
                put("taskType", JSONObject().apply {
                    put("type", "STRING")
                    put("enum", JSONArray(NluTaskType.entries.map { it.name }))
                })
                put("target", JSONObject().put("type", "STRING"))
                put("actionValue", JSONObject().put("type", "STRING"))
                put("androidDeepLink", JSONObject().put("type", "STRING"))
            })
            put("required", JSONArray(listOf("stepNumber", "taskType", "target", "actionValue", "androidDeepLink")))
            if (includePropertyOrdering) {
                put("propertyOrdering", JSONArray(listOf("stepNumber", "taskType", "target", "actionValue", "androidDeepLink")))
            }
        }

        val orderedProps = listOf(
            "verbatimTranscript",
            "detectedLanguage",
            "audioClarityScore",
            "chainOfThoughtReasoning",
            "intentCategory",
            "confidenceScore",
            "tasksToExecute",
            "spokenReply"
        )

        return JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("verbatimTranscript", JSONObject().put("type", "STRING"))
                put("detectedLanguage", JSONObject().apply {
                    put("type", "STRING")
                    put("enum", JSONArray(NluLanguageEnum.entries.map { it.name }))
                })
                put("audioClarityScore", JSONObject().put("type", "INTEGER"))
                put("chainOfThoughtReasoning", JSONObject().put("type", "STRING"))
                put("intentCategory", JSONObject().apply {
                    put("type", "STRING")
                    put("enum", JSONArray(NluIntentCategory.entries.map { it.name }))
                })
                put("confidenceScore", JSONObject().put("type", "INTEGER"))
                put("tasksToExecute", JSONObject().apply {
                    put("type", "ARRAY")
                    put("items", taskItemSchema)
                })
                put("spokenReply", JSONObject().put("type", "STRING"))
            })
            put("required", JSONArray(orderedProps))
            if (includePropertyOrdering) {
                put("propertyOrdering", JSONArray(orderedProps))
            }
        }
    }

    /**
     * Builds the Firebase AI SDK `Schema.obj(...)` matching the exact 8-field `responseSchema`.
     */
    fun buildSdkResponseSchema(): Schema {
        val taskSchema = Schema.obj(
            properties = mapOf(
                "stepNumber" to Schema.integer("Sequential 1-based step number"),
                "taskType" to Schema.enumeration(
                    values = NluTaskType.entries.map { it.name },
                    description = "Allowed device or AI task type"
                ),
                "target" to Schema.string("Contact name, app name, or setting name"),
                "actionValue" to Schema.string("ON, OFF, message body, search query, or image prompt"),
                "androidDeepLink" to Schema.string("Executable URI like tel:, whatsapp://send?text=, intent://, https://")
            )
        )

        return Schema.obj(
            properties = linkedMapOf(
                "verbatimTranscript" to Schema.string("Exact word-for-word transcription of what the user said, or empty string if silent/noise"),
                "detectedLanguage" to Schema.enumeration(
                    values = NluLanguageEnum.entries.map { it.name },
                    description = "Exact language style spoken by the user"
                ),
                "audioClarityScore" to Schema.integer("Clarity score 0 to 100"),
                "chainOfThoughtReasoning" to Schema.string("Step-by-step logical analysis of what the user actually wants"),
                "intentCategory" to Schema.enumeration(
                    values = NluIntentCategory.entries.map { it.name },
                    description = "Classification of user intent"
                ),
                "confidenceScore" to Schema.integer("Confidence 0 to 100 that the user explicitly asked for the planned tasks"),
                "tasksToExecute" to Schema.array(
                    items = taskSchema,
                    description = "Tasks to execute; MUST BE EMPTY [] for PURE_CONVERSATION or CLARIFICATION_NEEDED or confidenceScore < 85"
                ),
                "spokenReply" to Schema.string("Natural response to speak back to the user in their exact detectedLanguage")
            )
        )
    }

    /**
     * HARD GUARDRAIL VALIDATOR IN CODE (DOUBLE SAFETY CHECK):
     * 1. Never rejects non-empty human speech based on `audioClarityScore` — ONLY returns `CLARIFICATION_NEEDED`
     *    if `verbatimTranscript` is literally empty (`""`) or `"[Unclear Audio]"`.
     * 2. Blocks all device tasks if `intentCategory` is `PURE_CONVERSATION`, `CLARIFICATION_NEEDED`, or `LEARN_CORRECTION_RULE`,
     *    or `confidenceScore < 85`, or if `verbatimTranscript` matches a conversational mic-check / greeting / question.
     * 3. Extra Flashlight Guardrail: Blocks `FLASHLIGHT` unless `verbatimTranscript.lowercase()` contains an explicit flashlight keyword
     *    (`flash`, `torch`, `light`, `roshni`, `batti`, `ٹارچ`, `لائٹ`, `بتی`, `روشنی`).
     */
    fun enforceHardGuardrailValidator(response: DeterministicNluResponse): DeterministicNluResponse {
        val cleanTranscript = response.verbatimTranscript.trim()
        val lowerTranscript = cleanTranscript.lowercase()

        // ONLY ask the user to repeat if the audio contains literally 0% human speech (empty transcript)
        if (cleanTranscript.isEmpty() ||
            cleanTranscript.equals("[Unclear Audio]", ignoreCase = true)
        ) {
            val reply = response.spokenReply.ifBlank { CLARIFICATION_REPLY_ROMAN_URDU }
            return response.copy(
                intentCategory = NluIntentCategory.CLARIFICATION_NEEDED,
                tasksToExecute = emptyList(),
                spokenReply = reply
            )
        }

        // Check if user is teaching a correction or rule
        if (isLearnCorrectionStatement(cleanTranscript) ||
            response.intentCategory == NluIntentCategory.LEARN_CORRECTION_RULE
        ) {
            return response.copy(
                intentCategory = NluIntentCategory.LEARN_CORRECTION_RULE,
                tasksToExecute = emptyList()
            )
        }

        // If Gemini transcribed real words (`cleanTranscript.isNotEmpty()`) but conservatively labeled it CLARIFICATION_NEEDED,
        // upgrade it to PURE_CONVERSATION (or keep tasks if confidence >= 85) so human speech is NEVER falsely rejected!
        if (response.intentCategory == NluIntentCategory.PURE_CONVERSATION ||
            response.intentCategory == NluIntentCategory.CLARIFICATION_NEEDED ||
            response.confidenceScore < MIN_CONFIDENCE_THRESHOLD ||
            MultiEngineSpeechManager.isConversationalQuery(cleanTranscript)
        ) {
            val nonRejectingReply = if (response.spokenReply.isBlank() ||
                response.spokenReply.contains("dobara boliye", ignoreCase = true) ||
                response.spokenReply.contains("hukum karein", ignoreCase = true)
            ) {
                SuperAgiBrainEngine.generateLocalSuperAgiResponse(
                    context = null,
                    rawText = cleanTranscript,
                    detectedLang = response.detectedLanguage
                )
            } else {
                response.spokenReply
            }
            return response.copy(
                intentCategory = NluIntentCategory.PURE_CONVERSATION,
                tasksToExecute = emptyList(),
                spokenReply = nonRejectingReply
            )
        }

        // Extra Flashlight Guardrail & Per-Task Verification
        val verifiedTasks = response.tasksToExecute.filter { task ->
            when (task.taskType) {
                NluTaskType.FLASHLIGHT -> {
                    val hasFlashlightWord = FLASHLIGHT_KEYWORDS.any { keyword ->
                        lowerTranscript.contains(keyword)
                    }
                    if (!hasFlashlightWord) {
                        Log.w(TAG, "HARD GUARDRAIL BLOCKED hallucinated FLASHLIGHT task for transcript: '$cleanTranscript'")
                    }
                    hasFlashlightWord
                }
                else -> true
            }
        }.mapIndexed { idx, task ->
            task.copy(stepNumber = idx + 1)
        }

        // If all tasks were filtered out by the guardrail, downgrade to PURE_CONVERSATION
        if (verifiedTasks.isEmpty()) {
            return response.copy(
                intentCategory = NluIntentCategory.PURE_CONVERSATION,
                tasksToExecute = emptyList()
            )
        }

        return response.copy(tasksToExecute = verifiedTasks)
    }

    fun isLearnCorrectionStatement(transcript: String): Boolean {
        val lower = transcript.lowercase().trim()
        return lower.startsWith("you made a mistake") ||
            lower.startsWith("don't do that") ||
            lower.startsWith("remember that when i say") ||
            lower.startsWith("whenever i say") ||
            lower.startsWith("yaad rakho") ||
            lower.contains("ka matlab") && lower.contains("yaad")
    }

    /**
     * Evaluates raw audio bytes (`audio/wav`) with Gemini (`temperature = 0.0`, `topP = 0.1`, strict `responseSchema`),
     * then runs the Hard Guardrail Validator before returning.
     */
    suspend fun analyzeAudioDeterministically(
        context: Context,
        wavData: ByteArray,
        rawMimeType: String = "audio/wav"
    ): DeterministicNluResponse = withContext(Dispatchers.IO) {
        val cleanMime = cleanAudioMimeType(rawMimeType)
        val learnedRules = getLearnedRules(context)
        val systemInstruction = buildSystemInstruction(learnedRules)
        val userPrompt = """
            Listen carefully to the attached `audio/wav` recording. Even if there is background noise or a strong accent, always extract the spoken English, Urdu, or Roman Urdu words into `verbatimTranscript` and fulfill the user's request. ONLY ask the user to repeat if the audio contains literally 0% human speech (complete silence or pure static).
            Return a JSON object with these exact 8 fields:
            {
              "verbatimTranscript": "exact words spoken by the user",
              "detectedLanguage": "ENGLISH | URDU_SCRIPT | ROMAN_URDU_MIXED",
              "audioClarityScore": 95,
              "chainOfThoughtReasoning": "step-by-step analysis",
              "intentCategory": "PURE_CONVERSATION | CLARIFICATION_NEEDED | LEARN_CORRECTION_RULE | SINGLE_DEVICE_TASK | MULTI_STEP_WORKFLOW",
              "confidenceScore": 98,
              "tasksToExecute": [],
              "spokenReply": "natural reply in the user's language"
            }
        """.trimIndent()

        // 1. Try REST Gemini API with preferred models (`gemini-3.8-flash`, `gemini-4-argon`, `gemini-2.5-flash`)
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY") {
            val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)
            val modelsToTry = SuperAgiBrainEngine.PREFERRED_GEMINI_MODELS

            for (useSchema in listOf(true)) {
                val requestJson = JSONObject().apply {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", systemInstruction))
                        })
                    })
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
                                put(JSONObject().put("text", userPrompt))
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.0)
                        put("topP", 0.1)
                        put("responseMimeType", "application/json")
                        if (useSchema) {
                            put("responseSchema", buildRestResponseSchemaJson(includePropertyOrdering = false))
                        }
                    })
                }

                val bodyString = requestJson.toString()
                for (modelName in modelsToTry) {
                    try {
                        val req = Request.Builder()
                            .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey")
                            .post(bodyString.toRequestBody("application/json".toMediaType()))
                            .build()

                        val resp = httpClient.newCall(req).execute()
                        if (!resp.isSuccessful) continue
                        val respBody = resp.body?.string() ?: continue
                        val root = JSONObject(respBody)
                        val candidateText = root.optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text", "")
                            ?.trim()
                            .orEmpty()

                        if (candidateText.isNotBlank()) {
                            parseDeterministicJson(candidateText)?.let { parsed ->
                                val validated = enforceHardGuardrailValidator(parsed)
                                if (validated.intentCategory == NluIntentCategory.LEARN_CORRECTION_RULE) {
                                    saveLearnedRule(context, validated.verbatimTranscript)
                                }
                                return@withContext validated
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "REST deterministic NLU ($modelName, schema=$useSchema) failed: ${e.message}")
                    }
                }
            }
        }

        // 2. Try Firebase AI Logic SDK (`gemini-3.8-flash`, `gemini-4-argon`, `gemini-2.5-flash`) with `temperature = 0.0f`, `topP = 0.1f`
        for (sdkModelName in SuperAgiBrainEngine.PREFERRED_GEMINI_MODELS) {
            for (useSchema in listOf(true)) {
                try {
                    val config = generationConfig {
                        temperature = 0.0f
                        topP = 0.1f
                        responseMimeType = "application/json"
                        if (useSchema) {
                            responseSchema = buildSdkResponseSchema()
                        }
                    }
                    val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                        modelName = sdkModelName,
                        generationConfig = config,
                        systemInstruction = content { text(systemInstruction) }
                    )
                    val sdkResp = model.generateContent(
                        content {
                            inlineData(wavData, cleanMime)
                            text(userPrompt)
                        }
                    )
                    val sdkText = sdkResp.text?.trim()
                    if (!sdkText.isNullOrBlank()) {
                        parseDeterministicJson(sdkText)?.let { parsed ->
                            val validated = enforceHardGuardrailValidator(parsed)
                            if (validated.intentCategory == NluIntentCategory.LEARN_CORRECTION_RULE) {
                                saveLearnedRule(context, validated.verbatimTranscript)
                            }
                            return@withContext validated
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "Firebase AI SDK deterministic audio NLU ($sdkModelName, schema=$useSchema) unavailable: ${e.message}")
                }
            }
        }

        // 3. Safe Non-Hallucinating Fallback (CLARIFICATION_NEEDED, 0 tools)
        return@withContext DeterministicNluResponse(
            verbatimTranscript = "",
            detectedLanguage = NluLanguageEnum.ROMAN_URDU_MIXED,
            audioClarityScore = 30,
            chainOfThoughtReasoning = "No speech transcription could be verified. Blocking all device tasks.",
            intentCategory = NluIntentCategory.CLARIFICATION_NEEDED,
            confidenceScore = 0,
            tasksToExecute = emptyList(),
            spokenReply = CLARIFICATION_REPLY_ROMAN_URDU
        )
    }

    /**
     * Evaluates a text or pre-transcribed command using the exact same Deterministic NLU & Hard Guardrail architecture.
     */
    fun analyzeTextDeterministicallyLocal(
        context: Context,
        rawText: String
    ): DeterministicNluResponse {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty() || trimmed.equals("[Unclear Audio]", ignoreCase = true)) {
            return DeterministicNluResponse(
                verbatimTranscript = "",
                detectedLanguage = NluLanguageEnum.ROMAN_URDU_MIXED,
                audioClarityScore = 25,
                chainOfThoughtReasoning = "Input audio was silent or unclear (<60% clarity). Returning CLARIFICATION_NEEDED with zero tools.",
                intentCategory = NluIntentCategory.CLARIFICATION_NEEDED,
                confidenceScore = 0,
                tasksToExecute = emptyList(),
                spokenReply = CLARIFICATION_REPLY_ROMAN_URDU
            )
        }

        val detectedLang = LanguageProcessor.detectLanguage(trimmed)
        val nluLang = when (detectedLang) {
            DetectedLanguage.URDU_NASTALIQ -> NluLanguageEnum.URDU_SCRIPT
            DetectedLanguage.ROMAN_URDU, DetectedLanguage.MIXED -> NluLanguageEnum.ROMAN_URDU_MIXED
            DetectedLanguage.ENGLISH -> NluLanguageEnum.ENGLISH
        }

        // 1. Check Self-Learning "Teach Me" Rule
        if (isLearnCorrectionStatement(trimmed)) {
            saveLearnedRule(context, trimmed)
            val reply = when (nluLang) {
                NluLanguageEnum.URDU_SCRIPT -> "شکریہ! میں نے آپ کا نیا اصول یاد کر لیا ہے اور آئندہ ہمیشہ اس پر عمل کروں گا۔"
                NluLanguageEnum.ROMAN_URDU_MIXED -> "Shukriya! Maine aapka yeh rule apne Self-Learning Memory Bank mein save kar liya hai."
                NluLanguageEnum.ENGLISH -> "Got it! I have saved this correction rule to my Self-Learning Memory Bank and will follow it on every request."
            }
            return enforceHardGuardrailValidator(
                DeterministicNluResponse(
                    verbatimTranscript = trimmed,
                    detectedLanguage = nluLang,
                    audioClarityScore = 98,
                    chainOfThoughtReasoning = "User is teaching a correction or personal rule. Classified as LEARN_CORRECTION_RULE and persisted to omni_learned_rules with zero hardware tasks.",
                    intentCategory = NluIntentCategory.LEARN_CORRECTION_RULE,
                    confidenceScore = 99,
                    tasksToExecute = emptyList(),
                    spokenReply = reply
                )
            )
        }

        // 2. Check User-Taught Custom Trigger Rules in `omni_learned_rules`
        val learnedRules = getLearnedRules(context)

        // 3. Extract all explicit single or multi-step device tasks via SuperAgiBrainEngine
        val tasks = SuperAgiBrainEngine.extractAllDeviceTasks(context, trimmed).toMutableList()

        // 4. If no device tasks matched, or if it's a pure conversational/knowledge/coding/math query,
        // answer intelligently using SuperAgiBrainEngine
        if (tasks.isEmpty()) {
            val smartReply = SuperAgiBrainEngine.generateLocalSuperAgiResponse(
                context = context,
                rawText = trimmed,
                detectedLang = nluLang
            )
            return enforceHardGuardrailValidator(
                DeterministicNluResponse(
                    verbatimTranscript = trimmed,
                    detectedLanguage = nluLang,
                    audioClarityScore = 98,
                    chainOfThoughtReasoning = "Analyzed user query with SuperAgiBrainEngine (${learnedRules.size} learned rules active). Generated intelligent conversational/analytical response.",
                    intentCategory = NluIntentCategory.PURE_CONVERSATION,
                    confidenceScore = 99,
                    tasksToExecute = emptyList(),
                    spokenReply = smartReply
                )
            )
        }

        val category = if (tasks.size == 1) {
            NluIntentCategory.SINGLE_DEVICE_TASK
        } else {
            NluIntentCategory.MULTI_STEP_WORKFLOW
        }

        val reasoning = "User explicitly commanded ${tasks.size} action(s): ${tasks.joinToString(" -> ") { "${it.taskType}(${it.target}:${it.actionValue})" }}. Verified against Hard Guardrail."
        val reply = SuperAgiBrainEngine.buildSpokenSummaryForTasks(tasks, nluLang)

        return enforceHardGuardrailValidator(
            DeterministicNluResponse(
                verbatimTranscript = trimmed,
                detectedLanguage = nluLang,
                audioClarityScore = 98,
                chainOfThoughtReasoning = reasoning,
                intentCategory = category,
                confidenceScore = 99,
                tasksToExecute = tasks,
                spokenReply = reply
            )
        )
    }

    private fun extractImagePrompt(command: String): String {
        val match = Regex("""(?:image|tasveer|photo|dp)\s+(?:of\s+|ki\s+)?([^,.]+?)(?:\s+aur|\s+and|\s+upload|\s+set|\s+laga|$)""", RegexOption.IGNORE_CASE)
            .find(command)?.groupValues?.getOrNull(1)?.trim()
        return if (!match.isNullOrBlank() && match.length > 3) {
            "$match, ultra detailed 8k"
        } else {
            "futuristic cyber lion, ultra detailed 8k"
        }
    }

    /**
     * Parses raw JSON returned by Gemini into [DeterministicNluResponse].
     */
    fun parseDeterministicJson(rawJsonText: String): DeterministicNluResponse? {
        return try {
            val clean = rawJsonText.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val obj = JSONObject(clean)

            val verbatimTranscript = obj.optString(
                "verbatimTranscript",
                obj.optString("transcript", "")
            ).trim()

            val detectedLanguage = NluLanguageEnum.fromString(
                obj.optString("detectedLanguage", "ENGLISH")
            )

            val audioClarityScore = obj.optInt("audioClarityScore", 90).coerceIn(0, 100)
            val chainOfThoughtReasoning = obj.optString(
                "chainOfThoughtReasoning",
                "Evaluated user intent deterministically at temperature 0.0."
            )
            val intentCategory = NluIntentCategory.fromString(
                obj.optString("intentCategory", obj.optString("intentType", "PURE_CONVERSATION"))
            )
            val confidenceScore = obj.optInt("confidenceScore", 90).coerceIn(0, 100)
            val spokenReply = obj.optString(
                "spokenReply",
                obj.optString("reply", "")
            ).trim()

            val tasksArray = obj.optJSONArray("tasksToExecute") ?: JSONArray()
            val parsedTasks = mutableListOf<DeterministicNluTask>()
            for (i in 0 until tasksArray.length()) {
                val item = tasksArray.optJSONObject(i) ?: continue
                val taskType = NluTaskType.fromString(item.optString("taskType")) ?: continue
                parsedTasks.add(
                    DeterministicNluTask(
                        stepNumber = item.optInt("stepNumber", i + 1),
                        taskType = taskType,
                        target = item.optString("target", ""),
                        actionValue = item.optString("actionValue", ""),
                        androidDeepLink = item.optString("androidDeepLink", "")
                    )
                )
            }

            DeterministicNluResponse(
                verbatimTranscript = verbatimTranscript,
                detectedLanguage = detectedLanguage,
                audioClarityScore = audioClarityScore,
                chainOfThoughtReasoning = chainOfThoughtReasoning,
                intentCategory = intentCategory,
                confidenceScore = confidenceScore,
                tasksToExecute = parsedTasks,
                spokenReply = spokenReply
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse DeterministicNluResponse JSON: ${e.message}")
            null
        }
    }
}

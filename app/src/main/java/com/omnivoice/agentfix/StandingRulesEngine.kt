package com.omnivoice.agentfix

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class StandingRule(
    val id: String,
    val triggerType: String,     // "notification_received", "call_received", "app_opened", "phrase_spoken", "always"
    val triggerFilter: String,   // e.g. "whatsapp", "Ali", "battery_low", or phrase
    val actionType: String,      // "speak_aloud", "auto_reply", "open_app", "run_tool", "instruction"
    val actionPayload: String,   // e.g. "Mujhe batao", "Main busy hoon", "com.whatsapp"
    val rawRuleText: String,
    val createdAtMs: Long,
    val enabled: Boolean = true,
    val triggerCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("triggerType", triggerType)
        put("triggerFilter", triggerFilter)
        put("actionType", actionType)
        put("actionPayload", actionPayload)
        put("rawRuleText", rawRuleText)
        put("createdAtMs", createdAtMs)
        put("enabled", enabled)
        put("triggerCount", triggerCount)
    }

    companion object {
        fun fromJson(obj: JSONObject): StandingRule = StandingRule(
            id = obj.optString("id", UUID.randomUUID().toString().take(8)),
            triggerType = obj.optString("triggerType", "always"),
            triggerFilter = obj.optString("triggerFilter", ""),
            actionType = obj.optString("actionType", "instruction"),
            actionPayload = obj.optString("actionPayload", ""),
            rawRuleText = obj.optString("rawRuleText", ""),
            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
            enabled = obj.optBoolean("enabled", true),
            triggerCount = obj.optInt("triggerCount", 0)
        )
    }
}

data class StandingRuleExecutionRecord(
    val ruleId: String,
    val eventType: String,
    val summary: String,
    val timestampMs: Long
)

object StandingRulesEngine {
    private const val PREFS_NAME = "agentfix_standing_rules"
    private const val KEY_RULES_JSON = "standing_rules_array"
    private val recentExecutions = CopyOnWriteArrayList<StandingRuleExecutionRecord>()

    private val naturalRulePatterns = listOf(
        Regex("(?i)^(?:ab se|aaj se|from now on|hamesha)\\s+(?:jab bhi|whenever|if)\\s+(.+?)\\s+(?:to|tou|then)\\s+(.+)$"),
        Regex("(?i)^(?:jab bhi|whenever)\\s+(.+?)\\s+(?:aaye|aye|ho|arrives|happens)\\s*(?:to|tou|then)?\\s+(.+)$"),
        Regex("(?i)^(?:remember that|yaad rakhna ke|rule banao ke)\\s+(.+)$")
    )

    @Synchronized
    fun getAllRules(context: Context): List<StandingRule> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RULES_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<StandingRule>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                list.add(StandingRule.fromJson(item))
            }
            list
        } catch (e: Exception) {
            AgentLog.w("StandingRules", "Failed to parse standing rules: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    private fun saveAllRules(context: Context, rules: List<StandingRule>): Boolean {
        return try {
            val arr = JSONArray()
            for (r in rules) arr.put(r.toJson())
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RULES_JSON, arr.toString())
                .commit()
        } catch (e: Exception) {
            AgentLog.e("StandingRules", "Failed to save standing rules", e)
            false
        }
    }

    fun teachStandingRule(
        context: Context,
        triggerType: String = "",
        triggerFilter: String = "",
        actionType: String = "",
        actionPayload: String = "",
        ruleText: String = ""
    ): ToolResult {
        val cleanText = ruleText.trim().ifBlank {
            buildString {
                if (triggerType.isNotBlank()) append("When $triggerType")
                if (triggerFilter.isNotBlank()) append(" ($triggerFilter)")
                if (actionType.isNotBlank()) append(" -> $actionType")
                if (actionPayload.isNotBlank()) append(": $actionPayload")
            }.trim()
        }
        if (cleanText.isEmpty() && triggerType.isBlank() && actionPayload.isBlank()) {
            return ToolResult.failure(
                tool = "teach_standing_rule",
                message = "Standing rule text or trigger/action parameters cannot be empty.",
                errorCode = "EMPTY_RULE"
            )
        }

        val inferred = inferTriggerAndAction(cleanText, triggerType, triggerFilter, actionType, actionPayload)
        val existing = getAllRules(context).toMutableList()
        val duplicateIdx = existing.indexOfFirst {
            Txt.norm(it.rawRuleText) == Txt.norm(cleanText) ||
                (it.triggerType == inferred.triggerType &&
                    Txt.norm(it.triggerFilter) == Txt.norm(inferred.triggerFilter) &&
                    it.actionType == inferred.actionType &&
                    Txt.norm(it.actionPayload) == Txt.norm(inferred.actionPayload))
        }

        val savedRule = if (duplicateIdx >= 0) {
            val updated = existing[duplicateIdx].copy(
                triggerType = inferred.triggerType,
                triggerFilter = inferred.triggerFilter,
                actionType = inferred.actionType,
                actionPayload = inferred.actionPayload,
                rawRuleText = cleanText,
                enabled = true
            )
            existing[duplicateIdx] = updated
            updated
        } else {
            inferred.also { existing.add(it) }
        }

        val committed = saveAllRules(context, existing)
        val readBack = getAllRules(context).firstOrNull { it.id == savedRule.id }
        val verified = committed && readBack != null

        if (!verified) {
            return ToolResult.failure(
                tool = "teach_standing_rule",
                message = "Failed to persist standing rule '${savedRule.rawRuleText}' to local storage.",
                errorCode = "PERSISTENCE_FAILED"
            )
        }

        AgentLog.i("StandingRules", "Taught standing rule [${savedRule.id}]: ${savedRule.rawRuleText}")
        return ToolResult.success(
            tool = "teach_standing_rule",
            message = "Saved standing rule [${savedRule.id}]: \"${savedRule.rawRuleText}\" (trigger=${savedRule.triggerType}, action=${savedRule.actionType}).",
            verified = true,
            verificationNote = "Rule persisted in SharedPreferences and verified via read-back (totalRules=${existing.size}).",
            data = JSONObject().apply {
                put("rule", savedRule.toJson())
                put("totalRules", existing.size)
            }
        )
    }

    fun listStandingRules(context: Context): ToolResult {
        val rules = getAllRules(context)
        val arr = JSONArray()
        for (r in rules) arr.put(r.toJson())
        val summary = if (rules.isEmpty()) {
            "No standing rules are currently saved."
        } else {
            "Active standing rules (${rules.size}): " + rules.joinToString("; ") {
                "[${it.id}] ${it.rawRuleText} (${it.triggerType} -> ${it.actionType})"
            }
        }
        return ToolResult.success(
            tool = "list_standing_rules",
            message = summary,
            verified = true,
            verificationNote = "Read ${rules.size} standing rule(s) from persistent store.",
            data = JSONObject().apply {
                put("count", rules.size)
                put("rules", arr)
            }
        )
    }

    fun removeStandingRule(context: Context, ruleIdOrQuery: String): ToolResult {
        val q = ruleIdOrQuery.trim()
        if (q.isEmpty()) {
            return ToolResult.failure("remove_standing_rule", "Rule ID or search text is required.", "EMPTY_QUERY")
        }
        val rules = getAllRules(context).toMutableList()
        if (q.equals("all", ignoreCase = true)) {
            val count = rules.size
            val ok = saveAllRules(context, emptyList())
            val remaining = getAllRules(context).size
            return if (ok && remaining == 0) {
                ToolResult.success(
                    tool = "remove_standing_rule",
                    message = "Cleared all $count standing rule(s).",
                    verified = true,
                    verificationNote = "Store read-back confirmed 0 rules remaining.",
                    data = JSONObject().put("removedCount", count)
                )
            } else {
                ToolResult.failure("remove_standing_rule", "Could not clear standing rules.", "CLEAR_FAILED")
            }
        }

        val target = rules.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: rules.firstOrNull {
                it.rawRuleText.contains(q, ignoreCase = true) ||
                    it.triggerFilter.contains(q, ignoreCase = true) ||
                    it.actionPayload.contains(q, ignoreCase = true)
            }

        if (target == null) {
            return ToolResult(
                ok = false,
                status = ToolStatus.NOT_FOUND.name,
                tool = "remove_standing_rule",
                message = "No standing rule matching '$q' was found.",
                verified = false,
                verificationNote = "Searched ${rules.size} saved rule(s).",
                errorCode = "RULE_NOT_FOUND"
            )
        }

        rules.removeAll { it.id == target.id }
        val ok = saveAllRules(context, rules)
        val stillPresent = getAllRules(context).any { it.id == target.id }
        if (!ok || stillPresent) {
            return ToolResult.failure("remove_standing_rule", "Failed to remove rule [${target.id}].", "REMOVE_FAILED")
        }
        return ToolResult.success(
            tool = "remove_standing_rule",
            message = "Removed standing rule [${target.id}]: \"${target.rawRuleText}\".",
            verified = true,
            verificationNote = "Verified rule [${target.id}] is no longer present (${rules.size} remaining).",
            data = JSONObject().apply {
                put("removedRule", target.toJson())
                put("remainingCount", rules.size)
            }
        )
    }

    fun onUserUtterance(context: Context, utterance: String): StandingRule? {
        val trimmed = utterance.trim()
        for (regex in naturalRulePatterns) {
            val match = regex.find(trimmed)
            if (match != null) {
                val res = teachStandingRule(context = context, ruleText = trimmed)
                if (res.ok) {
                    val ruleJson = res.data.optJSONObject("rule")
                    if (ruleJson != null) return StandingRule.fromJson(ruleJson)
                }
            }
        }
        return null
    }

    fun onNotificationPosted(
        context: Context,
        packageName: String,
        title: String,
        text: String
    ): List<StandingRuleExecutionRecord> {
        val activeRules = getAllRules(context).filter {
            it.enabled && (it.triggerType == "notification_received" || it.triggerType == "always")
        }
        if (activeRules.isEmpty()) return emptyList()

        val matchedRecords = mutableListOf<StandingRuleExecutionRecord>()
        val combinedSource = "$packageName $title $text".lowercase(Locale.ROOT)
        val allRules = getAllRules(context).toMutableList()
        var modified = false

        for (rule in activeRules) {
            val filterLower = rule.triggerFilter.trim().lowercase(Locale.ROOT)
            val matchesFilter = filterLower.isEmpty() ||
                filterLower == "any" ||
                 filterLower == "all" ||
                combinedSource.contains(filterLower) ||
                Txt.containsAllTokens(combinedSource, filterLower)

            if (matchesFilter) {
                if (rule.actionType == "speak_aloud") {
                    com.example.engine.AssistantAudioStateMachine.shared.notifyAssistantSpeechStarted(
                        spokenText = "$title $text ${rule.actionPayload}",
                        source = "NOTIFICATION_ANNOUNCEMENT"
                    )
                }
                val summary = "StandingRule[${rule.id}] triggered on notification from $packageName ($title: $text) -> ${rule.actionType}: ${rule.actionPayload}"
                val record = StandingRuleExecutionRecord(
                    ruleId = rule.id,
                    eventType = "notification_received",
                    summary = summary,
                    timestampMs = System.currentTimeMillis()
                )
                recentExecutions.add(record)
                while (recentExecutions.size > 50) {
                    try { recentExecutions.removeAt(0) } catch (_: Exception) { break }
                }
                matchedRecords.add(record)
                AgentLog.i("StandingRules", summary)

                val idx = allRules.indexOfFirst { it.id == rule.id }
                if (idx >= 0) {
                    allRules[idx] = allRules[idx].copy(triggerCount = allRules[idx].triggerCount + 1)
                    modified = true
                }
            }
        }
        if (modified) {
            saveAllRules(context, allRules)
        }
        return matchedRecords
    }

    fun getRecentExecutions(): List<StandingRuleExecutionRecord> = recentExecutions.toList()

    fun formatActiveRulesForPrompt(context: Context): String {
        val rules = getAllRules(context).filter { it.enabled }
        if (rules.isEmpty()) return ""
        return buildString {
            appendLine("=== USER-TAUGHT STANDING RULES (ALWAYS OBEY) ===")
            for (r in rules) {
                appendLine("- [${r.id}] (${r.triggerType} filter='${r.triggerFilter}' -> ${r.actionType}): ${r.rawRuleText}")
            }
        }
    }

    private fun inferTriggerAndAction(
        rawText: String,
        explicitTriggerType: String,
        explicitTriggerFilter: String,
        explicitActionType: String,
        explicitActionPayload: String
    ): StandingRule {
        val lower = rawText.lowercase(Locale.ROOT)
        val tType = explicitTriggerType.trim().lowercase(Locale.ROOT).ifBlank {
            when {
                lower.contains("notification") || lower.contains("message aaye") || lower.contains("msg aaye") -> "notification_received"
                lower.contains("call aaye") || lower.contains("incoming call") || lower.contains("phone aaye") -> "call_received"
                lower.contains("open") && (lower.contains("jab") || lower.contains("when")) -> "app_opened"
                else -> "always"
            }
        }
        val tFilter = explicitTriggerFilter.trim().ifBlank {
            when {
                lower.contains("whatsapp") -> "whatsapp"
                lower.contains("instagram") -> "instagram"
                lower.contains("sms") -> "messaging"
                else -> "any"
            }
        }
        val aType = explicitActionType.trim().lowercase(Locale.ROOT).ifBlank {
            when {
                lower.contains("bata") || lower.contains("announce") || lower.contains("read aloud") || lower.contains("speak") -> "speak_aloud"
                lower.contains("reply") || lower.contains("jawab") -> "auto_reply"
                else -> "instruction"
            }
        }
        val aPayload = explicitActionPayload.trim().ifBlank { rawText }
        return StandingRule(
            id = "rule_" + UUID.randomUUID().toString().replace("-", "").take(6),
            triggerType = tType,
            triggerFilter = tFilter,
            actionType = aType,
            actionPayload = aPayload,
            rawRuleText = rawText,
            createdAtMs = System.currentTimeMillis(),
            enabled = true,
            triggerCount = 0
        )
    }
}

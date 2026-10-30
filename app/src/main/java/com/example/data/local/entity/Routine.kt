package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.example.data.model.VoiceAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a single tool call step within a multi-step voice [Routine].
 */
data class RoutineToolCall(
    val toolName: String,
    val target: String = "",
    val parameters: Map<String, String> = emptyMap(),
    val delayAfterMs: Long = 300L,
    val requiresUnlock: Boolean = false
) {
    fun toVoiceAction(spokenResponse: String = ""): VoiceAction {
        return VoiceAction(
            action = toolName,
            target = target,
            parameters = parameters,
            confirmationRequired = false,
            spokenResponse = spokenResponse,
            requiresUnlock = requiresUnlock
        )
    }

    companion object {
        fun fromVoiceAction(action: VoiceAction, delayAfterMs: Long = 300L): RoutineToolCall {
            return RoutineToolCall(
                toolName = action.action,
                target = action.target,
                parameters = action.parameters,
                delayAfterMs = delayAfterMs,
                requiresUnlock = action.requiresUnlock
            )
        }
    }
}

/**
 * Room [TypeConverter]s for serializing and deserializing a list of [RoutineToolCall] objects
 * into a JSON column so a single [Routine] can group multiple ordered tool calls.
 */
class RoutineConverters {

    @TypeConverter
    fun fromToolCallList(toolCalls: List<RoutineToolCall>?): String {
        if (toolCalls.isNullOrEmpty()) return "[]"
        val jsonArray = JSONArray()
        for (call in toolCalls) {
            val obj = JSONObject().apply {
                put("toolName", call.toolName)
                put("target", call.target)
                put("delayAfterMs", call.delayAfterMs)
                put("requiresUnlock", call.requiresUnlock)
                val paramsObj = JSONObject()
                for ((k, v) in call.parameters) {
                    paramsObj.put(k, v)
                }
                put("parameters", paramsObj)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    @TypeConverter
    fun toToolCallList(raw: String?): List<RoutineToolCall> {
        if (raw.isNullOrBlank()) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            return try {
                val jsonArray = JSONArray(trimmed)
                val result = mutableListOf<RoutineToolCall>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val toolName = obj.optString("toolName", "").ifBlank {
                        obj.optString("action", "")
                    }
                    if (toolName.isBlank()) continue
                    val target = obj.optString("target", "")
                    val delayAfterMs = obj.optLong("delayAfterMs", 300L)
                    val requiresUnlock = obj.optBoolean("requiresUnlock", false)
                    val paramsMap = mutableMapOf<String, String>()
                    val paramsObj = obj.optJSONObject("parameters")
                    if (paramsObj != null) {
                        val keys = paramsObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            paramsMap[key] = paramsObj.optString(key, "")
                        }
                    }
                    result.add(
                        RoutineToolCall(
                            toolName = toolName,
                            target = target,
                            parameters = paramsMap,
                            delayAfterMs = delayAfterMs,
                            requiresUnlock = requiresUnlock
                        )
                    )
                }
                result
            } catch (_: Exception) {
                emptyList()
            }
        }
        return parseLegacyPayload(trimmed)
    }

    companion object {
        /**
         * Parses pipe-delimited legacy payloads such as:
         * "set_alarm:07:00|toggle_flashlight:off|adjust_volume:mute"
         */
        fun parseLegacyPayload(payload: String): List<RoutineToolCall> {
            if (payload.isBlank()) return emptyList()
            return payload.split("|")
                .mapNotNull { token ->
                    val trimmed = token.trim()
                    if (trimmed.isEmpty()) return@mapNotNull null
                    val colonIndex = trimmed.indexOf(':')
                    if (colonIndex > 0) {
                        val tool = trimmed.substring(0, colonIndex).trim()
                        val target = trimmed.substring(colonIndex + 1).trim()
                        RoutineToolCall(toolName = tool, target = target)
                    } else {
                        RoutineToolCall(toolName = trimmed)
                    }
                }
        }

        fun formatLegacyPayload(toolCalls: List<RoutineToolCall>): String {
            return toolCalls.joinToString("|") { call ->
                if (call.target.isNotBlank()) "${call.toolName}:${call.target}" else call.toolName
            }
        }
    }
}

/**
 * Room entity representing a custom voice automation routine that groups
 * multiple ordered tool calls into a single spoken trigger phrase.
 */
@Entity(
    tableName = "routines",
    indices = [
        Index(value = ["routine_key"], unique = true),
        Index(value = ["trigger_phrase"])
    ]
)
data class Routine(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "routine_key")
    val routineKey: String,

    @ColumnInfo(name = "trigger_phrase")
    val triggerPhrase: String,

    @ColumnInfo(name = "description")
    val description: String,

    @ColumnInfo(name = "tool_calls")
    val toolCalls: List<RoutineToolCall> = emptyList(),

    @ColumnInfo(name = "actions_payload")
    val actionsPayload: String = RoutineConverters.formatLegacyPayload(toolCalls),

    @ColumnInfo(name = "confirmation_message")
    val confirmationMessage: String,

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "execution_count")
    val executionCount: Int = 0,

    @ColumnInfo(name = "last_executed_at")
    val lastExecutedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Returns the ordered list of tool calls for this routine, falling back to
     * parsing [actionsPayload] if [toolCalls] was not populated directly.
     */
    fun resolvedToolCalls(): List<RoutineToolCall> {
        if (toolCalls.isNotEmpty()) return toolCalls
        return RoutineConverters.parseLegacyPayload(actionsPayload)
    }
}

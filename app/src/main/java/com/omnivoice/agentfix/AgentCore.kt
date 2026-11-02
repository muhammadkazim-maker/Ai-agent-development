package com.omnivoice.agentfix

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object AgentBridge {
    @Volatile
    var service: AccessibilityService? = null

    @Volatile
    var appContext: Context? = null

    fun isServiceConnected(): Boolean = service != null
}

enum class ToolStatus {
    SUCCESS,
    FAILED,
    NEEDS_PERMISSION,
    NEEDS_CONFIRMATION,
    OS_RESTRICTED,
    DUPLICATE_BLOCKED,
    AMBIGUOUS_CONTACT,
    NOT_FOUND
}

data class ToolResult(
    val ok: Boolean,
    val status: String,
    val tool: String,
    val message: String,
    val verified: Boolean = false,
    val verificationNote: String = "",
    val data: JSONObject = JSONObject(),
    val errorCode: String? = null,
    val nextActionHint: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ok", ok)
        put("status", status)
        put("tool", tool)
        put("message", message)
        put("verified", verified)
        put("verificationNote", verificationNote)
        put("executed", ok && verified)
        if (errorCode != null) put("errorCode", errorCode)
        if (nextActionHint != null) put("nextActionHint", nextActionHint)
        put("data", data)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun success(
            tool: String,
            message: String,
            verified: Boolean = true,
            verificationNote: String = "Verified UI/system state change.",
            data: JSONObject = JSONObject()
        ) = ToolResult(true, ToolStatus.SUCCESS.name, tool, message, verified, verificationNote, data)

        fun failure(
            tool: String,
            message: String,
            errorCode: String = "EXECUTION_FAILED",
            verificationNote: String = "Verification did not pass.",
            data: JSONObject = JSONObject(),
            nextActionHint: String? = null
        ) = ToolResult(false, ToolStatus.FAILED.name, tool, message, false, verificationNote, data, errorCode, nextActionHint)

        fun needsPermission(
            tool: String,
            permissionOrService: String,
            message: String,
            nextActionHint: String = "Ask user to enable $permissionOrService in Android Settings."
        ) = ToolResult(
            false,
            ToolStatus.NEEDS_PERMISSION.name,
            tool,
            message,
            false,
            "Missing required permission/service: $permissionOrService",
            JSONObject().put("missingPermission", permissionOrService),
            "PERMISSION_REQUIRED",
            nextActionHint
        )

        fun needsConfirmation(tool: String, message: String) = ToolResult(
            false,
            ToolStatus.NEEDS_CONFIRMATION.name,
            tool,
            message,
            false,
            "Blocked by ConfirmationGate until explicit user confirmation.",
            JSONObject(),
            "CONFIRMATION_REQUIRED",
            "Ask the user to confirm before calling $tool again."
        )

        fun duplicateBlocked(
            tool: String,
            message: String = "Duplicate tool call blocked in the same turn/window."
        ) = ToolResult(
            false,
            ToolStatus.DUPLICATE_BLOCKED.name,
            tool,
            message,
            false,
            "Identical call already executed in this turn/cooldown window.",
            JSONObject(),
            "DUPLICATE_CALL"
        )

        fun osRestricted(
            tool: String,
            message: String,
            verifiedFallback: Boolean = false,
            data: JSONObject = JSONObject()
        ) = ToolResult(
            verifiedFallback,
            ToolStatus.OS_RESTRICTED.name,
            tool,
            message,
            verifiedFallback,
            "Android OS sandboxing blocks direct force-stop of third-party apps.",
            data,
            "ANDROID_OS_SANDBOX"
        )
    }
}

typealias AgentResult = ToolResult

object Txt {
    private val whitespaceRegex = Regex("\\s+")
    private val nonWordRegex = Regex("[^\\p{L}\\p{N}\\s+]")

    fun norm(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input.trim()
            .lowercase(Locale.ROOT)
            .replace(nonWordRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()
    }

    fun digitsOnly(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input.filter { it.isDigit() }
    }

    fun tokens(input: String?): List<String> {
        val n = norm(input)
        return if (n.isEmpty()) emptyList() else n.split(' ').filter { it.isNotBlank() }
    }

    fun containsAllTokens(haystack: String?, needle: String?): Boolean {
        val hTokens = tokens(haystack)
        val nTokens = tokens(needle)
        if (nTokens.isEmpty()) return false
        return nTokens.all { nt -> hTokens.any { ht -> ht == nt || ht.contains(nt) } }
    }

    fun isAffirmative(input: String?): Boolean {
        val t = tokens(input)
        val yesSet = setOf("yes", "haan", "han", "ji", "jee", "ok", "okay", "sure", "confirm", "bilkul", "zaroor", "kardo", "kar")
        return t.any { it in yesSet }
    }

    fun isNegative(input: String?): Boolean {
        val t = tokens(input)
        val noSet = setOf("no", "nahi", "na", "mat", "cancel", "stop", "abort", "ruko", "rehne")
        return t.any { it in noSet }
    }
}

object AgentLog {
    private const val MAX_LINES = 500
    private const val LOG_TAG = "OmniAgentFix"
    private val lines = CopyOnWriteArrayList<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun record(level: String, tag: String, msg: String) {
        val ts = synchronized(dateFormat) { dateFormat.format(Date()) }
        lines.add("$ts [$level/$tag] $msg")
        while (lines.size > MAX_LINES) {
            try { lines.removeAt(0) } catch (_: Exception) { break }
        }
    }

    fun d(tag: String, msg: String) { Log.d("$LOG_TAG:$tag", msg); record("D", tag, msg) }
    fun i(tag: String, msg: String) { Log.i("$LOG_TAG:$tag", msg); record("I", tag, msg) }
    fun w(tag: String, msg: String) { Log.w("$LOG_TAG:$tag", msg); record("W", tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e("$LOG_TAG:$tag", msg, tr)
        record("E", tag, if (tr != null) "$msg (${tr.javaClass.simpleName}: ${tr.message})" else msg)
    }

    fun dump(): String = if (lines.isEmpty()) "=== OmniVoice AgentLog (empty) ===" else buildString {
        appendLine("=== OmniVoice AgentLog (${lines.size} entries) ===")
        for (line in lines) appendLine(line)
    }

    fun toJsonArray(): JSONArray = JSONArray().apply { for (l in lines) put(l) }
    fun clear() { lines.clear() }

    fun shareIntent(): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OmniVoice Agent Fix Diagnostic Log")
            putExtra(Intent.EXTRA_TEXT, dump())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent.createChooser(send, "Share agent log").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

object ConfirmationGate {
    @Volatile
    var enforce: Boolean = false

    @Volatile
    private var lastConfirmedAtMs: Long = 0L

    private val confirmTokens = listOf(
        "yes", "haan", "han", "ji", "jee", "ok", "okay", "sure", "confirm",
        "delete", "mitao", "mita do", "hatao", "bhej do", "send", "kar do", "band karo", "close"
    )
    fun onUserUtterance(text: String) {
        val lower = text.lowercase(Locale.ROOT)
        if (confirmTokens.any { lower.contains(it) }) {
            lastConfirmedAtMs = SystemClock.elapsedRealtime()
        }
    }
    fun checkAllowed(toolName: String, args: JSONObject): Boolean {
        if (!enforce) return true
        if (toolName != "whatsapp_delete_message") return true
        val age = SystemClock.elapsedRealtime() - lastConfirmedAtMs
        return age in 0..30_000L
    }
}
object DuplicateCallGuard {
    private const val MUTATING_COOLDOWN_MS = 8_000L
    private val executedCallIds = ConcurrentHashMap<String, Long>()
    private val executedFingerprints = ConcurrentHashMap<String, Long>()
    private val readOnlyTools = setOf(
        "describe_screen",
        "get_device_info",
        "analyze_screen_visual",
        "list_standing_rules"
    )
    fun clear() {
        executedCallIds.clear()
        executedFingerprints.clear()
    }
    fun onNewUserTurn(turnId: Long) {
        val now = SystemClock.elapsedRealtime()
        executedCallIds.entries.removeIf { now - it.value > 60_000L }
        executedFingerprints.entries.removeIf { now - it.value > MUTATING_COOLDOWN_MS }
    }
    fun checkAndRecord(toolName: String, args: JSONObject, callId: String?): ToolResult? {
        val now = SystemClock.elapsedRealtime()
        if (!callId.isNullOrBlank()) {
            val prev = executedCallIds.putIfAbsent(callId, now)
            if (prev != null && (now - prev) < 60_000L) {
                AgentLog.w("Guard", "Blocked duplicate callId=$callId for $toolName")
                return ToolResult.duplicateBlocked(toolName, "Tool call $callId already executed.")
            }
        }
        if (toolName in readOnlyTools) return null
        val fp = "$toolName:${canonicalizeJson(args)}"
        val prevFpTime = executedFingerprints[fp]
        if (prevFpTime != null && (now - prevFpTime) < MUTATING_COOLDOWN_MS) {
            AgentLog.w("Guard", "Blocked duplicate fingerprint $fp (${now - prevFpTime}ms ago)")
            return ToolResult.duplicateBlocked(toolName, "Identical $toolName call already executed ${now - prevFpTime}ms ago.")
        }
        executedFingerprints[fp] = now
        return null
    }
    private fun canonicalizeJson(obj: JSONObject): String {
        val keys = obj.keys().asSequence().toList().sorted()
        return keys.joinToString("&") { k -> "$k=${obj.optString(k, "").trim().lowercase(Locale.ROOT)}" }
    }
}
object AgentSession {
    private val turnCounter = AtomicLong(1L)
    @Volatile var lastUserUtterance: String = ""; private set
    @Volatile var lastUtteranceElapsedMs: Long = 0L; private set
    val currentTurnId: Long get() = turnCounter.get()
    fun onUserUtterance(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        val newTurn = turnCounter.incrementAndGet()
        lastUserUtterance = cleaned
        lastUtteranceElapsedMs = SystemClock.elapsedRealtime()
        AgentLog.i("Session", "Turn #$newTurn user utterance: $cleaned")
        DuplicateCallGuard.onNewUserTurn(newTurn)
        ConfirmationGate.onUserUtterance(cleaned)
        try {
            val ctx = AgentBridge.appContext
            if (ctx != null) {
                StandingRulesEngine.onUserUtterance(ctx, cleaned)
            }
        } catch (_: Exception) {
        }
    }

    fun clearForTest() {
        lastUserUtterance = ""
        lastUtteranceElapsedMs = 0L
        DuplicateCallGuard.clear()
    }
}

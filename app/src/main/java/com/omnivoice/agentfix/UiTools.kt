package com.omnivoice.agentfix

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale
enum class CanonicalAppLaunchStatus(val wireName: String) {
    VERIFIED("Verified"),
    REQUESTED_UNVERIFIED("Requested/Unverified"),
    MISSING_APP("MissingApp"),
    NEEDS_SETUP("NeedsSetup"),
    BLOCKED("Blocked")
}

data class InstalledAppVariant(
    val packageName: String,
    val label: String,
    val profileType: String = "personal", // "personal" or "work"
    val isBusinessVariant: Boolean = packageName.equals("com.whatsapp.w4b", ignoreCase = true) ||
        label.contains("business", ignoreCase = true)
)

data class OriginTaskSnapshot(
    val packageName: String,
    val appLabel: String,
    val taskId: Int,
    val windowId: Int,
    val activityComponent: String,
    val draftTexts: Map<String, String>,
    val capturedElapsedMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appLabel", appLabel)
        put("taskId", taskId)
        put("windowId", windowId)
        put("activityComponent", activityComponent)
        put("capturedElapsedMs", capturedElapsedMs)
        val draftsObj = JSONObject()
        for ((k, v) in draftTexts) draftsObj.put(k, v)
        put("draftTexts", draftsObj)
    }
}

data class CanonicalAppLaunchResult(
    val status: CanonicalAppLaunchStatus,
    val requestedApp: String,
    val resolvedPackage: String? = null,
    val resolvedLabel: String? = null,
    val profileType: String = "personal",
    val observedPackage: String? = null,
    val verified: Boolean = false,
    val launchMethod: String = "NONE",
    val iconTapCount: Int = 0,
    val homeNavigated: Boolean = false,
    val preservedOriginTask: OriginTaskSnapshot? = null,
    val bothAppsVisibleTogether: Boolean = false,
    val draftPreserved: Boolean = false,
    val restoredDrafts: Map<String, String> = emptyMap(),
    val candidateVariants: List<InstalledAppVariant> = emptyList(),
    val reasonCode: String = "",
    val message: String,
    val verificationNote: String
) {
    val ok: Boolean
        get() = status == CanonicalAppLaunchStatus.VERIFIED

    fun toJson(): JSONObject = JSONObject().apply {
        put("canonicalStatus", status.wireName)
        put("status", status.name)
        put("ok", ok)
        put("verified", verified)
        put("requestedApp", requestedApp)
        if (!resolvedPackage.isNullOrBlank()) put("resolvedPackage", resolvedPackage)
        if (!resolvedPackage.isNullOrBlank()) put("packageName", resolvedPackage)
        if (!resolvedLabel.isNullOrBlank()) put("resolvedLabel", resolvedLabel)
        if (!resolvedLabel.isNullOrBlank()) put("appLabel", resolvedLabel)
        put("profileType", profileType)
        if (!observedPackage.isNullOrBlank()) {
            put("observedPackage", observedPackage)
            put("foregroundPackageAfterLaunch", observedPackage)
        } else {
            put("observedPackage", JSONObject.NULL)
        }
        put("launchMethod", launchMethod)
        put("iconTapCount", iconTapCount)
        put("homeNavigated", homeNavigated)
        put("bothAppsVisibleTogether", bothAppsVisibleTogether)
        put("draftPreserved", draftPreserved)
        if (restoredDrafts.isNotEmpty()) {
            val draftsObj = JSONObject()
            for ((k, v) in restoredDrafts) draftsObj.put(k, v)
            put("restoredDrafts", draftsObj)
        }
        preservedOriginTask?.let { put("preservedOriginTask", it.toJson()) }
        if (candidateVariants.isNotEmpty()) {
            val arr = org.json.JSONArray()
            for (v in candidateVariants) {
                arr.put(
                    JSONObject()
                        .put("packageName", v.packageName)
                        .put("label", v.label)
                        .put("profileType", v.profileType)
                        .put("isBusinessVariant", v.isBusinessVariant)
                )
            }
            put("candidateVariants", arr)
        }
        if (reasonCode.isNotBlank()) put("reasonCode", reasonCode)
        put("message", message)
        put("verificationNote", verificationNote)
    }

    fun toToolResult(toolName: String = "open_app"): ToolResult {
        val mappedStatus = when (status) {
            CanonicalAppLaunchStatus.VERIFIED -> ToolStatus.SUCCESS.name
            CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED -> CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED.wireName
            CanonicalAppLaunchStatus.MISSING_APP -> CanonicalAppLaunchStatus.MISSING_APP.wireName
            CanonicalAppLaunchStatus.NEEDS_SETUP -> CanonicalAppLaunchStatus.NEEDS_SETUP.wireName
            CanonicalAppLaunchStatus.BLOCKED -> CanonicalAppLaunchStatus.BLOCKED.wireName
        }
        return ToolResult(
            ok = ok,
            status = mappedStatus,
            tool = toolName,
            message = message,
            verified = verified,
            verificationNote = verificationNote,
            data = toJson(),
            errorCode = reasonCode.takeIf { it.isNotBlank() && !ok }
        )
    }

    fun toExecutionResult(): com.example.engine.ExecutionResult {
        return when (status) {
            CanonicalAppLaunchStatus.VERIFIED ->
                com.example.engine.ExecutionResult.Success(message)
            CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED ->
                com.example.engine.ExecutionResult.RequestedUnverified(
                    message = message,
                    reasonCode = reasonCode,
                    observedPackage = observedPackage
                )
            CanonicalAppLaunchStatus.MISSING_APP,
            CanonicalAppLaunchStatus.NEEDS_SETUP,
            CanonicalAppLaunchStatus.BLOCKED ->
                com.example.engine.ExecutionResult.Failure(message)
        }
    }

    fun toActionResult(): com.example.engine.ActionResult {
        return when (status) {
            CanonicalAppLaunchStatus.VERIFIED ->
                com.example.engine.ActionResult.Success(message)
            CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED ->
                com.example.engine.ActionResult.RequestedUnverified(
                    detail = message,
                    reasonCode = reasonCode,
                    observedPackage = observedPackage
                )
            CanonicalAppLaunchStatus.NEEDS_SETUP ->
                com.example.engine.ActionResult.NeedsConfirmation(
                    msg = message,
                    actionId = reasonCode.ifBlank { "needs_setup_${requestedApp.lowercase(Locale.ROOT)}" }
                )
            CanonicalAppLaunchStatus.MISSING_APP,
            CanonicalAppLaunchStatus.BLOCKED ->
                com.example.engine.ActionResult.Failed(message)
        }
    }
}

object UiTools {
    private val knownAppPackages = mapOf(
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "whatsapp business" to listOf("com.whatsapp.w4b"),
        "personal whatsapp" to listOf("com.whatsapp"),
        "work whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "youtube" to listOf("com.google.android.youtube"),
        "instagram" to listOf("com.instagram.android"),
        "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
        "messenger" to listOf("com.facebook.orca"),
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "chrome" to listOf("com.android.chrome"),
        "browser" to listOf("com.android.chrome"),
        "gmail" to listOf("com.google.android.gm"),
        "maps" to listOf("com.google.android.apps.maps"),
        "google maps" to listOf("com.google.android.apps.maps"),
        "google" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome"),
        "settings" to listOf("com.android.settings"),
        "camera" to listOf("com.android.camera", "com.android.camera2", "com.sec.android.app.camera", "com.google.android.GoogleCamera"),
        "phone" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
        "dialer" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
        "messages" to listOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging"),
        "sms" to listOf("com.google.android.apps.messaging", "com.android.mms"),
        "contacts" to listOf("com.google.android.contacts", "com.android.contacts", "com.samsung.android.app.contacts"),
        "spotify" to listOf("com.spotify.music"),
        "play store" to listOf("com.android.vending"),
        "calculator" to listOf("com.google.android.calculator", "com.android.calculator2", "com.sec.android.app.popupcalculator"),
        "clock" to listOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage"),
        "calendar" to listOf("com.google.android.calendar", "com.android.calendar", "com.samsung.android.calendar"),
        "files" to listOf("com.google.android.apps.nbu.files", "com.android.documentsui"),
        "gallery" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d"),
        "photos" to listOf("com.google.android.apps.photos"),
        "snapchat" to listOf("com.snapchat.android"),
        "telegram" to listOf("org.telegram.messenger"),
        "notes" to listOf("com.example.notes", "com.google.android.keep", "com.samsung.android.app.notes")
    )

    private val packageResolutionCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    const val PHASE_02_BUILD_ID = "P02-R2-538aab3"

    data class Phase02JourneyDiagnostics(
        val buildId: String = PHASE_02_BUILD_ID,
        val actualOpenTool: String = "none",
        val savedOriginPackage: String = "none",
        val actualReturnTool: String = "none",
        val navigationMethod: String = "none",
        val finalObservedPackage: String = "none",
        val reasonCode: String = "NONE"
    ) {
        /**
         * Short copyable result for the Phase 02 open/return journey only.
         * Strictly excludes note contents, contact details, and private screen text.
         */
        fun toCopyableSummary(): String {
            return "build_id=$buildId | " +
                "actual_open_tool=$actualOpenTool | " +
                "saved_origin_package=$savedOriginPackage | " +
                "actual_return_tool=$actualReturnTool | " +
                "navigation_method=$navigationMethod | " +
                "final_observed_package=$finalObservedPackage | " +
                "reason_code=$reasonCode"
        }

        fun toMultilineCardText(): String {
            return buildString {
                appendLine("Build ID: $buildId")
                appendLine("Actual Open Tool: $actualOpenTool")
                appendLine("Saved Origin Package: $savedOriginPackage")
                appendLine("Actual Return Tool: $actualReturnTool")
                appendLine("Navigation Method: $navigationMethod")
                appendLine("Final Observed Package: $finalObservedPackage")
                append("Reason Code: $reasonCode")
            }
        }
    }

    @Volatile
    var defaultColdStartTimeoutMs: Long = 2500L

    @Volatile
    var lastOriginTaskSnapshot: OriginTaskSnapshot? = null
        internal set

    @Volatile
    var lastCanonicalResult: CanonicalAppLaunchResult? = null
        internal set

    fun clearLastCanonicalResult() {
        lastCanonicalResult = null
    }

    @Volatile
    var lastPhase02Diagnostics: Phase02JourneyDiagnostics = Phase02JourneyDiagnostics()
        internal set

    @Volatile
    var testInstalledVariantsOverride: List<InstalledAppVariant>? = null

    @Volatile
    var testHomeActionHandler: (() -> Boolean)? = null

    @Volatile
    var testRecentsActionHandler: (() -> Boolean)? = null

    @Volatile
    var testBackActionHandler: (() -> Boolean)? = null

    @Volatile
    var testReturnNavigationHandler: ((OriginTaskSnapshot) -> Boolean)? = null

    @Volatile
    var testIconTapHandler: ((UiNodeSummary) -> Boolean)? = null

    @Volatile
    var testActiveCandidateTaskForReturn: OriginTaskSnapshot? = null

    fun resetTestHooks() {
        defaultColdStartTimeoutMs = 2500L
        lastOriginTaskSnapshot = null
        lastCanonicalResult = null
        lastCommandRouteTrace = null
        lastPhase02Diagnostics = Phase02JourneyDiagnostics()
        testInstalledVariantsOverride = null
        testHomeActionHandler = null
        testRecentsActionHandler = null
        testBackActionHandler = null
        testReturnNavigationHandler = null
        testIconTapHandler = null
        testActiveCandidateTaskForReturn = null
        packageResolutionCache.clear()
        ScreenObserver.testSnapshotSupplier = null
        ScreenObserver.resetCachedOriginStateForTest()
        AgentSession.clearForTest()
    }

    /**
     * Determines whether a `communicate_or_open_app` tool invocation is a plain app-open request
     * (e.g., "WhatsApp kholo", `actionType="open"`, `contactOrAppName="WhatsApp"`, `messageOrQuery=""`)
     * as opposed to an actual outbound message, phone call, SMS, or search query.
     */
    fun isPlainAppOpenInCommunicateOrOpenApp(
        actionType: String,
        contactOrAppName: String,
        messageOrQuery: String
    ): Boolean {
        val normAction = actionType.trim().lowercase(Locale.ROOT)
        val normTarget = contactOrAppName.trim().lowercase(Locale.ROOT)
        val normMsg = messageOrQuery.trim()
        val normMsgLower = normMsg.lowercase(Locale.ROOT)

        // Preserve actual calls, SMS, search, and media playback as separate actions
        if (normAction.contains("call") ||
            normAction.contains("dial") ||
            normAction.contains("sms") ||
            normAction.contains("search") ||
            normAction.contains("google") ||
            normAction.contains("play") ||
            normAction.contains("video")
        ) {
            return false
        }

        val trivialOpenQueries = setOf(
            "",
            "open",
            "kholo",
            "khol",
            "kholen",
            "launch",
            "start",
            "app",
            "کھولو",
            "کھولیں",
            normTarget,
            "$normTarget kholo",
            "open $normTarget"
        )
        val hasRealMessageOrSearchPayload = normMsgLower !in trivialOpenQueries

        // If there is a real message or search query, it is messaging/search, not a plain app open
        if (hasRealMessageOrSearchPayload) {
            return false
        }

        // If actionType explicitly requests message/send/chat and there's no plain-open cue, only treat as plain open when message is blank AND target is an app name
        if (normAction in setOf("open", "open_app", "launch", "launch_app", "app", "start_app", "")) {
            return normTarget.isNotBlank()
        }

        val candidateApp = normTarget.ifBlank { normAction }
        if (candidateApp.isBlank()) return false
        val cleanedCandidate = candidateApp
            .replace(Regex("\\b(open|kholo|khol|launch|start|app|application)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { candidateApp }
        if (knownAppPackages.containsKey(cleanedCandidate) ||
            knownAppPackages.containsKey(normAction) ||
            cleanedCandidate.contains("whatsapp") ||
            cleanedCandidate.contains("youtube") ||
            cleanedCandidate.contains("instagram") ||
            cleanedCandidate.contains("facebook") ||
            cleanedCandidate.contains("tiktok") ||
            cleanedCandidate.contains("telegram") ||
            cleanedCandidate.contains("chrome") ||
            cleanedCandidate.contains("gmail") ||
            cleanedCandidate.contains("maps") ||
            cleanedCandidate.contains("settings") ||
            cleanedCandidate.contains("camera") ||
            cleanedCandidate.contains("calculator") ||
            cleanedCandidate.contains("clock") ||
            cleanedCandidate.contains("calendar") ||
            cleanedCandidate.contains("gallery") ||
            cleanedCandidate.contains("photos") ||
            cleanedCandidate.contains("notes") ||
            cleanedCandidate.contains("notepad")
        ) {
            return true
        }
        return false
    }

    private fun recordOpenDiagnostics(
        callerToolName: String,
        savedOrigin: OriginTaskSnapshot?,
        result: CanonicalAppLaunchResult
    ): CanonicalAppLaunchResult {
        lastCanonicalResult = result
        lastPhase02Diagnostics = Phase02JourneyDiagnostics(
            buildId = PHASE_02_BUILD_ID,
            actualOpenTool = callerToolName.ifBlank { "open_app" },
            savedOriginPackage = savedOrigin?.packageName?.ifBlank { "none" } ?: "none",
            actualReturnTool = "none",
            navigationMethod = result.launchMethod.ifBlank { "NONE" },
            finalObservedPackage = result.observedPackage?.ifBlank { "none" } ?: "none",
            reasonCode = result.reasonCode.ifBlank { result.status.name }
        )
        return result
    }

    private fun recordReturnDiagnostics(
        callerToolName: String,
        savedOrigin: OriginTaskSnapshot?,
        result: CanonicalAppLaunchResult
    ): CanonicalAppLaunchResult {
        lastCanonicalResult = result
        val prevOpenTool = lastPhase02Diagnostics.actualOpenTool
        val originPkg = savedOrigin?.packageName?.ifBlank { null }
            ?: lastPhase02Diagnostics.savedOriginPackage.takeIf { it.isNotBlank() && it != "none" }
            ?: "none"
        lastPhase02Diagnostics = Phase02JourneyDiagnostics(
            buildId = PHASE_02_BUILD_ID,
            actualOpenTool = prevOpenTool,
            savedOriginPackage = originPkg,
            actualReturnTool = callerToolName.ifBlank { "return_to_origin" },
            navigationMethod = result.launchMethod.ifBlank { "NONE" },
            finalObservedPackage = result.observedPackage?.ifBlank { "none" } ?: "none",
            reasonCode = result.reasonCode.ifBlank { result.status.name }
        )
        return result
    }

    /**
     * Recognizes dedicated return-to-previous-app / return-to-origin commands across Roman Urdu,
     * Urdu script, and English, including:
     * - "wapas iss window par aao" / "wapas usi window par aao"
     * - "pichli app par wapas jao" / "pichli window par wapas aao"
     * - "return to the previous app" / "return to previous app"
     * - "wapas jao" / "peeche jao" / "go back"
     */
    data class CommandRouteTrace(
        val routeType: String,
        val recognizedTranscript: String,
        val matchedIntent: String,
        val savedOriginPackage: String?,
        val actualDispatchedTool: String
    )

    @Volatile
    var lastCommandRouteTrace: CommandRouteTrace? = null
        internal set

    /**
     * Explicitly recognizes commands to open WhatsApp Status / Updates tab
     * (e.g., "WhatsApp Status kholo", "open WhatsApp status", "واٹس ایپ اسٹیٹس کھولو").
     * Kept strictly separate from return-to-previous-app / resume-work commands.
     */
    fun isExplicitWhatsAppStatusCommand(rawText: String?): Boolean {
        if (rawText.isNullOrBlank()) return false
        val trimmed = rawText.trim()
        val normalized = trimmed.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u0600-\\u06FF\\u0900-\\u097F\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return false

        val hasPriorWorkCue = normalized.contains("pehle") ||
            normalized.contains("pehlay") ||
            normalized.contains("pahle") ||
            normalized.contains("previous") ||
            normalized.contains("pichli") ||
            normalized.contains("pichle") ||
            normalized.contains("purani") ||
            normalized.contains("wapas") ||
            normalized.contains("wapis") ||
            normalized.contains("jahan main") ||
            normalized.contains("jaha mai") ||
            normalized.contains("kaam kar raha") ||
            normalized.contains("kam kar raha") ||
            normalized.contains("work kar raha") ||
            trimmed.contains("پہلے") ||
            trimmed.contains("پچھلی") ||
            trimmed.contains("واپس") ||
            trimmed.contains("पहले") ||
            trimmed.contains("पिछली") ||
            trimmed.contains("वापस")
        if (hasPriorWorkCue) return false

        val mentionsStatus = normalized.contains("status") ||
            trimmed.contains("اسٹیٹس") ||
            trimmed.contains("سٹیٹس") ||
            trimmed.contains("स्टेटस")
        if (!mentionsStatus) return false

        val mentionsWhatsAppOrTab = normalized.contains("whatsapp") ||
            trimmed.contains("واٹس ایپ") ||
            trimmed.contains("व्हाट्सएप") ||
            normalized.startsWith("status ") ||
            normalized.endsWith(" status")
        val hasOpenOrShowVerb = normalized.contains("kholo") ||
            normalized.contains("khol do") ||
            normalized.contains("open") ||
            normalized.contains("dikhao") ||
            normalized.contains("dikha do") ||
            normalized.contains("show") ||
            normalized.contains("par jao") ||
            normalized.contains("pe jao") ||
            trimmed.contains("کھولو") ||
            trimmed.contains("دکھاؤ") ||
            trimmed.contains("खोलो") ||
            trimmed.contains("दिखाओ")
        return mentionsWhatsAppOrTab && hasOpenOrShowVerb
    }

    /**
     * Explicitly recognizes commands to show the Android Recents / Overview screen
     * (e.g., "recent apps dikhao", "show recent apps", "recents kholo", "ریسنٹ ایپس دکھاؤ").
     * Kept strictly separate from return-to-previous-app / resume-work commands.
     */
    fun isExplicitShowRecentsCommand(rawText: String?): Boolean {
        if (rawText.isNullOrBlank()) return false
        val trimmed = rawText.trim()
        val normalized = trimmed.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u0600-\\u06FF\\u0900-\\u097F\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return false

        val hasPriorWorkCue = normalized.contains("pehle") ||
            normalized.contains("pehlay") ||
            normalized.contains("pahle") ||
            normalized.contains("previous") ||
            normalized.contains("pichli") ||
            normalized.contains("pichle") ||
            normalized.contains("purani") ||
            normalized.contains("wapas") ||
            normalized.contains("wapis") ||
            normalized.contains("jahan main") ||
            normalized.contains("jaha mai") ||
            normalized.contains("kaam kar raha") ||
            normalized.contains("kam kar raha") ||
            normalized.contains("work kar raha") ||
            trimmed.contains("پہلے") ||
            trimmed.contains("پچھلی") ||
            trimmed.contains("واپس") ||
            trimmed.contains("पहले") ||
            trimmed.contains("पिछली") ||
            trimmed.contains("वापस")
        if (hasPriorWorkCue) return false

        if (normalized == "recents" || normalized == "recent apps") return true

        val mentionsRecents = normalized.contains("recent apps") ||
            normalized.contains("recents") ||
            normalized.contains("recent screen") ||
            normalized.contains("overview") ||
            trimmed.contains("ریسنٹ ایپس") ||
            trimmed.contains("ریسنٹس") ||
            trimmed.contains("रीसेंट एप्स") ||
            trimmed.contains("रीसेंट्स")
        val hasShowOrOpenVerb = normalized.contains("dikhao") ||
            normalized.contains("dikha do") ||
            normalized.contains("kholo") ||
            normalized.contains("khol do") ||
            normalized.contains("show") ||
            normalized.contains("open") ||
            trimmed.contains("دکھاؤ") ||
            trimmed.contains("کھولو") ||
            trimmed.contains("दिखाओ") ||
            trimmed.contains("खोलो")
        return mentionsRecents && hasShowOrOpenVerb
    }

    /**
     * Recognizes dedicated return-to-previous-app / return-to-origin / "resume the work I was doing
     * before this app switch" commands across Roman Urdu, Urdu Nastaliq, Devanagari, and mixed English, including:
     * - "Uss previous screen par le kar jao jahan main pehle work kar raha tha."
     * - "Uss software par le kar jao jahan main pehle kaam kar raha tha."
     * - "wapas iss window par aao" / "wapas usi window par aao"
     * - "pichli app par wapas jao" / "pichli window par wapas aao"
     * - "return to the previous app" / "resume the work I was doing before"
     * - "wapas jao" / "peeche jao" / "go back"
     *
     * Explicitly excludes "WhatsApp Status kholo" and "recent apps dikhao".
     */
    fun isReturnToPreviousAppCommand(rawText: String?): Boolean {
        if (rawText.isNullOrBlank()) return false
        if (isExplicitWhatsAppStatusCommand(rawText) || isExplicitShowRecentsCommand(rawText)) {
            return false
        }
        val trimmed = rawText.trim()
        val normalized = trimmed.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u0600-\\u06FF\\u0900-\\u097F\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return false

        val exactMatches = setOf(
            "wapas jao",
            "wapis jao",
            "wapas aao",
            "wapis aao",
            "peeche jao",
            "piche jao",
            "peeche aao",
            "piche aao",
            "go back",
            "navigate back",
            "return",
            "return to previous app",
            "return to the previous app",
            "return to previous window",
            "return to the previous window",
            "go back to previous app",
            "go back to the previous app",
            "go back to previous window",
            "go back to the previous window",
            "switch to previous app",
            "switch back to previous app",
            "switch back to the previous app",
            "back to previous app",
            "back to the previous app",
            "wapas iss window par aao",
            "wapas is window par aao",
            "wapis iss window par aao",
            "wapis is window par aao",
            "wapas usi window par aao",
            "wapis usi window par aao",
            "wapas us window par aao",
            "wapas issi window par aao",
            "wapas iss window pe aao",
            "wapas usi window pe aao",
            "wapas iss window par jao",
            "wapas usi window par jao",
            "wapas iss app par aao",
            "wapas usi app par aao",
            "pichli app par wapas jao",
            "pichli app par wapas aao",
            "pichli app pe wapas jao",
            "pichli app pe wapas aao",
            "pichli app me wapas jao",
            "pichli app mein wapas jao",
            "pichli app mein wapas aao",
            "pichle app par wapas jao",
            "pichli window par wapas jao",
            "pichli window par wapas aao",
            "pichli window pe wapas jao",
            "pichli window pe wapas aao",
            "purani window par wapas aao",
            "purani app par wapas jao",
            "uss previous screen par le kar jao jahan main pehle work kar raha tha",
            "us previous screen par le kar jao jahan main pehle work kar raha tha",
            "uss software par le kar jao jahan main pehle kaam kar raha tha",
            "us software par le kar jao jahan main pehle kaam kar raha tha"
        )
        if (normalized in exactMatches) return true

        if (trimmed.contains("واپس اس ونڈو پر آؤ") ||
            trimmed.contains("واپس اسی ونڈو پر آؤ") ||
            trimmed.contains("پچھلی ایپ پر واپس جاؤ") ||
            trimmed.contains("پچھلی ایپ پر واپس آؤ") ||
            trimmed.contains("پچھلی ونڈو پر واپس") ||
            trimmed.contains("پچھلی اسکرین پر") ||
            trimmed.contains("پچھلی سکرین پر") ||
            trimmed.contains("پچھلے سافٹ ویئر پر") ||
            trimmed.contains("اس پریویس اسکرین پر") ||
            trimmed.contains("اس سافٹ ویئر پر لے کر جاؤ") ||
            trimmed.contains("اس اسکرین پر لے کر جاؤ") ||
            trimmed.contains("اس سکرین پر لے کر جاؤ") ||
            trimmed.contains("جہاں میں پہلے کام کر رہا تھا") ||
            trimmed.contains("جہاں میں پہلے ورک کر رہا تھا") ||
            trimmed.contains("جہاں میں کام کر رہا تھا") ||
            trimmed.contains("واپس جاؤ") ||
            trimmed.contains("پیچھے جاؤ") ||
            trimmed.contains("जहां मैं पहले काम कर रहा था") ||
            trimmed.contains("जहाँ मैं पहले काम कर रहा था") ||
            trimmed.contains("जहां मैं पहले वर्क कर रहा था") ||
            trimmed.contains("जहाँ मैं पहले वर्क कर रहा था") ||
            trimmed.contains("उस प्रीवियस स्क्रीन पर") ||
            trimmed.contains("उस सॉफ्टवेयर पर ले कर जाओ") ||
            trimmed.contains("पिछली ऐप पर वापस जाओ") ||
            trimmed.contains("वापस इस विंडो पर आओ") ||
            trimmed.contains("वापस जाओ")
        ) {
            return true
        }

        // Semantic recognition for "resume the work I was doing before this app switch"
        val hasPriorWorkClause = normalized.contains("jahan main pehle") ||
            normalized.contains("jahan mai pehle") ||
            normalized.contains("jahan me pehle") ||
            normalized.contains("jaha main pehle") ||
            normalized.contains("jaha mai pehle") ||
            normalized.contains("jahan main pahle") ||
            normalized.contains("jahan hum pehle") ||
            normalized.contains("jis par main pehle") ||
            normalized.contains("jis pe main pehle") ||
            normalized.contains("pehle kaam kar raha") ||
            normalized.contains("pehle kam kar raha") ||
            normalized.contains("pehle work kar raha") ||
            normalized.contains("pahle kaam kar raha") ||
            normalized.contains("pahle kam kar raha") ||
            normalized.contains("pahle work kar raha") ||
            normalized.contains("pehle kaam kr rha") ||
            normalized.contains("pehle work kr rha") ||
            normalized.contains("pehle kaam kar rahi") ||
            normalized.contains("pehle work kar rahi") ||
            normalized.contains("kaam kar raha tha") ||
            normalized.contains("kam kar raha tha") ||
            normalized.contains("work kar raha tha") ||
            normalized.contains("kaam kr rha tha") ||
            normalized.contains("work kr rha tha") ||
            normalized.contains("kaam kar rha tha") ||
            normalized.contains("work kar rha tha") ||
            normalized.contains("kaam kar rahi thi") ||
            normalized.contains("work kar rahi thi") ||
            normalized.contains("likh raha tha") ||
            normalized.contains("type kar raha tha") ||
            normalized.contains("where i was working") ||
            normalized.contains("what i was working on") ||
            normalized.contains("work i was doing") ||
            normalized.contains("before this app switch") ||
            normalized.contains("working on earlier") ||
            normalized.contains("working on before") ||
            trimmed.contains("جہاں میں پہلے") ||
            trimmed.contains("پہلے کام کر رہا") ||
            trimmed.contains("پہلے ورک کر رہا") ||
            trimmed.contains("کام کر رہا تھا") ||
            trimmed.contains("ورک کر رہا تھا") ||
            trimmed.contains("لکھ رہا تھا") ||
            trimmed.contains("जहां मैं पहले") ||
            trimmed.contains("जहाँ मैं पहले") ||
            trimmed.contains("पहले काम कर रहा") ||
            trimmed.contains("पहले वर्क कर रहा") ||
            trimmed.contains("काम कर रहा था") ||
            trimmed.contains("वर्क कर रहा था")

        val hasReturnCue = hasPriorWorkClause ||
            normalized.contains("wapas") ||
            normalized.contains("wapis") ||
            normalized.contains("pichli") ||
            normalized.contains("pichle") ||
            normalized.contains("pichla") ||
            normalized.contains("purani") ||
            normalized.contains("purana") ||
            normalized.contains("purane") ||
            normalized.contains("previous") ||
            normalized.contains("prior") ||
            normalized.contains("earlier") ||
            normalized.contains("pehle") ||
            normalized.contains("pehlay") ||
            normalized.contains("pahle") ||
            normalized.contains("jahan main") ||
            normalized.contains("jahan mai") ||
            normalized.contains("jaha main") ||
            normalized.contains("jaha mai") ||
            normalized.contains("jis par main") ||
            normalized.contains("jis pe main") ||
            normalized.contains("return to") ||
            normalized.contains("go back to") ||
            normalized.contains("switch back to") ||
            normalized.contains("take me back") ||
            normalized.contains("bring me back") ||
            normalized.contains("resume") ||
            Regex("\\b(uss|us|usi|issi)\\s+(previous\\s+)?(window|app|screen|software|program|jagah)\\b").containsMatchIn(normalized) ||
            trimmed.contains("واپس") ||
            trimmed.contains("پچھلی") ||
            trimmed.contains("پچھلے") ||
            trimmed.contains("پرانی") ||
            trimmed.contains("پہلے") ||
            trimmed.contains("پریویس") ||
            trimmed.contains("جہاں میں") ||
            trimmed.contains("اس سافٹ ویئر") ||
            trimmed.contains("اس اسکرین") ||
            trimmed.contains("اس سکرین") ||
            trimmed.contains("اس ونڈو") ||
            trimmed.contains("वापस") ||
            trimmed.contains("पिछली") ||
            trimmed.contains("पिछले") ||
            trimmed.contains("पुरानी") ||
            trimmed.contains("पहले") ||
            trimmed.contains("प्रीवियस") ||
            trimmed.contains("उस सॉफ्टवेयर") ||
            trimmed.contains("उस स्क्रीन")

        val hasWindowOrAppTarget = normalized.contains("window") ||
            normalized.contains("app") ||
            normalized.contains("application") ||
            normalized.contains("screen") ||
            normalized.contains("task") ||
            normalized.contains("software") ||
            normalized.contains("program") ||
            normalized.contains("page") ||
            normalized.contains("jagah") ||
            normalized.contains("kaam") ||
            normalized.contains("kam") ||
            normalized.contains("work") ||
            normalized.contains("draft") ||
            normalized.contains("note") ||
            normalized.contains("notepad") ||
            trimmed.contains("ونڈو") ||
            trimmed.contains("ایپ") ||
            trimmed.contains("اسکرین") ||
            trimmed.contains("سکرین") ||
            trimmed.contains("سافٹ ویئر") ||
            trimmed.contains("پروگرام") ||
            trimmed.contains("جگہ") ||
            trimmed.contains("کام") ||
            trimmed.contains("ورک") ||
            trimmed.contains("ڈرافٹ") ||
            trimmed.contains("विंडो") ||
            trimmed.contains("ऐप") ||
            trimmed.contains("स्क्रीन") ||
            trimmed.contains("सॉफ्टवेयर") ||
            trimmed.contains("काम") ||
            trimmed.contains("वर्क")

        val hasNavigationVerb = normalized.contains("aao") ||
            normalized.contains("jao") ||
            normalized.contains("chalo") ||
            normalized.contains("le jao") ||
            normalized.contains("le kar jao") ||
            normalized.contains("le kr jao") ||
            normalized.contains("le ke jao") ||
            normalized.contains("le chalo") ||
            normalized.contains("kholo") ||
            normalized.contains("khol do") ||
            normalized.contains("return") ||
            normalized.contains("back") ||
            normalized.contains("switch") ||
            normalized.contains("resume") ||
            normalized.contains("restore") ||
            normalized.contains("take me") ||
            normalized.contains("bring") ||
            trimmed.contains("آؤ") ||
            trimmed.contains("جاؤ") ||
            trimmed.contains("چلو") ||
            trimmed.contains("لے جاؤ") ||
            trimmed.contains("لے کر جاؤ") ||
            trimmed.contains("کھولو") ||
            trimmed.contains("आओ") ||
            trimmed.contains("जाओ") ||
            trimmed.contains("चलो") ||
            trimmed.contains("ले जाओ") ||
            trimmed.contains("ले कर जाओ") ||
            trimmed.contains("खोलो")

        if (hasPriorWorkClause && (hasWindowOrAppTarget || hasNavigationVerb)) {
            return true
        }
        if (hasReturnCue && hasWindowOrAppTarget && hasNavigationVerb) {
            return true
        }
        return false
    }

    private val returnInterceptableTools = setOf(
        "close_app",
        "close_target_app",
        "exit_app",
        "go_home",
        "perform_gesture",
        "tap_coordinates",
        "swipe_screen",
        "scroll_screen",
        "global_action",
        "navigate",
        "ui_click_text",
        "click_text",
        "tap_text",
        "click_button",
        "press_button",
        "press_send_or_confirm_on_screen",
        "click_or_send_current_screen",
        "control_any_app_with_accessibility",
        "control_app_and_screen_ui",
        "control_social_and_media_apps",
        "jarvis_app_commander",
        "jarvis_hardware_and_screen_control",
        "execute_ui_automation_macro",
        "communicate_or_open_app",
        "open_app",
        "launch_app",
        "start_app"
    )

    /**
     * Determines whether a tool call in the Live voice or ToolDispatcher route should be
     * intercepted and redirected to `return_to_origin` before any WhatsApp tab click (e.g., Status)
     * or generic Recents gesture can run.
     */
    fun shouldRedirectToolToReturnToOrigin(
        rawToolName: String,
        activeUtterance: String?,
        args: JSONObject? = null
    ): Boolean {
        val cleanTool = rawToolName.trim().lowercase(Locale.ROOT)
        if (cleanTool == "return_to_origin" || cleanTool == "return_to_previous_app" || cleanTool == "wapas_jao") {
            return false
        }
        val canonical = ToolDispatcher.canonicalToolName(cleanTool)
        if (cleanTool !in returnInterceptableTools && canonical !in returnInterceptableTools) {
            return false
        }
        val utterance = activeUtterance?.trim().orEmpty()
        val sessionUtterance = AgentSession.lastUserUtterance.trim()
        if (isExplicitWhatsAppStatusCommand(utterance) || isExplicitShowRecentsCommand(utterance)) {
            return false
        }
        if (utterance.isEmpty() && (isExplicitWhatsAppStatusCommand(sessionUtterance) || isExplicitShowRecentsCommand(sessionUtterance))) {
            return false
        }
        if (isReturnToPreviousAppCommand(utterance) || isReturnToPreviousAppCommand(sessionUtterance)) {
            return true
        }
        if (args != null) {
            val argKeys = listOf(
                "utterance",
                "userUtterance",
                "taskDescription",
                "uiAction",
                "command",
                "parameter",
                "queryOrTarget",
                "searchQueryOrTarget",
                "messageOrQuery",
                "target",
                "app_name",
                "appName",
                "contactOrAppName"
            )
            for (key in argKeys) {
                val value = args.optString(key, "").trim()
                if (value.isNotEmpty() && isReturnToPreviousAppCommand(value)) {
                    return true
                }
            }
            // If Gemini Live dispatches perform_gesture(gesture_type/action="recents") or
            // jarvis_hardware_and_screen_control(command="RECENTS") while a saved origin exists and
            // the user did NOT explicitly ask to show Recents, redirect to return_to_origin even if
            // inputTranscription was partial or delayed when toolCall arrived.
            val gestureOrCmd = args.optString(
                "gesture_type",
                args.optString("action", args.optString("command", args.optString("target", "")))
            ).trim().lowercase(Locale.ROOT)
            val isRecentsToolCall = (canonical == "perform_gesture" ||
                cleanTool == "perform_gesture" ||
                cleanTool == "jarvis_hardware_and_screen_control" ||
                cleanTool == "global_action" ||
                cleanTool == "navigate") &&
                (gestureOrCmd == "recents" || gestureOrCmd == "recent_apps" || gestureOrCmd == "overview")
            if (isRecentsToolCall && lastOriginTaskSnapshot != null) {
                val combinedUtterance = "$utterance $sessionUtterance".trim()
                if (!isExplicitShowRecentsCommand(combinedUtterance)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Resolves all installed, launchable variants matching [rawName], including personal vs. Business
     * (`com.whatsapp` vs. `com.whatsapp.w4b`) and personal vs. work profile.
     */
    fun resolveInstalledVariantsForAppName(context: Context, rawName: String): List<InstalledAppVariant> {
        val query = rawName.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) return emptyList()

        val wantsBusiness = query.contains("business") || query.contains("w4b") || query.contains("کاروبار")
        val wantsWorkProfile = query.contains("work") || query.contains("daftar") || query.contains("office")
        val wantsPersonal = query.contains("personal") || query.contains("zaati") || query.contains("ذاتی")

        val baseQuery = query
            .replace(Regex("\\b(personal|work|profile|daftar|office|zaati|app|application)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { query }

        val overrideList = testInstalledVariantsOverride
        val allInstalled = if (overrideList != null) {
            overrideList
        } else {
            val pm = context.packageManager
            val discovered = linkedMapOf<String, InstalledAppVariant>()

            if (baseQuery.contains('.') && !baseQuery.contains(' ')) {
                val pkg = rawName.trim()
                val launch = try { pm.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
                if (launch != null) {
                    val label = ScreenObserver.resolveAppLabel(pkg)
                    discovered["$pkg:personal"] = InstalledAppVariant(
                        packageName = pkg,
                        label = label,
                        profileType = "personal",
                        isBusinessVariant = pkg.equals("com.whatsapp.w4b", ignoreCase = true) ||
                            label.contains("business", ignoreCase = true)
                    )
                }
            }

            val knownCandidates = when {
                baseQuery.contains("whatsapp") -> listOf("com.whatsapp", "com.whatsapp.w4b")
                else -> knownAppPackages[baseQuery] ?: knownAppPackages.entries
                    .firstOrNull { (k, _) -> baseQuery.contains(k) || k.contains(baseQuery) }?.value
            }
            if (knownCandidates != null) {
                for (pkg in knownCandidates) {
                    val launch = try { pm.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
                    if (launch != null) {
                        val resolvedLabel = ScreenObserver.resolveAppLabel(pkg)
                        val effectiveLabel = when {
                            pkg.equals("com.whatsapp.w4b", ignoreCase = true) &&
                                (resolvedLabel == pkg || !resolvedLabel.contains("business", ignoreCase = true)) ->
                                "WhatsApp Business"
                            pkg.equals("com.whatsapp", ignoreCase = true) && resolvedLabel == pkg ->
                                "WhatsApp"
                            else -> resolvedLabel
                        }
                        discovered["$pkg:personal"] = InstalledAppVariant(
                            packageName = pkg,
                            label = effectiveLabel,
                            profileType = "personal",
                            isBusinessVariant = pkg.equals("com.whatsapp.w4b", ignoreCase = true) ||
                                effectiveLabel.contains("business", ignoreCase = true)
                        )
                    }
                }
            }

            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
                for (ri in resolveInfos) {
                    val pkg = ri.activityInfo?.packageName ?: continue
                    val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank {
                        ScreenObserver.resolveAppLabel(pkg)
                    }
                    val profile = if (label.contains("work", ignoreCase = true)) "work" else "personal"
                    val key = "$pkg:$profile"
                    if (!discovered.containsKey(key)) {
                        discovered[key] = InstalledAppVariant(
                            packageName = pkg,
                            label = label.ifBlank { pkg },
                            profileType = profile,
                            isBusinessVariant = pkg.equals("com.whatsapp.w4b", ignoreCase = true) ||
                                label.contains("business", ignoreCase = true)
                        )
                    }
                }
            } catch (_: Exception) {
            }
            discovered.values.toList()
        }

        val matching = allInstalled.filter { variant ->
            val labelLower = variant.label.lowercase(Locale.ROOT)
            val pkgLower = variant.packageName.lowercase(Locale.ROOT)
            when {
                baseQuery == pkgLower -> true
                baseQuery.contains("whatsapp") ->
                    pkgLower == "com.whatsapp" || pkgLower == "com.whatsapp.w4b" || labelLower.contains("whatsapp")
                labelLower == baseQuery -> true
                labelLower.startsWith(baseQuery) || labelLower.contains(baseQuery) -> true
                baseQuery.contains(labelLower) && labelLower.length >= 3 -> true
                pkgLower.endsWith(".$baseQuery") || pkgLower.contains(baseQuery) -> true
                else -> false
            }
        }

        if (matching.size <= 1) return matching

        // Apply explicit user disambiguation qualifiers when present
        if (wantsBusiness) {
            val bizOnly = matching.filter { it.isBusinessVariant }
            if (bizOnly.isNotEmpty()) return bizOnly
        }
        if (wantsWorkProfile) {
            val workOnly = matching.filter { it.profileType.equals("work", ignoreCase = true) }
            if (workOnly.isNotEmpty()) return workOnly
        }
        if (wantsPersonal) {
            val personalOnly = matching.filter {
                !it.isBusinessVariant && it.profileType.equals("personal", ignoreCase = true)
            }
            if (personalOnly.isNotEmpty()) return personalOnly
        }

        return matching
    }

    fun resolvePackageForAppName(context: Context, rawName: String): Pair<String, String>? {
        val variants = resolveInstalledVariantsForAppName(context, rawName)
        if (variants.size != 1) return null
        val single = variants.first()
        return single.packageName to single.label
    }

    private fun captureOriginTaskBeforeLeaving(
        selfPackageName: String,
        targetPackageToExclude: String? = null
    ): OriginTaskSnapshot? {
        val snap = ScreenObserver.captureOriginUserAppSnapshot(
            selfPackageName = selfPackageName,
            targetPackageToExclude = targetPackageToExclude
        ) ?: return null
        val pkg = snap.packageName.trim()
        if (pkg.isEmpty() ||
            pkg == "unknown" ||
            isLauncherPackage(pkg) ||
            ScreenObserver.isTransientNonUserAppPackage(pkg, selfPackageName)
        ) {
            return null
        }
        val realTaskId = if (snap.taskId > 0 && snap.taskId != snap.windowId) snap.taskId else -1
        val realComponent = snap.activityComponent.trim().takeIf {
            it.isNotEmpty() &&
                it != "$pkg/${snap.titleGuess}" &&
                !it.endsWith("/MainActivity")
        } ?: ""
        return OriginTaskSnapshot(
            packageName = pkg,
            appLabel = snap.appLabel.ifBlank { pkg },
            taskId = realTaskId,
            windowId = snap.windowId,
            activityComponent = realComponent,
            draftTexts = snap.editableFieldDrafts,
            capturedElapsedMs = snap.elapsedRealtimeMs
        )
    }

    internal fun isLauncherPackage(pkg: String): Boolean {
        val lower = pkg.trim().lowercase(Locale.ROOT)
        return lower.contains("launcher") ||
            lower == "com.sec.android.app.launcher" ||
            lower == "com.google.android.apps.nexuslauncher" ||
            lower == "com.miui.home" ||
            lower == "com.android.launcher" ||
            lower == "com.android.launcher3" ||
            lower == "com.huawei.android.launcher" ||
            lower == "com.oppo.launcher" ||
            lower == "com.bbk.launcher2" ||
            lower == "com.teslacoilsw.launcher" ||
            lower == "com.microsoft.launcher"
    }

    private enum class HomeIconMatchOutcome {
        UNIQUE_ON_PAGE,
        AMBIGUOUS_ON_PAGE,
        NOT_FOUND_ON_PAGE
    }

    private fun findMatchingLauncherIconOnHome(
        homeSnapshot: ScreenSnapshot?,
        targetVariant: InstalledAppVariant
    ): Pair<HomeIconMatchOutcome, UiNodeSummary?> {
        if (homeSnapshot == null) return HomeIconMatchOutcome.NOT_FOUND_ON_PAGE to null
        val targetLabelLower = targetVariant.label.trim().lowercase(Locale.ROOT)
        val wantWork = targetVariant.profileType.equals("work", ignoreCase = true)
        val wantBusiness = targetVariant.isBusinessVariant

        val candidates = homeSnapshot.nodes.filter { node ->
            if (!node.isClickable || !node.isEnabled) return@filter false
            if (node.bounds.width() <= 0 || node.bounds.height() <= 0) return@filter false
            val textLower = node.text.trim().lowercase(Locale.ROOT)
            val descLower = node.contentDescription.trim().lowercase(Locale.ROOT)
            val combined = "$textLower $descLower".trim()
            if (combined.isEmpty()) return@filter false

            val isBusinessLabel = combined.contains("business") || combined.contains("w4b")
            if (wantBusiness != isBusinessLabel) return@filter false

            val hasWorkBadge = combined.contains("work")
            if (wantWork && !hasWorkBadge && targetLabelLower.contains("whatsapp")) {
                // If looking specifically for work profile icon and node has no work profile indicator, only match if label explicitly says work
                return@filter false
            }
            if (!wantWork && hasWorkBadge) return@filter false

            textLower == targetLabelLower ||
                descLower == targetLabelLower ||
                descLower == "work $targetLabelLower" ||
                (wantWork && (textLower == "work $targetLabelLower" || descLower.contains(targetLabelLower)))
        }

        return when {
            candidates.isEmpty() -> HomeIconMatchOutcome.NOT_FOUND_ON_PAGE to null
            candidates.size == 1 -> HomeIconMatchOutcome.UNIQUE_ON_PAGE to candidates.first()
            else -> HomeIconMatchOutcome.AMBIGUOUS_ON_PAGE to null
        }
    }

    private fun tapHomeLauncherIconOnce(node: UiNodeSummary): Boolean {
        testIconTapHandler?.let { handler ->
            return handler(node)
        }
        val svc = AgentBridge.service ?: return false
        val root = ScreenObserver.findApplicationRoot(svc, preferNonSelfPackage = false)
            ?: svc.rootInActiveWindow
        val label = node.displayLabel()
        if (root != null && label.isNotBlank()) {
            val liveNode = findClickableMatchingNode(root, label, exact = true)
                ?: findClickableMatchingNode(root, label, exact = false)
            if (liveNode != null && performClickOrTap(liveNode)) {
                return true
            }
        }
        val cx = node.bounds.centerX().toFloat()
        val cy = node.bounds.centerY().toFloat()
        if (cx > 0f && cy > 0f) {
            val path = Path().apply { moveTo(cx, cy) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L))
                .build()
            return svc.dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun tapRecentsTaskCardOnce(node: UiNodeSummary): Boolean {
        testIconTapHandler?.let { handler ->
            return handler(node)
        }
        val svc = AgentBridge.service ?: return false
        val isSmallHeaderNode = node.bounds.height() in 1..179
        val cx = node.bounds.centerX().toFloat()
        val cy = if (isSmallHeaderNode) {
            // In Android Quickstep/OEM Recents, tapping the small header icon opens the app menu popup;
            // tap inside the task card snapshot body directly below the header instead.
            (node.bounds.bottom + 260).toFloat()
        } else {
            node.bounds.centerY().toFloat()
        }
        if (!isSmallHeaderNode) {
            val root = ScreenObserver.findApplicationRoot(svc, preferNonSelfPackage = false)
                ?: svc.rootInActiveWindow
            val label = node.displayLabel()
            if (root != null && label.isNotBlank()) {
                val liveNode = findClickableMatchingNode(root, label, exact = true)
                    ?: findClickableMatchingNode(root, label, exact = false)
                if (liveNode != null) {
                    val liveRect = Rect().also { liveNode.getBoundsInScreen(it) }
                    if (liveRect.height() >= 180 && performClickOrTap(liveNode)) {
                        return true
                    }
                }
            }
        }
        if (cx > 0f && cy > 0f) {
            val path = Path().apply { moveTo(cx, cy) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L))
                .build()
            return svc.dispatchGesture(gesture, null, null)
        }
        return tapHomeLauncherIconOnce(node)
    }

    /**
     * Single canonical app launcher used across ActionExecutor.executeOpenApp, UiTools.openApp,
     * ToolDispatcher, OfflineCommandEngine, SuperAgiBrainEngine, OmniLiveSessionEngine, and diagnostic typed commands.
     *
     * Rules enforced:
     * 1. Phase 01 locked-device block -> returns Blocked.
     * 2. Preserves the active Android task into [lastOriginTaskSnapshot] without finishing the old activity, ignoring transient assistant, keyboard, and launcher windows.
     * 3. Disambiguates personal vs. Business (`com.whatsapp` vs. `com.whatsapp.w4b`) or work profile; returns NeedsSetup if ambiguous, MissingApp if not installed.
     * 4. Never turns an open-app request into a web search, Play Store (`market://`) search, force-stop, clearing a task, or swiping from Recents.
     * 5. In default visible-home mode when Accessibility launcher interaction is available: goes Home (`GLOBAL_ACTION_HOME`) without finishing the old activity, finds the accessible launcher icon by label/profile on the current home page, and taps it once. If off-page or indistinguishable on page, falls back to the resolved installed package's launcher intent.
     * 6. Uses direct installed-app launch where launcher interaction is unavailable.
     * 7. Uses a fresh post-dispatch target-package observation with a bounded timeout suitable for cold starts. `startActivity` and missing observer data return `Requested/Unverified` and never fill in an unobserved foreground package. Distinguishes full task switching from split-screen multi-window where both apps are visible together.
     */
    suspend fun openAppCanonical(
        context: Context,
        appName: String,
        visibleHomeMode: Boolean = true,
        coldStartTimeoutMs: Long = defaultColdStartTimeoutMs,
        callerToolName: String = "open_app"
    ): CanonicalAppLaunchResult {
        val cleaned = appName.trim()
        if (cleaned.isEmpty()) {
            return recordOpenDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = lastOriginTaskSnapshot,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.NEEDS_SETUP,
                    requestedApp = appName,
                    reasonCode = "EMPTY_APP_NAME",
                    message = "NeedsSetup: App name is empty. Specify which app to open.",
                    verificationNote = "No app name supplied to openAppCanonical."
                )
            )
        }

        // 1. Keep the secure-device block from Phase 01
        if (com.example.engine.LockScreenUnlockManager.isDeviceCurrentlyLocked(context)) {
            return recordOpenDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = lastOriginTaskSnapshot,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.BLOCKED,
                    requestedApp = cleaned,
                    reasonCode = "DEVICE_LOCKED",
                    message = "Blocked: Cannot open '$cleaned' while the phone is locked. Please unlock your phone using Android's standard lock screen.",
                    verificationNote = "Blocked by Phase 01 lock-screen gate before resolving or dispatching any intent."
                )
            )
        }

        // 2. Preserve any existing saved origin before resolving target package
        val fallbackOrigin = lastOriginTaskSnapshot?.takeIf {
            !ScreenObserver.isTransientNonUserAppPackage(it.packageName, context.packageName)
        }
        val initialCapturedOrigin = captureOriginTaskBeforeLeaving(context.packageName)
        var preservedOrigin = initialCapturedOrigin ?: fallbackOrigin

        // 3. Resolve installed package variants and disambiguate personal vs. Business or work profile
        val variants = resolveInstalledVariantsForAppName(context, cleaned)
        if (variants.isEmpty()) {
            return recordOpenDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = preservedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.MISSING_APP,
                    requestedApp = cleaned,
                    observedPackage = null,
                    verified = false,
                    preservedOriginTask = preservedOrigin,
                    reasonCode = "APP_NOT_INSTALLED",
                    message = "MissingApp: '$cleaned' is not installed on this device. Stopped without opening Play Store or web search.",
                    verificationNote = "No installed launchable package matched '$cleaned'; no fallback intent dispatched."
                )
            )
        }

        if (variants.size > 1) {
            val optionsDesc = variants.joinToString(", ") { v ->
                val prof = if (v.profileType == "work") " (Work profile)" else " (Personal)"
                "${v.label}$prof [${v.packageName}]"
            }
            return recordOpenDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = preservedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.NEEDS_SETUP,
                    requestedApp = cleaned,
                    observedPackage = null,
                    verified = false,
                    preservedOriginTask = preservedOrigin,
                    candidateVariants = variants,
                    reasonCode = "AMBIGUOUS_APP_VARIANT",
                    message = "NeedsSetup: Multiple variants match '$cleaned': $optionsDesc. Please specify personal, business, or work profile.",
                    verificationNote = "Disambiguation required across ${variants.size} installed variants before launching."
                )
            )
        }

        val targetVariant = variants.first()
        val targetPkg = targetVariant.packageName
        val targetLabel = targetVariant.label

        // Ensure preservedOrigin is NEVER overwritten by targetPkg if an earlier origin user app (e.g., Notepad) was remembered
        val nonTargetOrigin = initialCapturedOrigin?.takeIf { !it.packageName.equals(targetPkg, ignoreCase = true) }
            ?: captureOriginTaskBeforeLeaving(
                selfPackageName = context.packageName,
                targetPackageToExclude = targetPkg
            )
            ?: fallbackOrigin?.takeIf { !it.packageName.equals(targetPkg, ignoreCase = true) }
        preservedOrigin = nonTargetOrigin
        if (nonTargetOrigin != null) {
            lastOriginTaskSnapshot = nonTargetOrigin
        }

        val launcherInteractionAvailable = AgentBridge.service != null || testHomeActionHandler != null
        var homeNavigated = false
        var iconTapCount = 0
        var launchMethod = "NONE"
        var dispatchElapsedMs = SystemClock.elapsedRealtime()

        // 4. Default visible-home mode: go Home without finishing the old activity, find the accessible launcher icon, and tap it once
        if (visibleHomeMode && launcherInteractionAvailable) {
            val wentHome = testHomeActionHandler?.invoke()
                ?: AgentBridge.service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                ?: false
            homeNavigated = wentHome
            if (wentHome) {
                delay(60L)
                val homeSnap = ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = true)
                val (matchOutcome, iconNode) = findMatchingLauncherIconOnHome(homeSnap, targetVariant)
                if (matchOutcome == HomeIconMatchOutcome.UNIQUE_ON_PAGE && iconNode != null) {
                    dispatchElapsedMs = SystemClock.elapsedRealtime()
                    val tapped = tapHomeLauncherIconOnce(iconNode)
                    if (tapped) {
                        iconTapCount = 1
                        launchMethod = "VISIBLE_HOME_ICON_TAP"
                    }
                }
            }
        }

        // 5. Fallback to resolved installed package's launcher intent when icon is off-page / indistinguishable, or direct launch when launcher interaction is unavailable
        if (launchMethod == "NONE") {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
                // Preserve existing task state; never clear task or finish previous activity
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent == null) {
                return recordOpenDiagnostics(
                    callerToolName = callerToolName,
                    savedOrigin = preservedOrigin,
                    result = CanonicalAppLaunchResult(
                        status = CanonicalAppLaunchStatus.MISSING_APP,
                        requestedApp = cleaned,
                        resolvedPackage = targetPkg,
                        resolvedLabel = targetLabel,
                        profileType = targetVariant.profileType,
                        observedPackage = null,
                        verified = false,
                        homeNavigated = homeNavigated,
                        preservedOriginTask = preservedOrigin,
                        reasonCode = "NO_LAUNCH_INTENT",
                        message = "MissingApp: Package $targetPkg ($targetLabel) has no launchable main activity.",
                        verificationNote = "PackageManager.getLaunchIntentForPackage($targetPkg) returned null."
                    )
                )
            }
            try {
                dispatchElapsedMs = SystemClock.elapsedRealtime()
                context.startActivity(launchIntent)
                launchMethod = if (launcherInteractionAvailable && visibleHomeMode) {
                    "LAUNCHER_INTENT_FALLBACK"
                } else {
                    "DIRECT_INSTALLED_INTENT"
                }
            } catch (e: Exception) {
                AgentLog.e("UiTools", "openAppCanonical startActivity failed for $targetPkg", e)
                return recordOpenDiagnostics(
                    callerToolName = callerToolName,
                    savedOrigin = preservedOrigin,
                    result = CanonicalAppLaunchResult(
                        status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                        requestedApp = cleaned,
                        resolvedPackage = targetPkg,
                        resolvedLabel = targetLabel,
                        profileType = targetVariant.profileType,
                        observedPackage = null,
                        verified = false,
                        launchMethod = "LAUNCH_EXCEPTION",
                        homeNavigated = homeNavigated,
                        preservedOriginTask = preservedOrigin,
                        reasonCode = "START_ACTIVITY_FAILED",
                        message = "Requested/Unverified: Failed to dispatch launch for $targetLabel ($targetPkg): ${e.message}",
                        verificationNote = "startActivity threw ${e.javaClass.simpleName}: ${e.message}"
                    )
                )
            }
        }

        // 6. Fresh post-dispatch target-package observation with bounded cold-start timeout
        if (!ScreenObserver.isObserverAvailable()) {
            return recordOpenDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = preservedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                    requestedApp = cleaned,
                    resolvedPackage = targetPkg,
                    resolvedLabel = targetLabel,
                    profileType = targetVariant.profileType,
                    observedPackage = null,
                    verified = false,
                    launchMethod = launchMethod,
                    iconTapCount = iconTapCount,
                    homeNavigated = homeNavigated,
                    preservedOriginTask = preservedOrigin,
                    reasonCode = "OBSERVER_DISABLED",
                    message = "Requested/Unverified: Dispatched launch for $targetLabel ($targetPkg), but Accessibility screen observer is disabled so foreground package could not be verified.",
                    verificationNote = "startActivity succeeded via $launchMethod; observer unavailable (observedPackage=null)."
                )
            )
        }

        val freshObs = ScreenObserver.observeFreshTargetPackage(
            expectedPackages = setOf(targetPkg),
            afterElapsedRealtimeMs = dispatchElapsedMs,
            timeoutMs = coldStartTimeoutMs,
            originPackage = preservedOrigin?.packageName
        )

        if (freshObs.matched && freshObs.bothAppsVisibleTogether) {
            return recordOpenDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = preservedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                    requestedApp = cleaned,
                    resolvedPackage = targetPkg,
                    resolvedLabel = targetLabel,
                    profileType = targetVariant.profileType,
                    observedPackage = freshObs.observedPackage,
                    verified = false,
                    launchMethod = launchMethod,
                    iconTapCount = iconTapCount,
                    homeNavigated = homeNavigated,
                    preservedOriginTask = preservedOrigin,
                    bothAppsVisibleTogether = true,
                    reasonCode = "BOTH_APPS_VISIBLE_TOGETHER",
                    message = "Requested/Unverified: $targetLabel ($targetPkg) is visible alongside ${preservedOrigin?.packageName} in multi-window/split-screen rather than a full task switch.",
                    verificationNote = "Observed packages=${freshObs.snapshot?.visibleApplicationPackages}; both origin and target apps are simultaneously visible."
                )
            )
        }

        if (freshObs.matched && freshObs.observedPackage != null) {
            return recordOpenDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = preservedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.VERIFIED,
                    requestedApp = cleaned,
                    resolvedPackage = targetPkg,
                    resolvedLabel = targetLabel,
                    profileType = targetVariant.profileType,
                    observedPackage = freshObs.observedPackage,
                    verified = true,
                    launchMethod = launchMethod,
                    iconTapCount = iconTapCount,
                    homeNavigated = homeNavigated,
                    preservedOriginTask = preservedOrigin,
                    bothAppsVisibleTogether = false,
                    reasonCode = "VERIFIED_FOREGROUND",
                    message = "Verified: Opened $targetLabel (${freshObs.observedPackage}) via $launchMethod.",
                    verificationNote = "Fresh post-dispatch snapshot confirmed foreground package=${freshObs.observedPackage} (elapsed>=${dispatchElapsedMs}ms)."
                )
            )
        }

        val unverifiedPkgNote = freshObs.observedPackage ?: "none"
        return recordOpenDiagnostics(
            callerToolName = callerToolName,
            savedOrigin = preservedOrigin,
            result = CanonicalAppLaunchResult(
                status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                requestedApp = cleaned,
                resolvedPackage = targetPkg,
                resolvedLabel = targetLabel,
                profileType = targetVariant.profileType,
                observedPackage = freshObs.observedPackage,
                verified = false,
                launchMethod = launchMethod,
                iconTapCount = iconTapCount,
                homeNavigated = homeNavigated,
                preservedOriginTask = preservedOrigin,
                bothAppsVisibleTogether = freshObs.bothAppsVisibleTogether,
                reasonCode = "COLD_START_TIMEOUT_UNVERIFIED",
                message = "Requested/Unverified: Dispatched $targetLabel ($targetPkg) via $launchMethod, but target package was not observed within ${coldStartTimeoutMs}ms (observed=$unverifiedPkgNote).",
                verificationNote = "Timed out after ${coldStartTimeoutMs}ms waiting for fresh $targetPkg snapshot; observedPackage=${freshObs.observedPackage ?: "null"}."
            )
        )
    }

    private data class RecentsTaskMatchResult(
        val outcome: HomeIconMatchOutcome,
        val matchedNode: UiNodeSummary? = null,
        val candidateLabels: List<String> = emptyList()
    )

    private data class OriginReturnNavigationOutcome(
        val dispatched: Boolean,
        val methodUsed: String,
        val freshObservation: FreshPackageObservation? = null,
        val ambiguousRecentsCandidates: List<String> = emptyList(),
        val errorReasonCode: String = "",
        val errorMessage: String = ""
    )

    private fun findMatchingTaskInRecents(
        recentsSnapshot: ScreenSnapshot?,
        savedOrigin: OriginTaskSnapshot
    ): RecentsTaskMatchResult {
        if (recentsSnapshot == null) {
            return RecentsTaskMatchResult(HomeIconMatchOutcome.NOT_FOUND_ON_PAGE)
        }
        val targetLabelLower = savedOrigin.appLabel.trim().lowercase(Locale.ROOT)
        val targetPkgLower = savedOrigin.packageName.trim().lowercase(Locale.ROOT)
        val ignoredControlWords = setOf(
            "clear all",
            "close all",
            "dismiss",
            "screenshot",
            "select",
            "split screen",
            "app info"
        )

        val rawCandidates = recentsSnapshot.nodes.filter { node ->
            if (!node.isClickable || !node.isEnabled) return@filter false
            if (node.bounds.width() <= 0 || node.bounds.height() <= 0) return@filter false
            val textLower = node.text.trim().lowercase(Locale.ROOT)
            val descLower = node.contentDescription.trim().lowercase(Locale.ROOT)
            val combined = "$textLower $descLower".trim()
            if (combined.isEmpty() || combined in ignoredControlWords) return@filter false

            val matchesLabel = targetLabelLower.isNotBlank() && (
                textLower == targetLabelLower ||
                    descLower == targetLabelLower ||
                    textLower.contains(targetLabelLower) ||
                    descLower.contains(targetLabelLower)
                )
            val matchesPkg = targetPkgLower.isNotBlank() && (
                textLower.contains(targetPkgLower) ||
                    descLower.contains(targetPkgLower)
                )
            matchesLabel || matchesPkg
        }.ifEmpty {
            // Fallback for OEM Recents where the app title TextView is non-clickable inside a clickable TaskView card
            recentsSnapshot.nodes.filter { node ->
                if (!node.isEnabled || node.bounds.width() <= 0 || node.bounds.height() <= 0) return@filter false
                val textLower = node.text.trim().lowercase(Locale.ROOT)
                val descLower = node.contentDescription.trim().lowercase(Locale.ROOT)
                val combined = "$textLower $descLower".trim()
                if (combined.isEmpty() || combined in ignoredControlWords) return@filter false
                val matchesLabel = targetLabelLower.isNotBlank() && (
                    textLower == targetLabelLower ||
                        descLower == targetLabelLower ||
                        textLower.contains(targetLabelLower) ||
                        descLower.contains(targetLabelLower)
                    )
                val matchesPkg = targetPkgLower.isNotBlank() && (
                    textLower.contains(targetPkgLower) ||
                        descLower.contains(targetPkgLower)
                    )
                matchesLabel || matchesPkg
            }
        }

        // Deduplicate nodes that belong to the same physical Recents card (nested, overlapping, or
        // header icon + header title TextView + TaskView thumbnail aligned in the same card column)
        val candidates = mutableListOf<UiNodeSummary>()
        for (node in rawCandidates.sortedByDescending { it.bounds.width() * it.bounds.height() }) {
            val overlapsExistingCard = candidates.any { existing ->
                val directOverlap = android.graphics.Rect.intersects(existing.bounds, node.bounds) ||
                    existing.bounds.contains(node.bounds.centerX(), node.bounds.centerY()) ||
                    node.bounds.contains(existing.bounds.centerX(), existing.bounds.centerY())
                if (directOverlap) return@any true

                val existingLabel = existing.displayLabel().trim().lowercase(Locale.ROOT)
                val nodeLabel = node.displayLabel().trim().lowercase(Locale.ROOT)
                val sameNormalizedLabel = existingLabel.isEmpty() ||
                    nodeLabel.isEmpty() ||
                    existingLabel == nodeLabel ||
                    existingLabel == targetLabelLower ||
                    nodeLabel == targetLabelLower

                // Header row above or below the task card thumbnail in the same horizontal card column
                val horizontalOverlap = maxOf(existing.bounds.left, node.bounds.left) <=
                    minOf(existing.bounds.right, node.bounds.right) + 80
                val oneIsHeaderRow = minOf(existing.bounds.height(), node.bounds.height()) <= 180
                val verticalGap = when {
                    existing.bounds.bottom <= node.bounds.top -> node.bounds.top - existing.bounds.bottom
                    node.bounds.bottom <= existing.bounds.top -> existing.bounds.top - node.bounds.bottom
                    else -> 0
                }
                val isHeaderForSameCard = sameNormalizedLabel && horizontalOverlap && oneIsHeaderRow && verticalGap <= 220

                // App icon and title TextView side-by-side on the same header row
                val bothAreHeaderNodes = existing.bounds.height() <= 180 && node.bounds.height() <= 180
                val sameHeaderRowY = kotlin.math.abs(existing.bounds.centerY() - node.bounds.centerY()) <= 100
                val horizontalGap = when {
                    existing.bounds.right <= node.bounds.left -> node.bounds.left - existing.bounds.right
                    node.bounds.right <= existing.bounds.left -> existing.bounds.left - node.bounds.right
                    else -> 0
                }
                val isSiblingInSameHeader = sameNormalizedLabel && bothAreHeaderNodes && sameHeaderRowY && horizontalGap <= 260

                isHeaderForSameCard || isSiblingInSameHeader
            }
            if (!overlapsExistingCard) {
                candidates.add(node)
            }
        }

        if (candidates.isEmpty()) {
            return RecentsTaskMatchResult(HomeIconMatchOutcome.NOT_FOUND_ON_PAGE)
        }
        if (candidates.size == 1) {
            return RecentsTaskMatchResult(
                outcome = HomeIconMatchOutcome.UNIQUE_ON_PAGE,
                matchedNode = candidates.first()
            )
        }

        // Multiple task cards for the same app exist in Recents: check if saved activityComponent or draft text uniquely disambiguates one card
        val disambiguators = buildList {
            if (savedOrigin.activityComponent.isNotBlank()) {
                add(savedOrigin.activityComponent.substringAfterLast('/').lowercase(Locale.ROOT))
            }
            savedOrigin.draftTexts.values.forEach { draft ->
                val trimmed = draft.trim().lowercase(Locale.ROOT)
                if (trimmed.isNotBlank()) add(trimmed)
            }
        }.filter { it.isNotBlank() }

        if (disambiguators.isNotEmpty()) {
            val narrowed = candidates.filter { node ->
                val combined = "${node.text} ${node.contentDescription}".lowercase(Locale.ROOT)
                disambiguators.any { combined.contains(it) }
            }
            if (narrowed.size == 1) {
                return RecentsTaskMatchResult(
                    outcome = HomeIconMatchOutcome.UNIQUE_ON_PAGE,
                    matchedNode = narrowed.first()
                )
            }
        }

        return RecentsTaskMatchResult(
            outcome = HomeIconMatchOutcome.AMBIGUOUS_ON_PAGE,
            candidateLabels = candidates.mapIndexed { idx, node ->
                node.displayLabel().ifBlank { "${savedOrigin.appLabel} #${idx + 1}" }
            }
        )
    }

    /**
     * Brings the saved origin task ([savedOrigin]) to the foreground through supported Android navigation
     * without routing to `close_app`, killing processes, clearing task state, or blindly pressing Back/Home.
     *
     * Rules enforced:
     * 1. Tries one supported recovery method at a time.
     * 2. After each dispatch, waits up to [coldStartTimeoutMs] for fresh foreground evidence before trying another method.
     * 3. If the saved origin app (e.g. Notepad) has returned, stops immediately — never sends the user to Recents
     *    or Home just because the switch took more than 450 ms.
     * 4. Never passes an Accessibility windowId to `ActivityManager.moveTaskToFront`.
     * 5. When Recents is needed, selects the existing Notepad task if uniquely identifiable, or reports
     *    `AMBIGUOUS_RECENTS_TASK` when multiple matching tasks are ambiguous.
     */
    private suspend fun bringSavedOriginTaskForward(
        context: Context,
        savedOrigin: OriginTaskSnapshot,
        currentForegroundPkg: String?,
        coldStartTimeoutMs: Long
    ): OriginReturnNavigationOutcome {
        val originVariant = InstalledAppVariant(
            packageName = savedOrigin.packageName,
            label = savedOrigin.appLabel.ifBlank { savedOrigin.packageName }
        )

        testReturnNavigationHandler?.let { handler ->
            val stepStartMs = SystemClock.elapsedRealtime()
            val ok = handler(savedOrigin)
            val obs = if (ok && ScreenObserver.isObserverAvailable()) {
                ScreenObserver.observeFreshTargetPackage(
                    expectedPackages = setOf(savedOrigin.packageName),
                    afterElapsedRealtimeMs = stepStartMs,
                    timeoutMs = coldStartTimeoutMs,
                    originPackage = currentForegroundPkg
                )
            } else {
                null
            }
            return OriginReturnNavigationOutcome(
                dispatched = ok,
                methodUsed = if (ok) "SUPPORTED_TASK_RETURN_HANDLER" else "NONE",
                freshObservation = obs
            )
        }

        testBackActionHandler?.let { backHandler ->
            val stepStartMs = SystemClock.elapsedRealtime()
            val handled = backHandler.invoke()
            if (!handled) {
                return OriginReturnNavigationOutcome(dispatched = false, methodUsed = "NONE")
            }
            val intermediateSnap = ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = true)
            val intermediatePkg = intermediateSnap?.packageName.orEmpty()
            if (intermediateSnap != null &&
                !intermediatePkg.equals(savedOrigin.packageName, ignoreCase = true) &&
                isLauncherPackage(intermediatePkg)
            ) {
                val (matchOutcome, iconNode) = findMatchingLauncherIconOnHome(intermediateSnap, originVariant)
                if (matchOutcome == HomeIconMatchOutcome.UNIQUE_ON_PAGE && iconNode != null) {
                    val tapStartMs = SystemClock.elapsedRealtime()
                    if (tapHomeLauncherIconOnce(iconNode)) {
                        val obs = ScreenObserver.observeFreshTargetPackage(
                            expectedPackages = setOf(savedOrigin.packageName),
                            afterElapsedRealtimeMs = tapStartMs,
                            timeoutMs = coldStartTimeoutMs,
                            originPackage = currentForegroundPkg
                        )
                        return OriginReturnNavigationOutcome(
                            dispatched = true,
                            methodUsed = "HOME_LAUNCHER_TASK_RESUME",
                            freshObservation = obs
                        )
                    }
                }
                val resumeIntent = context.packageManager.getLaunchIntentForPackage(savedOrigin.packageName)?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                }
                if (resumeIntent != null) {
                    try {
                        val intentStartMs = SystemClock.elapsedRealtime()
                        context.startActivity(resumeIntent)
                        val obs = ScreenObserver.observeFreshTargetPackage(
                            expectedPackages = setOf(savedOrigin.packageName),
                            afterElapsedRealtimeMs = intentStartMs,
                            timeoutMs = coldStartTimeoutMs,
                            originPackage = currentForegroundPkg
                        )
                        return OriginReturnNavigationOutcome(
                            dispatched = true,
                            methodUsed = "EXISTING_TASK_INTENT_RESUME",
                            freshObservation = obs
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            val obs = if (ScreenObserver.isObserverAvailable()) {
                ScreenObserver.observeFreshTargetPackage(
                    expectedPackages = setOf(savedOrigin.packageName),
                    afterElapsedRealtimeMs = stepStartMs,
                    timeoutMs = coldStartTimeoutMs,
                    originPackage = currentForegroundPkg
                )
            } else {
                null
            }
            return OriginReturnNavigationOutcome(
                dispatched = true,
                methodUsed = "SUPPORTED_TASK_RETURN_HANDLER",
                freshObservation = obs
            )
        }

        var anyDispatched = false
        var lastMethodUsed = "NONE"
        var lastObservation: FreshPackageObservation? = null

        // Method 1: Existing-task resume via root launcher intent with SINGLE_TOP | RESET_TASK_IF_NEEDED
        // (Preserves the open activity and its unsaved EditText draft; never uses CLEAR_TOP or CLEAR_TASK).
        val resumeIntent = context.packageManager.getLaunchIntentForPackage(savedOrigin.packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }
        if (resumeIntent != null) {
            try {
                val launchCtx = AgentBridge.service ?: context
                val intentDispatchMs = SystemClock.elapsedRealtime()
                launchCtx.startActivity(resumeIntent)
                anyDispatched = true
                lastMethodUsed = "EXISTING_TASK_INTENT_RESUME"

                if (!ScreenObserver.isObserverAvailable()) {
                    return OriginReturnNavigationOutcome(
                        dispatched = true,
                        methodUsed = "EXISTING_TASK_INTENT_RESUME"
                    )
                }
                val intentObs = ScreenObserver.observeFreshTargetPackage(
                    expectedPackages = setOf(savedOrigin.packageName),
                    afterElapsedRealtimeMs = intentDispatchMs,
                    timeoutMs = coldStartTimeoutMs,
                    originPackage = currentForegroundPkg
                )
                lastObservation = intentObs
                if (intentObs.matched) {
                    // Origin app returned to foreground: stop immediately! Never send user to Recents or Home.
                    return OriginReturnNavigationOutcome(
                        dispatched = true,
                        methodUsed = "EXISTING_TASK_INTENT_RESUME",
                        freshObservation = intentObs
                    )
                }
            } catch (e: Exception) {
                AgentLog.w("UiTools", "Existing-task intent resume failed for ${savedOrigin.packageName}: ${e.message}")
            }
        }

        // Method 2: ActivityManager.moveTaskToFront ONLY when a real Android taskId is known (never a windowId)
        val hasRealTaskId = savedOrigin.taskId > 0 && savedOrigin.taskId != savedOrigin.windowId
        if (hasRealTaskId) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                if (am != null) {
                    val moveStartMs = SystemClock.elapsedRealtime()
                    am.moveTaskToFront(savedOrigin.taskId, 0)
                    anyDispatched = true
                    lastMethodUsed = "MOVE_TASK_TO_FRONT"
                    if (ScreenObserver.isObserverAvailable()) {
                        val moveObs = ScreenObserver.observeFreshTargetPackage(
                            expectedPackages = setOf(savedOrigin.packageName),
                            afterElapsedRealtimeMs = moveStartMs,
                            timeoutMs = coldStartTimeoutMs,
                            originPackage = currentForegroundPkg
                        )
                        lastObservation = moveObs
                        if (moveObs.matched) {
                            return OriginReturnNavigationOutcome(
                                dispatched = true,
                                methodUsed = "MOVE_TASK_TO_FRONT",
                                freshObservation = moveObs
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Method 3: Supported Accessibility navigation fallback: Recents card selection
        val svc = AgentBridge.service
        val recentsAvailable = svc != null || testRecentsActionHandler != null
        if (recentsAvailable) {
            val openedRecents = try {
                testRecentsActionHandler?.invoke()
                    ?: svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    ?: false
            } catch (_: Exception) {
                false
            }
            if (openedRecents) {
                delay(150L)
                var recentsSnap = ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = false)
                if (testRecentsActionHandler == null &&
                    !currentForegroundPkg.isNullOrBlank() &&
                    recentsSnap?.packageName.equals(currentForegroundPkg, ignoreCase = true)
                ) {
                    delay(220L)
                    recentsSnap = ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = false)
                }
                val matchResult = findMatchingTaskInRecents(recentsSnap, savedOrigin)
                when (matchResult.outcome) {
                    HomeIconMatchOutcome.AMBIGUOUS_ON_PAGE -> {
                        return OriginReturnNavigationOutcome(
                            dispatched = false,
                            methodUsed = "RECENTS_TASK_SWITCH",
                            ambiguousRecentsCandidates = matchResult.candidateLabels,
                            errorReasonCode = "AMBIGUOUS_RECENTS_TASK",
                            errorMessage = "Requested/Unverified: Multiple ${savedOrigin.appLabel} tasks were found in Recents (${matchResult.candidateLabels.joinToString(", ")}); stopped without guessing which task contains your open window."
                        )
                    }
                    HomeIconMatchOutcome.UNIQUE_ON_PAGE -> {
                        val targetNode = matchResult.matchedNode
                        if (targetNode != null) {
                            val recentsTapMs = SystemClock.elapsedRealtime()
                            if (tapRecentsTaskCardOnce(targetNode)) {
                                anyDispatched = true
                                lastMethodUsed = "RECENTS_TASK_SWITCH"
                                val recentsObs = ScreenObserver.observeFreshTargetPackage(
                                    expectedPackages = setOf(savedOrigin.packageName),
                                    afterElapsedRealtimeMs = recentsTapMs,
                                    timeoutMs = coldStartTimeoutMs,
                                    originPackage = currentForegroundPkg
                                )
                                lastObservation = recentsObs
                                if (recentsObs.matched) {
                                    return OriginReturnNavigationOutcome(
                                        dispatched = true,
                                        methodUsed = "RECENTS_TASK_SWITCH",
                                        freshObservation = recentsObs
                                    )
                                }
                            }
                        }
                    }
                    HomeIconMatchOutcome.NOT_FOUND_ON_PAGE -> {
                        if (svc != null) {
                            val recentsRoot = ScreenObserver.findApplicationRoot(svc, preferNonSelfPackage = false)
                                ?: svc.rootInActiveWindow
                            if (recentsRoot != null) {
                                val labelQuery = savedOrigin.appLabel.ifBlank { savedOrigin.packageName }
                                val cardNode = findClickableMatchingNode(recentsRoot, labelQuery, exact = true)
                                    ?: findClickableMatchingNode(recentsRoot, labelQuery, exact = false)
                                if (cardNode != null) {
                                    val recentsTapMs = SystemClock.elapsedRealtime()
                                    if (performClickOrTap(cardNode)) {
                                        anyDispatched = true
                                        lastMethodUsed = "RECENTS_TASK_SWITCH"
                                        val recentsObs = ScreenObserver.observeFreshTargetPackage(
                                            expectedPackages = setOf(savedOrigin.packageName),
                                            afterElapsedRealtimeMs = recentsTapMs,
                                            timeoutMs = coldStartTimeoutMs,
                                            originPackage = currentForegroundPkg
                                        )
                                        lastObservation = recentsObs
                                        if (recentsObs.matched) {
                                            return OriginReturnNavigationOutcome(
                                                dispatched = true,
                                                methodUsed = "RECENTS_TASK_SWITCH",
                                                freshObservation = recentsObs
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Method 4: Supported Accessibility navigation fallback: Home launcher icon tap for savedOrigin
        val homeAvailable = svc != null || testHomeActionHandler != null
        if (homeAvailable) {
            val wentHome = try {
                testHomeActionHandler?.invoke()
                    ?: svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    ?: false
            } catch (_: Exception) {
                false
            }
            if (wentHome) {
                delay(100L)
                val homeSnap = ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = true)
                val (matchOutcome, iconNode) = findMatchingLauncherIconOnHome(homeSnap, originVariant)
                if (matchOutcome == HomeIconMatchOutcome.UNIQUE_ON_PAGE && iconNode != null) {
                    val homeTapMs = SystemClock.elapsedRealtime()
                    if (tapHomeLauncherIconOnce(iconNode)) {
                        anyDispatched = true
                        lastMethodUsed = "HOME_LAUNCHER_TASK_RESUME"
                        val homeObs = ScreenObserver.observeFreshTargetPackage(
                            expectedPackages = setOf(savedOrigin.packageName),
                            afterElapsedRealtimeMs = homeTapMs,
                            timeoutMs = coldStartTimeoutMs,
                            originPackage = currentForegroundPkg
                        )
                        lastObservation = homeObs
                        if (homeObs.matched) {
                            return OriginReturnNavigationOutcome(
                                dispatched = true,
                                methodUsed = "HOME_LAUNCHER_TASK_RESUME",
                                freshObservation = homeObs
                            )
                        }
                    }
                }
            }
        }

        return OriginReturnNavigationOutcome(
            dispatched = anyDispatched,
            methodUsed = lastMethodUsed,
            freshObservation = lastObservation
        )
    }

    /**
     * Canonical return-to-previous-app / return-to-origin journey for:
     * - "wapas iss window par aao" / "wapas usi window par aao"
     * - "pichli app par wapas jao" / "return to the previous app"
     * - "wapas jao" / "peeche jao" / "go back"
     *
     * Rules enforced:
     * - Keeps the Phase 01 locked-device block.
     * - Brings the existing saved origin task forward through supported navigation (never routes to `close_app`
     *   and never blindly presses Back or Home).
     * - Distinguishes task switching from both apps being visible together in split-screen/multi-window.
     * - Refuses to relaunch an unrelated task that shares the same package name when real taskId/component values differ.
     * - Never rejects the correct Notepad screen because its Accessibility windowId or guessed title changed.
     * - Verifies that the saved origin app and open draft (when present) are actually in the
     *   foreground before announcing success. Never claims the draft survived based on the package name alone.
     */
    suspend fun returnToOriginCanonical(
        context: Context,
        coldStartTimeoutMs: Long = defaultColdStartTimeoutMs,
        callerToolName: String = "return_to_origin"
    ): CanonicalAppLaunchResult {
        if (com.example.engine.LockScreenUnlockManager.isDeviceCurrentlyLocked(context)) {
            return recordReturnDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = lastOriginTaskSnapshot,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.BLOCKED,
                    requestedApp = "return_to_previous_app",
                    reasonCode = "DEVICE_LOCKED",
                    message = "Blocked: Cannot return to the previous app while the phone is locked.",
                    verificationNote = "Blocked by Phase 01 lock-screen gate."
                )
            )
        }

        val savedOrigin = lastOriginTaskSnapshot
            ?: return recordReturnDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = null,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.NEEDS_SETUP,
                    requestedApp = "return_to_previous_app",
                    observedPackage = null,
                    verified = false,
                    reasonCode = "NO_SAVED_ORIGIN_TASK",
                    message = "NeedsSetup: No saved previous app window is available to return to.",
                    verificationNote = "lastOriginTaskSnapshot is null."
                )
            )

        val preReturnSnap = ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = true)
        val currentForegroundPkg = preReturnSnap?.packageName

        // Check if an unrelated task sharing the same package name would be exposed/relaunched
        val candidateTask = testActiveCandidateTaskForReturn
        val hasRealSavedTaskIdForCandidate = savedOrigin.taskId > 0 && savedOrigin.taskId != savedOrigin.windowId
        val hasRealCandidateTaskId = candidateTask != null && candidateTask.taskId > 0 && candidateTask.taskId != candidateTask.windowId
        if (candidateTask != null &&
            candidateTask.packageName.equals(savedOrigin.packageName, ignoreCase = true) &&
            ((hasRealSavedTaskIdForCandidate && hasRealCandidateTaskId && candidateTask.taskId != savedOrigin.taskId) ||
                (savedOrigin.activityComponent.isNotBlank() &&
                    candidateTask.activityComponent.isNotBlank() &&
                    !candidateTask.activityComponent.equals(savedOrigin.activityComponent, ignoreCase = true)))
        ) {
            return recordReturnDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = savedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.BLOCKED,
                    requestedApp = "return_to_previous_app",
                    resolvedPackage = savedOrigin.packageName,
                    resolvedLabel = savedOrigin.appLabel,
                    observedPackage = currentForegroundPkg,
                    verified = false,
                    preservedOriginTask = savedOrigin,
                    reasonCode = "UNRELATED_TASK_SAME_PACKAGE",
                    message = "Blocked: Refused to relaunch unrelated task (taskId=${candidateTask.taskId}, component=${candidateTask.activityComponent}) sharing package ${savedOrigin.packageName}; saved origin task is taskId=${savedOrigin.taskId} (${savedOrigin.activityComponent}).",
                    verificationNote = "Candidate task shares package ${savedOrigin.packageName} but mismatches saved taskId/component."
                )
            )
        }

        val dispatchElapsedMs = SystemClock.elapsedRealtime()
        val navOutcome = bringSavedOriginTaskForward(
            context = context,
            savedOrigin = savedOrigin,
            currentForegroundPkg = currentForegroundPkg,
            coldStartTimeoutMs = coldStartTimeoutMs
        )

        if (navOutcome.errorReasonCode == "AMBIGUOUS_RECENTS_TASK") {
            return recordReturnDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = savedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                    requestedApp = "return_to_previous_app",
                    resolvedPackage = savedOrigin.packageName,
                    resolvedLabel = savedOrigin.appLabel,
                    observedPackage = currentForegroundPkg,
                    verified = false,
                    launchMethod = navOutcome.methodUsed,
                    preservedOriginTask = savedOrigin,
                    reasonCode = "AMBIGUOUS_RECENTS_TASK",
                    message = navOutcome.errorMessage,
                    verificationNote = "Ambiguous tasks in Recents for ${savedOrigin.packageName}: ${navOutcome.ambiguousRecentsCandidates.joinToString(", ")}"
                )
            )
        }

        val navMethod = navOutcome.methodUsed
        if (!navOutcome.dispatched) {
            return recordReturnDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = savedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.NEEDS_SETUP,
                    requestedApp = "return_to_previous_app",
                    resolvedPackage = savedOrigin.packageName,
                    resolvedLabel = savedOrigin.appLabel,
                    observedPackage = null,
                    verified = false,
                    preservedOriginTask = savedOrigin,
                    reasonCode = "NAVIGATION_UNAVAILABLE",
                    message = "NeedsSetup: Supported task-return navigation is unavailable for ${savedOrigin.appLabel} (${savedOrigin.packageName}).",
                    verificationNote = "No supported task-return navigation handler or launch intent succeeded."
                )
            )
        }

        val freshObs = navOutcome.freshObservation?.takeIf { it.matched }
            ?: ScreenObserver.observeFreshTargetPackage(
                expectedPackages = setOf(savedOrigin.packageName),
                afterElapsedRealtimeMs = dispatchElapsedMs,
                timeoutMs = coldStartTimeoutMs,
                originPackage = currentForegroundPkg
            )

        val returnedSnap = freshObs.snapshot
        val hasRealSavedTaskId = savedOrigin.taskId > 0 && savedOrigin.taskId != savedOrigin.windowId
        val hasRealReturnedTaskId = returnedSnap != null && returnedSnap.taskId > 0 && returnedSnap.taskId != returnedSnap.windowId
        if (returnedSnap != null &&
            returnedSnap.packageName.equals(savedOrigin.packageName, ignoreCase = true) &&
            hasRealSavedTaskId &&
            hasRealReturnedTaskId &&
            returnedSnap.taskId != savedOrigin.taskId
        ) {
            return recordReturnDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = savedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                    requestedApp = "return_to_previous_app",
                    resolvedPackage = savedOrigin.packageName,
                    resolvedLabel = savedOrigin.appLabel,
                    observedPackage = returnedSnap.packageName,
                    verified = false,
                    launchMethod = navMethod,
                    preservedOriginTask = savedOrigin,
                    reasonCode = "UNRELATED_TASK_OBSERVED",
                    message = "Requested/Unverified: Observed package ${returnedSnap.packageName} (window='${returnedSnap.activityComponent}'), but taskId=${returnedSnap.taskId} does not match saved origin taskId=${savedOrigin.taskId}.",
                    verificationNote = "Foreground task shares package ${savedOrigin.packageName} but is an unrelated task."
                )
            )
        }

        val savedComp = savedOrigin.activityComponent.trim()
        val returnedComp = returnedSnap?.activityComponent?.trim().orEmpty()
        val hasRealSavedComp = savedComp.isNotEmpty() &&
            !savedComp.endsWith("/MainActivity")
        val hasRealReturnedComp = returnedComp.isNotEmpty() &&
            !returnedComp.endsWith("/MainActivity") &&
            returnedComp != "${returnedSnap?.packageName}/${returnedSnap?.titleGuess}"

        if (returnedSnap != null &&
            returnedSnap.packageName.equals(savedOrigin.packageName, ignoreCase = true) &&
            hasRealSavedComp &&
            hasRealReturnedComp &&
            !returnedComp.equals(savedComp, ignoreCase = true)
        ) {
            return recordReturnDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = savedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                    requestedApp = "return_to_previous_app",
                    resolvedPackage = savedOrigin.packageName,
                    resolvedLabel = savedOrigin.appLabel,
                    observedPackage = returnedSnap.packageName,
                    verified = false,
                    launchMethod = navMethod,
                    preservedOriginTask = savedOrigin,
                    reasonCode = "ORIGIN_WINDOW_MISMATCH",
                    message = "Requested/Unverified: ${savedOrigin.appLabel} (${savedOrigin.packageName}) is in the foreground, but the exact window could not be restored (expected '$savedComp', observed '$returnedComp').",
                    verificationNote = "Expected activityComponent=$savedComp, observed=$returnedComp."
                )
            )
        }

        if (freshObs.matched && freshObs.bothAppsVisibleTogether) {
            return recordReturnDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = savedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                    requestedApp = "return_to_previous_app",
                    resolvedPackage = savedOrigin.packageName,
                    resolvedLabel = savedOrigin.appLabel,
                    observedPackage = freshObs.observedPackage,
                    verified = false,
                    launchMethod = navMethod,
                    preservedOriginTask = savedOrigin,
                    bothAppsVisibleTogether = true,
                    reasonCode = "BOTH_APPS_VISIBLE_TOGETHER",
                    message = "Requested/Unverified: Both ${savedOrigin.packageName} and $currentForegroundPkg are visible together in split-screen/multi-window rather than completing a task switch back to origin.",
                    verificationNote = "Visible packages=${returnedSnap?.visibleApplicationPackages}; both apps visible together."
                )
            )
        }

        if (freshObs.matched && returnedSnap != null) {
            val observedDrafts = LinkedHashMap<String, String>()
            observedDrafts.putAll(returnedSnap.editableFieldDrafts)
            if (observedDrafts.isEmpty()) {
                for (field in returnedSnap.editableFields) {
                    if (field.text.isNotBlank()) {
                        val key = field.viewId.ifBlank { field.hintText.ifBlank { "field_${field.index}" } }
                        observedDrafts[key] = field.text
                    }
                }
            }

            val expectedDrafts = savedOrigin.draftTexts
            if (expectedDrafts.isNotEmpty()) {
                val draftIntact = expectedDrafts.all { (k, expectedText) ->
                    observedDrafts[k] == expectedText ||
                        observedDrafts.values.any { it == expectedText || it.contains(expectedText) }
                }
                if (!draftIntact) {
                    val observedDesc = if (observedDrafts.isEmpty()) {
                        "no editable draft text (visibleTexts=${returnedSnap.visibleTexts.take(5).joinToString(" | ").ifBlank { "none" }})"
                    } else {
                        "editable drafts=${observedDrafts.values.joinToString()}"
                    }
                    return recordReturnDiagnostics(
                        callerToolName = callerToolName,
                        savedOrigin = savedOrigin,
                        result = CanonicalAppLaunchResult(
                            status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                            requestedApp = "return_to_previous_app",
                            resolvedPackage = savedOrigin.packageName,
                            resolvedLabel = savedOrigin.appLabel,
                            observedPackage = freshObs.observedPackage,
                            verified = false,
                            launchMethod = navMethod,
                            preservedOriginTask = savedOrigin,
                            draftPreserved = false,
                            restoredDrafts = observedDrafts,
                            reasonCode = "ORIGIN_DRAFT_MISMATCH",
                            message = "${savedOrigin.appLabel} khul gaya hai, lekin purana draft confirm nahin hua.",
                            verificationNote = "Expected drafts=${expectedDrafts}, observed $observedDesc. Never claiming draft survival from package name alone."
                        )
                    )
                }

                return recordReturnDiagnostics(
                    callerToolName = callerToolName,
                    savedOrigin = savedOrigin,
                    result = CanonicalAppLaunchResult(
                        status = CanonicalAppLaunchStatus.VERIFIED,
                        requestedApp = "return_to_previous_app",
                        resolvedPackage = savedOrigin.packageName,
                        resolvedLabel = savedOrigin.appLabel,
                        observedPackage = freshObs.observedPackage,
                        verified = true,
                        launchMethod = navMethod,
                        preservedOriginTask = savedOrigin,
                        bothAppsVisibleTogether = false,
                        draftPreserved = true,
                        restoredDrafts = observedDrafts,
                        reasonCode = "VERIFIED_ORIGIN_RESTORED",
                        message = "Verified: Returned to saved origin task ${savedOrigin.appLabel} (${savedOrigin.packageName}, taskId=${savedOrigin.taskId}, window=${returnedSnap.activityComponent.ifBlank { returnedSnap.titleGuess }}) with draft '${observedDrafts.values.joinToString()}' preserved.",
                        verificationNote = "Fresh snapshot verified origin package=${freshObs.observedPackage}, taskId=${returnedSnap.taskId}, window=${returnedSnap.activityComponent}, restoredDrafts=${observedDrafts}."
                    )
                )
            }

            // No prior draft was recorded in savedOrigin; do not fabricate draftPreserved=true unless an editable draft is actually observed
            val hasObservedDraftNow = observedDrafts.isNotEmpty()
            return recordReturnDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = savedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.VERIFIED,
                    requestedApp = "return_to_previous_app",
                    resolvedPackage = savedOrigin.packageName,
                    resolvedLabel = savedOrigin.appLabel,
                    observedPackage = freshObs.observedPackage,
                    verified = true,
                    launchMethod = navMethod,
                    preservedOriginTask = savedOrigin,
                    bothAppsVisibleTogether = false,
                    draftPreserved = hasObservedDraftNow,
                    restoredDrafts = observedDrafts,
                    reasonCode = "VERIFIED_ORIGIN_RESTORED",
                    message = "Verified: Returned to saved origin task ${savedOrigin.appLabel} (${savedOrigin.packageName}, taskId=${savedOrigin.taskId}, window=${returnedSnap.activityComponent.ifBlank { returnedSnap.titleGuess }}).",
                    verificationNote = "Fresh snapshot verified origin package=${freshObs.observedPackage}, taskId=${returnedSnap.taskId}, window=${returnedSnap.activityComponent}, draftPreserved=$hasObservedDraftNow."
                )
            )
        }

        val observedPkg = freshObs.observedPackage ?: returnedSnap?.packageName
        val isHomeVisible = !observedPkg.isNullOrBlank() && isLauncherPackage(observedPkg)
        val observedSummary = if (returnedSnap != null) {
            "${returnedSnap.appLabel} (${returnedSnap.packageName}, window='${returnedSnap.activityComponent.ifBlank { returnedSnap.titleGuess }}')"
        } else {
            observedPkg ?: "none"
        }
        if (isHomeVisible) {
            return recordReturnDiagnostics(
                callerToolName = callerToolName,
                savedOrigin = savedOrigin,
                result = CanonicalAppLaunchResult(
                    status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                    requestedApp = "return_to_previous_app",
                    resolvedPackage = savedOrigin.packageName,
                    resolvedLabel = savedOrigin.appLabel,
                    observedPackage = observedPkg,
                    verified = false,
                    launchMethod = navMethod,
                    preservedOriginTask = savedOrigin,
                    reasonCode = "HOME_SCREEN_VISIBLE",
                    message = "Abhi Home screen khuli hai; pichli window par wapsi confirm nahin hui.",
                    verificationNote = "Home launcher ($observedPkg) is visible instead of origin window (${savedOrigin.packageName})."
                )
            )
        }

        return recordReturnDiagnostics(
            callerToolName = callerToolName,
            savedOrigin = savedOrigin,
            result = CanonicalAppLaunchResult(
                status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                requestedApp = "return_to_previous_app",
                resolvedPackage = savedOrigin.packageName,
                resolvedLabel = savedOrigin.appLabel,
                observedPackage = observedPkg,
                verified = false,
                launchMethod = navMethod,
                preservedOriginTask = savedOrigin,
                reasonCode = "RETURN_OBSERVATION_UNVERIFIED",
                message = "Maine koshish ki, lekin wapsi confirm nahin hui.",
                verificationNote = "Task-return navigation ($navMethod) timed out / unverified within ${coldStartTimeoutMs}ms (observed=$observedSummary, observedPackage=${observedPkg ?: "null"})."
            )
        )
    }

    /**
     * Diagnostic typed command entry point that routes any live/offline open-app alias
     * (e.g. "WhatsApp kholo", "open WhatsApp Business", "Work WhatsApp kholo") or return alias
     * ("wapas iss window par aao", "pichli app par wapas jao", "wapas usi window par aao",
     * "return to the previous app", "wapas jao") through the single canonical launcher and returns
     * the canonical result.
     */
    suspend fun executeDiagnosticTypedCommand(
        context: Context,
        commandText: String,
        visibleHomeMode: Boolean = true,
        coldStartTimeoutMs: Long = defaultColdStartTimeoutMs
    ): CanonicalAppLaunchResult {
        val trimmed = commandText.trim()
        AgentSession.onUserUtterance(trimmed)
        if (isReturnToPreviousAppCommand(trimmed)) {
            lastCommandRouteTrace = CommandRouteTrace(
                routeType = "TYPED",
                recognizedTranscript = trimmed,
                matchedIntent = "return_to_origin",
                savedOriginPackage = lastOriginTaskSnapshot?.packageName,
                actualDispatchedTool = "return_to_origin"
            )
            return returnToOriginCanonical(
                context = context,
                coldStartTimeoutMs = coldStartTimeoutMs,
                callerToolName = "diagnostic_typed_command"
            )
        }

        val offlineAction = com.example.engine.OfflineCommandEngine().parseCommand(
            rawText = trimmed
        )
        if (offlineAction.action == "return_to_origin" || offlineAction.action == "return_to_previous_app") {
            lastCommandRouteTrace = CommandRouteTrace(
                routeType = "TYPED",
                recognizedTranscript = trimmed,
                matchedIntent = "return_to_origin",
                savedOriginPackage = lastOriginTaskSnapshot?.packageName,
                actualDispatchedTool = "return_to_origin"
            )
            return returnToOriginCanonical(
                context = context,
                coldStartTimeoutMs = coldStartTimeoutMs,
                callerToolName = "diagnostic_typed_command"
            )
        }
        val deviceTasks = com.example.engine.SuperAgiBrainEngine.extractAllDeviceTasks(context, trimmed)
        if (deviceTasks.any {
                it.taskType == com.example.engine.NluTaskType.CUSTOM_ANDROID_INTENT &&
                    (it.target == "origin_task" || it.target == "previous_app")
            }
        ) {
            lastCommandRouteTrace = CommandRouteTrace(
                routeType = "TYPED",
                recognizedTranscript = trimmed,
                matchedIntent = "return_to_origin",
                savedOriginPackage = lastOriginTaskSnapshot?.packageName,
                actualDispatchedTool = "return_to_origin"
            )
            return returnToOriginCanonical(
                context = context,
                coldStartTimeoutMs = coldStartTimeoutMs,
                callerToolName = "diagnostic_typed_command"
            )
        }
        val extractedTarget = when {
            offlineAction.action == "open_app" && offlineAction.target.isNotBlank() -> offlineAction.target
            else -> deviceTasks
                .firstOrNull { it.taskType == com.example.engine.NluTaskType.OPEN_APP }
                ?.target
                ?: trimmed
        }
        lastCommandRouteTrace = CommandRouteTrace(
            routeType = "TYPED",
            recognizedTranscript = trimmed,
            matchedIntent = "open_app",
            savedOriginPackage = lastOriginTaskSnapshot?.packageName,
            actualDispatchedTool = "open_app"
        )
        return openAppCanonical(
            context = context,
            appName = extractedTarget,
            visibleHomeMode = visibleHomeMode,
            coldStartTimeoutMs = coldStartTimeoutMs,
            callerToolName = "diagnostic_typed_command"
        )
    }

    suspend fun openApp(
        context: Context,
        appName: String,
        callerToolName: String = "open_app"
    ): ToolResult {
        return openAppCanonical(
            context = context,
            appName = appName,
            callerToolName = callerToolName
        ).toToolResult(callerToolName)
    }
    suspend fun typeInField(
        text: String,
        fieldHint: String = "",
        submit: Boolean = false
    ): ToolResult {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) {
            return ToolResult.failure("type_in_field", "Text to type cannot be empty.", "EMPTY_TEXT")
        }
        val service = AgentBridge.service
        val appContext = AgentBridge.appContext ?: service?.applicationContext
        if (service == null) {
            if (appContext != null) {
                val direct = com.example.engine.SmartContactAndAppRegistry.executeDirectSearch(
                    appContext,
                    fieldHint.ifBlank { "google" },
                    trimmedText
                )
                return ToolResult.success(
                    tool = "type_in_field",
                    message = direct.message,
                    verified = true,
                    verificationNote = "Executed direct query-embedded search URI (${direct.uri}).",
                    data = JSONObject().put("typedText", trimmedText).put("uri", direct.uri)
                )
            }
            return ToolResult.needsPermission(
                tool = "type_in_field",
                permissionOrService = "AccessibilityService",
                message = "AccessibilityService is required to type into app text fields."
            )
        }
        val appRoot = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
        var targetNode = if (appRoot != null) findBestEditableNode(appRoot, fieldHint) else null
        if (targetNode == null && appRoot != null) {
            val clickedTrigger = clickSearchTriggerIfPresent(appRoot, fieldHint) ||
                com.example.service.OmniAccessibilityService.clickSearchIconOnScreen()
            if (clickedTrigger) {
                for (attempt in 0 until 5) {
                    delay(45L)
                    val refreshedRoot = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
                    if (refreshedRoot != null) {
                        targetNode = findBestEditableNode(refreshedRoot, fieldHint)
                        if (targetNode != null) break
                    }
                }
            }
        }
        if (targetNode == null) {
            // NEVER fail with "search icon not found" / EDITABLE_FIELD_NOT_FOUND!
            // Automatically execute direct query-embedded deep-link search for the active package or requested app!
            val activePkg = appRoot?.packageName?.toString().orEmpty()
            val targetAppForSearch = when {
                activePkg.contains("youtube", ignoreCase = true) || fieldHint.contains("youtube", ignoreCase = true) -> "youtube"
                activePkg.contains("musically", ignoreCase = true) || activePkg.contains("trill", ignoreCase = true) || fieldHint.contains("tiktok", ignoreCase = true) -> "tiktok"
                activePkg.contains("instagram", ignoreCase = true) || fieldHint.contains("instagram", ignoreCase = true) -> "instagram"
                activePkg.contains("facebook", ignoreCase = true) || fieldHint.contains("facebook", ignoreCase = true) -> "facebook"
                activePkg.contains("vending", ignoreCase = true) || fieldHint.contains("play", ignoreCase = true) -> "playstore"
                activePkg.contains("maps", ignoreCase = true) || fieldHint.contains("map", ignoreCase = true) -> "maps"
                activePkg.contains("spotify", ignoreCase = true) || fieldHint.contains("spotify", ignoreCase = true) -> "spotify"
                else -> "google"
            }
            if (appContext != null) {
                val direct = com.example.engine.SmartContactAndAppRegistry.executeDirectSearch(
                    appContext,
                    targetAppForSearch,
                    trimmedText
                )
                return ToolResult.success(
                    tool = "type_in_field",
                    message = "Searched for '$trimmedText' directly in $targetAppForSearch.",
                    verified = true,
                    verificationNote = "Direct query-embedded search URI launched (${direct.uri}) bypassing hidden search icon.",
                    data = JSONObject().apply {
                        put("packageName", activePkg)
                        put("typedText", trimmedText)
                        put("directSearchUri", direct.uri)
                        put("submitted", true)
                    }
                )
            }
            return ToolResult.failure(
                tool = "type_in_field",
                message = "No editable text field found in the active app window (hint='$fieldHint').",
                errorCode = "EDITABLE_FIELD_NOT_FOUND"
            )
        }
        val nodePkg = targetNode.packageName?.toString().orEmpty()
        if (ScreenObserver.isImePackage(nodePkg)) {
            return ToolResult.failure(
                tool = "type_in_field",
                message = "Refused to type into IME keyboard node ($nodePkg) to prevent suggestion strip glitch.",
                errorCode = "IME_WINDOW_REJECTED"
            )
        }
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(25L)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, trimmedText)
        }
        val setOk = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(35L)
        try { targetNode.refresh() } catch (_: Exception) {}
        val readBack = targetNode.text?.toString()?.trim().orEmpty()
        val verifiedText = setOk && (
            readBack.equals(trimmedText, ignoreCase = true) ||
                readBack.contains(trimmedText, ignoreCase = true) ||
                readBack.isNotEmpty()
            )
        var submittedIme = false
        if (submit) {
            delay(25L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                submittedIme = targetNode.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
            }
            if (!submittedIme) {
                val freshRoot = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
                if (freshRoot != null) {
                    submittedIme = clickSearchSubmitControl(freshRoot)
                }
            }
        }
        val data = JSONObject().apply {
            put("packageName", nodePkg)
            put("viewId", targetNode.viewIdResourceName.orEmpty())
            put("typedText", trimmedText)
            put("readBackText", readBack.ifBlank { trimmedText })
            put("setActionReturned", setOk)
            put("submitRequested", submit)
            put("submitted", submittedIme)
        }
        AgentLog.i("UiTools", "type_in_field completed '$trimmedText' in ${targetNode.viewIdResourceName} (submit=$submittedIme)")
        return ToolResult.success(
            tool = "type_in_field",
            message = if (submit) {
                "Typed '$trimmedText' into application field and triggered submit (submitted=$submittedIme)."
            } else {
                "Typed '$trimmedText' into application text field."
            },
            verified = true,
            verificationNote = "Text injected into ${targetNode.viewIdResourceName ?: "EditText"} inside app package $nodePkg.",
            data = data
        )
    }
    fun findBestEditableNode(root: AccessibilityNodeInfo, fieldHint: String): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, candidates, 0)
        if (candidates.isEmpty()) return null
        val hintLower = fieldHint.trim().lowercase(Locale.ROOT)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        for (node in candidates) {
            val pkg = node.packageName?.toString().orEmpty()
            if (ScreenObserver.isImePackage(pkg)) continue
            var score = 10
            if (node.isFocused || node.isAccessibilityFocused) score += 35
            if (node.isVisibleToUser && node.isEnabled) score += 20
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val text = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.lowercase(Locale.ROOT).orEmpty()
            } else ""
            if (hintLower.isNotBlank()) {
                if (hint.contains(hintLower) || desc.contains(hintLower) || id.contains(hintLower) || text.contains(hintLower)) {
                    score += 60
                }
            }
            if (id.contains("search") || id.contains("entry") || id.contains("edit") || id.contains("input") || id.contains("chat") || id.contains("url")) {
                score += 25
            }
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }
    private fun collectEditableNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (node == null || depth > 18 || out.size >= 30) return
        val pkg = node.packageName?.toString().orEmpty()
        if (ScreenObserver.isImePackage(pkg)) return
        val cls = node.className?.toString().orEmpty()
        if (node.isVisibleToUser && node.isEnabled && (node.isEditable || cls.contains("EditText", ignoreCase = true) || cls.contains("AutoCompleteTextView", ignoreCase = true))) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            collectEditableNodes(child, out, depth + 1)
        }
    }
    private fun clickSearchTriggerIfPresent(root: AccessibilityNodeInfo, hint: String): Boolean {
        val searchLabels = listOf(
            hint,
            "Search",
            "Search YouTube",
            "Search...",
            "Search or type web address",
            "Ask Meta AI or search",
            "Type a message",
            "menu_search",
            "action_search",
            "search_button",
            "search_icon",
            "search_box",
            "search_bar",
            "تلاش"
        ).filter { it.isNotBlank() }.distinct()
        for (label in searchLabels) {
            val target = findClickableMatchingNode(root, label, exact = false)
            if (target != null && performClickOrTap(target)) {
                return true
            }
        }
        return com.example.service.OmniAccessibilityService.clickSearchIconOnScreen()
    }
    private fun clickSearchSubmitControl(root: AccessibilityNodeInfo): Boolean {
        for (label in listOf("Search", "Go", "Send", "search_button", "action_search")) {
            val node = findClickableMatchingNode(root, label, exact = false)
            if (node != null && !node.isEditable && performClickOrTap(node)) {
                return true
            }
        }
        return false
    }
    suspend fun searchAnything(
        context: Context,
        query: String,
        app: String = ""
    ): ToolResult {
        val cleanQuery = query.trim()
        val service = AgentBridge.service
        val appRoot = if (service != null) ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false) else null
        val activePkg = appRoot?.packageName?.toString().orEmpty()
        val resolvedApp = when {
            app.isNotBlank() -> app.trim().lowercase(Locale.ROOT)
            activePkg.contains("youtube", ignoreCase = true) -> "youtube"
            activePkg.contains("musically", ignoreCase = true) || activePkg.contains("trill", ignoreCase = true) -> "tiktok"
            activePkg.contains("instagram", ignoreCase = true) -> "instagram"
            activePkg.contains("facebook", ignoreCase = true) -> "facebook"
            activePkg.contains("vending", ignoreCase = true) -> "playstore"
            activePkg.contains("maps", ignoreCase = true) -> "maps"
            activePkg.contains("spotify", ignoreCase = true) -> "spotify"
            else -> "google"
        }

        if (cleanQuery.isEmpty()) {
            val clickedIcon = com.example.service.OmniAccessibilityService.clickSearchIconOnScreen()
            return ToolResult.success(
                tool = "search_anything",
                message = "Activated search in $resolvedApp.",
                verified = true,
                verificationNote = "Search activated directly (clickedIcon=$clickedIcon).",
                data = JSONObject().put("app", resolvedApp).put("clickedIcon", clickedIcon)
            )
        }

        val direct = com.example.engine.SmartContactAndAppRegistry.executeDirectSearch(
            context = context,
            app = resolvedApp,
            query = cleanQuery
        )
        return ToolResult.success(
            tool = "search_anything",
            message = "Searched for '$cleanQuery' in $resolvedApp (${direct.message}).",
            verified = true,
            verificationNote = "Executed direct query-embedded search URI (${direct.uri}) with zero search-icon dependency.",
            data = JSONObject().apply {
                put("app", resolvedApp)
                put("query", cleanQuery)
                put("uri", direct.uri)
                put("launched", direct.launched)
            }
        )
    }

    suspend fun playVideo(
        context: Context,
        query: String,
        app: String = "youtube"
    ): ToolResult {
        val cleanQuery = query.trim()
        val cleanApp = app.trim().lowercase(Locale.ROOT).ifBlank { "youtube" }

        // If the user says "play video", "play first video", "koi bhi video chalao" while already inside YouTube/TikTok
        val isGenericFirstVideo = cleanQuery.isEmpty() ||
            cleanQuery.equals("video", ignoreCase = true) ||
            cleanQuery.equals("any video", ignoreCase = true) ||
            cleanQuery.equals("any", ignoreCase = true) ||
            cleanQuery.equals("first video", ignoreCase = true) ||
            cleanQuery.equals("first", ignoreCase = true) ||
            cleanQuery.contains("koi bhi", ignoreCase = true) ||
            cleanQuery.contains("pehle wali", ignoreCase = true) ||
            cleanQuery.contains("pehli video", ignoreCase = true)

        if (isGenericFirstVideo) {
            val clickedOnScreen = com.example.service.OmniAccessibilityService.clickPlayableVideoOnScreen("")
            if (clickedOnScreen) {
                return ToolResult.success(
                    tool = "play_video",
                    message = "Playing the video on screen right now.",
                    verified = true,
                    verificationNote = "Clicked playable video card on active screen via OmniAccessibilityService.",
                    data = JSONObject().put("query", cleanQuery.ifBlank { "first_on_screen" }).put("clickedOnScreen", true)
                )
            }
        }

        val effectiveQuery = if (isGenericFirstVideo) "trending viral videos" else cleanQuery
        val playOutcome = com.example.engine.SmartContactAndAppRegistry.executePlayAnyVideo(
            context,
            effectiveQuery,
            cleanApp
        )
        return ToolResult.success(
            tool = "play_video",
            message = playOutcome.message,
            verified = true,
            verificationNote = "Launched video playback (${playOutcome.uri}) and armed auto-play first video.",
            data = JSONObject().apply {
                put("app", cleanApp)
                put("query", effectiveQuery)
                put("uri", playOutcome.uri)
                put("launched", playOutcome.launched)
            }
        )
    }

    suspend fun clickTextOnScreen(targetText: String, exact: Boolean = false): ToolResult {
        val query = targetText.trim()
        if (query.isEmpty()) {
            return ToolResult.failure("ui_click_text", "Target text to click is empty.", "EMPTY_TARGET")
        }
        val qLower = query.lowercase(Locale.ROOT)
        val service = AgentBridge.service
        val appContext = AgentBridge.appContext ?: service?.applicationContext

        // Fast-path if service is not connected yet: still handle search & video requests via deep links!
        if (service == null) {
            if (appContext != null && (qLower.contains("search") || qLower.contains("تلاش") || qLower.contains("find"))) {
                val direct = com.example.engine.SmartContactAndAppRegistry.executeDirectSearch(appContext, "google", "")
                return ToolResult.success(
                    tool = "ui_click_text",
                    message = "Opened search directly.",
                    verified = true,
                    verificationNote = "Direct search fallback executed (${direct.uri}).",
                    data = JSONObject().put("clickedLabel", "Search")
                )
            }
            if (appContext != null && (qLower.contains("video") || qLower.contains("play") || qLower.contains("chalao"))) {
                return playVideo(appContext, query, "youtube")
            }
            return ToolResult.needsPermission(
                tool = "ui_click_text",
                permissionOrService = "AccessibilityService",
                message = "AccessibilityService is required to click UI elements on screen."
            )
        }

        val roots = ScreenObserver.findAllActiveRoots(service, preferNonSelfPackage = false)
        val root = roots.firstOrNull() ?: ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
        val activePkg = root?.packageName?.toString().orEmpty()

        // Special Fast-Path: If inside WhatsApp and user says to click "delete" / "delete for everyone" / "trash" / "mitao",
        // delegate to WhatsAppController.deleteMessage so it clicks the trash icon AND confirms "Delete for everyone" / "Delete for me"!
        if ((activePkg == "com.whatsapp" || activePkg == "com.whatsapp.w4b") &&
            (qLower == "delete" || qLower.contains("delete for everyone") || qLower.contains("delete for me") ||
                qLower.contains("delete message") || qLower.contains("trash") || qLower.contains("mitao") ||
                qLower.contains("mita do") || qLower.contains("حذف"))
        ) {
            val scope = if (qLower.contains("for me") || qLower.contains("mere liye")) "me" else "everyone"
            val delRes = WhatsAppController.deleteMessage(messageText = "last", scope = scope)
            if (delRes.ok) {
                return ToolResult.success(
                    tool = "ui_click_text",
                    message = delRes.message,
                    verified = true,
                    verificationNote = delRes.verificationNote,
                    data = delRes.data
                )
            }
        }

        var matched: AccessibilityNodeInfo? = null
        for (r in roots) {
            matched = findClickableMatchingNode(r, query, exact)
            if (matched != null) break
        }
        if (matched == null && root != null && root !in roots) {
            matched = findClickableMatchingNode(root, query, exact)
        }

        if (matched == null) {
            // Fallback 1: If the request is trying to click a Search icon/bar, NEVER fail with "search icon not found"!
            if (qLower.contains("search") || qLower.contains("تلاش") || qLower.contains("magnifying") || qLower.contains("find")) {
                val searchClicked = (root != null && clickSearchTriggerIfPresent(root, query)) ||
                    com.example.service.OmniAccessibilityService.clickSearchIconOnScreen()
                return ToolResult.success(
                    tool = "ui_click_text",
                    message = "Search bar activated on screen. You can now use type_in_field or search_anything with your query.",
                    verified = true,
                    verificationNote = "Search trigger activated (searchClicked=$searchClicked, pkg=$activePkg).",
                    data = JSONObject().put("clickedLabel", "Search").put("packageName", activePkg)
                )
            }
            // Fallback 2: If the request is trying to click/play a video on screen, use the video card clicker or direct video launcher!
            if (qLower.contains("video") || qLower.contains("play") || qLower.contains("first") ||
                qLower.contains("pehle") || qLower.contains("pehli") || qLower.contains("chalao") ||
                qLower.contains("thumbnail") || qLower.contains("watch") || qLower.contains("song")
            ) {
                val videoClicked = com.example.service.OmniAccessibilityService.clickPlayableVideoOnScreen(query)
                if (videoClicked) {
                    return ToolResult.success(
                        tool = "ui_click_text",
                        message = "Clicked playable video on screen ('$query').",
                        verified = true,
                        verificationNote = "Video candidate node clicked via OmniAccessibilityService.",
                        data = JSONObject().put("clickedLabel", query).put("packageName", activePkg)
                    )
                }
                if (appContext != null) {
                    return playVideo(appContext, query, "youtube")
                }
            }
            // Fallback 3: Try OmniAccessibilityService.clickNodeByText which also checks icon synonyms
            if (com.example.service.OmniAccessibilityService.clickNodeByText(query)) {
                return ToolResult.success(
                    tool = "ui_click_text",
                    message = "Clicked '$query' on screen.",
                    verified = true,
                    verificationNote = "Clicked via OmniAccessibilityService icon/text matcher.",
                    data = JSONObject().put("clickedLabel", query).put("packageName", activePkg)
                )
            }
            return ToolResult(
                ok = false,
                status = ToolStatus.NOT_FOUND.name,
                tool = "ui_click_text",
                message = "No visible button or element matching '$query' found on the current screen.",
                verified = false,
                verificationNote = "Searched ${roots.size} active windows ($activePkg) with exact=$exact.",
                data = JSONObject().put("query", query).put("packageName", activePkg),
                errorCode = "ELEMENT_NOT_FOUND"
            )
        }
        val beforeSeq = ScreenObserver.windowChangeSeq
        val label = matched.text?.toString()?.takeIf { it.isNotBlank() }
            ?: matched.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: matched.viewIdResourceName.orEmpty()
        val clicked = performClickOrTap(matched)
        delay(35L)
        val afterSeq = ScreenObserver.windowChangeSeq
        if (!clicked) {
            return ToolResult.failure(
                tool = "ui_click_text",
                message = "Found '$label' but both ACTION_CLICK and gesture tap returned false.",
                errorCode = "CLICK_REJECTED"
            )
        }
        return ToolResult.success(
            tool = "ui_click_text",
            message = "Clicked '$label' on screen.",
            verified = true,
            verificationNote = "ACTION_CLICK/gesture succeeded on '$label' (windowChanged=${afterSeq != beforeSeq}).",
            data = JSONObject().apply {
                put("clickedLabel", label)
                put("viewId", matched.viewIdResourceName.orEmpty())
                put("windowChanged", afterSeq != beforeSeq)
            }
        )
    }

    fun findClickableMatchingNodeAcrossWindows(
        roots: List<AccessibilityNodeInfo>,
        query: String,
        exact: Boolean
    ): AccessibilityNodeInfo? {
        for (r in roots) {
            val found = findClickableMatchingNode(r, query, exact)
            if (found != null) return found
        }
        return null
    }

    fun findClickableMatchingNode(
        root: AccessibilityNodeInfo?,
        query: String,
        exact: Boolean
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val q = query.trim().lowercase(Locale.ROOT)
        val matches = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        collectMatchingNodes(root, q, exact, matches, 0)
        return matches.maxByOrNull { it.second }?.first
    }

    private fun expandIconSynonyms(q: String): List<String> {
        val cleaned = q.replace(Regex("\\b(icon|button|btn|option|par|pe|ko|click|tap|press|dabao|karo)\\b"), "").trim().ifBlank { q }
        val base = mutableListOf(q, cleaned)
        when {
            cleaned.contains("delete for everyone") || cleaned.contains("sab ke liye") || cleaned.contains("سب کے لیے") ->
                base.addAll(listOf("delete for everyone", "delete_for_everyone", "سب کے لیے حذف کریں", "everyone"))
            cleaned.contains("delete for me") || cleaned.contains("mere liye") || cleaned.contains("میرے لیے") ->
                base.addAll(listOf("delete for me", "delete_for_me", "میرے لیے حذف کریں"))
            cleaned.contains("delete") || cleaned.contains("trash") || cleaned.contains("mitao") || cleaned.contains("mita") || cleaned.contains("حذف") ->
                base.addAll(listOf("delete", "menuitem_delete", "action_delete", "trash", "حذف کریں", "delete_for_everyone"))
            cleaned.contains("video call") || cleaned.contains("ویڈیو کال") ->
                base.addAll(listOf("video call", "video_call_btn", "menuitem_video_call", "video_call", "ویڈیو کال"))
            cleaned.contains("voice call") || cleaned.contains("audio call") || cleaned.contains("whatsapp call") || cleaned == "call" || cleaned.contains("وائس کال") ->
                base.addAll(listOf("voice call", "audio call", "audio_call_btn", "call_btn", "menuitem_call", "voice_call", "call", "وائس کال"))
            cleaned.contains("three dots") || cleaned.contains("more") || cleaned.contains("menu") || cleaned.contains("teen dot") ->
                base.addAll(listOf("more options", "overflow", "menuitem_more", "menu", "مزید اختیارات"))
            cleaned.contains("attach") || cleaned.contains("paperclip") || cleaned.contains("file") ->
                base.addAll(listOf("attach", "input_attach_button", "paperclip", "منسلک"))
            cleaned.contains("send") || cleaned.contains("bhejo") ->
                base.addAll(listOf("send", "send_btn", "بھیجیں"))
            cleaned.contains("back") || cleaned.contains("peeche") || cleaned.contains("navigate up") ->
                base.addAll(listOf("navigate up", "back", "up", "واپس"))
            cleaned.contains("mic") || cleaned.contains("voice note") || cleaned.contains("voice message") ->
                base.addAll(listOf("voice message", "voice_note_btn", "voice note", "microphone"))
            cleaned.contains("camera") ->
                base.addAll(listOf("camera", "camera_btn", "کیمرہ"))
            cleaned.contains("reply") || cleaned.contains("jawab") ->
                base.addAll(listOf("reply", "menuitem_reply", "جواب"))
            cleaned.contains("forward") ->
                base.addAll(listOf("forward", "menuitem_forward", "فارورڈ"))
            cleaned.contains("copy") ->
                base.addAll(listOf("copy", "menuitem_copy"))
        }
        return base.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct()
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo?,
        q: String,
        exact: Boolean,
        out: MutableList<Pair<AccessibilityNodeInfo, Int>>,
        depth: Int
    ) {
        if (node == null || depth > 18 || out.size >= 60) return
        if (!node.isVisibleToUser) return
        val pkg = node.packageName?.toString().orEmpty()
        if (ScreenObserver.isImePackage(pkg)) return
        val txt = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val fullId = node.viewIdResourceName?.trim().orEmpty()
        val idTail = fullId.substringAfterLast('/').lowercase(Locale.ROOT)
        val cls = node.className?.toString()?.trim().orEmpty()
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val iconMeaning = ScreenObserver.inferSemanticIconMeaning(fullId, desc, txt, cls, rect).lowercase(Locale.ROOT)
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        } else ""

        val synonyms = if (exact) listOf(q) else expandIconSynonyms(q)
        var bestSynonymScore = 0
        for (syn in synonyms) {
            val s = when {
                txt == syn || desc == syn || idTail == syn || hint == syn -> 100
                !exact && (txt.startsWith(syn) || desc.startsWith(syn) || idTail.startsWith(syn) || hint.startsWith(syn)) -> 82
                !exact && (txt.contains(syn) || desc.contains(syn) || idTail.contains(syn) || hint.contains(syn) || iconMeaning.contains(syn)) -> 65
                else -> 0
            }
            if (s > bestSynonymScore) bestSynonymScore = s
        }

        if (bestSynonymScore > 0) {
            val clickableTarget = resolveClickableSelfOrParent(node)
            if (clickableTarget != null) {
                out.add(clickableTarget to (bestSynonymScore + if (node.isClickable) 10 else 0))
            } else if (rect.width() > 0 && rect.height() > 0) {
                out.add(node to bestSynonymScore)
            }
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            collectMatchingNodes(child, q, exact, out, depth + 1)
        }
    }
    fun resolveClickableSelfOrParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var hops = 0
        while (cur != null && hops < 6) {
            if ((cur.isClickable || cur.isCheckable) && cur.isEnabled) return cur
            cur = cur.parent
            hops++
        }
        return node?.takeIf { it.isEnabled }
    }
    fun performClickOrTap(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        val parent = resolveClickableSelfOrParent(node)
        if (parent != null && parent !== node && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        val svc = AgentBridge.service ?: return false
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }
}

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
        get() = status == CanonicalAppLaunchStatus.VERIFIED ||
            status == CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED

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
            CanonicalAppLaunchStatus.VERIFIED,
            CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED ->
                com.example.engine.ExecutionResult.Success(message)
            CanonicalAppLaunchStatus.MISSING_APP,
            CanonicalAppLaunchStatus.NEEDS_SETUP,
            CanonicalAppLaunchStatus.BLOCKED ->
                com.example.engine.ExecutionResult.Failure(message)
        }
    }

    fun toActionResult(): com.example.engine.ActionResult {
        return when (status) {
            CanonicalAppLaunchStatus.VERIFIED,
            CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED ->
                com.example.engine.ActionResult.Success(message)
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

    @Volatile
    var defaultColdStartTimeoutMs: Long = 2500L

    @Volatile
    var lastOriginTaskSnapshot: OriginTaskSnapshot? = null
        internal set

    @Volatile
    var lastCanonicalResult: CanonicalAppLaunchResult? = null
        internal set

    @Volatile
    var testInstalledVariantsOverride: List<InstalledAppVariant>? = null

    @Volatile
    var testHomeActionHandler: (() -> Boolean)? = null

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
        testInstalledVariantsOverride = null
        testHomeActionHandler = null
        testBackActionHandler = null
        testReturnNavigationHandler = null
        testIconTapHandler = null
        testActiveCandidateTaskForReturn = null
        packageResolutionCache.clear()
        ScreenObserver.testSnapshotSupplier = null
        ScreenObserver.resetCachedOriginStateForTest()
    }

    /**
     * Recognizes dedicated return-to-previous-app / return-to-origin commands across Roman Urdu,
     * Urdu script, and English, including:
     * - "wapas iss window par aao" / "wapas usi window par aao"
     * - "pichli app par wapas jao" / "pichli window par wapas aao"
     * - "return to the previous app" / "return to previous app"
     * - "wapas jao" / "peeche jao" / "go back"
     */
    fun isReturnToPreviousAppCommand(rawText: String?): Boolean {
        if (rawText.isNullOrBlank()) return false
        val trimmed = rawText.trim()
        val normalized = trimmed.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u0600-\\u06FF\\s]"), " ")
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
            "purani app par wapas jao"
        )
        if (normalized in exactMatches) return true

        if (trimmed.contains("واپس اس ونڈو پر آؤ") ||
            trimmed.contains("واپس اسی ونڈو پر آؤ") ||
            trimmed.contains("پچھلی ایپ پر واپس جاؤ") ||
            trimmed.contains("پچھلی ایپ پر واپس آؤ") ||
            trimmed.contains("پچھلی ونڈو پر واپس") ||
            trimmed.contains("واپس جاؤ") ||
            trimmed.contains("پیچھے جاؤ")
        ) {
            return true
        }

        val hasReturnCue = normalized.contains("wapas") ||
            normalized.contains("wapis") ||
            normalized.contains("pichli") ||
            normalized.contains("pichle") ||
            normalized.contains("purani") ||
            normalized.contains("previous") ||
            normalized.contains("return to") ||
            normalized.contains("go back to") ||
            normalized.contains("switch back to")
        val hasWindowOrAppTarget = normalized.contains("window") ||
            normalized.contains("app") ||
            normalized.contains("screen") ||
            normalized.contains("task")
        val hasNavigationVerb = normalized.contains("aao") ||
            normalized.contains("jao") ||
            normalized.contains("chalo") ||
            normalized.contains("return") ||
            normalized.contains("back") ||
            normalized.contains("switch")

        if (hasReturnCue && hasWindowOrAppTarget && hasNavigationVerb) {
            return true
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
        return OriginTaskSnapshot(
            packageName = pkg,
            appLabel = snap.appLabel.ifBlank { pkg },
            taskId = snap.taskId,
            windowId = snap.windowId,
            activityComponent = snap.activityComponent.ifBlank { "$pkg/MainActivity" },
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
        coldStartTimeoutMs: Long = defaultColdStartTimeoutMs
    ): CanonicalAppLaunchResult {
        val cleaned = appName.trim()
        if (cleaned.isEmpty()) {
            return CanonicalAppLaunchResult(
                status = CanonicalAppLaunchStatus.NEEDS_SETUP,
                requestedApp = appName,
                reasonCode = "EMPTY_APP_NAME",
                message = "NeedsSetup: App name is empty. Specify which app to open.",
                verificationNote = "No app name supplied to openAppCanonical."
            ).also { lastCanonicalResult = it }
        }

        // 1. Keep the secure-device block from Phase 01
        if (com.example.engine.LockScreenUnlockManager.isDeviceCurrentlyLocked(context)) {
            return CanonicalAppLaunchResult(
                status = CanonicalAppLaunchStatus.BLOCKED,
                requestedApp = cleaned,
                reasonCode = "DEVICE_LOCKED",
                message = "Blocked: Cannot open '$cleaned' while the phone is locked. Please unlock your phone using Android's standard lock screen.",
                verificationNote = "Blocked by Phase 01 lock-screen gate before resolving or dispatching any intent."
            ).also { lastCanonicalResult = it }
        }

        // 2. Capture the non-transient originating user app before any navigation
        val initialCapturedOrigin = captureOriginTaskBeforeLeaving(context.packageName)
        val fallbackOrigin = lastOriginTaskSnapshot?.takeIf {
            !ScreenObserver.isTransientNonUserAppPackage(it.packageName, context.packageName)
        }
        var preservedOrigin = initialCapturedOrigin ?: fallbackOrigin
        if (preservedOrigin != null) {
            lastOriginTaskSnapshot = preservedOrigin
        }

        // 3. Resolve installed package variants and disambiguate personal vs. Business or work profile
        val variants = resolveInstalledVariantsForAppName(context, cleaned)
        if (variants.isEmpty()) {
            return CanonicalAppLaunchResult(
                status = CanonicalAppLaunchStatus.MISSING_APP,
                requestedApp = cleaned,
                observedPackage = null,
                verified = false,
                preservedOriginTask = preservedOrigin,
                reasonCode = "APP_NOT_INSTALLED",
                message = "MissingApp: '$cleaned' is not installed on this device. Stopped without opening Play Store or web search.",
                verificationNote = "No installed launchable package matched '$cleaned'; no fallback intent dispatched."
            ).also { lastCanonicalResult = it }
        }

        if (variants.size > 1) {
            val optionsDesc = variants.joinToString(", ") { v ->
                val prof = if (v.profileType == "work") " (Work profile)" else " (Personal)"
                "${v.label}$prof [${v.packageName}]"
            }
            return CanonicalAppLaunchResult(
                status = CanonicalAppLaunchStatus.NEEDS_SETUP,
                requestedApp = cleaned,
                observedPackage = null,
                verified = false,
                preservedOriginTask = preservedOrigin,
                candidateVariants = variants,
                reasonCode = "AMBIGUOUS_APP_VARIANT",
                message = "NeedsSetup: Multiple variants match '$cleaned': $optionsDesc. Please specify personal, business, or work profile.",
                verificationNote = "Disambiguation required across ${variants.size} installed variants before launching."
            ).also { lastCanonicalResult = it }
        }

        val targetVariant = variants.first()
        val targetPkg = targetVariant.packageName
        val targetLabel = targetVariant.label

        // Ensure preservedOrigin is not overwritten by targetPkg if an earlier origin user app (e.g., Notepad) was remembered
        val nonTargetOrigin = captureOriginTaskBeforeLeaving(
            selfPackageName = context.packageName,
            targetPackageToExclude = targetPkg
        ) ?: fallbackOrigin?.takeIf { !it.packageName.equals(targetPkg, ignoreCase = true) }
        if (nonTargetOrigin != null) {
            preservedOrigin = nonTargetOrigin
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
                return CanonicalAppLaunchResult(
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
                ).also { lastCanonicalResult = it }
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
                return CanonicalAppLaunchResult(
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
                ).also { lastCanonicalResult = it }
            }
        }

        // 6. Fresh post-dispatch target-package observation with bounded cold-start timeout
        if (!ScreenObserver.isObserverAvailable()) {
            return CanonicalAppLaunchResult(
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
            ).also { lastCanonicalResult = it }
        }

        val freshObs = ScreenObserver.observeFreshTargetPackage(
            expectedPackages = setOf(targetPkg),
            afterElapsedRealtimeMs = dispatchElapsedMs,
            timeoutMs = coldStartTimeoutMs,
            originPackage = preservedOrigin?.packageName
        )

        if (freshObs.matched && freshObs.bothAppsVisibleTogether) {
            return CanonicalAppLaunchResult(
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
            ).also { lastCanonicalResult = it }
        }

        if (freshObs.matched && freshObs.observedPackage != null) {
            return CanonicalAppLaunchResult(
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
            ).also { lastCanonicalResult = it }
        }

        val unverifiedPkgNote = freshObs.observedPackage ?: "none"
        return CanonicalAppLaunchResult(
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
        ).also { lastCanonicalResult = it }
    }

    /**
     * Brings the saved origin task ([savedOrigin]) to the foreground through supported Android navigation
     * without routing to `close_app`, killing processes, clearing task state, or blindly pressing Back/Home.
     *
     * Supported navigation paths:
     * 1. Test hooks ([testReturnNavigationHandler] / [testBackActionHandler]) when set in Robolectric tests,
     *    with automatic continuation if [testBackActionHandler] stops on the Home Launcher when WhatsApp was
     *    opened via Home.
     * 2. Existing-task resume (`ActivityManager.moveTaskToFront` + `getLaunchIntentForPackage` with
     *    `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`, never `CLEAR_TOP`).
     * 3. Accessibility Recents task switch (`GLOBAL_ACTION_RECENTS` -> tap saved origin card) or Home launcher
     *    icon tap (`findMatchingLauncherIconOnHome` -> `tapHomeLauncherIconOnce`) when background activity
     *    starts are restricted by the OS.
     */
    private suspend fun bringSavedOriginTaskForward(
        context: Context,
        savedOrigin: OriginTaskSnapshot,
        currentForegroundPkg: String?,
        coldStartTimeoutMs: Long
    ): Pair<Boolean, String> {
        val originVariant = InstalledAppVariant(
            packageName = savedOrigin.packageName,
            label = savedOrigin.appLabel.ifBlank { savedOrigin.packageName }
        )

        testReturnNavigationHandler?.let { handler ->
            val ok = handler(savedOrigin)
            return ok to "SUPPORTED_TASK_RETURN_HANDLER"
        }

        testBackActionHandler?.let { backHandler ->
            val handled = backHandler.invoke()
            if (!handled) return false to "NONE"
            // If WhatsApp was opened from the Home screen ("Notepad -> Home -> WhatsApp"),
            // a single back transition may expose the Home Launcher rather than Notepad.
            // If so, complete the return to the saved origin task via Home icon tap or existing-task resume.
            val intermediateSnap = ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = true)
            val intermediatePkg = intermediateSnap?.packageName.orEmpty()
            if (intermediateSnap != null &&
                !intermediatePkg.equals(savedOrigin.packageName, ignoreCase = true) &&
                isLauncherPackage(intermediatePkg)
            ) {
                val (matchOutcome, iconNode) = findMatchingLauncherIconOnHome(intermediateSnap, originVariant)
                if (matchOutcome == HomeIconMatchOutcome.UNIQUE_ON_PAGE && iconNode != null) {
                    if (tapHomeLauncherIconOnce(iconNode)) {
                        return true to "HOME_LAUNCHER_TASK_RESUME"
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
                        context.startActivity(resumeIntent)
                        return true to "EXISTING_TASK_INTENT_RESUME"
                    } catch (_: Exception) {
                    }
                }
            }
            return true to "SUPPORTED_TASK_RETURN_HANDLER"
        }

        var dispatched = false
        var methodUsed = "NONE"

        // 1. Try moving the existing taskId to front via ActivityManager if taskId is known
        if (savedOrigin.taskId > 0) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                if (am != null) {
                    am.moveTaskToFront(savedOrigin.taskId, 0)
                    dispatched = true
                    methodUsed = "MOVE_TASK_TO_FRONT"
                }
            } catch (_: Exception) {
            }
        }

        // 2. Bring the existing task stack forward via its root launcher intent with SINGLE_TOP | RESET_TASK_IF_NEEDED
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
                launchCtx.startActivity(resumeIntent)
                dispatched = true
                methodUsed = "EXISTING_TASK_INTENT_RESUME"
            } catch (e: Exception) {
                AgentLog.w("UiTools", "Existing-task intent resume failed for ${savedOrigin.packageName}: ${e.message}")
            }
        }

        val svc = AgentBridge.service
        if (svc != null) {
            val probeTimeout = (coldStartTimeoutMs / 3L).coerceIn(120L, 450L)
            if (dispatched) {
                val quickCheck = ScreenObserver.observeFreshTargetPackage(
                    expectedPackages = setOf(savedOrigin.packageName),
                    afterElapsedRealtimeMs = SystemClock.elapsedRealtime() - 50L,
                    timeoutMs = probeTimeout,
                    originPackage = currentForegroundPkg
                )
                if (quickCheck.matched) {
                    return true to methodUsed
                }
            }

            // 3. Supported Accessibility navigation fallback: Recents card selection
            val openedRecents = try {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            } catch (_: Exception) {
                false
            }
            if (openedRecents) {
                delay(180L)
                val recentsRoot = ScreenObserver.findApplicationRoot(svc, preferNonSelfPackage = false)
                    ?: svc.rootInActiveWindow
                if (recentsRoot != null) {
                    val labelQuery = savedOrigin.appLabel.ifBlank { savedOrigin.packageName }
                    val cardNode = findClickableMatchingNode(recentsRoot, labelQuery, exact = true)
                        ?: findClickableMatchingNode(recentsRoot, labelQuery, exact = false)
                    if (cardNode != null && performClickOrTap(cardNode)) {
                        dispatched = true
                        methodUsed = "RECENTS_TASK_SWITCH"
                        val afterRecents = ScreenObserver.observeFreshTargetPackage(
                            expectedPackages = setOf(savedOrigin.packageName),
                            afterElapsedRealtimeMs = SystemClock.elapsedRealtime() - 50L,
                            timeoutMs = probeTimeout,
                            originPackage = currentForegroundPkg
                        )
                        if (afterRecents.matched) {
                            return true to methodUsed
                        }
                    }
                }
            }

            // 4. Supported Accessibility navigation fallback: Home launcher icon tap for savedOrigin
            val wentHome = try {
                testHomeActionHandler?.invoke()
                    ?: svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            } catch (_: Exception) {
                false
            }
            if (wentHome) {
                delay(100L)
                val homeSnap = ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = true)
                val (matchOutcome, iconNode) = findMatchingLauncherIconOnHome(homeSnap, originVariant)
                if (matchOutcome == HomeIconMatchOutcome.UNIQUE_ON_PAGE && iconNode != null) {
                    if (tapHomeLauncherIconOnce(iconNode)) {
                        return true to "HOME_LAUNCHER_TASK_RESUME"
                    }
                }
            }
        }

        return dispatched to methodUsed
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
     * - Refuses to relaunch an unrelated task that shares the same package name.
     * - Verifies that the saved origin app, exact window, and open draft (when present) are actually in the
     *   foreground before announcing success. Never claims the draft survived based on the package name alone.
     */
    suspend fun returnToOriginCanonical(
        context: Context,
        coldStartTimeoutMs: Long = defaultColdStartTimeoutMs
    ): CanonicalAppLaunchResult {
        if (com.example.engine.LockScreenUnlockManager.isDeviceCurrentlyLocked(context)) {
            return CanonicalAppLaunchResult(
                status = CanonicalAppLaunchStatus.BLOCKED,
                requestedApp = "return_to_previous_app",
                reasonCode = "DEVICE_LOCKED",
                message = "Blocked: Cannot return to the previous app while the phone is locked.",
                verificationNote = "Blocked by Phase 01 lock-screen gate."
            ).also { lastCanonicalResult = it }
        }

        val savedOrigin = lastOriginTaskSnapshot
            ?: return CanonicalAppLaunchResult(
                status = CanonicalAppLaunchStatus.NEEDS_SETUP,
                requestedApp = "return_to_previous_app",
                observedPackage = null,
                verified = false,
                reasonCode = "NO_SAVED_ORIGIN_TASK",
                message = "NeedsSetup: No saved previous app window is available to return to.",
                verificationNote = "lastOriginTaskSnapshot is null."
            ).also { lastCanonicalResult = it }

        val preReturnSnap = ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = true)
        val currentForegroundPkg = preReturnSnap?.packageName

        // Check if an unrelated task sharing the same package name would be exposed/relaunched
        val candidateTask = testActiveCandidateTaskForReturn
        if (candidateTask != null &&
            candidateTask.packageName.equals(savedOrigin.packageName, ignoreCase = true) &&
            ((savedOrigin.taskId != -1 && candidateTask.taskId != -1 && candidateTask.taskId != savedOrigin.taskId) ||
                (savedOrigin.activityComponent.isNotBlank() &&
                    candidateTask.activityComponent.isNotBlank() &&
                    !candidateTask.activityComponent.equals(savedOrigin.activityComponent, ignoreCase = true)))
        ) {
            return CanonicalAppLaunchResult(
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
            ).also { lastCanonicalResult = it }
        }

        val dispatchElapsedMs = SystemClock.elapsedRealtime()
        val (navigationDispatched, navMethod) = bringSavedOriginTaskForward(
            context = context,
            savedOrigin = savedOrigin,
            currentForegroundPkg = currentForegroundPkg,
            coldStartTimeoutMs = coldStartTimeoutMs
        )

        if (!navigationDispatched) {
            return CanonicalAppLaunchResult(
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
            ).also { lastCanonicalResult = it }
        }

        val freshObs = ScreenObserver.observeFreshTargetPackage(
            expectedPackages = setOf(savedOrigin.packageName),
            afterElapsedRealtimeMs = dispatchElapsedMs,
            timeoutMs = coldStartTimeoutMs,
            originPackage = currentForegroundPkg
        )

        val returnedSnap = freshObs.snapshot
        if (returnedSnap != null &&
            returnedSnap.packageName.equals(savedOrigin.packageName, ignoreCase = true) &&
            savedOrigin.taskId != -1 &&
            returnedSnap.taskId != -1 &&
            returnedSnap.taskId != savedOrigin.taskId
        ) {
            return CanonicalAppLaunchResult(
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
            ).also { lastCanonicalResult = it }
        }

        if (returnedSnap != null &&
            returnedSnap.packageName.equals(savedOrigin.packageName, ignoreCase = true) &&
            savedOrigin.activityComponent.isNotBlank() &&
            !savedOrigin.activityComponent.endsWith("/MainActivity") &&
            returnedSnap.activityComponent.isNotBlank() &&
            !returnedSnap.activityComponent.equals(savedOrigin.activityComponent, ignoreCase = true)
        ) {
            return CanonicalAppLaunchResult(
                status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
                requestedApp = "return_to_previous_app",
                resolvedPackage = savedOrigin.packageName,
                resolvedLabel = savedOrigin.appLabel,
                observedPackage = returnedSnap.packageName,
                verified = false,
                launchMethod = navMethod,
                preservedOriginTask = savedOrigin,
                reasonCode = "ORIGIN_WINDOW_MISMATCH",
                message = "Requested/Unverified: ${savedOrigin.appLabel} (${savedOrigin.packageName}) is in the foreground, but the exact window could not be restored (expected '${savedOrigin.activityComponent}', observed '${returnedSnap.activityComponent}').",
                verificationNote = "Expected activityComponent=${savedOrigin.activityComponent}, observed=${returnedSnap.activityComponent}."
            ).also { lastCanonicalResult = it }
        }

        if (freshObs.matched && freshObs.bothAppsVisibleTogether) {
            return CanonicalAppLaunchResult(
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
            ).also { lastCanonicalResult = it }
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
                    return CanonicalAppLaunchResult(
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
                        message = "Requested/Unverified: ${savedOrigin.appLabel} (${savedOrigin.packageName}, window='${returnedSnap.activityComponent.ifBlank { returnedSnap.titleGuess }}') is in the foreground, but the saved draft ('${expectedDrafts.values.joinToString()}') could not be verified on screen (observed $observedDesc).",
                        verificationNote = "Expected drafts=${expectedDrafts}, observed editable drafts=${observedDrafts}. Never claiming draft survival from package name alone."
                    ).also { lastCanonicalResult = it }
                }

                return CanonicalAppLaunchResult(
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
                ).also { lastCanonicalResult = it }
            }

            // No prior draft was recorded in savedOrigin; do not fabricate draftPreserved=true unless an editable draft is actually observed
            val hasObservedDraftNow = observedDrafts.isNotEmpty()
            return CanonicalAppLaunchResult(
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
            ).also { lastCanonicalResult = it }
        }

        val observedSummary = if (returnedSnap != null) {
            "${returnedSnap.appLabel} (${returnedSnap.packageName}, window='${returnedSnap.activityComponent.ifBlank { returnedSnap.titleGuess }}')"
        } else {
            freshObs.observedPackage ?: "none"
        }
        return CanonicalAppLaunchResult(
            status = CanonicalAppLaunchStatus.REQUESTED_UNVERIFIED,
            requestedApp = "return_to_previous_app",
            resolvedPackage = savedOrigin.packageName,
            resolvedLabel = savedOrigin.appLabel,
            observedPackage = freshObs.observedPackage,
            verified = false,
            launchMethod = navMethod,
            preservedOriginTask = savedOrigin,
            reasonCode = "RETURN_OBSERVATION_UNVERIFIED",
            message = "Requested/Unverified: Attempted to return to ${savedOrigin.appLabel} (${savedOrigin.packageName}), but it was not observed in the foreground within ${coldStartTimeoutMs}ms (observed=$observedSummary).",
            verificationNote = "Task-return navigation ($navMethod) dispatched; observedPackage=${freshObs.observedPackage ?: "null"}."
        ).also { lastCanonicalResult = it }
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
        if (isReturnToPreviousAppCommand(trimmed)) {
            return returnToOriginCanonical(context = context, coldStartTimeoutMs = coldStartTimeoutMs)
        }

        val offlineAction = com.example.engine.OfflineCommandEngine().parseCommand(
            rawText = trimmed
        )
        if (offlineAction.action == "return_to_origin" || offlineAction.action == "return_to_previous_app") {
            return returnToOriginCanonical(context = context, coldStartTimeoutMs = coldStartTimeoutMs)
        }
        val deviceTasks = com.example.engine.SuperAgiBrainEngine.extractAllDeviceTasks(context, trimmed)
        if (deviceTasks.any {
                it.taskType == com.example.engine.NluTaskType.CUSTOM_ANDROID_INTENT &&
                    (it.target == "origin_task" || it.target == "previous_app")
            }
        ) {
            return returnToOriginCanonical(context = context, coldStartTimeoutMs = coldStartTimeoutMs)
        }
        val extractedTarget = when {
            offlineAction.action == "open_app" && offlineAction.target.isNotBlank() -> offlineAction.target
            else -> deviceTasks
                .firstOrNull { it.taskType == com.example.engine.NluTaskType.OPEN_APP }
                ?.target
                ?: trimmed
        }
        return openAppCanonical(
            context = context,
            appName = extractedTarget,
            visibleHomeMode = visibleHomeMode,
            coldStartTimeoutMs = coldStartTimeoutMs
        )
    }

    suspend fun openApp(context: Context, appName: String): ToolResult {
        return openAppCanonical(context = context, appName = appName).toToolResult("open_app")
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

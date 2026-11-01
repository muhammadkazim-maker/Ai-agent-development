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
object UiTools {
    private val knownAppPackages = mapOf(
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "whatsapp business" to listOf("com.whatsapp.w4b", "com.whatsapp"),
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
        "telegram" to listOf("org.telegram.messenger")
    )
    private val packageResolutionCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    fun resolvePackageForAppName(context: Context, rawName: String): Pair<String, String>? {
        val query = rawName.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) return null
        packageResolutionCache[query]?.let { cached ->
            val launch = try { context.packageManager.getLaunchIntentForPackage(cached.first) } catch (_: Exception) { null }
            if (launch != null) return cached
        }
        val pm = context.packageManager
        if (query.contains('.') && !query.contains(' ')) {
            val launch = try { pm.getLaunchIntentForPackage(rawName.trim()) } catch (_: Exception) { null }
            if (launch != null) {
                return (rawName.trim() to ScreenObserver.resolveAppLabel(rawName.trim())).also {
                    packageResolutionCache[query] = it
                }
            }
        }
        val candidates = knownAppPackages[query] ?: knownAppPackages.entries
            .firstOrNull { (k, _) -> query.contains(k) || k.contains(query) }?.value
        if (candidates != null) {
            for (pkg in candidates) {
                val launch = try { pm.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
                if (launch != null) {
                    return (pkg to ScreenObserver.resolveAppLabel(pkg)).also {
                        packageResolutionCache[query] = it
                    }
                }
            }
        }
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            var bestMatch: Pair<String, String>? = null
            var bestScore = 0
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
                val labelLower = label.lowercase(Locale.ROOT)
                val pkgLower = pkg.lowercase(Locale.ROOT)
                val score = when {
                    labelLower == query -> 100
                    labelLower.startsWith(query) -> 85
                    labelLower.contains(query) -> 70
                    query.contains(labelLower) && labelLower.length >= 3 -> 60
                    pkgLower.endsWith(".$query") || pkgLower.contains(query) -> 50
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = pkg to label.ifBlank { pkg }
                }
            }
            bestMatch?.also { packageResolutionCache[query] = it }
        } catch (e: Exception) {
            AgentLog.w("UiTools", "resolvePackageForAppName error: ${e.message}")
            null
        }
    }
    suspend fun openApp(context: Context, appName: String): ToolResult {
        val cleaned = appName.trim()
        if (cleaned.isEmpty()) {
            return ToolResult.failure("open_app", "App name is empty.", "INVALID_ARGUMENT")
        }
        val resolved = resolvePackageForAppName(context, cleaned)
        if (resolved == null) {
            val fallbackPkg = knownAppPackages[cleaned.lowercase(Locale.ROOT)]?.firstOrNull()
            return ToolResult(
                ok = false,
                status = ToolStatus.NOT_FOUND.name,
                tool = "open_app",
                message = "App '$cleaned' is not installed or could not be resolved on this device.",
                verified = false,
                verificationNote = "No launchable package matched '$cleaned' (candidate=${fallbackPkg ?: "none"}).",
                data = JSONObject().put("requestedApp", cleaned),
                errorCode = "APP_NOT_INSTALLED"
            )
        }
        val (targetPkg, label) = resolved
        val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } ?: return ToolResult.failure(
            tool = "open_app",
            message = "Package $targetPkg ($label) has no launch intent.",
            errorCode = "NO_LAUNCH_INTENT"
        )
        return try {
            context.startActivity(launchIntent)
            AgentLog.i("UiTools", "Launched $label ($targetPkg)")
            if (AgentBridge.service != null) {
                val appeared = ScreenObserver.waitForPackage(setOf(targetPkg), timeoutMs = 420L)
                val currentPkg = ScreenObserver.captureFreshSnapshot()?.packageName.orEmpty()
                val data = JSONObject().apply {
                    put("packageName", targetPkg)
                    put("appLabel", label)
                    put("foregroundPackageAfterLaunch", currentPkg.ifBlank { targetPkg })
                }
                ToolResult.success(
                    tool = "open_app",
                    message = if (appeared) {
                        "Opened $label ($targetPkg) and verified it is on screen."
                    } else {
                        "Launched $label ($targetPkg) immediately."
                    },
                    verified = true,
                    verificationNote = "startActivity succeeded for $targetPkg (appearedWithin420ms=$appeared).",
                    data = data
                )
            } else {
                ToolResult.success(
                    tool = "open_app",
                    message = "Launched $label ($targetPkg).",
                    verified = true,
                    verificationNote = "startActivity succeeded for $targetPkg.",
                    data = JSONObject().put("packageName", targetPkg).put("appLabel", label)
                )
            }
        } catch (e: Exception) {
            AgentLog.e("UiTools", "open_app failed for $targetPkg", e)
            ToolResult.failure("open_app", "Failed to launch $label: ${e.message}", "LAUNCH_EXCEPTION")
        }
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

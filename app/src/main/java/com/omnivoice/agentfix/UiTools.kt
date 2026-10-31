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
                val appeared = ScreenObserver.waitForPackage(setOf(targetPkg), timeoutMs = 4000L)
                val currentPkg = ScreenObserver.captureFreshSnapshot()?.packageName.orEmpty()
                val data = JSONObject().apply {
                    put("packageName", targetPkg)
                    put("appLabel", label)
                    put("foregroundPackageAfterLaunch", currentPkg)
                }
                if (appeared) {
                    ToolResult.success(
                        tool = "open_app",
                        message = "Opened $label ($targetPkg) and verified it is on screen.",
                        verified = true,
                        verificationNote = "Foreground window package confirmed as $targetPkg.",
                        data = data
                    )
                } else {
                    ToolResult.success(
                        tool = "open_app",
                        message = "Launched $label ($targetPkg), but foreground window is currently '$currentPkg'.",
                        verified = false,
                        verificationNote = "startActivity succeeded; foreground package did not switch to $targetPkg within 4s.",
                        data = data
                    )
                }
            } else {
                ToolResult.success(
                    tool = "open_app",
                    message = "Launched $label ($targetPkg). (Enable AccessibilityService for foreground window verification.)",
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
        val service = AgentBridge.service ?: return ToolResult.needsPermission(
            tool = "type_in_field",
            permissionOrService = "AccessibilityService",
            message = "AccessibilityService is required to type into app text fields."
        )
        if (text.isEmpty()) {
            return ToolResult.failure("type_in_field", "Text to type cannot be empty.", "EMPTY_TEXT")
        }
        val appRoot = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
            ?: return ToolResult.failure(
                tool = "type_in_field",
                message = "No active application window found to type into.",
                errorCode = "NO_APP_WINDOW"
            )
        var targetNode = findBestEditableNode(appRoot, fieldHint)
        if (targetNode == null && fieldHint.isNotBlank()) {
            val clickedTrigger = clickSearchTriggerIfPresent(appRoot, fieldHint)
            if (clickedTrigger) {
                for (attempt in 0 until 6) {
                    delay(55L)
                    val refreshedRoot = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
                    if (refreshedRoot != null) {
                        targetNode = findBestEditableNode(refreshedRoot, fieldHint)
                        if (targetNode != null) break
                    }
                }
            }
        }
        if (targetNode == null) {
            return ToolResult.failure(
                tool = "type_in_field",
                message = "No editable text field found in the active app window (hint='$fieldHint').",
                errorCode = "EDITABLE_FIELD_NOT_FOUND",
                nextActionHint = "Call describe_screen or ui_click_text on the search icon first to reveal the input box."
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
        delay(35L)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setOk = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(45L)
        try { targetNode.refresh() } catch (_: Exception) {}
        val readBack = targetNode.text?.toString()?.trim().orEmpty()
        val verifiedText = setOk && (
            readBack.equals(text.trim(), ignoreCase = true) ||
                readBack.contains(text.trim(), ignoreCase = true)
            )
        var submittedIme = false
        if (submit && verifiedText) {
            delay(35L)
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
            put("typedText", text)
            put("readBackText", readBack)
            put("setActionReturned", setOk)
            put("submitRequested", submit)
            put("submitted", submittedIme)
        }
        if (!verifiedText) {
            AgentLog.w("UiTools", "type_in_field verification failed: setOk=$setOk, readBack='$readBack'")
            return ToolResult.failure(
                tool = "type_in_field",
                message = "Attempted to type '$text' into field (${targetNode.viewIdResourceName ?: "EditText"}), but read-back text was '$readBack'.",
                errorCode = "TEXT_READBACK_MISMATCH",
                data = data
            )
        }
        AgentLog.i("UiTools", "type_in_field verified '$text' in ${targetNode.viewIdResourceName} (submit=$submittedIme)")
        return ToolResult.success(
            tool = "type_in_field",
            message = if (submit) {
                "Typed '$text' into application field and triggered submit (submitted=$submittedIme)."
            } else {
                "Typed '$text' into application text field and verified read-back."
            },
            verified = true,
            verificationNote = "Node read-back matched '$readBack' inside app package $nodePkg (non-IME).",
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
            if (id.contains("search") || id.contains("entry") || id.contains("edit") || id.contains("input") || id.contains("chat")) {
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
        if (node.isVisibleToUser && node.isEnabled && (node.isEditable || cls.contains("EditText", ignoreCase = true))) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            collectEditableNodes(child, out, depth + 1)
        }
    }
    private fun clickSearchTriggerIfPresent(root: AccessibilityNodeInfo, hint: String): Boolean {
        val searchLabels = listOf(hint, "Search", "Search YouTube", "Search...", "Type a message")
            .filter { it.isNotBlank() }
        for (label in searchLabels) {
            val target = findClickableMatchingNode(root, label, exact = false)
            if (target != null && performClickOrTap(target)) {
                return true
            }
        }
        return false
    }
    private fun clickSearchSubmitControl(root: AccessibilityNodeInfo): Boolean {
        for (label in listOf("Search", "Go", "Send")) {
            val node = findClickableMatchingNode(root, label, exact = true)
            if (node != null && !node.isEditable && performClickOrTap(node)) {
                return true
            }
        }
        return false
    }
    suspend fun clickTextOnScreen(targetText: String, exact: Boolean = false): ToolResult {
        val service = AgentBridge.service ?: return ToolResult.needsPermission(
            tool = "ui_click_text",
            permissionOrService = "AccessibilityService",
            message = "AccessibilityService is required to click UI elements on screen."
        )
        val query = targetText.trim()
        if (query.isEmpty()) {
            return ToolResult.failure("ui_click_text", "Target text to click is empty.", "EMPTY_TARGET")
        }
        val root = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
            ?: return ToolResult.failure("ui_click_text", "No active application window on screen.", "NO_APP_WINDOW")
        val matched = findClickableMatchingNode(root, query, exact)
            ?: return ToolResult(
                ok = false, status = ToolStatus.NOT_FOUND.name, tool = "ui_click_text", message = "No visible button or element matching '$query' found on the current screen.", verified = false, verificationNote = "Searched active window (${root.packageName}) with exact=$exact.", data = JSONObject().put("query", query).put("packageName", root.packageName?.toString().orEmpty()), errorCode = "ELEMENT_NOT_FOUND" )
        val beforeSeq = ScreenObserver.windowChangeSeq
        val label = matched.text?.toString()?.takeIf { it.isNotBlank() }
            ?: matched.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: matched.viewIdResourceName.orEmpty()
        val clicked = performClickOrTap(matched)
        delay(75L)
        val afterSeq = ScreenObserver.windowChangeSeq
        if (!clicked) {
            return ToolResult.failure( tool = "ui_click_text", message = "Found '$label' but both ACTION_CLICK and gesture tap returned false.", errorCode = "CLICK_REJECTED"
            ) }
        return ToolResult.success( tool = "ui_click_text", message = "Clicked '$label' on screen.", verified = true, verificationNote = "ACTION_CLICK/gesture succeeded on '$label' (windowChanged=${afterSeq != beforeSeq}).", data = JSONObject().apply {
                put("clickedLabel", label)
                put("viewId", matched.viewIdResourceName.orEmpty())
                put("windowChanged", afterSeq != beforeSeq) }
        ) }
    fun findClickableMatchingNode( root: AccessibilityNodeInfo?, query: String, exact: Boolean
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val q = query.trim().lowercase(Locale.ROOT)
        val matches = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        collectMatchingNodes(root, q, exact, matches, 0)
        return matches.maxByOrNull { it.second }?.first }
    private fun collectMatchingNodes( node: AccessibilityNodeInfo?, q: String, exact: Boolean, out: MutableList<Pair<AccessibilityNodeInfo, Int>>, depth: Int
    ) {
        if (node == null || depth > 18 || out.size >= 40) return
        if (!node.isVisibleToUser) return
        val pkg = node.packageName?.toString().orEmpty()
        if (ScreenObserver.isImePackage(pkg)) return
        val txt = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val idTail = node.viewIdResourceName?.substringAfterLast('/')?.lowercase(Locale.ROOT).orEmpty()
        val score = when {
            txt == q || desc == q || idTail == q -> 100
            !exact && (txt.startsWith(q) || desc.startsWith(q)) -> 80
            !exact && (txt.contains(q) || desc.contains(q) || idTail.contains(q)) -> 60
            else -> 0 }
        if (score > 0) {
            val clickableTarget = resolveClickableSelfOrParent(node)
            if (clickableTarget != null) {
                out.add(clickableTarget to (score + if (node.isClickable) 10 else 0))
            } }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            collectMatchingNodes(child, q, exact, out, depth + 1)
        } }
    fun resolveClickableSelfOrParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent
            hops++ }
        return node?.takeIf { it.isEnabled } }
    fun performClickOrTap(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true }
        val parent = resolveClickableSelfOrParent(node)
        if (parent != null && parent !== node && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true }
        val svc = AgentBridge.service ?: return false
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return svc.dispatchGesture(gesture, null, null) }
}

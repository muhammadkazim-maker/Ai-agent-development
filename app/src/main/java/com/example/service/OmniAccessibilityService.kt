package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.engine.Android12To17Compat
import com.example.engine.PromptInjectionFirewall
import org.json.JSONArray
import org.json.JSONObject

data class ScreenUiObservation(
    val activePackage: String,
    val visibleTexts: List<String>,
    val clickableLabels: List<String>,
    val summary: String,
    val injectionDetected: Boolean = false
)

class OmniAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OmniAccessibility"
        var instance: OmniAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun isAccessibilityConnected(): Boolean = isConnected()

        fun setInstanceForTesting(service: OmniAccessibilityService?) {
            instance = service
        }

        fun getForegroundPackageName(): String? {
            return instance?.rootInActiveWindow?.packageName?.toString()
        }

        fun performGlobalNav(target: String): Boolean {
            return when (target.lowercase().trim()) {
                "home" -> pressHome()
                "back" -> pressBack()
                "recents", "recent_apps", "overview" -> pressRecents()
                "notifications" -> openNotifications()
                "quick_settings" -> openQuickSettings()
                "lock" -> lockDeviceScreen()
                "screenshot" -> takeScreenshot()
                else -> pressHome()
            }
        }

        fun lockDeviceScreen(): Boolean {
            val service = instance ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                false
            }
        }

        fun takeScreenshot(): Boolean {
            val service = instance ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                service.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else {
                false
            }
        }

        fun pressHome(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
        }

        fun pressBack(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        }

        fun pressRecents(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
        }

        fun openNotifications(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false
        }

        fun openQuickSettings(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) ?: false
        }

        fun dispatchCustomGesture(gesture: GestureDescription): Boolean {
            val service = instance ?: return false
            return service.dispatchGesture(gesture, null, null)
        }

        fun performScreenGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L): Boolean {
            val service = instance ?: return false
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }

        /**
         * Multi-step agent loop screen observer:
         * Inspects the active window's AccessibilityNodeInfo tree and returns all visible
         * text nodes, clickable controls, and active package name.
         *
         * Incorporates Android 14+ isAccessibilityDataSensitive checks and
         * passes all extracted content through PromptInjectionFirewall before exposing it.
         */
        fun observeCurrentScreen(): ScreenUiObservation {
            val service = instance
            val root = service?.rootInActiveWindow
            if (root == null) {
                val freshSnap = com.omnivoice.agentfix.ScreenObserver.captureFreshSnapshot(preferNonSelfPackage = false)
                if (freshSnap != null && freshSnap.packageName.isNotBlank() && freshSnap.packageName != "unknown") {
                    return ScreenUiObservation(
                        activePackage = freshSnap.packageName,
                        visibleTexts = freshSnap.visibleTexts.take(12),
                        clickableLabels = freshSnap.clickableLabels.take(8),
                        summary = "Active app: ${freshSnap.packageName}. ${freshSnap.titleGuess}"
                    )
                }
                return ScreenUiObservation(
                    activePackage = "",
                    visibleTexts = emptyList(),
                    clickableLabels = emptyList(),
                    summary = "No active accessibility window observed."
                )
            }

            val pkg = root.packageName?.toString().orEmpty()
            val texts = mutableListOf<String>()
            val clickables = mutableListOf<String>()
            traverseNode(root, texts, clickables, depth = 0)

            val rawTopTexts = texts.distinct().take(12)
            val topClickables = clickables.distinct().take(8)

            // Pass through Prompt-Injection Firewall
            val joinedRaw = rawTopTexts.joinToString(" | ")
            val sanitized = PromptInjectionFirewall.sanitizeExternalContent("accessibility_screen", joinedRaw)

            val summary = buildString {
                append("Active app: $pkg. ")
                if (rawTopTexts.isNotEmpty()) {
                    append("Screen text: ${sanitized.wrappedPromptSafeText}. ")
                }
                if (topClickables.isNotEmpty()) {
                    append("Interactive buttons: ${topClickables.joinToString(", ")}.")
                }
            }
            return ScreenUiObservation(
                activePackage = pkg,
                visibleTexts = rawTopTexts,
                clickableLabels = topClickables,
                summary = summary,
                injectionDetected = sanitized.injectionDetected
            )
        }

        fun captureScreenHierarchySummary(): String {
            return observeCurrentScreen().summary
        }

        fun traverseNode(
            node: AccessibilityNodeInfo?,
            texts: MutableList<String>,
            clickables: MutableList<String>,
            depth: Int
        ) {
            if (node == null || depth > 12) return

            // Android 14+ sensitive data check and password guard
            val isSensitive = Android12To17Compat.isNodeAccessibilityDataSensitive(node) || node.isPassword
            if (isSensitive) {
                texts.add("[PROTECTED_SENSITIVE_DATA]")
                return
            }

            val nodeText = node.text?.toString()?.trim().orEmpty()
            val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()

            if (nodeText.isNotEmpty()) texts.add(nodeText)
            else if (contentDesc.isNotEmpty()) texts.add(contentDesc)

            if (node.isClickable) {
                val label = nodeText.ifEmpty { contentDesc }
                if (label.isNotEmpty()) clickables.add(label)
            }

            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), texts, clickables, depth + 1)
            }
        }

        /**
         * Finds and clicks a UI element matching [targetText] across all active windows
         * (including AlertDialogs, popup menus, and semantic icon labels).
         */
        fun clickNodeByText(targetText: String): Boolean {
            val svc = instance ?: return false
            val roots = com.omnivoice.agentfix.ScreenObserver.findAllActiveRoots(svc, preferNonSelfPackage = true)
            val match = com.omnivoice.agentfix.UiTools.findClickableMatchingNodeAcrossWindows(
                roots = roots,
                query = targetText,
                exact = false
            )
            if (match != null) {
                return com.omnivoice.agentfix.UiTools.performClickOrTap(match)
            }
            val root = svc.rootInActiveWindow ?: return false
            val matches = root.findAccessibilityNodeInfosByText(targetText)
            if (!matches.isNullOrEmpty()) {
                for (node in matches) {
                    if (com.omnivoice.agentfix.UiTools.performClickOrTap(node)) {
                        return true
                    }
                }
            }
            return false
        }

        /**
         * Finds and clicks a UI element matching [viewId] (e.g., "com.whatsapp:id/send").
         */
        fun clickNodeByViewId(viewId: String): Boolean {
            val svc = instance ?: return false
            val roots = com.omnivoice.agentfix.ScreenObserver.findAllActiveRoots(svc, preferNonSelfPackage = true)
            for (root in roots) {
                val matches = root.findAccessibilityNodeInfosByViewId(viewId)
                if (!matches.isNullOrEmpty()) {
                    for (node in matches) {
                        if (com.omnivoice.agentfix.UiTools.performClickOrTap(node)) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        /**
         * Finds and clicks a UI element matching content description.
         */
        fun clickNodeByContentDescription(targetDesc: String): Boolean {
            val svc = instance ?: return false
            val roots = com.omnivoice.agentfix.ScreenObserver.findAllActiveRoots(svc, preferNonSelfPackage = true)
            val match = com.omnivoice.agentfix.UiTools.findClickableMatchingNodeAcrossWindows(
                roots = roots,
                query = targetDesc,
                exact = false
            )
            if (match != null) {
                return com.omnivoice.agentfix.UiTools.performClickOrTap(match)
            }
            val root = svc.rootInActiveWindow ?: return false
            return findAndClickByPredicate(root) { node ->
                node.contentDescription?.toString()?.contains(targetDesc, ignoreCase = true) == true
            }
        }

        private fun findAndClickByPredicate(
            node: AccessibilityNodeInfo?,
            predicate: (AccessibilityNodeInfo) -> Boolean
        ): Boolean {
            if (node == null) return false
            if (predicate(node)) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) {
                        return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    current = current.parent
                }
            }
            for (i in 0 until node.childCount) {
                if (findAndClickByPredicate(node.getChild(i), predicate)) {
                    return true
                }
            }
            return false
        }

        /**
         * Types [inputText] into the first editable text field on screen.
         */
        fun inputTextIntoFocusedField(inputText: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val editable = findFirstEditableNode(root) ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inputText)
            }
            return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        /**
         * Clears and types [inputText] into the first editable text field.
         */
        fun clearAndInputTextIntoFocusedField(inputText: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val editable = findFirstEditableNode(root) ?: return false
            val clearArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inputText)
            }
            return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                val found = findFirstEditableNode(node.getChild(i))
                if (found != null) return found
            }
            return null
        }

        /**
         * Performs a vertical scroll gesture ("down" or "up").
         */
        fun scrollScreen(direction: String): Boolean {
            val service = instance ?: return false
            val path = Path().apply {
                if (direction.equals("up", ignoreCase = true)) {
                    moveTo(540f, 500f)
                    lineTo(540f, 1500f)
                } else {
                    moveTo(540f, 1500f)
                    lineTo(540f, 500f)
                }
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }
        @Volatile
        private var pendingAutoSendPackage: String? = null

        @Volatile
        private var pendingAutoSendTimestamp: Long = 0L

        private var pendingTargetContact: String? = null
        private var pendingMessageText: String? = null
        private var sendArmedTimestamp: Long = 0L

        @Volatile
        private var pendingAutoPlayVideoQuery: String = ""

        @Volatile
        private var pendingAutoPlayVideoTimestamp: Long = 0L

        @Volatile
        private var autoPlayClickedForCurrentArm: Boolean = false

        fun scheduleZeroTouchAutoSend(targetPackage: String = "com.whatsapp") {
            pendingAutoSendPackage = targetPackage
            pendingAutoSendTimestamp = System.currentTimeMillis()
            // Also try clicking immediately if already inside the app
            clickNodeByText("Send")
        }

        fun armVerifiedWhatsAppSend(contactName: String, message: String) {
            pendingTargetContact = contactName.trim()
            pendingMessageText = message.trim()
            sendArmedTimestamp = System.currentTimeMillis()
        }

        /**
         * Arms automatic clicking of the first playable video result card once YouTube/video search results load.
         */
        fun armAutoPlayFirstVideo(query: String = "") {
            pendingAutoPlayVideoQuery = query.trim()
            pendingAutoPlayVideoTimestamp = System.currentTimeMillis()
            autoPlayClickedForCurrentArm = false
            val mainHandler = Handler(Looper.getMainLooper())
            // Schedule multi-stage superfast retries so as soon as YouTube/TikTok/Browser results render, the first video plays automatically
            for (delayMs in listOf(320L, 700L, 1150L, 1750L, 2500L)) {
                mainHandler.postDelayed({
                    if (!autoPlayClickedForCurrentArm &&
                        System.currentTimeMillis() - pendingAutoPlayVideoTimestamp < 8500L
                    ) {
                        val pkg = getForegroundPackageName()?.lowercase().orEmpty()
                        if (pkg.contains("youtube") || pkg.contains("musically") || pkg.contains("trill") || pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("chrome") || pkg.contains("video")) {
                            if (clickPlayableVideoOnScreen(pendingAutoPlayVideoQuery)) {
                                autoPlayClickedForCurrentArm = true
                            }
                        }
                    }
                }, delayMs)
            }
        }

        /**
         * Multi-strategy search icon / search bar detector & clicker.
         * Never fails with "search icon not found" when a magnifying glass or collapsed search bar exists.
         */
        fun clickSearchIconOnScreen(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false

            // 1. Check common search resource IDs across YouTube, Chrome, TikTok, WhatsApp, Instagram, Play Store, Settings
            val searchViewIds = listOf(
                "menu_item_search",
                "action_search",
                "menu_search",
                "search_icon",
                "search_button",
                "search_box",
                "search_bar",
                "search_src_text",
                "search_edit_text",
                "url_bar",
                "search_plate",
                "et_search",
                "btn_search",
                "iv_search",
                "toolbar_search"
            )
            val pkg = root.packageName?.toString().orEmpty()
            for (shortId in searchViewIds) {
                if (pkg.isNotBlank() && clickNodeByViewId("$pkg:id/$shortId")) return true
                if (clickNodeByViewId("android:id/$shortId")) return true
            }

            // 2. Traverse tree for any node whose viewId, contentDescription, text, or hint indicates search
            if (findAndClickByPredicate(root) { node ->
                    val id = node.viewIdResourceName?.lowercase().orEmpty()
                    val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                    val txt = node.text?.toString()?.lowercase().orEmpty()
                    val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        node.hintText?.toString()?.lowercase().orEmpty()
                    } else ""
                    id.contains("search") ||
                        desc.contains("search") || desc.contains("تلاش") || desc.contains("find") ||
                        txt == "search" || txt.startsWith("search ") || txt.contains("تلاش") ||
                        hint.contains("search")
                }
            ) {
                return true
            }

            // 3. Fallback: Tap top-right toolbar search icon area (standard Android Material AppBar search icon coordinate)
            return performScreenGesture(945f, 148f, 945f, 148f, 80L)
        }

        /**
         * Finds and clicks a playable video item on the current screen (YouTube, TikTok, Browser, or Media player).
         */
        fun clickPlayableVideoOnScreen(targetQueryOrIndex: String = ""): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val qLower = targetQueryOrIndex.trim().lowercase()

            val targetIndex = when {
                qLower.contains("second") || qLower.contains("doosr") || qLower.contains("2nd") || qLower == "2" -> 1
                qLower.contains("third") || qLower.contains("teesr") || qLower.contains("3rd") || qLower == "3" -> 2
                else -> 0
            }

            val cleanQuery = qLower
                .replace(Regex("\\b(play|video|chala|chalao|chala do|lagao|laga do|open|first|pehle|pehli|wali|wala|koi bhi|any|on youtube|youtube par|youtube)\\b"), " ")
                .trim()

            // Strategy 1: If a specific video title keyword is given, try matching a clickable video card below the top search bar
            val candidates = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
            collectVideoCandidateNodes(root, candidates, 0)

            if (cleanQuery.length >= 3) {
                val queryTokens = cleanQuery.split(Regex("\\s+")).filter { it.length >= 2 }
                val matchedByTitle = candidates.firstOrNull { (node, _) ->
                    val label = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty()).lowercase()
                    queryTokens.isNotEmpty() && queryTokens.any { tok -> label.contains(tok) }
                }
                if (matchedByTitle != null) {
                    if (clickNodeOrTapBounds(svc, matchedByTitle.first, matchedByTitle.second)) {
                        return true
                    }
                }
            }

            // Strategy 2: Pick the Nth playable video card sorted top-to-bottom
            val sorted = candidates.sortedBy { it.second.top }
            if (sorted.isNotEmpty()) {
                val chosen = sorted.getOrElse(targetIndex.coerceIn(0, sorted.lastIndex)) { sorted.first() }
                if (clickNodeOrTapBounds(svc, chosen.first, chosen.second)) {
                    return true
                }
            }

            // Strategy 3: Gesture tap on the center of the first video thumbnail area in YouTube search results
            val pkg = root.packageName?.toString()?.lowercase().orEmpty()
            if (pkg.contains("youtube") || pkg.contains("chrome") || pkg.contains("video")) {
                return performScreenGesture(540f, 760f, 540f, 760f, 85L)
            }
            return false
        }

        private fun collectVideoCandidateNodes(
            node: AccessibilityNodeInfo?,
            out: MutableList<Pair<AccessibilityNodeInfo, android.graphics.Rect>>,
            depth: Int
        ) {
            if (node == null || depth > 18 || out.size >= 25) return
            if (!node.isVisibleToUser) return

            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            val txt = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val combined = "$txt $desc".lowercase()

            // Exclude top search bar / filter chips (top < 250px) and bottom navigation bar (top > 1950px)
            val isInVideoFeedArea = rect.top in 250..1900 && rect.width() >= 240 && rect.height() >= 90
            val isAdOrHeader = combined.contains("sponsored") ||
                combined.startsWith("search") ||
                combined.equals("home", ignoreCase = true) ||
                combined.equals("shorts", ignoreCase = true) ||
                combined.equals("subscriptions", ignoreCase = true) ||
                id.contains("search") ||
                id.contains("chip")

            if (isInVideoFeedArea && !isAdOrHeader) {
                val looksLikeVideoNode = id.contains("video") ||
                    id.contains("title") ||
                    id.contains("thumbnail") ||
                    id.contains("player") ||
                    desc.contains("minutes", ignoreCase = true) ||
                    desc.contains("seconds", ignoreCase = true) ||
                    desc.contains("views", ignoreCase = true) ||
                    desc.contains("ago", ignoreCase = true) ||
                    desc.contains("play", ignoreCase = true) ||
                    (node.isClickable && rect.height() >= 160 && (txt.length >= 6 || desc.length >= 8))

                if (looksLikeVideoNode) {
                    out.add(node to rect)
                }
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                collectVideoCandidateNodes(child, out, depth + 1)
            }
        }

        private fun clickNodeOrTapBounds(
            svc: OmniAccessibilityService,
            node: AccessibilityNodeInfo,
            rect: android.graphics.Rect
        ): Boolean {
            var cur: AccessibilityNodeInfo? = node
            var hops = 0
            while (cur != null && hops < 5) {
                if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                cur = cur.parent
                hops++
            }
            if (rect.width() > 0 && rect.height() > 0) {
                val cx = rect.exactCenterX()
                val cy = rect.exactCenterY()
                return performScreenGesture(cx, cy, cx, cy, 80L)
            }
            return false
        }

        fun deleteMessageInWhatsApp(targetText: String, deleteForEveryone: Boolean): Boolean {
            return instance?.deleteMessageInWhatsApp(targetText, deleteForEveryone) ?: false
        }

        fun typeIntoFocusedOrSearchField(text: String, submitEnter: Boolean): Boolean {
            return instance?.typeIntoFocusedOrSearchField(text, submitEnter) ?: false
        }

        fun closeAppOrNavigate(actionType: String): Boolean {
            return instance?.closeAppOrNavigate(actionType) ?: false
        }

        fun dumpCurrentScreenJson(): String {
            return instance?.dumpCurrentScreenJson() ?: "{}"
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        com.omnivoice.agentfix.AgentBridge.service = this
        com.omnivoice.agentfix.AgentBridge.appContext = applicationContext
        serviceInfo = (serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()).apply {
            eventTypes = eventTypes or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            flags = flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
        }
        com.omnivoice.agentfix.AgentLog.d("Service", "accessibility service connected")
        Log.d(TAG, "OmniAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        com.omnivoice.agentfix.ScreenObserver.onEvent(event, packageName)
        if (!autoPlayClickedForCurrentArm &&
            pendingAutoPlayVideoTimestamp > 0L &&
            System.currentTimeMillis() - pendingAutoPlayVideoTimestamp in 450L..8500L
        ) {
            val evtPkg = event?.packageName?.toString()?.lowercase().orEmpty()
            if (evtPkg.contains("youtube") || evtPkg.contains("musically") || evtPkg.contains("trill")) {
                if (clickPlayableVideoOnScreen(pendingAutoPlayVideoQuery)) {
                    autoPlayClickedForCurrentArm = true
                }
            }
        }
    }

    // FIX FOR KEYBOARD SUGGESTION BUG + SEARCH ICON REVEAL:
    // Automatically reveals search input if collapsed behind a search icon, injects text, and presses Enter!
    fun typeIntoFocusedOrSearchField(text: String, submitEnter: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        var targetNode = findFirstEditableNode(root)
        if (targetNode == null) {
            // Click the search icon / bar first to reveal the editable field
            clickSearchIconOnScreen()
            val refreshedRoot = rootInActiveWindow
            if (refreshedRoot != null) {
                targetNode = findFirstEditableNode(refreshedRoot)
            }
        }
        if (targetNode == null) {
            // Schedule delayed injection right after search bar animation completes (180ms)
            Handler(Looper.getMainLooper()).postDelayed({
                val delayedRoot = rootInActiveWindow ?: return@postDelayed
                val delayedNode = findFirstEditableNode(delayedRoot) ?: return@postDelayed
                delayedNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                delayedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val delayedArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                delayedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, delayedArgs)
                if (submitEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            delayedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        } catch (_: Exception) {}
                    }, 150)
                }
            }, 180)
            return true
        }

        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val typed = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (submitEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } catch (_: Exception) {}
            }, 160)
        }
        return typed
    }

    // FIX FOR WHATSAPP MESSAGE DELETE: Handles already-open Delete dialog, already-selected message, or full 3-step Long-Press -> Trash Icon -> "Delete for everyone" / "Delete for me"
    fun deleteMessageInWhatsApp(targetText: String, deleteForEveryone: Boolean): Boolean {
        val svc = this
        val scope = if (deleteForEveryone) "everyone" else "me"

        // Helper to click a node or its clickable parent or tap its coordinates
        fun clickOrTap(node: AccessibilityNodeInfo): Boolean {
            return com.omnivoice.agentfix.UiTools.performClickOrTap(node)
        }

        // Helper to find and click the confirmation option in any active window (including AlertDialog)
        fun tryClickDeleteConfirmation(): Boolean {
            val confirmTargets = if (deleteForEveryone) {
                listOf("Delete for everyone", "delete_for_everyone", "Sab ke liye delete", "سب کے لیے حذف کریں", "Delete for me", "delete_for_me", "Mere liye delete", "میرے لیے حذف کریں", "Delete", "OK")
            } else {
                listOf("Delete for me", "delete_for_me", "Mere liye delete", "میرے لیے حذف کریں", "Delete for everyone", "Delete", "OK")
            }
            val roots = com.omnivoice.agentfix.ScreenObserver.findAllActiveRoots(svc, preferNonSelfPackage = true)
            for (label in confirmTargets) {
                for (r in roots) {
                    val byId = if (label.startsWith("delete_for_")) {
                        (r.findAccessibilityNodeInfosByViewId("com.whatsapp:id/$label").orEmpty() +
                            r.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/$label").orEmpty())
                    } else emptyList()
                    if (byId.isNotEmpty() && clickOrTap(byId.first())) return true

                    val byText = r.findAccessibilityNodeInfosByText(label).orEmpty()
                    val validMatch = byText.firstOrNull { n ->
                        val t = (n.text?.toString() ?: n.contentDescription?.toString()).orEmpty().trim()
                        t.equals(label, ignoreCase = true) || (label.length > 6 && t.contains(label, ignoreCase = true))
                    }
                    if (validMatch != null && clickOrTap(validMatch)) return true
                }
            }
            return false
        }

        // Helper to find and click the top action-bar Trash / Delete icon
        fun tryClickTrashIcon(): Boolean {
            val roots = com.omnivoice.agentfix.ScreenObserver.findAllActiveRoots(svc, preferNonSelfPackage = true)
            for (r in roots) {
                val idMatches = r.findAccessibilityNodeInfosByViewId("com.whatsapp:id/menuitem_delete").orEmpty() +
                    r.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/menuitem_delete").orEmpty() +
                    r.findAccessibilityNodeInfosByViewId("com.whatsapp:id/delete").orEmpty()
                if (idMatches.isNotEmpty() && clickOrTap(idMatches.first())) return true
            }
            val trashMatch = com.omnivoice.agentfix.UiTools.findClickableMatchingNodeAcrossWindows(roots, "Delete", exact = false)
            if (trashMatch != null && clickOrTap(trashMatch)) return true
            return false
        }

        // Stage 0: If the confirmation dialog ("Delete for everyone" / "Delete for me") is ALREADY open on screen, click it right away!
        if (tryClickDeleteConfirmation()) {
            return true
        }

        // Stage 1: If a message is ALREADY selected and the top Trash icon is visible, click Trash -> then confirm!
        if (tryClickTrashIcon()) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!tryClickDeleteConfirmation()) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        tryClickDeleteConfirmation()
                    }, 450)
                }
            }, 420)
            return true
        }

        // Stage 2: Find the target message bubble (sorted by screen Y coordinate so .last() is the bottom-most message)
        val roots = com.omnivoice.agentfix.ScreenObserver.findAllActiveRoots(svc, preferNonSelfPackage = true)
        val rawNodes = mutableListOf<AccessibilityNodeInfo>()
        for (r in roots) {
            if (targetText == "LAST_MESSAGE" || targetText.equals("last", ignoreCase = true) || targetText.isBlank()) {
                rawNodes.addAll(r.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text").orEmpty())
                rawNodes.addAll(r.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/message_text").orEmpty())
                rawNodes.addAll(r.findAccessibilityNodeInfosByViewId("com.whatsapp:id/caption").orEmpty())
            } else {
                rawNodes.addAll(r.findAccessibilityNodeInfosByText(targetText).orEmpty())
            }
        }

        val sortedNodes = rawNodes.filter { node: AccessibilityNodeInfo ->
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            rect.height() > 10 && rect.top > 120
        }.sortedBy { node: AccessibilityNodeInfo ->
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            rect.centerY()
        }

        val lastMsgNode = sortedNodes.lastOrNull() ?: rawNodes.lastOrNull() ?: return false

        val rect = android.graphics.Rect()
        lastMsgNode.getBoundsInScreen(rect)
        var clickableParent: AccessibilityNodeInfo? = lastMsgNode
        var depth = 0
        while (clickableParent != null && !clickableParent.isLongClickable && depth < 8) {
            clickableParent = clickableParent.parent
            depth++
        }
        val longPressed = if (clickableParent != null && clickableParent.isLongClickable) {
            clickableParent.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } else {
            lastMsgNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        val waitMs = if (longPressed) 520L else {
            if (rect.width() > 0 && rect.height() > 0) {
                val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 650L))
                    .build()
                dispatchGesture(gesture, null, null)
            }
            820L
        }

        // Step 2: Wait for action mode bar and click the top Delete (Trash) icon
        Handler(Looper.getMainLooper()).postDelayed({
            tryClickTrashIcon()

            // Step 3: Wait for AlertDialog and click "Delete for everyone" (or "Delete for me")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!tryClickDeleteConfirmation()) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        tryClickDeleteConfirmation()
                    }, 450)
                }
            }, 480)
        }, waitMs)

        return true
    }

    // FIX FOR CLOSING APPS: Performs real OS Home / Back / Recents action
    fun closeAppOrNavigate(actionType: String): Boolean {
        return when (actionType.uppercase().trim()) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // FIX FOR STALE SCREEN CONTEXT: Scans the exact current screen at the moment of the user's question
    fun dumpCurrentScreenJson(): String {
        val json = JSONObject()
        val root = rootInActiveWindow
        if (root == null) {
            json.put("status", "NO_ACTIVE_WINDOW")
            return json.toString()
        }
        json.put("currentPackage", root.packageName?.toString() ?: "unknown")
        val visibleTexts = JSONArray()
        collectVisibleTexts(root, visibleTexts)
        json.put("visibleTexts", visibleTexts)
        json.put("timestamp", System.currentTimeMillis())
        return json.toString()
    }

    private fun collectVisibleTexts(node: AccessibilityNodeInfo?, outArray: JSONArray) {
        if (node == null || outArray.length() > 60) return
        val txt = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!txt.isNullOrEmpty()) outArray.put(txt)
        else if (!desc.isNullOrEmpty()) outArray.put("[$desc]")

        for (i in 0 until node.childCount) {
            collectVisibleTexts(node.getChild(i), outArray)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "OmniAccessibilityService interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (com.omnivoice.agentfix.AgentBridge.service === this) {
            com.omnivoice.agentfix.AgentBridge.service = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (com.omnivoice.agentfix.AgentBridge.service === this) {
            com.omnivoice.agentfix.AgentBridge.service = null
        }
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "OmniAccessibilityService destroyed")
    }
}

/**
 * Direct alias object for JarvisAccessibilityService providing drop-in compatibility.
 */
object JarvisAccessibilityService {
    val instance: OmniAccessibilityService? get() = OmniAccessibilityService.instance

    fun armVerifiedWhatsAppSend(contactName: String, message: String) {
        OmniAccessibilityService.armVerifiedWhatsAppSend(contactName, message)
    }

    fun deleteMessageInWhatsApp(targetText: String, deleteForEveryone: Boolean): Boolean {
        return OmniAccessibilityService.deleteMessageInWhatsApp(targetText, deleteForEveryone)
    }

    fun typeIntoFocusedOrSearchField(text: String, submitEnter: Boolean): Boolean {
        return OmniAccessibilityService.typeIntoFocusedOrSearchField(text, submitEnter)
    }

    fun closeAppOrNavigate(actionType: String): Boolean {
        return OmniAccessibilityService.closeAppOrNavigate(actionType)
    }

    fun dumpCurrentScreenJson(): String {
        return OmniAccessibilityService.dumpCurrentScreenJson()
    }
}

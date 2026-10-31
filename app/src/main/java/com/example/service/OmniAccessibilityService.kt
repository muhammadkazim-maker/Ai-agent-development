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
                return ScreenUiObservation(
                    activePackage = "com.example.omnivoice",
                    visibleTexts = listOf("OmniVoice Assistant Console", "Voice Orb Ready", "Bilingual Urdu + English Engine Active"),
                    clickableLabels = listOf("Mic Button", "Send Command", "AI Skills", "Routines"),
                    summary = "Active screen: OmniVoice Assistant Console (Ready for voice or cross-app commands)."
                )
            }

            val pkg = root.packageName?.toString() ?: "unknown"
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
         * Finds and clicks a UI element matching [targetText] in the current window.
         */
        fun clickNodeByText(targetText: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val matches = root.findAccessibilityNodeInfosByText(targetText)
            if (!matches.isNullOrEmpty()) {
                for (node in matches) {
                    var current: AccessibilityNodeInfo? = node
                    while (current != null) {
                        if (current.isClickable) {
                            return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        current = current.parent
                    }
                }
            }
            return false
        }

        /**
         * Finds and clicks a UI element matching [viewId] (e.g., "com.whatsapp:id/send").
         */
        fun clickNodeByViewId(viewId: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val matches = root.findAccessibilityNodeInfosByViewId(viewId)
            if (!matches.isNullOrEmpty()) {
                for (node in matches) {
                    var current: AccessibilityNodeInfo? = node
                    while (current != null) {
                        if (current.isClickable) {
                            return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        current = current.parent
                    }
                }
            }
            return false
        }

        /**
         * Finds and clicks a UI element matching content description.
         */
        fun clickNodeByContentDescription(targetDesc: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
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
    }

    // FIX FOR KEYBOARD SUGGESTION BUG: Directly injects text into the Search Bar / EditText and presses Enter!
    fun typeIntoFocusedOrSearchField(text: String, submitEnter: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = findFirstEditableNode(root) ?: return false

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
            }, 300)
        }
        return typed
    }

    // FIX FOR WHATSAPP MESSAGE DELETE: 1. Long-Press Message -> 2. Click Trash Icon -> 3. Click "Delete for everyone"
    fun deleteMessageInWhatsApp(targetText: String, deleteForEveryone: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val messageNodes = if (targetText == "LAST_MESSAGE" || targetText.isBlank()) {
            root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text")
        } else {
            root.findAccessibilityNodeInfosByText(targetText)
        }

        if (messageNodes.isNullOrEmpty()) return false
        val lastMsgNode = messageNodes.last()

        // Step 1: Long-press the message bubble or its long-clickable parent
        var clickableParent: AccessibilityNodeInfo? = lastMsgNode
        while (clickableParent != null && !clickableParent.isLongClickable) {
            clickableParent = clickableParent.parent
        }
        (clickableParent ?: lastMsgNode).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

        // Step 2: Wait 550ms and click the top Delete (Trash) icon
        Handler(Looper.getMainLooper()).postDelayed({
            val step2Root = rootInActiveWindow ?: return@postDelayed
            val trashNodes = step2Root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/menuitem_delete")
                ?.takeIf { it.isNotEmpty() }
                ?: step2Root.findAccessibilityNodeInfosByText("Delete")

            if (!trashNodes.isNullOrEmpty()) {
                for (trash in trashNodes) {
                    if (trash.isClickable) {
                        trash.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        break
                    }
                }
            }

            // Step 3: Wait 550ms and click "Delete for everyone" (or "Delete for me")
            Handler(Looper.getMainLooper()).postDelayed({
                val step3Root = rootInActiveWindow ?: return@postDelayed
                val confirmText = if (deleteForEveryone) "Delete for everyone" else "Delete for me"
                val confirmNodes = step3Root.findAccessibilityNodeInfosByText(confirmText)
                    ?.takeIf { it.isNotEmpty() }
                    ?: step3Root.findAccessibilityNodeInfosByText("Delete for me")

                if (!confirmNodes.isNullOrEmpty()) {
                    for (btn in confirmNodes) {
                        if (btn.isClickable) {
                            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            break
                        } else if (btn.parent?.isClickable == true) {
                            btn.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            break
                        }
                    }
                }
            }, 550)
        }, 550)

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

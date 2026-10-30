package com.omnivoice.agentfix

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
data class UiNodeSummary(
    val index: Int,
    val viewId: String,
    val className: String,
    val packageName: String,
    val text: String,
    val contentDescription: String,
    val hintText: String,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isFocused: Boolean,
    val isEnabled: Boolean,
    val bounds: Rect
) {
    fun displayLabel(): String {
        return when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            hintText.isNotBlank() -> hintText
            viewId.isNotBlank() -> viewId.substringAfterLast('/')
            else -> ""
        }
    }
    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        if (viewId.isNotBlank()) put("viewId", viewId)
        if (className.isNotBlank()) put("className", className.substringAfterLast('.'))
        if (text.isNotBlank()) put("text", text)
        if (contentDescription.isNotBlank()) put("contentDescription", contentDescription)
        if (hintText.isNotBlank()) put("hintText", hintText)
        put("clickable", isClickable)
        put("longClickable", isLongClickable)
        put("editable", isEditable)
        put("focused", isFocused)
        put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
    }
}
data class ScreenSnapshot(
    val timestampMs: Long,
    val elapsedRealtimeMs: Long,
    val packageName: String,
    val appLabel: String,
    val titleGuess: String,
    val nodes: List<UiNodeSummary>,
    val editableFields: List<UiNodeSummary>,
    val clickableLabels: List<String>,
    val visibleTexts: List<String>
) {
    fun ageMs(): Long = (SystemClock.elapsedRealtime() - elapsedRealtimeMs).coerceAtLeast(0L)
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appLabel", appLabel)
        put("titleGuess", titleGuess)
        put("ageMs", ageMs())
        put("timestampMs", timestampMs)
        put("visibleTexts", JSONArray(visibleTexts))
        put("clickableLabels", JSONArray(clickableLabels))
        val editArr = JSONArray()
        for (f in editableFields) editArr.put(f.toJson())
        put("editableFields", editArr)
        val nodeArr = JSONArray()
        for (n in nodes.take(60)) nodeArr.put(n.toJson())
        put("nodes", nodeArr)
    }
}
object ScreenObserver {
    private val windowChangeCounter = AtomicLong(0L)
    @Volatile
    var lastEventElapsedMs: Long = 0L
        private set
    @Volatile
    var lastObservedAppPackage: String = ""
        private set
    val windowChangeSeq: Long
        get() = windowChangeCounter.get()
    private val knownImePrefixes = listOf(
        "com.google.android.inputmethod",
        "com.android.inputmethod",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey",
        "com.microsoft.emmx.keyboard",
        "com.baidu.input",
        "com.iflytek.inputmethod",
        "com.miui.securityinputmethod",
        "com.sec.android.inputmethod",
        "com.sonyericsson.textinput",
        "com.lge.ime",
        "com.syntellia.fleksy",
        "org.pocketworkstation.pckeyboard",
        "dev.patrickgold.florisboard"
    )
    fun isImePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase(Locale.ROOT)
        if (lower.contains("inputmethod") || lower.contains("keyboard") || lower.contains("honeyboard") || lower.contains("swiftkey")) {
            return true
        }
        return knownImePrefixes.any { lower.startsWith(it) }
    }
    fun onEvent(event: AccessibilityEvent?, serviceOwnPackage: CharSequence? = null) {
        if (event == null) return
        lastEventElapsedMs = SystemClock.elapsedRealtime()
        val evPkg = event.packageName?.toString().orEmpty()
        val evType = event.eventType
        if (evType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            evType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            windowChangeCounter.incrementAndGet()
        }
        if (evPkg.isNotBlank() && !isImePackage(evPkg) && evPkg != "com.android.systemui") {
            if (evPkg != lastObservedAppPackage) {
                AgentLog.d("ScreenObserver", "Foreground app package -> $evPkg")
                lastObservedAppPackage = evPkg
            }
        }
    }
    fun findApplicationRoot(
        service: AccessibilityService? = AgentBridge.service,
        preferNonSelfPackage: Boolean = true
    ): AccessibilityNodeInfo? {
        val svc = service ?: return null
        val selfPkg = AgentBridge.appContext?.packageName.orEmpty()
        try {
            val windows = svc.windows
            if (!windows.isNullOrEmpty()) {
                val appWindows = windows.filter { w ->
                    w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        w.root != null &&
                        !isImePackage(w.root?.packageName?.toString())
                }
                if (preferNonSelfPackage) {
                    val externalActive = appWindows.firstOrNull { w ->
                        w.isActive && w.root?.packageName?.toString() != selfPkg
                    } ?: appWindows.firstOrNull { w ->
                        w.root?.packageName?.toString() != selfPkg
                    }
                    if (externalActive?.root != null) {
                        val root = externalActive.root!!
                        try { root.refresh() } catch (_: Exception) {}
                        return root
                    }
                }
                val focusedOrActive = appWindows.firstOrNull { it.isActive || it.isFocused }
                    ?: appWindows.firstOrNull()
                if (focusedOrActive?.root != null) {
                    val root = focusedOrActive.root!!
                    try { root.refresh() } catch (_: Exception) {}
                    return root
                }
            }
        } catch (e: Exception) {
            AgentLog.w("ScreenObserver", "windows lookup fallback: ${e.message}")
        }
        val fallbackRoot = try { svc.rootInActiveWindow } catch (_: Exception) { null }
        if (fallbackRoot != null) {
            try { fallbackRoot.refresh() } catch (_: Exception) {}
            if (!isImePackage(fallbackRoot.packageName?.toString())) {
                return fallbackRoot
            }
        }
        return null
    }
    fun captureFreshSnapshot(): ScreenSnapshot? {
        val svc = AgentBridge.service ?: return null
        val root = findApplicationRoot(svc, preferNonSelfPackage = false) ?: return null
        try { root.refresh() } catch (_: Exception) {}
        val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?: lastObservedAppPackage.ifBlank { "unknown" }
        val appLabel = resolveAppLabel(pkg)
        val nodes = mutableListOf<UiNodeSummary>()
        traverseTree(root, nodes, depth = 0, maxNodes = 160)
        val visibleTexts = nodes.mapNotNull { n ->
            n.text.takeIf { it.isNotBlank() } ?: n.contentDescription.takeIf { it.isNotBlank() }
        }.distinct().take(40)
        val clickableLabels = nodes.filter { it.isClickable || it.isLongClickable }
            .map { it.displayLabel() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(25)
        val editableFields = nodes.filter { it.isEditable && !isImePackage(it.packageName) }
        val titleGuess = guessScreenTitle(nodes, visibleTexts, appLabel)
        return ScreenSnapshot(
            timestampMs = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            packageName = pkg,
            appLabel = appLabel,
            titleGuess = titleGuess,
            nodes = nodes,
            editableFields = editableFields,
            clickableLabels = clickableLabels,
            visibleTexts = visibleTexts
        )
    }
    private fun traverseTree(
        node: AccessibilityNodeInfo?,
        out: MutableList<UiNodeSummary>,
        depth: Int,
        maxNodes: Int
    ) {
        if (node == null || depth > 18 || out.size >= maxNodes) return
        if (!node.isVisibleToUser) return
        if (node.isPassword) return
        val nodePkg = node.packageName?.toString().orEmpty()
        if (isImePackage(nodePkg)) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim().orEmpty()
        } else ""
        val viewId = node.viewIdResourceName?.trim().orEmpty()
        val cls = node.className?.toString()?.trim().orEmpty()
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val isMeaningful = text.isNotEmpty() ||
            desc.isNotEmpty() ||
            hint.isNotEmpty() ||
            node.isEditable ||
            node.isClickable ||
            node.isLongClickable ||
            viewId.isNotEmpty()
        if (isMeaningful && rect.width() > 0 && rect.height() > 0) {
            out.add(
                UiNodeSummary(
                    index = out.size,
                    viewId = viewId,
                    className = cls,
                    packageName = nodePkg,
                    text = text,
                    contentDescription = desc,
                    hintText = hint,
                    isClickable = node.isClickable,
                    isLongClickable = node.isLongClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isFocused = node.isFocused || node.isAccessibilityFocused,
                    isEnabled = node.isEnabled,
                    bounds = rect
                )
            )
        }
        val count = node.childCount
        for (i in 0 until count) {
            if (out.size >= maxNodes) break
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            traverseTree(child, out, depth + 1, maxNodes)
        }
    }
    private fun guessScreenTitle(
        nodes: List<UiNodeSummary>,
        visibleTexts: List<String>,
        appLabel: String
    ): String {
        val headerNode = nodes.firstOrNull { n ->
            n.text.isNotBlank() && (
                n.viewId.contains("conversation_contact_name", ignoreCase = true) ||
                    n.viewId.contains("toolbar", ignoreCase = true) ||
                    n.viewId.contains("action_bar_title", ignoreCase = true) ||
                    n.viewId.contains("title", ignoreCase = true)
                )
        }
        if (headerNode != null) return headerNode.text
        return visibleTexts.firstOrNull { it.length in 2..45 } ?: appLabel
    }
    private val appLabelCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun resolveAppLabel(packageName: String): String {
        appLabelCache[packageName]?.let { return it }
        val ctx = AgentBridge.appContext ?: return packageName
        return try {
            val pm = ctx.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString().also { appLabelCache[packageName] = it }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: Exception) {
            packageName
        }
    }
    suspend fun waitForPackage(expectedPackages: Set<String>, timeoutMs: Long = 4500L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val snap = captureFreshSnapshot()
            if (snap != null && expectedPackages.any { it.equals(snap.packageName, ignoreCase = true) }) {
                return true
            }
            delay(45L)
        }
        return false
    }
    fun describeCurrentScreen(): ToolResult {
        if (AgentBridge.service == null) {
            return ToolResult.needsPermission(
                tool = "describe_screen",
                permissionOrService = "AccessibilityService",
                message = "Accessibility service is not connected. Enable OmniVoice in Android Accessibility Settings to read the live screen."
            )
        }
        val snap = captureFreshSnapshot() ?: return ToolResult.failure(
            tool = "describe_screen",
            message = "Could not capture an active application window on screen right now.",
            errorCode = "NO_ACTIVE_WINDOW"
        )
        val summary = buildString {
            append("Current app: ${snap.appLabel} (${snap.packageName}). ")
            append("Header/Title: ${snap.titleGuess}. ")
            if (snap.visibleTexts.isNotEmpty()) {
                append("Visible text: ${snap.visibleTexts.take(15).joinToString(" | ")}. ")
            }
            if (snap.clickableLabels.isNotEmpty()) {
                append("Buttons: ${snap.clickableLabels.take(10).joinToString(", ")}.")
            }
        }
        AgentLog.i("ScreenObserver", "describe_screen -> ${snap.packageName} (${snap.nodes.size} nodes)")
        return ToolResult.success(
            tool = "describe_screen",
            message = summary,
            verified = true,
            verificationNote = "Fresh live AccessibilityNodeInfo tree captured (${snap.nodes.size} nodes, age=${snap.ageMs()}ms).",
            data = snap.toJson()
        )
    }
}

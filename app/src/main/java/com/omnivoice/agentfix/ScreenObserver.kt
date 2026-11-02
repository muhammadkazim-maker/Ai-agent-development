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
    val bounds: Rect,
    val inferredIconMeaning: String = "",
    val screenZone: String = ""
) {
    fun displayLabel(): String {
        return when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            inferredIconMeaning.isNotBlank() -> inferredIconMeaning
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
        if (inferredIconMeaning.isNotBlank()) put("iconMeaning", inferredIconMeaning)
        if (screenZone.isNotBlank()) put("zone", screenZone)
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
    val visibleTexts: List<String>,
    val visibleIcons: List<String> = emptyList(),
    val windowId: Int = -1,
    val taskId: Int = -1,
    val activityComponent: String = "",
    val visibleApplicationPackages: Set<String> = emptySet(),
    val editableFieldDrafts: Map<String, String> = emptyMap()
) {
    fun ageMs(): Long = (SystemClock.elapsedRealtime() - elapsedRealtimeMs).coerceAtLeast(0L)
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appLabel", appLabel)
        put("titleGuess", titleGuess)
        put("ageMs", ageMs())
        put("timestampMs", timestampMs)
        put("elapsedRealtimeMs", elapsedRealtimeMs)
        put("windowId", windowId)
        put("taskId", taskId)
        if (activityComponent.isNotBlank()) put("activityComponent", activityComponent)
        put("visibleApplicationPackages", JSONArray(visibleApplicationPackages.toList()))
        put("visibleTexts", JSONArray(visibleTexts))
        put("clickableLabels", JSONArray(clickableLabels))
        put("visibleIcons", JSONArray(visibleIcons))
        val draftsObj = JSONObject()
        for ((k, v) in editableFieldDrafts) draftsObj.put(k, v)
        put("editableFieldDrafts", draftsObj)
        val editArr = JSONArray()
        for (f in editableFields) editArr.put(f.toJson())
        put("editableFields", editArr)
        val nodeArr = JSONArray()
        for (n in nodes.take(90)) nodeArr.put(n.toJson())
        put("nodes", nodeArr)
    }
}

data class FreshPackageObservation(
    val matched: Boolean,
    val observedPackage: String?,
    val snapshot: ScreenSnapshot?,
    val bothAppsVisibleTogether: Boolean = false,
    val timedOut: Boolean = false,
    val observerAvailable: Boolean = true
)

object ScreenObserver {
    private val windowChangeCounter = AtomicLong(0L)
    @Volatile
    var lastEventElapsedMs: Long = 0L
        private set
    @Volatile
    var lastObservedAppPackage: String = ""
        private set
    @Volatile
    var lastObservedUserAppSnapshot: ScreenSnapshot? = null
        internal set
    private val lastObservedEditableDraftsByPackage =
        java.util.concurrent.ConcurrentHashMap<String, java.util.LinkedHashMap<String, String>>()
    @Volatile
    var testSnapshotSupplier: ((preferNonSelfPackage: Boolean) -> ScreenSnapshot?)? = null
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

    /**
     * Returns true if [pkg] is a transient assistant, keyboard (IME), system UI, permission dialog,
     * or launcher window that must never overwrite or masquerade as the user's originating app task.
     */
    fun isTransientNonUserAppPackage(
        pkg: String?,
        selfPkg: String? = AgentBridge.appContext?.packageName
    ): Boolean {
        if (pkg.isNullOrBlank()) return true
        val trimmed = pkg.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower == "unknown") return true
        if (!selfPkg.isNullOrBlank() && lower == selfPkg.trim().lowercase(Locale.ROOT)) return true
        if (lower == "com.example.omnivoice" || lower.startsWith("com.aistudio.")) return true
        if (isImePackage(trimmed)) return true
        if (lower == "com.android.systemui" ||
            lower.contains("permissioncontroller") ||
            lower == "com.android.packageinstaller" ||
            lower == "com.google.android.packageinstaller"
        ) {
            return true
        }
        if (UiTools.isLauncherPackage(trimmed)) return true
        return false
    }

    fun resetCachedOriginStateForTest() {
        lastObservedAppPackage = ""
        lastObservedUserAppSnapshot = null
        lastObservedEditableDraftsByPackage.clear()
    }

    internal fun recordUserAppSnapshotIfEligible(
        snap: ScreenSnapshot?,
        selfPkg: String? = AgentBridge.appContext?.packageName
    ): ScreenSnapshot? {
        if (snap == null) return null
        val pkg = snap.packageName.trim()
        if (isTransientNonUserAppPackage(pkg, selfPkg)) {
            return snap
        }
        if (snap.editableFieldDrafts.isNotEmpty()) {
            lastObservedEditableDraftsByPackage[pkg] = LinkedHashMap(snap.editableFieldDrafts)
        } else {
            lastObservedEditableDraftsByPackage.remove(pkg)
        }
        lastObservedUserAppSnapshot = snap
        return snap
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
        val selfPkg = serviceOwnPackage?.toString()?.takeIf { it.isNotBlank() }
            ?: AgentBridge.appContext?.packageName
        if (evPkg.isNotBlank() && !isImePackage(evPkg) && evPkg != "com.android.systemui") {
            if (evPkg != lastObservedAppPackage) {
                AgentLog.d("ScreenObserver", "Foreground app package -> $evPkg")
                lastObservedAppPackage = evPkg
            }
        }
        if (evPkg.isNotBlank() && !isTransientNonUserAppPackage(evPkg, selfPkg)) {
            if (evType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                evType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                evType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            ) {
                try {
                    val src = event.source
                    val isEditableSrc = src?.isEditable == true ||
                        event.className?.toString()?.contains("EditText", ignoreCase = true) == true
                    if (isEditableSrc) {
                        val rawText = src?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                            ?: event.text?.joinToString("") { it.toString() }?.trim()?.takeIf { it.isNotEmpty() }
                        if (!rawText.isNullOrBlank()) {
                            val fieldKey = src?.viewIdResourceName?.trim()?.takeIf { it.isNotEmpty() }
                                ?: "editable_draft"
                            val map = lastObservedEditableDraftsByPackage.getOrPut(evPkg) { LinkedHashMap() }
                            map[fieldKey] = rawText
                            val prevSnap = lastObservedUserAppSnapshot
                            if (prevSnap != null && prevSnap.packageName.equals(evPkg, ignoreCase = true)) {
                                val updatedDrafts = LinkedHashMap(prevSnap.editableFieldDrafts)
                                updatedDrafts[fieldKey] = rawText
                                lastObservedUserAppSnapshot = prevSnap.copy(
                                    editableFieldDrafts = updatedDrafts,
                                    elapsedRealtimeMs = SystemClock.elapsedRealtime()
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            if (evType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && AgentBridge.service != null) {
                try {
                    captureFreshSnapshot(preferNonSelfPackage = true)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Returns all active non-IME window roots ordered by priority:
     * 1. Active/focused dialog or top-layer application windows
     * 2. `rootInActiveWindow` (often points directly to an open AlertDialog/BottomSheet)
     * 3. Underlying application windows (e.g. main chat window underneath a dialog)
     */
    fun findAllActiveRoots(
        service: AccessibilityService? = AgentBridge.service,
        preferNonSelfPackage: Boolean = false
    ): List<AccessibilityNodeInfo> {
        val svc = service ?: return emptyList()
        val selfPkg = AgentBridge.appContext?.packageName.orEmpty()
        val results = mutableListOf<AccessibilityNodeInfo>()

        val activeRoot = try { svc.rootInActiveWindow } catch (_: Exception) { null }
        if (activeRoot != null) {
            try { activeRoot.refresh() } catch (_: Exception) {}
            val pkg = activeRoot.packageName?.toString().orEmpty()
            if (!isImePackage(pkg) && (!preferNonSelfPackage || (pkg != selfPkg && pkg != "com.example.omnivoice"))) {
                results.add(activeRoot)
            }
        }

        try {
            val windows = svc.windows
            if (!windows.isNullOrEmpty()) {
                val sortedWindows = windows.filter { w ->
                    w.root != null &&
                        !isImePackage(w.root?.packageName?.toString()) &&
                        (w.type == AccessibilityWindowInfo.TYPE_APPLICATION || w.type == AccessibilityWindowInfo.TYPE_SYSTEM)
                }.sortedByDescending { w ->
                    var priority = 0
                    val wPkg = w.root?.packageName?.toString().orEmpty()
                    if (w.isFocused) priority += 100
                    if (w.isActive) priority += 50
                    if (preferNonSelfPackage && wPkg != selfPkg && wPkg != "com.example.omnivoice") priority += 80
                    if (preferNonSelfPackage && !isTransientNonUserAppPackage(wPkg, selfPkg)) priority += 60
                    priority
                }
                for (w in sortedWindows) {
                    val r = w.root ?: continue
                    try { r.refresh() } catch (_: Exception) {}
                    if (results.none { it == r }) {
                        results.add(r)
                    }
                }
            }
        } catch (e: Exception) {
            AgentLog.w("ScreenObserver", "findAllActiveRoots windows lookup: ${e.message}")
        }

        if (results.isEmpty() && activeRoot != null && !isImePackage(activeRoot.packageName?.toString())) {
            results.add(activeRoot)
        }
        return results
    }

    fun findApplicationRoot(
        service: AccessibilityService? = AgentBridge.service,
        preferNonSelfPackage: Boolean = true
    ): AccessibilityNodeInfo? {
        val svc = service ?: return null
        val selfPkg = AgentBridge.appContext?.packageName.orEmpty()

        // First check rootInActiveWindow if it is a focused dialog or active external app window
        val activeRoot = try { svc.rootInActiveWindow } catch (_: Exception) { null }
        if (activeRoot != null) {
            try { activeRoot.refresh() } catch (_: Exception) {}
            val activePkg = activeRoot.packageName?.toString().orEmpty()
            if (activePkg.isNotBlank() && !isImePackage(activePkg) && activePkg != "com.android.systemui") {
                if (!preferNonSelfPackage || (activePkg != selfPkg && activePkg != "com.example.omnivoice")) {
                    // Check if windows list has an even more specific focused dialog window
                    try {
                        val windows = svc.windows
                        val focusedDialog = windows?.firstOrNull { w ->
                            w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                                w.isFocused &&
                                w.root != null &&
                                !isImePackage(w.root?.packageName?.toString()) &&
                                (!preferNonSelfPackage || (w.root?.packageName?.toString() != selfPkg && w.root?.packageName?.toString() != "com.example.omnivoice"))
                        }?.root
                        if (focusedDialog != null) {
                            try { focusedDialog.refresh() } catch (_: Exception) {}
                            return focusedDialog
                        }
                    } catch (_: Exception) {}
                    return activeRoot
                }
            }
        }

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
                        val wPkg = w.root?.packageName?.toString().orEmpty()
                        (w.isFocused || w.isActive) && !isTransientNonUserAppPackage(wPkg, selfPkg)
                    } ?: appWindows.firstOrNull { w ->
                        val wPkg = w.root?.packageName?.toString().orEmpty()
                        !isTransientNonUserAppPackage(wPkg, selfPkg)
                    } ?: appWindows.firstOrNull { w ->
                        val wPkg = w.root?.packageName?.toString().orEmpty()
                        (w.isFocused || w.isActive) && wPkg != selfPkg && wPkg != "com.example.omnivoice"
                    } ?: appWindows.firstOrNull { w ->
                        val wPkg = w.root?.packageName?.toString().orEmpty()
                        wPkg != selfPkg && wPkg != "com.example.omnivoice"
                    }
                    if (externalActive?.root != null) {
                        val root = externalActive.root!!
                        try { root.refresh() } catch (_: Exception) {}
                        return root
                    }
                }
                val focusedOrActive = appWindows.firstOrNull { it.isFocused }
                    ?: appWindows.firstOrNull { it.isActive }
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
        if (activeRoot != null && !isImePackage(activeRoot.packageName?.toString())) {
            return activeRoot
        }
        return null
    }

    /**
     * Infers human-readable icon meaning + bilingual aliases from viewId, contentDescription, className, and screen zone.
     * Ensures the assistant has full awareness of every icon on screen even when `text` is blank!
     */
    fun inferSemanticIconMeaning(
        viewId: String,
        contentDescription: String,
        text: String,
        className: String,
        bounds: Rect
    ): String {
        val idTail = viewId.substringAfterLast('/').lowercase(Locale.ROOT)
        val descLower = contentDescription.lowercase(Locale.ROOT)
        val txtLower = text.lowercase(Locale.ROOT)
        val combined = "$idTail $descLower $txtLower"

        return when {
            idTail.contains("menuitem_delete") || idTail == "delete" || idTail.contains("action_delete") ||
                idTail.contains("trash") || descLower == "delete" || descLower.contains("delete") ||
                descLower.contains("trash") || descLower.contains("حذف") ->
                "Delete / Trash Icon (delete, mitao, trash, menuitem_delete)"

            idTail.contains("delete_for_everyone") || txtLower.contains("delete for everyone") ||
                descLower.contains("delete for everyone") || txtLower.contains("سب کے لیے حذف") ->
                "Delete for Everyone Option (delete for everyone, sab ke liye delete)"

            idTail.contains("delete_for_me") || txtLower.contains("delete for me") ||
                descLower.contains("delete for me") || txtLower.contains("میرے لیے حذف") ->
                "Delete for Me Option (delete for me, mere liye delete)"

            idTail.contains("audio_call") || idTail == "call_btn" || idTail == "menuitem_call" ||
                idTail.contains("voice_call") || descLower.contains("voice call") ||
                descLower.contains("audio call") || descLower == "call" || descLower.contains("وائس کال") ->
                "Voice Call Icon (voice call, audio call, whatsapp call, call button)"

            idTail.contains("video_call") || idTail == "menuitem_video_call" ||
                descLower.contains("video call") || descLower.contains("ویڈیو کال") ->
                "Video Call Icon (video call, whatsapp video call)"

            idTail == "send" || idTail.contains("send_btn") || idTail.contains("send_button") ||
                descLower == "send" || descLower.contains("send message") || descLower.contains("بھیجیں") ->
                "Send Icon (send, bhejo, submit)"

            idTail.contains("voice_note") || idTail.contains("mic") || idTail.contains("voice_recorder") ||
                descLower.contains("voice message") || descLower.contains("voice note") || descLower.contains("microphone") ->
                "Microphone / Voice Note Icon (mic, voice note, audio message)"

            idTail.contains("attach") || idTail.contains("paperclip") ||
                descLower.contains("attach") || descLower.contains("منسلک") ->
                "Attach / Paperclip Icon (attach, file, media, document)"

            idTail.contains("camera") || descLower.contains("camera") || descLower.contains("کیمرہ") ->
                "Camera Icon (camera, photo)"

            idTail.contains("search") || descLower.contains("search") || descLower.contains("تلاش") ->
                "Search Icon (search, find, talaash)"

            idTail.contains("overflow") || idTail.contains("more") || idTail == "menuitem_more" ||
                descLower.contains("more options") || descLower.contains("menu") || descLower.contains("مزید اختیارات") ->
                "More Options / Three Dots Menu Icon (more options, menu, three dots)"

            idTail.contains("back") || idTail.contains("navigate_up") || idTail == "up" ||
                descLower.contains("navigate up") || descLower == "back" || descLower.contains("واپس") ->
                "Back / Navigate Up Icon (back, peeche, close)"

            idTail.contains("reply") || descLower.contains("reply") || descLower.contains("جواب") ->
                "Reply Icon (reply, jawab)"

            idTail.contains("forward") || descLower.contains("forward") || descLower.contains("فارورڈ") ->
                "Forward Icon (forward, aagay bhejo)"

            idTail.contains("copy") || descLower.contains("copy") ->
                "Copy Icon (copy)"

            idTail.contains("star") || idTail.contains("favorite") || descLower.contains("star") ->
                "Star / Favorite Icon (star, favorite)"

            idTail.contains("emoji") || idTail.contains("smiley") || descLower.contains("emoji") ->
                "Emoji / Sticker Icon (emoji, sticker)"

            idTail.contains("share") || descLower.contains("share") ->
                "Share Icon (share)"

            idTail.contains("like") || idTail.contains("heart") || descLower.contains("like") ->
                "Like / Heart Icon (like, heart)"

            idTail.contains("comment") || descLower.contains("comment") ->
                "Comment Icon (comment)"

            idTail.contains("speaker") || idTail.contains("audio_route") || descLower.contains("speaker") ->
                "Speakerphone Icon (speaker)"

            idTail.contains("mute") || descLower.contains("mute") ->
                "Mute Icon (mute)"

            idTail.contains("end_call") || idTail.contains("hangup") || idTail.contains("decline") ||
                descLower.contains("end call") || descLower.contains("hang up") || descLower.contains("decline") ->
                "End Call Icon (end call, hang up, call kaato)"

            (className.contains("ImageButton", ignoreCase = true) ||
                className.contains("ImageView", ignoreCase = true) ||
                className.contains("ActionMenuItemView", ignoreCase = true)) &&
                (contentDescription.isNotBlank() || idTail.isNotBlank()) ->
                "Icon: ${contentDescription.ifBlank { idTail }}"

            else -> ""
        }
    }

    fun inferScreenZone(bounds: Rect): String {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val vert = when {
            cy < 280 -> "top-bar"
            cy > 1850 -> "bottom-bar"
            else -> "middle"
        }
        val horiz = when {
            cx < 360 -> "left"
            cx > 720 -> "right"
            else -> "center"
        }
        return "$vert-$horiz"
    }

    fun isObserverAvailable(): Boolean {
        return testSnapshotSupplier != null || AgentBridge.service != null
    }

    fun captureFreshSnapshot(preferNonSelfPackage: Boolean = false): ScreenSnapshot? {
        testSnapshotSupplier?.let { supplier ->
            val supplied = supplier(preferNonSelfPackage)
            return recordUserAppSnapshotIfEligible(supplied)
        }
        val svc = AgentBridge.service ?: return null
        val roots = findAllActiveRoots(svc, preferNonSelfPackage = preferNonSelfPackage)
        val primaryRoot = roots.firstOrNull() ?: return null
        val pkg = primaryRoot.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?: lastObservedAppPackage.ifBlank { "" }
        if (pkg.isBlank()) return null
        val built = buildSnapshotFromRoots(svc, pkg, roots, primaryRoot)
        return recordUserAppSnapshotIfEligible(built)
    }

    private fun buildSnapshotFromRoots(
        svc: AccessibilityService,
        pkg: String,
        roots: List<AccessibilityNodeInfo>,
        primaryRoot: AccessibilityNodeInfo
    ): ScreenSnapshot {
        val appLabel = resolveAppLabel(pkg)
        val nodes = mutableListOf<UiNodeSummary>()
        for (r in roots) {
            if (nodes.size >= 180) break
            traverseTree(r, nodes, depth = 0, maxNodes = 180)
        }
        val visibleTexts = nodes.mapNotNull { n ->
            n.text.takeIf { it.isNotBlank() } ?: n.contentDescription.takeIf { it.isNotBlank() }
        }.distinct().take(45)
        val clickableLabels = nodes.filter { it.isClickable || it.isLongClickable }
            .map { it.displayLabel() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(35)
        val visibleIcons = nodes.mapNotNull { n ->
            if (n.inferredIconMeaning.isNotBlank()) {
                "${n.inferredIconMeaning} [${n.screenZone} @ (${n.bounds.centerX()},${n.bounds.centerY()})]"
            } else if ((n.className.contains("Image", ignoreCase = true) || n.className.contains("ActionMenuItem", ignoreCase = true)) && n.displayLabel().isNotBlank()) {
                "${n.displayLabel()} [${n.screenZone} @ (${n.bounds.centerX()},${n.bounds.centerY()})]"
            } else null
        }.distinct().take(35)
        val editableFields = nodes.filter { it.isEditable && !isImePackage(it.packageName) }
        val editableDrafts = linkedMapOf<String, String>()
        for (field in editableFields) {
            if (field.text.isNotBlank()) {
                val key = field.viewId.ifBlank { field.hintText.ifBlank { "field_${field.index}" } }
                editableDrafts[key] = field.text
            }
        }
        val visibleAppPackages = linkedSetOf<String>()
        visibleAppPackages.add(pkg)
        try {
            val windows = svc.windows
            if (!windows.isNullOrEmpty()) {
                for (w in windows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val wPkg = w.root?.packageName?.toString().orEmpty()
                        if (wPkg.isNotBlank() && !isImePackage(wPkg) && wPkg != "com.android.systemui") {
                            visibleAppPackages.add(wPkg)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        val primaryWindowId = try { primaryRoot.windowId } catch (_: Exception) { -1 }
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
            visibleTexts = visibleTexts,
            visibleIcons = visibleIcons,
            windowId = primaryWindowId,
            taskId = -1,
            activityComponent = "",
            visibleApplicationPackages = visibleAppPackages,
            editableFieldDrafts = editableDrafts
        )
    }

    /**
     * Captures or retrieves the originating non-transient user app snapshot before navigating Home
     * or launching a target app. Strictly ignores transient assistant ([selfPackageName]), keyboard (IME),
     * SystemUI, permission dialog, and launcher windows, falling back to any underlying user app window
     * in [AccessibilityService.getWindows] or [lastObservedUserAppSnapshot].
     */
    fun captureOriginUserAppSnapshot(
        selfPackageName: String,
        targetPackageToExclude: String? = null
    ): ScreenSnapshot? {
        val current = captureFreshSnapshot(preferNonSelfPackage = true)
            ?: captureFreshSnapshot(preferNonSelfPackage = false)
        if (current != null) {
            val pkg = current.packageName.trim()
            val excludedByTarget = !targetPackageToExclude.isNullOrBlank() &&
                pkg.equals(targetPackageToExclude.trim(), ignoreCase = true)
            if (!isTransientNonUserAppPackage(pkg, selfPackageName) && !excludedByTarget) {
                return recordUserAppSnapshotIfEligible(current, selfPackageName)
            }
        }

        // If the active window is a transient window (assistant, IME, launcher, SystemUI),
        // inspect underlying application windows in AccessibilityService.windows if available
        val svc = AgentBridge.service
        if (svc != null) {
            try {
                val windows = svc.windows
                if (!windows.isNullOrEmpty()) {
                    val userAppWin = windows.firstOrNull { w ->
                        if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@firstOrNull false
                        val r = w.root ?: return@firstOrNull false
                        val wPkg = r.packageName?.toString()?.trim().orEmpty()
                        val excludedByTarget = !targetPackageToExclude.isNullOrBlank() &&
                            wPkg.equals(targetPackageToExclude.trim(), ignoreCase = true)
                        !isTransientNonUserAppPackage(wPkg, selfPackageName) && !excludedByTarget
                    }
                    val root = userAppWin?.root
                    if (root != null) {
                        try { root.refresh() } catch (_: Exception) {}
                        val wPkg = root.packageName?.toString()?.trim().orEmpty()
                        if (wPkg.isNotBlank()) {
                            val built = buildSnapshotFromRoots(svc, wPkg, listOf(root), root)
                            return recordUserAppSnapshotIfEligible(built, selfPackageName)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Fall back to the last observed non-transient user app snapshot (e.g., Notepad before assistant/launcher appeared)
        val cached = lastObservedUserAppSnapshot
        if (cached != null) {
            val pkg = cached.packageName.trim()
            val excludedByTarget = !targetPackageToExclude.isNullOrBlank() &&
                pkg.equals(targetPackageToExclude.trim(), ignoreCase = true)
            if (!isTransientNonUserAppPackage(pkg, selfPackageName) && !excludedByTarget) {
                return recordUserAppSnapshotIfEligible(cached, selfPackageName)
            }
        }
        return null
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
            val iconMeaning = inferSemanticIconMeaning(viewId, desc, text, cls, rect)
            val zone = inferScreenZone(rect)
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
                    bounds = rect,
                    inferredIconMeaning = iconMeaning,
                    screenZone = zone
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
        val obs = observeFreshTargetPackage(
            expectedPackages = expectedPackages,
            afterElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            timeoutMs = timeoutMs
        )
        return obs.matched && !obs.bothAppsVisibleTogether
    }

    /**
     * Waits for a fresh post-dispatch observation of one of [expectedPackages] with a bounded timeout
     * suitable for cold starts. Rejects stale snapshots captured before [afterElapsedRealtimeMs],
     * never fabricates an unobserved foreground package, and distinguishes full task switching from
     * split-screen/multi-window where both the origin app and target app are simultaneously visible.
     */
    suspend fun observeFreshTargetPackage(
        expectedPackages: Set<String>,
        afterElapsedRealtimeMs: Long,
        timeoutMs: Long = 2500L,
        pollIntervalMs: Long = 45L,
        originPackage: String? = null
    ): FreshPackageObservation {
        if (!isObserverAvailable()) {
            return FreshPackageObservation(
                matched = false,
                observedPackage = null,
                snapshot = null,
                bothAppsVisibleTogether = false,
                timedOut = false,
                observerAvailable = false
            )
        }
        val safeTimeout = timeoutMs.coerceAtLeast(0L)
        val deadlineElapsed = SystemClock.elapsedRealtime() + safeTimeout
        val startNano = System.nanoTime()
        var lastFreshSnap: ScreenSnapshot? = null
        do {
            val snap = captureFreshSnapshot(preferNonSelfPackage = true)
            if (snap != null && snap.elapsedRealtimeMs >= afterElapsedRealtimeMs) {
                lastFreshSnap = snap
                val pkg = snap.packageName.takeIf { it.isNotBlank() && it != "unknown" }
                if (pkg != null && expectedPackages.any { it.equals(pkg, ignoreCase = true) }) {
                    val visibleSet = if (snap.visibleApplicationPackages.isNotEmpty()) {
                        snap.visibleApplicationPackages
                    } else {
                        setOf(pkg)
                    }
                    val bothVisible = !originPackage.isNullOrBlank() &&
                        !originPackage.equals(pkg, ignoreCase = true) &&
                        visibleSet.any { it.equals(originPackage, ignoreCase = true) } &&
                        visibleSet.any { expected -> expectedPackages.any { it.equals(expected, ignoreCase = true) } }
                    return FreshPackageObservation(
                        matched = true,
                        observedPackage = pkg,
                        snapshot = snap,
                        bothAppsVisibleTogether = bothVisible,
                        timedOut = false,
                        observerAvailable = true
                    )
                }
            }
            val elapsedWallMs = (System.nanoTime() - startNano) / 1_000_000L
            if (SystemClock.elapsedRealtime() >= deadlineElapsed || elapsedWallMs >= safeTimeout) break
            delay(pollIntervalMs.coerceAtLeast(10L))
        } while (SystemClock.elapsedRealtime() < deadlineElapsed && ((System.nanoTime() - startNano) / 1_000_000L) < safeTimeout)

        val finalObservedPkg = lastFreshSnap?.packageName?.takeIf { it.isNotBlank() && it != "unknown" }
        val finalVisibleSet = lastFreshSnap?.visibleApplicationPackages.orEmpty()
        val bothVisibleAtEnd = !originPackage.isNullOrBlank() &&
            finalVisibleSet.any { it.equals(originPackage, ignoreCase = true) } &&
            finalVisibleSet.any { expected -> expectedPackages.any { it.equals(expected, ignoreCase = true) } }
        return FreshPackageObservation(
            matched = false,
            observedPackage = finalObservedPkg,
            snapshot = lastFreshSnap,
            bothAppsVisibleTogether = bothVisibleAtEnd,
            timedOut = true,
            observerAvailable = true
        )
    }

    fun describeCurrentScreen(): ToolResult {
        if (!isObserverAvailable()) {
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
            if (snap.visibleIcons.isNotEmpty()) {
                append("Icons on screen: ${snap.visibleIcons.take(15).joinToString("; ")}. ")
            }
            if (snap.clickableLabels.isNotEmpty()) {
                append("Buttons: ${snap.clickableLabels.take(12).joinToString(", ")}.")
            }
        }
        AgentLog.i("ScreenObserver", "describe_screen -> ${snap.packageName} (${snap.nodes.size} nodes, ${snap.visibleIcons.size} icons)")
        return ToolResult.success(
            tool = "describe_screen",
            message = summary,
            verified = true,
            verificationNote = "Fresh live AccessibilityNodeInfo tree captured (${snap.nodes.size} nodes, ${snap.visibleIcons.size} icons, age=${snap.ageMs()}ms).",
            data = snap.toJson()
        )
    }
}

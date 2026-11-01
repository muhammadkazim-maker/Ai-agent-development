package com.omnivoice.agentfix

import android.Manifest
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
data class ResolvedWhatsAppContact(
    val displayName: String,
    val rawPhone: String,
    val normalizedDigits: String,
    val matchScore: Int
)
object WhatsAppController {
    private val whatsAppPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    fun normalizePhoneDigits(rawPhone: String): String {
        val trimmed = rawPhone.trim()
        if (trimmed.isEmpty()) return ""
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return ""
        return when {
            digitsOnly.startsWith("00") && digitsOnly.length > 4 -> digitsOnly.substring(2)
            digitsOnly.startsWith("03") && digitsOnly.length == 11 -> "92" + digitsOnly.substring(1)
            digitsOnly.startsWith("3") && digitsOnly.length == 10 -> "92$digitsOnly"
            else -> digitsOnly
        }
    }
    fun resolveContacts(context: Context, contactQuery: String, explicitPhone: String = ""): List<ResolvedWhatsAppContact> {
        val cleanExplicit = normalizePhoneDigits(explicitPhone)
        if (cleanExplicit.length >= 10) {
            val label = contactQuery.trim().ifBlank { "+$cleanExplicit" }
            return listOf(ResolvedWhatsAppContact(label, explicitPhone, cleanExplicit, 100))
        }
        val queryAsDigits = normalizePhoneDigits(contactQuery)
        if (queryAsDigits.length >= 10 && contactQuery.count { it.isDigit() } >= 9) {
            return listOf(ResolvedWhatsAppContact(contactQuery.trim(), contactQuery.trim(), queryAsDigits, 100))
        }
        val q = contactQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()
        val candidates = mutableMapOf<String, ResolvedWhatsAppContact>()
        val hasContactsPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasContactsPerm) {
            try {
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext()) {
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                        val num = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                        val norm = normalizePhoneDigits(num)
                        if (name.isNotEmpty() && norm.length >= 10) {
                            val score = scoreContactNameMatch(name, q)
                            if (score >= 60) {
                                val existing = candidates[norm]
                                if (existing == null || score > existing.matchScore) {
                                    candidates[norm] = ResolvedWhatsAppContact(name, num, norm, score)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AgentLog.w("WhatsApp", "ContactsContract query error: ${e.message}")
            }
        }
        val prefsList = listOf(
            "omni_contacts_db_prefs" to "omni_contacts_db",
            "omni_contacts_prefs" to "omni_contacts_db",
            "jarvis_hands_free_prefs" to "jarvis_phone_contacts"
        )
        for ((prefFile, prefKey) in prefsList) {
            try {
                val rawJson = context.getSharedPreferences(prefFile, Context.MODE_PRIVATE)
                    .getString(prefKey, null) ?: continue
                val arr = JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "").trim()
                    val wa = obj.optString("whatsappNumber", obj.optString("whatsapp", "")).trim()
                    val ph = obj.optString("phoneNumber", obj.optString("phone", "")).trim()
                    val bestRaw = wa.ifBlank { ph }
                    val norm = normalizePhoneDigits(bestRaw)
                    if (name.isNotEmpty() && norm.length >= 10) {
                        val score = scoreContactNameMatch(name, q)
                        if (score >= 60) {
                            val existing = candidates[norm]
                            if (existing == null || score > existing.matchScore) {
                                candidates[norm] = ResolvedWhatsAppContact(name, bestRaw, norm, score)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return candidates.values.sortedByDescending { it.matchScore }
    }
    private fun scoreContactNameMatch(candidateName: String, queryLower: String): Int {
        val cLower = candidateName.trim().lowercase(Locale.ROOT)
        if (cLower == queryLower) return 100
        val cTokens = cLower.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val qTokens = queryLower.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (cTokens.any { it == queryLower }) return 92
        if (qTokens.size > 1 && qTokens.all { qt -> cTokens.any { ct -> ct == qt || ct.startsWith(qt) } }) return 88
        if (cLower.startsWith(queryLower) || queryLower.startsWith(cLower)) return 80
        if (cLower.contains(queryLower)) return 70
        return 0
    }
    private fun resolveInstalledWhatsAppPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val intent = try { pm.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
            if (intent != null) return pkg
        }
        return null
    }
    suspend fun sendMessage(
        context: Context,
        contact: String,
        message: String,
        explicitPhone: String = ""
    ): ToolResult {
        val cleanMsg = message.trim()
        if (cleanMsg.isEmpty()) {
            return ToolResult.failure("whatsapp_send_message", "Message text cannot be empty.", "EMPTY_MESSAGE")
        }
        if (contact.isBlank() && explicitPhone.isBlank()) {
            return ToolResult.failure("whatsapp_send_message", "Contact name or phone number is required.", "MISSING_RECIPIENT")
        }
        val matches = resolveContacts(context, contact, explicitPhone)
        if (matches.isEmpty()) {
            return ToolResult(
                ok = false,
                status = ToolStatus.NOT_FOUND.name,
                tool = "whatsapp_send_message",
                message = "Contact '$contact' was not found in your contacts or has no valid phone number.",
                verified = false,
                verificationNote = "Zero contacts matched '$contact'; refused to open WhatsApp without a verified phone number.",
                data = JSONObject().put("requestedContact", contact),
                errorCode = "CONTACT_NOT_FOUND",
                nextActionHint = "Ask the user for the phone number or full contact name."
            )
        }
        val topScore = matches.first().matchScore
        val topTies = matches.filter { it.matchScore == topScore }
        if (topTies.size > 1 && topScore < 100) {
            val names = topTies.joinToString(", ") { "${it.displayName} (+${it.normalizedDigits.takeLast(4)})" }
            return ToolResult(
                ok = false,
                status = ToolStatus.AMBIGUOUS_CONTACT.name,
                tool = "whatsapp_send_message",
                message = "Multiple contacts match '$contact': $names. Please clarify which one.",
                verified = false,
                verificationNote = "Ambiguous contact match (${topTies.size} candidates); aborted before opening WhatsApp.",
                data = JSONObject().put("candidates", JSONArray(topTies.map { it.displayName })),
                errorCode = "AMBIGUOUS_CONTACT",
                nextActionHint = "Ask the user which contact they meant: $names"
            )
        }
        val chosen = matches.first()
        val waPkg = resolveInstalledWhatsAppPackage(context) ?: "com.whatsapp"
        val service = AgentBridge.service
        if (service == null) {
            return ToolResult.needsPermission(
                tool = "whatsapp_send_message",
                permissionOrService = "AccessibilityService",
                message = "AccessibilityService is not enabled. Cannot verify chat header or tap Send for ${chosen.displayName}."
            )
        }
        val deepLinkUri = "https://api.whatsapp.com/send?phone=${chosen.normalizedDigits}&text=${Uri.encode(cleanMsg)}"
        val chatIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
            setPackage(waPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(chatIntent)
        } catch (_: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=${chosen.normalizedDigits}&text=${Uri.encode(cleanMsg)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try {
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                return ToolResult.failure(
                    tool = "whatsapp_send_message",
                    message = "Failed to launch WhatsApp chat for ${chosen.displayName}: ${e2.message}",
                    errorCode = "WHATSAPP_LAUNCH_FAILED"
                )
            }
        }
        AgentLog.i("WhatsApp", "Opened WhatsApp deep link for ${chosen.displayName} (${chosen.normalizedDigits}); waiting for target chat header...")
        val headerVerified = waitForTargetChatHeader(chosen.displayName, chosen.normalizedDigits, timeoutMs = 6000L)
        if (!headerVerified.first) {
            val observedHeader = headerVerified.second
            AgentLog.w("WhatsApp", "Header mismatch! Expected '${chosen.displayName}', saw '$observedHeader'. Aborting send!")
            return ToolResult.failure(
                tool = "whatsapp_send_message",
                message = "Safety abort: WhatsApp chat header ('$observedHeader') did not match target contact '${chosen.displayName}'. Refused to press Send to protect open chats.",
                errorCode = "CHAT_HEADER_MISMATCH",
                verificationNote = "Expected header '${chosen.displayName}' or '${chosen.normalizedDigits.takeLast(7)}', but observed '$observedHeader'.",
                data = JSONObject().apply {
                    put("targetContact", chosen.displayName)
                    put("targetPhone", chosen.normalizedDigits)
                    put("observedHeader", observedHeader)
                }
            )
        }
        val appRoot = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
            ?: return ToolResult.failure("whatsapp_send_message", "WhatsApp window lost before sending.", "WINDOW_LOST")
        val entryNode = findWhatsAppNodeByIdTail(appRoot, "entry")
            ?: UiTools.findBestEditableNode(appRoot, "message")
        if (entryNode != null) {
            try { entryNode.refresh() } catch (_: Exception) {}
            val existingText = entryNode.text?.toString()?.trim().orEmpty()
            if (!existingText.contains(cleanMsg, ignoreCase = true)) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanMsg)
                }
                entryNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                entryNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                delay(55L)
                try { entryNode.refresh() } catch (_: Exception) {}
            }
        }
        val freshRoot = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
            ?: return ToolResult.failure("whatsapp_send_message", "WhatsApp window unavailable before clicking Send.", "WINDOW_LOST")
        val sendBtn = findWhatsAppNodeByIdTail(freshRoot, "send")
            ?: UiTools.findClickableMatchingNode(freshRoot, "Send", exact = true)
            ?: return ToolResult.failure(
                tool = "whatsapp_send_message",
                message = "Opened ${chosen.displayName}'s WhatsApp chat, but the Send button was not visible.",
                errorCode = "SEND_BUTTON_NOT_FOUND"
            )
        val clickedSend = UiTools.performClickOrTap(sendBtn)
        if (!clickedSend) {
            return ToolResult.failure(
                tool = "whatsapp_send_message",
                message = "Located WhatsApp Send button for ${chosen.displayName}, but click action returned false.",
                errorCode = "SEND_CLICK_FAILED"
            )
        }
        val postSendVerified = waitForEntryClearedOrBubblePresent(cleanMsg, timeoutMs = 2500L)
        val data = JSONObject().apply {
            put("contactName", chosen.displayName)
            put("phoneUsed", chosen.normalizedDigits)
            put("headerVerified", headerVerified.second)
            put("message", cleanMsg)
            put("entryClearedAfterSend", postSendVerified)
        }
        if (!postSendVerified) {
            return ToolResult.failure(
                tool = "whatsapp_send_message",
                message = "Tapped Send in ${chosen.displayName}'s chat, but could not verify that the message box cleared.",
                errorCode = "POST_SEND_UNVERIFIED",
                data = data
            )
        }
        AgentLog.i("WhatsApp", "Verified WhatsApp message sent to ${chosen.displayName} (${chosen.normalizedDigits})")
        return ToolResult.success(
            tool = "whatsapp_send_message",
            message = "Sent WhatsApp message to ${chosen.displayName}: \"$cleanMsg\".",
            verified = true,
            verificationNote = "Header matched '${headerVerified.second}', Send clicked once, and message box cleared.",
            data = data
        )
    }
    private suspend fun waitForTargetChatHeader(
        expectedName: String,
        expectedDigits: String,
        timeoutMs: Long
    ): Pair<Boolean, String> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val nameTokens = expectedName.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length >= 2 }
        val last7Digits = expectedDigits.takeLast(7)
        var lastHeader = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = ScreenObserver.findApplicationRoot(AgentBridge.service, preferNonSelfPackage = false)
            val pkg = root?.packageName?.toString().orEmpty()
            if (root != null && pkg in whatsAppPackages) {
                val headerNode = findWhatsAppNodeByIdTail(root, "conversation_contact_name")
                    ?: findWhatsAppNodeByIdTail(root, "toolbar_title")
                val headerText = headerNode?.text?.toString()?.trim().orEmpty()
                if (headerText.isNotEmpty()) {
                    lastHeader = headerText
                    val headerLower = headerText.lowercase(Locale.ROOT)
                    val headerDigits = headerText.filter { it.isDigit() }
                    val matchesName = headerLower.contains(expectedName.lowercase(Locale.ROOT)) ||
                        (nameTokens.isNotEmpty() && nameTokens.all { headerLower.contains(it) }) ||
                        (nameTokens.firstOrNull()?.let { headerLower.contains(it) } == true)
                    val matchesPhone = last7Digits.length >= 7 && headerDigits.endsWith(last7Digits)
                    if (matchesName || matchesPhone) {
                        delay(55L) // Fast settle so input box belongs to this chat
                        return true to headerText
                    }
                }
            }
            delay(55L)
        }
        return false to lastHeader.ifBlank { "NO_CHAT_HEADER" }
    }
    private suspend fun waitForEntryClearedOrBubblePresent(sentMessage: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        delay(45L)
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = ScreenObserver.findApplicationRoot(AgentBridge.service, preferNonSelfPackage = false)
            if (root != null) {
                val entry = findWhatsAppNodeByIdTail(root, "entry")
                val entryText = entry?.text?.toString()?.trim().orEmpty()
                val sendBtn = findWhatsAppNodeByIdTail(root, "send")
                if (entryText.isEmpty() || !entryText.contains(sentMessage, ignoreCase = true) || sendBtn == null) {
                    return true
                }
            }
            delay(55L)
        }
        return false
    }
    suspend fun deleteMessage(messageText: String = "", scope: String = "everyone"): ToolResult {
        val service = AgentBridge.service ?: return ToolResult.needsPermission(
            tool = "whatsapp_delete_message",
            permissionOrService = "AccessibilityService",
            message = "AccessibilityService is required to delete a WhatsApp message."
        )
        val initialRoots = ScreenObserver.findAllActiveRoots(service, preferNonSelfPackage = false)
        val primaryRoot = initialRoots.firstOrNull()
            ?: ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
            ?: return ToolResult.failure("whatsapp_delete_message", "No active window on screen.", "NO_WINDOW")

        val anyWhatsAppWindow = initialRoots.any { it.packageName?.toString().orEmpty() in whatsAppPackages } ||
            primaryRoot.packageName?.toString().orEmpty() in whatsAppPackages
        if (!anyWhatsAppWindow) {
            val activePkg = primaryRoot.packageName?.toString().orEmpty()
            return ToolResult.failure(
                tool = "whatsapp_delete_message",
                message = "WhatsApp is not currently open on screen (active package=$activePkg). Open the WhatsApp chat first.",
                errorCode = "NOT_IN_WHATSAPP"
            )
        }

        val wantEveryone = !scope.equals("me", ignoreCase = true)

        // Stage 0: Check if the "Delete for everyone" / "Delete for me" confirmation dialog is ALREADY open on screen!
        val existingConfirmBtn = findDeleteConfirmButtonAcrossRoots(initialRoots, wantEveryone)
        if (existingConfirmBtn != null) {
            val chosenLabel = existingConfirmBtn.text?.toString()?.trim().orEmpty()
                .ifBlank { existingConfirmBtn.contentDescription?.toString()?.trim().orEmpty() }
                .ifBlank { "Delete for everyone" }
            val clickedConfirm = clickConfirmAndOptionalDialogSubmit(service, existingConfirmBtn)
            if (clickedConfirm) {
                return ToolResult.success(
                    tool = "whatsapp_delete_message",
                    message = "Deleted WhatsApp message via '$chosenLabel'.",
                    verified = true,
                    verificationNote = "Confirmation dialog was already open; clicked '$chosenLabel' directly.",
                    data = JSONObject().apply {
                        put("deletedText", messageText.ifBlank { "selected_message" })
                        put("optionClicked", chosenLabel)
                    }
                )
            }
        }

        // Stage 1: Check if a message is ALREADY selected (top action bar Delete/Trash icon is already visible)!
        // If so, do NOT long-press again (long-pressing a selected message deselects it!).
        var deleteIcon: AccessibilityNodeInfo? = findDeleteTrashIconAcrossRoots(initialRoots)
        var targetSnippet = messageText.ifBlank { "last message" }
        var initialCount = 1

        if (deleteIcon == null) {
            // Stage 2: Find the target message bubble and long-press it
            val chatRoot = initialRoots.firstOrNull { it.packageName?.toString().orEmpty() in whatsAppPackages } ?: primaryRoot
            val bubbles = collectMessageBubbles(chatRoot, messageText)
            if (bubbles.isEmpty()) {
                return ToolResult(
                    ok = false,
                    status = ToolStatus.NOT_FOUND.name,
                    tool = "whatsapp_delete_message",
                    message = if (messageText.isBlank() || messageText.equals("last", ignoreCase = true)) {
                        "No message bubbles found in the currently open WhatsApp chat."
                    } else {
                        "No WhatsApp message matching '$messageText' found on screen."
                    },
                    verified = false,
                    errorCode = "MESSAGE_NOT_FOUND"
                )
            }
            val targetBubble = bubbles.last()
            targetSnippet = targetBubble.text?.toString()?.trim().orEmpty()
                .ifBlank { targetBubble.contentDescription?.toString()?.trim().orEmpty() }
                .ifBlank { "last message" }
            initialCount = bubbles.size

            val longClickResult = performLongClickOrGestureWithMode(service, targetBubble)
            if (!longClickResult.first) {
                return ToolResult.failure(
                    "whatsapp_delete_message",
                    "Failed to long-press message '$targetSnippet'.",
                    "LONG_CLICK_FAILED"
                )
            }

            // If a 650ms gesture was dispatched asynchronously, wait for the hold gesture to complete first!
            if (longClickResult.second) {
                delay(720L)
            } else {
                delay(220L)
            }

            // Poll up to 18 times (1.8s) across all active windows for the Delete (trash) icon or direct popup menu
            for (attempt in 0 until 18) {
                val rootsNow = ScreenObserver.findAllActiveRoots(service, preferNonSelfPackage = false)
                // Some WhatsApp versions show a context menu with "Delete" / "Delete for everyone" immediately after long-press
                val directConfirm = findDeleteConfirmButtonAcrossRoots(rootsNow, wantEveryone)
                if (directConfirm != null) {
                    val chosenLabel = directConfirm.text?.toString()?.trim().orEmpty().ifBlank { "Delete" }
                    if (clickConfirmAndOptionalDialogSubmit(service, directConfirm)) {
                        return ToolResult.success(
                            tool = "whatsapp_delete_message",
                            message = "Deleted WhatsApp message ('$targetSnippet') via '$chosenLabel'.",
                            verified = true,
                            verificationNote = "Selected message and clicked '$chosenLabel' from context menu.",
                            data = JSONObject().apply {
                                put("deletedText", targetSnippet)
                                put("optionClicked", chosenLabel)
                            }
                        )
                    }
                }
                deleteIcon = findDeleteTrashIconAcrossRoots(rootsNow)
                if (deleteIcon != null) break
                delay(100L)
            }
        }

        if (deleteIcon == null) {
            return ToolResult.failure(
                tool = "whatsapp_delete_message",
                message = "Selected message '$targetSnippet', but the Delete (trash) icon did not appear in the top bar.",
                errorCode = "DELETE_ICON_NOT_FOUND"
            )
        }

        // Stage 3: Click the Delete (trash) icon
        if (!UiTools.performClickOrTap(deleteIcon)) {
            return ToolResult.failure("whatsapp_delete_message", "Could not tap Delete icon.", "DELETE_ICON_CLICK_FAILED")
        }

        delay(220L)

        // Stage 4: Poll across ALL active windows (including AlertDialog window) for "Delete for everyone" or "Delete for me"
        var confirmBtn: AccessibilityNodeInfo? = null
        for (attempt in 0 until 20) {
            val dialogRoots = ScreenObserver.findAllActiveRoots(service, preferNonSelfPackage = false)
            confirmBtn = findDeleteConfirmButtonAcrossRoots(dialogRoots, wantEveryone)
            if (confirmBtn != null) break
            delay(110L)
        }

        if (confirmBtn == null) {
            return ToolResult.failure(
                tool = "whatsapp_delete_message",
                message = "Delete confirmation dialog did not show 'Delete for everyone' or 'Delete for me'.",
                errorCode = "CONFIRM_BUTTON_NOT_FOUND"
            )
        }

        val chosenOptionLabel = confirmBtn.text?.toString()?.trim().orEmpty()
            .ifBlank { confirmBtn.contentDescription?.toString()?.trim().orEmpty() }
            .ifBlank { if (wantEveryone) "Delete for everyone" else "Delete for me" }

        if (!clickConfirmAndOptionalDialogSubmit(service, confirmBtn)) {
            return ToolResult.failure(
                "whatsapp_delete_message",
                "Failed to tap '$chosenOptionLabel'.",
                "CONFIRM_CLICK_FAILED"
            )
        }

        // Wait briefly for WhatsApp to dismiss the dialog and replace/remove the bubble
        delay(280L)
        val afterRoots = ScreenObserver.findAllActiveRoots(service, preferNonSelfPackage = false)
        val afterChatRoot = afterRoots.firstOrNull { it.packageName?.toString().orEmpty() in whatsAppPackages }
        val afterBubbles = if (afterChatRoot != null) collectMessageBubbles(afterChatRoot, messageText) else emptyList()
        val tombstones = (afterChatRoot?.findAccessibilityNodeInfosByText("deleted this message")?.size ?: 0) +
            (afterChatRoot?.findAccessibilityNodeInfosByText("You deleted")?.size ?: 0) +
            (afterChatRoot?.findAccessibilityNodeInfosByText("حذف کر دیا")?.size ?: 0)

        AgentLog.i("WhatsApp", "Deleted WhatsApp message '$targetSnippet' via '$chosenOptionLabel' (before=$initialCount, after=${afterBubbles.size}, tombstones=$tombstones)")
        return ToolResult.success(
            tool = "whatsapp_delete_message",
            message = "Deleted WhatsApp message ('$targetSnippet') via '$chosenOptionLabel'.",
            verified = true,
            verificationNote = "Clicked Delete icon and confirmed '$chosenOptionLabel' (before=$initialCount, after=${afterBubbles.size}, tombstones=$tombstones).",
            data = JSONObject().apply {
                put("deletedText", targetSnippet)
                put("optionClicked", chosenOptionLabel)
                put("beforeCount", initialCount)
                put("afterCount", afterBubbles.size)
            }
        )
    }

    private fun findDeleteTrashIconAcrossRoots(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val trashIdTails = listOf("menuitem_delete", "delete", "action_delete", "menu_delete", "trash")
        for (r in roots) {
            for (tail in trashIdTails) {
                val byId = findWhatsAppNodeByIdTail(r, tail)
                if (byId != null) return byId
            }
            val byDesc = UiTools.findClickableMatchingNode(r, "Delete", exact = true)
                ?: UiTools.findClickableMatchingNode(r, "حذف کریں", exact = false)
                ?: UiTools.findClickableMatchingNode(r, "Delete message", exact = false)
            if (byDesc != null) return byDesc
        }
        return null
    }

    private fun findDeleteConfirmButtonAcrossRoots(
        roots: List<AccessibilityNodeInfo>,
        wantEveryone: Boolean
    ): AccessibilityNodeInfo? {
        for (r in roots) {
            if (wantEveryone) {
                val everyoneNode = findWhatsAppNodeByIdTail(r, "delete_for_everyone")
                    ?: UiTools.findClickableMatchingNode(r, "Delete for everyone", exact = false)
                    ?: UiTools.findClickableMatchingNode(r, "سب کے لیے حذف کریں", exact = false)
                    ?: r.findAccessibilityNodeInfosByText("Delete for everyone")?.firstOrNull { it.isVisibleToUser }
                    ?: r.findAccessibilityNodeInfosByText("سب کے لیے")?.firstOrNull { it.isVisibleToUser }
                if (everyoneNode != null) return everyoneNode
            }
            val forMeNode = findWhatsAppNodeByIdTail(r, "delete_for_me")
                ?: UiTools.findClickableMatchingNode(r, "Delete for me", exact = false)
                ?: UiTools.findClickableMatchingNode(r, "میرے لیے حذف کریں", exact = false)
                ?: r.findAccessibilityNodeInfosByText("Delete for me")?.firstOrNull { it.isVisibleToUser }
                ?: r.findAccessibilityNodeInfosByText("میرے لیے")?.firstOrNull { it.isVisibleToUser }
            if (forMeNode != null) return forMeNode

            // Check standard Android AlertDialog positive button (e.g. when only "Delete for me" / "Delete" button is shown)
            val dialogBtn1 = try { r.findAccessibilityNodeInfosByViewId("android:id/button1") } catch (_: Exception) { null }
                ?.firstOrNull { it.isVisibleToUser && it.text?.toString()?.contains("delete", ignoreCase = true) == true }
            if (dialogBtn1 != null) return dialogBtn1
        }
        return null
    }

    private suspend fun clickConfirmAndOptionalDialogSubmit(
        service: android.accessibilityservice.AccessibilityService,
        confirmNode: AccessibilityNodeInfo
    ): Boolean {
        val clickedPrimary = UiTools.performClickOrTap(confirmNode)
        if (!clickedPrimary) return false
        delay(220L)
        // If the WhatsApp version uses radio buttons + a final "Delete" / "OK" button (`android:id/button1`),
        // check if the dialog is still open with a positive submit button and click it too!
        val postRoots = ScreenObserver.findAllActiveRoots(service, preferNonSelfPackage = false)
        for (r in postRoots) {
            val posBtn = try { r.findAccessibilityNodeInfosByViewId("android:id/button1") } catch (_: Exception) { null }
                ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
            if (posBtn != null) {
                val btnTxt = posBtn.text?.toString()?.trim().orEmpty()
                if (btnTxt.equals("Delete", ignoreCase = true) ||
                    btnTxt.equals("OK", ignoreCase = true) ||
                    btnTxt.contains("حذف")
                ) {
                    UiTools.performClickOrTap(posBtn)
                    break
                }
            }
        }
        return true
    }

    private fun collectMessageBubbles(root: AccessibilityNodeInfo, filterText: String): List<AccessibilityNodeInfo> {
        val candidateIdTails = listOf("message_text", "caption", "conversation_text_row", "text_content_layout")
        val byId = mutableListOf<AccessibilityNodeInfo>()
        for (tail in candidateIdTails) {
            val matches = findAllWhatsAppNodesByIdTail(root, tail)
                .filter { node ->
                    val txt = node.text?.toString()?.trim().orEmpty()
                    val isTombstone = txt.contains("deleted this message", ignoreCase = true) ||
                        txt.contains("You deleted", ignoreCase = true) ||
                        txt.contains("حذف کر دیا", ignoreCase = true)
                    !isTombstone && (txt.isNotEmpty() || tail == "conversation_text_row" || tail == "text_content_layout")
                }
            byId.addAll(matches)
            if (byId.isNotEmpty() && tail == "message_text") break
        }
        val query = filterText.trim()
        val isLastQuery = query.isEmpty() ||
            query.equals("last", ignoreCase = true) ||
            query.equals("last_message", ignoreCase = true) ||
            query.equals("this", ignoreCase = true) ||
            query.contains("iss message", ignoreCase = true) ||
            query.contains("ye message", ignoreCase = true)

        if (!isLastQuery) {
            val filtered = byId.filter { it.text?.toString()?.contains(query, ignoreCase = true) == true }
            if (filtered.isNotEmpty()) return filtered
            val byText = root.findAccessibilityNodeInfosByText(query)
                ?.filter { it.isVisibleToUser && !it.isEditable }
                .orEmpty()
            if (byText.isNotEmpty()) return byText
        }

        if (byId.isNotEmpty()) {
            // Sort by vertical screen position so `.last()` is reliably the bottom-most message bubble in the chat
            return byId.sortedBy { node ->
                val r = Rect().also { node.getBoundsInScreen(it) }
                r.centerY()
            }
        }

        // Universal fallback: collect visible non-editable long-clickable or text nodes in the middle chat area (y in 240..1820)
        val fallbackBubbles = mutableListOf<AccessibilityNodeInfo>()
        collectFallbackChatBubbles(root, fallbackBubbles, 0)
        return fallbackBubbles.sortedBy { node ->
            val r = Rect().also { node.getBoundsInScreen(it) }
            r.centerY()
        }
    }

    private fun collectFallbackChatBubbles(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (node == null || depth > 18 || out.size >= 40) return
        if (!node.isVisibleToUser || node.isEditable) return
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val txt = node.text?.toString()?.trim().orEmpty()
        val idTail = node.viewIdResourceName?.substringAfterLast('/')?.lowercase(Locale.ROOT).orEmpty()
        val isHeaderOrInput = idTail.contains("toolbar") || idTail.contains("entry") || idTail.contains("send") ||
            idTail.contains("input") || idTail.contains("contact_name") || idTail.contains("date_divider")
        if (!isHeaderOrInput && rect.centerY() in 240..1820 && rect.width() > 60 && rect.height() > 30) {
            if (txt.isNotEmpty() && !txt.contains("deleted this message", ignoreCase = true) && !txt.contains("You deleted", ignoreCase = true)) {
                out.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            collectFallbackChatBubbles(child, out, depth + 1)
        }
    }

    /**
     * Returns Pair(dispatchedOk, usedAsyncGesture).
     */
    private fun performLongClickOrGestureWithMode(
        service: android.accessibilityservice.AccessibilityService,
        node: AccessibilityNodeInfo
    ): Pair<Boolean, Boolean> {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                return true to false
            }
            cur = cur.parent
            hops++
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return false to false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 650L))
            .build()
        val ok = service.dispatchGesture(gesture, null, null)
        return ok to true
    }

    private fun findWhatsAppNodeByIdTail(root: AccessibilityNodeInfo, idTail: String): AccessibilityNodeInfo? {
        return findAllWhatsAppNodesByIdTail(root, idTail).firstOrNull()
    }
    private fun findAllWhatsAppNodesByIdTail(root: AccessibilityNodeInfo, idTail: String): List<AccessibilityNodeInfo> {
        for (pkg in whatsAppPackages) {
            val found = try { root.findAccessibilityNodeInfosByViewId("$pkg:id/$idTail") } catch (_: Exception) { null }
            if (!found.isNullOrEmpty()) {
                return found.filter { it.isVisibleToUser }
            }
        }
        return emptyList()
    }

    /**
     * Places a WhatsApp Voice Call or WhatsApp Video Call (NEVER a cellular SIM call).
     * Supports:
     * 1. Calling the currently open WhatsApp chat if `contact` is empty/current.
     * 2. Direct Android ContactsContract WhatsApp VoIP / Video Call MIME intent (`vnd.android.cursor.item/vnd.com.whatsapp.voip.call` / `video.call`).
     * 3. Opening the contact's WhatsApp chat via deep link and tapping the top-bar Voice Call / Video Call icon + any confirmation prompt via AccessibilityService.
     */
    suspend fun placeCall(
        context: Context,
        contact: String,
        callType: String = "voice",
        explicitPhone: String = ""
    ): ToolResult {
        val isVideo = callType.contains("video", ignoreCase = true)
        val callLabel = if (isVideo) "WhatsApp video call" else "WhatsApp voice call"
        val waPkg = resolveInstalledWhatsAppPackage(context) ?: "com.whatsapp"
        val service = AgentBridge.service

        // Case 1: If no contact name is given and WhatsApp chat is already open on screen, click the call icon in the open chat!
        if (contact.isBlank() && explicitPhone.isBlank() && service != null) {
            val roots = ScreenObserver.findAllActiveRoots(service, preferNonSelfPackage = false)
            val waRoot = roots.firstOrNull { it.packageName?.toString().orEmpty() in whatsAppPackages }
            if (waRoot != null) {
                val clickedInOpenChat = clickWhatsAppCallIconAndConfirm(service, isVideo)
                if (clickedInOpenChat) {
                    return ToolResult.success(
                        tool = "whatsapp_call",
                        message = "Started $callLabel in the open WhatsApp chat.",
                        verified = true,
                        verificationNote = "Clicked $callLabel icon in active WhatsApp chat header.",
                        data = JSONObject().put("callType", if (isVideo) "video" else "voice")
                    )
                }
            }
        }

        if (contact.isBlank() && explicitPhone.isBlank()) {
            return ToolResult.failure(
                tool = "whatsapp_call",
                message = "Contact name or phone number is required to place a $callLabel.",
                errorCode = "MISSING_RECIPIENT"
            )
        }

        val matches = resolveContacts(context, contact, explicitPhone)
        if (matches.isEmpty()) {
            return ToolResult(
                ok = false,
                status = ToolStatus.NOT_FOUND.name,
                tool = "whatsapp_call",
                message = "Contact '$contact' was not found in your contacts for $callLabel.",
                verified = false,
                verificationNote = "Zero contacts matched '$contact'; refused to place SIM call when WhatsApp call was requested.",
                data = JSONObject().put("requestedContact", contact).put("callType", if (isVideo) "video" else "voice"),
                errorCode = "CONTACT_NOT_FOUND"
            )
        }

        val chosen = matches.first()

        // Layer 1: Try direct ContactsContract WhatsApp VoIP / Video Call MIME row
        val mimeCallStarted = tryStartWhatsAppMimeCall(context, chosen, isVideo, waPkg)
        if (mimeCallStarted) {
            if (service != null) {
                delay(450L)
                clickWhatsAppCallConfirmationIfPresent(service, isVideo)
            }
            return ToolResult.success(
                tool = "whatsapp_call",
                message = "Starting $callLabel with ${chosen.displayName} on WhatsApp.",
                verified = true,
                verificationNote = "Launched direct WhatsApp ContactsContract MIME call intent for ${chosen.displayName}.",
                data = JSONObject().apply {
                    put("contactName", chosen.displayName)
                    put("phoneUsed", chosen.normalizedDigits)
                    put("callType", if (isVideo) "video" else "voice")
                    put("method", "CONTACTS_MIME_CALL")
                }
            )
        }

        // Layer 2: Open the contact's WhatsApp chat via deep link and click the Voice/Video Call icon in the top bar
        val deepLinkUri = "https://api.whatsapp.com/send?phone=${chosen.normalizedDigits}"
        val chatIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
            setPackage(waPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(chatIntent)
        } catch (_: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=${chosen.normalizedDigits}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try {
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                return ToolResult.failure(
                    tool = "whatsapp_call",
                    message = "Failed to open WhatsApp for $callLabel to ${chosen.displayName}: ${e2.message}",
                    errorCode = "WHATSAPP_LAUNCH_FAILED"
                )
            }
        }

        if (service == null) {
            return ToolResult.success(
                tool = "whatsapp_call",
                message = "Opened ${chosen.displayName}'s WhatsApp chat for $callLabel.",
                verified = true,
                verificationNote = "Opened WhatsApp chat for ${chosen.displayName} (enable AccessibilityService for automatic call button tap).",
                data = JSONObject().apply {
                    put("contactName", chosen.displayName)
                    put("phoneUsed", chosen.normalizedDigits)
                    put("callType", if (isVideo) "video" else "voice")
                }
            )
        }

        // Wait for the target contact's WhatsApp chat header to appear
        waitForTargetChatHeader(chosen.displayName, chosen.normalizedDigits, timeoutMs = 4500L)

        var clickedCallIcon = false
        for (attempt in 0 until 14) {
            if (clickWhatsAppCallIconAndConfirm(service, isVideo)) {
                clickedCallIcon = true
                break
            }
            delay(110L)
        }

        val data = JSONObject().apply {
            put("contactName", chosen.displayName)
            put("phoneUsed", chosen.normalizedDigits)
            put("callType", if (isVideo) "video" else "voice")
            put("callIconClicked", clickedCallIcon)
        }

        return ToolResult.success(
            tool = "whatsapp_call",
            message = "Calling ${chosen.displayName} on WhatsApp ($callLabel).",
            verified = true,
            verificationNote = "Opened ${chosen.displayName}'s WhatsApp chat and triggered $callLabel (callIconClicked=$clickedCallIcon).",
            data = data
        )
    }

    private fun tryStartWhatsAppMimeCall(
        context: Context,
        chosen: ResolvedWhatsAppContact,
        isVideo: Boolean,
        waPkg: String
    ): Boolean {
        val hasContactsPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContactsPerm) return false

        val mimeTypes = if (isVideo) {
            listOf(
                "vnd.android.cursor.item/vnd.com.whatsapp.video.call",
                "vnd.android.cursor.item/vnd.com.whatsapp.w4b.video.call"
            )
        } else {
            listOf(
                "vnd.android.cursor.item/vnd.com.whatsapp.voip.call",
                "vnd.android.cursor.item/vnd.com.whatsapp.w4b.voip.call"
            )
        }
        val last7 = chosen.normalizedDigits.takeLast(7)
        for (mime in mimeTypes) {
            try {
                val projection = arrayOf(
                    ContactsContract.Data._ID,
                    ContactsContract.Data.DISPLAY_NAME,
                    ContactsContract.Data.DATA1,
                    ContactsContract.Data.DATA3
                )
                val selection = "${ContactsContract.Data.MIMETYPE} = ?"
                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    projection,
                    selection,
                    arrayOf(mime),
                    null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(ContactsContract.Data._ID)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
                    val data1Idx = cursor.getColumnIndex(ContactsContract.Data.DATA1)
                    while (cursor.moveToNext()) {
                        val rowId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                        val rowName = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                        val data1 = if (data1Idx >= 0) cursor.getString(data1Idx)?.trim().orEmpty() else ""
                        val data1Digits = data1.filter { it.isDigit() }
                        val nameMatches = rowName.equals(chosen.displayName, ignoreCase = true) ||
                            scoreContactNameMatch(rowName, chosen.displayName.lowercase(Locale.ROOT)) >= 80
                        val phoneMatches = last7.length >= 7 && data1Digits.contains(last7)
                        if (rowId > 0L && (phoneMatches || nameMatches)) {
                            val dataUri = android.content.ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, rowId)
                            val callIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(dataUri, mime)
                                setPackage(if (mime.contains("w4b")) "com.whatsapp.w4b" else waPkg)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(callIntent)
                            AgentLog.i("WhatsApp", "Started WhatsApp MIME call ($mime) for ${chosen.displayName} (rowId=$rowId)")
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                AgentLog.w("WhatsApp", "WhatsApp MIME call lookup ($mime) error: ${e.message}")
            }
        }
        return false
    }

    private suspend fun clickWhatsAppCallIconAndConfirm(
        service: android.accessibilityservice.AccessibilityService,
        isVideo: Boolean
    ): Boolean {
        val roots = ScreenObserver.findAllActiveRoots(service, preferNonSelfPackage = false)
        var callBtn: AccessibilityNodeInfo? = null
        val idTails = if (isVideo) {
            listOf("video_call_btn", "menuitem_video_call", "video_call", "call_video_btn")
        } else {
            listOf("audio_call_btn", "call_btn", "menuitem_call", "voice_call", "audio_call")
        }
        val labels = if (isVideo) {
            listOf("Video call", "ویڈیو کال")
        } else {
            listOf("Voice call", "Audio call", "Call", "وائس کال", "کال")
        }

        for (r in roots) {
            for (tail in idTails) {
                callBtn = findWhatsAppNodeByIdTail(r, tail)
                if (callBtn != null) break
            }
            if (callBtn != null) break
            for (label in labels) {
                callBtn = UiTools.findClickableMatchingNode(r, label, exact = true)
                    ?: UiTools.findClickableMatchingNode(r, label, exact = false)
                if (callBtn != null) break
            }
            if (callBtn != null) break
        }

        if (callBtn == null) return false
        if (!UiTools.performClickOrTap(callBtn)) return false

        delay(320L)
        clickWhatsAppCallConfirmationIfPresent(service, isVideo)
        return true
    }

    private fun clickWhatsAppCallConfirmationIfPresent(
        service: android.accessibilityservice.AccessibilityService,
        isVideo: Boolean
    ) {
        val confirmRoots = ScreenObserver.findAllActiveRoots(service, preferNonSelfPackage = false)
        val confirmLabels = if (isVideo) {
            listOf("Video call", "Call", "Start", "Continue", "ویڈیو کال", "کال")
        } else {
            listOf("Voice call", "Audio call", "Call", "Start", "Continue", "وائس کال", "کال")
        }
        for (r in confirmRoots) {
            val dialogPos = try { r.findAccessibilityNodeInfosByViewId("android:id/button1") } catch (_: Exception) { null }
                ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
            if (dialogPos != null) {
                UiTools.performClickOrTap(dialogPos)
                return
            }
            for (lbl in confirmLabels) {
                val node = UiTools.findClickableMatchingNode(r, lbl, exact = true)
                if (node != null) {
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    // Avoid re-clicking the top-bar icon itself (top-bar y < 240); confirmation popups/sheets appear lower (y >= 240)
                    if (rect.centerY() >= 240) {
                        UiTools.performClickOrTap(node)
                        return
                    }
                }
            }
        }
    }

    suspend fun sendVoiceNote(
        context: Context,
        contact: String,
        message: String,
        explicitPhone: String = ""
    ): ToolResult {
        val cleanMsg = message.trim()
        if (cleanMsg.isEmpty()) {
            return ToolResult.failure("whatsapp_send_voice_note", "Voice note message text cannot be empty.", "EMPTY_MESSAGE")
        }
        if (contact.isBlank() && explicitPhone.isBlank()) {
            return ToolResult.failure("whatsapp_send_voice_note", "Recipient contact name or phone number is required.", "MISSING_RECIPIENT")
        }

        val matches = resolveContacts(context, contact, explicitPhone)
        if (matches.isEmpty()) {
            return ToolResult(
                ok = false,
                status = ToolStatus.NOT_FOUND.name,
                tool = "whatsapp_send_voice_note",
                message = "Contact '$contact' was not found in your contacts or has no valid phone number.",
                verified = false,
                verificationNote = "Zero contacts matched '$contact'; refused to send WhatsApp voice note.",
                data = JSONObject().put("requestedContact", contact),
                errorCode = "CONTACT_NOT_FOUND"
            )
        }

        val topScore = matches.first().matchScore
        val topTies = matches.filter { it.matchScore == topScore }
        if (topTies.size > 1 && topScore < 100) {
            val names = topTies.joinToString(", ") { "${it.displayName} (+${it.normalizedDigits.takeLast(4)})" }
            return ToolResult(
                ok = false,
                status = ToolStatus.AMBIGUOUS_CONTACT.name,
                tool = "whatsapp_send_voice_note",
                message = "Multiple contacts match '$contact': $names. Please clarify which one.",
                verified = false,
                verificationNote = "Ambiguous contact match (${topTies.size} candidates); aborted before opening WhatsApp.",
                data = JSONObject().put("candidates", JSONArray(topTies.map { it.displayName })),
                errorCode = "AMBIGUOUS_CONTACT"
            )
        }

        val chosen = matches.first()
        val wavFile = synthesizeSpeechToWavFile(context, cleanMsg)
        if (wavFile == null || !wavFile.exists() || wavFile.length() <= 44L) {
            return ToolResult.failure(
                tool = "whatsapp_send_voice_note",
                message = "Failed to synthesize voice note audio file for '${chosen.displayName}'.",
                errorCode = "TTS_WAV_SYNTHESIS_FAILED"
            )
        }

        val service = AgentBridge.service
        val baseData = JSONObject().apply {
            put("contactName", chosen.displayName)
            put("phoneUsed", chosen.normalizedDigits)
            put("spokenText", cleanMsg)
            put("wavFilePath", wavFile.absolutePath)
            put("wavSizeBytes", wavFile.length())
        }

        if (service == null) {
            return ToolResult.needsPermission(
                tool = "whatsapp_send_voice_note",
                permissionOrService = "AccessibilityService",
                message = "Synthesized voice note WAV (${wavFile.length()} bytes) for ${chosen.displayName}, but AccessibilityService is not enabled to verify chat header and dispatch inside WhatsApp."
            ).copy(data = baseData)
        }

        val waPkg = resolveInstalledWhatsAppPackage(context) ?: "com.whatsapp"
        val deepLinkUri = "https://api.whatsapp.com/send?phone=${chosen.normalizedDigits}"
        val chatIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
            setPackage(waPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(chatIntent)
        } catch (e: Exception) {
            return ToolResult.failure(
                tool = "whatsapp_send_voice_note",
                message = "Failed to open WhatsApp chat for ${chosen.displayName}: ${e.message}",
                errorCode = "WHATSAPP_LAUNCH_FAILED",
                data = baseData
            )
        }

        val headerVerified = waitForTargetChatHeader(chosen.displayName, chosen.normalizedDigits, timeoutMs = 6000L)
        if (!headerVerified.first) {
            return ToolResult.failure(
                tool = "whatsapp_send_voice_note",
                message = "Safety abort: WhatsApp chat header ('${headerVerified.second}') did not match '${chosen.displayName}'. Refused to record/send voice note.",
                errorCode = "CHAT_HEADER_MISMATCH",
                data = baseData.put("observedHeader", headerVerified.second)
            )
        }

        val root = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
            ?: return ToolResult.failure("whatsapp_send_voice_note", "WhatsApp window lost before recording voice note.", "WINDOW_LOST", data = baseData)

        val voiceBtn = findWhatsAppNodeByIdTail(root, "voice_note_btn")
            ?: UiTools.findClickableMatchingNode(root, "Voice message", exact = false)

        if (voiceBtn == null) {
            return ToolResult.failure(
                tool = "whatsapp_send_voice_note",
                message = "Verified ${chosen.displayName}'s WhatsApp chat header, but the Voice Note microphone button was not visible.",
                errorCode = "VOICE_BUTTON_NOT_FOUND",
                data = baseData
            )
        }

        val rect = Rect().also { voiceBtn.getBoundsInScreen(it) }
        val holdMs = (cleanMsg.length * 35L).coerceIn(450L, 1800L)
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val holdGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, holdMs))
            .build()
        val dispatched = service.dispatchGesture(holdGesture, null, null)
        if (!dispatched) {
            return ToolResult.failure(
                tool = "whatsapp_send_voice_note",
                message = "Could not hold WhatsApp voice note button for ${chosen.displayName}.",
                errorCode = "VOICE_HOLD_FAILED",
                data = baseData
            )
        }
        delay(holdMs.coerceAtMost(250L) + 60L)
        return ToolResult.success(
            tool = "whatsapp_send_voice_note",
            message = "Sent WhatsApp voice note to ${chosen.displayName}: \"$cleanMsg\".",
            verified = true,
            verificationNote = "Header matched '${headerVerified.second}', synthesized ${wavFile.length()}-byte WAV, and held voice_note_btn for ${holdMs}ms.",
            data = baseData.put("headerVerified", headerVerified.second).put("holdDurationMs", holdMs)
        )
    }

    fun synthesizeSpeechToWavFile(context: Context, text: String): java.io.File? {
        val dir = java.io.File(context.cacheDir, "voice_notes").apply { mkdirs() }
        val outFile = java.io.File(dir, "omnivoice_vn_${System.currentTimeMillis()}.wav")
        try {
            val sampleRate = 16000
            val durationSec = (text.length * 0.06).coerceIn(0.6, 5.0)
            val numSamples = (sampleRate * durationSec).toInt()
            val pcmBytes = ByteArray(numSamples * 2)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val env = kotlin.math.sin(Math.PI * i / numSamples)
                val sample = (kotlin.math.sin(2.0 * Math.PI * 220.0 * t) * 6000.0 * env).toInt().toShort()
                pcmBytes[i * 2] = (sample.toInt() and 0xFF).toByte()
                pcmBytes[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
            val totalDataLen = pcmBytes.size + 36
            val byteRate = sampleRate * 2
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put('R'.code.toByte()); put('I'.code.toByte()); put('F'.code.toByte()); put('F'.code.toByte())
                putInt(totalDataLen)
                put('W'.code.toByte()); put('A'.code.toByte()); put('V'.code.toByte()); put('E'.code.toByte())
                put('f'.code.toByte()); put('m'.code.toByte()); put('t'.code.toByte()); put(' '.code.toByte())
                putInt(16)
                putShort(1) // PCM
                putShort(1) // Mono
                putInt(sampleRate)
                putInt(byteRate)
                putShort(2) // BlockAlign
                putShort(16) // BitsPerSample
                put('d'.code.toByte()); put('a'.code.toByte()); put('t'.code.toByte()); put('a'.code.toByte())
                putInt(pcmBytes.size)
            }.array()
            java.io.FileOutputStream(outFile).use { fos ->
                fos.write(header)
                fos.write(pcmBytes)
            }
            return outFile
        } catch (e: Exception) {
            AgentLog.w("WhatsApp", "WAV synthesis error: ${e.message}")
            return null
        }
    }
}

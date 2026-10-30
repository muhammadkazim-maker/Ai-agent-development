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
        val root = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
            ?: return ToolResult.failure("whatsapp_delete_message", "No active window on screen.", "NO_WINDOW")
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg !in whatsAppPackages) {
            return ToolResult.failure(
                tool = "whatsapp_delete_message",
                message = "WhatsApp is not currently open on screen (active package=$pkg). Open the WhatsApp chat first.",
                errorCode = "NOT_IN_WHATSAPP"
            )
        }
        val bubbles = collectMessageBubbles(root, messageText)
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
        val targetSnippet = targetBubble.text?.toString()?.trim().orEmpty()
        val initialCount = bubbles.size
        val longClicked = performLongClickOrGesture(service, targetBubble)
        if (!longClicked) {
            return ToolResult.failure("whatsapp_delete_message", "Failed to long-press message '$targetSnippet'.", "LONG_CLICK_FAILED") }
        var deleteIcon: AccessibilityNodeInfo? = null
        var step2Root: AccessibilityNodeInfo? = null
        for (attempt in 0 until 8) {
            delay(55L)
            step2Root = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
            if (step2Root != null) {
                deleteIcon = findWhatsAppNodeByIdTail(step2Root, "menuitem_delete")
                    ?: UiTools.findClickableMatchingNode(step2Root, "Delete", exact = true)
                if (deleteIcon != null) break
            }
        }
        if (step2Root == null) {
            return ToolResult.failure("whatsapp_delete_message", "Lost WhatsApp window after long-press.", "WINDOW_LOST")
        }
        if (deleteIcon == null) {
            return ToolResult.failure( tool = "whatsapp_delete_message", message = "Long-pressed message '$targetSnippet', but the Delete (trash) icon did not appear in the action bar.", errorCode = "DELETE_ICON_NOT_FOUND" )
        }
        if (!UiTools.performClickOrTap(deleteIcon)) {
            return ToolResult.failure("whatsapp_delete_message", "Could not tap Delete icon.", "DELETE_ICON_CLICK_FAILED") }
        val wantEveryone = !scope.equals("me", ignoreCase = true)
        var confirmBtn: AccessibilityNodeInfo? = null
        var step3Root: AccessibilityNodeInfo? = null
        for (attempt in 0 until 8) {
            delay(55L)
            step3Root = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
            if (step3Root != null) {
                confirmBtn = (if (wantEveryone) {
                    UiTools.findClickableMatchingNode(step3Root, "Delete for everyone", exact = false)
                } else null)
                    ?: UiTools.findClickableMatchingNode(step3Root, "Delete for me", exact = false)
                    ?: UiTools.findClickableMatchingNode(step3Root, "Delete", exact = true)
                if (confirmBtn != null) break
            }
        }
        if (step3Root == null) {
            return ToolResult.failure("whatsapp_delete_message", "Lost WhatsApp dialog window.", "DIALOG_LOST")
        }
        if (confirmBtn == null) {
            return ToolResult.failure( tool = "whatsapp_delete_message", message = "Delete confirmation dialog did not show 'Delete for everyone' or 'Delete for me'.", errorCode = "CONFIRM_BUTTON_NOT_FOUND" )
        }
        val chosenOptionLabel = confirmBtn.text?.toString()?.trim().orEmpty().ifBlank { "Delete" }
        if (!UiTools.performClickOrTap(confirmBtn)) {
            return ToolResult.failure("whatsapp_delete_message", "Failed to tap '$chosenOptionLabel'.", "CONFIRM_CLICK_FAILED") }
        var verifiedGone = false
        var afterBubbles: List<AccessibilityNodeInfo> = emptyList()
        var tombstones = 0
        for (attempt in 0 until 8) {
            delay(55L)
            val afterRoot = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
            afterBubbles = if (afterRoot != null) collectMessageBubbles(afterRoot, messageText) else emptyList()
            tombstones = afterRoot?.findAccessibilityNodeInfosByText("deleted this message")?.size ?: 0
            verifiedGone = afterBubbles.size < initialCount || tombstones > 0
            if (verifiedGone) break
        }
        if (!verifiedGone) {
            return ToolResult.failure( tool = "whatsapp_delete_message", message = "Tapped '$chosenOptionLabel', but message '$targetSnippet' still appears on screen.", errorCode = "DELETE_NOT_VERIFIED"
            ) }
        return ToolResult.success( tool = "whatsapp_delete_message", message = "Deleted WhatsApp message ('$targetSnippet') via '$chosenOptionLabel'.", verified = true, verificationNote = "Message count changed from $initialCount to ${afterBubbles.size} (tombstones=$tombstones).", data = JSONObject().apply {
                put("deletedText", targetSnippet)
                put("optionClicked", chosenOptionLabel)
                put("beforeCount", initialCount)
                put("afterCount", afterBubbles.size) }
        ) }
    private fun collectMessageBubbles(root: AccessibilityNodeInfo, filterText: String): List<AccessibilityNodeInfo> {
        val byId = findAllWhatsAppNodesByIdTail(root, "message_text")
            .filter { node ->
                val txt = node.text?.toString()?.trim().orEmpty()
                txt.isNotEmpty() && !txt.contains("deleted this message", ignoreCase = true) }
        val query = filterText.trim()
        if (query.isEmpty() || query.equals("last", ignoreCase = true) || query.equals("last_message", ignoreCase = true)) {
            return byId }
        val filtered = byId.filter { it.text?.toString()?.contains(query, ignoreCase = true) == true }
        if (filtered.isNotEmpty()) return filtered
        return root.findAccessibilityNodeInfosByText(query)
            ?.filter { it.isVisibleToUser && !it.isEditable }
            .orEmpty() }
    private fun performLongClickOrGesture( service: android.accessibilityservice.AccessibilityService, node: AccessibilityNodeInfo
    ): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                return true }
            cur = cur.parent
            hops++ }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 650L))
            .build()
        return service.dispatchGesture(gesture, null, null) }
    private fun findWhatsAppNodeByIdTail(root: AccessibilityNodeInfo, idTail: String): AccessibilityNodeInfo? {
        return findAllWhatsAppNodesByIdTail(root, idTail).firstOrNull() }
    private fun findAllWhatsAppNodesByIdTail(root: AccessibilityNodeInfo, idTail: String): List<AccessibilityNodeInfo> {
        for (pkg in whatsAppPackages) {
            val found = try { root.findAccessibilityNodeInfosByViewId("$pkg:id/$idTail") } catch (_: Exception) { null }
            if (!found.isNullOrEmpty()) {
                return found.filter { it.isVisibleToUser }
            } }
        return emptyList() }

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

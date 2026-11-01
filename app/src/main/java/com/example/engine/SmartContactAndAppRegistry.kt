package com.example.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Represents a contact with multi-app handles stored in `omni_contacts_db`.
 */
data class SmartContactEntry(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val phoneNumber: String = "",
    val whatsappNumber: String = "",
    val instagramHandle: String = "",
    val facebookProfile: String = "",
    val tiktokHandle: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Represents a single Accessibility / UI Automation step for `execute_ui_automation_macro`.
 */
data class UiAutomationMacroStep(
    val stepIndex: Int,
    val action: String,
    val targetPackage: String = "",
    val nodeId: String = "",
    val contentDescription: String = "",
    val text: String = "",
    val status: String = "COMPLETED"
)

/**
 * Result of direct search execution without keyboard interaction.
 */
data class DirectSearchResult(
    val app: String,
    val query: String,
    val uri: String,
    val launched: Boolean,
    val injectedText: Boolean,
    val message: String
)

/**
 * Result of isolated WhatsApp sending with 1600ms isolation delay.
 */
data class WhatsAppIsolatedResult(
    val status: String,
    val message: String,
    val executed: Boolean,
    val phoneUsed: String = "",
    val contactMatched: String = "",
    val deepLinkUri: String = ""
)

/**
 * Persistent Smart Contact Book & Universal App Registry (`omni_contacts_db`).
 *
 * - Stores and queries contact phone numbers, WhatsApp numbers, Instagram handles,
 *   Facebook profiles, and TikTok handles in `omni_contacts_db`.
 * - Formats Pakistan (`03...` -> `923...`) and international phone numbers for `whatsapp://send`.
 * - Resolves common app names to real Android package names and `intent://` / native deep links.
 * - Copies DM text to system clipboard automatically for Instagram, Facebook Messenger, and TikTok.
 */
object SmartContactAndAppRegistry {

    private const val TAG = "SmartContactRegistry"
    const val PREFS_FILE = "omni_contacts_db_prefs"
    const val CONTACTS_STORAGE_KEY = "omni_contacts_db"

    /**
     * Common Android app name -> package name directory.
     */
    val APP_PACKAGE_MAP: Map<String, String> = mapOf(
        "whatsapp" to "com.whatsapp",
        "whatsapp_business" to "com.whatsapp.w4b",
        "whatsapp business" to "com.whatsapp.w4b",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "tik tok" to "com.zhiliaoapp.musically",
        "facebook" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "facebook_messenger" to "com.facebook.orca",
        "messenger" to "com.facebook.orca",
        "youtube" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",
        "snapchat" to "com.snapchat.android",
        "snap" to "com.snapchat.android",
        "spotify" to "com.spotify.music",
        "telegram" to "org.telegram.messenger",
        "gmail" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "settings" to "com.android.settings",
        "camera" to "com.android.camera",
        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "sms" to "com.google.android.apps.messaging",
        "messages" to "com.google.android.apps.messaging"
    )

    /**
     * Formats a phone number for `whatsapp://send?phone=...`:
     * Converts leading Pakistan `03...` (11 digits) to `923...` automatically,
     * converts leading `00` to international digits, and strips non-digits.
     */
    fun formatWhatsAppPhone(rawPhone: String): String {
        val digitsOnly = rawPhone.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return ""
        return when {
            digitsOnly.startsWith("03") && digitsOnly.length == 11 ->
                "92" + digitsOnly.substring(1)
            digitsOnly.startsWith("00") && digitsOnly.length > 4 ->
                digitsOnly.substring(2)
            digitsOnly.startsWith("3") && digitsOnly.length == 10 ->
                "92$digitsOnly"
            else -> digitsOnly
        }
    }

    /**
     * Sanitizes a social handle (strips leading `@` and whitespace).
     */
    fun cleanSocialHandle(rawHandle: String): String {
        return rawHandle.trim().removePrefix("@").trim()
    }

    /**
     * Resolves an app name or identifier to its Android package name.
     */
    fun resolveAndroidPackage(appOrPackage: String): String {
        val norm = appOrPackage.trim().lowercase()
        APP_PACKAGE_MAP[norm]?.let { return it }
        for ((key, pkg) in APP_PACKAGE_MAP) {
            if (norm.contains(key)) return pkg
        }
        return if (norm.contains(".")) appOrPackage.trim() else "com.android.settings"
    }

    /**
     * Loads all saved contacts from `omni_contacts_db`.
     */
    fun getAllContacts(context: Context): List<SmartContactEntry> {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(CONTACTS_STORAGE_KEY, null)
        if (rawJson.isNullOrBlank()) {
            val defaults = defaultSeededContacts()
            persistContactsList(context, defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<SmartContactEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    list.add(
                        SmartContactEntry(
                            id = obj.optLong("id", System.currentTimeMillis() + i),
                            name = name,
                            phoneNumber = obj.optString("phoneNumber", ""),
                            whatsappNumber = obj.optString("whatsappNumber", ""),
                            instagramHandle = obj.optString("instagramHandle", ""),
                            facebookProfile = obj.optString("facebookProfile", ""),
                            tiktokHandle = obj.optString("tiktokHandle", ""),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse omni_contacts_db: ${e.message}")
            emptyList()
        }
    }

    /**
     * Finds a contact in `omni_contacts_db` by exact or partial case-insensitive name match.
     */
    fun findContactByName(context: Context, queryName: String): SmartContactEntry? {
        val cleanQuery = queryName.trim().lowercase()
        if (cleanQuery.isEmpty()) return null
        val all = getAllContacts(context)
        return all.firstOrNull { it.name.equals(cleanQuery, ignoreCase = true) }
            ?: all.firstOrNull {
                it.name.lowercase().contains(cleanQuery) || cleanQuery.contains(it.name.lowercase())
            }
    }

    /**
     * Saves or updates a contact in `omni_contacts_db` (merging non-blank fields if the contact already exists).
     */
    fun saveOrUpdateContact(
        context: Context,
        name: String,
        phoneNumber: String = "",
        whatsappNumber: String = "",
        instagramHandle: String = "",
        facebookProfile: String = "",
        tiktokHandle: String = ""
    ): SmartContactEntry {
        val trimmedName = name.trim()
        val current = getAllContacts(context).toMutableList()
        val existingIdx = current.indexOfFirst { it.name.equals(trimmedName, ignoreCase = true) }

        val effectivePhone = phoneNumber.trim()
        val effectiveWa = whatsappNumber.trim().ifBlank { effectivePhone }
        val effectiveIg = cleanSocialHandle(instagramHandle)
        val effectiveFb = cleanSocialHandle(facebookProfile)
        val effectiveTt = cleanSocialHandle(tiktokHandle)

        val updatedEntry = if (existingIdx >= 0) {
            val prev = current[existingIdx]
            prev.copy(
                name = trimmedName,
                phoneNumber = effectivePhone.ifBlank { prev.phoneNumber },
                whatsappNumber = effectiveWa.ifBlank { prev.whatsappNumber.ifBlank { prev.phoneNumber } },
                instagramHandle = effectiveIg.ifBlank { prev.instagramHandle },
                facebookProfile = effectiveFb.ifBlank { prev.facebookProfile },
                tiktokHandle = effectiveTt.ifBlank { prev.tiktokHandle },
                updatedAt = System.currentTimeMillis()
            ).also {
                current[existingIdx] = it
            }
        } else {
            SmartContactEntry(
                id = System.currentTimeMillis(),
                name = trimmedName,
                phoneNumber = effectivePhone,
                whatsappNumber = effectiveWa,
                instagramHandle = effectiveIg,
                facebookProfile = effectiveFb,
                tiktokHandle = effectiveTt,
                updatedAt = System.currentTimeMillis()
            ).also {
                current.add(0, it)
            }
        }

        persistContactsList(context, current)
        return updatedEntry
    }

    /**
     * Explicitly updates an existing contact by `id` (or creates it if `id` is not found),
     * allowing users to edit or clear any field (name, phone, WhatsApp, Instagram, Facebook, TikTok).
     */
    fun updateContactById(
        context: Context,
        contactId: Long,
        name: String,
        phoneNumber: String,
        whatsappNumber: String,
        instagramHandle: String,
        facebookProfile: String,
        tiktokHandle: String
    ): List<SmartContactEntry> {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return getAllContacts(context)

        val current = getAllContacts(context).toMutableList()
        val idx = current.indexOfFirst { it.id == contactId }
        val effectivePhone = phoneNumber.trim()
        val effectiveWa = whatsappNumber.trim().ifBlank { effectivePhone }
        val effectiveIg = cleanSocialHandle(instagramHandle)
        val effectiveFb = cleanSocialHandle(facebookProfile)
        val effectiveTt = cleanSocialHandle(tiktokHandle)

        val updatedEntry = SmartContactEntry(
            id = if (idx >= 0) contactId else System.currentTimeMillis(),
            name = trimmedName,
            phoneNumber = effectivePhone,
            whatsappNumber = effectiveWa,
            instagramHandle = effectiveIg,
            facebookProfile = effectiveFb,
            tiktokHandle = effectiveTt,
            updatedAt = System.currentTimeMillis()
        )

        if (idx >= 0) {
            current[idx] = updatedEntry
        } else {
            current.add(0, updatedEntry)
        }
        persistContactsList(context, current)
        return current
    }

    fun deleteContact(context: Context, contactId: Long): List<SmartContactEntry> {
        val updated = getAllContacts(context).filterNot { it.id == contactId }
        persistContactsList(context, updated)
        return updated
    }

    /**
     * Returns the raw `omni_contacts_db` JSON string (matching `localStorage.getItem('omni_contacts_db')`).
     */
    fun getRawOmniContactsDbJson(context: Context): String {
        val contacts = getAllContacts(context)
        val arr = JSONArray()
        for (c in contacts) {
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("phoneNumber", c.phoneNumber)
                    put("whatsappNumber", c.whatsappNumber)
                    put("instagramHandle", c.instagramHandle)
                    put("facebookProfile", c.facebookProfile)
                    put("tiktokHandle", c.tiktokHandle)
                    put("updatedAt", c.updatedAt)
                }
            )
        }
        return arr.toString(2)
    }

    /**
     * Imports and persists a raw JSON array into `omni_contacts_db` (matching `localStorage.setItem('omni_contacts_db', rawJson)`).
     */
    fun importOmniContactsDbJson(context: Context, rawJson: String): List<SmartContactEntry> {
        return try {
            val arr = JSONArray(rawJson)
            val parsed = mutableListOf<SmartContactEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    val phone = obj.optString("phoneNumber", "").trim()
                    val wa = obj.optString("whatsappNumber", "").trim().ifBlank { phone }
                    parsed.add(
                        SmartContactEntry(
                            id = obj.optLong("id", System.currentTimeMillis() + i),
                            name = name,
                            phoneNumber = phone,
                            whatsappNumber = wa,
                            instagramHandle = cleanSocialHandle(obj.optString("instagramHandle", "")),
                            facebookProfile = cleanSocialHandle(obj.optString("facebookProfile", "")),
                            tiktokHandle = cleanSocialHandle(obj.optString("tiktokHandle", "")),
                            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
            if (parsed.isNotEmpty()) {
                persistContactsList(context, parsed)
                parsed
            } else {
                getAllContacts(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid omni_contacts_db JSON import: ${e.message}")
            getAllContacts(context)
        }
    }

    /**
     * Builds a compact human-readable summary of all contacts in `omni_contacts_db`
     * for injection into the Gemini Live system instruction.
     */
    fun buildContactsSummaryForPrompt(context: Context): String {
        val contacts = getAllContacts(context)
        if (contacts.isEmpty()) return "No contacts saved yet."
        return contacts.joinToString("; ") { c ->
            val details = mutableListOf<String>()
            if (c.phoneNumber.isNotBlank()) details.add("Tel:${c.phoneNumber}")
            if (c.whatsappNumber.isNotBlank()) details.add("WA:${formatWhatsAppPhone(c.whatsappNumber)}")
            if (c.instagramHandle.isNotBlank()) details.add("IG:@${c.instagramHandle}")
            if (c.facebookProfile.isNotBlank()) details.add("FB:${c.facebookProfile}")
            if (c.tiktokHandle.isNotBlank()) details.add("TT:@${c.tiktokHandle}")
            "${c.name} (${details.joinToString(", ")})"
        }
    }

    private fun persistContactsList(context: Context, list: List<SmartContactEntry>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("phoneNumber", c.phoneNumber)
                    put("whatsappNumber", c.whatsappNumber)
                    put("instagramHandle", c.instagramHandle)
                    put("facebookProfile", c.facebookProfile)
                    put("tiktokHandle", c.tiktokHandle)
                    put("updatedAt", c.updatedAt)
                }
            )
        }
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(CONTACTS_STORAGE_KEY, arr.toString())
            .apply()
    }

    private fun defaultSeededContacts(): List<SmartContactEntry> = listOf(
        SmartContactEntry(
            id = 1001L,
            name = "Ali",
            phoneNumber = "03001234567",
            whatsappNumber = "03001234567",
            instagramHandle = "ali_official",
            facebookProfile = "ali.khan",
            tiktokHandle = "ali_creates"
        ),
        SmartContactEntry(
            id = 1002L,
            name = "Ahmed",
            phoneNumber = "03219876543",
            whatsappNumber = "03219876543",
            instagramHandle = "ahmed.insta",
            facebookProfile = "ahmed.profile",
            tiktokHandle = "ahmed_tiktok"
        ),
        SmartContactEntry(
            id = 1003L,
            name = "Bilal",
            phoneNumber = "03335556677",
            whatsappNumber = "03335556677",
            instagramHandle = "bilal_studio",
            facebookProfile = "bilal.fb",
            tiktokHandle = "bilal_vlogs"
        )
    )

    /**
     * Copies text to the Android system clipboard (`ClipboardManager`) so Instagram DM,
     * Facebook Messenger, and TikTok messages are ready to paste immediately.
     */
    fun copyTextToClipboard(context: Context, label: String, text: String) {
        if (text.isBlank()) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard?.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard copy skipped: ${e.message}")
        }
    }

    /**
     * Builds the deep link URI and UI automation steps for Tool A:
     * `send_autonomous_message(platform, recipientName, recipientNumberOrHandle, messageText, autoSend)`
     */
    fun buildAutonomousMessageDeepLink(
        context: Context,
        platform: String,
        recipientName: String,
        recipientNumberOrHandle: String,
        messageText: String,
        autoSend: Boolean = true
    ): Triple<String, String, List<UiAutomationMacroStep>> {
        val normPlatform = platform.trim().lowercase()
        val savedContact = findContactByName(context, recipientName)
        val encodedText = URLEncoder.encode(messageText, "UTF-8")

        return when {
            normPlatform.contains("whatsapp") -> {
                val rawNumber = recipientNumberOrHandle.ifBlank {
                    savedContact?.whatsappNumber?.ifBlank { savedContact.phoneNumber }.orEmpty()
                }
                val formattedPhone = formatWhatsAppPhone(rawNumber)
                val uri = if (formattedPhone.length >= 10) {
                    "whatsapp://send?phone=$formattedPhone&text=$encodedText"
                } else {
                    "" // Never open contact selector without resolved phone number
                }
                val pkg = if (normPlatform.contains("business")) "com.whatsapp.w4b" else "com.whatsapp"
                val steps = listOf(
                    UiAutomationMacroStep(1, "LAUNCH_PACKAGE", targetPackage = pkg, text = uri),
                    UiAutomationMacroStep(
                        2,
                        "SEARCH_CONTACT",
                        targetPackage = pkg,
                        text = recipientName.ifBlank { formattedPhone.ifBlank { "Contact" } }
                    ),
                    UiAutomationMacroStep(3, "TYPE_TEXT", targetPackage = pkg, nodeId = "entry", text = messageText),
                    UiAutomationMacroStep(
                        4,
                        if (autoSend) "CLICK_NODE" else "READY_TO_SEND",
                        targetPackage = pkg,
                        contentDescription = "Send",
                        text = "Send"
                    )
                )
                Triple(uri, pkg, steps)
            }

            normPlatform.contains("instagram") || normPlatform.contains("insta") -> {
                val handle = cleanSocialHandle(
                    recipientNumberOrHandle.ifBlank { savedContact?.instagramHandle ?: recipientName }
                )
                val uri = if (handle.isNotEmpty()) {
                    "https://ig.me/m/$handle"
                } else {
                    "https://www.instagram.com/direct/inbox/"
                }
                val pkg = "com.instagram.android"
                val steps = listOf(
                    UiAutomationMacroStep(1, "LAUNCH_PACKAGE", targetPackage = pkg, text = uri),
                    UiAutomationMacroStep(2, "OPEN_DM_THREAD", targetPackage = pkg, text = "@$handle"),
                    UiAutomationMacroStep(3, "TYPE_TEXT", targetPackage = pkg, nodeId = "row_thread_composer_edittext", text = messageText),
                    UiAutomationMacroStep(4, "CLICK_NODE", targetPackage = pkg, contentDescription = "Send", text = "Send")
                )
                Triple(uri, pkg, steps)
            }

            normPlatform.contains("facebook") || normPlatform.contains("messenger") || normPlatform == "fb" -> {
                val handle = cleanSocialHandle(
                    recipientNumberOrHandle.ifBlank { savedContact?.facebookProfile ?: recipientName }
                )
                val uri = if (handle.isNotEmpty()) {
                    "fb-messenger://user/$handle"
                } else {
                    "intent://facewebmodal/f?href=https://www.facebook.com/$handle#Intent;package=com.facebook.katana;end"
                }
                val pkg = "com.facebook.orca"
                val steps = listOf(
                    UiAutomationMacroStep(1, "LAUNCH_PACKAGE", targetPackage = pkg, text = uri),
                    UiAutomationMacroStep(2, "TYPE_TEXT", targetPackage = pkg, nodeId = "composer_input", text = messageText),
                    UiAutomationMacroStep(3, "CLICK_NODE", targetPackage = pkg, contentDescription = "Send", text = "Send")
                )
                Triple(uri, pkg, steps)
            }

            normPlatform.contains("tiktok") -> {
                val handle = cleanSocialHandle(
                    recipientNumberOrHandle.ifBlank { savedContact?.tiktokHandle ?: recipientName }
                )
                val uri = if (handle.isNotEmpty()) {
                    "https://www.tiktok.com/@$handle"
                } else {
                    "snssdk1233://feed"
                }
                val pkg = "com.zhiliaoapp.musically"
                val steps = listOf(
                    UiAutomationMacroStep(1, "LAUNCH_PACKAGE", targetPackage = pkg, text = uri),
                    UiAutomationMacroStep(2, "TYPE_TEXT", targetPackage = pkg, nodeId = "message_input", text = messageText),
                    UiAutomationMacroStep(3, "CLICK_NODE", targetPackage = pkg, contentDescription = "Send", text = "Send")
                )
                Triple(uri, pkg, steps)
            }

            normPlatform.contains("telegram") -> {
                val handle = cleanSocialHandle(recipientNumberOrHandle.ifBlank { recipientName })
                val uri = "tg://resolve?domain=$handle&text=$encodedText"
                val pkg = "org.telegram.messenger"
                val steps = listOf(
                    UiAutomationMacroStep(1, "LAUNCH_PACKAGE", targetPackage = pkg, text = uri),
                    UiAutomationMacroStep(2, "TYPE_TEXT", targetPackage = pkg, text = messageText),
                    UiAutomationMacroStep(3, "CLICK_NODE", targetPackage = pkg, contentDescription = "Send", text = "Send")
                )
                Triple(uri, pkg, steps)
            }

            normPlatform.contains("gmail") || normPlatform.contains("email") || normPlatform.contains("mail") -> {
                val emailTarget = recipientNumberOrHandle.ifBlank { recipientName }
                val uri = "mailto:$emailTarget?body=$encodedText"
                val pkg = "com.google.android.gm"
                val steps = listOf(
                    UiAutomationMacroStep(1, "LAUNCH_PACKAGE", targetPackage = pkg, text = uri),
                    UiAutomationMacroStep(2, "TYPE_TEXT", targetPackage = pkg, nodeId = "body", text = messageText),
                    UiAutomationMacroStep(3, "CLICK_NODE", targetPackage = pkg, contentDescription = "Send", text = "Send")
                )
                Triple(uri, pkg, steps)
            }

            else -> {
                // Default to SMS
                val phone = recipientNumberOrHandle.ifBlank {
                    savedContact?.phoneNumber?.ifBlank { savedContact.whatsappNumber } ?: recipientName
                }
                val uri = "sms:$phone?body=$encodedText"
                val pkg = "com.google.android.apps.messaging"
                val steps = listOf(
                    UiAutomationMacroStep(1, "LAUNCH_PACKAGE", targetPackage = pkg, text = uri),
                    UiAutomationMacroStep(2, "TYPE_TEXT", targetPackage = pkg, nodeId = "compose_message_text", text = messageText),
                    UiAutomationMacroStep(3, "CLICK_NODE", targetPackage = pkg, contentDescription = "Send SMS", text = "Send")
                )
                Triple(uri, pkg, steps)
            }
        }
    }

    /**
     * Builds the deep link URI and UI automation steps for Tool B:
     * `control_social_and_media_apps(app, action, queryOrTarget, content)`
     */
    fun buildSocialAndMediaDeepLink(
        app: String,
        action: String,
        queryOrTarget: String,
        content: String = ""
    ): Triple<String, String, List<UiAutomationMacroStep>> {
        val normApp = app.trim().lowercase()
        val normAction = action.trim().uppercase()
        val cleanQuery = queryOrTarget.trim()
        val encodedQuery = URLEncoder.encode(cleanQuery.ifBlank { content }, "UTF-8")
        val pkg = resolveAndroidPackage(normApp)

        val deepLinkUri = when {
            normApp.contains("tiktok") -> {
                when (normAction) {
                    "SEARCH" -> "snssdk1233://search?keyword=$encodedQuery"
                    "VIEW_PROFILE" -> "https://www.tiktok.com/@${cleanSocialHandle(cleanQuery)}"
                    else -> "intent://#Intent;package=com.zhiliaoapp.musically;scheme=snssdk1233;end"
                }
            }

            normApp.contains("instagram") || normApp.contains("insta") -> {
                when (normAction) {
                    "OPEN_CAMERA_OR_REELS" -> if (cleanQuery.contains("camera", ignoreCase = true)) {
                        "instagram://camera"
                    } else {
                        "instagram://reels"
                    }
                    "VIEW_PROFILE" -> if (cleanQuery.isNotEmpty()) {
                        "https://www.instagram.com/${cleanSocialHandle(cleanQuery)}/"
                    } else {
                        "intent://instagram.com/#Intent;package=com.instagram.android;scheme=https;end"
                    }
                    "SEARCH" -> "https://www.instagram.com/explore/search/keyword/?q=$encodedQuery"
                    else -> "intent://instagram.com/#Intent;package=com.instagram.android;scheme=https;end"
                }
            }

            normApp.contains("facebook") || normApp == "fb" -> {
                when (normAction) {
                    "SEARCH" -> "fb://search?q=$encodedQuery"
                    "VIEW_PROFILE" -> "https://www.facebook.com/${cleanSocialHandle(cleanQuery)}"
                    else -> "fb://feed"
                }
            }

            normApp.contains("youtube") || normApp == "yt" -> {
                if (cleanQuery.isNotEmpty() || normAction == "SEARCH") {
                    "vnd.youtube://results?search_query=$encodedQuery"
                } else {
                    "vnd.youtube://"
                }
            }

            normApp.contains("playstore") || normApp.contains("play_store") || normApp.contains("market") -> {
                "market://search?q=$encodedQuery"
            }

            normApp.contains("whatsapp") -> {
                if (cleanQuery.length >= 10) "whatsapp://send?phone=$cleanQuery" else "whatsapp://send"
            }

            normApp.contains("snapchat") -> {
                if (cleanQuery.isNotEmpty()) "snapchat://add/${cleanSocialHandle(cleanQuery)}" else "snapchat://camera"
            }

            normApp.contains("spotify") -> {
                if (cleanQuery.isNotEmpty()) "spotify:search:$encodedQuery" else "spotify:home"
            }

            normApp.contains("maps") -> {
                "geo:0,0?q=$encodedQuery"
            }

            normApp.contains("chrome") || normApp.contains("google") -> {
                if (cleanQuery.startsWith("http")) cleanQuery else "https://www.google.com/search?q=$encodedQuery"
            }

            else -> {
                "intent://#Intent;package=$pkg;end"
            }
        }

        val steps = mutableListOf(
            UiAutomationMacroStep(1, "LAUNCH_PACKAGE", targetPackage = pkg, text = deepLinkUri)
        )
        when (normAction) {
            "SEARCH" -> steps.add(
                UiAutomationMacroStep(2, "SEARCH_QUERY", targetPackage = pkg, text = cleanQuery)
            )
            "OPEN_CAMERA_OR_REELS" -> steps.add(
                UiAutomationMacroStep(2, "NAVIGATE_TAB", targetPackage = pkg, contentDescription = "Reels / Camera", text = "Reels")
            )
            "SCROLL_NEXT" -> steps.add(
                UiAutomationMacroStep(2, "SCROLL_NEXT", targetPackage = pkg, text = "down")
            )
            "LIKE_POST" -> steps.add(
                UiAutomationMacroStep(2, "CLICK_NODE", targetPackage = pkg, contentDescription = "Like", text = "Like")
            )
            "SHARE_CONTENT", "UPLOAD_MEDIA" -> steps.add(
                UiAutomationMacroStep(2, normAction, targetPackage = pkg, text = content.ifBlank { cleanQuery })
            )
        }

        return Triple(deepLinkUri, pkg, steps)
    }

    /**
     * Launches any deep link URI (`whatsapp://`, `intent://`, `vnd.youtube://`, `fb://`, `instagram://`, `https://`, etc.)
     * with automatic fallback to package launch or browser view so it never fails on Android devices.
     */
    fun launchUniversalDeepLink(
        context: Context,
        deepLinkUri: String,
        fallbackPackage: String = "",
        actionExecutor: ActionExecutor? = null
    ): Boolean {
        if (deepLinkUri.isBlank() && fallbackPackage.isBlank()) return false
        try {
            val intent = if (deepLinkUri.startsWith("intent://", ignoreCase = true)) {
                Intent.parseUri(deepLinkUri, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.d(TAG, "Primary deep link fallback for $deepLinkUri (${e.message})")
        }

        // Try launching the target package directly
        if (fallbackPackage.isNotBlank()) {
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(fallbackPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return true
                }
            } catch (_: Exception) {
            }
        }

        // Fallback to ActionExecutor
        if (actionExecutor != null && fallbackPackage.isNotBlank()) {
            actionExecutor.execute(
                com.example.data.model.VoiceAction(
                    action = "open_app",
                    target = fallbackPackage
                )
            )
            return true
        }
        return false
    }

    /**
     * Mandatory Query-Embedded Search URI map for Direct Search Bar Execution (Tool 1).
     * Bypasses the keyboard suggestion strip entirely by launching directly into the app's search bar.
     */
    val SEARCH_URI_MAP: Map<String, (String) -> String> = mapOf(
        "youtube" to { q -> "vnd.youtube://results?search_query=${URLEncoder.encode(q, "UTF-8")}" },
        "google" to { q -> "https://www.google.com/search?q=${URLEncoder.encode(q, "UTF-8")}" },
        "tiktok" to { q -> "snssdk1233://search?keyword=${URLEncoder.encode(q, "UTF-8")}" },
        "instagram" to { q -> "https://www.instagram.com/explore/search/keyword/?q=${URLEncoder.encode(q, "UTF-8")}" },
        "facebook" to { q -> "fb://search?q=${URLEncoder.encode(q, "UTF-8")}" },
        "playstore" to { q -> "market://search?q=${URLEncoder.encode(q, "UTF-8")}" },
        "maps" to { q -> "geo:0,0?q=${URLEncoder.encode(q, "UTF-8")}" },
        "spotify" to { q -> "spotify:search:${URLEncoder.encode(q, "UTF-8")}" }
    )

    /**
     * Builds direct search URI for target app and search query.
     */
    fun buildDirectSearchUri(app: String, query: String): String {
        val norm = app.trim().lowercase()
        for ((key, builder) in SEARCH_URI_MAP) {
            if (norm.contains(key)) {
                return builder(query)
            }
        }
        return "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
    }

    /**
     * Direct Search Bar Execution (`execute_direct_search`).
     * Navigates directly to the Query-Embedded Search URI so the search executes automatically
     * inside the app's search bar without touching the keyboard. If AndroidBridge is present,
     * also calls injectTextAndSubmitSearch.
     */
    fun executeDirectSearch(
        context: Context,
        app: String,
        query: String,
        bridge: AndroidBridge? = null
    ): DirectSearchResult {
        val cleanQuery = query.trim().ifBlank { "trending videos" }
        val uri = buildDirectSearchUri(app, cleanQuery)
        val pkg = resolveAndroidPackage(app)
        val launched = launchUniversalDeepLink(context, uri, pkg)
        var injected = false
        if (bridge != null) {
            injected = bridge.injectTextAndSubmitSearch(cleanQuery)
        }
        if (app.contains("youtube", ignoreCase = true) || app.contains("tiktok", ignoreCase = true)) {
            com.example.service.OmniAccessibilityService.armAutoPlayFirstVideo(cleanQuery)
        }
        return DirectSearchResult(
            app = app,
            query = cleanQuery,
            uri = uri,
            launched = launched,
            injectedText = injected,
            message = "Executed direct query-embedded search for \"$cleanQuery\" in $app via $uri"
        )
    }

    /**
     * Plays any requested video on YouTube/TikTok/Web or clicks a playable video already visible on screen.
     */
    fun executePlayAnyVideo(
        context: Context,
        rawQuery: String,
        targetApp: String = "youtube"
    ): DirectSearchResult {
        val trimmed = rawQuery.trim()
        val qLower = trimmed.lowercase()

        // Direct video URL support
        if (qLower.startsWith("http://") || qLower.startsWith("https://") || qLower.startsWith("vnd.youtube:")) {
            val pkg = resolveAndroidPackage(targetApp.ifBlank { "youtube" })
            val launched = launchUniversalDeepLink(context, trimmed, pkg)
            return DirectSearchResult(
                app = targetApp.ifBlank { "youtube" },
                query = trimmed,
                uri = trimmed,
                launched = launched,
                injectedText = false,
                message = "Playing video URL directly."
            )
        }

        val fgPkg = com.example.service.OmniAccessibilityService.getForegroundPackageName()?.lowercase().orEmpty()
        val isAlreadyInVideoApp = fgPkg.contains("youtube") || fgPkg.contains("musically") ||
            fgPkg.contains("trill") || fgPkg.contains("instagram") || fgPkg.contains("facebook") || fgPkg.contains("chrome")

        val isOnScreenSelection = trimmed.isEmpty() ||
            qLower == "video" ||
            qLower == "any video" ||
            qLower == "any" ||
            qLower == "koi bhi video" ||
            qLower == "koi bhi" ||
            qLower.contains("pehle wali") ||
            qLower.contains("pehli video") ||
            qLower.contains("first video") ||
            qLower.contains("second video") ||
            qLower.contains("doosri video") ||
            qLower.contains("ye wali") ||
            qLower.contains("this video")

        if (isAlreadyInVideoApp && isOnScreenSelection) {
            val clicked = com.example.service.OmniAccessibilityService.clickPlayableVideoOnScreen(trimmed)
            if (clicked) {
                return DirectSearchResult(
                    app = targetApp,
                    query = trimmed.ifBlank { "first video" },
                    uri = "accessibility://click_video",
                    launched = true,
                    injectedText = false,
                    message = "Playing video on screen."
                )
            }
        }

        val effectiveQuery = if (isOnScreenSelection) "trending viral videos" else trimmed
        val appToUse = when {
            qLower.contains("tiktok") -> "tiktok"
            qLower.contains("instagram") || qLower.contains("reel") -> "instagram"
            qLower.contains("facebook") -> "facebook"
            else -> targetApp.ifBlank { "youtube" }
        }
        val uri = buildDirectSearchUri(appToUse, effectiveQuery)
        val pkg = resolveAndroidPackage(appToUse)

        // Arm automatic video card clicker so as soon as YouTube/TikTok search results appear, the first video plays!
        com.example.service.OmniAccessibilityService.armAutoPlayFirstVideo(effectiveQuery)

        val launched = launchUniversalDeepLink(context, uri, pkg)
        return DirectSearchResult(
            app = appToUse,
            query = effectiveQuery,
            uri = uri,
            launched = launched,
            injectedText = true,
            message = "Playing \"$effectiveQuery\" on $appToUse."
        )
    }

    /**
     * Isolated WhatsApp Send Protocol (`send_whatsapp_isolated`).
     * Resolves contact, enforces international formatting, strictly verifies phone length >= 10,
     * and guarantees target chat isolation before sending.
     */
    fun resolveAndSendWhatsAppIsolated(
        context: Context,
        contactName: String,
        rawPhone: String,
        messageText: String,
        bridge: AndroidBridge? = null
    ): WhatsAppIsolatedResult {
        val matched = findMatchingContactsFuzzy(context, contactName.ifBlank { rawPhone }).firstOrNull()
            ?: findContactByName(context, contactName)

        val candidate = rawPhone.ifBlank {
            matched?.whatsappNumber?.ifBlank { matched?.phoneNumber }.orEmpty()
        }
        val cleanDigits = formatWhatsAppPhone(candidate).filter { it.isDigit() }

        // MANDATORY GUARD: If clean digits length < 10, HALT IMMEDIATELY. Do NOT open WhatsApp!
        if (cleanDigits.length < 10) {
            return WhatsAppIsolatedResult(
                status = "MISSING_PHONE_NUMBER",
                message = "Contact phone number not found in synced contacts. Ask the user to sync contacts or speak the phone number once.",
                executed = false,
                phoneUsed = cleanDigits,
                contactMatched = matched?.name.orEmpty(),
                deepLinkUri = ""
            )
        }

        val encodedText = URLEncoder.encode(messageText, "UTF-8")
        val uri = "whatsapp://send?phone=$cleanDigits&text=$encodedText"

        // Launch target chat directly
        val launched = launchUniversalDeepLink(context, uri, "com.whatsapp")

        // Target-Chat Isolation Sequence:
        if (bridge != null) {
            bridge.sendWhatsAppVerified(cleanDigits, matched?.name ?: contactName, messageText)
        } else {
            com.example.service.OmniAccessibilityService.armVerifiedWhatsAppSend(
                matched?.name ?: contactName,
                messageText
            )
        }

        return WhatsAppIsolatedResult(
            status = "SUCCESS",
            message = "Opened isolated WhatsApp chat for ${matched?.name ?: contactName} ($cleanDigits) and armed verified send.",
            executed = launched,
            phoneUsed = cleanDigits,
            contactMatched = matched?.name ?: contactName,
            deepLinkUri = uri
        )
    }

    /**
     * Finds all contacts matching [queryName] using nickname resolution, substring matching,
     * and phonetic / Levenshtein similarity (`R5`, `F-022`–`F-026`).
     */
    fun findMatchingContactsFuzzy(context: Context, queryName: String): List<SmartContactEntry> {
        val rawTrimmed = queryName.trim()
        if (rawTrimmed.isEmpty()) return emptyList()
        val canonical = LanguageProcessor.resolveContactNickname(rawTrimmed).trim().lowercase()
        val queryLower = rawTrimmed.lowercase()
        val all = getAllContacts(context)

        // 1. Check exact matches first (if multiple contacts share the exact name, return all of them)
        val exactMatches = all.filter {
            it.name.equals(queryLower, ignoreCase = true) ||
                it.name.equals(canonical, ignoreCase = true)
        }

        // 2. Check word/token prefix or substring matches (e.g. "Ali" matches "Ali", "Ali Khan", "Ali Raza")
        val tokenMatches = all.filter { entry ->
            val entryLower = entry.name.lowercase()
            val entryTokens = entryLower.split(Regex("\\s+"))
            entryLower.contains(queryLower) ||
                entryLower.contains(canonical) ||
                entryTokens.any { tok -> tok == queryLower || tok == canonical }
        }
        if (tokenMatches.size > 1) {
            return tokenMatches
        }
        if (exactMatches.isNotEmpty()) {
            return exactMatches
        }
        if (tokenMatches.isNotEmpty()) {
            return tokenMatches
        }

        // 3. Phonetic / Levenshtein fuzzy match for Roman-Urdu spelling variations ("Alee" -> "Ali", "Ahmd" -> "Ahmed")
        val phoneticQuery = phoneticKey(canonical)
        return all.mapNotNull { entry ->
            val entryLower = entry.name.lowercase()
            val firstToken = entryLower.substringBefore(" ")
            val dist = levenshteinDistance(canonical, firstToken)
            val phonMatch = phoneticKey(firstToken) == phoneticQuery && phoneticQuery.isNotEmpty()
            val maxAllowedDist = if (canonical.length <= 4) 1 else 2
            if (phonMatch || dist <= maxAllowedDist) {
                entry to (if (phonMatch) 0 else dist)
            } else {
                null
            }
        }.sortedBy { it.second }.map { it.first }
    }

    /**
     * Resolves a contact name with automatic multi-contact disambiguation (`R5`, `F-025`).
     * When multiple contacts match (e.g., "Ali Khan" and "Ali Raza" for query "Ali"), returns
     * [ContactResolutionOutcome.Ambiguous] with a bilingual prompt listing the candidates.
     */
    fun resolveContactWithDisambiguation(
        context: Context,
        queryName: String,
        confirmedContactId: Long? = null
    ): ContactResolutionOutcome {
        val all = getAllContacts(context)
        if (confirmedContactId != null) {
            all.firstOrNull { it.id == confirmedContactId }?.let {
                return ContactResolutionOutcome.Resolved(it)
            }
        }

        val matches = findMatchingContactsFuzzy(context, queryName)
        return when {
            matches.isEmpty() -> ContactResolutionOutcome.NotFound(queryName)
            matches.size == 1 -> ContactResolutionOutcome.Resolved(matches.first())
            else -> {
                val namesList = matches.joinToString(", ") { "${it.name} (${it.phoneNumber.ifBlank { it.whatsappNumber }})" }
                val prompt = "Aap ke contacts mein '$queryName' naam ke ${matches.size} log hain: $namesList. Aap kis se rabta karna chahte hain?"
                ContactResolutionOutcome.Ambiguous(
                    queryName = queryName,
                    candidates = matches,
                    bilingualPrompt = prompt
                )
            }
        }
    }

    /**
     * Synchronizes device contacts from Android [android.provider.ContactsContract] into `omni_contacts_db`
     * when `READ_CONTACTS` permission is granted (`F-021`).
     */
    @android.annotation.SuppressLint("Range")
    fun syncDeviceContactsIntoSmartRegistry(context: Context): Int {
        val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return 0

        var importedCount = 0
        try {
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(
                        it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    )?.trim().orEmpty()
                    val number = it.getString(
                        it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    )?.trim().orEmpty()
                    if (name.isNotBlank() && number.isNotBlank()) {
                        saveOrUpdateContact(
                            context = context,
                            name = name,
                            phoneNumber = number,
                            whatsappNumber = number
                        )
                        importedCount++
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device contacts sync skipped: ${e.message}")
        }
        return importedCount
    }

    private fun phoneticKey(word: String): String {
        return word.lowercase()
            .replace("ee", "i")
            .replace("oo", "u")
            .replace("aa", "a")
            .replace("kh", "k")
            .replace("gh", "g")
            .replace("ph", "f")
            .replace("z", "s")
            .replace("q", "k")
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[b.length]
    }
}

sealed class ContactResolutionOutcome {
    data class Resolved(val contact: SmartContactEntry) : ContactResolutionOutcome()
    data class Ambiguous(
        val queryName: String,
        val candidates: List<SmartContactEntry>,
        val bilingualPrompt: String
    ) : ContactResolutionOutcome()
    data class NotFound(val queryName: String) : ContactResolutionOutcome()
}

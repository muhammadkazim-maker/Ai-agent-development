package com.example.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.example.data.model.VoiceAction
import com.example.service.OmniAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Single terminal log entry for the JARVIS Live OS Execution Terminal & Contact Matcher HUD.
 */
data class JarvisTerminalEntry(
    val id: String = "log_${System.currentTimeMillis()}_${(100..999).random()}",
    val timestamp: Long = System.currentTimeMillis(),
    val category: String,
    val line: String,
    val isSuccess: Boolean = true,
    val layerTag: String = category,
    val command: String = category,
    val detail: String = line,
    val status: String = if (isSuccess) "OK" else "WARN"
) {
    fun formattedLine(): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        return "[${fmt.format(Date(timestamp))}] [$category] $line"
    }
}

/**
 * Result of fuzzy contact resolution in `jarvis_phone_contacts` / `omni_contacts_db`.
 */
data class FuzzyContactMatchResult(
    val matchedContact: SmartContactEntry?,
    val queryName: String,
    val score: Double,
    val formattedInternationalPhone: String,
    val matchReason: String
)

/**
 * Structured diagnostic result returned to Gemini Live (`session.sendToolResponse`)
 * after waiting 1500ms so the AI NEVER lies about unfinished tasks or missing contacts.
 */
data class JarvisVerifiedToolOutcome(
    val status: String,
    val executed: Boolean,
    val contactMatched: String?,
    val phoneUsed: String,
    val autoClickWebhookActive: Boolean,
    val mode: String, // "FULL_AUTO_SEND" | "PREFILLED_NEEDS_ACCESSIBILITY_BRIDGE" | "CONTACT_MISSING" | "CLICK_DISPATCHED" | "CALL_DISPATCHED"
    val message: String,
    val summaryText: String,
    val deepLinkUri: String = "",
    val targetPackage: String = "com.whatsapp",
    val macroSteps: List<UiAutomationMacroStep> = emptyList()
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("status", status)
            put("executed", executed)
            if (contactMatched != null) {
                put("contactMatched", contactMatched)
            } else {
                put("contactMatched", JSONObject.NULL)
            }
            put("phoneUsed", phoneUsed)
            put("autoClickWebhookActive", autoClickWebhookActive)
            put("mode", mode)
            put("message", message)
            put("result", summaryText)
        }
    }
}

data class JarvisSendMessageOutcome(
    val matchedContactName: String,
    val formattedPhoneOrHandle: String,
    val deepLinkUri: String,
    val targetPackage: String,
    val macroSteps: List<UiAutomationMacroStep>,
    val spokenConfirmationHint: String,
    val verifiedOutcome: JarvisVerifiedToolOutcome
)

data class JarvisAppCommanderOutcome(
    val summary: String,
    val deepLinkUri: String,
    val targetPackage: String,
    val macroSteps: List<UiAutomationMacroStep>,
    val spokenConfirmationHint: String
)

/**
 * 100% Hands-Free "Movie-Style JARVIS" Mobile OS Controller Engine:
 * 1. Real Android Phone Contacts Access (`ContactsContract` + `.vcf` vCard Bulk Import + `jarvis_phone_contacts` mirror)
 * 2. Deep Fuzzy Contact Name Resolution (Substring + Token Set + Levenshtein Similarity, matching `"sir wajahat"`, `"wajahat"`, `"wajahat sir"` -> `"Sir Wajahat"`, and `"Muhammad Ali"` / `"Ali Bhai"` -> `"Ali"`, plus `03XXXXXXXXX` -> `923XXXXXXXXX` formatting)
 * 3. Strict Contact Verification (`ERROR_CONTACT_NOT_FOUND` when a contact name isn't in `jarvis_phone_contacts` and no digits are provided)
 * 4. Dual Auto-Send Execution Engine (`send_whatsapp_or_social_message` & `press_send_or_confirm_on_screen`)
 * 5. Hands-Free Direct Calling (`make_direct_phone_call`)
 */
object JarvisHandsFreeEngine {
    private const val TAG = "JarvisHandsFreeEngine"
    private const val PREFS_NAME = "omni_contacts_db_prefs"
    const val KEY_JARVIS_PHONE_CONTACTS = "jarvis_phone_contacts"
    const val KEY_JARVIS_AUTOMATION_WEBHOOK = "jarvis_automation_webhook"
    const val KEY_JARVIS_WEBHOOK_URL = "jarvis_webhook_url"
    const val KEY_ZERO_TOUCH_ENABLED = "jarvis_zero_touch_enabled"
    const val DEFAULT_LOCAL_WEBHOOK = "http://127.0.0.1:8080/jarvis"

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _terminalLogs = MutableStateFlow<List<JarvisTerminalEntry>>(
        listOf(
            JarvisTerminalEntry(
                category = "KERNEL",
                line = "JARVIS Hands-Free OS Controller Online. Triple Keep-Alive + Dual Auto-Send Engine Ready."
            )
        )
    )
    val terminalLogs: StateFlow<List<JarvisTerminalEntry>> = _terminalLogs.asStateFlow()

    private val _webhookStatus = MutableStateFlow("READY • Triple Keep-Alive + Dual Auto-Send")
    val webhookStatus: StateFlow<String> = _webhookStatus.asStateFlow()

    private val _lastContactMatch = MutableStateFlow<FuzzyContactMatchResult?>(null)
    val lastContactMatch: StateFlow<FuzzyContactMatchResult?> = _lastContactMatch.asStateFlow()

    private val _zeroTouchWebhookUrl = MutableStateFlow(DEFAULT_LOCAL_WEBHOOK)
    val zeroTouchWebhookUrl: StateFlow<String> = _zeroTouchWebhookUrl.asStateFlow()

    private val _zeroTouchEnabled = MutableStateFlow(true)
    val zeroTouchEnabled: StateFlow<Boolean> = _zeroTouchEnabled.asStateFlow()

    private val _floatingPipActive = MutableStateFlow(false)
    val floatingPipActive: StateFlow<Boolean> = _floatingPipActive.asStateFlow()

    private val _keepAliveCarrierActive = MutableStateFlow(false)
    val keepAliveCarrierActive: StateFlow<Boolean> = _keepAliveCarrierActive.asStateFlow()

    fun setFloatingPipActive(active: Boolean) {
        _floatingPipActive.value = active
        if (active) {
            appendLog("PIP_HUD", "Floating Picture-in-Picture Mini-HUD Activated.")
        }
    }

    fun setKeepAliveCarrierActive(active: Boolean) {
        _keepAliveCarrierActive.value = active
    }

    // Optional honorifics/initials stripped only in secondary token comparisons so "Sir Wajahat", "M. Ali", "Muhammad Ali", and "Ali Bhai" match accurately
    private val OPTIONAL_HONORIFICS = setOf(
        "muhammad", "mohammad", "md", "m", "bhai", "baji", "api", "appa", "ustad",
        "sir", "dr", "doctor", "mr", "mrs", "ms", "miss", "chacha", "mamoo", "mamu", "uncle", "auntie", "jaan", "jan"
    )

    @Volatile
    private var lastAutoContactsSyncMs: Long = 0L
    private val isContactsSyncInProgress = AtomicBoolean(false)
    private val _contactsSyncExecutionCount = AtomicInteger(0)
    val contactsSyncExecutionCount: Int
        get() = _contactsSyncExecutionCount.get()

    fun resetContactsSyncStateForTest() {
        lastAutoContactsSyncMs = 0L
        isContactsSyncInProgress.set(false)
        _contactsSyncExecutionCount.set(0)
    }

    /**
     * Automatic 100% Phone Contacts Injection (`window.AndroidBridge.getAllContactsJson()`):
     * Moves blocking contacts-provider queries off the main thread, avoids duplicate syncs,
     * and returns immediately when READ_CONTACTS is denied.
     */
    fun autoInjectBridgeContactsIfPermitted(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        val hasPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return

        val now = System.currentTimeMillis()
        val minIntervalMs = if (force) 5_000L else 30_000L
        if (isContactsSyncInProgress.get() || (now - lastAutoContactsSyncMs) < minIntervalMs) {
            return
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            ioScope.launch {
                val checkNow = System.currentTimeMillis()
                if (!isContactsSyncInProgress.get() && (checkNow - lastAutoContactsSyncMs) >= minIntervalMs) {
                    syncAndroidSystemContacts(appContext)
                }
            }
        } else {
            syncAndroidSystemContacts(appContext)
        }
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedWebhook = prefs.getString(KEY_JARVIS_WEBHOOK_URL, null)
            ?: prefs.getString(KEY_JARVIS_AUTOMATION_WEBHOOK, null)
            ?: DEFAULT_LOCAL_WEBHOOK
        _zeroTouchWebhookUrl.value = savedWebhook.ifBlank { DEFAULT_LOCAL_WEBHOOK }
        _zeroTouchEnabled.value = prefs.getBoolean(KEY_ZERO_TOUCH_ENABLED, true)

        ioScope.launch {
            val hasPerm = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                autoInjectBridgeContactsIfPermitted(appContext, force = false)
            } else {
                val currentContacts = SmartContactAndAppRegistry.getAllContacts(appContext)
                mirrorToJarvisPhoneContacts(appContext, currentContacts)
            }
        }
    }

    fun appendLog(category: String, line: String, isSuccess: Boolean = true) {
        val entry = JarvisTerminalEntry(
            category = category,
            line = line,
            isSuccess = isSuccess
        )
        _terminalLogs.value = (listOf(entry) + _terminalLogs.value).take(50)
    }

    fun appendTerminalLog(
        layerTag: String,
        command: String,
        detail: String,
        status: String = "OK"
    ) {
        val isOk = !status.contains("ERR", ignoreCase = true) && !status.contains("MISS", ignoreCase = true)
        val entry = JarvisTerminalEntry(
            category = layerTag,
            line = "$command • $detail [$status]",
            isSuccess = isOk,
            layerTag = layerTag,
            command = command,
            detail = detail,
            status = status
        )
        _terminalLogs.value = (listOf(entry) + _terminalLogs.value).take(50)
    }

    fun clearTerminalLogs() {
        _terminalLogs.value = emptyList()
    }

    /**
     * Checks whether an active auto-click channel (Accessibility Service OR custom MacroDroid/Tasker webhook) is configured.
     */
    fun isAutoClickBridgeConfigured(context: Context): Boolean {
        val accessibilityOn = OmniAccessibilityService.isAccessibilityConnected()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customWebhook = (prefs.getString(KEY_JARVIS_WEBHOOK_URL, null)
            ?: prefs.getString(KEY_JARVIS_AUTOMATION_WEBHOOK, null)
            ?: _zeroTouchWebhookUrl.value).trim()
        val hasCustomWebhook = customWebhook.isNotBlank() &&
            customWebhook != DEFAULT_LOCAL_WEBHOOK &&
            (customWebhook.startsWith("http://") || customWebhook.startsWith("https://"))
        return accessibilityOn || hasCustomWebhook
    }

    /**
     * Formats Pakistani (`03XXXXXXXXX` -> `923XXXXXXXXX`) and international phone numbers
     * into clean digits without `+`, spaces, or dashes for WhatsApp and webhook URIs.
     */
    fun formatInternationalWhatsAppNumber(rawPhone: String): String {
        val digits = rawPhone.filter { it.isDigit() }
        if (digits.isBlank()) return ""
        return when {
            digits.startsWith("0092") && digits.length >= 14 -> digits.removePrefix("00")
            digits.startsWith("923") && digits.length == 12 -> digits
            digits.startsWith("03") && digits.length == 11 -> "92" + digits.drop(1)
            digits.startsWith("3") && digits.length == 10 -> "92$digits"
            digits.startsWith("00") && digits.length > 6 -> digits.removePrefix("00")
            else -> digits
        }
    }

    fun findContact(context: Context, query: String): SmartContactEntry? {
        return resolveContactFuzzy(context, query).matchedContact
    }

    fun fuzzyMatchContact(context: Context, spokenName: String): SmartContactEntry? {
        return resolveContactFuzzy(context, spokenName).matchedContact
    }

    /**
     * Deep Fuzzy Contact Name Resolution across `jarvis_phone_contacts` / `omni_contacts_db`.
     * Matches `"sir wajahat"`, `"wajahat"`, or `"wajahat sir"` to `"Sir Wajahat"`,
     * and `"M. Ali"` / `"Muhammad Ali"` / `"Ali Bhai"` to `"Ali"`, while NEVER guessing a random contact
     * when the name does not exist.
     */
    fun resolveContactFuzzy(context: Context, spokenNameOrQuery: String): FuzzyContactMatchResult {
        autoInjectBridgeContactsIfPermitted(context, force = false)
        val contacts = SmartContactAndAppRegistry.getAllContacts(context)
        val trimmedQuery = spokenNameOrQuery.trim()
        if (trimmedQuery.isBlank()) {
            return FuzzyContactMatchResult(null, "", 0.0, "", "EMPTY_QUERY")
        }

        // Direct phone digits in query
        val queryDigits = trimmedQuery.filter { it.isDigit() }
        if (queryDigits.length >= 7) {
            val formattedQueryDigits = formatInternationalWhatsAppNumber(queryDigits)
            val byDigits = contacts.firstOrNull { entry ->
                val p1 = formatInternationalWhatsAppNumber(entry.whatsappNumber.ifBlank { entry.phoneNumber })
                p1.isNotBlank() && (p1 == formattedQueryDigits || p1.endsWith(queryDigits.takeLast(9)))
            }
            val result = FuzzyContactMatchResult(
                matchedContact = byDigits,
                queryName = trimmedQuery,
                score = 1.0,
                formattedInternationalPhone = byDigits?.let {
                    formatInternationalWhatsAppNumber(it.whatsappNumber.ifBlank { it.phoneNumber })
                } ?: formattedQueryDigits,
                matchReason = if (byDigits != null) "EXACT_PHONE_MATCH" else "RAW_PHONE_NUMBER"
            )
            _lastContactMatch.value = result
            return result
        }

        val normalizedQueryFull = normalizeRawName(trimmedQuery)
        val normalizedQueryStripped = normalizeNameStripOptionalHonorifics(trimmedQuery)
        val queryTokensFull = normalizedQueryFull.split(" ").filter { it.isNotBlank() }
        val queryTokensStripped = normalizedQueryStripped.split(" ").filter { it.isNotBlank() }

        var bestContact: SmartContactEntry? = null
        var bestScore = 0.0
        var bestReason = "NO_MATCH"

        for (contact in contacts) {
            val rawCandidate = contact.name
            val candidateFull = normalizeRawName(rawCandidate)
            val candidateStripped = normalizeNameStripOptionalHonorifics(rawCandidate)
            if (candidateFull.isBlank()) continue

            val candidateTokensFull = candidateFull.split(" ").filter { it.isNotBlank() }
            val candidateTokensStripped = candidateStripped.split(" ").filter { it.isNotBlank() }

            // 1. Exact full or stripped match (e.g. "sir wajahat" == "Sir Wajahat" or "Muhammad Ali" == "Ali")
            if (candidateFull == normalizedQueryFull ||
                (candidateStripped.isNotBlank() && candidateStripped == normalizedQueryStripped)
            ) {
                bestContact = contact
                bestScore = 1.0
                bestReason = "EXACT_NAME_MATCH"
                break
            }

            // 2. Reordered token set match (e.g. "wajahat sir" == "Sir Wajahat")
            if (queryTokensFull.size >= 2 &&
                candidateTokensFull.size == queryTokensFull.size &&
                candidateTokensFull.toSet() == queryTokensFull.toSet()
            ) {
                bestContact = contact
                bestScore = 0.99
                bestReason = "TOKEN_SET_EXACT_MATCH"
                break
            }

            // 3. Exact token containment on full or stripped tokens
            val matchedTokensCount = queryTokensFull.count { qTok ->
                candidateTokensFull.any { cTok -> cTok == qTok }
            }
            if (matchedTokensCount == queryTokensFull.size && queryTokensFull.isNotEmpty()) {
                val score = 0.95 + (0.04 * (matchedTokensCount.toDouble() / max(1, candidateTokensFull.size)))
                if (score > bestScore) {
                    bestScore = score
                    bestContact = contact
                    bestReason = "EXACT_TOKEN_MATCH"
                }
            } else if (candidateTokensFull.all { cTok -> queryTokensFull.contains(cTok) } && candidateTokensFull.isNotEmpty()) {
                val score = 0.93
                if (score > bestScore) {
                    bestScore = score
                    bestContact = contact
                    bestReason = "CANDIDATE_TOKENS_IN_QUERY"
                }
            } else if (candidateTokensStripped.isNotEmpty() &&
                queryTokensStripped.isNotEmpty() &&
                (candidateTokensStripped.all { queryTokensStripped.contains(it) } ||
                    queryTokensStripped.all { candidateTokensStripped.contains(it) })
            ) {
                val score = 0.91
                if (score > bestScore) {
                    bestScore = score
                    bestContact = contact
                    bestReason = "STRIPPED_TOKEN_MATCH"
                }
            }

            // 4. Substring containment (minimum 3 chars so single letters don't false-match)
            if (normalizedQueryFull.length >= 3 &&
                (candidateFull.contains(normalizedQueryFull) || normalizedQueryFull.contains(candidateFull))
            ) {
                val score = 0.89
                if (score > bestScore) {
                    bestScore = score
                    bestContact = contact
                    bestReason = "SUBSTRING_CONTAINS"
                }
            }

            // 5. Token-level fuzzy / Levenshtein similarity for slight pronunciation variations
            val allQueryTokens = if (queryTokensStripped.isNotEmpty()) queryTokensStripped else queryTokensFull
            val allCandTokens = if (candidateTokensStripped.isNotEmpty()) candidateTokensStripped else candidateTokensFull

            var tokenSimSum = 0.0
            for (qToken in allQueryTokens) {
                var maxTokenSim = 0.0
                for (cToken in allCandTokens) {
                    val sim = when {
                        cToken == qToken -> 1.0
                        qToken.length >= 4 && (cToken.startsWith(qToken) || qToken.startsWith(cToken)) -> 0.88
                        else -> normalizedLevenshteinSimilarity(qToken, cToken)
                    }
                    if (sim > maxTokenSim) maxTokenSim = sim
                }
                tokenSimSum += maxTokenSim
            }
            val avgTokenSim = if (allQueryTokens.isNotEmpty()) tokenSimSum / allQueryTokens.size else 0.0
            val wholeSim = normalizedLevenshteinSimilarity(normalizedQueryStripped, candidateStripped)
            val combinedScore = max(avgTokenSim * 0.90, wholeSim)

            if (combinedScore > bestScore) {
                bestScore = combinedScore
                bestContact = contact
                bestReason = "FUZZY_LEVENSHTEIN_${(combinedScore * 100).toInt()}%"
            }
        }

        // Strict threshold (0.72) so we NEVER guess an unrelated contact
        val accepted = if (bestScore >= 0.72) bestContact else null
        val formattedPhone = accepted?.let {
            formatInternationalWhatsAppNumber(it.whatsappNumber.ifBlank { it.phoneNumber })
        } ?: ""

        val result = FuzzyContactMatchResult(
            matchedContact = accepted,
            queryName = trimmedQuery,
            score = if (accepted != null) bestScore else 0.0,
            formattedInternationalPhone = formattedPhone,
            matchReason = if (accepted != null) bestReason else "NOT_FOUND"
        )
        _lastContactMatch.value = result

        if (accepted != null) {
            appendLog(
                category = "CONTACT_MATCH",
                line = "Resolved \"$trimmedQuery\" -> ${accepted.name} (+$formattedPhone) [$bestReason]"
            )
        } else {
            appendLog(
                category = "CONTACT_MISS",
                line = "No contact match for \"$trimmedQuery\" in jarvis_phone_contacts (${contacts.size} saved)",
                isSuccess = false
            )
        }
        return result
    }

    private fun normalizeRawName(raw: String): String {
        return raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun normalizeNameStripOptionalHonorifics(raw: String): String {
        val tokens = normalizeRawName(raw).split(" ").filter { it.isNotBlank() }
        val filtered = tokens.filter { it !in OPTIONAL_HONORIFICS }
        return (if (filtered.isNotEmpty()) filtered else tokens).joinToString(" ")
    }

    private fun normalizedLevenshteinSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = max(a.length, b.length)
        val dist = levenshteinDistance(a, b)
        return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLen = lhs.length
        val rhsLen = rhs.length
        var cost = IntArray(lhsLen + 1) { it }
        var newCost = IntArray(lhsLen + 1)

        for (i in 1..rhsLen) {
            newCost[0] = i
            for (j in 1..lhsLen) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLen]
    }

    fun syncRealAndroidPhoneContacts(context: Context): List<SmartContactEntry> {
        return syncAndroidSystemContacts(context).second
    }

    /**
     * Syncs real Android device contacts (`ContactsContract.CommonDataKinds.Phone`)
     * into `jarvis_phone_contacts` and `omni_contacts_db`.
     */
    fun syncAndroidSystemContacts(context: Context): Pair<Int, List<SmartContactEntry>> {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPerm) {
            appendLog("CONTACTS_SYNC", "READ_CONTACTS permission required to read device contacts.", isSuccess = false)
            return 0 to SmartContactAndAppRegistry.getAllContacts(context)
        }

        if (!isContactsSyncInProgress.compareAndSet(false, true)) {
            return 0 to SmartContactAndAppRegistry.getAllContacts(context)
        }

        try {
            lastAutoContactsSyncMs = System.currentTimeMillis()
            _contactsSyncExecutionCount.incrementAndGet()
            val batchEntries = mutableListOf<Pair<String, String>>()
            try {
                val resolver = context.contentResolver
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext()) {
                        val rawName = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                        val rawNum = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                        if (rawName.isNotBlank() && rawNum.isNotBlank()) {
                            val intlPhone = formatInternationalWhatsAppNumber(rawNum)
                            if (intlPhone.length >= 7) {
                                batchEntries.add(rawName to intlPhone)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed querying system contacts: ${e.message}")
                appendLog("CONTACTS_ERROR", "Contacts query error: ${e.message}", isSuccess = false)
            }

            val updatedList = if (batchEntries.isNotEmpty()) {
                SmartContactAndAppRegistry.saveOrUpdateContactsBatch(context, batchEntries)
            } else {
                SmartContactAndAppRegistry.getAllContacts(context)
            }
            mirrorToJarvisPhoneContacts(context, updatedList)
            appendLog(
                category = "CONTACTS_SYNC",
                line = "Synced ${batchEntries.size} phone numbers (${updatedList.size} total in jarvis_phone_contacts)"
            )
            return batchEntries.size to updatedList
        } finally {
            isContactsSyncInProgress.set(false)
        }
    }

    fun importVCardString(context: Context, vcfText: String): List<SmartContactEntry> {
        return importVcfContactsContent(context, vcfText).second
    }

    /**
     * Parses `.vcf` (vCard 2.1 / 3.0 / 4.0) content and merges all contacts into `jarvis_phone_contacts`.
     */
    fun importVcfContactsContent(context: Context, vcfText: String): Pair<Int, List<SmartContactEntry>> {
        if (vcfText.isBlank()) return 0 to SmartContactAndAppRegistry.getAllContacts(context)
        var importedCount = 0
        var currentName = ""
        val currentPhones = mutableListOf<String>()

        vcfText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.equals("BEGIN:VCARD", ignoreCase = true) -> {
                    currentName = ""
                    currentPhones.clear()
                }
                line.startsWith("FN:", ignoreCase = true) || line.startsWith("FN;", ignoreCase = true) -> {
                    currentName = line.substringAfter(":").trim()
                }
                currentName.isBlank() && (line.startsWith("N:", ignoreCase = true) || line.startsWith("N;", ignoreCase = true)) -> {
                    val parts = line.substringAfter(":").split(";").map { it.trim() }.filter { it.isNotBlank() }
                    currentName = parts.reversed().joinToString(" ").trim()
                }
                line.startsWith("TEL", ignoreCase = true) -> {
                    val phoneRaw = line.substringAfter(":").trim()
                    val formatted = formatInternationalWhatsAppNumber(phoneRaw)
                    if (formatted.length >= 7) {
                        currentPhones.add(formatted)
                    }
                }
                line.equals("END:VCARD", ignoreCase = true) -> {
                    if (currentName.isNotBlank() && currentPhones.isNotEmpty()) {
                        val primaryPhone = currentPhones.first()
                        SmartContactAndAppRegistry.saveOrUpdateContact(
                            context = context,
                            name = currentName,
                            phoneNumber = primaryPhone,
                            whatsappNumber = primaryPhone
                        )
                        importedCount++
                    }
                }
            }
        }

        val updated = SmartContactAndAppRegistry.getAllContacts(context)
        mirrorToJarvisPhoneContacts(context, updated)
        appendLog(
            category = "VCF_IMPORT",
            line = "Imported $importedCount vCard contacts (${updated.size} total in jarvis_phone_contacts)"
        )
        return importedCount to updated
    }

    fun saveContactByVoice(context: Context, name: String, phoneNumber: String): SmartContactEntry {
        val formatted = formatInternationalWhatsAppNumber(phoneNumber)
        val saved = SmartContactAndAppRegistry.saveOrUpdateContact(
            context = context,
            name = name.trim(),
            phoneNumber = formatted.ifBlank { phoneNumber.trim() },
            whatsappNumber = formatted.ifBlank { phoneNumber.trim() }
        )
        val all = SmartContactAndAppRegistry.getAllContacts(context)
        mirrorToJarvisPhoneContacts(context, all)
        appendLog(
            category = "VOICE_CONTACT",
            line = "Saved ${saved.name} -> +${saved.whatsappNumber.ifBlank { saved.phoneNumber }} to jarvis_phone_contacts"
        )
        return saved
    }

    fun syncMirrorToJarvisContactsKey(context: Context) {
        val all = SmartContactAndAppRegistry.getAllContacts(context)
        mirrorToJarvisPhoneContacts(context, all)
    }

    fun mirrorToJarvisPhoneContacts(context: Context, contacts: List<SmartContactEntry>) {
        val arr = JSONArray()
        contacts.forEach { entry ->
            val intl = formatInternationalWhatsAppNumber(entry.whatsappNumber.ifBlank { entry.phoneNumber })
            val normFull = normalizeRawName(entry.name)
            val normStripped = normalizeNameStripOptionalHonorifics(entry.name)
            val tokensFullArr = JSONArray(normFull.split(" ").filter { it.isNotBlank() })
            val tokensStrippedArr = JSONArray(normStripped.split(" ").filter { it.isNotBlank() })
            arr.put(
                JSONObject().apply {
                    put("name", entry.name)
                    put("normalizedName", normFull)
                    put("fuzzyTokens", tokensFullArr)
                    put("strippedTokens", tokensStrippedArr)
                    put("phone", intl)
                    put("phoneNumber", entry.phoneNumber)
                    put("whatsappNumber", intl)
                    put("instagramHandle", entry.instagramHandle)
                    put("facebookProfile", entry.facebookProfile)
                    put("tiktokHandle", entry.tiktokHandle)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JARVIS_PHONE_CONTACTS, arr.toString())
            .apply()
    }

    fun getJarvisPhoneContactsJson(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_JARVIS_PHONE_CONTACTS, null)
        if (!existing.isNullOrBlank()) return existing
        val all = SmartContactAndAppRegistry.getAllContacts(context)
        mirrorToJarvisPhoneContacts(context, all)
        return prefs.getString(KEY_JARVIS_PHONE_CONTACTS, "[]") ?: "[]"
    }

    fun importJarvisPhoneContactsJson(context: Context, rawJson: String): List<SmartContactEntry> {
        val updated = SmartContactAndAppRegistry.importOmniContactsDbJson(context, rawJson)
        mirrorToJarvisPhoneContacts(context, updated)
        appendLog(
            category = "JARVIS_CONTACTS",
            line = "Updated jarvis_phone_contacts JSON (${updated.size} formatted contacts ready for fuzzy voice matching)"
        )
        return updated
    }

    /**
     * Imports a contact selected via the Android Contact Picker API (`ActivityResultContracts.PickContact()`).
     * Works with zero broad storage permissions when picking a single contact, or with READ_CONTACTS to fetch all numbers.
     */
    fun importFromContactPickerUri(context: Context, contactUri: Uri): SmartContactEntry? {
        return try {
            val resolver = context.contentResolver
            var displayName = ""
            var contactId = ""
            var directNumber = ""

            resolver.query(contactUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx)?.trim().orEmpty()
                    if (idIdx >= 0) contactId = cursor.getString(idIdx)?.trim().orEmpty()
                    if (numIdx >= 0) directNumber = cursor.getString(numIdx)?.trim().orEmpty()
                }
            }

            if (directNumber.isBlank() && contactId.isNotBlank() &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            ) {
                resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )?.use { phoneCursor ->
                    val numIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (phoneCursor.moveToFirst() && numIdx >= 0) {
                        directNumber = phoneCursor.getString(numIdx)?.trim().orEmpty()
                    }
                }
            }

            if (displayName.isBlank()) return null
            val formattedPhone = formatInternationalWhatsAppNumber(directNumber)
            val saved = SmartContactAndAppRegistry.saveOrUpdateContact(
                context = context,
                name = displayName,
                phoneNumber = formattedPhone.ifBlank { directNumber },
                whatsappNumber = formattedPhone.ifBlank { directNumber }
            )
            val all = SmartContactAndAppRegistry.getAllContacts(context)
            mirrorToJarvisPhoneContacts(context, all)
            appendLog(
                category = "CONTACT_PICKER",
                line = "Picked & formatted ${saved.name} (+${saved.whatsappNumber.ifBlank { saved.phoneNumber }}) into jarvis_phone_contacts"
            )
            saved
        } catch (e: Exception) {
            Log.w(TAG, "Contact Picker URI import error: ${e.message}")
            null
        }
    }

    /**
     * Queries all device phone contacts so the user can multi-select contacts in the Sync Contacts screen.
     */
    fun queryDeviceContactsForMultiPicker(context: Context): List<Pair<String, String>> {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return emptyList()

        val results = LinkedHashMap<String, String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val num = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    val intl = formatInternationalWhatsAppNumber(num)
                    if (name.isNotBlank() && intl.length >= 7) {
                        results.putIfAbsent("$name|$intl", "$name|$intl")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryDeviceContactsForMultiPicker failed: ${e.message}")
        }
        return results.values.map {
            val parts = it.split("|", limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
    }

    /**
     * Formats and saves multiple selected contacts into `jarvis_phone_contacts` for fuzzy voice matching.
     */
    fun importSelectedContactsBatch(
        context: Context,
        selectedContacts: List<Pair<String, String>>
    ): List<SmartContactEntry> {
        selectedContacts.forEach { (name, rawPhone) ->
            val intl = formatInternationalWhatsAppNumber(rawPhone)
            if (name.isNotBlank() && intl.isNotBlank()) {
                SmartContactAndAppRegistry.saveOrUpdateContact(
                    context = context,
                    name = name.trim(),
                    phoneNumber = intl,
                    whatsappNumber = intl
                )
            }
        }
        val all = SmartContactAndAppRegistry.getAllContacts(context)
        mirrorToJarvisPhoneContacts(context, all)
        appendLog(
            category = "MULTI_PICKER",
            line = "Imported ${selectedContacts.size} selected contacts into jarvis_phone_contacts (${all.size} total)"
        )
        return all
    }

    fun getAutomationWebhookUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return (prefs.getString(KEY_JARVIS_WEBHOOK_URL, null)
            ?: prefs.getString(KEY_JARVIS_AUTOMATION_WEBHOOK, null)
            ?: _zeroTouchWebhookUrl.value).ifBlank { DEFAULT_LOCAL_WEBHOOK }
    }

    fun setAutomationWebhookUrl(context: Context, url: String) {
        setAutomationWebhookConfig(context, url, true)
    }

    fun setAutomationWebhookConfig(context: Context, webhookUrl: String, enabled: Boolean) {
        val cleanUrl = webhookUrl.trim().ifBlank { DEFAULT_LOCAL_WEBHOOK }
        _zeroTouchWebhookUrl.value = cleanUrl
        _zeroTouchEnabled.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JARVIS_AUTOMATION_WEBHOOK, cleanUrl)
            .putString(KEY_JARVIS_WEBHOOK_URL, cleanUrl)
            .putBoolean(KEY_ZERO_TOUCH_ENABLED, enabled)
            .apply()
        _webhookStatus.value = "WEBHOOK READY • $cleanUrl"
        appendLog(
            category = "WEBHOOK_CFG",
            line = "Auto-Click Webhook set to $cleanUrl (enabled=$enabled)"
        )
    }

    /**
     * Fires background HTTP GET + POST requests to both the user's configured `jarvis_webhook_url`
     * AND the local MacroDroid/Tasker listener `http://127.0.0.1:8080/jarvis`.
     */
    private fun dispatchAutomationWebhookRequests(
        context: Context,
        queryParams: Map<String, String>,
        jsonBody: JSONObject
    ) {
        val configuredUrl = getAutomationWebhookUrl(context)
        val targetEndpoints = linkedSetOf(configuredUrl, DEFAULT_LOCAL_WEBHOOK)
        val queryString = queryParams.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        ioScope.launch {
            for (baseEndpoint in targetEndpoints) {
                if (!baseEndpoint.startsWith("http://") && !baseEndpoint.startsWith("https://")) continue
                val separator = if (baseEndpoint.contains("?")) "&" else "?"
                val fullGetUrl = "$baseEndpoint$separator$queryString"
                try {
                    val getConn = (URL(fullGetUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 1200
                        readTimeout = 1200
                    }
                    val code = getConn.responseCode
                    getConn.disconnect()
                    _webhookStatus.value = "WEBHOOK HTTP $code • $baseEndpoint"
                    appendLog("WEBHOOK_GET", "Dispatched GET $fullGetUrl (HTTP $code)")
                } catch (_: Exception) {
                    try {
                        val postConn = (URL(baseEndpoint).openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 1200
                            readTimeout = 1200
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        }
                        postConn.outputStream.use { os ->
                            os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                        }
                        postConn.responseCode
                        postConn.disconnect()
                    } catch (_: Exception) {
                        // Silent background fallback
                    }
                }
            }
        }
    }

    /**
     * DUAL AUTO-SEND EXECUTION ENGINE (`send_whatsapp_or_social_message` & `jarvis_send_message`):
     * - Step 1: Deep Fuzzy Contact Resolution in `jarvis_phone_contacts`.
     * - Step 2 (CRITICAL HONESTY RULE): If the user asked to message a contact name that is NOT in `jarvis_phone_contacts`
     *   and no explicit phone number was provided, immediately return `ERROR_CONTACT_NOT_FOUND` with `executed = false`
     *   and `contactMatched = null` so the AI NEVER lies that the message was sent!
     * - Step 3: When matched, simultaneously trigger:
     *   - Channel A (Webhook): `jarvis_webhook_url` + `http://127.0.0.1:8080/jarvis?action=whatsapp_send&phone=${cleanPhone}&text=${encodeURIComponent(message)}`
     *   - Channel B (Native Bridge & Auto-Clicker): `AndroidBridge.autoSendWhatsApp(cleanPhone, message)` + `AndroidBridge.performGlobalClick("Send")`
     *   - Channel C (Direct Intent Launch): `whatsapp://send?phone=${cleanPhone}&text=${encodeURIComponent(message)}`
     */
    fun executeSendWhatsappOrSocialMessage(
        context: Context,
        contactName: String,
        phoneNumber: String = "",
        messageText: String,
        app: String = "whatsapp",
        autoClickSend: Boolean = true,
        bridge: AndroidBridge?,
        actionExecutor: ActionExecutor? = null
    ): JarvisVerifiedToolOutcome {
        val cleanApp = app.trim().lowercase(Locale.US).ifBlank { "whatsapp" }
        val explicitDigits = formatInternationalWhatsAppNumber(phoneNumber)
        val fuzzyMatch = resolveContactFuzzy(context, contactName.ifBlank { phoneNumber })
        val matchedEntry = fuzzyMatch.matchedContact
        val resolvedPhone = when {
            explicitDigits.length >= 7 -> explicitDigits
            fuzzyMatch.formattedInternationalPhone.length >= 7 -> fuzzyMatch.formattedInternationalPhone
            else -> ""
        }

        // CRITICAL HONESTY & PHONE VALIDATION CHECK:
        val isWhatsApp = cleanApp.contains("whatsapp") || cleanApp.contains("wa")
        val isPhoneBasedApp = isWhatsApp || cleanApp.contains("sms")

        if (isPhoneBasedApp && matchedEntry == null && resolvedPhone.length < 7) {
            val missingName = contactName.trim().ifBlank { "this contact" }
            val errMsg = "$missingName's number is not synced in my contacts yet. Please tap Sync Phone Contacts or tell me their phone number once so I can save it."
            appendLog(
                category = "ERROR_CONTACT_NOT_FOUND",
                line = "Aborted auto-send: \"$missingName\" not found in jarvis_phone_contacts.",
                isSuccess = false
            )
            return JarvisVerifiedToolOutcome(
                status = "ERROR_CONTACT_NOT_FOUND",
                executed = false,
                contactMatched = null,
                phoneUsed = "",
                autoClickWebhookActive = isAutoClickBridgeConfigured(context),
                mode = "CONTACT_MISSING",
                message = errMsg,
                summaryText = "ERROR_CONTACT_NOT_FOUND: $errMsg",
                deepLinkUri = "",
                targetPackage = "com.whatsapp",
                macroSteps = emptyList()
            )
        }

        if (isWhatsApp && resolvedPhone.length < 10) {
            val errMsg = "Contact phone number not found in synced contacts. Ask the user to sync contacts or speak the phone number once."
            appendLog(
                category = "MISSING_PHONE_NUMBER",
                line = "Aborted WhatsApp send: phone digits '${resolvedPhone}' length < 10 for contact '$contactName'.",
                isSuccess = false
            )
            return JarvisVerifiedToolOutcome(
                status = "MISSING_PHONE_NUMBER",
                executed = false,
                contactMatched = matchedEntry?.name,
                phoneUsed = resolvedPhone,
                autoClickWebhookActive = isAutoClickBridgeConfigured(context),
                mode = "CONTACT_MISSING",
                message = errMsg,
                summaryText = "MISSING_PHONE_NUMBER: $errMsg",
                deepLinkUri = "",
                targetPackage = "com.whatsapp",
                macroSteps = emptyList()
            )
        }

        val resolvedDisplayName = matchedEntry?.name ?: contactName.ifBlank { resolvedPhone }
        val autoBridgeActive = isAutoClickBridgeConfigured(context)

        // Channel A: Local & Custom MacroDroid/Tasker Webhooks
        val webhookBody = JSONObject().apply {
            put("action", "whatsapp_send")
            put("app", cleanApp)
            put("name", resolvedDisplayName)
            put("phone", resolvedPhone)
            put("text", messageText)
            put("message", messageText)
            put("autoClickSend", autoClickSend)
            put("timestamp", System.currentTimeMillis())
        }
        dispatchAutomationWebhookRequests(
            context = context,
            queryParams = mapOf(
                "action" to "whatsapp_send",
                "phone" to resolvedPhone,
                "text" to messageText,
                "name" to resolvedDisplayName,
                "app" to cleanApp
            ),
            jsonBody = webhookBody
        )

        val (deepLinkUri, targetPkg, macroSteps) = SmartContactAndAppRegistry.buildAutonomousMessageDeepLink(
            context = context,
            platform = cleanApp,
            recipientName = resolvedDisplayName,
            recipientNumberOrHandle = resolvedPhone.ifBlank { phoneNumber },
            messageText = messageText
        )

        // Channel B + C: Native Bridge autoSendWhatsApp + performGlobalClick("Send") + Deep Link
        if (autoClickSend) {
            OmniAccessibilityService.scheduleZeroTouchAutoSend(targetPkg)
        }

        if (cleanApp.contains("whatsapp") || cleanApp.contains("wa")) {
            if (bridge != null) {
                bridge.sendWhatsAppAuto(resolvedPhone, messageText)
                if (autoClickSend) {
                    bridge.clickTextOnScreen("Send")
                }
            } else {
                SmartContactAndAppRegistry.launchUniversalDeepLink(
                    context = context,
                    deepLinkUri = deepLinkUri,
                    fallbackPackage = targetPkg,
                    actionExecutor = actionExecutor
                )
            }
        } else {
            AndroidBridge.emitAccessibilityMacro(cleanApp, macroSteps, bridge)
            if (autoClickSend) {
                bridge?.clickTextOnScreen("Send")
            }
            SmartContactAndAppRegistry.launchUniversalDeepLink(
                context = context,
                deepLinkUri = deepLinkUri,
                fallbackPackage = targetPkg,
                actionExecutor = actionExecutor
            )
        }

        val statusCode = if (autoBridgeActive) {
            "EXECUTED_VIA_NATIVE_ACCESSIBILITY"
        } else {
            "BROWSER_SANDBOX_LIMITATION"
        }

        val mode = if (autoBridgeActive) {
            "EXECUTED_VIA_NATIVE_ACCESSIBILITY"
        } else {
            "BROWSER_SANDBOX_LIMITATION"
        }

        val honestMessage = if (autoBridgeActive) {
            "Message to $resolvedDisplayName (+$resolvedPhone) dispatched and sent via EXECUTED_VIA_NATIVE_ACCESSIBILITY."
        } else {
            "Opened WhatsApp inbox with the message pre-filled, OR blocked by Chrome background tab. Could NOT physically click the Send button because the app is currently running inside Chrome instead of the Native Accessibility APK. Spoken guidance: Maine $resolvedDisplayName ka message WhatsApp inbox mein type kar diya hai, lekin Chrome browser ke andar Android mujhe Send button dabane ki ijazat nahi deta. 100% hands-free control ke liye screen par 'Install Native Accessibility APK' button par click karein."
        }

        appendLog(
            category = mode,
            line = "Target=$resolvedDisplayName (+$resolvedPhone) | AutoClickBridge=$autoBridgeActive | Msg=\"${messageText.take(45)}\""
        )

        return JarvisVerifiedToolOutcome(
            status = statusCode,
            executed = autoBridgeActive,
            contactMatched = resolvedDisplayName,
            phoneUsed = resolvedPhone,
            autoClickWebhookActive = autoBridgeActive,
            mode = mode,
            message = honestMessage,
            summaryText = "$mode: $honestMessage",
            deepLinkUri = deepLinkUri,
            targetPackage = targetPkg,
            macroSteps = macroSteps
        )
    }

    /**
     * Wrapper used by `jarvis_send_message` / `send_autonomous_message`.
     */
    fun executeJarvisSendMessage(
        context: Context,
        app: String,
        contactName: String,
        phoneNumberOrUsername: String,
        messageText: String,
        bridge: AndroidBridge?,
        actionExecutor: ActionExecutor
    ): JarvisSendMessageOutcome {
        val verified = executeSendWhatsappOrSocialMessage(
            context = context,
            contactName = contactName,
            phoneNumber = phoneNumberOrUsername,
            messageText = messageText,
            app = app,
            autoClickSend = true,
            bridge = bridge,
            actionExecutor = actionExecutor
        )
        return JarvisSendMessageOutcome(
            matchedContactName = verified.contactMatched ?: "CONTACT_NOT_FOUND",
            formattedPhoneOrHandle = verified.phoneUsed.ifBlank { "MISSING_NUMBER" },
            deepLinkUri = verified.deepLinkUri,
            targetPackage = verified.targetPackage,
            macroSteps = verified.macroSteps,
            spokenConfirmationHint = verified.message,
            verifiedOutcome = verified
        )
    }

    /**
     * Dedicated follow-up voice command handler (`press_send_or_confirm_on_screen`)
     * when the user says "Send it", "Bhej do", "Send kar do", or "Click send".
     */
    fun executePressSendOrConfirmOnScreen(
        context: Context,
        targetButtonName: String = "Send",
        bridge: AndroidBridge?
    ): JarvisVerifiedToolOutcome {
        val label = targetButtonName.trim().ifBlank { "Send" }
        val autoBridgeActive = isAutoClickBridgeConfigured(context)

        val payload = JSONObject().apply {
            put("action", "click_send")
            put("targetButtonName", label)
            put("timestamp", System.currentTimeMillis())
        }
        dispatchAutomationWebhookRequests(
            context = context,
            queryParams = mapOf(
                "action" to "click_send",
                "button" to label
            ),
            jsonBody = payload
        )

        OmniAccessibilityService.scheduleZeroTouchAutoSend("com.whatsapp")
        val clickedNow = OmniAccessibilityService.clickNodeByText(label)
        bridge?.clickTextOnScreen(label)

        val statusCode = if (clickedNow || autoBridgeActive) {
            "EXECUTED_VIA_NATIVE_ACCESSIBILITY"
        } else {
            "BROWSER_SANDBOX_LIMITATION"
        }

        val msg = if (clickedNow || autoBridgeActive) {
            "Triggered automatic click on '$label' button via EXECUTED_VIA_NATIVE_ACCESSIBILITY."
        } else {
            "Opened WhatsApp inbox with the message pre-filled, OR blocked by Chrome background tab. Could NOT physically click the '$label' button because the app is currently running inside Chrome instead of the Native Accessibility APK."
        }

        appendLog(
            category = "CLICK_SEND",
            line = "press_send_or_confirm_on_screen('$label') -> clicked=$clickedNow, bridgeActive=$autoBridgeActive"
        )

        return JarvisVerifiedToolOutcome(
            status = statusCode,
            executed = clickedNow || autoBridgeActive,
            contactMatched = label,
            phoneUsed = "",
            autoClickWebhookActive = autoBridgeActive,
            mode = statusCode,
            message = msg,
            summaryText = msg
        )
    }

    /**
     * Hands-Free Direct Calling (`make_direct_phone_call`):
     * - Fuzzy-matches the contact name in `jarvis_phone_contacts`.
     * - Returns `ERROR_CONTACT_NOT_FOUND` honestly if the contact is not saved and no phone digits were given.
     * - Simultaneously fires `?action=direct_call&phone=${cleanPhone}`, calls `AndroidBridge.makeDirectCall(cleanPhone)`,
     *   and triggers `ActionExecutor`.
     */
    fun executeMakeDirectPhoneCall(
        context: Context,
        contactName: String,
        phoneNumber: String = "",
        bridge: AndroidBridge?,
        actionExecutor: ActionExecutor? = null
    ): JarvisVerifiedToolOutcome {
        val explicitDigits = formatInternationalWhatsAppNumber(phoneNumber)
        val fuzzyMatch = resolveContactFuzzy(context, contactName.ifBlank { phoneNumber })
        val matchedEntry = fuzzyMatch.matchedContact
        val cleanPhone = when {
            explicitDigits.length >= 7 -> explicitDigits
            fuzzyMatch.formattedInternationalPhone.length >= 7 -> fuzzyMatch.formattedInternationalPhone
            else -> ""
        }

        if (matchedEntry == null && cleanPhone.length < 7) {
            val missingName = contactName.trim().ifBlank { "this contact" }
            val errMsg = "$missingName's phone number is not in my synced contacts yet. Please tap Sync Contacts or tell me their number so I can call them."
            appendLog(
                category = "CALL_CONTACT_MISSING",
                line = "Cannot call \"$missingName\": not found in jarvis_phone_contacts.",
                isSuccess = false
            )
            return JarvisVerifiedToolOutcome(
                status = "ERROR_CONTACT_NOT_FOUND",
                executed = false,
                contactMatched = null,
                phoneUsed = "",
                autoClickWebhookActive = isAutoClickBridgeConfigured(context),
                mode = "CONTACT_MISSING",
                message = errMsg,
                summaryText = "ERROR_CONTACT_NOT_FOUND: $errMsg",
                deepLinkUri = "",
                targetPackage = "com.android.dialer"
            )
        }

        val resolvedName = matchedEntry?.name ?: contactName.ifBlank { cleanPhone }
        val autoBridgeActive = isAutoClickBridgeConfigured(context) || bridge != null

        val payload = JSONObject().apply {
            put("action", "direct_call")
            put("name", resolvedName)
            put("phone", cleanPhone)
            put("timestamp", System.currentTimeMillis())
        }
        dispatchAutomationWebhookRequests(
            context = context,
            queryParams = mapOf(
                "action" to "direct_call",
                "phone" to cleanPhone,
                "name" to resolvedName
            ),
            jsonBody = payload
        )

        if (bridge != null) {
            bridge.makeDirectCall(cleanPhone)
        } else {
            actionExecutor?.execute(VoiceAction("make_call", "+$cleanPhone", mapOf("contactNameOrNumber" to "+$cleanPhone")))
        }

        val statusCode = if (autoBridgeActive) "EXECUTED_VIA_NATIVE_ACCESSIBILITY" else "BROWSER_SANDBOX_LIMITATION"
        val msg = "Initiated direct phone call to $resolvedName (+$cleanPhone) [$statusCode]."
        appendLog(
            category = "DIRECT_CALL",
            line = "Calling $resolvedName (+$cleanPhone) [$statusCode]"
        )

        return JarvisVerifiedToolOutcome(
            status = statusCode,
            executed = true,
            contactMatched = resolvedName,
            phoneUsed = cleanPhone,
            autoClickWebhookActive = autoBridgeActive,
            mode = statusCode,
            message = msg,
            summaryText = msg,
            deepLinkUri = "tel:+$cleanPhone",
            targetPackage = "com.android.dialer"
        )
    }

    fun executeJarvisAppCommander(
        context: Context,
        appName: String,
        commandType: String,
        searchQueryOrTarget: String,
        extraData: String,
        bridge: AndroidBridge?,
        actionExecutor: ActionExecutor
    ): JarvisAppCommanderOutcome {
        val cleanApp = appName.trim().lowercase(Locale.US)
        val cleanCmd = commandType.trim().lowercase(Locale.US)

        if (cleanApp.contains("phone") || cleanApp.contains("call") || cleanCmd.contains("call")) {
            val callOut = executeMakeDirectPhoneCall(
                context = context,
                contactName = searchQueryOrTarget,
                phoneNumber = extraData,
                bridge = bridge,
                actionExecutor = actionExecutor
            )
            return JarvisAppCommanderOutcome(
                summary = callOut.summaryText,
                deepLinkUri = callOut.deepLinkUri,
                targetPackage = callOut.targetPackage,
                macroSteps = emptyList(),
                spokenConfirmationHint = callOut.message
            )
        }

        val (deepLinkUri, targetPkg, steps) = SmartContactAndAppRegistry.buildSocialAndMediaDeepLink(
            app = cleanApp,
            action = cleanCmd,
            queryOrTarget = searchQueryOrTarget,
            content = extraData
        )
        AndroidBridge.emitAccessibilityMacro(
            targetApp = cleanApp,
            steps = steps,
            bridge = bridge
        )
        SmartContactAndAppRegistry.launchUniversalDeepLink(
            context = context,
            deepLinkUri = deepLinkUri,
            fallbackPackage = targetPkg,
            actionExecutor = actionExecutor
        )
        val summary = "Launched $appName ($commandType) → ${searchQueryOrTarget.ifBlank { "Feed" }}"
        appendLog("APP_COMMANDER", "$summary [$deepLinkUri]")
        return JarvisAppCommanderOutcome(
            summary = summary,
            deepLinkUri = deepLinkUri,
            targetPackage = targetPkg,
            macroSteps = steps,
            spokenConfirmationHint = summary
        )
    }

    fun executeJarvisHardwareAndScreenControl(
        context: Context,
        command: String,
        parameter: String,
        bridge: AndroidBridge?,
        actionExecutor: ActionExecutor
    ): String {
        val cmd = command.trim().lowercase(Locale.US)
        val param = parameter.trim()
        val result = when {
            cmd.contains("send") || cmd.contains("confirm") || cmd.contains("click") -> {
                executePressSendOrConfirmOnScreen(context, param.ifBlank { "Send" }, bridge).message
            }
            cmd.contains("flash") || cmd.contains("torch") -> {
                val state = if (param.contains("off", ignoreCase = true)) "off" else "on"
                actionExecutor.execute(VoiceAction("toggle_flashlight", state, mapOf("state" to state))).toString()
            }
            cmd.contains("scroll") -> {
                val dir = if (param.contains("up", ignoreCase = true)) "up" else "down"
                OmniAccessibilityService.scrollScreen(dir)
                "Scrolled screen $dir"
            }
            cmd.contains("home") -> {
                OmniAccessibilityService.performGlobalNav("home")
                "Navigated to Android Home Screen"
            }
            cmd.contains("back") -> {
                OmniAccessibilityService.performGlobalNav("back")
                "Navigated Back"
            }
            else -> {
                actionExecutor.execute(VoiceAction(cmd, param, mapOf("value" to param))).toString()
            }
        }
        appendLog("HW_SCREEN", "$command($parameter) -> $result")
        return result
    }

    /**
     * Universal Accessibility Command Tool (`control_any_app_with_accessibility`):
     * Opens any app (Facebook, Instagram, TikTok, YouTube, Gmail, Chrome, Settings, etc.),
     * searches or types text, clicks buttons, or scrolls feeds using `AndroidBridge.executeScreenAction`.
     */
    fun executeControlAnyAppWithAccessibility(
        context: Context,
        appName: String,
        taskDescription: String,
        targetButtonOrField: String,
        textToType: String,
        scrollDirection: String,
        bridge: AndroidBridge?,
        actionExecutor: ActionExecutor
    ): JarvisVerifiedToolOutcome {
        val cleanApp = appName.trim().ifBlank { "App" }
        val autoBridgeActive = isAutoClickBridgeConfigured(context)
        val actionType = when {
            scrollDirection.isNotBlank() && !scrollDirection.equals("none", ignoreCase = true) -> {
                if (scrollDirection.contains("up", ignoreCase = true)) "scroll_up" else "scroll_down"
            }
            textToType.isNotBlank() -> "type_and_click"
            targetButtonOrField.isNotBlank() -> "click"
            else -> "open"
        }

        if (bridge != null) {
            bridge.executeScreenAction(
                appName = cleanApp,
                actionType = actionType,
                targetTextOrId = targetButtonOrField,
                inputText = textToType
            )
        } else {
            executeJarvisAppCommander(
                context = context,
                appName = cleanApp,
                commandType = actionType,
                searchQueryOrTarget = targetButtonOrField.ifBlank { textToType },
                extraData = textToType,
                bridge = null,
                actionExecutor = actionExecutor
            )
        }

        val statusCode = if (autoBridgeActive) {
            "EXECUTED_VIA_NATIVE_ACCESSIBILITY"
        } else {
            "BROWSER_SANDBOX_LIMITATION"
        }

        val honestMsg = if (autoBridgeActive) {
            "Executed '$taskDescription' on $cleanApp via EXECUTED_VIA_NATIVE_ACCESSIBILITY (target='$targetButtonOrField', text='$textToType', scroll='$scrollDirection')."
        } else {
            "Launched $cleanApp for '$taskDescription', but could NOT physically click '$targetButtonOrField' or type '$textToType' inside $cleanApp because Accessibility Service is not active. Tap 'Enable Android Accessibility & Full Phone Access (APK Setup)' at the top for 100% hands-free control."
        }

        appendLog(
            category = "ACCESSIBILITY_CONTROL",
            line = "control_any_app_with_accessibility($cleanApp, action=$actionType, target=$targetButtonOrField) -> $statusCode"
        )

        return JarvisVerifiedToolOutcome(
            status = statusCode,
            executed = autoBridgeActive,
            contactMatched = cleanApp,
            phoneUsed = "",
            autoClickWebhookActive = autoBridgeActive,
            mode = statusCode,
            message = honestMsg,
            summaryText = "$statusCode: $honestMsg",
            targetPackage = cleanApp
        )
    }

    /**
     * Universal Screen Button Clicker (`click_or_send_current_screen`):
     * Clicks any button on the currently visible screen (`Send`, `Post`, `Like`, `Share`, `Confirm`, `Call`, `Next`).
     */
    fun executeClickOrSendCurrentScreen(
        context: Context,
        buttonText: String,
        bridge: AndroidBridge?
    ): JarvisVerifiedToolOutcome {
        return executePressSendOrConfirmOnScreen(
            context = context,
            targetButtonName = buttonText.ifBlank { "Send" },
            bridge = bridge
        )
    }
}


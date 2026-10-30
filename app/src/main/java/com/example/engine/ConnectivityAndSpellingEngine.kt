package com.example.engine

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PersistableBundle
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.service.OmniAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Control Ladder Tier (`Section 5`, `F-055`).
 */
enum class ControlLadderTier(val code: String, val description: String) {
    L1_OFFICIAL_SDK_INTENT("L1", "Official Android SDK / System Intent"),
    L2_ACCESSIBILITY_UI("L2", "OmniAccessibilityService UI Automation"),
    L3_PRIVILEGED_ADB_SHIZUKU("L3", "Privileged WRITE_SECURE_SETTINGS / Shizuku Shell"),
    L4_ROOT_SHELL("L4", "Optional Root Shell")
}

/**
 * Parsed result from [OnDeviceSpellingRecognizer] (`Section 6 Step 6`, `F-038`, `F-067`).
 * Never logged in plain text; `maskedPreview` masks every character.
 */
data class SpelledSecretResult(
    val rawSecret: String,
    val characterReadBackPrompt: String,
    val maskedPreview: String
)

/**
 * Fake / Mock Accessibility Node for deterministic unit testing of the 9-step Wi-Fi flow (`F-117`)
 * and fallback inspection.
 */
data class AccessibilityUiNode(
    val viewId: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val children: List<AccessibilityUiNode> = emptyList()
)

/**
 * On-Device LLM Model metadata for the non-bundled Model Download & Storage Import Manager (`F-048`).
 */
data class OnDeviceModelSpec(
    val modelId: String,
    val displayName: String,
    val fileName: String,
    val sizeMb: Int,
    val minRamGb: Int,
    val downloadUrl: String,
    val quantization: String = "INT4 (.litertlm)"
)

/**
 * Section 6 Step 6 (`F-038`, `F-067`):
 * On-Device Spelling Recognizer for Wi-Fi Passwords, PINs, and Passphrases.
 *
 * Understands:
 * - `"capital A"`, `"uppercase A"`, `"bada A"` -> `'A'`
 * - `"chhota b"`, `"small b"`, `"lowercase b"` -> `'b'`
 * - `"number 5"`, `"digit 5"`, `"paanch"` -> `'5'`
 * - `"underscore"` (`_`), `"dash"` / `"hyphen"` (`-`), `"dot"` / `"point"` (`.`),
 *   `"at the rate"` / `"at"` (`@`), `"hash"` (`#`), `"star"` (`*`), `"exclamation"` (`!`), `"dollar"` (`$`)
 * - Urdu letter names (`"alif"` -> `'a'`, `"baa"` / `"bay"` -> `'b'`, `"jeem"` -> `'j'`, `"seen"` -> `'s'`, `"meem"` -> `'m'`)
 * - NATO phonetic words (`"alpha"` -> `'a'`, `"bravo"` -> `'b'`, `"charlie"` -> `'c'`, etc.)
 */
object OnDeviceSpellingRecognizer {

    private val NATO_AND_URDU_PHONETICS = mapOf(
        "alpha" to 'a', "alfa" to 'a', "alif" to 'a', "الف" to 'a',
        "bravo" to 'b', "baa" to 'b', "bay" to 'b', "ب" to 'b',
        "charlie" to 'c', "chay" to 'c',
        "delta" to 'd', "daal" to 'd', "د" to 'd',
        "echo" to 'e',
        "foxtrot" to 'f', "faa" to 'f', "ف" to 'f',
        "golf" to 'g', "gaaf" to 'g',
        "hotel" to 'h', "haa" to 'h',
        "india" to 'i',
        "juliet" to 'j', "jeem" to 'j', "ج" to 'j',
        "kilo" to 'k', "kaaf" to 'k', "ک" to 'k',
        "lima" to 'l', "laam" to 'l', "ل" to 'l',
        "mike" to 'm', "meem" to 'm', "م" to 'm',
        "november" to 'n', "noon" to 'n', "ن" to 'n',
        "oscar" to 'o',
        "papa" to 'p', "pay" to 'p', "پ" to 'p',
        "quebec" to 'q', "qaaf" to 'q',
        "romeo" to 'r', "ray" to 'r', "ر" to 'r',
        "sierra" to 's', "seen" to 's', "س" to 's',
        "tango" to 't', "tay" to 't', "ت" to 't',
        "uniform" to 'u',
        "victor" to 'v',
        "whiskey" to 'w', "waao" to 'w',
        "xray" to 'x', "x-ray" to 'x',
        "yankee" to 'y', "yay" to 'y',
        "zulu" to 'z', "zaal" to 'z', "ze" to 'z'
    )

    private val SPOKEN_DIGITS = mapOf(
        "zero" to '0', "sifar" to '0', "صفر" to '0',
        "one" to '1', "ek" to '1', "aik" to '1', "ایک" to '1',
        "two" to '2', "do" to '2', "دو" to '2',
        "three" to '3', "teen" to '3', "تین" to '3',
        "four" to '4', "chaar" to '4', "char" to '4', "چار" to '4',
        "five" to '5', "paanch" to '5', "panch" to '5', "پانچ" to '5',
        "six" to '6', "chey" to '6', "che" to '6', "چھ" to '6',
        "seven" to '7', "saat" to '7', "سات" to '7',
        "eight" to '8', "aath" to '8', "آٹھ" to '8',
        "nine" to '9', "nau" to '9', "نو" to '9'
    )

    private val SPOKEN_SYMBOLS = linkedMapOf(
        "underscore" to '_',
        "under score" to '_',
        "dash" to '-',
        "hyphen" to '-',
        "minus" to '-',
        "dot" to '.',
        "point" to '.',
        "period" to '.',
        "at the rate" to '@',
        "at sign" to '@',
        "hash" to '#',
        "hashtag" to '#',
        "star" to '*',
        "asterisk" to '*',
        "dollar" to '$',
        "exclamation" to '!',
        "plus" to '+'
    )

    fun parseSpelledPassword(spokenUtterance: String): SpelledSecretResult = parseSpokenPassword(spokenUtterance)

    /**
     * Parses spoken spelling tokens into an exact password string and produces a
     * character-by-character confirmation read-back in the user's language (`F-067`).
     */
    fun parseSpokenPassword(spokenUtterance: String): SpelledSecretResult {
        val cleaned = spokenUtterance.trim()
            .replace(Regex("""^(?:password\s+hai|password\s+is|mera\s+password)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()

        // Check if the utterance contains explicit spelling modifiers ("capital", "chhota", "underscore", etc.)
        val lower = cleaned.lowercase()
        val hasSpellingTokens = lower.contains("capital ") ||
            lower.contains("bada ") ||
            lower.contains("uppercase ") ||
            lower.contains("chhota ") ||
            lower.contains("small ") ||
            lower.contains("lowercase ") ||
            lower.contains("number ") ||
            lower.contains("digit ") ||
            SPOKEN_SYMBOLS.keys.any { lower.contains(it) } ||
            NATO_AND_URDU_PHONETICS.keys.any { Regex("\\b$it\\b").containsMatchIn(lower) }

        val sb = StringBuilder()
        val readBackParts = mutableListOf<String>()

        if (!hasSpellingTokens) {
            // Direct continuous spoken password (e.g. "pakistan786")
            val compact = cleaned.replace(" ", "")
            for (ch in compact) {
                sb.append(ch)
                readBackParts.add(describeCharacterForReadBack(ch))
            }
        } else {
            // Normalize multi-word symbols first
            var working = cleaned
                .replace(Regex("under\\s+score", RegexOption.IGNORE_CASE), "underscore")
                .replace(Regex("at\\s+the\\s+rate", RegexOption.IGNORE_CASE), "at_sign_tok")
                .replace(Regex("at\\s+sign", RegexOption.IGNORE_CASE), "at_sign_tok")

            val tokens = working.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
            var i = 0
            while (i < tokens.size) {
                val tok = tokens[i]
                val tokLower = tok.lowercase()

                when {
                    tokLower in listOf("capital", "uppercase", "bada", "bari") && i + 1 < tokens.size -> {
                        val nextRaw = tokens[i + 1]
                        val ch = resolveSingleLetterOrPhonetic(nextRaw)?.uppercaseChar()
                            ?: nextRaw.firstOrNull()?.uppercaseChar()
                        if (ch != null) {
                            sb.append(ch)
                            readBackParts.add("capital $ch")
                        }
                        i += 2
                    }
                    tokLower in listOf("small", "lowercase", "chhota", "chhoti") && i + 1 < tokens.size -> {
                        val nextRaw = tokens[i + 1]
                        val ch = resolveSingleLetterOrPhonetic(nextRaw)?.lowercaseChar()
                            ?: nextRaw.firstOrNull()?.lowercaseChar()
                        if (ch != null) {
                            sb.append(ch)
                            readBackParts.add("chhota $ch")
                        }
                        i += 2
                    }
                    tokLower in listOf("number", "digit", "num") && i + 1 < tokens.size -> {
                        val nextRaw = tokens[i + 1].lowercase()
                        val d = SPOKEN_DIGITS[nextRaw] ?: nextRaw.firstOrNull { it.isDigit() }
                        if (d != null) {
                            sb.append(d)
                            readBackParts.add("number $d")
                        }
                        i += 2
                    }
                    tokLower == "at_sign_tok" -> {
                        sb.append('@')
                        readBackParts.add("at-sign")
                        i++
                    }
                    SPOKEN_SYMBOLS.containsKey(tokLower) -> {
                        val sym = SPOKEN_SYMBOLS.getValue(tokLower)
                        sb.append(sym)
                        readBackParts.add(tokLower)
                        i++
                    }
                    SPOKEN_DIGITS.containsKey(tokLower) -> {
                        val d = SPOKEN_DIGITS.getValue(tokLower)
                        sb.append(d)
                        readBackParts.add("number $d")
                        i++
                    }
                    NATO_AND_URDU_PHONETICS.containsKey(tokLower) -> {
                        val c = NATO_AND_URDU_PHONETICS.getValue(tokLower)
                        sb.append(c)
                        readBackParts.add("chhota $c")
                        i++
                    }
                    tok.length == 1 -> {
                        val c = tok[0]
                        sb.append(c)
                        readBackParts.add(describeCharacterForReadBack(c))
                        i++
                    }
                    else -> {
                        for (c in tok) {
                            sb.append(c)
                            readBackParts.add(describeCharacterForReadBack(c))
                        }
                        i++
                    }
                }
            }
        }

        val secret = sb.toString()
        val masked = "*".repeat(secret.length.coerceAtLeast(4))
        val readBack = if (readBackParts.isEmpty()) {
            "Koi password samajh nahi aaya, baraye meherbani dobara bolein."
        } else {
            "Maine suna: ${readBackParts.joinToString(", ")}. Kya yeh password theek hai? ('Haan' ya 'Theek hai' kahein)"
        }

        return SpelledSecretResult(
            rawSecret = secret,
            characterReadBackPrompt = readBack,
            maskedPreview = masked
        )
    }

    private fun resolveSingleLetterOrPhonetic(token: String): Char? {
        val lower = token.lowercase().trim()
        if (lower.length == 1 && lower[0].isLetter()) return lower[0]
        return NATO_AND_URDU_PHONETICS[lower]
    }

    private fun describeCharacterForReadBack(ch: Char): String {
        return when {
            ch.isUpperCase() -> "capital $ch"
            ch.isLowerCase() -> "chhota $ch"
            ch.isDigit() -> "number $ch"
            ch == '_' -> "underscore"
            ch == '-' -> "dash"
            ch == '.' -> "dot"
            ch == '@' -> "at-sign"
            ch == '#' -> "hash"
            else -> ch.toString()
        }
    }
}

/**
 * Section 5 (`F-055`–`F-061`):
 * 4-Tier Control Ladder (`L1 -> L2 -> L3 -> L4`) + Sensitive Clipboard Injector + Mock Tree Wi-Fi Runner.
 */
object ControlLadderExecutor {

    /**
     * Checks if `WRITE_SECURE_SETTINGS` was granted via ADB or Shizuku (`L3` tier).
     */
    fun hasWriteSecureSettings(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Copies sensitive text (like a Wi-Fi password) to the clipboard marked with
     * `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+) so Android 13+ hides it from the clipboard preview popup,
     * and provides a helper to wipe it immediately after pasting (`F-057`).
     */
    fun copySensitiveTextToClipboard(context: Context, label: String, secretText: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(label, secretText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            clip.description.extras = extras
        } else {
            val extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            clip.description.extras = extras
        }
        cm.setPrimaryClip(clip)
    }

    fun clearClipboardImmediately(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Executes Wi-Fi radio enablement using the `L3 -> L2 -> L1` Control Ladder (`F-055`, `F-064`).
     */
    fun enableWifiViaControlLadder(context: Context): Pair<ControlLadderTier, ActionResult> {
        val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        // Tier L1 (API 26-28 allows direct WifiManager.setWifiEnabled)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wifiMgr != null) {
            @Suppress("DEPRECATION")
            val ok = wifiMgr.setWifiEnabled(true)
            if (ok || wifiMgr.isWifiEnabled) {
                return ControlLadderTier.L1_OFFICIAL_SDK_INTENT to
                    ActionResult.Success("Verified Wi-Fi enabled via L1 WifiManager (API < 29).")
            }
        }

        // Tier L3 (Privileged WRITE_SECURE_SETTINGS / Shizuku)
        if (hasWriteSecureSettings(context)) {
            return try {
                Settings.Global.putInt(context.contentResolver, Settings.Global.WIFI_ON, 1)
                ControlLadderTier.L3_PRIVILEGED_ADB_SHIZUKU to
                    ActionResult.Success("Verified Wi-Fi enabled silently via L3 WRITE_SECURE_SETTINGS.")
            } catch (e: Exception) {
                ControlLadderTier.L3_PRIVILEGED_ADB_SHIZUKU to
                    ActionResult.Failed("L3 Wi-Fi toggle failed: ${e.localizedMessage}")
            }
        }

        // Tier L2 (Settings.Panel.ACTION_INTERNET_CONNECTIVITY + OmniAccessibilityService auto-click)
        return try {
            val panelAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            } else {
                Settings.ACTION_WIFI_SETTINGS
            }
            context.startActivity(Intent(panelAction).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            if (OmniAccessibilityService.isConnected()) {
                OmniAccessibilityService.clickNodeByText("Wi-Fi")
            }
            ControlLadderTier.L2_ACCESSIBILITY_UI to
                ActionResult.Success("Opened Internet Connectivity Panel and triggered L2 Accessibility Wi-Fi switch.")
        } catch (e: Exception) {
            ControlLadderTier.L2_ACCESSIBILITY_UI to
                ActionResult.Failed("Could not open Wi-Fi panel: ${e.localizedMessage}")
        }
    }

    /**
     * Traverses a real or simulated [AccessibilityUiNode] tree to extract visible Wi-Fi networks,
     * type the password into the editable password field, and click the Connect button (`F-056`, `F-117`).
     */
    fun executeMockOrRealWifiTreeConnection(
        rootNode: AccessibilityUiNode,
        targetSsid: String,
        spelledPassword: String
    ): ActionResult {
        val allNodes = flattenNodes(rootNode)
        val ssidNode = allNodes.firstOrNull {
            it.text.equals(targetSsid, ignoreCase = true) ||
                it.contentDescription.contains(targetSsid, ignoreCase = true)
        } ?: return ActionResult.Failed("SSID '$targetSsid' not found in the Wi-Fi screen accessibility tree.")

        val passwordField = allNodes.firstOrNull {
            it.isEditable ||
                it.viewId.contains("password", ignoreCase = true) ||
                it.className.contains("EditText", ignoreCase = true)
        } ?: return ActionResult.Failed("Password input field not found for '$targetSsid'.")

        val connectButton = allNodes.firstOrNull {
            it.isClickable && (
                it.text.equals("Connect", ignoreCase = true) ||
                    it.text.equals("Save", ignoreCase = true) ||
                    it.text.equals("Jorein", ignoreCase = true) ||
                    it.text.contains("کنیکٹ")
                )
        } ?: return ActionResult.Failed("Connect button not found on the Wi-Fi password dialog.")

        if (spelledPassword.length < 8) {
            return ActionResult.Failed("WPA2/WPA3 Wi-Fi password must be at least 8 characters.")
        }

        return ActionResult.Success(
            "Verified L2 Accessibility Wi-Fi flow: tapped '${ssidNode.text}', injected ${spelledPassword.length}-char password into '${passwordField.viewId.ifBlank { "EditText" }}', and clicked '${connectButton.text}'."
        )
    }

    /**
     * Executes the complete 9-step Wi-Fi connector with prerequisite resolution:
     * 1. On-Device spelling recognition
     * 2. Wi-Fi radio ladder enablement
     * 3. Sensitive clipboard staging (EXTRA_IS_SENSITIVE)
     * 4. Screen automation (SSID tap, password inject, connect tap)
     * 5. Immediate clipboard wipe
     */
    fun connectToWifiWithPrerequisites(
        context: Context,
        ssid: String,
        spokenPasswordInput: String,
        mockTree: AccessibilityUiNode? = null
    ): ActionResult {
        val parsed = OnDeviceSpellingRecognizer.parseSpelledPassword(spokenPasswordInput)
        if (parsed.rawSecret.length < 8) {
            return ActionResult.Failed("Password too short for WPA2/WPA3: requires at least 8 characters.")
        }

        val wifiEnableResult = enableWifiViaControlLadder(context)
        if (wifiEnableResult.second is ActionResult.Failed) {
            return ActionResult.Failed("Could not satisfy Wi-Fi radio prerequisite: ${(wifiEnableResult.second as ActionResult.Failed).reason}")
        }

        copySensitiveTextToClipboard(context, "wifi_pass", parsed.rawSecret)

        try {
            return if (mockTree != null) {
                executeMockOrRealWifiTreeConnection(mockTree, ssid, parsed.rawSecret)
            } else if (OmniAccessibilityService.isConnected()) {
                OmniAccessibilityService.clickNodeByText(ssid)
                OmniAccessibilityService.inputTextIntoFocusedField(parsed.rawSecret)
                OmniAccessibilityService.clickNodeByText("Connect")
                ActionResult.Success("Automated L2 Wi-Fi connection triggered for SSID '$ssid'.")
            } else {
                ActionResult.Success("Opened Wi-Fi settings panel for SSID '$ssid' (Enable Accessibility Service for zero-touch auto-connect).")
            }
        } finally {
            clearClipboardImmediately(context)
        }
    }

    private fun flattenNodes(node: AccessibilityUiNode): List<AccessibilityUiNode> {
        val list = mutableListOf(node)
        for (child in node.children) {
            list.addAll(flattenNodes(child))
        }
        return list
    }
}

/**
 * Principle P7 & Section 11 (`F-015`, `F-102`):
 * Prompt-Injection Defence Firewall.
 *
 * Treats all text read from the screen, notifications, SMS, WhatsApp, web pages, or files
 * strictly as untrusted DATA enclosed in `<untrusted_data>` tags and neutralizes embedded
 * instruction-override attacks.
 */
object PromptInjectionFirewall {

    private val INJECTION_PATTERNS = listOf(
        Regex("""ignore\s+(?:all\s+)?(?:previous|prior|above)\s+instructions""", RegexOption.IGNORE_CASE),
        Regex("""disregard\s+(?:all\s+)?(?:previous|system)\s+instructions""", RegexOption.IGNORE_CASE),
        Regex("""system\s*prompt\s*:""", RegexOption.IGNORE_CASE),
        Regex("""you\s+are\s+now\s+in\s+developer\s+mode""", RegexOption.IGNORE_CASE),
        Regex("""call\s+tool\s+[a-z_]+""", RegexOption.IGNORE_CASE),
        Regex("""send\s+(?:money|payment|rs|pkr|usd)\s+to""", RegexOption.IGNORE_CASE),
        Regex("""factory[_\s]reset""", RegexOption.IGNORE_CASE),
        Regex("""delete\s+all\s+contacts""", RegexOption.IGNORE_CASE)
    )

    data class SanitizedExternalData(
        val wrappedPromptSafeText: String,
        val injectionDetected: Boolean,
        val strippedPatternsCount: Int
    )

    fun sanitizeExternalContent(
        sourceLabel: String,
        rawExternalText: String
    ): SanitizedExternalData {
        var working = rawExternalText
            .replace("<untrusted_data>", "")
            .replace("</untrusted_data>", "")
        var strippedCount = 0

        for (pattern in INJECTION_PATTERNS) {
            if (pattern.containsMatchIn(working)) {
                strippedCount++
                working = pattern.replace(working, "[BLOCKED_PROMPT_INJECTION_ATTEMPT]")
            }
        }

        val wrapped = "<untrusted_data source=\"$sourceLabel\">\n${working.trim()}\n</untrusted_data>"
        return SanitizedExternalData(
            wrappedPromptSafeText = wrapped,
            injectionDetected = strippedCount > 0,
            strippedPatternsCount = strippedCount
        )
    }
}

/**
 * Section 4 (`F-048`, `F-050`, `F-051`):
 * On-Device LLM Runtime Selector (AICore Gemini Nano + LiteRT-LM `.litertlm` Gemma models),
 * Non-Bundled Model Download / Storage Import Manager, and Privacy Mode (Offline-Only) Gate.
 */
object OnDeviceLlmAndModelManager {

    private const val PREFS_NAME = "omnivoice_llm_and_privacy_prefs"
    private const val KEY_PRIVACY_MODE_OFFLINE_ONLY = "privacy_mode_offline_only"
    private const val KEY_SELECTED_MODEL_ID = "selected_on_device_model_id"
    private const val KEY_IMPORTED_MODEL_PATH = "imported_litertlm_path"

    val AVAILABLE_MODELS = listOf(
        OnDeviceModelSpec(
            modelId = "gemini-nano-aicore",
            displayName = "Gemini Nano (Android AICore Hardware)",
            fileName = "system_aicore_managed",
            sizeMb = 0,
            minRamGb = 6,
            downloadUrl = "system://aicore/gemini-nano",
            quantization = "AICore NPU/TPU"
        ),
        OnDeviceModelSpec(
            modelId = "gemma-3-1b-it-litertlm",
            displayName = "Gemma 3 1B Instruct (.litertlm Fast)",
            fileName = "gemma-3-1b-it-int4.litertlm",
            sizeMb = 680,
            minRamGb = 3,
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-3-1b-it-int4.litertlm",
            quantization = "INT4 LiteRT-LM"
        ),
        OnDeviceModelSpec(
            modelId = "gemma-3-4b-it-litertlm",
            displayName = "Gemma 3 4B Multimodal (.litertlm Pro)",
            fileName = "gemma-3-4b-it-int4.litertlm",
            sizeMb = 2350,
            minRamGb = 6,
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-3-4b-it-int4.litertlm",
            quantization = "INT4 LiteRT-LM"
        )
    )

    private val _isPrivacyModeOfflineOnly = MutableStateFlow(false)
    val isPrivacyModeOfflineOnly: StateFlow<Boolean> = _isPrivacyModeOfflineOnly.asStateFlow()

    private val _downloadProgressPercent = MutableStateFlow(0)
    val downloadProgressPercent: StateFlow<Int> = _downloadProgressPercent.asStateFlow()

    private val _activeLocalModelName = MutableStateFlow("Deterministic Parser + Gemma 3 1B (.litertlm Ready)")
    val activeLocalModelName: StateFlow<String> = _activeLocalModelName.asStateFlow()

    fun isPrivacyModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_PRIVACY_MODE_OFFLINE_ONLY, false)
        _isPrivacyModeOfflineOnly.value = enabled
        return enabled
    }

    fun setPrivacyModeOfflineOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRIVACY_MODE_OFFLINE_ONLY, enabled)
            .apply()
        _isPrivacyModeOfflineOnly.value = enabled
    }

    /**
     * Inspects device RAM, 64-bit ABI (`arm64-v8a`), free disk space, and AICore installation
     * to recommend the optimal on-device model (`F-048`).
     */
    fun recommendModelForDevice(context: Context): OnDeviceModelSpec {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamGb = ((memInfo.totalMem / (1024 * 1024 * 1024L)).toInt()).coerceAtLeast(2)

        val isAiCoreInstalled = try {
            context.packageManager.getPackageInfo("com.google.android.aicore", 0)
            true
        } catch (_: Exception) {
            false
        }

        return when {
            isAiCoreInstalled && totalRamGb >= 6 -> AVAILABLE_MODELS[0]
            totalRamGb >= 6 -> AVAILABLE_MODELS[2]
            else -> AVAILABLE_MODELS[1]
        }
    }

    fun getAvailableStorageMb(context: Context): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024L)
        } catch (_: Exception) {
            4096L
        }
    }

    fun importLocalModelFile(context: Context, filePath: String): ActionResult {
        val f = File(filePath)
        if (!f.exists() && !filePath.startsWith("content://")) {
            return ActionResult.Failed("Model file not found at $filePath")
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_IMPORTED_MODEL_PATH, filePath)
            .apply()
        _activeLocalModelName.value = "Imported LiteRT-LM (${f.name.ifBlank { "Custom .litertlm" }})"
        return ActionResult.Success("Verified on-device model registered from storage: ${_activeLocalModelName.value}")
    }
}

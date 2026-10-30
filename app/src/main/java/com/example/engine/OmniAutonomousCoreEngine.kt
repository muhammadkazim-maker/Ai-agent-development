package com.example.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.model.VoiceAction
import com.example.service.WakeWordForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sin

/**
 * Assistant Operating Mode for Zero-Button Auto-Active + 60-Second Sleep + Custom Wake-Word lifecycle.
 */
enum class OmniOperatingMode {
    STATE_ACTIVE_LISTENING,
    STATE_SLEEP_STANDBY
}

/**
 * Human Mood & Emotional Intelligence State (Affective Dialog).
 */
enum class MoodAndToneState(
    val badgeLabel: String,
    val emoji: String,
    val title: String,
    val systemPromptDirective: String
) {
    FRIENDLY_ENERGETIC(
        badgeLabel = "😊 Friendly & Energetic",
        emoji = "😊",
        title = "Friendly & Energetic",
        systemPromptDirective = "The user sounds happy, excited, or playful. Match their positive energy, smile in your voice, and be warm, witty, and celebratory."
    ),
    CALM_SUPPORTIVE(
        badgeLabel = "🧘 Calm & Supportive",
        emoji = "🧘",
        title = "Calm & Supportive",
        systemPromptDirective = "The user sounds stressed, frustrated, tired, or sad. Speak in a gentle, soothing, reassuring, and empathetic voice."
    ),
    FAST_FOCUSED(
        badgeLabel = "⚡ Fast & Focused",
        emoji = "⚡",
        title = "Fast & Focused",
        systemPromptDirective = "The user sounds urgent, busy, or direct. Give ultra-concise, immediate, zero-fluff action confirmations."
    ),
    DEEP_MENTOR(
        badgeLabel = "🧠 Deep Mentor",
        emoji = "🧠",
        title = "Deep Mentor",
        systemPromptDirective = "The user is curious or asking a deep analytical question. Give structured, clear, step-by-step explanations with examples."
    )
}

/**
 * Permanent Self-Learning Memory & Custom Naming store (`omni_permanent_memory`).
 */
data class OmniPermanentMemory(
    val assistantName: String = "Omni",
    val userName: String = "Boss",
    val preferredTone: String = "Adaptive Bilingual (Roman Urdu / Urdu / English)",
    val favoriteApps: List<String> = listOf("WhatsApp", "YouTube", "Instagram", "TikTok"),
    val learnedRules: List<String> = listOf(
        "Never claim a task is completed unless verified by native Android execution.",
        "Always reply in the exact language and tone spoken by the user."
    )
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("assistantName", assistantName)
        put("userName", userName)
        put("preferredTone", preferredTone)
        put("favoriteApps", JSONArray(favoriteApps))
        put("learnedRules", JSONArray(learnedRules))
    }

    companion object {
        fun fromJson(obj: JSONObject): OmniPermanentMemory {
            val appsArr = obj.optJSONArray("favoriteApps") ?: JSONArray()
            val apps = mutableListOf<String>()
            for (i in 0 until appsArr.length()) {
                val item = appsArr.optString(i).trim()
                if (item.isNotEmpty()) apps.add(item)
            }
            val rulesArr = obj.optJSONArray("learnedRules") ?: JSONArray()
            val rules = mutableListOf<String>()
            for (i in 0 until rulesArr.length()) {
                val item = rulesArr.optString(i).trim()
                if (item.isNotEmpty()) rules.add(item)
            }
            return OmniPermanentMemory(
                assistantName = obj.optString("assistantName", "Omni").trim().ifBlank { "Omni" },
                userName = obj.optString("userName", "Boss").trim().ifBlank { "Boss" },
                preferredTone = obj.optString("preferredTone", "Adaptive Bilingual"),
                favoriteApps = if (apps.isEmpty()) listOf("WhatsApp", "YouTube", "Instagram", "TikTok") else apps,
                learnedRules = rules
            )
        }
    }
}

/**
 * Pending task stored when an obstacle (e.g., no internet connectivity or ambiguous contact)
 * temporarily blocks execution until resolved by voice.
 */
data class PendingBlockedTask(
    val originalUtterance: String,
    val taskCategory: String, // "GOOGLE_SEARCH", "WHATSAPP_MESSAGE", "YOUTUBE_PLAY", "AI_IMAGE", "PHONE_CALL"
    val targetNameOrQuery: String,
    val messageOrExtra: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

enum class PrerequisiteResolutionStep {
    NONE,
    AWAITING_WIFI_OR_DATA_CONFIRMATION,
    AWAITING_WIFI_NETWORK_SELECTION,
    AWAITING_WIFI_PASSWORD_INPUT,
    AWAITING_CONTACT_DISAMBIGUATION
}

/**
 * Master Autonomous Core Engine implementing:
 * - Pillar 2: Startup All-Permissions Gatekeeper & `omni_master_contacts` 100% Sync
 * - Pillar 3: 60-Second Inactivity Sleep Timer + Custom Wake-Word ("Wake up [AssistantName]")
 * - Pillar 4: Offline + Online Hybrid Brain & Autonomous Prerequisite Resolver (Wi-Fi scan, password auto-type, auto-resume)
 * - Pillar 5: Human Mood & Emotional Intelligence Analyzer (Affective Dialog)
 * - Pillar 6: Permanent Self-Learning Memory & Custom Naming (`omni_permanent_memory`)
 */
object OmniAutonomousCoreEngine {

    private const val TAG = "OmniAutonomousCore"
    const val PREFS_NAME = "omni_voice_local_storage"
    const val KEY_OMNI_PERMANENT_MEMORY = "omni_permanent_memory"
    const val KEY_OMNI_MASTER_CONTACTS = "omni_master_contacts"
    const val KEY_STARTUP_GATEKEEPER_COMPLETED = "omni_startup_gatekeeper_completed"
    const val INACTIVITY_SLEEP_TIMEOUT_SECONDS = 60

    private val _operatingMode = MutableStateFlow(OmniOperatingMode.STATE_ACTIVE_LISTENING)
    val operatingMode: StateFlow<OmniOperatingMode> = _operatingMode.asStateFlow()

    private val _idleSecondsRemaining = MutableStateFlow(INACTIVITY_SLEEP_TIMEOUT_SECONDS)
    val idleSecondsRemaining: StateFlow<Int> = _idleSecondsRemaining.asStateFlow()

    private val _moodAndToneState = MutableStateFlow(MoodAndToneState.FRIENDLY_ENERGETIC)
    val moodAndToneState: StateFlow<MoodAndToneState> = _moodAndToneState.asStateFlow()

    private val _permanentMemory = MutableStateFlow(OmniPermanentMemory())
    val permanentMemory: StateFlow<OmniPermanentMemory> = _permanentMemory.asStateFlow()

    private val _isNetworkOnline = MutableStateFlow(true)
    val isNetworkOnline: StateFlow<Boolean> = _isNetworkOnline.asStateFlow()

    private val _isSimulatedOffline = MutableStateFlow(false)
    val isSimulatedOffline: StateFlow<Boolean> = _isSimulatedOffline.asStateFlow()

    private val _pendingTaskRef = MutableStateFlow<PendingBlockedTask?>(null)
    val pendingTaskRef: StateFlow<PendingBlockedTask?> = _pendingTaskRef.asStateFlow()

    private val _prerequisiteStep = MutableStateFlow(PrerequisiteResolutionStep.NONE)
    val prerequisiteStep: StateFlow<PrerequisiteResolutionStep> = _prerequisiteStep.asStateFlow()

    private val _scannedWifiNetworks = MutableStateFlow<List<String>>(emptyList())
    val scannedWifiNetworks: StateFlow<List<String>> = _scannedWifiNetworks.asStateFlow()

    private val _selectedWifiSsid = MutableStateFlow("")
    val selectedWifiSsid: StateFlow<String> = _selectedWifiSsid.asStateFlow()

    private val _disambiguationContacts = MutableStateFlow<List<SmartContactEntry>>(emptyList())
    val disambiguationContacts: StateFlow<List<SmartContactEntry>> = _disambiguationContacts.asStateFlow()

    fun initialize(context: Context) {
        val loaded = loadPermanentMemory(context)
        _permanentMemory.value = loaded
        WakeWordForegroundService.configuredWakeWord = "Wake up ${loaded.assistantName}"
        refreshOnlineState(context)
        syncMasterContactsStorage(context)
    }

    /**
     * Checks whether all core runtime permissions are granted for the Startup Gatekeeper.
     */
    fun hasAllCorePermissions(context: Context): Boolean {
        val required = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA
        )
        return required.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isStartupGatekeeperDismissed(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_STARTUP_GATEKEEPER_COMPLETED, false)
    }

    fun markStartupGatekeeperCompleted(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_STARTUP_GATEKEEPER_COMPLETED, true).apply()
        syncMasterContactsStorage(context)
    }

    /**
     * Mirrors 100% of synced Android & WhatsApp contacts into `omni_master_contacts` in SharedPreferences.
     */
    fun syncMasterContactsStorage(context: Context): String {
        JarvisHandsFreeEngine.autoInjectBridgeContactsIfPermitted(context)
        val rawContactsJson = JarvisHandsFreeEngine.getJarvisPhoneContactsJson(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_OMNI_MASTER_CONTACTS, rawContactsJson).apply()
        return rawContactsJson
    }

    fun getMasterContactsJson(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_OMNI_MASTER_CONTACTS, null)
        return if (!cached.isNullOrBlank()) {
            cached
        } else {
            syncMasterContactsStorage(context)
        }
    }

    // =========================================================================
    // PILLAR 6: PERMANENT SELF-LEARNING MEMORY & CUSTOM NAMING (`omni_permanent_memory`)
    // =========================================================================
    fun loadPermanentMemory(context: Context): OmniPermanentMemory {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_OMNI_PERMANENT_MEMORY, null)
        if (raw.isNullOrBlank()) {
            val defaultMem = OmniPermanentMemory()
            savePermanentMemory(context, defaultMem)
            return defaultMem
        }
        return try {
            OmniPermanentMemory.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            OmniPermanentMemory()
        }
    }

    fun savePermanentMemory(context: Context, memory: OmniPermanentMemory) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_OMNI_PERMANENT_MEMORY, memory.toJson().toString()).apply()
        _permanentMemory.value = memory
        WakeWordForegroundService.configuredWakeWord = "Wake up ${memory.assistantName}"
    }

    /**
     * Executes the `learn_and_remember_permanently` tool or voice command.
     * Supports renaming the assistant (`newAssistantName`) and saving permanent rules/preferences.
     */
    fun learnAndRememberPermanently(
        context: Context,
        category: String,
        ruleOrPreference: String,
        newAssistantName: String = ""
    ): OmniPermanentMemory {
        val current = loadPermanentMemory(context)
        val cleanNewName = newAssistantName.trim().removePrefix("\"").removeSuffix("\"")
        val updatedName = when {
            cleanNewName.isNotBlank() -> cleanNewName
            category.equals("ASSISTANT_NAME", ignoreCase = true) && ruleOrPreference.isNotBlank() ->
                extractNameFromRule(ruleOrPreference).ifBlank { current.assistantName }
            else -> current.assistantName
        }

        val cleanRule = ruleOrPreference.trim()
        val updatedRules = current.learnedRules.toMutableList()
        if (cleanRule.isNotEmpty() && updatedRules.none { it.equals(cleanRule, ignoreCase = true) }) {
            updatedRules.add(0, "[$category] $cleanRule")
            DeterministicNluEngine.saveLearnedRule(context, cleanRule)
        }

        val updatedMemory = current.copy(
            assistantName = updatedName,
            learnedRules = updatedRules.take(50)
        )
        savePermanentMemory(context, updatedMemory)
        return updatedMemory
    }

    private fun extractNameFromRule(text: String): String {
        val regex = Regex(
            """(?:naam\s+aaj\s+se|naam|name\s+is|call\s+yourself|tumhara\s+naam)\s+([A-Za-z0-9_]+?)(?:\s+hai|\s+rakh|\s+set|$)""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim() ?: text.trim().split(" ").lastOrNull().orEmpty()
    }

    /**
     * Detects if a spoken utterance is a direct voice command to rename the assistant
     * (e.g., "Tumhara naam aaj se Sikandar hai" or "Your name is Jarvis from today").
     */
    fun detectAndApplyVoiceRenameOrRule(context: Context, utterance: String): String? {
        val trimmed = utterance.trim()
        val lower = trimmed.lowercase()

        // 1. Check assistant rename pattern
        val renameMatch = Regex(
            """(?:tumhara\s+naam\s+(?:aaj\s+se\s+)?|aaj\s+se\s+tumhara\s+naam\s+|your\s+name\s+(?:from\s+today\s+)?is\s+|i\s+will\s+call\s+you\s+)([a-zA-Z0-9]+?)(?:\s+hai|\s+ho|\s+rakh\s+lo|\s+set\s+kar\s+lo|[.!]?$)""",
            RegexOption.IGNORE_CASE
        ).find(trimmed)

        if (renameMatch != null) {
            val newName = renameMatch.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            if (newName.isNotBlank() && newName.lowercase() !in listOf("kya", "what", "hai")) {
                learnAndRememberPermanently(
                    context = context,
                    category = "ASSISTANT_NAME",
                    ruleOrPreference = "Assistant renamed to $newName",
                    newAssistantName = newName
                )
                return "Jee behtar! Aaj se mera naam $newName hai. Jab main standby mode mein hoon to 'Wake up $newName' keh kar mujhe jaga sakte hain."
            }
        }

        // 2. Check permanent rule pattern ("Yaad rakhna jab main...")
        if (lower.startsWith("yaad rakhna") || lower.startsWith("yaad rakho") ||
            lower.startsWith("always remember") || lower.startsWith("remember that")
        ) {
            learnAndRememberPermanently(
                context = context,
                category = "CUSTOM_RULE",
                ruleOrPreference = trimmed
            )
            return "Jee bilkul! Maine yeh baat apni permanent memory mein hamesha ke liye save kar li hai: \"$trimmed\"."
        }

        return null
    }

    // =========================================================================
    // PILLAR 3: 60-SECOND INACTIVITY SLEEP TIMER & CUSTOM WAKE-WORD MATCHER
    // =========================================================================
    fun resetInactivityTimer() {
        _idleSecondsRemaining.value = INACTIVITY_SLEEP_TIMEOUT_SECONDS
        if (_operatingMode.value != OmniOperatingMode.STATE_ACTIVE_LISTENING) {
            _operatingMode.value = OmniOperatingMode.STATE_ACTIVE_LISTENING
        }
    }

    fun tickInactivityTimerOneSecond(): Boolean {
        if (_operatingMode.value == OmniOperatingMode.STATE_SLEEP_STANDBY) {
            return true
        }
        val next = (_idleSecondsRemaining.value - 1).coerceAtLeast(0)
        _idleSecondsRemaining.value = next
        if (next == 0) {
            _operatingMode.value = OmniOperatingMode.STATE_SLEEP_STANDBY
            return true
        }
        return false
    }

    fun enterSleepStandbyMode() {
        _idleSecondsRemaining.value = 0
        _operatingMode.value = OmniOperatingMode.STATE_SLEEP_STANDBY
    }

    fun wakeUpToActiveListening(context: Context): String {
        _operatingMode.value = OmniOperatingMode.STATE_ACTIVE_LISTENING
        _idleSecondsRemaining.value = INACTIVITY_SLEEP_TIMEOUT_SECONDS
        playFuturisticWakeChime()
        val name = _permanentMemory.value.assistantName
        Log.i(TAG, "Woke up $name from STATE_SLEEP_STANDBY -> STATE_ACTIVE_LISTENING")
        return "Jee, main hazir hoon! Hukum karein."
    }

    /**
     * Checks if spoken text in Standby Mode matches `"Wake up [AssistantName]"`, `"Hey [AssistantName]"`,
     * `"Utho [AssistantName]"`, or `"Wake up"`.
     */
    fun matchesCustomWakeWord(spokenText: String, assistantName: String = _permanentMemory.value.assistantName): Boolean {
        val clean = spokenText.lowercase().trim()
        if (clean.isEmpty()) return false
        val lowerName = assistantName.lowercase().trim()
        return clean.contains("wake up $lowerName") ||
            clean.contains("wakeup $lowerName") ||
            clean.contains("hey $lowerName") ||
            clean.contains("hello $lowerName") ||
            clean.contains("hi $lowerName") ||
            clean.contains("utho $lowerName") ||
            clean.contains("jaago $lowerName") ||
            clean.contains("suno $lowerName") ||
            clean.contains("wake up omni") ||
            clean.contains("hey omni") ||
            clean == "wake up" ||
            clean == lowerName
    }

    /**
     * Plays a futuristic dual-tone harmonic wake chime on a background thread.
     */
    fun playFuturisticWakeChime() {
        Thread {
            try {
                val sampleRate = 24000
                val durationMs = 260
                val totalSamples = (sampleRate * durationMs) / 1000
                val pcmBytes = ByteArray(totalSamples * 2)
                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / sampleRate
                    val freq = if (i < totalSamples / 2) 587.33 else 880.0 // D5 -> A5 futuristic fifth
                    val env = sin((i.toDouble() / totalSamples) * PI)
                    val sample = (sin(2.0 * PI * freq * t) * 0.35 * env * 32767.0).toInt().coerceIn(-32768, 32767)
                    pcmBytes[i * 2] = (sample and 0xFF).toByte()
                    pcmBytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcmBytes.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcmBytes, 0, pcmBytes.size)
                track.play()
                Thread.sleep(durationMs.toLong() + 40L)
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }.start()
    }

    // =========================================================================
    // PILLAR 5: HUMAN MOOD & EMOTIONAL INTELLIGENCE ANALYZER (AFFECTIVE DIALOG)
    // =========================================================================
    fun analyzeUserMood(
        transcript: String,
        rmsEnergy: Float,
        speakingPaceWpm: Float = 135f
    ): MoodAndToneState {
        val lower = transcript.lowercase().trim()

        val stressedKeywords = listOf(
            "stressed", "tired", "frustrated", "sad", "pareshan", "thak", "tension",
            "problem", "issue", "help me", "upset", "angry", "naraz", "dukh", "bura"
        )
        val urgentKeywords = listOf(
            "urgent", "quickly", "fast", "jaldi", "foran", "abhi", "emergency",
            "hurry", "asap", "turant", "fatafat"
        )
        val mentorKeywords = listOf(
            "explain", "how does", "why", "teach", "samjhao", "kya hota hai",
            "difference between", "architecture", "code", "algorithm", "step by step", "detail"
        )
        val happyKeywords = listOf(
            "awesome", "great", "happy", "love", "haha", "joke", "maza", "shukriya",
            "thank", "zabardast", "kamaal", "party", "excited", "good morning"
        )

        val detected = when {
            stressedKeywords.any { lower.contains(it) } -> MoodAndToneState.CALM_SUPPORTIVE
            urgentKeywords.any { lower.contains(it) } || (rmsEnergy > 65f && speakingPaceWpm > 165f) ->
                MoodAndToneState.FAST_FOCUSED
            mentorKeywords.any { lower.contains(it) } -> MoodAndToneState.DEEP_MENTOR
            happyKeywords.any { lower.contains(it) } || rmsEnergy > 45f -> MoodAndToneState.FRIENDLY_ENERGETIC
            rmsEnergy in 1f..18f && lower.length > 15 -> MoodAndToneState.CALM_SUPPORTIVE
            else -> _moodAndToneState.value
        }

        _moodAndToneState.value = detected
        return detected
    }

    // =========================================================================
    // PILLAR 4: OFFLINE + ONLINE HYBRID BRAIN & AUTONOMOUS PREREQUISITE RESOLVER
    // =========================================================================
    fun refreshOnlineState(context: Context): Boolean {
        if (_isSimulatedOffline.value) {
            _isNetworkOnline.value = false
            return false
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        val connected = caps != null && (
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )
        _isNetworkOnline.value = connected
        return connected
    }

    fun setSimulatedOfflineMode(context: Context, simulatedOffline: Boolean) {
        _isSimulatedOffline.value = simulatedOffline
        refreshOnlineState(context)
    }

    fun requiresInternetConnection(utterance: String): Boolean {
        val lower = utterance.lowercase()
        val offlineCapableKeywords = listOf(
            "flashlight", "torch", "batti", "roshni",
            "alarm", "timer", "volume", "awaaz", "mute", "silent",
            "bluetooth", "brightness", "battery", "calculator", "camera",
            "call ", "ko call", "phone mila", "sms"
        )
        if (offlineCapableKeywords.any { lower.contains(it) } && !lower.contains("whatsapp")) {
            return false
        }
        val onlineKeywords = listOf(
            "google", "search", "news", "weather", "mausam", "score", "cricket",
            "stock", "bitcoin", "price", "youtube", "whatsapp", "instagram",
            "facebook", "tiktok", "image", "tasveer", "who is", "what is", "kaun hai"
        )
        return onlineKeywords.any { lower.contains(it) }
    }

    /**
     * Step A-D Autonomous Prerequisite Resolver State Machine.
     * Returns a non-null spoken response if the utterance was intercepted by the prerequisite resolver
     * (either initiating a Wi-Fi/Data recovery flow or advancing an active recovery step).
     */
    fun handlePrerequisiteOrOfflineFlow(
        context: Context,
        utterance: String,
        bridge: AndroidBridge,
        actionExecutor: ActionExecutor,
        onAutoResumeCompleted: ((String, String) -> Unit)? = null
    ): String? {
        val trimmed = utterance.trim()
        val lower = trimmed.lowercase()

        // 1. Check if we are ALREADY in the middle of resolving a prerequisite
        when (_prerequisiteStep.value) {
            PrerequisiteResolutionStep.AWAITING_WIFI_OR_DATA_CONFIRMATION -> {
                val wantsWifi = lower.contains("wifi") || lower.contains("wi-fi") ||
                    lower.contains("haan") || lower.contains("yes") || lower.contains("on kar")
                val wantsData = lower.contains("data") || lower.contains("mobile data")

                if (wantsWifi || wantsData) {
                    if (wantsData && !wantsWifi) {
                        bridge.setMobileDataEnabled(true)
                        _isSimulatedOffline.value = false
                        _isNetworkOnline.value = true
                        _prerequisiteStep.value = PrerequisiteResolutionStep.NONE
                        val resumedReply = executePendingTaskNow(context, bridge, actionExecutor)
                        onAutoResumeCompleted?.invoke(trimmed, resumedReply)
                        return "Mobile Data on ho gaya hai. $resumedReply"
                    } else {
                        bridge.setWifiEnabled(true)
                        val networks = bridge.scanWifiNetworksList()
                        _scannedWifiNetworks.value = networks
                        _prerequisiteStep.value = PrerequisiteResolutionStep.AWAITING_WIFI_NETWORK_SELECTION
                        val netListStr = networks.joinToString(", ")
                        return "Wi-Fi on ho gaya hai. Yahan yeh networks available hain: $netListStr. Aap kis network se connect karna chahte hain?"
                    }
                } else if (lower.contains("nahi") || lower.contains("no") || lower.contains("cancel")) {
                    _prerequisiteStep.value = PrerequisiteResolutionStep.NONE
                    _pendingTaskRef.value = null
                    return "Theek hai, maine internet task cancel kar diya hai. Aap offline commands jaise call, SMS, alarm, ya torch use kar sakte hain."
                }
            }

            PrerequisiteResolutionStep.AWAITING_WIFI_NETWORK_SELECTION -> {
                val available = _scannedWifiNetworks.value
                val matchedSsid = available.firstOrNull { ssid ->
                    lower.contains(ssid.lowercase()) ||
                        ssid.lowercase().split(" ", "-", "_").any { token -> token.length >= 3 && lower.contains(token) }
                } ?: trimmed

                _selectedWifiSsid.value = matchedSsid
                _prerequisiteStep.value = PrerequisiteResolutionStep.AWAITING_WIFI_PASSWORD_INPUT
                return "Iss Wi-Fi ($matchedSsid) ka password bataiye, main khud type kar ke connect kar deta hoon."
            }

            PrerequisiteResolutionStep.AWAITING_WIFI_PASSWORD_INPUT -> {
                val ssid = _selectedWifiSsid.value.ifBlank { "Home_5G" }
                val spokenPassword = trimmed
                    .replace(Regex("""^(?:password\s+hai|password\s+is|mera\s+password)\s+""", RegexOption.IGNORE_CASE), "")
                    .replace(" ", "")
                    .trim()

                bridge.connectWifiWithPassword(ssid, spokenPassword)
                _isSimulatedOffline.value = false
                _isNetworkOnline.value = true
                _prerequisiteStep.value = PrerequisiteResolutionStep.NONE

                val resumedReply = executePendingTaskNow(context, bridge, actionExecutor)
                onAutoResumeCompleted?.invoke(trimmed, resumedReply)
                return "Maine Accessibility Service ke zariye '$ssid' mein password type kar ke Wi-Fi connect kar diya hai! Ab aapka ruka hua kaam mukammal kar raha hoon: $resumedReply"
            }

            PrerequisiteResolutionStep.AWAITING_CONTACT_DISAMBIGUATION -> {
                val candidates = _disambiguationContacts.value
                val chosen = candidates.firstOrNull { c ->
                    lower.contains(c.name.lowercase()) ||
                        c.name.lowercase().split(" ").any { part -> part.length >= 3 && lower.contains(part) }
                } ?: candidates.firstOrNull()

                if (chosen != null) {
                    val pending = _pendingTaskRef.value
                    _prerequisiteStep.value = PrerequisiteResolutionStep.NONE
                    _disambiguationContacts.value = emptyList()
                    _pendingTaskRef.value = null
                    if (pending != null) {
                        val outcome = JarvisHandsFreeEngine.executeJarvisSendMessage(
                            context = context,
                            app = "whatsapp",
                            contactName = chosen.name,
                            phoneNumberOrUsername = chosen.whatsappNumber.ifBlank { chosen.phoneNumber },
                            messageText = pending.messageOrExtra.ifBlank { "Assalam-o-Alaikum" },
                            bridge = bridge,
                            actionExecutor = actionExecutor
                        )
                        return outcome.spokenConfirmationHint
                    }
                }
            }

            PrerequisiteResolutionStep.NONE -> Unit
        }

        // 2. If currently OFFLINE and the user requests an ONLINE task, trigger Step A!
        val isOnlineNow = refreshOnlineState(context)
        if (!isOnlineNow && requiresInternetConnection(trimmed)) {
            val taskCategory = when {
                lower.contains("whatsapp") -> "WHATSAPP_MESSAGE"
                lower.contains("youtube") -> "YOUTUBE_PLAY"
                lower.contains("image") || lower.contains("tasveer") -> "AI_IMAGE"
                else -> "GOOGLE_SEARCH"
            }
            _pendingTaskRef.value = PendingBlockedTask(
                originalUtterance = trimmed,
                taskCategory = taskCategory,
                targetNameOrQuery = trimmed
            )
            _prerequisiteStep.value = PrerequisiteResolutionStep.AWAITING_WIFI_OR_DATA_CONFIRMATION
            return "Iss waqt aapke mobile par internet connect nahi hai. Kya main aapka Wi-Fi ya Mobile Data on kar doon?"
        }

        // 3. If currently OFFLINE and the task is an OFFLINE hardware/call/app command, execute locally!
        if (!isOnlineNow) {
            val offlineAction = OfflineCommandEngine().parseCommand(trimmed)
            if (offlineAction.action != "general_chat") {
                val execResult = actionExecutor.execute(offlineAction)
                return when (execResult) {
                    is ExecutionResult.Success -> offlineAction.spokenResponse.ifBlank { execResult.message }
                    is ExecutionResult.Failure -> execResult.error
                }
            }
        }

        return null
    }

    /**
     * Checks if multiple contacts match a partial name (e.g. "Ali") and starts disambiguation if needed.
     */
    fun findMatchingContactsForDisambiguation(context: Context, query: String): List<SmartContactEntry> {
        val clean = query.trim().lowercase()
        if (clean.isEmpty()) return emptyList()
        val all = SmartContactAndAppRegistry.getAllContacts(context)
        val exact = all.filter { it.name.equals(clean, ignoreCase = true) }
        if (exact.size == 1) return exact
        return all.filter { entry ->
            entry.name.lowercase().contains(clean) ||
                clean.contains(entry.name.lowercase())
        }
    }

    fun startContactDisambiguation(
        originalUtterance: String,
        messageText: String,
        candidates: List<SmartContactEntry>
    ): String {
        _pendingTaskRef.value = PendingBlockedTask(
            originalUtterance = originalUtterance,
            taskCategory = "WHATSAPP_MESSAGE",
            targetNameOrQuery = candidates.firstOrNull()?.name.orEmpty(),
            messageOrExtra = messageText
        )
        _disambiguationContacts.value = candidates
        _prerequisiteStep.value = PrerequisiteResolutionStep.AWAITING_CONTACT_DISAMBIGUATION
        val namesList = candidates.joinToString(", ") { "${it.name} (${it.phoneNumber})" }
        return "Aapke contacts mein ${candidates.size} milte julte naam hain: $namesList. Aap kis ko message bhejna chahte hain?"
    }

    private fun executePendingTaskNow(
        context: Context,
        bridge: AndroidBridge,
        actionExecutor: ActionExecutor
    ): String {
        val pending = _pendingTaskRef.value ?: return "Internet connect ho gaya hai, ab aap apna hukum bata sakte hain."
        _pendingTaskRef.value = null

        return when (pending.taskCategory) {
            "WHATSAPP_MESSAGE" -> {
                val nlu = DeterministicNluEngine.analyzeTextDeterministicallyLocal(context, pending.originalUtterance)
                val waTask = nlu.tasksToExecute.firstOrNull { it.taskType == NluTaskType.WHATSAPP_MESSAGE }
                val outcome = JarvisHandsFreeEngine.executeJarvisSendMessage(
                    context = context,
                    app = "whatsapp",
                    contactName = waTask?.target ?: "Ali",
                    phoneNumberOrUsername = "",
                    messageText = waTask?.actionValue ?: pending.originalUtterance,
                    bridge = bridge,
                    actionExecutor = actionExecutor
                )
                outcome.spokenConfirmationHint
            }
            "YOUTUBE_PLAY" -> {
                val outcome = JarvisHandsFreeEngine.executeJarvisAppCommander(
                    context = context,
                    appName = "youtube",
                    commandType = "play_video",
                    searchQueryOrTarget = pending.originalUtterance,
                    extraData = "",
                    bridge = bridge,
                    actionExecutor = actionExecutor
                )
                outcome.spokenConfirmationHint
            }
            else -> {
                actionExecutor.execute(
                    VoiceAction(
                        action = "google_search",
                        target = pending.originalUtterance
                    )
                )
                "Maine Google Search par '${pending.originalUtterance}' ki live maloomat open kar di hain."
            }
        }
    }
}

package com.example.engine

import android.content.Context
import org.json.JSONObject
import java.util.Locale

enum class DetectedLanguage(val label: String, val ttsLocale: Locale) {
    ENGLISH("English", Locale.US),
    URDU_NASTALIQ("Urdu (اردو)", Locale.forLanguageTag("ur-PK")),
    ROMAN_URDU("Roman Urdu", Locale.forLanguageTag("en-PK")),
    MIXED("Urdu-English Mixed", Locale.forLanguageTag("en-PK"))
}

data class FewShotExample(
    val command: String,
    val language: String,
    val toolName: String,
    val argsSummary: String,
    val expectedReply: String
)

object LanguageProcessor {

    private val URDU_UNICODE_REGEX = Regex("[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]")

    private val URDU_SCRIPT_DIGITS = mapOf(
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
        '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9'
    )

    private val ROMAN_URDU_MARKERS = setOf(
        "karo", "kro", "karna", "krna", "kardo", "krdo", "kar", "bhai", "baji", "ammi", "ami", "abbu", "abu",
        "shukriya", "baje", "bja", "khol", "kholo", "chalao", "chlao", "sunao", "laga", "lagao", "lgao",
        "batao", "mujhe", "tum", "aap", "theek", "thik", "acha", "achha", "hain", "hai", "nahi", "nahin",
        "nhi", "kya", "kia", "kaise", "kese", "kal", "parson", "subah", "shaam", "sham", "raat", "dopahar",
        "ko", "ka", "ki", "ke", "se", "aur", "phir", "fir", "ab", "abhi", "mera", "meri", "mere", "apna",
        "waqt", "kitna", "kitne", "kon", "koun", "kahan", "kidhar", "band", "bnd", "roshni", "awaz", "awaaz",
        "bhejo", "bhej", "parho", "parh", "dikhao", "rasta", "raaste", "shuru", "hoon", "main", "mein",
        "yaad", "matlab", "batti", "jalao", "bujhao", "agar", "kaat", "baad", "karunga", "chachu",
        "jee", "ji", "hazir", "hukum", "karein", "karen", "bataiye", "batayein", "sun", "raha", "rahi",
        "hogaya", "hoga", "diya", "pehle", "pehli", "doosri", "dusri", "sim", "rupay", "rupee", "paise"
    )

    private val ROMAN_TO_URDU_CONVERSATIONAL_LEXICON = linkedMapOf(
        "assalam-o-alaikum" to "السلام علیکم",
        "assalam o alaikum" to "السلام علیکم",
        "walaikum assalam" to "وعلیکم السلام",
        "jee main hazir hoon" to "جی میں حاضر ہوں",
        "main hazir hoon" to "میں حاضر ہوں",
        "hukum karein" to "حکم کریں",
        "kaam ho gaya hai" to "کام ہو گیا ہے",
        "ho gaya hai" to "ہو گیا ہے",
        "kar diya hai" to "کر دیا ہے",
        "bhej diya hai" to "بھیج دیا ہے",
        "laga diya hai" to "لگا دیا ہے",
        "khol diya hai" to "کھول دیا ہے",
        "band kar diya hai" to "بند کر دیا ہے",
        "on kar diya hai" to "آن کر دیا ہے",
        "off kar diya hai" to "آف کر دیا ہے",
        "kya aap" to "کیا آپ",
        "chahte hain" to "چاہتے ہیں",
        "shukriya" to "شکریہ",
        "meherbani" to "مہربانی",
        "ammi" to "امی",
        "abbu" to "ابو",
        "bhai" to "بھائی",
        "baji" to "باجی",
        "subah" to "صبح",
        "shaam" to "شام",
        "raat" to "رات",
        "baje" to "بجے",
        "aur" to "اور",
        "mein" to "میں",
        "main" to "میں",
        "hoon" to "ہوں",
        "hai" to "ہے",
        "hain" to "ہیں",
        "nahi" to "نہیں",
        "theek" to "ٹھیک",
        "jee" to "جی"
    )

    private val URDU_TO_ROMAN_CONVERSATIONAL_LEXICON = linkedMapOf(
        "السلام علیکم" to "Assalam-o-Alaikum",
        "وعلیکم السلام" to "Walaikum Assalam",
        "جی میں حاضر ہوں" to "Jee main hazir hoon",
        "میں حاضر ہوں" to "Main hazir hoon",
        "حکم کریں" to "Hukum karein",
        "کام ہو گیا ہے" to "Kaam ho gaya hai",
        "ہو گیا ہے" to "ho gaya hai",
        "کر دیا ہے" to "kar diya hai",
        "بھیج دیا ہے" to "bhej diya hai",
        "لگا دیا ہے" to "laga diya hai",
        "کھول دیا ہے" to "khol diya hai",
        "بند کر دیا ہے" to "band kar diya hai",
        "کیا آپ" to "Kya aap",
        "چاہتے ہیں" to "chahte hain",
        "شکریہ" to "Shukriya",
        "مہربانی" to "Meherbani",
        "امی" to "Ammi",
        "ابو" to "Abbu",
        "بھائی" to "Bhai",
        "باجی" to "Baji",
        "صبح" to "subah",
        "شام" to "shaam",
        "رات" to "raat",
        "ساڑھے" to "saarhe",
        "بجے" to "baje",
        "روپے" to "rupay",
        "کال" to "call",
        "میسج" to "message",
        "واٹس ایپ" to "WhatsApp",
        "علی" to "Ali",
        "احمد" to "Ahmed",
        "اور" to "aur",
        "میں" to "mein",
        "ہوں" to "hoon",
        "ہے" to "hai",
        "ہیں" to "hain",
        "نہیں" to "nahi",
        "ٹھیک" to "theek",
        "جی" to "Jee",
        "کو" to "ko",
        "کا" to "ka",
        "کی" to "ki",
        "کے" to "ke",
        "سے" to "se",
        "پر" to "par"
    )

    private val ENGLISH_MARKERS = setOf(
        "the", "is", "at", "which", "on", "in", "it", "to", "for", "with", "from",
        "i", "i'll", "you", "he", "she", "we", "they", "will", "be", "late", "busy", "now",
        "please", "turn", "set", "make", "what", "where", "when", "how", "and", "a",
        "my", "your", "his", "her", "can", "could", "would", "should", "send",
        "message", "open", "flashlight", "wifi", "bluetooth",
        "screen", "battery", "saver", "navigation", "if", "reject", "later", "silent"
    )

    private val URDU_NUMERAL_MAP = linkedMapOf(
        "paintalis" to "45", "pentalis" to "45", "پینتالیس" to "45",
        "saath" to "60", "ساٹھ" to "60",
        "tees" to "30", "tis" to "30", "تیس" to "30",
        "bees" to "20", "bis" to "20", "بیس" to "20",
        "pandrah" to "15", "pandra" to "15", "پندرہ" to "15",
        "barah" to "12", "بارہ" to "12",
        "gyarah" to "11", "گیارہ" to "11",
        "das" to "10", "dus" to "10", "دس" to "10",
        "nau" to "9", "نو" to "9",
        "aath" to "8", "ath" to "8", "آٹھ" to "8",
        "saat" to "7", "sat" to "7", "سات" to "7",
        "chey" to "6", "che" to "6", "chay" to "6", "چھ" to "6",
        "paanch" to "5", "panch" to "5", "پانچ" to "5",
        "char" to "4", "chaar" to "4", "چار" to "4",
        "teen" to "3", "tin" to "3", "تین" to "3",
        "do" to "2", "doh" to "2", "دو" to "2",
        "ek" to "1", "aik" to "1", "ایک" to "1"
    )

    private val NICKNAME_MAP = mutableMapOf(
        "ammi" to "Mom",
        "ami" to "Mom",
        "ammi jaan" to "Mom",
        "valida" to "Mom",
        "امی" to "Mom",
        "abbu" to "Dad",
        "abu" to "Dad",
        "abbu jaan" to "Dad",
        "walid" to "Dad",
        "ابو" to "Dad",
        "bhai" to "Brother",
        "bhaiya" to "Brother",
        "بھائی" to "Brother",
        "baji" to "Sister",
        "aapi" to "Sister",
        "باجی" to "Sister",
        "chachu" to "Uncle Tariq",
        "چاچو" to "Uncle Tariq"
    )

    private val SCRIPT_NAME_EQUIVALENTS = linkedMapOf(
        "واٹس ایپ" to "WhatsApp",
        "یوٹیوب" to "YouTube",
        "ٹیلی گرام" to "Telegram",
        "گوگل میپس" to "Google Maps",
        "گوگل" to "Google",
        "کیمرہ" to "Camera",
        "سیٹنگز" to "Settings",
        "فلیش لائٹ" to "flashlight",
        "بلوٹوتھ" to "bluetooth",
        "وائی فائی" to "wifi",
        "الارم" to "alarm",
        "ٹائمر" to "timer",
        "سکرین شاٹ" to "screenshot",
        "علی" to "Ali",
        "احمد" to "Ahmed",
        "محمد" to "Muhammad",
        "فاطمہ" to "Fatima",
        "زین" to "Zain",
        "بلال" to "Bilal",
        "حسن" to "Hassan",
        "عائشہ" to "Ayesha",
        "وجاہت" to "Wajahat",
        "عثمان" to "Usman",
        "حمزہ" to "Hamza"
    )

    private val dynamicSttCorrections = mutableMapOf(
        "col" to "call",
        "kol" to "call",
        "caal" to "call",
        "kall" to "call",
        "watsap" to "WhatsApp",
        "whatsap" to "WhatsApp",
        "wotzap" to "WhatsApp",
        "masij" to "message",
        "msj" to "message",
        "msg" to "message",
        "mesij" to "message",
        "torch" to "flashlight",
        "roshni" to "flashlight",
        "alram" to "alarm",
        "ullarm" to "alarm",
        "alaram" to "alarm",
        "blootooth" to "bluetooth",
        "blutooth" to "bluetooth",
        "waifai" to "wifi",
        "nevegat" to "navigate",
        "kro" to "karo",
        "krna" to "karna",
        "kia" to "kya",
        "nhi" to "nahi",
        "nahin" to "nahi",
        "bja" to "baje",
        "bje" to "baje"
    )

    private val fewShotDataset = mutableListOf(
        FewShotExample("Ali ko call karo", "ROMAN_URDU", "make_call", "contact_name=Ali", "Kya aap Ali ko call karna chahte hain?"),
        FewShotExample("علی کو کال کرو", "URDU_NASTALIQ", "make_call", "contact_name=Ali", "کیا آپ علی کو کال کرنا چاہتے ہیں؟"),
        FewShotExample("Ammi ko message send karo ke main raste mein hoon", "MIXED", "send_sms", "recipient=Mom, message_text=main raste mein hoon", "Kya main Ammi ko message bhej doon?"),
        FewShotExample("WhatsApp khol kar Ahmed ko message send karo ke I'll be late", "MIXED", "send_whatsapp", "recipient=Ahmed, message_text=I'll be late", "Ahmed ko WhatsApp message bhejoon?"),
        FewShotExample("Kal subah 6 baje alarm laga do", "ROMAN_URDU", "set_alarm", "hour=6, minute=0", "Subah 06:00 baje ka alarm set kar diya."),
        FewShotExample("Subah 7 baje alarm lagao, phir Ammi ko message karo ke main late hoon, aur raaste ka navigation shuru karo", "MIXED", "execute_multi_step_plan", "steps=set_alarm:07:00|send_sms:Mom:main late hoon|navigate:Home", "Subah 7 baje ka alarm lag gaya, Ammi ko message bhej diya, aur navigation shuru kar di."),
        FewShotExample("Mera phone silent karo, battery saver on karo, aur 30 minute ka timer lagao", "ROMAN_URDU", "execute_multi_step_plan", "steps=adjust_volume:mute|toggle_battery_saver:on|set_timer:1800", "Phone silent kar diya, battery saver on ho gaya, aur 30 minute ka timer shuru kar diya."),
        FewShotExample("If Ali calls, reject it and send him 'I'll call you later'", "ENGLISH", "save_conditional_rule", "trigger=incoming_call:Ali, actions=end_call|send_sms:Ali:I'll call you later", "Rule saved: If Ali calls, I'll reject it and text him 'I'll call you later'.")
    )

    /**
     * Optionally loads asset JSON datasets (`stt_corrections.json`, `commands_dataset.json`, `vocabulary.json`)
     * at app startup while keeping in-memory defaults available for unit tests.
     */
    fun initializeWithContext(context: Context) {
        try {
            val sttJson = context.assets.open("stt_corrections.json").bufferedReader().use { it.readText() }
            val sttRoot = JSONObject(sttJson)
            val arr = sttRoot.optJSONArray("corrections")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val mis = obj.optString("misrecognition").trim()
                    val cor = obj.optString("correction").trim()
                    if (mis.isNotEmpty() && cor.isNotEmpty()) {
                        dynamicSttCorrections[mis.lowercase()] = cor
                    }
                }
            }
        } catch (_: Exception) {
        }

        try {
            val cmdJson = context.assets.open("commands_dataset.json").bufferedReader().use { it.readText() }
            val cmdRoot = JSONObject(cmdJson)
            val exArr = cmdRoot.optJSONArray("examples")
            if (exArr != null && exArr.length() > 0) {
                fewShotDataset.clear()
                for (i in 0 until exArr.length()) {
                    val obj = exArr.optJSONObject(i) ?: continue
                    val toolObj = obj.optJSONObject("tool_call")
                    val toolName = toolObj?.optString("name") ?: "general_chat"
                    val argsObj = toolObj?.optJSONObject("args")
                    fewShotDataset.add(
                        FewShotExample(
                            command = obj.optString("command"),
                            language = obj.optString("language", "ROMAN_URDU"),
                            toolName = toolName,
                            argsSummary = argsObj?.toString() ?: "{}",
                            expectedReply = obj.optString("expected_reply")
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Registers a custom nickname or personal vocabulary phrase at runtime.
     */
    fun registerCustomNickname(alias: String, targetName: String) {
        if (alias.isNotBlank() && targetName.isNotBlank()) {
            NICKNAME_MAP[alias.lowercase().trim()] = targetName.trim()
        }
    }

    /**
     * Retrieves the top K most relevant few-shot examples from `commands_dataset.json`
     * based on token overlap with the user's normalized utterance.
     */
    fun retrieveFewShotExamples(query: String, topK: Int = 5): List<FewShotExample> {
        val queryTokens = query.lowercase().split(Regex("[\\s,?.!]+")).filter { it.length > 1 }.toSet()
        if (queryTokens.isEmpty()) return fewShotDataset.take(topK)

        return fewShotDataset
            .map { ex ->
                val exTokens = ex.command.lowercase().split(Regex("[\\s,?.!]+")).toSet()
                val overlap = queryTokens.intersect(exTokens).size
                ex to overlap
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(topK)
    }

    /**
     * Returns `true` if the text contains predominantly Right-to-Left (Urdu Nastaliq / Arabic) script (`F-126`–`F-128`).
     */
    fun isRtlScript(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val urduCharCount = trimmed.count { ch -> URDU_UNICODE_REGEX.matches(ch.toString()) }
        val totalLetters = trimmed.count { it.isLetter() }
        return urduCharCount > 0 && (totalLetters == 0 || (urduCharCount.toDouble() / totalLetters) >= 0.25)
    }

    /**
     * Transliterates common Roman Urdu conversational phrases into Urdu Nastaliq script so that
     * `ur-PK` TTS engines pronounce them with authentic Urdu phonetics rather than English spelling (`F-015`, `F-046`).
     */
    fun transliterateRomanUrduToUrduScript(text: String): String {
        var result = text
        for ((roman, urdu) in ROMAN_TO_URDU_CONVERSATIONAL_LEXICON) {
            result = result.replace(
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(roman)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE),
                urdu
            )
        }
        return result
    }

    /**
     * Transliterates Urdu Nastaliq script into phonetic Roman Urdu so that devices missing the
     * `ur-PK` offline voice pack can still speak replies aloud using `en-PK` / `en-IN` / `en-US` TTS (`F-052`).
     */
    fun transliterateUrduScriptToRomanUrdu(text: String): String {
        var result = text
        for ((urdu, roman) in URDU_TO_ROMAN_CONVERSATIONAL_LEXICON) {
            result = result.replace(urdu, " $roman ")
        }
        val sb = StringBuilder(result.length)
        for (ch in result) {
            sb.append(URDU_SCRIPT_DIGITS[ch] ?: ch)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Detects input language style (Urdu Nastaliq, Roman Urdu, English, or Mixed).
     */
    fun detectLanguage(text: String): DetectedLanguage {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return DetectedLanguage.ENGLISH

        val urduCharCount = trimmed.count { char -> URDU_UNICODE_REGEX.matches(char.toString()) }
        val totalLetters = trimmed.count { it.isLetter() }

        if (totalLetters > 0 && (urduCharCount.toDouble() / totalLetters) >= 0.30) {
            return DetectedLanguage.URDU_NASTALIQ
        }

        val tokens = trimmed.lowercase().split(Regex("[\\s,?.!:'\"]+")).filter { it.isNotBlank() }
        var romanUrduTokens = 0
        var englishTokens = 0

        for (t in tokens) {
            val isRomanUrdu = ROMAN_URDU_MARKERS.contains(t)
            val isEnglish = ENGLISH_MARKERS.contains(t)
            if (isRomanUrdu) romanUrduTokens++
            if (isEnglish && !isRomanUrdu) englishTokens++
        }

        return when {
            romanUrduTokens >= 1 && englishTokens >= 2 -> DetectedLanguage.MIXED
            romanUrduTokens >= 1 -> DetectedLanguage.ROMAN_URDU
            else -> DetectedLanguage.ENGLISH
        }
    }

    /**
     * Normalizes bilingual input by:
     * - Converting Urdu script digits (`۰-۹`) to ASCII digits (`0-9`)
     * - Cross-script matching for contacts and apps (`علی` -> `Ali`, `واٹس ایپ` -> `WhatsApp`)
     * - Fixing STT phonetic mistakes (`watsap` -> `WhatsApp`, `masij` -> `message`, `col` -> `call`)
     * - Converting spoken Urdu/Roman Urdu numbers & time phrases (`sarhe 7 baje`, `subah 6 baje`, `دو بجے`) into HH:mm / digits
     */
    fun normalizeInput(rawInput: String): String {
        var normalized = rawInput.trim()
        if (normalized.isEmpty()) return normalized

        // 0. Convert Eastern Arabic / Urdu script digits to standard ASCII digits
        val sb = StringBuilder(normalized.length)
        for (ch in normalized) {
            sb.append(URDU_SCRIPT_DIGITS[ch] ?: ch)
        }
        normalized = sb.toString()

        // 1. Script cross-matching for common names and apps
        SCRIPT_NAME_EQUIVALENTS.forEach { (urdu, english) ->
            normalized = normalized.replace(urdu, english, ignoreCase = true)
        }

        // 2. Apply STT misrecognition dictionary (word-boundary safe)
        dynamicSttCorrections.forEach { (mis, cor) ->
            if (!mis.contains(" ")) {
                normalized = normalized.replace(Regex("\\b${Regex.escape(mis)}\\b", RegexOption.IGNORE_CASE), cor)
            } else {
                normalized = normalized.replace(mis, cor, ignoreCase = true)
            }
        }

        // 3. Handle "dedh" (1:30), "dhai" (2:30), "sarhe" / "ساڑھے" (half past), "sawa" / "سوا" (quarter past), and "paune" / "پونے" (quarter to)
        normalized = normalized.replace(
            Regex("\\b(?:dedh|dearh|ڈیڑھ)\\s*(?:baje|bja|بجے)?\\b", RegexOption.IGNORE_CASE),
            "1:30"
        )
        normalized = normalized.replace(
            Regex("\\b(?:dhai|dhaai|ڈھائی)\\s*(?:baje|bja|بجے)?\\b", RegexOption.IGNORE_CASE),
            "2:30"
        )
        URDU_NUMERAL_MAP.forEach { (word, digit) ->
            normalized = normalized.replace(
                Regex("\\b(?:sarhe|saarhe|ساڑھے)\\s+$word\\s*(?:baje|bja|بجے)?\\b", RegexOption.IGNORE_CASE),
                "$digit:30"
            )
            normalized = normalized.replace(
                Regex("\\b(?:sawa|سوا)\\s+$word\\s*(?:baje|bja|بجے)?\\b", RegexOption.IGNORE_CASE),
                "$digit:15"
            )
            val hourInt = digit.toIntOrNull()
            if (hourInt != null && hourInt in 1..12) {
                val prevHour = if (hourInt == 1) 12 else hourInt - 1
                normalized = normalized.replace(
                    Regex("\\b(?:paune|pune|پونے)\\s+$word\\s*(?:baje|bja|بجے)?\\b", RegexOption.IGNORE_CASE),
                    "$prevHour:45"
                )
            }
            normalized = normalized.replace(
                Regex("\\b$word\\s+(?:sau|so|سو)\\b", RegexOption.IGNORE_CASE),
                "${digit}00"
            )
            normalized = normalized.replace(
                Regex("\\b$word\\s+(?:hazaar|hazar|ہزار)\\b", RegexOption.IGNORE_CASE),
                "${digit}000"
            )
        }
        normalized = normalized.replace(
            Regex("\\b(?:sarhe|saarhe|ساڑھے)\\s+(\\d{1,2})\\s*(?:baje|bja|بجے)?\\b", RegexOption.IGNORE_CASE),
            "$1:30"
        )
        normalized = normalized.replace(
            Regex("\\b(?:sawa|سوا)\\s+(\\d{1,2})\\s*(?:baje|bja|بجے)?\\b", RegexOption.IGNORE_CASE),
            "$1:15"
        )
        normalized = normalized.replace(
            Regex("\\b(?:paune|pune|پونے)\\s+(\\d{1,2})\\s*(?:baje|bja|بجے)?\\b", RegexOption.IGNORE_CASE)
        ) { mr ->
            val h = mr.groupValues[1].toIntOrNull() ?: 1
            val prevHour = if (h == 1) 12 else h - 1
            "$prevHour:45"
        }

        // 4. Spoken time conversion: e.g. "subah 6 baje" -> "06:00", "دو بجے" -> "2:00"
        URDU_NUMERAL_MAP.forEach { (word, digit) ->
            normalized = normalized.replace(Regex("\\b$word\\s+(?:baje|bja|بجے)\\b", RegexOption.IGNORE_CASE), "$digit:00")
            normalized = normalized.replace(Regex("\\b$word\\s+(?:minute|minutes|min|منٹ)\\b", RegexOption.IGNORE_CASE), "$digit minutes")
            normalized = normalized.replace(Regex("\\b$word\\b", RegexOption.IGNORE_CASE), digit)
        }

        // Also convert "<digit> بجے" or "<digit> baje" where digit is already numeric
        normalized = normalized.replace(Regex("\\b(\\d{1,2})\\s+(?:baje|bja|بجے)\\b", RegexOption.IGNORE_CASE), "$1:00")

        // 5. Adjust AM/PM for "subah" (morning), "dopahar" (afternoon), "shaam" (evening), "raat" (night)
        normalized = normalized.replace(Regex("(?:subah|morning|صبح)\\s*(\\d{1,2}):(\\d{2})", RegexOption.IGNORE_CASE)) { mr ->
            val hour = mr.groupValues[1].toIntOrNull() ?: 6
            val min = mr.groupValues[2].toIntOrNull() ?: 0
            String.format(Locale.US, "%02d:%02d", hour, min)
        }
        normalized = normalized.replace(Regex("(?:shaam|raat(?:\\s+ko)?|dopahar|evening|night|شام|رات)\\s*(\\d{1,2}):(\\d{2})", RegexOption.IGNORE_CASE)) { mr ->
            var hour = mr.groupValues[1].toIntOrNull() ?: 8
            val min = mr.groupValues[2].toIntOrNull() ?: 0
            if (hour in 1..11) hour += 12
            String.format(Locale.US, "%02d:%02d", hour, min)
        }

        return normalized
    }

    /**
     * Fuzzy cross-script contact & app name matcher ("علی" == "Ali" == "Aly", "احمد" == "Ahmed" == "Ahmad").
     */
    fun canonicalizeEntityName(rawName: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return trimmed

        SCRIPT_NAME_EQUIVALENTS[trimmed]?.let { return it }
        NICKNAME_MAP[trimmed.lowercase()]?.let { return it }

        val lower = trimmed.lowercase()
        return when {
            lower == "aly" || lower == "alee" || lower == "ali" -> "Ali"
            lower == "ahmad" || lower == "ahmed" || lower == "ahmd" -> "Ahmed"
            lower == "mohammad" || lower == "muhammad" || lower == "mohd" -> "Muhammad"
            lower == "fatimah" || lower == "fatima" -> "Fatima"
            lower == "watsapp" || lower == "whatsap" || lower == "whatsapp" -> "WhatsApp"
            lower == "utube" || lower == "youtube" -> "YouTube"
            else -> resolveContactNickname(trimmed)
        }
    }

    /**
     * Resolves contact targets considering Urdu nicknames ("Ammi", "Abbu", "Bhai", etc.).
     */
    fun resolveContactNickname(rawName: String): String {
        val lower = rawName.lowercase().trim()
        return NICKNAME_MAP[lower] ?: rawName.trim()
    }

    /**
     * Produces a natural spoken confirmation or reply formatted in the exact language of the user.
     * Enforces the strict rule:
     * - English in -> English out
     * - Urdu Nastaliq in -> Urdu Nastaliq out
     * - Roman Urdu or Mixed in -> natural Roman Urdu / Mixed out
     */
    fun formatReplyInLanguage(
        englishText: String,
        romanUrduText: String,
        urduNastaliqText: String,
        detectedLang: DetectedLanguage
    ): String {
        return when (detectedLang) {
            DetectedLanguage.URDU_NASTALIQ -> urduNastaliqText
            DetectedLanguage.ROMAN_URDU, DetectedLanguage.MIXED -> romanUrduText
            DetectedLanguage.ENGLISH -> englishText
        }
    }
}

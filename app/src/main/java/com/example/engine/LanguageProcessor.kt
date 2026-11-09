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
        "hogaya", "hoga", "diya", "pehle", "pehli", "doosri", "dusri", "sim", "rupay", "rupee", "paise",
        "bilkul", "zaroor", "foran", "apni", "apne", "aapka", "aapki", "aapke", "bolein", "bolain",
        "likhein", "dekhein", "samajh", "sakta", "sakti", "sakte", "chuka", "chuki", "chukay", "gaya",
        "gayi", "gaye", "liye", "sath", "saath", "walay", "wala", "wali", "lekin", "magar", "kyunke",
        "bohat", "bahut", "zyada", "kam", "jaldi", "aaram", "sukoon", "pareshan", "khush", "shandar",
        "wapas", "wapis", "jao", "aao", "pichli", "pichle", "purani", "purana", "peeche", "piche",
        "par", "pe", "iss", "usi", "khul", "kholen", "mehfooz", "koshish", "wapsi"
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
        "ho gayi hai" to "ہو گئی ہے",
        "kar diya gaya hai" to "کر دیا گیا ہے",
        "kar diya hai" to "کر دیا ہے",
        "kar di hai" to "کر دی ہے",
        "bhej diya hai" to "بھیج دیا ہے",
        "bhej raha hoon" to "بھیج رہا ہوں",
        "laga diya hai" to "لگا دیا ہے",
        "khol diya hai" to "کھول دیا ہے",
        "khol raha hoon" to "کھول رہا ہوں",
        "band kar diya hai" to "بند کر دیا ہے",
        "on kar diya hai" to "آن کر دیا ہے",
        "off kar diya hai" to "آف کر دیا ہے",
        "open kar diya hai" to "اوپن کر دیا ہے",
        "open kar raha hoon" to "اوپن کر رہا ہوں",
        "call mila raha hoon" to "کال ملا رہا ہوں",
        "search kar raha hoon" to "سرچ کر رہا ہوں",
        "chala raha hoon" to "چلا رہا ہوں",
        "tayyar kar raha hoon" to "تیار کر رہا ہوں",
        "kya aap" to "کیا آپ",
        "chahte hain" to "چاہتے ہیں",
        "aap ki voice recognize nahi ho rahi" to "آپ کی آواز پہچانی نہیں جا رہی",
        "aap ki awaz verify ho gayi hai" to "آپ کی آواز تصدیق ہو گئی ہے",
        "secret code bolein ya type karein" to "سیکرٹ کوڈ بولیں یا ٹائپ کریں",
        "verify ho gaya" to "تصدیق ہو گیا",
        "unlock kar diya gaya hai" to "ان لاک کر دیا گیا ہے",
        "pareshan na hon" to "پریشان نہ ہوں",
        "main aap ke sath hoon" to "میں آپ کے ساتھ ہوں",
        "foran kar raha hoon" to "فوراً کر رہا ہوں",
        "jee bilkul" to "جی بالکل",
        "ji bilkul" to "جی بالکل",
        "shukriya" to "شکریہ",
        "meherbani" to "مہربانی",
        "baraye meherbani" to "برائے مہربانی",
        "ammi" to "امی",
        "abbu" to "ابو",
        "bhai" to "بھائی",
        "baji" to "باجی",
        "janab" to "جناب",
        "subah" to "صبح",
        "dopahar" to "دوپہر",
        "shaam" to "شام",
        "raat" to "رات",
        "baje" to "بجے",
        "aapka" to "آپ کا",
        "aapki" to "آپ کی",
        "aapke" to "آپ کے",
        "aap" to "آپ",
        "mujhe" to "مجھے",
        "mera" to "میرا",
        "meri" to "میری",
        "mere" to "میرے",
        "apna" to "اپنا",
        "apni" to "اپنی",
        "apne" to "اپنے",
        "aur" to "اور",
        "lekin" to "لیکن",
        "magar" to "مگر",
        "agar" to "اگر",
        "phir" to "پھر",
        "abhi" to "ابھی",
        "foran" to "فوراً",
        "bilkul" to "بالکل",
        "zaroor" to "ضرور",
        "bohat" to "بہت",
        "bahut" to "بہت",
        "zyada" to "زیادہ",
        "shandar" to "شاندار",
        "zabardast" to "زبردست",
        "bolein" to "بولیں",
        "karein" to "کریں",
        "batayein" to "بتائیں",
        "batao" to "بتاؤ",
        "kholo" to "کھولو",
        "lagao" to "لگاؤ",
        "bhejo" to "بھیجو",
        "karo" to "کرو",
        "raha" to "رہا",
        "rahi" to "رہی",
        "rahe" to "رہے",
        "gaya" to "گیا",
        "gayi" to "گئی",
        "gaye" to "گئے",
        "diya" to "دیا",
        "mein" to "میں",
        "main" to "میں",
        "hoon" to "ہوں",
        "hai" to "ہے",
        "hain" to "ہیں",
        "nahi" to "نہیں",
        "nahin" to "نہیں",
        "theek" to "ٹھیک",
        "acha" to "اچھا",
        "jee" to "جی",
        "ji" to "جی",
        "hisaab" to "حساب",
        "mutabiq" to "مطابق",
        "jawab" to "جواب",
        "bilkul" to "بالکل",
        "banta" to "بنتا",
        "ko" to "کو",
        "ka" to "کا",
        "ki" to "کی",
        "ke" to "کے",
        "se" to "سے",
        "par" to "پر",
        "ne" to "نے"
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
        "ساڑھے" to "sarhe",
        "بجے" to "baje",
        "روپے" to "rupees",
        "کال" to "call",
        "میسج" to "message",
        "واٹس ایپ" to "WhatsApp",
        "علی" to "Ali",
        "احمد" to "Ahmed",
        "اور" to "aur",
        "میں" to "main",
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

    /**
     * Maps Roman Urdu words that English/Latin TTS engines typically mispronounce
     * (such as "main" -> "Main Street", "hoon" -> "noon", "kar" -> "car", "hai" -> "high",
     * "aap" -> "app") to phonetically natural Subcontinental pronunciations while leaving
     * real English words intact.
     */
    private val ROMAN_URDU_PHONETIC_PRONUNCIATION_MAP = linkedMapOf(
        "assalam-o-alaikum" to "Assalaam o Alaikum",
        "assalam o alaikum" to "Assalaam o Alaikum",
        "walaikum assalam" to "Walaikum Assalaam",
        "main ne" to "mein ney",
        "main aap" to "mein aap",
        "main aapka" to "mein aap ka",
        "main aapki" to "mein aap ki",
        "main abhi" to "mein abhi",
        "main hazir" to "mein haazir",
        "jee main" to "jee mein",
        "ji main" to "jee mein",
        "ke main" to "key mein",
        "aapka" to "aap ka",
        "aapki" to "aap ki",
        "aapke" to "aap key",
        "aapko" to "aap ko",
        "mujhe" to "mujhey",
        "tumhe" to "tumhey",
        "unhein" to "un-hein",
        "inhein" to "in-hein",
        "karein" to "ka-rein",
        "bolein" to "bo-lein",
        "batayein" to "ba-taayein",
        "batao" to "ba-taao",
        "lagao" to "la-gaao",
        "chalao" to "cha-laao",
        "dikhao" to "di-khaao",
        "sunao" to "su-naao",
        "kholo" to "kho-lo",
        "bhejo" to "bhey-jo",
        "bhej" to "bheyj",
        "hoon" to "huun",
        "hain" to "hein",
        "hai" to "hey",
        "kar" to "karr",
        "karo" to "ka-ro",
        "kardo" to "karr do",
        "hazir" to "haazir",
        "shukriya" to "shuk-riya",
        "meherbani" to "meher-baani",
        "kripya" to "bara-e-meherbaani"
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
     * Reshapes Roman Urdu spelling into natural Subcontinental phonetics so that Latin/English
     * TTS voices (`en-PK`, `en-IN`, `hi-IN`, `en-US`) pronounce Roman Urdu words with authentic
     * Urdu/Hindustani vowels and consonants instead of American English ("Main Street", "car", "noon").
     */
    fun reshapeRomanUrduForNaturalPronunciation(text: String): String {
        var result = text
        for ((rawPhrase, phoneticPhrase) in ROMAN_URDU_PHONETIC_PRONUNCIATION_MAP) {
            result = result.replace(
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(rawPhrase)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE),
                phoneticPhrase
            )
        }
        return result
    }

    fun isRomanUrduToken(token: String): Boolean {
        val clean = token.lowercase(Locale.ROOT).trim { !it.isLetterOrDigit() }
        if (clean.isEmpty()) return false
        return ROMAN_URDU_MARKERS.contains(clean) || ROMAN_TO_URDU_CONVERSATIONAL_LEXICON.containsKey(clean)
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
     * User-configurable language policy mode.
     * In [AUTO] mode, OmniVoice responds in Urdu to Urdu, English to English, and naturally mixed
     * Roman Urdu/English to mixed speech while preserving conversation language across app names,
     * tool names, result codes, and accessibility labels.
     */
    enum class UserLanguagePreferenceMode(val id: String, val displayName: String) {
        AUTO("AUTO", "Auto (Match User Conversation)"),
        URDU_SCRIPT("URDU_SCRIPT", "Urdu (اردو)"),
        ROMAN_URDU_MIXED("ROMAN_URDU_MIXED", "Roman Urdu / Mixed"),
        ENGLISH("ENGLISH", "English");

        companion object {
            fun fromIdOrLabel(raw: String?): UserLanguagePreferenceMode {
                val clean = raw?.trim().orEmpty()
                if (clean.isEmpty()) return AUTO
                return entries.firstOrNull {
                    it.id.equals(clean, ignoreCase = true) ||
                        it.displayName.equals(clean, ignoreCase = true)
                } ?: when {
                    clean.contains("auto", ignoreCase = true) -> AUTO
                    clean.contains("roman", ignoreCase = true) || clean.contains("mixed", ignoreCase = true) -> ROMAN_URDU_MIXED
                    clean.contains("urdu", ignoreCase = true) || clean.contains("اردو") -> URDU_SCRIPT
                    clean.contains("english", ignoreCase = true) -> ENGLISH
                    else -> AUTO
                }
            }
        }
    }

    /**
     * Correlated language decision with explicit audit reason for command diagnostics.
     */
    data class LanguageResolution(
        val language: DetectedLanguage,
        val reason: String
    )

    /**
     * Tokens that represent app names, package identifiers, tool names, status labels,
     * accessibility labels, or wake words. By themselves, these tokens must NEVER switch
     * an ongoing Urdu / Roman Urdu conversation to English.
     */
    private val NON_LINGUISTIC_ENTITY_AND_SYSTEM_TOKENS = setOf(
        "whatsapp", "watsapp", "whatsap", "notepad", "notes", "keep", "youtube", "utube",
        "chrome", "browser", "instagram", "insta", "facebook", "fb", "tiktok", "settings",
        "camera", "gallery", "photos", "calculator", "clock", "alarm", "calendar", "maps",
        "gmail", "email", "play", "store", "playstore", "phone", "dialer", "messages",
        "sms", "contacts", "spotify", "telegram", "snapchat", "netflix", "drive", "files",
        "open_app", "return_to_origin", "navigate", "click", "long_click", "type_text",
        "scroll", "read_screen", "control_hardware", "manage_connectivity_and_settings",
        "verified", "requested_unverified", "missing_app", "needs_setup", "blocked",
        "failed", "success", "error", "none", "null", "true", "false",
        "jarvis", "omni", "omnivoice", "assistant",
        "status", "updates", "chats", "calls", "communities", "recents", "overview",
        "button", "tab", "icon", "toolbar", "header", "search_button", "com", "android",
        "google", "apps", "app", "package", "draft"
    )

    /**
     * Genuine English sentence/command words (verbs, pronouns, question words, prepositions)
     * that indicate the user is actively speaking English rather than just naming an app.
     */
    private val GENUINE_ENGLISH_SENTENCE_MARKERS = setOf(
        "open", "launch", "start", "go", "back", "return", "take", "bring", "me", "to",
        "the", "my", "previous", "last", "where", "was", "working", "please", "can",
        "could", "would", "you", "what", "how", "why", "when", "who", "is", "are", "am",
        "tell", "joke", "turn", "on", "off", "enable", "disable", "show", "close",
        "switch", "hello", "hi", "hey", "thanks", "thank", "good", "morning", "evening",
        "night", "send", "read", "write", "delete", "find", "search", "check", "call"
    )

    /**
     * Detects whether the user utterance explicitly requests switching the conversation language.
     */
    fun detectExplicitLanguageSwitchDirective(text: String): DetectedLanguage? {
        val lower = text.trim().lowercase()
        if (lower.isEmpty()) return null
        return when {
            lower.contains("speak in english") ||
                lower.contains("reply in english") ||
                lower.contains("talk in english") ||
                lower.contains("english mein baat") ||
                lower.contains("english me baat") ||
                lower.contains("english mein جواب") -> DetectedLanguage.ENGLISH
            lower.contains("urdu mein baat") ||
                lower.contains("urdu me baat") ||
                lower.contains("speak in urdu") ||
                lower.contains("reply in urdu") ||
                lower.contains("اردو میں بات") ||
                lower.contains("اردو میں جواب") -> {
                if (text.any { URDU_UNICODE_REGEX.matches(it.toString()) }) {
                    DetectedLanguage.URDU_NASTALIQ
                } else {
                    DetectedLanguage.ROMAN_URDU
                }
            }
            else -> null
        }
    }

    /**
     * Returns a non-null [DetectedLanguage] ONLY when [text] contains genuine linguistic markers
     * (Urdu script, Roman Urdu vocabulary, or genuine English sentence structure).
     * Returns `null` when [text] is blank or consists solely of app names, tool names, result codes,
     * numbers, or accessibility labels so that non-linguistic tokens never overwrite conversation language.
     */
    fun detectConversationalLanguageEvidence(text: String): LanguageResolution? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        detectExplicitLanguageSwitchDirective(trimmed)?.let { explicitLang ->
            return LanguageResolution(explicitLang, "EXPLICIT_USER_LANGUAGE_DIRECTIVE_${explicitLang.name}")
        }

        val urduCharCount = trimmed.count { char -> URDU_UNICODE_REGEX.matches(char.toString()) }
        val totalLetters = trimmed.count { it.isLetter() }
        if (totalLetters > 0 && (urduCharCount.toDouble() / totalLetters) >= 0.25) {
            return LanguageResolution(DetectedLanguage.URDU_NASTALIQ, "UTTERANCE_URDU_SCRIPT")
        }

        val tokens = trimmed.lowercase()
            .split(Regex("[\\s,?.!:'\"()\\[\\]/\\\\-]+"))
            .filter { it.isNotBlank() && !it.all { ch -> ch.isDigit() } }
        if (tokens.isEmpty()) return null

        var romanUrduTokens = 0
        var genuineEnglishSentenceTokens = 0

        for (t in tokens) {
            val isRomanUrdu = ROMAN_URDU_MARKERS.contains(t) || ROMAN_TO_URDU_CONVERSATIONAL_LEXICON.containsKey(t)
            val isSystemOrEntityOnly = NON_LINGUISTIC_ENTITY_AND_SYSTEM_TOKENS.contains(t)
            val isGenuineEnglish = !isRomanUrdu && !isSystemOrEntityOnly &&
                (GENUINE_ENGLISH_SENTENCE_MARKERS.contains(t) || ENGLISH_MARKERS.contains(t))
            if (isRomanUrdu) romanUrduTokens++
            if (isGenuineEnglish) genuineEnglishSentenceTokens++
        }

        return when {
            romanUrduTokens >= 1 && genuineEnglishSentenceTokens >= 2 ->
                LanguageResolution(DetectedLanguage.MIXED, "UTTERANCE_MIXED_ROMAN_URDU_ENGLISH")
            romanUrduTokens >= 1 ->
                LanguageResolution(DetectedLanguage.ROMAN_URDU, "UTTERANCE_ROMAN_URDU")
            genuineEnglishSentenceTokens >= 1 ->
                LanguageResolution(DetectedLanguage.ENGLISH, "UTTERANCE_ENGLISH")
            else -> null
        }
    }

    /**
     * Resolves the reply language and records why it was chosen across cloud responses,
     * local acknowledgements, fallback speech, and errors.
     *
     * Rules:
     * 1. Explicit language switch in the current utterance wins.
     * 2. If the user explicitly selected a non-AUTO language preference, honour it.
     * 3. If the current utterance has genuine Urdu, Roman Urdu, Mixed, or English linguistic evidence,
     *    respond in that language.
     * 4. If the current utterance is empty or contains only an English app name, tool name,
     *    result code, or accessibility label, preserve [conversationLanguage] if present.
     * 5. Otherwise default to [DetectedLanguage.ENGLISH].
     */
    fun resolveReplyLanguage(
        utterance: String,
        preferredMode: UserLanguagePreferenceMode = UserLanguagePreferenceMode.AUTO,
        conversationLanguage: DetectedLanguage? = null
    ): LanguageResolution {
        val trimmed = utterance.trim()

        detectExplicitLanguageSwitchDirective(trimmed)?.let { explicit ->
            return LanguageResolution(explicit, "EXPLICIT_LANGUAGE_SWITCH_${explicit.name}")
        }

        when (preferredMode) {
            UserLanguagePreferenceMode.URDU_SCRIPT ->
                return LanguageResolution(DetectedLanguage.URDU_NASTALIQ, "USER_PREFERENCE_URDU_SCRIPT")
            UserLanguagePreferenceMode.ROMAN_URDU_MIXED ->
                return LanguageResolution(DetectedLanguage.ROMAN_URDU, "USER_PREFERENCE_ROMAN_URDU_MIXED")
            UserLanguagePreferenceMode.ENGLISH ->
                return LanguageResolution(DetectedLanguage.ENGLISH, "USER_PREFERENCE_ENGLISH")
            UserLanguagePreferenceMode.AUTO -> Unit
        }

        val utteranceEvidence = detectConversationalLanguageEvidence(trimmed)
        if (utteranceEvidence != null) {
            return utteranceEvidence
        }

        if (conversationLanguage != null) {
            val contextReason = if (trimmed.isEmpty()) {
                "CONVERSATION_CONTEXT_${conversationLanguage.name}_ON_EMPTY_UTTERANCE"
            } else {
                "CONVERSATION_CONTEXT_${conversationLanguage.name}_PRESERVED_ON_ENTITY_OR_TOOL_TOKEN"
            }
            return LanguageResolution(conversationLanguage, contextReason)
        }

        return LanguageResolution(
            language = DetectedLanguage.ENGLISH,
            reason = if (trimmed.isEmpty()) "DEFAULT_ENGLISH_EMPTY_UTTERANCE" else "DEFAULT_ENGLISH_NO_PRIOR_CONTEXT"
        )
    }

    /**
     * Detects input language style (Urdu Nastaliq, Roman Urdu, English, or Mixed).
     */
    fun detectLanguage(
        text: String,
        fallbackConversationLanguage: DetectedLanguage? = null
    ): DetectedLanguage {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return fallbackConversationLanguage ?: DetectedLanguage.ENGLISH

        val urduCharCount = trimmed.count { char -> URDU_UNICODE_REGEX.matches(char.toString()) }
        val totalLetters = trimmed.count { it.isLetter() }

        if (totalLetters > 0 && (urduCharCount.toDouble() / totalLetters) >= 0.25) {
            return DetectedLanguage.URDU_NASTALIQ
        }

        val tokens = trimmed.lowercase().split(Regex("[\\s,?.!:'\"]+")).filter { it.isNotBlank() }
        var romanUrduTokens = 0
        var englishTokens = 0

        for (t in tokens) {
            val isRomanUrdu = ROMAN_URDU_MARKERS.contains(t) || ROMAN_TO_URDU_CONVERSATIONAL_LEXICON.containsKey(t)
            val isSystemOrEntityOnly = NON_LINGUISTIC_ENTITY_AND_SYSTEM_TOKENS.contains(t)
            val isEnglish = !isRomanUrdu && !isSystemOrEntityOnly &&
                (GENUINE_ENGLISH_SENTENCE_MARKERS.contains(t) || ENGLISH_MARKERS.contains(t))
            if (isRomanUrdu) romanUrduTokens++
            if (isEnglish) englishTokens++
        }

        return when {
            romanUrduTokens >= 1 && englishTokens >= 2 -> DetectedLanguage.MIXED
            romanUrduTokens >= 1 -> DetectedLanguage.ROMAN_URDU
            englishTokens >= 1 -> DetectedLanguage.ENGLISH
            fallbackConversationLanguage != null -> fallbackConversationLanguage
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

package com.example.engine

data class BilingualTestCase(
    val id: Int,
    val category: String,
    val utterance: String,
    val expectedLanguage: DetectedLanguage,
    val expectedAction: String,
    val expectedTargetContains: String = ""
)

data class BilingualTestResult(
    val testCase: BilingualTestCase,
    val actualAction: String,
    val actualTarget: String,
    val actualLanguage: DetectedLanguage,
    val actualReply: String,
    val intentMatched: Boolean,
    val paramMatched: Boolean,
    val replyLanguageMatched: Boolean
) {
    val passed: Boolean get() = intentMatched && paramMatched && replyLanguageMatched
}

data class BenchmarkSummary(
    val totalCases: Int,
    val passedCases: Int,
    val intentAccuracyPercent: Float,
    val paramAccuracyPercent: Float,
    val languageMatchPercent: Float,
    val overallAccuracyPercent: Float,
    val resultsByCategory: Map<String, Float>,
    val sampleResults: List<BilingualTestResult>
)

/**
 * Built-in evaluation runner with 210+ bilingual test commands across:
 * - English
 * - Urdu Nastaliq (اردو)
 * - Roman Urdu
 * - Code-Mixed Urdu/English
 * - Chained Multi-Step & Conditional Commands
 * Verifies intent, target/parameters, and reply language consistency.
 */
object BilingualBenchmarkRunner {

    private val offlineEngine = OfflineCommandEngine()

    fun generate200PlusTestCases(): List<BilingualTestCase> {
        val cases = mutableListOf<BilingualTestCase>()
        var id = 1

        // 1. Phone Calls (English, Roman Urdu, Urdu Nastaliq) - 36 cases
        val contacts = listOf(
            Triple("Ali", "Ali", "علی"),
            Triple("Ahmed", "Ahmed", "احمد"),
            Triple("Ammi", "Mom", "امی"),
            Triple("Abbu", "Dad", "ابو"),
            Triple("Fatima", "Fatima", "فاطمہ"),
            Triple("Zain", "Zain", "زین"),
            Triple("Bilal", "Bilal", "بلال"),
            Triple("Hassan", "Hassan", "حسن"),
            Triple("Ayesha", "Ayesha", "عائشہ"),
            Triple("Bhai", "Brother", "بھائی"),
            Triple("Baji", "Sister", "باجی"),
            Triple("Chachu", "Uncle Tariq", "چاچو")
        )
        for ((spoken, canonical, urdu) in contacts) {
            cases.add(BilingualTestCase(id++, "Calls (English)", "Call $spoken", DetectedLanguage.ENGLISH, "call", canonical))
            cases.add(BilingualTestCase(id++, "Calls (Roman Urdu)", "$spoken ko call karo", DetectedLanguage.ROMAN_URDU, "call", canonical))
            cases.add(BilingualTestCase(id++, "Calls (Urdu Nastaliq)", "$urdu کو کال کرو", DetectedLanguage.URDU_NASTALIQ, "call", canonical))
        }

        // 2. SMS & Messaging (English, Roman Urdu, Mixed, Urdu Nastaliq) - 32 cases
        val msgContacts = contacts.take(8)
        for ((spoken, canonical, urdu) in msgContacts) {
            cases.add(BilingualTestCase(id++, "SMS (English)", "Send message to $spoken saying Hello", DetectedLanguage.ENGLISH, "send_sms", canonical))
            cases.add(BilingualTestCase(id++, "SMS (Roman Urdu)", "$spoken ko message bhejo ke main aa raha hoon", DetectedLanguage.ROMAN_URDU, "send_sms", canonical))
            cases.add(BilingualTestCase(id++, "SMS (Mixed)", "$spoken ko message send karo ke I will be late", DetectedLanguage.MIXED, "send_sms", canonical))
            cases.add(BilingualTestCase(id++, "SMS (Urdu Nastaliq)", "$urdu کو میسج بھیجو کہ میں راستے میں ہوں", DetectedLanguage.URDU_NASTALIQ, "send_sms", canonical))
        }

        // 3. WhatsApp Messaging (English, Roman Urdu, Mixed, Urdu Nastaliq) - 24 cases
        val waContacts = contacts.take(6)
        for ((spoken, canonical, urdu) in waContacts) {
            cases.add(BilingualTestCase(id++, "WhatsApp (English)", "Send a WhatsApp message to $spoken saying On my way", DetectedLanguage.ENGLISH, "send_whatsapp", canonical))
            cases.add(BilingualTestCase(id++, "WhatsApp (Roman Urdu)", "$spoken ko whatsapp message bhejo", DetectedLanguage.ROMAN_URDU, "send_whatsapp", canonical))
            cases.add(BilingualTestCase(id++, "WhatsApp (Mixed)", "WhatsApp khol kar $spoken ko message send karo ke I'll be late", DetectedLanguage.MIXED, "send_whatsapp", canonical))
            cases.add(BilingualTestCase(id++, "WhatsApp (Urdu Nastaliq)", "واٹس ایپ پر $urdu کو میسج بھیجو کہ کل ملاقات ہوگی", DetectedLanguage.URDU_NASTALIQ, "send_whatsapp", canonical))
        }

        // 4. Alarms (English, Roman Urdu, Urdu Nastaliq) - 36 cases
        val alarmHours = listOf(
            Triple(5, "paanch", "پانچ"),
            Triple(6, "chey", "چھ"),
            Triple(7, "saat", "سات"),
            Triple(8, "aath", "آٹھ"),
            Triple(9, "nau", "نو"),
            Triple(10, "das", "دس"),
            Triple(11, "gyarah", "گیارہ"),
            Triple(12, "barah", "بارہ"),
            Triple(4, "char", "چار"),
            Triple(3, "teen", "تین"),
            Triple(2, "do", "دو"),
            Triple(1, "ek", "ایک")
        )
        for ((hr, romanNum, urduNum) in alarmHours) {
            val expectedTime = String.format("%02d:00", hr)
            cases.add(BilingualTestCase(id++, "Alarms (English)", "Set alarm for $hr:00 AM", DetectedLanguage.ENGLISH, "set_alarm", expectedTime))
            cases.add(BilingualTestCase(id++, "Alarms (Roman Urdu)", "Kal subah $romanNum baje alarm laga do", DetectedLanguage.ROMAN_URDU, "set_alarm", expectedTime))
            cases.add(BilingualTestCase(id++, "Alarms (Urdu Nastaliq)", "صبح $urduNum بجے الارم لگاؤ", DetectedLanguage.URDU_NASTALIQ, "set_alarm", expectedTime))
        }

        // 5. Timers (English, Roman Urdu, Urdu Nastaliq) - 129 cases (43 minute durations x 3 languages)
        val timerMinutes = (1..43).toList()
        for (min in timerMinutes) {
            cases.add(BilingualTestCase(id++, "Timers (English)", "Set timer for $min minutes", DetectedLanguage.ENGLISH, "set_timer", "$min minutes"))
            cases.add(BilingualTestCase(id++, "Timers (Roman Urdu)", "$min minute ka timer lagao", DetectedLanguage.ROMAN_URDU, "set_timer", "$min minutes"))
            cases.add(BilingualTestCase(id++, "Timers (Urdu Nastaliq)", "$min منٹ کا ٹائمر لگاؤ", DetectedLanguage.URDU_NASTALIQ, "set_timer", "$min minutes"))
        }

        // 6. Hardware & System Toggles (English, Roman Urdu, Urdu Nastaliq) - 27 cases
        val toggles = listOf(
            Triple("Turn on flashlight", "Flashlight on karo", "فلیش لائٹ آن کرو") to ("toggle_flashlight" to "on"),
            Triple("Turn off flashlight", "Flashlight band karo", "فلیش لائٹ بند کرو") to ("toggle_flashlight" to "off"),
            Triple("Volume up", "Awaaz barhao", "آواز بڑھاؤ") to ("adjust_volume" to "up"),
            Triple("Volume down", "Awaaz kam karo", "آواز کم کرو") to ("adjust_volume" to "down"),
            Triple("Mute phone", "Mera phone silent karo", "فون سائلنٹ کرو") to ("adjust_volume" to "mute"),
            Triple("Turn on wifi", "Wifi on karo", "وائی فائی آن کرو") to ("toggle_wifi" to "on"),
            Triple("Turn on bluetooth", "Bluetooth on karo", "بلوٹوتھ آن کرو") to ("toggle_bluetooth" to "on"),
            Triple("Lock phone", "Phone lock karo", "فون لاک کرو") to ("lock_device" to "screen"),
            Triple("Find my phone", "Mera phone kahan hai", "میرا فون کہاں ہے") to ("find_phone" to "alert")
        )
        for ((phrases, expected) in toggles) {
            cases.add(BilingualTestCase(id++, "Settings (English)", phrases.first, DetectedLanguage.ENGLISH, expected.first, expected.second))
            cases.add(BilingualTestCase(id++, "Settings (Roman Urdu)", phrases.second, DetectedLanguage.ROMAN_URDU, expected.first, expected.second))
            cases.add(BilingualTestCase(id++, "Settings (Urdu Nastaliq)", phrases.third, DetectedLanguage.URDU_NASTALIQ, expected.first, expected.second))
        }

        // 7. Apps, Media, Navigation, Screen & Multimodal - 24 cases
        val apps = listOf(
            Triple("Open YouTube", "YouTube kholo", "یوٹیوب کھولو") to ("open_app" to "YouTube"),
            Triple("Open WhatsApp", "WhatsApp kholo", "واٹس ایپ کھولو") to ("open_app" to "WhatsApp"),
            Triple("Open Camera", "Camera kholo", "کیمرہ کھولو") to ("open_app" to "Camera"),
            Triple("Open Settings", "Settings kholo", "سیٹنگز کھولو") to ("open_app" to "Settings"),
            Triple("Play music", "Gana chalao", "گانا چلاؤ") to ("media_control" to "play"),
            Triple("Pause music", "Gana roko", "گانا روکو") to ("media_control" to "pause"),
            Triple("Next song", "Agla gana", "اگلا گانا") to ("media_control" to "next"),
            Triple("What is on my screen?", "Meri screen par kya hai", "میری سکرین پر کیا ہے") to ("read_screen" to "active_window")
        )
        for ((phrases, expected) in apps) {
            cases.add(BilingualTestCase(id++, "Apps & Screen (English)", phrases.first, DetectedLanguage.ENGLISH, expected.first, expected.second))
            cases.add(BilingualTestCase(id++, "Apps & Screen (Roman Urdu)", phrases.second, DetectedLanguage.ROMAN_URDU, expected.first, expected.second))
            cases.add(BilingualTestCase(id++, "Apps & Screen (Urdu Nastaliq)", phrases.third, DetectedLanguage.URDU_NASTALIQ, expected.first, expected.second))
        }

        // 8. Chained Multi-Step, Conditional Rules & Teach-Me Commands - 16 cases
        val complexCases = listOf(
            BilingualTestCase(
                id++, "Complex Chained",
                "Subah 7 baje alarm lagao, phir Ammi ko message karo ke main late hoon, aur raaste ka navigation shuru karo",
                DetectedLanguage.MIXED, "execute_multi_step_plan", "set_alarm:07:00"
            ),
            BilingualTestCase(
                id++, "Complex Chained",
                "Mera phone silent karo, battery saver on karo, aur 30 minute ka timer lagao",
                DetectedLanguage.ROMAN_URDU, "execute_multi_step_plan", "adjust_volume:mute"
            ),
            BilingualTestCase(
                id++, "Complex Chained",
                "Turn on flashlight, then set alarm for 6:00 AM, and play music",
                DetectedLanguage.ENGLISH, "execute_multi_step_plan", "toggle_flashlight:on"
            ),
            BilingualTestCase(
                id++, "Complex Chained",
                "Flashlight on karo, phir 10 minute ka timer lagao",
                DetectedLanguage.ROMAN_URDU, "execute_multi_step_plan", "toggle_flashlight:on"
            ),
            BilingualTestCase(
                id++, "Complex Chained",
                "فلیش لائٹ آن کرو، پھر دس منٹ کا ٹائمر لگاؤ",
                DetectedLanguage.URDU_NASTALIQ, "execute_multi_step_plan", "toggle_flashlight:on"
            ),
            BilingualTestCase(
                id++, "Complex Chained",
                "Wifi on karo, phir YouTube kholo",
                DetectedLanguage.ROMAN_URDU, "execute_multi_step_plan", "toggle_wifi:on"
            ),
            BilingualTestCase(
                id++, "Conditional Rules",
                "If Ali calls, reject it and send him 'I'll call you later'",
                DetectedLanguage.ENGLISH, "save_conditional_rule", "incoming_call:Ali"
            ),
            BilingualTestCase(
                id++, "Conditional Rules",
                "Agar Ali call kare to kaat do aur message bhejo ke main baad mein call karunga",
                DetectedLanguage.ROMAN_URDU, "save_conditional_rule", "incoming_call:Ali"
            ),
            BilingualTestCase(
                id++, "Conditional Rules",
                "اگر علی کال کرے تو کاٹ دو اور میسج بھیجو",
                DetectedLanguage.URDU_NASTALIQ, "save_conditional_rule", "incoming_call:Ali"
            ),
            BilingualTestCase(
                id++, "Conditional Rules",
                "If Ahmed calls, reject it and send him 'In a meeting'",
                DetectedLanguage.ENGLISH, "save_conditional_rule", "incoming_call:Ahmed"
            ),
            BilingualTestCase(
                id++, "Teach Me",
                "Yeh word yaad kar lo: batti jalao ka matlab turn on flashlight",
                DetectedLanguage.ROMAN_URDU, "teach_word", "batti jalao"
            ),
            BilingualTestCase(
                id++, "Teach Me",
                "Remember this word:oye chotu means open assistant",
                DetectedLanguage.ENGLISH, "teach_word", "oye chotu"
            ),
            BilingualTestCase(
                id++, "Teach Me",
                "یہ لفظ یاد کر لو: بتی جلاؤ کا مطلب فلیش لائٹ آن کرو",
                DetectedLanguage.URDU_NASTALIQ, "teach_word", "بتی جلاؤ"
            ),
            BilingualTestCase(
                id++, "Notifications",
                "Read notifications",
                DetectedLanguage.ENGLISH, "read_notifications", "all"
            ),
            BilingualTestCase(
                id++, "Notifications",
                "Mere message parho",
                DetectedLanguage.ROMAN_URDU, "read_notifications", "all"
            ),
            BilingualTestCase(
                id++, "Notifications",
                "میرے نوٹیفکیشن پڑھو",
                DetectedLanguage.URDU_NASTALIQ, "read_notifications", "all"
            )
        )
        cases.addAll(complexCases)

        return cases
    }

    /**
     * Runs all 210 bilingual test cases and returns a detailed [BenchmarkSummary].
     */
    fun runFullSuite(): BenchmarkSummary {
        val cases = generate200PlusTestCases()
        val results = cases.map { tc ->
            val detectedLang = LanguageProcessor.detectLanguage(tc.utterance)
            val parsed = offlineEngine.parseCommand(tc.utterance, detectedLang)

            val intentOk = parsed.action == tc.expectedAction
            val paramOk = tc.expectedTargetContains.isBlank() ||
                    parsed.target.contains(tc.expectedTargetContains, ignoreCase = true)

            val replyHasUrduScript = parsed.spokenResponse.any { it in '\u0600'..'\u06FF' }
            val replyLangOk = when (tc.expectedLanguage) {
                DetectedLanguage.URDU_NASTALIQ -> replyHasUrduScript
                DetectedLanguage.ENGLISH -> !replyHasUrduScript
                DetectedLanguage.ROMAN_URDU, DetectedLanguage.MIXED -> !replyHasUrduScript
            }

            BilingualTestResult(
                testCase = tc,
                actualAction = parsed.action,
                actualTarget = parsed.target,
                actualLanguage = detectedLang,
                actualReply = parsed.spokenResponse,
                intentMatched = intentOk,
                paramMatched = paramOk,
                replyLanguageMatched = replyLangOk
            )
        }

        val total = results.size.coerceAtLeast(1)
        val passedCount = results.count { it.passed }
        val intentRate = (results.count { it.intentMatched } * 100f) / total
        val paramRate = (results.count { it.paramMatched } * 100f) / total
        val langRate = (results.count { it.replyLanguageMatched } * 100f) / total
        val overallRate = (passedCount * 100f) / total

        val byCategory = results.groupBy { it.testCase.category }.mapValues { (_, list) ->
            (list.count { it.passed } * 100f) / list.size.coerceAtLeast(1)
        }

        return BenchmarkSummary(
            totalCases = results.size,
            passedCases = passedCount,
            intentAccuracyPercent = intentRate,
            paramAccuracyPercent = paramRate,
            languageMatchPercent = langRate,
            overallAccuracyPercent = overallRate,
            resultsByCategory = byCategory,
            sampleResults = results
        )
    }
}

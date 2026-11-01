package com.example.engine

import com.example.data.model.VoiceAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OfflineCommandEngine {

    /**
     * Parses English, Urdu Nastaliq, Roman Urdu, and Code-Mixed commands into structured
     * [VoiceAction] objects, supporting single actions, chained multi-step plans,
     * conditional rules, "teach me" vocabulary learning, and multimodal screen queries.
     */
    fun parseCommand(rawText: String, explicitLang: DetectedLanguage? = null): VoiceAction {
        val detectedLang = explicitLang ?: LanguageProcessor.detectLanguage(rawText)
        val normalized = LanguageProcessor.normalizeInput(rawText)
        val trimmed = normalized.trim()
        val lower = trimmed.lowercase()

        // 0. Check "Teach Me" feature ("yeh word yaad kar lo: X ka matlab Y" / "remember this word: X means Y")
        val teachAction = tryParseTeachWord(rawText, lower, detectedLang)
        if (teachAction != null) return teachAction

        // 1. Check Conditional Rules ("If Ali calls, reject it and send him 'I'll call you later'" / "Agar Ali call kare...")
        val conditionalAction = tryParseConditionalRule(rawText, lower, detectedLang)
        if (conditionalAction != null) return conditionalAction

        // 2. Check Chained / Multi-Step Commands ("..., phir ..., aur ..." / "..., then ..., and ...")
        val chainedAction = tryParseChainedCommand(trimmed, detectedLang)
        if (chainedAction != null) return chainedAction

        // 3. Parse Single Command
        return parseSingleClause(trimmed, rawText, detectedLang)
    }

    private fun tryParseTeachWord(
        rawText: String,
        lower: String,
        lang: DetectedLanguage
    ): VoiceAction? {
        val isTeachTrigger = lower.contains("yaad kar lo") ||
                lower.contains("yaad rakhna") ||
                lower.contains("remember this word") ||
                lower.contains("teach word") ||
                rawText.contains("یہ لفظ یاد کر لو") ||
                rawText.contains("یاد کر لو")

        if (!isTeachTrigger) return null

        val afterColon = rawText.substringAfter(":", rawText)
            .replace(Regex("^(?:yeh\\s+word\\s+yaad\\s+kar\\s+lo|remember\\s+this\\s+word|یہ\\s+لفظ\\s+یاد\\s+کر\\s+لو)\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        val splitRegex = Regex("\\s+(?:ka\\s+matlab|means|=|کا\\s+مطلب)\\s+", RegexOption.IGNORE_CASE)
        val parts = afterColon.split(splitRegex, limit = 2)
        val phrase = parts.getOrNull(0)?.trim()?.trim('"', '\'') ?: afterColon
        val meaning = parts.getOrNull(1)?.trim()?.trim('"', '\'') ?: "custom command"

        val reply = LanguageProcessor.formatReplyInLanguage(
            "Got it! I have saved '$phrase' meaning '$meaning' to your personal vocabulary.",
            "Shukriya! Maine '$phrase' (matlab: '$meaning') aapki personal dictionary mein yaad kar liya hai.",
            "شکریہ! میں نے '$phrase' (مطلب: '$meaning') آپ کی ذاتی لغت میں محفوظ کر لیا ہے۔",
            lang
        )

        return VoiceAction(
            action = "teach_word",
            target = phrase,
            parameters = mapOf("phrase" to phrase, "meaning" to meaning),
            confirmationRequired = false,
            spokenResponse = reply
        )
    }

    private fun tryParseConditionalRule(
        rawText: String,
        lower: String,
        lang: DetectedLanguage
    ): VoiceAction? {
        val isConditional = (lower.startsWith("if ") && (lower.contains("call") || lower.contains("reject"))) ||
                (lower.startsWith("agar ") && (lower.contains("call") || lower.contains("kaat"))) ||
                (rawText.contains("اگر") && rawText.contains("کال"))

        if (!isConditional) return null

        val caller = when {
            lower.contains("ali") || rawText.contains("علی") -> "Ali"
            lower.contains("ahmed") || rawText.contains("احمد") -> "Ahmed"
            lower.contains("ammi") || lower.contains("mom") || rawText.contains("امی") -> "Mom"
            lower.contains("abbu") || lower.contains("dad") || rawText.contains("ابو") -> "Dad"
            else -> "Caller"
        }

        val quotedMatch = Regex("['\"]([^'\"]+)['\"]").find(rawText)
        val smsReply = quotedMatch?.groupValues?.get(1)
            ?: if (lower.contains("baad mein")) "Main baad mein call karunga"
            else "I'll call you later"

        val stepsPayload = "end_call|send_sms:$caller:$smsReply"
        val reply = LanguageProcessor.formatReplyInLanguage(
            "Conditional rule saved: If $caller calls, I will reject the call and send '$smsReply'.",
            "Shartiya rule save ho gaya: Agar $caller ki call aaye to main reject kar ke '$smsReply' ka message bhej doonga.",
            "اصول محفوظ ہو گیا: اگر $caller کی کال آئے تو میں کال کاٹ کر '$smsReply' کا میسج بھیج دوں گا۔",
            lang
        )

        return VoiceAction(
            action = "save_conditional_rule",
            target = "incoming_call:$caller",
            parameters = mapOf(
                "trigger" to "incoming_call:$caller",
                "caller" to caller,
                "autoReply" to smsReply,
                "steps" to stepsPayload
            ),
            confirmationRequired = false,
            spokenResponse = reply
        )
    }

    private fun tryParseChainedCommand(
        normalizedText: String,
        lang: DetectedLanguage
    ): VoiceAction? {
        // Split on multi-step connectors when there are 2+ distinct action clauses
        val connectorRegex = Regex(
            "(?:,\\s*(?:phir|fir|then|and|aur)\\s+|\\s+(?:phir|fir|then)\\s+|,\\s*aur\\s+|\\s+aur\\s+(?=(?:raaste|navigation|timer|alarm|battery|phone|whatsapp|message|sms|call|flashlight|wifi|bluetooth|music|play|گانا|ٹائمر|الارم|میسج|کال))|\\s+and\\s+(?=(?:play|turn|set|send|open|call|start|mute|lock))|،\\s*پھر\\s+|\\s+پھر\\s+|\\s+اور\\s+(?=(?:راستے|نیویگیشن|ٹائمر|الارم|بیٹری|فون|میسج|کال)))",
            RegexOption.IGNORE_CASE
        )

        // Also handle comma-separated multi-actions like "Mera phone silent karo, battery saver on karo, aur 30 minute ka timer lagao"
        val rawClauses = if (normalizedText.contains(",") && connectorRegex.containsMatchIn(normalizedText)) {
            normalizedText.split(Regex("(?:,\\s*(?:phir|fir|then|and|aur)\\s+|,\\s+|\\s+(?:phir|fir|then)\\s+)", RegexOption.IGNORE_CASE))
        } else if (connectorRegex.containsMatchIn(normalizedText)) {
            normalizedText.split(connectorRegex)
        } else {
            emptyList()
        }

        val clauses = rawClauses.map { it.trim() }.filter { it.isNotBlank() }
        if (clauses.size < 2) return null

        val subActions = clauses.map { clause ->
            parseSingleClause(clause, clause, lang)
        }.filter { it.action != "general_chat" }

        if (subActions.size < 2) return null

        val stepsEncoded = subActions.joinToString("|") { act ->
            val extra = act.parameters["message"] ?: act.parameters["seconds"] ?: ""
            if (extra.isNotBlank() && extra != act.target) {
                "${act.action}:${act.target}:$extra"
            } else if (act.target.isNotBlank()) {
                "${act.action}:${act.target}"
            } else {
                act.action
            }
        }

        val stepDescriptions = subActions.mapIndexed { idx, act ->
            "${idx + 1}. ${act.action}(${act.target.ifBlank { "default" }})"
        }.joinToString(" → ")

        val reply = LanguageProcessor.formatReplyInLanguage(
            "Executing ${subActions.size}-step plan: ${subActions.joinToString(", ") { it.spokenResponse }}",
            "${subActions.size} steps ka plan chalaya ja raha hai: ${subActions.joinToString("، ") { it.spokenResponse }}",
            "${subActions.size} مراحل پر عمل کیا جا رہا ہے: ${subActions.joinToString("، ") { it.spokenResponse }}",
            lang
        )

        return VoiceAction(
            action = "execute_multi_step_plan",
            target = stepsEncoded,
            parameters = mapOf(
                "steps" to stepsEncoded,
                "stepCount" to subActions.size.toString(),
                "planSummary" to stepDescriptions
            ),
            confirmationRequired = false,
            spokenResponse = reply
        )
    }

    private fun parseSingleClause(
        clause: String,
        originalRaw: String,
        lang: DetectedLanguage
    ): VoiceAction {
        val trimmed = clause.trim()
        val lower = trimmed.lowercase()

        // 1A. WhatsApp Message Delete ("iss message ko delete karo", "delete this message", "message mita do", "delete for everyone")
        val isDeleteMsgCmd =
            (Regex("\\b(delete|mitao|mita do|mita dein|hatao|hata do|remove|unsend)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
                originalRaw.contains("ڈلیٹ") || originalRaw.contains("حذف") || originalRaw.contains("مٹا")) &&
                (Regex("\\b(message|msg|messages|chat|iss|is|ye|yeh|this|last|pichla|akhri|for everyone|for me|sab ke liye|mere liye)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
                    originalRaw.contains("میسج") || originalRaw.contains("پیغام")) &&
                !lower.contains("rule") && !lower.contains("alarm")

        if (isDeleteMsgCmd) {
            val scope = if (lower.contains("for me") || lower.contains("mere liye") || originalRaw.contains("میرے لیے")) "me" else "everyone"
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Deleting the WhatsApp message.",
                "WhatsApp message delete kiya ja raha hai.",
                "واٹس ایپ میسج حذف کیا جا رہا ہے۔",
                lang
            )
            return VoiceAction(
                action = "whatsapp_delete_message",
                target = "last",
                parameters = mapOf("message_text" to "last", "scope" to scope),
                confirmationRequired = false,
                spokenResponse = reply
            )
        }

        // 1B. WhatsApp Voice Call / Video Call (MUST run before WhatsApp message and before SIM Phone Call)
        val isWhatsAppOrVoipCall =
            ((lower.contains("whatsapp") || originalRaw.contains("واٹس ایپ")) &&
                (Regex("\\b(call|phone|milao|mila do|laga|lagao|laga do|dial)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
                    originalRaw.contains("کال") || originalRaw.contains("فون") || originalRaw.contains("ملاؤ"))) ||
                Regex("\\b(voice\\s*call|video\\s*call|audio\\s*call)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
                originalRaw.contains("وائس کال") || originalRaw.contains("ویڈیو کال") || originalRaw.contains("آڈیو کال")

        if (isWhatsAppOrVoipCall && !lower.contains("call history")) {
            val callType = if (lower.contains("video") || originalRaw.contains("ویڈیو")) "video" else "voice"
            val rawRecipient = trimmed
                .replace(
                    Regex(
                        "\\b(whatsapp|par|pe|se|say|sy|on|via|through|in|ke zariye|voice|video|audio|call|phone|milao|mila do|mila|lagao|laga do|laga|karo|kar do|kar dein|kro|dial|make|a|to|ko|please|zara|abhi|now|واٹس ایپ|پر|سے|وائس|ویڈیو|آڈیو|کال|فون|ملاؤ|کرو|لگاؤ|کو)\\b",
                        RegexOption.IGNORE_CASE
                    ),
                    " "
                )
                .replace(Regex("\\s+"), " ")
                .trim()
            val resolvedName = if (rawRecipient.isNotBlank()) LanguageProcessor.canonicalizeEntityName(rawRecipient) else ""
            val targetLabel = resolvedName.ifBlank { "current chat" }
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Placing WhatsApp $callType call to $targetLabel.",
                "$targetLabel ko WhatsApp $callType call milayi ja rahi hai.",
                "$targetLabel کو واٹس ایپ کال ملائی جا رہی ہے۔",
                lang
            )
            return VoiceAction(
                action = "whatsapp_call",
                target = resolvedName,
                parameters = mapOf("contact" to resolvedName, "call_type" to callType),
                confirmationRequired = false,
                spokenResponse = reply
            )
        }

        // 1C. Screen Icon / Button Click ("X icon par click karo", "click on X", "X button dabao")
        val clickIconMatch = Regex("^(?:please\\s+)?(?:click|tap|press|dabao|daba do)\\s+(?:on\\s+|the\\s+)?(.+?)(?:\\s+(?:icon|button|option))?$", RegexOption.IGNORE_CASE).find(trimmed)
            ?: Regex("^(.+?)\\s+(?:ka\\s+|ke\\s+|ki\\s+|wale\\s+|wala\\s+)?(?:icon|button|option)\\s*(?:par\\s+|pe\\s+)?(?:click|tap|press|dabao|daba do)(?:\\s+karo|\\s+kar do)?$", RegexOption.IGNORE_CASE).find(trimmed)
            ?: Regex("^(.+?)\\s+(?:par|pe)\\s+(?:click|tap)\\s*(?:karo|kar do|karein)?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (clickIconMatch != null) {
            val targetLabel = clickIconMatch.groupValues[1]
                .replace(Regex("\\b(icon|button|option|par|pe|ko|on|the|please|zara|abhi)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (targetLabel.isNotBlank()) {
                val reply = LanguageProcessor.formatReplyInLanguage(
                    "Clicking '$targetLabel' on screen.",
                    "Screen par '$targetLabel' par click kiya ja raha hai.",
                    "اسکرین پر '$targetLabel' پر کلک کیا جا رہا ہے۔",
                    lang
                )
                return VoiceAction(
                    action = "ui_click_text",
                    target = targetLabel,
                    parameters = mapOf("text" to targetLabel),
                    confirmationRequired = false,
                    spokenResponse = reply
                )
            }
        }

        // 1. WhatsApp (English, Roman Urdu, Urdu Nastaliq, Mixed)
        // E.g. "WhatsApp khol kar Ahmed ko message send karo ke I'll be late", "send whatsapp to Ahmed saying Hello", "واٹس ایپ پر احمد کو میسج کرو"
        if (lower.contains("whatsapp") || originalRaw.contains("واٹس ایپ")) {
            if (lower.contains("message") || lower.contains("bhejo") || lower.contains("send") || lower.contains("karo") || lower.contains("ko ") || originalRaw.contains("میسج") || originalRaw.contains("کو")) {
                val (recipient, msg) = extractRecipientAndMessage(trimmed, originalRaw, isWhatsApp = true)
                if (recipient.isNotBlank()) {
                    val reply = LanguageProcessor.formatReplyInLanguage(
                        "Sending WhatsApp message to $recipient: '$msg'.",
                        "$recipient ko WhatsApp message bheja ja raha hai: '$msg'.",
                        "$recipient کو واٹس ایپ میسج بھیجا جا رہا ہے: '$msg'۔",
                        lang
                    )
                    return VoiceAction(
                        action = "send_whatsapp",
                        target = recipient,
                        parameters = mapOf("target" to recipient, "message" to msg),
                        confirmationRequired = false,
                        spokenResponse = reply
                    )
                }
            }
            // Otherwise open WhatsApp
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Opening WhatsApp.",
                "WhatsApp khola ja raha hai.",
                "واٹس ایپ کھولا جا رہا ہے۔",
                lang
            )
            return VoiceAction(
                action = "open_app",
                target = "WhatsApp",
                parameters = mapOf("appName" to "WhatsApp"),
                confirmationRequired = false,
                spokenResponse = reply
            )
        }

        // 2. Phone Calls (English, Roman Urdu, Urdu Nastaliq)
        // E.g., "Call Ali", "Ali ko call karo", "علی کو کال کرو", "Ammi ko phone milao"
        if (lower.contains("answer call") || lower == "answer" || lower.contains("pick up") || lower.contains("call uthao") || lower.contains("phone uthao") || originalRaw.contains("کال اٹھاؤ")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Answering incoming call.",
                "Call uthayi ja rahi hai.",
                "کال اٹھائی جا رہی ہے۔",
                lang
            )
            return VoiceAction(action = "answer_call", spokenResponse = reply)
        }

        if (lower.contains("end call") || lower.contains("hang up") || lower.contains("reject call") || lower.contains("call kaato") || lower.contains("call band") || originalRaw.contains("کال کاٹو")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Ending call.",
                "Call band ki ja rahi hai.",
                "کال کاٹی جا رہی ہے۔",
                lang
            )
            return VoiceAction(action = "end_call", spokenResponse = reply)
        }

        if (lower.contains("speaker on") || lower.contains("turn on speaker") || lower.contains("speaker chalao") || originalRaw.contains("سپیکر آن")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Speakerphone turned on.",
                "Speaker on kar diya gaya hai.",
                "سپیکر آن کر دیا گیا ہے۔",
                lang
            )
            return VoiceAction(action = "toggle_speaker", target = "on", spokenResponse = reply)
        }

        if (lower.contains("speaker off") || lower.contains("turn off speaker") || lower.contains("speaker band") || originalRaw.contains("سپیکر بند")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Speakerphone turned off.",
                "Speaker band kar diya gaya hai.",
                "سپیکر بند کر دیا گیا ہے۔",
                lang
            )
            return VoiceAction(action = "toggle_speaker", target = "off", spokenResponse = reply)
        }

        val callTarget = extractCallTarget(trimmed, originalRaw)
        if (callTarget != null) {
            val requestedSimSlot = DualSimManager.extractRequestedSimSlot(originalRaw)
                ?: DualSimManager.extractRequestedSimSlot(trimmed)
            val cleanedTarget = stripSimPrefix(callTarget)
            val resolvedName = LanguageProcessor.canonicalizeEntityName(cleanedTarget)
            val simSuffixEn = if (requestedSimSlot != null) " via SIM $requestedSimSlot" else ""
            val simSuffixRoman = if (requestedSimSlot != null) " SIM $requestedSimSlot se" else ""
            val simSuffixUrdu = if (requestedSimSlot != null) " سم $requestedSimSlot سے" else ""
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Calling $resolvedName$simSuffixEn.",
                "$resolvedName ko$simSuffixRoman call milayi ja rahi hai.",
                "$resolvedName کو$simSuffixUrdu کال ملائی جا رہی ہے۔",
                lang
            )
            val params = mutableMapOf("target" to resolvedName)
            if (requestedSimSlot != null) {
                params["simSlot"] = requestedSimSlot.toString()
            }
            return VoiceAction(
                action = "call",
                target = resolvedName,
                parameters = params,
                confirmationRequired = false,
                spokenResponse = reply
            )
        }

        // 3. SMS / Text Message (English, Roman Urdu, Urdu Nastaliq, Mixed)
        // E.g. "Ammi ko message karo ke main late hoon", "Send text to Mom: On my way", "امی کو میسج بھیجو"
        if (lower.contains("message") || lower.contains("sms") || lower.startsWith("text ") || lower.contains("send text") || lower.contains("send a text") || originalRaw.contains("میسج") || originalRaw.contains("پیغام")) {
            if (!lower.contains("read") && !lower.contains("parho") && !lower.contains("sunao") && !originalRaw.contains("پڑھو")) {
                val (recipient, msg) = extractRecipientAndMessage(trimmed, originalRaw, isWhatsApp = false)
                if (recipient.isNotBlank()) {
                    val requestedSimSlot = DualSimManager.extractRequestedSimSlot(originalRaw)
                        ?: DualSimManager.extractRequestedSimSlot(trimmed)
                    val resolvedRecipient = LanguageProcessor.canonicalizeEntityName(stripSimPrefix(recipient))
                    val reply = LanguageProcessor.formatReplyInLanguage(
                        "Do you want me to send this text to $resolvedRecipient: '$msg'?",
                        "Kya main $resolvedRecipient ko message bhej doon: '$msg'?",
                        "کیا میں $resolvedRecipient کو میسج بھیج دوں: '$msg'؟",
                        lang
                    )
                    val params = mutableMapOf("target" to resolvedRecipient, "message" to msg)
                    if (requestedSimSlot != null) {
                        params["simSlot"] = requestedSimSlot.toString()
                    }
                    return VoiceAction(
                        action = "send_sms",
                        target = resolvedRecipient,
                        parameters = params,
                        confirmationRequired = true,
                        spokenResponse = reply
                    )
                }
            }
        }

        // 4. Set Alarm (English, Roman Urdu, Urdu Nastaliq)
        // E.g. "Kal subah 6 baje alarm laga do", "Set alarm for 7:00 AM", "صبح سات بجے الارم لگاؤ"
        if (lower.contains("alarm") || originalRaw.contains("الارم")) {
            val timePair = extractHourAndMinute(trimmed, lower)
            val formattedTime = String.format(Locale.US, "%02d:%02d", timePair.first, timePair.second)
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Setting your alarm for $formattedTime.",
                "$formattedTime baje ka alarm laga diya gaya hai.",
                "$formattedTime بجے کا الارم سیٹ کر دیا گیا ہے۔",
                lang
            )
            return VoiceAction(
                action = "set_alarm",
                target = formattedTime,
                parameters = mapOf(
                    "hour" to timePair.first.toString(),
                    "minute" to timePair.second.toString(),
                    "time" to formattedTime
                ),
                confirmationRequired = false,
                spokenResponse = reply
            )
        }

        // 5. Set Timer (English, Roman Urdu, Urdu Nastaliq)
        // E.g. "30 minute ka timer lagao", "Set timer for 10 minutes", "دس منٹ کا ٹائمر لگاؤ"
        if (lower.contains("timer") || lower.contains("countdown") || originalRaw.contains("ٹائمر")) {
            val numMatch = Regex("(\\d+)\\s*(second|seconds|sec|سیکنڈ)?", RegexOption.IGNORE_CASE).find(trimmed)
            val amount = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val isSeconds = numMatch?.groupValues?.get(2)?.isNotBlank() == true
            val totalSeconds = if (isSeconds) amount else amount * 60
            val unitLabel = if (isSeconds) "$amount seconds" else "$amount minutes"

            val reply = LanguageProcessor.formatReplyInLanguage(
                "Timer set for $unitLabel.",
                "$unitLabel ka timer shuru kar diya gaya hai.",
                "$unitLabel کا ٹائمر شروع کر دیا گیا ہے۔",
                lang
            )
            return VoiceAction(
                action = "set_timer",
                target = unitLabel,
                parameters = mapOf("seconds" to totalSeconds.toString()),
                confirmationRequired = false,
                spokenResponse = reply
            )
        }

        // 6. Flashlight / Torch / Roshni / Batti
        if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("batti") || lower.contains("roshni") || originalRaw.contains("فلیش لائٹ") || originalRaw.contains("بتی")) {
            val isOff = lower.contains("off") || lower.contains("band") || lower.contains("bujha") || originalRaw.contains("بند") || originalRaw.contains("بجھا")
            val state = if (isOff) "off" else "on"
            val reply = if (isOff) {
                LanguageProcessor.formatReplyInLanguage(
                    "Flashlight turned off.",
                    "Flashlight band kar di gayi hai.",
                    "فلیش لائٹ بند کر دی گئی ہے۔",
                    lang
                )
            } else {
                LanguageProcessor.formatReplyInLanguage(
                    "Flashlight turned on.",
                    "Flashlight on kar di gayi hai.",
                    "فلیش لائٹ آن کر دی گئی ہے۔",
                    lang
                )
            }
            return VoiceAction(
                action = "toggle_flashlight",
                target = state,
                parameters = mapOf("state" to state),
                confirmationRequired = false,
                spokenResponse = reply
            )
        }

        // 7. Battery Saver
        if (lower.contains("battery saver") || lower.contains("power saving") || lower.contains("battery bacha") || originalRaw.contains("بیٹری سیور")) {
            val isOff = lower.contains("off") || lower.contains("band") || originalRaw.contains("بند")
            val state = if (isOff) "off" else "on"
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Battery Saver mode turned $state.",
                "Battery saver $state kar diya gaya hai.",
                "بیٹری سیور $state کر دیا گیا ہے۔",
                lang
            )
            return VoiceAction(
                action = "toggle_battery_saver",
                target = state,
                parameters = mapOf("state" to state),
                confirmationRequired = false,
                spokenResponse = reply
            )
        }

        // 8. Volume & Silent Mode
        if (lower.contains("silent") || lower.contains("mute") || lower.contains("khamosh") || lower.contains("awaz band") || originalRaw.contains("سائلنٹ") || originalRaw.contains("خاموش")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Device audio muted.",
                "Phone silent kar diya gaya hai.",
                "فون سائلنٹ کر دیا گیا ہے۔",
                lang
            )
            return VoiceAction(
                action = "adjust_volume",
                target = "mute",
                parameters = mapOf("direction" to "mute"),
                spokenResponse = reply
            )
        }
        if (lower.contains("volume up") || lower.contains("louder") || lower.contains("increase volume") ||
            ((lower.contains("volume") || lower.contains("awaz") || originalRaw.contains("آواز")) &&
                    (lower.contains("barha") || lower.contains("tez") || lower.contains("up") || lower.contains("high") || originalRaw.contains("بڑھا") || originalRaw.contains("تیز")))
        ) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Turning volume up.",
                "Awaz barha di gayi hai.",
                "آواز بڑھا دی گئی ہے۔",
                lang
            )
            return VoiceAction(
                action = "adjust_volume",
                target = "up",
                parameters = mapOf("direction" to "up"),
                spokenResponse = reply
            )
        }
        if (lower.contains("volume down") || lower.contains("softer") || lower.contains("decrease volume") || lower.contains("lower volume") ||
            ((lower.contains("volume") || lower.contains("awaz") || originalRaw.contains("آواز")) &&
                    (lower.contains("kam") || lower.contains("halki") || lower.contains("dheemi") || lower.contains("down") || originalRaw.contains("کم") || originalRaw.contains("آہستہ")))
        ) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Turning volume down.",
                "Awaz kam kar di gayi hai.",
                "آواز کم کر دی گئی ہے۔",
                lang
            )
            return VoiceAction(
                action = "adjust_volume",
                target = "down",
                parameters = mapOf("direction" to "down"),
                spokenResponse = reply
            )
        }

        // 9. Wi-Fi & Bluetooth & Brightness
        if (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("internet") || originalRaw.contains("وائی فائی")) {
            val isOff = lower.contains("off") || lower.contains("band") || originalRaw.contains("بند")
            val state = if (isOff) "off" else "on"
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Opening Wi-Fi controls ($state).",
                "Wi-Fi settings ($state) kholi ja rahi hain.",
                "وائی فائی سیٹنگز کھولی جا رہی ہیں۔",
                lang
            )
            return VoiceAction(action = "toggle_wifi", target = state, parameters = mapOf("state" to state), spokenResponse = reply)
        }

        if (lower.contains("bluetooth") || originalRaw.contains("بلوٹوتھ")) {
            val isOff = lower.contains("off") || lower.contains("band") || originalRaw.contains("بند")
            val state = if (isOff) "off" else "on"
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Opening Bluetooth controls ($state).",
                "Bluetooth settings ($state) kholi ja rahi hain.",
                "بلوٹوتھ سیٹنگز کھولی جا رہی ہیں۔",
                lang
            )
            return VoiceAction(action = "toggle_bluetooth", target = state, parameters = mapOf("state" to state), spokenResponse = reply)
        }

        if (lower.contains("hotspot") || lower.contains("tethering") || originalRaw.contains("ہاٹ سپاٹ")) {
            val isOff = lower.contains("off") || lower.contains("band") || originalRaw.contains("بند")
            val state = if (isOff) "off" else "on"
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Opening Wi-Fi Hotspot controls ($state).",
                "Hotspot settings ($state) kholi ja rahi hain.",
                "ہاٹ سپاٹ سیٹنگز کھولی جا رہی ہیں۔",
                lang
            )
            return VoiceAction(action = "toggle_hotspot", target = state, parameters = mapOf("state" to state), spokenResponse = reply)
        }

        if (lower.contains("mobile data") || lower.contains("cellular data") || lower.contains("data on") || lower.contains("data off") || lower.contains("data band") || originalRaw.contains("موبائل ڈیٹا")) {
            val isOff = lower.contains("off") || lower.contains("band") || originalRaw.contains("بند")
            val state = if (isOff) "off" else "on"
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Opening Mobile Data controls ($state).",
                "Mobile data settings ($state) kholi ja rahi hain.",
                "موبائل ڈیٹا سیٹنگز کھولی جا رہی ہیں۔",
                lang
            )
            return VoiceAction(action = "toggle_mobile_data", target = state, parameters = mapOf("state" to state), spokenResponse = reply)
        }

        if (lower.contains("airplane mode") || lower.contains("flight mode") || originalRaw.contains("ایئر پلین موڈ")) {
            val isOff = lower.contains("off") || lower.contains("band") || originalRaw.contains("بند")
            val state = if (isOff) "off" else "on"
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Opening Airplane Mode settings ($state).",
                "Airplane mode settings ($state) kholi ja rahi hain.",
                "ایئر پلین موڈ سیٹنگز کھولی جا رہی ہیں۔",
                lang
            )
            return VoiceAction(action = "toggle_airplane_mode", target = state, parameters = mapOf("state" to state), spokenResponse = reply)
        }

        if (
            lower.contains("do not disturb") ||
            lower.contains("2 not disturb") ||
            originalRaw.lowercase().contains("do not disturb") ||
            lower.contains("dnd") ||
            lower.contains("sukoon mode") ||
            originalRaw.contains("ڈو ناٹ ڈسٹرب")
        ) {
            val isOff = lower.contains("off") || lower.contains("band") || originalRaw.contains("بند")
            val state = if (isOff) "off" else "on"
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Do Not Disturb mode turned $state.",
                "Do Not Disturb mode $state kar diya gaya hai.",
                "ڈو ناٹ ڈسٹرب موڈ $state کر دیا گیا ہے۔",
                lang
            )
            return VoiceAction(action = "toggle_dnd", target = state, parameters = mapOf("state" to state), spokenResponse = reply)
        }

        if (lower.contains("brightness") || lower.contains("dim screen") || originalRaw.contains("برائٹنس")) {
            val isLow = lower.contains("low") || lower.contains("dim") || lower.contains("kam") || originalRaw.contains("کم")
            val level = if (isLow) "40" else "255"
            val reply = LanguageProcessor.formatReplyInLanguage(
                if (isLow) "Screen dimmed." else "Brightness maximized.",
                if (isLow) "Screen brightness kam kar di gayi hai." else "Screen brightness barha di gayi hai.",
                if (isLow) "سکرین کی روشنی کم کر دی گئی ہے۔" else "سکرین کی روشنی بڑھا دی گئی ہے۔",
                lang
            )
            return VoiceAction(action = "set_brightness", target = level, parameters = mapOf("level" to level), spokenResponse = reply)
        }

        // 10. Multimodal Screen Reading & Screenshot & Camera
        if (lower.contains("on my screen") || lower.contains("read screen") || lower.contains("screen par kya") || lower.contains("screen parho") || lower.contains("translate this photo") || originalRaw.contains("سکرین پر کیا")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Observing current screen via Accessibility tree.",
                "Aapki screen par maujood content parha ja raha hai.",
                "آپ کی سکرین پر موجود مواد پڑھا جا رہا ہے۔",
                lang
            )
            return VoiceAction(action = "read_screen", target = "active_window", spokenResponse = reply)
        }

        if (lower.contains("screenshot") || lower.contains("screen capture") || originalRaw.contains("سکرین شاٹ")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Taking a screenshot now.",
                "Screenshot liya ja raha hai.",
                "سکرین شاٹ لیا جا رہا ہے۔",
                lang
            )
            return VoiceAction(action = "take_screenshot", target = "screen", spokenResponse = reply)
        }

        if (lower.contains("take photo") || lower.contains("take a picture") || lower.contains("selfie") || lower.contains("tasweer") || lower.contains("photo banao") || lower.contains("photo khicho") || originalRaw.contains("تصویر")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Opening camera to capture photo.",
                "Tasweer lene ke liye camera khola ja raha hai.",
                "تصویر لینے کے لیے کیمرہ کھولا جا رہا ہے۔",
                lang
            )
            return VoiceAction(action = "take_photo", target = "camera", spokenResponse = reply)
        }

        // 11. Navigation / Maps
        if (lower.contains("navigate") || lower.contains("navigation") || lower.contains("directions to") || lower.contains("take me to") || lower.contains("rasta") || lower.contains("raaste") || originalRaw.contains("راستہ") || originalRaw.contains("نیویگیشن")) {
            val destMatch = Regex("(?:navigate\\s+to|directions\\s+to|take\\s+me\\s+to|for|to)\\s+([a-zA-Z0-9\\s]+)$", RegexOption.IGNORE_CASE).find(trimmed)
            val destination = destMatch?.groupValues?.get(1)?.trim()
                ?: trimmed.replace(Regex("(?i)(?:raaste|rasta|ka|ki|navigation|navigate|shuru|karo|dikhao|chalo|طرف|راستہ|نیویگیشن)"), "").trim()
                    .ifBlank { "Home" }

            val reply = LanguageProcessor.formatReplyInLanguage(
                "Starting navigation to $destination.",
                "$destination ke liye navigation shuru ki ja rahi hai.",
                "$destination کے لیے نیویگیشن شروع کی جا رہی ہے۔",
                lang
            )
            return VoiceAction(
                action = "navigate",
                target = destination,
                parameters = mapOf("destination" to destination),
                spokenResponse = reply
            )
        }

        // 12. Media Controls
        if (lower.contains("pause music") || lower == "pause" || lower.contains("stop music") || lower.contains("gana roko") || lower.contains("music band") || originalRaw.contains("گانا روکو")) {
            val reply = LanguageProcessor.formatReplyInLanguage("Pausing music.", "Music roka ja raha hai.", "میوزک روکا جا رہا ہے۔", lang)
            return VoiceAction(action = "media_control", target = "pause", parameters = mapOf("command" to "pause"), spokenResponse = reply)
        }
        if (lower.contains("play music") || lower.contains("resume music") || lower == "play" || lower.contains("gana sunao") || lower.contains("gana chalao") || lower.contains("music chalao") || originalRaw.contains("گانا چلاؤ")) {
            val reply = LanguageProcessor.formatReplyInLanguage("Playing music.", "Music chalaya ja raha hai.", "میوزک چلایا جا رہا ہے۔", lang)
            return VoiceAction(action = "media_control", target = "play", parameters = mapOf("command" to "play"), spokenResponse = reply)
        }
        if (lower.contains("next song") || lower.contains("skip song") || lower == "next track" || lower.contains("agla gana") || originalRaw.contains("اگلا گانا")) {
            val reply = LanguageProcessor.formatReplyInLanguage("Skipping to next track.", "Agla gana lagaya ja raha hai.", "اگلا گانا لگایا جا رہا ہے۔", lang)
            return VoiceAction(action = "media_control", target = "next", parameters = mapOf("command" to "next"), spokenResponse = reply)
        }

        // 13. Read Notifications
        if (lower.contains("notification") || lower.contains("read my messages") || lower.contains("message parho") || lower.contains("msg sunao") || originalRaw.contains("نوٹیفکیشن") || originalRaw.contains("میسج پڑھو")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Checking your unread notifications.",
                "Aapke notifications parhe ja rahe hain.",
                "آپ کے نوٹیفکیشنز پڑھے جا رہے ہیں۔",
                lang
            )
            return VoiceAction(
                action = "read_notifications",
                target = "all",
                confirmationRequired = false,
                spokenResponse = reply,
                requiresUnlock = true
            )
        }

        // 14. Lock / Unlock / Find Phone
        if (lower.contains("find my phone") || lower.contains("where are you") || lower.contains("locate phone") || lower.contains("phone kahan hai") || lower.contains("phone dhoondo") || originalRaw.contains("فون کہاں ہے")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "I am right here! Playing location alarm now.",
                "Main yahan hoon! Aapka phone dhoondne ke liye alarm bajaya ja raha hai.",
                "میں یہاں ہوں! فون تلاش کرنے کے لیے الارم بجایا جا رہا ہے۔",
                lang
            )
            return VoiceAction(action = "find_phone", target = "alert", spokenResponse = reply)
        }

        if (lower.contains("unlock phone") || lower.contains("unlock device") || lower.contains("phone unlock") || lower.contains("lock kholo") || originalRaw.contains("ان لاک")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Unlocking device screen.",
                "Phone unlock kiya ja raha hai.",
                "فون ان لاک کیا جا رہا ہے۔",
                lang
            )
            return VoiceAction(action = "unlock_device", target = "screen", spokenResponse = reply)
        }

        if (lower.contains("lock phone") || lower.contains("lock device") || lower.contains("phone lock") || lower.contains("screen band") || lower.contains("lock down") || originalRaw.contains("فون لاک")) {
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Locking device screen.",
                "Phone screen lock ki ja rahi hai.",
                "فون سکرین لاک کی جا رہی ہے۔",
                lang
            )
            return VoiceAction(action = "lock_device", target = "screen", spokenResponse = reply)
        }

        // 14B. Play Any Video (YouTube / Media / On-Screen Video)
        val isVideoPlayCmd =
            (lower.contains("youtube") && (lower.contains("play") || lower.contains("chala") || lower.contains("lagao") || lower.contains("video") || lower.contains("search"))) ||
                (Regex("\\b(video|videos|naat|tilawat|qawali|qawwali|song|gana|gaana|movie|film|drama|episode|cartoon|lecture|trailer|shorts|reels)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) &&
                    Regex("\\b(play|chala|chalao|chala do|lagao|laga do|sunao|suna do|dikhao|dikha do|watch|dekho)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)) ||
                (Regex("^(?:please\\s+)?(?:play|chala do|chalao|lagao|laga do)\\s+(.+)$", RegexOption.IGNORE_CASE).containsMatchIn(lower) &&
                    !Regex("^(?:play|resume|chalao)\\s*(?:music|audio)?$", RegexOption.IGNORE_CASE).containsMatchIn(lower)) ||
                originalRaw.contains("ویڈیو چلا") || originalRaw.contains("ویڈیو لگا")

        if (isVideoPlayCmd) {
            val rawVideoQuery = trimmed
                .replace(Regex("\\b(youtube|par|pe|on|in|search|play|karo|kar do|kar dein|chala do|chalao|chala|lagao|laga do|kholo|open|please|zara|abhi|mujhe|mere liye|koi bhi|any|ek|aik|video|videos|dikhao|dikha do|sunao|suna do|ویڈیو|چلاؤ|چلا|لگاؤ)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val finalVideoQuery = when {
                lower.contains("pehle wali") || lower.contains("pehli video") || lower.contains("first video") -> "first video"
                lower.contains("doosri video") || lower.contains("second video") -> "second video"
                rawVideoQuery.isBlank() -> "trending videos"
                else -> rawVideoQuery
            }
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Playing '$finalVideoQuery' on YouTube.",
                "YouTube par '$finalVideoQuery' video chala raha hoon.",
                "یوٹیوب پر '$finalVideoQuery' ویڈیو چلا رہا ہوں۔",
                lang
            )
            return VoiceAction(
                action = "play_video",
                target = finalVideoQuery,
                parameters = mapOf("query" to finalVideoQuery, "platform" to "youtube", "autoPlay" to "true"),
                spokenResponse = reply
            )
        }

        // 14C. Universal Search (Google / YouTube / TikTok / Play Store / Any App)
        val isSearchCmd = Regex(
            "\\b(google par|search on|google search|web search|search web|search for|search karo|search kar do|search karein|talaash karo|talash karo|dhoondo|dhoond do)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(lower) ||
            Regex("^(?:please\\s+)?search\\s+(.+)$", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            originalRaw.contains("سرچ") || originalRaw.contains("تلاش")

        if (isSearchCmd && !lower.contains("phone dhoondo") && !lower.contains("find my phone")) {
            val platform = when {
                lower.contains("youtube") -> "youtube"
                lower.contains("tiktok") || lower.contains("tik tok") -> "tiktok"
                lower.contains("instagram") || lower.contains("insta") -> "instagram"
                lower.contains("facebook") || lower.contains("fb") -> "facebook"
                lower.contains("play store") || lower.contains("playstore") -> "playstore"
                lower.contains("spotify") -> "spotify"
                lower.contains("map") || lower.contains("maps") -> "maps"
                else -> "google"
            }
            val searchQuery = trimmed
                .replace(Regex("\\b(google|youtube|tiktok|instagram|facebook|play store|playstore|spotify|maps|par|pe|mein|main|on|in|search|web|for|karo|kar do|kar dein|karein|kholo|talaash|talash|dhoondo|dhoond|please|zara|abhi|سرچ|تلاش|کرو)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { trimmed }
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Searching for '$searchQuery' on $platform.",
                "$platform par '$searchQuery' search kar raha hoon.",
                "$platform پر '$searchQuery' تلاش کر رہا ہوں۔",
                lang
            )
            return VoiceAction(
                action = if (platform == "youtube") "play_video" else "search_web",
                target = searchQuery,
                parameters = mapOf("query" to searchQuery, "platform" to platform),
                spokenResponse = reply
            )
        }

        // 15. Open Application (English, Roman Urdu, Urdu Nastaliq)
        // E.g. "Open YouTube", "YouTube kholo", "یوٹیوب کھولو"
        val appName = extractAppName(trimmed, originalRaw)
        if (appName != null) {
            val canonicalApp = LanguageProcessor.canonicalizeEntityName(appName)
            val isRestricted = canonicalApp.lowercase().let { it.contains("bank") || it.contains("wallet") || it.contains("pay") || it.contains("easypaisa") || it.contains("jazzcash") }
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Opening $canonicalApp.",
                "$canonicalApp khola ja raha hai.",
                "$canonicalApp کھولا جا رہا ہے۔",
                lang
            )
            return VoiceAction(
                action = "open_app",
                target = canonicalApp,
                parameters = mapOf("appName" to canonicalApp),
                confirmationRequired = isRestricted,
                spokenResponse = reply,
                requiresUnlock = isRestricted
            )
        }

        // 16. Time & Date Queries
        if (lower.contains("what time") || lower == "time" || lower.contains("kitne baje") || lower.contains("kya waqt") || lower.contains("time batao") || originalRaw.contains("کتنے بجے") || originalRaw.contains("کیا وقت")) {
            val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            val reply = LanguageProcessor.formatReplyInLanguage(
                "It is currently $time.",
                "Abhi waqt $time hua hai.",
                "ابھی وقت $time ہوا ہے۔",
                lang
            )
            return VoiceAction(action = "general_chat", target = "time", spokenResponse = reply)
        }

        if (lower.contains("what day") || lower.contains("today's date") || lower == "date" || lower.contains("kya tareekh") || lower.contains("date batao") || originalRaw.contains("کیا تاریخ")) {
            val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())
            val reply = LanguageProcessor.formatReplyInLanguage(
                "Today is $date.",
                "Aaj ki tareekh $date hai.",
                "آج کی تاریخ $date ہے۔",
                lang
            )
            return VoiceAction(action = "general_chat", target = "date", spokenResponse = reply)
        }

        // 17. General Conversational Fallback in matching language
        val fallbackReply = LanguageProcessor.formatReplyInLanguage(
            "I heard you say: '$originalRaw'. You can ask me to call, message, control media, toggle settings, or set alarms.",
            "Maine suna: '$originalRaw'. Aap mujh se call, message, alarm, settings ya kisi bhi app ko control karne ka keh sakte hain.",
            "میں نے سنا: '$originalRaw'۔ آپ مجھ سے کال، میسج، الارم یا فون سیٹنگز کنٹرول کرنے کا کہہ سکتے ہیں۔",
            lang
        )
        return VoiceAction(
            action = "general_chat",
            target = trimmed,
            parameters = mapOf("query" to originalRaw),
            confirmationRequired = false,
            spokenResponse = fallbackReply
        )
    }

    private fun stripSimPrefix(rawName: String): String {
        return rawName
            .replace(
                Regex("^(?:(?:sim\\s*[12]|pehli\\s+sim|doosri\\s+sim|dusri\\s+sim|first\\s+sim|second\\s+sim|سم\\s*[12۱٢]|پہلی\\s+سم|دوسری\\s+سم)\\s*(?:se|par|via|from|سے|پر)?\\s*)", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(
                Regex("(?:\\s*(?:via|from|on|se|سے)\\s*(?:sim\\s*[12]|pehli\\s+sim|doosri\\s+sim|dusri\\s+sim|first\\s+sim|second\\s+sim|سم\\s*[12۱٢]|پہلی\\s+سم|دوسری\\s+سم))$", RegexOption.IGNORE_CASE),
                ""
            )
            .trim()
    }

    private fun extractCallTarget(normalized: String, originalRaw: String): String? {
        // English: "Call Ali", "Dial Mom", "Phone Ahmed"
        val engRegex = Regex("^(?:please\\s+)?(?:call|dial|phone)\\s+(?:my\\s+)?([a-zA-Z0-9+\\s]+?)(?:\\s+right\\s+now|\\s+now|\\s+please)?$", RegexOption.IGNORE_CASE)
        engRegex.find(normalized)?.let { return stripSimPrefix(it.groupValues[1].trim()) }

        // Roman Urdu: "Ali ko call karo", "SIM 2 se Ali ko call karo", "Ammi ko phone milao", "Ahmed se rabta karo"
        val romanRegex = Regex("^([a-zA-Z0-9+\\s]+?)\\s+(?:ko|se)\\s+(?:call|phone|number|rabta)(?:\\s+karo|\\s+kro|\\s+milao|\\s+kardo|\\s+krna)?$", RegexOption.IGNORE_CASE)
        romanRegex.find(normalized)?.let { return stripSimPrefix(it.groupValues[1].trim()) }

        // Urdu Nastaliq: "علی کو کال کرو", "سم 2 سے علی کو کال کرو", "امی کو فون ملاؤ"
        val urduRegex = Regex("^(.+?)\\s+کو\\s+(?:کال|فون|نمبر|رابطہ)")
        urduRegex.find(originalRaw.trim())?.let { return stripSimPrefix(it.groupValues[1].trim()) }

        return null
    }

    private fun extractRecipientAndMessage(
        normalized: String,
        originalRaw: String,
        isWhatsApp: Boolean
    ): Pair<String, String> {
        // Pattern 1 (Roman Urdu / Mixed): "<App khol kar> <Recipient> ko <whatsapp> message <send/bhejo/karo> ke <Message>"
        val romanMsgRegex = Regex(
            "(?:whatsapp\\s+(?:khol\\s+kar|par|pe)\\s+)?([a-zA-Z]+)\\s+ko\\s+(?:whatsapp\\s+)?(?:message|sms|text|msg)?\\s*(?:bhejo|send\\s+karo|karo|bhej\\s+do|kro)?\\s*(?:ke|kay|that|:)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        )
        romanMsgRegex.find(normalized)?.let {
            return it.groupValues[1].trim() to it.groupValues[2].trim()
        }

        // Pattern 2 (Roman Urdu without message body): "Ahmed ko whatsapp message bhejo"
        val romanShortRegex = Regex(
            "(?:whatsapp\\s+(?:khol\\s+kar|par|pe)\\s+)?([a-zA-Z]+)\\s+ko\\s+(?:whatsapp\\s+)?(?:message|sms|text|msg)(?:\\s+bhejo|\\s+karo|\\s+send\\s+karo)?$",
            RegexOption.IGNORE_CASE
        )
        romanShortRegex.find(normalized)?.let {
            return it.groupValues[1].trim() to "Assalam-o-Alaikum"
        }

        // Pattern 3 (Urdu Nastaliq): "امی کو میسج بھیجو کہ میں دیر سے آؤں گا"
        val urduMsgRegex = Regex("(?:واٹس\\s*ایپ\\s*پر\\s*)?(.+?)\\s+کو\\s+(?:واٹس\\s*ایپ\\s*)?(?:میسج|پیغام).*?(?:کہ|:)\\s*(.+)$")
        urduMsgRegex.find(originalRaw.trim())?.let {
            return it.groupValues[1].trim() to it.groupValues[2].trim()
        }
        val urduShortRegex = Regex("(?:واٹس\\s*ایپ\\s*پر\\s*)?(.+?)\\s+کو\\s+(?:واٹس\\s*ایپ\\s*)?(?:میسج|پیغام)")
        urduShortRegex.find(originalRaw.trim())?.let {
            return it.groupValues[1].trim() to "السلام علیکم"
        }

        // Pattern 4 (English): "Send a WhatsApp/text/message to Ahmed saying: I'll be late"
        val engRegex = Regex(
            "^(?:send\\s+(?:an?\\s+)?(?:whatsapp(?:\\s+message)?|sms|text|message)\\s+to|text|message|whatsapp)\\s+([a-zA-Z0-9\\s]+?)(?:\\s*(?::|saying|that|with\\s+text)\\s*(.+))?$",
            RegexOption.IGNORE_CASE
        )
        engRegex.find(normalized)?.let {
            val target = it.groupValues[1].trim()
            val body = it.groupValues[2].trim().ifBlank { "Hello" }
            return target to body
        }

        return "" to ""
    }

    private fun extractHourAndMinute(normalized: String, lower: String): Pair<Int, Int> {
        val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(normalized)
        var hour = match?.groupValues?.get(1)?.toIntOrNull() ?: 7
        val minute = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val amPm = match?.groupValues?.get(3)?.lowercase() ?: ""

        val isPmModifier = amPm == "pm" || lower.contains("shaam") || lower.contains("raat") || lower.contains("dopahar") || lower.contains("evening") || lower.contains("night")
        val isAmModifier = amPm == "am" || lower.contains("subah") || lower.contains("morning") || lower.contains("fajar")

        if (isPmModifier && hour in 1..11) hour += 12
        if (isAmModifier && hour == 12) hour = 0

        return hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
    }

    private fun extractAppName(normalized: String, originalRaw: String): String? {
        // English: "open YouTube", "launch Camera"
        val engRegex = Regex("^(?:open|launch|start|run)\\s+([a-zA-Z0-9\\s]+)$", RegexOption.IGNORE_CASE)
        engRegex.find(normalized)?.let { return it.groupValues[1].trim() }

        // Roman Urdu: "YouTube kholo", "Camera open karo", "WhatsApp chalao"
        val romanRegex = Regex("^([a-zA-Z0-9\\s]+?)\\s+(?:kholo|khol\\s+do|open\\s+karo|chalao|start\\s+karo)$", RegexOption.IGNORE_CASE)
        romanRegex.find(normalized)?.let { return it.groupValues[1].trim() }

        // Urdu Nastaliq: "یوٹیوب کھولو"
        val urduRegex = Regex("^(.+?)\\s+(?:کھولو|کھول\\s+دو|چلاؤ)$")
        urduRegex.find(originalRaw.trim())?.let { return it.groupValues[1].trim() }

        return null
    }
}

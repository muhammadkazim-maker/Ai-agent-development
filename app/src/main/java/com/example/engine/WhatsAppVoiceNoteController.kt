package com.example.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceNoteOutcome(
    val ok: Boolean,
    val recipient: String,
    val audioFilePath: String,
    val method: String,
    val message: String,
    val detail: String = message
)

/**
 * WhatsApp Voice Note Controller (Phase 01 Hardened):
 * - Disables the broken voice-note sending path until Phase 11.
 * - Unresolved or ambiguous contacts stop immediately before any Intent is created or launched.
 * - Never falls back to a hardcoded phone number.
 * - Silence, a mic button click, and opening a share sheet (`ACTION_SEND`) are never treated as proof of sending.
 */
class WhatsAppVoiceNoteController(private val context: Context) {

    companion object {
        private const val TAG = "WhatsAppVoiceNoteCtrl"
        @Volatile
        private var instance: WhatsAppVoiceNoteController? = null

        fun getInstance(context: Context): WhatsAppVoiceNoteController {
            return synchronized(this) {
                val appCtx = context.applicationContext ?: context
                val existing = instance
                if (existing == null || existing.context !== appCtx) {
                    WhatsAppVoiceNoteController(appCtx).also { instance = it }
                } else {
                    existing
                }
            }
        }
    }

    private val _lastVoiceNoteStatus = MutableStateFlow("IDLE")
    val lastVoiceNoteStatus: StateFlow<String> = _lastVoiceNoteStatus.asStateFlow()

    suspend fun sendVoiceNote(
        recipientName: String,
        spokenMessage: String
    ): VoiceNoteOutcome {
        val cleanRecipient = recipientName.trim()
        val cleanMessage = spokenMessage.trim()

        // 1. Block private/outbound action whenever device is locked
        if (LockScreenUnlockManager.isDeviceCurrentlyLocked(context)) {
            val msg = "Blocked: Cannot send a WhatsApp voice note while the phone is locked."
            _lastVoiceNoteStatus.value = "BLOCKED_DEVICE_LOCKED: $msg"
            Log.w(TAG, msg)
            return VoiceNoteOutcome(
                ok = false,
                recipient = cleanRecipient,
                audioFilePath = "",
                method = "BLOCKED_DEVICE_LOCKED",
                message = msg
            )
        }

        // 2. Validate recipient and stop BEFORE any intent if unresolved or ambiguous
        if (cleanRecipient.isBlank()) {
            val msg = "Cannot send WhatsApp voice note: No recipient contact name was provided. Stopped before any intent."
            _lastVoiceNoteStatus.value = "MISSING_RECIPIENT: $msg"
            return VoiceNoteOutcome(
                ok = false,
                recipient = "",
                audioFilePath = "",
                method = "MISSING_RECIPIENT",
                message = msg
            )
        }

        val resolution = SmartContactAndAppRegistry.resolveContactWithDisambiguation(context, cleanRecipient)
        val resolvedContactName: String
        when (resolution) {
            is ContactResolutionOutcome.NotFound -> {
                val msg = "Contact '$cleanRecipient' was not found in contacts. Stopped before launching any WhatsApp intent."
                _lastVoiceNoteStatus.value = "CONTACT_NOT_FOUND: $msg"
                Log.w(TAG, msg)
                return VoiceNoteOutcome(
                    ok = false,
                    recipient = cleanRecipient,
                    audioFilePath = "",
                    method = "CONTACT_NOT_FOUND",
                    message = msg
                )
            }
            is ContactResolutionOutcome.Ambiguous -> {
                val names = resolution.candidates.joinToString(", ") { it.name }
                val msg = "Contact '$cleanRecipient' is ambiguous ($names). Stopped before launching any WhatsApp intent."
                _lastVoiceNoteStatus.value = "AMBIGUOUS_CONTACT: $msg"
                Log.w(TAG, msg)
                return VoiceNoteOutcome(
                    ok = false,
                    recipient = cleanRecipient,
                    audioFilePath = "",
                    method = "AMBIGUOUS_CONTACT",
                    message = msg
                )
            }
            is ContactResolutionOutcome.Resolved -> {
                val phone = SmartContactAndAppRegistry.formatWhatsAppPhone(
                    resolution.contact.whatsappNumber.ifBlank { resolution.contact.phoneNumber }
                )
                if (phone.length < 10) {
                    val msg = "Contact '${resolution.contact.name}' does not have a valid phone number. Stopped before launching any WhatsApp intent."
                    _lastVoiceNoteStatus.value = "INVALID_CONTACT_NUMBER: $msg"
                    return VoiceNoteOutcome(
                        ok = false,
                        recipient = resolution.contact.name,
                        audioFilePath = "",
                        method = "INVALID_CONTACT_NUMBER",
                        message = msg
                    )
                }
                resolvedContactName = resolution.contact.name
            }
        }

        if (cleanMessage.isBlank()) {
            val msg = "Cannot send WhatsApp voice note to $resolvedContactName: Message text is empty."
            _lastVoiceNoteStatus.value = "MISSING_MESSAGE: $msg"
            return VoiceNoteOutcome(
                ok = false,
                recipient = resolvedContactName,
                audioFilePath = "",
                method = "MISSING_MESSAGE",
                message = msg
            )
        }

        // 3. Phase 01 Policy: Broken voice-note sending path is disabled until Phase 11.
        // Silence, a mic click, and opening a share sheet are never proof of sending.
        val disabledMsg =
            "WhatsApp voice-note sending to $resolvedContactName is disabled until Phase 11. " +
                "Silence, a microphone click, or opening a share sheet is not proof of sending, so no voice note was sent."
        _lastVoiceNoteStatus.value = "DISABLED_UNTIL_PHASE_11: $disabledMsg"
        Log.i(TAG, disabledMsg)

        return VoiceNoteOutcome(
            ok = false,
            recipient = resolvedContactName,
            audioFilePath = "",
            method = "DISABLED_UNTIL_PHASE_11",
            message = disabledMsg
        )
    }
}

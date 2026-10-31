package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.PendingAppLockChallenge
import com.example.engine.SecretCodeVerificationResult
import com.example.engine.SpeakingAndaz
import com.example.engine.VoiceAppLockAndMoodEngine
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private val StarkDeepBlack = Color(0xFF04060C)
private val StarkCardBg = Color(0xFF090D18)
private val StarkCyan = Color(0xFF00E5FF)
private val CrimsonError = Color(0xFFEF4444)

/**
 * First-Page Setup Card for Universal App Voice Lock, Human Mood/Andaz Intelligence,
 * and the 10 Secret Commands (with 3-Attempt Anti-Eavesdropping Cooldown).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstPageVoiceAppLockSetupCard(
    voiceLockEngine: VoiceAppLockAndMoodEngine,
    onConfigurationSaved: () -> Unit = {},
    onTriggerTestChallenge: (String) -> Unit = {}
) {
    val ownerNameState by voiceLockEngine.ownerName.collectAsState()
    val preferredAndazState by voiceLockEngine.ownerPreferredAndaz.collectAsState()
    val globalLockEnabled by voiceLockEngine.isGlobalAppVoiceLockEnabled.collectAsState()
    val simulateVoiceFail by voiceLockEngine.simulateSoreThroatOrVoiceFail.collectAsState()
    val secretCodes by voiceLockEngine.secretCodes.collectAsState()
    val moodEvaluation by voiceLockEngine.lastMoodEvaluation.collectAsState()
    val ownerPitchHz by voiceLockEngine.ownerBasePitchHz.collectAsState()

    var ownerNameInput by remember(ownerNameState) { mutableStateOf(ownerNameState) }
    var selectedAndaz by remember(preferredAndazState) { mutableStateOf(preferredAndazState) }
    var lockAllAppsToggle by remember(globalLockEnabled) { mutableStateOf(globalLockEnabled) }
    var showAllTenCodesEditor by remember { mutableStateOf(true) }
    var voiceSampleEnrolledFeedback by remember { mutableStateOf("Enrolled (${ownerPitchHz.toInt()} Hz baseline)") }

    val editableCodes = remember(secretCodes) {
        mutableStateListOf<String>().apply {
            secretCodes.forEach { add(it.secretPhrase) }
            while (size < VoiceAppLockAndMoodEngine.REQUIRED_SECRET_CODES_COUNT) {
                add(VoiceAppLockAndMoodEngine.DEFAULT_TEN_SECRET_CODES[size])
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("first_page_voice_applock_card"),
        colors = CardDefaults.cardColors(containerColor = StarkCardBg),
        border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = StarkCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "6. Universal App Voice Lock & 10 Secret Codes",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Har app par Voice Lock + Insan ke Mood/Andaz ki Intelligence + 10 Secret Codes (3-Attempt Cooldown) + Type Code Fallback",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
                Surface(
                    color = if (lockAllAppsToggle) EmeraldTertiary.copy(alpha = 0.15f) else StarkCyan.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (lockAllAppsToggle) EmeraldTertiary else StarkCyan)
                ) {
                    Text(
                        text = if (lockAllAppsToggle) "ALL APPS LOCKED" else "OFF",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (lockAllAppsToggle) EmeraldTertiary else StarkCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Owner Name Input
            OutlinedTextField(
                value = ownerNameInput,
                onValueChange = { ownerNameInput = it },
                label = { Text("Owner Name (For Mood & Voice Verification)", fontSize = 12.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_voice_lock_owner_name"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StarkCyan,
                    unfocusedBorderColor = DarkSurfaceBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Voice Enrollment & Pitch Calibration Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Owner Voice Biometric Print",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = voiceSampleEnrolledFeedback,
                        fontSize = 11.sp,
                        color = EmeraldTertiary
                    )
                }
                OutlinedButton(
                    onClick = {
                        voiceLockEngine.enrollOwnerVoiceSample(customPitchHz = 150f)
                        voiceSampleEnrolledFeedback = "Voice Verified & Enrolled (150 Hz Timbre)"
                    },
                    modifier = Modifier.testTag("btn_enroll_owner_voice"),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, StarkCyan),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StarkCyan)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Calibrate Voice", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Speaking Style ("Andaz") Selector
            Text(
                text = "Owner Natural Speaking Style (Andaz) & Mood Profile:",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = StarkCyan
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    SpeakingAndaz.FRIENDLY_CASUAL,
                    SpeakingAndaz.POLITE_RESPECTFUL,
                    SpeakingAndaz.DIRECT_COMMANDING
                ).forEach { andaz ->
                    val isSelected = selectedAndaz == andaz.name
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedAndaz = andaz.name },
                        label = {
                            Text(
                                text = andaz.displayName,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StarkCyan.copy(alpha = 0.25f),
                            selectedLabelColor = StarkCyan,
                            containerColor = StarkDeepBlack,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) StarkCyan else DarkSurfaceBorder
                        )
                    )
                }
            }

            // Live Mood & Andaz Intelligence Readout
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                color = StarkDeepBlack,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, DarkSurfaceBorder)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = EmeraldTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Mood & Andaz AI: ${moodEvaluation.detectedMood.romanUrduLabel} • ${moodEvaluation.detectedAndaz.displayName}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldTertiary
                        )
                        Text(
                            text = "General baatein har kisi ke sath hongi, lekin mobile ki tamam apps sirf aap ki voice verify hone ya 10 Secret Codes se hi open hongi.",
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Toggle 1: Lock All Mobile Apps with Voice Lock
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lock All Mobile Apps with Voice Lock",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Protects WhatsApp, YouTube, Instagram, Gallery, Settings & all apps",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = lockAllAppsToggle,
                    onCheckedChange = {
                        lockAllAppsToggle = it
                        voiceLockEngine.setGlobalAppVoiceLockEnabled(it)
                    },
                    modifier = Modifier.testTag("switch_global_voice_app_lock"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = StarkCyan,
                        checkedTrackColor = StarkCyan.copy(alpha = 0.3f)
                    )
                )
            }

            // Toggle 2: Gala Kharab / Sore Throat Mode (for instant testing or when throat is sore)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gala Kharab / Sore Throat Fallback Mode",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Jab voice match na ho to assistant 10 Secret Codes maangay ga",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = simulateVoiceFail,
                    onCheckedChange = { voiceLockEngine.setSimulateSoreThroatOrVoiceFail(it) },
                    modifier = Modifier.testTag("switch_sore_throat_mode"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = EmeraldTertiary,
                        checkedTrackColor = EmeraldTertiary.copy(alpha = 0.3f)
                    )
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = DarkSurfaceBorder
            )

            // 10 Secret Fallback Codes Section (with 3-Attempt Rotation Rule)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "10 SECRET FALLBACK CODES (3-ATTEMPT COOLDOWN)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StarkCyan,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "Jo code aik baar use ho jaye uss ki baari 3 attempts ke baad wapas aayegi",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
                OutlinedButton(
                    onClick = { showAllTenCodesEditor = !showAllTenCodesEditor },
                    modifier = Modifier.testTag("btn_toggle_ten_secret_codes"),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = if (showAllTenCodesEditor) "Hide 10 Codes" else "Edit 10 Codes",
                        fontSize = 10.sp,
                        color = StarkCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (showAllTenCodesEditor) {
                Spacer(modifier = Modifier.height(8.dp))
                for (i in 0 until VoiceAppLockAndMoodEngine.REQUIRED_SECRET_CODES_COUNT) {
                    val entry = secretCodes.getOrNull(i)
                    val cooldownLeft = entry?.cooldownAttemptsRemaining ?: 0
                    val isReady = cooldownLeft == 0

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = editableCodes.getOrElse(i) { "" },
                            onValueChange = { newVal ->
                                editableCodes[i] = newVal
                                voiceLockEngine.updateSingleSecretCode(i, newVal)
                            },
                            label = {
                                Text(
                                    text = if (isReady) {
                                        "Secret Code #${i + 1} (READY)"
                                    } else {
                                        "Secret Code #${i + 1} (Wait $cooldownLeft attempts)"
                                    },
                                    fontSize = 11.sp
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("input_secret_code_${i + 1}"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (isReady) StarkCyan else CrimsonError,
                                unfocusedBorderColor = if (isReady) DarkSurfaceBorder else CrimsonError.copy(alpha = 0.5f),
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = if (isReady) EmeraldTertiary.copy(alpha = 0.15f) else CrimsonError.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isReady) EmeraldTertiary.copy(alpha = 0.5f) else CrimsonError.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = if (isReady) "ACTIVE" else "WAIT $cooldownLeft/3",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isReady) EmeraldTertiary else CrimsonError,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons: Save First-Page Setup + Test / Type Secret Code Modal
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        voiceLockEngine.saveFirstPageVoiceLockConfiguration(
                            ownerNameInput = ownerNameInput,
                            preferredAndaz = selectedAndaz,
                            codesList = editableCodes.toList(),
                            enableAllAppsLock = lockAllAppsToggle
                        )
                        onConfigurationSaved()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_save_first_page_voice_lock"),
                    colors = ButtonDefaults.buttonColors(containerColor = StarkCyan),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Color(0xFF030712), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ACTIVATE VOICE LOCK",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF030712)
                    )
                }

                OutlinedButton(
                    onClick = {
                        voiceLockEngine.saveFirstPageVoiceLockConfiguration(
                            ownerNameInput = ownerNameInput,
                            preferredAndaz = selectedAndaz,
                            codesList = editableCodes.toList(),
                            enableAllAppsLock = lockAllAppsToggle
                        )
                        onTriggerTestChallenge("WhatsApp")
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_open_secret_code_verifier"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, EmeraldTertiary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldTertiary)
                ) {
                    Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "VERIFY / TYPE CODE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Interactive Voice Lock & 10-Secret-Code Challenge Modal.
 * Shown when:
 * 1. User asks to open an app (or taps an app) and voice verification fails (e.g., sore throat / noise).
 * 2. User wants to speak or type one of the 10 secret codes to unlock the app.
 * Enforces the 3-attempt anti-eavesdropping cooldown rotation and provides a manual Type Code option.
 */
@Composable
fun VoiceAppLockChallengeDialog(
    voiceLockEngine: VoiceAppLockAndMoodEngine,
    challenge: PendingAppLockChallenge?,
    onCodeVerifiedAndUnlocked: (SecretCodeVerificationResult.Unlocked) -> Unit,
    onRequestMicForSpokenCode: () -> Unit,
    onDismiss: () -> Unit
) {
    val secretCodes by voiceLockEngine.secretCodes.collectAsState()
    val moodEval by voiceLockEngine.lastMoodEvaluation.collectAsState()

    var typedCodeInput by remember { mutableStateOf("") }
    var feedbackBanner by remember(challenge) {
        mutableStateOf(
            challenge?.failureReasonRomanUrdu
                ?: "Aap ki voice recognize nahi ho rahi, please apne 10 Secret Codes mein se koi aik code bolein ya niche type karein."
        )
    }
    var isErrorFeedback by remember { mutableStateOf(false) }
    var isSuccessFeedback by remember { mutableStateOf(false) }

    val targetAppName = challenge?.appName?.ifBlank { "Locked App" } ?: "Locked Mobile App"

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 640.dp)
                .padding(12.dp)
                .testTag("voice_app_lock_challenge_dialog"),
            colors = CardDefaults.cardColors(containerColor = StarkDeepBlack),
            border = BorderStroke(1.5.dp, StarkCyan),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Surface(
                            color = StarkCyan.copy(alpha = 0.15f),
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = StarkCyan, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "VOICE LOCK GUARD: ${targetAppName.uppercase()}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = StarkCyan,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Mood: ${moodEval.detectedMood.romanUrduLabel} • Andaz: ${moodEval.detectedAndaz.displayName}",
                                fontSize = 11.sp,
                                color = EmeraldTertiary
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("btn_dismiss_challenge_dialog")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Status / Assistant Prompt Banner
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("secret_code_feedback_banner"),
                    color = when {
                        isSuccessFeedback -> EmeraldTertiary.copy(alpha = 0.15f)
                        isErrorFeedback -> CrimsonError.copy(alpha = 0.15f)
                        else -> StarkCardBg
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        when {
                            isSuccessFeedback -> EmeraldTertiary
                            isErrorFeedback -> CrimsonError
                            else -> StarkCyan.copy(alpha = 0.4f)
                        }
                    )
                ) {
                    Text(
                        text = feedbackBanner,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            isSuccessFeedback -> EmeraldTertiary
                            isErrorFeedback -> CrimsonError
                            else -> TextPrimary
                        },
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Option A: Speak Secret Code via Microphone
                OutlinedButton(
                    onClick = onRequestMicForSpokenCode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("btn_speak_secret_code"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, StarkCyan),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StarkCyan)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SPEAK SECRET CODE (VOICE INPUT)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Option B: Type Secret Code Fallback (For Speech Glitch / Manual Entry)
                Text(
                    text = "TYPE SECRET CODE (GLITCH / SILENT FALLBACK):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = StarkCyan,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = typedCodeInput,
                    onValueChange = { typedCodeInput = it },
                    label = { Text("Type any 1 of your 10 Secret Codes...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_type_secret_code"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StarkCyan,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        when (val result = voiceLockEngine.verifySecretCode(typedCodeInput)) {
                            is SecretCodeVerificationResult.Unlocked -> {
                                isErrorFeedback = false
                                isSuccessFeedback = true
                                feedbackBanner = result.spokenConfirmationRomanUrdu
                                onCodeVerifiedAndUnlocked(result)
                            }
                            is SecretCodeVerificationResult.CodeOnCooldown -> {
                                isSuccessFeedback = false
                                isErrorFeedback = true
                                feedbackBanner = result.spokenErrorRomanUrdu
                            }
                            is SecretCodeVerificationResult.InvalidCode -> {
                                isSuccessFeedback = false
                                isErrorFeedback = true
                                feedbackBanner = result.spokenErrorRomanUrdu
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("btn_verify_secret_code"),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = Color(0xFF030712))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "VERIFY SECRET CODE & UNLOCK APP",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF030712)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = DarkSurfaceBorder)
                Spacer(modifier = Modifier.height(10.dp))

                // Live 10 Secret Codes Pool Status (Shows Available vs 3-Attempt Cooldown)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "YOUR 10 SECRET CODES (TAP TO AUTO-FILL):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                    TextButtonResetCooldowns(
                        onReset = {
                            voiceLockEngine.resetAllSecretCodeCooldowns()
                            isErrorFeedback = false
                            feedbackBanner = "Tamam 10 Secret Codes ke cooldown reset ho gaye hain."
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                secretCodes.forEach { entry ->
                    val isAvailable = entry.isAvailableNow
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable {
                                typedCodeInput = entry.secretPhrase
                            }
                            .testTag("chip_secret_code_${entry.index + 1}"),
                        color = if (isAvailable) StarkCardBg else Color(0xFF1F1218),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isAvailable) EmeraldTertiary.copy(alpha = 0.45f) else CrimsonError.copy(alpha = 0.45f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isAvailable) Icons.Default.Key else Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (isAvailable) EmeraldTertiary else CrimsonError,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "#${entry.index + 1}: ${entry.secretPhrase}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isAvailable) TextPrimary else TextSecondary
                                )
                            }
                            Text(
                                text = if (isAvailable) {
                                    "READY"
                                } else {
                                    "COOLDOWN (${entry.cooldownAttemptsRemaining} attempts left)"
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isAvailable) EmeraldTertiary else CrimsonError
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextButtonResetCooldowns(onReset: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable { onReset() }
            .padding(4.dp)
            .testTag("btn_reset_code_cooldowns"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Refresh, contentDescription = "Reset Cooldowns", tint = StarkCyan, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Reset Cooldowns",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = StarkCyan
        )
    }
}

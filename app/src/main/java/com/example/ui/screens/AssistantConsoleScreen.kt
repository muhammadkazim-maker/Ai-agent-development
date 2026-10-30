package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.model.VoiceState
import com.example.ui.components.VoiceOrb
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletSecondary
import com.example.ui.theme.containsUrduScript
import com.example.ui.theme.fontFamilyForBilingualText
import com.example.ui.theme.textDirectionForBilingualText
import com.example.ui.viewmodel.VoiceAssistantViewModel

@Composable
fun AssistantConsoleScreen(
    viewModel: VoiceAssistantViewModel,
    onOpenAiSkills: () -> Unit = {}
) {
    val voiceState by viewModel.voiceState.collectAsState()
    val liveTranscription by viewModel.liveTranscription.collectAsState()
    val spokenResponse by viewModel.spokenResponse.collectAsState()
    val audioRms by viewModel.audioRms.collectAsState()
    val isLockedModeSimulated by viewModel.isLockedModeSimulated.collectAsState()
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val lockoutSeconds by viewModel.lockoutSeconds.collectAsState()
    val config by viewModel.config.collectAsState()
    val detectedLanguage by viewModel.detectedLanguage.collectAsState()
    val lastExecutedAction by viewModel.lastExecutedAction.collectAsState()
    val plannedSteps by viewModel.plannedSteps.collectAsState()

    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleForegroundService(true)
        }
    }

    var manualCommandText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Status Bar: Background Service & Simulated Lock Switch
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = Brush.horizontalGradient(listOf(DarkSurfaceBorder, DarkSurfaceBorder))
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isServiceRunning) EmeraldTertiary else Color(0xFF64748B))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isServiceRunning) "Listening for '${config.wakeWord}'" else "Background Mic Paused",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isServiceRunning) "Multi-Engine STT + Wake-Word Active" else "Tap switch to enable wake-word",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }

                Switch(
                    checked = isServiceRunning,
                    onCheckedChange = { enable ->
                        if (enable) {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) {
                                viewModel.toggleForegroundService(true)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            viewModel.toggleForegroundService(false)
                        }
                    },
                    modifier = Modifier.testTag("service_toggle_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyanPrimary,
                        checkedTrackColor = CyanPrimary.copy(alpha = 0.3f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick Launch Banner for the 7 Gemini AI Studio Skills
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .clickable { onOpenAiSkills() }
                .testTag("open_ai_skills_banner"),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CyanPrimary.copy(alpha = 0.14f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "✨ Gemini 4 Argon + 3.8 Flash Router Ready",
                        color = CyanPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Deep Thinking • Multi-Step Agent • Search Grounding • Live Voice • Bilingual Urdu/English",
                        color = TextPrimary,
                        fontSize = 10.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Skills →",
                    color = CyanPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Lock Mode Simulator Switch
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isLockedModeSimulated) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (isLockedModeSimulated) Color(0xFFFBBF24) else EmeraldTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isLockedModeSimulated) "Simulated Mode: LOCKED SCREEN" else "Mode: UNLOCKED",
                            color = if (isLockedModeSimulated) Color(0xFFFBBF24) else EmeraldTertiary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isLockedModeSimulated) "Requires spoken passphrase verification" else "Executes commands directly without passphrase",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }

                Switch(
                    checked = isLockedModeSimulated,
                    onCheckedChange = { viewModel.toggleSimulateLockedMode() },
                    modifier = Modifier.testTag("lock_simulator_switch")
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Center Voice Orb
        VoiceOrb(
            voiceState = voiceState,
            audioRms = audioRms
        )

        Spacer(modifier = Modifier.height(14.dp))

        // State Label
        val (stateTitle, stateColor) = when (voiceState) {
            is VoiceState.Idle -> "Tap mic or say '${config.wakeWord}'" to TextSecondary
            is VoiceState.ListeningForWakeWord -> "Awaiting wake phrase..." to CyanPrimary
            is VoiceState.WakeWordDetected -> "Wake word recognized!" to EmeraldTertiary
            is VoiceState.ListeningForCommand -> "Listening (Urdu / Roman Urdu / English)..." to CyanPrimary
            is VoiceState.ProcessingAi -> "Gemini Reasoning & Planning..." to VioletSecondary
            is VoiceState.AwaitingPassphrase -> "Speak your security passphrase" to Color(0xFFF59E0B)
            is VoiceState.VerifyingVoice -> "Verifying voice signature..." to VioletSecondary
            is VoiceState.ConfirmingAction -> "Safety Check: Confirm with 'Yes / Haan' or 'No / Nahi'" to Color(0xFFF59E0B)
            is VoiceState.ExecutingAction -> "Executing phone action..." to EmeraldTertiary
            is VoiceState.Speaking -> "Speaking in ${detectedLanguage.label}..." to CyanPrimary
            is VoiceState.Error -> (voiceState as VoiceState.Error).message to Color(0xFFEF4444)
        }

        Text(
            text = stateTitle,
            color = stateColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        if (lockoutSeconds > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Security Lockout: $lockoutSeconds seconds remaining",
                color = Color(0xFFEF4444),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Safety Confirmation Card for Risky Actions
        if (voiceState is VoiceState.ConfirmingAction) {
            val pending = (voiceState as VoiceState.ConfirmingAction).question
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SAFETY CONFIRMATION REQUIRED",
                            color = Color(0xFFFBBF24),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = pending,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { viewModel.confirmPendingActionNow() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldTertiary,
                                contentColor = Color(0xFF002114)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("confirm_action_yes_btn")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Confirm (Haan)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { viewModel.cancelPendingActionNow() },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("confirm_action_no_btn")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cancel (Nahi)", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Live Transcription & Response Card with RTL + Noto Nastaliq Urdu support
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = Brush.horizontalGradient(listOf(DarkSurfaceBorder, DarkSurfaceBorder))
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "BILINGUAL LIVE TRANSCRIPT",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CyanPrimary.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Reply Mode: ${detectedLanguage.label}",
                            color = CyanPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (liveTranscription.isNotBlank()) {
                    val isUrduInput = containsUrduScript(liveTranscription)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (isUrduInput) "آپ:" else "You:",
                            color = CyanPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = if (isUrduInput) TextAlign.End else TextAlign.Start
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = liveTranscription,
                            color = TextPrimary,
                            fontSize = if (isUrduInput) 16.sp else 14.sp,
                            lineHeight = if (isUrduInput) 28.sp else 20.sp,
                            fontFamily = fontFamilyForBilingualText(liveTranscription),
                            style = TextStyle(
                                textDirection = textDirectionForBilingualText(liveTranscription)
                            ),
                            textAlign = if (isUrduInput) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Text(
                        text = "Speak in English, اردو (Nastaliq), Roman Urdu, or Mixed Urdu/English.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }

                if (spokenResponse.isNotBlank()) {
                    val isUrduReply = containsUrduScript(spokenResponse)
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurfaceVariant)
                            .padding(12.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (isUrduReply) "اومنی وائس:" else "OmniVoice:",
                                color = VioletSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = if (isUrduReply) TextAlign.End else TextAlign.Start
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = spokenResponse,
                                color = TextPrimary,
                                fontSize = if (isUrduReply) 16.sp else 13.sp,
                                lineHeight = if (isUrduReply) 28.sp else 20.sp,
                                fontFamily = fontFamilyForBilingualText(spokenResponse),
                                style = TextStyle(
                                    textDirection = textDirectionForBilingualText(spokenResponse)
                                ),
                                textAlign = if (isUrduReply) TextAlign.End else TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // Planned Steps Card (Multi-Step Chained Execution)
        if (plannedSteps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "MULTI-STEP EXECUTION PLAN (${plannedSteps.size} STEPS)",
                        color = VioletSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    plannedSteps.forEachIndexed { idx, step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldTertiary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${idx + 1}. $step",
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Last Executed Action Card with Quick Undo Button
        lastExecutedAction?.let { lastCmd ->
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LAST ACTION: ${lastCmd.action.uppercase()}",
                            color = EmeraldTertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = lastCmd.target.ifBlank { lastCmd.spokenResponse },
                            color = TextPrimary,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.undoLastAction() },
                        modifier = Modifier.testTag("quick_undo_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo Last Action",
                            tint = CyanPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Undo", color = CyanPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Bilingual Quick Command Chips (Urdu Nastaliq, Roman Urdu, Code-Mixed, Chained, Conditional)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
        ) {
            Text(
                text = "BILINGUAL & MULTI-STEP QUICK COMMANDS",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            val quickCommands = listOf(
                "علی کو کال کرو",
                "Kal subah 6 baje alarm laga do",
                "WhatsApp khol kar Ahmed ko message send karo ke I'll be late",
                "Mera phone silent karo, battery saver on karo, aur 30 minute ka timer lagao",
                "Subah 7 baje alarm lagao, phir Ammi ko message karo ke main late hoon, aur raaste ka navigation shuru karo",
                "If Ali calls, reject it and send him 'I'll call you later'",
                "'Jani' ka matlab mera best friend Bilal hai, yeh word yaad kar lo",
                "Meri screen par kya hai",
                "フラッシュライト / Turn on flashlight"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (cmd in quickCommands) {
                    val isUrduChip = containsUrduScript(cmd)
                    AssistChip(
                        onClick = { viewModel.submitTextCommand(cmd) },
                        label = {
                            Text(
                                text = cmd,
                                fontSize = if (isUrduChip) 14.sp else 12.sp,
                                fontFamily = fontFamilyForBilingualText(cmd)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = DarkSurfaceVariant,
                            labelColor = CyanPrimary
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Direct Text Input Field (Supports English, Roman Urdu, and Urdu Nastaliq)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = manualCommandText,
                onValueChange = { manualCommandText = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("manual_command_input"),
                placeholder = {
                    Text(
                        "Type English, Roman Urdu, or اردو...",
                        fontSize = 13.sp
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = DarkSurfaceBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (manualCommandText.isNotBlank()) {
                        viewModel.submitTextCommand(manualCommandText)
                        manualCommandText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(CyanPrimary, RoundedCornerShape(12.dp))
                    .testTag("send_command_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send Command",
                    tint = Color(0xFF003548),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Main Microphone FAB
        FloatingActionButton(
            onClick = {
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasMic) {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    if (voiceState is VoiceState.ListeningForCommand || voiceState is VoiceState.AwaitingPassphrase) {
                        viewModel.stopListening()
                    } else {
                        viewModel.startListeningForCommand()
                    }
                }
            },
            modifier = Modifier
                .size(68.dp)
                .testTag("mic_fab"),
            containerColor = if (voiceState is VoiceState.ListeningForCommand) Color(0xFFEF4444) else CyanPrimary,
            contentColor = Color(0xFF003548)
        ) {
            Icon(
                imageVector = if (voiceState is VoiceState.ListeningForCommand) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = "Toggle Voice Listening",
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (voiceState is VoiceState.ListeningForCommand) "Tap to Stop Listening" else "Tap to Speak (English / اردو / Roman Urdu)",
            color = TextSecondary,
            fontSize = 12.sp
        )
    }
}

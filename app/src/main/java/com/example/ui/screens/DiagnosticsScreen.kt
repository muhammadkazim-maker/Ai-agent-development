package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.engine.Android12To17Compat
import com.example.engine.CapabilityProbe
import com.example.engine.DualSimManager
import com.example.engine.SttEngineType
import com.example.service.OmniAccessibilityService
import com.example.service.OmniDeviceAdminReceiver
import com.example.service.OmniNotificationListenerService
import com.example.service.WakeWordForegroundService
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.VoiceAssistantViewModel

data class DiagnosticTest(
    val category: String,
    val name: String,
    val passed: Boolean,
    val description: String,
    val fixActionLabel: String? = null,
    val onFixAction: (() -> Unit)? = null
)

@Composable
fun DiagnosticsScreen(viewModel: VoiceAssistantViewModel) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var logCopiedMessage by remember { mutableStateOf<String?>(null) }

    val isServiceActive by WakeWordForegroundService.isServiceActive.collectAsState()
    val hasPassphrase by viewModel.hasPassphrase.collectAsState()
    val enrolledProfile by viewModel.enrolledProfile.collectAsState()
    val currentAudioOwner by viewModel.audioStateMachine.currentOwner.collectAsState()
    val activeEngine by viewModel.activeSttEngine.collectAsState()
    val sttLastError by viewModel.sttLastError.collectAsState()
    val audioRms by viewModel.audioRms.collectAsState()
    val animatedRms by animateFloatAsState(targetValue = (audioRms / 100f).coerceIn(0f, 1f), label = "vu_meter")

    val capabilityProfile = remember(refreshKey, isServiceActive) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val netActive = cm?.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        CapabilityProbe.probeDevice(context, isOnlineAndApiKeyConfigured = netActive)
    }

    var preferredSimSlot by remember(refreshKey) {
        mutableIntStateOf(DualSimManager.getPreferredSimSlot(context))
    }

    val tests = remember(
        refreshKey,
        isServiceActive,
        hasPassphrase,
        enrolledProfile,
        activeEngine,
        sttLastError,
        capabilityProfile
    ) {
        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkActive = cm?.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

        val isSpeechRecogAvailable = viewModel.sttManager.isRecognitionAvailable()
        val isAccessibilityActive = OmniAccessibilityService.isConnected()
        val isNotifListenerActive = OmniNotificationListenerService.isServiceRunning()
        val isDeviceAdminActive = OmniDeviceAdminReceiver.isAdminActive(context)
        val audioFocusAllowed = Android12To17Compat.probeAudioFocusHardening(context)

        listOf(
            DiagnosticTest(
                category = "ROLE & OS",
                name = "Default Digital Assistant Role (ROLE_ASSISTANT)",
                passed = capabilityProfile.hasRoleAssistant,
                description = if (capabilityProfile.hasRoleAssistant) {
                    "RoleManager.ROLE_ASSISTANT held. VoiceInteractionSession & lock-screen hotword enabled."
                } else {
                    "OmniVoice is not set as Default Digital Assistant. Recommended for Android 14–17 lock-screen voice trigger."
                },
                fixActionLabel = if (!capabilityProfile.hasRoleAssistant) "Set Default" else null,
                onFixAction = {
                    try {
                        context.startActivity(Android12To17Compat.createRequestRoleAssistantIntent(context))
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }
                }
            ),
            DiagnosticTest(
                category = "ANDROID 17",
                name = "Advanced Protection & Accessibility Compatibility",
                passed = !capabilityProfile.isAdvancedProtectionEnabled,
                description = if (!capabilityProfile.isAdvancedProtectionEnabled) {
                    "Advanced Protection is OFF. Full L3 Accessibility UI automation is permitted."
                } else {
                    "Android 17 Advanced Protection is ON (Accessibility blocked by OS). Running in Tier D (L1/L2/L4 Direct APIs)."
                }
            ),
            DiagnosticTest(
                category = "ANDROID 17",
                name = "Background-Audio & Volume Hardening Check",
                passed = audioFocusAllowed,
                description = if (audioFocusAllowed) {
                    "AudioFocus & AudioManager volume adjustments are permitted in current lifecycle state."
                } else {
                    "Android 17 background-audio hardening active: bring assistant session to foreground before changing volume."
                }
            ),
            DiagnosticTest(
                category = "AUDIO",
                name = "Microphone Permission & Privacy Toggle",
                passed = hasMicPermission,
                description = if (hasMicPermission) {
                    "Hardware RECORD_AUDIO granted (All-zero mic privacy toggle detector active)."
                } else {
                    "Microphone permission is denied or revoked."
                },
                fixActionLabel = if (!hasMicPermission) "Grant Mic" else null,
                onFixAction = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            ),
            DiagnosticTest(
                category = "AUDIO",
                name = "Raw Audio & Auto-VAD Pipeline",
                passed = isSpeechRecogAvailable,
                description = if (isSpeechRecogAvailable) {
                    "16kHz PCM AudioRecord + AEC/NS/AGC + Auto-VAD ready"
                } else {
                    "Tap to Activate Omni Microphone permission required."
                },
                fixActionLabel = if (!isSpeechRecogAvailable) "Enable Mic" else null,
                onFixAction = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            ),
            DiagnosticTest(
                category = "AUDIO",
                name = "Active Multimodal Audio Engine",
                passed = true,
                description = when (activeEngine) {
                    SttEngineType.GEMINI_AUDIO_PCM -> "Primary: Raw Microphone PCM + Auto-VAD + Gemini Native Multimodal Audio"
                    SttEngineType.OFFLINE_ACOUSTIC_VAD -> "Secondary: On-Device Offline Acoustic & Cadence VAD Matcher"
                }
            ),
            DiagnosticTest(
                category = "CONNECTIVITY",
                name = "Internet & Gemini Endpoint",
                passed = networkActive,
                description = if (networkActive) {
                    "Network active. Cloud Gemini 2.5 Flash/Pro router online."
                } else {
                    "No internet connection. Assistant running in ${capabilityProfile.offlineTier.label}."
                },
                fixActionLabel = if (!networkActive) "Open Wi-Fi" else null,
                onFixAction = {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                }
            ),
            DiagnosticTest(
                category = "SYSTEM",
                name = "Battery Optimization Exemption (${capabilityProfile.oemSkin.displayName})",
                passed = ignoringBattery,
                description = if (ignoringBattery) {
                    "Whitelisted from battery restrictions (24/7 background uptime guaranteed)"
                } else {
                    capabilityProfile.oemSkin.batteryFixGuide
                },
                fixActionLabel = if (!ignoringBattery) "Exempt App" else null,
                onFixAction = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }
                }
            ),
            DiagnosticTest(
                category = "SYSTEM",
                name = "Permission Auto-Reset Disabled (Hibernation Off)",
                passed = capabilityProfile.isAutoRevokeWhitelisted,
                description = if (capabilityProfile.isAutoRevokeWhitelisted) {
                    "Auto-revoke whitelisted. OS will not strip permissions when idle."
                } else {
                    "Turn off 'Remove permissions if app isn't used' in App Info."
                },
                fixActionLabel = if (!capabilityProfile.isAutoRevokeWhitelisted) "Whitelist" else null,
                onFixAction = {
                    context.startActivity(Android12To17Compat.createAutoRevokeWhitelistIntent(context))
                }
            ),
            DiagnosticTest(
                category = "BACKGROUND",
                name = "Wake-Word Foreground Service",
                passed = isServiceActive,
                description = if (isServiceActive) {
                    "Active 24/7 foreground service with foregroundServiceType=microphone"
                } else {
                    "Foreground listener is stopped. Tap Start Service to activate."
                },
                fixActionLabel = if (!isServiceActive) "Start Service" else null,
                onFixAction = {
                    viewModel.toggleForegroundService(true)
                }
            ),
            DiagnosticTest(
                category = "SERVICES",
                name = "Accessibility Service (L3 UI Control)",
                passed = isAccessibilityActive,
                description = if (isAccessibilityActive) {
                    "Connected. Full cross-app UI automation, gestures, and settings navigation enabled."
                } else {
                    "Service not enabled. Required for L3 UI automation when direct APIs are blocked."
                },
                fixActionLabel = if (!isAccessibilityActive) "Enable" else null,
                onFixAction = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                }
            ),
            DiagnosticTest(
                category = "SERVICES",
                name = "Notification Listener (RemoteInput Reply)",
                passed = isNotifListenerActive,
                description = if (isNotifListenerActive) {
                    "Authorized to read and reply to incoming WhatsApp / SMS messages in background."
                } else {
                    "Notification access required for reading and replying to messages aloud."
                },
                fixActionLabel = if (!isNotifListenerActive) "Enable" else null,
                onFixAction = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                }
            ),
            DiagnosticTest(
                category = "SECURITY",
                name = "Device Administrator (Screen Lock)",
                passed = isDeviceAdminActive,
                description = if (isDeviceAdminActive) {
                    "Active. Grants instant screen lock on voice command."
                } else {
                    "Admin not active. Tap Activate to enable instant screen lock."
                },
                fixActionLabel = if (!isDeviceAdminActive) "Activate" else null,
                onFixAction = {
                    OmniDeviceAdminReceiver.requestAdminPermission(context)
                }
            ),
            DiagnosticTest(
                category = "SECURITY",
                name = "Spoken Passphrase & Biometrics",
                passed = hasPassphrase,
                description = if (hasPassphrase) {
                    "Passphrase hash stored in Keystore. Voice biometrics enrolled: ${enrolledProfile.isEnrolled}"
                } else {
                    "Spoken passphrase not configured. Setup in Security tab."
                },
                fixActionLabel = if (!hasPassphrase) "Configure" else null,
                onFixAction = {
                    viewModel.ttsManager.speak("Please open the Security tab to enroll your secret passphrase.")
                }
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(CyanPrimary.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "DIAGNOSTICS & CAPABILITY PROBE (ANDROID 12–17)",
                                color = CyanPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "${capabilityProfile.manufacturer} ${capabilityProfile.model} • ${capabilityProfile.androidReleaseName}",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Active Tier: ${capabilityProfile.currentTier.label}",
                        color = EmeraldTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "RAM: ${capabilityProfile.availableRamMb} MB free / ${capabilityProfile.totalRamMb} MB total • Storage: ${capabilityProfile.freeStorageMb} MB free • ABI arm64-v8a: ${capabilityProfile.isArm64V8a} • 16KB Page-Size Ready: ${capabilityProfile.supports16KbPageSize}",
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "OEM Skin: ${capabilityProfile.oemSkin.displayName} • Play Services: ${capabilityProfile.hasGooglePlayServices} • AICore (Gemini Nano): ${capabilityProfile.hasAiCoreGeminiNano} • Shizuku: ${capabilityProfile.isShizukuInstalled}",
                        color = TextSecondary,
                        fontSize = 10.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val report = CapabilityProbe.buildSanitizedDiagnosticsReport(
                                context = context,
                                profile = capabilityProfile,
                                activeEngineName = activeEngine.name,
                                audioOwnerName = currentAudioOwner.name,
                                lastError = sttLastError
                            )
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("OmniVoice Diagnostics", report))
                            logCopiedMessage = "Sanitized diagnostics report copied to clipboard (secrets stripped)."
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_sanitized_diagnostics_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export Sanitized Diagnostics Log (No Secrets)", fontSize = 11.sp, color = CyanPrimary)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(com.omnivoice.agentfix.AgentLog.shareIntent())
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("share_agent_log_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Share agent log", fontSize = 11.sp, color = EmeraldTertiary, fontWeight = FontWeight.Bold)
                    }

                    if (!logCopiedMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = logCopiedMessage!!, color = EmeraldTertiary, fontSize = 10.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Dual-SIM Selector & Status Card (R16 / F-139)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SimCard, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "DUAL-SIM VOICE CALL & SMS ROUTING",
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val simDesc = if (capabilityProfile.simSlots.isEmpty()) {
                        "Detected SIMs: System Default (Say 'SIM 1 se call karo' or 'SIM 2 se call karo')"
                    } else {
                        capabilityProfile.simSlots.joinToString(" | ") {
                            "SIM ${it.slotIndex + 1}: ${it.carrierName}"
                        }
                    }
                    Text(text = simDesc, color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0 to "Ask / Auto", 1 to "Prefer SIM 1", 2 to "Prefer SIM 2").forEach { (slot, label) ->
                            val selected = preferredSimSlot == slot
                            OutlinedButton(
                                onClick = {
                                    DualSimManager.setPreferredSimSlot(context, slot)
                                    preferredSimSlot = slot
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    color = if (selected) EmeraldTertiary else TextSecondary,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Live Microphone Input Level & Engine Status Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "MICROPHONE VU METER",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "${audioRms.toInt()} dB",
                            color = CyanPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { animatedRms },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = if (animatedRms > 0.6f) EmeraldTertiary else CyanPrimary,
                        trackColor = Color(0xFF1E293B)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Mic Owner: $currentAudioOwner | Active Engine: ${activeEngine.name}",
                        color = TextSecondary,
                        fontSize = 10.sp
                    )

                    if (!sttLastError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Last Handled Error: $sttLastError",
                            color = Color(0xFFFBBF24),
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Quick self-tests, Acoustic Echo Test, and On-Device LLM Status
        item {
            var echoTestReport by remember { mutableStateOf<String?>(null) }
            val modelRec = remember {
                com.example.engine.OnDeviceLlmAndModelManager.recommendModelForDevice(context)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "SELF-TEST & ACOUSTIC ECHO DIAGNOSTICS",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.speakResponse("OmniVoice audio and speech pipeline are fully operational.") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Test Voice TTS", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val res = com.example.engine.AudioOutputManager.runEchoDiagnosticsTest(context)
                                echoTestReport = res.summary
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("run_echo_test_button"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Run Echo Test", fontSize = 11.sp, color = CyanPrimary)
                        }
                        Button(
                            onClick = { refreshKey++ },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("refresh_diagnostics_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF003548)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh", color = Color(0xFF003548), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (!echoTestReport.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = echoTestReport!!,
                            color = EmeraldTertiary,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    val freeMb = remember { com.example.engine.OnDeviceLlmAndModelManager.getAvailableStorageMb(context) }
                    Text(
                        text = "Recommended On-Device LLM: ${modelRec.displayName} (${modelRec.quantization}, ~${modelRec.sizeMb} MB) | Min RAM: ${modelRec.minRamGb} GB | Free Storage: $freeMb MB",
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                val installTts = Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(installTts)
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Install Offline Urdu (ur-PK) & English TTS Voice Packs", fontSize = 11.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Test items list with "Fix this" action buttons
        items(tests) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item.passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (item.passed) EmeraldTertiary else Color(0xFFEF4444),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.name,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "• ${item.category}",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.description,
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    if (!item.passed && item.fixActionLabel != null && item.onFixAction != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = item.onFixAction,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(item.fixActionLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (item.passed) "PASS" else "WARN",
                            color = if (item.passed) EmeraldTertiary else Color(0xFFFBBF24),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

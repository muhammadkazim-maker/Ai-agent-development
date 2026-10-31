package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.engine.AccentTrainingMode
import com.example.engine.BiometricVaultManager
import com.example.engine.DraggableOrbState
import com.example.engine.GeminiLiveVoice
import com.example.engine.JarvisOrbActionState
import com.example.engine.LiveConnectionState
import com.example.engine.OsKernel
import com.example.engine.SuperAgiBrainEngine
import com.example.engine.TextToSpeechManager
import com.example.service.OmniAccessibilityService
import com.example.service.OmniNotificationListenerService
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletSecondary
import com.example.ui.viewmodel.VoiceAssistantViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class JarvisStage {
    STAGE_A_PERMISSIONS_GATEKEEPER,
    STAGE_B_MINIMAL_START,
    STAGE_C_CORNER_ORB
}

private val StarkDeepBlack = Color(0xFF03050A)
private val StarkObsidianSurface = Color(0xFF070C16)
private val StarkCardBg = Color(0xFF0A1120)
private val StarkCyan = Color(0xFF00E5FF)
private val StarkBlue = Color(0xFF2563EB)
private val StarkGold = Color(0xFFF59E0B)
private val StarkRed = Color(0xFFEF4444)

@Composable
fun JarvisStageFlowScreen(
    viewModel: VoiceAssistantViewModel,
    onEnterPipMode: () -> Boolean = { false },
    onOpenAdvancedSettingsHub: () -> Unit = {}
) {
    val context = LocalContext.current
    val vaultManager = remember { BiometricVaultManager.getInstance(context) }
    val osKernel = remember { OsKernel.getInstance(context) }

    val isPermsCompleted by vaultManager.isPermissionsCompleted.collectAsState()
    val isLiveActive by viewModel.isLiveSessionActive.collectAsState()
    val liveState by viewModel.liveConnectionState.collectAsState()
    val selectedVoice by viewModel.selectedLiveVoice.collectAsState()
    val inputRms by viewModel.liveInputRms.collectAsState()
    val outputRms by viewModel.liveOutputRms.collectAsState()
    val kernelActionState by osKernel.currentActionState.collectAsState()
    val lastActionDetail by osKernel.lastActionDetail.collectAsState()
    val liveUserTranscript by viewModel.liveUserTranscript.collectAsState()
    val liveAiTranscript by viewModel.liveAiTranscript.collectAsState()

    var currentStage by remember(isPermsCompleted) {
        mutableStateOf(
            if (!isPermsCompleted) JarvisStage.STAGE_A_PERMISSIONS_GATEKEEPER
            else JarvisStage.STAGE_B_MINIMAL_START
        )
    }

    var showQuickSettingsDialog by remember { mutableStateOf(false) }
    var showExpandedHudDrawer by remember { mutableStateOf(false) }

    // If live session starts, transition automatically to Stage C Corner Orb
    LaunchedEffect(isLiveActive) {
        if (isLiveActive && currentStage != JarvisStage.STAGE_C_CORNER_ORB) {
            currentStage = JarvisStage.STAGE_C_CORNER_ORB
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        StarkDeepBlack,
                        StarkObsidianSurface,
                        StarkDeepBlack
                    )
                )
            )
            .testTag("jarvis_stage_flow_root")
    ) {
        when (currentStage) {
            JarvisStage.STAGE_A_PERMISSIONS_GATEKEEPER -> {
                JarvisStageAPermissionsGatekeeper(
                    vaultManager = vaultManager,
                    onAllGranted = {
                        vaultManager.markPermissionsCompleted()
                        currentStage = JarvisStage.STAGE_B_MINIMAL_START
                    }
                )
            }
            JarvisStage.STAGE_B_MINIMAL_START -> {
                JarvisStageBMinimalLaunchScreen(
                    selectedVoice = selectedVoice,
                    userTranscript = liveUserTranscript,
                    aiTranscript = liveAiTranscript,
                    onSelectVoice = { voice -> viewModel.omniLiveEngine.selectVoice(voice) },
                    onStartJarvis = {
                        viewModel.startGeminiLiveSession()
                        onEnterPipMode()
                        currentStage = JarvisStage.STAGE_C_CORNER_ORB
                    },
                    onSendPrompt = { queryText ->
                        viewModel.omniLiveEngine.sendTextMessageToLiveSession(queryText)
                    },
                    onOpenSettings = { showQuickSettingsDialog = true },
                    onOpenAdvancedSettingsHub = onOpenAdvancedSettingsHub,
                    onTestAccent = { samplePhrase ->
                        viewModel.ttsManager.speak(samplePhrase)
                    }
                )
            }
            JarvisStage.STAGE_C_CORNER_ORB -> {
                JarvisStageCCornerOrbView(
                    liveState = liveState,
                    inputRms = inputRms,
                    outputRms = outputRms,
                    actionState = kernelActionState,
                    actionDetail = lastActionDetail,
                    userTranscript = liveUserTranscript,
                    aiTranscript = liveAiTranscript,
                    vaultManager = vaultManager,
                    onSendPrompt = { queryText ->
                        viewModel.omniLiveEngine.sendTextMessageToLiveSession(queryText)
                    },
                    onTriggerVoiceListen = {
                        viewModel.omniLiveEngine.startNativeSpeechRecognizerLoop()
                    },
                    onExpandHud = { showExpandedHudDrawer = true },
                    onOpenSettings = { showQuickSettingsDialog = true },
                    onEnterPip = { onEnterPipMode() },
                    onStop = {
                        viewModel.endGeminiLiveSession()
                        currentStage = JarvisStage.STAGE_B_MINIMAL_START
                    }
                )
            }
        }

        // Quick Settings Drawer (Gear Icon)
        if (showQuickSettingsDialog) {
            JarvisSettingsAndVaultModal(
                vaultManager = vaultManager,
                onOpenAdvancedSettingsHub = {
                    showQuickSettingsDialog = false
                    onOpenAdvancedSettingsHub()
                },
                onDismiss = { showQuickSettingsDialog = false }
            )
        }

        // Revamped Neural Status Console (Zero History/Routines/Memory Clutter — Backend Only)
        if (showExpandedHudDrawer) {
            JarvisExpandedHudDrawer(
                viewModel = viewModel,
                onDismiss = { showExpandedHudDrawer = false }
            )
        }
    }
}

// =========================================================================
// STAGE A: SYSTEM PERMISSIONS GATEKEEPER
// =========================================================================
@Composable
private fun JarvisStageAPermissionsGatekeeper(
    vaultManager: BiometricVaultManager,
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    var hasAccessibility by remember { mutableStateOf(OmniAccessibilityService.isConnected()) }
    var hasNotificationListener by remember { mutableStateOf(OmniNotificationListenerService.isServiceRunning()) }
    var hasOverlay by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        )
    }

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasContacts by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasPhone by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        )
    }

    var masterPinInput by remember { mutableStateOf(vaultManager.getMasterPin().ifBlank { "1234" }) }
    var patternInput by remember { mutableStateOf("L-Shape TopToBottom") }
    var voiceUnlockInput by remember { mutableStateOf(vaultManager.getSecretVoiceUnlockWord()) }
    var biometricEnrolled by remember { mutableStateOf(true) }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasMic = results[Manifest.permission.RECORD_AUDIO] == true || hasMic
        hasContacts = results[Manifest.permission.READ_CONTACTS] == true || hasContacts
        hasPhone = results[Manifest.permission.CALL_PHONE] == true || hasPhone
    }

    LaunchedEffect(Unit) {
        hasAccessibility = OmniAccessibilityService.isConnected()
        hasNotificationListener = OmniNotificationListenerService.isServiceRunning()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasOverlay = Settings.canDrawOverlays(context)
        }
    }

    val totalSteps = 5
    val completedSteps = (if (hasAccessibility) 1 else 0) +
        1 + // Shizuku / ADB God-Mode is software enabled
        (if (hasNotificationListener) 1 else 0) +
        (if (hasMic && hasContacts) 1 else 0) +
        (if (masterPinInput.length >= 4) 1 else 0)

    val progress = (completedSteps.toFloat() / totalSteps.toFloat()).coerceIn(0.25f, 1f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("stage_a_permission_gatekeeper"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "JARVIS NEURAL OS • SYSTEM SETUP",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = StarkCyan,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Autonomous OS Permissions Gatekeeper",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary
                )
            }
            Surface(
                color = StarkCyan.copy(alpha = 0.16f),
                shape = CircleShape,
                border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.45f))
            ) {
                Text(
                    text = "$completedSteps / $totalSteps Ready",
                    fontSize = 11.sp,
                    color = StarkCyan,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = StarkCyan,
            trackColor = StarkCardBg
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Accessibility Service
            item {
                GatekeeperPermissionRow(
                    title = "1. Android Accessibility Service",
                    subtitle = "Required for zero-touch auto-click, gesture automation & screen observation",
                    isGranted = hasAccessibility,
                    icon = Icons.Default.SettingsAccessibility,
                    actionLabel = if (hasAccessibility) "Enabled" else "Open Settings",
                    onAction = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
            }

            // 2. Shizuku / Local ADB Shell God-Mode Bridge (http://127.0.0.1:8765)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StarkCardBg),
                    border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = StarkCyan.copy(alpha = 0.15f),
                                shape = CircleShape,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Terminal, contentDescription = null, tint = StarkCyan, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "2. Shizuku / Local ADB Shell Bridge",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Rootless God-Mode on http://127.0.0.1:8765 for raw OS commands (input tap, swipe, keyevent, screencap)",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("JARVIS_BRIDGE", vaultManager.getPythonBridgeOneLiner())
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "Copied 1-Click Shizuku / Termux Bridge Command (Port 8765)!", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, StarkCyan),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StarkCyan)
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy 1-Click Bridge Command (Port 8765)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 3. Notification Listener & Call Screening
            item {
                GatekeeperPermissionRow(
                    title = "3. Notification & Call Screening",
                    subtitle = "Intercepts incoming notifications & powers AI Call Secretary in backend",
                    isGranted = hasNotificationListener,
                    icon = Icons.Default.Notifications,
                    actionLabel = if (hasNotificationListener) "Connected" else "Grant Access",
                    onAction = {
                        try {
                            context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
            }

            // 4. Contacts, Mic, Overlay & Screen Capture
            item {
                GatekeeperPermissionRow(
                    title = "4. Audio, Contacts & System Overlay",
                    subtitle = "Real-time 16kHz microphone stream, full contacts sync & floating orb",
                    isGranted = hasMic && hasContacts && hasOverlay,
                    icon = Icons.Default.Layers,
                    actionLabel = if (hasMic && hasContacts && hasOverlay) "Granted" else "Request All",
                    onAction = {
                        runtimePermLauncher.launch(
                            arrayOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.CALL_PHONE,
                                Manifest.permission.CAMERA
                            )
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                            try {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                                )
                            } catch (_: Exception) {}
                        }
                    }
                )
            }

            // 5. Biometric Fingerprint & Master PIN/Password Vault Enrollment
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StarkCardBg),
                    border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = StarkCyan, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "5. Biometric Vault & Secret Device Unlock",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = "Enables hands-free phone unlock without manual screen touch.",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                        )

                        OutlinedTextField(
                            value = masterPinInput,
                            onValueChange = { masterPinInput = it },
                            label = { Text("Device Master PIN", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StarkCyan,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = voiceUnlockInput,
                            onValueChange = { voiceUnlockInput = it },
                            label = { Text("Secret Voice Unlock Phrase", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = StarkCyan,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Enroll Biometric Fingerprint", fontSize = 12.sp, color = TextPrimary)
                            Switch(
                                checked = biometricEnrolled,
                                onCheckedChange = { biometricEnrolled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = StarkCyan,
                                    checkedTrackColor = StarkCyan.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = {
                vaultManager.enrollVault(
                    masterPin = masterPinInput,
                    lockScreenPattern = patternInput,
                    secretVoiceWord = voiceUnlockInput,
                    biometricEnrolled = biometricEnrolled
                )
                vaultManager.markPermissionsCompleted()
                onAllGranted()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("btn_grant_all_permissions"),
            colors = ButtonDefaults.buttonColors(containerColor = StarkCyan),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF030712))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ACTIVATE JARVIS & LAUNCH NEURAL CORE",
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF030712),
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun GatekeeperPermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StarkCardBg),
        border = BorderStroke(
            1.dp,
            if (isGranted) EmeraldTertiary.copy(alpha = 0.45f) else DarkSurfaceBorder
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = if (isGranted) EmeraldTertiary.copy(alpha = 0.15f) else StarkCyan.copy(alpha = 0.12f),
                shape = CircleShape,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isGranted) EmeraldTertiary else StarkCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (isGranted) EmeraldTertiary else StarkCyan
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isGranted) EmeraldTertiary else StarkCyan
                )
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// =========================================================================
// STAGE B: REVAMPED NEURAL COMMAND CENTER (ZERO UI CLUTTER)
// =========================================================================
@Composable
private fun JarvisStageBMinimalLaunchScreen(
    selectedVoice: GeminiLiveVoice,
    userTranscript: String = "",
    aiTranscript: String = "",
    onSelectVoice: (GeminiLiveVoice) -> Unit,
    onStartJarvis: () -> Unit,
    onSendPrompt: (String) -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenAdvancedSettingsHub: () -> Unit = {},
    onTestAccent: (String) -> Unit = {}
) {
    val activeAccentMode by TextToSpeechManager.activeAccentMode.collectAsState()
    var stageBPromptInput by remember { mutableStateOf("") }

    val infiniteTransition = rememberInfiniteTransition(label = "arc_reactor")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(11000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reactor_spin"
    )

    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.60f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_glow"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("stage_b_minimal_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Sleek Top Telemetry Header Bar with Gemini-4-Argon AGI Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = EmeraldTertiary,
                    modifier = Modifier.size(9.dp)
                ) {}
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "OMNIVOICE • JARVIS AGI",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = StarkCyan,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "Autonomous OS Controller • ${SuperAgiBrainEngine.PREFERRED_GEMINI_MODELS.first().uppercase()}",
                        fontSize = 10.sp,
                        color = EmeraldTertiary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(40.dp)
                    .background(StarkCardBg, CircleShape)
                    .border(1.dp, StarkCyan.copy(alpha = 0.35f), CircleShape)
                    .testTag("btn_quick_settings_gear")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Quick Settings & Vault",
                    tint = StarkCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 2. Centerpiece: Revamped Multi-Layer Neural Arc-Reactor Core
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onStartJarvis() }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(204.dp)
                    .testTag("btn_start_jarvis")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)

                    // Atmospheric Outer Halo
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                StarkCyan.copy(alpha = 0.38f * pulseGlow),
                                VioletSecondary.copy(alpha = 0.14f * pulseGlow),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )

                    // Outer Rotating Segmented Telemetry Ring
                    rotate(rotation, pivot = center) {
                        for (i in 0 until 16) {
                            val angleRad = (i * 22.5f) * (PI / 180f)
                            val startX = center.x + cos(angleRad).toFloat() * (radius * 0.78f)
                            val startY = center.y + sin(angleRad).toFloat() * (radius * 0.78f)
                            val endX = center.x + cos(angleRad).toFloat() * (radius * 0.94f)
                            val endY = center.y + sin(angleRad).toFloat() * (radius * 0.94f)
                            drawLine(
                                color = if (i % 2 == 0) StarkCyan.copy(alpha = 0.85f) else EmeraldTertiary.copy(alpha = 0.65f),
                                start = Offset(startX, startY),
                                end = Offset(endX, endY),
                                strokeWidth = 3.2f
                            )
                        }
                        drawCircle(
                            color = StarkCyan.copy(alpha = 0.55f),
                            radius = radius * 0.73f,
                            center = center,
                            style = Stroke(width = 2f)
                        )
                    }

                    // Counter-Rotating Neural Ring
                    rotate(-rotation * 1.4f, pivot = center) {
                        drawCircle(
                            color = VioletSecondary.copy(alpha = 0.75f),
                            radius = radius * 0.55f,
                            center = center,
                            style = Stroke(width = 2.5f)
                        )
                    }

                    // Inner Glowing Reactor Core
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White,
                                StarkCyan,
                                Color(0xFF0284C7)
                            ),
                            center = center,
                            radius = radius * 0.41f
                        ),
                        radius = radius * 0.41f,
                        center = center
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start Jarvis",
                        tint = Color(0xFF030712),
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = "ACTIVATE",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF030712),
                        letterSpacing = 2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "TAP TO START NEURAL CORE",
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                color = TextPrimary,
                letterSpacing = 1.6.sp
            )

            Text(
                text = "GEMINI-4-ARGON AGI • URDU + ENGLISH + MIXED ACCENT • OS CONTROLLER",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = StarkCyan,
                letterSpacing = 0.8.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        // 3. Bottom Control Deck: Super-AGI Prompt Bar + Trilingual Accent Training Card + Voice Profile + Voice Lock
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Live Super-AGI Response Card (when user asks a question or runs a command from Stage B)
            if (userTranscript.isNotBlank() || aiTranscript.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("stage_b_agi_response_card"),
                    colors = CardDefaults.cardColors(containerColor = StarkCardBg.copy(alpha = 0.95f)),
                    border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (userTranscript.isNotBlank()) {
                            Text(
                                text = "You: $userTranscript",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (aiTranscript.isNotBlank()) {
                            Text(
                                text = "Jarvis AGI: $aiTranscript",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StarkCyan,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
            }

            // Direct Gemini-4-Argon / ChatGPT Super-AGI Input Bar on Stage B
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = stageBPromptInput,
                    onValueChange = { stageBPromptInput = it },
                    placeholder = {
                        Text(
                            text = "Ask Gemini-4-Argon AGI or command phone...",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stage_b_agi_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StarkCyan,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = StarkCardBg,
                        unfocusedContainerColor = StarkCardBg
                    )
                )
                Button(
                    onClick = {
                        val q = stageBPromptInput.trim()
                        if (q.isNotEmpty()) {
                            onSendPrompt(q)
                            stageBPromptInput = ""
                        }
                    },
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("stage_b_agi_send_btn"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StarkCyan)
                ) {
                    Text(
                        text = "ASK",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF030712)
                    )
                }
            }

            // Trilingual Native Accent Training & Silent Backend Intelligence Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("silent_backend_intelligence_card"),
                colors = CardDefaults.cardColors(containerColor = StarkCardBg.copy(alpha = 0.94f)),
                border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = EmeraldTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Accent Engine: ${activeAccentMode.title}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            color = StarkCyan.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .clickable {
                                    val testPhrase = when (activeAccentMode) {
                                        AccentTrainingMode.URDU_COMPLETE_ACCENT ->
                                            "السلام علیکم باس، میں جاروس ہوں اور آپ کی اردو آواز بالکل خالص لہجے میں تیار ہے۔"
                                        AccentTrainingMode.ENGLISH_COMPLETE_ACCENT ->
                                            "Hello Boss, Jarvis Gemini Four Argon neural engine is online with complete English accent."
                                        AccentTrainingMode.MIXED_URDU_COMPLETE_ACCENT,
                                        AccentTrainingMode.AUTO_TRILINGUAL ->
                                            "Assalam o Alaikum Boss, main Jarvis hoon aur aap ka har command super speed ke sath execute karne ke liye tayyar hoon."
                                    }
                                    onTestAccent(testPhrase)
                                }
                                .testTag("btn_test_accent")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Test Accent",
                                    tint = StarkCyan,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Test Accent",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StarkCyan
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("accent_mode_selector_row")
                    ) {
                        items(AccentTrainingMode.entries) { mode ->
                            val isSelected = mode == activeAccentMode
                            Surface(
                                color = if (isSelected) EmeraldTertiary else Color(0xFF060B14),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) EmeraldTertiary else DarkSurfaceBorder
                                ),
                                modifier = Modifier.clickable {
                                    TextToSpeechManager.setAccentTrainingMode(mode)
                                }
                            ) {
                                Text(
                                    text = mode.title,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = if (isSelected) Color(0xFF030712) else TextPrimary,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Standing Rules, Routines & Long-Term Memory running silently in backend",
                        fontSize = 9.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("voice_selector_chips")
            ) {
                items(GeminiLiveVoice.entries) { voice ->
                    val isSelected = voice == selectedVoice
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectVoice(voice) },
                        label = {
                            Text(
                                text = voice.voiceName,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = StarkCyan,
                            selectedLabelColor = Color(0xFF040A18),
                            containerColor = StarkCardBg,
                            labelColor = TextPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) StarkCyan else DarkSurfaceBorder
                        )
                    )
                }
            }
        }
    }
}

// =========================================================================
// STAGE C: REVAMPED LIVE FLOATING ORB HUD (CLEAN WORKSPACE)
// =========================================================================
@Composable
private fun JarvisStageCCornerOrbView(
    liveState: LiveConnectionState,
    inputRms: Float,
    outputRms: Float,
    actionState: JarvisOrbActionState,
    actionDetail: String,
    userTranscript: String,
    aiTranscript: String,
    vaultManager: BiometricVaultManager,
    sensitivity: Float = vaultManager.getOrbDragSensitivity(),
    onSendPrompt: (String) -> Unit = {},
    onTriggerVoiceListen: () -> Unit = {},
    onExpandHud: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnterPip: () -> Boolean,
    onStop: () -> Unit
) {
    val vault by vaultManager.vaultState.collectAsState()
    val dragSensitivity = vault.orbDragSensitivity.coerceIn(0.1f, 1.0f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    var livePromptInput by remember { mutableStateOf("") }

    val orbSizeDp = 92.dp
    val orbSizePx = with(density) { orbSizeDp.toPx() }
    val edgeMarginPx = with(density) { 16.dp.toPx() }
    val snapThresholdPx = with(density) { 88.dp.toPx() }

    val draggableOrbState = remember {
        DraggableOrbState(
            initialX = 40f,
            initialY = 80f,
            sensitivity = sensitivity,
            edgeMarginPx = edgeMarginPx,
            snapThresholdPx = snapThresholdPx
        )
    }
    draggableOrbState.sensitivity = dragSensitivity

    val slopThresholdDp = draggableOrbState.slopThresholdDp
    val slopThresholdPx = with(density) { slopThresholdDp.dp.toPx() }

    var offsetX by remember { mutableFloatStateOf(draggableOrbState.offsetX) }
    var offsetY by remember { mutableFloatStateOf(draggableOrbState.offsetY) }
    var isDraggingState by remember { mutableStateOf(false) }
    var snappedEdgeLabel by remember { mutableStateOf("RIGHT") }
    var isDockInitialized by remember { mutableStateOf(false) }

    val animatedOffsetX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = if (isDraggingState) {
            spring(stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        },
        label = "orb_snap_x"
    )
    val animatedOffsetY by animateFloatAsState(
        targetValue = offsetY,
        animationSpec = if (isDraggingState) {
            spring(stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
        },
        label = "orb_snap_y"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "corner_orb")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_spin"
    )

    val activeRms = if (liveState == LiveConnectionState.LIVE_AI_SPEAKING) outputRms else inputRms
    val audioGlowFactor = (activeRms / 60f).coerceIn(0.1f, 1.4f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("stage_c_corner_orb_container")
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()
        val maxOffsetX = (screenWidthPx - orbSizePx).coerceAtLeast(0f)
        val maxOffsetY = (screenHeightPx - orbSizePx).coerceAtLeast(0f)

        LaunchedEffect(screenWidthPx, screenHeightPx) {
            if (!isDockInitialized && screenWidthPx > 0) {
                val marginY = with(density) { 140.dp.toPx() }
                offsetX = (maxOffsetX - edgeMarginPx).coerceAtLeast(10f)
                offsetY = (screenHeightPx - orbSizePx - marginY).coerceAtLeast(40f)
                draggableOrbState.offsetX = offsetX
                draggableOrbState.offsetY = offsetY
                snappedEdgeLabel = "RIGHT"
                isDockInitialized = true
            }
        }

        var hudTouchX by remember { mutableFloatStateOf(540f) }
        var hudTouchY by remember { mutableFloatStateOf(1200f) }

        // 1. Live Neural Viewport (Clean, Clutter-Free Observation Canvas)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 124.dp)
        ) {
            Surface(
                color = StarkCardBg,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = EmeraldTertiary, modifier = Modifier.size(8.dp)) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "NEURAL STREAM: $actionDetail",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "LIVE",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = EmeraldTertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = Color(0xFF040812),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, DarkSurfaceBorder),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            hudTouchX = change.position.x
                            hudTouchY = change.position.y
                        }
                    }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val grid = 44.dp.toPx()
                        var cx = 0f
                        while (cx < w) {
                            drawLine(color = Color(0xFF091526), start = Offset(cx, 0f), end = Offset(cx, h), strokeWidth = 1f)
                            cx += grid
                        }
                        var cy = 0f
                        while (cy < h) {
                            drawLine(color = Color(0xFF091526), start = Offset(0f, cy), end = Offset(w, cy), strokeWidth = 1f)
                            cy += grid
                        }
                        drawCircle(
                            color = StarkCyan.copy(alpha = 0.7f),
                            radius = 16f,
                            center = Offset(hudTouchX.coerceIn(0f, w), hudTouchY.coerceIn(0f, h)),
                            style = Stroke(width = 2f)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = StarkCyan.copy(alpha = 0.55f),
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "JARVIS AUTONOMOUS OS CONTROLLER ACTIVE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = StarkCyan,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Standing Rules, Routines & Memory running silently in backend",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // 2. Bottom Live Subtitle & Quick Control Bar
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            if (userTranscript.isNotBlank() || aiTranscript.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = StarkCardBg.copy(alpha = 0.94f)),
                    border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (userTranscript.isNotBlank()) {
                            Text(
                                text = "You: $userTranscript",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (aiTranscript.isNotBlank()) {
                            Text(
                                text = "Jarvis AGI: $aiTranscript",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StarkCyan,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Quick Super-AGI Action & Question Chips
            val quickPrompts = remember {
                listOf(
                    "Flashlight on karo",
                    "Open WhatsApp",
                    "Write Python Fibonacci code",
                    "25 * 48 + 120 kitna hai?",
                    "Biryani recipe batao",
                    "Battery status batao"
                )
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                items(quickPrompts) { chipText ->
                    Surface(
                        color = StarkCardBg.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.35f)),
                        modifier = Modifier.clickable { onSendPrompt(chipText) }
                    ) {
                        Text(
                            text = chipText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StarkCyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Direct Gemini / ChatGPT Prompt Bar in Stage C Live View
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = livePromptInput,
                    onValueChange = { livePromptInput = it },
                    placeholder = {
                        Text(
                            text = "Speak or type any command / question...",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stage_c_agi_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StarkCyan,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = StarkCardBg,
                        unfocusedContainerColor = StarkCardBg
                    )
                )
                IconButton(
                    onClick = onTriggerVoiceListen,
                    modifier = Modifier
                        .size(48.dp)
                        .background(EmeraldTertiary.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                        .border(1.dp, EmeraldTertiary, RoundedCornerShape(12.dp))
                        .testTag("stage_c_mic_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Voice Input",
                        tint = EmeraldTertiary
                    )
                }
                Button(
                    onClick = {
                        val q = livePromptInput.trim()
                        if (q.isNotEmpty()) {
                            onSendPrompt(q)
                            livePromptInput = ""
                        }
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("stage_c_agi_send_btn"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StarkCyan)
                ) {
                    Text(
                        text = "SEND",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF030712)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = StarkCardBg.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, DarkSurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = StarkCyan,
                            shape = CircleShape,
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = actionState.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StarkCyan
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // PiP Button
                    IconButton(
                        onClick = { onEnterPip() },
                        modifier = Modifier
                            .size(38.dp)
                            .background(StarkCardBg, CircleShape)
                            .border(1.dp, DarkSurfaceBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPicture,
                            contentDescription = "Picture in Picture",
                            tint = StarkCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Settings Button
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(38.dp)
                            .background(StarkCardBg, CircleShape)
                            .border(1.dp, StarkCyan.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Orb Sensitivity & Vault Settings",
                            tint = StarkCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Expand Neural Status Console
                    IconButton(
                        onClick = onExpandHud,
                        modifier = Modifier
                            .size(38.dp)
                            .background(StarkCardBg, CircleShape)
                            .border(1.dp, DarkSurfaceBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Expand Neural Console",
                            tint = StarkCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Stop
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(38.dp)
                            .background(StarkCardBg, CircleShape)
                            .border(1.dp, StarkRed.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop Jarvis",
                            tint = StarkRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // 3. Draggable 92.dp Glowing Arc-Reactor Orb with Magnetic Edge Snap
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffsetX.roundToInt(), animatedOffsetY.roundToInt()) }
                .size(orbSizeDp)
                .pointerInput(dragSensitivity, maxOffsetX, maxOffsetY) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalDistance = 0f
                        var isActivelyDragging = false

                        do {
                            val event = awaitPointerEvent()
                            val currentChange = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!currentChange.pressed) break

                            val dragDelta = currentChange.position - currentChange.previousPosition
                            val distance = dragDelta.getDistance()

                            if (!isActivelyDragging) {
                                totalDistance += distance
                                if (totalDistance >= slopThresholdPx) {
                                    isActivelyDragging = true
                                    isDraggingState = true
                                    currentChange.consume()
                                    val snapRes = draggableOrbState.applyDragDelta(
                                        deltaX = dragDelta.x,
                                        deltaY = dragDelta.y,
                                        maxOffsetX = maxOffsetX,
                                        maxOffsetY = maxOffsetY,
                                        applyLiveMagneticPull = true
                                    )
                                    offsetX = snapRes.targetX
                                    offsetY = snapRes.targetY
                                    snappedEdgeLabel = snapRes.snappedEdge
                                }
                            } else {
                                currentChange.consume()
                                val snapRes = draggableOrbState.applyDragDelta(
                                    deltaX = dragDelta.x,
                                    deltaY = dragDelta.y,
                                    maxOffsetX = maxOffsetX,
                                    maxOffsetY = maxOffsetY,
                                    applyLiveMagneticPull = true
                                )
                                offsetX = snapRes.targetX
                                offsetY = snapRes.targetY
                                snappedEdgeLabel = snapRes.snappedEdge
                            }
                        } while (true)

                        val wasDragging = isActivelyDragging
                        isDraggingState = false

                        if (wasDragging) {
                            val finalSnap = draggableOrbState.snapToEdgeIfNearBoundary(
                                maxOffsetX = maxOffsetX,
                                maxOffsetY = maxOffsetY,
                                forceHorizontalDock = true
                            )
                            offsetX = finalSnap.targetX
                            offsetY = finalSnap.targetY
                            snappedEdgeLabel = finalSnap.snappedEdge
                        } else if (totalDistance < slopThresholdPx) {
                            onExpandHud()
                        }
                    }
                }
                .testTag("jarvis_corner_floating_orb"),
            contentAlignment = Alignment.Center
        ) {
            if (isDraggingState) {
                Surface(
                    color = StarkCardBg.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (snappedEdgeLabel != "NONE") EmeraldTertiary else StarkCyan),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-28).dp)
                ) {
                    val statusTag = if (snappedEdgeLabel != "NONE") {
                        "MAGNETIC SNAP [$snappedEdgeLabel]"
                    } else {
                        "MOVING (${offsetX.roundToInt()}, ${offsetY.roundToInt()})"
                    }
                    Text(
                        text = statusTag,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (snappedEdgeLabel != "NONE") EmeraldTertiary else StarkCyan,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            StarkCyan.copy(alpha = (0.45f * audioGlowFactor).coerceIn(0.15f, 0.9f)),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius * (1.1f + audioGlowFactor * 0.25f)
                    ),
                    radius = radius * (1.1f + audioGlowFactor * 0.25f),
                    center = center
                )

                rotate(spinAngle, pivot = center) {
                    for (i in 0 until 8) {
                        val angle = (i * 45f) * (PI / 180f)
                        val startX = center.x + cos(angle).toFloat() * (radius * 0.72f)
                        val startY = center.y + sin(angle).toFloat() * (radius * 0.72f)
                        val endX = center.x + cos(angle).toFloat() * (radius * 0.95f)
                        val endY = center.y + sin(angle).toFloat() * (radius * 0.95f)
                        drawLine(
                            color = StarkCyan,
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = 2.5f
                        )
                    }
                    drawCircle(
                        color = StarkCyan.copy(alpha = 0.75f),
                        radius = radius * 0.70f,
                        center = center,
                        style = Stroke(width = 2f)
                    )
                }

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            StarkCyan,
                            Color(0xFF0C4A6E)
                        ),
                        center = center,
                        radius = radius * 0.44f
                    ),
                    radius = radius * 0.44f,
                    center = center
                )
            }

            Icon(
                imageVector = when (actionState) {
                    JarvisOrbActionState.IN_CALL -> Icons.Default.Call
                    JarvisOrbActionState.SPEAKING -> Icons.AutoMirrored.Filled.VolumeUp
                    JarvisOrbActionState.RUNNING_SHELL -> Icons.Default.Terminal
                    JarvisOrbActionState.UNLOCKING -> Icons.Default.Key
                    else -> Icons.Default.GraphicEq
                },
                contentDescription = actionState.label,
                tint = Color(0xFF030712),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// =========================================================================
// QUICK SETTINGS & VAULT MODAL (TINY GEAR ICON)
// =========================================================================
@Composable
private fun JarvisSettingsAndVaultModal(
    vaultManager: BiometricVaultManager,
    onOpenAdvancedSettingsHub: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val vault by vaultManager.vaultState.collectAsState()
    var pinEdit by remember { mutableStateOf(vault.masterPin) }
    var voiceEdit by remember { mutableStateOf(vault.secretVoiceUnlockWord) }
    var orbSensitivityEdit by remember(vault.orbDragSensitivity) { mutableFloatStateOf(vault.orbDragSensitivity) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = StarkCardBg),
            border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "JARVIS VAULT & ORB CONTROLS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = StarkCyan,
                        letterSpacing = 1.sp
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = pinEdit,
                    onValueChange = { pinEdit = it },
                    label = { Text("Device Master PIN") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StarkCyan,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = voiceEdit,
                    onValueChange = { voiceEdit = it },
                    label = { Text("Voice Unlock Phrase") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StarkCyan,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = DarkSurfaceBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = StarkCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ORB DRAG SENSITIVITY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StarkCyan,
                            letterSpacing = 1.sp
                        )
                    }
                    Text(
                        text = "${(orbSensitivityEdit * 100).roundToInt()}% (${vaultManager.calculateSlopThresholdDp(orbSensitivityEdit).roundToInt()}dp slop)",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = listOf(
                        "Anti-Accidental" to 0.20f,
                        "Balanced" to 0.50f,
                        "Ultra-Responsive" to 0.85f
                    )
                    presets.forEach { (label, value) ->
                        val isSelected = kotlin.math.abs(orbSensitivityEdit - value) < 0.15f
                        FilterChip(
                            selected = isSelected,
                            onClick = { orbSensitivityEdit = value },
                            label = { Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
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
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Slider(
                    value = orbSensitivityEdit,
                    onValueChange = { orbSensitivityEdit = it },
                    valueRange = 0.10f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = StarkCyan,
                        activeTrackColor = StarkCyan,
                        inactiveTrackColor = DarkSurfaceBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_orb_drag_sensitivity")
                )

                Text(
                    text = when {
                        orbSensitivityEdit < 0.35f -> "Low sensitivity: Requires a firm, intentional drag before moving. Prevents accidental displacement during screen taps."
                        orbSensitivityEdit <= 0.70f -> "Balanced: Optimal balance between tap responsiveness and smooth gliding movement."
                        else -> "High sensitivity: Immediate drag detection. Moves with the slightest finger displacement."
                    },
                    fontSize = 10.sp,
                    color = TextSecondary,
                    lineHeight = 14.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenAdvancedSettingsHub,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StarkCyan)
                    ) {
                        Text("SYSTEM HUB", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StarkCyan)
                    }

                    Button(
                        onClick = {
                            vaultManager.enrollVault(
                                masterPin = pinEdit,
                                lockScreenPattern = vault.lockScreenPattern,
                                secretVoiceWord = voiceEdit,
                                biometricEnrolled = true
                            )
                            vaultManager.setOrbDragSensitivity(orbSensitivityEdit)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = StarkCyan),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("SAVE VAULT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF030712))
                    }
                }
            }
        }
    }
}

// =========================================================================
// REVAMPED NEURAL STATUS CONSOLE (ZERO HISTORY / ROUTINES / MEMORY UI LISTS)
// =========================================================================
@Composable
private fun JarvisExpandedHudDrawer(
    viewModel: VoiceAssistantViewModel,
    onDismiss: () -> Unit
) {
    val userTranscript by viewModel.liveUserTranscript.collectAsState()
    val aiTranscript by viewModel.liveAiTranscript.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(14.dp),
            colors = CardDefaults.cardColors(containerColor = StarkDeepBlack),
            border = BorderStroke(1.5.dp, StarkCyan.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = StarkCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "JARVIS NEURAL CORE & BACKEND ENGINE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = StarkCyan,
                            letterSpacing = 1.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Live Dialogue Stream Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StarkCardBg),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, DarkSurfaceBorder)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "You: ${userTranscript.ifBlank { "Listening for your voice..." }}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = "Jarvis: ${aiTranscript.ifBlank { "Ready for autonomous execution." }}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = StarkCyan,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Silent Backend Systems Summary (No visible History, Routines, or Memory lists)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StarkCardBg),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, EmeraldTertiary.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "SILENT BACKEND INTELLIGENCE (AUTO-MANAGED)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = EmeraldTertiary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "• Super-AGI Model: ${SuperAgiBrainEngine.PREFERRED_GEMINI_MODELS.first().uppercase()}",
                            fontSize = 11.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "• Standing Rules, Routines, Memory & Command Processing: Running 100% silently in backend",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

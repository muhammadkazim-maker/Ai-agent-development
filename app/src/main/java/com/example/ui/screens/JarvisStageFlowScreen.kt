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
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import com.example.engine.DraggableOrbState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
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
import com.example.engine.BiometricVaultManager
import com.example.engine.GeminiLiveVoice
import com.example.engine.JarvisOrbActionState
import com.example.engine.LiveConnectionState
import com.example.engine.OsKernel
import com.example.service.OmniAccessibilityService
import com.example.service.OmniNotificationListenerService
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
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

private val StarkDeepBlack = Color(0xFF04060C)
private val StarkCardBg = Color(0xFF090D18)
private val StarkCyan = Color(0xFF00E5FF)
private val StarkBlue = Color(0xFF2563EB)
private val StarkGold = Color(0xFFF59E0B)
private val StarkRed = Color(0xFFEF4444)

@Composable
fun JarvisStageFlowScreen(
    viewModel: VoiceAssistantViewModel,
    onEnterPipMode: () -> Boolean = { false }
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
            .background(StarkDeepBlack)
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
                    onSelectVoice = { voice -> viewModel.omniLiveEngine.selectVoice(voice) },
                    onStartJarvis = {
                        viewModel.startGeminiLiveSession()
                        onEnterPipMode()
                        currentStage = JarvisStage.STAGE_C_CORNER_ORB
                    },
                    onOpenSettings = { showQuickSettingsDialog = true }
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
                onDismiss = { showQuickSettingsDialog = false }
            )
        }

        // Expanded Floating HUD / Terminal Drawer
        if (showExpandedHudDrawer) {
            JarvisExpandedHudDrawer(
                viewModel = viewModel,
                osKernel = osKernel,
                onDismiss = { showExpandedHudDrawer = false }
            )
        }
    }
}

// =========================================================================
// STAGE A: MANDATORY STARTUP PERMISSION GATEKEEPER
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

    // Refresh system service connections on resumed composition
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

    val progress = (completedSteps.toFloat() / totalSteps.toFloat()).coerceIn(0.2f, 1f)

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
        // Futuristic Header
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "JARVIS SYSTEM INITIALIZATION",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = StarkCyan,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Zero-Trust Permission Gatekeeper",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
            }
            Surface(
                color = StarkCyan.copy(alpha = 0.15f),
                shape = CircleShape,
                border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.4f))
            ) {
                Text(
                    text = "$completedSteps / $totalSteps Ready",
                    fontSize = 11.sp,
                    color = StarkCyan,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
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
                    subtitle = "Required for screen vision, zero-touch auto-click & gesture injection",
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
                    shape = RoundedCornerShape(12.dp)
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
                                    text = "Rootless God-Mode on http://127.0.0.1:8765 for raw OS commands (input tap, swipe, keyevent, uiautomator dump, screencap)",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("JARVIS_BRIDGE", vaultManager.getPythonBridgeOneLiner())
                                    clipboard.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(context, "Copied 1-Click Shizuku / Termux Bridge Command (Port 8765)!", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
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
            }

            // 3. Notification Listener & Call Screening
            item {
                GatekeeperPermissionRow(
                    title = "3. Notification & Call Screening",
                    subtitle = "Intercepts incoming notifications & powers AI Call Secretary",
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
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = StarkCyan, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "5. Biometric Vault & Secret Unlock",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = "Enables hands-free phone unlock and app lock bypass without touching screen.",
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

        // Grant All & Initialize Button
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
                .height(52.dp)
                .testTag("btn_grant_all_permissions"),
            colors = ButtonDefaults.buttonColors(containerColor = StarkCyan),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF030712))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "GRANT ALL & INITIALIZE SYSTEM",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
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
            if (isGranted) EmeraldTertiary.copy(alpha = 0.4f) else DarkSurfaceBorder
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = if (isGranted) EmeraldTertiary.copy(alpha = 0.15f) else StarkCyan.copy(alpha = 0.1f),
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
                shape = RoundedCornerShape(8.dp),
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
// STAGE B: ULTRA-MINIMALIST MAIN LAUNCH SCREEN
// =========================================================================
@Composable
private fun JarvisStageBMinimalLaunchScreen(
    selectedVoice: GeminiLiveVoice,
    onSelectVoice: (GeminiLiveVoice) -> Unit,
    onStartJarvis: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "arc_reactor")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reactor_spin"
    )

    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_glow"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag("stage_b_minimal_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Minimal Top Bar (Gear Icon in Top-Right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = StarkCyan,
                    modifier = Modifier.size(8.dp)
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "JARVIS OS",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = StarkCyan,
                    letterSpacing = 2.sp
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("btn_quick_settings_gear")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Quick Settings & Vault",
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Centerpiece: Giant Glowing Arc-Reactor "START JARVIS" Button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onStartJarvis() }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(220.dp)
                    .testTag("btn_start_jarvis")
            ) {
                // Outer Arc-Reactor Rings Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)

                    // Outer glowing energy ring
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                StarkCyan.copy(alpha = 0.35f * pulseGlow),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )

                    // Rotating segmented reactor ring
                    rotate(rotation, pivot = center) {
                        for (i in 0 until 12) {
                            val angleRad = (i * 30f) * (PI / 180f)
                            val startX = center.x + cos(angleRad).toFloat() * (radius * 0.76f)
                            val startY = center.y + sin(angleRad).toFloat() * (radius * 0.76f)
                            val endX = center.x + cos(angleRad).toFloat() * (radius * 0.94f)
                            val endY = center.y + sin(angleRad).toFloat() * (radius * 0.94f)
                            drawLine(
                                color = StarkCyan.copy(alpha = 0.8f),
                                start = Offset(startX, startY),
                                end = Offset(endX, endY),
                                strokeWidth = 3f
                            )
                        }
                        drawCircle(
                            color = StarkCyan.copy(alpha = 0.6f),
                            radius = radius * 0.72f,
                            center = center,
                            style = Stroke(width = 2f)
                        )
                    }

                    // Counter-rotating inner ring
                    rotate(-rotation * 1.5f, pivot = center) {
                        drawCircle(
                            color = VioletSecondary.copy(alpha = 0.7f),
                            radius = radius * 0.52f,
                            center = center,
                            style = Stroke(width = 2.5f)
                        )
                    }

                    // Inner reactor core
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White,
                                StarkCyan,
                                Color(0xFF0369A1)
                            ),
                            center = center,
                            radius = radius * 0.40f
                        ),
                        radius = radius * 0.40f,
                        center = center
                    )
                }

                // Center Text Inside Core
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start",
                        tint = Color(0xFF040A18),
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "START",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF040A18),
                        letterSpacing = 2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "START JARVIS",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary,
                letterSpacing = 2.sp
            )

            Text(
                text = "ALWAYS-ONLINE CONTINUOUS LIVE ENGINE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = StarkCyan,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Voice Selector Chips Control
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "VOICE PROFILE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

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
                                fontSize = 12.sp,
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
// STAGE C: CORNER MEDIUM FLOATING CIRCLE (88px x 88px / 88.dp ARC-REACTOR ORB)
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
    onExpandHud: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnterPip: () -> Boolean,
    onStop: () -> Unit
) {
    val vault by vaultManager.vaultState.collectAsState()
    val dragSensitivity = vault.orbDragSensitivity.coerceIn(0.1f, 1.0f)
    val density = androidx.compose.ui.platform.LocalDensity.current

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

    // Dynamic Reactive Audio Amplitude
    val activeRms = if (liveState == LiveConnectionState.LIVE_AI_SPEAKING) outputRms else inputRms
    val audioGlowFactor = (activeRms / 60f).coerceIn(0.1f, 1.4f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("stage_c_corner_orb_container")
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()
        val maxOffsetX = (screenWidthPx - orbSizePx).coerceAtLeast(0f)
        val maxOffsetY = (screenHeightPx - orbSizePx).coerceAtLeast(0f)

        // Dock initially in bottom-right corner with safe margins
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

        // 1. Live Interactive Main Window Viewer (Full screen visibility behind corner orb)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 120.dp)
        ) {
            Surface(
                color = StarkCardBg,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = EmeraldTertiary, modifier = Modifier.size(8.dp)) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIVE WINDOW: $actionDetail",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "HUD: (${hudTouchX.roundToInt()}, ${hudTouchY.roundToInt()})",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = StarkCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = Color(0xFF030712),
                shape = RoundedCornerShape(16.dp),
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
                            drawLine(color = Color(0xFF0A1828), start = Offset(cx, 0f), end = Offset(cx, h), strokeWidth = 1f)
                            cx += grid
                        }
                        var cy = 0f
                        while (cy < h) {
                            drawLine(color = Color(0xFF0A1828), start = Offset(0f, cy), end = Offset(w, cy), strokeWidth = 1f)
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
                        modifier = Modifier.align(Alignment.Center).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = null, tint = StarkCyan.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "LIVE MAIN WINDOW OBSERVATION STREAM ACTIVE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = StarkCyan,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Background apps fully visible • Continuous Live Screen Vision",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // Unobstructed / Transparent Live Interactive Workspace View
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Live Subtitle Transcript Float Card
            if (userTranscript.isNotBlank() || aiTranscript.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = StarkCardBg.copy(alpha = 0.88f)),
                    border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.35f)),
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
                                text = "Jarvis: $aiTranscript",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StarkCyan,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Quick Control Bottom Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = StarkCardBg.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, DarkSurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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

                    // Expand HUD
                    IconButton(
                        onClick = onExpandHud,
                        modifier = Modifier
                            .size(38.dp)
                            .background(StarkCardBg, CircleShape)
                            .border(1.dp, DarkSurfaceBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Expand HUD",
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

        // Draggable 92px x 92px (92.dp) Glowing Arc-Reactor Orb Docked in Corner
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
                            // If user performed a clean tap without exceeding the anti-accidental slop threshold
                            onExpandHud()
                        }
                    }
                }
                .testTag("jarvis_corner_floating_orb"),
            contentAlignment = Alignment.Center
        ) {
            // Dragging / Magnetic Edge Snap feedback badge directly above orb
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
                        "MAGNETIC PULL [$snappedEdgeLabel] (${offsetX.roundToInt()}, ${offsetY.roundToInt()})"
                    } else {
                        "DRAGGING (${offsetX.roundToInt()}, ${offsetY.roundToInt()})"
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

            // Arc-Reactor Canvas with Reactive Audio Pulse
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                // Outer reactive glow
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

                // Rotating Segmented Reactor Ring
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

                // Inner core
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

            // Central Icon based on State
            Icon(
                imageVector = when (actionState) {
                    JarvisOrbActionState.IN_CALL -> Icons.Default.Call
                    JarvisOrbActionState.SPEAKING -> Icons.Default.VolumeUp
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
            border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "JARVIS VAULT & CONTROLS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
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

                // Floating Orb Drag Sensitivity Section
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

                // Sensitivity Preset Chips
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
                    modifier = Modifier.fillMaxWidth().testTag("slider_orb_drag_sensitivity")
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
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StarkCyan),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("SAVE SETTINGS & VAULT", fontWeight = FontWeight.Bold, color = Color(0xFF030712))
                }
            }
        }
    }
}

// =========================================================================
// EXPANDED HUD / TERMINAL DRAWER
// =========================================================================
@Composable
private fun JarvisExpandedHudDrawer(
    viewModel: VoiceAssistantViewModel,
    osKernel: OsKernel,
    onDismiss: () -> Unit
) {
    val logs by viewModel.jarvisTerminalLogs.collectAsState()
    val userTranscript by viewModel.liveUserTranscript.collectAsState()
    val aiTranscript by viewModel.liveAiTranscript.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 580.dp)
                .padding(14.dp),
            colors = CardDefaults.cardColors(containerColor = StarkDeepBlack),
            border = BorderStroke(1.dp, StarkCyan.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = StarkCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "JARVIS HUD & OS KERNEL LOGS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = StarkCyan,
                            letterSpacing = 1.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Transcripts Box
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = StarkCardBg),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "User: ${userTranscript.ifBlank { "Listening..." }}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = "Jarvis: ${aiTranscript.ifBlank { "Standby" }}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = StarkCyan,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Terminal Logs
                Text(
                    text = "LIVE EXECUTION LOGS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(Color(0xFF020408), RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs.takeLast(25)) { log ->
                        Text(
                            text = "[${log.layerTag}] ${log.command} → ${log.status} (${log.detail})",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (log.status == "SAVED" || log.status == "EXECUTED") EmeraldTertiary else StarkCyan
                        )
                    }
                }
            }
        }
    }
}

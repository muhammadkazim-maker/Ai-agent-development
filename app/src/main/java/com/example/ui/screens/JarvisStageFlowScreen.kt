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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.engine.BiometricVaultManager
import com.example.engine.DraggableOrbState
import com.example.engine.GeminiLiveVoice
import com.example.engine.JarvisHandsFreeEngine
import com.example.engine.JarvisOrbActionState
import com.example.engine.LiveConnectionState
import com.example.engine.OsKernel
import com.example.engine.SuperAgiBrainEngine
import com.example.engine.TextToSpeechManager
import com.example.service.OmniAccessibilityService
import com.example.service.OmniNotificationListenerService
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.PlusJakartaSansFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletSecondary
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.fontFamilyForBilingualText
import com.example.ui.theme.textDirectionForBilingualText
import com.example.ui.viewmodel.VoiceAssistantViewModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class JarvisStage {
    STAGE_A_PERMISSIONS_GATEKEEPER,
    STAGE_B_MINIMAL_START,
    STAGE_C_CORNER_ORB
}

// Bespoke Aether-X Quantum Neural OS Palette Tokens
private val QuantumVoid = Color(0xFF04060C)
private val QuantumObsidian = Color(0xFF090D18)
private val QuantumGlassCard = Color(0xFF101729)
private val QuantumElevatedCard = Color(0xFF162038)
private val QuantumBorder = Color(0xFF243252)
private val QuantumCyan = CyanPrimary
private val QuantumMint = EmeraldTertiary
private val QuantumViolet = VioletSecondary
private val QuantumAmber = WarningAmber
private val QuantumCrimson = DangerRed

@Composable
fun JarvisStageFlowScreen(
    viewModel: VoiceAssistantViewModel,
    onEnterPipMode: () -> Boolean = { false },
    onOpenAdvancedSettingsHub: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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

    var hasMicPermissionActual by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val shouldOpenHomeStage = isPermsCompleted || hasMicPermissionActual

    var currentStage by remember(shouldOpenHomeStage) {
        mutableStateOf(
            if (!shouldOpenHomeStage) JarvisStage.STAGE_A_PERMISSIONS_GATEKEEPER
            else JarvisStage.STAGE_B_MINIMAL_START
        )
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val micGrantedNow = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                hasMicPermissionActual = micGrantedNow
                val contactsGrantedNow = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
                if (contactsGrantedNow) {
                    JarvisHandsFreeEngine.autoInjectBridgeContactsIfPermitted(context, force = false)
                }
                if (micGrantedNow) {
                    vaultManager.markPermissionsCompleted()
                    if (currentStage == JarvisStage.STAGE_A_PERMISSIONS_GATEKEEPER) {
                        currentStage = JarvisStage.STAGE_B_MINIMAL_START
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showQuickSettingsDialog by remember { mutableStateOf(false) }
    var showExpandedHudDrawer by remember { mutableStateOf(false) }
    var pendingContextualMicAction by remember { mutableStateOf<String?>(null) }

    val wakeLifecycleState by viewModel.wakeSpeechLifecycleState.collectAsState()
    val activeMicOwner by viewModel.activeMicOwner.collectAsState()
    val activeInputRoute by viewModel.activeAudioInputRoute.collectAsState()
    val isUserArmed by viewModel.isUserArmed.collectAsState()

    val contextualMicPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermissionActual = granted
        viewModel.refreshWakeAndSpeechState()
        val action = pendingContextualMicAction
        pendingContextualMicAction = null
        if (granted) {
            vaultManager.markPermissionsCompleted()
            when (action) {
                "START_JARVIS_LIVE" -> {
                    viewModel.armJarvisWakeAndSpeechSession()
                    viewModel.startGeminiLiveSession()
                    currentStage = JarvisStage.STAGE_C_CORNER_ORB
                }
                "ARM_WAKE_ONLY" -> {
                    viewModel.armJarvisWakeAndSpeechSession()
                }
                "VOICE_LISTEN_WINDOW" -> {
                    viewModel.armJarvisWakeAndSpeechSession()
                    viewModel.omniLiveEngine.startNativeSpeechRecognizerLoop()
                }
            }
        }
    }

    val requestMicInContextOrRun: (String, () -> Unit) -> Unit = { actionKey, onPermitted ->
        val grantedNow = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        hasMicPermissionActual = grantedNow
        if (grantedNow) {
            onPermitted()
        } else {
            pendingContextualMicAction = actionKey
            contextualMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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
                        QuantumVoid,
                        QuantumObsidian,
                        QuantumVoid
                    )
                )
            )
            .testTag("jarvis_stage_flow_root")
    ) {
        // Ambient Bioluminescent Spatial Backdrop Canvas
        QuantumSpatialBackdrop()

        Column(modifier = Modifier.fillMaxSize()) {
            // Visible Listening State, Single-Owner Mic & Working Stop / Re-Arm Control Bar
            Surface(
                color = QuantumGlassCard,
                border = BorderStroke(1.dp, QuantumCyan.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("wake_speech_status_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "STATE: ${wakeLifecycleState.badgeLabel} • INPUT: ${activeInputRoute.label.uppercase()}",
                            fontSize = 9.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (isUserArmed) QuantumMint else QuantumAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "MIC OWNER: ${activeMicOwner.displayLabel}",
                            fontSize = 9.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = QuantumMint.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, QuantumMint.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .clickable {
                                    requestMicInContextOrRun("ARM_WAKE_ONLY") {
                                        viewModel.armJarvisWakeAndSpeechSession()
                                    }
                                }
                                .testTag("btn_arm_or_rearm_jarvis")
                        ) {
                            Text(
                                text = if (wakeLifecycleState.requiresUserActionToArm) "TAP TO RE-ARM" else "ARM JARVIS",
                                fontSize = 9.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = QuantumMint,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }

                        Surface(
                            color = QuantumCyan.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, QuantumCyan.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .clickable {
                                    try {
                                        val roleIntent = com.example.service.OmniVoiceInteractionService
                                            .buildRequestAssistantRoleIntent(context)
                                        context.startActivity(roleIntent)
                                    } catch (_: Exception) {
                                    }
                                }
                                .testTag("btn_request_assistant_role")
                        ) {
                            Text(
                                text = "ROLE",
                                fontSize = 9.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = QuantumCyan,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                            )
                        }

                        Surface(
                            color = QuantumCrimson.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, QuantumCrimson.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .clickable {
                                    viewModel.stopAllWakeAndSpeechAndPinToDevice()
                                    if (currentStage == JarvisStage.STAGE_C_CORNER_ORB) {
                                        currentStage = JarvisStage.STAGE_B_MINIMAL_START
                                    }
                                }
                                .testTag("btn_stop_wake_and_speech")
                        ) {
                            Text(
                                text = "STOP",
                                fontSize = 9.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = QuantumCrimson,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentStage) {
                    JarvisStage.STAGE_A_PERMISSIONS_GATEKEEPER -> {
                        JarvisStageAPermissionsGatekeeper(
                            vaultManager = vaultManager,
                            onAllGranted = {
                                vaultManager.markPermissionsCompleted()
                                viewModel.refreshWakeAndSpeechState()
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
                                requestMicInContextOrRun("START_JARVIS_LIVE") {
                                    viewModel.armJarvisWakeAndSpeechSession()
                                    viewModel.startGeminiLiveSession()
                                    currentStage = JarvisStage.STAGE_C_CORNER_ORB
                                }
                            },
                            onSwitchToLiveHud = {
                                currentStage = JarvisStage.STAGE_C_CORNER_ORB
                            },
                            onTriggerVoiceListen = {
                                requestMicInContextOrRun("VOICE_LISTEN_WINDOW") {
                                    viewModel.armJarvisWakeAndSpeechSession()
                                    viewModel.omniLiveEngine.startNativeSpeechRecognizerLoop()
                                }
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
                                requestMicInContextOrRun("VOICE_LISTEN_WINDOW") {
                                    viewModel.armJarvisWakeAndSpeechSession()
                                    viewModel.omniLiveEngine.startNativeSpeechRecognizerLoop()
                                }
                            },
                            onExpandHud = { showExpandedHudDrawer = true },
                            onOpenSettings = { showQuickSettingsDialog = true },
                            onOpenAdvancedSettingsHub = onOpenAdvancedSettingsHub,
                            onEnterPip = { onEnterPipMode() },
                            onReturnToHub = {
                                currentStage = JarvisStage.STAGE_B_MINIMAL_START
                            },
                            onStop = {
                                viewModel.stopAllWakeAndSpeechAndPinToDevice()
                                currentStage = JarvisStage.STAGE_B_MINIMAL_START
                            }
                        )
                    }
                }
            }
        }

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

        if (showExpandedHudDrawer) {
            JarvisExpandedHudDrawer(
                viewModel = viewModel,
                onDismiss = { showExpandedHudDrawer = false }
            )
        }
    }
}

@Composable
private fun QuantumSpatialBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Top-right Cyan Nebula Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    QuantumCyan.copy(alpha = 0.11f),
                    QuantumViolet.copy(alpha = 0.04f),
                    Color.Transparent
                ),
                center = Offset(w * 0.85f, h * 0.12f),
                radius = w * 0.75f
            ),
            radius = w * 0.75f,
            center = Offset(w * 0.85f, h * 0.12f)
        )

        // Bottom-left Bioluminescent Mint Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    QuantumMint.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = Offset(w * 0.15f, h * 0.82f),
                radius = w * 0.65f
            ),
            radius = w * 0.65f,
            center = Offset(w * 0.15f, h * 0.82f)
        )
    }
}

// =========================================================================
// STAGE A: AUTONOMOUS OS PERMISSIONS GATEKEEPER (BENTO DECK)
// =========================================================================
@Composable
private fun JarvisStageAPermissionsGatekeeper(
    vaultManager: BiometricVaultManager,
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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

    fun refreshActualPermissions() {
        hasAccessibility = OmniAccessibilityService.isConnected()
        hasNotificationListener = OmniNotificationListenerService.isServiceRunning()
        hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        hasContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        hasPhone = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        if (hasContacts) {
            JarvisHandsFreeEngine.autoInjectBridgeContactsIfPermitted(context, force = false)
        }
    }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasMic = results[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        hasContacts = results[Manifest.permission.READ_CONTACTS]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
        hasPhone = results[Manifest.permission.CALL_PHONE]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
        if (hasContacts) {
            JarvisHandsFreeEngine.autoInjectBridgeContactsIfPermitted(context, force = false)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshActualPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        refreshActualPermissions()
    }

    val totalSteps = 3
    val completedSteps = (if (hasAccessibility) 1 else 0) +
        (if (hasNotificationListener) 1 else 0) +
        (if (hasMic) 1 else 0)

    val progress = (completedSteps.toFloat() / totalSteps.toFloat()).coerceIn(0.25f, 1f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag("stage_a_permission_gatekeeper"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Bento Readiness Header Card
        Surface(
            color = QuantumGlassCard,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, QuantumBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AETHER-X // KERNEL INITIALIZATION",
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = QuantumMint,
                            letterSpacing = 1.6.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Autonomous OS Gatekeeper",
                            fontSize = 20.sp,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Surface(
                        color = QuantumCyan.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, QuantumCyan.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = "$completedSteps/$totalSteps READY",
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = QuantumCyan,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = QuantumMint,
                    trackColor = QuantumVoid
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                GatekeeperPermissionRow(
                    title = "01 // Accessibility Vision & Auto-Click",
                    subtitle = "Powers WhatsApp message delete, voice call clicker & full screen icon awareness",
                    isGranted = hasAccessibility,
                    icon = Icons.Default.SettingsAccessibility,
                    actionLabel = if (hasAccessibility) "ACTIVE" else "ENABLE",
                    onAction = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
            }

            item {
                GatekeeperPermissionRow(
                    title = "02 // Notification & Call Interceptor",
                    subtitle = "Reads incoming alerts & powers autonomous AI Call Secretary in background",
                    isGranted = hasNotificationListener,
                    icon = Icons.Default.Notifications,
                    actionLabel = if (hasNotificationListener) "LINKED" else "GRANT",
                    onAction = {
                        try {
                            context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
            }

            item {
                GatekeeperPermissionRow(
                    title = "03 // 16kHz Audio, Contacts & Overlay",
                    subtitle = "Bilingual voice recognition, WhatsApp contact matcher & floating plasma orb",
                    isGranted = hasMic,
                    icon = Icons.Default.Layers,
                    actionLabel = if (hasMic) "READY" else "ALLOW",
                    onAction = {
                        if (!hasMic || !hasContacts || !hasPhone) {
                            runtimePermLauncher.launch(
                                arrayOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.READ_CONTACTS,
                                    Manifest.permission.CALL_PHONE,
                                    Manifest.permission.CAMERA
                                )
                            )
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
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
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                vaultManager.markPermissionsCompleted()
                onAllGranted()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("btn_grant_all_permissions"),
            colors = ButtonDefaults.buttonColors(containerColor = QuantumCyan),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = QuantumVoid)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "INITIALIZE QUANTUM NEURAL CORE",
                fontSize = 13.sp,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                color = QuantumVoid,
                letterSpacing = 1.2.sp
            )
        }
    }
}

@Composable
private fun GatekeeperPermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    icon: ImageVector,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = QuantumGlassCard),
        border = BorderStroke(
            1.dp,
            if (isGranted) QuantumMint.copy(alpha = 0.5f) else QuantumBorder
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = if (isGranted) QuantumMint.copy(alpha = 0.15f) else QuantumCyan.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isGranted) QuantumMint else QuantumCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontFamily = SpaceGroteskFontFamily,
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

            Spacer(modifier = Modifier.width(10.dp))

            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (isGranted) QuantumMint else QuantumCyan
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isGranted) QuantumMint else QuantumCyan
                )
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// =========================================================================
// STAGE B: AETHER-X QUANTUM NEURAL COMMAND CENTER (COMPLETELY REVAMPED)
// =========================================================================
@Composable
private fun JarvisStageBMinimalLaunchScreen(
    selectedVoice: GeminiLiveVoice,
    userTranscript: String = "",
    aiTranscript: String = "",
    onSelectVoice: (GeminiLiveVoice) -> Unit,
    onStartJarvis: () -> Unit,
    onSwitchToLiveHud: () -> Unit = {},
    onTriggerVoiceListen: () -> Unit = {},
    onSendPrompt: (String) -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenAdvancedSettingsHub: () -> Unit = {},
    onTestAccent: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var stageBPromptInput by remember { mutableStateOf("") }
    var isAccessibilityConnected by remember { mutableStateOf(OmniAccessibilityService.isConnected()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityConnected = OmniAccessibilityService.isConnected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "quantum_core")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbital_spin"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "harmonic_phase"
    )

    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.68f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("stage_b_minimal_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // -----------------------------------------------------------------
        // 1. TOP SPATIAL TELEMETRY PILL BAR
        // -----------------------------------------------------------------
        Surface(
            color = QuantumGlassCard.copy(alpha = 0.92f),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, QuantumBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Glowing Bioluminescent Status Dot
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .background(QuantumMint.copy(alpha = 0.15f), CircleShape)
                            .border(1.dp, QuantumMint.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = QuantumMint,
                            modifier = Modifier.size(9.dp)
                        ) {}
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "OMNIVOICE",
                                fontSize = 15.sp,
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                letterSpacing = 1.2.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = QuantumCyan.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(0.8.dp, QuantumCyan.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "AGI 4.0",
                                    fontSize = 9.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = QuantumCyan,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "${SuperAgiBrainEngine.PREFERRED_GEMINI_MODELS.first().uppercase()} • ZERO-LATENCY OS",
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Switch to Live HUD Button
                    Surface(
                        color = QuantumElevatedCard,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, QuantumCyan.copy(alpha = 0.4f)),
                        modifier = Modifier.clickable { onSwitchToLiveHud() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Radar,
                                contentDescription = "Live HUD",
                                tint = QuantumCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "HUD",
                                fontSize = 10.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = QuantumCyan
                            )
                        }
                    }

                    // System Settings Hub Gear Button
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(40.dp)
                            .background(QuantumElevatedCard, CircleShape)
                            .border(1.dp, QuantumBorder, CircleShape)
                            .testTag("btn_quick_settings_gear")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Quick Settings & Vault",
                            tint = QuantumCyan,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 2. SCROLLABLE CENTER WORKSPACE: QUANTUM PLASMA ORB + BENTO ACTION DECK
        // -----------------------------------------------------------------
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Accessibility & Screen Vision Quick Status Banner
            Surface(
                color = if (isAccessibilityConnected) QuantumMint.copy(alpha = 0.1f) else QuantumAmber.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (isAccessibilityConnected) QuantumMint.copy(alpha = 0.4f) else QuantumAmber.copy(alpha = 0.45f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isAccessibilityConnected) {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                )
                            } catch (_: Exception) {}
                        } else {
                            onOpenAdvancedSettingsHub()
                        }
                    }
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
                            imageVector = Icons.Default.SettingsAccessibility,
                            contentDescription = null,
                            tint = if (isAccessibilityConnected) QuantumMint else QuantumAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isAccessibilityConnected) {
                                "SCREEN ICON VISION & WHATSAPP AUTO-CLICK ACTIVE"
                            } else {
                                "ENABLE ACCESSIBILITY FOR FULL SCREEN ICON & WHATSAPP CONTROL"
                            },
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (isAccessibilityConnected) QuantumMint else QuantumAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = if (isAccessibilityConnected) "ONLINE" else "ENABLE →",
                        fontSize = 10.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            // Centerpiece: Interactive Multi-Harmonic Quantum Plasma Core
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onStartJarvis() }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(192.dp)
                        .clickable { onStartJarvis() }
                        .testTag("btn_start_jarvis")
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val radius = size.minDimension / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)

                        // Deep Bioluminescent Aura
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    QuantumCyan.copy(alpha = 0.34f * pulseGlow),
                                    QuantumMint.copy(alpha = 0.16f * pulseGlow),
                                    QuantumViolet.copy(alpha = 0.08f * pulseGlow),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = radius
                            ),
                            radius = radius,
                            center = center
                        )

                        // Outer Precision Telemetry Ticks
                        rotate(rotation, pivot = center) {
                            for (i in 0 until 24) {
                                val angleRad = (i * 15f) * (PI / 180f)
                                val isMajor = i % 3 == 0
                                val innerR = if (isMajor) radius * 0.80f else radius * 0.85f
                                val outerR = radius * 0.94f
                                val startX = center.x + cos(angleRad).toFloat() * innerR
                                val startY = center.y + sin(angleRad).toFloat() * innerR
                                val endX = center.x + cos(angleRad).toFloat() * outerR
                                val endY = center.y + sin(angleRad).toFloat() * outerR
                                drawLine(
                                    color = if (isMajor) QuantumCyan else QuantumMint.copy(alpha = 0.55f),
                                    start = Offset(startX, startY),
                                    end = Offset(endX, endY),
                                    strokeWidth = if (isMajor) 3f else 1.5f,
                                    cap = StrokeCap.Round
                                )
                            }
                        }

                        // Harmonic Sine-Wave Plasma Ring
                        val wavePath = Path()
                        val steps = 72
                        val baseWaveR = radius * 0.66f
                        for (s in 0..steps) {
                            val theta = (s.toFloat() / steps) * (2f * PI.toFloat())
                            val harmonicOffset = sin(theta * 6f + wavePhase) * (radius * 0.045f * pulseGlow)
                            val r = baseWaveR + harmonicOffset
                            val px = center.x + cos(theta) * r
                            val py = center.y + sin(theta) * r
                            if (s == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
                        }
                        wavePath.close()
                        drawPath(
                            path = wavePath,
                            color = QuantumMint.copy(alpha = 0.78f),
                            style = Stroke(width = 2.2f)
                        )

                        // Counter-Rotating Orbital Ring
                        rotate(-rotation * 1.5f, pivot = center) {
                            drawCircle(
                                color = QuantumViolet.copy(alpha = 0.65f),
                                radius = radius * 0.54f,
                                center = center,
                                style = Stroke(width = 2f)
                            )
                        }

                        // Inner Quantum Core Sphere
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White,
                                    QuantumCyan,
                                    Color(0xFF0077B6),
                                    QuantumObsidian
                                ),
                                center = center,
                                radius = radius * 0.42f
                            ),
                            radius = radius * 0.40f,
                            center = center
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Activate Neural Core",
                            tint = QuantumVoid,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "LAUNCH AGI",
                            fontSize = 11.sp,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = QuantumVoid,
                            letterSpacing = 1.4.sp
                        )
                    }
                }

                Text(
                    text = "TAP ORB FOR HANDS-FREE LIVE SESSION",
                    fontSize = 14.sp,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = "WhatsApp Call • Auto Message Delete • Screen Icon Vision • Video Play",
                    fontSize = 11.sp,
                    color = QuantumCyan,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // -----------------------------------------------------------------
            // 3. BENTO QUICK-COMMAND MATRIX (2x2 TACTILE GRID)
            // -----------------------------------------------------------------
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "INSTANT AUTONOMOUS ACTIONS",
                        fontSize = 10.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "1-TAP EXECUTE",
                        fontSize = 10.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = QuantumMint,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuantumBentoActionTile(
                        title = "WhatsApp Call",
                        subtitle = "Voice / Video Call",
                        badge = "VOIP",
                        icon = Icons.Default.Call,
                        accentColor = QuantumMint,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            stageBPromptInput = "WhatsApp par voice call karo "
                        }
                    )
                    QuantumBentoActionTile(
                        title = "Delete Msg",
                        subtitle = "Delete for Everyone",
                        badge = "AUTO",
                        icon = Icons.Default.DeleteSweep,
                        accentColor = QuantumCrimson,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSendPrompt("WhatsApp par last message delete karo")
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuantumBentoActionTile(
                        title = "Screen Icon AI",
                        subtitle = "Click Any Icon/UI",
                        badge = "VISION",
                        icon = Icons.Default.TouchApp,
                        accentColor = QuantumCyan,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSendPrompt("Click Search icon")
                        }
                    )
                    QuantumBentoActionTile(
                        title = "Play Video",
                        subtitle = "YouTube Instant Play",
                        badge = "MEDIA",
                        icon = Icons.Default.PlayCircle,
                        accentColor = QuantumAmber,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSendPrompt("Play trending video on YouTube")
                        }
                    )
                }
            }

            // -----------------------------------------------------------------
            // 4. LIVE CONVERSATIONAL AGI STREAM CARD
            // -----------------------------------------------------------------
            if (userTranscript.isNotBlank() || aiTranscript.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stage_b_agi_response_card"),
                    colors = CardDefaults.cardColors(containerColor = QuantumGlassCard),
                    border = BorderStroke(1.dp, QuantumCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = QuantumCyan,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "NEURAL DIALOGUE STREAM",
                                    fontSize = 10.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = QuantumCyan
                                )
                            }
                            if (aiTranscript.isNotBlank()) {
                                Surface(
                                    color = QuantumCyan.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable { onTestAccent(aiTranscript) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = "Speak Reply",
                                            tint = QuantumCyan,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "REPLAY",
                                            fontSize = 9.sp,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            color = QuantumCyan
                                        )
                                    }
                                }
                            }
                        }

                        if (userTranscript.isNotBlank()) {
                            Text(
                                text = "YOU // $userTranscript",
                                style = TextStyle(
                                    fontFamily = fontFamilyForBilingualText(userTranscript),
                                    textDirection = textDirectionForBilingualText(userTranscript),
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (aiTranscript.isNotBlank()) {
                            Text(
                                text = aiTranscript,
                                style = TextStyle(
                                    fontFamily = fontFamilyForBilingualText(aiTranscript),
                                    textDirection = textDirectionForBilingualText(aiTranscript),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                ),
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // -----------------------------------------------------------------
            // 5. NEURAL VOICE PERSONA SELECTION
            // -----------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VOICE",
                    fontSize = 10.sp,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = QuantumMint,
                    letterSpacing = 1.4.sp
                )
                Spacer(modifier = Modifier.width(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("voice_selector_chips")
                ) {
                    items(GeminiLiveVoice.entries) { voice ->
                        val isSelected = voice == selectedVoice
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectVoice(voice) },
                            label = {
                                Text(
                                    text = voice.voiceName,
                                    fontSize = 10.sp,
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = QuantumCyan,
                                selectedLabelColor = QuantumVoid,
                                containerColor = QuantumGlassCard,
                                labelColor = TextPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) QuantumCyan else QuantumBorder
                            )
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 6. BOTTOM FLOATING GLASSMORPHIC COMMAND DOCK
        // -----------------------------------------------------------------
        Surface(
            color = QuantumGlassCard,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, QuantumCyan.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onTriggerVoiceListen,
                    modifier = Modifier
                        .size(46.dp)
                        .background(QuantumMint.copy(alpha = 0.16f), CircleShape)
                        .border(1.dp, QuantumMint.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Speak Command",
                        tint = QuantumMint,
                        modifier = Modifier.size(20.dp)
                    )
                }

                OutlinedTextField(
                    value = stageBPromptInput,
                    onValueChange = { stageBPromptInput = it },
                    placeholder = {
                        Text(
                            text = "Ask AGI or command WhatsApp, Calls, Video...",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stage_b_agi_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = QuantumCyan,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = QuantumVoid,
                        unfocusedContainerColor = QuantumVoid
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
                        .height(46.dp)
                        .testTag("stage_b_agi_send_btn"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuantumCyan),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text(
                        text = "ASK",
                        fontSize = 12.sp,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = QuantumVoid
                    )
                }
            }
        }
    }
}

@Composable
private fun QuantumBentoActionTile(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = QuantumGlassCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = accentColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accentColor,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Surface(
                    color = accentColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = badge,
                        fontSize = 9.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// =========================================================================
// STAGE C: REVAMPED LIVE FLOATING ORB HUD & RADAR WORKSPACE
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
    onOpenAdvancedSettingsHub: () -> Unit = {},
    onEnterPip: () -> Boolean,
    onReturnToHub: () -> Unit = {},
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
            animation = tween(7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_spin"
    )

    val activeRms = if (liveState == LiveConnectionState.LIVE_AI_SPEAKING) outputRms else inputRms
    val audioGlowFactor = (activeRms / 60f).coerceIn(0.15f, 1.4f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag("stage_c_corner_orb_container")
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()
        val maxOffsetX = (screenWidthPx - orbSizePx).coerceAtLeast(0f)
        val maxOffsetY = (screenHeightPx - orbSizePx).coerceAtLeast(0f)

        LaunchedEffect(screenWidthPx, screenHeightPx) {
            if (!isDockInitialized && screenWidthPx > 0) {
                val marginY = with(density) { 160.dp.toPx() }
                offsetX = (maxOffsetX - edgeMarginPx).coerceAtLeast(10f)
                offsetY = (screenHeightPx - orbSizePx - marginY).coerceAtLeast(40f)
                draggableOrbState.offsetX = offsetX
                draggableOrbState.offsetY = offsetY
                snappedEdgeLabel = "RIGHT"
                isDockInitialized = true
            }
        }

        var hudTouchX by remember { mutableFloatStateOf(540f) }
        var hudTouchY by remember { mutableFloatStateOf(640f) }

        // -----------------------------------------------------------------
        // 1. LIVE NEURAL VIEWPORT & SPATIAL RADAR CANVAS
        // -----------------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 10.dp, start = 16.dp, end = 16.dp, bottom = 148.dp)
        ) {
            // Top Telemetry Status Bar
            Surface(
                color = QuantumGlassCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, QuantumCyan.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = QuantumMint,
                            modifier = Modifier.size(9.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "LIVE NEURAL HUD // ${actionState.label.uppercase()}",
                                fontSize = 10.sp,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = QuantumMint
                            )
                            Text(
                                text = actionDetail,
                                fontSize = 12.sp,
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        color = QuantumElevatedCard,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, QuantumBorder),
                        modifier = Modifier.clickable { onReturnToHub() }
                    ) {
                        Text(
                            text = "HUB VIEW",
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = QuantumCyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Interactive Spatial Radar & Screen-Graph Visualizer
            Surface(
                color = Color(0xFF060A14),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, QuantumBorder),
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
                        val center = Offset(w / 2f, h / 2f)
                        val maxR = size.minDimension * 0.36f

                        // Subtle Tactical Grid
                        val grid = 42.dp.toPx()
                        var cx = 0f
                        while (cx < w) {
                            drawLine(
                                color = Color(0xFF0C1528),
                                start = Offset(cx, 0f),
                                end = Offset(cx, h),
                                strokeWidth = 1f
                            )
                            cx += grid
                        }
                        var cy = 0f
                        while (cy < h) {
                            drawLine(
                                color = Color(0xFF0C1528),
                                start = Offset(0f, cy),
                                end = Offset(w, cy),
                                strokeWidth = 1f
                            )
                            cy += grid
                        }

                        // Concentric Radar Rings
                        for (ring in 1..3) {
                            drawCircle(
                                color = QuantumCyan.copy(alpha = 0.14f),
                                radius = maxR * (ring / 3f),
                                center = center,
                                style = Stroke(width = 1.2f)
                            )
                        }

                        // Rotating Radar Sweep Line
                        rotate(spinAngle, pivot = center) {
                            drawLine(
                                brush = Brush.linearGradient(
                                    colors = listOf(QuantumMint.copy(alpha = 0.8f), Color.Transparent),
                                    start = center,
                                    end = Offset(center.x + maxR, center.y)
                                ),
                                start = center,
                                end = Offset(center.x + maxR, center.y),
                                strokeWidth = 3f,
                                cap = StrokeCap.Round
                            )
                        }

                        // Touch Reticle
                        val tx = hudTouchX.coerceIn(24f, w - 24f)
                        val ty = hudTouchY.coerceIn(24f, h - 24f)
                        drawCircle(
                            color = QuantumCyan.copy(alpha = 0.65f),
                            radius = 18f,
                            center = Offset(tx, ty),
                            style = Stroke(width = 2f)
                        )
                    }

                    // Center HUD Status Readouts
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = QuantumCyan.copy(alpha = 0.12f),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, QuantumCyan.copy(alpha = 0.4f)),
                            modifier = Modifier.size(54.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = QuantumCyan,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Text(
                            text = "AUTONOMOUS SCREEN & OS ENGINE",
                            fontSize = 13.sp,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.Center
                        )

                        // Capability Status Pills
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HudCapabilityBadge("WA CALLS", QuantumMint)
                            HudCapabilityBadge("MSG DELETE", QuantumCrimson)
                            HudCapabilityBadge("ICON AI", QuantumCyan)
                        }
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 2. BOTTOM LIVE DIALOGUE & TACTICAL CONTROL DOCK
        // -----------------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            if (userTranscript.isNotBlank() || aiTranscript.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = QuantumGlassCard.copy(alpha = 0.96f)),
                    border = BorderStroke(1.dp, QuantumCyan.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (userTranscript.isNotBlank()) {
                            Text(
                                text = "YOU // $userTranscript",
                                style = TextStyle(
                                    fontFamily = fontFamilyForBilingualText(userTranscript),
                                    textDirection = textDirectionForBilingualText(userTranscript),
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (aiTranscript.isNotBlank()) {
                            Text(
                                text = aiTranscript,
                                style = TextStyle(
                                    fontFamily = fontFamilyForBilingualText(aiTranscript),
                                    textDirection = textDirectionForBilingualText(aiTranscript),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = QuantumCyan
                                ),
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Quick Super-AGI Action Chips (Updated with WhatsApp Call, Delete Msg & Screen Icon Click)
            val quickPrompts = remember {
                listOf(
                    "WhatsApp par last message delete karo",
                    "WhatsApp par voice call karo",
                    "Click Search icon",
                    "Play trending video on YouTube",
                    "Flashlight on karo",
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
                        color = QuantumGlassCard,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, QuantumCyan.copy(alpha = 0.35f)),
                        modifier = Modifier.clickable { onSendPrompt(chipText) }
                    ) {
                        Text(
                            text = chipText,
                            fontSize = 11.sp,
                            fontFamily = PlusJakartaSansFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = QuantumCyan,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Prompt Input + Mic + Send Bar
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
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stage_c_agi_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = QuantumCyan,
                        unfocusedBorderColor = QuantumBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = QuantumGlassCard,
                        unfocusedContainerColor = QuantumGlassCard
                    )
                )
                IconButton(
                    onClick = onTriggerVoiceListen,
                    modifier = Modifier
                        .size(48.dp)
                        .background(QuantumMint.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                        .border(1.dp, QuantumMint, RoundedCornerShape(14.dp))
                        .testTag("stage_c_mic_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Voice Input",
                        tint = QuantumMint
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
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuantumCyan)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = QuantumVoid,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "SEND",
                        fontSize = 11.sp,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = QuantumVoid
                    )
                }
            }

            // Bottom Tactical Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = QuantumGlassCard,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, QuantumBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = QuantumMint,
                            shape = CircleShape,
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = actionState.label,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = QuantumCyan
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { onEnterPip() },
                        modifier = Modifier
                            .size(40.dp)
                            .background(QuantumGlassCard, CircleShape)
                            .border(1.dp, QuantumBorder, CircleShape)
                            .testTag("btn_enter_pip_mode")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPicture,
                            contentDescription = "Picture in Picture",
                            tint = QuantumCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(40.dp)
                            .background(QuantumGlassCard, CircleShape)
                            .border(1.dp, QuantumCyan.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Orb Sensitivity & Vault Settings",
                            tint = QuantumCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onExpandHud,
                        modifier = Modifier
                            .size(40.dp)
                            .background(QuantumGlassCard, CircleShape)
                            .border(1.dp, QuantumBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Expand Neural Console",
                            tint = QuantumCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(40.dp)
                            .background(QuantumCrimson.copy(alpha = 0.14f), CircleShape)
                            .border(1.dp, QuantumCrimson.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop Jarvis",
                            tint = QuantumCrimson,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 3. DRAGGABLE 92.DP QUANTUM PLASMA ORB WITH MAGNETIC EDGE SNAP
        // -----------------------------------------------------------------
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
                    color = QuantumGlassCard.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (snappedEdgeLabel != "NONE") QuantumMint else QuantumCyan),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-28).dp)
                ) {
                    val statusTag = if (snappedEdgeLabel != "NONE") {
                        "SNAP [$snappedEdgeLabel]"
                    } else {
                        "POS (${offsetX.roundToInt()}, ${offsetY.roundToInt()})"
                    }
                    Text(
                        text = statusTag,
                        fontSize = 9.sp,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (snappedEdgeLabel != "NONE") QuantumMint else QuantumCyan,
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
                            QuantumCyan.copy(alpha = (0.48f * audioGlowFactor).coerceIn(0.18f, 0.92f)),
                            QuantumMint.copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius * (1.12f + audioGlowFactor * 0.25f)
                    ),
                    radius = radius * (1.12f + audioGlowFactor * 0.25f),
                    center = center
                )

                rotate(spinAngle, pivot = center) {
                    for (i in 0 until 12) {
                        val angle = (i * 30f) * (PI / 180f)
                        val startX = center.x + cos(angle).toFloat() * (radius * 0.72f)
                        val startY = center.y + sin(angle).toFloat() * (radius * 0.72f)
                        val endX = center.x + cos(angle).toFloat() * (radius * 0.94f)
                        val endY = center.y + sin(angle).toFloat() * (radius * 0.94f)
                        drawLine(
                            color = if (i % 2 == 0) QuantumCyan else QuantumMint,
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = 2.5f,
                            cap = StrokeCap.Round
                        )
                    }
                    drawCircle(
                        color = QuantumCyan.copy(alpha = 0.8f),
                        radius = radius * 0.68f,
                        center = center,
                        style = Stroke(width = 2f)
                    )
                }

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            QuantumCyan,
                            Color(0xFF073B5A)
                        ),
                        center = center,
                        radius = radius * 0.45f
                    ),
                    radius = radius * 0.45f,
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
                tint = QuantumVoid,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HudCapabilityBadge(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// =========================================================================
// QUICK SETTINGS & VAULT MODAL
// =========================================================================
@Composable
private fun JarvisSettingsAndVaultModal(
    vaultManager: BiometricVaultManager,
    onOpenAdvancedSettingsHub: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val vault by vaultManager.vaultState.collectAsState()
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
            colors = CardDefaults.cardColors(containerColor = QuantumGlassCard),
            border = BorderStroke(1.dp, QuantumCyan.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "AETHER-X // QUICK VAULT",
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = QuantumMint
                        )
                        Text(
                            text = "Vault & Orb Calibration",
                            fontSize = 17.sp,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Device PIN/pattern collection and replay are disabled. Android's standard lock screen remains active.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = voiceEdit,
                    onValueChange = { voiceEdit = it },
                    label = { Text("Voice Unlock Phrase") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = QuantumCyan,
                        unfocusedBorderColor = QuantumBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = QuantumBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = QuantumCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ORB DRAG SENSITIVITY",
                            fontSize = 11.sp,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = QuantumCyan
                        )
                    }
                    Text(
                        text = "${(orbSensitivityEdit * 100).roundToInt()}% (${vaultManager.calculateSlopThresholdDp(orbSensitivityEdit).roundToInt()}dp slop)",
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMonoFontFamily,
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
                        val isSelected = abs(orbSensitivityEdit - value) < 0.15f
                        FilterChip(
                            selected = isSelected,
                            onClick = { orbSensitivityEdit = value },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = QuantumCyan.copy(alpha = 0.25f),
                                selectedLabelColor = QuantumCyan,
                                containerColor = QuantumVoid,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) QuantumCyan else QuantumBorder
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
                        thumbColor = QuantumCyan,
                        activeTrackColor = QuantumCyan,
                        inactiveTrackColor = QuantumBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_orb_drag_sensitivity")
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenAdvancedSettingsHub,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, QuantumCyan)
                    ) {
                        Text(
                            text = "SYSTEM HUB",
                            fontSize = 11.sp,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = QuantumCyan
                        )
                    }

                    Button(
                        onClick = {
                            vaultManager.enrollVault(
                                masterPin = "",
                                lockScreenPattern = "",
                                secretVoiceWord = voiceEdit,
                                biometricEnrolled = true
                            )
                            vaultManager.setOrbDragSensitivity(orbSensitivityEdit)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = QuantumCyan),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "SAVE VAULT",
                            fontSize = 11.sp,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = QuantumVoid
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// REVAMPED NEURAL STATUS CONSOLE MODAL
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
            colors = CardDefaults.cardColors(containerColor = QuantumGlassCard),
            border = BorderStroke(1.5.dp, QuantumCyan.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = QuantumCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AETHER-X // NEURAL TELEMETRY",
                            fontSize = 12.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = QuantumCyan,
                            letterSpacing = 1.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = QuantumVoid),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, QuantumBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "YOU // ${userTranscript.ifBlank { "Listening for voice command..." }}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = "JARVIS // ${aiTranscript.ifBlank { "Ready for autonomous execution." }}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = QuantumCyan,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = QuantumVoid),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, QuantumMint.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "AUTONOMOUS CAPABILITIES ONLINE",
                            fontSize = 10.sp,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = QuantumMint,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "• Model: ${SuperAgiBrainEngine.PREFERRED_GEMINI_MODELS.first().uppercase()}",
                            fontSize = 11.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "• WhatsApp Voice/Video Call + Auto Message Delete ('Delete for everyone')",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = "• Full Screen Icon & UI Node Semantic Awareness Across Windows",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

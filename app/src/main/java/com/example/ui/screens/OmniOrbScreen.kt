package com.example.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.LaunchedEffect
import com.example.engine.GeminiLiveVoice
import com.example.engine.GeneratedImageAsset
import com.example.engine.LiveConnectionState
import com.example.engine.LiveSlideUpCard
import com.example.engine.MoodAndToneState
import com.example.engine.OmniOperatingMode
import com.example.engine.OmniPermanentMemory
import com.example.engine.PendingBlockedTask
import com.example.engine.PrerequisiteResolutionStep
import com.example.engine.SmartContactAndAppRegistry
import com.example.engine.SmartContactEntry
import com.example.ui.viewmodel.VoiceAssistantViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val GeminiDeepNight = Color(0xFF05070E)
private val GeminiCardSurface = Color(0xFF0E1424)
private val GeminiCardBorder = Color(0xFF1F2B48)
private val GeminiCyan = Color(0xFF00E5FF)
private val GeminiBlue = Color(0xFF4C8DF6)
private val GeminiViolet = Color(0xFFA855F7)
private val GeminiMagenta = Color(0xFFEC4899)
private val GeminiEmerald = Color(0xFF10B981)
private val GeminiAmber = Color(0xFFF59E0B)
private val GeminiCoralRed = Color(0xFFEF4444)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)

@Composable
fun OmniOrbScreen(
    viewModel: VoiceAssistantViewModel,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit
) {
    val connectionState by viewModel.liveConnectionState.collectAsState()
    val isLiveActive by viewModel.isLiveSessionActive.collectAsState()
    val isMicMuted by viewModel.isLiveMicMuted.collectAsState()
    val selectedVoice by viewModel.selectedLiveVoice.collectAsState()
    val liveUserTranscript by viewModel.liveUserTranscript.collectAsState()
    val liveAiTranscript by viewModel.liveAiTranscript.collectAsState()
    val inputRms by viewModel.liveInputRms.collectAsState()
    val outputRms by viewModel.liveOutputRms.collectAsState()
    val liveBands by viewModel.liveFrequencyBands.collectAsState()
    val slideUpCards by viewModel.liveSlideUpCards.collectAsState()
    val smartContacts by viewModel.smartContacts.collectAsState()
    val activeModelLabel by viewModel.activeLiveModelLabel.collectAsState()
    val jarvisTerminalLogs by viewModel.jarvisTerminalLogs.collectAsState()
    val jarvisWebhookStatus by viewModel.jarvisWebhookStatus.collectAsState()
    val isScreenVisionActive by viewModel.isScreenVisionActive.collectAsState()

    // 7-Pillar Autonomous Mobile AI OS State Flows
    val operatingMode by viewModel.operatingMode.collectAsState()
    val idleSecondsRemaining by viewModel.idleSecondsRemaining.collectAsState()
    val moodAndToneState by viewModel.moodAndToneState.collectAsState()
    val permanentMemory by viewModel.permanentMemory.collectAsState()
    val isNetworkOnline by viewModel.isNetworkOnline.collectAsState()
    val isSimulatedOffline by viewModel.isSimulatedOffline.collectAsState()
    val prerequisiteStep by viewModel.prerequisiteStep.collectAsState()
    val pendingBlockedTask by viewModel.pendingBlockedTask.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    var showVoicePicker by remember { mutableStateOf(false) }
    var showContactsDrawer by remember { mutableStateOf(false) }
    var showSyncContactsScreen by remember { mutableStateOf(false) }
    var showZeroTouchWizard by remember { mutableStateOf(false) }
    var showNativeApkGeneratorModal by remember { mutableStateOf(false) }
    var showKeyboardBar by remember { mutableStateOf(false) }
    var typedMessage by remember { mutableStateOf("") }

    var hasAllStartupPermissions by remember(hasMicPermission) {
        mutableStateOf(
            hasMicPermission &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_CONTACTS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val allPermissionsGatekeeperLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { resultMap ->
        val micGranted = resultMap[android.Manifest.permission.RECORD_AUDIO] == true || hasMicPermission
        val contactsGranted = resultMap[android.Manifest.permission.READ_CONTACTS] == true
        hasAllStartupPermissions = micGranted && contactsGranted
        viewModel.completeStartupPermissionsAndContactsGatekeeper()
        if (micGranted && !isLiveActive && operatingMode == OmniOperatingMode.STATE_ACTIVE_LISTENING) {
            viewModel.startGeminiLiveSession()
        }
    }

    // Unified Permission & Contacts Onboarding Sequence + Auto-Active on Launch (NO manual "Start Live Call" required)
    LaunchedEffect(hasMicPermission) {
        viewModel.completeStartupPermissionsAndContactsGatekeeper()
        if (hasMicPermission && !isLiveActive && operatingMode == OmniOperatingMode.STATE_ACTIVE_LISTENING) {
            viewModel.startGeminiLiveSession()
        }
    }

    val contactsPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.syncAndroidPhoneContacts()
        }
        showSyncContactsScreen = true
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(GeminiDeepNight)
            .testTag("omni_orb_root"),
        containerColor = GeminiDeepNight,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            when (connectionState) {
                                LiveConnectionState.LIVE_AI_SPEAKING -> GeminiViolet.copy(alpha = 0.22f)
                                LiveConnectionState.LIVE_EXECUTING_TOOL -> GeminiEmerald.copy(alpha = 0.22f)
                                LiveConnectionState.LIVE_LISTENING -> GeminiCyan.copy(alpha = 0.18f)
                                LiveConnectionState.CONNECTING -> GeminiBlue.copy(alpha = 0.16f)
                                LiveConnectionState.DISCONNECTED -> Color(0xFF0B1120)
                            },
                            GeminiDeepNight,
                            Color(0xFF030408)
                        ),
                        radius = 1200f
                    )
                )
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 620.dp)
                    .align(Alignment.Center)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(10.dp))

                // 1. Minimalist Gemini Live Top Header Pill + Contacts & App Links Drawer Button + Voice Badge
                GeminiLiveHeaderBar(
                    connectionState = connectionState,
                    isLiveActive = isLiveActive,
                    isMicMuted = isMicMuted,
                    selectedVoice = selectedVoice,
                    contactsCount = smartContacts.size,
                    activeModelLabel = activeModelLabel,
                    hasMicPermission = hasMicPermission,
                    assistantName = permanentMemory.assistantName,
                    operatingMode = operatingMode,
                    idleSecondsRemaining = idleSecondsRemaining,
                    onOpenContactsDrawer = { showContactsDrawer = true },
                    onVoiceBadgeClick = { showVoicePicker = !showVoicePicker }
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 1.25 Startup All-Permissions & 100% Contacts Gatekeeper Banner (shown if permissions not yet granted)
                if (!hasAllStartupPermissions) {
                    StartupPermissionsGatekeeperBanner(
                        assistantName = permanentMemory.assistantName,
                        contactsCount = smartContacts.size,
                        onGrantFullAccessClick = {
                            onRequestMicPermission()
                            val perms = mutableListOf(
                                android.Manifest.permission.RECORD_AUDIO,
                                android.Manifest.permission.READ_CONTACTS,
                                android.Manifest.permission.CALL_PHONE,
                                android.Manifest.permission.SEND_SMS,
                                android.Manifest.permission.CAMERA
                            )
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                            allPermissionsGatekeeperLauncher.launch(perms.toTypedArray())
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // 1.35 Affective Mood & Tone Sensor + 60s Sleep Timer + Hybrid Offline/Online Brain Strip
                AutonomousMoodAndHybridBrainStrip(
                    assistantName = permanentMemory.assistantName,
                    operatingMode = operatingMode,
                    idleSecondsRemaining = idleSecondsRemaining,
                    moodAndToneState = moodAndToneState,
                    isNetworkOnline = isNetworkOnline,
                    isSimulatedOffline = isSimulatedOffline,
                    prerequisiteStep = prerequisiteStep,
                    pendingBlockedTask = pendingBlockedTask,
                    learnedRulesCount = permanentMemory.learnedRules.size,
                    onToggleOfflineSimulation = { viewModel.toggleSimulatedOfflineMode() },
                    onWakeOrSleepToggle = {
                        if (operatingMode == OmniOperatingMode.STATE_SLEEP_STANDBY) {
                            viewModel.wakeUpAssistantFromStandby()
                        } else {
                            viewModel.enterStandbyModeNow()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 1.5 Movie-Style JARVIS HUD Telemetry Strip + Real-Time OS Execution Terminal
                JarvisHudTelemetryAndTerminal(
                    contactsCount = smartContacts.size,
                    webhookStatus = jarvisWebhookStatus,
                    terminalLogs = jarvisTerminalLogs,
                    onSyncPhoneContactsClick = {
                        val hasContactsPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.READ_CONTACTS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (hasContactsPerm) {
                            viewModel.syncAndroidPhoneContacts()
                        }
                        showSyncContactsScreen = true
                    },
                    onOpenZeroTouchWizardClick = { showZeroTouchWizard = true },
                    onOpenContactsBookClick = { showContactsDrawer = true },
                    onOpenNativeApkSetupClick = { showNativeApkGeneratorModal = true },
                    isScreenVisionActive = isScreenVisionActive,
                    onToggleScreenVision = { viewModel.toggleScreenVision() }
                )

                // Optional Voice Selector Strip (`Aoede`, `Puck`, `Charon`, `Kore`, `Fenrir`)
                AnimatedVisibility(
                    visible = showVoicePicker,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    GeminiVoiceSelectorRow(
                        selectedVoice = selectedVoice,
                        onSelectVoice = { voice ->
                            viewModel.selectLiveVoice(voice)
                            showVoicePicker = false
                        }
                    )
                }

                // 2. Center Full-Screen Fluid Gemini Live Aura / Orb
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        GeminiLiveFluidAuraOrb(
                            connectionState = connectionState,
                            isLiveActive = isLiveActive,
                            isMicMuted = isMicMuted,
                            operatingMode = operatingMode,
                            idleSecondsRemaining = idleSecondsRemaining,
                            assistantName = permanentMemory.assistantName,
                            inputRms = inputRms,
                            outputRms = outputRms,
                            frequencyBands = liveBands,
                            onOrbClick = {
                                if (!hasMicPermission) {
                                    onRequestMicPermission()
                                } else {
                                    viewModel.toggleGeminiLiveSession()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(22.dp))

                        // 3. Live Floating Subtitles (Input & Output Audio Transcriptions)
                        GeminiLiveFloatingSubtitles(
                            connectionState = connectionState,
                            isLiveActive = isLiveActive,
                            isMicMuted = isMicMuted,
                            operatingMode = operatingMode,
                            assistantName = permanentMemory.assistantName,
                            userTranscript = liveUserTranscript,
                            aiTranscript = liveAiTranscript
                        )
                    }
                }

                // 4. Slide-Up Live Autonomous Action & Image Cards
                AnimatedVisibility(
                    visible = slideUpCards.isNotEmpty(),
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
                ) {
                    val topCard = slideUpCards.firstOrNull()
                    if (topCard != null) {
                        GeminiLiveSlideUpCardView(
                            card = topCard,
                            onDismiss = { viewModel.dismissLiveSlideUpCard(topCard.id) },
                            onLaunchDeepLinkNow = { viewModel.launchLiveCardDeepLinkNow(topCard) },
                            onApplyWhatsAppDp = { asset ->
                                viewModel.applyGeneratedImageToWhatsAppDp(asset)
                            },
                            onShareImage = { prompt, url ->
                                viewModel.sendTextToLiveSession("Share generated image '$prompt' ($url) on WhatsApp")
                            }
                        )
                    }
                }

                // 5. Optional Compact Keyboard Bar to type into the Live Session
                AnimatedVisibility(
                    visible = showKeyboardBar,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
                ) {
                    GeminiLiveKeyboardInputBar(
                        value = typedMessage,
                        onValueChange = { typedMessage = it },
                        onSend = {
                            val msg = typedMessage.trim()
                            if (msg.isNotEmpty()) {
                                viewModel.sendTextToLiveSession(msg)
                                typedMessage = ""
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 6. Bottom Minimalist Gemini Live Dock (3 Circular Buttons + Keyboard Toggle)
                GeminiLiveBottomControlDock(
                    isLiveActive = isLiveActive,
                    isMicMuted = isMicMuted,
                    selectedVoice = selectedVoice,
                    isKeyboardVisible = showKeyboardBar,
                    onToggleMicMute = {
                        if (!hasMicPermission) {
                            onRequestMicPermission()
                        } else if (!isLiveActive) {
                            viewModel.startGeminiLiveSession()
                        } else {
                            viewModel.toggleLiveMicMute()
                        }
                    },
                    onCycleVoice = {
                        viewModel.cycleLiveVoice()
                    },
                    onToggleLiveSession = {
                        if (!hasMicPermission) {
                            onRequestMicPermission()
                        } else {
                            viewModel.toggleGeminiLiveSession()
                        }
                    },
                    onToggleKeyboard = {
                        showKeyboardBar = !showKeyboardBar
                    },
                    onOpenContactsManager = {
                        showContactsDrawer = true
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))
            }
        }
    }

    // Full-Screen "Smart Contact Book (omni_contacts_db)" Modal / Screen
    if (showContactsDrawer) {
        OmniContactsManagerModal(
            contacts = smartContacts,
            rawJsonProvider = { viewModel.getRawOmniContactsDbJson() },
            onDismiss = { showContactsDrawer = false },
            onSaveNewContact = { name, phone, wa, ig, fb, tt ->
                viewModel.saveOrUpdateSmartContact(
                    name = name,
                    phoneNumber = phone,
                    whatsappNumber = wa,
                    instagramHandle = ig,
                    facebookProfile = fb,
                    tiktokHandle = tt
                )
            },
            onUpdateExistingContact = { contactId, name, phone, wa, ig, fb, tt ->
                viewModel.updateSmartContactById(
                    contactId = contactId,
                    name = name,
                    phoneNumber = phone,
                    whatsappNumber = wa,
                    instagramHandle = ig,
                    facebookProfile = fb,
                    tiktokHandle = tt
                )
            },
            onDeleteContact = { id ->
                viewModel.deleteSmartContact(id)
            },
            onImportRawJson = { rawJson ->
                viewModel.importOmniContactsDbJson(rawJson)
            },
            onQuickVoiceCommand = { cmd ->
                showContactsDrawer = false
                viewModel.sendTextToLiveSession(cmd)
            }
        )
    }

    // Dedicated 'Sync Contacts' Screen UI (Android Contact Picker API + Multi-Select + jarvis_phone_contacts Fuzzy Formatter)
    if (showSyncContactsScreen) {
        JarvisSyncContactsScreenModal(
            savedContacts = smartContacts,
            onDismiss = { showSyncContactsScreen = false },
            onRefreshSyncedContacts = { viewModel.syncAndroidPhoneContacts() },
            onImportVcfText = { vcf -> viewModel.importVCardContacts(vcf) },
            onDeleteContact = { id -> viewModel.deleteSmartContact(id) }
        )
    }

    // Built-In "100% Hands-Free Setup Wizard" Modal
    if (showZeroTouchWizard) {
        JarvisZeroTouchSetupModal(
            contactsCount = smartContacts.size,
            initialWebhookUrl = viewModel.getAutomationWebhookUrl(),
            onDismiss = { showZeroTouchWizard = false },
            onSyncPhoneContacts = { viewModel.syncAndroidPhoneContacts() },
            onImportVcfText = { vcf -> viewModel.importVCardContacts(vcf) },
            onSaveWebhookUrl = { url -> viewModel.setAutomationWebhookUrl(url) },
            onOpenContactsBook = { showContactsDrawer = true }
        )
    }

    // Built-In "Install Native Android Accessibility APK" Code Generator Modal
    if (showNativeApkGeneratorModal) {
        NativeAccessibilityApkGeneratorModal(
            isAccessibilityConnected = com.example.service.OmniAccessibilityService.isConnected(),
            syncedContactsCount = smartContacts.size,
            onAutoInjectContactsNow = { viewModel.syncAndroidPhoneContacts() },
            onDismiss = { showNativeApkGeneratorModal = false }
        )
    }
}

@Composable
private fun StartupPermissionsGatekeeperBanner(
    assistantName: String,
    contactsCount: Int,
    onGrantFullAccessClick: () -> Unit
) {
    Surface(
        color = GeminiCardSurface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GeminiEmerald.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("startup_permissions_gatekeeper_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Zero-Touch Access & 100% Contacts Sync ($contactsCount Ready)",
                    color = GeminiEmerald,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Enable Mic, Contacts, Call/SMS & Torch for hands-free $assistantName execution.",
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onGrantFullAccessClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GeminiEmerald,
                    contentColor = GeminiDeepNight
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("grant_full_mobile_contacts_access_button")
            ) {
                Text(
                    text = "Grant Full Mobile & Contacts Access",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
private fun AutonomousMoodAndHybridBrainStrip(
    assistantName: String,
    operatingMode: OmniOperatingMode,
    idleSecondsRemaining: Int,
    moodAndToneState: MoodAndToneState,
    isNetworkOnline: Boolean,
    isSimulatedOffline: Boolean,
    prerequisiteStep: PrerequisiteResolutionStep,
    pendingBlockedTask: PendingBlockedTask?,
    learnedRulesCount: Int,
    onToggleOfflineSimulation: () -> Unit,
    onWakeOrSleepToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("autonomous_mood_and_hybrid_strip"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Live Affective Mood & Tone Sensor Badge
            Surface(
                color = GeminiCardSurface.copy(alpha = 0.9f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, GeminiViolet.copy(alpha = 0.55f)),
                modifier = Modifier
                    .weight(1f)
                    .testTag("mood_and_tone_sensor_badge")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = moodAndToneState.badgeLabel,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 2. 60-Second Sleep Countdown / Standby Wake Pill
            val isStandby = operatingMode == OmniOperatingMode.STATE_SLEEP_STANDBY
            Surface(
                color = if (isStandby) GeminiAmber.copy(alpha = 0.16f) else GeminiCardSurface.copy(alpha = 0.9f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (isStandby) GeminiAmber else GeminiCyan.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onWakeOrSleepToggle)
                    .testTag("sleep_countdown_pill")
            ) {
                Text(
                    text = if (isStandby) {
                        "Standby • Wake '$assistantName'"
                    } else {
                        "Auto-Sleep: ${idleSecondsRemaining}s"
                    },
                    color = if (isStandby) GeminiAmber else GeminiCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            // 3. Offline + Online Hybrid Brain Toggle Chip
            Surface(
                color = if (!isNetworkOnline || isSimulatedOffline) {
                    GeminiCoralRed.copy(alpha = 0.18f)
                } else {
                    GeminiEmerald.copy(alpha = 0.16f)
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (!isNetworkOnline || isSimulatedOffline) GeminiCoralRed else GeminiEmerald
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onToggleOfflineSimulation)
                    .testTag("hybrid_network_mode_chip")
            ) {
                Text(
                    text = if (!isNetworkOnline || isSimulatedOffline) {
                        "Offline Brain ($learnedRulesCount rules)"
                    } else {
                        "Online + Search ($learnedRulesCount)"
                    },
                    color = if (!isNetworkOnline || isSimulatedOffline) GeminiCoralRed else GeminiEmerald,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                )
            }
        }

        // If Prerequisite Resolver is actively handling an offline task (Steps A-D), show live status banner
        if (prerequisiteStep != PrerequisiteResolutionStep.NONE || pendingBlockedTask != null) {
            Surface(
                color = GeminiAmber.copy(alpha = 0.16f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, GeminiAmber),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("prerequisite_resolver_banner")
            ) {
                Text(
                    text = "Zero-Touch Prerequisite Resolver (${prerequisiteStep.name}): Queued '${pendingBlockedTask?.originalUtterance ?: ""}' — Say 'Wi-Fi on kar do', pick SSID, & speak password to auto-resume.",
                    color = GeminiAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun GeminiLiveHeaderBar(
    connectionState: LiveConnectionState,
    isLiveActive: Boolean,
    isMicMuted: Boolean,
    selectedVoice: GeminiLiveVoice,
    contactsCount: Int,
    activeModelLabel: String,
    hasMicPermission: Boolean,
    assistantName: String,
    operatingMode: OmniOperatingMode,
    idleSecondsRemaining: Int,
    onOpenContactsDrawer: () -> Unit,
    onVoiceBadgeClick: () -> Unit
) {
    val statusDotColor = when {
        !hasMicPermission -> GeminiCoralRed
        operatingMode == OmniOperatingMode.STATE_SLEEP_STANDBY -> GeminiAmber
        !isLiveActive -> GeminiCyan
        isMicMuted -> GeminiAmber
        connectionState == LiveConnectionState.LIVE_AI_SPEAKING -> GeminiViolet
        connectionState == LiveConnectionState.LIVE_EXECUTING_TOOL -> GeminiEmerald
        connectionState == LiveConnectionState.LIVE_LISTENING -> GeminiCyan
        connectionState == LiveConnectionState.CONNECTING -> GeminiBlue
        else -> GeminiCyan
    }

    val statusLabel = when {
        !hasMicPermission -> "Mic Permission Needed"
        operatingMode == OmniOperatingMode.STATE_SLEEP_STANDBY -> "Standby — Say 'Wake up $assistantName'"
        isMicMuted -> "$assistantName Mic Paused"
        connectionState == LiveConnectionState.CONNECTING -> "$assistantName Connecting..."
        connectionState == LiveConnectionState.LIVE_AI_SPEAKING -> "$assistantName Speaking (24kHz)"
        connectionState == LiveConnectionState.LIVE_EXECUTING_TOOL -> "$assistantName Executing Tool..."
        connectionState == LiveConnectionState.LIVE_LISTENING -> "$assistantName Active (${idleSecondsRemaining}s)"
        else -> "$assistantName Always-Active (${idleSecondsRemaining}s)"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Live Status Pill
        Surface(
            color = GeminiCardSurface.copy(alpha = 0.88f),
            shape = CircleShape,
            border = BorderStroke(1.dp, statusDotColor.copy(alpha = 0.45f)),
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag("live_status_pill")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = statusLabel,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = activeModelLabel,
                        color = TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Center-Right: "Contacts & App Links" Drawer Button (`omni_contacts_db`)
        Surface(
            color = GeminiCardSurface.copy(alpha = 0.88f),
            shape = CircleShape,
            border = BorderStroke(1.dp, GeminiCyan.copy(alpha = 0.45f)),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onOpenContactsDrawer)
                .testTag("contacts_and_apps_drawer_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Contacts,
                    contentDescription = "Contacts & App Links",
                    tint = GeminiCyan,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Contacts ($contactsCount)",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Right: Active Prebuilt Voice Badge (`Aoede`, `Puck`, `Charon`, `Kore`, `Fenrir`)
        Surface(
            color = GeminiCardSurface.copy(alpha = 0.88f),
            shape = CircleShape,
            border = BorderStroke(1.dp, GeminiViolet.copy(alpha = 0.45f)),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onVoiceBadgeClick)
                .testTag("voice_selector_badge")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = "Select Gemini Live Voice",
                    tint = GeminiViolet,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = selectedVoice.voiceName,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun GeminiVoiceSelectorRow(
    selectedVoice: GeminiLiveVoice,
    onSelectVoice: (GeminiLiveVoice) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(GeminiLiveVoice.entries.toList()) { voice ->
            val isSelected = voice == selectedVoice
            Surface(
                color = if (isSelected) GeminiViolet.copy(alpha = 0.24f) else GeminiCardSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (isSelected) GeminiViolet else GeminiCardBorder
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectVoice(voice) }
                    .testTag("voice_chip_${voice.voiceName.lowercase()}")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = voice.voiceName,
                        color = if (isSelected) GeminiCyan else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = voice.subtitle,
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun GeminiLiveFluidAuraOrb(
    connectionState: LiveConnectionState,
    isLiveActive: Boolean,
    isMicMuted: Boolean,
    operatingMode: OmniOperatingMode,
    idleSecondsRemaining: Int,
    assistantName: String,
    inputRms: Float,
    outputRms: Float,
    frequencyBands: List<Float>,
    onOrbClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gemini_live_orb")

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase1"
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )

    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleBreath"
    )

    val activeEnergy = when (connectionState) {
        LiveConnectionState.LIVE_AI_SPEAKING -> (0.25f + (outputRms / 100f) * 0.85f).coerceIn(0.15f, 1f)
        LiveConnectionState.LIVE_LISTENING -> if (isMicMuted) 0.05f else (0.12f + (inputRms / 100f) * 0.9f).coerceIn(0.08f, 1f)
        LiveConnectionState.LIVE_EXECUTING_TOOL -> 0.55f
        LiveConnectionState.CONNECTING -> 0.3f
        LiveConnectionState.DISCONNECTED -> 0.05f
    }

    val animatedScale by animateFloatAsState(
        targetValue = if (isLiveActive) (1.0f + activeEnergy * 0.34f) else idleBreath,
        animationSpec = tween(durationMillis = 120),
        label = "orbScale"
    )

    val primaryColor = when {
        !isLiveActive -> GeminiBlue
        isMicMuted -> GeminiAmber
        connectionState == LiveConnectionState.LIVE_AI_SPEAKING -> GeminiViolet
        connectionState == LiveConnectionState.LIVE_EXECUTING_TOOL -> GeminiEmerald
        else -> GeminiCyan
    }

    val secondaryColor = when {
        !isLiveActive -> GeminiViolet
        isMicMuted -> GeminiCoralRed
        connectionState == LiveConnectionState.LIVE_AI_SPEAKING -> GeminiMagenta
        connectionState == LiveConnectionState.LIVE_EXECUTING_TOOL -> GeminiCyan
        else -> GeminiBlue
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(280.dp)
            .clip(CircleShape)
            .clickable(onClick = onOrbClick)
            .testTag("omni_orb_button")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension / 3.1f) * animatedScale

            // Outer ambient Gemini Live aura glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.42f),
                        secondaryColor.copy(alpha = 0.18f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.75f
                ),
                radius = baseRadius * 1.75f,
                center = center
            )

            // Fluid morphing harmonic lobe 1
            rotate(degrees = phase1, pivot = center) {
                val lobePath1 = Path()
                val steps = 72
                for (i in 0..steps) {
                    val angle = (i.toFloat() / steps) * (2f * PI.toFloat())
                    val bandIdx = i % frequencyBands.size.coerceAtLeast(1)
                    val bandAmp = frequencyBands.getOrElse(bandIdx) { 0.15f }
                    val wave = sin(angle * 3f + (phase1 * PI.toFloat() / 180f)) *
                        (12f + bandAmp * 34f * activeEnergy)
                    val r = baseRadius * 1.08f + wave
                    val x = center.x + r * cos(angle)
                    val y = center.y + r * sin(angle)
                    if (i == 0) lobePath1.moveTo(x, y) else lobePath1.lineTo(x, y)
                }
                lobePath1.close()
                drawPath(
                    path = lobePath1,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.55f),
                            secondaryColor.copy(alpha = 0.45f)
                        )
                    ),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Fluid morphing harmonic lobe 2
            rotate(degrees = phase2, pivot = center) {
                val lobePath2 = Path()
                val steps = 72
                for (i in 0..steps) {
                    val angle = (i.toFloat() / steps) * (2f * PI.toFloat())
                    val bandIdx = (i + 7) % frequencyBands.size.coerceAtLeast(1)
                    val bandAmp = frequencyBands.getOrElse(bandIdx) { 0.15f }
                    val wave = cos(angle * 4f - (phase2 * PI.toFloat() / 180f)) *
                        (10f + bandAmp * 28f * activeEnergy)
                    val r = baseRadius * 0.96f + wave
                    val x = center.x + r * cos(angle)
                    val y = center.y + r * sin(angle)
                    if (i == 0) lobePath2.moveTo(x, y) else lobePath2.lineTo(x, y)
                }
                lobePath2.close()
                drawPath(
                    path = lobePath2,
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            secondaryColor.copy(alpha = 0.65f),
                            primaryColor.copy(alpha = 0.65f),
                            GeminiCyan.copy(alpha = 0.55f),
                            secondaryColor.copy(alpha = 0.65f)
                        ),
                        center = center
                    ),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Inner glowing core sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.92f),
                        primaryColor.copy(alpha = 0.88f),
                        secondaryColor.copy(alpha = 0.72f),
                        GeminiDeepNight.copy(alpha = 0.9f)
                    ),
                    center = Offset(center.x - baseRadius * 0.18f, center.y - baseRadius * 0.18f),
                    radius = baseRadius * 0.92f
                ),
                radius = baseRadius * 0.78f,
                center = center
            )
        }

        // Center icon / status hint inside the fluid Orb
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = when {
                    !isLiveActive -> Icons.Default.AutoAwesome
                    isMicMuted -> Icons.Default.MicOff
                    connectionState == LiveConnectionState.LIVE_AI_SPEAKING -> Icons.Default.GraphicEq
                    connectionState == LiveConnectionState.LIVE_EXECUTING_TOOL -> Icons.Default.Bolt
                    else -> Icons.Default.Mic
                },
                contentDescription = "Gemini Live Orb",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    operatingMode == OmniOperatingMode.STATE_SLEEP_STANDBY -> "STANDBY • WAKE $assistantName"
                    isMicMuted -> "MUTED"
                    connectionState == LiveConnectionState.LIVE_AI_SPEAKING -> "SPEAKING"
                    connectionState == LiveConnectionState.LIVE_EXECUTING_TOOL -> "TOOL CALL"
                    isLiveActive -> "ACTIVE • ${idleSecondsRemaining}S"
                    else -> "AUTO-ACTIVE • ${idleSecondsRemaining}S"
                },
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.4.sp
            )
        }
    }
}

@Composable
private fun GeminiLiveFloatingSubtitles(
    connectionState: LiveConnectionState,
    isLiveActive: Boolean,
    isMicMuted: Boolean,
    operatingMode: OmniOperatingMode,
    assistantName: String,
    userTranscript: String,
    aiTranscript: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Real-time User Input Caption (`inputAudioTranscription`)
        if (userTranscript.isNotBlank()) {
            Text(
                text = "\"$userTranscript\"",
                color = GeminiCyan.copy(alpha = 0.92f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag("live_user_subtitle")
            )
        }

        // Real-time Gemini Output Caption (`outputAudioTranscription`)
        val subtitleText = when {
            operatingMode == OmniOperatingMode.STATE_SLEEP_STANDBY ->
                "Standby Mode — Say 'Wake up $assistantName' to activate."
            aiTranscript.isNotBlank() -> aiTranscript
            !isLiveActive -> "$assistantName is auto-active — speak in English, Urdu (اردو), or Roman Urdu to control calls, WhatsApp, Wi-Fi, Instagram, TikTok, YouTube & Google Search."
            isMicMuted -> "Microphone is paused. Tap Unmute to continue speaking."
            else -> "Listening ($assistantName)... Speak naturally or interrupt anytime."
        }

        Text(
            text = subtitleText,
            color = if (connectionState == LiveConnectionState.LIVE_AI_SPEAKING) {
                TextPrimary
            } else {
                TextPrimary.copy(alpha = 0.86f)
            },
            fontSize = 16.sp,
            fontWeight = if (connectionState == LiveConnectionState.LIVE_AI_SPEAKING) {
                FontWeight.SemiBold
            } else {
                FontWeight.Normal
            },
            textAlign = TextAlign.Center,
            lineHeight = 23.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("live_ai_subtitle")
        )
    }
}

@Composable
private fun GeminiLiveSlideUpCardView(
    card: LiveSlideUpCard,
    onDismiss: () -> Unit,
    onLaunchDeepLinkNow: () -> Unit,
    onApplyWhatsAppDp: (GeneratedImageAsset) -> Unit,
    onShareImage: (String, String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("live_slide_up_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = GeminiCardSurface.copy(alpha = 0.96f)
        ),
        border = BorderStroke(1.dp, GeminiCyan.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = GeminiEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = card.title,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = card.subtitle,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss Card",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Live Autonomous UI Macro Step Trace (if macroSteps are present)
            if (card.macroSteps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF080D1A))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    card.macroSteps.take(4).forEach { step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "✓",
                                color = GeminiEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${step.stepIndex}. ${step.action}",
                                color = GeminiCyan,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (step.text.isNotBlank()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "(${step.text.take(28)})",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // One-Tap Deep-Link Fallback Button for Zero-Friction App Launching
            if (card.deepLinkUri.isNotBlank() || card.isLaunchingApp) {
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onLaunchDeepLinkNow,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = GeminiCyan.copy(alpha = 0.18f),
                        contentColor = GeminiCyan
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("card_launch_deep_link_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (card.appName.isNotBlank()) {
                            "Launch ${card.appName} Now"
                        } else {
                            "Tap to Open App Now"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // If this tool call generated an AI image (`generate_and_use_ai_image`), render it with actions
            val imageAsset = card.generatedImage
            if (imageAsset != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val decodedBitmap = remember(imageAsset.base64DataUrl) {
                    try {
                        val rawBase64 = imageAsset.base64DataUrl.substringAfter("base64,", "")
                        if (rawBase64.isNotEmpty()) {
                            val bytes = Base64.decode(rawBase64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }

                if (decodedBitmap != null) {
                    Image(
                        bitmap = decodedBitmap.asImageBitmap(),
                        contentDescription = imageAsset.prompt,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(165.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, GeminiCardBorder, RoundedCornerShape(16.dp))
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onApplyWhatsAppDp(imageAsset) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = GeminiEmerald.copy(alpha = 0.2f),
                            contentColor = GeminiEmerald
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Set WhatsApp DP", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { onShareImage(imageAsset.prompt, card.imageUrl ?: imageAsset.imageUrl) },
                        border = BorderStroke(1.dp, GeminiCyan.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = GeminiCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", color = GeminiCyan, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartContactsAndAppsDrawerDialog(
    contacts: List<SmartContactEntry>,
    onDismiss: () -> Unit,
    onSaveContact: (String, String, String, String, String, String) -> Unit,
    onDeleteContact: (Long) -> Unit,
    onQuickCommand: (String) -> Unit
) {
    var nameInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }
    var igInput by remember { mutableStateOf("") }
    var fbInput by remember { mutableStateOf("") }
    var tiktokInput by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 640.dp)
                .testTag("smart_contacts_drawer_dialog"),
            shape = RoundedCornerShape(26.dp),
            color = GeminiCardSurface,
            border = BorderStroke(1.dp, GeminiCyan.copy(alpha = 0.45f))
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
                    Column {
                        Text(
                            text = "Smart Contacts & App Links",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "omni_contacts_db • Voice-addressable across WhatsApp, IG, FB & TikTok",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Contacts Drawer",
                            tint = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quick App Launch / Voice Test Strip
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val samples = listOf(
                        "WhatsApp Ali" to "WhatsApp par Ali ko message bhejo ke main class mein hoon",
                        "TikTok Cats" to "TikTok kholo aur funny cat videos search karo",
                        "IG Reels" to "Instagram open karo aur reels chalao",
                        "FB Search" to "Facebook par Ahmed ko dhoondo"
                    )
                    items(samples) { (label, cmd) ->
                        Surface(
                            color = GeminiCyan.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, GeminiCyan.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onQuickCommand(cmd) }
                        ) {
                            Text(
                                text = label,
                                color = GeminiCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Add / Update Contact Form
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF080D18))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Add or Update Contact Handle",
                        color = GeminiCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            placeholder = { Text("Name (e.g. Ali)", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = { phoneInput = it },
                            placeholder = { Text("03001234567", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = igInput,
                            onValueChange = { igInput = it },
                            placeholder = { Text("@instagram", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = fbInput,
                            onValueChange = { fbInput = it },
                            placeholder = { Text("fb.profile", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = tiktokInput,
                            onValueChange = { tiktokInput = it },
                            placeholder = { Text("@tiktok", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            if (nameInput.isNotBlank()) {
                                onSaveContact(
                                    nameInput.trim(),
                                    phoneInput.trim(),
                                    phoneInput.trim(),
                                    igInput.trim(),
                                    fbInput.trim(),
                                    tiktokInput.trim()
                                )
                                nameInput = ""
                                phoneInput = ""
                                igInput = ""
                                fbInput = ""
                                tiktokInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GeminiCyan,
                            contentColor = GeminiDeepNight
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("save_smart_contact_button")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save to omni_contacts_db", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Saved Contacts List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(contacts, key = { it.id }) { c ->
                        Surface(
                            color = Color(0xFF121A2E),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, GeminiCardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = c.name,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val formattedWa = SmartContactAndAppRegistry.formatWhatsAppPhone(
                                        c.whatsappNumber.ifBlank { c.phoneNumber }
                                    )
                                    Text(
                                        text = buildString {
                                            if (c.phoneNumber.isNotBlank()) append("Tel: ${c.phoneNumber} ")
                                            if (formattedWa.isNotBlank()) append("• WA: $formattedWa ")
                                            if (c.instagramHandle.isNotBlank()) append("• IG: @${c.instagramHandle} ")
                                            if (c.tiktokHandle.isNotBlank()) append("• TT: @${c.tiktokHandle}")
                                        },
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteContact(c.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Contact",
                                        tint = GeminiCoralRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeminiLiveKeyboardInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    "Ask Omni Live or say 'WhatsApp par Ali ko message bhejo...'",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = GeminiCardSurface,
                unfocusedContainerColor = GeminiCardSurface,
                focusedBorderColor = GeminiCyan,
                unfocusedBorderColor = GeminiCardBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier
                .weight(1f)
                .testTag("live_text_input")
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = onSend,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(GeminiCyan)
                .testTag("live_send_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send to Live Session",
                tint = GeminiDeepNight
            )
        }
    }
}

@Composable
private fun GeminiLiveBottomControlDock(
    isLiveActive: Boolean,
    isMicMuted: Boolean,
    selectedVoice: GeminiLiveVoice,
    isKeyboardVisible: Boolean,
    onToggleMicMute: () -> Unit,
    onCycleVoice: () -> Unit,
    onToggleLiveSession: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onOpenContactsManager: () -> Unit = {}
) {
    Surface(
        color = GeminiCardSurface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(36.dp),
        border = BorderStroke(1.dp, GeminiCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gemini_live_bottom_dock")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Pause / Mute Mic Button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onToggleMicMute,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(
                            if (isMicMuted) GeminiAmber.copy(alpha = 0.22f)
                            else Color(0xFF172036)
                        )
                        .border(
                            1.dp,
                            if (isMicMuted) GeminiAmber else GeminiCardBorder,
                            CircleShape
                        )
                        .testTag("dock_mute_mic_button")
                ) {
                    Icon(
                        imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMicMuted) "Unmute Mic" else "Pause / Mute Mic",
                        tint = if (isMicMuted) GeminiAmber else TextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isMicMuted) "Unmute" else "Mute Mic",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }

            // 2. Switch Voice Button (`Aoede`, `Puck`, `Charon`, `Kore`, `Fenrir`)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onCycleVoice,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(GeminiViolet.copy(alpha = 0.2f))
                        .border(1.dp, GeminiViolet.copy(alpha = 0.6f), CircleShape)
                        .testTag("dock_switch_voice_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = "Switch Voice",
                        tint = GeminiViolet
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = selectedVoice.voiceName,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 3. Start / End Live Session Primary Pill Button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onToggleLiveSession,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(
                            if (isLiveActive) GeminiCoralRed else GeminiCyan
                        )
                        .testTag("dock_start_end_live_button")
                ) {
                    Icon(
                        imageVector = if (isLiveActive) Icons.Default.CallEnd else Icons.Default.AutoAwesome,
                        contentDescription = if (isLiveActive) "End Live Session" else "Start Live Session",
                        tint = if (isLiveActive) Color.White else GeminiDeepNight,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isLiveActive) "End Live" else "Start Live",
                    color = if (isLiveActive) GeminiCoralRed else GeminiCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 4. Smart Contacts (`omni_contacts_db`) Button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onOpenContactsManager,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(GeminiEmerald.copy(alpha = 0.18f))
                        .border(1.dp, GeminiEmerald.copy(alpha = 0.6f), CircleShape)
                        .testTag("dock_contacts_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Contacts,
                        contentDescription = "Open Smart Contacts Book",
                        tint = GeminiEmerald
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Contacts",
                    color = GeminiEmerald,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 5. Compact Keyboard Toggle Button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = onToggleKeyboard,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF172036))
                        .border(1.dp, GeminiCardBorder, CircleShape)
                        .testTag("dock_keyboard_button")
                ) {
                    Icon(
                        imageVector = if (isKeyboardVisible) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                        contentDescription = "Toggle Keyboard Input",
                        tint = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Type",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

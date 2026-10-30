package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.roundToInt
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import com.example.engine.BiometricVaultManager
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletSecondary
import com.example.ui.viewmodel.VoiceAssistantViewModel

@Composable
fun SettingsHubScreen(viewModel: VoiceAssistantViewModel) {
    var subScreenIndex by remember { mutableIntStateOf(0) }
    val config by viewModel.config.collectAsState()
    val preferredLanguageMode by viewModel.preferredLanguageMode.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    var wakeWordInput by remember(config.wakeWord) { mutableStateOf(config.wakeWord) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Sub-navigation bar inside Settings Hub
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val subTabs = listOf(
                "Voice & Language" to "settings_sub_voice",
                "Orb & Gestures" to "settings_sub_gestures",
                "Security Vault" to "settings_sub_security",
                "Permissions" to "settings_sub_permissions",
                "Diagnostics" to "settings_sub_diagnostics"
            )
            subTabs.forEachIndexed { idx, (title, tag) ->
                FilterChip(
                    selected = subScreenIndex == idx,
                    onClick = { subScreenIndex = idx },
                    label = { Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.testTag(tag),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanPrimary.copy(alpha = 0.22f),
                        selectedLabelColor = CyanPrimary
                    )
                )
            }
        }

        when (subScreenIndex) {
            0 -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Language Mode Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Bilingual Language Mode (Urdu / Roman Urdu / English)",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "OmniVoice automatically detects your language per utterance and replies in the exact same script and language.",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            val modes = listOf(
                                "Auto (Urdu / Roman Urdu / English)",
                                "Urdu Nastaliq (اردو)",
                                "Roman Urdu + Mixed",
                                "English Only"
                            )
                            modes.forEach { mode ->
                                FilterChip(
                                    selected = preferredLanguageMode == mode,
                                    onClick = { viewModel.setPreferredLanguageMode(mode) },
                                    label = { Text(mode, fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                                        selectedLabelColor = CyanPrimary
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. Voice Speed, Pitch & Wake Word Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = VioletSecondary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "TTS Voice Speed, Pitch & Wake Word",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Speech Speed: ${"%.2f".format(config.voiceSpeed)}x",
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                            Slider(
                                value = config.voiceSpeed,
                                onValueChange = { viewModel.updateConfig(config.copy(voiceSpeed = it)) },
                                valueRange = 0.6f..1.6f,
                                colors = SliderDefaults.colors(thumbColor = CyanPrimary, activeTrackColor = CyanPrimary)
                            )

                            Text(
                                text = "Voice Pitch: ${"%.2f".format(config.voicePitch)}x",
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                            Slider(
                                value = config.voicePitch,
                                onValueChange = { viewModel.updateConfig(config.copy(voicePitch = it)) },
                                valueRange = 0.7f..1.4f,
                                colors = SliderDefaults.colors(thumbColor = VioletSecondary, activeTrackColor = VioletSecondary)
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = wakeWordInput,
                                    onValueChange = { wakeWordInput = it },
                                    placeholder = { Text("Wake phrase (e.g. Hey Omni)", fontSize = 12.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CyanPrimary,
                                        unfocusedBorderColor = DarkSurfaceBorder,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (wakeWordInput.isNotBlank()) {
                                            viewModel.updateConfig(config.copy(wakeWord = wakeWordInput.trim()))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                                ) {
                                    Text("Update", color = Color(0xFF003548), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. Appearance Theme Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.DarkMode, contentDescription = null, tint = EmeraldTertiary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Dark Cyber-Assistant Theme", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Toggle between Dark and Light Material 3 palettes", color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                            Switch(
                                checked = isDarkTheme,
                                onCheckedChange = { viewModel.toggleDarkTheme(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 4. Privacy Mode (Offline-Only) & Affective Mood Adaptation Card (F-051, F-097)
                    val context = androidx.compose.ui.platform.LocalContext.current
                    var privacyOfflineOnly by remember {
                        mutableStateOf(com.example.engine.OnDeviceLlmAndModelManager.isPrivacyModeEnabled(context))
                    }
                    val moodAdaptationEnabled by com.example.engine.MoodAnalyzerEngine.moodAdaptationEnabled.collectAsState()
                    val currentMood by com.example.engine.MoodAnalyzerEngine.currentMoodEstimate.collectAsState()
                    var backupStatusMsg by remember { mutableStateOf<String?>(null) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Privacy Mode (100% Offline-Only)",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Blocks all cloud Gemini requests and forces on-device NLU/LLM + local TTS",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = privacyOfflineOnly,
                                    onCheckedChange = {
                                        privacyOfflineOnly = it
                                        com.example.engine.OnDeviceLlmAndModelManager.setPrivacyModeOfflineOnly(context, it)
                                    },
                                    modifier = Modifier.testTag("privacy_mode_offline_switch"),
                                    colors = SwitchDefaults.colors(checkedThumbColor = EmeraldTertiary)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Affective Mood Adaptation (${currentMood.label.displayTitle} ${currentMood.label.emoji})",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Adapts speech speed, pitch, and warmth to your real-time mood",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = moodAdaptationEnabled,
                                    onCheckedChange = { com.example.engine.MoodAnalyzerEngine.setMoodAdaptationEnabled(it) },
                                    modifier = Modifier.testTag("mood_adaptation_switch"),
                                    colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val payload = com.example.engine.EncryptedMemoryBackupManager.exportEncryptedMemoryBackup(context)
                                        backupStatusMsg = "Keystore AES-GCM Backup Ready (${payload.length} bytes)"
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                                ) {
                                    Text("Export Encrypted Backup", color = Color(0xFF003548), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = {
                                        com.example.engine.MoodAnalyzerEngine.clearMoodHistory()
                                        backupStatusMsg = "Mood history cleared."
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceBorder)
                                ) {
                                    Text("Clear Mood History", color = TextPrimary, fontSize = 11.sp)
                                }
                            }

                            if (!backupStatusMsg.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(text = backupStatusMsg!!, color = EmeraldTertiary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            1 -> OrbAndGesturesSettingSubScreen()
            2 -> SecuritySetupScreen(viewModel)
            3 -> PermissionsSetupScreen()
            4 -> DiagnosticsScreen(viewModel)
        }
    }
}

@Composable
private fun OrbAndGesturesSettingSubScreen() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val vaultManager = remember { BiometricVaultManager.getInstance(context) }
    val vault by vaultManager.vaultState.collectAsState()

    var sensitivity by remember(vault.orbDragSensitivity) { mutableFloatStateOf(vault.orbDragSensitivity) }
    val slopDp = vaultManager.calculateSlopThresholdDp(sensitivity)
    val slopPx = with(density) { slopDp.dp.toPx() }
    val speedMultiplier = vaultManager.calculateMovementMultiplier(sensitivity)

    // Practice area interactive orb state
    var practiceOrbX by remember { mutableFloatStateOf(80f) }
    var practiceOrbY by remember { mutableFloatStateOf(60f) }
    var practiceIsDragging by remember { mutableStateOf(false) }
    var practiceTapCount by remember { mutableIntStateOf(0) }
    var practiceStatusMessage by remember { mutableStateOf("Tap or drag the test orb below to test deadzone") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Drag Sensitivity Controller Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Floating Orb Drag Sensitivity",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Prevents accidental triggers while ensuring smooth gliding across the screen",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Deadzone & Responsiveness",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Surface(
                        color = CyanPrimary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "${(sensitivity * 100).roundToInt()}% • ${slopDp.roundToInt()}dp slop",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(
                        "Anti-Accidental" to 0.20f,
                        "Balanced" to 0.50f,
                        "Ultra-Responsive" to 0.85f
                    )
                    presets.forEach { (label, value) ->
                        val isSelected = kotlin.math.abs(sensitivity - value) < 0.15f
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                sensitivity = value
                                vaultManager.setOrbDragSensitivity(value)
                            },
                            label = { Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary.copy(alpha = 0.25f),
                                selectedLabelColor = CyanPrimary,
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) CyanPrimary else DarkSurfaceBorder
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = sensitivity,
                    onValueChange = {
                        sensitivity = it
                        vaultManager.setOrbDragSensitivity(it)
                    },
                    valueRange = 0.10f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = CyanPrimary,
                        activeTrackColor = CyanPrimary,
                        inactiveTrackColor = DarkSurfaceBorder
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("settings_orb_sensitivity_slider")
                )

                // Explanation Banner
                Surface(
                    color = DarkSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DarkSurfaceBorder),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = when {
                                sensitivity < 0.35f -> EmeraldTertiary
                                sensitivity <= 0.70f -> CyanPrimary
                                else -> VioletSecondary
                            },
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                sensitivity < 0.35f -> "Anti-Accidental Mode: High slop threshold (${slopDp.roundToInt()}dp). Requires an intentional, firm drag. Casual grazes and taps will NEVER move the orb."
                                sensitivity <= 0.70f -> "Balanced Mode: Medium slop threshold (${slopDp.roundToInt()}dp). Smooth gliding with instantaneous tap-versus-drag separation."
                                else -> "Ultra-Responsive Mode: Low slop threshold (${slopDp.roundToInt()}dp). Moves with minimal finger displacement for speed."
                            },
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Interactive Sensitivity Test Sandbox
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Interactive Test Sandbox",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Taps: $practiceTapCount",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyanPrimary
                    )
                }

                Text(
                    text = practiceStatusMessage,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )

                // Sandbox Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color(0xFF040A14), RoundedCornerShape(12.dp))
                        .border(1.dp, if (practiceIsDragging) CyanPrimary else DarkSurfaceBorder, RoundedCornerShape(12.dp))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val grid = 30.dp.toPx()
                        var cx = 0f
                        while (cx < w) {
                            drawLine(color = Color(0xFF081828), start = Offset(cx, 0f), end = Offset(cx, h), strokeWidth = 1f)
                            cx += grid
                        }
                        var cy = 0f
                        while (cy < h) {
                            drawLine(color = Color(0xFF081828), start = Offset(0f, cy), end = Offset(w, cy), strokeWidth = 1f)
                            cy += grid
                        }
                    }

                    val previewOrbSizeDp = 64.dp
                    val previewOrbSizePx = with(density) { previewOrbSizeDp.toPx() }

                    // Draggable Sandbox Orb
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(practiceOrbX.roundToInt(), practiceOrbY.roundToInt()) }
                            .size(previewOrbSizeDp)
                            .pointerInput(sensitivity) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var totalDistance = 0f
                                    var isDragging = false

                                    do {
                                        val event = awaitPointerEvent()
                                        val currentChange = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!currentChange.pressed) break

                                        val dragDelta = currentChange.position - currentChange.previousPosition
                                        val distance = dragDelta.getDistance()

                                        if (!isDragging) {
                                            totalDistance += distance
                                            if (totalDistance >= slopPx) {
                                                isDragging = true
                                                practiceIsDragging = true
                                                practiceStatusMessage = "DRAGGING ENGAGED • Slop ($slopDp dp) exceeded"
                                                currentChange.consume()
                                                practiceOrbX = (practiceOrbX + dragDelta.x * speedMultiplier).coerceIn(0f, size.width.toFloat() - previewOrbSizePx)
                                                practiceOrbY = (practiceOrbY + dragDelta.y * speedMultiplier).coerceIn(0f, size.height.toFloat() - previewOrbSizePx)
                                            } else {
                                                practiceStatusMessage = "DEADZONE PROTECTED (${totalDistance.roundToInt()}/$slopPx px) • No movement"
                                            }
                                        } else {
                                            currentChange.consume()
                                            practiceOrbX = (practiceOrbX + dragDelta.x * speedMultiplier).coerceIn(0f, size.width.toFloat() - previewOrbSizePx)
                                            practiceOrbY = (practiceOrbY + dragDelta.y * speedMultiplier).coerceIn(0f, size.height.toFloat() - previewOrbSizePx)
                                        }
                                    } while (true)

                                    val wasDragging = isDragging
                                    practiceIsDragging = false

                                    if (!wasDragging && totalDistance < slopPx) {
                                        practiceTapCount++
                                        practiceStatusMessage = "CLEAN TAP DETECTED • Accidental drag blocked!"
                                    } else if (wasDragging) {
                                        val snapRes = vaultManager.computeMagneticEdgeSnap(
                                            currentX = practiceOrbX,
                                            currentY = practiceOrbY,
                                            maxOffsetX = (size.width.toFloat() - previewOrbSizePx).coerceAtLeast(0f),
                                            maxOffsetY = (size.height.toFloat() - previewOrbSizePx).coerceAtLeast(0f),
                                            edgeMarginPx = 8f,
                                            snapThresholdPx = 56f,
                                            forceHorizontalEdgeOnRelease = true
                                        )
                                        practiceOrbX = snapRes.targetX
                                        practiceOrbY = snapRes.targetY
                                        practiceStatusMessage = "MAGNETIC SNAP [${snapRes.snappedEdge}] at (${practiceOrbX.roundToInt()}, ${practiceOrbY.roundToInt()})"
                                    }
                                }
                            }
                            .testTag("practice_sandbox_orb"),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f
                            val center = Offset(size.width / 2f, size.height / 2f)

                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        CyanPrimary.copy(alpha = if (practiceIsDragging) 0.8f else 0.4f),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = r
                                ),
                                radius = r,
                                center = center
                            )

                            drawCircle(
                                color = CyanPrimary,
                                radius = r * 0.7f,
                                center = center,
                                style = Stroke(width = if (practiceIsDragging) 3f else 2f)
                            )

                            drawCircle(
                                color = Color.White,
                                radius = r * 0.35f,
                                center = center
                            )
                        }

                        Text(
                            text = if (practiceIsDragging) "MOVE" else "TEST",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF040A14)
                        )
                    }
                }
            }
        }
    }
}

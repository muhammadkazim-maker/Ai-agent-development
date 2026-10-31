package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.gemini.GeminiChatMessage
import com.example.engine.gemini.GeminiChatModel
import com.example.engine.gemini.GeminiSkillsEngine
import com.example.engine.gemini.ThinkingLevel
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

private data class SkillSectionTab(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tag: String
)

@Composable
fun AiSkillsHubScreen(viewModel: VoiceAssistantViewModel) {
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    val sections = listOf(
        SkillSectionTab("Chatbot & Grounding", "Argon • 3.8 Flash • Search & Maps", Icons.AutoMirrored.Filled.Chat, "skill_tab_chat"),
        SkillSectionTab("Create & Edit Image", GeminiSkillsEngine.IMAGE_MODEL, Icons.Default.Image, "skill_tab_image"),
        SkillSectionTab("Animate to Video", GeminiSkillsEngine.VIDEO_MODEL, Icons.Default.Movie, "skill_tab_video"),
        SkillSectionTab("Live Voice & STT", "3.8 Live • 3.5 Transcribe", Icons.Default.RecordVoiceOver, "skill_tab_live_voice")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header Banner showcasing all 7 Active Gemini Skills
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Gemini Multimodal AI Studio (7 Active Skills)",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Chatbot (4 Argon / 3.8 Flash / 3.1 Flash-Lite) • Search Grounding • Maps Grounding • Image Studio (nano-banana-2.1) • Video Animator (omni-1.1-flash) • Live Voice (3.8-live) • Audio Transcribe (3.5-transcribe)",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal Skill Section Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sections.forEachIndexed { idx, tab ->
                FilterChip(
                    selected = selectedSection == idx,
                    onClick = { selectedSection = idx },
                    label = {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(tab.title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(tab.subtitle, fontSize = 9.sp, color = TextSecondary)
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.testTag(tab.tag),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                        selectedLabelColor = CyanPrimary,
                        selectedLeadingIconColor = CyanPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
        ) {
            when (selectedSection) {
                0 -> GeminiChatbotAndGroundingPanel(viewModel)
                1 -> ImageStudioPanel(viewModel)
                2 -> VideoAnimatorPanel(viewModel)
                3 -> LiveVoiceAndTranscribePanel(viewModel)
            }
        }
    }
}

// ============================================================================
// MULTI-TURN GEMINI CHATBOT + GOOGLE SEARCH GROUNDING + GOOGLE MAPS GROUNDING
// ============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeminiChatbotAndGroundingPanel(viewModel: VoiceAssistantViewModel) {
    val context = LocalContext.current
    val messages by viewModel.chatMessages.collectAsState()
    val selectedModel by viewModel.selectedChatModel.collectAsState()
    val systemInstruction by viewModel.chatSystemInstruction.collectAsState()
    val enableSearch by viewModel.enableSearchGrounding.collectAsState()
    val enableMaps by viewModel.enableMapsGrounding.collectAsState()
    val thinkingConfig by viewModel.thinkingConfig.collectAsState()
    val isLoading by viewModel.isChatLoading.collectAsState()

    var chatInput by remember { mutableStateOf("") }
    var showRoleCustomizer by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Model Selector Row (gemini-4-argon, gemini-3.8-flash, gemini-3.1-flash-lite)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Select Gemini Model",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = { viewModel.clearChatHistory() },
                    modifier = Modifier.testTag("clear_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Chat History",
                        tint = TextSecondary
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GeminiChatModel.entries.forEach { model ->
                    FilterChip(
                        selected = selectedModel == model,
                        onClick = { viewModel.selectChatModel(model) },
                        label = {
                            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(model.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(model.badge, fontSize = 9.sp)
                            }
                        },
                        modifier = Modifier.testTag("model_chip_${model.modelId}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanPrimary.copy(alpha = 0.22f),
                            selectedLabelColor = CyanPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Google Search Grounding & Google Maps Grounding Switches (gemini-3.8-flash)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Google Search Grounding",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Real-time web facts via ${GeminiSkillsEngine.GROUNDING_MODEL}",
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Switch(
                            checked = enableSearch,
                            onCheckedChange = { viewModel.toggleSearchGrounding(it) },
                            modifier = Modifier.testTag("toggle_search_grounding"),
                            colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                tint = EmeraldTertiary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Google Maps Grounding",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Places, directions & local data via ${GeminiSkillsEngine.GROUNDING_MODEL}",
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Switch(
                            checked = enableMaps,
                            onCheckedChange = { viewModel.toggleMapsGrounding(it) },
                            modifier = Modifier.testTag("toggle_maps_grounding"),
                            colors = SwitchDefaults.colors(checkedThumbColor = EmeraldTertiary)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Multi-Step Thinking Configuration (thinkingConfig)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = VioletSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Multi-Step Thinking (thinkingConfig)",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (thinkingConfig.enabled) {
                                        "Active: ${thinkingConfig.thinkingLevel.displayName} • ${thinkingConfig.thinkingLevel.description}"
                                    } else {
                                        "Disabled • Direct response without intermediate thought steps"
                                    },
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Switch(
                            checked = thinkingConfig.enabled,
                            onCheckedChange = { viewModel.toggleThinkingMode(it) },
                            modifier = Modifier.testTag("toggle_thinking_mode"),
                            colors = SwitchDefaults.colors(checkedThumbColor = VioletSecondary)
                        )
                    }

                    if (thinkingConfig.enabled) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ThinkingLevel.entries.forEach { level ->
                                FilterChip(
                                    selected = thinkingConfig.thinkingLevel == level,
                                    onClick = { viewModel.selectThinkingLevel(level) },
                                    label = {
                                        Text(
                                            text = level.displayName,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    },
                                    modifier = Modifier.testTag("thinking_level_${level.apiName.lowercase()}"),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = VioletSecondary.copy(alpha = 0.25f),
                                        selectedLabelColor = VioletSecondary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Role / System Instruction Customizer Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = { showRoleCustomizer = !showRoleCustomizer },
                    label = {
                        Text(
                            if (showRoleCustomizer) "Hide Role Settings" else "Customize Bot Role / Persona",
                            fontSize = 11.sp
                        )
                    }
                )

                val quickGroundingPrompts = listOf(
                    "Latest tech news today",
                    "Best cafes near Gulberg Lahore",
                    "Plan a 3-step Android architecture"
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickGroundingPrompts.forEach { sample ->
                        AssistChip(
                            onClick = { viewModel.sendStudioChatMessage(sample) },
                            label = { Text(sample, fontSize = 10.sp) }
                        )
                    }
                }
            }

            if (showRoleCustomizer) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = systemInstruction,
                    onValueChange = { viewModel.updateChatSystemInstruction(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("system_instruction_input"),
                    label = { Text("System Instruction / Chatbot Role") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Multi-turn Conversation History List
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                messages.forEach { msg ->
                    ChatMessageBubble(
                        message = msg,
                        onSpeak = { viewModel.speakResponse(msg.text) },
                        onOpenUrl = { url ->
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                        }
                    )
                }

                if (isLoading) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = CyanPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Thinking with ${selectedModel.displayName}...",
                            color = CyanPrimary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Chat Input Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chatbot_message_input"),
                    placeholder = {
                        Text("Ask anything (Search, Maps, Urdu/English)...", fontSize = 13.sp)
                    },
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
                        if (chatInput.isNotBlank()) {
                            viewModel.sendStudioChatMessage(chatInput)
                            chatInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(CyanPrimary, RoundedCornerShape(12.dp))
                        .testTag("chatbot_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send Chat Message",
                        tint = Color(0xFF003548)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageBubble(
    message: GeminiChatMessage,
    onSpeak: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) CyanPrimary.copy(alpha = 0.18f) else DarkSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bubbleColor)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isUser) "You" else "Gemini",
                    color = if (isUser) CyanPrimary else EmeraldTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (message.modelUsed.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = VioletSecondary.copy(alpha = 0.2f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = message.modelUsed,
                            color = VioletSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (!isUser) {
                IconButton(
                    onClick = onSpeak,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Speak Reply",
                        tint = CyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (!isUser && (message.reasoningSteps.isNotEmpty() || message.thoughtSummary.isNotBlank())) {
            var showThoughts by rememberSaveable { mutableStateOf(true) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { showThoughts = !showThoughts }
                    .testTag("thinking_trace_card"),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = VioletSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Multi-Step Reasoning (${message.reasoningSteps.size.coerceAtLeast(1)} steps)",
                                color = VioletSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = if (showThoughts) "Hide" else "Show",
                            color = CyanPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (showThoughts) {
                        Spacer(modifier = Modifier.height(6.dp))
                        if (message.reasoningSteps.isNotEmpty()) {
                            message.reasoningSteps.forEachIndexed { idx, step ->
                                Text(
                                    text = "${idx + 1}. $step",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        } else {
                            Text(
                                text = message.thoughtSummary,
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = message.text,
            color = TextPrimary,
            fontSize = 13.sp
        )

        if (message.citations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Grounded Sources (Google Search & Maps):",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                message.citations.forEach { citation ->
                    AssistChip(
                        onClick = { onOpenUrl(citation.uri) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (citation.isMapsPlace) Icons.Default.Place else Icons.Default.Public,
                                contentDescription = null,
                                tint = if (citation.isMapsPlace) EmeraldTertiary else CyanPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        label = {
                            Text(
                                text = citation.title.take(36),
                                fontSize = 10.sp,
                                color = TextPrimary
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = DarkSurface
                        )
                    )
                }
            }
        }
    }
}

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VoiceAction
import com.example.engine.VoiceRoutine
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

private val AVAILABLE_TOOL_CALLS = listOf(
    "set_alarm" to "07:00",
    "toggle_flashlight" to "on",
    "adjust_volume" to "up",
    "read_notifications" to "all",
    "open_app" to "WhatsApp",
    "send_sms" to "Mom",
    "make_call" to "Ali",
    "find_phone" to "alert",
    "lock_device" to "screen",
    "general_chat" to "Routine completed"
)

@Composable
fun RoutinesScreen(viewModel: VoiceAssistantViewModel) {
    val routines by viewModel.routineManager.routines.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var newTriggerPhrase by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    var selectedToolName by remember { mutableStateOf(AVAILABLE_TOOL_CALLS.first().first) }
    var toolTargetInput by remember { mutableStateOf(AVAILABLE_TOOL_CALLS.first().second) }
    var stagedActions by remember {
        mutableStateOf(
            listOf(
                VoiceAction(action = "adjust_volume", target = "up"),
                VoiceAction(action = "read_notifications", target = "all")
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
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(VioletSecondary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoMode,
                            contentDescription = null,
                            tint = VioletSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "VOICE AUTOMATION ROUTINES (ROOM DB)",
                            color = VioletSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Group multiple tool calls into a single spoken trigger phrase",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        items(routines, key = { it.id }) { routine ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .padding(vertical = 5.dp)
                    .testTag("routine_card_${routine.id}"),
                shape = RoundedCornerShape(14.dp),
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
                                text = "“${routine.triggerPhrase}”",
                                color = CyanPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (routine.executionCount > 0) {
                                Text(
                                    text = "Executed ${routine.executionCount} time(s)",
                                    color = EmeraldTertiary,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { viewModel.submitTextCommand(routine.triggerPhrase) },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("run_routine_${routine.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Run Routine",
                                    tint = EmeraldTertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (routine.id.startsWith("custom_")) {
                                IconButton(
                                    onClick = { viewModel.routineManager.deleteRoutine(routine.id) },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("delete_routine_${routine.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Routine",
                                        tint = Color(0xFFF87171),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Switch(
                                checked = routine.isEnabled,
                                onCheckedChange = { viewModel.routineManager.toggleRoutine(routine.id) },
                                modifier = Modifier.testTag("toggle_routine_${routine.id}"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CyanPrimary,
                                    checkedTrackColor = CyanPrimary.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = routine.description,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${routine.actions.size} tool call(s) chained: ${
                            routine.actions.joinToString(" → ") {
                                if (it.target.isNotBlank()) "${it.action}(${it.target})" else it.action
                            }
                        }",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            if (!showAddDialog) {
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                        .testTag("add_routine_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Create Custom Voice Routine", color = CyanPrimary, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "New Multi-Tool Voice Routine",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newTriggerPhrase,
                            onValueChange = { newTriggerPhrase = it },
                            placeholder = { Text("Spoken trigger phrase (e.g. 'workout mode')", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("routine_trigger_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newDescription,
                            onValueChange = { newDescription = it },
                            placeholder = { Text("Description (e.g. 'Turns volume up and reads notifications')", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("routine_description_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Select Tool Call to Add:",
                            color = VioletSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AVAILABLE_TOOL_CALLS.forEach { (tool, defaultTarget) ->
                                FilterChip(
                                    selected = selectedToolName == tool,
                                    onClick = {
                                        selectedToolName = tool
                                        toolTargetInput = defaultTarget
                                    },
                                    label = { Text(tool, fontSize = 11.sp) },
                                    modifier = Modifier.testTag("tool_chip_$tool"),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                                        selectedLabelColor = CyanPrimary
                                    )
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = toolTargetInput,
                                onValueChange = { toolTargetInput = it },
                                placeholder = { Text("Tool argument / target", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("routine_tool_target_input"),
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
                                    stagedActions = stagedActions + VoiceAction(
                                        action = selectedToolName,
                                        target = toolTargetInput.trim()
                                    )
                                },
                                modifier = Modifier.testTag("add_tool_step_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = VioletSecondary)
                            ) {
                                Text("+ Step", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Staged Tool Calls (${stagedActions.size}):",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        stagedActions.forEachIndexed { idx, step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${idx + 1}. ${step.action}(${step.target})",
                                    color = CyanPrimary,
                                    fontSize = 12.sp
                                )
                                if (stagedActions.size > 1) {
                                    IconButton(
                                        onClick = {
                                            stagedActions = stagedActions.filterIndexed { i, _ -> i != idx }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Remove Step",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = { showAddDialog = false },
                                modifier = Modifier.testTag("cancel_routine_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceBorder)
                            ) {
                                Text("Cancel", color = TextSecondary)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (newTriggerPhrase.isNotBlank() && stagedActions.isNotEmpty()) {
                                        val custom = VoiceRoutine(
                                            id = "custom_${System.currentTimeMillis()}",
                                            triggerPhrase = newTriggerPhrase.lowercase().trim(),
                                            description = newDescription.ifBlank {
                                                "Runs ${stagedActions.size} tool calls"
                                            },
                                            actions = stagedActions,
                                            confirmationMessage = "Custom routine '$newTriggerPhrase' executed."
                                        )
                                        viewModel.routineManager.addCustomRoutine(custom)
                                        showAddDialog = false
                                        newTriggerPhrase = ""
                                        newDescription = ""
                                    }
                                },
                                modifier = Modifier.testTag("save_routine_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                            ) {
                                Text("Save Routine", color = Color(0xFF003548), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

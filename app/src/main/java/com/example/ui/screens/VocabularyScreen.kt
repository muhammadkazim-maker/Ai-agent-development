package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletSecondary
import com.example.ui.theme.fontFamilyForBilingualText
import com.example.ui.theme.textDirectionForBilingualText
import com.example.ui.viewmodel.VoiceAssistantViewModel

@Composable
fun VocabularyScreen(viewModel: VoiceAssistantViewModel) {
    val personalVocab by viewModel.personalVocabulary.collectAsState()
    val userMemories by viewModel.userMemories.collectAsState()
    val failedLogs by viewModel.failedLogs.collectAsState()
    val benchmarkSummary by viewModel.benchmarkSummary.collectAsState()

    var activeSection by remember { mutableIntStateOf(0) } // 0=Teach Me Vocab, 1=Memory & Rules, 2=Self-Learning Log, 3=200+ Benchmark
    var spokenPhraseInput by remember { mutableStateOf("") }
    var meaningInput by remember { mutableStateOf("") }
    var urduScriptInput by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("custom_command") }

    var memoryKeyInput by remember { mutableStateOf("") }
    var memoryValInput by remember { mutableStateOf("") }

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
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(CyanPrimary.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "URDU + ROMAN URDU + ENGLISH TRAINING HUB",
                                color = CyanPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "Personal Dictionary • Long-Term Memory • Self-Learning • 210+ Benchmark",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val tabs = listOf(
                            "Teach Me (${personalVocab.size})",
                            "Memory (${userMemories.size})",
                            "Self-Learn (${failedLogs.size})",
                            "210+ Tests"
                        )
                        tabs.forEachIndexed { index, label ->
                            FilterChip(
                                selected = activeSection == index,
                                onClick = { activeSection = index },
                                label = { Text(label, fontSize = 10.sp) },
                                modifier = Modifier.testTag("vocab_section_$index"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanPrimary.copy(alpha = 0.22f),
                                    selectedLabelColor = CyanPrimary
                                )
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // SECTION 0: TEACH ME PERSONAL VOCABULARY
        if (activeSection == 0) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Teach OmniVoice a New Word or Phrase",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Or speak aloud: “Yeh word yaad kar lo: batti jalao ka matlab turn on flashlight”",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = spokenPhraseInput,
                            onValueChange = { spokenPhraseInput = it },
                            placeholder = { Text("Spoken phrase (e.g. 'batti jalao' or 'chachu')", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("teach_phrase_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = meaningInput,
                            onValueChange = { meaningInput = it },
                            placeholder = { Text("Canonical meaning / target (e.g. 'turn on flashlight' or 'Uncle Tariq')", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("teach_meaning_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = urduScriptInput,
                            onValueChange = { urduScriptInput = it },
                            placeholder = { Text("Optional Urdu Nastaliq script (e.g. بتی جلاؤ)", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = DarkSurfaceBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("custom_command" to "Command Synonym", "contact_nickname" to "Contact Nickname").forEach { (cat, title) ->
                                FilterChip(
                                    selected = selectedCategory == cat,
                                    onClick = { selectedCategory = cat },
                                    label = { Text(title, fontSize = 11.sp) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (spokenPhraseInput.isNotBlank() && meaningInput.isNotBlank()) {
                                    viewModel.teachPersonalVocabularyFromUi(
                                        spokenPhrase = spokenPhraseInput,
                                        canonicalMeaning = meaningInput,
                                        urduScript = urduScriptInput,
                                        category = selectedCategory
                                    )
                                    spokenPhraseInput = ""
                                    meaningInput = ""
                                    urduScriptInput = ""
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("save_taught_word_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF003548), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save to Personal Dictionary", color = Color(0xFF003548), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            items(personalVocab, key = { it.id }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "“${item.spokenPhrase}”",
                                    color = CyanPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (item.urduScript.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = item.urduScript,
                                        color = EmeraldTertiary,
                                        fontSize = 14.sp,
                                        fontFamily = fontFamilyForBilingualText(item.urduScript),
                                        style = TextStyle(textDirection = textDirectionForBilingualText(item.urduScript))
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "→ ${item.canonicalMeaning} (${item.category})",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Row {
                            IconButton(onClick = { viewModel.submitTextCommand(item.spokenPhrase) }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Test Phrase", tint = EmeraldTertiary)
                            }
                            IconButton(onClick = { viewModel.deletePersonalVocabulary(item.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF87171))
                            }
                        }
                    }
                }
            }
        }

        // SECTION 1: LONG-TERM MEMORY & CONDITIONAL RULES
        if (activeSection == 1) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = VioletSecondary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Long-Term Memory & Conditional Rules (Room DB)",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Stores your name, contact nicknames, preferences, and conditional rules (e.g. 'If Ali calls -> reject and SMS').",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = memoryKeyInput,
                                onValueChange = { memoryKeyInput = it },
                                placeholder = { Text("Key (e.g. nickname:bhai)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary,
                                    unfocusedBorderColor = DarkSurfaceBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )
                            OutlinedTextField(
                                value = memoryValInput,
                                onValueChange = { memoryValInput = it },
                                placeholder = { Text("Value (e.g. Bilal Khan)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary,
                                    unfocusedBorderColor = DarkSurfaceBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (memoryKeyInput.isNotBlank() && memoryValInput.isNotBlank()) {
                                    viewModel.saveUserMemoryFromUi(memoryKeyInput, memoryValInput)
                                    memoryKeyInput = ""
                                    memoryValInput = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = VioletSecondary)
                        ) {
                            Text("Save Memory Entry", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            items(userMemories, key = { it.id }) { mem ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp)
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mem.memoryKey,
                                color = VioletSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = mem.memoryValue,
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Type: ${mem.memoryType}",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                        IconButton(onClick = { viewModel.deleteUserMemory(mem.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Memory", tint = Color(0xFFF87171))
                        }
                    }
                }
            }
        }

        // SECTION 2: SELF-LEARNING FAILED COMMAND LOG
        if (activeSection == 2) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = EmeraldTertiary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Self-Learning Command Review Log",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Commands that fell back to general chat or required correction are logged here. Promote any phrase to your Personal Dictionary in one tap.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (failedLogs.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldTertiary, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Zero unrecognized commands!", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("All recent Urdu, Roman Urdu, and English commands matched cleanly.", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                items(failedLogs, key = { it.id }) { log ->
                    var fixMeaning by remember { mutableStateOf(log.suggestedCorrection.ifBlank { "turn on flashlight" }) }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp)
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Utterance: “${log.rawUtterance}” (${log.detectedLanguage})",
                                color = CyanPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Status: ${if (log.resolved) "Promoted to Dictionary" else log.failureReason}",
                                color = if (log.resolved) EmeraldTertiary else Color(0xFFFBBF24),
                                fontSize = 11.sp
                            )
                            if (!log.resolved) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = fixMeaning,
                                        onValueChange = { fixMeaning = it },
                                        placeholder = { Text("Map to canonical command...", fontSize = 11.sp) },
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
                                            viewModel.promoteFailedCommand(log.id, log.rawUtterance, fixMeaning)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary)
                                    ) {
                                        Text("Teach", color = Color(0xFF003823), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // SECTION 3: 200+ BILINGUAL COMMAND BENCHMARK RUNNER
        if (activeSection == 3) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.School, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "210+ Bilingual Command Test Suite",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Automated evaluation across 210+ English, Urdu Nastaliq (اردو), Roman Urdu, Code-Mixed, Chained, and Conditional commands. Verifies intent, extracted parameters, and reply language.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.runBilingual200Benchmark() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("run_benchmark_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF003548), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Run 210+ Bilingual Command Benchmark", color = Color(0xFF003548), fontWeight = FontWeight.Bold)
                        }

                        val summary = benchmarkSummary
                        if (summary != null) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Passed: ${summary.passedCases} / ${summary.totalCases}",
                                    color = EmeraldTertiary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Overall Accuracy: ${"%.1f".format(summary.overallAccuracyPercent)}%",
                                    color = CyanPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (summary.overallAccuracyPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape),
                                color = EmeraldTertiary,
                                trackColor = DarkSurfaceBorder
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Intent: ${"%.1f".format(summary.intentAccuracyPercent)}% • Params: ${"%.1f".format(summary.paramAccuracyPercent)}% • Reply Language Match: ${"%.1f".format(summary.languageMatchPercent)}%",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            summary.resultsByCategory.forEach { (cat, pct) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(cat, color = TextPrimary, fontSize = 11.sp)
                                    Text("${"%.0f".format(pct)}%", color = EmeraldTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

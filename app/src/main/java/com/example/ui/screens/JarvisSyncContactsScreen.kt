package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.engine.JarvisHandsFreeEngine
import com.example.engine.SmartContactEntry

private val SyncBg = Color(0xFF040812)
private val SyncCard = Color(0xFF0B1324)
private val SyncBorder = Color(0xFF1E3A5F)
private val SyncCyan = Color(0xFF00F0FF)
private val SyncEmerald = Color(0xFF10B981)
private val SyncAmber = Color(0xFFFBBF24)
private val SyncTextPrimary = Color(0xFFF8FAFC)
private val SyncTextSecondary = Color(0xFF94A3B8)

/**
 * Dedicated 'Sync Contacts' Screen UI:
 * - Uses the Android Contact Picker API (`ActivityResultContracts.PickContact()`) and Multi-Select Device Contacts Picker
 *   to select single or multiple contacts.
 * - Formats and saves them into `jarvis_phone_contacts` (`localStorage.setItem('jarvis_phone_contacts', ...)` & SharedPreferences)
 *   with `normalizedName`, `fuzzyTokens`, `strippedTokens`, and international WhatsApp numbers (`03XXXXXXXXX` -> `923XXXXXXXXX`).
 * - Includes an interactive Fuzzy Voice Command Matcher Playground so users can test how spoken names resolve.
 */
@Composable
fun JarvisSyncContactsScreenModal(
    savedContacts: List<SmartContactEntry>,
    onDismiss: () -> Unit,
    onRefreshSyncedContacts: () -> Unit,
    onImportVcfText: (String) -> Unit,
    onDeleteContact: (Long) -> Unit
) {
    BackHandler { onDismiss() }
    val context = LocalContext.current

    var statusBanner by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableStateOf(0) } // 0 = Contact Picker & Multi-Select, 1 = Fuzzy Matcher Test, 2 = jarvis_phone_contacts JSON
    var keepPickingMultiple by remember { mutableStateOf(false) }

    // Device contacts available for multi-select
    var deviceContacts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val selectedDeviceKeys = remember { mutableStateListOf<String>() }
    var searchFilter by remember { mutableStateOf("") }

    // Manual quick-add inputs
    var manualName by remember { mutableStateOf("") }
    var manualPhone by remember { mutableStateOf("") }

    // Fuzzy voice test query
    var fuzzyTestQuery by remember { mutableStateOf("sir wajahat") }
    var rawJsonText by remember(savedContacts) {
        mutableStateOf(JarvisHandsFreeEngine.getJarvisPhoneContactsJson(context))
    }

    // 1. Official Android System Contact Picker API Launcher (`ActivityResultContracts.PickContact()`)
    var pickContactLauncherRef: (() -> Unit)? = null
    val pickContactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri: Uri? ->
        if (contactUri != null) {
            val imported = JarvisHandsFreeEngine.importFromContactPickerUri(context, contactUri)
            onRefreshSyncedContacts()
            rawJsonText = JarvisHandsFreeEngine.getJarvisPhoneContactsJson(context)
            statusBanner = if (imported != null) {
                "Picked '${imported.name}' (+${imported.whatsappNumber}) → Saved to jarvis_phone_contacts!"
            } else {
                "Contact picked. Grant READ_CONTACTS to extract all phone numbers automatically."
            }
            if (keepPickingMultiple) {
                pickContactLauncherRef?.invoke()
            }
        } else {
            keepPickingMultiple = false
        }
    }
    pickContactLauncherRef = { pickContactLauncher.launch(null) }

    // 2. Permission Launcher for READ_CONTACTS (Multi-Select Device Contacts + Full Sync)
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val loaded = JarvisHandsFreeEngine.queryDeviceContactsForMultiPicker(context)
            deviceContacts = loaded
            selectedDeviceKeys.clear()
            selectedDeviceKeys.addAll(loaded.map { "${it.first}|${it.second}" })
            val (syncedCount, _) = JarvisHandsFreeEngine.syncAndroidSystemContacts(context)
            onRefreshSyncedContacts()
            rawJsonText = JarvisHandsFreeEngine.getJarvisPhoneContactsJson(context)
            statusBanner = "Synced $syncedCount contacts into jarvis_phone_contacts!"
        } else {
            // Even without READ_CONTACTS, Android's system PickContact intent works for single-contact picking
            pickContactLauncher.launch(null)
        }
    }

    // 3. .VCF vCard File Picker Launcher
    val vcfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val vcf = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (vcf.isNotBlank()) {
                    onImportVcfText(vcf)
                    onRefreshSyncedContacts()
                    rawJsonText = JarvisHandsFreeEngine.getJarvisPhoneContactsJson(context)
                    statusBanner = "Imported .vcf file into jarvis_phone_contacts!"
                }
            } catch (e: Exception) {
                statusBanner = "Failed reading .vcf: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val loaded = JarvisHandsFreeEngine.queryDeviceContactsForMultiPicker(context)
            deviceContacts = loaded
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(SyncBg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .testTag("jarvis_sync_contacts_screen"),
            color = SyncBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Top Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "📇 Sync Contacts (jarvis_phone_contacts)",
                            color = SyncCyan,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "${savedContacts.size} contacts formatted for fuzzy voice matching (03xx → 923xx)",
                            color = SyncTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_sync_contacts_screen_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Sync Contacts Screen",
                            tint = SyncTextPrimary
                        )
                    }
                }

                // Status Banner
                AnimatedVisibility(visible = !statusBanner.isNullOrBlank()) {
                    Surface(
                        color = SyncEmerald.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, SyncEmerald),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusBanner.orEmpty(),
                            color = SyncEmerald,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // Primary Action Row: Android Contact Picker API + 1-Tap Full Sync + .VCF Import
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            keepPickingMultiple = false
                            pickContactLauncher.launch(null)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SyncCyan,
                            contentColor = SyncBg
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("launch_android_contact_picker_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Contacts,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pick Contact", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_CONTACTS
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                val loaded = JarvisHandsFreeEngine.queryDeviceContactsForMultiPicker(context)
                                deviceContacts = loaded
                                val (count, _) = JarvisHandsFreeEngine.syncAndroidSystemContacts(context)
                                onRefreshSyncedContacts()
                                rawJsonText = JarvisHandsFreeEngine.getJarvisPhoneContactsJson(context)
                                statusBanner = "Synced $count device contacts into jarvis_phone_contacts!"
                            } else {
                                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SyncEmerald,
                            contentColor = SyncBg
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sync_all_device_contacts_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync All Phone", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { vcfPickerLauncher.launch("*/*") },
                        border = BorderStroke(1.dp, SyncCyan),
                        modifier = Modifier.testTag("import_vcf_contacts_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = null,
                            tint = SyncCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(".VCF", color = SyncCyan, fontSize = 12.sp)
                    }
                }

                // Mode Tabs:
                // 0: Multi-Select & Saved Contacts
                // 1: Fuzzy Voice Match Tester
                // 2: jarvis_phone_contacts JSON
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val tabs = listOf(
                        "Contacts (${savedContacts.size})",
                        "Fuzzy Voice Test",
                        "localStorage JSON"
                    )
                    tabs.forEachIndexed { idx, title ->
                        val selected = activeTab == idx
                        Surface(
                            color = if (selected) SyncCyan.copy(alpha = 0.2f) else SyncCard,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (selected) SyncCyan else SyncBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { activeTab = idx }
                                .testTag("sync_contacts_tab_$idx")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)
                            ) {
                                Text(
                                    text = title,
                                    color = if (selected) SyncCyan else SyncTextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                when (activeTab) {
                    0 -> {
                        // Quick Add Contact + Multi-Select Device Contacts + Saved jarvis_phone_contacts List
                        Surface(
                            color = SyncCard,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SyncBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Add or Format Contact for Voice Commands",
                                    color = SyncCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = manualName,
                                        onValueChange = { manualName = it },
                                        placeholder = { Text("Name (e.g. Sir Wajahat)", fontSize = 11.sp) },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("sync_screen_name_input"),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = SyncCyan,
                                            unfocusedBorderColor = SyncBorder,
                                            focusedTextColor = SyncTextPrimary,
                                            unfocusedTextColor = SyncTextPrimary
                                        )
                                    )
                                    OutlinedTextField(
                                        value = manualPhone,
                                        onValueChange = { manualPhone = it },
                                        placeholder = { Text("03001234567", fontSize = 11.sp) },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("sync_screen_phone_input"),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = SyncCyan,
                                            unfocusedBorderColor = SyncBorder,
                                            focusedTextColor = SyncTextPrimary,
                                            unfocusedTextColor = SyncTextPrimary
                                        )
                                    )
                                    Button(
                                        onClick = {
                                            if (manualName.isNotBlank() && manualPhone.isNotBlank()) {
                                                val saved = JarvisHandsFreeEngine.saveContactByVoice(
                                                    context = context,
                                                    name = manualName.trim(),
                                                    phoneNumber = manualPhone.trim()
                                                )
                                                manualName = ""
                                                manualPhone = ""
                                                onRefreshSyncedContacts()
                                                rawJsonText = JarvisHandsFreeEngine.getJarvisPhoneContactsJson(context)
                                                statusBanner = "Saved '${saved.name}' (+${saved.whatsappNumber}) to jarvis_phone_contacts!"
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SyncCyan,
                                            contentColor = SyncBg
                                        ),
                                        modifier = Modifier.testTag("sync_screen_save_contact_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Save", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Multi-Select Device Contacts Picker (when device contacts are present)
                        if (deviceContacts.isNotEmpty()) {
                            Surface(
                                color = SyncCard,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, SyncCyan.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Multi-Select Phone Contacts (${selectedDeviceKeys.size}/${deviceContacts.size} selected)",
                                            color = SyncCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = if (selectedDeviceKeys.size == deviceContacts.size) "Clear All" else "Select All",
                                                color = SyncAmber,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable {
                                                    if (selectedDeviceKeys.size == deviceContacts.size) {
                                                        selectedDeviceKeys.clear()
                                                    } else {
                                                        selectedDeviceKeys.clear()
                                                        selectedDeviceKeys.addAll(deviceContacts.map { "${it.first}|${it.second}" })
                                                    }
                                                }
                                            )
                                            Text(
                                                text = "Import Selected",
                                                color = SyncEmerald,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier
                                                    .clickable {
                                                        val toImport = deviceContacts.filter {
                                                            selectedDeviceKeys.contains("${it.first}|${it.second}")
                                                        }
                                                        JarvisHandsFreeEngine.importSelectedContactsBatch(context, toImport)
                                                        onRefreshSyncedContacts()
                                                        rawJsonText = JarvisHandsFreeEngine.getJarvisPhoneContactsJson(context)
                                                        statusBanner = "Imported ${toImport.size} selected contacts into jarvis_phone_contacts!"
                                                    }
                                                    .testTag("import_selected_batch_button")
                                            )
                                        }
                                    }

                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 130.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(deviceContacts, key = { "${it.first}|${it.second}" }) { pair ->
                                            val key = "${pair.first}|${pair.second}"
                                            val isChecked = selectedDeviceKeys.contains(key)
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        if (isChecked) selectedDeviceKeys.remove(key) else selectedDeviceKeys.add(key)
                                                    }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Checkbox(
                                                        checked = isChecked,
                                                        onCheckedChange = { checked ->
                                                            if (checked) selectedDeviceKeys.add(key) else selectedDeviceKeys.remove(key)
                                                        },
                                                        colors = CheckboxDefaults.colors(
                                                            checkedColor = SyncCyan,
                                                            checkmarkColor = SyncBg
                                                        )
                                                    )
                                                    Text(
                                                        text = pair.first,
                                                        color = SyncTextPrimary,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                                Text(
                                                    text = "+${pair.second}",
                                                    color = SyncEmerald,
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Search Filter + Saved `jarvis_phone_contacts` List
                        OutlinedTextField(
                            value = searchFilter,
                            onValueChange = { searchFilter = it },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = SyncCyan
                                )
                            },
                            placeholder = { Text("Search synced contacts in jarvis_phone_contacts...", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sync_contacts_search_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SyncCyan,
                                unfocusedBorderColor = SyncBorder,
                                focusedTextColor = SyncTextPrimary,
                                unfocusedTextColor = SyncTextPrimary
                            )
                        )

                        val filteredSaved = remember(savedContacts, searchFilter) {
                            if (searchFilter.isBlank()) {
                                savedContacts
                            } else {
                                val q = searchFilter.trim().lowercase()
                                savedContacts.filter {
                                    it.name.lowercase().contains(q) ||
                                        it.whatsappNumber.contains(q) ||
                                        it.phoneNumber.contains(q)
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 12.dp)
                        ) {
                            items(filteredSaved, key = { it.id }) { contact ->
                                val intlPhone = JarvisHandsFreeEngine.formatInternationalWhatsAppNumber(
                                    contact.whatsappNumber.ifBlank { contact.phoneNumber }
                                )
                                val fuzzyTokens = contact.name
                                    .lowercase()
                                    .replace(Regex("[^a-z0-9\\s]"), " ")
                                    .split(Regex("\\s+"))
                                    .filter { it.isNotBlank() }

                                Surface(
                                    color = SyncCard,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, SyncBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = contact.name,
                                                color = SyncTextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "WhatsApp / Intl: +$intlPhone",
                                                color = SyncEmerald,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = "Fuzzy Voice Tokens: ${fuzzyTokens.joinToString(", ") { "\"$it\"" }}",
                                                color = SyncTextSecondary,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                onDeleteContact(contact.id)
                                                rawJsonText = JarvisHandsFreeEngine.getJarvisPhoneContactsJson(context)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete Contact",
                                                tint = SyncTextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // Tab 1: Interactive Fuzzy Voice Command Matcher Playground
                        val matchResult = remember(fuzzyTestQuery, savedContacts) {
                            JarvisHandsFreeEngine.resolveContactFuzzy(context, fuzzyTestQuery)
                        }

                        Surface(
                            color = SyncCard,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, SyncCyan),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Fuzzy Voice Name Resolution Tester",
                                    color = SyncCyan,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Test how spoken names in voice commands (e.g. 'sir wajahat', 'wajahat sir', 'muhammad ali', 'ali bhai') match against jarvis_phone_contacts:",
                                    color = SyncTextSecondary,
                                    fontSize = 12.sp
                                )
                                OutlinedTextField(
                                    value = fuzzyTestQuery,
                                    onValueChange = { fuzzyTestQuery = it },
                                    label = { Text("Spoken Contact Name in Voice Command") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("fuzzy_matcher_test_input"),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = SyncCyan,
                                        unfocusedBorderColor = SyncBorder,
                                        focusedTextColor = SyncTextPrimary,
                                        unfocusedTextColor = SyncTextPrimary
                                    )
                                )

                                Surface(
                                    color = if (matchResult.matchedContact != null) {
                                        SyncEmerald.copy(alpha = 0.14f)
                                    } else {
                                        SyncAmber.copy(alpha = 0.14f)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (matchResult.matchedContact != null) SyncEmerald else SyncAmber
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (matchResult.matchedContact != null) {
                                            Text(
                                                text = "MATCHED: ${matchResult.matchedContact.name}",
                                                color = SyncEmerald,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Text(
                                                text = "Formatted WhatsApp Number: +${matchResult.formattedInternationalPhone}",
                                                color = SyncTextPrimary,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = "Algorithm: ${matchResult.matchReason} • Score: ${(matchResult.score * 100).toInt()}%",
                                                color = SyncCyan,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        } else {
                                            Text(
                                                text = "ERROR_CONTACT_NOT_FOUND (contactMatched = null)",
                                                color = SyncAmber,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Strict Honesty Rule Active: JARVIS will NOT claim the message was sent and will ask you to sync or speak the number.",
                                                color = SyncTextSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        // Tab 2: Raw `jarvis_phone_contacts` localStorage JSON Inspector & Importer
                        Surface(
                            color = SyncCard,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, SyncBorder),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "localStorage.getItem('jarvis_phone_contacts')",
                                    color = SyncCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                OutlinedTextField(
                                    value = rawJsonText,
                                    onValueChange = { rawJsonText = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .testTag("jarvis_phone_contacts_json_editor"),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = SyncCyan,
                                        unfocusedBorderColor = SyncBorder,
                                        focusedTextColor = SyncTextPrimary,
                                        unfocusedTextColor = SyncTextPrimary
                                    )
                                )
                                Button(
                                    onClick = {
                                        val updated = JarvisHandsFreeEngine.importJarvisPhoneContactsJson(context, rawJsonText)
                                        onRefreshSyncedContacts()
                                        rawJsonText = JarvisHandsFreeEngine.getJarvisPhoneContactsJson(context)
                                        statusBanner = "Saved ${updated.size} contacts to jarvis_phone_contacts!"
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SyncEmerald,
                                        contentColor = SyncBg
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("save_jarvis_phone_contacts_json_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save to jarvis_phone_contacts", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

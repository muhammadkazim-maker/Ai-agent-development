package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.example.engine.SmartContactAndAppRegistry
import com.example.engine.SmartContactEntry

private val BgDeepNight = Color(0xFF060A14)
private val CardSurface = Color(0xFF0E1526)
private val CardHighlight = Color(0xFF141E36)
private val CardBorder = Color(0xFF233252)
private val AccentCyan = Color(0xFF00F0FF)
private val AccentViolet = Color(0xFF8B5CF6)
private val AccentEmerald = Color(0xFF10B981)
private val AccentAmber = Color(0xFFF59E0B)
private val AccentCoral = Color(0xFFEF4444)
private val TextMain = Color(0xFFF8FAFC)
private val TextSub = Color(0xFF94A3B8)

/**
 * Full-screen Modal wrapper for managing `omni_contacts_db` in `localStorage` / persistent storage.
 */
@Composable
fun OmniContactsManagerModal(
    contacts: List<SmartContactEntry>,
    rawJsonProvider: () -> String,
    onDismiss: () -> Unit,
    onSaveNewContact: (
        name: String,
        phoneNumber: String,
        whatsappNumber: String,
        instagramHandle: String,
        facebookProfile: String,
        tiktokHandle: String
    ) -> Unit,
    onUpdateExistingContact: (
        contactId: Long,
        name: String,
        phoneNumber: String,
        whatsappNumber: String,
        instagramHandle: String,
        facebookProfile: String,
        tiktokHandle: String
    ) -> Unit,
    onDeleteContact: (Long) -> Unit,
    onImportRawJson: (String) -> Unit,
    onQuickVoiceCommand: (String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        OmniContactsManagerScreen(
            contacts = contacts,
            rawJsonProvider = rawJsonProvider,
            onClose = onDismiss,
            onSaveNewContact = onSaveNewContact,
            onUpdateExistingContact = onUpdateExistingContact,
            onDeleteContact = onDeleteContact,
            onImportRawJson = onImportRawJson,
            onQuickVoiceCommand = onQuickVoiceCommand
        )
    }
}

/**
 * Dedicated Screen for viewing, searching, adding, editing, and syncing contacts in `omni_contacts_db`.
 * Supports Phone numbers, WhatsApp numbers (with automatic `03...` -> `923...` formatting preview),
 * Instagram handles, Facebook profiles, TikTok handles, and direct `localStorage['omni_contacts_db']` JSON inspection.
 */
@Composable
fun OmniContactsManagerScreen(
    contacts: List<SmartContactEntry>,
    rawJsonProvider: () -> String,
    onClose: () -> Unit,
    onSaveNewContact: (
        name: String,
        phoneNumber: String,
        whatsappNumber: String,
        instagramHandle: String,
        facebookProfile: String,
        tiktokHandle: String
    ) -> Unit,
    onUpdateExistingContact: (
        contactId: Long,
        name: String,
        phoneNumber: String,
        whatsappNumber: String,
        instagramHandle: String,
        facebookProfile: String,
        tiktokHandle: String
    ) -> Unit,
    onDeleteContact: (Long) -> Unit,
    onImportRawJson: (String) -> Unit,
    onQuickVoiceCommand: (String) -> Unit
) {
    BackHandler {
        onClose()
    }

    val context = LocalContext.current

    // Search & platform filter states
    var searchQuery by remember { mutableStateOf("") }
    var selectedPlatformFilter by remember { mutableStateOf("ALL") }

    // Editor form state (null editingContactId = Adding new contact; non-null = Editing existing contact)
    var editingContactId by remember { mutableStateOf<Long?>(null) }
    var isEditorExpanded by remember { mutableStateOf(true) }

    var nameInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }
    var whatsappInput by remember { mutableStateOf("") }
    var instagramInput by remember { mutableStateOf("") }
    var facebookInput by remember { mutableStateOf("") }
    var tiktokInput by remember { mutableStateOf("") }
    var statusBannerText by remember { mutableStateOf<String?>(null) }

    // Raw localStorage['omni_contacts_db'] JSON editor state
    var showRawJsonEditor by remember { mutableStateOf(false) }
    var rawJsonText by remember(contacts, showRawJsonEditor) {
        mutableStateOf(rawJsonProvider())
    }

    fun clearAndResetForm() {
        editingContactId = null
        nameInput = ""
        phoneInput = ""
        whatsappInput = ""
        instagramInput = ""
        facebookInput = ""
        tiktokInput = ""
    }

    fun startEditingContact(contact: SmartContactEntry) {
        editingContactId = contact.id
        nameInput = contact.name
        phoneInput = contact.phoneNumber
        whatsappInput = contact.whatsappNumber.ifBlank { contact.phoneNumber }
        instagramInput = contact.instagramHandle
        facebookInput = contact.facebookProfile
        tiktokInput = contact.tiktokHandle
        isEditorExpanded = true
        statusBannerText = "Editing '${contact.name}' — update any number or handle below and tap Update."
    }

    val filteredContacts = remember(contacts, searchQuery, selectedPlatformFilter) {
        contacts.filter { c ->
            val matchesQuery = if (searchQuery.isBlank()) {
                true
            } else {
                val q = searchQuery.trim().lowercase()
                c.name.lowercase().contains(q) ||
                    c.phoneNumber.lowercase().contains(q) ||
                    c.whatsappNumber.lowercase().contains(q) ||
                    c.instagramHandle.lowercase().contains(q) ||
                    c.facebookProfile.lowercase().contains(q) ||
                    c.tiktokHandle.lowercase().contains(q)
            }
            val matchesPlatform = when (selectedPlatformFilter) {
                "WHATSAPP" -> c.whatsappNumber.isNotBlank() || c.phoneNumber.isNotBlank()
                "INSTAGRAM" -> c.instagramHandle.isNotBlank()
                "FACEBOOK" -> c.facebookProfile.isNotBlank()
                "TIKTOK" -> c.tiktokHandle.isNotBlank()
                else -> true
            }
            matchesQuery && matchesPlatform
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(BgDeepNight, Color(0xFF0A1022), BgDeepNight)
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag("omni_contacts_manager_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 1. Top Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(AccentEmerald)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Smart Contact Book",
                            color = TextMain,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "localStorage['omni_contacts_db'] • ${contacts.size} saved contacts",
                        color = TextSub,
                        fontSize = 12.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Raw JSON Toggle Button
                    Surface(
                        color = if (showRawJsonEditor) AccentViolet.copy(alpha = 0.25f) else CardSurface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (showRawJsonEditor) AccentViolet else CardBorder),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                showRawJsonEditor = !showRawJsonEditor
                                if (showRawJsonEditor) {
                                    rawJsonText = rawJsonProvider()
                                }
                            }
                            .testTag("toggle_raw_json_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = "Raw omni_contacts_db JSON",
                                tint = if (showRawJsonEditor) AccentViolet else AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "JSON",
                                color = TextMain,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Close Button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(CardSurface)
                            .border(1.dp, CardBorder, CircleShape)
                            .testTag("close_contacts_manager_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Contacts Manager",
                            tint = TextMain
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Optional Status Feedback Banner
            AnimatedVisibility(visible = statusBannerText != null) {
                Surface(
                    color = AccentEmerald.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusBannerText.orEmpty(),
                            color = AccentEmerald,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { statusBannerText = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss Banner",
                                tint = AccentEmerald,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Raw `localStorage['omni_contacts_db']` JSON Inspector & Sync Panel
            AnimatedVisibility(visible = showRawJsonEditor) {
                Surface(
                    color = CardSurface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, AccentViolet.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "localStorage.getItem('omni_contacts_db')",
                                color = AccentViolet,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        SmartContactAndAppRegistry.copyTextToClipboard(
                                            context,
                                            "omni_contacts_db",
                                            rawJsonText
                                        )
                                        statusBannerText = "Copied omni_contacts_db JSON to clipboard!"
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy JSON", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        onImportRawJson(rawJsonText)
                                        statusBannerText = "Synced raw JSON to omni_contacts_db!"
                                        showRawJsonEditor = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AccentViolet,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("apply_raw_json_button")
                                ) {
                                    Text("Save JSON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        OutlinedTextField(
                            value = rawJsonText,
                            onValueChange = { rawJsonText = it },
                            maxLines = 7,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = TextMain,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentViolet,
                                unfocusedBorderColor = CardBorder,
                                focusedContainerColor = BgDeepNight,
                                unfocusedContainerColor = BgDeepNight
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("raw_omni_contacts_json_input")
                        )
                    }
                }
            }

            // 2. Main Scrollable Content (Add/Edit Form + Search/Filter + Contact Cards)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // Add / Edit Contact Form Card
                item {
                    AddOrEditContactFormCard(
                        editingContactId = editingContactId,
                        isExpanded = isEditorExpanded,
                        nameInput = nameInput,
                        phoneInput = phoneInput,
                        whatsappInput = whatsappInput,
                        instagramInput = instagramInput,
                        facebookInput = facebookInput,
                        tiktokInput = tiktokInput,
                        onToggleExpand = { isEditorExpanded = !isEditorExpanded },
                        onNameChange = { nameInput = it },
                        onPhoneChange = {
                            phoneInput = it
                            if (whatsappInput.isBlank()) {
                                whatsappInput = it
                            }
                        },
                        onWhatsappChange = { whatsappInput = it },
                        onCopyPhoneToWhatsapp = { whatsappInput = phoneInput },
                        onInstagramChange = { instagramInput = it },
                        onFacebookChange = { facebookInput = it },
                        onTiktokChange = { tiktokInput = it },
                        onCancelEdit = {
                            clearAndResetForm()
                            statusBannerText = null
                        },
                        onSubmit = {
                            val cleanName = nameInput.trim()
                            if (cleanName.isNotEmpty()) {
                                val targetId = editingContactId
                                if (targetId != null) {
                                    onUpdateExistingContact(
                                        targetId,
                                        cleanName,
                                        phoneInput.trim(),
                                        whatsappInput.trim().ifBlank { phoneInput.trim() },
                                        instagramInput.trim(),
                                        facebookInput.trim(),
                                        tiktokInput.trim()
                                    )
                                    statusBannerText = "Updated '$cleanName' in omni_contacts_db!"
                                } else {
                                    onSaveNewContact(
                                        cleanName,
                                        phoneInput.trim(),
                                        whatsappInput.trim().ifBlank { phoneInput.trim() },
                                        instagramInput.trim(),
                                        facebookInput.trim(),
                                        tiktokInput.trim()
                                    )
                                    statusBannerText = "Added '$cleanName' to omni_contacts_db!"
                                }
                                clearAndResetForm()
                            }
                        }
                    )
                }

                // Search Bar & Platform Filter Row
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    "Search by name, phone, WhatsApp, @instagram, facebook, or @tiktok...",
                                    color = TextSub,
                                    fontSize = 12.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search Contacts",
                                    tint = AccentCyan
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear Search",
                                            tint = TextSub
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = CardSurface,
                                unfocusedContainerColor = CardSurface,
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_contacts_input")
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val filters = listOf(
                                "ALL" to "All (${contacts.size})",
                                "WHATSAPP" to "WhatsApp",
                                "INSTAGRAM" to "Instagram",
                                "FACEBOOK" to "Facebook",
                                "TIKTOK" to "TikTok"
                            )
                            items(filters) { (key, label) ->
                                val selected = selectedPlatformFilter == key
                                Surface(
                                    color = if (selected) AccentCyan.copy(alpha = 0.2f) else CardSurface,
                                    shape = RoundedCornerShape(20.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) AccentCyan else CardBorder
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .clickable { selectedPlatformFilter = key }
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) AccentCyan else TextSub,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Empty State if no contacts match
                if (filteredContacts.isEmpty()) {
                    item {
                        Surface(
                            color = CardSurface,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, CardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No matching contacts in omni_contacts_db",
                                    color = TextMain,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Use the form above to add a contact's Phone, WhatsApp, Instagram, Facebook, or TikTok handle, or say 'Save Ali's number as 03001234567'.",
                                    color = TextSub,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // Contact Cards List
                items(filteredContacts, key = { it.id }) { contact ->
                    SmartContactItemCard(
                        contact = contact,
                        isCurrentlyEditing = editingContactId == contact.id,
                        onEditClick = { startEditingContact(contact) },
                        onDeleteClick = {
                            if (editingContactId == contact.id) {
                                clearAndResetForm()
                            }
                            onDeleteContact(contact.id)
                            statusBannerText = "Removed '${contact.name}' from omni_contacts_db."
                        },
                        onLaunchWhatsApp = {
                            val formattedWa = SmartContactAndAppRegistry.formatWhatsAppPhone(
                                contact.whatsappNumber.ifBlank { contact.phoneNumber }
                            )
                            val uri = if (formattedWa.isNotEmpty()) {
                                "whatsapp://send?phone=$formattedWa&text="
                            } else {
                                "whatsapp://send"
                            }
                            SmartContactAndAppRegistry.launchUniversalDeepLink(
                                context = context,
                                deepLinkUri = uri,
                                fallbackPackage = "com.whatsapp"
                            )
                        },
                        onLaunchCall = {
                            val num = contact.phoneNumber.ifBlank { contact.whatsappNumber }
                            if (num.isNotBlank()) {
                                SmartContactAndAppRegistry.launchUniversalDeepLink(
                                    context = context,
                                    deepLinkUri = "tel:$num",
                                    fallbackPackage = "com.google.android.dialer"
                                )
                            }
                        },
                        onLaunchInstagramDm = {
                            val handle = SmartContactAndAppRegistry.cleanSocialHandle(contact.instagramHandle)
                            val uri = if (handle.isNotEmpty()) "https://ig.me/m/$handle" else "https://www.instagram.com/"
                            SmartContactAndAppRegistry.launchUniversalDeepLink(
                                context = context,
                                deepLinkUri = uri,
                                fallbackPackage = "com.instagram.android"
                            )
                        },
                        onLaunchFacebookMsg = {
                            val handle = SmartContactAndAppRegistry.cleanSocialHandle(contact.facebookProfile)
                            val uri = if (handle.isNotEmpty()) "fb-messenger://user/$handle" else "fb://feed"
                            SmartContactAndAppRegistry.launchUniversalDeepLink(
                                context = context,
                                deepLinkUri = uri,
                                fallbackPackage = "com.facebook.orca"
                            )
                        },
                        onLaunchTikTok = {
                            val handle = SmartContactAndAppRegistry.cleanSocialHandle(contact.tiktokHandle)
                            val uri = if (handle.isNotEmpty()) "https://www.tiktok.com/@$handle" else "snssdk1233://feed"
                            SmartContactAndAppRegistry.launchUniversalDeepLink(
                                context = context,
                                deepLinkUri = uri,
                                fallbackPackage = "com.zhiliaoapp.musically"
                            )
                        },
                        onVoiceMessageCommand = {
                            onQuickVoiceCommand("WhatsApp par ${contact.name} ko message bhejo ke main class mein hoon")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddOrEditContactFormCard(
    editingContactId: Long?,
    isExpanded: Boolean,
    nameInput: String,
    phoneInput: String,
    whatsappInput: String,
    instagramInput: String,
    facebookInput: String,
    tiktokInput: String,
    onToggleExpand: () -> Unit,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onWhatsappChange: (String) -> Unit,
    onCopyPhoneToWhatsapp: () -> Unit,
    onInstagramChange: (String) -> Unit,
    onFacebookChange: (String) -> Unit,
    onTiktokChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSubmit: () -> Unit
) {
    val isEditMode = editingContactId != null
    val borderAccent = if (isEditMode) AccentAmber else AccentCyan

    Surface(
        color = CardSurface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.5.dp, borderAccent.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("contact_editor_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isEditMode) Icons.Default.Edit else Icons.Default.Add,
                        contentDescription = null,
                        tint = borderAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isEditMode) "Edit Contact in omni_contacts_db" else "Add New Contact to omni_contacts_db",
                            color = borderAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isEditMode) {
                                "Modify phone, WhatsApp, or social handles below"
                            } else {
                                "Save phone, WhatsApp (03... -> 923...), IG, FB & TikTok handles"
                            },
                            color = TextSub,
                            fontSize = 11.sp
                        )
                    }
                }

                if (isEditMode) {
                    OutlinedButton(
                        onClick = onCancelEdit,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("cancel_edit_contact_button")
                    ) {
                        Text("Cancel Edit", color = AccentAmber, fontSize = 11.sp)
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Row 1: Contact Name & Phone Number
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = onNameChange,
                            label = { Text("Contact Name *", fontSize = 11.sp) },
                            placeholder = { Text("e.g. Ali", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = borderAccent,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("contact_name_input")
                        )
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = onPhoneChange,
                            label = { Text("Phone Number", fontSize = 11.sp) },
                            placeholder = { Text("03001234567", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = borderAccent,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("contact_phone_input")
                        )
                    }

                    // Row 2: WhatsApp Number + Formatted Preview
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = whatsappInput,
                            onValueChange = onWhatsappChange,
                            label = { Text("WhatsApp Number", fontSize = 11.sp) },
                            placeholder = { Text("03001234567 or +92300...", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentEmerald,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("contact_whatsapp_input")
                        )

                        if (phoneInput.isNotBlank() && whatsappInput != phoneInput) {
                            OutlinedButton(
                                onClick = onCopyPhoneToWhatsapp,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text("Same as Phone", fontSize = 11.sp, color = AccentEmerald)
                            }
                        }
                    }

                    val formattedWaPreview = SmartContactAndAppRegistry.formatWhatsAppPhone(
                        whatsappInput.ifBlank { phoneInput }
                    )
                    if (formattedWaPreview.isNotBlank()) {
                        Text(
                            text = "WhatsApp Deep-Link Format: whatsapp://send?phone=$formattedWaPreview",
                            color = AccentEmerald,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Row 3: Social Media Handles (Instagram, Facebook, TikTok)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = instagramInput,
                            onValueChange = onInstagramChange,
                            label = { Text("Instagram", fontSize = 10.sp) },
                            placeholder = { Text("@username", fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = borderAccent,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("contact_instagram_input")
                        )
                        OutlinedTextField(
                            value = facebookInput,
                            onValueChange = onFacebookChange,
                            label = { Text("Facebook", fontSize = 10.sp) },
                            placeholder = { Text("fb.profile", fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = borderAccent,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("contact_facebook_input")
                        )
                        OutlinedTextField(
                            value = tiktokInput,
                            onValueChange = onTiktokChange,
                            label = { Text("TikTok", fontSize = 10.sp) },
                            placeholder = { Text("@tiktok", fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = borderAccent,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("contact_tiktok_input")
                        )
                    }

                    // Submit Button
                    Button(
                        onClick = onSubmit,
                        enabled = nameInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = borderAccent,
                            contentColor = BgDeepNight
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("save_smart_contact_button")
                    ) {
                        Icon(
                            imageVector = if (isEditMode) Icons.Default.Check else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isEditMode) "Update Contact in omni_contacts_db" else "Save Contact to omni_contacts_db",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartContactItemCard(
    contact: SmartContactEntry,
    isCurrentlyEditing: Boolean,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onLaunchWhatsApp: () -> Unit,
    onLaunchCall: () -> Unit,
    onLaunchInstagramDm: () -> Unit,
    onLaunchFacebookMsg: () -> Unit,
    onLaunchTikTok: () -> Unit,
    onVoiceMessageCommand: () -> Unit
) {
    val formattedWa = SmartContactAndAppRegistry.formatWhatsAppPhone(
        contact.whatsappNumber.ifBlank { contact.phoneNumber }
    )

    Surface(
        color = if (isCurrentlyEditing) CardHighlight else CardSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = if (isCurrentlyEditing) 1.5.dp else 1.dp,
            color = if (isCurrentlyEditing) AccentAmber else CardBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("contact_card_${contact.name.lowercase()}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Avatar + Name + Edit & Delete Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(AccentCyan.copy(alpha = 0.3f), AccentViolet.copy(alpha = 0.3f))
                                )
                            )
                            .border(1.dp, AccentCyan.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = contact.name.take(2).uppercase(),
                            color = AccentCyan,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = contact.name,
                                color = TextMain,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (isCurrentlyEditing) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = AccentAmber.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "EDITING",
                                        color = AccentAmber,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = buildString {
                                if (contact.phoneNumber.isNotBlank()) append("Phone: ${contact.phoneNumber}  ")
                                if (formattedWa.isNotBlank()) append("• WA: +$formattedWa")
                            }.ifBlank { "No phone number set" },
                            color = TextSub,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = onEditClick,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AccentCyan.copy(alpha = 0.14f))
                            .testTag("edit_contact_${contact.name.lowercase()}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit ${contact.name}",
                            tint = AccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AccentCoral.copy(alpha = 0.14f))
                            .testTag("delete_contact_${contact.name.lowercase()}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete ${contact.name}",
                            tint = AccentCoral,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Middle Row: Social Handles Pills
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (formattedWa.isNotBlank()) {
                    item {
                        HandleBadgePill(
                            platformLabel = "WhatsApp",
                            handleValue = "+$formattedWa",
                            accentColor = AccentEmerald
                        )
                    }
                }
                if (contact.instagramHandle.isNotBlank()) {
                    item {
                        HandleBadgePill(
                            platformLabel = "Instagram",
                            handleValue = "@${SmartContactAndAppRegistry.cleanSocialHandle(contact.instagramHandle)}",
                            accentColor = Color(0xFFEC4899)
                        )
                    }
                }
                if (contact.facebookProfile.isNotBlank()) {
                    item {
                        HandleBadgePill(
                            platformLabel = "Facebook",
                            handleValue = SmartContactAndAppRegistry.cleanSocialHandle(contact.facebookProfile),
                            accentColor = Color(0xFF3B82F6)
                        )
                    }
                }
                if (contact.tiktokHandle.isNotBlank()) {
                    item {
                        HandleBadgePill(
                            platformLabel = "TikTok",
                            handleValue = "@${SmartContactAndAppRegistry.cleanSocialHandle(contact.tiktokHandle)}",
                            accentColor = AccentViolet
                        )
                    }
                }
            }

            // Bottom Row: One-Tap Assistant Deep-Link & Voice Actions
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    QuickActionPill(
                        label = "WhatsApp",
                        icon = Icons.AutoMirrored.Filled.Send,
                        color = AccentEmerald,
                        onClick = onLaunchWhatsApp
                    )
                }
                item {
                    QuickActionPill(
                        label = "Call",
                        icon = Icons.Default.Call,
                        color = AccentCyan,
                        onClick = onLaunchCall
                    )
                }
                if (contact.instagramHandle.isNotBlank()) {
                    item {
                        QuickActionPill(
                            label = "IG DM",
                            icon = Icons.AutoMirrored.Filled.Send,
                            color = Color(0xFFEC4899),
                            onClick = onLaunchInstagramDm
                        )
                    }
                }
                if (contact.facebookProfile.isNotBlank()) {
                    item {
                        QuickActionPill(
                            label = "FB Msg",
                            icon = Icons.AutoMirrored.Filled.Send,
                            color = Color(0xFF3B82F6),
                            onClick = onLaunchFacebookMsg
                        )
                    }
                }
                if (contact.tiktokHandle.isNotBlank()) {
                    item {
                        QuickActionPill(
                            label = "TikTok",
                            icon = Icons.Default.Person,
                            color = AccentViolet,
                            onClick = onLaunchTikTok
                        )
                    }
                }
                item {
                    QuickActionPill(
                        label = "Ask Omni Live",
                        icon = Icons.Default.RecordVoiceOver,
                        color = AccentAmber,
                        onClick = onVoiceMessageCommand
                    )
                }
            }
        }
    }
}

@Composable
private fun HandleBadgePill(
    platformLabel: String,
    handleValue: String,
    accentColor: Color
) {
    Surface(
        color = accentColor.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$platformLabel: ",
                color = TextSub,
                fontSize = 11.sp
            )
            Text(
                text = handleValue,
                color = accentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun QuickActionPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        color = BgDeepNight,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                color = TextMain,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

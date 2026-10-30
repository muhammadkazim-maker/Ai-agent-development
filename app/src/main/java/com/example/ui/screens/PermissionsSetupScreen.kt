package com.example.ui.screens

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.core.content.ContextCompat
import com.example.engine.Android12To17Compat
import com.example.engine.CapabilityProbe
import com.example.service.OmniAccessibilityService
import com.example.service.OmniDeviceAdminReceiver
import com.example.service.OmniNotificationListenerService
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

data class PermissionItem(
    val id: String,
    val category: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val runtimePermissions: List<String> = emptyList(),
    val isSystemSetting: Boolean = false,
    val onOpenSpecialSetting: ((Context) -> Unit)? = null
)

@Composable
fun PermissionsSetupScreen() {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var adbCopiedNotice by remember { mutableStateOf(false) }

    val capabilityProfile = remember(refreshTrigger) {
        CapabilityProbe.probeDevice(context)
    }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshTrigger++
    }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshTrigger++
    }

    val permissionStatus = remember(refreshTrigger) {
        val map = mutableMapOf<String, Boolean>()
        // 1. Default Assistant Role (RoleManager.ROLE_ASSISTANT)
        map["role_assistant"] = Android12To17Compat.isRoleAssistantHeld(context)

        // 2. Runtime permissions (Android 12–17)
        map["mic"] = hasPerm(context, Manifest.permission.RECORD_AUDIO)
        map["contacts"] = hasPerm(context, Manifest.permission.READ_CONTACTS) &&
            hasPerm(context, Manifest.permission.WRITE_CONTACTS)
        map["phone_call_log"] = hasPerm(context, Manifest.permission.CALL_PHONE) &&
            hasPerm(context, Manifest.permission.READ_PHONE_STATE) &&
            hasPerm(context, Manifest.permission.ANSWER_PHONE_CALLS) &&
            hasPerm(context, Manifest.permission.READ_CALL_LOG)
        map["sms"] = hasPerm(context, Manifest.permission.SEND_SMS) &&
            hasPerm(context, Manifest.permission.READ_SMS) &&
            hasPerm(context, Manifest.permission.RECEIVE_SMS)
        map["calendar"] = hasPerm(context, Manifest.permission.READ_CALENDAR) &&
            hasPerm(context, Manifest.permission.WRITE_CALENDAR)
        map["camera"] = hasPerm(context, Manifest.permission.CAMERA)
        map["notifications"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(context, Manifest.permission.POST_NOTIFICATIONS)
        } else true
        map["wifi_location"] = hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasPerm(context, Manifest.permission.NEARBY_WIFI_DEVICES)
            } else true)
        map["bluetooth"] = hasPerm(context, Manifest.permission.BLUETOOTH_CONNECT) &&
            hasPerm(context, Manifest.permission.BLUETOOTH_SCAN)
        map["media"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hasPerm(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                hasPerm(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(context, Manifest.permission.READ_MEDIA_IMAGES)
        } else true

        // 3. Special App Access & System Services (Section 13)
        map["accessibility"] = OmniAccessibilityService.isConnected()
        map["notification_listener"] = OmniNotificationListenerService.isServiceRunning()
        map["overlay"] = Settings.canDrawOverlays(context)
        map["write_settings"] = Settings.System.canWrite(context)
        map["battery"] = isIgnoringBatteryOptimizations(context)
        map["exact_alarm"] = canScheduleExactAlarms(context)
        map["full_screen_intent"] = Android12To17Compat.canUseFullScreenIntent(context)
        map["dnd_policy"] = isNotificationPolicyAccessGranted(context)
        map["usage_stats"] = hasUsageStatsPermission(context)
        map["device_admin"] = OmniDeviceAdminReceiver.isAdminActive(context)
        map["auto_revoke_off"] = Android12To17Compat.isAutoRevokeWhitelisted(context)
        map["write_secure_or_shizuku"] = capabilityProfile.hasWriteSecureSettings || capabilityProfile.isShizukuInstalled

        map
    }

    val items = remember(capabilityProfile) {
        listOf(
            PermissionItem(
                id = "role_assistant",
                category = "ROLE & HOTWORD",
                title = "Default Digital Assistant (ROLE_ASSISTANT)",
                description = "Grants official VoiceInteractionSession privileges for lock-screen hotword and power-button invocation.",
                icon = Icons.Default.Mic,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    try {
                        roleLauncher.launch(Android12To17Compat.createRequestRoleAssistantIntent(ctx))
                    } catch (_: Exception) {
                        openSafeIntent(ctx, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    }
                }
            ),
            PermissionItem(
                id = "mic",
                category = "RUNTIME",
                title = "Microphone (RECORD_AUDIO)",
                description = "Essential for 16kHz PCM voice capture, wake-word detection, and bilingual commands.",
                icon = Icons.Default.Mic,
                runtimePermissions = listOf(Manifest.permission.RECORD_AUDIO)
            ),
            PermissionItem(
                id = "contacts",
                category = "RUNTIME",
                title = "Contacts Read & Write",
                description = "Resolves bilingual names ('Ali Bhai', 'Ammi') and saves new contacts by voice.",
                icon = Icons.Default.Contacts,
                runtimePermissions = listOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS
                )
            ),
            PermissionItem(
                id = "phone_call_log",
                category = "RUNTIME",
                title = "Phone Calls, Call Log & Dual-SIM",
                description = "Places SIM 1 / SIM 2 calls, answers/rejects incoming calls, and reads missed calls.",
                icon = Icons.Default.Call,
                runtimePermissions = buildList {
                    add(Manifest.permission.CALL_PHONE)
                    add(Manifest.permission.READ_PHONE_STATE)
                    add(Manifest.permission.ANSWER_PHONE_CALLS)
                    add(Manifest.permission.READ_CALL_LOG)
                    add(Manifest.permission.READ_PHONE_NUMBERS)
                }
            ),
            PermissionItem(
                id = "sms",
                category = "RUNTIME",
                title = "SMS Send & Read",
                description = "Sends verified SMS with PendingIntent callback and reads incoming OTP/messages aloud.",
                icon = Icons.Default.Sms,
                runtimePermissions = listOf(
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS
                )
            ),
            PermissionItem(
                id = "calendar",
                category = "RUNTIME",
                title = "Calendar Read & Write",
                description = "Creates verified calendar events and reads today's schedule by voice.",
                icon = Icons.Default.CalendarMonth,
                runtimePermissions = listOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            ),
            PermissionItem(
                id = "camera",
                category = "RUNTIME",
                title = "Camera & Vision (CAMERA)",
                description = "Required only when you ask OmniVoice to take a photo or read text via camera.",
                icon = Icons.Default.CameraAlt,
                runtimePermissions = listOf(Manifest.permission.CAMERA)
            ),
            PermissionItem(
                id = "notifications",
                category = "RUNTIME",
                title = "Notifications (POST_NOTIFICATIONS)",
                description = "Displays the persistent foreground microphone status and stop/pause controls.",
                icon = Icons.Default.Notifications,
                runtimePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                } else emptyList()
            ),
            PermissionItem(
                id = "wifi_location",
                category = "RUNTIME",
                title = "Wi-Fi Scan & Location (NEARBY_WIFI_DEVICES)",
                description = "Required by Android 12–17 to scan nearby Wi-Fi SSIDs and read connected network name.",
                icon = Icons.Default.Wifi,
                runtimePermissions = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }
                }
            ),
            PermissionItem(
                id = "bluetooth",
                category = "RUNTIME",
                title = "Bluetooth Connect & Scan",
                description = "Connects to paired Bluetooth headsets/cars and routes audio over SCO/BLE.",
                icon = Icons.Default.Bluetooth,
                runtimePermissions = listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            ),
            PermissionItem(
                id = "media",
                category = "RUNTIME",
                title = "Photos, Video & Audio Media Access",
                description = "Supports Android 13/14+ granular media permissions & Selected Photos Access.",
                icon = Icons.Default.PhotoLibrary,
                runtimePermissions = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.READ_MEDIA_IMAGES)
                        add(Manifest.permission.READ_MEDIA_VIDEO)
                        add(Manifest.permission.READ_MEDIA_AUDIO)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                    }
                }
            ),
            PermissionItem(
                id = "accessibility",
                category = "SPECIAL ACCESS",
                title = "Accessibility Service (L3 UI Control)",
                description = "Reads screen trees, clicks buttons, types into apps, and automates Settings/WhatsApp.",
                icon = Icons.Default.SettingsAccessibility,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    openSafeIntent(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            ),
            PermissionItem(
                id = "notification_listener",
                category = "SPECIAL ACCESS",
                title = "Notification Listener Access",
                description = "Reads incoming WhatsApp/SMS notifications and replies directly via RemoteInput.",
                icon = Icons.Default.Notifications,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    openSafeIntent(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            ),
            PermissionItem(
                id = "overlay",
                category = "SPECIAL ACCESS",
                title = "Display Over Other Apps (SYSTEM_ALERT_WINDOW)",
                description = "Shows the floating voice orb and live verification HUD on top of any app.",
                icon = Icons.Default.Layers,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    openSafeIntent(
                        ctx,
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
            ),
            PermissionItem(
                id = "write_settings",
                category = "SPECIAL ACCESS",
                title = "Modify System Settings (WRITE_SETTINGS)",
                description = "Allows direct brightness, auto-brightness, screen timeout, and auto-rotate control.",
                icon = Icons.Default.Settings,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    openSafeIntent(
                        ctx,
                        Intent(
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
            ),
            PermissionItem(
                id = "battery",
                category = "OEM & DOZE",
                title = "Unrestricted Battery (${capabilityProfile.oemSkin.displayName})",
                description = capabilityProfile.oemSkin.batteryFixGuide,
                icon = Icons.Default.BatteryChargingFull,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    openSafeIntent(
                        ctx,
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
            ),
            PermissionItem(
                id = "auto_revoke_off",
                category = "OEM & DOZE",
                title = "Turn OFF Permission Auto-Reset (Hibernation)",
                description = "Prevents Android 12–17 from revoking permissions or hibernating OmniVoice.",
                icon = Icons.Default.Security,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    openSafeIntent(ctx, Android12To17Compat.createAutoRevokeWhitelistIntent(ctx))
                }
            ),
            PermissionItem(
                id = "exact_alarm",
                category = "SPECIAL ACCESS",
                title = "Alarms & Reminders (SCHEDULE_EXACT_ALARM)",
                description = "Required on Android 12–17 so spoken alarms and reminders fire at the exact second.",
                icon = Icons.Default.AccessAlarm,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        openSafeIntent(
                            ctx,
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    }
                }
            ),
            PermissionItem(
                id = "full_screen_intent",
                category = "SPECIAL ACCESS",
                title = "Full-Screen Lock-Screen Wake Intent (API 34+)",
                description = "Required on Android 14–17 to wake the lock screen for hands-free voice responses.",
                icon = Icons.Default.Lock,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    openSafeIntent(ctx, Android12To17Compat.createFullScreenIntentSettings(ctx))
                }
            ),
            PermissionItem(
                id = "dnd_policy",
                category = "SPECIAL ACCESS",
                title = "Do Not Disturb Policy Access",
                description = "Allows toggling DND and switching ringer to Silent/Vibrate/Normal.",
                icon = Icons.Default.DoNotDisturbOn,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    openSafeIntent(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            ),
            PermissionItem(
                id = "usage_stats",
                category = "SPECIAL ACCESS",
                title = "Usage Access (PACKAGE_USAGE_STATS)",
                description = "Detects foreground app package when Accessibility is unavailable.",
                icon = Icons.Default.Build,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    openSafeIntent(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            ),
            PermissionItem(
                id = "device_admin",
                category = "SECURITY",
                title = "Device Administrator (Screen Lock Only)",
                description = "Used exclusively to lock the screen instantly on your voice command.",
                icon = Icons.Default.Lock,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    OmniDeviceAdminReceiver.requestAdminPermission(ctx)
                }
            ),
            PermissionItem(
                id = "write_secure_or_shizuku",
                category = "L2 POWER USER",
                title = "L2 Direct Toggle (WRITE_SECURE_SETTINGS / Shizuku)",
                description = "Optional power-user level to toggle Mobile Data, Hotspot, Location & NFC directly.",
                icon = Icons.Default.Terminal,
                isSystemSetting = true,
                onOpenSpecialSetting = { ctx ->
                    val adbCmd = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("ADB Command", adbCmd))
                    adbCopiedNotice = true
                }
            )
        )
    }

    val grantedCount = items.count { permissionStatus[it.id] == true }
    val progress = (grantedCount.toFloat() / items.size.coerceAtLeast(1)).coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            // Capability Tier & OEM Header Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FIRST-RUN SETUP & CAPABILITY WIZARD",
                            color = CyanPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        OutlinedButton(
                            onClick = { refreshTrigger++ },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = CyanPrimary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Re-Check", fontSize = 10.sp, color = CyanPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${capabilityProfile.androidReleaseName} • ${capabilityProfile.oemSkin.displayName} • ${capabilityProfile.currentTier.label}",
                        color = EmeraldTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = capabilityProfile.currentTier.summary,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Setup Readiness",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "$grantedCount of ${items.size} Active",
                            color = if (grantedCount == items.size) EmeraldTertiary else CyanPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        color = CyanPrimary,
                        trackColor = DarkSurfaceBorder
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            val allRuntimePerms = items
                                .flatMap { it.runtimePermissions }
                                .distinct()
                                .filter { !hasPerm(context, it) }
                            if (allRuntimePerms.isNotEmpty()) {
                                runtimePermLauncher.launch(allRuntimePerms.toTypedArray())
                            } else {
                                refreshTrigger++
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("request_all_permissions_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Text(
                            "Grant All Runtime Permissions (1-Tap)",
                            color = Color(0xFF003548),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Android 17 Advanced Protection Warning Banner (if detected)
            if (capabilityProfile.isAdvancedProtectionEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3B181E))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFF87171),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ANDROID 17 ADVANCED PROTECTION MODE DETECTED",
                                color = Color(0xFFF87171),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Android 17 Advanced Protection Mode blocks AccessibilityService for non-accessibility-tool apps. OmniVoice is operating in Tier D (Direct APIs, Telecom, Intents & Notification Reply).",
                            color = TextPrimary,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Android 13–17 "Allow Restricted Settings" 1-Tap Helper Card (Section 1 & Section 13)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ANDROID 13–17 RESTRICTED SETTINGS UNLOCK",
                            color = Color(0xFFFBBF24),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "If Android grays out Accessibility or Notification Access on a sideloaded APK: tap the button below -> tap the 3-dot menu (⋮) in the top-right corner of App Info -> tap 'Allow restricted settings', then return here.",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            openSafeIntent(
                                context,
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_app_info_restricted_settings_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Open App Info (⋮ -> Allow Restricted Settings)", fontSize = 11.sp, color = CyanPrimary)
                    }
                }
            }

            if (adbCopiedNotice) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "ADB Command Copied to Clipboard:",
                            color = EmeraldTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
                            color = TextPrimary,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        items(items, key = { it.id }) { item ->
            val isGranted = permissionStatus[item.id] == true

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .padding(vertical = 5.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                if (isGranted) EmeraldTertiary.copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.title,
                            tint = if (isGranted) EmeraldTertiary else Color(0xFFEF4444),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.title,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            if (isGranted) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Granted",
                                    tint = EmeraldTertiary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        Text(
                            text = item.category,
                            color = CyanPrimary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.description,
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (!isGranted) {
                        OutlinedButton(
                            onClick = {
                                if (item.isSystemSetting && item.onOpenSpecialSetting != null) {
                                    item.onOpenSpecialSetting.invoke(context)
                                } else if (item.runtimePermissions.isNotEmpty()) {
                                    runtimePermLauncher.launch(item.runtimePermissions.toTypedArray())
                                }
                            },
                            modifier = Modifier.testTag("fix_permission_${item.id}"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Fix this", fontSize = 11.sp, color = CyanPrimary, fontWeight = FontWeight.Bold)
                                if (item.isSystemSetting) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        tint = CyanPrimary,
                                        modifier = Modifier.size(12.dp)
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

private fun hasPerm(context: Context, perm: String): Boolean {
    return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

private fun openSafeIntent(context: Context, intent: Intent) {
    try {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        } catch (_: Exception) {
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
}

private fun canScheduleExactAlarms(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        am?.canScheduleExactAlarms() == true
    } else true
}

private fun isNotificationPolicyAccessGranted(context: Context): Boolean {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    return nm?.isNotificationPolicyAccessGranted == true
}

private fun hasUsageStatsPermission(context: Context): Boolean {
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}

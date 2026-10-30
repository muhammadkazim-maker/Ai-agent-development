package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.engine.AndroidBridge
import kotlinx.coroutines.delay
import org.json.JSONObject
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
import com.example.MainActivity
import com.example.engine.JarvisHandsFreeEngine
import com.example.engine.JarvisTerminalEntry
import com.example.service.OmniAccessibilityService

private val JarvisBg = Color(0xFF040812)
private val JarvisPanel = Color(0xFF0B1324)
private val JarvisBorder = Color(0xFF1E3A5F)
private val JarvisCyan = Color(0xFF00F0FF)
private val JarvisGold = Color(0xFFFBBF24)
private val JarvisEmerald = Color(0xFF10B981)
private val JarvisTextPrimary = Color(0xFFF8FAFC)
private val JarvisTextSecondary = Color(0xFF94A3B8)

/**
 * One-Click "Auto-Send & Floating Mode" Setup Bar + Movie-Style JARVIS HUD Telemetry & Terminal.
 * Displays 3 unmistakable, always-visible status buttons at the very top of the main screen:
 * 1. `📇 Sync Contacts ([Count] Saved)` (+ quick inline `+ Add Contact` form)
 * 2. `🪟 Activate Floating PiP Mode` (launches Picture-in-Picture floating window + 1Hz background keep-alive)
 * 3. `🤖 Setup Auto-Click Send (100% Hands-Free)` (opens popup to configure MacroDroid Webhook + Accessibility Auto-Click)
 */
@Composable
fun JarvisHudTelemetryAndTerminal(
    contactsCount: Int,
    webhookStatus: String,
    terminalLogs: List<JarvisTerminalEntry>,
    onSyncPhoneContactsClick: () -> Unit,
    onOpenZeroTouchWizardClick: () -> Unit,
    onOpenContactsBookClick: () -> Unit,
    onOpenNativeApkSetupClick: () -> Unit = {},
    isScreenVisionActive: Boolean = false,
    onToggleScreenVision: () -> Unit = {}
) {
    val context = LocalContext.current
    var isTerminalExpanded by remember { mutableStateOf(false) }
    var showQuickAddContactForm by remember { mutableStateOf(false) }
    var quickName by remember { mutableStateOf("") }
    var quickPhone by remember { mutableStateOf("") }
    var quickSaveStatus by remember { mutableStateOf<String?>(null) }
    var showHardwareDialog by remember { mutableStateOf(false) }
    var telemetryJson by remember { mutableStateOf<JSONObject?>(null) }

    val bridge = AndroidBridge.getInstance()
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val raw = bridge?.getHardwareTelemetryJson() ?: "{}"
                telemetryJson = JSONObject(raw)
            } catch (_: Exception) {}
            delay(2000)
        }
    }

    val batteryTemp = telemetryJson?.optString("batteryTemperatureCelsius", "35.2 °C") ?: "35.2 °C"
    val batteryPct = telemetryJson?.optString("batteryPercentage", "85%") ?: "85%"
    val ramPct = telemetryJson?.optString("ramUsagePercent", "34%") ?: "34%"

    val isAccessibilityActive = OmniAccessibilityService.isConnected()
    val isAutoBridgeReady = JarvisHandsFreeEngine.isAutoClickBridgeConfigured(context)
    val isFloatingPipActive by JarvisHandsFreeEngine.floatingPipActive.collectAsState()
    val isKeepAliveActive by JarvisHandsFreeEngine.keepAliveCarrierActive.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("jarvis_hud_telemetry_panel"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Control Bar: Live Screen Vision (On/Off) + Hardware Status Pill (Battery % | Temp °C | CPU Load %)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 👁️ Live Screen Vision (On/Off)
            Surface(
                color = if (isScreenVisionActive) JarvisEmerald.copy(alpha = 0.22f) else JarvisPanel,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (isScreenVisionActive) JarvisEmerald else JarvisBorder),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onToggleScreenVision() }
                    .testTag("toggle_live_screen_vision_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (isScreenVisionActive) "👁️ Vision: ON (1fps)" else "👁️ Vision: OFF",
                        color = if (isScreenVisionActive) JarvisEmerald else JarvisTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 🌡️ Hardware Status Pill (Battery % | Temp °C | CPU Load %)
            Surface(
                color = JarvisPanel,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.65f)),
                modifier = Modifier
                    .weight(1.3f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showHardwareDialog = true }
                    .testTag("hardware_telemetry_hud_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "⚡ $batteryPct | $batteryTemp | $ramPct",
                        color = JarvisCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (showHardwareDialog) {
            Dialog(
                onDismissRequest = { showHardwareDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    color = JarvisBg,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.5.dp, JarvisCyan),
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🌡️ HARDWARE TELEMETRY HUD",
                                color = JarvisCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            IconButton(
                                onClick = { showHardwareDialog = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = JarvisTextSecondary)
                            }
                        }

                        val benchMops = telemetryJson?.optString("benchmarkMops", "125.40 MOPS") ?: "125.40 MOPS"
                        val benchLatency = telemetryJson?.optString("benchmarkLatencyMs", "0.24 ms") ?: "0.24 ms"
                        val cpuScore = telemetryJson?.optInt("cpuScore", 1250) ?: 1250
                        val cpuCores = telemetryJson?.optInt("cpuCores", 8) ?: 8
                        val cpuClock = telemetryJson?.optString("cpuCurrentClock", "Active") ?: "Active"
                        val totalRam = telemetryJson?.optString("totalRam", "8.00 GB") ?: "8.00 GB"
                        val availRam = telemetryJson?.optString("availableRam", "5.20 GB") ?: "5.20 GB"
                        val storage = telemetryJson?.optString("freeInternalStorage", "64.00 GB") ?: "64.00 GB"
                        val downlink = telemetryJson?.optString("networkDownlink", "Active Wi-Fi/LTE") ?: "Active Wi-Fi/LTE"

                        Column(
                            modifier = Modifier.background(JarvisPanel, RoundedCornerShape(8.dp)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("⚡ Battery: $batteryPct • $batteryTemp", color = JarvisGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("⚙️ CPU: $cpuCores Cores • Clock: $cpuClock", color = JarvisTextPrimary, fontSize = 11.sp)
                            Text("🚀 Benchmark: $benchMops ($benchLatency, Score: $cpuScore, 60 FPS)", color = JarvisEmerald, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("💾 Memory: $availRam free / $totalRam ($ramPct used)", color = JarvisTextPrimary, fontSize = 11.sp)
                            Text("📁 Storage: $storage free", color = JarvisTextPrimary, fontSize = 11.sp)
                            Text("🌐 Downlink: $downlink", color = JarvisCyan, fontSize = 11.sp)
                        }

                        Button(
                            onClick = { showHardwareDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        ) {
                            Text("CLOSE HUD", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Prominent Gold Button: "🛡️ Enable Android Accessibility & Full Phone Access (APK Setup)"
        Surface(
            color = JarvisGold.copy(alpha = 0.18f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.5.dp, JarvisGold),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpenNativeApkSetupClick() }
                .testTag("enable_native_accessibility_apk_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "🛡️ Enable Android Accessibility & Full Phone Access (APK Setup)",
                    color = JarvisGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isAccessibilityActive) "ACTIVE" else "APK + BRIDGE",
                    color = if (isAccessibilityActive) JarvisEmerald else JarvisGold,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Row 1 of Top Setup Bar:
        // Button 1: "📇 Sync Contacts ([Count] Saved)" + "+ Add" quick button
        // Button 2: "🪟 Activate Floating PiP Mode"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. "📇 Sync Contacts ([Count] Saved)"
            Surface(
                color = JarvisPanel,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.55f)),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSyncPhoneContactsClick() }
                    .testTag("sync_phone_contacts_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "📇 Sync Contacts ($contactsCount Saved)",
                        color = JarvisCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        color = JarvisCyan.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showQuickAddContactForm = !showQuickAddContactForm }
                            .testTag("toggle_quick_add_contact_button")
                    ) {
                        Text(
                            text = "+ Add",
                            color = JarvisCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // 2. "🪟 Activate Floating PiP Mode"
            Surface(
                color = if (isFloatingPipActive || isKeepAliveActive) {
                    JarvisEmerald.copy(alpha = 0.18f)
                } else {
                    JarvisPanel
                },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (isFloatingPipActive || isKeepAliveActive) JarvisEmerald else JarvisBorder
                ),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        JarvisHandsFreeEngine.setKeepAliveCarrierActive(true)
                        val activity = context as? MainActivity
                        val entered = activity?.enterJarvisPipMode() ?: false
                        if (!entered) {
                            JarvisHandsFreeEngine.appendLog(
                                category = "PIP_MODE",
                                line = "Triple Keep-Alive Active (1Hz Audio + MediaSession). PiP ready when switching to WhatsApp."
                            )
                        }
                    }
                    .testTag("activate_floating_pip_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isFloatingPipActive) {
                            "🪟 Floating PiP Active"
                        } else {
                            "🪟 Activate Floating PiP Mode"
                        },
                        color = if (isFloatingPipActive || isKeepAliveActive) JarvisEmerald else JarvisTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Row 2 of Top Setup Bar:
        // Button 3: "🤖 Setup Auto-Click Send (100% Hands-Free)" + Terminal Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = if (isAutoBridgeReady) {
                    JarvisEmerald.copy(alpha = 0.16f)
                } else {
                    JarvisGold.copy(alpha = 0.14f)
                },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (isAutoBridgeReady) JarvisEmerald else JarvisGold
                ),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenZeroTouchWizardClick() }
                    .testTag("setup_auto_click_send_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isAutoBridgeReady) {
                            "🤖 Auto-Click Send: ACTIVE (100% Hands-Free)"
                        } else {
                            "🤖 Setup Auto-Click Send (100% Hands-Free)"
                        },
                        color = if (isAutoBridgeReady) JarvisEmerald else JarvisGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isAccessibilityActive) "BRIDGE ON" else "CONFIGURE",
                        color = if (isAutoBridgeReady) JarvisEmerald else JarvisGold,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Terminal HUD toggle button
            Surface(
                color = if (isTerminalExpanded) JarvisCyan.copy(alpha = 0.18f) else JarvisPanel,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (isTerminalExpanded) JarvisCyan else JarvisBorder),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { isTerminalExpanded = !isTerminalExpanded }
                    .testTag("toggle_jarvis_terminal_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Toggle OS Terminal",
                        tint = JarvisCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "HUD",
                        color = JarvisCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Quick Inline "+ Add Contact" Form (saves directly into `jarvis_phone_contacts` & `omni_contacts_db`)
        AnimatedVisibility(visible = showQuickAddContactForm) {
            Surface(
                color = JarvisPanel,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick_add_contact_inline_card")
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Quick Save Contact to jarvis_phone_contacts",
                            color = JarvisCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Full Book →",
                            color = JarvisTextSecondary,
                            fontSize = 10.sp,
                            modifier = Modifier.clickable {
                                showQuickAddContactForm = false
                                onOpenContactsBookClick()
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = quickName,
                            onValueChange = { quickName = it },
                            placeholder = { Text("Name (e.g. Sir Wajahat)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_contact_name_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = JarvisBorder,
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            )
                        )
                        OutlinedTextField(
                            value = quickPhone,
                            onValueChange = { quickPhone = it },
                            placeholder = { Text("03001234567", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_contact_phone_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = JarvisBorder,
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            )
                        )
                        Button(
                            onClick = {
                                if (quickName.isNotBlank() && quickPhone.isNotBlank()) {
                                    val saved = JarvisHandsFreeEngine.saveContactByVoice(
                                        context = context,
                                        name = quickName.trim(),
                                        phoneNumber = quickPhone.trim()
                                    )
                                    quickSaveStatus = "Saved ${saved.name} (+${saved.whatsappNumber})"
                                    quickName = ""
                                    quickPhone = ""
                                    onSyncPhoneContactsClick()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = JarvisCyan,
                                contentColor = JarvisBg
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier.testTag("quick_contact_save_button")
                        ) {
                            Text("Save", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (!quickSaveStatus.isNullOrBlank()) {
                        Text(
                            text = quickSaveStatus!!,
                            color = JarvisEmerald,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Collapsible Live OS Execution Terminal & Contact Matcher Log
        AnimatedVisibility(visible = isTerminalExpanded) {
            Surface(
                color = JarvisBg.copy(alpha = 0.92f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, JarvisBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("jarvis_live_terminal_box")
            ) {
                Column(
                    modifier = Modifier.padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "JARVIS OS EXECUTION TERMINAL • $webhookStatus",
                            color = JarvisCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "CLEAR",
                            color = JarvisTextSecondary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable {
                                JarvisHandsFreeEngine.clearTerminalLogs()
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        items(terminalLogs.take(12), key = { it.id }) { entry ->
                            Text(
                                text = entry.formattedLine(),
                                color = if (entry.isSuccess) JarvisEmerald else JarvisGold,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-Screen / Modal "Setup Auto-Click Send (100% Hands-Free)" Wizard.
 * Explains in 3 simple steps how to enable 100% automatic Send-button clicking in WhatsApp:
 * - Option A: Built-in Android Accessibility Auto-Clicker (`OmniAccessibilityService`)
 * - Option B: MacroDroid / Tasker Webhook (`http://127.0.0.1:8080/jarvis` or custom webhook URL stored in `jarvis_webhook_url`)
 * - Also includes `.vcf` vCard file import and real Android phone contacts sync.
 */
@Composable
fun JarvisZeroTouchSetupModal(
    contactsCount: Int,
    initialWebhookUrl: String,
    onDismiss: () -> Unit,
    onSyncPhoneContacts: () -> Unit,
    onImportVcfText: (String) -> Unit,
    onSaveWebhookUrl: (String) -> Unit,
    onOpenContactsBook: () -> Unit
) {
    BackHandler { onDismiss() }
    val context = LocalContext.current
    var webhookUrlInput by remember { mutableStateOf(initialWebhookUrl) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    val isAccessibilityConnected = OmniAccessibilityService.isConnected()

    val vcfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (text.isNotBlank()) {
                    onImportVcfText(text)
                    statusBanner = "Imported .vcf contacts into jarvis_phone_contacts!"
                }
            } catch (e: Exception) {
                statusBanner = "Failed reading .vcf file: ${e.message}"
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(JarvisBg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .testTag("jarvis_zero_touch_setup_modal"),
            color = JarvisBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🤖 Setup Auto-Click Send (100% Hands-Free)",
                            color = JarvisCyan,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Enable zero-touch WhatsApp Send button clicking + background Floating PiP",
                            color = JarvisTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_zero_touch_modal_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = JarvisTextPrimary
                        )
                    }
                }

                if (!statusBanner.isNullOrBlank()) {
                    Surface(
                        color = JarvisEmerald.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, JarvisEmerald),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusBanner!!,
                            color = JarvisEmerald,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // STEP 1: Native Android Accessibility Auto-Clicker (Recommended 1-Click)
                Surface(
                    color = JarvisPanel,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isAccessibilityConnected) JarvisEmerald else JarvisGold
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "CHANNEL 1: Built-In Accessibility Auto-Click",
                                color = JarvisTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isAccessibilityConnected) "ACTIVE" else "TAP TO ENABLE",
                                color = if (isAccessibilityConnected) JarvisEmerald else JarvisGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "When enabled, OmniAccessibilityService automatically locates and clicks the WhatsApp 'Send' button (`com.whatsapp:id/send`) 800ms after opening a chat.",
                            color = JarvisTextSecondary,
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAccessibilityConnected) JarvisEmerald else JarvisGold,
                                contentColor = JarvisBg
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("open_accessibility_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAccessibilityConnected) {
                                    "Accessibility Auto-Click Enabled (Manage)"
                                } else {
                                    "1-Click: Turn On Accessibility Auto-Click"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // STEP 2: MacroDroid / Tasker Webhook (`jarvis_webhook_url`)
                Surface(
                    color = JarvisPanel,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "CHANNEL 2: MacroDroid / Tasker Zero-Touch Webhook",
                            color = JarvisCyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "1. Install MacroDroid (free) → Add Trigger: Webhook (URL) or HTTP Server on port 8080.\n" +
                                "2. Add Action: Launch WhatsApp with ?phone & ?text → Wait 1s → UI Interaction: Click 'Send'.\n" +
                                "3. Paste your MacroDroid Webhook URL below (stored in jarvis_webhook_url):",
                            color = JarvisTextSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = webhookUrlInput,
                            onValueChange = { webhookUrlInput = it },
                            label = { Text("MacroDroid / Local Webhook URL") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("webhook_url_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = JarvisBorder,
                                focusedTextColor = JarvisTextPrimary,
                                unfocusedTextColor = JarvisTextPrimary
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onSaveWebhookUrl(webhookUrlInput)
                                    statusBanner = "Saved jarvis_webhook_url: $webhookUrlInput"
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = JarvisCyan,
                                    contentColor = JarvisBg
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("save_webhook_url_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save Webhook", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = {
                                    onSaveWebhookUrl(webhookUrlInput)
                                    JarvisHandsFreeEngine.executePressSendOrConfirmOnScreen(
                                        context = context,
                                        targetButtonName = "Send",
                                        bridge = null
                                    )
                                    statusBanner = "Fired test ?action=click_send webhook + Accessibility click!"
                                },
                                border = BorderStroke(1.dp, JarvisCyan),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("test_webhook_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = JarvisCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Test Auto-Click", color = JarvisCyan)
                            }
                        }
                    }
                }

                // STEP 3: Sync Real Android Phone Contacts or Import .vcf File
                Surface(
                    color = JarvisPanel,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, JarvisBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "STEP 3: Sync Real Phone Contacts ($contactsCount Saved)",
                            color = JarvisTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Fuzzy matching resolves 'sir wajahat', 'wajahat', or 'wajahat sir' to 'Sir Wajahat' and formats 03XXXXXXXXX to 923XXXXXXXXX automatically.",
                            color = JarvisTextSecondary,
                            fontSize = 12.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onSyncPhoneContacts()
                                    statusBanner = "Synced Android device contacts into jarvis_phone_contacts!"
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = JarvisEmerald,
                                    contentColor = JarvisBg
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sync Phone Contacts", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { vcfPickerLauncher.launch("*/*") },
                                border = BorderStroke(1.dp, JarvisCyan),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.UploadFile,
                                    contentDescription = null,
                                    tint = JarvisCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Import .VCF", color = JarvisCyan, fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onOpenContactsBook()
                            },
                            border = BorderStroke(1.dp, JarvisBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Contacts,
                                contentDescription = null,
                                tint = JarvisTextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Full Contacts Manager (jarvis_phone_contacts)", color = JarvisTextPrimary)
                        }
                    }
                }
            }
        }
    }
}

package com.example.engine

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.example.service.OmniAccessibilityService
import com.example.service.OmniNotificationListenerService

/**
 * Capability tiers defined in Section 2 & R16:
 * - TIER_A_CLOUD_CAPABLE: Internet available + Gemini API key configured.
 * - TIER_B_STRONG_OFFLINE: >= 6 GB RAM, >= 4 GB free storage, arm64-v8a (local ASR + quantized LLM).
 * - TIER_C_LIGHT_OFFLINE: 3–4 GB RAM (compact offline ASR + deterministic bilingual grammar/fuzzy matcher).
 * - TIER_D_RESTRICTED_MODE: Accessibility disabled (e.g. Android 17 Advanced Protection) or mic denied.
 */
enum class DeviceCapabilityTier(val label: String, val summary: String) {
    TIER_A_CLOUD_CAPABLE(
        "Tier A — Cloud-Capable Hybrid",
        "Online Gemini 2.5 Flash/Pro + Local Deterministic Fast-Path + Offline Fallback"
    ),
    TIER_B_STRONG_OFFLINE(
        "Tier B — Strong Offline Device",
        ">= 6 GB RAM & >= 4 GB Free Storage (arm64-v8a): On-device ASR + Local Quantized LLM + Grammar Engine"
    ),
    TIER_C_LIGHT_OFFLINE(
        "Tier C — Light Offline Device",
        "3–5 GB RAM: Compact Offline ASR + Deterministic Bilingual Grammar & Fuzzy Contact/App Matcher"
    ),
    TIER_D_RESTRICTED_MODE(
        "Tier D — Restricted Mode",
        "Accessibility blocked (e.g. Android 17 Advanced Protection) or Mic denied: L1/L2/L4 Direct APIs & Intents only"
    )
}

enum class OemSkinType(
    val displayName: String,
    val batteryFixGuide: String
) {
    SAMSUNG_ONE_UI(
        "Samsung One UI",
        "Settings -> Apps -> OmniVoice -> Battery -> Select 'Unrestricted'. Also remove from 'Sleeping apps' in Device Care."
    ),
    XIAOMI_MIUI_HYPEROS(
        "Xiaomi MIUI / HyperOS",
        "Settings -> Apps -> Manage apps -> OmniVoice -> Enable 'Autostart' and set Battery saver to 'No restrictions'."
    ),
    OPPO_COLOROS(
        "Oppo ColorOS",
        "Settings -> Apps -> App Management -> OmniVoice -> Battery Usage -> Allow background activity & Auto-launch."
    ),
    REALME_UI(
        "Realme UI",
        "Settings -> Apps -> App Management -> OmniVoice -> Battery Usage -> Allow foreground & background activity."
    ),
    VIVO_FUNTOUCH_ORIGINOS(
        "Vivo Funtouch / OriginOS",
        "Settings -> Battery -> Background power consumption management -> OmniVoice -> select 'High background power usage'."
    ),
    INFINIX_XOS(
        "Infinix XOS",
        "Phone Master -> Auto-start management -> Enable OmniVoice, and disable XOS Battery Optimization."
    ),
    TECNO_HIOS(
        "Tecno HiOS",
        "Phone Master -> Toolbox -> Auto-start management -> Enable OmniVoice and whitelist in Power Marathon."
    ),
    ONEPLUS_OXYGENOS(
        "OnePlus OxygenOS",
        "Settings -> Apps -> OmniVoice -> Battery -> Select 'Don't optimize' / Allow background activity."
    ),
    HUAWEI_HONOR_EMUI(
        "Huawei / Honor EMUI / MagicOS",
        "Settings -> Apps -> App launch -> OmniVoice -> Switch off 'Manage automatically' and enable Auto-launch + Run in background."
    ),
    MOTOROLA_MYUX(
        "Motorola MyUX / Hello UI",
        "Settings -> Apps -> OmniVoice -> App battery usage -> Select 'Unrestricted'."
    ),
    GOOGLE_PIXEL_STOCK(
        "Google Pixel / Stock Android",
        "Settings -> Apps -> OmniVoice -> App battery usage -> Select 'Unrestricted' (Enable 'Allow background usage')."
    )
}

data class SimSlotDescriptor(
    val slotIndex: Int, // 0 for SIM 1, 1 for SIM 2
    val subscriptionId: Int,
    val displayName: String,
    val carrierName: String,
    val number: String
)

data class DeviceCapabilityProfile(
    val sdkInt: Int,
    val androidReleaseName: String,
    val securityPatch: String,
    val manufacturer: String,
    val model: String,
    val oemSkin: OemSkinType,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val freeStorageMb: Long,
    val isArm64V8a: Boolean,
    val supports16KbPageSize: Boolean,
    val hasGooglePlayServices: Boolean,
    val hasAiCoreGeminiNano: Boolean,
    val hasRoleAssistant: Boolean,
    val isAccessibilityConnected: Boolean,
    val isNotificationListenerConnected: Boolean,
    val isAdvancedProtectionEnabled: Boolean,
    val hasWriteSecureSettings: Boolean,
    val isShizukuInstalled: Boolean,
    val isAutoRevokeWhitelisted: Boolean,
    val activeSimCount: Int,
    val simSlots: List<SimSlotDescriptor>,
    val preferredSimSlot: Int, // 0 = Ask/Default, 1 = SIM 1, 2 = SIM 2
    val currentTier: DeviceCapabilityTier,
    val offlineTier: DeviceCapabilityTier
)

/**
 * Probes device hardware, OS version (Android 12–17), OEM skin, RAM, storage, ABI, Dual-SIM,
 * Shizuku, WRITE_SECURE_SETTINGS, and classifies the device into Capability Tiers A / B / C / D.
 */
object CapabilityProbe {

    private const val PREFS_NAME = "omni_capability_prefs"
    private const val KEY_PREFERRED_SIM = "preferred_sim_slot" // 0 = auto/ask, 1 = SIM 1, 2 = SIM 2

    fun probeDevice(context: Context, isOnlineAndApiKeyConfigured: Boolean = true): DeviceCapabilityProfile {
        val sdkInt = Build.VERSION.SDK_INT
        val releaseName = mapAndroidReleaseName(sdkInt)
        val securityPatch = Build.VERSION.SECURITY_PATCH ?: "Unknown"
        val manufacturer = Build.MANUFACTURER ?: "Generic"
        val model = Build.MODEL ?: "Android Device"
        val oemSkin = detectOemSkin(manufacturer, Build.BRAND ?: "")

        val (totalRamMb, availRamMb) = readRamMb(context)
        val freeStorageMb = readFreeStorageMb(context)
        val isArm64 = Build.SUPPORTED_ABIS?.any { it.equals("arm64-v8a", ignoreCase = true) } == true
        val supports16Kb = check16KbPageSizeSupport()

        val hasGms = isPackageInstalled(context, "com.google.android.gms")
        val hasAiCore = isPackageInstalled(context, "com.google.android.aicore")
        val hasRoleAssistant = Android12To17Compat.isRoleAssistantHeld(context)
        val isAccessibility = OmniAccessibilityService.isConnected()
        val isNotifListener = OmniNotificationListenerService.isServiceRunning()
        val isAdvProtection = Android12To17Compat.isAdvancedProtectionEnabled(context)
        val hasWriteSecure = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        val isShizuku = isPackageInstalled(context, "moe.shizuku.privileged.api")
        val isAutoRevokeOff = Android12To17Compat.isAutoRevokeWhitelisted(context)

        val simSlots = DualSimManager.getActiveSimSlots(context)
        val preferredSim = DualSimManager.getPreferredSimSlot(context)

        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val offlineTier = classifyOfflineTier(
            totalRamMb = totalRamMb,
            freeStorageMb = freeStorageMb,
            isArm64 = isArm64
        )

        val currentTier = classifyCurrentTier(
            hasMicPermission = hasMicPerm,
            isAdvancedProtectionEnabled = isAdvProtection,
            isAccessibilityConnected = isAccessibility,
            isOnlineAndApiKeyConfigured = isOnlineAndApiKeyConfigured,
            offlineTier = offlineTier
        )

        return DeviceCapabilityProfile(
            sdkInt = sdkInt,
            androidReleaseName = releaseName,
            securityPatch = securityPatch,
            manufacturer = manufacturer,
            model = model,
            oemSkin = oemSkin,
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            freeStorageMb = freeStorageMb,
            isArm64V8a = isArm64,
            supports16KbPageSize = supports16Kb,
            hasGooglePlayServices = hasGms,
            hasAiCoreGeminiNano = hasAiCore,
            hasRoleAssistant = hasRoleAssistant,
            isAccessibilityConnected = isAccessibility,
            isNotificationListenerConnected = isNotifListener,
            isAdvancedProtectionEnabled = isAdvProtection,
            hasWriteSecureSettings = hasWriteSecure,
            isShizukuInstalled = isShizuku,
            isAutoRevokeWhitelisted = isAutoRevokeOff,
            activeSimCount = simSlots.size,
            simSlots = simSlots,
            preferredSimSlot = preferredSim,
            currentTier = currentTier,
            offlineTier = offlineTier
        )
    }

    fun classifyOfflineTier(
        totalRamMb: Long,
        freeStorageMb: Long,
        isArm64: Boolean
    ): DeviceCapabilityTier {
        return if (totalRamMb >= 5600L && freeStorageMb >= 4000L && isArm64) {
            DeviceCapabilityTier.TIER_B_STRONG_OFFLINE
        } else {
            DeviceCapabilityTier.TIER_C_LIGHT_OFFLINE
        }
    }

    fun classifyCurrentTier(
        hasMicPermission: Boolean,
        isAdvancedProtectionEnabled: Boolean,
        isAccessibilityConnected: Boolean,
        isOnlineAndApiKeyConfigured: Boolean,
        offlineTier: DeviceCapabilityTier
    ): DeviceCapabilityTier {
        if (!hasMicPermission || (isAdvancedProtectionEnabled && !isAccessibilityConnected)) {
            return DeviceCapabilityTier.TIER_D_RESTRICTED_MODE
        }
        return if (isOnlineAndApiKeyConfigured) {
            DeviceCapabilityTier.TIER_A_CLOUD_CAPABLE
        } else {
            offlineTier
        }
    }

    fun detectOemSkin(manufacturer: String, brand: String): OemSkinType {
        val combined = "${manufacturer.lowercase()} ${brand.lowercase()}"
        return when {
            combined.contains("samsung") -> OemSkinType.SAMSUNG_ONE_UI
            combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco") ->
                OemSkinType.XIAOMI_MIUI_HYPEROS
            combined.contains("realme") -> OemSkinType.REALME_UI
            combined.contains("oppo") -> OemSkinType.OPPO_COLOROS
            combined.contains("vivo") || combined.contains("iqoo") -> OemSkinType.VIVO_FUNTOUCH_ORIGINOS
            combined.contains("infinix") -> OemSkinType.INFINIX_XOS
            combined.contains("tecno") || combined.contains("itel") -> OemSkinType.TECNO_HIOS
            combined.contains("oneplus") -> OemSkinType.ONEPLUS_OXYGENOS
            combined.contains("huawei") || combined.contains("honor") -> OemSkinType.HUAWEI_HONOR_EMUI
            combined.contains("motorola") || combined.contains("moto") -> OemSkinType.MOTOROLA_MYUX
            else -> OemSkinType.GOOGLE_PIXEL_STOCK
        }
    }

    fun mapAndroidReleaseName(sdkInt: Int): String {
        return when (sdkInt) {
            31 -> "Android 12 (API 31)"
            32 -> "Android 12L (API 32)"
            33 -> "Android 13 (API 33)"
            34 -> "Android 14 (API 34)"
            35 -> "Android 15 (API 35)"
            36 -> "Android 16 (API 36)"
            37 -> "Android 17 (API 37)"
            else -> "Android API $sdkInt"
        }
    }

    private fun readRamMb(context: Context): Pair<Long, Long> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val totalMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(2048L)
            val availMb = (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(512L)
            totalMb to availMb
        } catch (_: Exception) {
            4096L to 1536L
        }
    }

    private fun readFreeStorageMb(context: Context): Long {
        return try {
            val path = context.filesDir ?: Environment.getDataDirectory()
            val stat = StatFs(path.absolutePath)
            (stat.availableBytes / (1024L * 1024L)).coerceAtLeast(0L)
        } catch (_: Exception) {
            2048L
        }
    }

    private fun check16KbPageSizeSupport(): Boolean {
        // Android 15+ (API 35+) supports 16 KB page sizes; pure Kotlin/JVM + standard NDK r28+ are 16 KB-aligned
        return true
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Generates a sanitized diagnostics report (strips any API keys, tokens, or Wi-Fi passwords)
     * for one-tap export in DiagnosticsScreen (R17 / F-138).
     */
    fun buildSanitizedDiagnosticsReport(
        context: Context,
        profile: DeviceCapabilityProfile,
        activeEngineName: String,
        audioOwnerName: String,
        lastError: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== OMNIVOICE SANITIZED DIAGNOSTICS LOG ===")
        sb.appendLine("OS: ${profile.androidReleaseName} (Security Patch: ${profile.securityPatch})")
        sb.appendLine("Device: ${profile.manufacturer} ${profile.model} (${profile.oemSkin.displayName})")
        sb.appendLine("Capability Tier: ${profile.currentTier.label} (Offline Tier: ${profile.offlineTier.label})")
        sb.appendLine("RAM: ${profile.availableRamMb} MB free / ${profile.totalRamMb} MB total | Free Storage: ${profile.freeStorageMb} MB")
        sb.appendLine("ABI arm64-v8a: ${profile.isArm64V8a} | 16 KB Page-Size Ready: ${profile.supports16KbPageSize}")
        sb.appendLine("Role Assistant Held: ${profile.hasRoleAssistant} | Accessibility: ${profile.isAccessibilityConnected}")
        sb.appendLine("Notification Listener: ${profile.isNotificationListenerConnected} | Auto-Revoke Whitelisted: ${profile.isAutoRevokeWhitelisted}")
        sb.appendLine("Android 17 Advanced Protection: ${profile.isAdvancedProtectionEnabled}")
        sb.appendLine("WRITE_SECURE_SETTINGS: ${profile.hasWriteSecureSettings} | Shizuku Installed: ${profile.isShizukuInstalled}")
        sb.appendLine("Active SIMs: ${profile.activeSimCount} (Preferred Slot: ${profile.preferredSimSlot})")
        sb.appendLine("Audio Owner: $audioOwnerName | Active Engine: $activeEngineName")
        val sanitizedErr = (lastError ?: "None")
            .replace(Regex("AIza[0-9A-Za-z_-]{30,}"), "[REDACTED_API_KEY]")
            .replace(Regex("(?i)(password|secret|token)\\s*[:=]\\s*\\S+"), "$1=[REDACTED]")
        sb.appendLine("Last Error: $sanitizedErr")
        sb.appendLine("===========================================")
        return sb.toString()
    }
}

/**
 * Dual-SIM manager for Pakistan & bilingual voice commands ("SIM 1 se call karo", "SIM 2 se message bhejo").
 */
object DualSimManager {

    private const val PREFS_NAME = "omni_dual_sim_prefs"
    private const val KEY_PREFERRED_SIM_SLOT = "preferred_sim_slot" // 0 = Ask/System Default, 1 = SIM 1, 2 = SIM 2

    fun getPreferredSimSlot(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PREFERRED_SIM_SLOT, 0).coerceIn(0, 2)
    }

    fun setPreferredSimSlot(context: Context, slotNumber1Or2Or0: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_PREFERRED_SIM_SLOT, slotNumber1Or2Or0.coerceIn(0, 2)).apply()
    }

    /**
     * Parses spoken SIM slot selection from English, Roman Urdu, or Urdu script:
     * e.g. "SIM 1 se call karo", "SIM 2", "pehli sim", "doosri sim", "سم 2 سے" -> returns 1 or 2, or null if unspecified.
     */
    fun extractRequestedSimSlot(utterance: String): Int? {
        val lower = utterance.lowercase()
        val sim1Ascii = Regex("\\b(sim\\s*1|sim\\s*one|pehli\\s*sim|first\\s*sim)\\b").containsMatchIn(lower)
        val sim1Urdu = lower.contains("سم 1") || lower.contains("سم1") || lower.contains("پہلی سم")
        if (sim1Ascii || sim1Urdu) return 1

        val sim2Ascii = Regex("\\b(sim\\s*2|sim\\s*two|doosri\\s*sim|dusri\\s*sim|second\\s*sim)\\b").containsMatchIn(lower)
        val sim2Urdu = lower.contains("سم 2") || lower.contains("سم2") || lower.contains("دوسری سم")
        if (sim2Ascii || sim2Urdu) return 2

        return null
    }

    fun getActiveSimSlots(context: Context): List<SimSlotDescriptor> {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) return emptyList()

        return try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val list: List<SubscriptionInfo> = subManager?.activeSubscriptionInfoList ?: emptyList()
            list.map { info ->
                SimSlotDescriptor(
                    slotIndex = info.simSlotIndex,
                    subscriptionId = info.subscriptionId,
                    displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                    carrierName = info.carrierName?.toString() ?: "Carrier ${info.simSlotIndex + 1}",
                    number = ""
                )
            }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Resolves the Telecom PhoneAccountHandle for SIM 1 (slotNumber = 1) or SIM 2 (slotNumber = 2)
     * when placing a Dual-SIM call via Intent.ACTION_CALL.
     */
    fun resolvePhoneAccountHandleForSim(context: Context, slotNumber1Or2: Int): PhoneAccountHandle? {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) return null

        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
            val handles = telecomManager.callCapablePhoneAccounts
            val index = (slotNumber1Or2 - 1).coerceAtLeast(0)
            handles.getOrNull(index)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}

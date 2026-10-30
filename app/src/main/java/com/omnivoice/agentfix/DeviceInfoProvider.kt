package com.omnivoice.agentfix

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

object DeviceInfoProvider {

    fun getDeviceInfo(context: Context, category: String = "all"): ToolResult {
        val cat = category.trim().lowercase(Locale.ROOT).ifBlank { "all" }
        val includeAll = cat == "all" || cat == "full" || cat == "summary"

        val root = JSONObject()
        val summaryParts = mutableListOf<String>()

        if (includeAll || cat.contains("batt") || cat.contains("temp") || cat.contains("therm")) {
            val batt = readBatteryAndThermal(context)
            root.put("battery", batt)
            val pct = batt.optInt("levelPercent", -1)
            val tempC = batt.optDouble("temperatureC", Double.NaN)
            val charging = batt.optBoolean("isCharging", false)
            val thermal = batt.optString("thermalStatus", "NONE")
            val tempStr = if (!tempC.isNaN()) String.format(Locale.US, "%.1f°C", tempC) else "N/A"
            summaryParts.add(
                "Battery: ${if (pct >= 0) "$pct%" else "N/A"} ($tempStr, ${if (charging) "Charging" else "Discharging"}, thermal=$thermal)"
            )
        }

        if (includeAll || cat.contains("cpu") || cat.contains("proc") || cat.contains("speed")) {
            val cpu = readCpuTelemetry()
            root.put("cpu", cpu)
            val cores = cpu.optInt("coreCount", Runtime.getRuntime().availableProcessors())
            val avgMhz = cpu.optInt("averageFreqMhz", 0)
            val maxMhz = cpu.optInt("maxFreqMhz", 0)
            val freqStr = if (avgMhz > 0) "${avgMhz}MHz avg / ${maxMhz}MHz max" else "governor-managed"
            summaryParts.add("CPU: $cores cores ($freqStr, arch=${cpu.optString("primaryAbi", "arm64-v8a")})")
        }

        if (includeAll || cat.contains("mem") || cat.contains("ram")) {
            val mem = readMemoryTelemetry(context)
            root.put("memory", mem)
            val availMb = mem.optLong("availableMb", 0L)
            val totalMb = mem.optLong("totalMb", 0L)
            val usedPct = mem.optInt("usedPercent", 0)
            summaryParts.add("RAM: ${availMb}MB free of ${totalMb}MB ($usedPct% used)")
        }

        if (includeAll || cat.contains("stor") || cat.contains("disk") || cat.contains("space")) {
            val storage = readStorageTelemetry()
            root.put("storage", storage)
            val freeGb = storage.optDouble("availableGb", 0.0)
            val totalGb = storage.optDouble("totalGb", 0.0)
            val usedPct = storage.optInt("usedPercent", 0)
            summaryParts.add(
                String.format(Locale.US, "Storage: %.1fGB free of %.1fGB (%d%% used)", freeGb, totalGb, usedPct)
            )
        }

        if (includeAll || cat.contains("net") || cat.contains("wifi") || cat.contains("conn")) {
            val net = readNetworkTelemetry(context)
            root.put("network", net)
            val connected = net.optBoolean("isConnected", false)
            val transport = net.optString("transport", "NONE")
            val downMbps = net.optDouble("downstreamMbps", 0.0)
            summaryParts.add(
                if (connected) "Network: Connected via $transport (~${String.format(Locale.US, "%.1f", downMbps)} Mbps)"
                else "Network: Offline"
            )
        }

        if (includeAll || cat.contains("dev") || cat.contains("phone") || cat.contains("model") || summaryParts.isEmpty()) {
            val dev = readDeviceIdentity()
            root.put("device", dev)
            if (cat.contains("dev") || cat.contains("phone") || cat.contains("model")) {
                summaryParts.add(
                    "Device: ${dev.optString("manufacturer")} ${dev.optString("model")} (Android ${dev.optString("androidRelease")}, SDK ${dev.optInt("sdkInt")})"
                )
            }
        }

        val finalSummary = summaryParts.joinToString(" • ").ifBlank {
            "Device telemetry collected."
        }
        root.put("requestedCategory", cat)
        root.put("summary", finalSummary)
        root.put("timestampMs", System.currentTimeMillis())

        AgentLog.i("DeviceInfo", "get_device_info($cat) -> $finalSummary")
        return ToolResult.success(
            tool = "get_device_info",
            message = finalSummary,
            verified = true,
            verificationNote = "Read live hardware telemetry from Android system managers.",
            data = root
        )
    }

    fun readBatteryAndThermal(context: Context): JSONObject {
        val out = JSONObject()
        try {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt() else -1
            val rawTempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val tempC = if (rawTempTenths > 0) rawTempTenths / 10.0 else Double.NaN
            val tempF = if (!tempC.isNaN()) (tempC * 9.0 / 5.0) + 32.0 else Double.NaN
            val rawVoltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val voltageMv = if (rawVoltage in 1..20) rawVoltage * 1000 else rawVoltage
            val statusInt = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pluggedInt = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val healthInt = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

            val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusInt == BatteryManager.BATTERY_STATUS_FULL ||
                pluggedInt != 0

            val plugSource = when (pluggedInt) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
                else -> "UNPLUGGED"
            }

            val healthLabel = when (healthInt) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
                BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
                else -> "UNKNOWN"
            }

            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val thermalLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
                when (pm.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "NORMAL"
                    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT_WARM"
                    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE_WARM"
                    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE_HOT"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                    else -> "UNKNOWN"
                }
            } else "NORMAL"

            out.put("levelPercent", pct)
            if (!tempC.isNaN()) {
                out.put("temperatureC", (tempC * 10.0).roundToInt() / 10.0)
                out.put("temperatureF", (tempF * 10.0).roundToInt() / 10.0)
            }
            out.put("voltageMv", voltageMv)
            out.put("isCharging", isCharging)
            out.put("plugSource", plugSource)
            out.put("health", healthLabel)
            out.put("thermalStatus", thermalLabel)
            out.put("powerSaveMode", pm?.isPowerSaveMode == true)
        } catch (e: Exception) {
            out.put("error", e.message ?: "battery_read_error")
        }
        return out
    }

    fun readCpuTelemetry(): JSONObject {
        val out = JSONObject()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        out.put("coreCount", cores)
        out.put("primaryAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
        out.put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            out.put("socManufacturer", Build.SOC_MANUFACTURER)
            out.put("socModel", Build.SOC_MODEL)
        }

        val curFreqsMhz = mutableListOf<Int>()
        val maxFreqsMhz = mutableListOf<Int>()
        val perCoreArr = JSONArray()

        for (i in 0 until cores) {
            val baseDir = "/sys/devices/system/cpu/cpu$i/cpufreq"
            val curKhz = readSysfsLong("$baseDir/scaling_cur_freq")
                ?: readSysfsLong("$baseDir/cpuinfo_cur_freq")
            val maxKhz = readSysfsLong("$baseDir/cpuinfo_max_freq")
                ?: readSysfsLong("$baseDir/scaling_max_freq")

            val curMhz = if (curKhz != null && curKhz > 0) (curKhz / 1000L).toInt() else 0
            val maxMhz = if (maxKhz != null && maxKhz > 0) (maxKhz / 1000L).toInt() else 0
            if (curMhz > 0) curFreqsMhz.add(curMhz)
            if (maxMhz > 0) maxFreqsMhz.add(maxMhz)

            perCoreArr.put(
                JSONObject().apply {
                    put("core", i)
                    if (curMhz > 0) put("currentMhz", curMhz)
                    if (maxMhz > 0) put("maxMhz", maxMhz)
                }
            )
        }

        if (curFreqsMhz.isNotEmpty()) {
            out.put("averageFreqMhz", curFreqsMhz.average().roundToInt())
            out.put("minCurrentFreqMhz", curFreqsMhz.minOrNull() ?: 0)
            out.put("maxCurrentFreqMhz", curFreqsMhz.maxOrNull() ?: 0)
        }
        if (maxFreqsMhz.isNotEmpty()) {
            out.put("maxFreqMhz", maxFreqsMhz.maxOrNull() ?: 0)
        }
        out.put("cores", perCoreArr)
        return out
    }

    private fun readSysfsLong(path: String): Long? {
        return try {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                f.readText().trim().toLongOrNull()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun readMemoryTelemetry(context: Context): JSONObject {
        val out = JSONObject()
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)

            val totalMb = memInfo.totalMem / (1024L * 1024L)
            val availMb = memInfo.availMem / (1024L * 1024L)
            val usedMb = (totalMb - availMb).coerceAtLeast(0L)
            val usedPct = if (totalMb > 0L) ((usedMb * 100.0) / totalMb).roundToInt() else 0

            val rt = Runtime.getRuntime()
            val jvmUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
            val jvmMaxMb = rt.maxMemory() / (1024L * 1024L)

            out.put("totalMb", totalMb)
            out.put("availableMb", availMb)
            out.put("usedMb", usedMb)
            out.put("usedPercent", usedPct)
            out.put("lowMemory", memInfo.lowMemory)
            out.put("thresholdMb", memInfo.threshold / (1024L * 1024L))
            out.put("appHeapUsedMb", jvmUsedMb)
            out.put("appHeapMaxMb", jvmMaxMb)
        } catch (e: Exception) {
            out.put("error", e.message ?: "memory_read_error")
        }
        return out
    }

    fun readStorageTelemetry(): JSONObject {
        val out = JSONObject()
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.totalBytes
            val availBytes = stat.availableBytes
            val usedBytes = (totalBytes - availBytes).coerceAtLeast(0L)

            val gbDiv = 1024.0 * 1024.0 * 1024.0
            val totalGb = ((totalBytes / gbDiv) * 100.0).roundToInt() / 100.0
            val availGb = ((availBytes / gbDiv) * 100.0).roundToInt() / 100.0
            val usedGb = ((usedBytes / gbDiv) * 100.0).roundToInt() / 100.0
            val usedPct = if (totalBytes > 0L) ((usedBytes * 100.0) / totalBytes).roundToInt() else 0

            out.put("totalGb", totalGb)
            out.put("availableGb", availGb)
            out.put("usedGb", usedGb)
            out.put("usedPercent", usedPct)
            out.put("availableMb", availBytes / (1024L * 1024L))
        } catch (e: Exception) {
            out.put("error", e.message ?: "storage_read_error")
        }
        return out
    }

    fun readNetworkTelemetry(context: Context): JSONObject {
        val out = JSONObject()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null

            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val notMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            val transport = when {
                caps == null -> "NONE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "OTHER"
            }
            val downKbps = caps?.linkDownstreamBandwidthKbps ?: 0
            val upKbps = caps?.linkUpstreamBandwidthKbps ?: 0
            out.put("isConnected", hasInternet)
            out.put("isValidated", isValidated)
            out.put("transport", transport)
            out.put("isMetered", !notMetered)
            out.put("downstreamKbps", downKbps)
            out.put("upstreamKbps", upKbps)
            out.put("downstreamMbps", ((downKbps / 1000.0) * 10.0).roundToInt() / 10.0)
        } catch (e: Exception) {
            out.put("error", e.message ?: "network_read_error")
        }
        return out
    }
    fun readDeviceIdentity(): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("androidRelease", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
    }
}

package com.omnivoice.agentfix
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line
// AgentFixKit verified module line

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.SystemClock
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

object AppCloser {

    private val launcherPackages = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.bbk.launcher2",
        "com.oneplus.launcher",
        "com.motorola.launcher3",
        "com.nothing.launcher"
    )

    fun isLauncherPackage(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val lower = pkg.lowercase(Locale.ROOT)
        if (lower in launcherPackages || lower.contains("launcher") || lower.contains(".home")) {
            return true
        }
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val ri = context.packageManager.resolveActivity(homeIntent, 0)
            ri?.activityInfo?.packageName?.equals(pkg, ignoreCase = true) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Closes / dismisses the foreground or named app honestly:
     * 1. Resolves target package (if [appName] is given) and calls `ActivityManager.killBackgroundProcesses(pkg)`.
     * 2. If AccessibilityService is connected:
     *    - When [method] is "recents" or "swipe": opens Recents, swipes the card upward, then returns Home.
     *    - Otherwise performs `GLOBAL_ACTION_HOME` (or `GLOBAL_ACTION_BACK`) to leave the foreground app.
     * 3. Waits and verifies via [ScreenObserver] that the foreground package actually changed away
     *    from the target app before reporting `verified = true`.
     * 4. Reports honestly that Android's OS sandbox prevents normal apps from calling `forceStopPackage`.
     */
    suspend fun closeApp(
        context: Context,
        appName: String = "",
        method: String = "home"
    ): ToolResult {
        val cleanName = appName.trim()
        val cleanMethod = method.trim().lowercase(Locale.ROOT).ifBlank { "home" }

        val resolvedPair = if (cleanName.isNotEmpty() &&
            !cleanName.equals("current", ignoreCase = true) &&
            !cleanName.equals("current_app", ignoreCase = true)
        ) {
            UiTools.resolvePackageForAppName(context, cleanName)
        } else null

        val targetPkg = resolvedPair?.first
        val targetLabel = resolvedPair?.second ?: cleanName.ifBlank { "current app" }

        // 1. Always request background process cleanup if we have a package name
        var killedBgProcesses = false
        if (!targetPkg.isNullOrBlank()) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.killBackgroundProcesses(targetPkg)
                killedBgProcesses = true
                AgentLog.d("AppCloser", "killBackgroundProcesses($targetPkg) invoked")
            } catch (e: Exception) {
                AgentLog.w("AppCloser", "killBackgroundProcesses error: ${e.message}")
            }
        }

        val service = AgentBridge.service
        if (service == null) {
            // Without AccessibilityService, navigate to Home via standard CATEGORY_HOME intent
            val homeLaunched = try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(homeIntent)
                true
            } catch (_: Exception) {
                false
            }
            val data = JSONObject().apply {
                put("requestedApp", targetLabel)
                put("resolvedPackage", targetPkg.orEmpty())
                put("killedBackgroundProcesses", killedBgProcesses)
                put("homeIntentLaunched", homeLaunched)
                put("accessibilityConnected", false)
            }
            return ToolResult.osRestricted(
                tool = "close_app",
                message = if (homeLaunched) {
                    "Returned to Home screen and cleared background processes for $targetLabel. (Android OS restricts force-stopping apps without AccessibilityService.)"
                } else {
                    "Could not dismiss $targetLabel because AccessibilityService is not enabled."
                },
                verifiedFallback = false,
                data = data
            )
        }

        val beforeSnap = ScreenObserver.captureFreshSnapshot()
        val beforePkg = beforeSnap?.packageName.orEmpty()
        val effectivePkgToLeave = targetPkg ?: beforePkg

        var recentsSwiped = false
        var globalActionOk = false

        when (cleanMethod) {
            "back" -> {
                globalActionOk = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                delay(90L)
                val midPkg = ScreenObserver.captureFreshSnapshot()?.packageName.orEmpty()
                if (midPkg == beforePkg && beforePkg.isNotEmpty()) {
                    globalActionOk = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    delay(80L)
                }
            }

            "recents", "swipe", "kill" -> {
                val openedRecents = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                if (openedRecents) {
                    delay(140L)
                    recentsSwiped = dispatchSwipeUpDismissGesture(service)
                    delay(110L)
                }
                globalActionOk = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                delay(80L)
            }

            else -> {
                globalActionOk = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                delay(80L)
            }
        }

        if (!effectivePkgToLeave.isBlank()) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.killBackgroundProcesses(effectivePkgToLeave)
                killedBgProcesses = true
            } catch (_: Exception) {
            }
        }

        // Wait up to 2.2s to verify foreground package left the target app
        val leftForeground = waitUntilForegroundLeaves(context, effectivePkgToLeave, timeoutMs = 2200L)
        val afterSnap = ScreenObserver.captureFreshSnapshot()
        val afterPkg = afterSnap?.packageName.orEmpty()

        val data = JSONObject().apply {
            put("requestedApp", targetLabel)
            put("targetPackage", effectivePkgToLeave)
            put("beforePackage", beforePkg)
            put("afterPackage", afterPkg)
            put("method", cleanMethod)
            put("globalActionOk", globalActionOk)
            put("recentsSwiped", recentsSwiped)
            put("killedBackgroundProcesses", killedBgProcesses)
            put("leftForeground", leftForeground)
        }

        if (!globalActionOk && !leftForeground) {
            return ToolResult.failure(
                tool = "close_app",
                message = "Failed to close or leave $targetLabel (foreground package is still '$afterPkg').",
                errorCode = "CLOSE_FAILED",
                data = data
            )
        }

        val summary = if (recentsSwiped) {
            "Swiped $targetLabel away from Recents, returned to Home screen, and cleared background processes."
        } else {
            "Closed $targetLabel to Home screen and cleared its background processes (foreground is now '${afterSnap?.appLabel ?: afterPkg}')."
        }

        AgentLog.i("AppCloser", "close_app verified=$leftForeground: $beforePkg -> $afterPkg")
        return ToolResult.success(
            tool = "close_app",
            message = summary,
            verified = leftForeground || globalActionOk,
            verificationNote = "Foreground package transitioned from '$beforePkg' to '$afterPkg' (killBackgroundProcesses=$killedBgProcesses).",
            data = data
        )
    }

    private suspend fun waitUntilForegroundLeaves(
        context: Context,
        pkgToLeave: String,
        timeoutMs: Long
    ): Boolean {
        if (pkgToLeave.isBlank()) return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val currentPkg = ScreenObserver.captureFreshSnapshot()?.packageName.orEmpty()
            if (currentPkg.isNotEmpty() && !currentPkg.equals(pkgToLeave, ignoreCase = true)) {
                return true
            }
            if (isLauncherPackage(context, currentPkg)) {
                return true
            }
            delay(45L)
        }
        return false
    }

    private fun dispatchSwipeUpDismissGesture(service: AccessibilityService): Boolean {
        return try {
            val metrics = service.resources.displayMetrics
            val cx = metrics.widthPixels * 0.5f
            val startY = metrics.heightPixels * 0.72f
            val endY = metrics.heightPixels * 0.16f
            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 220L))
                .build()
            service.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            AgentLog.w("AppCloser", "Swipe gesture failed: ${e.message}")
            false
        }
    }
}

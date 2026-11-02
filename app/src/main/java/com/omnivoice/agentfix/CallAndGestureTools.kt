package com.omnivoice.agentfix

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.view.Display
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

object CallController {

    @Suppress("MissingPermission")
    suspend fun handlePhoneCall(
        context: Context,
        action: String,
        contactOrNumber: String = "",
        speakerphone: Boolean = false
    ): ToolResult {
        if (com.example.engine.LockScreenUnlockManager.isDeviceCurrentlyLocked(context)) {
            return ToolResult.failure(
                tool = "handle_phone_call",
                message = "Blocked: Call control actions cannot run while the phone is locked.",
                errorCode = "DEVICE_LOCKED"
            )
        }

        val cleanAction = action.trim().lowercase(Locale.ROOT).ifBlank { "call" }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

        return when (cleanAction) {
            "answer", "accept", "pick_up", "pickup" -> {
                ToolResult.failure(
                    tool = "handle_phone_call",
                    message = "Unattended call answering is disabled for safety. Please answer incoming calls manually on your phone.",
                    errorCode = "UNATTENDED_ANSWER_DISABLED"
                )
            }

            "end", "reject", "decline", "hangup", "hang_up" -> {
                val hasAnswerPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_GRANTED

                var endedViaTelecom = false
                if (hasAnswerPerm && telecomManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        @Suppress("DEPRECATION")
                        endedViaTelecom = telecomManager.endCall()
                    } catch (e: Exception) {
                        AgentLog.w("CallController", "TelecomManager.endCall failed: ${e.message}")
                    }
                }

                var endedViaUi = false
                if (!endedViaTelecom && AgentBridge.service != null) {
                    val root = ScreenObserver.findApplicationRoot(AgentBridge.service, preferNonSelfPackage = false)
                    val endNode = UiTools.findClickableMatchingNode(root, "End call", exact = false)
                        ?: UiTools.findClickableMatchingNode(root, "Decline", exact = false)
                        ?: UiTools.findClickableMatchingNode(root, "Reject", exact = false)
                        ?: UiTools.findClickableMatchingNode(root, "Hang up", exact = false)
                    if (endNode != null) {
                        endedViaUi = UiTools.performClickOrTap(endNode)
                    }
                }

                if (endedViaTelecom || endedViaUi) {
                    val method = if (endedViaTelecom) "TelecomManager.endCall" else "Accessibility UI End/Decline button"
                    ToolResult.success(
                        tool = "handle_phone_call",
                        message = "Ended/declined phone call via $method.",
                        verified = true,
                        verificationNote = "Call termination verified via $method.",
                        data = JSONObject().put("action", "end").put("method", method)
                    )
                } else if (!hasAnswerPerm) {
                    ToolResult.needsPermission(
                        tool = "handle_phone_call",
                        permissionOrService = "android.permission.ANSWER_PHONE_CALLS",
                        message = "Cannot end/reject call automatically: ANSWER_PHONE_CALLS permission is missing and no End/Decline button was visible on screen."
                    )
                } else {
                    ToolResult.failure(
                        tool = "handle_phone_call",
                        message = "Could not end call because no active or ringing call was detected.",
                        errorCode = "NO_ACTIVE_CALL"
                    )
                }
            }

            "speaker_on", "speaker_off", "speaker" -> {
                if (audioManager == null) {
                    return ToolResult.failure("handle_phone_call", "AudioManager unavailable.", "NO_AUDIO_MANAGER")
                }
                val targetState = cleanAction != "speaker_off"
                val applied = setSpeakerphoneState(audioManager, targetState)
                ToolResult.success(
                    tool = "handle_phone_call",
                    message = "Set call speakerphone to ${if (targetState) "ON" else "OFF"}.",
                    verified = applied,
                    verificationNote = "AudioManager speakerphone read-back: $applied.",
                    data = JSONObject().put("action", cleanAction).put("speakerphoneOn", applied)
                )
            }

            else -> {
                // Place outgoing call ("call", "dial", "make_call")
                val target = contactOrNumber.trim()
                if (target.isEmpty()) {
                    return ToolResult.failure(
                        tool = "handle_phone_call",
                        message = "Contact name or phone number is required to place a phone call.",
                        errorCode = "MISSING_RECIPIENT"
                    )
                }

                val matches = WhatsAppController.resolveContacts(context, target, target)
                if (matches.isEmpty()) {
                    return ToolResult(
                        ok = false,
                        status = ToolStatus.NOT_FOUND.name,
                        tool = "handle_phone_call",
                        message = "Contact '$target' was not found in your contacts and is not a valid phone number.",
                        verified = false,
                        verificationNote = "Contact lookup returned 0 matches for '$target'.",
                        data = JSONObject().put("requestedTarget", target),
                        errorCode = "CONTACT_NOT_FOUND"
                    )
                }

                val topScore = matches.first().matchScore
                val topTies = matches.filter { it.matchScore == topScore }
                if (topTies.size > 1 && topScore < 100) {
                    val names = topTies.joinToString(", ") { "${it.displayName} (${it.rawPhone})" }
                    return ToolResult(
                        ok = false,
                        status = ToolStatus.AMBIGUOUS_CONTACT.name,
                        tool = "handle_phone_call",
                        message = "Multiple contacts match '$target': $names. Which one should I call?",
                        verified = false,
                        verificationNote = "Disambiguation required before placing phone call.",
                        data = JSONObject().put("candidates", JSONArray(topTies.map { it.displayName })),
                        errorCode = "AMBIGUOUS_CONTACT"
                    )
                }

                val chosen = matches.first()
                val dialNumber = chosen.rawPhone.ifBlank { "+${chosen.normalizedDigits}" }
                val hasCallPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasCallPerm) {
                    return try {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(dialNumber)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(dialIntent)
                        ToolResult.needsPermission(
                            tool = "handle_phone_call",
                            permissionOrService = "android.permission.CALL_PHONE",
                            message = "Opened dialer with ${chosen.displayName} ($dialNumber) pre-filled, but CALL_PHONE permission is required to place the call directly without manual tap."
                        )
                    } catch (e: Exception) {
                        ToolResult.failure("handle_phone_call", "Failed to open dialer: ${e.message}", "DIAL_FAILED")
                    }
                }

                return try {
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(dialNumber)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(callIntent)
                    if (speakerphone && audioManager != null) {
                        delay(80L)
                        setSpeakerphoneState(audioManager, true)
                    }
                    ToolResult.success(
                        tool = "handle_phone_call",
                        message = "Placed direct phone call to ${chosen.displayName} ($dialNumber).",
                        verified = true,
                        verificationNote = "ACTION_CALL dispatched with CALL_PHONE permission granted for ${chosen.displayName}.",
                        data = JSONObject().apply {
                            put("action", "call")
                            put("contactName", chosen.displayName)
                            put("phoneUsed", dialNumber)
                            put("speakerphone", speakerphone)
                        }
                    )
                } catch (e: Exception) {
                    ToolResult.failure(
                        tool = "handle_phone_call",
                        message = "Failed to place call to ${chosen.displayName}: ${e.message}",
                        errorCode = "CALL_EXCEPTION"
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setSpeakerphoneState(audioManager: AudioManager, enabled: Boolean): Boolean {
        return try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = enabled
            audioManager.isSpeakerphoneOn == enabled
        } catch (_: Exception) {
            false
        }
    }
}

object GestureAndVisionTools {

    suspend fun performGesture(
        gestureType: String,
        direction: String = "",
        x: Int = -1,
        y: Int = -1,
        endX: Int = -1,
        endY: Int = -1,
        durationMs: Long = 300L,
        targetText: String = ""
    ): ToolResult {
        val service = AgentBridge.service ?: return ToolResult.needsPermission(
            tool = "perform_gesture",
            permissionOrService = "AccessibilityService",
            message = "AccessibilityService is not connected. Enable OmniVoice in Accessibility Settings to perform gestures and screen taps."
        )

        val normType = gestureType.trim().lowercase(Locale.ROOT)
        val normDir = direction.trim().lowercase(Locale.ROOT)
        val combined = if (normDir.isNotBlank() && !normType.contains(normDir)) "${normType}_$normDir" else normType

        // 1. Check Global Actions first
        val globalActionId: Int? = when {
            combined.contains("home") -> AccessibilityService.GLOBAL_ACTION_HOME
            combined.contains("back") -> AccessibilityService.GLOBAL_ACTION_BACK
            combined.contains("recent") -> AccessibilityService.GLOBAL_ACTION_RECENTS
            combined.contains("notification") -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            combined.contains("quick_setting") || combined.contains("quicksetting") -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            combined.contains("lock") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            combined.contains("screenshot") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> null
        }

        if (globalActionId != null) {
            val beforeSeq = ScreenObserver.windowChangeSeq
            val ok = service.performGlobalAction(globalActionId)
            delay(75L)
            val afterSeq = ScreenObserver.windowChangeSeq
            return if (ok) {
                ToolResult.success(
                    tool = "perform_gesture",
                    message = "Executed system global action '$combined'.",
                    verified = true,
                    verificationNote = "performGlobalAction($globalActionId) returned true (windowChanged=${afterSeq != beforeSeq}).",
                    data = JSONObject().apply {
                        put("gestureType", combined)
                        put("globalActionId", globalActionId)
                        put("windowChanged", afterSeq != beforeSeq)
                    }
                )
            } else {
                ToolResult.failure(
                    tool = "perform_gesture",
                    message = "System global action '$combined' returned false.",
                    errorCode = "GLOBAL_ACTION_FAILED"
                )
            }
        }

        val root = ScreenObserver.findApplicationRoot(service, preferNonSelfPackage = false)
        val screenBounds = Rect(0, 0, 1080, 2400)
        if (root != null) {
            val r = Rect()
            root.getBoundsInScreen(r)
            if (r.width() > 200 && r.height() > 200) {
                screenBounds.set(r)
            }
        }

        // 2. If targetText is provided for tap / long_press
        if (targetText.isNotBlank() && (combined.contains("tap") || combined.contains("click") || combined.contains("press"))) {
            if (!combined.contains("long")) {
                return UiTools.clickTextOnScreen(targetText, exact = false)
            } else if (root != null) {
                val node = UiTools.findClickableMatchingNode(root, targetText, exact = false)
                if (node != null) {
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    return dispatchStroke(
                        service = service,
                        label = "long_press('$targetText')",
                        startX = rect.exactCenterX(),
                        startY = rect.exactCenterY(),
                        endX = rect.exactCenterX(),
                        endY = rect.exactCenterY(),
                        durationMs = durationMs.coerceAtLeast(600L)
                    )
                }
            }
        }

        // 3. Coordinate tap / long_press
        if (combined.contains("tap") || combined.contains("press") || combined.contains("click")) {
            val tapX = if (x >= 0) x.toFloat() else screenBounds.exactCenterX()
            val tapY = if (y >= 0) y.toFloat() else screenBounds.exactCenterY()
            val holdMs = if (combined.contains("long")) durationMs.coerceAtLeast(600L) else 65L
            return dispatchStroke(
                service = service,
                label = "${if (combined.contains("long")) "long_press" else "tap"}(${tapX.toInt()},${tapY.toInt()})",
                startX = tapX,
                startY = tapY,
                endX = tapX,
                endY = tapY,
                durationMs = holdMs
            )
        }

        // 4. Swipes / Scrolls
        val w = screenBounds.width().toFloat().coerceAtLeast(500f)
        val h = screenBounds.height().toFloat().coerceAtLeast(800f)
        val cx = screenBounds.left + w * 0.5f
        val cy = screenBounds.top + h * 0.5f

        val (sx, sy, ex, ey) = when {
            x >= 0 && y >= 0 && endX >= 0 && endY >= 0 ->
                listOf(x.toFloat(), y.toFloat(), endX.toFloat(), endY.toFloat())
            combined.contains("up") || combined.contains("scroll_down") ->
                // Swipe finger UP to scroll content down
                listOf(cx, screenBounds.top + h * 0.75f, cx, screenBounds.top + h * 0.25f)
            combined.contains("down") || combined.contains("scroll_up") ->
                // Swipe finger DOWN to scroll content up
                listOf(cx, screenBounds.top + h * 0.25f, cx, screenBounds.top + h * 0.75f)
            combined.contains("left") ->
                listOf(screenBounds.left + w * 0.82f, cy, screenBounds.left + w * 0.18f, cy)
            combined.contains("right") ->
                listOf(screenBounds.left + w * 0.18f, cy, screenBounds.left + w * 0.82f, cy)
            else ->
                listOf(cx, screenBounds.top + h * 0.75f, cx, screenBounds.top + h * 0.25f)
        }

        return dispatchStroke(
            service = service,
            label = "swipe($combined: ${sx.toInt()},${sy.toInt()} -> ${ex.toInt()},${ey.toInt()})",
            startX = sx,
            startY = sy,
            endX = ex,
            endY = ey,
            durationMs = durationMs.coerceIn(120L, 1500L)
        )
    }

    private suspend fun dispatchStroke(
        service: AccessibilityService,
        label: String,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): ToolResult {
        val beforeSnap = ScreenObserver.captureFreshSnapshot()
        val path = Path().apply {
            moveTo(startX, startY)
            if (startX != endX || startY != endY) {
                lineTo(endX, endY)
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        val dispatched = service.dispatchGesture(gesture, null, null)
        if (!dispatched) {
            return ToolResult.failure(
                tool = "perform_gesture",
                message = "Accessibility dispatchGesture rejected $label.",
                errorCode = "GESTURE_DISPATCH_FAILED"
            )
        }
        delay(durationMs.coerceAtMost(180L) + 50L)
        val afterSnap = ScreenObserver.captureFreshSnapshot()
        val pkgAfter = afterSnap?.packageName ?: beforeSnap?.packageName.orEmpty()
        return ToolResult.success(
            tool = "perform_gesture",
            message = "Performed gesture $label on screen (activePackage=$pkgAfter).",
            verified = true,
            verificationNote = "GestureDescription stroke ($durationMs ms) dispatched and post-gesture snapshot captured.",
            data = JSONObject().apply {
                put("gesture", label)
                put("startX", startX.toInt())
                put("startY", startY.toInt())
                put("endX", endX.toInt())
                put("endY", endY.toInt())
                put("durationMs", durationMs)
                put("activePackage", pkgAfter)
            }
        )
    }

    suspend fun analyzeScreenVisual(focusQuery: String = ""): ToolResult {
        val service = AgentBridge.service ?: return ToolResult.needsPermission(
            tool = "analyze_screen_visual",
            permissionOrService = "AccessibilityService",
            message = "AccessibilityService is not connected. Enable OmniVoice in Accessibility Settings to inspect the visual layout and screen elements."
        )

        val snap = ScreenObserver.captureFreshSnapshot() ?: return ToolResult.failure(
            tool = "analyze_screen_visual",
            message = "No active application window could be captured for visual analysis.",
            errorCode = "NO_ACTIVE_WINDOW"
        )

        var screenshotCaptured = false
        var downscaledDim = "none"
        var avgLuma = -1
        var workerThreadUsed = com.example.engine.VisionWebWorkerEngine.WORKER_THREAD_NAME

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bmpResult = withTimeoutOrNull(1500L) {
                captureAccessibilityBitmap(service)
            }
            if (bmpResult != null) {
                try {
                    val encoded = com.example.engine.VisionWebWorkerEngine.downscaleAndEncodeBitmapSuspend(
                        sourceBitmap = bmpResult,
                        targetWidth = 512,
                        targetHeight = 512,
                        quality = 70
                    )
                    screenshotCaptured = true
                    downscaledDim = "${encoded.width}x${encoded.height}"
                    avgLuma = encoded.centerLuminance
                    workerThreadUsed = encoded.workerThreadName
                    com.example.engine.OmniLiveSessionEngine.getInstance()?.injectVisionFrame(encoded.base64Jpeg)
                    if (!bmpResult.isRecycled) {
                        bmpResult.recycle()
                    }
                } catch (_: Exception) {
                    try {
                        if (!bmpResult.isRecycled) bmpResult.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val topRegion = mutableListOf<String>()
        val middleRegion = mutableListOf<String>()
        val bottomRegion = mutableListOf<String>()
        val queryMatches = JSONArray()
        val qLower = focusQuery.trim().lowercase(Locale.ROOT)

        for (node in snap.nodes) {
            val label = node.displayLabel()
            if (label.isBlank()) continue
            val cy = node.bounds.centerY()
            val entry = "$label @(${node.bounds.centerX()},${node.bounds.centerY()})"
            when {
                cy < 350 -> if (topRegion.size < 8) topRegion.add(entry)
                cy > 1850 -> if (bottomRegion.size < 8) bottomRegion.add(entry)
                else -> if (middleRegion.size < 14) middleRegion.add(entry)
            }
            if (qLower.isNotBlank() && label.lowercase(Locale.ROOT).contains(qLower)) {
                queryMatches.put(
                    JSONObject().apply {
                        put("label", label)
                        put("centerX", node.bounds.centerX())
                        put("centerY", node.bounds.centerY())
                        put("clickable", node.isClickable)
                        put("editable", node.isEditable)
                    }
                )
            }
        }

        val visualSummary = buildString {
            append("Visual layout of ${snap.appLabel} (${snap.packageName}) — Title: ${snap.titleGuess}. ")
            if (topRegion.isNotEmpty()) append("Top bar: ${topRegion.joinToString(", ")}. ")
            if (middleRegion.isNotEmpty()) append("Main content: ${middleRegion.joinToString(", ")}. ")
            if (bottomRegion.isNotEmpty()) append("Bottom bar: ${bottomRegion.joinToString(", ")}. ")
            if (qLower.isNotBlank()) append("Matches for '$focusQuery': ${queryMatches.length()}.")
        }

        val data = snap.toJson().apply {
            put("screenshotCaptured", screenshotCaptured)
            put("downscaledResolution", downscaledDim)
            put("visionWorkerThread", workerThreadUsed)
            if (avgLuma >= 0) put("centerLuminance", avgLuma)
            put("topRegion", JSONArray(topRegion))
            put("middleRegion", JSONArray(middleRegion))
            put("bottomRegion", JSONArray(bottomRegion))
            put("queryMatches", queryMatches)
        }

        return ToolResult.success(
            tool = "analyze_screen_visual",
            message = visualSummary,
            verified = true,
            verificationNote = "Visual + spatial Accessibility tree captured (${snap.nodes.size} nodes, screenshot=$screenshotCaptured, worker=$workerThreadUsed).",
            data = data
        )
    }

    private suspend fun captureAccessibilityBitmap(service: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    com.example.engine.VisionWebWorkerEngine.workerExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val hwBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val bmp = try {
                                val hwBmp = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                hwBmp?.copy(Bitmap.Config.ARGB_8888, false).also {
                                    hwBmp?.recycle()
                                    hwBuffer.close()
                                }
                            } catch (_: Exception) {
                                try { hwBuffer.close() } catch (_: Exception) {}
                                null
                            }
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
}

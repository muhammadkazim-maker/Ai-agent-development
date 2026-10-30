package com.omnivoice.agentfix

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Standalone AccessibilityService provided by the Agent Fix Kit.
 * When the host app already has an AccessibilityService, merge these hooks into it.
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentBridge.service = this
        AgentBridge.appContext = applicationContext
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
        }
        AgentLog.d("Service", "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        ScreenObserver.onEvent(event, packageName)
    }

    override fun onInterrupt() {
        AgentLog.w("Service", "accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (AgentBridge.service === this) {
            AgentBridge.service = null
        }
        AgentLog.w("Service", "accessibility service unbound")
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (AgentBridge.service === this) {
            AgentBridge.service = null
        }
        AgentLog.w("Service", "accessibility service destroyed")
        super.onDestroy()
    }
}

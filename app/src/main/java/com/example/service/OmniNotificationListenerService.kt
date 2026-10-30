package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

enum class NotificationCategory {
    MESSAGING,
    CALL,
    ALARM_REMINDER,
    SYSTEM,
    GENERAL
}

data class NotificationReplyAction(
    val title: String,
    val pendingIntent: PendingIntent,
    val remoteInputs: Array<RemoteInput>,
    val resultKey: String
) {
    fun sendReply(context: Context, replyMessage: String): Boolean {
        return try {
            val replyIntent = Intent()
            val bundle = Bundle().apply {
                putCharSequence(resultKey, replyMessage)
            }
            RemoteInput.addResultsToIntent(remoteInputs, replyIntent, bundle)
            pendingIntent.send(context, 0, replyIntent)
            Log.i("OmniNotificationService", "Direct Reply successfully sent via RemoteInput: '$replyMessage'")
            true
        } catch (e: Exception) {
            Log.e("OmniNotificationService", "Failed to send Direct Reply: ${e.message}", e)
            false
        }
    }
}

data class CachedNotification(
    val id: Int,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val category: NotificationCategory = NotificationCategory.GENERAL,
    val replyAction: NotificationReplyAction? = null
) {
    val isReplyable: Boolean get() = replyAction != null
}

class OmniNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "OmniNotificationService"
        private val notificationsList = mutableListOf<CachedNotification>()
        private var isConnected = false
        private var instance: OmniNotificationListenerService? = null

        fun isServiceRunning(): Boolean = isConnected

        fun setInstanceForTesting(service: OmniNotificationListenerService?) {
            instance = service
            isConnected = (service != null)
        }

        @Synchronized
        fun getRecentNotifications(): List<CachedNotification> {
            return notificationsList.toList().takeLast(10)
        }

        @Synchronized
        fun getFilteredNotifications(
            targetPackage: String? = null,
            targetCategory: NotificationCategory? = null,
            query: String? = null
        ): List<CachedNotification> {
            return notificationsList.filter { notif ->
                val matchesPkg = targetPackage.isNullOrBlank() || notif.packageName.equals(targetPackage, ignoreCase = true)
                val matchesCat = targetCategory == null || notif.category == targetCategory
                val matchesQuery = query.isNullOrBlank() ||
                    notif.title.contains(query, ignoreCase = true) ||
                    notif.text.contains(query, ignoreCase = true)
                matchesPkg && matchesCat && matchesQuery
            }.sortedByDescending { it.postTime }
        }

        @Synchronized
        fun findReplyableNotification(packageName: String): CachedNotification? {
            return notificationsList
                .filter { it.packageName.contains(packageName, ignoreCase = true) && it.isReplyable }
                .maxByOrNull { it.postTime }
        }

        @Synchronized
        fun replyToNotification(notificationId: Int, replyText: String, context: Context): Boolean {
            val target = notificationsList.firstOrNull { it.id == notificationId && it.isReplyable }
                ?: return false
            return target.replyAction?.sendReply(context, replyText) ?: false
        }

        @Synchronized
        fun replyToPackage(packageName: String, replyText: String, context: Context): Boolean {
            val target = findReplyableNotification(packageName) ?: return false
            return target.replyAction?.sendReply(context, replyText) ?: false
        }

        @Synchronized
        fun injectNotificationForTesting(notification: CachedNotification) {
            notificationsList.removeAll { it.id == notification.id && it.packageName == notification.packageName }
            notificationsList.add(notification)
            try {
                val ctx = com.omnivoice.agentfix.AgentBridge.appContext
                if (ctx != null) {
                    com.omnivoice.agentfix.StandingRulesEngine.onNotificationPosted(
                        context = ctx,
                        packageName = notification.packageName,
                        title = notification.title,
                        text = notification.text
                    )
                }
            } catch (_: Exception) {
            }
        }

        @Synchronized
        fun clearNotificationsForTesting() {
            notificationsList.clear()
        }

        fun determineCategory(packageName: String, notification: Notification): NotificationCategory {
            val category = notification.category
            if (category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_MISSED_CALL) {
                return NotificationCategory.CALL
            }
            if (category == Notification.CATEGORY_MESSAGE ||
                packageName.contains("whatsapp") ||
                packageName.contains("messaging") ||
                packageName.contains("telegram") ||
                packageName.contains("signal")
            ) {
                return NotificationCategory.MESSAGING
            }
            if (category == Notification.CATEGORY_ALARM || category == Notification.CATEGORY_REMINDER) {
                return NotificationCategory.ALARM_REMINDER
            }
            if (packageName.startsWith("android") || packageName.startsWith("com.android")) {
                return NotificationCategory.SYSTEM
            }
            return NotificationCategory.GENERAL
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        instance = this
        Log.d(TAG, "Notification listener connected")
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        extractAndStore(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        synchronized(notificationsList) {
            notificationsList.removeAll { it.id == sbn.id && it.packageName == sbn.packageName }
        }
    }

    private fun refreshActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            synchronized(notificationsList) {
                notificationsList.clear()
                for (sbn in active) {
                    extractAndStore(sbn)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing active notifications", e)
        }
    }

    private fun extractAndStore(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        // Filter out empty or self notifications
        if (title.isBlank() && text.isBlank()) return
        if (sbn.packageName == packageName) return

        val category = determineCategory(sbn.packageName, notification)
        val replyAction = extractReplyAction(notification)

        val cached = CachedNotification(
            id = sbn.id,
            packageName = sbn.packageName,
            title = title,
            text = text,
            postTime = sbn.postTime,
            category = category,
            replyAction = replyAction
        )

        synchronized(notificationsList) {
            notificationsList.removeAll { it.id == sbn.id && it.packageName == sbn.packageName }
            notificationsList.add(cached)
            if (notificationsList.size > 50) {
                notificationsList.removeAt(0)
            }
        }
        try {
            com.omnivoice.agentfix.StandingRulesEngine.onNotificationPosted(
                context = applicationContext,
                packageName = cached.packageName,
                title = cached.title,
                text = cached.text
            )
        } catch (_: Exception) {
        }
    }

    private fun extractReplyAction(notification: Notification): NotificationReplyAction? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val remoteInputs = action.remoteInputs
            if (!remoteInputs.isNullOrEmpty()) {
                val inputWithKey = remoteInputs.firstOrNull { !it.resultKey.isNullOrBlank() }
                if (inputWithKey != null && action.actionIntent != null) {
                    return NotificationReplyAction(
                        title = action.title?.toString().orEmpty(),
                        pendingIntent = action.actionIntent,
                        remoteInputs = remoteInputs,
                        resultKey = inputWithKey.resultKey
                    )
                }
            }
        }
        return null
    }
}

package com.balteklabs.voiceos

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class VoiceOSNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "VoiceOSNotif"
        private val recent = ArrayDeque<Map<String, Any>>(50)
        private val lock = Any()

        @Volatile
        var instance: VoiceOSNotificationService? = null
            private set

        /** Shared reference to the ContextStore for incremental indexing. Set by MainActivity. */
        var contextStore: ContextStore? = null

        fun getRecent(): List<Map<String, Any>> = synchronized(lock) {
            recent.toList().reversed()  // newest first
        }

        fun dismiss(key: String) = synchronized(lock) {
            recent.removeAll { it["key"] == key }
        }

        /** Cancel (dismiss) a notification from the status bar by its key. */
        fun dismissNotification(key: String): Boolean {
            return try {
                instance?.cancelNotification(key)
                dismiss(key)
                true
            } catch (e: Exception) {
                Log.e(TAG, "dismiss failed: ${e.message}")
                false
            }
        }

        /**
         * List action button titles available on an active notification.
         * Returns empty list if the notification is no longer active.
         */
        fun getNotificationActions(key: String): List<String> {
            val sbn = instance?.activeNotifications?.firstOrNull { it.key == key }
                ?: return emptyList()
            return sbn.notification.actions
                ?.mapNotNull { it.title?.toString()?.trim()?.takeIf { t -> t.isNotBlank() } }
                ?: emptyList()
        }

        /**
         * Send an inline text reply to a notification via its RemoteInput action.
         * Works with WhatsApp, Messages, Telegram, Gmail and any app that supports
         * direct reply. Returns false when the notification has no free-form reply action.
         */
        fun replyToNotification(ctx: Context, key: String, replyText: String): Boolean {
            return try {
                val sbn = instance?.activeNotifications?.firstOrNull { it.key == key }
                    ?: return false
                val replyAction = sbn.notification.actions?.firstOrNull { action ->
                    action.remoteInputs?.any { it.allowFreeFormInput } == true
                } ?: return false
                val remoteInput = replyAction.remoteInputs.first { it.allowFreeFormInput }
                val intent = Intent()
                RemoteInput.addResultsToIntent(
                    replyAction.remoteInputs, intent,
                    Bundle().apply { putCharSequence(remoteInput.resultKey, replyText) }
                )
                replyAction.actionIntent.send(ctx, 0, intent)
                dismiss(key)  // remove from in-memory cache after reply
                true
            } catch (e: Exception) {
                Log.e(TAG, "reply failed: ${e.message}")
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return   // ignore our own
        if (sbn.isOngoing) return                    // skip persistent system notifications

        val extras = sbn.notification.extras
        val title  = extras.getCharSequence("android.title")?.toString()?.trim() ?: ""
        val text   = extras.getCharSequence("android.bigText")?.toString()?.trim()
                  ?: extras.getCharSequence("android.text")?.toString()?.trim() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        val appName = appLabel(sbn.packageName)

        synchronized(lock) {
            // Deduplicate: same package + notification ID = update in place
            recent.removeAll { it["key"] == sbn.key }
            while (recent.size >= 50) recent.removeFirst()
            recent.addLast(mapOf(
                "key"   to sbn.key,
                "pkg"   to sbn.packageName,
                "app"   to appName,
                "title" to title,
                "text"  to text,
                "time"  to sbn.postTime
            ))
        }

        // Incrementally index into context store (non-blocking)
        contextStore?.let { store ->
            try {
                val discoveryEngine = DiscoveryEngine(applicationContext, store)
                discoveryEngine.indexNotification(sbn.packageName, appName, title, text, sbn.postTime)
            } catch (_: Exception) {}
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lock) { recent.removeAll { it["key"] == sbn.key } }

        // Remove from context store too
        contextStore?.let { store ->
            try {
                val extras = sbn.notification.extras
                val title  = extras.getCharSequence("android.title")?.toString()?.trim() ?: ""
                DiscoveryEngine(applicationContext, store).removeNotification(sbn.packageName, title)
            } catch (_: Exception) {}
        }
    }

    private fun appLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}

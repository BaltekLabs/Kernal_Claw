package com.balteklabs.voiceos

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class VoiceOSNotificationService : NotificationListenerService() {

    companion object {
        private val recent = ArrayDeque<Map<String, Any>>(50)
        private val lock = Any()

        fun getRecent(): List<Map<String, Any>> = synchronized(lock) {
            recent.toList().reversed()  // newest first
        }

        fun dismiss(key: String) = synchronized(lock) {
            recent.removeAll { it["key"] == key }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return   // ignore our own
        if (sbn.isOngoing) return                    // skip persistent system notifications

        val extras = sbn.notification.extras
        val title  = extras.getCharSequence("android.title")?.toString()?.trim() ?: ""
        val text   = extras.getCharSequence("android.bigText")?.toString()?.trim()
                  ?: extras.getCharSequence("android.text")?.toString()?.trim() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        synchronized(lock) {
            // Deduplicate: same package + notification ID = update in place
            recent.removeAll { it["key"] == sbn.key }
            while (recent.size >= 50) recent.removeFirst()
            recent.addLast(mapOf(
                "key"   to sbn.key,
                "pkg"   to sbn.packageName,
                "app"   to appLabel(sbn.packageName),
                "title" to title,
                "text"  to text,
                "time"  to sbn.postTime
            ))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lock) { recent.removeAll { it["key"] == sbn.key } }
    }

    private fun appLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}

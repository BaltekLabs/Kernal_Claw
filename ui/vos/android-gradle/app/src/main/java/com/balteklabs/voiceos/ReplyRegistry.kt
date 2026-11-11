package com.balteklabs.voiceos

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Inline "Reply" actions from chat-app notifications (Signal, WhatsApp, Google
 * Messages/RCS, Telegram…), so a reply can be sent without opening the app —
 * the same mechanism watches and Android Auto use.
 *
 * Keyed by app name + conversation name, matching how MessageLog names threads
 * (the conversation title for groups, the sender otherwise). An action is only
 * usable while its notification is showing, so entries are dropped when the
 * notification goes away.
 */
object ReplyRegistry {

    private data class Entry(val sbnKey: String, val action: Notification.Action, val time: Long)

    private val entries = HashMap<String, Entry>()
    private val lock = Any()

    private fun key(app: String, person: String) = "${app.trim().lowercase()}|${person.trim().lowercase()}"

    /** Remember the notification's reply action, if it has one. */
    fun capture(sbn: StatusBarNotification, appName: String) {
        val n = sbn.notification
        val action = replyAction(n) ?: return
        val names = LinkedHashSet<String>()
        try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.let { style ->
                style.conversationTitle?.toString()?.let { names += it }
                val me = style.user.name?.toString()
                style.messages.mapNotNullTo(names) { m -> m.person?.name?.toString()?.takeIf { it != me } }
            }
        } catch (_: Exception) {}
        n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { names += it }
        n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.let { names += it }

        val entry = Entry(sbn.key, action, sbn.postTime)
        synchronized(lock) {
            names.filter { it.isNotBlank() }.forEach { entries[key(appName, it)] = entry }
        }
    }

    fun remove(sbn: StatusBarNotification) = synchronized(lock) {
        entries.values.removeAll { it.sbnKey == sbn.key }
    }

    fun canReply(app: String, person: String): Boolean = synchronized(lock) { entries.containsKey(key(app, person)) }

    /** Fill in the reply text and fire the app's own reply action. */
    fun reply(context: Context, app: String, person: String, text: String): String? {
        val entry = synchronized(lock) { entries[key(app, person)] }
            ?: return "no_reply_action"
        return try {
            val inputs = entry.action.remoteInputs ?: return "no_reply_action"
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val fill = Intent()
            RemoteInput.addResultsToIntent(inputs, fill, results)
            entry.action.actionIntent.send(context, 0, fill)
            null
        } catch (e: Exception) {
            // The app cancelled the action (conversation read elsewhere, notification gone).
            synchronized(lock) { entries.values.removeAll { it.sbnKey == entry.sbnKey } }
            "reply_failed: ${e.message}"
        }
    }

    private fun replyAction(n: Notification): Notification.Action? {
        val withInput = n.actions?.filter { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }?.let { return it }
        return withInput.firstOrNull()
    }
}

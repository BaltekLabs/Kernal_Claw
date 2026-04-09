package com.balteklabs.voiceos

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * ActionQueue.kt — staged outgoing actions pending user approval.
 *
 * The agent uses tools like draft_email / queue_sms / queue_whatsapp to
 * place actions here instead of firing them immediately. The frontend
 * polls /api/queue and shows approve/dismiss controls. On approve the
 * action is executed (Intent fired). On dismiss it is dropped.
 *
 * Action types (string keys match tool names for easy mapping):
 *   draft_email      — open mail client with pre-filled to/subject/body
 *   queue_sms        — open SMS app with pre-filled number + body
 *   queue_whatsapp   — share to WhatsApp with number + body
 *   queue_message    — generic share intent
 */
data class QueuedAction(
    val id: String,
    val type: String,
    val params: Map<String, String>,
    val status: String = "pending",   // pending | approved | dismissed
    val preview: String,              // one-line summary shown in UI
    val createdMs: Long = System.currentTimeMillis()
)

class ActionQueue(private val context: Context) {
    private val gson = Gson()
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voiceos_queue", Context.MODE_PRIVATE)

    fun add(type: String, params: Map<String, String>, preview: String): QueuedAction {
        val action = QueuedAction(
            id      = "q${System.currentTimeMillis().toString(36)}",
            type    = type,
            params  = params,
            preview = preview
        )
        val all = load().toMutableList()
        // Deduplicate: drop older identical pending item (same type + to)
        all.removeAll { it.type == type && it.params["to"] == params["to"] && it.status == "pending" }
        all += action
        save(all)
        return action
    }

    fun getPending(): List<QueuedAction> = load().filter { it.status == "pending" }

    fun getAll(): List<QueuedAction> = load()

    fun approve(id: String): QueuedAction? {
        val all = load().toMutableList()
        val idx = all.indexOfFirst { it.id == id } .takeIf { it >= 0 } ?: return null
        val updated = all[idx].copy(status = "approved")
        all[idx] = updated
        save(all)
        return updated
    }

    fun dismiss(id: String): Boolean {
        val all = load().toMutableList()
        val idx = all.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return false
        all[idx] = all[idx].copy(status = "dismissed")
        save(all)
        return true
    }

    fun remove(id: String) { save(load().filter { it.id != id }) }

    /** Prune approved/dismissed actions older than 24 hours. */
    fun prune() {
        val cutoff = System.currentTimeMillis() - 86_400_000L
        save(load().filter { it.status == "pending" || it.createdMs > cutoff })
    }

    private fun load(): List<QueuedAction> {
        val json = prefs.getString("queue", "[]") ?: "[]"
        return try {
            val type = object : TypeToken<List<QueuedAction>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun save(actions: List<QueuedAction>) {
        prefs.edit().putString("queue", gson.toJson(actions)).apply()
    }
}

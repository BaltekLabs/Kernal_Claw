package com.balteklabs.voiceos

import android.util.Log

/**
 * Heartbeat.kt — Kotlin port of ui/vos/src/agent/heartbeat.py
 *
 * Instead of one monolithic 30-second poll that dumps everything to the LLM,
 * we run named probes on independent intervals. Each probe gets only the tools
 * it needs, with a minimal focused prompt — dramatically reducing token usage.
 *
 * Default probes:
 *   notification_triage  — every  2 min   tools: get_notifications, remember
 *   calendar_check       — every 30 min   tools: read_calendar, get_device_info
 *   task_review          — every 10 min   tools: list_tasks, add_task, update_task, complete_task
 *   battery_watch        — every 15 min   tools: get_battery
 *
 * Results that are not "[idle]" are pushed to a pending queue.
 * VoiceOSServer.handleHeartbeat() pops the next pending result when the
 * frontend calls /api/heartbeat.
 */

data class HeartbeatProbe(
    val name: String,
    val intervalMs: Long,
    /** Subset of tool names to pass to the LLM for this probe. Empty = all tools. */
    val toolNames: List<String>,
    /** Short, focused prompt — the LLM must respond "[idle]" or ≤ 12 words. */
    val prompt: String,
    var enabled: Boolean = true,
    @Volatile var lastRunMs: Long = 0L
)

class ProbeHeartbeat(
    /** Called with a probe when it is due. Return result text, or null to skip. */
    private val runProbe: (probe: HeartbeatProbe) -> String?
) {
    private val probes         = mutableListOf<HeartbeatProbe>()
    private val pendingResults = ArrayDeque<String>()   // non-idle results waiting to be popped
    @Volatile private var running = false
    private var thread: Thread? = null

    // ── Registration ───────────────────────────────────────────────
    fun register(vararg p: HeartbeatProbe) = synchronized(probes) { probes.addAll(p) }

    // ── Lifecycle ──────────────────────────────────────────────────
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            Log.i(TAG, "Started with ${probes.size} probes")
            while (running) {
                tick()
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
            }
            Log.i(TAG, "Stopped")
        }.apply {
            isDaemon = true
            name = "voiceos-heartbeat"
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    // ── Result queue ───────────────────────────────────────────────
    /** Pop the next pending result, or null if queue is empty. */
    fun popPending(): String? = synchronized(pendingResults) {
        if (pendingResults.isEmpty()) null else pendingResults.removeFirst()
    }

    fun hasPending(): Boolean = synchronized(pendingResults) { pendingResults.isNotEmpty() }

    // ── Management ─────────────────────────────────────────────────
    fun setEnabled(name: String, enabled: Boolean) = synchronized(probes) {
        probes.firstOrNull { it.name == name }?.enabled = enabled
    }

    fun list(): List<Map<String, Any>> = synchronized(probes) {
        probes.map {
            mapOf(
                "name"       to it.name,
                "interval_s" to it.intervalMs / 1000,
                "enabled"    to it.enabled,
                "last_run"   to it.lastRunMs
            )
        }
    }

    // ── Internal ───────────────────────────────────────────────────
    private fun tick() {
        val now = System.currentTimeMillis()
        val due = synchronized(probes) {
            probes.filter { it.enabled && now - it.lastRunMs >= it.intervalMs }
        }
        for (probe in due) {
            probe.lastRunMs = now
            try {
                val result = runProbe(probe)?.trim() ?: continue
                if (result.isNotBlank() && result != "[idle]") {
                    Log.d(TAG, "Probe '${probe.name}' surfaced: $result")
                    synchronized(pendingResults) { pendingResults.addLast(result) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Probe '${probe.name}' failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "VoiceOSHeartbeat"

        // ── Default probe definitions ──────────────────────────────
        fun defaultProbes() = arrayOf(
            HeartbeatProbe(
                name        = "notification_triage",
                intervalMs  = 2 * 60_000L,
                toolNames   = listOf("get_notifications", "remember"),
                prompt      = """Background check — notifications only.
Use get_notifications. Reply EXACTLY "[idle]" if nothing important (ads, social, routine updates).
Otherwise reply ONE sentence (≤12 words) about what genuinely needs attention — unread message from a person, urgent alert, action needed.
Do not explain. Do not ask questions."""
            ),
            HeartbeatProbe(
                name        = "calendar_check",
                intervalMs  = 30 * 60_000L,
                toolNames   = listOf("read_calendar", "get_device_info"),
                prompt      = """Background check — calendar only.
Use read_calendar with days=1. Reply "[idle]" if no events start within 30 minutes.
Otherwise reply ONE sentence: event name and how soon it starts."""
            ),
            HeartbeatProbe(
                name        = "task_review",
                intervalMs  = 10 * 60_000L,
                toolNames   = listOf("list_tasks", "add_task", "update_task", "complete_task"),
                prompt      = """Background check — task list only.
Use list_tasks. Reply "[idle]" if no high-priority pending tasks.
Otherwise: if you can autonomously advance one task using your tools, do it and report the action in ONE sentence.
If not, reply ONE sentence describing what is blocked."""
            ),
            HeartbeatProbe(
                name        = "battery_watch",
                intervalMs  = 15 * 60_000L,
                toolNames   = listOf("get_battery"),
                prompt      = """Background check — battery only.
Use get_battery. Reply "[idle]" unless battery is below 20% AND not charging.
If critical, reply ONE sentence: current percentage and charging status."""
            )
        )
    }
}

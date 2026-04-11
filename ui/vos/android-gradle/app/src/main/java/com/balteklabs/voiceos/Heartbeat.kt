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
    /** Static prompt — used when promptFactory is null. */
    val prompt: String = "",
    /**
     * Dynamic prompt factory — receives the current user profile map and returns
     * the prompt string. Return "[idle]" to skip this probe entirely when the
     * profile has no relevant data (e.g. no projects for project_pulse).
     */
    val promptFactory: ((Map<String, Any>) -> String)? = null,
    var enabled: Boolean = true,
    @Volatile var lastRunMs: Long = 0L
) {
    /** Build the prompt for a given profile, preferring factory over static string. */
    fun buildPrompt(profile: Map<String, Any>): String =
        promptFactory?.invoke(profile) ?: prompt
}

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

    /** Push a result directly — used by external code (e.g. approved shell commands). */
    fun pushResult(text: String) = synchronized(pendingResults) { pendingResults.addLast(text) }

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

            // ── Device / ambient probes ────────────────────────────
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
                name        = "battery_watch",
                intervalMs  = 15 * 60_000L,
                toolNames   = listOf("get_battery"),
                prompt      = """Background check — battery only.
Use get_battery. Reply "[idle]" unless battery is below 20% AND not charging.
If critical, reply ONE sentence: current percentage and charging status."""
            ),

            // ── Initiative probes — profile-driven ────────────────

            /**
             * project_pulse — checks git status for the user's active projects.
             * Only fires when the profile has projects with a termux_path.
             * Skips silently if the Termux bridge is not running.
             */
            HeartbeatProbe(
                name       = "project_pulse",
                intervalMs = 20 * 60_000L,
                toolNames  = listOf("run_shell", "list_tasks", "remember"),
                promptFactory = { profile ->
                    @Suppress("UNCHECKED_CAST")
                    val projs = (profile["projects"] as? List<*>)
                        ?.filterIsInstance<Map<String, Any>>()
                        ?.filter { (it["termux_path"] as? String)?.isNotBlank() == true }
                        ?: emptyList()
                    if (projs.isEmpty()) "[idle]"
                    else {
                        val list = projs.take(3).joinToString("; ") { p ->
                            "${p["name"]} (${p["termux_path"]})"
                        }
                        """Background check — project git status.
Projects: $list
For each project use run_shell with command "git status --short" and the project's termux_path as workdir.
Reply "[idle]" if all repos are clean or bridge unavailable (timeout).
Otherwise ONE sentence: which project has uncommitted changes and what kind (new files / modified / deleted).
Do NOT suggest anything. Do NOT ask questions."""
                    }
                }
            ),

            /**
             * social_nudge — surfaces overdue social goals from the user's profile.
             * Only fires when the profile has social_goals defined.
             */
            HeartbeatProbe(
                name       = "social_nudge",
                intervalMs = 6 * 3600_000L,
                toolNames  = listOf("get_relationship_health", "suggest_social_outreach", "remember"),
                promptFactory = { profile ->
                    @Suppress("UNCHECKED_CAST")
                    val goals = (profile["social_goals"] as? List<*>)
                        ?.filterIsInstance<Map<String, Any>>() ?: emptyList()
                    if (goals.isEmpty()) "[idle]"
                    else {
                        val people = goals.take(6).mapNotNull { it["person"] as? String }.joinToString(", ")
                        """Background check — social outreach.
Tracked people: $people
Use get_relationship_health to find who is significantly overdue for contact (>20% past their target frequency).
Reply "[idle]" if nobody is overdue.
Otherwise ONE sentence naming the most overdue person and how long it has been since contact."""
                    }
                }
            ),

            /**
             * task_advance — proactively works on the highest-priority pending task.
             * Uses run_shell, web_search, etc. to make concrete progress.
             */
            HeartbeatProbe(
                name       = "task_advance",
                intervalMs = 15 * 60_000L,
                toolNames  = listOf("list_tasks", "run_shell", "web_search", "update_task", "complete_task", "remember"),
                promptFactory = { _ ->
                    """Background — proactive task work.
Use list_tasks to find the highest-priority pending task that can be advanced autonomously.
If you can make concrete progress right now (run a shell command, look up information, update task notes), do it.
Report in ONE sentence what you did, or reply "[idle]" if all pending tasks require direct user involvement."""
                }
            )
        )
    }
}

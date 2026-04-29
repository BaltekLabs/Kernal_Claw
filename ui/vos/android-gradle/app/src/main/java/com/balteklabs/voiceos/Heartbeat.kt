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
    /**
     * If true, this probe is handled by the nativeRunner (zero LLM cost).
     * The llmRunner is never called for native probes.
     */
    val isNative: Boolean = false,
    var enabled: Boolean = true,
    @Volatile var lastRunMs: Long = 0L
) {
    /** Build the prompt for a given profile, preferring factory over static string. */
    fun buildPrompt(profile: Map<String, Any>): String =
        promptFactory?.invoke(profile) ?: prompt
}

class ProbeHeartbeat(
    /**
     * Zero-cost handler for native probes (battery, notification triage, etc.).
     * Return non-null string to surface a result, null for idle.
     * Return null from the lambda itself (not the String) to fall through to llmRunner.
     */
    private val nativeRunner: ((probe: HeartbeatProbe) -> String?)? = null,
    /** LLM-backed handler for all other probes. Uses FAST tier model. */
    private val llmRunner: (probe: HeartbeatProbe) -> String?
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
                // Route: native probes bypass LLM entirely
                val result = if (probe.isNative && nativeRunner != null) {
                    nativeRunner.invoke(probe)?.trim()
                } else {
                    llmRunner(probe)?.trim()
                } ?: continue
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
            // ── Native probes — zero LLM cost ─────────────────────
            HeartbeatProbe(
                name       = "notification_triage",
                intervalMs = 2 * 60_000L,
                toolNames  = emptyList(),
                isNative   = true,
                prompt     = ""   // handled by nativeRunner in VoiceOSServer
            ),
            HeartbeatProbe(
                name       = "battery_watch",
                intervalMs = 15 * 60_000L,
                toolNames  = emptyList(),
                isNative   = true,
                prompt     = ""   // handled by nativeRunner in VoiceOSServer
            ),

            // ── LLM probes — use FAST tier model ──────────────────
            HeartbeatProbe(
                name        = "calendar_check",
                intervalMs  = 30 * 60_000L,
                toolNames   = listOf("read_calendar", "get_device_info"),
                prompt      = """Background check — calendar only.
Use read_calendar with days=1. Reply "[idle]" if no events start within 30 minutes.
Otherwise reply ONE sentence: event name and how soon it starts."""
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
                toolNames  = listOf("get_followup_contacts", "get_relationship_health", "suggest_social_outreach", "remember"),
                promptFactory = { _ ->
                    """Background check — social follow-ups and outreach.
Step 1: Call get_followup_contacts. If anyone is flagged, reply ONE sentence: "[Name] is flagged for follow-up — [brief reason from their notes]".
Step 2 (only if nobody flagged): Call get_relationship_health with min_days_since=14. Reply ONE sentence naming the most overdue person.
Reply "[idle]" if no flagged contacts and nobody is significantly overdue."""
                }
            ),

            /**
             * task_status — READ-ONLY check for blocked or overdue tasks.
             * Reports status only. NEVER modifies tasks, NEVER combines task context,
             * NEVER takes action. Tasks are independent items — treat each one in isolation.
             */
            HeartbeatProbe(
                name       = "task_status",
                intervalMs = 30 * 60_000L,
                toolNames  = listOf("list_tasks"),
                promptFactory = { _ ->
                    """Background — read-only task check.
Use list_tasks. Treat EVERY task as completely independent of all others — never infer relationships.
Reply "[idle]" unless a task has status "blocked" or was created more than 7 days ago with status still "pending".
If something qualifies, reply ONE sentence naming only that task and its age/block reason.
Do NOT suggest actions. Do NOT mention other tasks. Do NOT combine tasks."""
                }
            ),

            /**
             * context_digest — reads the context store for newly arrived high-priority items.
             * READ-ONLY — can only surface information, never create or modify anything.
             */
            HeartbeatProbe(
                name       = "context_digest",
                intervalMs = 5 * 60_000L,
                toolNames  = listOf(
                    "get_pending_attention", "context_search", "get_message_threads",
                    "get_notifications", "read_sms", "get_relationship_health",
                    "remember"
                ),
                prompt     = """Background check — review what needs attention.
Use get_pending_attention to retrieve high-priority unread messages, notifications, and upcoming events.
If nothing needs attention, reply "[idle]".
If something genuinely needs the user's attention, reply with ONE or TWO specific action proposals (≤20 words total), e.g.:
  "3 unread texts from Alice — want me to draft a reply?"
  "Team standup in 18 min"
Do NOT list every item. Pick the single most important thing. Do not ask questions about routine items.
Do NOT create tasks. Do NOT modify anything."""
            )
        )
    }
}

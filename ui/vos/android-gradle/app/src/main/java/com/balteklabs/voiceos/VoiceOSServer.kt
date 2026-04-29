package com.balteklabs.voiceos

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

class VoiceOSServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voiceos", Context.MODE_PRIVATE)

    // ── Preferences ───────────────────────────────────────────────
    private var activeProvider: String
        get() = prefs.getString("provider", "ollama") ?: "ollama"
        set(v) { prefs.edit().putString("provider", v).apply() }

    private var activeModel: String
        get() = prefs.getString("model", "mistral:latest") ?: "mistral:latest"
        set(v) { prefs.edit().putString("model", v).apply() }

    private var ollamaUrl: String
        get() = prefs.getString("ollama_url", "http://127.0.0.1:11434") ?: "http://127.0.0.1:11434"
        set(v) { prefs.edit().putString("ollama_url", v).apply() }

    private fun apiKey(provider: String) = (prefs.getString("key_$provider", "") ?: "").trim()
    private fun saveKey(provider: String, key: String) =
        prefs.edit().putString("key_$provider", key.trim()).apply()

    // ── Model tiers ───────────────────────────────────────────────
    /** Three-tier model routing: LOCAL (no LLM), FAST (cheap), FULL (capable). */
    enum class ModelTier { LOCAL, FAST, FULL }

    /** Default fast (cheap) model for each provider. */
    private val defaultFastModel = mapOf(
        "anthropic" to "claude-haiku-4-5-20251001",
        "openai"    to "gpt-4o-mini",
        "grok"      to "grok-4-1-fast-non-reasoning",
        "ollama"    to ""   // same as full for Ollama — user picks their own small model
    )

    /** The fast model for the active provider. Falls back to full model if not set. */
    private var activeFastModel: String
        get() {
            val saved = prefs.getString("model_fast_$activeProvider", "") ?: ""
            return saved.ifBlank { defaultFastModel[activeProvider] ?: activeModel }
        }
        set(v) { prefs.edit().putString("model_fast_$activeProvider", v).apply() }

    /** Return the model string to use for a given tier and provider. */
    private fun modelForTier(tier: ModelTier): String = when (tier) {
        ModelTier.FAST  -> activeFastModel.ifBlank { activeModel }
        ModelTier.FULL  -> activeModel
        ModelTier.LOCAL -> ""   // no model needed
    }

    // ── Query router ──────────────────────────────────────────────
    /**
     * Classify a user query before touching any LLM.
     * LOCAL  → answer entirely from device data, zero API cost
     * FAST   → use cheap model (Haiku / gpt-4o-mini / llama-8b)
     * FULL   → use full model for complex reasoning / writing
     */
    fun classifyQuery(query: String): ModelTier {
        val q = query.lowercase().trim()

        // Patterns that can be answered locally without any LLM
        val localPatterns = listOf(
            Regex("(show|list|what are|any).{0,20}(task|todo|to-do)"),
            Regex("(who|which).{0,30}(reach out|contact|follow.?up|overdue|haven.t talked)"),
            Regex("(battery|charge).{0,20}(level|percent|status|how much)"),
            Regex("(add|create|new).{0,10}task"),
            Regex("(complete|done|finish|mark).{0,15}task"),
            Regex("(what|any).{0,20}notification"),
            Regex("(what.s|show|list).{0,15}(app|install)"),
            Regex("(set|create).{0,10}(alarm|reminder|timer)"),
            Regex("(open|launch|start).{0,20}(app|gmail|youtube|maps|chrome|spotify|settings)"),
            Regex("(call|ring|phone).{0,20}\\w+"),
            Regex("(what.s|current|check).{0,10}(time|date|day)"),
            Regex("(recall|remember|what did you).{0,20}note")
        )
        if (localPatterns.any { it.containsMatchIn(q) }) return ModelTier.LOCAL

        // Patterns suited for the fast cheap model
        val fastPatterns = listOf(
            Regex("(summar|brief|quick|short).{0,20}(email|message|notif|news)"),
            Regex("(should i|do i need|is it important|prioriti)"),
            Regex("(relationship|social|outreach|friend|contact).{0,20}(health|status|suggest)"),
            Regex("(check|review|scan).{0,20}(task|calendar|schedule|inbox)"),
            Regex("(what.s (on|in)|show).{0,20}(calendar|schedule|agenda)"),
            Regex("(translate|convert|calculate|how many|how much)"),
            Regex("(weather|temperature|forecast)"),
            Regex("(simple|quick) (question|answer|lookup)")
        )
        if (fastPatterns.any { it.containsMatchIn(q) }) return ModelTier.FAST

        // Default to FULL for everything else (complex, writing, multi-step)
        return ModelTier.FULL
    }

    // ── User profile ─────────────────────────────────────────────
    private val PROFILE_KEY = "user_profile"

    private fun loadProfile(): MutableMap<String, Any> {
        val json = prefs.getString(PROFILE_KEY, "{}") ?: "{}"
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(json, Map::class.java) as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
        } catch (_: Exception) { mutableMapOf() }
    }

    private fun saveProfile(profile: Map<String, Any>) {
        prefs.edit().putString(PROFILE_KEY, gson.toJson(profile)).apply()
    }

    /** Merge partial fields into the stored profile. Lists and objects are replaced, not appended. */
    @Suppress("UNCHECKED_CAST")
    private fun mergeProfile(updates: Map<String, Any>): Map<String, Any> {
        val profile = loadProfile()
        updates.forEach { (k, v) -> if (v != null) profile[k] = v }
        saveProfile(profile)
        return profile
    }

    /** Format the profile for injection into the system prompt. Returns "" when profile is absent. */
    private fun buildProfileContext(): String {
        val p = loadProfile()
        if (p.isEmpty()) return ""
        val sb = StringBuilder("\n## Your User\n")
        (p["name"] as? String)?.takeIf { it.isNotBlank() }?.let { sb.appendLine("Name: $it") }

        @Suppress("UNCHECKED_CAST")
        val projects = (p["projects"] as? List<Map<String, Any>>)?.filterIsInstance<Map<String, Any>>() ?: emptyList()
        if (projects.isNotEmpty()) {
            sb.appendLine("Active projects:")
            projects.forEach { proj ->
                val n    = proj["name"] as? String ?: return@forEach
                val desc = proj["description"] as? String ?: ""
                val path = (proj["termux_path"] as? String)?.takeIf { it.isNotBlank() }?.let { " [path: $it]" } ?: ""
                val st   = (proj["status"] as? String)?.takeIf { it.isNotBlank() }?.let { " [status: $it]" } ?: ""
                val nx   = (proj["next_action"] as? String)?.takeIf { it.isNotBlank() }?.let { " [next: $it]" } ?: ""
                sb.appendLine("  • $n: $desc$path$st$nx")
            }
        }

        @Suppress("UNCHECKED_CAST")
        val social = (p["social_goals"] as? List<Map<String, Any>>)?.filterIsInstance<Map<String, Any>>() ?: emptyList()
        if (social.isNotEmpty()) {
            sb.appendLine("Social goals (relationship · target frequency):")
            social.forEach { g ->
                val person = g["person"] as? String ?: return@forEach
                val rel    = (g["relationship"] as? String)?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                val freq   = (g["frequency_days"] as? Double)?.toInt()?.let { " every ${it}d" } ?: ""
                sb.appendLine("  • $person$rel$freq")
            }
        }

        @Suppress("UNCHECKED_CAST")
        val sched = p["schedule"] as? Map<String, String>
        if (sched != null) {
            val tz    = sched["timezone"] ?: ""
            val start = sched["work_start"] ?: ""
            val end   = sched["work_end"] ?: ""
            if (start.isNotBlank()) sb.appendLine("Schedule: $start–$end${if (tz.isNotBlank()) " $tz" else ""}")
        }

        @Suppress("UNCHECKED_CAST")
        val focus = (p["focus_areas"] as? List<*>)?.filterIsInstance<String>()
        if (!focus.isNullOrEmpty()) sb.appendLine("Focus: ${focus.joinToString(", ")}")

        return sb.toString().trimEnd()
    }

    // ── Termux bridge ─────────────────────────────────────────────
    private val bridgeDir: java.io.File by lazy {
        java.io.File(android.os.Environment.getExternalStorageDirectory(), "voiceos_bridge")
    }
    /** Short timeout for read-only commands (ls, git status, cat…). */
    private val BRIDGE_READ_TIMEOUT_MS  = 30_000L
    /** Long timeout for write operations (git clone, npm install, pip install…). */
    private val BRIDGE_WRITE_TIMEOUT_MS = 180_000L

    private fun isBridgeReady(): Boolean = try {
        if (!bridgeDir.exists()) bridgeDir.mkdirs()
        bridgeDir.exists() && bridgeDir.canWrite()
    } catch (_: Exception) { false }

    /** True for commands that are clearly read-only — used to pick timeout, not to gate execution. */
    private fun isReadOnlyShellCmd(cmd: String): Boolean {
        val c = cmd.trim().lowercase()
        return listOf(
            "ls", "cat ", "head ", "tail ", "grep ", "find ", "echo ", "printf ",
            "pwd", "whoami", "date", "uname", "which ", "type ",
            "git status", "git log", "git diff", "git branch", "git remote",
            "git show", "git stash list", "git describe", "git tag",
            "ps ", "df ", "du ", "free", "env", "printenv",
            "wc ", "sort ", "uniq ", "cut ", "tr ", "awk ", "sed -n",
            "python3 -c", "python -c", "node -e", "node -p",
            "pip list", "pip show", "npm list", "npm ls",
            "ll", "la", "cat\t"
        ).any { c.startsWith(it) }
    }

    /**
     * Write command to bridge dir, block until Termux daemon writes result, return output.
     * [timeoutMs] is chosen by the caller based on expected duration.
     * Returns a structured result string including exit code on failure.
     */
    private fun executeBridgeCommand(cmd: String, workdir: String, timeoutMs: Long = BRIDGE_READ_TIMEOUT_MS): String {
        return try {
            if (!isBridgeReady())
                return "Bridge dir not accessible. Ensure termux-setup-storage has been run and the VoiceOS bridge daemon is running in Termux."
            val cmdFile    = java.io.File(bridgeDir, "cmd.txt")
            val wdirFile   = java.io.File(bridgeDir, "workdir.txt")
            val doneFile   = java.io.File(bridgeDir, "done")
            val resultFile = java.io.File(bridgeDir, "result.txt")
            val exitFile   = java.io.File(bridgeDir, "exit_code.txt")

            // Clear previous result sentinel
            doneFile.delete(); resultFile.delete(); exitFile.delete()

            // Issue the command
            wdirFile.writeText(workdir.ifBlank { "~" })
            cmdFile.writeText(cmd)

            // Wait for daemon to signal completion
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (doneFile.exists()) {
                    val out  = try { resultFile.readText().trim() } catch (_: Exception) { "" }
                    val code = try { exitFile.readText().trim().toIntOrNull() ?: 0 } catch (_: Exception) { 0 }
                    doneFile.delete()
                    return if (code == 0) out.ifBlank { "(command completed, no output)" }
                           else "[exit $code]\n${out.ifBlank { "(no output)" }}"
                }
                Thread.sleep(500)
            }
            "Timeout (${timeoutMs / 1000}s) waiting for bridge result. Bridge daemon may have stopped — restart it in Termux."
        } catch (e: Exception) { "Bridge error: ${e.message}" }
    }

    /**
     * Run a shell command via the Termux bridge.
     * ALL commands execute immediately and return their result inline to the agent,
     * so multi-step workflows (clone → verify → configure credentials → retry) work correctly.
     * Mutation commands use a longer timeout (3 min) for operations like git clone or npm install.
     */
    private fun toolRunShell(args: Map<String, Any>): String {
        val cmd     = args["command"] as? String ?: return "Missing: command"
        val workdir = args["workdir"] as? String ?: "~"
        val timeout = if (isReadOnlyShellCmd(cmd)) BRIDGE_READ_TIMEOUT_MS else BRIDGE_WRITE_TIMEOUT_MS
        return executeBridgeCommand(cmd, workdir, timeout)
    }

    private fun toolGitSetupCredential(args: Map<String, Any>): String {
        val host     = args["host"]     as? String ?: return "Missing host"
        val username = args["username"] as? String ?: return "Missing username"
        val token    = args["token"]    as? String ?: return "Missing token"
        // Configure credential helper and store the credential
        val setupHelper = executeBridgeCommand("git config --global credential.helper store", "~")
        val credLine    = "https://$username:$token@$host"
        // Append to .git-credentials (store helper file)
        val storeResult = executeBridgeCommand(
            "echo '$credLine' >> ~/.git-credentials && chmod 600 ~/.git-credentials", "~"
        )
        // Also set user.email / user.name if not set
        val nameCheck = executeBridgeCommand("git config --global user.name", "~")
        val extraSetup = if (nameCheck.isBlank() || nameCheck.startsWith("[exit")) {
            executeBridgeCommand("git config --global user.name \"$username\"", "~")
            "\nNote: set git user.name to \"$username\". You may also want to set user.email."
        } else ""
        val ok = !storeResult.startsWith("[exit")
        return if (ok) "Git credentials configured for $host (user: $username). HTTPS clones should now work without prompting.$extraSetup"
               else "Credential store failed: $storeResult"
    }

    private fun toolGitClone(args: Map<String, Any>): String {
        val url     = args["url"]     as? String ?: return "Missing url"
        val workdir = args["workdir"] as? String ?: "~"
        val name    = args["name"]    as? String ?: ""

        // Determine target folder name
        val repoName = name.ifBlank { url.trimEnd('/').substringAfterLast('/').removeSuffix(".git") }
        val targetPath = if (workdir == "~") "~/$repoName" else "$workdir/$repoName"

        // Check if already cloned
        val existsCheck = executeBridgeCommand("ls -d $targetPath 2>/dev/null && echo EXISTS", workdir)
        if ("EXISTS" in existsCheck) {
            val statusOut = executeBridgeCommand("git status --short", targetPath)
            return "Already cloned at $targetPath\nStatus: ${statusOut.take(200)}"
        }

        // Run the clone with a long timeout
        val cloneCmd  = if (name.isBlank()) "git clone $url" else "git clone $url $name"
        val cloneOut  = executeBridgeCommand(cloneCmd, workdir, BRIDGE_WRITE_TIMEOUT_MS)

        return if (cloneOut.startsWith("[exit")) {
            // Parse the error and give actionable guidance
            val errLower = cloneOut.lowercase()
            val guidance = when {
                "authentication" in errLower || "403" in errLower || "401" in errLower ->
                    "\n\nNext step: use git_setup_credential to store your Personal Access Token for ${url.substringAfter("://").substringBefore("/")}, then try git_clone again."
                "repository not found" in errLower || "404" in errLower ->
                    "\n\nCheck the repository URL is correct and that you have access to it."
                "already exists" in errLower ->
                    "\n\nFolder already exists at $targetPath. Use run_shell(\"ls $targetPath\") to inspect it."
                "could not resolve host" in errLower || "network" in errLower ->
                    "\n\nNetwork error — check that Termux has internet access."
                "permission denied" in errLower && "publickey" in errLower ->
                    "\n\nSSH key not found. Either use an HTTPS URL instead, or run run_shell(\"ssh-keygen -t ed25519\") to create a key and add the public key to your git host."
                else -> ""
            }
            "Clone failed: $cloneOut$guidance"
        } else {
            // Verify the clone succeeded
            val verifyOut = executeBridgeCommand("git -C $targetPath log --oneline -3 2>&1", workdir)
            "Cloned successfully to $targetPath\n${cloneOut.take(200)}\n\nRecent commits:\n$verifyOut"
        }
    }

    private fun toolGetBridgeSetup(): String {
        val dirPath = try { bridgeDir.absolutePath } catch (_: Exception) { "/sdcard/voiceos_bridge" }
        return """Termux Bridge Setup
===================
1. Open Termux and run: termux-setup-storage
2. Run: curl http://localhost:8741/api/bridge/setup > ~/voiceos-bridge.sh
   (while VoiceOS is open and the server is running)
3. Start daemon: bash ~/voiceos-bridge.sh
   Or background: nohup bash ~/voiceos-bridge.sh > ~/voiceos-bridge.log 2>&1 &

Bridge dir: $dirPath
Status: ${if (isBridgeReady()) "directory accessible" else "not accessible — run termux-setup-storage first"}

Once running, use run_shell to execute commands from VoiceOS.
Read-only commands (ls, git status, cat…) run immediately.
Mutation commands (git commit, rm, pip install…) are queued for your approval."""
    }

    // ── Onboarding conversation context (separate from main agent) ──
    private val onboardingContext = ConversationContext(maxMessages = 40)

    private val ONBOARD_SYS = """You are setting up the VoiceOS agent profile for a new user.
Your goal: collect enough information to make the agent proactively useful every day.
Ask ONE short question at a time. Be warm, brief, and concrete.

Collect (in this order, skip if user already covered it):
1. Their name
2. Active projects — name, one-line description, and Termux path if on this device (e.g. ~/Dev/myapp)
3. People they want to stay connected with — name, relationship (friend/family/work/mentor), and how often (days)
4. Typical work schedule — start time, end time, timezone

After you have reasonable answers for each area (they can be brief), call update_user_profile with everything collected and onboarding_complete: true.

Rules: Never ask more than one question at once. Accept "skip" or "not sure" gracefully. Keep responses ≤3 sentences.
Start: introduce yourself in one sentence, then ask for their name."""

    // ── Subsystems ────────────────────────────────────────────────
    private val actionQueue by lazy { ActionQueue(context) }

    val contextStore by lazy { ContextStore(context) }
    val discoveryEngine by lazy { DiscoveryEngine(context, contextStore) }

    private val skillRegistry by lazy { buildSkillRegistry() }

    /** Holds the tier for the current agent request so the callLLM lambda can use it. */
    private val requestTier = ThreadLocal<ModelTier>()

    private val engine by lazy {
        AgentEngine(
            skillRegistry  = skillRegistry,
            allToolDefs    = allTools,
            executeTool    = ::executeTool,
            callLLM        = { msgs, toolDefs, sys ->
                val tier = requestTier.get() ?: ModelTier.FULL
                callLLMWithTools(msgs, toolDefs, sys, tier)
            },
            buildSysPrompt = { liveCtx, skillSuffix -> agentSystemPrompt(liveCtx, skillSuffix) }
        )
    }

    private val heartbeat by lazy {
        ProbeHeartbeat(
            // ── Native probe handler — zero LLM cost ────────────────
            nativeRunner = { probe ->
                when (probe.name) {
                    "battery_watch" -> nativeBatteryCheck()
                    "notification_triage" -> nativeNotificationTriage()
                    else -> null   // not a native probe, fall through to LLM runner
                }
            },
            // ── LLM probe handler — uses FAST tier model ─────────────
            llmRunner = { probe ->
                val profile  = loadProfile()
                val prompt   = probe.buildPrompt(profile)
                if (prompt == "[idle]") return@ProbeHeartbeat null

                val toolDefs  = if (probe.toolNames.isEmpty()) allTools
                                else allTools.filter { it.name in probe.toolNames }
                val liveCtx   = try { buildLiveContext(minimal = true) } catch (_: Exception) { "" }
                val sysPrompt = agentSystemPrompt(liveCtx)
                val msgs = mutableListOf<Map<String, Any>>(
                    mapOf("role" to "user", "content" to prompt)
                )
                val sb = StringBuilder()
                for (step in 0..2) {
                    // All probes use FAST tier — probes are cheap checks, not complex reasoning
                    val result = callLLMWithTools(msgs, toolDefs, sysPrompt, ModelTier.FAST)
                    if (result.text.isNotBlank()) sb.append(result.text)
                    if (result.done || result.toolCalls.isEmpty()) break
                    // Same fix as AgentEngine: if rawContent is a complete OpenAI message
                    // (has a "role" key), add it directly — don't wrap it in content.
                    val rawContent = result.rawContent
                    @Suppress("UNCHECKED_CAST")
                    if (rawContent is Map<*, *> && (rawContent as Map<*, *>).containsKey("role")) {
                        msgs += rawContent as Map<String, Any>
                    } else {
                        msgs += mapOf("role" to "assistant", "content" to rawContent)
                    }
                    for (tc in result.toolCalls) {
                        val r = try { executeTool(tc.name, tc.args) } catch (e: Exception) { "error: ${e.message}" }
                        if (activeProvider == "anthropic") {
                            msgs += mapOf("role" to "user", "content" to listOf(
                                mapOf("type" to "tool_result", "tool_use_id" to tc.id, "content" to r)
                            ))
                        } else {
                            msgs += mapOf("role" to "tool", "tool_call_id" to tc.id, "name" to tc.name, "content" to r)
                        }
                    }
                }
                sb.toString().trim().ifBlank { null }
            }
        ).also { hb ->
            hb.register(*ProbeHeartbeat.defaultProbes())
        }
    }

    /**
     * Handle LOCAL-tier queries entirely on device — no LLM, no API cost.
     * Returns a response string, or null if the query can't be handled locally
     * (in which case the caller falls through to FAST-tier LLM).
     */
    private fun handleLocalQuery(query: String): String? {
        val q = query.lowercase().trim()
        return when {
            // Task list
            Regex("(show|list|what are|any|my).{0,20}(task|todo|to-do)").containsMatchIn(q) ->
                toolListTasks()

            // Who to reach out to — CRM lookup
            Regex("(who|which).{0,30}(reach out|contact|follow.?up|overdue|haven.t talked)").containsMatchIn(q) ->
                toolGetRelationshipHealth(mapOf("limit" to 5.0, "min_days_since" to 14.0))

            // Battery
            Regex("(battery|charge).{0,20}(level|percent|status|how much)").containsMatchIn(q) -> {
                val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val charging = bm?.isCharging ?: false
                if (pct < 0) null else "Battery: $pct%${if (charging) " (charging)" else ""}"
            }

            // Notifications
            Regex("(what|any|show).{0,20}notification").containsMatchIn(q) ->
                toolGetPendingAttention(mapOf("limit" to 5.0))

            // Notes/recall
            Regex("(recall|what did you note|what.*remember|your notes)").containsMatchIn(q) ->
                toolRecall()

            // Not handled locally
            else -> null
        }
    }

    /** Zero-LLM battery check. Only surfaces if critical. */
    private fun nativeBatteryCheck(): String? {
        val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
            ?: return null
        val pct     = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return if (pct in 1..19 && !charging) "Battery at $pct% — not charging" else null
    }

    /** Zero-LLM notification triage. Checks ContextStore for high-weight unread items. */
    private fun nativeNotificationTriage(): String? {
        return try {
            val items = contextStore.getPendingAttention(5)
            if (items.isEmpty()) return null
            // Only surface items < 10 minutes old with weight >= 2.0
            val cutoff = System.currentTimeMillis() - 10 * 60_000L
            val urgent = items.filter { it.weight >= 2.0 && it.timestamp > cutoff }
            if (urgent.isEmpty()) return null
            val top = urgent.first()
            // Surface at most one item per check, de-duped by title
            top.title.take(80)
        } catch (_: Exception) { null }
    }

    // ── Per-request streaming writer (Ollama token forwarding) ───
    /** Holds the active pipe writer for the current agent request thread.
     *  ollamaWithTools writes tokens here directly so the frontend sees them
     *  progressively rather than waiting for the full response. */
    private val streamingWriter = ThreadLocal<java.io.Writer?>()

    // ── Unified LLM dispatch ──────────────────────────────────────
    private fun callLLMWithTools(
        messages: List<Map<String, Any>>,
        toolDefs: List<ToolDef>,
        sysPrompt: String,
        tier: ModelTier = ModelTier.FULL
    ): AgentResult {
        val model = modelForTier(tier)
        return when (activeProvider) {
            "anthropic" -> anthropicWithTools(messages, toolDefs, sysPrompt, model)
            "openai"    -> openaiWithTools(messages, toolDefs, "https://api.openai.com/v1/chat/completions", sysPrompt, model)
            "grok"      -> openaiWithTools(messages, toolDefs, "https://api.x.ai/v1/chat/completions", sysPrompt, model)
            "ollama"    -> ollamaWithTools(messages, toolDefs, sysPrompt)
            else        -> AgentResult("Unknown provider: $activeProvider", toolCalls = emptyList(), done = true)
        }
    }

    // ── Task storage ──────────────────────────────────────────────
    private fun loadTasks(): MutableList<MutableMap<String, Any>> {
        val json = prefs.getString("agent_tasks", "[]") ?: "[]"
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(json, List::class.java) as List<Map<String, Any>>)
                .map { it.toMutableMap() }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun saveTasks(tasks: List<Map<String, Any>>) {
        prefs.edit().putString("agent_tasks", gson.toJson(tasks)).apply()
    }

    private fun newTaskId() = "t${System.currentTimeMillis().toString(36)}"

    // ════════════════════════════════════════════════════════════════
    // Tool registry
    // ════════════════════════════════════════════════════════════════

    private val allTools = listOf(
        // ── Information ──────────────────────────────────────────
        ToolDef("web_search",
            "Search the web and return a summary of results",
            mapOf("query" to mapOf("type" to "string", "description" to "Search query")),
            listOf("query")),
        ToolDef("read_calendar",
            "Read upcoming calendar events",
            mapOf("days" to mapOf("type" to "integer", "description" to "How many days ahead to look (default 7)")),
            emptyList()),
        ToolDef("get_battery",
            "Get device battery level and charging status",
            emptyMap(), emptyList()),
        ToolDef("get_volume",
            "Get current media volume level (0-100)",
            emptyMap(), emptyList()),
        ToolDef("get_device_info",
            "Get current device info: exact time, date, battery level, WiFi network.",
            emptyMap(), emptyList()),
        ToolDef("get_notifications",
            "Read recent device notifications — messages, alerts, app notifications.",
            mapOf("limit" to mapOf("type" to "integer", "description" to "Max notifications to return (default 10)")),
            emptyList()),
        ToolDef("read_sms",
            "Read recent SMS messages from the inbox.",
            mapOf(
                "contact" to mapOf("type" to "string",  "description" to "Filter by contact name or number (optional)"),
                "limit"   to mapOf("type" to "integer", "description" to "Max messages to return (default 10)")
            ), emptyList()),
        ToolDef("get_recent_calls",
            "Read the recent call log — incoming, outgoing, and missed calls.",
            mapOf("limit" to mapOf("type" to "integer", "description" to "Max calls to return (default 10)")),
            emptyList()),
        ToolDef("recall",
            "Retrieve previously stored notes and remembered information",
            emptyMap(), emptyList()),
        ToolDef("get_clipboard",
            "Read the current text on the clipboard",
            emptyMap(), emptyList()),

        // ── Device control ────────────────────────────────────────
        ToolDef("launch_app",
            "Launch an installed Android app by name",
            mapOf("app_name" to mapOf("type" to "string", "description" to "App name (e.g. Spotify, Chrome)")),
            listOf("app_name")),
        ToolDef("set_alarm",
            "Set an alarm",
            mapOf(
                "hour"    to mapOf("type" to "integer", "description" to "Hour (0-23)"),
                "minute"  to mapOf("type" to "integer", "description" to "Minute (0-59)"),
                "message" to mapOf("type" to "string",  "description" to "Optional alarm label")
            ), listOf("hour", "minute")),
        ToolDef("set_volume",
            "Set media volume level (0-100)",
            mapOf("level" to mapOf("type" to "integer", "description" to "Volume 0-100")),
            listOf("level")),
        ToolDef("set_brightness",
            "Set screen brightness level (0-100). Requires WRITE_SETTINGS permission.",
            mapOf("level" to mapOf("type" to "integer", "description" to "Brightness 0-100")),
            listOf("level")),
        ToolDef("toggle_wifi",
            "Enable or disable WiFi. On Android 10+ opens WiFi settings panel.",
            mapOf("enable" to mapOf("type" to "boolean", "description" to "true to enable, false to disable")),
            listOf("enable")),
        ToolDef("toggle_bluetooth",
            "Enable or disable Bluetooth. On Android 12+ opens Bluetooth settings.",
            mapOf("enable" to mapOf("type" to "boolean", "description" to "true to enable, false to disable")),
            listOf("enable")),
        ToolDef("toggle_dnd",
            "Enable or disable Do Not Disturb mode. Requires notification policy access.",
            mapOf("enable" to mapOf("type" to "boolean", "description" to "true to enable DND, false to disable")),
            listOf("enable")),
        ToolDef("set_clipboard",
            "Copy text to the clipboard",
            mapOf("text" to mapOf("type" to "string", "description" to "Text to copy")),
            listOf("text")),

        // ── Calendar & tasks ──────────────────────────────────────
        ToolDef("create_event",
            "Create a new calendar event. start_time in ISO 8601 (yyyy-MM-dd'T'HH:mm) or natural language like 'tomorrow at 3pm'.",
            mapOf(
                "title"        to mapOf("type" to "string",  "description" to "Event title"),
                "start_time"   to mapOf("type" to "string",  "description" to "Start time — ISO 8601 or natural language"),
                "duration_min" to mapOf("type" to "integer", "description" to "Duration in minutes (default 60)"),
                "description"  to mapOf("type" to "string",  "description" to "Optional event description"),
                "location"     to mapOf("type" to "string",  "description" to "Optional location")
            ), listOf("title", "start_time")),
        ToolDef("list_tasks",
            "List all current tasks with their status and priority",
            emptyMap(), emptyList()),
        ToolDef("add_task",
            "Add a single, specific task to the task list. Each task must represent ONE action. " +
            "Never combine multiple unrelated actions into one task. Never add context from other tasks into notes.",
            mapOf(
                "title"    to mapOf("type" to "string", "description" to "Task title — one specific action, e.g. 'Text Josh to catch up'"),
                "priority" to mapOf("type" to "string", "description" to "Priority: high, medium, or low"),
                "notes"    to mapOf("type" to "string", "description" to "Optional notes or context")
            ), listOf("title")),
        ToolDef("update_task",
            "Update the status or notes of an existing task",
            mapOf(
                "id"     to mapOf("type" to "string", "description" to "Task ID"),
                "status" to mapOf("type" to "string", "description" to "New status: pending, in_progress, blocked, or done"),
                "notes"  to mapOf("type" to "string", "description" to "Updated notes")
            ), listOf("id")),
        ToolDef("complete_task",
            "Mark a task as complete",
            mapOf("id" to mapOf("type" to "string", "description" to "Task ID to mark complete")),
            listOf("id")),
        ToolDef("delete_task",
            "Permanently delete a task from the list by ID",
            mapOf("id" to mapOf("type" to "string", "description" to "Task ID to delete")),
            listOf("id")),
        ToolDef("clear_completed_tasks",
            "Remove all completed (done) tasks from the list in one operation",
            emptyMap(), emptyList()),

        // ── Memory ────────────────────────────────────────────────
        ToolDef("remember",
            "Store a personal note or fact for later recall. Use this for information, observations, and preferences — NOT for to-do items (use add_task for those)",
            mapOf("note" to mapOf("type" to "string", "description" to "The information to store")),
            listOf("note")),

        // ── Social CRM ────────────────────────────────────────────
        ToolDef("get_contact_profile",
            "Get a full social profile for a contact: relationship notes, interaction history, call/SMS recency",
            mapOf("name" to mapOf("type" to "string", "description" to "Contact name")),
            listOf("name")),
        ToolDef("add_relationship_note",
            "Save a note about a person — what you talked about, how they're doing, context for next time",
            mapOf(
                "name" to mapOf("type" to "string", "description" to "Contact name"),
                "note" to mapOf("type" to "string", "description" to "Note to save"),
                "type" to mapOf("type" to "string", "description" to "Relationship type (friend, family, colleague, mentor, etc.) — optional")
            ), listOf("name", "note")),
        ToolDef("log_interaction",
            "Log that you had an interaction with someone (call, coffee, meeting, etc.) with a brief summary",
            mapOf(
                "name"    to mapOf("type" to "string", "description" to "Contact name"),
                "summary" to mapOf("type" to "string", "description" to "Brief summary of the interaction")
            ), listOf("name", "summary")),
        ToolDef("get_relationship_health",
            "Show relationship health — who you haven't spoken to in a while and for how long",
            mapOf(
                "limit"           to mapOf("type" to "integer", "description" to "Max contacts to return (default 10)"),
                "min_days_since"  to mapOf("type" to "integer", "description" to "Only show contacts not contacted in at least this many days (default 0)")
            ), emptyList()),
        ToolDef("suggest_social_outreach",
            "Suggest who you should reach out to based on relationship drift and interaction patterns",
            mapOf("threshold_days" to mapOf("type" to "integer", "description" to "Days of silence before flagging (default 21)")),
            emptyList()),
        ToolDef("get_followup_contacts",
            "Return all contacts the user has explicitly flagged for follow-up, with their notes and last-contact dates. ALWAYS call this first when the user asks to review people, check follow-ups, or says 'who do I need to follow up with'. Each contact's notes contain the reason for follow-up — use them to propose specific tasks or draft messages.",
            emptyMap(), emptyList()),
        ToolDef("flag_followup",
            "Set or clear the follow-up flag on a contact. Use this when the user says they want to remember to follow up with someone, or when you finish a follow-up and want to clear the flag.",
            mapOf(
                "name"    to mapOf("type" to "string", "description" to "Contact name"),
                "enabled" to mapOf("type" to "boolean", "description" to "true to flag for follow-up, false to clear the flag")
            ), listOf("name", "enabled")),
        ToolDef("draft_outreach_message",
            "Package a contact's full profile context and return it ready for message drafting. Use this to compose a warm, context-aware message (text, email, or DM) referencing real details from the relationship.",
            mapOf(
                "name"    to mapOf("type" to "string", "description" to "Contact name"),
                "medium"  to mapOf("type" to "string", "description" to "text | email | whatsapp | dm (default: text)"),
                "tone"    to mapOf("type" to "string", "description" to "warm | casual | professional | funny (default: warm and friendly)"),
                "context" to mapOf("type" to "string", "description" to "Extra context from user about what they want to say")
            ), listOf("name")),
        ToolDef("set_contact_frequency",
            "Set a recurring contact-frequency goal for someone (daily/weekly/biweekly/monthly/quarterly). The agent will nudge when the user is overdue.",
            mapOf(
                "name"        to mapOf("type" to "string",  "description" to "Contact name"),
                "frequency"   to mapOf("type" to "string",  "description" to "daily | weekly | biweekly | monthly | quarterly"),
                "custom_days" to mapOf("type" to "integer", "description" to "Custom interval in days if frequency is not a preset")
            ), listOf("name", "frequency")),
        ToolDef("get_birthday_reminders",
            "Return upcoming birthdays from device contacts within the next N days.",
            mapOf("days_ahead" to mapOf("type" to "integer", "description" to "How many days ahead to look (default 30)")),
            emptyList()),
        ToolDef("schedule_social",
            "Create a calendar event for a social activity with a specific person (coffee, lunch, call, game night, etc.).",
            mapOf(
                "name"             to mapOf("type" to "string",  "description" to "Contact name"),
                "activity"         to mapOf("type" to "string",  "description" to "Type of activity (coffee, lunch, call, etc.)"),
                "date_time"        to mapOf("type" to "string",  "description" to "ISO 8601 date-time string"),
                "duration_minutes" to mapOf("type" to "integer", "description" to "Duration in minutes (default 60)")
            ), listOf("name", "activity", "date_time")),
        ToolDef("get_interaction_history",
            "Return full interaction timeline for a contact: notes, call log, and recent SMS excerpts combined in chronological view.",
            mapOf(
                "name"  to mapOf("type" to "string",  "description" to "Contact name"),
                "limit" to mapOf("type" to "integer", "description" to "Max entries per category (default 20)")
            ), listOf("name")),
        ToolDef("log_sentiment",
            "Record how someone is doing right now. Use this when the user mentions how a contact is feeling or what's going on in their life.",
            mapOf(
                "name"      to mapOf("type" to "string", "description" to "Contact name"),
                "sentiment" to mapOf("type" to "string", "description" to "great | ok | struggling | busy"),
                "note"      to mapOf("type" to "string", "description" to "Optional detail (e.g. 'new job, stressed about move')")
            ), listOf("name", "sentiment")),
        ToolDef("suggest_conversation_topics",
            "Generate specific conversation starters for a contact based on their profile, notes, and relationship history. Call before reaching out if the user seems unsure what to say.",
            mapOf("name" to mapOf("type" to "string", "description" to "Contact name")),
            listOf("name")),
        ToolDef("bulk_relationship_review",
            "Comprehensive review: follow-up flags + relationship drift + social goals status + upcoming birthdays — all in one call. Use this when user asks to 'review people', 'check in on relationships', or 'what's my social situation'.",
            emptyMap(), emptyList()),
        ToolDef("create_social_goal",
            "Set a recurring relationship goal for a contact (e.g. 'monthly coffee with Josh'). Stored and tracked by the agent.",
            mapOf(
                "name"      to mapOf("type" to "string", "description" to "Contact name"),
                "frequency" to mapOf("type" to "string", "description" to "daily | weekly | biweekly | monthly | quarterly"),
                "note"      to mapOf("type" to "string", "description" to "What the goal is about (e.g. 'keep mentor relationship alive')")
            ), listOf("name", "frequency")),
        ToolDef("get_social_goals",
            "List all social goals (contact-frequency targets) and whether each is currently on track or overdue.",
            emptyMap(), emptyList()),

        // ── Communication (queued — require approval) ─────────────
        ToolDef("call_contact",
            "Open the phone dialer to call a contact or number",
            mapOf("name_or_number" to mapOf("type" to "string", "description" to "Contact name or phone number")),
            listOf("name_or_number"), requiresConfirm = true),
        ToolDef("send_sms",
            "Open the SMS app with a pre-filled message ready to send",
            mapOf(
                "name_or_number" to mapOf("type" to "string", "description" to "Contact name or phone number"),
                "body"           to mapOf("type" to "string", "description" to "Message text")
            ), listOf("name_or_number", "body"), requiresConfirm = true),
        ToolDef("draft_email",
            "Queue an email draft for user review before sending. Opens mail app on approval.",
            mapOf(
                "to"      to mapOf("type" to "string", "description" to "Recipient email address or contact name"),
                "subject" to mapOf("type" to "string", "description" to "Email subject line"),
                "body"    to mapOf("type" to "string", "description" to "Email body text")
            ), listOf("to", "subject", "body")),
        ToolDef("send_whatsapp",
            "Queue a WhatsApp message for user review before sending.",
            mapOf(
                "name_or_number" to mapOf("type" to "string", "description" to "Contact name or phone number"),
                "body"           to mapOf("type" to "string", "description" to "Message text")
            ), listOf("name_or_number", "body")),
        ToolDef("navigate",
            "Open Maps to navigate to a location",
            mapOf("destination" to mapOf("type" to "string", "description" to "Destination address or place name")),
            listOf("destination"), requiresConfirm = true),

        // ── User profile ──────────────────────────────────────────
        ToolDef("get_user_profile",
            "Retrieve the user's persistent agent profile: projects, social goals, schedule, focus areas.",
            emptyMap(), emptyList()),
        ToolDef("update_user_profile",
            "Create or update the user's persistent agent profile. Merge partial updates — only supply fields that changed. " +
            "Set onboarding_complete:true when the initial profile collection is finished.",
            mapOf(
                "name"                to mapOf("type" to "string",  "description" to "User's preferred name"),
                "projects"            to mapOf("type" to "array",   "items" to mapOf("type" to "object", "additionalProperties" to true),
                                               "description" to "Active projects: [{name,description,termux_path,status,next_action}]"),
                "social_goals"        to mapOf("type" to "array",   "items" to mapOf("type" to "object", "additionalProperties" to true),
                                               "description" to "Relationship goals: [{person,relationship,frequency_days}]"),
                "schedule"            to mapOf("type" to "object",  "additionalProperties" to true,
                                               "description" to "Work schedule: {timezone,work_start,work_end}"),
                "focus_areas"         to mapOf("type" to "array",   "items" to mapOf("type" to "string"),
                                               "description" to "Main focus areas e.g. ['project development','social networking']"),
                "onboarding_complete" to mapOf("type" to "boolean", "description" to "Set true when profile collection is done")
            ), emptyList()),

        // ── Screen interaction (requires Accessibility Service) ───
        ToolDef("take_screenshot",
            "Capture the current screen and analyze it with vision AI. Returns a description of what's visible: " +
            "app name, text content (quoted verbatim), and tap coordinates for interactive elements. " +
            "Use after launching an app to read its content. Also works for reading emails, messages, web pages.",
            mapOf(
                "query" to mapOf("type" to "string", "description" to "What to focus on, e.g. 'email subject and body' or 'find the reply button'. Leave blank for a general description.")
            ), emptyList()),
        ToolDef("get_screen_text",
            "Extract all visible text from the current screen using the accessibility tree — much faster than take_screenshot. " +
            "Use this first when you just need to read text content (emails, messages, lists). " +
            "Fall back to take_screenshot if the screen is complex or you need coordinates.",
            emptyMap(), emptyList()),
        ToolDef("tap_screen",
            "Tap at a specific pixel coordinate. Use coordinates from take_screenshot or get_screen_text analysis. " +
            "Wait ~800ms after tapping before calling take_screenshot or get_screen_text to let the UI settle.",
            mapOf(
                "x" to mapOf("type" to "number", "description" to "X pixel coordinate (from left edge)"),
                "y" to mapOf("type" to "number", "description" to "Y pixel coordinate (from top edge)")
            ), listOf("x", "y")),
        ToolDef("swipe_screen",
            "Swipe between two points. Use for scrolling (swipe up = scroll down content), pull-to-refresh, or navigation.",
            mapOf(
                "x1"          to mapOf("type" to "number",  "description" to "Start X"),
                "y1"          to mapOf("type" to "number",  "description" to "Start Y"),
                "x2"          to mapOf("type" to "number",  "description" to "End X"),
                "y2"          to mapOf("type" to "number",  "description" to "End Y"),
                "duration_ms" to mapOf("type" to "integer", "description" to "Swipe duration ms (default 300; use 600 for slow scroll)")
            ), listOf("x1", "y1", "x2", "y2")),
        ToolDef("press_back",
            "Press the Android back button. Use to exit an app or go back a screen.",
            emptyMap(), emptyList()),
        ToolDef("press_home",
            "Press the Android home button to return to the launcher.",
            emptyMap(), emptyList()),

        // ── Termux bridge ─────────────────────────────────────────
        ToolDef("run_shell",
            "Run ANY shell command in Termux via the file bridge. " +
            "ALL commands execute immediately and return their output — use multiple run_shell calls to chain steps. " +
            "For multi-step tasks (e.g. git clone → check error → configure credentials → retry): " +
            "inspect each result and call run_shell again based on what you see. " +
            "Read-only commands timeout at 30s. Write operations (git clone, npm install, pip install, make) timeout at 3 minutes. " +
            "Output starts with '[exit N]' if the command failed (non-zero exit code). " +
            "Do NOT assume directory paths — ask the user or check their profile termux_path fields.",
            mapOf(
                "command" to mapOf("type" to "string", "description" to "Shell command to run"),
                "workdir" to mapOf("type" to "string", "description" to "Working directory in Termux (ask user if unsure; default: ~)")
            ), listOf("command")),
        ToolDef("git_setup_credential",
            "Configure git credentials in Termux so HTTPS clones work without prompting. " +
            "Stores a Personal Access Token (PAT) for a git host. " +
            "Run this when git clone fails with authentication errors.",
            mapOf(
                "host"     to mapOf("type" to "string", "description" to "Git host, e.g. github.com or gitlab.com"),
                "username" to mapOf("type" to "string", "description" to "Git username"),
                "token"    to mapOf("type" to "string", "description" to "Personal Access Token or password")
            ), listOf("host", "username", "token")),
        ToolDef("git_clone",
            "Clone a git repository into Termux. Handles the full workflow: " +
            "checks for existing clone, runs git clone, verifies success, and reports any auth errors with next steps.",
            mapOf(
                "url"     to mapOf("type" to "string", "description" to "Repository URL (HTTPS or SSH)"),
                "workdir" to mapOf("type" to "string", "description" to "Parent directory to clone into (default: ~)"),
                "name"    to mapOf("type" to "string", "description" to "Custom folder name for the clone (optional)")
            ), listOf("url")),
        ToolDef("get_bridge_setup",
            "Get Termux bridge setup status and instructions. Call this if run_shell times out or bridge may not be configured.",
            emptyMap(), emptyList()),

        // ── Context discovery ─────────────────────────────────────
        ToolDef("context_search",
            "Search the local context store — an index of your notifications, SMS threads, contacts, calendar, tasks, and notes. " +
            "Use this for questions like 'what did X say?', 'any messages about Y?', or 'what's upcoming related to Z?'.",
            mapOf(
                "query"  to mapOf("type" to "string",  "description" to "Keywords or phrase to search for"),
                "type"   to mapOf("type" to "string",  "description" to "Filter by type: notification, sms_thread, contact, note, task, calendar, app (optional)"),
                "limit"  to mapOf("type" to "integer", "description" to "Max results to return (default 8)")
            ), listOf("query")),
        ToolDef("get_pending_attention",
            "Get a prioritised summary of items that need attention right now: unread messages, recent notifications, " +
            "upcoming calendar events, and high-priority tasks. Use this at the start of agent mode to understand what's pressing.",
            mapOf(
                "limit" to mapOf("type" to "integer", "description" to "Max items to return (default 8)")
            ), emptyList()),
        ToolDef("get_message_threads",
            "Get all recent SMS thread summaries grouped by contact — shows the last few messages per thread and whether there are unread messages.",
            mapOf(
                "limit"   to mapOf("type" to "integer", "description" to "Max threads to return (default 10)"),
                "unread_only" to mapOf("type" to "boolean", "description" to "Only return threads with unread messages (default false)")
            ), emptyList()),
        ToolDef("discover_now",
            "Trigger a fresh scan of all on-device data sources (contacts, SMS, notifications, calendar, tasks, notes) and rebuild the context index. " +
            "Use this when the user says data seems stale or asks you to refresh.",
            emptyMap(), emptyList()),
        ToolDef("get_discovery_status",
            "Show the status of the local context index: when each data type was last scanned and how many documents are indexed.",
            emptyMap(), emptyList()),

        // ── Quick actions ─────────────────────────────────────────────
        ToolDef("set_timer",
            "Set a countdown timer for a specified number of minutes (or seconds). The timer app will alert when done.",
            mapOf(
                "duration_minutes" to mapOf("type" to "number",  "description" to "Timer duration in minutes (can be fractional, e.g. 0.5 for 30s)"),
                "label"            to mapOf("type" to "string",  "description" to "Optional label for the timer")
            ), listOf("duration_minutes")),

        ToolDef("media_control",
            "Control media playback — play, pause, skip to next track, go to previous track, or stop.",
            mapOf("action" to mapOf("type" to "string", "description" to "One of: play, pause, next, previous, stop")),
            listOf("action")),

        ToolDef("open_url",
            "Open a URL in the device browser.",
            mapOf("url" to mapOf("type" to "string", "description" to "Full URL to open, e.g. https://example.com")),
            listOf("url")),

        ToolDef("search_contacts",
            "Search device contacts by name. Returns matching contacts with phone numbers.",
            mapOf(
                "query"  to mapOf("type" to "string",  "description" to "Name or partial name to search"),
                "limit"  to mapOf("type" to "integer", "description" to "Max results to return (default 10)")
            ), listOf("query")),

        ToolDef("get_network_info",
            "Get detailed network info: WiFi SSID, IP address, signal strength, and mobile carrier/data type.",
            emptyMap(), emptyList()),

        ToolDef("get_storage_info",
            "Get internal storage usage: total, used, and available space in GB.",
            emptyMap(), emptyList()),

        ToolDef("set_ringer_mode",
            "Set phone ringer mode.",
            mapOf("mode" to mapOf("type" to "string", "description" to "Mode: normal, vibrate, or silent")),
            listOf("mode")),

        ToolDef("toggle_flashlight",
            "Toggle the device flashlight (torch) on or off.",
            mapOf("enable" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off")),
            listOf("enable")),

        ToolDef("open_settings_screen",
            "Open a specific Android settings screen.",
            mapOf("screen" to mapOf("type" to "string",
                "description" to "Settings screen to open: wifi, bluetooth, battery, display, sound, apps, " +
                "accessibility, notifications, location, storage, developer, about, date_time, language, security, nfc, hotspot")),
            listOf("screen")),

        ToolDef("list_installed_apps",
            "List all installed apps on the device, optionally filtered by name search.",
            mapOf("filter" to mapOf("type" to "string", "description" to "Optional name filter — only return apps matching this substring")),
            emptyList()),

        ToolDef("fetch_webpage",
            "Fetch a web page URL and return its text content (HTML stripped). Good for reading articles, documentation, or any specific page. Use web_search first to find URLs, then fetch_webpage to read them.",
            mapOf(
                "url"       to mapOf("type" to "string",  "description" to "Full URL to fetch"),
                "max_chars" to mapOf("type" to "integer", "description" to "Max characters to return (default 3000)")
            ), listOf("url")),

        ToolDef("create_contact",
            "Open the contacts app to create a new contact with the given details.",
            mapOf(
                "name"  to mapOf("type" to "string", "description" to "Contact full name"),
                "phone" to mapOf("type" to "string", "description" to "Phone number"),
                "email" to mapOf("type" to "string", "description" to "Email address (optional)"),
                "notes" to mapOf("type" to "string", "description" to "Notes (optional)")
            ), listOf("name")),

        // ── Advanced screen automation ─────────────────────────────────
        ToolDef("type_text",
            "Type text into the currently focused input field using accessibility. " +
            "First tap an input field with tap_screen, then call type_text to fill it.",
            mapOf("text" to mapOf("type" to "string", "description" to "Text to type into the focused field")),
            listOf("text")),

        ToolDef("long_press_screen",
            "Long-press at a pixel coordinate. Use for context menus, text selection, or drag-and-drop initiation.",
            mapOf(
                "x"           to mapOf("type" to "number",  "description" to "X pixel coordinate (from left)"),
                "y"           to mapOf("type" to "number",  "description" to "Y pixel coordinate (from top)"),
                "duration_ms" to mapOf("type" to "integer", "description" to "Hold duration in ms (default 800)")
            ), listOf("x", "y")),

        ToolDef("pull_notification_shade",
            "Pull down the notification shade to see all notifications. Use before reading notifications if the panel is closed.",
            emptyMap(), emptyList()),

        ToolDef("open_quick_settings",
            "Open the Quick Settings panel (expanded notification shade with toggles for WiFi, Bluetooth, flashlight, etc.).",
            emptyMap(), emptyList()),

        ToolDef("get_foreground_app",
            "Get the name and package of the app currently in the foreground (what's visible on screen).",
            emptyMap(), emptyList()),

        // ── Intelligence & planning ───────────────────────────────────
        ToolDef("morning_briefing",
            "Generate a comprehensive morning briefing: today's date/time, battery, active tasks, calendar events today, " +
            "pending notifications, and relationship nudges. Use this when the user says 'good morning' or asks for a daily summary.",
            emptyMap(), emptyList()),

        ToolDef("prioritize_tasks",
            "Analyze all current tasks and return them ranked by urgency and importance with brief reasoning. " +
            "Suggests which task to work on next.",
            emptyMap(), emptyList()),

        ToolDef("summarize_sms_thread",
            "Read and summarize an SMS conversation with a contact. Returns a concise summary of the conversation, " +
            "key points, and suggested reply if appropriate.",
            mapOf(
                "contact" to mapOf("type" to "string",  "description" to "Contact name or phone number"),
                "limit"   to mapOf("type" to "integer", "description" to "Number of messages to include (default 20)")
            ), listOf("contact"))
    )

    // ════════════════════════════════════════════════════════════════
    // Social CRM — SharedPrefs-backed relationship layer
    // ════════════════════════════════════════════════════════════════

    /** Load the relationship map: contactName -> {type, notes, lastSeen, interactionCount} */
    private fun loadRelationships(): MutableMap<String, MutableMap<String, Any>> {
        val json = prefs.getString("crm_relationships", "{}") ?: "{}"
        return try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(json, Map::class.java) as Map<String, Map<String, Any>>)
                .mapValues { it.value.toMutableMap() }.toMutableMap()
        } catch (_: Exception) { mutableMapOf() }
    }

    private fun saveRelationships(rel: Map<String, Map<String, Any>>) {
        prefs.edit().putString("crm_relationships", gson.toJson(rel)).apply()
    }

    /** Compute days since last device interaction (call or SMS) for a name. */
    private fun daysSinceLastDeviceContact(name: String): Int? {
        val number = resolveContact(name) ?: return null
        return daysSinceLastContactByNumber(number)
    }

    /** Build a rich contact profile for the agent */
    private fun toolGetContactProfile(args: Map<String, Any>): String {
        val name = args["name"] as? String ?: return "Missing name"
        val rel  = loadRelationships()
        val meta = rel[name.lowercase()] ?: emptyMap<String, Any>()

        val sb = StringBuilder("=== $name ===\n")

        // Follow-up flag — surface prominently so the agent acts on it
        if (meta["followUp"] as? Boolean == true) {
            sb.appendLine("⚑ FLAGGED FOR FOLLOW-UP — check notes below for the reason and propose a specific action.")
        }

        // Basic info from device contacts
        val number = resolveContact(name)
        if (number != null) sb.appendLine("Phone: $number")
        (meta["type"] as? String)?.let { sb.appendLine("Relationship: $it") }

        // Last interaction from device
        val daysSince = daysSinceLastDeviceContact(name)
        if (daysSince != null) sb.appendLine("Last device contact: ${daysSince}d ago")
        else (meta["lastSeen"] as? String)?.let { sb.appendLine("Last seen: $it") }

        // Interaction count
        (meta["interactionCount"] as? Double)?.toInt()?.let { sb.appendLine("Logged interactions: $it") }

        // Relationship notes
        val notes = meta["notes"] as? String ?: ""
        if (notes.isNotBlank()) sb.appendLine("\nNotes:\n$notes")

        // Recent call log
        try {
            val cur = context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.DATE, android.provider.CallLog.Calls.DURATION),
                "${android.provider.CallLog.Calls.NUMBER} LIKE ?",
                arrayOf("%${(number ?: "").takeLast(9)}%"),
                "${android.provider.CallLog.Calls.DATE} DESC"
            )
            val calls = mutableListOf<String>()
            cur?.use { c ->
                while (c.moveToNext() && calls.size < 3) {
                    val type = when (c.getInt(0)) {
                        android.provider.CallLog.Calls.INCOMING_TYPE -> "in"
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> "out"
                        else                                          -> "missed"
                    }
                    val age  = (System.currentTimeMillis() - c.getLong(1)) / 86_400_000L
                    val dur  = c.getLong(2)
                    calls += "[$type, ${age}d ago${if (dur > 0) ", ${dur}s" else ""}]"
                }
            }
            if (calls.isNotEmpty()) sb.appendLine("\nRecent calls: ${calls.joinToString(" ")}")
        } catch (_: Exception) {}

        // Recent SMS
        try {
            val cur = context.contentResolver.query(
                android.net.Uri.parse("content://sms"),
                arrayOf("body", "date", "type"),
                "address LIKE ?",
                arrayOf("%${(number ?: "").takeLast(9)}%"),
                "date DESC"
            )
            val msgs = mutableListOf<String>()
            cur?.use { c ->
                while (c.moveToNext() && msgs.size < 2) {
                    val dir  = if (c.getInt(2) == 1) "them" else "you"
                    val age  = (System.currentTimeMillis() - c.getLong(1)) / 86_400_000L
                    msgs += "[${age}d ago, from $dir] ${c.getString(0)?.take(80) ?: ""}"
                }
            }
            if (msgs.isNotEmpty()) sb.appendLine("\nRecent SMS:\n${msgs.joinToString("\n")}")
        } catch (_: Exception) {}

        return sb.toString().trim()
    }

    private fun toolAddRelationshipNote(args: Map<String, Any>): String {
        val name = args["name"] as? String ?: return "Missing name"
        val note = args["note"] as? String ?: return "Missing note"
        val type = args["type"] as? String  // optional relationship type update
        val rel  = loadRelationships()
        val key  = name.lowercase()
        val entry = rel.getOrPut(key) { mutableMapOf("notes" to "", "interactionCount" to 0.0) }.toMutableMap()
        val ts   = java.text.SimpleDateFormat("MM/dd/yy", java.util.Locale.US).format(java.util.Date())
        val prev = entry["notes"] as? String ?: ""
        entry["notes"] = if (prev.isBlank()) "[$ts] $note" else "$prev\n[$ts] $note"
        if (!type.isNullOrBlank()) entry["type"] = type
        rel[key] = entry
        saveRelationships(rel)
        return "Note saved for $name"
    }

    private fun toolLogInteraction(args: Map<String, Any>): String {
        val name    = args["name"]    as? String ?: return "Missing name"
        val summary = args["summary"] as? String ?: return "Missing summary"
        val rel     = loadRelationships()
        val key     = name.lowercase()
        val entry   = rel.getOrPut(key) { mutableMapOf("notes" to "", "interactionCount" to 0.0) }.toMutableMap()
        val ts      = java.text.SimpleDateFormat("MM/dd/yy", java.util.Locale.US).format(java.util.Date())
        val prev    = entry["notes"] as? String ?: ""
        entry["notes"]            = if (prev.isBlank()) "[$ts] ✓ $summary" else "$prev\n[$ts] ✓ $summary"
        entry["lastSeen"]         = ts
        entry["interactionCount"] = ((entry["interactionCount"] as? Double ?: 0.0) + 1.0)
        rel[key] = entry
        saveRelationships(rel)
        return "Interaction logged with $name: $summary"
    }

    private fun toolGetRelationshipHealth(args: Map<String, Any>): String {
        val limit    = (args["limit"] as? Double)?.toInt() ?: 10
        val minDays  = (args["min_days_since"] as? Double)?.toInt() ?: 0
        val rel      = loadRelationships()

        data class Health(val name: String, val daysSince: Int?, val type: String?, val lastSeen: String?)

        // Gather all tracked + recently called contacts
        val tracked = rel.keys.map { key ->
            val entry = rel[key]!!
            val displayName = key.replaceFirstChar { it.titlecase() }
            val days = daysSinceLastDeviceContact(displayName) ?: daysSinceLastDeviceContact(key)
            Health(displayName, days, entry["type"] as? String, entry["lastSeen"] as? String)
        }
        // Also include top call log contacts not yet tracked
        val callContacts = mutableListOf<Health>()
        try {
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.CACHED_NAME, android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE),
                null, null, "${android.provider.CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val seen = mutableSetOf<String>()
                while (c.moveToNext() && callContacts.size < 20) {
                    val cName = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    if (cName.lowercase() in rel || cName.lowercase() in seen) continue
                    seen += cName.lowercase()
                    val days = ((System.currentTimeMillis() - c.getLong(2)) / 86_400_000L).toInt()
                    callContacts += Health(cName, days, null, null)
                }
            }
        } catch (_: Exception) {}

        val all = (tracked + callContacts)
            .filter { it.daysSince != null && it.daysSince >= minDays }
            .sortedByDescending { it.daysSince ?: 0 }
            .take(limit)

        if (all.isEmpty()) return "All tracked contacts are recently in touch."
        return "Relationship health (days since contact):\n" + all.joinToString("\n") { h ->
            val days = if (h.daysSince != null) "${h.daysSince}d ago" else "unknown"
            "• ${h.name}${if (h.type != null) " [${h.type}]" else ""} — $days"
        }
    }

    private fun toolSuggestSocialOutreach(args: Map<String, Any>): String {
        val threshold = (args["threshold_days"] as? Double)?.toInt() ?: 21
        val rel       = loadRelationships()

        data class Candidate(val name: String, val days: Int, val type: String?, val lastNote: String?)

        val candidates = mutableListOf<Candidate>()
        // From tracked relationships
        rel.forEach { (key, entry) ->
            val displayName = key.replaceFirstChar { it.titlecase() }
            val days = daysSinceLastDeviceContact(displayName) ?: daysSinceLastDeviceContact(key) ?: return@forEach
            if (days >= threshold) {
                val lastNote = (entry["notes"] as? String)?.lines()?.lastOrNull()
                candidates += Candidate(displayName, days, entry["type"] as? String, lastNote)
            }
        }
        // From call log — frequent contacts who went quiet
        val callFrequency = mutableMapOf<String, Pair<Int, Long>>() // name -> (count, lastDate)
        try {
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.CACHED_NAME, android.provider.CallLog.Calls.DATE),
                null, null, "${android.provider.CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val cName = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val date  = c.getLong(1)
                    val cur   = callFrequency[cName] ?: Pair(0, 0L)
                    callFrequency[cName] = Pair(cur.first + 1, maxOf(cur.second, date))
                }
            }
        } catch (_: Exception) {}
        callFrequency.forEach { (name, pair) ->
            val (count, lastDate) = pair
            if (count < 3) return@forEach
            val days = ((System.currentTimeMillis() - lastDate) / 86_400_000L).toInt()
            if (days >= threshold && candidates.none { it.name.equals(name, ignoreCase = true) }) {
                candidates += Candidate(name, days, null, "Frequent contact (${count} calls total)")
            }
        }

        if (candidates.isEmpty()) return "Everyone you track is recently in touch. Good job!"
        val sorted = candidates.sortedByDescending { it.days }.take(5)
        return "People to reach out to:\n" + sorted.joinToString("\n") { c ->
            buildString {
                append("• ${c.name} — ${c.days}d since last contact")
                c.type?.let { append(" [$it]") }
                c.lastNote?.let { append("\n  Last note: $it") }
            }
        }
    }

    private fun toolGetFollowupContacts(): String {
        val rel = loadRelationships()
        val followups = rel.filter { (_, entry) -> entry["followUp"] as? Boolean == true }
        if (followups.isEmpty()) return "No contacts are currently flagged for follow-up.\n(Tip: the user can tap 🔔 on any contact card in the People panel to flag them.)"
        val sb = StringBuilder("Follow-up contacts (${followups.size} flagged):\n")
        followups.forEach { (key, entry) ->
            val displayName = (entry["displayName"] as? String)?.takeIf { it.isNotBlank() }
                ?: key.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
            val daysSince = daysSinceLastDeviceContact(displayName) ?: daysSinceLastDeviceContact(key)
            sb.appendLine("\n── $displayName ──")
            (entry["type"] as? String)?.let { sb.appendLine("  Relationship: $it") }
            if (daysSince != null) sb.appendLine("  Last contact: ${daysSince}d ago")
            val notesRaw = entry["notes"] as? String ?: ""
            if (notesRaw.isNotBlank()) {
                sb.appendLine("  Notes:")
                notesRaw.lines().filter { it.isNotBlank() }.forEach { line ->
                    sb.appendLine("    $line")
                }
            } else {
                sb.appendLine("  No notes — ask the user what they need to follow up about.")
            }
        }
        sb.appendLine("\nINSTRUCTION: For each person above, read their notes carefully and propose a SPECIFIC task or action (e.g. 'Text Josh about the payment plan' or 'Call Sarah to schedule coffee'). Add each as a separate task with add_task if the user confirms.")
        return sb.toString().trimEnd()
    }

    private fun toolFlagFollowup(args: Map<String, Any>): String {
        val name    = args["name"]    as? String  ?: return "Missing: name"
        val enabled = when (val e = args["enabled"]) {
            is Boolean -> e
            is String  -> e.equals("true", ignoreCase = true)
            else       -> return "Missing: enabled (true/false)"
        }
        val rel   = loadRelationships()
        val key   = name.lowercase()
        val entry = rel.getOrPut(key) { mutableMapOf("notes" to "", "interactionCount" to 0.0) }.toMutableMap()
        entry["followUp"] = enabled
        rel[key] = entry
        saveRelationships(rel)
        return if (enabled) "$name flagged for follow-up." else "Follow-up cleared for $name."
    }

    /** Draft context packaged for outreach message composition. */
    private fun toolDraftOutreachMessage(args: Map<String, Any>): String {
        val name    = args["name"]    as? String ?: return "Missing: name"
        val tone    = args["tone"]    as? String ?: "warm and friendly"
        val medium  = args["medium"]  as? String ?: "text"
        val context = args["context"] as? String  // optional extra context from user
        val profile = toolGetContactProfile(mapOf("name" to name))
        return buildString {
            appendLine("=== Draft outreach to $name ===")
            appendLine("Medium: $medium | Tone: $tone")
            if (!context.isNullOrBlank()) appendLine("User context: $context")
            appendLine()
            appendLine(profile)
            appendLine()
            appendLine("Using the profile above, compose a $medium message that:")
            appendLine("• Opens naturally based on the last interaction or notes")
            appendLine("• References something specific (topic, shared memory, their situation)")
            appendLine("• Is appropriately brief (1-3 sentences for text, short paragraph for email)")
            appendLine("• Matches a $tone tone")
            appendLine("• Ends with a soft call-to-action (catch up, grab coffee, quick call)")
            appendLine()
            appendLine("Write ONLY the message body — no labels or explanation.")
        }.trimEnd()
    }

    /** Set a recurring contact frequency goal for a person. */
    private fun toolSetContactFrequency(args: Map<String, Any>): String {
        val name  = args["name"]      as? String ?: return "Missing: name"
        val freq  = args["frequency"] as? String ?: return "Missing: frequency (daily/weekly/biweekly/monthly/quarterly)"
        val days  = when (freq.lowercase()) {
            "daily"      -> 1;  "weekly" -> 7;  "biweekly" -> 14
            "monthly"    -> 30; "quarterly" -> 90
            else         -> (args["custom_days"] as? Double)?.toInt() ?: 30
        }
        val rel   = loadRelationships()
        val key   = name.lowercase()
        val entry = rel.getOrPut(key) { mutableMapOf("notes" to "", "interactionCount" to 0.0) }.toMutableMap()
        entry["frequency"]     = freq.lowercase()
        entry["frequencyDays"] = days.toDouble()
        rel[key] = entry
        saveRelationships(rel)
        return "Frequency for $name set to $freq (every $days days). I'll nudge you when you're overdue."
    }

    /** Upcoming birthdays from device contacts within the next N days. */
    private fun toolGetBirthdayReminders(args: Map<String, Any>): String {
        val daysAhead = (args["days_ahead"] as? Double)?.toInt() ?: 30
        val birthdays = mutableListOf<Triple<String, Int, Int>>()   // name, month, day
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Event.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                        ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name    = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val dateStr = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val clean   = dateStr.replace("--", "")
                    val parts   = clean.split("-")
                    val month   = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: continue
                    val day     = parts.getOrNull(parts.size - 1)?.toIntOrNull() ?: continue
                    birthdays += Triple(name, month, day)
                }
            }
        } catch (e: Exception) { return "Could not read birthday data: ${e.message}" }

        val now      = java.util.Calendar.getInstance()
        val nowMonth = now.get(java.util.Calendar.MONTH) + 1
        val nowDay   = now.get(java.util.Calendar.DAY_OF_MONTH)
        fun dayOfYear(m: Int, d: Int) = m * 31 + d
        val nowDoy   = dayOfYear(nowMonth, nowDay)

        val upcoming = birthdays.filter { (_, m, d) ->
            var diff = dayOfYear(m, d) - nowDoy
            if (diff < 0) diff += 372
            diff in 0..daysAhead
        }.sortedWith(compareBy { (_, m, d) ->
            var diff = dayOfYear(m, d) - nowDoy; if (diff < 0) diff += 372; diff
        })

        if (upcoming.isEmpty()) return "No birthdays in the next $daysAhead days."
        val months = java.text.DateFormatSymbols().shortMonths
        return "Upcoming birthdays (next ${daysAhead}d):\n" +
            upcoming.joinToString("\n") { (n, m, d) -> "• $n — ${months[m-1]} $d" }
    }

    /** Create a social calendar event with a person. */
    private fun toolScheduleSocial(args: Map<String, Any>): String {
        val name     = args["name"]       as? String ?: return "Missing: name"
        val activity = args["activity"]   as? String ?: "catch-up"
        val dateTime = args["date_time"]  as? String ?: return "Missing: date_time (ISO 8601)"
        val duration = (args["duration_minutes"] as? Double)?.toInt() ?: 60
        // Delegate to create_event
        return toolCreateEvent(mapOf(
            "title"            to "$activity with $name",
            "start"            to dateTime,
            "duration_minutes" to duration.toDouble(),
            "description"      to "Social – $activity with $name. Logged by VoiceOS."
        ))
    }

    /** Full interaction timeline for a contact: notes + calls + SMS. */
    private fun toolGetInteractionHistory(args: Map<String, Any>): String {
        val name  = args["name"]  as? String ?: return "Missing: name"
        val limit = (args["limit"] as? Double)?.toInt() ?: 20
        val rel   = loadRelationships()
        val key   = name.lowercase()
        val meta  = rel[key] ?: emptyMap<String, Any>()
        val sb    = StringBuilder("=== Interaction history: $name ===\n")

        // Notes timeline
        val notesRaw = meta["notes"] as? String ?: ""
        val noteLines = notesRaw.lines().filter { it.isNotBlank() }
        if (noteLines.isNotEmpty()) {
            sb.appendLine("\nNotes & interactions:")
            noteLines.takeLast(limit).forEach { sb.appendLine("  $it") }
        }

        // Device calls
        val number = resolveContact(name) ?: resolveContact(key)
        if (number != null) {
            val calls = mutableListOf<String>()
            try {
                context.contentResolver.query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    arrayOf(android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.DATE,
                            android.provider.CallLog.Calls.DURATION),
                    "${android.provider.CallLog.Calls.NUMBER} LIKE ?",
                    arrayOf("%${number.takeLast(9)}%"),
                    "${android.provider.CallLog.Calls.DATE} DESC"
                )?.use { c ->
                    while (c.moveToNext() && calls.size < limit) {
                        val type = when (c.getInt(0)) {
                            android.provider.CallLog.Calls.INCOMING_TYPE -> "↙ call"
                            android.provider.CallLog.Calls.OUTGOING_TYPE -> "↗ call"
                            else                                          -> "✗ missed"
                        }
                        val ts  = java.text.SimpleDateFormat("MM/dd/yy", java.util.Locale.US)
                            .format(java.util.Date(c.getLong(1)))
                        val dur = c.getLong(2)
                        calls += "[$ts] $type${if (dur > 0) " (${dur}s)" else ""}"
                    }
                }
            } catch (_: Exception) {}
            if (calls.isNotEmpty()) { sb.appendLine("\nCall log:"); calls.forEach { sb.appendLine("  $it") } }

            // SMS excerpts
            val msgs = mutableListOf<String>()
            try {
                context.contentResolver.query(
                    android.net.Uri.parse("content://sms"),
                    arrayOf("body", "date", "type"),
                    "address LIKE ?",
                    arrayOf("%${number.takeLast(9)}%"),
                    "date DESC"
                )?.use { c ->
                    while (c.moveToNext() && msgs.size < 5) {
                        val dir = if (c.getInt(2) == 1) "them" else "you"
                        val ts  = java.text.SimpleDateFormat("MM/dd/yy", java.util.Locale.US)
                            .format(java.util.Date(c.getLong(1)))
                        msgs += "[$ts] ($dir) ${c.getString(0)?.take(80) ?: ""}"
                    }
                }
            } catch (_: Exception) {}
            if (msgs.isNotEmpty()) { sb.appendLine("\nRecent SMS:"); msgs.forEach { sb.appendLine("  $it") } }
        }

        return sb.toString().trimEnd().ifBlank { "No interaction history found for $name." }
    }

    /** Record how someone is doing right now — factors into outreach timing. */
    private fun toolLogSentiment(args: Map<String, Any>): String {
        val name      = args["name"]      as? String ?: return "Missing: name"
        val sentiment = args["sentiment"] as? String ?: return "Missing: sentiment (great/ok/struggling/busy)"
        val note      = args["note"]      as? String ?: ""
        val rel   = loadRelationships()
        val key   = name.lowercase()
        val entry = rel.getOrPut(key) { mutableMapOf("notes" to "", "interactionCount" to 0.0) }.toMutableMap()
        val ts    = java.text.SimpleDateFormat("MM/dd/yy", java.util.Locale.US).format(java.util.Date())
        entry["sentiment"] = mapOf("value" to sentiment.lowercase(), "ts" to ts)
        // Also append to notes so the agent sees it in profiles
        val prev = entry["notes"] as? String ?: ""
        val noteText = "[$ts] Sentiment: $sentiment${if (note.isNotBlank()) " — $note" else ""}"
        entry["notes"] = if (prev.isBlank()) noteText else "$prev\n$noteText"
        rel[key] = entry
        saveRelationships(rel)
        return "Logged: $name is $sentiment${if (note.isNotBlank()) " ($note)" else ""}."
    }

    /** Conversation starter suggestions based on contact profile and notes. */
    private fun toolSuggestConversationTopics(args: Map<String, Any>): String {
        val name    = args["name"] as? String ?: return "Missing: name"
        val profile = toolGetContactProfile(mapOf("name" to name))
        return buildString {
            appendLine("=== Conversation topics for $name ===")
            appendLine(profile)
            appendLine()
            appendLine("Based on the above, suggest 3-5 specific conversation openers or topics:")
            appendLine("• Reference real details from their notes (situations, projects, events mentioned)")
            appendLine("• Include one check-in on something they were dealing with")
            appendLine("• Include one topic that could deepen the relationship")
            appendLine("• Keep each suggestion to one sentence")
        }.trimEnd()
    }

    /** Comprehensive relationship overview: followups + drift + upcoming birthdays. */
    private fun toolBulkRelationshipReview(args: Map<String, Any>): String {
        val sb = StringBuilder("=== Full Relationship Review ===\n")

        // Flagged follow-ups
        val followups = toolGetFollowupContacts()
        if (!followups.startsWith("No contacts")) {
            sb.appendLine("\n── Follow-ups ──"); sb.appendLine(followups)
        } else {
            sb.appendLine("\n── Follow-ups ── None flagged.")
        }

        // Relationship drift (14+ days)
        val health = toolGetRelationshipHealth(mapOf("limit" to 8.0, "min_days_since" to 14.0))
        sb.appendLine("\n── Drift (14+ days) ──"); sb.appendLine(health)

        // Social goals status
        val goals = toolGetSocialGoals()
        if (!goals.startsWith("No social")) {
            sb.appendLine("\n── Goals ──"); sb.appendLine(goals)
        }

        // Upcoming birthdays
        val bdays = toolGetBirthdayReminders(mapOf("days_ahead" to 14.0))
        sb.appendLine("\n── Birthdays (14 days) ──"); sb.appendLine(bdays)

        sb.appendLine("\nINSTRUCTION: For each section above, identify the single most time-sensitive action and propose it to the user.")
        return sb.toString().trimEnd()
    }

    /** Set a recurring social goal for a person. */
    private fun toolCreateSocialGoal(args: Map<String, Any>): String {
        val name      = args["name"]      as? String ?: return "Missing: name"
        val frequency = args["frequency"] as? String ?: return "Missing: frequency"
        val note      = args["note"]      as? String ?: ""
        // Reuse set_contact_frequency to store the cadence
        toolSetContactFrequency(mapOf("name" to name, "frequency" to frequency))
        val rel   = loadRelationships()
        val key   = name.lowercase()
        val entry = rel.getOrPut(key) { mutableMapOf("notes" to "", "interactionCount" to 0.0) }.toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val goals = ((entry["socialGoals"] as? List<*>)?.filterIsInstance<Map<String, Any>>()
            ?.map { it.toMutableMap() } ?: emptyList<MutableMap<String, Any>>()).toMutableList()
        val ts = java.text.SimpleDateFormat("MM/dd/yy", java.util.Locale.US).format(java.util.Date())
        goals.removeAll { (it["frequency"] as? String) == frequency }   // replace if same freq exists
        goals += mutableMapOf("frequency" to frequency, "note" to note, "ts" to ts)
        entry["socialGoals"] = goals
        rel[key] = entry
        saveRelationships(rel)
        return "Goal set: connect with $name $frequency${if (note.isNotBlank()) " ($note)" else ""}."
    }

    /** List all social goals and whether each is on track based on last contact date. */
    private fun toolGetSocialGoals(): String {
        val rel = loadRelationships()
        data class GoalStatus(val name: String, val freq: String, val days: Int, val targetDays: Int, val note: String)
        val statuses = mutableListOf<GoalStatus>()
        rel.forEach { (key, entry) ->
            val freq = entry["frequency"] as? String ?: return@forEach
            val targetDays = (entry["frequencyDays"] as? Double)?.toInt() ?: return@forEach
            val displayName = (entry["displayName"] as? String)?.takeIf { it.isNotBlank() }
                ?: key.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
            val daysSince = daysSinceLastDeviceContact(displayName) ?: daysSinceLastDeviceContact(key) ?: -1
            @Suppress("UNCHECKED_CAST")
            val goalNote = ((entry["socialGoals"] as? List<*>)?.filterIsInstance<Map<String, Any>>()
                ?.firstOrNull { it["frequency"] == freq }?.get("note") as? String) ?: ""
            statuses += GoalStatus(displayName, freq, daysSince, targetDays, goalNote)
        }
        if (statuses.isEmpty()) return "No social goals set yet. Use set_contact_frequency or create_social_goal to add some."
        val (overdue, onTrack) = statuses.partition { it.days >= 0 && it.days > it.targetDays }
        return buildString {
            if (overdue.isNotEmpty()) {
                appendLine("⚠ Overdue:")
                overdue.sortedByDescending { it.days - it.targetDays }.forEach { g ->
                    val over = if (g.days >= 0) "${g.days - g.targetDays}d overdue" else "unknown"
                    appendLine("  • ${g.name} [${g.freq}] — $over${if (g.note.isNotBlank()) " · ${g.note}" else ""}")
                }
            }
            if (onTrack.isNotEmpty()) {
                appendLine("✓ On track:")
                onTrack.forEach { g ->
                    val remaining = g.targetDays - g.days
                    appendLine("  • ${g.name} [${g.freq}] — ${if (remaining > 0) "${remaining}d left" else "contact today"}${if (g.note.isNotBlank()) " · ${g.note}" else ""}")
                }
            }
        }.trimEnd()
    }

    // ── CRM HTTP handlers ─────────────────────────────────────────

    /**
     * Pre-build a map of phone-number-suffix → latest contact epoch-ms from call log + SMS.
     * Two queries instead of 2-per-contact, making handleCrmContacts O(1) in DB round-trips.
     */
    private fun buildLastContactMap(): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        try {
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE),
                null, null, "${android.provider.CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val num = c.getString(0) ?: ""
                    if (num.isBlank()) continue
                    val suffix = num.takeLast(9)
                    if (suffix !in map) map[suffix] = c.getLong(1)
                }
            }
        } catch (_: Exception) {}
        try {
            context.contentResolver.query(
                android.net.Uri.parse("content://sms"),
                arrayOf("address", "date"),
                null, null, "date DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(0) ?: ""
                    if (addr.isBlank()) continue
                    val suffix = addr.takeLast(9)
                    val date = c.getLong(1)
                    map[suffix] = maxOf(map[suffix] ?: 0L, date)
                }
            }
        } catch (_: Exception) {}
        return map
    }

    /** Reverse-lookup: phone number → display name from contacts, used by import. */
    private fun resolveNumberToDisplayName(number: String): String? {
        return try {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(android.net.Uri.encode(number)).build()
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }
    }

    /** Build the full contact record map used in API responses */
    private fun buildContactRecord(
        key: String,
        entry: MutableMap<String, Any>,
        lastContactMs: Map<String, Long>? = null
    ): Map<String, Any> {
        val displayName = (entry["displayName"] as? String)?.takeIf { it.isNotBlank() }
            ?: key.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
        val phone    = (entry["phone"] as? String) ?: resolveContact(displayName) ?: resolveContact(key) ?: ""
        val days     = if (phone.isNotBlank()) {
            if (lastContactMs != null) {
                val suffix = phone.takeLast(9)
                val latest = lastContactMs[suffix] ?: 0L
                if (latest > 0L) ((System.currentTimeMillis() - latest) / 86_400_000L).toInt() else null
            } else {
                daysSinceLastContactByNumber(phone)
            }
        } else null
        // Split notes into a list of {ts, text} objects for the UI
        val notesRaw = entry["notes"] as? String ?: ""
        val noteLines = if (notesRaw.isBlank()) emptyList<Map<String, String>>()
        else notesRaw.lines().filter { it.isNotBlank() }.map { line ->
            val tsMatch = Regex("""^\[(\d{2}/\d{2}/\d{2})] (.*)$""").matchEntire(line.trim())
            if (tsMatch != null) mapOf("ts" to tsMatch.groupValues[1], "text" to tsMatch.groupValues[2])
            else mapOf("ts" to "", "text" to line.trim())
        }
        @Suppress("UNCHECKED_CAST")
        val tags = (entry["tags"] as? List<*>)?.filterIsInstance<String>() ?: emptyList<String>()
        @Suppress("UNCHECKED_CAST")
        val sentiment = entry["sentiment"] as? Map<String, Any>
        val freqDays  = (entry["frequencyDays"] as? Double)?.toInt()
        val freqOverdue = if (freqDays != null && days != null && days >= 0) days > freqDays else false
        return mapOf(
            "key"              to key,
            "name"             to displayName,
            "phone"            to phone,
            "type"             to (entry["type"] as? String ?: ""),
            "tags"             to tags,
            "followUp"         to (entry["followUp"] as? Boolean ?: false),
            "birthday"         to (entry["birthday"] as? String ?: ""),
            "frequency"        to (entry["frequency"] as? String ?: ""),
            "frequencyDays"    to (freqDays ?: 0),
            "frequencyOverdue" to freqOverdue,
            "sentiment"        to (sentiment ?: emptyMap<String, Any>()),
            "daysSince"        to (days ?: -1),
            "lastSeen"         to (entry["lastSeen"] as? String ?: ""),
            "notes"            to noteLines,
            "notesRaw"         to notesRaw,
            "interactionCount" to ((entry["interactionCount"] as? Double)?.toInt() ?: 0)
        )
    }

    /** Days since last call OR SMS using a phone number directly (no name lookup) */
    private fun daysSinceLastContactByNumber(number: String): Int? {
        val suffix = number.takeLast(9)
        var latest = 0L
        try {
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.DATE),
                "${android.provider.CallLog.Calls.NUMBER} LIKE ?",
                arrayOf("%$suffix%"),
                "${android.provider.CallLog.Calls.DATE} DESC"
            )?.use { c -> if (c.moveToFirst()) latest = maxOf(latest, c.getLong(0)) }
        } catch (_: Exception) {}
        try {
            context.contentResolver.query(
                android.net.Uri.parse("content://sms"),
                arrayOf("date"),
                "address LIKE ?",
                arrayOf("%$suffix%"),
                "date DESC"
            )?.use { c -> if (c.moveToFirst()) latest = maxOf(latest, c.getLong(0)) }
        } catch (_: Exception) {}
        if (latest == 0L) return null
        return ((System.currentTimeMillis() - latest) / 86_400_000L).toInt()
    }

    private fun handleCrmContacts(session: IHTTPSession): Response {
        val typeFilter   = session.parameters["type"]?.firstOrNull()?.takeIf { it.isNotBlank() && it != "all" }
        val search       = session.parameters["q"]?.firstOrNull()?.lowercase()?.trim()
        val followupOnly = session.parameters["followup"]?.firstOrNull() == "true"
        val rel          = loadRelationships()
        // Single pre-fetch of all call+SMS dates (2 queries total instead of 2 per contact)
        val lastContactMs = buildLastContactMap()
        var contacts   = rel.map { (key, entry) -> buildContactRecord(key, entry, lastContactMs) }
        if (followupOnly) contacts = contacts.filter { it["followUp"] as? Boolean == true }
        if (!typeFilter.isNullOrBlank()) contacts = contacts.filter {
            (it["type"] as? String)?.equals(typeFilter, ignoreCase = true) == true
        }
        if (!search.isNullOrBlank()) contacts = contacts.filter {
            (it["name"] as? String)?.lowercase()?.contains(search) == true ||
            (it["tags"] as? List<*>)?.any { t -> (t as? String)?.lowercase()?.contains(search) == true } == true
        }
        contacts = contacts.sortedWith(compareByDescending<Map<String, Any>> {
            val d = it["daysSince"] as? Int ?: -1; if (d >= 0) d else Int.MIN_VALUE
        })
        // needsAttention: use already-computed daysSince from the full (unfiltered) contact set
        val needsAttention = rel.count { (key, entry) ->
            val phone = (entry["phone"] as? String)?.takeIf { it.isNotBlank() }
                ?: resolveContact(key.replaceFirstChar { c -> c.titlecase() }) ?: ""
            if (phone.isBlank()) return@count false
            val latest = lastContactMs[phone.takeLast(9)] ?: 0L
            if (latest == 0L) return@count false
            ((System.currentTimeMillis() - latest) / 86_400_000L).toInt() >= 14
        }
        return jsonResponse(mapOf("contacts" to contacts, "total" to rel.size, "needsAttention" to needsAttention))
    }

    private fun handleCrmFollowups(): Response {
        val rel = loadRelationships()
        val lastContactMs = buildLastContactMap()
        val contacts = rel
            .filter { (_, entry) -> entry["followUp"] as? Boolean == true }
            .map { (key, entry) -> buildContactRecord(key, entry.toMutableMap(), lastContactMs) }
            .sortedByDescending { (it["daysSince"] as? Int ?: -1).let { d -> if (d >= 0) d else Int.MIN_VALUE } }
        return jsonResponse(mapOf("contacts" to contacts, "total" to contacts.size))
    }

    private fun handleCrmAddNote(session: IHTTPSession): Response {
        val body = parseBody(session)
        val name = body["name"] as? String ?: return jsonResponse(mapOf("error" to "missing name"), Status.BAD_REQUEST)
        val note = body["note"] as? String ?: return jsonResponse(mapOf("error" to "missing note"), Status.BAD_REQUEST)
        val type = body["type"] as? String
        val args = mutableMapOf<String, Any>("name" to name, "note" to note)
        if (!type.isNullOrBlank()) args["type"] = type
        val result = toolAddRelationshipNote(args)
        return jsonResponse(mapOf("ok" to true, "message" to result))
    }

    private fun handleCrmUpdateContact(session: IHTTPSession): Response {
        val body = parseBody(session)
        val name = body["name"] as? String ?: return jsonResponse(mapOf("error" to "missing name"), Status.BAD_REQUEST)
        val key  = name.lowercase()
        val rel  = loadRelationships()
        val entry = rel.getOrPut(key) { mutableMapOf("notes" to "", "interactionCount" to 0.0) }.toMutableMap()
        (body["type"]        as? String)?.let { entry["type"]        = it }
        (body["displayName"] as? String)?.let { entry["displayName"] = it }
        (body["phone"]       as? String)?.let { entry["phone"]       = it }
        (body["birthday"]    as? String)?.let { entry["birthday"]    = it }
        (body["frequency"]   as? String)?.let { entry["frequency"]   = it }
        (body["frequencyDays"] as? Double)?.let { entry["frequencyDays"] = it }
        @Suppress("UNCHECKED_CAST")
        (body["tags"] as? List<*>)?.filterIsInstance<String>()?.let { entry["tags"] = it }
        // followUp: accept Boolean from JSON, or string "true"/"false" for robustness
        when (val fu = body["followUp"]) {
            is Boolean -> entry["followUp"] = fu
            is String  -> entry["followUp"] = fu.equals("true", ignoreCase = true)
        }
        rel[key] = entry
        saveRelationships(rel)
        return jsonResponse(mapOf("ok" to true, "contact" to buildContactRecord(key, entry)))
    }

    private fun handleCrmDeleteContact(session: IHTTPSession): Response {
        val body = parseBody(session)
        val name = body["name"] as? String ?: return jsonResponse(mapOf("error" to "missing name"), Status.BAD_REQUEST)
        val rel  = loadRelationships()
        rel.remove(name.lowercase())
        saveRelationships(rel)
        return jsonResponse(mapOf("ok" to true))
    }

    /** Bulk-import device contacts (phone book + SMS threads) into the CRM store (non-destructive). */
    private fun handleCrmImport(): Response {
        val rel  = loadRelationships()
        var added   = 0
        var skipped = 0
        val seen = mutableSetOf<String>()   // keys touched this pass (avoids dupes across sources)

        // ── 1. Phone book contacts ────────────────────────────────
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val dName  = cur.getString(0)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val number = cur.getString(1)?.trim() ?: ""
                    val key    = dName.lowercase()
                    if (key in seen) continue; seen += key
                    if (key in rel) { skipped++; continue }
                    rel[key] = mutableMapOf(
                        "displayName"      to dName,
                        "phone"            to number,
                        "type"             to "",
                        "tags"             to emptyList<String>(),
                        "notes"            to "",
                        "interactionCount" to 0.0
                    )
                    added++
                }
            }
        } catch (e: Exception) {
            return jsonResponse(mapOf("error" to "Import failed: ${e.message}"), Status.INTERNAL_ERROR)
        }

        // ── 2. SMS thread contacts (catches texters not saved in phone book) ──
        try {
            val seenNums = mutableSetOf<String>()
            context.contentResolver.query(
                android.net.Uri.parse("content://sms"),
                arrayOf("address"),
                null, null, "date DESC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val address = cur.getString(0)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val suffix  = address.takeLast(9)
                    if (suffix in seenNums) continue; seenNums += suffix
                    // Resolve number → saved contact name (skip unknown numbers)
                    val dName = resolveNumberToDisplayName(address) ?: continue
                    val key = dName.lowercase()
                    if (key in seen || key in rel) { skipped++; continue }
                    seen += key
                    rel[key] = mutableMapOf(
                        "displayName"      to dName,
                        "phone"            to address,
                        "type"             to "",
                        "tags"             to emptyList<String>(),
                        "notes"            to "",
                        "interactionCount" to 0.0
                    )
                    added++
                }
            }
        } catch (_: Exception) {}

        saveRelationships(rel)
        return jsonResponse(mapOf("ok" to true, "added" to added, "skipped" to skipped, "total" to rel.size))
    }

    private fun handleCrmInsights(): Response {
        val health   = toolGetRelationshipHealth(mapOf("limit" to 8.0))
        val outreach = toolSuggestSocialOutreach(mapOf("threshold_days" to 14.0))
        return jsonResponse(mapOf("health" to health, "outreach" to outreach))
    }

    private fun handleCrmProfile(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull()
            ?: return jsonResponse(mapOf("error" to "missing name"), Status.BAD_REQUEST)
        val profile = toolGetContactProfile(mapOf("name" to name))
        return jsonResponse(mapOf("profile" to profile))
    }

    // ── Skill registry ────────────────────────────────────────────
    private fun buildSkillRegistry(): SkillRegistry {
        val reg = SkillRegistry()
        reg.register(
            SkillDef(
                name               = "communication",
                description        = "SMS, calls, email, WhatsApp, notifications, contacts",
                systemPromptSuffix = """
## Active skill: Communication
Focus on the user's communication needs. Check notifications, SMS, call log as needed.
For outgoing messages: use draft_email to queue emails, send_whatsapp for WhatsApp, send_sms for SMS.
All outgoing messages are queued for user approval before sending — never send without queuing.
Resolve contact names to numbers using your knowledge of the conversation or ask the user.""",
                toolNames          = listOf(
                    "context_search", "get_pending_attention", "get_message_threads",
                    "read_sms", "get_recent_calls", "get_notifications",
                    "call_contact", "send_sms", "draft_email", "send_whatsapp",
                    "remember", "recall", "get_device_info"
                ),
                triggerWords       = listOf(
                    "text", "sms", "message", "email", "mail", "call", "phone",
                    "whatsapp", "telegram", "contact", "notification", "inbox",
                    "reply", "respond", "send", "write to", "follow up"
                )
            ),
            SkillDef(
                name               = "calendar_tasks",
                description        = "Calendar events, scheduling, tasks, reminders",
                systemPromptSuffix = """
## Active skill: Calendar & Tasks
Help the user manage their schedule and tasks.
For create_event: parse natural language times (e.g. "tomorrow 3pm", "next Monday") into ISO 8601.
Today's date and time is available via get_device_info.
Always confirm details before creating events. Add tasks proactively when the user mentions something to do.""",
                toolNames          = listOf(
                    "read_calendar", "create_event", "set_alarm",
                    "list_tasks", "add_task", "update_task", "complete_task",
                    "remember", "recall", "get_device_info"
                ),
                triggerWords       = listOf(
                    "calendar", "schedule", "event", "meeting", "appointment",
                    "reminder", "alarm", "task", "todo", "deadline", "due",
                    "book", "plan", "next week", "tomorrow", "today at"
                )
            ),
            SkillDef(
                name               = "device_control",
                description        = "App launching, volume, brightness, WiFi, Bluetooth, DND",
                systemPromptSuffix = """
## Active skill: Device Control
Help the user control their device settings and apps.
For brightness/WiFi/Bluetooth — apply the change and confirm. Report if a permission grant is needed.""",
                toolNames          = listOf(
                    "launch_app", "set_alarm", "set_timer", "get_battery", "get_volume",
                    "set_volume", "set_brightness", "toggle_wifi", "toggle_bluetooth",
                    "toggle_dnd", "toggle_flashlight", "set_ringer_mode",
                    "get_device_info", "get_network_info", "get_storage_info",
                    "get_clipboard", "set_clipboard", "media_control",
                    "open_settings_screen", "list_installed_apps"
                ),
                triggerWords       = listOf(
                    "open", "launch", "start app", "volume", "brightness", "screen",
                    "wifi", "bluetooth", "airplane", "dnd", "do not disturb", "silent",
                    "battery", "clipboard", "copy", "paste", "timer", "flashlight",
                    "torch", "ringer", "ring", "vibrate", "storage", "network", "ip",
                    "music", "play", "pause", "skip", "next song", "settings"
                )
            ),
            SkillDef(
                name               = "web_research",
                description        = "Web search, information lookup, facts, page reading",
                systemPromptSuffix = """
## Active skill: Web Research
Search the web to answer the user's question. Use web_search first for quick answers.
Use fetch_webpage to read a specific URL in full (article, documentation, etc.).
Use open_url to open a page in the browser if the user wants to see it directly.
Summarise results concisely — bullet points where appropriate.""",
                toolNames          = listOf("web_search", "fetch_webpage", "open_url", "remember", "recall"),
                triggerWords       = listOf(
                    "search", "look up", "find out", "what is", "who is", "how to",
                    "weather", "news", "price", "stock", "score", "wiki", "google"
                )
            ),
            SkillDef(
                name               = "memory",
                description        = "Storing and recalling notes, reminders",
                systemPromptSuffix = """
## Active skill: Memory
Help the user store and retrieve information across conversations.""",
                toolNames          = listOf("remember", "recall", "context_search"),
                triggerWords       = listOf(
                    "remember", "remind me", "note", "save this", "don't forget",
                    "recall", "what did i", "forgot", "memorize"
                )
            ),
            SkillDef(
                name               = "screen_automation",
                description        = "Screen reading, app interaction, gesture control, UI automation",
                systemPromptSuffix = """
## Active skill: Screen Automation
Automate on-screen interactions. Typical workflow:
1. get_screen_text (fast, no vision) — reads text via accessibility tree
2. take_screenshot (slow, with coordinates) — if you need to tap specific elements
3. tap_screen / swipe_screen / long_press_screen — interact with elements
4. type_text — fill in text fields (tap the field first)
5. press_back / press_home — navigation

Always wait ~800ms after each tap before reading the screen again.
If the accessibility service is not enabled, tell the user to go to Settings › Accessibility › VoiceOS.""",
                toolNames          = listOf(
                    "launch_app", "get_screen_text", "take_screenshot",
                    "tap_screen", "swipe_screen", "long_press_screen",
                    "type_text", "press_back", "press_home",
                    "pull_notification_shade", "open_quick_settings",
                    "get_foreground_app", "open_url", "open_settings_screen",
                    "get_device_info", "get_notifications"
                ),
                triggerWords       = listOf(
                    "tap", "click", "press", "swipe", "scroll", "type into",
                    "fill in", "read screen", "what's on screen", "open the",
                    "in the app", "automate", "interact with", "navigate to",
                    "find on screen", "read the page", "read the email",
                    "what does it say", "close the", "go back"
                )
            ),
            SkillDef(
                name               = "daily_planning",
                description        = "Morning briefings, daily planning, prioritization, productivity",
                systemPromptSuffix = """
## Active skill: Daily Planning
Help the user plan and manage their day effectively.
Start with morning_briefing to get a full picture, then use:
- prioritize_tasks to rank what to work on
- read_calendar / create_event for scheduling
- suggest_social_outreach for relationship maintenance
- add_task / update_task to capture and track action items
Proactively suggest what to focus on based on priority, deadlines, and relationship health.""",
                toolNames          = listOf(
                    "morning_briefing", "prioritize_tasks",
                    "list_tasks", "add_task", "update_task", "complete_task",
                    "read_calendar", "create_event", "set_alarm", "set_timer",
                    "get_pending_attention", "context_search",
                    "suggest_social_outreach", "get_relationship_health",
                    "remember", "recall", "get_device_info"
                ),
                triggerWords       = listOf(
                    "morning", "good morning", "daily briefing", "today's plan",
                    "what should i", "prioritize", "focus on", "plan my day",
                    "what's important", "brief me", "catch me up",
                    "what do i have today", "agenda", "plan for the day",
                    "productivity", "goals for today", "what next"
                )
            ),
            SkillDef(
                name               = "termux_dev",
                description        = "Termux shell, git, coding, file management, package installation",
                systemPromptSuffix = """
## Active skill: Termux Development
You have access to a Termux terminal on the device via the file bridge.
ALL run_shell calls execute immediately and return their output — you MUST inspect the result and take follow-up actions.

Multi-step workflow rules:
1. Always verify success after each command by checking the output. '[exit N]' means failure.
2. For git clone failures: check the error, then use git_setup_credential if auth failed, then retry.
3. For auth errors on HTTPS: use git_setup_credential(host, username, token) then git_clone again.
4. For SSH auth errors: run_shell("cat ~/.ssh/id_ed25519.pub") to get the public key and tell the user to add it to their git host.
5. After cloning, verify with run_shell("ls <dir>") or run_shell("git status", "<dir>").
6. For package install failures: check error, then run_shell("pkg update") and retry.
7. Do NOT assume paths exist — always verify with ls before cd or git operations.

When the user asks to set up credentials or keys: ask for the host, username, and token/key up front before running commands.""",
                toolNames          = listOf(
                    "run_shell", "git_clone", "git_setup_credential", "get_bridge_setup",
                    "get_discovery_status", "context_search",
                    "add_task", "list_tasks", "remember", "recall",
                    "get_device_info", "get_storage_info"
                ),
                triggerWords       = listOf(
                    "git", "clone", "repo", "repository", "commit", "push", "pull",
                    "termux", "shell", "terminal", "bash", "script", "run",
                    "install", "npm", "pip", "pip3", "pkg install", "apt",
                    "code", "build", "compile", "make", "python", "node", "java",
                    "file", "directory", "mkdir", "ls ", "cat ", "ssh", "credential",
                    "token", "github", "gitlab", "bitbucket"
                )
            ),
            SkillDef(
                name               = "research",
                description        = "Web search, page reading, fact-finding, deep research",
                systemPromptSuffix = """
## Active skill: Research
Research and information gathering. Workflow:
1. Use web_search to find relevant URLs and get quick answers
2. Use fetch_webpage to read the full content of a specific page
3. Use context_search to check what you already know from device data
4. Use remember to save key findings for later
Combine sources for comprehensive answers. Cite where information came from.""",
                toolNames          = listOf(
                    "web_search", "fetch_webpage", "open_url",
                    "context_search", "remember", "recall", "get_device_info"
                ),
                triggerWords       = listOf(
                    "research", "deep dive", "find out more", "read this", "look at this page",
                    "fetch", "what does the article say", "summarize this",
                    "from the website", "check the page", "look up", "find out",
                    "what is", "who is", "how does", "why does", "explain",
                    "weather", "news", "search for", "google"
                )
            ),
            SkillDef(
                name               = "social_crm",
                description        = "Relationship management, contact profiles, follow-up tracking, social outreach",
                systemPromptSuffix = """
## Active skill: Social CRM

You are a personal relationship manager. When reviewing people or follow-ups, ALWAYS take concrete action — not just analysis.

### When the user says "review people", "check follow-ups", "who do I need to follow up with", or "people panel":
1. Call get_followup_contacts FIRST. Read every note carefully.
2. For each flagged contact, propose a SPECIFIC action based on their notes (e.g. "Text Josh about the payment plan", "Call Sarah to reschedule").
3. Ask the user if they want to create tasks and/or send messages — then do it with add_task and send_sms/call_contact.
4. After acting on a follow-up, offer to clear the flag with flag_followup(name, false).

### Other CRM actions:
- get_contact_profile: pull full relationship context before any interaction.
- suggest_social_outreach / get_relationship_health: identify who to reconnect with.
- add_relationship_note: capture context after interactions.
- log_interaction: when user tells you about a recent call or meeting.
- flag_followup(name, true): flag someone for follow-up when user says "remind me to follow up with X" or "add X to follow-ups".

### Key rule: notes in a contact profile are ACTION ITEMS, not passive history. When you see a note like "follow up about payment plan", treat it as a task to create.""",
                toolNames          = listOf(
                    "context_search", "get_pending_attention",
                    "get_contact_profile", "add_relationship_note", "log_interaction",
                    "get_relationship_health", "suggest_social_outreach",
                    "get_followup_contacts", "flag_followup",
                    "draft_outreach_message", "suggest_conversation_topics",
                    "set_contact_frequency", "create_social_goal", "get_social_goals",
                    "get_birthday_reminders", "schedule_social",
                    "get_interaction_history", "log_sentiment",
                    "bulk_relationship_review",
                    "read_sms", "get_recent_calls", "call_contact", "send_sms",
                    "add_task", "create_event", "remember", "recall", "get_device_info"
                ),
                triggerWords       = listOf(
                    "relationship", "catch up", "catch-up", "reach out", "haven't talked",
                    "haven't spoken", "who should i call", "check in", "social", "network",
                    "friend", "family", "colleague", "follow up", "how is", "note about",
                    "remember about", "last time i talked", "relationship health", "drift",
                    "outreach", "reconnect", "keep in touch", "touch base",
                    "people panel", "review people", "who do i need to", "my contacts",
                    "flag", "remind me to", "birthday", "draft", "message to",
                    "frequency", "goal", "how are they doing", "conversation",
                    "sentiment", "schedule", "coffee with", "lunch with", "call with"
                )
            )
        )
        return reg
    }

    // ════════════════════════════════════════════════════════════════
    // HTTP router
    // ════════════════════════════════════════════════════════════════
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/" || uri == "/index.html" -> serveAsset("index.html", "text/html")
            uri.startsWith("/static/") -> {
                val file = uri.removePrefix("/static/")
                val mime = when {
                    file.endsWith(".css") -> "text/css"
                    file.endsWith(".js")  -> "application/javascript"
                    else                  -> "application/octet-stream"
                }
                serveAsset(file, mime)
            }

            uri == "/api/status" -> jsonResponse(mapOf(
                "provider"        to activeProvider,
                "model"           to activeModel,
                "model_fast"      to activeFastModel,
                "active_provider" to activeProvider,
                "active_model"    to activeModel,
                "has_key"         to apiKey(activeProvider).isNotEmpty(),
                "ollama_url"      to ollamaUrl
            ))

            uri == "/api/apps"     -> handleApps()
            uri == "/api/launch"   && session.method == Method.POST -> handleLaunch(session)
            uri == "/api/provider" && session.method == Method.POST -> handleProvider(session)
            uri == "/api/keys"     && session.method == Method.POST -> handleSaveKey(session)
            uri == "/api/keys"     && session.method == Method.GET  -> {
                val p = session.parameters["provider"]?.firstOrNull() ?: activeProvider
                jsonResponse(mapOf("provider" to p, "has_key" to apiKey(p).isNotEmpty()))
            }
            uri == "/api/keys/test" && session.method == Method.GET -> handleTestKey(session)
            uri == "/api/clear"     && session.method == Method.POST -> handleClear()
            uri == "/api/chat"      && session.method == Method.POST -> handleChat(session)
            uri == "/api/agent"     && session.method == Method.POST -> handleAgent(session)
            uri == "/api/heartbeat" -> handleHeartbeat()
            uri == "/api/heartbeat/probes" -> jsonResponse(heartbeat.list())
            uri == "/api/heartbeat/probes"   && session.method == Method.POST -> handleSetProbe(session)
            uri == "/api/heartbeat/control"  && session.method == Method.POST -> handleHeartbeatControl(session)

            uri == "/api/contacts"  && session.method == Method.GET  -> handleContacts(session)
            uri == "/api/call"      && session.method == Method.POST -> handleCall(session)
            uri == "/api/sms"       && session.method == Method.POST -> handleSms(session)
            uri == "/api/maps"      && session.method == Method.POST -> handleMaps(session)

            uri == "/api/tasks"     && session.method == Method.GET    -> handleGetTasks()
            uri == "/api/tasks"     && session.method == Method.POST   -> handleCreateTask(session)
            uri == "/api/tasks"     && session.method == Method.PUT    -> handleUpdateTask(session)
            uri == "/api/tasks"     && session.method == Method.DELETE -> handleDeleteTask(session)

            uri == "/api/queue"     && session.method == Method.GET    -> handleGetQueue()
            uri == "/api/queue/approve" && session.method == Method.POST -> handleApproveAction(session)
            uri == "/api/queue/dismiss" && session.method == Method.POST -> handleDismissAction(session)

            uri == "/api/skills"   -> jsonResponse(skillRegistry.list())

            uri == "/api/notifications" && session.method == Method.GET    -> handleGetNotifications()
            uri == "/api/notifications" && session.method == Method.DELETE -> handleDismissNotification(session)
            uri == "/api/notifications/settings" && session.method == Method.POST -> handleOpenNotifSettings()

            uri == "/api/ollama/test" -> handleOllamaTest()

            uri == "/api/profile"    && session.method == Method.GET  -> handleGetProfile()
            uri == "/api/profile"    && session.method == Method.POST -> handleSaveProfile(session)
            uri == "/api/onboard"    && session.method == Method.POST -> handleOnboard(session)
            uri == "/api/bridge/setup" -> handleBridgeSetup()
            uri == "/api/bridge/status" -> jsonResponse(mapOf(
                "ready"    to isBridgeReady(),
                "dir"      to (try { bridgeDir.absolutePath } catch (_: Exception) { "" }),
                "has_daemon" to java.io.File(bridgeDir, "done").let { false }  // daemon sets done, we just check dir
            ))

            uri == "/api/crm/contacts"   && session.method == Method.GET    -> handleCrmContacts(session)
            uri == "/api/crm/followups"  && session.method == Method.GET    -> handleCrmFollowups()
            uri == "/api/crm/note"       && session.method == Method.POST   -> handleCrmAddNote(session)
            // Accept both POST and PUT for updates (NanoHTTPD parseBody is reliable on POST)
            uri == "/api/crm/contact/update" && session.method == Method.POST -> handleCrmUpdateContact(session)
            uri == "/api/crm/contact"   && session.method == Method.PUT    -> handleCrmUpdateContact(session)
            uri == "/api/crm/contact/delete" && session.method == Method.POST -> handleCrmDeleteContact(session)
            uri == "/api/crm/contact"   && session.method == Method.DELETE -> handleCrmDeleteContact(session)
            uri == "/api/crm/import"    && session.method == Method.POST   -> handleCrmImport()
            uri == "/api/crm/insights"  && session.method == Method.GET    -> handleCrmInsights()
            uri == "/api/crm/profile"   && session.method == Method.GET    -> handleCrmProfile(session)

            // Screen automation diagnostics
            uri == "/api/screen/status" -> jsonResponse(mapOf(
                "accessibility_service" to VoiceOSAccessibilityService.isAvailable(),
                "api_level"             to android.os.Build.VERSION.SDK_INT,
                "screenshot_supported"  to (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R),
                "screen_size"           to VoiceOSAccessibilityService.getScreenSize().let {
                    mapOf("width" to it.first, "height" to it.second)
                }
            ))
            uri == "/api/screen/text" && session.method == Method.GET -> {
                val text = VoiceOSAccessibilityService.getScreenText()
                jsonResponse(mapOf(
                    "ok"      to VoiceOSAccessibilityService.isAvailable(),
                    "content" to text.ifBlank { "(empty — accessibility service may not be enabled)" }
                ))
            }
            uri == "/api/screen/screenshot" && session.method == Method.GET -> {
                if (!VoiceOSAccessibilityService.isAvailable())
                    return jsonResponse(mapOf("ok" to false, "error" to "Accessibility service not enabled"))
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R)
                    return jsonResponse(mapOf("ok" to false, "error" to "Requires Android 11+"))
                val b64 = VoiceOSAccessibilityService.takeScreenshot()
                if (b64 == null)
                    jsonResponse(mapOf("ok" to false, "error" to "Screenshot capture returned null"))
                else
                    jsonResponse(mapOf("ok" to true, "bytes" to b64.length, "preview_url" to "data:image/jpeg;base64,${b64.take(100)}…"))
            }

            // Discovery
            uri == "/api/discovery/status" -> jsonResponse(mapOf(
                "total_docs" to contextStore.totalDocCount(),
                "last_scan"  to contextStore.lastScanMs(),
                "by_type"    to contextStore.getDiscoveryStatus()
            ))
            uri == "/api/discovery/scan" && session.method == Method.POST -> {
                discoveryEngine.scanIfStale(force = true) { count ->
                    Log.i("VoiceOS", "Discovery scan triggered via API: $count docs")
                }
                jsonResponse(mapOf("ok" to true, "message" to "Discovery scan started in background"))
            }

            else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not found: $uri")
        }
    }

    // ── Asset serving ─────────────────────────────────────────────
    private fun serveAsset(name: String, mimeType: String): Response {
        return try {
            val stream = context.assets.open("web/$name")
            newChunkedResponse(Status.OK, mimeType, stream).also {
                it.addHeader("Cache-Control", "no-cache")
            }
        } catch (_: IOException) {
            newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Asset not found: $name")
        }
    }

    // ── /api/apps ─────────────────────────────────────────────────
    private fun handleApps(): Response {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .map { info -> mapOf(
                "package" to info.activityInfo.packageName,
                "name"    to info.loadLabel(pm).toString(),
                "icon"    to encodeIcon(pm, info)
            )}
        return jsonResponse(apps)
    }

    private fun encodeIcon(pm: PackageManager, info: android.content.pm.ResolveInfo): String? {
        return try {
            val drawable = info.loadIcon(pm)
            val w = if (drawable.intrinsicWidth  > 0) drawable.intrinsicWidth  else 48
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 48
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, w, h); drawable.draw(Canvas(bmp))
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 85, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }

    // ── /api/launch ───────────────────────────────────────────────
    private fun handleLaunch(session: IHTTPSession): Response {
        val pkg = parseBody(session)["package"] as? String
            ?: return jsonResponse(mapOf("error" to "missing package"), Status.BAD_REQUEST)
        return try {
            val i = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return jsonResponse(mapOf("error" to "not found"), Status.NOT_FOUND)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(i)
            jsonResponse(mapOf("ok" to true))
        } catch (e: Exception) {
            jsonResponse(mapOf("error" to (e.message ?: "failed")), Status.INTERNAL_ERROR)
        }
    }

    // ── /api/provider ─────────────────────────────────────────────
    private fun handleProvider(session: IHTTPSession): Response {
        val map = parseBody(session)
        activeProvider = map["provider"]   as? String ?: activeProvider
        activeModel    = map["model"]      as? String ?: activeModel
        (map["model_fast"]  as? String)?.takeIf { it.isNotBlank() }?.let { activeFastModel = it }
        (map["ollama_url"]  as? String)?.takeIf { it.isNotBlank() }?.let { ollamaUrl = it.trim() }
        return jsonResponse(mapOf(
            "provider" to activeProvider, "model" to activeModel,
            "model_fast" to activeFastModel,
            "active_provider" to activeProvider, "active_model" to activeModel,
            "has_key" to apiKey(activeProvider).isNotEmpty(), "ollama_url" to ollamaUrl
        ))
    }

    // ── /api/keys ─────────────────────────────────────────────────
    private fun handleSaveKey(session: IHTTPSession): Response {
        val map      = parseBody(session)
        val provider = map["provider"] as? String ?: return jsonResponse(mapOf("error" to "missing provider"), Status.BAD_REQUEST)
        val key      = map["key"]      as? String ?: return jsonResponse(mapOf("error" to "missing key"),     Status.BAD_REQUEST)
        saveKey(provider, key.trim())
        return jsonResponse(mapOf("ok" to true, "provider" to provider, "has_key" to key.isNotBlank()))
    }

    // ── /api/keys/test ────────────────────────────────────────────
    /** Makes a minimal API call to verify the key for a given provider works. */
    private fun handleTestKey(session: IHTTPSession): Response {
        val provider = session.parameters["provider"]?.firstOrNull() ?: activeProvider
        val key = apiKey(provider)
        if (key.isBlank()) return jsonResponse(mapOf("ok" to false, "error" to "No key saved for $provider"))

        return try {
            val (code, body) = when (provider) {
                "anthropic" -> {
                    val payload = gson.toJson(mapOf(
                        "model" to "claude-haiku-4-5-20251001", "max_tokens" to 8,
                        "messages" to listOf(mapOf("role" to "user", "content" to "hi"))
                    ))
                    val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("x-api-key", key)
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                    conn.doOutput = true; conn.connectTimeout = 10_000; conn.readTimeout = 15_000
                    conn.outputStream.use { it.write(payload.toByteArray()) }
                    val rc = conn.responseCode
                    val b = (if (rc == 200) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                    Pair(rc, b)
                }
                "openai", "grok" -> {
                    val url = if (provider == "grok") "https://api.x.ai/v1/chat/completions"
                              else "https://api.openai.com/v1/chat/completions"
                    val model = if (provider == "grok") "grok-4-1-fast-non-reasoning" else "gpt-4o-mini"
                    val payload = gson.toJson(mapOf(
                        "model" to model, "max_tokens" to 8,
                        "messages" to listOf(mapOf("role" to "user", "content" to "hi"))
                    ))
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer $key")
                    conn.doOutput = true; conn.connectTimeout = 10_000; conn.readTimeout = 15_000
                    conn.outputStream.use { it.write(payload.toByteArray()) }
                    val rc = conn.responseCode
                    val b = (if (rc == 200) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                    Pair(rc, b)
                }
                else -> Pair(400, "Unsupported provider: $provider")
            }
            jsonResponse(mapOf(
                "ok"       to (code == 200),
                "provider" to provider,
                "status"   to code,
                "response" to body.take(300)
            ))
        } catch (e: Exception) {
            jsonResponse(mapOf("ok" to false, "provider" to provider, "error" to (e.message ?: "network error")))
        }
    }

    // ── /api/clear ────────────────────────────────────────────────
    private fun handleClear(): Response {
        engine.context.clear()
        return jsonResponse(mapOf("ok" to true))
    }

    // ── /api/chat (legacy streaming non-agent) ────────────────────
    private fun handleChat(session: IHTTPSession): Response {
        val map     = parseBody(session)
        val userMsg = map["text"] as? String ?: ""

        if (activeProvider == "ollama") return streamOllamaChat(userMsg)
        val reply = when (activeProvider) {
            "anthropic" -> { val k = apiKey("anthropic"); if (k.isBlank()) "No Anthropic API key." else anthropicChat(userMsg, k) }
            "openai"    -> { val k = apiKey("openai");    if (k.isBlank()) "No OpenAI API key."    else openaiChat(userMsg, k, "https://api.openai.com/v1/chat/completions") }
            "groq"      -> { val k = apiKey("groq");      if (k.isBlank()) "No Groq API key."      else openaiChat(userMsg, k, "https://api.groq.com/openai/v1/chat/completions") }
            else        -> "Unknown provider: $activeProvider"
        }
        return jsonResponse(mapOf("text" to reply))
    }

    // ════════════════════════════════════════════════════════════════
    // /api/agent  —  Skill-routed ReAct loop
    // ════════════════════════════════════════════════════════════════
    private fun handleAgent(session: IHTTPSession): Response {
        val map     = parseBody(session)
        val userMsg = map["text"] as? String ?: ""

        // ── Classify query — route LOCAL queries without any LLM call ──
        val tier = classifyQuery(userMsg)
        if (tier == ModelTier.LOCAL) {
            val localResult = handleLocalQuery(userMsg)
            if (localResult != null) {
                val resp = newFixedLengthResponse(Status.OK, "text/plain; charset=utf-8", localResult)
                resp.addHeader("Access-Control-Allow-Origin", "*")
                return resp
            }
            // If local handler couldn't answer, fall through to FAST tier
        }

        val pipeOut = PipedOutputStream()
        val pipeIn  = PipedInputStream(pipeOut, 65536)

        Thread {
            try {
                // Set tier for this thread so callLLM lambda picks it up
                requestTier.set(if (tier == ModelTier.LOCAL) ModelTier.FAST else tier)
                pipeOut.writer().use { writer ->
                    // Assistant mode: minimal context — model asks for info via tools when needed.
                    // Agent mode: full context so probes and autonomous checks have device state.
                    val isAgentMode = (map["mode"] as? String) == "agent"
                    val minimal = !isAgentMode || activeProvider == "ollama"
                    val liveCtx = try { buildLiveContext(minimal = minimal) } catch (_: Exception) { "" }
                    engine.run(
                        userMsg     = userMsg,
                        liveCtx     = liveCtx,
                        writer      = writer,
                        gson        = gson,
                        isAnthropic = activeProvider == "anthropic"
                    )
                }
            } catch (e: Exception) {
                try { pipeOut.write("Error: ${e.message}".toByteArray()) } catch (_: Exception) {}
            } finally {
                requestTier.remove()
                try { pipeOut.close() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()

        return newChunkedResponse(Status.OK, "text/plain; charset=utf-8", pipeIn).also {
            it.addHeader("Access-Control-Allow-Origin", "*")
            it.addHeader("Cache-Control", "no-cache")
        }
    }

    // ════════════════════════════════════════════════════════════════
    // /api/heartbeat  —  Returns next pending probe result or ""
    // ════════════════════════════════════════════════════════════════
    private fun handleHeartbeat(): Response {
        // Start heartbeat lazily on first call (agent mode enabled in frontend)
        if (!heartbeat.hasPending()) {
            // Initialise if not started
        }
        val result = heartbeat.popPending() ?: ""
        val resp = newFixedLengthResponse(Status.OK, "text/plain; charset=utf-8", result)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun handleSetProbe(session: IHTTPSession): Response {
        val map = parseBody(session)
        val name    = map["name"]    as? String  ?: return jsonResponse(mapOf("error" to "missing name"), Status.BAD_REQUEST)
        val enabled = map["enabled"] as? Boolean ?: true
        heartbeat.setEnabled(name, enabled)
        return jsonResponse(mapOf("ok" to true))
    }

    fun startHeartbeat() { heartbeat.start() }
    fun stopHeartbeat()  { heartbeat.stop() }

    // ── Ollama model keep-alive ────────────────────────────────────────────
    // Pings Ollama every 4 minutes so the model never hits the 5-minute
    // default keepalive expiry and gets unloaded between user requests.
    @Volatile private var ollamaPingRunning = false
    private var ollamaPingThread: Thread? = null

    /** Start background warmup + periodic keep-alive pings for Ollama. */
    fun warmupOllama() {
        if (activeProvider != "ollama") return
        ollamaPingRunning = true
        ollamaPingThread = Thread {
            // Initial delay — let the server finish binding before first load
            Thread.sleep(2_000)
            while (ollamaPingRunning) {
                ollamaPing()
                // Ping every 4 minutes — shorter than Ollama's 5-minute default keepalive
                var waited = 0
                while (ollamaPingRunning && waited < 240_000) {
                    Thread.sleep(5_000)
                    waited += 5_000
                }
            }
        }.apply { isDaemon = true; name = "ollama-keepalive" }
        ollamaPingThread!!.start()
    }

    fun stopWarmup() {
        ollamaPingRunning = false
        ollamaPingThread?.interrupt()
        ollamaPingThread = null
    }

    private fun ollamaPing() {
        try {
            val conn = java.net.URL("${ollamaUrl.trimEnd('/')}/api/generate")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Connection", "close")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 600_000   // model load from cold can take several minutes
            conn.outputStream.use { os ->
                os.write(gson.toJson(mapOf(
                    "model"      to activeModel,
                    "prompt"     to "hi",
                    "stream"     to true,
                    "keep_alive" to "10m"
                )).toByteArray())
            }
            // Drain NDJSON stream lines and discard — we only need the model to load
            conn.inputStream.bufferedReader().use { r -> r.forEachLine { } }
        } catch (_: Exception) {}
    }

    private fun handleHeartbeatControl(session: IHTTPSession): Response {
        val map = parseBody(session)
        when (map["action"] as? String) {
            "start" -> {
                heartbeat.start()
                // Kick off background discovery when agent mode activates
                discoveryEngine.scanIfStale()
            }
            "stop"  -> heartbeat.stop()
        }
        return jsonResponse(mapOf("ok" to true, "probes" to heartbeat.list()))
    }

    // ════════════════════════════════════════════════════════════════
    // /api/queue  —  Action queue (email/SMS/WhatsApp drafts)
    // ════════════════════════════════════════════════════════════════
    private fun handleGetQueue(): Response =
        jsonResponse(actionQueue.getPending().map { actionToMap(it) })

    private fun handleApproveAction(session: IHTTPSession): Response {
        val id = parseBody(session)["id"] as? String
            ?: return jsonResponse(mapOf("error" to "missing id"), Status.BAD_REQUEST)
        val action = actionQueue.approve(id)
            ?: return jsonResponse(mapOf("error" to "not found"), Status.NOT_FOUND)
        executeQueuedAction(action)
        return jsonResponse(mapOf("ok" to true, "type" to action.type))
    }

    private fun handleDismissAction(session: IHTTPSession): Response {
        val id = parseBody(session)["id"] as? String
            ?: return jsonResponse(mapOf("error" to "missing id"), Status.BAD_REQUEST)
        actionQueue.dismiss(id)
        return jsonResponse(mapOf("ok" to true))
    }

    private fun executeQueuedAction(action: QueuedAction) {
        try {
            when (action.type) {
                "run_shell" -> {
                    val cmd     = action.params["command"] ?: return
                    val workdir = action.params["workdir"] ?: "~"
                    Thread {
                        val out     = executeBridgeCommand(cmd, workdir)
                        val preview = cmd.take(60)
                        heartbeat.pushResult("Shell ✓ `$preview`\n${out.take(300)}")
                    }.apply { isDaemon = true; start() }
                }
                "draft_email" -> {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_EMAIL,   arrayOf(action.params["to"] ?: ""))
                        putExtra(Intent.EXTRA_SUBJECT, action.params["subject"] ?: "")
                        putExtra(Intent.EXTRA_TEXT,    action.params["body"] ?: "")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(intent, "Send Email").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
                "send_sms" -> {
                    val number = resolveContact(action.params["to"] ?: "") ?: action.params["to"] ?: return
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("sms:${Uri.encode(number)}")).apply {
                        putExtra("sms_body", action.params["body"] ?: "")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                "send_whatsapp" -> {
                    val number = resolveContact(action.params["to"] ?: "") ?: action.params["to"] ?: return
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://api.whatsapp.com/send?phone=${Uri.encode(number)}&text=${Uri.encode(action.params["body"] ?: "")}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceOS", "executeQueuedAction failed: ${e.message}")
        }
    }

    private fun actionToMap(a: QueuedAction) = mapOf(
        "id"        to a.id,
        "type"      to a.type,
        "params"    to a.params,
        "status"    to a.status,
        "preview"   to a.preview,
        "createdMs" to a.createdMs
    )

    // ════════════════════════════════════════════════════════════════
    // Tool execution dispatch
    // ════════════════════════════════════════════════════════════════
    private fun executeTool(name: String, args: Map<String, Any>): String {
        return try {
            when (name) {
                // Information
                "web_search"        -> toolWebSearch(args)
                "read_calendar"     -> toolReadCalendar(args)
                "get_battery"       -> toolGetBattery()
                "get_volume"        -> toolGetVolume()
                "get_device_info"   -> toolGetDeviceInfo()
                "get_notifications" -> toolGetNotifications(args)
                "read_sms"          -> toolReadSms(args)
                "get_recent_calls"  -> toolGetRecentCalls(args)
                "recall"            -> toolRecall()
                "get_clipboard"     -> toolGetClipboard()
                // Device control
                "launch_app"        -> toolLaunchApp(args)
                "set_alarm"         -> toolSetAlarm(args)
                "set_volume"        -> toolSetVolume(args)
                "set_brightness"    -> toolSetBrightness(args)
                "toggle_wifi"       -> toolToggleWifi(args)
                "toggle_bluetooth"  -> toolToggleBluetooth(args)
                "toggle_dnd"        -> toolToggleDnd(args)
                "set_clipboard"     -> toolSetClipboard(args)
                // Calendar & tasks
                "create_event"      -> toolCreateEvent(args)
                "list_tasks"             -> toolListTasks()
                "add_task"               -> toolAddTask(args)
                "update_task"            -> toolUpdateTask(args)
                "complete_task"          -> toolCompleteTask(args)
                "delete_task"            -> toolDeleteTask(args)
                "clear_completed_tasks"  -> toolClearCompletedTasks()
                // Memory & profile
                "remember"              -> toolRemember(args)
                "get_user_profile"      -> toolGetUserProfile()
                "update_user_profile"   -> toolUpdateUserProfile(args)
                // Termux bridge
                "run_shell"               -> toolRunShell(args)
                "git_setup_credential"    -> toolGitSetupCredential(args)
                "git_clone"               -> toolGitClone(args)
                "get_bridge_setup"        -> toolGetBridgeSetup()
                // Social CRM
                "get_contact_profile"     -> toolGetContactProfile(args)
                "add_relationship_note"   -> toolAddRelationshipNote(args)
                "log_interaction"         -> toolLogInteraction(args)
                "get_relationship_health" -> toolGetRelationshipHealth(args)
                "suggest_social_outreach" -> toolSuggestSocialOutreach(args)
                "get_followup_contacts"        -> toolGetFollowupContacts()
                "flag_followup"               -> toolFlagFollowup(args)
                "draft_outreach_message"      -> toolDraftOutreachMessage(args)
                "set_contact_frequency"       -> toolSetContactFrequency(args)
                "get_birthday_reminders"      -> toolGetBirthdayReminders(args)
                "schedule_social"             -> toolScheduleSocial(args)
                "get_interaction_history"     -> toolGetInteractionHistory(args)
                "log_sentiment"               -> toolLogSentiment(args)
                "suggest_conversation_topics" -> toolSuggestConversationTopics(args)
                "bulk_relationship_review"    -> toolBulkRelationshipReview(args)
                "create_social_goal"          -> toolCreateSocialGoal(args)
                "get_social_goals"            -> toolGetSocialGoals()
                // Communication (queued)
                "call_contact"      -> toolCallContact(args)
                "send_sms"          -> toolSendSms(args)
                "draft_email"       -> toolDraftEmail(args)
                "send_whatsapp"     -> toolSendWhatsapp(args)
                "navigate"          -> toolNavigate(args)
                // Screen interaction
                "take_screenshot"  -> toolTakeScreenshot(args)
                "get_screen_text"  -> toolGetScreenText()
                "tap_screen"       -> toolTapScreen(args)
                "swipe_screen"     -> toolSwipeScreen(args)
                "press_back"       -> { VoiceOSAccessibilityService.pressBack(); Thread.sleep(400); "Back pressed" }
                "press_home"       -> { VoiceOSAccessibilityService.pressHome(); Thread.sleep(400); "Home pressed" }
                // Context discovery
                "context_search"        -> toolContextSearch(args)
                "get_pending_attention" -> toolGetPendingAttention(args)
                "get_message_threads"   -> toolGetMessageThreads(args)
                "discover_now"          -> toolDiscoverNow()
                "get_discovery_status"  -> toolGetDiscoveryStatus()
                // Quick actions
                "set_timer"             -> toolSetTimer(args)
                "media_control"         -> toolMediaControl(args)
                "open_url"              -> toolOpenUrl(args)
                "search_contacts"       -> toolSearchContacts(args)
                "get_network_info"      -> toolGetNetworkInfo()
                "get_storage_info"      -> toolGetStorageInfo()
                "set_ringer_mode"       -> toolSetRingerMode(args)
                "toggle_flashlight"     -> toolToggleFlashlight(args)
                "open_settings_screen"  -> toolOpenSettingsScreen(args)
                "list_installed_apps"   -> toolListInstalledApps(args)
                "fetch_webpage"         -> toolFetchWebpage(args)
                "create_contact"        -> toolCreateContact(args)
                // Advanced screen automation
                "type_text"             -> toolTypeText(args)
                "long_press_screen"     -> toolLongPressScreen(args)
                "pull_notification_shade" -> { VoiceOSAccessibilityService.pullNotificationShade(); Thread.sleep(500); "Notification shade opened" }
                "open_quick_settings"   -> { VoiceOSAccessibilityService.openQuickSettings(); Thread.sleep(500); "Quick settings opened" }
                "get_foreground_app"    -> toolGetForegroundApp()
                // Intelligence & planning
                "morning_briefing"      -> toolMorningBriefing()
                "prioritize_tasks"      -> toolPrioritizeTasks()
                "summarize_sms_thread"  -> toolSummarizeSmsThread(args)
                else                -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            "Tool error ($name): ${e.message}"
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Tool implementations
    // ════════════════════════════════════════════════════════════════

    // ── Information ───────────────────────────────────────────────
    private fun toolWebSearch(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return "Missing query"
        return try {
            val url  = "https://api.duckduckgo.com/?q=${Uri.encode(query)}&format=json&no_redirect=1&no_html=1"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "VoiceOS/1.0")
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            @Suppress("UNCHECKED_CAST")
            val obj    = gson.fromJson(conn.inputStream.bufferedReader().readText(), Map::class.java) as Map<String, Any>
            val answer = obj["Answer"]   as? String ?: ""
            val abs    = obj["Abstract"] as? String ?: ""
            @Suppress("UNCHECKED_CAST")
            val topics = (obj["RelatedTopics"] as? List<Map<String, Any>>)
                ?.take(3)?.mapNotNull { it["Text"] as? String }?.joinToString(" | ") ?: ""
            listOf(answer, abs, topics).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "No results for: $query" }
        } catch (e: Exception) { "Search failed: ${e.message}" }
    }

    private fun toolReadCalendar(args: Map<String, Any>): String {
        val days = (args["days"] as? Double)?.toInt() ?: 7
        return try {
            val now = System.currentTimeMillis()
            val end = now + days * 86_400_000L
            val cur = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
                        CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_LOCATION),
                "${CalendarContract.Events.DTSTART} BETWEEN ? AND ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(now.toString(), end.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )
            val events = mutableListOf<String>()
            cur?.use {
                while (it.moveToNext() && events.size < 10) {
                    val title    = it.getString(0) ?: continue
                    val startMs  = it.getLong(1)
                    val location = it.getString(3) ?: ""
                    val cal = Calendar.getInstance().apply { timeInMillis = startMs }
                    val time = "${cal.get(Calendar.MONTH)+1}/${cal.get(Calendar.DAY_OF_MONTH)} " +
                               "${cal.get(Calendar.HOUR_OF_DAY)}:${"%02d".format(cal.get(Calendar.MINUTE))}"
                    events += "$time — $title${if (location.isNotBlank()) " @ $location" else ""}"
                }
            }
            if (events.isEmpty()) "No events in the next $days days"
            else "Upcoming events:\n${events.joinToString("\n")}"
        } catch (e: Exception) { "Calendar read failed: ${e.message}" }
    }

    private fun toolGetBattery(): String {
        val bm  = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return "Battery: $pct%${if (bm?.isCharging == true) " (charging)" else ""}"
    }

    private fun toolGetVolume(): String {
        val am  = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return "Volume: ${(cur * 100 / max.coerceAtLeast(1))}% ($cur/$max)"
    }

    private fun toolGetDeviceInfo(): String {
        val sdf  = java.text.SimpleDateFormat("EEE MMM d yyyy, HH:mm:ss z", java.util.Locale.US)
        val wifi = try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wm.isWifiEnabled) "WiFi: ${wm.connectionInfo.ssid?.replace("\"", "") ?: "connected"}" else "WiFi off"
        } catch (_: Exception) { "Network: unknown" }
        return "${sdf.format(java.util.Date())}\n${toolGetBattery()}\n$wifi"
    }

    private fun toolGetNotifications(args: Map<String, Any>): String {
        if (!isNotificationListenerEnabled())
            return "Notification access not granted. Enable VoiceOS in Settings → Apps → Special app access → Notification access."
        val limit  = (args["limit"] as? Double)?.toInt() ?: 10
        val notifs = VoiceOSNotificationService.getRecent().take(limit)
        if (notifs.isEmpty()) return "No recent notifications."
        return notifs.joinToString("\n") { n ->
            val app   = n["app"] as? String ?: n["pkg"] as? String ?: "?"
            val title = n["title"] as? String ?: ""
            val text  = n["text"]  as? String ?: ""
            val age   = System.currentTimeMillis() - (n["time"] as? Long ?: 0L)
            val ageStr = when {
                age < 60_000    -> "just now"
                age < 3_600_000 -> "${age / 60_000}m ago"
                else            -> "${age / 3_600_000}h ago"
            }
            "[$ageStr] $app${if (title.isNotBlank()) " \"$title\"" else ""}${if (text.isNotBlank()) ": $text" else ""}"
        }
    }

    private fun toolReadSms(args: Map<String, Any>): String {
        val limit   = (args["limit"]   as? Double)?.toInt() ?: 10
        val contact = args["contact"]  as? String
        return try {
            val sel   = if (!contact.isNullOrBlank()) "address LIKE ?" else null
            val sArgs = if (!contact.isNullOrBlank()) arrayOf("%$contact%") else null
            val cur   = context.contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date", "read"), sel, sArgs, "date DESC"
            )
            val results = mutableListOf<String>()
            cur?.use {
                while (it.moveToNext() && results.size < limit) {
                    val addr   = it.getString(0) ?: "?"
                    val body   = it.getString(1) ?: ""
                    val date   = it.getLong(2)
                    val unread = it.getInt(3) != 1
                    val age    = System.currentTimeMillis() - date
                    val ageStr = when {
                        age < 60_000     -> "just now"
                        age < 3_600_000  -> "${age / 60_000}m ago"
                        age < 86_400_000 -> "${age / 3_600_000}h ago"
                        else             -> "${age / 86_400_000}d ago"
                    }
                    results += "[$ageStr]${if (unread) "[UNREAD]" else ""} ${resolveContactName(addr)}: ${body.take(160)}"
                }
            }
            if (results.isEmpty()) "No messages${if (!contact.isNullOrBlank()) " from $contact" else ""}."
            else results.joinToString("\n")
        } catch (e: Exception) { "SMS unavailable: ${e.message}" }
    }

    private fun toolGetRecentCalls(args: Map<String, Any>): String {
        val limit = (args["limit"] as? Double)?.toInt() ?: 10
        return try {
            val cur = context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.CACHED_NAME,
                        android.provider.CallLog.Calls.NUMBER,
                        android.provider.CallLog.Calls.TYPE,
                        android.provider.CallLog.Calls.DATE,
                        android.provider.CallLog.Calls.DURATION),
                null, null, "${android.provider.CallLog.Calls.DATE} DESC"
            )
            val results = mutableListOf<String>()
            cur?.use {
                while (it.moveToNext() && results.size < limit) {
                    val name     = it.getString(0)?.takeIf { n -> n.isNotBlank() } ?: it.getString(1) ?: "?"
                    val type     = it.getInt(2)
                    val date     = it.getLong(3)
                    val duration = it.getLong(4)
                    val typeStr  = when (type) {
                        android.provider.CallLog.Calls.INCOMING_TYPE -> "incoming"
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        android.provider.CallLog.Calls.MISSED_TYPE   -> "missed"
                        else                                          -> "unknown"
                    }
                    val age = System.currentTimeMillis() - date
                    val ageStr = when {
                        age < 60_000     -> "just now"
                        age < 3_600_000  -> "${age / 60_000}m ago"
                        age < 86_400_000 -> "${age / 3_600_000}h ago"
                        else             -> "${age / 86_400_000}d ago"
                    }
                    results += "[$ageStr] $typeStr — $name${if (duration > 0) " (${duration}s)" else ""}"
                }
            }
            if (results.isEmpty()) "No recent calls." else results.joinToString("\n")
        } catch (e: Exception) { "Call log unavailable: ${e.message}" }
    }

    private fun toolRecall(): String {
        val notes = prefs.getString("agent_notes", "") ?: ""
        return if (notes.isBlank()) "No notes stored." else "Notes:\n$notes"
    }

    private fun toolGetClipboard(): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "Clipboard is empty"
        } catch (e: Exception) { "Clipboard unavailable: ${e.message}" }
    }

    // ── Device control ────────────────────────────────────────────
    private fun toolLaunchApp(args: Map<String, Any>): String {
        val name = args["app_name"] as? String ?: return "Missing app_name"
        val pm   = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }, PackageManager.GET_META_DATA)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
            ?: return "App '$name' not found"
        context.startActivity(pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: return "Cannot launch ${match.loadLabel(pm)}")
        return "Launched ${match.loadLabel(pm)}"
    }

    private fun toolSetAlarm(args: Map<String, Any>): String {
        val hour   = (args["hour"]   as? Double)?.toInt() ?: return "Missing hour"
        val minute = (args["minute"] as? Double)?.toInt() ?: return "Missing minute"
        val msg    = args["message"] as? String ?: "Alarm"
        context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, msg)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return "Alarm set for %02d:%02d".format(hour, minute)
    }

    private fun toolSetVolume(args: Map<String, Any>): String {
        val pct = (args["level"] as? Double)?.toInt() ?: return "Missing level"
        val am  = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (pct * max / 100).coerceIn(0, max), 0)
        return "Volume set to $pct%"
    }

    private fun toolSetBrightness(args: Map<String, Any>): String {
        val level = (args["level"] as? Double)?.toInt() ?: return "Missing level"
        return try {
            if (!Settings.System.canWrite(context)) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return "Permission required — opening settings to grant WRITE_SETTINGS."
            }
            val raw = (level * 255 / 100).coerceIn(0, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
            "Brightness set to $level%"
        } catch (e: Exception) { "Brightness change failed: ${e.message}" }
    }

    private fun toolToggleWifi(args: Map<String, Any>): String {
        val enable = args["enable"] as? Boolean ?: return "Missing enable"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ cannot toggle WiFi programmatically — open settings panel
            context.startActivity(Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "Opening WiFi settings panel (Android 10+ restriction)"
        } else {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wm.isWifiEnabled = enable
            "WiFi ${if (enable) "enabled" else "disabled"}"
        }
    }

    private fun toolToggleBluetooth(args: Map<String, Any>): String {
        val enable = args["enable"] as? Boolean ?: return "Missing enable"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "Opening Bluetooth settings (Android 12+ restriction)"
        } else {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val ba = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            @Suppress("DEPRECATION")
            if (enable) ba?.enable() else ba?.disable()
            "Bluetooth ${if (enable) "enabling" else "disabling"}…"
        }
    }

    private fun toolToggleDnd(args: Map<String, Any>): String {
        val enable = args["enable"] as? Boolean ?: return "Missing enable"
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return "Permission required — opening DND settings."
            }
            nm.setInterruptionFilter(
                if (enable) android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                else        android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            )
            "Do Not Disturb ${if (enable) "enabled" else "disabled"}"
        } catch (e: Exception) { "DND change failed: ${e.message}" }
    }

    private fun toolSetClipboard(args: Map<String, Any>): String {
        val text = args["text"] as? String ?: return "Missing text"
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("VoiceOS", text))
            "Copied to clipboard: ${text.take(60)}${if (text.length > 60) "…" else ""}"
        } catch (e: Exception) { "Clipboard write failed: ${e.message}" }
    }

    // ── Calendar & tasks ──────────────────────────────────────────
    private fun toolCreateEvent(args: Map<String, Any>): String {
        val title    = args["title"]       as? String ?: return "Missing title"
        val startStr = args["start_time"]  as? String ?: return "Missing start_time"
        val durMin   = (args["duration_min"] as? Double)?.toInt() ?: 60
        val desc     = args["description"] as? String ?: ""
        val location = args["location"]    as? String ?: ""
        return try {
            val startMs = parseIsoTime(startStr)
            val cv = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, startMs + durMin * 60_000L)
                put(CalendarContract.Events.DESCRIPTION, desc)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.CALENDAR_ID, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv)
            "Created event: \"$title\" at $startStr (${durMin}min)"
        } catch (e: Exception) { "Create event failed: ${e.message}" }
    }

    private fun toolListTasks(): String {
        val all   = loadTasks()
        val tasks = all.filter { it["status"] != "done" }
        if (tasks.isEmpty()) return if (all.isEmpty()) "No tasks." else "No active tasks. (${all.size - tasks.size} completed tasks hidden — use clear_completed_tasks to remove them.)"
        val sb = StringBuilder("Tasks (${tasks.size} active) — each task is INDEPENDENT, treat them separately:\n")
        // Group by priority for clarity
        for (pri in listOf("high", "medium", "low")) {
            val group = tasks.filter { (it["priority"] as? String ?: "medium") == pri }
            if (group.isEmpty()) continue
            sb.appendLine("── ${pri.uppercase()} ──")
            group.forEach { t ->
                val status = t["status"] as? String ?: "pending"
                val notes  = (t["notes"] as? String)?.takeIf { it.isNotBlank() }
                sb.appendLine("  [${t["id"]}] [$status] ${t["title"]}")
                if (notes != null) sb.appendLine("        notes: $notes")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun toolAddTask(args: Map<String, Any>): String {
        val title    = args["title"]    as? String ?: return "Missing title"
        val priority = args["priority"] as? String ?: "medium"
        val notes    = args["notes"]    as? String ?: ""
        val tasks    = loadTasks()
        val ts       = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        val task     = mutableMapOf<String, Any>(
            "id" to newTaskId(), "title" to title, "status" to "pending",
            "priority" to priority, "notes" to notes, "created" to ts, "updated" to ts
        )
        tasks.add(task); saveTasks(tasks)
        return "Task added: \"$title\" [${task["id"]}]"
    }

    private fun toolUpdateTask(args: Map<String, Any>): String {
        val id    = args["id"]     as? String ?: return "Missing id"
        val tasks = loadTasks()
        val task  = tasks.firstOrNull { it["id"] == id } ?: return "Task $id not found"
        val ts    = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        (args["status"] as? String)?.let { task["status"] = it }
        (args["notes"]  as? String)?.let { task["notes"]  = it }
        task["updated"] = ts; saveTasks(tasks)
        return "Task $id updated"
    }

    private fun toolCompleteTask(args: Map<String, Any>): String {
        val id    = args["id"] as? String ?: return "Missing id"
        val tasks = loadTasks()
        val task  = tasks.firstOrNull { it["id"] == id } ?: return "Task $id not found"
        val title = task["title"] as? String ?: id
        // Remove immediately — don't leave done tasks in the list where they can be re-triggered
        saveTasks(tasks.also { it.removeAll { t -> t["id"] == id } })
        return "Task \"$title\" completed and removed."
    }

    private fun toolDeleteTask(args: Map<String, Any>): String {
        val id    = args["id"] as? String ?: return "Missing id"
        val tasks = loadTasks()
        val task  = tasks.firstOrNull { it["id"] == id } ?: return "Task $id not found"
        saveTasks(tasks.also { it.removeAll { t -> t["id"] == id } })
        return "Task \"${task["title"]}\" deleted"
    }

    private fun toolClearCompletedTasks(): String {
        val tasks   = loadTasks()
        val before  = tasks.size
        saveTasks(tasks.also { it.removeAll { t -> t["status"] == "done" } })
        val cleared = before - loadTasks().size
        return if (cleared > 0) "Cleared $cleared completed task${if (cleared != 1) "s" else ""}"
               else "No completed tasks to clear"
    }

    // ── Memory ────────────────────────────────────────────────────
    private fun toolRemember(args: Map<String, Any>): String {
        val note  = args["note"] as? String ?: return "Missing note"
        val notes = prefs.getString("agent_notes", "") ?: ""
        val ts    = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US).format(java.util.Date())
        prefs.edit().putString("agent_notes", "$notes\n[$ts] $note".trimStart()).apply()
        return "Remembered: $note"
    }

    private fun toolGetUserProfile(): String {
        val p = loadProfile()
        if (p.isEmpty()) return "No profile saved yet."
        return buildProfileContext().ifBlank { "Profile is empty." }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toolUpdateUserProfile(args: Map<String, Any>): String {
        val profile = mergeProfile(args)
        val complete = profile["onboarding_complete"] as? Boolean ?: false
        val name = profile["name"] as? String ?: "user"
        return if (complete) "Profile saved for $name. Onboarding complete." else "Profile updated."
    }

    // ── Communication ─────────────────────────────────────────────
    private fun toolCallContact(args: Map<String, Any>): String {
        val target = args["name_or_number"] as? String ?: return "Missing name_or_number"
        val number = resolveContact(target) ?: target
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return "Opening dialer for $number"
    }

    private fun toolSendSms(args: Map<String, Any>): String {
        val target = args["name_or_number"] as? String ?: return "Missing name_or_number"
        val body   = args["body"] as? String ?: ""
        val number = resolveContact(target) ?: target
        // Queue for approval instead of opening immediately
        val action = actionQueue.add(
            type    = "send_sms",
            params  = mapOf("to" to number, "body" to body),
            preview = "SMS to $target: ${body.take(60)}"
        )
        return "SMS queued for approval [${action.id}]: \"${body.take(80)}\""
    }

    private fun toolDraftEmail(args: Map<String, Any>): String {
        val to      = args["to"]      as? String ?: return "Missing to"
        val subject = args["subject"] as? String ?: return "Missing subject"
        val body    = args["body"]    as? String ?: return "Missing body"
        val action  = actionQueue.add(
            type    = "draft_email",
            params  = mapOf("to" to to, "subject" to subject, "body" to body),
            preview = "Email to $to: $subject"
        )
        return "Email queued for approval [${action.id}]: \"$subject\" to $to"
    }

    private fun toolSendWhatsapp(args: Map<String, Any>): String {
        val target = args["name_or_number"] as? String ?: return "Missing name_or_number"
        val body   = args["body"]           as? String ?: return "Missing body"
        val number = resolveContact(target) ?: target
        val action = actionQueue.add(
            type    = "send_whatsapp",
            params  = mapOf("to" to number, "body" to body),
            preview = "WhatsApp to $target: ${body.take(60)}"
        )
        return "WhatsApp message queued for approval [${action.id}]"
    }

    private fun toolNavigate(args: Map<String, Any>): String {
        val dest = args["destination"] as? String ?: return "Missing destination"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return "Navigating to $dest"
    }

    // ════════════════════════════════════════════════════════════════
    // LLM calling — all accept explicit toolDefs for skill filtering
    // ════════════════════════════════════════════════════════════════

    private fun anthropicWithTools(
        messages: List<Map<String, Any>>,
        toolDefs: List<ToolDef>,
        sysPrompt: String,
        modelOverride: String = activeModel
    ): AgentResult {
        val key = apiKey("anthropic")
        if (key.isBlank()) return AgentResult("No Anthropic API key saved.", toolCalls = emptyList(), done = true)
        return try {
            val toolsJson = toolDefs.map { t -> mapOf(
                "name"         to t.name,
                "description"  to t.description,
                "input_schema" to mapOf("type" to "object", "properties" to t.params, "required" to t.required)
            )}
            // System prompt as structured content block with prompt caching enabled.
            // cache_control: ephemeral caches this block for ~5 min, cutting repeat input cost by ~90%.
            val systemBlock = listOf(mapOf(
                "type"          to "text",
                "text"          to sysPrompt,
                "cache_control" to mapOf("type" to "ephemeral")
            ))
            val payload = gson.toJson(mapOf(
                "model" to modelOverride, "max_tokens" to 4096,
                "system" to systemBlock, "tools" to toolsJson, "messages" to messages
            ))
            val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("anthropic-beta", "prompt-caching-2024-07-31")
            conn.doOutput = true; conn.connectTimeout = 15_000; conn.readTimeout = 120_000
            conn.outputStream.use { it.write(payload.toByteArray()) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                return AgentResult("Anthropic error: $err", toolCalls = emptyList(), done = true)
            }
            @Suppress("UNCHECKED_CAST")
            val obj        = gson.fromJson(conn.inputStream.bufferedReader().readText(), Map::class.java) as Map<String, Any>
            val stopReason = obj["stop_reason"] as? String ?: "end_turn"
            @Suppress("UNCHECKED_CAST")
            val content    = obj["content"] as? List<Map<String, Any>> ?: emptyList()
            val textParts  = content.filter { it["type"] == "text" }.joinToString("") { it["text"] as? String ?: "" }
            val toolBlocks = content.filter { it["type"] == "tool_use" }
            if (toolBlocks.isEmpty() || stopReason == "end_turn") {
                return AgentResult(textParts, rawContent = content, toolCalls = emptyList(), done = true)
            }
            val calls = toolBlocks.map { blk ->
                @Suppress("UNCHECKED_CAST")
                ToolCall(id = blk["id"] as? String ?: "", name = blk["name"] as? String ?: "",
                         args = blk["input"] as? Map<String, Any> ?: emptyMap())
            }
            AgentResult(textParts, rawContent = content, toolCalls = calls, done = false)
        } catch (e: Exception) { AgentResult("Anthropic request failed: ${e.message}", toolCalls = emptyList(), done = true) }
    }

    private fun openaiWithTools(
        messages: List<Map<String, Any>>,
        toolDefs: List<ToolDef>,
        url: String,
        sysPrompt: String,
        modelOverride: String = activeModel
    ): AgentResult {
        val provName = when {
            url.contains("x.ai")  -> "grok"
            else                  -> "openai"
        }
        val key = apiKey(provName)
        if (key.isBlank()) return AgentResult("No $provName API key saved.", toolCalls = emptyList(), done = true)
        return try {
            val toolsJson = toolDefs.map { t -> mapOf("type" to "function", "function" to mapOf(
                "name" to t.name, "description" to t.description,
                "parameters" to mapOf("type" to "object", "properties" to t.params, "required" to t.required)
            ))}
            val payload = gson.toJson(mapOf(
                "model" to modelOverride, "tools" to toolsJson,
                "messages" to listOf(mapOf("role" to "system", "content" to sysPrompt)) + messages
            ))
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.doOutput = true; conn.connectTimeout = 15_000; conn.readTimeout = 120_000
            conn.outputStream.use { it.write(payload.toByteArray()) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                return AgentResult("$provName error: $err", toolCalls = emptyList(), done = true)
            }
            @Suppress("UNCHECKED_CAST")
            val obj     = gson.fromJson(conn.inputStream.bufferedReader().readText(), Map::class.java) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val msg     = ((obj["choices"] as? List<Map<String, Any>>)?.firstOrNull()?.get("message")) as? Map<String, Any> ?: emptyMap()
            val text    = msg["content"] as? String ?: ""
            @Suppress("UNCHECKED_CAST")
            val rawCalls = msg["tool_calls"] as? List<Map<String, Any>>
            if (rawCalls.isNullOrEmpty()) return AgentResult(text, rawContent = msg, toolCalls = emptyList(), done = true)
            val calls = rawCalls.mapNotNull { tc ->
                @Suppress("UNCHECKED_CAST")
                val fn   = tc["function"] as? Map<String, Any> ?: return@mapNotNull null
                val name = fn["name"] as? String ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val args = try { gson.fromJson(fn["arguments"] as? String ?: "{}", Map::class.java) as Map<String, Any> } catch (_: Exception) { emptyMap() }
                ToolCall(id = tc["id"] as? String ?: "", name = name, args = args)
            }
            // Build a complete OpenAI-format assistant message to store in context.
            // rawContent must NOT be the full msg map — AgentEngine will wrap it in
            // {"role":"assistant","content":<rawContent>} which would double-nest it.
            // We instead use a ready-to-add raw message and signal that via rawContent
            // being a Map with a "role" key (AgentEngine checks for this).
            val assistantMsg = mutableMapOf<String, Any?>(
                "role"       to "assistant",
                "content"    to text.ifBlank { null },   // OpenAI requires explicit null, not absent
                "tool_calls" to rawCalls
            )
            AgentResult(text, rawContent = assistantMsg, toolCalls = calls, done = false)
        } catch (e: Exception) { AgentResult("$provName request failed: ${e.message}", toolCalls = emptyList(), done = true) }
    }

    private fun ollamaWithTools(
        messages: List<Map<String, Any>>,
        toolDefs: List<ToolDef>,
        sysPrompt: String
    ): AgentResult {
        // Bypass the full cloud-model system prompt for Ollama.
        // The full prompt (profile + live context + skill suffix + tool descriptions)
        // can be 500–1000 tokens — fine for a cloud API, but kills prefill speed on-device.
        // Instead, give the local model a 3-line prompt + compact tool names only.
        val userName = (loadProfile()["name"] as? String)?.takeIf { it.isNotBlank() }?.let { ", user is $it" } ?: ""
        val toolListStr = toolDefs.joinToString(" | ") { t ->
            val params = t.params.keys.joinToString(",")
            if (params.isEmpty()) t.name else "${t.name}($params)"
        }
        val systemPrompt = "You are VoiceOS, an Android assistant$userName. Be concise.\n" +
            "To call a tool: <tool_call>{\"name\":\"tool\",\"args\":{...}}</tool_call>\n" +
            "Tools: $toolListStr\n" +
            "After tool results give a plain-text answer. No <tool_call> in final answer."
        // Limit history to the 4 most-recent messages for local models —
        // older turns inflate prefill tokens without helping the current query.
        val histStr = messages.takeLast(4).joinToString("\n") { msg ->
            val role    = msg["role"] as? String ?: "user"
            val content = when (val c = msg["content"]) {
                is String -> c
                is List<*> -> @Suppress("UNCHECKED_CAST") (c as? List<Map<String, Any>>)?.joinToString(" ") { it["content"] as? String ?: "" } ?: ""
                else -> c.toString()
            }
            "${role.uppercase()}: $content"
        }
        return try {
            val conn = URL("${ollamaUrl.trimEnd('/')}/api/generate").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Connection", "close")
            conn.doOutput = true; conn.connectTimeout = 8_000; conn.readTimeout = 300_000
            conn.outputStream.use { it.write(gson.toJson(mapOf(
                "model"      to activeModel,
                "prompt"     to "$systemPrompt\n\n$histStr\nASSISTANT:",
                "stream"     to true,
                "keep_alive" to "30m"
            )).toByteArray()) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                return AgentResult(ollamaHint(err), toolCalls = emptyList(), done = true)
            }
            // stream=true: read NDJSON lines and accumulate tokens
            val sb = StringBuilder()
            conn.inputStream.bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    @Suppress("UNCHECKED_CAST")
                    val obj = gson.fromJson(line, Map::class.java) as Map<String, Any>
                    (obj["response"] as? String)?.let { sb.append(it) }
                } catch (_: Exception) {}
            }
            val full = sb.toString().trim()

            val matches = Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL).findAll(full).toList()
            if (matches.isEmpty()) {
                return AgentResult(full, toolCalls = emptyList(), done = true)
            }
            val calls = matches.mapNotNull { m ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    val tc   = gson.fromJson(m.groupValues[1].trim(), Map::class.java) as Map<String, Any>
                    val name = tc["name"] as? String ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val args = tc["args"] as? Map<String, Any> ?: emptyMap()
                    ToolCall(id = "ollama_${System.currentTimeMillis()}", name = name, args = args)
                } catch (_: Exception) { null }
            }
            AgentResult(full.substringBefore("<tool_call>").trim(), rawContent = full,
                toolCalls = calls, done = false)
        } catch (e: Exception) { AgentResult(ollamaHint("${e.javaClass.simpleName}: ${e.message}"), toolCalls = emptyList(), done = true) }
    }

    // ════════════════════════════════════════════════════════════════
    // New expanded tool implementations
    // ════════════════════════════════════════════════════════════════

    private fun toolSetTimer(args: Map<String, Any>): String {
        val minutes = (args["duration_minutes"] as? Double) ?: return "Missing duration_minutes"
        val label   = args["label"] as? String ?: "Timer"
        val seconds = (minutes * 60).toInt().coerceAtLeast(1)
        context.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        val display = if (minutes < 1.0) "${seconds}s" else if (minutes == minutes.toLong().toDouble()) "${minutes.toInt()}m" else "${minutes}m"
        return "Timer set: $display — $label"
    }

    private fun toolMediaControl(args: Map<String, Any>): String {
        val action = args["action"] as? String ?: return "Missing action"
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val keyCode = when (action.lowercase()) {
            "play", "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next"          -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous"      -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop"          -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else            -> return "Unknown action: $action. Use: play, pause, next, previous, stop"
        }
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        return "Media: ${action.lowercase()} dispatched"
    }

    private fun toolOpenUrl(args: Map<String, Any>): String {
        val url = args["url"] as? String ?: return "Missing url"
        val safeUrl = if (!url.startsWith("http")) "https://$url" else url
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return "Opened: $safeUrl"
    }

    private fun toolSearchContacts(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return "Missing query"
        val limit = (args["limit"] as? Double)?.toInt() ?: 10
        return try {
            val cur = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            val seen = mutableSetOf<String>()
            val results = mutableListOf<String>()
            cur?.use { c ->
                while (c.moveToNext() && results.size < limit) {
                    val name = c.getString(0) ?: continue
                    val num  = c.getString(1) ?: continue
                    val key  = "${name.lowercase()}:${num.takeLast(7)}"
                    if (key in seen) continue; seen += key
                    val typeStr = when (c.getInt(2)) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME   -> "home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK   -> "work"
                        else -> "phone"
                    }
                    results += "$name — $num ($typeStr)"
                }
            }
            if (results.isEmpty()) "No contacts found matching: $query"
            else "Contacts matching \"$query\":\n${results.joinToString("\n")}"
        } catch (e: Exception) { "Contact search failed: ${e.message}" }
    }

    private fun toolGetNetworkInfo(): String {
        val sb = StringBuilder()
        try {
            val wm = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (wm.isWifiEnabled) {
                val info = wm.connectionInfo
                val ssid = info.ssid?.replace("\"", "") ?: "unknown"
                val ip   = android.text.format.Formatter.formatIpAddress(info.ipAddress)
                val rssi = info.rssi
                val qual = android.net.wifi.WifiManager.calculateSignalLevel(rssi, 5)
                sb.appendLine("WiFi: $ssid")
                sb.appendLine("IP: $ip")
                sb.appendLine("Signal: $qual/4 (${rssi}dBm)")
            } else {
                sb.appendLine("WiFi: off")
            }
        } catch (_: Exception) { sb.appendLine("WiFi: unavailable") }
        try {
            val tm = context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val carrier = tm.networkOperatorName ?: "unknown"
            val dataState = when (tm.dataState) {
                android.telephony.TelephonyManager.DATA_CONNECTED    -> "connected"
                android.telephony.TelephonyManager.DATA_CONNECTING   -> "connecting"
                android.telephony.TelephonyManager.DATA_DISCONNECTED -> "disconnected"
                else -> "unknown"
            }
            sb.appendLine("Carrier: $carrier")
            sb.appendLine("Mobile data: $dataState")
        } catch (_: Exception) {}
        return sb.toString().trim()
    }

    private fun toolGetStorageInfo(): String {
        return try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val total = stat.totalBytes / (1024L * 1024L * 1024L)
            val avail = stat.availableBytes / (1024L * 1024L * 1024L)
            val used  = total - avail
            val extStat = try {
                val es = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
                "\nSD/External: ${es.availableBytes / (1024L * 1024L * 1024L)}GB free / ${es.totalBytes / (1024L * 1024L * 1024L)}GB total"
            } catch (_: Exception) { "" }
            "Internal storage: ${used}GB used / ${total}GB total (${avail}GB free)$extStat"
        } catch (e: Exception) { "Storage info unavailable: ${e.message}" }
    }

    private fun toolSetRingerMode(args: Map<String, Any>): String {
        val mode = args["mode"] as? String ?: return "Missing mode"
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val ringerMode = when (mode.lowercase()) {
            "silent"  -> android.media.AudioManager.RINGER_MODE_SILENT
            "vibrate" -> android.media.AudioManager.RINGER_MODE_VIBRATE
            "normal"  -> android.media.AudioManager.RINGER_MODE_NORMAL
            else      -> return "Unknown mode: $mode. Use: normal, vibrate, or silent"
        }
        return try {
            am.ringerMode = ringerMode
            "Ringer mode set to ${mode.lowercase()}"
        } catch (e: Exception) { "Ringer mode change failed: ${e.message}" }
    }

    private fun toolToggleFlashlight(args: Map<String, Any>): String {
        val enable = args["enable"] as? Boolean ?: return "Missing enable"
        return try {
            val cm = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flash hardware found"
            cm.setTorchMode(cameraId, enable)
            "Flashlight ${if (enable) "on" else "off"}"
        } catch (e: Exception) { "Flashlight failed: ${e.message}" }
    }

    private fun toolOpenSettingsScreen(args: Map<String, Any>): String {
        val screen = args["screen"] as? String ?: return "Missing screen"
        val action = when (screen.lowercase().replace(" ", "_").replace("-", "_")) {
            "wifi"              -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth"         -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery"           -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "display"           -> Settings.ACTION_DISPLAY_SETTINGS
            "sound"             -> Settings.ACTION_SOUND_SETTINGS
            "apps"              -> Settings.ACTION_APPLICATION_SETTINGS
            "accessibility"     -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notifications"     -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            "location"          -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "storage"           -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "developer"         -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "about"             -> Settings.ACTION_DEVICE_INFO_SETTINGS
            "date_time"         -> Settings.ACTION_DATE_SETTINGS
            "language"          -> Settings.ACTION_LOCALE_SETTINGS
            "security"          -> Settings.ACTION_SECURITY_SETTINGS
            "nfc"               -> Settings.ACTION_NFC_SETTINGS
            "hotspot"           -> Settings.ACTION_WIRELESS_SETTINGS
            else                -> Settings.ACTION_SETTINGS
        }
        context.startActivity(Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return "Opened $screen settings"
    }

    private fun toolListInstalledApps(args: Map<String, Any>): String {
        val filter = (args["filter"] as? String)?.lowercase()?.trim()
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            PackageManager.GET_META_DATA
        ).map { it.loadLabel(pm).toString() }
         .filter { filter.isNullOrBlank() || it.lowercase().contains(filter) }
         .sorted()
        return if (apps.isEmpty()) "No apps found${if (!filter.isNullOrBlank()) " matching: $filter" else ""}"
               else "Installed apps (${apps.size}):\n${apps.joinToString(", ")}"
    }

    private fun toolFetchWebpage(args: Map<String, Any>): String {
        val url      = args["url"] as? String ?: return "Missing url"
        val maxChars = (args["max_chars"] as? Double)?.toInt() ?: 3000
        val safeUrl  = if (!url.startsWith("http")) "https://$url" else url
        return try {
            val conn = java.net.URL(safeUrl).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; VoiceOS/1.0)")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain")
            conn.connectTimeout = 12_000; conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code >= 400) return "HTTP $code for $safeUrl"
            val raw = conn.inputStream.bufferedReader().readText()
            // Strip HTML tags and collapse whitespace
            val text = raw
                .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("&nbsp;"), " ")
                .replace(Regex("&amp;"), "&")
                .replace(Regex("&lt;"), "<")
                .replace(Regex("&gt;"), ">")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isBlank()) "Page appears empty or is non-text content."
            else "Content from $safeUrl:\n${text.take(maxChars)}${if (text.length > maxChars) "\n[truncated — ${text.length - maxChars} chars remaining]" else ""}"
        } catch (e: Exception) { "Fetch failed: ${e.message}" }
    }

    private fun toolCreateContact(args: Map<String, Any>): String {
        val name  = args["name"]  as? String ?: return "Missing name"
        val phone = args["phone"] as? String ?: ""
        val email = args["email"] as? String ?: ""
        val notes = args["notes"] as? String ?: ""
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            if (name.isNotBlank())  putExtra(ContactsContract.Intents.Insert.NAME, name)
            if (phone.isNotBlank()) putExtra(ContactsContract.Intents.Insert.PHONE, phone)
            if (email.isNotBlank()) putExtra(ContactsContract.Intents.Insert.EMAIL, email)
            if (notes.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NOTES, notes)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opening contacts app to create: $name${if (phone.isNotBlank()) " ($phone)" else ""}"
    }

    private fun toolTypeText(args: Map<String, Any>): String {
        if (!VoiceOSAccessibilityService.isAvailable())
            return "Accessibility service not enabled. Ask the user to enable VoiceOS in Settings › Accessibility."
        val text = args["text"] as? String ?: return "Missing text"
        val ok = VoiceOSAccessibilityService.typeText(text)
        return if (ok) "Typed: ${text.take(60)}${if (text.length > 60) "…" else ""}"
               else "Could not type — no input field is focused. Use tap_screen on an input field first, then call type_text."
    }

    private fun toolLongPressScreen(args: Map<String, Any>): String {
        if (!VoiceOSAccessibilityService.isAvailable()) return "Accessibility service not enabled"
        val x   = (args["x"] as? Double)?.toFloat() ?: return "Missing x"
        val y   = (args["y"] as? Double)?.toFloat() ?: return "Missing y"
        val dur = (args["duration_ms"] as? Double)?.toLong() ?: 800L
        val ok  = VoiceOSAccessibilityService.longPress(x, y, dur)
        return if (ok) "Long-pressed (${x.toInt()}, ${y.toInt()}) for ${dur}ms"
               else "Long press failed"
    }

    private fun toolGetForegroundApp(): String {
        return try {
            if (!VoiceOSAccessibilityService.isAvailable())
                return "Accessibility service not enabled — enable VoiceOS in Settings › Accessibility"
            val pkgName = VoiceOSAccessibilityService.instance
                ?.rootInActiveWindow?.packageName?.toString() ?: return "Could not determine foreground app"
            val pm   = context.packageManager
            val name = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString()
            } catch (_: Exception) { pkgName }
            "Foreground app: $name (package: $pkgName)"
        } catch (e: Exception) { "Could not determine foreground app: ${e.message}" }
    }

    private fun toolMorningBriefing(): String {
        val sb = StringBuilder()
        val sdf = java.text.SimpleDateFormat("EEEE, MMMM d yyyy", java.util.Locale.US)
        val tdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        sb.appendLine("## Morning Briefing — ${sdf.format(java.util.Date())}")
        sb.appendLine("Time: ${tdf.format(java.util.Date())}")
        sb.appendLine()

        // Battery
        try { sb.appendLine("**${toolGetBattery()}**") } catch (_: Exception) {}

        // Today's calendar
        sb.appendLine()
        sb.appendLine("### Today's Schedule")
        try {
            val cal = toolReadCalendar(mapOf("days" to 1.0))
            sb.appendLine(if (cal.startsWith("No events")) "Nothing scheduled today." else cal)
        } catch (_: Exception) { sb.appendLine("Calendar unavailable.") }

        // Active tasks
        sb.appendLine()
        sb.appendLine("### Tasks")
        val tasks = loadTasks().filter { it["status"] != "done" }
        if (tasks.isEmpty()) {
            sb.appendLine("No pending tasks.")
        } else {
            val hi  = tasks.filter { it["priority"] == "high" }
            val mid = tasks.filter { it["priority"] == "medium" }
            if (hi.isNotEmpty()) {
                sb.appendLine("**High priority (${hi.size}):**")
                hi.take(3).forEach { sb.appendLine("• ${it["title"]}") }
            }
            if (mid.isNotEmpty()) {
                sb.appendLine("Medium (${mid.size} tasks)")
            }
            sb.appendLine("Total: ${tasks.size} active tasks")
        }

        // Notifications / pending attention
        sb.appendLine()
        sb.appendLine("### Pending Attention")
        try {
            val attention = contextStore.getPendingAttention(5)
            if (attention.isEmpty()) {
                sb.appendLine("Inbox clear.")
            } else {
                attention.forEach { doc ->
                    sb.appendLine("• [${doc.type}] ${doc.title.take(60)}")
                }
            }
        } catch (_: Exception) {
            if (isNotificationListenerEnabled()) {
                val notifs = VoiceOSNotificationService.getRecent().take(5)
                if (notifs.isEmpty()) sb.appendLine("No recent notifications.")
                else notifs.forEach { n ->
                    val app = n["app"] as? String ?: "?"
                    val title = n["title"] as? String ?: ""
                    sb.appendLine("• $app${if (title.isNotBlank()) ": $title" else ""}")
                }
            } else sb.appendLine("Notification access not enabled.")
        }

        // Social nudges
        sb.appendLine()
        sb.appendLine("### Relationship Nudges")
        try {
            val outreach = toolSuggestSocialOutreach(mapOf("threshold_days" to 14.0))
            sb.appendLine(if (outreach.startsWith("Everyone")) "All connections are up to date." else outreach)
        } catch (_: Exception) { sb.appendLine("CRM unavailable.") }

        return sb.toString().trimEnd()
    }

    private fun toolPrioritizeTasks(): String {
        val tasks = loadTasks().filter { it["status"] != "done" }
        if (tasks.isEmpty()) return "No active tasks to prioritize."
        val sb = StringBuilder("### Task Priority Analysis\n\n")
        val high   = tasks.filter { it["priority"] == "high" }
        val medium = tasks.filter { it["priority"] == "medium" }
        val low    = tasks.filter { it["priority"] == "low" || it["priority"] == null }
        if (high.isNotEmpty()) {
            sb.appendLine("**Do first — High priority:**")
            high.forEach { t ->
                val status = t["status"] as? String ?: "pending"
                sb.appendLine("• [${t["id"]}] ${t["title"]} [$status]${(t["notes"] as? String)?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}")
            }
            sb.appendLine()
        }
        if (medium.isNotEmpty()) {
            sb.appendLine("**Then — Medium priority (${medium.size}):**")
            medium.take(5).forEach { t -> sb.appendLine("• [${t["id"]}] ${t["title"]}") }
            if (medium.size > 5) sb.appendLine("  … and ${medium.size - 5} more")
            sb.appendLine()
        }
        if (low.isNotEmpty()) {
            sb.appendLine("**Later — Low priority (${low.size} tasks)**")
        }
        val blocked = tasks.filter { it["status"] == "blocked" }
        if (blocked.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("**Blocked (needs attention):**")
            blocked.forEach { t -> sb.appendLine("• [${t["id"]}] ${t["title"]}") }
        }
        val next = high.firstOrNull() ?: medium.firstOrNull() ?: low.firstOrNull()
        if (next != null) {
            sb.appendLine()
            sb.appendLine("**Suggested next:** ${next["title"]}")
        }
        return sb.toString().trimEnd()
    }

    private fun toolSummarizeSmsThread(args: Map<String, Any>): String {
        val contact = args["contact"] as? String ?: return "Missing contact"
        val limit   = (args["limit"] as? Double)?.toInt() ?: 20
        return try {
            val number = resolveContact(contact) ?: contact
            val suffix = number.takeLast(9)
            val cur = context.contentResolver.query(
                android.net.Uri.parse("content://sms"),
                arrayOf("address", "body", "date", "type"),
                "address LIKE ?",
                arrayOf("%$suffix%"),
                "date DESC"
            )
            val msgs = mutableListOf<Triple<String, String, Boolean>>()  // (ageStr, body, isIncoming)
            cur?.use { c ->
                while (c.moveToNext() && msgs.size < limit) {
                    val body     = c.getString(1) ?: continue
                    val date     = c.getLong(2)
                    val incoming = c.getInt(3) == 1
                    val age      = System.currentTimeMillis() - date
                    val ageStr   = when {
                        age < 3_600_000  -> "${age / 60_000}m ago"
                        age < 86_400_000 -> "${age / 3_600_000}h ago"
                        else             -> "${age / 86_400_000}d ago"
                    }
                    msgs += Triple(ageStr, body, incoming)
                }
            }
            if (msgs.isEmpty()) return "No SMS conversation found with $contact"
            val sb = StringBuilder("### SMS Thread with $contact (${msgs.size} messages)\n\n")
            // Show most recent first in summary
            msgs.reversed().takeLast(10).forEach { (age, body, incoming) ->
                val who = if (incoming) contact.split(" ").first() else "You"
                sb.appendLine("[$age] **$who**: ${body.take(120)}")
            }
            sb.appendLine()
            val unread = msgs.count { it.third }
            val lastMsg = msgs.first()
            sb.appendLine("**Latest:** ${lastMsg.second.take(80)} (${lastMsg.first})")
            if (unread > 0) sb.appendLine("**Unread messages:** $unread")
            sb.toString().trimEnd()
        } catch (e: Exception) { "SMS read failed: ${e.message}" }
    }

    // ════════════════════════════════════════════════════════════════
    // Context discovery tools
    // ════════════════════════════════════════════════════════════════
    // Screen interaction tools
    // ════════════════════════════════════════════════════════════════

    private fun toolTakeScreenshot(args: Map<String, Any>): String {
        if (!VoiceOSAccessibilityService.isAvailable()) {
            return "Accessibility service not enabled. Ask the user to go to Settings › Accessibility › VoiceOS and enable it, then try again."
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return "Screenshot capture requires Android 11+. Use get_screen_text instead."
        }
        val query = (args["query"] as? String)?.takeIf { it.isNotBlank() }

        // Small settle delay in case we just dispatched a tap
        Thread.sleep(600)

        val base64 = VoiceOSAccessibilityService.takeScreenshot()
            ?: return "Screenshot capture failed — service may need a moment. Try again."

        val (w, h) = VoiceOSAccessibilityService.getScreenSize()
        return callVisionLlm(base64, buildVisionPrompt(w, h, query))
    }

    private fun buildVisionPrompt(screenW: Int, screenH: Int, query: String?): String = buildString {
        appendLine("You are analyzing a screenshot from an Android phone.")
        appendLine("Screen resolution: ${screenW}×${screenH} pixels. Coordinates are from the top-left corner.")
        appendLine()
        if (!query.isNullOrBlank()) {
            appendLine("Focus on: $query")
            appendLine()
        }
        appendLine("Provide:")
        appendLine("1. App/screen name and what is being displayed")
        appendLine("2. Key text content — quote important text verbatim (email body, message content, etc.)")
        appendLine("3. Interactive elements with their CENTER pixel coordinates in the format (x, y):")
        appendLine("   List every visible button, link, input field, back arrow, menu item")
        appendLine("   Example: \"Reply button — tap at (540, 1820)\"")
        if (!query.isNullOrBlank()) {
            appendLine()
            appendLine("Then directly answer: $query")
        }
    }

    /**
     * Call the active LLM provider with an image. Tries vision-capable endpoint.
     * Returns a text description of the image.
     */
    private fun callVisionLlm(base64Jpeg: String, prompt: String): String {
        return try {
            when (activeProvider) {
                "anthropic" -> visionCallAnthropic(base64Jpeg, prompt)
                "openai"    -> visionCallOpenAI(base64Jpeg, prompt, "https://api.openai.com/v1/chat/completions")
                "grok"      -> visionCallOpenAI(base64Jpeg, prompt, "https://api.x.ai/v1/chat/completions")
                "ollama"    -> visionCallOllama(base64Jpeg, prompt)
                else        -> "Vision not supported for provider: $activeProvider"
            }
        } catch (e: Exception) {
            "Vision analysis failed: ${e.message}"
        }
    }

    private fun visionCallAnthropic(base64Jpeg: String, prompt: String): String {
        val key = apiKey("anthropic").ifBlank { return "Anthropic API key not set" }
        val body = gson.toJson(mapOf(
            "model"      to activeModel,
            "max_tokens" to 1024,
            "messages"   to listOf(mapOf(
                "role"    to "user",
                "content" to listOf(
                    mapOf("type" to "image", "source" to mapOf(
                        "type"       to "base64",
                        "media_type" to "image/jpeg",
                        "data"       to base64Jpeg
                    )),
                    mapOf("type" to "text", "text" to prompt)
                )
            ))
        ))
        return httpPost("https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01",
                  "content-type" to "application/json"),
            body) { resp ->
            @Suppress("UNCHECKED_CAST")
            val content = (gson.fromJson(resp, Map::class.java)["content"] as? List<*>)
            (content?.firstOrNull() as? Map<*, *>)?.get("text") as? String ?: resp
        }
    }

    private fun visionCallOpenAI(base64Jpeg: String, prompt: String, endpoint: String): String {
        val provider = if (endpoint.contains("groq")) "groq" else "openai"
        val key = apiKey(provider).ifBlank { return "$provider API key not set" }
        val body = gson.toJson(mapOf(
            "model"      to activeModel,
            "max_tokens" to 1024,
            "messages"   to listOf(mapOf(
                "role"    to "user",
                "content" to listOf(
                    mapOf("type" to "image_url", "image_url" to mapOf(
                        "url" to "data:image/jpeg;base64,$base64Jpeg"
                    )),
                    mapOf("type" to "text", "text" to prompt)
                )
            ))
        ))
        return httpPost(endpoint,
            mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
            body) { resp ->
            @Suppress("UNCHECKED_CAST")
            val choices = (gson.fromJson(resp, Map::class.java)["choices"] as? List<*>)
            val msg = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
            msg?.get("content") as? String ?: resp
        }
    }

    private fun visionCallOllama(base64Jpeg: String, prompt: String): String {
        val url  = "${ollamaUrl.trimEnd('/')}/api/generate"
        val body = gson.toJson(mapOf(
            "model"  to activeModel,
            "prompt" to prompt,
            "images" to listOf(base64Jpeg),
            "stream" to false
        ))
        return httpPost(url, mapOf("Content-Type" to "application/json"), body) { resp ->
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson(resp, Map::class.java)["response"] as? String) ?: resp
        }
    }

    /** Generic HTTP POST helper — [parse] receives the raw response body. */
    private fun <T> httpPost(
        url: String,
        headers: Map<String, String>,
        body: String,
        parse: (String) -> T
    ): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout    = 60_000
        conn.doOutput = true
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val resp = conn.inputStream.bufferedReader().readText()
        return parse(resp)
    }

    private fun toolGetScreenText(): String {
        if (!VoiceOSAccessibilityService.isAvailable()) {
            return "Accessibility service not enabled. Ask the user to go to Settings › Accessibility › VoiceOS and enable it."
        }
        val text = VoiceOSAccessibilityService.getScreenText()
        return if (text.isBlank()) "Screen appears empty or content is not accessible as text. Try take_screenshot instead."
               else "Screen content:\n$text"
    }

    private fun toolTapScreen(args: Map<String, Any>): String {
        if (!VoiceOSAccessibilityService.isAvailable()) return "Accessibility service not enabled"
        val x = (args["x"] as? Double)?.toFloat() ?: return "Missing x coordinate"
        val y = (args["y"] as? Double)?.toFloat() ?: return "Missing y coordinate"
        val ok = VoiceOSAccessibilityService.tap(x, y)
        return if (ok) "Tapped (${x.toInt()}, ${y.toInt()})" else "Tap failed — gesture was cancelled"
    }

    private fun toolSwipeScreen(args: Map<String, Any>): String {
        if (!VoiceOSAccessibilityService.isAvailable()) return "Accessibility service not enabled"
        val x1 = (args["x1"] as? Double)?.toFloat() ?: return "Missing x1"
        val y1 = (args["y1"] as? Double)?.toFloat() ?: return "Missing y1"
        val x2 = (args["x2"] as? Double)?.toFloat() ?: return "Missing x2"
        val y2 = (args["y2"] as? Double)?.toFloat() ?: return "Missing y2"
        val dur = (args["duration_ms"] as? Double)?.toLong() ?: 300L
        val ok  = VoiceOSAccessibilityService.swipe(x1, y1, x2, y2, dur)
        return if (ok) "Swiped (${x1.toInt()},${y1.toInt()}) → (${x2.toInt()},${y2.toInt()})"
               else "Swipe failed"
    }

    // ════════════════════════════════════════════════════════════════

    private fun toolContextSearch(args: Map<String, Any>): String {
        val query      = args["query"]  as? String  ?: return "Missing query"
        val typeFilter = args["type"]   as? String
        val limit      = (args["limit"] as? Double)?.toInt() ?: 8
        val results    = contextStore.search(query, typeFilter, limit)
        if (results.isEmpty()) return "No context found for: \"$query\"${if (typeFilter != null) " (type=$typeFilter)" else ""}"
        val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US)
        return buildString {
            appendLine("Context search: \"$query\" — ${results.size} result(s)")
            results.forEach { doc ->
                val age = sdf.format(java.util.Date(doc.timestamp))
                appendLine("\n[${doc.type.uppercase()}] ${doc.title} ($age)")
                appendLine(doc.body.take(200))
            }
        }.trimEnd()
    }

    private fun toolGetPendingAttention(args: Map<String, Any>): String {
        val limit = (args["limit"] as? Double)?.toInt() ?: 8
        // Trigger background discovery if stale
        discoveryEngine.scanIfStale()
        val items = contextStore.getPendingAttention(limit)
        if (items.isEmpty()) {
            return "No high-priority items found. Everything looks quiet right now."
        }
        val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US)
        return buildString {
            appendLine("Pending attention (${items.size} items):")
            items.forEach { doc ->
                val age = sdf.format(java.util.Date(doc.timestamp))
                appendLine("• [${doc.type}] ${doc.title} — $age")
                val preview = doc.body.lines().firstOrNull()?.take(100) ?: ""
                if (preview.isNotBlank()) appendLine("  $preview")
            }
            appendLine()
            appendLine("Suggested next steps:")
            // Group and suggest based on types present
            val types = items.map { it.type }.toSet()
            if (ContextStore.TYPE_SMS_THREAD in types) {
                val unreadThreads = items.filter { it.type == ContextStore.TYPE_SMS_THREAD && "unread" in it.tags }
                if (unreadThreads.isNotEmpty()) appendLine("• Reply to ${unreadThreads.size} unread SMS thread(s): use send_sms or get_message_threads for details")
            }
            if (ContextStore.TYPE_NOTIFICATION in types) appendLine("• Check notifications: use get_notifications for full detail")
            if (ContextStore.TYPE_CALENDAR in types) appendLine("• Upcoming events: use read_calendar to confirm details")
            if (ContextStore.TYPE_TASK in types) {
                val highPri = items.filter { it.type == ContextStore.TYPE_TASK && "high" in it.tags }
                if (highPri.isNotEmpty()) appendLine("• ${highPri.size} high-priority task(s) pending: use list_tasks for full list")
            }
        }.trimEnd()
    }

    private fun toolGetMessageThreads(args: Map<String, Any>): String {
        val limit      = (args["limit"]       as? Double)?.toInt() ?: 10
        val unreadOnly = args["unread_only"]  as? Boolean ?: false
        var threads = contextStore.getByType(ContextStore.TYPE_SMS_THREAD, limit * 2)
        if (unreadOnly) threads = threads.filter { "unread" in it.tags }
        threads = threads.take(limit)
        if (threads.isEmpty()) return if (unreadOnly) "No unread SMS threads." else "No SMS threads in context store. Try discover_now."
        val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US)
        return buildString {
            appendLine("SMS threads (${threads.size}):")
            threads.forEach { doc ->
                val age = sdf.format(java.util.Date(doc.timestamp))
                appendLine("\n── ${doc.title} [$age]")
                doc.body.lines().take(5).forEach { appendLine("  $it") }
            }
        }.trimEnd()
    }

    private fun toolDiscoverNow(): String {
        val start = System.currentTimeMillis()
        val count = try { discoveryEngine.fullScan() } catch (e: Exception) { return "Discovery failed: ${e.message}" }
        val ms = System.currentTimeMillis() - start
        return "Discovery complete in ${ms}ms — $count documents indexed across contacts, SMS, notifications, calendar, tasks, notes, and apps."
    }

    private fun toolGetDiscoveryStatus(): String {
        val status = contextStore.getDiscoveryStatus()
        val total  = contextStore.totalDocCount()
        val lastMs = contextStore.lastScanMs()
        if (status.isEmpty()) return "Context store is empty. Use discover_now to index device data."
        val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US)
        val lastScan = if (lastMs > 0) sdf.format(java.util.Date(lastMs)) else "never"
        return buildString {
            appendLine("Context store: $total documents total (last scan: $lastScan)")
            status.entries.sortedBy { it.key }.forEach { (type, meta) ->
                val scannedMs = meta["scanned_ms"] as? Long ?: 0L
                val count     = meta["doc_count"]  as? Int  ?: 0
                val ts = if (scannedMs > 0) sdf.format(java.util.Date(scannedMs)) else "never"
                appendLine("  $type: $count docs (scanned $ts)")
            }
        }.trimEnd()
    }

    // ════════════════════════════════════════════════════════════════
    // System prompt & live context
    // ════════════════════════════════════════════════════════════════
    private fun agentSystemPrompt(liveCtx: String = "", skillSuffix: String? = null): String {
        val profileCtx = buildProfileContext()
        val profile = if (profileCtx.isNotBlank()) "\n\n$profileCtx" else ""
        val ctx   = if (liveCtx.isNotBlank()) "\n\n$liveCtx" else ""
        val skill = if (!skillSuffix.isNullOrBlank()) "\n\n$skillSuffix" else ""
        return """You are VoiceOS, a personal AI assistant on the user's Android phone.

## Response style
- Match response length to request complexity. Greetings → 1 sentence. Simple questions → 1-3 sentences. Complex tasks → structured with headers/bullets.
- Use markdown: **bold** for key info, bullet lists for multiple items, ## headers to separate sections.
- Never narrate or repeat the device snapshot. It is silent context for you — the user cannot see it and does not want it read back.
- Never open with "I'm VoiceOS" or self-introductions after the first message. Never end with "How can I help?" unless nothing was asked.
- When you take an action, say what you did in one line. Don't explain how you did it.

## Capabilities
- Device: notifications, SMS, call log, calendar, contacts, apps, tasks, battery, screen automation.
- Context index: use context_search / get_pending_attention / get_message_threads FIRST for questions about people, messages, or upcoming events.
- Social layer: get_contact_profile, add_relationship_note, log_interaction, get_relationship_health, suggest_social_outreach.
- Shell (if Termux): run_shell for git, scripts, files. Read-only runs immediately; mutations queue for approval.
- Screen: get_screen_text (fast) or take_screenshot (vision + coordinates) → tap_screen / swipe_screen.

## Rules
- Use add_task (never remember) for to-do items.
- draft_email / send_sms / send_whatsapp always queue for approval.
- call_contact and navigate open the app directly.
- **Tasks are completely independent items.** Never infer that tasks are related or sequential just because they appear in the same list. Never combine the context of separate tasks. Never act on multiple tasks in a single turn unless the user explicitly asks you to work on multiple tasks. One task about texting someone is not connected to another task about a payment or a purchase.
- **Never act on a task autonomously.** Only advance a task when the user explicitly asks "work on this task" or "do [task title]". Listing tasks does not mean the user wants them executed.$profile$ctx$skill"""
    }

    private fun buildLiveContext(minimal: Boolean = false): String {
        val sb  = StringBuilder()
        val sdf = java.text.SimpleDateFormat("EEE MMM d yyyy, HH:mm", java.util.Locale.US)
        sb.appendLine("╔═ DEVICE SNAPSHOT ══════════════════════════════════════╗")
        sb.appendLine("  Time:    ${sdf.format(java.util.Date())}")
        try { sb.appendLine("  Device:  ${toolGetBattery()}") } catch (_: Exception) {}
        if (!minimal) {
            // WiFi
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (wm.isWifiEnabled) sb.appendLine("  Network: WiFi — ${wm.connectionInfo.ssid?.replace("\"","") ?: "connected"}")
            } catch (_: Exception) {}
            // Active tasks
            val active = loadTasks().filter { it["status"] != "done" }
            if (active.isNotEmpty()) {
                val hi = active.count { it["priority"] == "high" }
                sb.appendLine("  Tasks:   ${active.size} active${if (hi > 0) " ($hi high-priority)" else ""}")
                active.take(4).forEach { sb.appendLine("    • [${it["priority"]}/${it["status"]}] ${it["title"]}") }
            } else { sb.appendLine("  Tasks:   none") }
            // Calendar
            try {
                val cal = toolReadCalendar(mapOf("days" to 1.0))
                if (!cal.startsWith("No events")) {
                    sb.appendLine("  Calendar (24h):"); cal.lines().drop(1).take(4).forEach { sb.appendLine("    $it") }
                }
            } catch (_: Exception) {}
            // Notifications
            if (isNotificationListenerEnabled()) {
                val notifs = VoiceOSNotificationService.getRecent().take(8)
                if (notifs.isNotEmpty()) {
                    sb.appendLine("  Notifications (${notifs.size}):")
                    notifs.forEach { n ->
                        val app   = n["app"] as? String ?: n["pkg"] as? String ?: "?"
                        val title = n["title"] as? String ?: ""
                        val text  = n["text"]  as? String ?: ""
                        val age   = System.currentTimeMillis() - (n["time"] as? Long ?: 0L)
                        val ageStr = when { age < 60_000 -> "just now"; age < 3_600_000 -> "${age/60_000}m ago"; else -> "${age/3_600_000}h ago" }
                        sb.appendLine("    [$ageStr] $app${if (title.isNotBlank()) " \"$title\"" else ""}${if (text.isNotBlank()) ": ${text.take(80)}" else ""}")
                    }
                }
            }
            // Memory
            val notes = prefs.getString("agent_notes", "") ?: ""
            if (notes.isNotBlank()) { sb.appendLine("  Memory:"); notes.lines().takeLast(3).forEach { sb.appendLine("    $it") } }
            // User profile — project names only (full detail is in the system prompt via buildProfileContext)
            val prof = loadProfile()
            if (prof["onboarding_complete"] == true) {
                @Suppress("UNCHECKED_CAST")
                val projects = (prof["projects"] as? List<Map<String, Any>>)?.filterIsInstance<Map<String, Any>>() ?: emptyList()
                if (projects.isNotEmpty()) {
                    sb.appendLine("  Active projects: ${projects.mapNotNull { it["name"] as? String }.joinToString(", ")}")
                }
                // Note: social/CRM nudges are intentionally omitted here.
                // They require per-contact ContentResolver queries (expensive) and bloat tokens.
                // The social_nudge heartbeat probe surfaces overdue contacts asynchronously.
                // The agent can query get_relationship_health / suggest_social_outreach on demand.
            }
            // Pending queue
            val pending = actionQueue.getPending()
            if (pending.isNotEmpty()) {
                sb.appendLine("  Queued actions (${pending.size} awaiting approval):")
                pending.take(3).forEach { sb.appendLine("    • [${it.type}] ${it.preview}") }
            }
            // Context store pending attention (high-weight items from last 24h)
            try {
                val attention = contextStore.getPendingAttention(6)
                if (attention.isNotEmpty()) {
                    sb.appendLine("  Needs attention (${attention.size} items — use get_pending_attention for details):")
                    attention.take(4).forEach { doc ->
                        sb.appendLine("    • [${doc.type}] ${doc.title.take(60)}")
                    }
                }
                val totalDocs = contextStore.totalDocCount()
                val lastScan  = contextStore.lastScanMs()
                if (totalDocs > 0) {
                    val scanAge = if (lastScan > 0) {
                        val mins = (System.currentTimeMillis() - lastScan) / 60_000L
                        when { mins < 2 -> "just now"; mins < 60 -> "${mins}m ago"; else -> "${mins / 60}h ago" }
                    } else "not yet"
                    sb.appendLine("  Context index: $totalDocs docs (scanned $scanAge) — use context_search to query")
                } else {
                    sb.appendLine("  Context index: empty — use discover_now to index device data")
                }
            } catch (_: Exception) {}

            // Accessibility service availability
            val a11yReady = VoiceOSAccessibilityService.isAvailable()
            sb.appendLine("  Screen automation: ${if (a11yReady) "ready (take_screenshot, tap_screen, get_screen_text available)" else "not enabled — user must enable VoiceOS in Settings › Accessibility"}")
        }
        sb.append("╚════════════════════════════════════════════════════════╝")
        return sb.toString()
    }

    // ════════════════════════════════════════════════════════════════
    // User profile & onboarding handlers
    // ════════════════════════════════════════════════════════════════

    private fun handleGetProfile(): Response {
        val profile = loadProfile()
        return jsonResponse(profile)
    }

    private fun handleSaveProfile(session: IHTTPSession): Response {
        val body = parseBody(session)
        if (body.isEmpty()) return jsonResponse(mapOf("error" to "empty body"), Status.BAD_REQUEST)
        val merged = mergeProfile(body)
        return jsonResponse(mapOf("ok" to true, "profile" to merged))
    }

    /**
     * Onboarding conversation endpoint.
     * POST { message: "" }  → agent sends opening question
     * POST { message: "..." } → continues the conversation
     * Returns { reply: "...", done: false|true }
     * done=true means update_user_profile was called with onboarding_complete:true
     */
    private fun handleOnboard(session: IHTTPSession): Response {
        val body    = parseBody(session)
        val userMsg = (body["message"] as? String)?.trim() ?: ""
        val reset   = body["reset"] as? Boolean ?: false

        if (reset) {
            onboardingContext.clear()
            mergeProfile(mapOf("onboarding_complete" to false))
        }

        // First turn: agent sends the opening.
        // Anthropic requires ≥1 message, so inject a silent system-kick message.
        if (userMsg.isBlank() && onboardingContext.messages.isEmpty()) {
            val msgs  = listOf(mapOf<String, Any>("role" to "user", "content" to "Begin the onboarding."))
            val tools = allTools.filter { it.name in listOf("update_user_profile", "get_user_profile") }
            val result = callLLMWithTools(msgs, tools, ONBOARD_SYS)
            val reply  = result.text.trim()
            if (reply.isNotBlank()) onboardingContext.addAssistant(reply)
            return jsonResponse(mapOf("reply" to reply, "done" to false))
        }

        // Subsequent turns
        if (userMsg.isNotBlank()) onboardingContext.addUser(userMsg)
        val tools = allTools.filter { it.name in listOf("update_user_profile", "get_user_profile") }
        val msgs  = onboardingContext.snapshot().toMutableList()
        var reply = ""
        var done  = false

        for (step in 0..3) {
            val result = callLLMWithTools(msgs, tools, ONBOARD_SYS)
            if (result.text.isNotBlank()) reply += result.text
            if (result.done || result.toolCalls.isEmpty()) {
                onboardingContext.addAssistant(result.rawContent)
                break
            }
            msgs += mapOf("role" to "assistant", "content" to result.rawContent)
            onboardingContext.addAssistant(result.rawContent)
            for (tc in result.toolCalls) {
                val toolResult = try { executeTool(tc.name, tc.args) } catch (e: Exception) { "error: ${e.message}" }
                if (tc.name == "update_user_profile") {
                    val savedProfile = loadProfile()
                    done = savedProfile["onboarding_complete"] as? Boolean ?: false
                }
                val toolMsg = if (activeProvider == "anthropic") {
                    mapOf("role" to "user", "content" to listOf(
                        mapOf("type" to "tool_result", "tool_use_id" to tc.id, "content" to toolResult)
                    ))
                } else {
                    mapOf("role" to "tool", "tool_call_id" to tc.id, "name" to tc.name, "content" to toolResult)
                }
                msgs += toolMsg
                onboardingContext.addRawMessage(toolMsg)
            }
            if (done) break
        }

        return jsonResponse(mapOf("reply" to reply.trim(), "done" to done))
    }

    // ── Bridge setup — serves the Termux daemon shell script ─────────
    private fun handleBridgeSetup(): Response {
        val dirPath = try { bridgeDir.absolutePath } catch (_: Exception) { "/sdcard/voiceos_bridge" }
        val script = """#!/data/data/com.termux/files/usr/bin/bash
# VoiceOS Bridge Daemon
# Generated by VoiceOS — run with: bash ~/voiceos-bridge.sh
# Background: nohup bash ~/voiceos-bridge.sh > ~/voiceos-bridge.log 2>&1 &
BRIDGE_DIR="$dirPath"
mkdir -p "${'$'}BRIDGE_DIR"
echo "[VoiceOS Bridge] Started. Watching ${'$'}BRIDGE_DIR"
while true; do
    if [ -f "${'$'}BRIDGE_DIR/cmd.txt" ]; then
        CMD=$(cat "${'$'}BRIDGE_DIR/cmd.txt")
        WDIR=$(cat "${'$'}BRIDGE_DIR/workdir.txt" 2>/dev/null || echo "~")
        mv "${'$'}BRIDGE_DIR/cmd.txt" "${'$'}BRIDGE_DIR/cmd.txt.lock" 2>/dev/null || { sleep 0.5; continue; }
        EXPANDED=$(eval echo "${'$'}WDIR" 2>/dev/null || echo "${'$'}HOME")
        OUT=$(cd "${'$'}EXPANDED" 2>/dev/null && eval "${'$'}CMD" 2>&1 | head -c 8000)
        CODE=${'$'}?
        printf '%s' "${'$'}OUT" > "${'$'}BRIDGE_DIR/result.txt"
        printf '%d' "${'$'}CODE" > "${'$'}BRIDGE_DIR/exit_code.txt"
        rm -f "${'$'}BRIDGE_DIR/cmd.txt.lock"
        touch "${'$'}BRIDGE_DIR/done"
    fi
    sleep 0.5
done
""".trimIndent()
        return newFixedLengthResponse(Status.OK, "text/x-shellscript", script)
    }

    // ════════════════════════════════════════════════════════════════
    // Remaining API handlers
    // ════════════════════════════════════════════════════════════════

    private fun handleContacts(session: IHTTPSession): Response {
        val q = session.parameters["q"]?.firstOrNull() ?: return jsonResponse(emptyList<Any>())
        return try {
            val cur = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"), ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val contacts = mutableListOf<Map<String, String>>()
            cur?.use {
                while (it.moveToNext() && contacts.size < 8) {
                    contacts += mapOf("name" to (it.getString(0) ?: ""), "phone" to (it.getString(1) ?: ""))
                }
            }
            jsonResponse(contacts)
        } catch (e: Exception) { jsonResponse(emptyList<Any>()) }
    }

    private fun handleCall(session: IHTTPSession): Response {
        val number = parseBody(session)["number"] as? String ?: return jsonResponse(mapOf("error" to "missing number"), Status.BAD_REQUEST)
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleSms(session: IHTTPSession): Response {
        val body = parseBody(session)
        val number = body["number"] as? String ?: return jsonResponse(mapOf("error" to "missing number"), Status.BAD_REQUEST)
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("sms:${Uri.encode(number)}")).apply {
            (body["body"] as? String)?.let { putExtra("sms_body", it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleMaps(session: IHTTPSession): Response {
        val query = parseBody(session)["query"] as? String ?: return jsonResponse(mapOf("error" to "missing query"), Status.BAD_REQUEST)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return jsonResponse(mapOf("ok" to true))
    }

    // ── Task CRUD ─────────────────────────────────────────────────
    private fun handleGetTasks() = jsonResponse(loadTasks())

    private fun handleCreateTask(session: IHTTPSession): Response {
        val body     = parseBody(session)
        val title    = body["title"]    as? String ?: return jsonResponse(mapOf("error" to "missing title"), Status.BAD_REQUEST)
        val priority = body["priority"] as? String ?: "medium"
        val notes    = body["notes"]    as? String ?: ""
        val tasks    = loadTasks()
        val ts       = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        val task     = mutableMapOf<String, Any>(
            "id" to newTaskId(), "title" to title, "status" to "pending",
            "priority" to priority, "notes" to notes, "created" to ts, "updated" to ts
        )
        tasks.add(task); saveTasks(tasks)
        return jsonResponse(task)
    }

    private fun handleUpdateTask(session: IHTTPSession): Response {
        val body  = parseBody(session)
        val id    = body["id"] as? String ?: return jsonResponse(mapOf("error" to "missing id"), Status.BAD_REQUEST)
        val tasks = loadTasks()
        val task  = tasks.firstOrNull { it["id"] == id } ?: return jsonResponse(mapOf("error" to "not found"), Status.NOT_FOUND)
        val ts    = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        listOf("title", "status", "priority", "notes").forEach { key -> (body[key] as? String)?.let { task[key] = it } }
        task["updated"] = ts; saveTasks(tasks)
        return jsonResponse(task)
    }

    private fun handleDeleteTask(session: IHTTPSession): Response {
        val id = parseBody(session)["id"] as? String ?: return jsonResponse(mapOf("error" to "missing id"), Status.BAD_REQUEST)
        val tasks = loadTasks()
        saveTasks(tasks.also { it.removeAll { t -> t["id"] == id } })
        return jsonResponse(mapOf("ok" to true))
    }

    // ── Notifications ─────────────────────────────────────────────
    private fun isNotificationListenerEnabled(): Boolean {
        val cn   = android.content.ComponentName(context, VoiceOSNotificationService::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(cn.flattenToString())
    }

    private fun handleGetNotifications(): Response {
        val enabled = isNotificationListenerEnabled()
        return jsonResponse(mapOf("enabled" to enabled,
            "notifications" to if (enabled) VoiceOSNotificationService.getRecent() else emptyList<Any>()))
    }

    private fun handleDismissNotification(session: IHTTPSession): Response {
        val key = parseBody(session)["key"] as? String ?: return jsonResponse(mapOf("error" to "missing key"), Status.BAD_REQUEST)
        VoiceOSNotificationService.dismiss(key)
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleOpenNotifSettings(): Response {
        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return jsonResponse(mapOf("ok" to true))
    }

    // ── Ollama test ───────────────────────────────────────────────
    private fun handleOllamaTest(): Response {
        return try {
            val base = ollamaUrl.trimEnd('/')
            val conn = URL("$base/api/tags").openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000; conn.readTimeout = 8_000
            if (conn.responseCode != 200) return jsonResponse(mapOf("ok" to false, "error" to "HTTP ${conn.responseCode}"))
            @Suppress("UNCHECKED_CAST")
            val obj    = gson.fromJson(conn.inputStream.bufferedReader().readText(), Map::class.java) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val models = (obj["models"] as? List<*>)?.mapNotNull {
                @Suppress("UNCHECKED_CAST") (it as? Map<String, Any>)?.get("name") as? String
            } ?: emptyList<String>()
            jsonResponse(mapOf("ok" to true, "url" to base, "models" to models))
        } catch (e: Exception) { jsonResponse(mapOf("ok" to false, "error" to e.message)) }
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    private fun resolveContact(nameOrNumber: String): String? {
        if (nameOrNumber.any { it.isDigit() }) return nameOrNumber
        return try {
            val cur = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$nameOrNumber%"), null
            )
            var number: String? = null
            cur?.use { if (it.moveToFirst()) number = it.getString(0) }
            number
        } catch (_: Exception) { null }
    }

    private fun resolveContactName(number: String): String {
        return try {
            val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cur = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            var name: String? = null
            cur?.use { if (it.moveToFirst()) name = it.getString(0) }
            name ?: number
        } catch (_: Exception) { number }
    }

    private fun parseIsoTime(s: String): Long {
        val fmts = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        for (fmt in fmts) {
            try {
                return java.text.SimpleDateFormat(fmt, java.util.Locale.US).parse(s)?.time
                    ?: continue
            } catch (_: Exception) { continue }
        }
        return System.currentTimeMillis() + 3_600_000
    }

    private fun parseBody(session: IHTTPSession): Map<String, Any> {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(files["postData"] ?: "{}", Map::class.java) as Map<String, Any>
        } catch (_: Exception) { emptyMap() }
    }

    private fun jsonResponse(data: Any, status: Status = Status.OK): Response {
        val json = gson.toJson(data)
        val resp = newFixedLengthResponse(status, "application/json", json)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun ollamaHint(err: String): String {
        return if (err.contains("Connection refused") || err.contains("connect"))
            "Ollama not reachable at $ollamaUrl. Start Ollama or change the URL in settings."
        else "Ollama error: $err"
    }

    // ── Legacy chat helpers (for /api/chat) ───────────────────────
    private fun anthropicChat(prompt: String, key: String): String {
        return try {
            val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.doOutput = true; conn.connectTimeout = 10_000; conn.readTimeout = 60_000
            conn.outputStream.use { it.write(gson.toJson(mapOf(
                "model" to activeModel, "max_tokens" to 1024,
                "messages" to listOf(mapOf("role" to "user", "content" to prompt))
            )).toByteArray()) }
            if (conn.responseCode != 200) return "Anthropic error: ${conn.errorStream?.bufferedReader()?.readText()}"
            @Suppress("UNCHECKED_CAST")
            val obj = gson.fromJson(conn.inputStream.bufferedReader().readText(), Map::class.java) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            ((obj["content"] as? List<Map<String, Any>>)?.firstOrNull()?.get("text") as? String) ?: "(no response)"
        } catch (e: Exception) { "Anthropic request failed: ${e.message}" }
    }

    private fun openaiChat(prompt: String, key: String, url: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.doOutput = true; conn.connectTimeout = 10_000; conn.readTimeout = 60_000
            conn.outputStream.use { it.write(gson.toJson(mapOf(
                "model" to activeModel,
                "messages" to listOf(mapOf("role" to "user", "content" to prompt))
            )).toByteArray()) }
            if (conn.responseCode != 200) return "Error: ${conn.errorStream?.bufferedReader()?.readText()}"
            @Suppress("UNCHECKED_CAST")
            val obj = gson.fromJson(conn.inputStream.bufferedReader().readText(), Map::class.java) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            ((obj["choices"] as? List<Map<String, Any>>)?.firstOrNull()?.get("message") as? Map<String, Any>)?.get("content") as? String ?: "(no response)"
        } catch (e: Exception) { "Request failed: ${e.message}" }
    }

    private fun streamOllamaChat(prompt: String): Response {
        val pipeOut = PipedOutputStream()
        val pipeIn  = PipedInputStream(pipeOut, 32768)
        Thread {
            try {
                val conn = URL("${ollamaUrl.trimEnd('/')}/api/generate").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true; conn.connectTimeout = 8_000; conn.readTimeout = 300_000
                conn.outputStream.use { it.write(gson.toJson(mapOf("model" to activeModel, "prompt" to prompt, "stream" to true)).toByteArray()) }
                conn.inputStream.bufferedReader().use { br ->
                    br.lineSequence().forEach { line ->
                        if (line.isBlank()) return@forEach
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val obj = gson.fromJson(line, Map::class.java) as Map<String, Any>
                            val chunk = obj["response"] as? String ?: ""
                            if (chunk.isNotEmpty()) { pipeOut.write(chunk.toByteArray()); pipeOut.flush() }
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                try { pipeOut.write(ollamaHint("${e.javaClass.simpleName}: ${e.message}").toByteArray()) } catch (_: Exception) {}
            } finally { try { pipeOut.close() } catch (_: Exception) {} }
        }.apply { isDaemon = true }.start()
        return newChunkedResponse(Status.OK, "text/plain; charset=utf-8", pipeIn).also {
            it.addHeader("Access-Control-Allow-Origin", "*")
            it.addHeader("Cache-Control", "no-cache")
        }
    }
}

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
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Base64
import android.util.Log
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

    private fun apiKey(provider: String) = prefs.getString("key_$provider", "") ?: ""
    private fun saveKey(provider: String, key: String) =
        prefs.edit().putString("key_$provider", key).apply()

    // ── Subsystems ────────────────────────────────────────────────
    private val actionQueue by lazy { ActionQueue(context) }

    private val skillRegistry by lazy { buildSkillRegistry() }

    private val engine by lazy {
        AgentEngine(
            skillRegistry  = skillRegistry,
            allToolDefs    = allTools,
            executeTool    = ::executeTool,
            callLLM        = { msgs, toolDefs, sys -> callLLMWithTools(msgs, toolDefs, sys) },
            buildSysPrompt = { liveCtx, skillSuffix -> agentSystemPrompt(liveCtx, skillSuffix) }
        )
    }

    private val heartbeat by lazy {
        ProbeHeartbeat { probe ->
            val toolDefs = if (probe.toolNames.isEmpty()) allTools
                           else allTools.filter { it.name in probe.toolNames }
            val liveCtx  = try { buildLiveContext(minimal = true) } catch (_: Exception) { "" }
            val sysPrompt = agentSystemPrompt(liveCtx)
            val msgs = mutableListOf<Map<String, Any>>(
                mapOf("role" to "user", "content" to probe.prompt)
            )
            val sb = StringBuilder()
            for (step in 0..2) {
                val result = callLLMWithTools(msgs, toolDefs, sysPrompt)
                if (result.text.isNotBlank()) sb.append(result.text)
                if (result.done || result.toolCalls.isEmpty()) break
                msgs += mapOf("role" to "assistant", "content" to result.rawContent)
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
        }.also { hb ->
            hb.register(*ProbeHeartbeat.defaultProbes())
        }
    }

    // ── Unified LLM dispatch ──────────────────────────────────────
    private fun callLLMWithTools(
        messages: List<Map<String, Any>>,
        toolDefs: List<ToolDef>,
        sysPrompt: String
    ): AgentResult = when (activeProvider) {
        "anthropic" -> anthropicWithTools(messages, toolDefs, sysPrompt)
        "openai"    -> openaiWithTools(messages, toolDefs, "https://api.openai.com/v1/chat/completions", sysPrompt)
        "groq"      -> openaiWithTools(messages, toolDefs, "https://api.groq.com/openai/v1/chat/completions", sysPrompt)
        "ollama"    -> ollamaWithTools(messages, toolDefs, sysPrompt)
        else        -> AgentResult("Unknown provider: $activeProvider", toolCalls = emptyList(), done = true)
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
            "Add a new task to the task list",
            mapOf(
                "title"    to mapOf("type" to "string", "description" to "Task title"),
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

        // ── Memory ────────────────────────────────────────────────
        ToolDef("remember",
            "Store a note or piece of information for later recall",
            mapOf("note" to mapOf("type" to "string", "description" to "The information to store")),
            listOf("note")),

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
            listOf("destination"), requiresConfirm = true)
    )

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
                    "launch_app", "set_alarm", "get_battery", "get_volume",
                    "set_volume", "set_brightness", "toggle_wifi", "toggle_bluetooth",
                    "toggle_dnd", "get_device_info", "get_clipboard", "set_clipboard"
                ),
                triggerWords       = listOf(
                    "open", "launch", "start app", "volume", "brightness", "screen",
                    "wifi", "bluetooth", "airplane", "dnd", "do not disturb", "silent",
                    "battery", "clipboard", "copy", "paste"
                )
            ),
            SkillDef(
                name               = "web_research",
                description        = "Web search, information lookup, facts",
                systemPromptSuffix = """
## Active skill: Web Research
Search the web to answer the user's question. Always use web_search first.
Summarise results concisely — bullet points where appropriate.""",
                toolNames          = listOf("web_search", "remember", "recall"),
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
                toolNames          = listOf("remember", "recall"),
                triggerWords       = listOf(
                    "remember", "remind me", "note", "save this", "don't forget",
                    "recall", "what did i", "forgot", "memorize"
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
        activeProvider = map["provider"] as? String ?: activeProvider
        activeModel    = map["model"]    as? String ?: activeModel
        (map["ollama_url"] as? String)?.takeIf { it.isNotBlank() }?.let { ollamaUrl = it.trim() }
        return jsonResponse(mapOf(
            "provider" to activeProvider, "model" to activeModel,
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

        val pipeOut = PipedOutputStream()
        val pipeIn  = PipedInputStream(pipeOut, 65536)

        Thread {
            try {
                pipeOut.writer().use { writer ->
                    val liveCtx = try { buildLiveContext() } catch (_: Exception) { "" }
                    engine.run(
                        userMsg  = userMsg,
                        liveCtx  = liveCtx,
                        writer   = writer,
                        gson     = gson,
                        isAnthropic = activeProvider == "anthropic"
                    )
                }
            } catch (e: Exception) {
                try { pipeOut.write("Error: ${e.message}".toByteArray()) } catch (_: Exception) {}
            } finally {
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

    private fun handleHeartbeatControl(session: IHTTPSession): Response {
        val map = parseBody(session)
        when (map["action"] as? String) {
            "start" -> heartbeat.start()
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
                "list_tasks"        -> toolListTasks()
                "add_task"          -> toolAddTask(args)
                "update_task"       -> toolUpdateTask(args)
                "complete_task"     -> toolCompleteTask(args)
                // Memory
                "remember"          -> toolRemember(args)
                // Communication (queued)
                "call_contact"      -> toolCallContact(args)
                "send_sms"          -> toolSendSms(args)
                "draft_email"       -> toolDraftEmail(args)
                "send_whatsapp"     -> toolSendWhatsapp(args)
                "navigate"          -> toolNavigate(args)
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
        val tasks = loadTasks()
        if (tasks.isEmpty()) return "No tasks in the list."
        return tasks.joinToString("\n") { t ->
            "[${t["id"]}] [${t["priority"]}/${t["status"]}] ${t["title"]}${
                (t["notes"] as? String)?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
            }"
        }
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
        val ts    = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        task["status"] = "done"; task["updated"] = ts; saveTasks(tasks)
        return "Task \"${task["title"]}\" marked complete"
    }

    // ── Memory ────────────────────────────────────────────────────
    private fun toolRemember(args: Map<String, Any>): String {
        val note  = args["note"] as? String ?: return "Missing note"
        val notes = prefs.getString("agent_notes", "") ?: ""
        val ts    = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US).format(java.util.Date())
        prefs.edit().putString("agent_notes", "$notes\n[$ts] $note".trimStart()).apply()
        return "Remembered: $note"
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
        sysPrompt: String
    ): AgentResult {
        val key = apiKey("anthropic")
        if (key.isBlank()) return AgentResult("No Anthropic API key saved.", toolCalls = emptyList(), done = true)
        return try {
            val toolsJson = toolDefs.map { t -> mapOf(
                "name"         to t.name,
                "description"  to t.description,
                "input_schema" to mapOf("type" to "object", "properties" to t.params, "required" to t.required)
            )}
            val payload = gson.toJson(mapOf(
                "model" to activeModel, "max_tokens" to 4096,
                "system" to sysPrompt, "tools" to toolsJson, "messages" to messages
            ))
            val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
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
        sysPrompt: String
    ): AgentResult {
        val provName = if (url.contains("groq")) "groq" else "openai"
        val key = apiKey(provName)
        if (key.isBlank()) return AgentResult("No $provName API key saved.", toolCalls = emptyList(), done = true)
        return try {
            val toolsJson = toolDefs.map { t -> mapOf("type" to "function", "function" to mapOf(
                "name" to t.name, "description" to t.description,
                "parameters" to mapOf("type" to "object", "properties" to t.params, "required" to t.required)
            ))}
            val payload = gson.toJson(mapOf(
                "model" to activeModel, "tools" to toolsJson,
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
            AgentResult(text, rawContent = msg, toolCalls = calls, done = false)
        } catch (e: Exception) { AgentResult("$provName request failed: ${e.message}", toolCalls = emptyList(), done = true) }
    }

    private fun ollamaWithTools(
        messages: List<Map<String, Any>>,
        toolDefs: List<ToolDef>,
        sysPrompt: String
    ): AgentResult {
        val toolListStr = toolDefs.joinToString("\n") { t ->
            val params = t.params.entries.joinToString(", ") { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                "$k: ${(v as? Map<String, Any>)?.get("type") ?: "string"}"
            }
            "- ${t.name}($params): ${t.description}"
        }
        val systemPrompt = "$sysPrompt\n\nYou have access to tools. To use one output:\n<tool_call>{\"name\": \"tool_name\", \"args\": {\"param\": \"value\"}}</tool_call>\n\nAvailable tools:\n$toolListStr\n\nAfter tool results, continue reasoning. Give final answer without tool_call tags."
        val histStr = messages.joinToString("\n") { msg ->
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
            conn.doOutput = true; conn.connectTimeout = 8_000; conn.readTimeout = 300_000
            conn.outputStream.use { it.write(gson.toJson(mapOf(
                "model" to activeModel, "prompt" to "$systemPrompt\n\n$histStr\nASSISTANT:", "stream" to false
            )).toByteArray()) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                return AgentResult(ollamaHint(err), toolCalls = emptyList(), done = true)
            }
            @Suppress("UNCHECKED_CAST")
            val obj  = gson.fromJson(conn.inputStream.bufferedReader().readText(), Map::class.java) as Map<String, Any>
            val full = obj["response"] as? String ?: ""
            val matches = Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL).findAll(full).toList()
            if (matches.isEmpty()) return AgentResult(full.trim(), toolCalls = emptyList(), done = true)
            val calls = matches.mapNotNull { m ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    val tc = gson.fromJson(m.groupValues[1].trim(), Map::class.java) as Map<String, Any>
                    val name = tc["name"] as? String ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val args = tc["args"] as? Map<String, Any> ?: emptyMap()
                    ToolCall(id = "ollama_${System.currentTimeMillis()}", name = name, args = args)
                } catch (_: Exception) { null }
            }
            AgentResult(full.substringBefore("<tool_call>").trim(), rawContent = full, toolCalls = calls, done = false)
        } catch (e: Exception) { AgentResult(ollamaHint("${e.javaClass.simpleName}: ${e.message}"), toolCalls = emptyList(), done = true) }
    }

    // ════════════════════════════════════════════════════════════════
    // System prompt & live context
    // ════════════════════════════════════════════════════════════════
    private fun agentSystemPrompt(liveCtx: String = "", skillSuffix: String? = null): String {
        val ctx = if (liveCtx.isNotBlank()) "\n\n$liveCtx" else ""
        val skill = if (!skillSuffix.isNullOrBlank()) "\n\n$skillSuffix" else ""
        return """You are VoiceOS — an intelligent personal assistant running as the Android launcher on the user's phone.
You have direct access to the device: notifications, SMS, call log, calendar, contacts, apps, tasks, battery, and the web.
Be concise and action-oriented. ALWAYS use tools to answer questions about device state.
For outgoing actions: draft_email / send_sms / send_whatsapp queue the action for user approval — never open apps directly for these.
call_contact and navigate open the relevant app directly.$ctx$skill"""
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
            // Pending queue
            val pending = actionQueue.getPending()
            if (pending.isNotEmpty()) {
                sb.appendLine("  Queued actions (${pending.size} awaiting approval):")
                pending.take(3).forEach { sb.appendLine("    • [${it.type}] ${it.preview}") }
            }
        }
        sb.append("╚════════════════════════════════════════════════════════╝")
        return sb.toString()
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

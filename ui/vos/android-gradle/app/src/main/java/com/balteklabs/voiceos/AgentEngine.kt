package com.balteklabs.voiceos

/**
 * AgentEngine.kt — Kotlin port of ui/vos/src/agent/engine.py
 *
 * Provides:
 *   - ToolDef / ToolCall / AgentResult  (shared data classes)
 *   - SkillDef + SkillRegistry          (skill routing, tool filtering)
 *   - ConversationContext               (rolling message history)
 *   - AgentEngine                       (ReAct loop with skill routing)
 */

// ── Shared data classes ────────────────────────────────────────────────

data class ToolDef(
    val name: String,
    val description: String,
    val params: Map<String, Any>,
    val required: List<String> = emptyList(),
    val requiresConfirm: Boolean = false
)

data class ToolCall(val id: String, val name: String, val args: Map<String, Any>)

data class AgentResult(
    val text: String,
    val rawContent: Any = text,
    val toolCalls: List<ToolCall>,
    val done: Boolean,
    /** True when the provider already streamed tokens directly to the frontend writer.
     *  AgentEngine skips re-writing result.text to avoid duplicating output. */
    val alreadyStreamed: Boolean = false
)

// ── Skill definitions (port of engine.py SkillDef) ────────────────────

data class SkillDef(
    val name: String,
    val description: String,
    /** Appended to the base system prompt when this skill is active. */
    val systemPromptSuffix: String,
    /** Tool names available to this skill. Empty = all tools. */
    val toolNames: List<String> = emptyList(),
    val triggerWords: List<String>
)

class SkillRegistry {
    private val skills = mutableListOf<SkillDef>()

    fun register(vararg defs: SkillDef) { skills.addAll(defs) }

    /**
     * Match skill by counting trigger-word hits — mirrors engine.py skill.match().
     * Returns null if no trigger words match.
     */
    fun match(input: String): SkillDef? {
        val lower = input.lowercase()
        return skills
            .associateWith { skill -> skill.triggerWords.count { lower.contains(it) } }
            .filter { (_, hits) -> hits > 0 }
            .maxByOrNull { (_, hits) -> hits }
            ?.key
    }

    fun list(): List<Map<String, String>> =
        skills.map { mapOf("name" to it.name, "description" to it.description) }
}

// ── Conversation context (port of engine.py ConversationContext) ───────

class ConversationContext(val maxMessages: Int = 24) {
    private val _messages = mutableListOf<Map<String, Any>>()
    val messages: List<Map<String, Any>> get() = synchronized(this) { _messages.toList() }

    fun addUser(content: String) = synchronized(this) {
        _messages += mapOf("role" to "user", "content" to content)
        trim()
    }

    fun addAssistant(content: Any) = synchronized(this) {
        _messages += mapOf("role" to "assistant", "content" to content)
        trim()
    }

    fun addToolResult(id: String, name: String, result: String) = synchronized(this) {
        _messages += mapOf("role" to "tool", "tool_call_id" to id, "name" to name, "content" to result)
        trim()
    }

    fun addRawMessage(msg: Map<String, Any>) = synchronized(this) {
        _messages += msg
        trim()
    }

    fun snapshot(): List<Map<String, Any>> = synchronized(this) { _messages.toList() }
    fun clear()                             = synchronized(this) { _messages.clear() }

    private fun trim() {
        while (_messages.size > maxMessages) {
            _messages.removeAt(0)
            // Remove orphaned tool-result messages that lost their assistant tool_use
            while (_messages.isNotEmpty()) {
                val first = _messages[0]
                val role = first["role"] as? String
                if (role == "tool") {
                    _messages.removeAt(0)
                } else if (role == "user") {
                    val content = first["content"]
                    if (content is List<*> && content.firstOrNull().let {
                            it is Map<*, *> && it["type"] == "tool_result"
                        }) {
                        _messages.removeAt(0)
                    } else {
                        break
                    }
                } else {
                    break
                }
            }
        }
    }
}

// ── Agent engine (port of engine.py AgentEngine) ──────────────────────

/**
 * Wraps the ReAct loop with skill-based tool filtering and system prompt augmentation.
 *
 * @param skillRegistry   Skill matcher — selects skill from user input
 * @param allToolDefs     Full tool list; filtered down per skill before LLM call
 * @param executeTool     Executes a named tool and returns its result string
 * @param callLLM         Provider-dispatched LLM call; returns AgentResult
 * @param buildSysPrompt  Builds the system prompt (with optional skill suffix appended)
 * @param onSkillMatch    Optional callback — fired when a skill is routed
 * @param onToolBadge     Optional callback — fired with tool name before execution (for streaming)
 */
class AgentEngine(
    val skillRegistry: SkillRegistry,
    private val allToolDefs: List<ToolDef>,
    private val executeTool: (name: String, args: Map<String, Any>) -> String,
    private val callLLM: (messages: List<Map<String, Any>>, tools: List<ToolDef>, sysPrompt: String) -> AgentResult,
    private val buildSysPrompt: (liveCtx: String, skillSuffix: String?) -> String,
    val onSkillMatch: ((skillName: String) -> Unit)? = null,
    val onToolBadge: ((name: String, args: Map<String, Any>) -> Unit)? = null
) {
    val context = ConversationContext()

    companion object {
        const val MAX_STEPS = 8
    }

    /**
     * Run the full ReAct loop for one user turn.
     * Streams text + tool badges to [writer].
     * Returns the final answer text.
     */
    fun run(
        userMsg: String,
        liveCtx: String,
        writer: java.io.Writer,
        gson: com.google.gson.Gson,
        isAnthropic: Boolean
    ): String {
        // ── Skill routing ──────────────────────────────────────────
        val skill = skillRegistry.match(userMsg)
        if (skill != null) {
            onSkillMatch?.invoke(skill.name)
            writer.write("\n[SKILL:${skill.name}]\n")
            writer.flush()
        }

        // ── Tool filtering (port of engine.py skill tool filtering) ──
        val toolDefs = if (skill != null && skill.toolNames.isNotEmpty()) {
            val allowed = skill.toolNames.toSet()
            allToolDefs.filter { it.name in allowed }
        } else {
            allToolDefs
        }

        // ── System prompt ──────────────────────────────────────────
        val sysPrompt = buildSysPrompt(liveCtx, skill?.systemPromptSuffix)

        // ── Add user turn to context ───────────────────────────────
        context.addUser(userMsg)
        val messages = context.snapshot().toMutableList()

        var finalText = ""

        for (step in 0 until MAX_STEPS) {
            val result = callLLM(messages, toolDefs, sysPrompt)

            // Only write if the provider hasn't already streamed tokens directly
            if (result.text.isNotBlank() && !result.alreadyStreamed) {
                writer.write(result.text)
                writer.flush()
            }

            if (result.done || result.toolCalls.isEmpty()) {
                finalText = result.text
                context.addAssistant(finalText)
                break
            }

            // Execute tool calls
            val toolResults = mutableListOf<Map<String, Any>>()
            for (tc in result.toolCalls) {
                val toolDef = toolDefs.find { it.name == tc.name }
                if (toolDef == null) {
                    toolResults += mapOf("id" to tc.id, "name" to tc.name, "result" to "Unknown tool: ${tc.name}")
                    continue
                }
                if (toolDef.requiresConfirm) {
                    writer.write("\n[CONFIRM:${tc.name}:${gson.toJson(tc.args)}]\n")
                    writer.flush()
                }
                writer.write("\n[TOOL:${tc.name}]\n")
                writer.flush()
                onToolBadge?.invoke(tc.name, tc.args)
                val execResult = executeTool(tc.name, tc.args)
                writer.write("[RESULT:${execResult.take(120)}]\n")
                writer.flush()
                toolResults += mapOf("id" to tc.id, "name" to tc.name, "result" to execResult)
            }

            // Append to working message list and context
            messages += mapOf("role" to "assistant", "content" to result.rawContent)
            context.addAssistant(result.rawContent)

            if (isAnthropic) {
                val toolMsg = mapOf(
                    "role" to "user",
                    "content" to toolResults.map { tr ->
                        mapOf("type" to "tool_result", "tool_use_id" to tr["id"], "content" to tr["result"])
                    }
                )
                messages += toolMsg
                context.addRawMessage(toolMsg)
            } else {
                for (tr in toolResults) {
                    val toolMsg = mapOf(
                        "role" to "tool",
                        "tool_call_id" to tr["id"]!!,
                        "name" to tr["name"]!!,
                        "content" to tr["result"]!!
                    )
                    messages += toolMsg
                    context.addToolResult(tr["id"] as String, tr["name"] as String, tr["result"] as String)
                }
            }
        }

        return finalText
    }
}

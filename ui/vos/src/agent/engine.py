"""
Agent Engine — the core agentic loop.

Implements an OpenClaw-style agent that:
1. Selects the best skill for the user's input
2. Builds the system prompt (base + skill)
3. Calls the LLM with the full tool set
4. Executes tool calls in a loop until the LLM gives a final answer
5. Streams the final response to the event bus

Tool-call loop depth is bounded (default: 10 iterations) to prevent runaway agents.
"""

import json
import logging
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from llm.providers.base import LLMResponse, Message, ToolCall, ToolDefinition
from llm.providers.switcher import LLMSwitcher
from skills.registry import SkillRegistry
from tools.registry import ToolRegistry

logger = logging.getLogger(__name__)

BASE_SYSTEM_PROMPT = """You are VoiceOS, an intelligent Linux operating system assistant
embedded in the Baltek DTE environment. You have direct access to the kernel through
a rich set of tools: shell execution, filesystem access, kernel interfaces (dmesg, procfs,
sysfs, sysctl), HTTP requests, and persistent memory.

When you need information or need to act, USE YOUR TOOLS — don't guess.
Always run the relevant tool first, then interpret the results for the user.

Rules:
- Be concise and precise. No filler text.
- Show relevant command output when useful.
- If a tool fails, explain why and try an alternative approach.
- For kernel/system tasks, use the kernel-specific tools over raw shell when possible.
"""

MAX_TOOL_ITERATIONS = 10


@dataclass
class AgentResponse:
    text: str
    tool_calls_made: int = 0
    skill_used: Optional[str] = None
    provider_used: Optional[str] = None
    error: Optional[str] = None


@dataclass
class ConversationContext:
    """Rolling conversation history."""
    messages: List[Message] = field(default_factory=list)
    max_messages: int = 20  # keep last N messages

    def add(self, role: str, content: str, **kwargs) -> None:
        self.messages.append(Message(role=role, content=content, **kwargs))
        # Trim to max_messages (keep system-role messages)
        if len(self.messages) > self.max_messages:
            self.messages = self.messages[-self.max_messages:]

    def add_tool_result(self, tool_call_id: str, content: str, name: str) -> None:
        self.messages.append(Message(
            role="tool",
            content=content,
            tool_call_id=tool_call_id,
            name=name,
        ))

    def add_assistant_tool_calls(self, content: str, tool_calls: List[ToolCall]) -> None:
        """Add an assistant message that contains tool calls."""
        tc_dicts = [
            {"id": tc.id, "name": tc.name, "arguments": tc.arguments}
            for tc in tool_calls
        ]
        self.messages.append(Message(
            role="assistant",
            content=content,
            tool_calls=tc_dicts,
        ))


class AgentEngine:
    """
    Core agent loop — connects LLM switcher, tools, and skills.
    """

    def __init__(
        self,
        llm_switcher: LLMSwitcher,
        tool_registry: ToolRegistry,
        skill_registry: SkillRegistry,
        on_stream: Optional[Callable[[str], None]] = None,
        on_tool_call: Optional[Callable[[str, Dict], None]] = None,
        on_tool_result: Optional[Callable[[str, str], None]] = None,
        on_skill_selected: Optional[Callable[[str], None]] = None,
        on_provider_switch: Optional[Callable[[str, str], None]] = None,
    ):
        self._llm = llm_switcher
        self._tools = tool_registry
        self._skills = skill_registry
        self._on_stream = on_stream
        self._on_tool_call = on_tool_call
        self._on_tool_result = on_tool_result
        self._on_skill_selected = on_skill_selected
        self._on_provider_switch = on_provider_switch
        self._context = ConversationContext()

    async def process(self, user_input: str) -> AgentResponse:
        """
        Main entry point — process a user message through the full agent loop.
        """
        # --- Handle LLM swap commands directly ---
        swap_result = self._handle_llm_swap(user_input)
        if swap_result:
            return swap_result

        # --- Select skill ---
        skill = self._skills.match(user_input)
        skill_name = skill.name if skill else None
        if skill and self._on_skill_selected:
            self._on_skill_selected(skill.name)

        # --- Build system prompt ---
        system_prompt = BASE_SYSTEM_PROMPT
        if skill:
            system_prompt += f"\n\n## Active Skill: {skill.name}\n{skill.system_prompt}"

        # --- Get tools (filtered by skill if specified) ---
        if skill and skill.tools:
            # Only include tools the skill asked for + memory (always available)
            requested = set(skill.tools) | {"memory"}
            tool_defs = [t for t in self._tools.get_definitions() if t.name in requested]
        else:
            tool_defs = self._tools.get_definitions()

        # --- Add user message to context ---
        self._context.add("user", user_input)

        # --- Run the tool-calling loop ---
        tool_calls_made = 0
        final_text = ""
        error = None

        for iteration in range(MAX_TOOL_ITERATIONS):
            provider = self._llm.active_provider
            if not provider:
                error = "No LLM provider available"
                break

            try:
                # Use tool calling if provider supports it and tools are available
                if provider.supports_tools() and tool_defs:
                    response: LLMResponse = await self._llm.generate(
                        messages=self._context.messages,
                        tools=tool_defs,
                        system=system_prompt,
                    )
                else:
                    # Streaming mode (no tool use)
                    chunks = []
                    async for chunk in self._llm.generate_stream(
                        messages=self._context.messages,
                        system=system_prompt,
                    ):
                        chunks.append(chunk)
                        if self._on_stream:
                            self._on_stream(chunk)
                    final_text = "".join(chunks)
                    self._context.add("assistant", final_text)
                    break

                # If LLM wants to call tools
                if response.tool_calls:
                    # Add assistant message with tool calls to context
                    self._context.add_assistant_tool_calls(
                        response.content, response.tool_calls
                    )

                    # Execute all tool calls
                    for tc in response.tool_calls:
                        tool_calls_made += 1
                        logger.info("Tool call: %s(%s)", tc.name, tc.arguments)

                        if self._on_tool_call:
                            self._on_tool_call(tc.name, tc.arguments)

                        result = await self._tools.execute(tc.name, **tc.arguments)

                        if self._on_tool_result:
                            self._on_tool_result(tc.name, str(result))

                        logger.debug("Tool result for %s: success=%s", tc.name, result.success)

                        self._context.add_tool_result(
                            tool_call_id=tc.id,
                            content=str(result),
                            name=tc.name,
                        )
                    # Continue loop — LLM needs to process tool results
                    continue

                # LLM gave a final text response
                final_text = response.content
                self._context.add("assistant", final_text)

                if self._on_stream:
                    self._on_stream(final_text)
                break

            except Exception as e:
                logger.error("Agent loop error (iteration %d): %s", iteration, e, exc_info=True)
                error = str(e)
                break
        else:
            logger.warning("Agent reached max tool iterations (%d)", MAX_TOOL_ITERATIONS)
            error = f"Reached max tool iterations ({MAX_TOOL_ITERATIONS})"

        return AgentResponse(
            text=final_text,
            tool_calls_made=tool_calls_made,
            skill_used=skill_name,
            provider_used=self._llm.active_name,
            error=error,
        )

    def _handle_llm_swap(self, user_input: str) -> Optional[AgentResponse]:
        """
        Detect and handle LLM swap commands inline without calling the LLM.
        Examples: "use ollama", "switch to claude", "use gpt-4o-mini"
        """
        lower = user_input.lower().strip()

        # Map keywords to (provider, model)
        swap_map = {
            # Ollama
            "use ollama": ("ollama", None),
            "switch to ollama": ("ollama", None),
            "use mistral": ("ollama", "mistral:latest"),
            "use llama3": ("ollama", "llama3.1:latest"),
            "use llama": ("ollama", "llama3.1:latest"),
            "use codellama": ("ollama", "codellama:latest"),
            "use phi": ("ollama", "phi:latest"),
            "use mixtral": ("ollama", "mixtral:latest"),
            # OpenAI
            "use openai": ("openai", None),
            "use gpt": ("openai", None),
            "use gpt-4o": ("openai", "gpt-4o"),
            "use gpt-4o-mini": ("openai", "gpt-4o-mini"),
            "use o1": ("openai", "o1"),
            # Anthropic
            "use anthropic": ("anthropic", None),
            "use claude": ("anthropic", "claude-sonnet-4-6"),
            "use claude opus": ("anthropic", "claude-opus-4-6"),
            "use claude sonnet": ("anthropic", "claude-sonnet-4-6"),
            "use claude haiku": ("anthropic", "claude-haiku-4-5-20251001"),
            "switch to claude": ("anthropic", "claude-sonnet-4-6"),
            # Groq
            "use groq": ("groq", None),
            "switch to groq": ("groq", None),
            "use gemma": ("groq", "gemma2-9b-it"),
        }

        for trigger, (provider, model) in swap_map.items():
            if trigger in lower:
                try:
                    self._llm.use(provider, model)
                    status = self._llm.status()
                    msg = (
                        f"Switched to {status['active_provider']} / {status['active_model']}. "
                        f"Tool calling: {'yes' if status['supports_tools'] else 'no'}."
                    )
                    if self._on_stream:
                        self._on_stream(msg)
                    if self._on_provider_switch:
                        self._on_provider_switch(provider, model or "default")
                    return AgentResponse(
                        text=msg,
                        provider_used=status["active_provider"],
                    )
                except KeyError:
                    msg = f"Provider '{provider}' not registered. Available: {self._llm.list_providers()}"
                    if self._on_stream:
                        self._on_stream(msg)
                    return AgentResponse(text=msg, error=f"Unknown provider: {provider}")

        return None

    def clear_context(self) -> None:
        """Reset conversation history."""
        self._context = ConversationContext()

    def get_status(self) -> Dict[str, Any]:
        return {
            "llm": self._llm.status(),
            "tools": self._tools.list_tools(),
            "skills": self._skills.list_skills(),
            "context_messages": len(self._context.messages),
        }

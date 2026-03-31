"""
Anthropic (Claude) LLM provider with full tool-use support.
"""

import json
import logging
from typing import Any, AsyncGenerator, Dict, List, Optional

from llm.providers.base import (
    BaseLLMProvider,
    LLMResponse,
    Message,
    ToolCall,
    ToolDefinition,
)

logger = logging.getLogger(__name__)


class AnthropicProvider(BaseLLMProvider):
    name = "anthropic"

    def __init__(
        self,
        model: str = "claude-sonnet-4-6",
        api_key: Optional[str] = None,
        max_tokens: int = 4096,
    ):
        self._model = model
        self._api_key = api_key
        self._max_tokens = max_tokens
        self._client = None

    async def initialize(self) -> None:
        try:
            import anthropic  # type: ignore

            kwargs: Dict[str, Any] = {}
            if self._api_key:
                kwargs["api_key"] = self._api_key

            self._client = anthropic.AsyncAnthropic(**kwargs)
            logger.info("AnthropicProvider initialized with model=%s", self._model)
        except ImportError:
            raise RuntimeError(
                "anthropic package not installed. Run: pip install anthropic"
            )

    async def cleanup(self) -> None:
        pass  # Anthropic client has no explicit close

    @property
    def model(self) -> str:
        return self._model

    def set_model(self, model: str) -> None:
        self._model = model

    def supports_tools(self) -> bool:
        return True

    async def list_models(self) -> List[str]:
        return [
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "claude-haiku-4-5-20251001",
        ]

    def _to_anthropic_messages(self, messages: List[Message]) -> List[Dict]:
        result = []
        for m in messages:
            if m.role == "system":
                continue  # system is passed separately
            if m.role == "tool":
                result.append(
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "tool_result",
                                "tool_use_id": m.tool_call_id,
                                "content": m.content,
                            }
                        ],
                    }
                )
            elif m.tool_calls:
                content = []
                if m.content:
                    content.append({"type": "text", "text": m.content})
                for tc in m.tool_calls:
                    content.append(
                        {
                            "type": "tool_use",
                            "id": tc["id"],
                            "name": tc["name"],
                            "input": tc.get("arguments", {}),
                        }
                    )
                result.append({"role": "assistant", "content": content})
            else:
                result.append({"role": m.role, "content": m.content or ""})
        return result

    def _to_anthropic_tools(self, tools: List[ToolDefinition]) -> List[Dict]:
        return [
            {
                "name": t.name,
                "description": t.description,
                "input_schema": t.parameters,
            }
            for t in tools
        ]

    async def generate(
        self,
        messages: List[Message],
        tools: Optional[List[ToolDefinition]] = None,
        system: Optional[str] = None,
        temperature: float = 0.7,
        max_tokens: int = 4096,
    ) -> LLMResponse:
        if not self._client:
            raise RuntimeError("AnthropicProvider not initialized")

        kwargs: Dict[str, Any] = {
            "model": self._model,
            "messages": self._to_anthropic_messages(messages),
            "max_tokens": max_tokens,
            "temperature": temperature,
        }
        if system:
            kwargs["system"] = system
        if tools:
            kwargs["tools"] = self._to_anthropic_tools(tools)

        resp = await self._client.messages.create(**kwargs)

        text_content = ""
        tool_calls = []

        for block in resp.content:
            if block.type == "text":
                text_content += block.text
            elif block.type == "tool_use":
                tool_calls.append(
                    ToolCall(
                        id=block.id,
                        name=block.name,
                        arguments=block.input or {},
                    )
                )

        return LLMResponse(
            content=text_content,
            tool_calls=tool_calls,
            model=self._model,
            finish_reason=(
                "tool_calls"
                if tool_calls
                else ("length" if resp.stop_reason == "max_tokens" else "stop")
            ),
        )

    async def generate_stream(
        self,
        messages: List[Message],
        system: Optional[str] = None,
        temperature: float = 0.7,
    ) -> AsyncGenerator[str, None]:
        if not self._client:
            raise RuntimeError("AnthropicProvider not initialized")

        kwargs: Dict[str, Any] = {
            "model": self._model,
            "messages": self._to_anthropic_messages(messages),
            "max_tokens": self._max_tokens,
            "temperature": temperature,
        }
        if system:
            kwargs["system"] = system

        async with self._client.messages.stream(**kwargs) as stream:
            async for text in stream.text_stream:
                yield text

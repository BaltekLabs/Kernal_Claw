"""
OpenAI-compatible LLM provider.
Works with OpenAI API and any OpenAI-compatible endpoint (vLLM, LM Studio, etc.).
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


class OpenAIProvider(BaseLLMProvider):
    name = "openai"

    def __init__(
        self,
        model: str = "gpt-4o-mini",
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,  # override for compatible endpoints
    ):
        self._model = model
        self._api_key = api_key
        self._base_url = base_url  # None = use default OpenAI endpoint
        self._client = None

    async def initialize(self) -> None:
        try:
            from openai import AsyncOpenAI  # type: ignore

            kwargs: Dict[str, Any] = {}
            if self._api_key:
                kwargs["api_key"] = self._api_key
            if self._base_url:
                kwargs["base_url"] = self._base_url

            self._client = AsyncOpenAI(**kwargs)
            logger.info("OpenAIProvider initialized with model=%s", self._model)
        except ImportError:
            raise RuntimeError(
                "openai package not installed. Run: pip install openai"
            )

    async def cleanup(self) -> None:
        if self._client:
            await self._client.close()

    @property
    def model(self) -> str:
        return self._model

    def set_model(self, model: str) -> None:
        self._model = model

    def supports_tools(self) -> bool:
        return True

    async def list_models(self) -> List[str]:
        if not self._client:
            return []
        models = await self._client.models.list()
        return [m.id for m in models.data]

    def _to_openai_messages(
        self, messages: List[Message], system: Optional[str]
    ) -> List[Dict]:
        result = []
        if system:
            result.append({"role": "system", "content": system})
        for m in messages:
            msg: Dict[str, Any] = {"role": m.role, "content": m.content or ""}
            if m.tool_call_id:
                msg["role"] = "tool"
                msg["tool_call_id"] = m.tool_call_id
                if m.name:
                    msg["name"] = m.name
            if m.tool_calls:
                msg["tool_calls"] = m.tool_calls
            result.append(msg)
        return result

    def _to_openai_tools(self, tools: List[ToolDefinition]) -> List[Dict]:
        return [
            {
                "type": "function",
                "function": {
                    "name": t.name,
                    "description": t.description,
                    "parameters": t.parameters,
                },
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
            raise RuntimeError("OpenAIProvider not initialized")

        kwargs: Dict[str, Any] = {
            "model": self._model,
            "messages": self._to_openai_messages(messages, system),
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        if tools:
            kwargs["tools"] = self._to_openai_tools(tools)
            kwargs["tool_choice"] = "auto"

        resp = await self._client.chat.completions.create(**kwargs)
        choice = resp.choices[0]
        msg = choice.message

        tool_calls = []
        if msg.tool_calls:
            for tc in msg.tool_calls:
                args = tc.function.arguments
                if isinstance(args, str):
                    try:
                        args = json.loads(args)
                    except json.JSONDecodeError:
                        args = {}
                tool_calls.append(
                    ToolCall(id=tc.id, name=tc.function.name, arguments=args)
                )

        return LLMResponse(
            content=msg.content or "",
            tool_calls=tool_calls,
            model=self._model,
            finish_reason=choice.finish_reason or "stop",
        )

    async def generate_stream(
        self,
        messages: List[Message],
        system: Optional[str] = None,
        temperature: float = 0.7,
    ) -> AsyncGenerator[str, None]:
        if not self._client:
            raise RuntimeError("OpenAIProvider not initialized")

        stream = await self._client.chat.completions.create(
            model=self._model,
            messages=self._to_openai_messages(messages, system),
            temperature=temperature,
            stream=True,
        )
        async for chunk in stream:
            delta = chunk.choices[0].delta
            if delta.content:
                yield delta.content

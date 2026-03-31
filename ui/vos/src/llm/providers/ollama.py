"""
Ollama LLM provider — wraps existing OllamaClient with tool-call support.
Uses /api/chat for tool use (Ollama >= 0.3 models like llama3.1, mistral-nemo).
"""

import json
import logging
from typing import Any, AsyncGenerator, Dict, List, Optional

import aiohttp

from llm.providers.base import (
    BaseLLMProvider,
    LLMResponse,
    Message,
    ToolCall,
    ToolDefinition,
)

logger = logging.getLogger(__name__)


class OllamaProvider(BaseLLMProvider):
    name = "ollama"

    def __init__(
        self,
        model: str = "mistral:latest",
        base_url: str = "http://localhost:11434",
    ):
        self._model = model
        self.base_url = base_url
        self._session: Optional[aiohttp.ClientSession] = None

    async def initialize(self) -> None:
        self._session = aiohttp.ClientSession()
        try:
            async with self._session.get(f"{self.base_url}/api/tags") as resp:
                if resp.status != 200:
                    raise RuntimeError(f"Ollama not reachable at {self.base_url}")
            logger.info("OllamaProvider initialized with model=%s", self._model)
        except Exception as e:
            await self._session.close()
            raise

    async def cleanup(self) -> None:
        if self._session:
            await self._session.close()

    @property
    def model(self) -> str:
        return self._model

    def set_model(self, model: str) -> None:
        self._model = model

    def supports_tools(self) -> bool:
        # Ollama supports tools for llama3.1+, mistral-nemo, etc.
        return True

    async def list_models(self) -> List[str]:
        if not self._session:
            return []
        async with self._session.get(f"{self.base_url}/api/tags") as resp:
            data = await resp.json()
            return [m["name"] for m in data.get("models", [])]

    def _messages_to_ollama(self, messages: List[Message]) -> List[Dict]:
        result = []
        for m in messages:
            msg: Dict[str, Any] = {"role": m.role, "content": m.content or ""}
            if m.tool_calls:
                msg["tool_calls"] = m.tool_calls
            if m.tool_call_id:
                msg["role"] = "tool"
            result.append(msg)
        return result

    def _tools_to_ollama(self, tools: List[ToolDefinition]) -> List[Dict]:
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
        if not self._session:
            raise RuntimeError("OllamaProvider not initialized")

        chat_messages = []
        if system:
            chat_messages.append({"role": "system", "content": system})
        chat_messages.extend(self._messages_to_ollama(messages))

        payload: Dict[str, Any] = {
            "model": self._model,
            "messages": chat_messages,
            "stream": False,
            "options": {"temperature": temperature},
        }
        if tools:
            payload["tools"] = self._tools_to_ollama(tools)

        async with self._session.post(
            f"{self.base_url}/api/chat", json=payload
        ) as resp:
            data = await resp.json()

        msg = data.get("message", {})
        content = msg.get("content", "")
        raw_tool_calls = msg.get("tool_calls", [])

        tool_calls = []
        for tc in raw_tool_calls:
            fn = tc.get("function", {})
            args = fn.get("arguments", {})
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except json.JSONDecodeError:
                    args = {}
            tool_calls.append(
                ToolCall(
                    id=tc.get("id", fn.get("name", "call")),
                    name=fn.get("name", ""),
                    arguments=args,
                )
            )

        return LLMResponse(
            content=content,
            tool_calls=tool_calls,
            model=self._model,
            finish_reason="tool_calls" if tool_calls else "stop",
        )

    async def generate_stream(
        self,
        messages: List[Message],
        system: Optional[str] = None,
        temperature: float = 0.7,
    ) -> AsyncGenerator[str, None]:
        if not self._session:
            raise RuntimeError("OllamaProvider not initialized")

        chat_messages = []
        if system:
            chat_messages.append({"role": "system", "content": system})
        chat_messages.extend(self._messages_to_ollama(messages))

        payload = {
            "model": self._model,
            "messages": chat_messages,
            "stream": True,
            "options": {"temperature": temperature},
        }

        async with self._session.post(
            f"{self.base_url}/api/chat", json=payload
        ) as resp:
            async for line in resp.content:
                line = line.strip()
                if not line:
                    continue
                try:
                    data = json.loads(line)
                    chunk = data.get("message", {}).get("content", "")
                    if chunk:
                        yield chunk
                except json.JSONDecodeError:
                    pass

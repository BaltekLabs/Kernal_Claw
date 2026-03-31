"""
LLM Switcher — runtime hot-swap between providers and models.
Emits events so the UI can reflect the active provider/model.
"""

import logging
from typing import Any, AsyncGenerator, Dict, List, Optional

from llm.providers.base import (
    BaseLLMProvider,
    LLMResponse,
    Message,
    ToolDefinition,
)

logger = logging.getLogger(__name__)


class LLMSwitcher:
    """
    Manages multiple providers and allows hot-swapping at runtime.

    Usage:
        switcher = LLMSwitcher()
        switcher.register("ollama", OllamaProvider(...))
        switcher.register("openai", OpenAIProvider(...))
        await switcher.initialize_all()

        # swap provider
        switcher.use("openai")
        response = await switcher.generate(messages)
    """

    def __init__(self, event_bus=None):
        self._providers: Dict[str, BaseLLMProvider] = {}
        self._active: Optional[str] = None
        self._event_bus = event_bus

    def register(self, name: str, provider: BaseLLMProvider) -> None:
        self._providers[name] = provider
        if self._active is None:
            self._active = name

    async def initialize_all(self) -> None:
        for name, provider in self._providers.items():
            try:
                await provider.initialize()
                logger.info("Provider '%s' initialized", name)
            except Exception as e:
                logger.warning("Provider '%s' failed to initialize: %s", name, e)

    async def initialize_provider(self, name: str) -> None:
        if name not in self._providers:
            raise KeyError(f"Unknown provider: {name}")
        await self._providers[name].initialize()

    async def cleanup_all(self) -> None:
        for name, provider in self._providers.items():
            try:
                await provider.cleanup()
            except Exception as e:
                logger.warning("Error cleaning up provider '%s': %s", name, e)

    def use(self, provider_name: str, model: Optional[str] = None) -> None:
        if provider_name not in self._providers:
            raise KeyError(f"Unknown provider: {provider_name}")
        self._active = provider_name
        if model:
            p = self._providers[provider_name]
            if hasattr(p, "set_model"):
                p.set_model(model)
        logger.info(
            "Switched to provider='%s' model='%s'",
            provider_name,
            self.active_provider.model if self.active_provider else "?",
        )

    @property
    def active_provider(self) -> Optional[BaseLLMProvider]:
        if self._active and self._active in self._providers:
            return self._providers[self._active]
        return None

    @property
    def active_name(self) -> Optional[str]:
        return self._active

    def list_providers(self) -> List[str]:
        return list(self._providers.keys())

    async def list_models(self, provider: Optional[str] = None) -> List[str]:
        name = provider or self._active
        if name and name in self._providers:
            return await self._providers[name].list_models()
        return []

    def status(self) -> Dict[str, Any]:
        p = self.active_provider
        return {
            "active_provider": self._active,
            "active_model": p.model if p else None,
            "providers": list(self._providers.keys()),
            "supports_tools": p.supports_tools() if p else False,
        }

    async def generate(
        self,
        messages: List[Message],
        tools: Optional[List[ToolDefinition]] = None,
        system: Optional[str] = None,
        temperature: float = 0.7,
        max_tokens: int = 4096,
    ) -> LLMResponse:
        p = self.active_provider
        if not p:
            raise RuntimeError("No active LLM provider")
        return await p.generate(
            messages, tools=tools, system=system,
            temperature=temperature, max_tokens=max_tokens
        )

    async def generate_stream(
        self,
        messages: List[Message],
        system: Optional[str] = None,
        temperature: float = 0.7,
    ) -> AsyncGenerator[str, None]:
        p = self.active_provider
        if not p:
            raise RuntimeError("No active LLM provider")
        async for chunk in p.generate_stream(messages, system=system, temperature=temperature):
            yield chunk

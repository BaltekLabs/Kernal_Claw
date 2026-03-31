"""
Groq LLM provider — fast inference via Groq API (OpenAI-compatible).
Supports llama3-70b, mixtral-8x7b, gemma, etc.
"""

import logging
from typing import AsyncGenerator, List, Optional

from llm.providers.openai_provider import OpenAIProvider
from llm.providers.base import LLMResponse, Message, ToolDefinition

logger = logging.getLogger(__name__)

GROQ_BASE_URL = "https://api.groq.com/openai/v1"


class GroqProvider(OpenAIProvider):
    """Groq is OpenAI-compatible, so we reuse OpenAIProvider with a custom base URL."""

    name = "groq"

    def __init__(
        self,
        model: str = "llama-3.3-70b-versatile",
        api_key: Optional[str] = None,
    ):
        super().__init__(model=model, api_key=api_key, base_url=GROQ_BASE_URL)

    async def initialize(self) -> None:
        await super().initialize()
        logger.info("GroqProvider initialized with model=%s", self._model)

    async def list_models(self) -> List[str]:
        return [
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "mixtral-8x7b-32768",
            "gemma2-9b-it",
            "deepseek-r1-distill-llama-70b",
        ]

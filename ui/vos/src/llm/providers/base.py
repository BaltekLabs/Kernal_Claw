"""
Abstract base class for all LLM providers.
Supports tool/function calling and streaming.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, AsyncGenerator, Dict, List, Optional


@dataclass
class Message:
    role: str  # "user", "assistant", "system", "tool"
    content: str
    tool_call_id: Optional[str] = None
    tool_calls: Optional[List[Dict]] = None
    name: Optional[str] = None


@dataclass
class ToolDefinition:
    name: str
    description: str
    parameters: Dict[str, Any]  # JSON Schema


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: Dict[str, Any]


@dataclass
class LLMResponse:
    content: str
    tool_calls: List[ToolCall] = field(default_factory=list)
    model: str = ""
    finish_reason: str = "stop"  # "stop", "tool_calls", "length"


class BaseLLMProvider(ABC):
    """Abstract base for all LLM providers."""

    name: str = "base"

    @abstractmethod
    async def initialize(self) -> None:
        """Initialize the provider (e.g. connect, validate API key)."""

    @abstractmethod
    async def cleanup(self) -> None:
        """Release resources."""

    @abstractmethod
    async def generate(
        self,
        messages: List[Message],
        tools: Optional[List[ToolDefinition]] = None,
        system: Optional[str] = None,
        temperature: float = 0.7,
        max_tokens: int = 4096,
    ) -> LLMResponse:
        """Single-shot generation with optional tool use."""

    @abstractmethod
    async def generate_stream(
        self,
        messages: List[Message],
        system: Optional[str] = None,
        temperature: float = 0.7,
    ) -> AsyncGenerator[str, None]:
        """Streaming text generation (no tool use)."""

    @abstractmethod
    def supports_tools(self) -> bool:
        """Whether this provider supports function/tool calling."""

    @abstractmethod
    async def list_models(self) -> List[str]:
        """List available models for this provider."""

    @property
    @abstractmethod
    def model(self) -> str:
        """Current active model name."""

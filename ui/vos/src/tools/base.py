"""
Base class and result types for all agent tools.
Tools are JSON-Schema defined, async, and sandboxable.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Dict, Optional

from llm.providers.base import ToolDefinition


@dataclass
class ToolResult:
    success: bool
    output: str
    error: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "success": self.success,
            "output": self.output,
            "error": self.error,
            "metadata": self.metadata,
        }

    def __str__(self) -> str:
        if self.success:
            return self.output
        return f"ERROR: {self.error}\n{self.output}"


class BaseTool(ABC):
    """Base class for all agent tools."""

    # Override in subclasses
    name: str = "base_tool"
    description: str = "A tool."

    @abstractmethod
    def get_definition(self) -> ToolDefinition:
        """Return the JSON-Schema tool definition for LLM function calling."""

    @abstractmethod
    async def execute(self, **kwargs) -> ToolResult:
        """Execute the tool with given arguments."""

    async def initialize(self) -> None:
        """Optional initialization hook."""

    async def cleanup(self) -> None:
        """Optional cleanup hook."""

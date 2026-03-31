"""
Memory tool — persistent key-value memory stored as YAML files.
Lets the agent remember facts across sessions (OpenClaw-style).
"""

import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

from llm.providers.base import ToolDefinition
from tools.base import BaseTool, ToolResult

logger = logging.getLogger(__name__)

try:
    import yaml
    HAS_YAML = True
except ImportError:
    HAS_YAML = False


class MemoryTool(BaseTool):
    name = "memory"
    description = (
        "Store and retrieve persistent memory as key-value entries. "
        "Use to remember user preferences, facts, task progress, and context across sessions. "
        "Memory is stored as plain YAML files that can be inspected and edited."
    )

    def __init__(self, memory_dir: Optional[str] = None):
        if memory_dir:
            self._dir = Path(memory_dir)
        else:
            # Default: alongside the vos directory
            self._dir = Path(__file__).parents[3] / "memory"
        self._dir.mkdir(parents=True, exist_ok=True)
        self._cache: Dict[str, Any] = {}
        self._loaded = False

    def _memory_file(self) -> Path:
        return self._dir / "agent_memory.yaml"

    def _load(self) -> Dict[str, Any]:
        if self._loaded:
            return self._cache
        f = self._memory_file()
        if f.exists() and HAS_YAML:
            try:
                data = yaml.safe_load(f.read_text()) or {}
                self._cache = data
            except Exception:
                self._cache = {}
        else:
            self._cache = {}
        self._loaded = True
        return self._cache

    def _save(self, data: Dict[str, Any]) -> None:
        self._cache = data
        if HAS_YAML:
            self._memory_file().write_text(yaml.safe_dump(data, default_flow_style=False))
        else:
            # Fallback: JSON
            import json
            (self._dir / "agent_memory.json").write_text(json.dumps(data, indent=2))

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["get", "set", "delete", "list", "clear"],
                        "description": "Action: get/set/delete a key, list all keys, or clear all memory.",
                    },
                    "key": {"type": "string", "description": "Memory key (required for get/set/delete)."},
                    "value": {"type": "string", "description": "Value to store (required for set)."},
                },
                "required": ["action"],
            },
        )

    async def execute(
        self,
        action: str,
        key: Optional[str] = None,
        value: Optional[str] = None,
    ) -> ToolResult:
        data = self._load()

        if action == "set":
            if not key:
                return ToolResult(success=False, output="", error="key required for set")
            data[key] = value
            self._save(data)
            return ToolResult(success=True, output=f"Stored: {key} = {value}")

        elif action == "get":
            if not key:
                return ToolResult(success=False, output="", error="key required for get")
            val = data.get(key)
            if val is None:
                return ToolResult(success=True, output=f"(not found: {key})")
            return ToolResult(success=True, output=str(val))

        elif action == "delete":
            if not key:
                return ToolResult(success=False, output="", error="key required for delete")
            if key in data:
                del data[key]
                self._save(data)
                return ToolResult(success=True, output=f"Deleted: {key}")
            return ToolResult(success=True, output=f"(key not found: {key})")

        elif action == "list":
            if not data:
                return ToolResult(success=True, output="(no memories stored)")
            lines = [f"{k}: {v}" for k, v in sorted(data.items())]
            return ToolResult(success=True, output="\n".join(lines))

        elif action == "clear":
            self._save({})
            return ToolResult(success=True, output="Memory cleared.")

        return ToolResult(success=False, output="", error=f"Unknown action: {action}")

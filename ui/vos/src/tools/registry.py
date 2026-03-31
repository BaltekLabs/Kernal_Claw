"""
Tool registry — manages all available tools and their definitions.
"""

import logging
from typing import Dict, List, Optional

from llm.providers.base import ToolDefinition
from tools.base import BaseTool, ToolResult

logger = logging.getLogger(__name__)


class ToolRegistry:
    def __init__(self):
        self._tools: Dict[str, BaseTool] = {}

    def register(self, tool: BaseTool) -> None:
        self._tools[tool.name] = tool
        logger.info("Registered tool: %s", tool.name)

    def unregister(self, name: str) -> None:
        self._tools.pop(name, None)

    def get(self, name: str) -> Optional[BaseTool]:
        return self._tools.get(name)

    def list_tools(self) -> List[str]:
        return list(self._tools.keys())

    def get_definitions(self) -> List[ToolDefinition]:
        return [t.get_definition() for t in self._tools.values()]

    async def execute(self, name: str, **kwargs) -> ToolResult:
        tool = self._tools.get(name)
        if not tool:
            return ToolResult(
                success=False,
                output="",
                error=f"Unknown tool: {name}. Available: {self.list_tools()}",
            )
        try:
            return await tool.execute(**kwargs)
        except Exception as e:
            logger.error("Tool '%s' raised: %s", name, e, exc_info=True)
            return ToolResult(success=False, output="", error=str(e))

    async def initialize_all(self) -> None:
        for tool in self._tools.values():
            await tool.initialize()

    async def cleanup_all(self) -> None:
        for tool in self._tools.values():
            await tool.cleanup()

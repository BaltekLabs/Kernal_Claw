"""
HTTP tool — make GET/POST requests. Use for web APIs, webhooks, scraping.
"""

import logging
from typing import Any, Dict, Optional

import aiohttp

from llm.providers.base import ToolDefinition
from tools.base import BaseTool, ToolResult

logger = logging.getLogger(__name__)

MAX_RESPONSE_BYTES = 64 * 1024


class HttpTool(BaseTool):
    name = "http"
    description = (
        "Make HTTP GET or POST requests. "
        "Use to call REST APIs, fetch web pages, send webhooks, or query JSON services."
    )

    def __init__(self, timeout: int = 30):
        self._timeout = timeout
        self._session: Optional[aiohttp.ClientSession] = None

    async def initialize(self) -> None:
        self._session = aiohttp.ClientSession()

    async def cleanup(self) -> None:
        if self._session:
            await self._session.close()

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "url": {"type": "string", "description": "Full URL to request."},
                    "method": {
                        "type": "string",
                        "enum": ["GET", "POST", "PUT", "DELETE", "PATCH"],
                        "default": "GET",
                    },
                    "headers": {
                        "type": "object",
                        "description": "Optional HTTP headers as key-value pairs.",
                    },
                    "body": {
                        "type": "object",
                        "description": "JSON body for POST/PUT requests.",
                    },
                    "params": {
                        "type": "object",
                        "description": "URL query parameters.",
                    },
                },
                "required": ["url"],
            },
        )

    async def execute(
        self,
        url: str,
        method: str = "GET",
        headers: Optional[Dict[str, str]] = None,
        body: Optional[Dict[str, Any]] = None,
        params: Optional[Dict[str, str]] = None,
    ) -> ToolResult:
        if not self._session:
            self._session = aiohttp.ClientSession()

        try:
            kwargs: Dict[str, Any] = {
                "headers": headers or {},
                "timeout": aiohttp.ClientTimeout(total=self._timeout),
            }
            if params:
                kwargs["params"] = params
            if body:
                kwargs["json"] = body

            async with self._session.request(method.upper(), url, **kwargs) as resp:
                raw = await resp.read()
                text = raw[:MAX_RESPONSE_BYTES].decode(errors="replace")

                # Try to pretty-print JSON
                content_type = resp.headers.get("Content-Type", "")
                if "json" in content_type:
                    import json
                    try:
                        import json
                        text = json.dumps(json.loads(text), indent=2)[:MAX_RESPONSE_BYTES]
                    except Exception:
                        pass

                return ToolResult(
                    success=resp.status < 400,
                    output=text,
                    error=f"HTTP {resp.status}" if resp.status >= 400 else None,
                    metadata={"status": resp.status, "url": url, "method": method},
                )
        except Exception as e:
            logger.error("HttpTool error: %s", e)
            return ToolResult(success=False, output="", error=str(e))

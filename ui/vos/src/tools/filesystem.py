"""
Filesystem tool — read, write, list, search files with path sandboxing.
"""

import logging
import os
from pathlib import Path
from typing import List, Optional

from llm.providers.base import ToolDefinition
from tools.base import BaseTool, ToolResult

logger = logging.getLogger(__name__)

MAX_READ_BYTES = 64 * 1024  # 64 KB


class FileReadTool(BaseTool):
    name = "file_read"
    description = (
        "Read the contents of a file. "
        "Use to inspect source code, configs, logs, or any text file."
    )

    def __init__(self, allowed_paths: Optional[List[str]] = None):
        self._allowed = [Path(p).resolve() for p in (allowed_paths or ["/"])]

    def _allowed_path(self, path: str) -> bool:
        p = Path(path).resolve()
        return any(str(p).startswith(str(a)) for a in self._allowed)

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Absolute or relative path to the file."},
                    "start_line": {"type": "integer", "description": "First line to read (1-indexed, optional)."},
                    "end_line": {"type": "integer", "description": "Last line to read (optional)."},
                },
                "required": ["path"],
            },
        )

    async def execute(self, path: str, start_line: Optional[int] = None, end_line: Optional[int] = None) -> ToolResult:
        if not self._allowed_path(path):
            return ToolResult(success=False, output="", error=f"Access denied: {path}")
        try:
            p = Path(path)
            if not p.exists():
                return ToolResult(success=False, output="", error=f"File not found: {path}")
            if not p.is_file():
                return ToolResult(success=False, output="", error=f"Not a file: {path}")

            raw = p.read_bytes()[:MAX_READ_BYTES]
            text = raw.decode(errors="replace")
            lines = text.splitlines(keepends=True)

            if start_line or end_line:
                s = (start_line or 1) - 1
                e = end_line or len(lines)
                lines = lines[s:e]
                text = "".join(lines)

            return ToolResult(
                success=True,
                output=text,
                metadata={"path": str(p), "size": p.stat().st_size, "lines": len(lines)},
            )
        except Exception as e:
            return ToolResult(success=False, output="", error=str(e))


class FileWriteTool(BaseTool):
    name = "file_write"
    description = (
        "Write or append content to a file. "
        "Use to save code, config changes, notes, or any text output."
    )

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Path to the file to write."},
                    "content": {"type": "string", "description": "Content to write."},
                    "mode": {
                        "type": "string",
                        "enum": ["write", "append"],
                        "default": "write",
                        "description": "'write' overwrites, 'append' adds to end.",
                    },
                    "create_dirs": {
                        "type": "boolean",
                        "default": True,
                        "description": "Create parent directories if missing.",
                    },
                },
                "required": ["path", "content"],
            },
        )

    async def execute(self, path: str, content: str, mode: str = "write", create_dirs: bool = True) -> ToolResult:
        try:
            p = Path(path)
            if create_dirs:
                p.parent.mkdir(parents=True, exist_ok=True)

            flag = "w" if mode == "write" else "a"
            with open(p, flag, encoding="utf-8") as f:
                f.write(content)

            return ToolResult(
                success=True,
                output=f"Written {len(content)} chars to {path}",
                metadata={"path": str(p), "mode": mode},
            )
        except Exception as e:
            return ToolResult(success=False, output="", error=str(e))


class FileListTool(BaseTool):
    name = "file_list"
    description = "List files and directories in a path. Supports glob patterns."

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Directory path to list."},
                    "pattern": {"type": "string", "description": "Glob pattern (e.g. '*.py'). Default: '*'"},
                    "recursive": {"type": "boolean", "default": False},
                },
                "required": ["path"],
            },
        )

    async def execute(self, path: str, pattern: str = "*", recursive: bool = False) -> ToolResult:
        try:
            p = Path(path)
            if not p.exists():
                return ToolResult(success=False, output="", error=f"Path not found: {path}")

            if recursive:
                entries = list(p.rglob(pattern))
            else:
                entries = list(p.glob(pattern))

            entries.sort()
            lines = []
            for e in entries[:500]:  # cap at 500 entries
                suffix = "/" if e.is_dir() else ""
                size = e.stat().st_size if e.is_file() else 0
                lines.append(f"{e.relative_to(p)}{suffix}  ({size} bytes)" if e.is_file() else f"{e.relative_to(p)}{suffix}")

            return ToolResult(
                success=True,
                output="\n".join(lines) or "(empty)",
                metadata={"count": len(entries)},
            )
        except Exception as e:
            return ToolResult(success=False, output="", error=str(e))


class FileSearchTool(BaseTool):
    name = "file_search"
    description = "Search for a pattern in files using grep. Returns matching lines with file:line context."

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "pattern": {"type": "string", "description": "Regex or text pattern to search for."},
                    "path": {"type": "string", "description": "Directory or file to search in."},
                    "file_pattern": {"type": "string", "description": "File name glob (e.g. '*.c'). Default: '*'"},
                    "case_insensitive": {"type": "boolean", "default": False},
                    "max_results": {"type": "integer", "default": 50},
                },
                "required": ["pattern", "path"],
            },
        )

    async def execute(
        self,
        pattern: str,
        path: str,
        file_pattern: str = "*",
        case_insensitive: bool = False,
        max_results: int = 50,
    ) -> ToolResult:
        import asyncio

        flags = ["-rn", "--include", file_pattern]
        if case_insensitive:
            flags.append("-i")
        flags += [pattern, path]

        proc = await asyncio.create_subprocess_exec(
            "grep", *flags,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await proc.communicate()
        out = stdout.decode(errors="replace")
        lines = out.splitlines()[:max_results]
        return ToolResult(
            success=True,
            output="\n".join(lines) or "(no matches)",
            metadata={"matches": len(lines)},
        )

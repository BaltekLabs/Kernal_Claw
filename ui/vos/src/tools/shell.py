"""
Shell tool — execute arbitrary shell commands with timeout and sandboxing.
Kernel-aware: works inside the Baltek DTE environment.
"""

import asyncio
import logging
import os
from typing import Optional

from llm.providers.base import ToolDefinition
from tools.base import BaseTool, ToolResult

logger = logging.getLogger(__name__)

# Default timeout for shell commands (seconds)
DEFAULT_TIMEOUT = 30
MAX_OUTPUT_CHARS = 8000


class ShellTool(BaseTool):
    name = "shell"
    description = (
        "Execute a shell command and return stdout/stderr. "
        "Use for system administration, kernel inspection, file manipulation, "
        "and any terminal operation. Commands run in a sandboxed environment."
    )

    def __init__(
        self,
        allowed_commands: Optional[list] = None,
        blocked_commands: Optional[list] = None,
        timeout: int = DEFAULT_TIMEOUT,
        working_dir: Optional[str] = None,
    ):
        self._allowed = set(allowed_commands) if allowed_commands else None
        self._blocked = set(blocked_commands or [
            "rm -rf /", "mkfs", "dd if=/dev/zero", "> /dev/sda",
            ":(){ :|:& };:",  # fork bomb
        ])
        self._timeout = timeout
        self._cwd = working_dir or os.getcwd()

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "command": {
                        "type": "string",
                        "description": "The shell command to execute.",
                    },
                    "timeout": {
                        "type": "integer",
                        "description": "Timeout in seconds (default 30).",
                        "default": DEFAULT_TIMEOUT,
                    },
                    "cwd": {
                        "type": "string",
                        "description": "Working directory for the command.",
                    },
                },
                "required": ["command"],
            },
        )

    def _is_blocked(self, command: str) -> bool:
        for b in self._blocked:
            if b in command:
                return True
        return False

    async def execute(self, command: str, timeout: int = DEFAULT_TIMEOUT, cwd: Optional[str] = None) -> ToolResult:
        if self._is_blocked(command):
            return ToolResult(
                success=False,
                output="",
                error=f"Command blocked for safety: {command[:80]}",
            )

        if self._allowed is not None:
            cmd_base = command.split()[0] if command.strip() else ""
            if cmd_base not in self._allowed:
                return ToolResult(
                    success=False,
                    output="",
                    error=f"Command '{cmd_base}' not in allowed list.",
                )

        work_dir = cwd or self._cwd
        try:
            proc = await asyncio.create_subprocess_shell(
                command,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                cwd=work_dir,
            )
            try:
                stdout, stderr = await asyncio.wait_for(
                    proc.communicate(), timeout=float(timeout)
                )
            except asyncio.TimeoutError:
                proc.kill()
                return ToolResult(
                    success=False,
                    output="",
                    error=f"Command timed out after {timeout}s: {command[:80]}",
                )

            out = stdout.decode(errors="replace")
            err = stderr.decode(errors="replace")
            combined = (out + ("\nSTDERR:\n" + err if err.strip() else ""))[:MAX_OUTPUT_CHARS]
            success = proc.returncode == 0

            return ToolResult(
                success=success,
                output=combined,
                error=err.strip() if not success else None,
                metadata={"returncode": proc.returncode, "command": command},
            )
        except Exception as e:
            logger.error("ShellTool error: %s", e)
            return ToolResult(success=False, output="", error=str(e))

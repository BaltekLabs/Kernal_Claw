"""
Kernel tools — expose Linux kernel interfaces to the agent.
Covers: dmesg, sysctl, procfs, modules, sysfs, perf counters.
These integrate with the host kernel, making VoiceOS kernel-aware.
"""

import asyncio
import logging
from pathlib import Path
from typing import Optional

from llm.providers.base import ToolDefinition
from tools.base import BaseTool, ToolResult

logger = logging.getLogger(__name__)


class KernelLogTool(BaseTool):
    name = "kernel_log"
    description = (
        "Read the kernel ring buffer (dmesg). "
        "Use to debug hardware issues, driver errors, boot messages, and kernel events."
    )

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "lines": {"type": "integer", "default": 50, "description": "Number of recent lines to return."},
                    "filter": {"type": "string", "description": "Optional grep filter pattern."},
                    "level": {
                        "type": "string",
                        "enum": ["emerg", "alert", "crit", "err", "warn", "notice", "info", "debug"],
                        "description": "Minimum severity level filter.",
                    },
                },
                "required": [],
            },
        )

    async def execute(self, lines: int = 50, filter: Optional[str] = None, level: Optional[str] = None) -> ToolResult:
        cmd = ["dmesg", "--time-format=reltime", "-H=never"]
        if level:
            cmd += [f"--level={level}"]

        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, _ = await proc.communicate()
        text = stdout.decode(errors="replace")
        text_lines = text.splitlines()

        if filter:
            text_lines = [l for l in text_lines if filter.lower() in l.lower()]

        tail = text_lines[-lines:]
        return ToolResult(
            success=True,
            output="\n".join(tail),
            metadata={"total_lines": len(text_lines), "shown": len(tail)},
        )


class SysctlTool(BaseTool):
    name = "sysctl"
    description = (
        "Read or write Linux kernel parameters via sysctl. "
        "Use to tune kernel behavior, check network settings, memory limits, etc."
    )

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "key": {
                        "type": "string",
                        "description": "Sysctl key (e.g. 'vm.swappiness'). Omit to list all.",
                    },
                    "value": {
                        "type": "string",
                        "description": "New value to set (omit for read-only).",
                    },
                },
                "required": [],
            },
        )

    async def execute(self, key: Optional[str] = None, value: Optional[str] = None) -> ToolResult:
        if key and value:
            cmd = ["sysctl", "-w", f"{key}={value}"]
        elif key:
            cmd = ["sysctl", key]
        else:
            cmd = ["sysctl", "-a"]

        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await proc.communicate()
        out = stdout.decode(errors="replace")
        err = stderr.decode(errors="replace")
        success = proc.returncode == 0
        return ToolResult(
            success=success,
            output=out[:4000],
            error=err.strip() if not success else None,
        )


class ProcFSTool(BaseTool):
    name = "procfs"
    description = (
        "Read files from /proc filesystem. "
        "Access process info, system stats, CPU/memory/IO metrics, "
        "and kernel subsystem state directly from the kernel."
    )

    SAFE_PROC_PATHS = {
        "/proc/cpuinfo", "/proc/meminfo", "/proc/loadavg", "/proc/uptime",
        "/proc/version", "/proc/stat", "/proc/net/dev", "/proc/net/tcp",
        "/proc/net/udp", "/proc/diskstats", "/proc/mounts", "/proc/modules",
        "/proc/interrupts", "/proc/softirqs", "/proc/buddyinfo", "/proc/slabinfo",
        "/proc/zoneinfo", "/proc/vmstat", "/proc/schedstat", "/proc/crypto",
        "/proc/filesystems", "/proc/devices", "/proc/partitions",
    }

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Path under /proc to read (e.g. 'meminfo', 'cpuinfo', 'net/dev').",
                    },
                    "pid": {
                        "type": "integer",
                        "description": "Process ID for /proc/<pid>/ files (status, maps, fd, etc.).",
                    },
                },
                "required": [],
            },
        )

    async def execute(self, path: Optional[str] = None, pid: Optional[int] = None) -> ToolResult:
        if pid:
            full_path = f"/proc/{pid}/{path or 'status'}"
        elif path:
            if not path.startswith("/"):
                full_path = f"/proc/{path}"
            else:
                full_path = path
        else:
            full_path = "/proc/meminfo"

        p = Path(full_path)
        if not p.exists():
            return ToolResult(success=False, output="", error=f"Not found: {full_path}")
        if not p.is_file():
            # List directory
            entries = sorted(str(e.name) for e in p.iterdir())
            return ToolResult(success=True, output="\n".join(entries[:200]))

        try:
            text = p.read_text(errors="replace")[:8000]
            return ToolResult(success=True, output=text, metadata={"path": full_path})
        except PermissionError:
            return ToolResult(success=False, output="", error=f"Permission denied: {full_path}")
        except Exception as e:
            return ToolResult(success=False, output="", error=str(e))


class KernelModuleTool(BaseTool):
    name = "kernel_module"
    description = (
        "List, inspect, load, or unload kernel modules. "
        "Use for driver management and kernel feature toggling."
    )

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["list", "info", "load", "unload"],
                        "description": "Action to perform.",
                        "default": "list",
                    },
                    "module": {
                        "type": "string",
                        "description": "Module name (required for info/load/unload).",
                    },
                },
                "required": ["action"],
            },
        )

    async def execute(self, action: str = "list", module: Optional[str] = None) -> ToolResult:
        if action == "list":
            cmd = ["lsmod"]
        elif action == "info" and module:
            cmd = ["modinfo", module]
        elif action == "load" and module:
            cmd = ["modprobe", module]
        elif action == "unload" and module:
            cmd = ["modprobe", "-r", module]
        else:
            return ToolResult(success=False, output="", error="Invalid action or missing module name.")

        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await proc.communicate()
        out = stdout.decode(errors="replace")
        err = stderr.decode(errors="replace")
        success = proc.returncode == 0
        return ToolResult(
            success=success,
            output=out[:4000],
            error=err.strip() if not success else None,
        )


class SysFSTool(BaseTool):
    name = "sysfs"
    description = (
        "Read or write sysfs entries (/sys). "
        "Use to inspect and control hardware, CPU governors, power management, "
        "block device settings, and driver parameters."
    )

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Path under /sys (e.g. 'devices/system/cpu/cpu0/cpufreq/scaling_governor').",
                    },
                    "value": {
                        "type": "string",
                        "description": "Value to write (omit for read).",
                    },
                },
                "required": ["path"],
            },
        )

    async def execute(self, path: str, value: Optional[str] = None) -> ToolResult:
        if not path.startswith("/"):
            path = f"/sys/{path}"
        p = Path(path)

        if value is not None:
            try:
                p.write_text(value)
                return ToolResult(success=True, output=f"Written '{value}' to {path}")
            except Exception as e:
                return ToolResult(success=False, output="", error=str(e))

        if not p.exists():
            return ToolResult(success=False, output="", error=f"Not found: {path}")
        if p.is_dir():
            entries = sorted(str(e.name) for e in p.iterdir())
            return ToolResult(success=True, output="\n".join(entries[:200]))
        try:
            text = p.read_text(errors="replace").strip()
            return ToolResult(success=True, output=text, metadata={"path": path})
        except Exception as e:
            return ToolResult(success=False, output="", error=str(e))

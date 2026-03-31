"""
System info tool — comprehensive system state snapshot.
Combines /proc, psutil, and kernel interfaces for the LLM.
"""

import asyncio
import logging
import platform
from typing import Optional

import psutil

from llm.providers.base import ToolDefinition
from tools.base import BaseTool, ToolResult

logger = logging.getLogger(__name__)


class SystemInfoTool(BaseTool):
    name = "system_info"
    description = (
        "Get a comprehensive snapshot of system state: CPU, memory, disk, "
        "network, processes, kernel version, and more. "
        "Use to answer questions about system health, performance, and resource usage."
    )

    def get_definition(self) -> ToolDefinition:
        return ToolDefinition(
            name=self.name,
            description=self.description,
            parameters={
                "type": "object",
                "properties": {
                    "category": {
                        "type": "string",
                        "enum": ["all", "cpu", "memory", "disk", "network", "processes", "kernel"],
                        "default": "all",
                        "description": "Which category of info to return.",
                    },
                    "top_n_procs": {
                        "type": "integer",
                        "default": 10,
                        "description": "Number of top processes to show (by CPU usage).",
                    },
                },
                "required": [],
            },
        )

    async def execute(self, category: str = "all", top_n_procs: int = 10) -> ToolResult:
        lines = []

        if category in ("all", "kernel"):
            lines += [
                "=== KERNEL ===",
                f"OS: {platform.system()} {platform.release()}",
                f"Version: {platform.version()[:120]}",
                f"Machine: {platform.machine()}",
                f"Hostname: {platform.node()}",
                "",
            ]

        if category in ("all", "cpu"):
            cpu_pct = psutil.cpu_percent(interval=0.1, percpu=True)
            load = psutil.getloadavg()
            lines += [
                "=== CPU ===",
                f"Cores: {psutil.cpu_count(logical=False)} physical / {psutil.cpu_count()} logical",
                f"Usage per core: {' '.join(f'{p:.0f}%' for p in cpu_pct)}",
                f"Load avg (1/5/15min): {load[0]:.2f} / {load[1]:.2f} / {load[2]:.2f}",
                "",
            ]

        if category in ("all", "memory"):
            vm = psutil.virtual_memory()
            sw = psutil.swap_memory()
            lines += [
                "=== MEMORY ===",
                f"Total: {vm.total // 1024**2} MB",
                f"Used: {vm.used // 1024**2} MB ({vm.percent:.1f}%)",
                f"Available: {vm.available // 1024**2} MB",
                f"Swap: {sw.used // 1024**2}/{sw.total // 1024**2} MB used",
                "",
            ]

        if category in ("all", "disk"):
            lines.append("=== DISK ===")
            for part in psutil.disk_partitions():
                try:
                    usage = psutil.disk_usage(part.mountpoint)
                    lines.append(
                        f"{part.device} -> {part.mountpoint} "
                        f"({part.fstype}): {usage.used // 1024**3}/"
                        f"{usage.total // 1024**3} GB used ({usage.percent:.1f}%)"
                    )
                except PermissionError:
                    pass
            lines.append("")

        if category in ("all", "network"):
            lines.append("=== NETWORK ===")
            net = psutil.net_io_counters(pernic=True)
            for iface, stats in net.items():
                lines.append(
                    f"{iface}: rx={stats.bytes_recv // 1024} KB  tx={stats.bytes_sent // 1024} KB"
                )
            lines.append("")

        if category in ("all", "processes"):
            lines.append(f"=== TOP {top_n_procs} PROCESSES (by CPU) ===")
            procs = []
            for p in psutil.process_iter(["pid", "name", "cpu_percent", "memory_percent", "status"]):
                try:
                    procs.append(p.info)
                except psutil.NoSuchProcess:
                    pass
            procs.sort(key=lambda x: x.get("cpu_percent", 0), reverse=True)
            for p in procs[:top_n_procs]:
                lines.append(
                    f"  PID {p['pid']:6d}  {p.get('name','?'):<20s}  "
                    f"CPU {p.get('cpu_percent',0):5.1f}%  "
                    f"MEM {p.get('memory_percent',0):4.1f}%  "
                    f"{p.get('status','?')}"
                )
            lines.append("")

        output = "\n".join(lines)
        return ToolResult(success=True, output=output)

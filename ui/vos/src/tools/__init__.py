import platform

from tools.base import BaseTool, ToolResult
from tools.registry import ToolRegistry
from tools.shell import ShellTool
from tools.filesystem import FileReadTool, FileWriteTool, FileListTool, FileSearchTool
from tools.http_tool import HttpTool
from tools.memory_tool import MemoryTool
from tools.system_info import SystemInfoTool

# Kernel tools are only available on Linux with procfs/sysfs access.
# On Android (via Chaquopy/UserLAnd/Termux) they are skipped gracefully.
_KERNEL_TOOLS_AVAILABLE = False
try:
    if platform.system() == "Linux":
        from tools.kernel import KernelLogTool, SysctlTool, ProcFSTool, KernelModuleTool, SysFSTool
        _KERNEL_TOOLS_AVAILABLE = True
except Exception:
    pass

if not _KERNEL_TOOLS_AVAILABLE:
    # Provide stub names so existing imports don't break
    KernelLogTool = KernelModuleTool = SysctlTool = ProcFSTool = SysFSTool = None


def create_default_registry(memory_dir: str = None) -> ToolRegistry:
    """Create a ToolRegistry pre-loaded with all standard tools."""
    registry = ToolRegistry()

    # Core tools (always available)
    registry.register(ShellTool())
    registry.register(FileReadTool())
    registry.register(FileWriteTool())
    registry.register(FileListTool())
    registry.register(FileSearchTool())
    registry.register(HttpTool())
    registry.register(MemoryTool(memory_dir=memory_dir))
    registry.register(SystemInfoTool())

    # Kernel tools (Linux only, skipped on Android/mobile)
    if _KERNEL_TOOLS_AVAILABLE:
        registry.register(KernelLogTool())
        registry.register(SysctlTool())
        registry.register(ProcFSTool())
        registry.register(KernelModuleTool())
        registry.register(SysFSTool())

    return registry


__all__ = [
    "BaseTool", "ToolResult", "ToolRegistry",
    "ShellTool", "FileReadTool", "FileWriteTool", "FileListTool", "FileSearchTool",
    "KernelLogTool", "SysctlTool", "ProcFSTool", "KernelModuleTool", "SysFSTool",
    "HttpTool", "MemoryTool", "SystemInfoTool",
    "create_default_registry",
]

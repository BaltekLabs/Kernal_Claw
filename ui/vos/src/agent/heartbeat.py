"""
Heartbeat — proactive agent tasks that run on a schedule.
Inspired by OpenClaw's heartbeat mechanism.

The heartbeat checks every N minutes for things the agent should
proactively do: resource alerts, pending tasks, system health checks.
"""

import asyncio
import logging
from dataclasses import dataclass
from typing import Any, Callable, Coroutine, Dict, List, Optional

logger = logging.getLogger(__name__)


@dataclass
class HeartbeatTask:
    name: str
    interval_seconds: int
    task_fn: Callable[[], Coroutine]
    description: str = ""
    enabled: bool = True
    _last_run: float = 0.0


class Heartbeat:
    """
    Runs periodic background tasks without user interaction.
    Tasks can trigger agent actions, emit events, or run diagnostics.
    """

    def __init__(self, event_bus=None):
        self._tasks: List[HeartbeatTask] = []
        self._running = False
        self._handle: Optional[asyncio.Task] = None
        self._event_bus = event_bus

    def register(self, task: HeartbeatTask) -> None:
        self._tasks.append(task)
        logger.info("Heartbeat task registered: %s (every %ds)", task.name, task.interval_seconds)

    def unregister(self, name: str) -> None:
        self._tasks = [t for t in self._tasks if t.name != name]

    async def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._handle = asyncio.create_task(self._loop(), name="heartbeat")
        logger.info("Heartbeat started with %d tasks", len(self._tasks))

    async def stop(self) -> None:
        self._running = False
        if self._handle:
            self._handle.cancel()
            try:
                await self._handle
            except asyncio.CancelledError:
                pass

    async def _loop(self) -> None:
        import time
        while self._running:
            now = time.monotonic()
            for task in self._tasks:
                if not task.enabled:
                    continue
                if now - task._last_run >= task.interval_seconds:
                    task._last_run = now
                    try:
                        await task.task_fn()
                    except Exception as e:
                        logger.error("Heartbeat task '%s' failed: %s", task.name, e)
            await asyncio.sleep(5)  # check every 5 seconds

    def list_tasks(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": t.name,
                "interval_seconds": t.interval_seconds,
                "description": t.description,
                "enabled": t.enabled,
            }
            for t in self._tasks
        ]


def make_system_health_task(
    tool_registry,
    event_bus,
    interval_seconds: int = 300,  # 5 minutes
    cpu_threshold: float = 90.0,
    mem_threshold: float = 90.0,
) -> HeartbeatTask:
    """
    Periodic system health check — alerts if CPU or memory is critically high.
    """
    async def check_health():
        try:
            import psutil
            cpu = psutil.cpu_percent(interval=0.5)
            mem = psutil.virtual_memory().percent

            alerts = []
            if cpu > cpu_threshold:
                alerts.append(f"CPU usage critical: {cpu:.1f}%")
            if mem > mem_threshold:
                alerts.append(f"Memory usage critical: {mem:.1f}%")

            if alerts and event_bus:
                from core.event.event_bus import Event, Priority
                await event_bus.emit(Event(
                    type="system_alert",
                    data={"alerts": alerts, "cpu": cpu, "memory": mem},
                    priority=Priority.HIGH,
                ))
                logger.warning("System health alert: %s", ", ".join(alerts))
        except Exception as e:
            logger.debug("Health check error: %s", e)

    return HeartbeatTask(
        name="system_health",
        interval_seconds=interval_seconds,
        task_fn=check_health,
        description=f"Alert when CPU>{cpu_threshold}% or MEM>{mem_threshold}%",
    )

# src/core/llm/monitor/resource_monitor.py

import psutil
import logging
import asyncio
from typing import Dict, Optional
from core.event.event_bus import EventBus, Event, Priority

logger = logging.getLogger(__name__)

class ResourceMonitor:
    def __init__(self, event_bus: EventBus):
        self.event_bus = event_bus
        self.running = False
        self._monitor_task: Optional[asyncio.Task] = None

    async def start(self):
        self.running = True
        self._monitor_task = asyncio.create_task(self._monitor_loop())
        logger.info("Resource monitor started")

    async def stop(self):
        self.running = False
        if self._monitor_task:
            await self._monitor_task
        logger.info("Resource monitor stopped")

    def get_system_resources(self) -> Dict:
        try:
            cpu_percent = psutil.cpu_percent(interval=None)
            memory = psutil.virtual_memory()
            return {
                'cpu_percent': cpu_percent,
                'memory_percent': memory.percent,
                'memory_available': memory.available,
                'memory_total': memory.total
            }
        except Exception as e:
            logger.error(f"Error monitoring resources: {e}")
            return {}

    async def _monitor_loop(self):
        while self.running:
            try:
                resources = self.get_system_resources()
                event = Event(
                    type="system.resources.update",
                    data=resources,
                    priority=Priority.LOW
                )
                await self.event_bus.emit(event)
                
                # Check thresholds
                if resources.get('cpu_percent', 0) > 80 or resources.get('memory_percent', 0) > 85:
                    warning_event = Event(
                        type="system.resources.warning",
                        data={
                            'warnings': [
                                f"High CPU usage: {resources.get('cpu_percent')}%" if resources.get('cpu_percent', 0) > 80 else None,
                                f"High memory usage: {resources.get('memory_percent')}%" if resources.get('memory_percent', 0) > 85 else None
                            ]
                        },
                        priority=Priority.HIGH
                    )
                    await self.event_bus.emit(warning_event)
                
                await asyncio.sleep(1)
            except Exception as e:
                logger.error(f"Error in monitor loop: {e}")
                await asyncio.sleep(1)
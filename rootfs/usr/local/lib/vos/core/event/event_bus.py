# src/core/event/event_bus.py

import asyncio
import logging
from enum import IntEnum
from typing import Dict, List, Callable, Awaitable, Any, Optional
from dataclasses import dataclass

logger = logging.getLogger(__name__)

class Priority(IntEnum):
    LOW = 0
    MEDIUM = 1
    HIGH = 2

@dataclass
class Event:
    type: str
    data: Any
    priority: Priority = Priority.MEDIUM
    correlation_id: Optional[str] = None
    
    def __lt__(self, other):
        if not isinstance(other, Event):
            return NotImplemented
        return self.priority.value > other.priority.value  # Higher priority values come first

class EventBus:
    def __init__(self):
        self._subscribers: Dict[str, List[Callable[[Event], Awaitable[None]]]] = {}
        self._running = False
        self._queue: asyncio.PriorityQueue = asyncio.PriorityQueue()
        self._task: Optional[asyncio.Task] = None

    def subscribe(self, event_type: str, handler: Callable[[Event], Awaitable[None]]):
        if event_type not in self._subscribers:
            self._subscribers[event_type] = []
        self._subscribers[event_type].append(handler)
        logger.debug(f"Subscribed handler to event type: {event_type}")

    async def emit(self, event: Event):
        if not isinstance(event, Event):
            event = Event(type=event.type, data=event.data, priority=event.priority)
        await self._queue.put((-event.priority.value, event))

    async def _process_events(self):
        while self._running:
            try:
                _, event = await self._queue.get()
                if event.type in self._subscribers:
                    for handler in self._subscribers[event.type]:
                        try:
                            await handler(event)
                        except Exception as e:
                            logger.error(f"Error in event handler: {e}")
                self._queue.task_done()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error processing event: {e}")

    async def start(self):
        self._running = True
        self._task = asyncio.create_task(self._process_events())
        logger.info("Event bus started")

    async def stop(self):
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("Event bus stopped")
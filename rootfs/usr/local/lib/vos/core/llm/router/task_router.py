# src/core/llm/router/task_router.py

import asyncio
import logging
from typing import Dict, List, Optional, Set
from datetime import datetime
import uuid

from core.event.event_bus import Event, EventBus, Priority
from core.llm.registry import ModelRegistry, ModelType, ModelStatus
from .types import Task, TaskStatus, TaskType, TaskPriority, TaskResult

class TaskRouter:
    """Routes tasks to appropriate models based on capabilities and availability."""
    
    def __init__(self, event_bus: EventBus, model_registry: ModelRegistry):
        self.event_bus = event_bus
        self.model_registry = model_registry
        self.tasks: Dict[str, Task] = {}
        self.pending_tasks: asyncio.PriorityQueue = asyncio.PriorityQueue()
        self._running = False
        self._worker_task: Optional[asyncio.Task] = None
        self.logger = logging.getLogger(__name__)
        
        # Register event handlers
        self._register_event_handlers()
        
        # Task type to model type mapping
        self.task_model_mapping = {
            TaskType.CHAT: {ModelType.BRAIN, ModelType.TEXT_WORKER},
            TaskType.CODE: {ModelType.CODE_WORKER},
            TaskType.SYSTEM: {ModelType.SYSTEM_WORKER},
            TaskType.REASONING: {ModelType.BRAIN},
            TaskType.ANALYSIS: {ModelType.BRAIN, ModelType.CODE_WORKER},
            TaskType.EXTERNAL: {ModelType.API_MODEL}
        }
    
    def _register_event_handlers(self):
        """Register handlers for task-related events."""
        self.event_bus.subscribe("task_submit", self._handle_task_submit)
        self.event_bus.subscribe("task_cancel", self._handle_task_cancel)
        self.event_bus.subscribe("model_status_update", self._handle_model_status_update)
        self.event_bus.subscribe("task_complete", self._handle_task_complete)
        self.event_bus.subscribe("task_failed", self._handle_task_failed)
    
    async def start(self):
        """Start the task routing worker."""
        if self._running:
            return
        
        self._running = True
        self._worker_task = asyncio.create_task(self._route_tasks())
        self.logger.info("Task router started")
    
    async def stop(self):
        """Stop the task routing worker."""
        if not self._running:
            return
        
        self._running = False
        if self._worker_task:
            await self._worker_task
            self._worker_task = None
        self.logger.info("Task router stopped")
    
    async def _handle_task_submit(self, event: Event):
        """Handle task submission events."""
        task_data = event.data
        task = Task(
            task_id=str(uuid.uuid4()),
            task_type=task_data["task_type"],
            priority=task_data["priority"],
            content=task_data["content"],
            required_capabilities=set(task_data["required_capabilities"]),
            correlation_id=event.correlation_id
        )
        
        await self.submit_task(task)
    
    async def submit_task(self, task: Task) -> str:
        """Submit a new task for processing."""
        self.tasks[task.task_id] = task
        # Priority queue item: (priority number, creation timestamp, task_id)
        # Lower priority number = higher priority
        await self.pending_tasks.put((
            task.priority.value,
            task.created_at.timestamp(),
            task.task_id
        ))
        
        await self.event_bus.emit(Event(
            type="task_queued",
            data={"task_id": task.task_id},
            correlation_id=task.correlation_id,
            priority=Priority.NORMAL
        ))
        
        return task.task_id
    
    async def _route_tasks(self):
        """Main task routing loop."""
        while self._running:
            try:
                if self.pending_tasks.empty():
                    await asyncio.sleep(0.1)
                    continue
                
                # Get highest priority task
                _, _, task_id = await self.pending_tasks.get()
                task = self.tasks[task_id]
                
                if task.status == TaskStatus.CANCELLED:
                    continue
                
                # Find suitable model
                model = await self._find_suitable_model(task)
                if not model:
                    if task.retry_count < task.max_retries:
                        task.retry_count += 1
                        await self.pending_tasks.put((
                            task.priority.value,
                            datetime.now().timestamp(),
                            task.task_id
                        ))
                        continue
                    else:
                        await self._handle_task_failed(task, "No suitable model available")
                        continue
                
                # Assign task to model
                task.status = TaskStatus.ASSIGNED
                task.assigned_model = model.model_id
                
                # Emit task assignment event
                await self.event_bus.emit(Event(
                    type="task_assigned",
                    data={
                        "task_id": task.task_id,
                        "model_id": model.model_id
                    },
                    correlation_id=task.correlation_id,
                    priority=Priority.HIGH
                ))
                
            except Exception as e:
                self.logger.error(f"Error in task routing: {str(e)}")
                await asyncio.sleep(1)
    
    async def _find_suitable_model(self, task: Task):
        """Find the best available model for a task."""
        compatible_model_types = self.task_model_mapping[task.task_type]
        
        best_model = None
        highest_score = -1
        
        for model_type in compatible_model_types:
            # Request model from registry
            event = Event(
                type="request_model",
                data={
                    "specialization": list(task.required_capabilities),
                    "model_type": model_type
                },
                correlation_id=task.correlation_id,
                priority=Priority.HIGH
            )
            
            await self.event_bus.emit(event)
            # Note: In a real implementation, we'd need to wait for and handle the response
            # This is simplified for the example
        
        return best_model
    
    async def _handle_task_cancel(self, event: Event):
        """Handle task cancellation requests."""
        task_id = event.data["task_id"]
        if task_id in self.tasks:
            task = self.tasks[task_id]
            task.status = TaskStatus.CANCELLED
            
            await self.event_bus.emit(Event(
                type="task_cancelled",
                data={"task_id": task_id},
                correlation_id=task.correlation_id,
                priority=Priority.HIGH
            ))
    
    async def _handle_model_status_update(self, event: Event):
        """Handle model status updates."""
        # Implement logic to reassign tasks if needed
        pass
    
    async def _handle_task_complete(self, event: Event):
        """Handle task completion events."""
        task_id = event.data["task_id"]
        if task_id in self.tasks:
            task = self.tasks[task_id]
            task.status = TaskStatus.COMPLETED
            task.result = event.data.get("result")
            
            # Emit task completion event
            await self.event_bus.emit(Event(
                type="task_completed",
                data={
                    "task_id": task_id,
                    "result": task.result
                },
                correlation_id=task.correlation_id,
                priority=Priority.HIGH
            ))
    
    async def _handle_task_failed(self, task: Task, error: str):
        """Handle task failure."""
        task.status = TaskStatus.FAILED
        task.error = error
        
        await self.event_bus.emit(Event(
            type="task_failed",
            data={
                "task_id": task.task_id,
                "error": error
            },
            correlation_id=task.correlation_id,
            priority=Priority.HIGH
        ))
    
    def get_task_status(self, task_id: str) -> Optional[TaskStatus]:
        """Get the current status of a task."""
        if task_id in self.tasks:
            return self.tasks[task_id].status
        return None
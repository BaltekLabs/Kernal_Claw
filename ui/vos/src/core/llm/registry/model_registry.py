# Rest of the ModelRegistry class implementation from the previous artifact
# But remove the type definitions that we moved to types.py
# Add imports:
from typing import Dict, List, Optional, Union
import asyncio
import logging
from core.event.event_bus import Event, EventBus, Priority
from .types import ModelType, ModelStatus, ResourceType, ModelInfo, ModelCapabilities, ResourceRequirements

from enum import Enum, auto
from typing import Dict, List, Optional, Set, Union
from dataclasses import dataclass, field
import asyncio
from datetime import datetime
from pathlib import Path
import logging
from core.event.event_bus import Event, EventBus, Priority

class ModelRegistry:
    """Event-driven model registry for managing multiple LLM instances."""
    
    def __init__(self, event_bus: EventBus):
        self.event_bus = event_bus
        self.models: Dict[str, ModelInfo] = {}
        self.type_index: Dict[ModelType, List[str]] = {t: [] for t in ModelType}
        self.specialization_index: Dict[str, List[str]] = {}
        self.resource_index: Dict[ResourceType, List[str]] = {t: [] for t in ResourceType}
        self._lock = asyncio.Lock()
        self.logger = logging.getLogger(__name__)
        
        # Register event handlers
        self._register_event_handlers()

    def _register_event_handlers(self):
        """Register handlers for model-related events."""
        self.event_bus.subscribe("model_register", self._handle_model_registration)
        self.event_bus.subscribe("model_unregister", self._handle_model_unregistration)
        self.event_bus.subscribe("model_status_update", self._handle_status_update)
        self.event_bus.subscribe("request_model", self._handle_model_request)
        self.event_bus.subscribe("model_metrics_update", self._handle_metrics_update)
    
    async def _handle_model_registration(self, event: Event):
        """Handle model registration events."""
        model_info = event.data
        success = await self.register_model(model_info)
        
        await self.event_bus.emit(Event(
            type="model_registration_result",
            data={
                "success": success,
                "model_id": model_info.model_id
            },
            correlation_id=event.correlation_id,
            priority=Priority.HIGH
        ))

    async def _handle_model_unregistration(self, event: Event):
        """Handle model unregistration events."""
        model_id = event.data["model_id"]
        success = await self.unregister_model(model_id)
        
        await self.event_bus.emit(Event(
            type="model_unregistration_result",
            data={
                "success": success,
                "model_id": model_id
            },
            correlation_id=event.correlation_id,
            priority=Priority.HIGH
        ))

    async def _handle_status_update(self, event: Event):
        """Handle model status update events."""
        data = event.data
        success = await self.update_model_status(
            data["model_id"],
            data["status"],
            data.get("error")
        )
        
        if not success:
            await self.event_bus.emit(Event(
                type="error",
                data={
                    "message": f"Failed to update status for model {data['model_id']}",
                    "model_id": data["model_id"]
                },
                correlation_id=event.correlation_id,
                priority=Priority.HIGH
            ))

    async def _handle_model_request(self, event: Event):
        """Handle requests for model allocation."""
        request = event.data
        model = await self.get_best_model(
            specialization=request["specialization"],
            preferred_type=request.get("model_type"),
            required_resource=request.get("resource_type")
        )
        
        await self.event_bus.emit(Event(
            type="model_allocation_result",
            data={
                "model": model,
                "request": request
            },
            correlation_id=event.correlation_id,
            priority=Priority.HIGH
        ))

    async def _handle_metrics_update(self, event: Event):
        """Handle model metrics update events."""
        data = event.data
        await self.update_metrics(
            data["model_id"],
            data["successful"],
            data["latency"]
        )

    async def register_model(self, model_info: ModelInfo) -> bool:
        """Register a new model in the registry."""
        async with self._lock:
            try:
                # Add to main registry
                self.models[model_info.model_id] = model_info
                
                # Update indices
                self.type_index[model_info.model_type].append(model_info.model_id)
                
                for spec in model_info.capabilities.specializations:
                    if spec not in self.specialization_index:
                        self.specialization_index[spec] = []
                    self.specialization_index[spec].append(model_info.model_id)
                
                self.resource_index[model_info.resources.resource_type].append(
                    model_info.model_id
                )
                
                # Emit registration success event
                await self.event_bus.emit(Event(
                    type="model_registered",
                    data={"model_id": model_info.model_id},
                    priority=Priority.NORMAL
                ))
                
                return True
                
            except Exception as e:
                self.logger.error(f"Failed to register model: {str(e)}")
                await self.event_bus.emit(Event(
                    type="error",
                    data={
                        "message": f"Failed to register model: {str(e)}",
                        "model_id": model_info.model_id
                    },
                    priority=Priority.HIGH
                ))
                return False

    async def unregister_model(self, model_id: str) -> bool:
        """Remove a model from the registry."""
        async with self._lock:
            if model_id not in self.models:
                return False
            
            try:
                model_info = self.models[model_id]
                
                # Remove from indices
                self.type_index[model_info.model_type].remove(model_id)
                
                for spec in model_info.capabilities.specializations:
                    if spec in self.specialization_index:
                        self.specialization_index[spec].remove(model_id)
                        
                self.resource_index[model_info.resources.resource_type].remove(model_id)
                
                # Remove from main registry
                del self.models[model_id]
                
                # Emit unregistration event
                await self.event_bus.emit(Event(
                    type="model_unregistered",
                    data={"model_id": model_id},
                    priority=Priority.NORMAL
                ))
                
                return True
                
            except Exception as e:
                self.logger.error(f"Failed to unregister model: {str(e)}")
                return False

    async def get_best_model(
        self,
        specialization: str,
        preferred_type: Optional[ModelType] = None,
        required_resource: Optional[ResourceType] = None
    ) -> Optional[ModelInfo]:
        """Get the best available model for a given specialization."""
        candidates = await self.get_available_models(
            model_type=preferred_type,
            specialization=specialization,
            resource_type=required_resource
        )
        
        if not candidates:
            await self.event_bus.emit(Event(
                type="warning",
                data={
                    "message": f"No available models found for specialization: {specialization}"
                },
                priority=Priority.HIGH
            ))
            return None
        
        # Score and sort candidates
        scored_candidates = [
            (
                model,
                (model.get_success_rate() * 0.4 +
                 (1 - model.current_load) * 0.4 +
                 (1 - min(model.capabilities.average_latency / 1000, 1)) * 0.2)
            )
            for model in candidates
            if model.status == ModelStatus.READY
        ]
        
        best_model = max(scored_candidates, key=lambda x: x[1])[0] if scored_candidates else None
        
        if best_model:
            await self.event_bus.emit(Event(
                type="model_selected",
                data={
                    "model_id": best_model.model_id,
                    "specialization": specialization
                },
                priority=Priority.NORMAL
            ))
            
        return best_model

    async def get_system_status(self) -> Dict:
        """Get overall system status and statistics."""
        async with self._lock:
            status = {
                "total_models": len(self.models),
                "models_by_type": {
                    t.name: len(models) 
                    for t, models in self.type_index.items()
                },
                "models_by_status": {
                    status.name: len([
                        m for m in self.models.values() 
                        if m.status == status
                    ])
                    for status in ModelStatus
                },
                "specializations_available": list(self.specialization_index.keys())
            }
            
            await self.event_bus.emit(Event(
                type="system_status_update",
                data=status,
                priority=Priority.LOW
            ))
            
            return status
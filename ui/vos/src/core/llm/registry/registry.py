# src/core/llm/registry.py

import logging
from typing import Dict, Optional
from core.event.event_bus import EventBus, Event, Priority

logger = logging.getLogger(__name__)

class ModelRegistry:
    def __init__(self, event_bus: EventBus):
        """Initialize the model registry
        
        Args:
            event_bus: Event bus instance for publishing model-related events
        """
        self.event_bus = event_bus
        self.models: Dict[str, dict] = {}
        self.model_statuses: Dict[str, str] = {}

    async def register_model(self, model_name: str, model_info: dict) -> None:
        """Register a model with the registry
        
        Args:
            model_name: Name of the model
            model_info: Model information dictionary
        """
        try:
            # Store the model info
            self.models[model_name] = model_info
            self.model_statuses[model_name] = "READY"
            
            # Emit registration event
            await self.event_bus.emit(Event(
                type="model.registered",
                data={
                    "model_name": model_name,
                    "info": model_info
                },
                priority=Priority.LOW
            ))
            logger.info(f"Successfully registered model: {model_name}")
        except Exception as e:
            logger.error(f"Failed to register model {model_name}: {str(e)}")
            raise

    async def update_model_status(self, model_name: str, status: str) -> None:
        """Update the status of a model
        
        Args:
            model_name: Name of the model
            status: New status string
        """
        if model_name in self.models:
            try:
                # Update the status
                self.model_statuses[model_name] = status
                
                # Emit status update event
                await self.event_bus.emit(Event(
                    type="model.status_updated",
                    data={
                        "model_name": model_name,
                        "status": status
                    },
                    priority=Priority.MEDIUM
                ))
                logger.info(f"Updated status for model {model_name}: {status}")
            except Exception as e:
                logger.error(f"Failed to update model status: {str(e)}")
                raise
        else:
            logger.warning(f"Attempted to update status for unregistered model: {model_name}")

    def get_model_info(self, model_name: str) -> Optional[dict]:
        """Get information about a specific model"""
        return self.models.get(model_name)

    def get_model_status(self, model_name: str) -> Optional[str]:
        """Get the current status of a model"""
        return self.model_statuses.get(model_name)

    def list_models(self) -> Dict[str, dict]:
        """Get a copy of all registered models"""
        return self.models.copy()

    def is_model_registered(self, model_name: str) -> bool:
        """Check if a model is registered"""
        return model_name in self.models

    async def unregister_model(self, model_name: str) -> None:
        """Remove a model from the registry
        
        Args:
            model_name: Name of the model to unregister
        """
        if model_name in self.models:
            try:
                # Remove the model
                del self.models[model_name]
                if model_name in self.model_statuses:
                    del self.model_statuses[model_name]
                
                # Emit unregistration event
                await self.event_bus.emit(Event(
                    type="model.unregistered",
                    data={"model_name": model_name},
                    priority=Priority.LOW
                ))
                logger.info(f"Unregistered model: {model_name}")
            except Exception as e:
                logger.error(f"Failed to unregister model: {str(e)}")
                raise
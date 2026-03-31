# src/llm/adapters/ollama_adapter.py

import logging
from typing import Optional
from core.event.event_bus import Event, Priority
from .exceptions import ResourceError

logger = logging.getLogger(__name__)

class OllamaAdapter:
    def __init__(self, event_bus, model_registry, resource_monitor, ollama_client):
        self.event_bus = event_bus
        self.model_registry = model_registry
        self.resource_monitor = resource_monitor
        self.ollama_client = ollama_client
        self._current_model: Optional[str] = None
        self._initialized = False

    async def initialize(self):
        """Initialize the Ollama adapter"""
        try:
            if self._initialized:
                logger.warning("Ollama adapter already initialized")
                return

            # Initialize the Ollama client
            await self.ollama_client.initialize()
            
            # Get available models
            response = await self.ollama_client.list_models()
            
            # Register models with the model registry
            if 'models' in response:
                logger.info(f"Found {len(response['models'])} available models")
                for model in response['models']:
                    model_name = model.get('name')
                    if model_name:
                        try:
                            await self.model_registry.register_model(model_name, model)
                            logger.info(f"Successfully registered model: {model_name}")
                        except Exception as e:
                            logger.error(f"Failed to register model {model_name}: {str(e)}")
            else:
                logger.warning("No models found in Ollama response")
            
            # Subscribe to relevant events
            self.event_bus.subscribe("generate_request", self._handle_generate_request)
            self.event_bus.subscribe("model_status_update", self._handle_model_status_update)
            
            self._initialized = True
            logger.info("Ollama adapter initialized successfully")
            
        except Exception as e:
            logger.error(f"Failed to initialize Ollama adapter: {e}")
            raise

    async def cleanup(self):
        """Cleanup the adapter resources"""
        try:
            if self._current_model:
                # Reset any active model state
                await self.event_bus.emit(Event(
                    type="model_status_update",
                    data={"status": "READY", "model": self._current_model},
                    priority=Priority.HIGH
                ))
            self._initialized = False
            logger.info("Ollama adapter cleaned up successfully")
        except Exception as e:
            logger.error(f"Error during Ollama adapter cleanup: {e}")

    async def _check_resources(self):
        """Check if system resources are sufficient"""
        resources = self.resource_monitor.get_system_resources()
        
        # Define thresholds
        CPU_THRESHOLD = 90  # 90% CPU usage
        MEMORY_THRESHOLD = 85  # 85% memory usage
        
        if resources.get('cpu_percent', 0) > CPU_THRESHOLD:
            raise ResourceError("CPU usage too high")
        
        if resources.get('memory_percent', 0) > MEMORY_THRESHOLD:
            raise ResourceError("Memory usage too high")

    async def _handle_model_status_update(self, event: Event):
        """Handle model status update events"""
        try:
            data = event.data
            model_name = data.get('model')
            status = data.get('status')
            if model_name and status:
                await self.model_registry.update_model_status(model_name, status)
                logger.debug(f"Updated model {model_name} status to {status}")
        except Exception as e:
            logger.error(f"Error handling model status update: {e}")

    async def _handle_generate_request(self, event: Event):
        """Handle generation requests"""
        try:
            # Check resources before processing
            await self._check_resources()
            
            request_data = event.data
            model_name = request_data.get('model')
            prompt = request_data.get('prompt')
            system_prompt = request_data.get('system', '')
            
            logger.info(f"Handling generate request for model: {model_name}")
            
            if not model_name or not prompt:
                raise ValueError("Missing required parameters: model or prompt")
            
            if not self.model_registry.is_model_registered(model_name):
                logger.error(f"Model {model_name} is not registered. Available models: {list(self.model_registry.list_models().keys())}")
                raise ValueError(f"Model {model_name} is not registered")
            
            # Update current model
            self._current_model = model_name
            
            # Emit model status update
            await self.event_bus.emit(Event(
                type="model_status_update",
                data={"status": "PROCESSING", "model": model_name},
                priority=Priority.HIGH
            ))
            
            try:
                # Log the full request
                logger.debug(f"Starting generation with model {model_name}. Prompt: {prompt}, System: {system_prompt}")
                
                # Generate response
                async for response in self.ollama_client.generate_stream(
                    model_name=model_name,
                    prompt=prompt,
                    system_prompt=system_prompt
                ):
                    # Check resources periodically during generation
                    await self._check_resources()
                    
                    # Log the response structure
                    logger.debug(f"Response chunk: {response}")
                    
                    # Emit each chunk
                    await self.event_bus.emit(Event(
                        type="generation_chunk",
                        data=response,
                        priority=Priority.MEDIUM
                    ))
                    
                logger.info(f"Generation completed for model {model_name}")
                
                # Emit completion status
                await self.event_bus.emit(Event(
                    type="model_status_update",
                    data={"status": "READY", "model": model_name},
                    priority=Priority.HIGH
                ))
                
            except Exception as e:
                logger.error(f"Generation error with model {model_name}: {str(e)}")
                raise RuntimeError(f"Generation error: {str(e)}")
                
        except ResourceError as e:
            logger.warning(f"Resource constraint: {e}")
            await self.event_bus.emit(Event(
                type="model_status_update",
                data={"status": "ERROR", "model": self._current_model, "error": str(e)},
                priority=Priority.HIGH
            ))
        except Exception as e:
            logger.error(f"Error handling generation request: {e}")
            await self.event_bus.emit(Event(
                type="model_status_update",
                data={"status": "ERROR", "model": self._current_model, "error": str(e)},
                priority=Priority.HIGH
            ))
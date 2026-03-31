# src/llm/inference/ollama_client.py

import aiohttp
import logging
from typing import Dict, Optional, AsyncGenerator, Any
import json

logger = logging.getLogger(__name__)

class OllamaClient:
    def __init__(self, event_bus, base_url: str = "http://localhost:11434"):
        self.base_url = base_url
        self.event_bus = event_bus
        self._session: Optional[aiohttp.ClientSession] = None

    async def initialize(self):
        """Initialize the Ollama client"""
        try:
            self._session = aiohttp.ClientSession()
            # Test connection
            async with self._session.get(f"{self.base_url}/api/tags") as response:
                if response.status != 200:
                    raise Exception(f"Failed to connect to Ollama service: {response.status}")
            logger.info("Ollama client initialized successfully")
        except Exception as e:
            logger.error(f"Failed to initialize Ollama client: {e}")
            if self._session:
                await self._session.close()
            raise

    async def cleanup(self):
        """Cleanup the Ollama client"""
        if self._session:
            try:
                await self._session.close()
                logger.info("Ollama client cleaned up successfully")
            except Exception as e:
                logger.error(f"Error during Ollama client cleanup: {e}")

    async def get_model_info(self, model_name: str) -> Dict:
        """Get information about a specific model"""
        if not self._session:
            raise Exception("Client not initialized")
        
        try:
            async with self._session.post(
                f"{self.base_url}/api/show",
                json={"name": model_name}
            ) as response:
                if response.status == 200:
                    return await response.json()
                else:
                    error_text = await response.text()
                    raise Exception(f"Failed to get model info: {error_text}")
        except Exception as e:
            logger.error(f"Failed to get model info: {e}")
            raise

    async def generate_stream(
        self,
        model_name: str,
        prompt: str,
        system_prompt: str = "",
        temperature: float = 0.7
    ) -> AsyncGenerator[Dict[str, Any], None]:
        """Generate streaming response from the model"""
        if not self._session:
            raise Exception("Client not initialized")

        payload = {
            "model": model_name,
            "prompt": prompt,
            "system": system_prompt,
            "temperature": temperature,
            "stream": True
        }

        try:
            async with self._session.post(
                f"{self.base_url}/api/generate",
                json=payload
            ) as response:
                async for line in response.content:
                    if line:
                        try:
                            data = json.loads(line)
                            yield data
                        except json.JSONDecodeError:
                            logger.error(f"Failed to decode response: {line}")
        except Exception as e:
            logger.error(f"Error in generate_stream: {e}")
            raise

    async def list_models(self) -> Dict:
        """List all available models"""
        if not self._session:
            raise Exception("Client not initialized")
        
        try:
            async with self._session.get(f"{self.base_url}/api/tags") as response:
                if response.status == 200:
                    return await response.json()
                else:
                    error_text = await response.text()
                    raise Exception(f"Failed to list models: {error_text}")
        except Exception as e:
            logger.error(f"Failed to list models: {e}")
            raise
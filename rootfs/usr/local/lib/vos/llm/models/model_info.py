# src/llm/models/model_info.py

from dataclasses import dataclass
from typing import Optional

@dataclass
class ModelInfo:
    name: str
    size: Optional[int] = None
    format: Optional[str] = None
    family: Optional[str] = None
    parameter_size: Optional[str] = None
    quantization_level: Optional[str] = None

    @classmethod
    def from_ollama_response(cls, response: dict) -> 'ModelInfo':
        return cls(
            name=response.get('name', ''),
            size=response.get('size'),
            format=response.get('format'),
            family=response.get('family'),
            parameter_size=response.get('parameter_size'),
            quantization_level=response.get('quantization_level')
        )
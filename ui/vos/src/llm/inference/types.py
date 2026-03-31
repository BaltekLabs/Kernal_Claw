# src/llm/inference/types.py

from dataclasses import dataclass
from typing import Dict, List, Optional, Union
from enum import Enum

class OllamaModel(Enum):
    """Predefined Ollama models with their roles"""
    BRAIN = "mixtral"              # Main reasoning model
    CODE = "codellama"            # Code generation model
    FAST = "mistral"              # Quick response model
    TINY = "phi"                  # Lightweight tasks

@dataclass
class OllamaRequest:
    """Request structure for Ollama API"""
    model: str
    prompt: str
    system: Optional[str] = None
    template: Optional[str] = None
    context: Optional[List[int]] = None
    stream: bool = False
    raw: bool = False
    format: Optional[str] = None
    options: Optional[Dict] = None

@dataclass
class OllamaResponse:
    """Response structure from Ollama API"""
    model: str
    created_at: str
    response: str
    done: bool
    context: Optional[List[int]] = None
    total_duration: Optional[int] = None
    load_duration: Optional[int] = None
    prompt_eval_duration: Optional[int] = None
    eval_count: Optional[int] = None
    eval_duration: Optional[int] = None

@dataclass
class ModelInfo:
    """Information about a loaded Ollama model"""
    name: str
    size: int
    digest: str
    modified_at: str
    model_file: str
    parameters: str
    template: str
    license: str
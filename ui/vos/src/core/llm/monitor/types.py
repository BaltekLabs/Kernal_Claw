# src/core/llm/monitor/types.py

from dataclasses import dataclass
from datetime import datetime
from enum import Enum, auto
from typing import Dict, List, Optional

class ResourceType(Enum):
    CPU = auto()
    GPU = auto()
    MEMORY = auto()
    GPU_MEMORY = auto()

@dataclass
class CPUStats:
    usage_percent: float            # Overall CPU usage
    per_core_usage: List[float]     # Usage per CPU core
    temperature: Optional[float]     # CPU temperature if available
    frequency: float                # Current CPU frequency
    available_cores: int            # Number of available cores

@dataclass
class GPUStats:
    usage_percent: float            # GPU utilization
    memory_used: int               # Used GPU memory in MB
    memory_total: int              # Total GPU memory in MB
    temperature: Optional[float]    # GPU temperature if available
    power_usage: Optional[float]    # Power usage in watts if available
    device_name: str               # GPU device name

@dataclass
class MemoryStats:
    total: int                     # Total system memory in MB
    available: int                 # Available memory in MB
    used: int                      # Used memory in MB
    cached: int                    # Cached memory in MB
    swap_total: int               # Total swap memory in MB
    swap_used: int                # Used swap memory in MB

@dataclass
class ModelResourceUsage:
    model_id: str
    cpu_usage: float              # CPU usage percentage
    memory_usage: int             # Memory usage in MB
    gpu_memory_usage: Optional[int] # GPU memory usage in MB if applicable
    start_time: datetime          # When the model was loaded
    last_active: datetime         # Last activity timestamp

@dataclass
class SystemResources:
    cpu: CPUStats
    memory: MemoryStats
    gpu: Optional[GPUStats]
    timestamp: datetime
    model_usage: Dict[str, ModelResourceUsage]  # Model ID -> usage stats
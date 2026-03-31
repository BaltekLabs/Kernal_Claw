from enum import Enum, auto
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional, Set

class ModelType(Enum):
    BRAIN = auto()
    CODE_WORKER = auto()
    SYSTEM_WORKER = auto()
    TEXT_WORKER = auto()
    API_MODEL = auto()

class ModelStatus(Enum):
    INITIALIZING = "INITIALIZING"
    READY = "READY"
    BUSY = "BUSY"
    ERROR = "ERROR"
    OFFLINE = "OFFLINE"

class ResourceType(Enum):
    CPU = auto()
    GPU = auto()
    CPU_GPU = auto()

@dataclass
class ModelCapabilities:
    specializations: Set[str]
    max_context_length: int
    supports_streaming: bool
    batch_processing: bool
    average_latency: float = 0.0
    error_rate: float = 0.0

@dataclass
class ResourceRequirements:
    resource_type: ResourceType
    min_memory: int
    preferred_memory: int
    compute_units: int

@dataclass
class ModelInfo:
    model_id: str
    model_type: ModelType
    name: str
    version: str
    capabilities: ModelCapabilities
    resources: ResourceRequirements
    status: ModelStatus = ModelStatus.OFFLINE
    current_load: float = 0.0
    last_error: Optional[str] = None
    last_status_update: datetime = field(default_factory=datetime.now)
    total_requests: int = 0
    successful_requests: int = 0

    def get_success_rate(self) -> float:
        return (self.successful_requests / self.total_requests 
                if self.total_requests > 0 else 0.0)
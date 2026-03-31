# src/core/llm/router/types.py

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum, auto
from typing import Any, Dict, Optional

class TaskStatus(Enum):
    PENDING = auto()
    ASSIGNED = auto()
    PROCESSING = auto()
    COMPLETED = auto()
    FAILED = auto()
    CANCELLED = auto()

class TaskPriority(Enum):
    LOW = 0
    NORMAL = 1
    HIGH = 2
    CRITICAL = 3

class TaskType(Enum):
    CHAT = auto()           # General conversation
    CODE = auto()           # Code generation/analysis
    SYSTEM = auto()         # System operations
    REASONING = auto()      # Complex reasoning tasks
    ANALYSIS = auto()       # Data analysis
    EXTERNAL = auto()       # External API calls

@dataclass
class Task:
    """Represents a task to be processed by the LLM system"""
    task_id: str
    task_type: TaskType
    priority: TaskPriority
    content: Dict[str, Any]  # Task-specific data
    required_capabilities: set[str]
    correlation_id: str
    max_retries: int = 3
    timeout: Optional[float] = None
    created_at: datetime = field(default_factory=datetime.now)
    status: TaskStatus = TaskStatus.PENDING
    assigned_model: Optional[str] = None
    retry_count: int = 0
    error: Optional[str] = None
    result: Optional[Any] = None

@dataclass
class TaskResult:
    """Represents the result of a task execution"""
    task_id: str
    success: bool
    result: Optional[Any] = None
    error: Optional[str] = None
    execution_time: float = 0.0
    model_id: Optional[str] = None
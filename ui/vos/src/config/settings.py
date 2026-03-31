"""
Configuration loader for VoiceOS / Baltek DTE agent.
Reads YAML config from vos/config.yaml with environment variable overrides.
"""

import logging
import os
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

try:
    import yaml
    HAS_YAML = True
except ImportError:
    HAS_YAML = False

# Paths
VOS_DIR = Path(__file__).parents[3]  # ui/vos/
DEFAULT_CONFIG_PATH = VOS_DIR / "config.yaml"
DEFAULT_SKILLS_DIR = VOS_DIR / "skills"
DEFAULT_MEMORY_DIR = VOS_DIR / "memory"


class Settings:
    """
    VoiceOS configuration.
    Merges config.yaml with environment variable overrides.
    """

    def __init__(self, config_path: Optional[str] = None):
        self._path = Path(config_path) if config_path else DEFAULT_CONFIG_PATH
        self._data: Dict[str, Any] = {}
        self.load()

    def load(self) -> None:
        if self._path.exists() and HAS_YAML:
            try:
                self._data = yaml.safe_load(self._path.read_text()) or {}
                logger.info("Config loaded from %s", self._path)
            except Exception as e:
                logger.warning("Failed to load config: %s", e)
                self._data = {}
        else:
            logger.info("No config file found at %s, using defaults", self._path)
            self._data = {}

        self._apply_env_overrides()

    def _apply_env_overrides(self) -> None:
        """Apply environment variable overrides."""
        env_map = {
            "VOICEOS_LLM_PROVIDER": ("llm", "provider"),
            "VOICEOS_LLM_MODEL": ("llm", "model"),
            "VOICEOS_OLLAMA_URL": ("llm", "ollama_url"),
            "OPENAI_API_KEY": ("llm", "openai_api_key"),
            "ANTHROPIC_API_KEY": ("llm", "anthropic_api_key"),
            "GROQ_API_KEY": ("llm", "groq_api_key"),
            "GITHUB_TOKEN": ("remote_skills", "github_token"),
        }
        for env_key, (section, key) in env_map.items():
            val = os.environ.get(env_key)
            if val:
                if section not in self._data:
                    self._data[section] = {}
                self._data[section][key] = val

    def get(self, *keys: str, default: Any = None) -> Any:
        """Navigate nested keys: get('llm', 'provider', default='ollama')"""
        d = self._data
        for k in keys:
            if isinstance(d, dict):
                d = d.get(k)
            else:
                return default
        return d if d is not None else default

    # --- LLM settings ---
    @property
    def llm_provider(self) -> str:
        return self.get("llm", "provider", default="ollama")

    @property
    def llm_model(self) -> Optional[str]:
        return self.get("llm", "model")

    @property
    def ollama_url(self) -> str:
        return self.get("llm", "ollama_url", default="http://localhost:11434")

    @property
    def openai_api_key(self) -> Optional[str]:
        return self.get("llm", "openai_api_key") or os.environ.get("OPENAI_API_KEY")

    @property
    def anthropic_api_key(self) -> Optional[str]:
        return self.get("llm", "anthropic_api_key") or os.environ.get("ANTHROPIC_API_KEY")

    @property
    def groq_api_key(self) -> Optional[str]:
        return self.get("llm", "groq_api_key") or os.environ.get("GROQ_API_KEY")

    # --- Tool settings ---
    @property
    def shell_timeout(self) -> int:
        return int(self.get("tools", "shell_timeout", default=30))

    @property
    def http_timeout(self) -> int:
        return int(self.get("tools", "http_timeout", default=30))

    # --- Paths ---
    @property
    def skills_dirs(self) -> List[str]:
        dirs = self.get("skills", "paths", default=[str(DEFAULT_SKILLS_DIR)])
        if isinstance(dirs, str):
            dirs = [dirs]
        return dirs

    @property
    def memory_dir(self) -> str:
        return self.get("memory", "dir", default=str(DEFAULT_MEMORY_DIR))

    # --- Heartbeat ---
    @property
    def heartbeat_enabled(self) -> bool:
        return bool(self.get("heartbeat", "enabled", default=True))

    @property
    def heartbeat_health_interval(self) -> int:
        return int(self.get("heartbeat", "health_interval_seconds", default=300))

    # --- Remote skills ---
    @property
    def remote_skills(self) -> Dict[str, Any]:
        """
        Returns the full remote_skills config block, e.g.::

            remote_skills:
              enabled: true
              ttl_hours: 24
              github_token: ""
              cache_dir: ""         # defaults to ui/vos/.skill_cache
              sources:
                - owner: openai
                  repo: openai-agents-python
                  path: docs
                - owner: openai
                  repo: openai-cookbook
                  path: articles
        """
        return self.get("remote_skills", default={}) or {}

    # --- UI ---
    @property
    def window_width(self) -> Optional[int]:
        v = self.get("ui", "width")
        return int(v) if v else None

    @property
    def window_height(self) -> Optional[int]:
        v = self.get("ui", "height")
        return int(v) if v else None

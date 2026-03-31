"""
Skill loader — loads skills from markdown files with YAML front-matter.

Skill file format (markdown with YAML front-matter):
---
name: kernel_debug
description: Debug Linux kernel issues
tools: [shell, kernel_log, procfs, sysctl]
triggers: [kernel, crash, dmesg, module, driver, panic]
tags: [linux, kernel, debugging]
---

# Kernel Debug Skill

You are a Linux kernel debugging expert. When the user reports kernel issues...

## Available Actions
- Run `dmesg` to check kernel messages
- Check `/proc/` for system state
...
"""

import logging
import re
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

try:
    import yaml
    HAS_YAML = True
except ImportError:
    HAS_YAML = False


class Skill:
    def __init__(
        self,
        name: str,
        description: str,
        system_prompt: str,
        tools: Optional[List[str]] = None,
        triggers: Optional[List[str]] = None,
        tags: Optional[List[str]] = None,
        source_file: Optional[str] = None,
    ):
        self.name = name
        self.description = description
        self.system_prompt = system_prompt
        self.tools = tools or []
        self.triggers = triggers or []
        self.tags = tags or []
        self.source_file = source_file

    def matches(self, user_input: str) -> bool:
        """Check if this skill matches the user input based on triggers."""
        lower = user_input.lower()
        return any(trigger in lower for trigger in self.triggers)

    def match_score(self, user_input: str) -> float:
        """Return a score (0-1) for how well this skill matches the input."""
        lower = user_input.lower()
        matched = sum(1 for t in self.triggers if t in lower)
        return matched / max(len(self.triggers), 1)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "tools": self.tools,
            "triggers": self.triggers,
            "tags": self.tags,
            "source_file": self.source_file,
        }

    def __repr__(self) -> str:
        return f"<Skill name={self.name} triggers={self.triggers}>"


def parse_skill_file(path: Path) -> Optional[Skill]:
    """Parse a markdown skill file with YAML front-matter."""
    text = path.read_text(encoding="utf-8")

    # Extract YAML front-matter between --- delimiters
    meta: Dict[str, Any] = {}
    body = text

    fm_match = re.match(r"^---\s*\n(.*?)\n---\s*\n(.*)", text, re.DOTALL)
    if fm_match and HAS_YAML:
        try:
            meta = yaml.safe_load(fm_match.group(1)) or {}
            body = fm_match.group(2).strip()
        except yaml.YAMLError as e:
            logger.warning("Failed to parse front-matter in %s: %s", path, e)

    name = meta.get("name") or path.stem
    description = meta.get("description", f"Skill: {name}")
    tools = meta.get("tools", [])
    triggers = meta.get("triggers", [name])
    tags = meta.get("tags", [])

    if not body.strip():
        logger.warning("Skill file %s has no body content", path)
        return None

    return Skill(
        name=name,
        description=description,
        system_prompt=body,
        tools=tools,
        triggers=triggers,
        tags=tags,
        source_file=str(path),
    )


class SkillLoader:
    """Load skills from directories of markdown files."""

    def __init__(self):
        self._search_paths: List[Path] = []

    def add_path(self, path: str) -> None:
        p = Path(path)
        if p.exists():
            self._search_paths.append(p)

    def load_all(self) -> List[Skill]:
        skills = []
        for search_path in self._search_paths:
            for md_file in search_path.rglob("*.md"):
                try:
                    skill = parse_skill_file(md_file)
                    if skill:
                        skills.append(skill)
                        logger.debug("Loaded skill: %s from %s", skill.name, md_file)
                except Exception as e:
                    logger.warning("Failed to load skill from %s: %s", md_file, e)
        return skills

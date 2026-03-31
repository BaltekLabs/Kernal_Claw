"""
Remote skill fetcher — pulls skills from GitHub repos and adapts them to
VoiceOS skill format, with local disk cache (TTL-based).

How it works
------------
1. For each configured source (owner, repo, path) it hits the GitHub
   Contents API to list markdown files.
2. Each file is downloaded (raw) and converted to a Skill:
     - name       : filename stem  (e.g. "tool_use_overview")
     - description: first # heading, or first non-empty line
     - triggers   : words split from the filename + first-heading words
     - system_prompt : full markdown body
3. Results are cached to  <cache_dir>/<owner>__<repo>/<file>.md
   alongside a JSON manifest that stores ETags and fetch timestamps.
4. On the next startup the manifest is checked:  if the file was fetched
   within TTL_HOURS the cached copy is used without a network call.
5. GitHub API rate limit (60 req/hr unauth) is respected by stopping
   early if a 403 is received and falling back to cache.

Configuration (config.yaml):
    remote_skills:
      enabled: true
      ttl_hours: 24
      github_token: ""          # optional — raises rate limit to 5000/hr
      sources:
        - owner: openai
          repo: openai-agents-python
          path: docs
        - owner: openai
          repo: openai-agents-python
          path: examples
        - owner: openai
          repo: openai-cookbook
          path: articles
"""

from __future__ import annotations

import json
import logging
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

# Lazy import — aiohttp is already in requirements.txt
try:
    import aiohttp
    HAS_AIOHTTP = True
except ImportError:
    HAS_AIOHTTP = False

from skills.loader import Skill

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

GITHUB_API = "https://api.github.com"
RAW_BASE = "https://raw.githubusercontent.com"
DEFAULT_TTL_HOURS = 24
MAX_FILE_BYTES = 80_000   # skip huge notebooks / generated files
MANIFEST_NAME = "_manifest.json"

DEFAULT_SOURCES: List[Dict] = [
    {"owner": "openai", "repo": "openai-agents-python", "path": "docs"},
    {"owner": "openai", "repo": "openai-agents-python", "path": "examples"},
    {"owner": "openai", "repo": "openai-cookbook",       "path": "articles"},
]


# ---------------------------------------------------------------------------
# Markdown → Skill conversion
# ---------------------------------------------------------------------------

def _slug_to_words(stem: str) -> List[str]:
    """'tool_use-overview' → ['tool', 'use', 'overview']"""
    return [w.lower() for w in re.split(r"[-_\s]+", stem) if w]


def _extract_title(text: str) -> str:
    """Return text of the first # heading, or first non-empty line."""
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#"):
            return line.lstrip("#").strip()
        if line:
            return line[:120]
    return ""


def markdown_to_skill(
    content: str,
    stem: str,
    source_tag: str,
    source_url: str,
) -> Optional[Skill]:
    """Convert raw markdown content to a Skill object."""
    if not content.strip():
        return None

    title = _extract_title(content)
    description = title or stem.replace("_", " ").replace("-", " ").title()

    # Build triggers from filename words + significant title words
    file_words = _slug_to_words(stem)
    title_words = _slug_to_words(re.sub(r"[^a-z0-9\s_-]", "", title.lower()))
    # de-dupe, keep meaningful words (length > 2)
    seen: set = set()
    triggers: List[str] = []
    for w in file_words + title_words:
        if w not in seen and len(w) > 2:
            seen.add(w)
            triggers.append(w)

    # Prepend a brief context header so the LLM knows where this came from
    system_prompt = (
        f"[Skill sourced from {source_tag} — {source_url}]\n\n"
        + content
    )

    return Skill(
        name=f"{source_tag}/{stem}",
        description=description,
        system_prompt=system_prompt,
        tools=[],          # remote skills don't mandate specific tools
        triggers=triggers,
        tags=[source_tag, "remote"],
        source_file=source_url,
    )


# ---------------------------------------------------------------------------
# Manifest helpers  (cache bookkeeping)
# ---------------------------------------------------------------------------

class _Manifest:
    """Thin wrapper around a JSON file that tracks cached file metadata."""

    def __init__(self, path: Path):
        self._path = path
        self._data: Dict[str, Dict] = {}
        if path.exists():
            try:
                self._data = json.loads(path.read_text())
            except Exception:
                self._data = {}

    def is_fresh(self, filename: str, ttl_hours: float) -> bool:
        entry = self._data.get(filename)
        if not entry:
            return False
        fetched_at = entry.get("fetched_at", "")
        if not fetched_at:
            return False
        try:
            then = datetime.fromisoformat(fetched_at)
            now = datetime.now(timezone.utc)
            # make both offset-aware
            if then.tzinfo is None:
                then = then.replace(tzinfo=timezone.utc)
            return (now - then).total_seconds() < ttl_hours * 3600
        except ValueError:
            return False

    def etag(self, filename: str) -> Optional[str]:
        return (self._data.get(filename) or {}).get("etag")

    def touch(self, filename: str, etag: Optional[str] = None) -> None:
        self._data.setdefault(filename, {})
        self._data[filename]["fetched_at"] = datetime.now(timezone.utc).isoformat()
        if etag:
            self._data[filename]["etag"] = etag

    def save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._path.write_text(json.dumps(self._data, indent=2))


# ---------------------------------------------------------------------------
# Main fetcher
# ---------------------------------------------------------------------------

class RemoteSkillFetcher:
    """
    Async fetcher that pulls markdown skills from GitHub repos.

    Usage::

        fetcher = RemoteSkillFetcher(cache_dir=Path("~/.vos/skill_cache"))
        skills = await fetcher.fetch_all(sources=DEFAULT_SOURCES)
    """

    def __init__(
        self,
        cache_dir: Path,
        ttl_hours: float = DEFAULT_TTL_HOURS,
        github_token: str = "",
    ):
        self.cache_dir = Path(cache_dir).expanduser()
        self.ttl_hours = ttl_hours
        self._token = github_token
        self._session: Optional["aiohttp.ClientSession"] = None

    # ------------------------------------------------------------------
    # Session management
    # ------------------------------------------------------------------

    def _headers(self) -> Dict[str, str]:
        h = {"Accept": "application/vnd.github.v3+json", "User-Agent": "VoiceOS-SkillFetcher/1.0"}
        if self._token:
            h["Authorization"] = f"Bearer {self._token}"
        return h

    async def _get_session(self) -> "aiohttp.ClientSession":
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(headers=self._headers())
        return self._session

    async def close(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()

    # ------------------------------------------------------------------
    # GitHub API helpers
    # ------------------------------------------------------------------

    async def _list_md_files(
        self, owner: str, repo: str, path: str
    ) -> List[Dict]:
        """
        Return list of {name, download_url, size, sha} for .md files
        at owner/repo/path via the GitHub Contents API.
        Returns [] on any error (rate-limit, 404, network, …).
        """
        if not HAS_AIOHTTP:
            return []
        session = await self._get_session()
        url = f"{GITHUB_API}/repos/{owner}/{repo}/contents/{path}"
        try:
            async with session.get(url, timeout=aiohttp.ClientTimeout(total=15)) as resp:
                if resp.status == 403:
                    logger.warning("GitHub rate limit hit fetching %s/%s/%s", owner, repo, path)
                    return []
                if resp.status == 404:
                    logger.debug("Path not found: %s/%s/%s", owner, repo, path)
                    return []
                resp.raise_for_status()
                items = await resp.json()
                if not isinstance(items, list):
                    return []
                return [
                    i for i in items
                    if i.get("type") == "file"
                    and i.get("name", "").endswith(".md")
                    and i.get("size", 0) <= MAX_FILE_BYTES
                ]
        except Exception as exc:
            logger.debug("Failed listing %s/%s/%s: %s", owner, repo, path, exc)
            return []

    async def _fetch_raw(
        self, raw_url: str, cached_path: Path, etag: Optional[str]
    ) -> Tuple[Optional[str], Optional[str]]:
        """
        Download raw content.  Returns (content, new_etag) or (None, None)
        on error / 304-not-modified (caller should use cache).
        """
        if not HAS_AIOHTTP:
            return None, None
        session = await self._get_session()
        req_headers: Dict[str, str] = {}
        if etag:
            req_headers["If-None-Match"] = etag
        try:
            async with session.get(
                raw_url, headers=req_headers,
                timeout=aiohttp.ClientTimeout(total=20)
            ) as resp:
                if resp.status == 304:
                    # Not modified — use cached file
                    return None, etag
                if resp.status == 403:
                    logger.warning("Rate limited fetching %s", raw_url)
                    return None, None
                resp.raise_for_status()
                content = await resp.text(encoding="utf-8", errors="replace")
                new_etag = resp.headers.get("ETag")
                return content, new_etag
        except Exception as exc:
            logger.debug("Failed fetching %s: %s", raw_url, exc)
            return None, None

    # ------------------------------------------------------------------
    # Per-source fetch
    # ------------------------------------------------------------------

    async def _fetch_source(
        self, owner: str, repo: str, path: str
    ) -> List[Skill]:
        source_tag = f"{owner}/{repo}"
        cache_subdir = self.cache_dir / f"{owner}__{repo}"
        cache_subdir.mkdir(parents=True, exist_ok=True)
        manifest = _Manifest(cache_subdir / MANIFEST_NAME)

        skills: List[Skill] = []

        # List remote files
        remote_files = await self._list_md_files(owner, repo, path)

        # Also scan local cache so we can serve cached skills even if
        # the listing call failed (offline / rate-limited)
        cached_md = {f.name: f for f in cache_subdir.glob("*.md")}

        # Build set of filenames to process (remote ∪ cache)
        all_names: set = {f["name"] for f in remote_files} | set(cached_md.keys())

        remote_by_name = {f["name"]: f for f in remote_files}

        for filename in sorted(all_names):
            cached_path = cache_subdir / filename
            stem = Path(filename).stem

            # Check if we can use cache
            if manifest.is_fresh(filename, self.ttl_hours) and cached_path.exists():
                content = cached_path.read_text(encoding="utf-8", errors="replace")
                skill = markdown_to_skill(
                    content, stem, source_tag,
                    f"https://github.com/{owner}/{repo}/blob/main/{path}/{filename}"
                )
                if skill:
                    skills.append(skill)
                    logger.debug("Skill from cache: %s", skill.name)
                continue

            # Need to fetch / refresh
            info = remote_by_name.get(filename)
            if not info:
                # Only in cache, not in remote listing (probably rate-limited listing)
                if cached_path.exists():
                    content = cached_path.read_text(encoding="utf-8", errors="replace")
                    skill = markdown_to_skill(
                        content, stem, source_tag,
                        f"https://github.com/{owner}/{repo}/blob/main/{path}/{filename}"
                    )
                    if skill:
                        skills.append(skill)
                continue

            raw_url = info.get("download_url") or (
                f"{RAW_BASE}/{owner}/{repo}/main/{path}/{filename}"
            )
            content, new_etag = await self._fetch_raw(
                raw_url, cached_path, manifest.etag(filename)
            )

            if content is None and cached_path.exists():
                # 304 not-modified or error — use existing cache
                content = cached_path.read_text(encoding="utf-8", errors="replace")
                manifest.touch(filename, new_etag or manifest.etag(filename))
            elif content:
                cached_path.write_text(content, encoding="utf-8")
                manifest.touch(filename, new_etag)
            else:
                logger.debug("Skipping %s — no content available", filename)
                continue

            skill = markdown_to_skill(
                content, stem, source_tag,
                f"https://github.com/{owner}/{repo}/blob/main/{path}/{filename}"
            )
            if skill:
                skills.append(skill)
                logger.debug("Fetched skill: %s", skill.name)

        manifest.save()
        return skills

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def fetch_all(
        self, sources: Optional[List[Dict]] = None
    ) -> List[Skill]:
        """
        Fetch (or load from cache) skills from all sources.
        Returns list of Skill objects.  Never raises — errors are logged.
        """
        if not HAS_AIOHTTP:
            logger.warning("aiohttp not available — remote skills disabled")
            return []

        if sources is None:
            sources = DEFAULT_SOURCES

        all_skills: List[Skill] = []
        for src in sources:
            try:
                batch = await self._fetch_source(
                    src["owner"], src["repo"], src.get("path", "")
                )
                all_skills.extend(batch)
                logger.info(
                    "Remote skills from %s/%s/%s: %d",
                    src["owner"], src["repo"], src.get("path", ""), len(batch)
                )
            except Exception as exc:
                logger.warning("Error fetching remote skills from %s: %s", src, exc)

        await self.close()
        return all_skills

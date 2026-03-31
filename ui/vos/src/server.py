"""
server.py — VoiceOS Launcher FastAPI backend
=============================================
Replaces the Pygame main loop with an HTTP + WebSocket server.
The existing agent engine, LLM providers, tools, and skills are
kept intact; only the UI layer changes (web instead of Pygame).

Endpoints:
  GET  /                 → serve launcher web UI (index.html)
  GET  /static/*         → serve CSS/JS assets from web/
  WS   /ws               → real-time streaming (agent → browser)
  POST /api/chat         → send message (non-streaming fallback)
  GET  /api/status       → current provider/model/tool/skill status
  POST /api/provider     → switch LLM provider/model
  POST /api/clear        → clear conversation context
  GET  /api/apps         → list installed apps (Android / stub)
  POST /api/launch       → launch an app by package name
"""

import asyncio
import json
import logging
import platform
import sys
from pathlib import Path
from typing import Optional, Set

# FastAPI
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

# Ensure src/ is on the path
_src = Path(__file__).parent
if str(_src) not in sys.path:
    sys.path.insert(0, str(_src))

from config.settings import Settings
from core.event.event_bus import EventBus, Event, Priority
from core.llm.monitor import ResourceMonitor
from core.llm.registry import ModelRegistry
from core.llm.router import TaskRouter
from llm.providers import (
    OllamaProvider,
    OpenAIProvider,
    AnthropicProvider,
    GroqProvider,
    LLMSwitcher,
)
from tools import create_default_registry
from skills import SkillRegistry
from agent.engine import AgentEngine
from agent.heartbeat import Heartbeat, make_system_health_task

logger = logging.getLogger(__name__)

# ── Paths ────────────────────────────────────────────────────────
_vos_dir = Path(__file__).parents[2]       # ui/vos/
_web_dir = _vos_dir / "web"
_src_dir = Path(__file__).parent            # ui/vos/src/

app = FastAPI(title="VoiceOS Launcher", docs_url=None, redoc_url=None)

# ── Serve static assets ──────────────────────────────────────────
app.mount("/static", StaticFiles(directory=str(_web_dir)), name="static")


@app.get("/")
async def serve_index():
    return FileResponse(_web_dir / "index.html")


# ── App state (initialised in lifespan) ─────────────────────────
class AppState:
    settings: Settings
    event_bus: EventBus
    model_registry: ModelRegistry
    resource_monitor: ResourceMonitor
    task_router: TaskRouter
    llm_switcher: LLMSwitcher
    tool_registry: any
    skill_registry: SkillRegistry
    agent: AgentEngine
    heartbeat: Heartbeat
    active_connections: Set[WebSocket]

    def __init__(self):
        self.active_connections = set()


state = AppState()


# ── Lifespan (startup / shutdown) ────────────────────────────────
@app.on_event("startup")
async def startup():
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s %(name)s %(levelname)s %(message)s'
    )

    state.settings = Settings()

    state.event_bus      = EventBus()
    state.model_registry = ModelRegistry(state.event_bus)
    state.resource_monitor = ResourceMonitor(state.event_bus)
    state.task_router    = TaskRouter(state.event_bus, state.model_registry)

    state.llm_switcher = _build_llm_switcher(state.settings, state.event_bus)

    memory_dir = _vos_dir / "memory"
    state.tool_registry = create_default_registry(memory_dir=str(memory_dir))

    state.skill_registry = SkillRegistry()

    state.agent = AgentEngine(
        llm_switcher=state.llm_switcher,
        tool_registry=state.tool_registry,
        skill_registry=state.skill_registry,
        on_stream=_make_stream_cb(),
        on_tool_call=_make_tool_call_cb(),
        on_tool_result=None,
        on_skill_selected=_make_skill_cb(),
        on_provider_switch=None,
    )

    state.heartbeat = Heartbeat(event_bus=state.event_bus)

    # Start subsystems
    await state.event_bus.start()
    await state.resource_monitor.start()
    await state.task_router.start()
    await state.llm_switcher.initialize_all()
    await state.tool_registry.initialize_all()

    # Load skills
    skills_dirs = [str(_vos_dir / "skills")] + (state.settings.skills_dirs or [])
    count = state.skill_registry.load_from_dirs(*skills_dirs)
    logger.info("Skills loaded: %d", count)

    remote_cfg = getattr(state.settings, "remote_skills", {}) or {}
    if remote_cfg.get("enabled", True):
        cache_dir = remote_cfg.get("cache_dir") or str(_vos_dir / ".skill_cache")
        try:
            rc = await state.skill_registry.load_remote(
                cache_dir=cache_dir,
                sources=remote_cfg.get("sources") or None,
                ttl_hours=float(remote_cfg.get("ttl_hours", 24)),
                github_token=remote_cfg.get("github_token", ""),
            )
            logger.info("Remote skills: %d", rc)
        except Exception as e:
            logger.warning("Remote skill fetch failed: %s", e)

    if state.settings.heartbeat_enabled:
        health_task = make_system_health_task(
            tool_registry=state.tool_registry,
            event_bus=state.event_bus,
            interval_seconds=state.settings.heartbeat_health_interval,
        )
        state.heartbeat.register(health_task)
        await state.heartbeat.start()

    logger.info("VoiceOS server ready — LLM: %s", state.llm_switcher.status())


@app.on_event("shutdown")
async def shutdown():
    await state.heartbeat.stop()
    await state.tool_registry.cleanup_all()
    await state.llm_switcher.cleanup_all()
    await state.task_router.stop()
    await state.resource_monitor.stop()
    await state.event_bus.stop()


# ── LLM builder (mirrors main.py) ────────────────────────────────
def _build_llm_switcher(settings: Settings, event_bus: EventBus) -> LLMSwitcher:
    switcher = LLMSwitcher(event_bus=event_bus)
    model = settings.llm_model or "mistral:latest"
    switcher.register("ollama", OllamaProvider(model=model, base_url=settings.ollama_url))
    if settings.openai_api_key:
        switcher.register("openai", OpenAIProvider(model="gpt-4o-mini", api_key=settings.openai_api_key))
    if settings.anthropic_api_key:
        switcher.register("anthropic", AnthropicProvider(model="claude-sonnet-4-6", api_key=settings.anthropic_api_key))
    if settings.groq_api_key:
        switcher.register("groq", GroqProvider(model="llama-3.3-70b-versatile", api_key=settings.groq_api_key))
    active = settings.llm_provider
    if active in switcher.list_providers():
        switcher.use(active)
    return switcher


# ── WebSocket broadcast helpers ───────────────────────────────────
def _broadcast(msg: dict):
    """Fire-and-forget broadcast to all active WS connections."""
    data = json.dumps(msg)
    dead = set()
    for ws in list(state.active_connections):
        try:
            asyncio.ensure_future(ws.send_text(data))
        except Exception:
            dead.add(ws)
    state.active_connections -= dead


def _make_stream_cb():
    def cb(chunk: str):
        _broadcast({"type": "chunk", "text": chunk})
    return cb


def _make_tool_call_cb():
    def cb(tool_name: str, arguments: dict):
        _broadcast({"type": "tool_call", "tool": tool_name})
    return cb


def _make_skill_cb():
    def cb(skill_name: str):
        _broadcast({"type": "skill", "skill": skill_name})
    return cb


# ── WebSocket endpoint ────────────────────────────────────────────
@app.websocket("/ws")
async def ws_endpoint(websocket: WebSocket):
    await websocket.accept()
    state.active_connections.add(websocket)

    # Send initial status on connect
    try:
        s = state.llm_switcher.status()
        await websocket.send_text(json.dumps({
            "type": "status",
            "active_provider": s["active_provider"],
            "active_model": s["active_model"],
        }))
    except Exception:
        pass

    try:
        while True:
            raw = await websocket.receive_text()
            msg = json.loads(raw)

            if msg.get("type") == "chat":
                text = msg.get("text", "").strip()
                if not text:
                    continue
                try:
                    resp = await state.agent.process(text)
                    s = state.llm_switcher.status()
                    await websocket.send_text(json.dumps({
                        "type": "done",
                        "provider": s["active_provider"],
                        "model": s["active_model"],
                    }))
                except Exception as e:
                    await websocket.send_text(json.dumps({"type": "error", "text": str(e)}))

    except WebSocketDisconnect:
        pass
    finally:
        state.active_connections.discard(websocket)


# ── REST endpoints ────────────────────────────────────────────────
@app.post("/api/chat")
async def api_chat(body: dict):
    """Non-streaming fallback for environments without WebSocket support."""
    text = (body.get("text") or "").strip()
    if not text:
        return JSONResponse({"error": "empty input"}, status_code=400)
    try:
        resp = await state.agent.process(text)
        s = state.llm_switcher.status()
        return {"text": resp.text, "error": resp.error,
                "provider": s["active_provider"], "model": s["active_model"]}
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.get("/api/status")
async def api_status():
    s = state.llm_switcher.status()
    return {
        "active_provider": s["active_provider"],
        "active_model":    s["active_model"],
        "supports_tools":  s["supports_tools"],
        "providers":       state.llm_switcher.list_providers(),
        "tools":           state.tool_registry.list_tools(),
        "skills":          state.skill_registry.list_skills(),
    }


@app.post("/api/provider")
async def api_switch_provider(body: dict):
    provider = body.get("provider")
    model    = body.get("model") or None
    try:
        state.llm_switcher.use(provider, model)
        s = state.llm_switcher.status()
        # Notify all WS clients
        _broadcast({"type": "status",
                    "active_provider": s["active_provider"],
                    "active_model":    s["active_model"]})
        return {"active_provider": s["active_provider"], "active_model": s["active_model"]}
    except KeyError:
        return JSONResponse({"error": f"Unknown provider: {provider}"}, status_code=400)


@app.post("/api/clear")
async def api_clear():
    state.agent.clear_context()
    return {"ok": True}


# ── App drawer ────────────────────────────────────────────────────
@app.get("/api/apps")
async def api_apps():
    """
    Return list of installed apps.
    On Android (via Chaquopy/p4a) this queries PackageManager.
    Falls back to a set of useful web-accessible apps when not on Android.
    """
    apps = _get_installed_apps()
    return apps


@app.post("/api/launch")
async def api_launch(body: dict):
    pkg = body.get("package", "")
    success = _launch_app(pkg)
    return {"ok": success, "package": pkg}


def _get_installed_apps():
    """Query installed apps. Android-native via jnius/Chaquopy if available."""
    try:
        # Android path: use jnius to call PackageManager
        from jnius import autoclass  # type: ignore
        PythonActivity  = autoclass('org.kivy.android.PythonActivity')
        PackageManager  = autoclass('android.content.pm.PackageManager')
        Intent          = autoclass('android.content.Intent')
        pm = PythonActivity.mActivity.getPackageManager()
        intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        activities = pm.queryIntentActivities(intent, 0)
        apps = []
        for ri in activities.toArray():
            info = ri.activityInfo.applicationInfo
            name = str(pm.getApplicationLabel(info))
            pkg  = str(info.packageName)
            apps.append({"name": name, "package": pkg, "emoji": None, "icon": None})
        apps.sort(key=lambda x: x["name"].lower())
        return apps
    except ImportError:
        pass
    except Exception as e:
        logger.warning("Android app list failed: %s", e)

    # Fallback: curated list of common apps / shortcuts
    return [
        {"name": "Chrome",       "package": "com.android.chrome",          "emoji": "🌐"},
        {"name": "Settings",     "package": "com.android.settings",        "emoji": "⚙️"},
        {"name": "Camera",       "package": "com.android.camera2",         "emoji": "📷"},
        {"name": "Messages",     "package": "com.google.android.apps.messaging", "emoji": "💬"},
        {"name": "Phone",        "package": "com.android.dialer",          "emoji": "📞"},
        {"name": "Maps",         "package": "com.google.android.apps.maps","emoji": "🗺️"},
        {"name": "YouTube",      "package": "com.google.android.youtube",  "emoji": "▶️"},
        {"name": "Calculator",   "package": "com.android.calculator2",     "emoji": "🔢"},
        {"name": "Clock",        "package": "com.android.deskclock",       "emoji": "⏰"},
        {"name": "Files",        "package": "com.android.documentsui",     "emoji": "📁"},
        {"name": "UserLAnd",     "package": "tech.ula",                    "emoji": "🐧"},
        {"name": "Termux",       "package": "com.termux",                  "emoji": "💻"},
    ]


def _launch_app(package: str) -> bool:
    """Launch an Android app by package name."""
    try:
        from jnius import autoclass  # type: ignore
        PythonActivity = autoclass('org.kivy.android.PythonActivity')
        Intent         = autoclass('android.content.Intent')
        context = PythonActivity.mActivity
        pm = context.getPackageManager()
        launch_intent = pm.getLaunchIntentForPackage(package)
        if launch_intent:
            context.startActivity(launch_intent)
            return True
    except ImportError:
        pass
    except Exception as e:
        logger.warning("Launch failed (%s): %s", package, e)
    return False


# ── Entry point ───────────────────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    port = int(getattr(state.settings if hasattr(state, 'settings') else type('', (), {'server_port': 8741})(), 'server_port', 8741))
    uvicorn.run("server:app", host="127.0.0.1", port=8741, log_level="info")

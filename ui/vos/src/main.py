"""
VoiceOS — Baltek DTE AI Terminal
=================================
OpenClaw-inspired agent with:
  - Multi-LLM providers (Ollama, OpenAI, Anthropic, Groq) with hot-swap
  - Full tool set (shell, filesystem, kernel, HTTP, memory, system_info)
  - Modular skills loaded from markdown files
  - Agentic tool-calling loop
  - Proactive heartbeat
  - Animated Pygame UI
"""

import asyncio
import logging
import sys
from pathlib import Path
from typing import Optional

import pygame

# Ensure src/ is on the path when running directly
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

from ui.circle import Circle, CircleState, CircleConfig
from ui.text_input import TextInput
from ui.components.content_display import ContentDisplay


class VoiceOS:
    def __init__(self, config_path: Optional[str] = None):
        # Setup logging
        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
        )
        self.logger = logging.getLogger(__name__)

        # Load configuration
        self.settings = Settings(config_path)

        # Initialize Pygame
        pygame.init()
        pygame.display.set_caption("VoiceOS — Baltek DTE")
        screen_info = pygame.display.Info()
        self.width = self.settings.window_width or int(screen_info.current_w * 0.8)
        self.height = self.settings.window_height or int(screen_info.current_h * 0.8)
        self.screen = pygame.display.set_mode(
            (self.width, self.height),
            pygame.RESIZABLE | pygame.DOUBLEBUF
        )
        pygame.event.set_allowed([
            pygame.QUIT, pygame.KEYDOWN, pygame.MOUSEBUTTONDOWN,
            pygame.MOUSEWHEEL, pygame.VIDEORESIZE,
        ])
        self.clock = pygame.time.Clock()

        # Initialize UI components
        self.circle = Circle(self.screen, CircleConfig(
            radius=150,
            num_points=300,
            line_thickness=2
        ))
        self.text_input = TextInput(self.screen)
        self.content_display = ContentDisplay(self.screen, self.circle)

        # Core infrastructure
        self.event_bus = EventBus()
        self.model_registry = ModelRegistry(self.event_bus)
        self.resource_monitor = ResourceMonitor(self.event_bus)
        self.task_router = TaskRouter(self.event_bus, self.model_registry)

        # LLM providers + switcher (OpenClaw-style multi-provider)
        self.llm_switcher = self._build_llm_switcher()

        # Tool registry (full kernel + system + network toolset)
        skills_dir = Path(self.settings.skills_dirs[0]) if self.settings.skills_dirs else None
        vos_dir = Path(__file__).parents[2]  # ui/vos/
        memory_dir = vos_dir / "memory"
        self.tool_registry = create_default_registry(memory_dir=str(memory_dir))

        # Skill registry (loaded from markdown files)
        self.skill_registry = SkillRegistry()

        # Agent engine — the core agentic loop
        self.agent = AgentEngine(
            llm_switcher=self.llm_switcher,
            tool_registry=self.tool_registry,
            skill_registry=self.skill_registry,
            on_stream=self._on_agent_stream,
            on_tool_call=self._on_tool_call,
            on_tool_result=self._on_tool_result,
            on_skill_selected=self._on_skill_selected,
            on_provider_switch=self._on_provider_switch,
        )

        # Heartbeat — proactive background tasks
        self.heartbeat = Heartbeat(event_bus=self.event_bus)

        self.running = False
        self._response_started = False

        self._setup_event_handlers()

    # ------------------------------------------------------------------
    # LLM provider setup
    # ------------------------------------------------------------------

    def _build_llm_switcher(self) -> LLMSwitcher:
        switcher = LLMSwitcher(event_bus=self.event_bus)

        # Ollama — always available (local, no key required)
        model = self.settings.llm_model or "mistral:latest"
        switcher.register(
            "ollama",
            OllamaProvider(model=model, base_url=self.settings.ollama_url),
        )

        # OpenAI — if API key is set
        if self.settings.openai_api_key:
            switcher.register(
                "openai",
                OpenAIProvider(model="gpt-4o-mini", api_key=self.settings.openai_api_key),
            )

        # Anthropic — if API key is set
        if self.settings.anthropic_api_key:
            switcher.register(
                "anthropic",
                AnthropicProvider(model="claude-sonnet-4-6", api_key=self.settings.anthropic_api_key),
            )

        # Groq — if API key is set
        if self.settings.groq_api_key:
            switcher.register(
                "groq",
                GroqProvider(model="llama-3.3-70b-versatile", api_key=self.settings.groq_api_key),
            )

        # Activate configured provider
        active = self.settings.llm_provider
        if active in switcher.list_providers():
            switcher.use(active)

        return switcher

    # ------------------------------------------------------------------
    # Event handlers
    # ------------------------------------------------------------------

    def _setup_event_handlers(self):
        self.event_bus.subscribe("text_input", self._handle_text_input)
        self.event_bus.subscribe("generation_chunk", self._handle_generation_chunk)
        self.event_bus.subscribe("model_status_update", self._handle_model_status)
        self.event_bus.subscribe("resource_warning", self._handle_resource_warning)
        self.event_bus.subscribe("system_alert", self._handle_system_alert)
        self.logger.info("Event handlers setup complete")

    async def _handle_text_input(self, event: Event):
        """Route user text through the agent engine."""
        text = event.data
        self.circle.set_state(CircleState.LISTENING)
        self.content_display.clear_content()
        self._response_started = False

        try:
            self.circle.set_state(CircleState.PROCESSING)
            await self.agent.process(text)
            self.circle.set_state(CircleState.RESPONDING)
            await asyncio.sleep(0.3)
            self.circle.set_state(CircleState.IDLE)
        except Exception as e:
            self.logger.error(f"Error processing input: {e}")
            self.content_display.append_content(f"\n[Error: {e}]")
            self.circle.set_state(CircleState.ERROR)
            await asyncio.sleep(1.0)
            self.circle.set_state(CircleState.IDLE)

    async def _handle_generation_chunk(self, event: Event):
        """Legacy streaming handler — kept for backward compatibility."""
        response = event.data
        if isinstance(response, dict):
            chunk = response.get('response', '')
            done = response.get('done', False)
            if chunk and not done:
                if not self._response_started:
                    self.content_display.clear_content()
                    self._response_started = True
                self.content_display.append_content(chunk)
            elif done:
                self.circle.set_state(CircleState.RESPONDING)
                await asyncio.sleep(0.3)
                self.circle.set_state(CircleState.IDLE)

    async def _handle_model_status(self, event: Event):
        status = event.data.get('status')
        if status == 'PROCESSING':
            self.circle.set_state(CircleState.PROCESSING)
        elif status == 'READY':
            self.circle.set_state(CircleState.IDLE)
        elif status == 'ERROR':
            self.circle.set_state(CircleState.ERROR)

    async def _handle_resource_warning(self, event: Event):
        warnings = event.data.get('warnings', [])
        for warning in warnings:
            self.logger.warning(f"Resource warning: {warning}")

    async def _handle_system_alert(self, event: Event):
        alerts = event.data.get('alerts', [])
        if alerts:
            self.content_display.append_content("\n[ALERT] " + " | ".join(alerts))

    # ------------------------------------------------------------------
    # Agent callbacks
    # ------------------------------------------------------------------

    def _on_agent_stream(self, chunk: str):
        """Called by agent engine with each text chunk to display."""
        if not self._response_started:
            self.content_display.clear_content()
            self._response_started = True
        self.content_display.append_content(chunk)

    def _on_tool_call(self, tool_name: str, arguments: dict):
        self.logger.info("Tool call: %s", tool_name)
        self.content_display.append_content(f"\n[{tool_name}...]\n")

    def _on_tool_result(self, tool_name: str, result: str):
        self.logger.debug("Tool result (%s): %d chars", tool_name, len(result))

    def _on_skill_selected(self, skill_name: str):
        self.logger.info("Skill: %s", skill_name)
        self.content_display.append_content(f"[skill:{skill_name}] ")

    def _on_provider_switch(self, provider: str, model: str):
        self.logger.info("Switched to %s / %s", provider, model)

    # ------------------------------------------------------------------
    # Initialization & cleanup
    # ------------------------------------------------------------------

    async def initialize_llm_system(self):
        """Initialize all subsystems."""
        try:
            await self.event_bus.start()
            await self.resource_monitor.start()
            await self.task_router.start()

            # Initialize LLM providers
            await self.llm_switcher.initialize_all()
            self.logger.info("LLM switcher ready: %s", self.llm_switcher.status())

            # Initialize tools
            await self.tool_registry.initialize_all()
            self.logger.info("Tools: %s", self.tool_registry.list_tools())

            # Load skills from all configured directories
            vos_dir = Path(__file__).parents[2]
            skills_dirs = [str(vos_dir / "skills")] + self.settings.skills_dirs
            count = self.skill_registry.load_from_dirs(*skills_dirs)
            self.logger.info("Skills loaded: %d", count)

            # Pull remote skills from OpenAI (and any configured) repos
            remote_cfg = getattr(self.settings, "remote_skills", {}) or {}
            if remote_cfg.get("enabled", True):
                cache_dir = remote_cfg.get("cache_dir") or str(vos_dir / ".skill_cache")
                remote_count = await self.skill_registry.load_remote(
                    cache_dir=cache_dir,
                    sources=remote_cfg.get("sources") or None,
                    ttl_hours=float(remote_cfg.get("ttl_hours", 24)),
                    github_token=remote_cfg.get("github_token", ""),
                )
                self.logger.info("Remote skills added: %d", remote_count)

            # Start heartbeat
            if self.settings.heartbeat_enabled:
                health_task = make_system_health_task(
                    tool_registry=self.tool_registry,
                    event_bus=self.event_bus,
                    interval_seconds=self.settings.heartbeat_health_interval,
                )
                self.heartbeat.register(health_task)
                await self.heartbeat.start()

            self.logger.info("VoiceOS initialized")

            # Welcome message
            status = self.llm_switcher.status()
            welcome = (
                f"VoiceOS ready.\n"
                f"Provider: {status['active_provider']} / {status['active_model']}\n"
                f"Tools: {len(self.tool_registry.list_tools())} | "
                f"Skills: {len(self.skill_registry.list_skills())}\n\n"
                "Type a message and press Enter.\n"
                "Try: 'system info'  |  'check kernel logs'  |  'use claude'  |  'help'"
            )
            self.content_display.append_content(welcome)

        except Exception as e:
            self.logger.error(f"Initialization error: {e}")
            self.content_display.append_content(f"[Init error: {e}]\nRunning with limited features.")

    async def cleanup_llm_system(self):
        """Shutdown all subsystems."""
        try:
            await self.heartbeat.stop()
            await self.tool_registry.cleanup_all()
            await self.llm_switcher.cleanup_all()
            await self.task_router.stop()
            await self.resource_monitor.stop()
            await self.event_bus.stop()
            self.logger.info("LLM system cleanup complete")
        except Exception as e:
            self.logger.error(f"Cleanup error: {e}")

    # ------------------------------------------------------------------
    # Main loop
    # ------------------------------------------------------------------

    async def _handle_resize(self, new_width: int, new_height: int):
        self.width = max(800, new_width)
        self.height = max(600, new_height)
        self.screen = pygame.display.set_mode(
            (self.width, self.height), pygame.RESIZABLE | pygame.DOUBLEBUF
        )
        self.circle.update_screen(self.screen)
        self.text_input.update_screen(self.screen)
        self.content_display.update_screen(self.screen)

    async def _process_pygame_events(self):
        try:
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    self.running = False
                elif event.type == pygame.VIDEORESIZE:
                    await self._handle_resize(event.w, event.h)
                elif event.type == pygame.MOUSEWHEEL and self.content_display.active:
                    self.content_display.handle_scroll(event.y)
                elif event.type == pygame.MOUSEBUTTONDOWN:
                    if event.button == 1 and self.content_display.active:
                        self.content_display.handle_click(event.pos)
                elif event.type == pygame.KEYDOWN:
                    if event.key == pygame.K_ESCAPE:
                        self.running = False
                    else:
                        completed_text = self.text_input.handle_event(event)
                        if completed_text:
                            self.logger.info(f"Input: {completed_text}")
                            await self.event_bus.emit(Event(
                                type="text_input",
                                data=completed_text,
                                priority=Priority.HIGH
                            ))
        except Exception as e:
            self.logger.error(f"Pygame event error: {e}")

    async def _update(self):
        dt = self.clock.get_time() / 1000.0
        self.circle.update(dt)
        self.text_input.update(dt)
        self.content_display.update(dt)

    async def _draw(self):
        self.screen.fill((0, 0, 0))
        self.circle.draw()
        self.content_display.draw()
        self.text_input.draw()
        pygame.display.flip()

    async def run(self):
        self.running = True
        try:
            await self.initialize_llm_system()
            while self.running:
                await self._process_pygame_events()
                await self._update()
                await self._draw()
                await asyncio.sleep(0)
                self.clock.tick(60)
        except Exception as e:
            self.logger.error(f"Runtime error: {e}")
        finally:
            self.logger.info("Shutting down")
            await self.cleanup_llm_system()
            pygame.quit()


def main():
    app = VoiceOS()
    asyncio.run(app.run())


if __name__ == "__main__":
    main()

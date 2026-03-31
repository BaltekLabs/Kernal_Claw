import asyncio
import os
import pygame
import sys
import logging
from typing import Optional

from core.event.event_bus import EventBus, Event, Priority
from llm.adapters import AicoreAdapter
from ui.circle import Circle, CircleState, CircleConfig
from ui.text_input import TextInput
from ui.components.content_display import ContentDisplay


class VoiceOS:
    def __init__(self, width: int = None, height: int = None):
        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
        )
        self.logger = logging.getLogger(__name__)

        pygame.init()
        pygame.display.set_caption("VoiceOS")

        fullscreen = os.environ.get('VOS_FULLSCREEN', '0') == '1'
        os.environ['SDL_VIDEO_WINDOW_ALWAYS_ON_TOP'] = '1'

        if fullscreen:
            self.screen = pygame.display.set_mode((0, 0), pygame.FULLSCREEN | pygame.DOUBLEBUF)
            self.width, self.height = self.screen.get_size()
            if self.width == 0 or self.height == 0:
                self.width, self.height = 1024, 768
                self.screen = pygame.display.set_mode(
                    (self.width, self.height), pygame.FULLSCREEN | pygame.DOUBLEBUF
                )
        else:
            screen_info = pygame.display.Info()
            self.width = width or 400
            self.height = height or 600
            self.screen = pygame.display.set_mode(
                (self.width, self.height),
                pygame.RESIZABLE | pygame.DOUBLEBUF | pygame.NOFRAME
            )

        try:
            import pygame._sdl2.video as video
            self.window = video.Window.from_display_module()
        except Exception:
            self.window = None
        
        self.dragging = False

        pygame.event.set_allowed([
            pygame.QUIT, pygame.KEYDOWN,
            pygame.MOUSEBUTTONDOWN, pygame.MOUSEBUTTONUP, pygame.MOUSEMOTION,
            pygame.MOUSEWHEEL, pygame.VIDEORESIZE
        ])

        self.clock = pygame.time.Clock()

        self.circle = Circle(self.screen, CircleConfig(
            radius=150,
            num_points=300,
            line_thickness=2
        ))

        self.event_bus = EventBus()
        self.aicore_adapter = AicoreAdapter(self.event_bus)

        self._setup_event_handlers()

        self.running = False
        self.text_input = TextInput(self.screen)
        self.content_display = ContentDisplay(self.screen, self.circle)

    def _setup_event_handlers(self):
        self.event_bus.subscribe("text_input", self._handle_text_input)
        self.event_bus.subscribe("generation_chunk", self._handle_generation_chunk)
        self.logger.info("Event handlers setup complete")

    async def _handle_resize(self, new_width: int, new_height: int):
        try:
            self.width = max(800, new_width)
            self.height = max(600, new_height)
            previous_text_active = self.text_input.active
            previous_content = self.content_display.current_text

            self.screen = pygame.display.set_mode(
                (self.width, self.height),
                pygame.RESIZABLE | pygame.DOUBLEBUF
            )
            self.circle.update_screen(self.screen)
            self.text_input.update_screen(self.screen)
            self.content_display.update_screen(self.screen)

            if previous_text_active:
                self.text_input.active = True
            if previous_content:
                self.content_display.append_content(previous_content)
        except Exception as e:
            self.logger.error(f"Error handling resize: {str(e)}")

    async def _handle_text_input(self, event: Event):
        text = event.data
        self.circle.set_state(CircleState.LISTENING)
        try:
            self.content_display.clear_content()
            self.circle.set_state(CircleState.PROCESSING)
            await self.event_bus.emit(Event(
                type="generate_request",
                data={"prompt": text},
                priority=Priority.HIGH
            ))
        except Exception as e:
            self.logger.error(f"Error processing text input: {str(e)}")
            self.circle.set_state(CircleState.ERROR)
            await asyncio.sleep(1.0)
            self.circle.set_state(CircleState.IDLE)

    async def _handle_generation_chunk(self, event: Event):
        response = event.data
        try:
            if isinstance(response, dict):
                chunk = response.get('response', '')
                done = response.get('done', False)

                if chunk and not done:
                    if not hasattr(self, '_response_started'):
                        self.content_display.clear_content()
                        self._response_started = True
                    self.content_display.append_content(chunk)
                elif done:
                    if hasattr(self, '_response_started'):
                        delattr(self, '_response_started')
                    self.content_display.set_streaming_done()
                    self.circle.set_state(CircleState.RESPONDING)
                    await asyncio.sleep(0.3)
                    self.circle.set_state(CircleState.IDLE)
        except Exception as e:
            self.logger.error(f"Error handling generation chunk: {str(e)}")
            self.circle.set_state(CircleState.ERROR)

    async def _process_pygame_events(self):
        try:
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    self.running = False
                elif event.type == pygame.VIDEORESIZE:
                    await self._handle_resize(event.w, event.h)
                elif event.type == pygame.MOUSEBUTTONDOWN:
                    if event.button == 1:
                        import math
                        mx, my = event.pos
                        cx, cy = self.circle.center
                        if math.hypot(mx - cx, my - cy) <= self.circle.config.radius:
                            self.dragging = True
                elif event.type == pygame.MOUSEBUTTONUP:
                    if event.button == 1:
                        self.dragging = False
                elif event.type == pygame.MOUSEMOTION:
                    if getattr(self, 'dragging', False) and getattr(self, 'window', None):
                        wx, wy = self.window.position
                        self.window.position = (wx + event.rel[0], wy + event.rel[1])
                elif event.type == pygame.MOUSEWHEEL and self.content_display.active:
                    self.content_display.handle_scroll(event.y)
                elif event.type == pygame.KEYDOWN:
                    if event.key == pygame.K_ESCAPE:
                        self.running = False
                    elif event.key == pygame.K_F1:
                        os.system("chvt 1")
                    elif event.key == pygame.K_F2:
                        os.system("chvt 2")
                    try:
                        completed_text = self.text_input.handle_event(event)
                        if completed_text:
                            self.logger.info(f"Text input completed: {completed_text}")
                            await self.event_bus.emit(Event(
                                type="text_input",
                                data=completed_text,
                                priority=Priority.HIGH
                            ))
                    except Exception as e:
                        self.logger.error(f"Error handling text input: {e}")
        except Exception as e:
            self.logger.error(f"Error in event processing: {e}")

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
            await self.event_bus.start()
            await self.aicore_adapter.initialize()

            while self.running:
                await self._process_pygame_events()
                await self._update()
                await self._draw()
                await asyncio.sleep(0)
                self.clock.tick(60)
        except Exception as e:
            self.logger.error(f"Runtime error: {str(e)}")
        finally:
            self.logger.info("Shutting down")
            await self.event_bus.stop()
            pygame.quit()


def main():
    app = VoiceOS()
    asyncio.run(app.run())


if __name__ == "__main__":
    main()

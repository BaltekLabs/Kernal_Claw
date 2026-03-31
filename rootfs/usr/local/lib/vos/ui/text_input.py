import pygame
import math
from typing import Optional, List


class TextInput:
    def __init__(self, screen: pygame.Surface):
        self.text = ""
        self.active = False
        self.alpha = 0
        self.current_y = 0
        self.target_y = 0

        self.animation_speed = 300
        self.fade_speed = 2.5

        self.font = pygame.font.Font(None, 48)
        self.line_height = self.font.get_linesize()

        self.update_screen(screen)

    def update_screen(self, screen: pygame.Surface):
        self.screen = screen
        self.width, self.height = screen.get_size()
        self.max_line_width = int(self.width * 0.82)
        self._update_positions()

    def _update_positions(self):
        self.target_y = self.height - 55
        if not self.active:
            self.current_y = self.height + 60
        else:
            self.current_y = self.target_y

    def _wrap(self, text: str) -> List[str]:
        """Split text into lines that fit within max_line_width."""
        if not text:
            return [""]
        words = text.split(" ")
        lines: List[str] = []
        current = ""
        for word in words:
            candidate = (current + " " + word).lstrip()
            if self.font.size(candidate)[0] <= self.max_line_width:
                current = candidate
            else:
                if current:
                    lines.append(current)
                current = word
        if current:
            lines.append(current)
        return lines if lines else [""]

    def handle_event(self, event: pygame.event.Event) -> Optional[str]:
        if event.type != pygame.KEYDOWN:
            return None
        if not self.active and event.unicode.isprintable():
            self.active = True
            self.text = event.unicode
        elif self.active:
            if event.key == pygame.K_RETURN:
                completed = self.text
                self.text = ""
                self.active = False
                return completed
            elif event.key == pygame.K_BACKSPACE:
                self.text = self.text[:-1]
            elif event.unicode.isprintable():
                self.text += event.unicode
        return None

    def update(self, dt: float):
        if self.active:
            self.alpha = min(255, self.alpha + self.fade_speed * dt * 255)
            distance = self.target_y - self.current_y
            if abs(distance) > 1:
                self.current_y += min(
                    self.animation_speed * dt, abs(distance)
                ) * math.copysign(1, distance)
        else:
            self.alpha = max(0, self.alpha - self.fade_speed * dt * 255)
            if self.alpha == 0:
                self.current_y = self.height + 60

    def draw(self):
        if self.alpha <= 0:
            return

        lines = self._wrap(self.text)
        num_lines = len(lines)
        total_h = num_lines * self.line_height

        # Anchor: bottom of the text block sits at current_y
        block_top = int(self.current_y) - total_h

        for i, line in enumerate(lines):
            # Add cursor only to the last line
            display = line + "|" if (self.active and i == num_lines - 1) else line
            surf = self.font.render(display, True, (255, 255, 255))
            surf.set_alpha(int(self.alpha))
            rect = surf.get_rect(
                centerx=self.width // 2,
                top=block_top + i * self.line_height,
            )
            self.screen.blit(surf, rect)

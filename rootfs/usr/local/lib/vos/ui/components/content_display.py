import pygame
import math
from typing import List, Dict, Any


class ContentDisplay:
    # Palette matched to the neural-net colours
    COLOR_BASE   = (100, 200, 240)   # calm cyan for settled text
    COLOR_HOT    = (220, 248, 255)   # near-white for freshly arrived text
    COLOR_FRAME  = (0,   180, 220)   # bracket / border colour
    COLOR_BG     = (4,   14,  28)    # panel background fill

    BRACKET_LEN  = 20                # corner-bracket arm length (px)
    PADDING      = 14                # inner text padding

    def __init__(self, screen: pygame.Surface, circle):
        self.screen  = screen
        self.circle  = circle
        self.content: List[Dict[str, Any]] = []
        self.font    = pygame.font.Font(None, 26)
        self.active  = False
        self.current_text = ""

        self.line_height     = self.font.get_linesize() + 2
        self.scroll_offset   = 0
        self.selected_item   = None

        # Expansion slide-in
        self.expansion_progress = 0.0
        self.target_expansion   = 0.0
        self.expansion_speed    = 2.5

        # Glow: decays from 1.0 (fresh chunk) → 0.0 (settled)
        self._glow = 0.0
        self._glow_decay = 1.0      # per second

        # Blinking cursor shown while the AI is streaming
        self._streaming  = False
        self._blink_t    = 0.0

        self.update_screen(screen)

    # ------------------------------------------------------------------
    # Layout
    # ------------------------------------------------------------------

    def update_screen(self, screen: pygame.Surface):
        self.screen = screen
        self.width, self.height = screen.get_size()

        radius = self.circle.config.radius
        self.start_x    = int(self.width  * 0.10)
        # Use the circle's center y to determine where to start text
        self.start_y    = int(self.height * 0.35) + radius + 20
        self.post_width = int(self.width  * 0.80)
        # Leave ~70 px at bottom for the text-input widget
        self.post_height = self.height - self.start_y - 70
        self.max_lines   = max(1, self.post_height // self.line_height)

    # ------------------------------------------------------------------
    # Content management
    # ------------------------------------------------------------------

    def append_content(self, text: str):
        text = " ".join(text.split())
        if not text:
            return
        self.current_text = (self.current_text + " " + text).lstrip()
        self._glow       = 1.0
        self._streaming  = True
        self.content = [{
            "text":  self.current_text,
            "lines": self._wrap(self.current_text),
        }]
        self.active          = True
        self.target_expansion = 1.0
        # Auto-scroll to newest
        total = len(self.content[0]["lines"])
        if total > self.max_lines:
            self.scroll_offset = total - self.max_lines

    def clear_content(self):
        self.content          = []
        self.current_text     = ""
        self.active           = False
        self.selected_item    = None
        self.target_expansion = 0.0
        self.expansion_progress = 0.0
        self.scroll_offset    = 0
        self._glow            = 0.0
        self._streaming       = False

    def set_streaming_done(self):
        self._streaming = False

    # ------------------------------------------------------------------
    # Update / scroll
    # ------------------------------------------------------------------

    def update(self, dt: float):
        self._blink_t = (self._blink_t + dt * 2.8) % (math.pi * 2)
        self._glow    = max(0.0, self._glow - self._glow_decay * dt)

        if self.expansion_progress < self.target_expansion:
            self.expansion_progress = min(
                1.0, self.expansion_progress + self.expansion_speed * dt)
        elif self.expansion_progress > self.target_expansion:
            self.expansion_progress = max(
                0.0, self.expansion_progress - self.expansion_speed * dt)

    def handle_scroll(self, amount: int):
        if self.content and "lines" in self.content[0]:
            total    = len(self.content[0]["lines"])
            max_off  = max(0, total - self.max_lines)
            self.scroll_offset = max(0, min(self.scroll_offset - amount, max_off))

    # ------------------------------------------------------------------
    # Text wrapping
    # ------------------------------------------------------------------

    def _wrap(self, text: str) -> List[str]:
        max_w = self.post_width - self.PADDING * 2
        lines: List[str] = []
        for para in text.split("\n"):
            if not para.strip():
                lines.append("")
                continue
            current = ""
            for word in para.split():
                candidate = (current + " " + word).lstrip()
                if self.font.size(candidate)[0] > max_w:
                    if current:
                        lines.append(current)
                    current = word
                else:
                    current = candidate
            if current:
                lines.append(current)
        return lines

    # ------------------------------------------------------------------
    # Drawing helpers
    # ------------------------------------------------------------------

    def _hud_frame(self, surf: pygame.Surface,
                   x: int, y: int, w: int, h: int,
                   color, alpha: int):
        """Corner-bracket frame with a faint full-width top rule."""
        b  = self.BRACKET_LEN
        th = 2
        c  = (*color, alpha)

        # Corners
        for px, py, dx, dy in [
            (x,     y,     1,  1),
            (x + w, y,    -1,  1),
            (x,     y + h, 1, -1),
            (x + w, y + h,-1, -1),
        ]:
            pygame.draw.line(surf, c, (px, py), (px + dx * b, py), th)
            pygame.draw.line(surf, c, (px, py), (px, py + dy * b), th)

        # Full-width top/bottom rules (very dim)
        dim = (*color, alpha // 5)
        pygame.draw.line(surf, dim, (x + b, y),     (x + w - b, y),     1)
        pygame.draw.line(surf, dim, (x + b, y + h), (x + w - b, y + h), 1)

    def _draw_text_glow(self, surf: pygame.Surface,
                        text: str, pos, color, intensity: float):
        """Render text with a soft 1-px spread glow."""
        for ox, oy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            gs = self.font.render(text, True, color)
            gs.set_alpha(int(intensity * 70))
            surf.blit(gs, (pos[0] + ox, pos[1] + oy))

    # ------------------------------------------------------------------
    # Main draw
    # ------------------------------------------------------------------

    def draw(self):
        if not self.active or not self.content or self.expansion_progress <= 0:
            return

        lines   = self.content[0].get("lines", [])
        visible = lines[self.scroll_offset: self.scroll_offset + self.max_lines]
        total   = len(lines)
        n_vis   = len(visible)

        x = self.start_x
        y = self.start_y
        w = int(self.post_width * self.expansion_progress)
        h = self.post_height

        surf = pygame.Surface(self.screen.get_size(), pygame.SRCALPHA)

        # --- Text lines ---
        for i, line in enumerate(visible):
            if not line:
                continue

            # Recency: last 5 lines glow with the fresh-content decay
            recency = max(0.0, (i - (n_vis - 5)) / 5.0)
            line_glow = self._glow * recency

            # Interpolate base → hot colour
            r = int(self.COLOR_BASE[0] + (self.COLOR_HOT[0] - self.COLOR_BASE[0]) * line_glow)
            g = int(self.COLOR_BASE[1] + (self.COLOR_HOT[1] - self.COLOR_BASE[1]) * line_glow)
            b = int(self.COLOR_BASE[2] + (self.COLOR_HOT[2] - self.COLOR_BASE[2]) * line_glow)
            col = (r, g, b)

            tx = x + self.PADDING
            ty = y + self.PADDING + i * self.line_height

            if line_glow > 0.15:
                self._draw_text_glow(surf, line, (tx, ty), col, line_glow)

            surf.blit(self.font.render(line, True, col), (tx, ty))

        # --- Blinking cursor after last visible line while streaming ---
        if self._streaming and visible:
            last_w   = self.font.size(visible[-1])[0]
            cx       = x + self.PADDING + last_w + 4
            cy       = y + self.PADDING + (n_vis - 1) * self.line_height
            c_alpha  = int((math.sin(self._blink_t) * 0.5 + 0.5) * 180 + 55)
            c_surf   = pygame.Surface((2, self.line_height - 4), pygame.SRCALPHA)
            c_surf.fill((0, 220, 255, c_alpha))
            surf.blit(c_surf, (cx, cy + 2))

        # --- Scroll-fade gradient at bottom (hints at more content) ---
        can_scroll = total > self.max_lines and self.scroll_offset < total - self.max_lines
        if can_scroll:
            fade_h = min(48, h // 4)
            for row in range(fade_h):
                a = int((row / fade_h) ** 1.6 * 200)
                fl = pygame.Surface((w, 1), pygame.SRCALPHA)
                fl.fill((*self.COLOR_BG, a))
                surf.blit(fl, (x, y + h - fade_h + row))

        self.screen.blit(surf, (0, 0))

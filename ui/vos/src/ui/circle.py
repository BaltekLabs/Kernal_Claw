from __future__ import annotations

import pygame
import math
import random
from dataclasses import dataclass
from typing import Tuple, Optional, List
from enum import Enum, auto


class CircleState(Enum):
    IDLE = auto()
    LISTENING = auto()
    PROCESSING = auto()
    RESPONDING = auto()
    ERROR = auto()


@dataclass
class CircleConfig:
    radius: int = 150
    num_points: int = 300   # kept for API compatibility
    line_thickness: int = 2
    amplitude_base: float = 15
    base_speed: float = 0.15


# ---------------------------------------------------------------------------
# Internal node / particle helpers
# ---------------------------------------------------------------------------

class _Node:
    def __init__(self, base_x: float, base_y: float, layer: int):
        self.base_x = base_x
        self.base_y = base_y
        self.x = base_x
        self.y = base_y
        self.layer = layer
        self.radius = random.uniform(3, 5)
        self.activation = 0.0
        self.phase = random.uniform(0, math.tau)
        self.drift_px = random.uniform(0, math.tau)
        self.drift_py = random.uniform(0, math.tau)
        self.drift_speed = random.uniform(0.9, 2.0)
        self.drift_r = random.uniform(5, 12)

    def update(self, t: float, dt: float):
        self.x = self.base_x + math.sin(t * self.drift_speed + self.drift_px) * self.drift_r
        self.y = self.base_y + math.cos(t * self.drift_speed * 0.7 + self.drift_py) * self.drift_r * 0.6
        self.activation = max(0.0, self.activation - dt * 0.9)


class _Particle:
    def __init__(self, src: _Node, dst: _Node, color: Tuple[int, int, int]):
        self.src = src
        self.dst = dst
        self.color = color
        self.t = 0.0
        self.speed = random.uniform(0.7, 1.5)

    def update(self, dt: float) -> bool:
        """Returns True when the particle has reached its destination."""
        self.t = min(1.0, self.t + self.speed * dt)
        return self.t >= 1.0

    def pos(self) -> Tuple[float, float]:
        return (
            self.src.x + (self.dst.x - self.src.x) * self.t,
            self.src.y + (self.dst.y - self.src.y) * self.t,
        )


# ---------------------------------------------------------------------------
# Main class — same public interface as the old blob Circle
# ---------------------------------------------------------------------------

class Circle:
    """
    Neural-net / nodal-network visualization.

    Public API is identical to the old blob Circle so nothing else needs changing:
        circle.update(dt)
        circle.draw()
        circle.set_state(CircleState.PROCESSING)
        circle.update_screen(new_surface)
        circle.config.radius   (read by ContentDisplay)
    """

    _LAYERS = [4, 6, 6, 4]   # nodes per layer, total = 20

    # State -> (node_color, edge_color)
    _PALETTE = {
        CircleState.IDLE:       ((30,  100, 220), (15,  55, 140)),
        CircleState.LISTENING:  ((80,  180, 255), (40, 120, 200)),
        CircleState.PROCESSING: ((0,   220, 255), (0,  120, 200)),
        CircleState.RESPONDING: ((80,  255, 160), (30, 180, 100)),
        CircleState.ERROR:      ((255,  60,  60), (180, 20,  20)),
    }

    def __init__(self, screen: pygame.Surface, config: Optional[CircleConfig] = None):
        self.screen = screen
        self.config = config or CircleConfig()
        self.width, self.height = screen.get_size()
        self.center = (self.width // 2, int(self.height * 0.35))

        self.current_state = CircleState.IDLE
        self.target_state = CircleState.IDLE
        self.transition_progress = 1.0
        self.transition_speed = 0.7
        self.state = CircleState.IDLE      # backward-compat alias

        self.time = 0.0
        self._particles: List[_Particle] = []
        self._spawn_timer = 0.0
        self._pulse_timer = 0.0

        self._build_network()

    # ------------------------------------------------------------------
    # Network construction
    # ------------------------------------------------------------------

    def _build_network(self):
        cx, cy = self.center
        r = self.config.radius

        total_layers = len(self._LAYERS)
        layer_xs = [cx - r * 0.9 + (i / (total_layers - 1)) * r * 1.8
                    for i in range(total_layers)]

        self._nodes: List[_Node] = []
        self._edges: List[Tuple[_Node, _Node]] = []
        self._layer_nodes: List[List[_Node]] = []

        for li, (lx, count) in enumerate(zip(layer_xs, self._LAYERS)):
            layer = []
            for ni in range(count):
                frac = (ni + 0.5) / count
                y = cy - r * 0.85 + frac * r * 1.7
                node = _Node(lx, y, li)
                self._nodes.append(node)
                layer.append(node)
            self._layer_nodes.append(layer)

        # Adjacent-layer connections (sparse for readability)
        for li in range(total_layers - 1):
            for src in self._layer_nodes[li]:
                for dst in self._layer_nodes[li + 1]:
                    if random.random() > 0.2:
                        self._edges.append((src, dst))

        # A few skip-layer connections for visual complexity
        if total_layers > 2:
            for _ in range(5):
                li = random.randint(0, total_layers - 3)
                src = random.choice(self._layer_nodes[li])
                dst = random.choice(self._layer_nodes[li + 2])
                self._edges.append((src, dst))

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _lerp(a: float, b: float, t: float) -> float:
        return a + (b - a) * t

    @staticmethod
    def _lerp_color(a: Tuple, b: Tuple, t: float) -> Tuple[int, int, int]:
        return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

    def _blended_colors(self) -> Tuple[Tuple, Tuple]:
        nc_a, ec_a = self._PALETTE[self.current_state]
        nc_b, ec_b = self._PALETTE[self.target_state]
        t = self.transition_progress
        return self._lerp_color(nc_a, nc_b, t), self._lerp_color(ec_a, ec_b, t)

    def _draw_glow(self, surface: pygame.Surface,
                   pos: Tuple[float, float],
                   color: Tuple[int, int, int],
                   radius: float,
                   alpha: int = 80):
        """Layered additive soft glow around a point."""
        gr = max(2, int(radius * 3.5))
        g = pygame.Surface((gr * 2, gr * 2), pygame.SRCALPHA)
        steps = 4
        for s in range(steps, 0, -1):
            r = int(gr * s / steps)
            a = alpha // s
            pygame.draw.circle(g, (*color, a), (gr, gr), r)
        surface.blit(g, (int(pos[0]) - gr, int(pos[1]) - gr),
                     special_flags=pygame.BLEND_ADD)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def set_state(self, state: CircleState):
        if state != self.target_state:
            self.current_state = self.target_state
            self.target_state = state
            self.transition_progress = 0.0
            self.state = state

            # Immediate wave-activation of all nodes on PROCESSING
            if state == CircleState.PROCESSING:
                for layer in self._layer_nodes:
                    for node in layer:
                        node.activation = min(1.0, node.activation + 0.5)

    def update(self, dt: float):
        self.time += dt * self.config.base_speed * 7.0

        # State transition blend
        if self.transition_progress < 1.0:
            self.transition_progress = min(1.0, self.transition_progress + dt / self.transition_speed)
            if self.transition_progress >= 1.0:
                self.current_state = self.target_state

        # Update all node positions and activation decay
        for node in self._nodes:
            node.update(self.time, dt)

        # Spawn signal particles when active
        active_states = (CircleState.PROCESSING, CircleState.LISTENING, CircleState.RESPONDING)
        nc, _ = self._blended_colors()
        if self.target_state in active_states:
            self._spawn_timer -= dt
            if self._spawn_timer <= 0 and self._edges:
                rate = 0.06 if self.target_state == CircleState.PROCESSING else 0.12
                self._spawn_timer = random.uniform(rate, rate * 2.5)
                src, dst = random.choice(self._edges)
                self._particles.append(_Particle(src, dst, nc))
                dst.activation = min(1.0, dst.activation + 0.6)

        # Idle background pulse — occasional random node flicker
        self._pulse_timer -= dt
        if self._pulse_timer <= 0:
            self._pulse_timer = random.uniform(0.4, 1.5)
            if self._nodes:
                random.choice(self._nodes).activation = min(1.0,
                    random.choice(self._nodes).activation + 0.35)

        # Advance / expire particles
        self._particles = [p for p in self._particles if not p.update(dt)]
        if len(self._particles) > 100:
            self._particles = self._particles[-100:]

    def draw(self):
        nc, ec = self._blended_colors()

        # --- Edge layer (SRCALPHA surface so we can vary per-edge alpha) ---
        edge_surf = pygame.Surface(self.screen.get_size(), pygame.SRCALPHA)
        for src, dst in self._edges:
            act = max(src.activation, dst.activation)
            alpha = int(self._lerp(120, 255, act))
            width = 2 if act > 0.3 else 1
            pygame.draw.line(
                edge_surf, (*ec, alpha),
                (int(src.x), int(src.y)),
                (int(dst.x), int(dst.y)),
                width
            )
            # Glow along active edges
            if act > 0.4:
                mid = ((src.x + dst.x) / 2, (src.y + dst.y) / 2)
                self._draw_glow(edge_surf, mid, ec, 3, int(act * 60))
        self.screen.blit(edge_surf, (0, 0))

        # --- Particle layer ---
        part_surf = pygame.Surface(self.screen.get_size(), pygame.SRCALPHA)
        for p in self._particles:
            px, py = p.pos()
            # Brightest in the middle of the journey
            fade = 1.0 - abs(p.t - 0.5) * 1.8
            fade = max(0.0, fade)
            self._draw_glow(part_surf, (px, py), p.color, 4, int(55 * fade))
            pygame.draw.circle(part_surf, (*p.color, int(220 * fade)), (int(px), int(py)), 3)
        self.screen.blit(part_surf, (0, 0))

        # --- Node layer ---
        for node in self._nodes:
            act = node.activation
            idle_pulse = 0.25 + 0.12 * math.sin(self.time * 1.4 + node.phase)
            brightness = min(1.0, idle_pulse + act * 0.75)
            r = int(node.radius)  # fixed size — no expansion

            # Glow halo (additive blend directly onto screen)
            if brightness > 0.15:
                self._draw_glow(self.screen, (node.x, node.y), nc, r,
                                int(brightness * 90))

            # Solid core
            core_color = self._lerp_color(nc, (200, 235, 255), act * 0.5)
            core_alpha = int(brightness * 255)
            node_surf = pygame.Surface((r * 2 + 2, r * 2 + 2), pygame.SRCALPHA)
            pygame.draw.circle(node_surf, (*core_color, core_alpha), (r + 1, r + 1), r)
            self.screen.blit(node_surf, (int(node.x) - r - 1, int(node.y) - r - 1))

    def update_screen(self, screen: pygame.Surface):
        self.screen = screen
        self.width, self.height = screen.get_size()
        self.center = (self.width // 2, int(self.height * 0.35))
        self.config.radius = min(self.width, self.height) // 4
        self._build_network()
        self._particles.clear()

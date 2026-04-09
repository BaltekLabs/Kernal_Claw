/**
 * circle.js — Canvas port of rootfs/usr/local/lib/vos/ui/circle.py
 * Neural-net / nodal-network visualization with signal particles.
 */

export const CircleState = {
  IDLE:       'IDLE',
  LISTENING:  'LISTENING',
  PROCESSING: 'PROCESSING',
  RESPONDING: 'RESPONDING',
  ERROR:      'ERROR',
};

const PALETTE = {
  IDLE:       { node: [50,  130, 255], edge: [25,   65,  180] },
  LISTENING:  { node: [120, 210, 255], edge: [60,  140, 230] },
  PROCESSING: { node: [0,   240, 255], edge: [0,   140, 230] },
  RESPONDING: { node: [80,  255, 160], edge: [35,  200, 110] },
  ERROR:      { node: [255,  60,  60], edge: [200,  20,  20] },
};

// ── Node ──────────────────────────────────────────────────────────────────

class Node {
  constructor(bx, by, layer) {
    this.bx = bx; this.by = by;
    this.x  = bx; this.y  = by;
    this.layer      = layer;
    this.r          = 4 + Math.random() * 4;
    this.activation = 0;
    this.phase      = Math.random() * Math.PI * 2;
    this.dpx        = Math.random() * Math.PI * 2;
    this.dpy        = Math.random() * Math.PI * 2;
    this.ds         = 0.9 + Math.random() * 1.1;
    this.dr         = 5   + Math.random() * 7;
  }

  update(t, dt) {
    this.x          = this.bx + Math.sin(t * this.ds + this.dpx) * this.dr;
    this.y          = this.by + Math.cos(t * this.ds * 0.7 + this.dpy) * this.dr * 0.6;
    this.activation = Math.max(0, this.activation - dt * 0.9);
  }
}

// ── Particle ──────────────────────────────────────────────────────────────

class Particle {
  constructor(src, dst, color) {
    this.src   = src;
    this.dst   = dst;
    this.color = color;
    this.t     = 0;
    this.speed = 0.7 + Math.random() * 0.8;
  }

  /** Returns true when particle reaches destination. */
  update(dt) {
    this.t = Math.min(1, this.t + this.speed * dt);
    return this.t >= 1;
  }

  pos() {
    return [
      this.src.x + (this.dst.x - this.src.x) * this.t,
      this.src.y + (this.dst.y - this.src.y) * this.t,
    ];
  }
}

// ── Circle ────────────────────────────────────────────────────────────────

export class Circle {
  static _LAYERS = [3, 5, 7, 7, 5, 3];   // 6 layers, 30 nodes — fills full screen

  constructor(canvas) {
    this.canvas = canvas;
    this.ctx    = canvas.getContext('2d');

    this.currentState      = CircleState.IDLE;
    this.targetState       = CircleState.IDLE;
    this.transitionProgress = 1.0;
    this.transitionSpeed   = 0.7;

    this.time        = 0;
    this._particles  = [];
    this._spawnTimer = 0;
    this._pulseTimer = 0;
    this._lastW      = 0;
    this._lastH      = 0;

    this._updateDimensions();
    this._buildNetwork();
  }

  // ── Dimensions & network construction ──────────────────────────────────

  _updateDimensions() {
    this.width  = this.canvas.width;
    this.height = this.canvas.height;
    this.cx     = this.width  / 2;
    this.cy     = this.height / 2;
    // radius kept for external API compat only (tap detection)
    this.radius = Math.min(this.width, this.height) / 3;
  }

  _buildNetwork() {
    const { width, height } = this;
    const layers = Circle._LAYERS;
    const total  = layers.length;

    // Outer layers sit outside the canvas so edges bleed into every corner
    const xOuter = width  * 0.10;   // 10% beyond left/right edge
    const yOuter = height * 0.08;   // 8% beyond top/bottom edge

    const layerXs = layers.map((_, i) =>
      -xOuter + (i / (total - 1)) * (width + xOuter * 2)
    );

    const yTop = -yOuter;
    const yBot = height + yOuter;

    this._nodes      = [];
    this._edges      = [];
    this._layerNodes = [];

    for (let li = 0; li < total; li++) {
      const count = layers[li];
      const lx    = layerXs[li];
      const layer = [];
      for (let ni = 0; ni < count; ni++) {
        const frac = (ni + 0.5) / count;
        const y    = yTop + frac * (yBot - yTop);
        const node = new Node(lx, y, li);
        this._nodes.push(node);
        layer.push(node);
      }
      this._layerNodes.push(layer);
    }

    // Adjacent-layer connections (sparse)
    for (let li = 0; li < total - 1; li++) {
      for (const src of this._layerNodes[li]) {
        for (const dst of this._layerNodes[li + 1]) {
          if (Math.random() > 0.25) this._edges.push([src, dst]);
        }
      }
    }

    // Skip-layer connections — more of them for a denser mesh
    const skipCount = total * 3;
    for (let i = 0; i < skipCount; i++) {
      const li  = Math.floor(Math.random() * (total - 2));
      const src = this._layerNodes[li][Math.floor(Math.random() * this._layerNodes[li].length)];
      const dst = this._layerNodes[li + 2][Math.floor(Math.random() * this._layerNodes[li + 2].length)];
      this._edges.push([src, dst]);
    }
  }

  // ── Public API ──────────────────────────────────────────────────────────

  setState(state) {
    if (state === this.targetState) return;
    this.currentState       = this.targetState;
    this.targetState        = state;
    this.transitionProgress = 0;
    if (state === CircleState.PROCESSING) {
      for (const n of this._nodes)
        n.activation = Math.min(1, n.activation + 0.5);
    }
  }

  update(dt) {
    this.time += dt * 0.15 * 7.0;   // base_speed * 7

    // State transition
    if (this.transitionProgress < 1) {
      this.transitionProgress = Math.min(
        1, this.transitionProgress + dt / this.transitionSpeed
      );
      if (this.transitionProgress >= 1) this.currentState = this.targetState;
    }

    // Node drift + activation decay
    for (const n of this._nodes) n.update(this.time, dt);

    // Particle spawning
    const { node: nc } = this._blendedColors();
    const active = this.targetState === CircleState.PROCESSING ||
                   this.targetState === CircleState.LISTENING  ||
                   this.targetState === CircleState.RESPONDING;
    if (active) {
      this._spawnTimer -= dt;
      if (this._spawnTimer <= 0 && this._edges.length) {
        const rate = this.targetState === CircleState.PROCESSING ? 0.06 : 0.12;
        this._spawnTimer = rate + Math.random() * rate * 1.5;
        const [src, dst] = this._edges[
          Math.floor(Math.random() * this._edges.length)
        ];
        this._particles.push(new Particle(src, dst, nc));
        dst.activation = Math.min(1, dst.activation + 0.6);
      }
    }

    // Idle background pulse
    this._pulseTimer -= dt;
    if (this._pulseTimer <= 0) {
      this._pulseTimer = 0.4 + Math.random() * 1.1;
      if (this._nodes.length) {
        const n = this._nodes[Math.floor(Math.random() * this._nodes.length)];
        n.activation = Math.min(1, n.activation + 0.35);
      }
    }

    // Advance / expire particles
    this._particles = this._particles.filter(p => !p.update(dt));
    if (this._particles.length > 200) this._particles = this._particles.slice(-200);
  }

  draw() {
    // Rebuild on canvas resize
    if (this.canvas.width !== this._lastW || this.canvas.height !== this._lastH) {
      this._updateDimensions();
      this._buildNetwork();
      this._particles = [];
      this._lastW = this.canvas.width;
      this._lastH = this.canvas.height;
    }

    const ctx            = this.ctx;
    const { node: nc, edge: ec } = this._blendedColors();

    // ── Edges ─────────────────────────────────────────────────────────────
    for (const [src, dst] of this._edges) {
      const act   = Math.max(src.activation, dst.activation);
      const alpha = this._lerp(160, 255, act) / 255;
      ctx.beginPath();
      ctx.moveTo(src.x, src.y);
      ctx.lineTo(dst.x, dst.y);
      ctx.strokeStyle = `rgba(${ec[0]},${ec[1]},${ec[2]},${alpha.toFixed(3)})`;
      ctx.lineWidth   = act > 0.3 ? 2 : 1;
      ctx.stroke();

      // Mid-edge glow on highly active edges
      if (act > 0.4) {
        const mx = (src.x + dst.x) / 2;
        const my = (src.y + dst.y) / 2;
        ctx.save();
        ctx.globalCompositeOperation = 'lighter';
        ctx.globalAlpha = act * 0.35;
        ctx.shadowBlur  = 10;
        ctx.shadowColor = `rgb(${ec[0]},${ec[1]},${ec[2]})`;
        ctx.beginPath();
        ctx.arc(mx, my, 3, 0, Math.PI * 2);
        ctx.fillStyle = `rgb(${ec[0]},${ec[1]},${ec[2]})`;
        ctx.fill();
        ctx.restore();
      }
    }

    // ── Particles ─────────────────────────────────────────────────────────
    for (const p of this._particles) {
      const [px, py] = p.pos();
      const fade = Math.max(0, 1 - Math.abs(p.t - 0.5) * 1.8);
      if (fade <= 0) continue;
      const [r, g, b] = p.color;

      // Radial glow (additive)
      ctx.save();
      ctx.globalCompositeOperation = 'lighter';
      const grd = ctx.createRadialGradient(px, py, 0, px, py, 10);
      grd.addColorStop(0, `rgba(${r},${g},${b},${(fade * 0.40).toFixed(3)})`);
      grd.addColorStop(1, `rgba(${r},${g},${b},0)`);
      ctx.fillStyle = grd;
      ctx.beginPath();
      ctx.arc(px, py, 10, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();

      // Solid core
      ctx.beginPath();
      ctx.arc(px, py, 3, 0, Math.PI * 2);
      ctx.fillStyle = `rgba(${r},${g},${b},${(fade * 0.86).toFixed(3)})`;
      ctx.fill();
    }

    // ── Nodes ─────────────────────────────────────────────────────────────
    for (const node of this._nodes) {
      const act        = node.activation;
      const idlePulse  = 0.40 + 0.20 * Math.sin(this.time * 1.4 + node.phase);
      const brightness = Math.min(1, idlePulse + act * 0.75);
      const nr = nc[0], ng = nc[1], nb = nc[2];
      const r  = Math.max(1, Math.round(node.r));

      // Glow halo (additive blend)
      if (brightness > 0.15) {
        const glowR = r * 3.5;
        ctx.save();
        ctx.globalCompositeOperation = 'lighter';
        const grd = ctx.createRadialGradient(node.x, node.y, 0, node.x, node.y, glowR);
        const ga  = (brightness * 130 / 255).toFixed(3);
        grd.addColorStop(0, `rgba(${nr},${ng},${nb},${ga})`);
        grd.addColorStop(1, `rgba(${nr},${ng},${nb},0)`);
        ctx.fillStyle = grd;
        ctx.beginPath();
        ctx.arc(node.x, node.y, glowR, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
      }

      // Solid core (lerp toward white at high activation)
      const t   = act * 0.5;
      const cR  = Math.round(nr + (200 - nr) * t);
      const cG  = Math.round(ng + (235 - ng) * t);
      const cB  = Math.round(nb + (255 - nb) * t);
      ctx.beginPath();
      ctx.arc(node.x, node.y, r, 0, Math.PI * 2);
      ctx.fillStyle = `rgba(${cR},${cG},${cB},${brightness.toFixed(3)})`;
      ctx.fill();
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────

  _lerp(a, b, t) { return a + (b - a) * t; }

  _lerpColor(a, b, t) {
    return a.map((v, i) => Math.round(v + (b[i] - v) * t));
  }

  _blendedColors() {
    const ca = PALETTE[this.currentState];
    const cb = PALETTE[this.targetState];
    const t  = this.transitionProgress;
    return {
      node: this._lerpColor(ca.node, cb.node, t),
      edge: this._lerpColor(ca.edge, cb.edge, t),
    };
  }
}

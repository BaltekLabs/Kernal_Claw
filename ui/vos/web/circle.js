/**
 * circle.js — Canvas port of ui/vos/src/ui/circle.py
 * Organic morphing circle with state-based animation.
 */

export const CircleState = {
  IDLE: 'IDLE',
  LISTENING: 'LISTENING',
  PROCESSING: 'PROCESSING',
  RESPONDING: 'RESPONDING',
  ERROR: 'ERROR',
};

const STATE_COLORS = {
  IDLE:       [0, 150, 255],
  LISTENING:  [0, 220, 160],
  PROCESSING: [200, 120, 0],
  RESPONDING: [80, 200, 80],
  ERROR:      [220, 40, 40],
};

const STATE_INTENSITIES = {
  IDLE:       1.0,
  LISTENING:  1.8,
  PROCESSING: 2.5,
  RESPONDING: 1.5,
  ERROR:      3.0,
};

export class Circle {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.numPoints = 300;
    this.lineThickness = 2;
    this.baseSpeed = 0.15;
    this.time = 0;

    this.currentState = CircleState.IDLE;
    this.targetState  = CircleState.IDLE;
    this.transitionProgress = 1.0;
    this.transitionSpeed = 0.8; // seconds for full transition

    this._noiseOffsets = Array.from({ length: this.numPoints }, () => [
      Math.random() * 1000,
      Math.random() * 1000,
    ]);

    this._updateDimensions();
  }

  _updateDimensions() {
    this.width  = this.canvas.width;
    this.height = this.canvas.height;
    this.cx = this.width  / 2;
    this.cy = this.height / 2;
    // Radius scales with the smaller dimension, same formula as Python
    this.radius = Math.min(this.width, this.height) / 4;
  }

  setState(state) {
    if (state === this.targetState) return;
    this.currentState = this.targetState;
    this.targetState  = state;
    this.transitionProgress = 0.0;
  }

  _lerp(a, b, t) { return a + (b - a) * t; }

  _lerpColor(c1, c2, t) {
    return c1.map((v, i) => Math.round(this._lerp(v, c2[i], t)));
  }

  _getOrganicDeformation(angle, t) {
    const ci = STATE_INTENSITIES[this.currentState];
    const ti = STATE_INTENSITIES[this.targetState];
    const blended = this._lerp(ci, ti, this.transitionProgress);

    const baseWave = Math.sin(angle * 2 + t * 1.0) * 10;
    const ripples  = Math.sin(angle * 4 + t * 2.4) * 5
                   + Math.sin(angle * 3 - t * 1.6) * 4;
    const pulse    = Math.sin(t * 0.6) * 8;

    return (baseWave + ripples + pulse) * blended;
  }

  _generatePoints(t) {
    const pts = [];
    for (let i = 0; i < this.numPoints; i++) {
      const angle  = (i / this.numPoints) * Math.PI * 2;
      const deform = this._getOrganicDeformation(angle, t);
      const noise  = Math.sin(t + i * 0.2) * 3;
      const r      = this.radius + deform + noise;
      pts.push([this.cx + r * Math.cos(angle), this.cy + r * Math.sin(angle)]);
    }
    // 4 smoothing passes (mirrors Python)
    let smoothed = pts;
    for (let pass = 0; pass < 4; pass++) {
      smoothed = this._interpolatePoints(smoothed, 0.18);
    }
    return smoothed;
  }

  _interpolatePoints(pts, factor) {
    const n = pts.length;
    return pts.map((curr, i) => {
      const p2 = pts[(i - 2 + n) % n];
      const p1 = pts[(i - 1 + n) % n];
      const n1 = pts[(i + 1) % n];
      const n2 = pts[(i + 2) % n];
      return [
        curr[0] * (1 - factor) + factor * (p2[0]*0.1 + p1[0]*0.2 + n1[0]*0.2 + n2[0]*0.1),
        curr[1] * (1 - factor) + factor * (p2[1]*0.1 + p1[1]*0.2 + n1[1]*0.2 + n2[1]*0.1),
      ];
    });
  }

  update(dt) {
    this.time += dt * this.baseSpeed;
    if (this.transitionProgress < 1.0) {
      this.transitionProgress = Math.min(1.0, this.transitionProgress + dt / this.transitionSpeed);
    }
  }

  draw() {
    this._updateDimensions();
    const ctx  = this.ctx;
    const pts  = this._generatePoints(this.time);
    const col  = this._lerpColor(
      STATE_COLORS[this.currentState],
      STATE_COLORS[this.targetState],
      this.transitionProgress
    );

    ctx.beginPath();
    ctx.moveTo(pts[0][0], pts[0][1]);
    for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]);
    ctx.closePath();

    ctx.fillStyle = `rgb(${col[0]},${col[1]},${col[2]})`;
    ctx.fill();

    ctx.strokeStyle = 'rgba(255,255,255,0.85)';
    ctx.lineWidth = this.lineThickness;
    ctx.stroke();
  }
}

/**
 * Start grid — packing, live tiles, and edit mode.
 *
 * Tiles are an ordered list; positions come from first-fit packing on a grid of
 * `cols` small-tile units (4, or 6 for "more tiles"). Each tile is absolutely
 * positioned, so reorders and resizes animate on their own via CSS transitions.
 *
 * Interaction (Windows Phone rules):
 *   tap            → open (with a press "tilt")
 *   press & hold   → edit mode, that tile selected; keep holding to drag it
 *   in edit mode   → drag the selected tile to move it; its buttons unpin,
 *                    resize, and recolor; tap another tile to select it;
 *                    tap empty space / Back / done to finish
 */
import { CATALOG, labelFor, PALETTE } from './catalog.js';
import { icon } from './icons.js';

const DIMS = { s: [1, 1], m: [2, 2], w: [4, 2], l: [4, 4] };
const HOLD_MS = 450, SLOP = 10;

let gridEl, scrollEl, pageEl;
let layout = { cols: 4, tiles: [] };
let onSave = () => {}, onOpen = () => {};
const els = new Map();          // tile id → element
let slots = {};                 // tile id → { r, c, w, h }
let editing = false, selectedId = null;
let metrics = { unit: 80, gap: 6 };

export function initGrid(opts) {
  ({ gridEl, scrollEl, pageEl } = opts);
  onSave = opts.onSave; onOpen = opts.onOpen;
  layout = opts.layout;
  window.addEventListener('resize', () => render());
  gridEl.addEventListener('pointerdown', onDown);
  // Tap on empty space while editing → done.
  scrollEl.addEventListener('click', e => {
    if (editing && !e.target.closest('.tile')) exitEdit();
  });
  // Once a press has turned into a drag, stop the page from scrolling under it.
  document.addEventListener('touchmove', e => { if (press?.armed) e.preventDefault(); }, { passive: false });
  render();
  setInterval(rotateLive, 2600);
}

export function getLayout() { return layout; }
export function setLayout(l) { layout = l; els.forEach(el => el.remove()); els.clear(); render(); }
export const isEditing = () => editing;

// ── Packing ─────────────────────────────────────────────────────
function dims(tile) {
  let [w, h] = DIMS[tile.size] || DIMS.m;
  if (w > layout.cols) { w = layout.cols; }
  return [w, h];
}

function pack(tiles) {
  const cols = layout.cols, occ = [], out = {};
  const fits = (r, c, w, h) => {
    if (c + w > cols) return false;
    for (let y = r; y < r + h; y++) for (let x = c; x < c + w; x++) if (occ[y]?.[x]) return false;
    return true;
  };
  for (const t of tiles) {
    const [w, h] = dims(t);
    search: for (let r = 0; ; r++) {
      for (let c = 0; c + w <= cols; c++) {
        if (fits(r, c, w, h)) {
          for (let y = r; y < r + h; y++) { occ[y] ||= []; for (let x = c; x < c + w; x++) occ[y][x] = t.id; }
          out[t.id] = { r, c, w, h };
          break search;
        }
      }
    }
  }
  return { slots: out, rows: occ.length, occ };
}

// ── Rendering ───────────────────────────────────────────────────
export function render() {
  if (!gridEl) return;
  const width = gridEl.clientWidth || window.innerWidth - 24;
  const gap = layout.cols > 4 ? 5 : 6;
  const unit = (width - gap * (layout.cols - 1)) / layout.cols;
  metrics = { unit, gap };

  const packed = pack(layout.tiles);
  slots = packed.slots;
  gridEl.style.height = `${packed.rows * (unit + gap) - gap}px`;

  const live = new Set();
  layout.tiles.forEach((tile, i) => {
    if (!CATALOG[tile.type]) return;
    live.add(tile.id);
    let el = els.get(tile.id);
    if (!el) { el = createTile(tile, i); els.set(tile.id, el); gridEl.appendChild(el); }
    el.className = `tile ${tile.size}${tile.type === 'app' ? ' app' : ''}` +
      `${CATALOG[tile.type].anim === 'flip' ? ' flip' : ''}${tile.id === selectedId ? ' selected' : ''}` +
      `${el.classList.contains('dragging') ? ' dragging' : ''}`;
    el.style.background = tile.color || '';
    if (!el.classList.contains('dragging')) place(el, slots[tile.id]);
    else sizeOnly(el, slots[tile.id]);
    updateContent(el, tile);
  });
  for (const [id, el] of els) if (!live.has(id)) { el.remove(); els.delete(id); }
}

function px(s) {
  const { unit, gap } = metrics;
  return { left: s.c * (unit + gap), top: s.r * (unit + gap),
           width: s.w * unit + (s.w - 1) * gap, height: s.h * unit + (s.h - 1) * gap };
}
function place(el, s) {
  const p = px(s);
  Object.assign(el.style, { left: `${p.left}px`, top: `${p.top}px`, width: `${p.width}px`, height: `${p.height}px` });
}
function sizeOnly(el, s) {
  const p = px(s);
  Object.assign(el.style, { width: `${p.width}px`, height: `${p.height}px` });
}

function createTile(tile, i) {
  const el = document.createElement('div');
  el.dataset.id = tile.id;
  el.style.animationDelay = `${Math.min(i, 16) * 35}ms`;
  el.innerHTML = `
    <div class="faces"></div>
    <div class="label"></div><div class="count"></div>
    <button class="tile-btn unpin"  data-btn="unpin"  aria-label="Unpin">${icon('unpin')}</button>
    <button class="tile-btn resize" data-btn="resize" aria-label="Resize">${icon('resize')}</button>
    <button class="tile-btn color"  data-btn="color"  aria-label="Color">${icon('palette')}</button>`;
  return el;
}

/** Refresh what each tile shows (called whenever store data changes). */
export function updateContent(el, tile) {
  const def = CATALOG[tile.type];
  let faces = [];
  try { faces = def.faces(tile, tile.size) || []; } catch (e) { console.error(tile.type, e); }
  faces = faces.map(f => typeof f === 'string' ? { html: f, cls: '' } : f);
  if (!faces.length) faces = [{ html: '', cls: '' }];

  const box = el.querySelector('.faces');
  const sig = faces.map(f => f.cls + f.html).join('\u0000');
  if (box._sig !== sig) {
    box._sig = sig;
    const prev = Math.min(box._cur || 0, faces.length - 1);
    box.innerHTML = faces.map((f, i) =>
      `<div class="face ${f.cls || ''}${i === prev ? '' : ' off'}">${f.html}</div>`).join('');
    box._cur = prev;
  }
  el.querySelector('.label').textContent = labelFor(tile);
  const n = def.count ? def.count(tile) : 0;
  el.querySelector('.count').textContent = n ? String(n) : '';
  const btn = el.querySelector('.tile-btn.resize');
  if (btn) btn.style.transform = `rotate(${({ s: 0, m: 90, w: 180, l: 270 })[nextSize(tile)] || 0}deg)`;
}

export function refreshContent() {
  for (const tile of layout.tiles) { const el = els.get(tile.id); if (el) updateContent(el, tile); }
}

// Live tiles: every few seconds, roll one tile to its next face.
const lastRoll = new Map();
function rotateLive() {
  if (document.hidden || editing) return;
  const now = Date.now();
  const candidates = [...els.entries()].filter(([id, el]) => {
    const box = el.querySelector('.faces');
    return box.children.length > 1 && now - (lastRoll.get(id) || 0) > 7000 && isVisible(el);
  });
  if (!candidates.length) return;
  const [id, el] = candidates[Math.floor(Math.random() * candidates.length)];
  lastRoll.set(id, now);
  const box = el.querySelector('.faces');
  const cur = box._cur || 0, next = (cur + 1) % box.children.length;
  const a = box.children[cur], b = box.children[next];
  // Park the incoming face below (no transition), then roll both.
  b.style.transition = 'none'; b.classList.remove('out'); b.classList.add('off');
  void b.offsetWidth; b.style.transition = '';
  a.classList.add('out'); b.classList.remove('off');
  box._cur = next;
  setTimeout(() => {
    a.style.transition = 'none'; a.classList.remove('out'); a.classList.add('off');
    void a.offsetWidth; a.style.transition = '';
  }, 750);
}
function isVisible(el) {
  const r = el.getBoundingClientRect();
  return r.bottom > 0 && r.top < window.innerHeight;
}

// ── Edit mode ───────────────────────────────────────────────────
export function enterEdit(id) {
  editing = true; selectedId = id;
  pageEl.classList.add('editing');
  if (navigator.vibrate) navigator.vibrate(18);
  render();
}
export function exitEdit() {
  if (!editing) return;
  editing = false; selectedId = null;
  pageEl.classList.remove('editing');
  render(); onSave(layout);
}
function select(id) { selectedId = id; render(); }

function nextSize(tile) {
  const sizes = CATALOG[tile.type].sizes.filter(s => DIMS[s][0] <= layout.cols);
  return sizes[(sizes.indexOf(tile.size) + 1) % sizes.length];
}

function tileBtn(btn, tile) {
  if (btn === 'unpin') {
    layout.tiles = layout.tiles.filter(t => t !== tile);
    selectedId = null;
  } else if (btn === 'resize') {
    tile.size = nextSize(tile);
  } else if (btn === 'color') {
    const i = PALETTE.indexOf(tile.color);
    tile.color = i === PALETTE.length - 1 ? undefined : PALETTE[i + 1];   // cycles back to accent
  }
  render(); onSave(layout);
}

export function pin(tile) {
  layout.tiles.push(tile);
  render(); onSave(layout);
  requestAnimationFrame(() => els.get(tile.id)?.scrollIntoView({ behavior: 'smooth', block: 'center' }));
}

// ── Pointer handling ────────────────────────────────────────────
let press = null;   // { id, el, x, y, timer, held, armed, dragging, moved, offX, offY, lastTarget }

function onDown(e) {
  const btn = e.target.closest('.tile-btn');
  const el = e.target.closest('.tile');
  if (!el || press) return;
  const tile = layout.tiles.find(t => t.id === el.dataset.id);
  if (!tile) return;

  if (btn && editing) {
    e.stopPropagation();
    const up = () => { el.removeEventListener('pointerup', up); tileBtn(btn.dataset.btn, tile); };
    el.addEventListener('pointerup', up);
    return;
  }

  press = { id: tile.id, el, tile, x: e.clientX, y: e.clientY, pid: e.pointerId };
  if (editing) {
    if (tile.id === selectedId) press.armed = true;
  } else {
    tilt(el, e);
    press.timer = setTimeout(() => {
      if (!press) return;
      press.held = true; press.armed = true;
      untilt(el);
      enterEdit(tile.id);
    }, HOLD_MS);
  }
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
  window.addEventListener('pointercancel', onCancel);
}

function onMove(e) {
  if (!press || e.pointerId !== press.pid) return;
  const dx = e.clientX - press.x, dy = e.clientY - press.y;
  const dist = Math.hypot(dx, dy);
  if (!press.armed) {
    if (dist > SLOP) { press.moved = true; clearTimeout(press.timer); untilt(press.el); }
    return;
  }
  if (!press.dragging && dist > 6) startDrag(e);
  if (press.dragging) dragTo(e.clientX, e.clientY);
}

function onUp(e) {
  if (!press || e.pointerId !== press.pid) return;
  const p = press; press = null;
  cleanupListeners();
  clearTimeout(p.timer);
  untilt(p.el);
  if (p.dragging) return endDrag(p);
  if (p.held || p.moved) return;
  if (editing) { if (p.id !== selectedId) select(p.id); return; }
  // A real tap: open it once the tilt has eased back.
  setTimeout(() => onOpen(p.tile), 60);
}

function onCancel() {
  if (!press) return;
  const p = press; press = null;
  cleanupListeners();
  clearTimeout(p.timer); untilt(p.el);
  if (p.dragging) endDrag(p);
}

function cleanupListeners() {
  window.removeEventListener('pointermove', onMove);
  window.removeEventListener('pointerup', onUp);
  window.removeEventListener('pointercancel', onCancel);
}

function tilt(el, e) {
  const r = el.getBoundingClientRect();
  const x = (e.clientX - r.left) / r.width - .5, y = (e.clientY - r.top) / r.height - .5;
  el.classList.add('pressed');
  el.style.transform = `perspective(600px) rotateX(${(-y * 14).toFixed(1)}deg) rotateY(${(x * 14).toFixed(1)}deg) scale(.97)`;
}
function untilt(el) { el.classList.remove('pressed'); el.style.transform = ''; }

// ── Drag to reorder ─────────────────────────────────────────────
let autoScroll = 0, rafId = 0, lastPointer = null;

function startDrag(e) {
  const p = press;
  p.dragging = true;
  const g = gridEl.getBoundingClientRect();
  const s = px(slots[p.id]);
  p.offX = e.clientX - g.left - s.left;
  p.offY = e.clientY - g.top - s.top;
  p.el.classList.add('dragging');
  try { p.el.setPointerCapture(p.pid); } catch { /* pointer already gone */ }
  rafId = requestAnimationFrame(scrollLoop);
}

function dragTo(cx, cy) {
  const p = press;
  lastPointer = { x: cx, y: cy };
  const g = gridEl.getBoundingClientRect();
  const left = cx - g.left - p.offX, top = cy - g.top - p.offY;
  p.el.style.left = `${left}px`; p.el.style.top = `${top}px`;

  // Edge auto-scroll.
  const sr = scrollEl.getBoundingClientRect();
  autoScroll = cy < sr.top + 80 ? -10 : cy > sr.bottom - 90 ? 10 : 0;

  // Which cell is under the dragged tile's top-left quadrant centre?
  const { unit, gap } = metrics;
  const step = unit + gap;
  const col = Math.max(0, Math.min(layout.cols - 1, Math.floor((left + unit / 2) / step)));
  const row = Math.max(0, Math.floor((top + unit / 2) / step));
  const occ = pack(layout.tiles).occ;
  const target = occ[row]?.[col];
  const now = Date.now();
  if (target === p.id || now - (p.lastMove || 0) < 140) return;
  const from = layout.tiles.findIndex(t => t.id === p.id);
  let to;
  if (target) to = layout.tiles.findIndex(t => t.id === target);
  else if (row >= occ.length) to = layout.tiles.length - 1;
  else return;   // a hole inside the grid — packing will fill it anyway
  if (to === from || target === p.lastTarget) return;
  p.lastTarget = target; p.lastMove = now;
  const [moved] = layout.tiles.splice(from, 1);
  layout.tiles.splice(to, 0, moved);
  render();
}

function scrollLoop() {
  if (!press?.dragging) return;
  if (autoScroll) {
    scrollEl.scrollTop += autoScroll;
    if (lastPointer) dragTo(lastPointer.x, lastPointer.y);
  }
  rafId = requestAnimationFrame(scrollLoop);
}

function endDrag(p) {
  cancelAnimationFrame(rafId);
  autoScroll = 0;
  p.el.classList.remove('dragging');
  render(); onSave(layout);
}

/**
 * app.js — VoiceOS Launcher frontend
 *
 * Replaces the Pygame UI with a WebView-compatible interface.
 * All keyboard shortcuts replaced with swipe/touch gestures.
 *
 * Gestures:
 *   Tap circle          → open text input
 *   Swipe up            → open text input (or app drawer if no input)
 *   Swipe down          → toggle settings panel
 *   Swipe left          → clear conversation / dismiss panels
 *   Swipe right         → conversation history
 *   Long press          → quick action menu
 */

import { Circle, CircleState } from './circle.js';

// ── DOM refs ────────────────────────────────────────────────────
const canvas         = document.getElementById('canvas');
const statusBar      = document.getElementById('status-bar');
const hintEl         = document.getElementById('hint');
const processingLabel= document.getElementById('processing-label');

const responsePanel  = document.getElementById('response-panel');
const responseScroll = document.getElementById('response-scroll');
const responseText   = document.getElementById('response-text');

const inputPanel     = document.getElementById('input-panel');
const chatInput      = document.getElementById('chat-input');
const sendBtn        = document.getElementById('send-btn');

const settingsPanel  = document.getElementById('settings-panel');
const clearBtn       = document.getElementById('clear-btn');
const providerBtns   = document.querySelectorAll('.provider-btn');

const appDrawer      = document.getElementById('app-drawer');
const drawerSearch   = document.getElementById('drawer-search');
const appGrid        = document.getElementById('app-grid');

const historyPanel   = document.getElementById('history-panel');
const historyList    = document.getElementById('history-list');

// ── State ───────────────────────────────────────────────────────
let circle;
let ws = null;
let activePanel = null;   // 'input' | 'settings' | 'drawer' | 'history' | null
let conversationHistory = [];  // [{q, a}]
let isProcessing = false;
let currentResponse = '';
let lastTap = 0;

// ── Canvas init ─────────────────────────────────────────────────
function initCanvas() {
  function resize() {
    canvas.width  = window.innerWidth  * window.devicePixelRatio;
    canvas.height = window.innerHeight * window.devicePixelRatio;
    canvas.style.width  = window.innerWidth  + 'px';
    canvas.style.height = window.innerHeight + 'px';
    const ctx = canvas.getContext('2d');
    ctx.scale(window.devicePixelRatio, window.devicePixelRatio);
  }
  resize();
  window.addEventListener('resize', resize);
  circle = new Circle(canvas);
}

// ── Animation loop ──────────────────────────────────────────────
let lastTs = null;
function frame(ts) {
  if (lastTs === null) lastTs = ts;
  const dt = Math.min((ts - lastTs) / 1000, 0.05); // cap at 50ms
  lastTs = ts;

  const ctx = canvas.getContext('2d');
  ctx.save();
  ctx.scale(1 / window.devicePixelRatio, 1 / window.devicePixelRatio);
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.restore();

  circle.update(dt);
  circle.draw();
  requestAnimationFrame(frame);
}

// ── WebSocket ───────────────────────────────────────────────────
function connectWS() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws`);

  ws.onopen = () => console.log('WS connected');

  ws.onmessage = (evt) => {
    const msg = JSON.parse(evt.data);
    switch (msg.type) {
      case 'chunk':
        appendResponseChunk(msg.text);
        break;
      case 'tool_call':
        appendResponseChunk(`\n[${msg.tool}…]\n`);
        break;
      case 'skill':
        prependBadge(msg.skill);
        break;
      case 'done':
        onResponseDone(msg);
        break;
      case 'status':
        updateStatusBar(msg);
        break;
      case 'error':
        appendResponseChunk(`\n[Error: ${msg.text}]`);
        onResponseDone({});
        break;
    }
  };

  ws.onclose = () => {
    console.log('WS closed, reconnecting in 2s');
    setTimeout(connectWS, 2000);
  };
}

function sendMessage(text) {
  if (!text.trim() || isProcessing) return;
  isProcessing = true;

  // Clear response area, show processing
  responseText.textContent = '';
  document.querySelectorAll('.badge').forEach(b => b.remove());
  currentResponse = '';
  showPanel('response');
  setCircleState(CircleState.PROCESSING);
  processingLabel.classList.add('visible');
  hintEl.style.opacity = '0';

  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'chat', text }));
  } else {
    // Fallback: HTTP POST
    fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text }),
    })
      .then(r => r.json())
      .then(data => {
        appendResponseChunk(data.text || data.error || '(no response)');
        onResponseDone(data);
      })
      .catch(err => {
        appendResponseChunk(`[Error: ${err}]`);
        onResponseDone({});
      });
  }
}

function appendResponseChunk(chunk) {
  currentResponse += chunk;
  responseText.textContent = currentResponse;
  // Auto-scroll to bottom
  responseScroll.scrollTop = responseScroll.scrollHeight;
}

function prependBadge(skillName) {
  const badge = document.createElement('span');
  badge.className = 'badge';
  badge.textContent = skillName;
  responseScroll.insertBefore(badge, responseText);
}

function onResponseDone(msg) {
  isProcessing = false;
  processingLabel.classList.remove('visible');
  setCircleState(CircleState.IDLE);
  hintEl.style.opacity = '1';

  // Save to history
  if (currentResponse) {
    const lastQ = chatInput.dataset.lastQuery || '';
    if (lastQ) {
      conversationHistory.unshift({ q: lastQ, a: currentResponse });
      if (conversationHistory.length > 50) conversationHistory.pop();
      renderHistory();
    }
  }

  if (msg.status) updateStatusBar(msg.status);
}

// ── Status bar ──────────────────────────────────────────────────
async function loadStatus() {
  try {
    const data = await fetch('/api/status').then(r => r.json());
    updateStatusBar(data);
  } catch { /* backend not ready yet */ }
}

function updateStatusBar(data) {
  if (data.provider && data.model) {
    statusBar.textContent = `${data.provider} · ${data.model}`;
  } else if (data.active_provider) {
    statusBar.textContent = `${data.active_provider} · ${data.active_model}`;
  }
  // Highlight active provider button
  if (data.active_provider || data.provider) {
    const active = data.active_provider || data.provider;
    providerBtns.forEach(btn => {
      btn.classList.toggle('active', btn.dataset.provider === active);
    });
  }
}

// ── Circle state helper ─────────────────────────────────────────
function setCircleState(state) {
  circle.setState(state);
}

// ── Panel management ─────────────────────────────────────────────
const PANELS = {
  input:    inputPanel,
  response: responsePanel,
  settings: settingsPanel,
  drawer:   appDrawer,
  history:  historyPanel,
};

function showPanel(name) {
  // Close current if switching
  if (activePanel && activePanel !== name) closePanel(activePanel, false);
  PANELS[name]?.classList.add('visible');
  activePanel = name;
  if (name === 'input') {
    setTimeout(() => chatInput.focus(), 100);
    setCircleState(CircleState.LISTENING);
  }
  if (name === 'drawer') loadApps();
}

function closePanel(name, resetActive = true) {
  PANELS[name]?.classList.remove('visible');
  if (resetActive) {
    activePanel = null;
    if (!isProcessing) setCircleState(CircleState.IDLE);
  }
}

function closeAllPanels() {
  Object.keys(PANELS).forEach(k => closePanel(k, false));
  activePanel = null;
  if (!isProcessing) setCircleState(CircleState.IDLE);
  hintEl.style.opacity = '1';
}

// ── App drawer ──────────────────────────────────────────────────
async function loadApps() {
  if (appGrid.dataset.loaded) return;
  try {
    const apps = await fetch('/api/apps').then(r => r.json());
    renderApps(apps);
    appGrid.dataset.loaded = '1';
  } catch {
    appGrid.innerHTML = '<p style="color:rgba(255,255,255,0.4);padding:20px;grid-column:1/-1">App list unavailable</p>';
  }
}

function renderApps(apps) {
  appGrid.innerHTML = '';
  apps.forEach(app => {
    const el = document.createElement('div');
    el.className = 'app-icon';
    el.innerHTML = `
      <div class="app-icon-img">
        ${app.icon ? `<img src="data:image/png;base64,${app.icon}" alt="">` : app.emoji || '📱'}
      </div>
      <span class="app-icon-label">${escHtml(app.name)}</span>`;
    el.addEventListener('click', () => launchApp(app.package));
    appGrid.appendChild(el);
  });
}

async function launchApp(pkg) {
  closeAllPanels();
  try {
    await fetch('/api/launch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ package: pkg }),
    });
  } catch (e) {
    console.error('Launch failed', e);
  }
}

// drawer search filter
drawerSearch.addEventListener('input', () => {
  const q = drawerSearch.value.toLowerCase();
  appGrid.querySelectorAll('.app-icon').forEach(el => {
    const label = el.querySelector('.app-icon-label').textContent.toLowerCase();
    el.style.display = label.includes(q) ? '' : 'none';
  });
});
drawerSearch.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// ── History ─────────────────────────────────────────────────────
function renderHistory() {
  historyList.innerHTML = '';
  conversationHistory.forEach(({ q, a }) => {
    const el = document.createElement('div');
    el.className = 'history-item';
    el.innerHTML = `<div class="hi-q">${escHtml(q)}</div><div class="hi-a">${escHtml(a.slice(0, 80))}</div>`;
    el.addEventListener('click', () => {
      closePanel('history');
      showPanel('input');
      chatInput.value = q;
    });
    historyList.appendChild(el);
  });
}

// ── Settings / provider switching ───────────────────────────────
providerBtns.forEach(btn => {
  btn.addEventListener('click', async () => {
    const provider = btn.dataset.provider;
    const model    = btn.dataset.model || null;
    try {
      const data = await fetch('/api/provider', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider, model }),
      }).then(r => r.json());
      updateStatusBar(data);
      providerBtns.forEach(b => b.classList.toggle('active', b === btn));
    } catch (e) { console.error('Provider switch failed', e); }
  });
});

clearBtn.addEventListener('click', async () => {
  await fetch('/api/clear', { method: 'POST' });
  conversationHistory = [];
  historyList.innerHTML = '';
  closeAllPanels();
  responseText.textContent = '';
  currentResponse = '';
});

// ── Input handling ──────────────────────────────────────────────
sendBtn.addEventListener('click', () => submitInput());
chatInput.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    submitInput();
  }
});
chatInput.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
chatInput.addEventListener('input', () => {
  // Auto-grow textarea
  chatInput.style.height = 'auto';
  chatInput.style.height = Math.min(chatInput.scrollHeight, 120) + 'px';
});

function submitInput() {
  const text = chatInput.value.trim();
  if (!text) return;
  chatInput.dataset.lastQuery = text;
  chatInput.value = '';
  chatInput.style.height = '';
  closePanel('input');
  sendMessage(text);
}

// ── Gesture engine ──────────────────────────────────────────────
// Detects swipe up/down/left/right on the main canvas area.
// Touch events that start on scrollable panels are not intercepted.

let touchStartX = 0, touchStartY = 0, touchStartTime = 0;
let longPressTimer = null;
const SWIPE_MIN = 60;   // px
const SWIPE_MAX_PERP = 80; // max perpendicular movement for directional swipe
const LONG_PRESS_MS = 500;

function isScrollableTarget(el) {
  // Don't capture gestures that start on interactive elements
  const panels = [inputPanel, responseScroll, appDrawer, historyPanel, settingsPanel];
  return panels.some(p => p.contains(el));
}

document.addEventListener('touchstart', (e) => {
  if (isScrollableTarget(e.target)) return;
  const t = e.touches[0];
  touchStartX = t.clientX;
  touchStartY = t.clientY;
  touchStartTime = Date.now();

  longPressTimer = setTimeout(() => {
    onLongPress(touchStartX, touchStartY);
  }, LONG_PRESS_MS);
}, { passive: true });

document.addEventListener('touchmove', (e) => {
  if (longPressTimer) {
    const t = e.touches[0];
    const dx = Math.abs(t.clientX - touchStartX);
    const dy = Math.abs(t.clientY - touchStartY);
    if (dx > 10 || dy > 10) { clearTimeout(longPressTimer); longPressTimer = null; }
  }
}, { passive: true });

document.addEventListener('touchend', (e) => {
  if (longPressTimer) { clearTimeout(longPressTimer); longPressTimer = null; }
  if (isScrollableTarget(e.target)) return;

  const t = e.changedTouches[0];
  const dx = t.clientX - touchStartX;
  const dy = t.clientY - touchStartY;
  const dt = Date.now() - touchStartTime;
  const adx = Math.abs(dx), ady = Math.abs(dy);

  // Tap detection (small movement, quick)
  if (adx < 15 && ady < 15 && dt < 300) {
    onTap(t.clientX, t.clientY);
    return;
  }

  if (adx < SWIPE_MIN && ady < SWIPE_MIN) return; // too short

  if (ady > adx && ady > SWIPE_MIN && adx < SWIPE_MAX_PERP) {
    if (dy < 0) onSwipeUp();
    else        onSwipeDown();
  } else if (adx > ady && adx > SWIPE_MIN && ady < SWIPE_MAX_PERP) {
    if (dx < 0) onSwipeLeft();
    else        onSwipeRight();
  }
}, { passive: true });

// ── Gesture actions ─────────────────────────────────────────────
function onTap(x, y) {
  const now = Date.now();
  // Double-tap on circle → clear conversation
  const circleCx = window.innerWidth / 2;
  const circleCy = window.innerHeight / 2;
  const dist = Math.hypot(x - circleCx, y - circleCy);
  if (dist < circle.radius / window.devicePixelRatio + 30) {
    if (now - lastTap < 400) {
      // Double tap
      fetch('/api/clear', { method: 'POST' });
      closeAllPanels();
      responseText.textContent = '';
      currentResponse = '';
      setCircleState(CircleState.IDLE);
      lastTap = 0;
      return;
    }
    lastTap = now;
  }

  // Tap anywhere else: if a panel is open, close it; otherwise open input
  if (activePanel && activePanel !== 'response') {
    closeAllPanels();
  } else if (!activePanel || activePanel === 'response') {
    showPanel('input');
  }
}

function onSwipeUp() {
  if (activePanel === 'history') { closePanel('history'); return; }
  if (activePanel === 'settings') { closePanel('settings'); return; }
  if (!activePanel || activePanel === 'response') {
    // First swipe up opens input; second opens app drawer
    if (activePanel === null) showPanel('input');
    else showPanel('drawer');
  } else if (activePanel === 'input') {
    showPanel('drawer');
  } else {
    closeAllPanels();
  }
}

function onSwipeDown() {
  if (activePanel === 'drawer') { closePanel('drawer'); return; }
  if (activePanel) { closeAllPanels(); return; }
  showPanel('settings');
}

function onSwipeLeft() {
  if (activePanel === 'history') { closePanel('history'); return; }
  closeAllPanels();
}

function onSwipeRight() {
  if (activePanel === 'history') { closePanel('history'); return; }
  closeAllPanels();
  showPanel('history');
}

function onLongPress(x, y) {
  // Quick actions: show a simple picker via the settings panel
  showPanel('settings');
}

// ── Keyboard fallback (desktop / hardware keyboard on mobile) ───
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { closeAllPanels(); return; }
  // Any printable key opens input (desktop compat)
  if (!activePanel && e.key.length === 1 && !e.metaKey && !e.ctrlKey) {
    showPanel('input');
    chatInput.value += e.key;
  }
});

// ── Utility ─────────────────────────────────────────────────────
function escHtml(str) {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// ── Boot sequence ────────────────────────────────────────────────
async function boot() {
  initCanvas();
  requestAnimationFrame(frame);
  connectWS();
  await loadStatus();
}

boot();

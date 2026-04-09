/**
 * app.js — VoiceOS Launcher frontend
 *
 * Gestures:
 *   Tap / swipe up        → open text input
 *   Swipe down            → close active panel
 *   Swipe left            → dismiss
 *   Swipe right           → conversation history
 *
 * Command shortcuts (type then Enter):
 *   call [name]              → open phone dialer for contact
 *   text [name]              → open SMS to contact
 *   open / launch [app]      → launch matching app
 *   navigate to / maps [place] → open Maps
 */

import { Circle, CircleState } from './circle.js';

// ── DOM refs ────────────────────────────────────────────────────
const canvas          = document.getElementById('canvas');
const statusBar       = document.getElementById('status-bar');
const hintEl          = document.getElementById('hint');
const processingLabel = document.getElementById('processing-label');

const responsePanel        = document.getElementById('response-panel');
const responseScroll       = document.getElementById('response-scroll');
const responseText         = document.getElementById('response-text');
const responseActions      = document.getElementById('response-actions');
const followupVoiceBtn     = document.getElementById('followup-voice-btn');
const followupTypeBtn      = document.getElementById('followup-type-btn');
const followupClearBtn     = document.getElementById('followup-clear-btn');

const msgDisplay      = document.getElementById('msg-display');
const msgDisplayText  = document.getElementById('msg-display-text');

const skillIndicator  = document.getElementById('skill-indicator');
const queueBadge      = document.getElementById('queue-badge');
const queuePanel      = document.getElementById('queue-panel');
const queueClose      = document.getElementById('queue-close');
const queueList       = document.getElementById('queue-list');
const queueEmpty      = document.getElementById('queue-empty');

const suggestionsEl    = document.getElementById('suggestions');

const inputDisplay     = document.getElementById('input-display');
const inputTextLarge   = document.getElementById('input-text-large');
const inputTyped       = document.getElementById('input-typed');
const inputPlaceholder = document.getElementById('input-placeholder');

const inputPanel      = document.getElementById('input-panel');
const chatInput       = document.getElementById('chat-input');
const sendBtn         = document.getElementById('send-btn');

const settingsPanel   = document.getElementById('settings-panel');
const clearBtn        = document.getElementById('clear-btn');
const providerBtns    = document.querySelectorAll('.provider-btn');
const apiKeyRow       = document.getElementById('api-key-row');
const apiKeyInput     = document.getElementById('api-key-input');
const saveKeyBtn      = document.getElementById('save-key-btn');
const keyStatus       = document.getElementById('key-status');
const ollamaModelRow  = document.getElementById('ollama-model-row');
const ollamaModelInput= document.getElementById('ollama-model-input');
const saveModelBtn    = document.getElementById('save-model-btn');
const ollamaUrlRow    = document.getElementById('ollama-url-row');
const ollamaUrlInput  = document.getElementById('ollama-url-input');
const testOllamaBtn   = document.getElementById('test-ollama-btn');
const ollamaStatus    = document.getElementById('ollama-status');

const appDrawer       = document.getElementById('app-drawer');
const drawerClose     = document.getElementById('drawer-close');
const drawerSearch    = document.getElementById('drawer-search');
const appGrid         = document.getElementById('app-grid');

const historyPanel    = document.getElementById('history-panel');
const historyList     = document.getElementById('history-list');

// ── Mode toggle refs ─────────────────────────────────────────────
const modeToggle      = document.getElementById('mode-toggle');
const modeBtns        = document.querySelectorAll('.mode-btn');
const agentIndicator  = document.getElementById('agent-indicator');
const agentLabel      = document.getElementById('agent-label');
const confirmDialog   = document.getElementById('confirm-dialog');
const confirmMessage  = document.getElementById('confirm-message');
const confirmYes      = document.getElementById('confirm-yes');
const confirmNo       = document.getElementById('confirm-no');

// ── Voice input refs ─────────────────────────────────────────────
const voiceBar        = document.getElementById('voice-bar');
const micBtn          = document.getElementById('mic-btn');
const micBtnSmall     = document.getElementById('mic-btn-small');
const keyboardBtn     = document.getElementById('keyboard-btn');
const voiceStatus     = document.getElementById('voice-status');

// ── State ───────────────────────────────────────────────────────
let circle;
let activePanel = null;   // 'input' | 'settings' | 'drawer' | 'history' | null
let conversationHistory = [];
let isProcessing = false;
let currentResponse = '';
let lastTap = 0;
let allApps = [];         // cached app list for suggestions
let currentMode = 'assistant';   // 'assistant' | 'agent'
let heartbeatTimer = null;
let confirmResolve = null;
let inputMode = 'idle';           // 'idle' | 'voice' | 'keyboard'
let speechRec = null;
let voiceAutoSubmitTimer = null;

// ── Command patterns ─────────────────────────────────────────────
const CMD = [
  { re: /^call\s+(.+)/i,                                    action: 'call',     g: 1 },
  { re: /^(text|sms|message)\s+(.+)/i,                      action: 'text',     g: 2 },
  { re: /^(open|launch|start)\s+(.+)/i,                     action: 'open',     g: 2 },
  { re: /^(navigate to|go to|maps?|directions? to)\s+(.+)/i, action: 'navigate', g: 2 },
];

function parseCommand(text) {
  for (const { re, action, g } of CMD) {
    const m = text.match(re);
    if (m) return { action, target: m[g].trim() };
  }
  return null;
}

// ── Canvas init ─────────────────────────────────────────────────
function initCanvas() {
  function resize() {
    canvas.width  = window.innerWidth  * window.devicePixelRatio;
    canvas.height = window.innerHeight * window.devicePixelRatio;
    canvas.style.width  = window.innerWidth  + 'px';
    canvas.style.height = window.innerHeight + 'px';
  }
  resize();
  window.addEventListener('resize', resize);
  circle = new Circle(canvas);
}

// ── Animation loop ──────────────────────────────────────────────
let lastTs = null;
function frame(ts) {
  if (lastTs === null) lastTs = ts;
  const dt = Math.min((ts - lastTs) / 1000, 0.05);
  lastTs = ts;

  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  circle.update(dt);
  circle.draw();
  requestAnimationFrame(frame);
}

// ── Chat / Agent ─────────────────────────────────────────────────
function sendMessage(text) {
  if (!text.trim() || isProcessing) return;
  isProcessing = true;

  responseText.textContent = '';
  document.querySelectorAll('.badge, .tool-badge').forEach(b => b.remove());
  currentResponse = '';
  responseActions.classList.remove('visible');
  closePanel('input');
  hideSuggestions();
  responsePanel.classList.add('visible');
  activePanel = 'response';
  setCircleState(CircleState.PROCESSING);
  processingLabel.classList.add('visible');
  hintEl.style.opacity = '0';

  const endpoint = '/api/agent';
  const xhr = new XMLHttpRequest();
  xhr.open('POST', endpoint);
  xhr.setRequestHeader('Content-Type', 'application/json');

  let xhrPos  = 0;
  let lineAcc = '';   // accumulate partial lines for tag detection

  xhr.onprogress = () => {
    const chunk = xhr.responseText.substring(xhrPos);
    xhrPos = xhr.responseText.length;
    if (!chunk) return;
    lineAcc += chunk;
    // Process complete lines for special tags, stream rest to response
    const lines = lineAcc.split('\n');
    lineAcc = lines.pop();   // last element may be incomplete
    for (const line of lines) {
      processStreamLine(line + '\n');
    }
  };
  xhr.onload = () => {
    if (lineAcc) processStreamLine(lineAcc);
    const remaining = xhr.responseText.substring(xhrPos);
    if (remaining) processStreamLine(remaining);
    onResponseDone({});
  };
  xhr.onerror = () => {
    appendResponseChunk('[Network error]');
    onResponseDone({});
  };
  xhr.send(JSON.stringify({ text, mode: currentMode }));
}

// Parse special server-sent lines ([TOOL:...], [RESULT:...], [CONFIRM:...])
// Everything else is streamed to the response as normal text.
function processStreamLine(line) {
  const toolMatch    = line.match(/^\[TOOL:([^\]]+)\]\s*$/);
  const resultMatch  = line.match(/^\[RESULT:([^\]]*)\]\s*$/);
  const confirmMatch = line.match(/^\[CONFIRM:([^:]+):(.+)\]\s*$/);
  const skillMatch   = line.match(/^\[SKILL:([^\]]+)\]\s*$/);

  if (skillMatch) {
    showSkillIndicator(skillMatch[1]);
  } else if (toolMatch) {
    showToolBadge(toolMatch[1]);
  } else if (resultMatch) {
    // Suppress result lines from main text (they're shown in badges)
  } else if (confirmMatch) {
    const toolName = confirmMatch[1];
    let args = {};
    try { args = JSON.parse(confirmMatch[2]); } catch { /* ignore */ }
    showConfirmDialog(toolName, args);
  } else {
    appendResponseChunk(line);
  }
}

function showToolBadge(toolName) {
  const badge = document.createElement('span');
  badge.className = 'tool-badge';
  const icons = {
    launch_app: '📱', web_search: '🔍', read_calendar: '📅',
    create_event: '📅', set_alarm: '⏰', get_battery: '🔋',
    get_volume: '🔊', set_volume: '🔊', remember: '💾', recall: '💾',
    call_contact: '📞', send_sms: '💬', navigate: '🗺️',
  };
  badge.textContent = `${icons[toolName] || '⚙️'} ${toolName.replace(/_/g, ' ')}`;
  responseScroll.insertBefore(badge, responseText);
}

function showConfirmDialog(toolName, args) {
  const descriptions = {
    call_contact: `Call ${args.name_or_number || '?'}`,
    send_sms:     `Send SMS to ${args.name_or_number || '?'}`,
    navigate:     `Navigate to ${args.destination || '?'}`,
  };
  confirmMessage.textContent = descriptions[toolName] || `Allow ${toolName.replace(/_/g, ' ')}?`;
  const badge = document.createElement('span');
  badge.className = 'tool-badge confirm';
  badge.textContent = `⚠️ ${toolName.replace(/_/g, ' ')} — awaiting confirmation`;
  responseScroll.insertBefore(badge, responseText);
  confirmDialog.classList.add('visible');
  // Resolution is cosmetic here; actual execution is handled server-side
  // (auto-proceeds currently; future: block on this response)
  confirmResolve = (allowed) => {
    confirmDialog.classList.remove('visible');
    confirmResolve = null;
    if (!allowed) {
      appendResponseChunk('\n[Action denied by user]\n');
    }
  };
}

function appendResponseChunk(chunk) {
  currentResponse += chunk;
  responseText.textContent = currentResponse;
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
  setCircleState(CircleState.RESPONDING);
  setTimeout(() => { if (!isProcessing) setCircleState(CircleState.IDLE); }, 1500);
  hintEl.style.opacity = '1';
  if (currentResponse) responseActions.classList.add('visible');
  // Refresh queue — agent may have staged new actions
  pollQueue();
  // Refresh task cache — agent may have created/updated tasks
  loadTaskCache();
  resetIdleTimer();

  if (currentResponse) {
    const lastQ = chatInput.dataset.lastQuery || '';
    if (lastQ) {
      conversationHistory.unshift({ q: lastQ, a: currentResponse });
      if (conversationHistory.length > 50) conversationHistory.pop();
      renderHistory();
    }
  }
}

// ── Command execution ───────────────────────────────────────────
async function executeCommand(cmd) {
  const { action, target } = cmd;

  switch (action) {
    case 'call': {
      showFeedback(`Looking up ${target}…`);
      const contacts = await fetchContacts(target);
      if (contacts.length > 0) {
        const c = contacts[0];
        await post('/api/call', { number: c.phone });
        showFeedback(`Calling ${c.name} (${c.phone})…`);
      } else {
        showFeedback(`No contact found for "${target}"`);
      }
      break;
    }
    case 'text': {
      showFeedback(`Looking up ${target}…`);
      const contacts = await fetchContacts(target);
      if (contacts.length > 0) {
        const c = contacts[0];
        await post('/api/sms', { number: c.phone });
        showFeedback(`Opening SMS to ${c.name}…`);
      } else {
        showFeedback(`No contact found for "${target}"`);
      }
      break;
    }
    case 'open': {
      const app = findApp(target);
      if (app) {
        launchApp(app.package);
        showFeedback(`Opening ${app.name}…`);
      } else {
        showFeedback(`App not found: "${target}"`);
      }
      break;
    }
    case 'navigate': {
      await post('/api/maps', { query: target });
      showFeedback(`Navigating to ${target}…`);
      break;
    }
  }
}

let msgDisplayTimer = null;
function showMsgDisplay(text, durationMs = 5000) {
  msgDisplayText.textContent = text;
  msgDisplay.classList.add('visible');
  clearTimeout(msgDisplayTimer);
  msgDisplayTimer = setTimeout(() => msgDisplay.classList.remove('visible'), durationMs);
}

function showFeedback(msg) {
  showMsgDisplay(msg, 4000);
  setCircleState(CircleState.RESPONDING);
  setTimeout(() => { if (!isProcessing) setCircleState(CircleState.IDLE); }, 3000);
}

function findApp(query) {
  const q = query.toLowerCase();
  return allApps.find(a => a.name.toLowerCase() === q)
      || allApps.find(a => a.name.toLowerCase().startsWith(q))
      || allApps.find(a => a.name.toLowerCase().includes(q))
      || null;
}

async function fetchContacts(name) {
  try {
    return await fetch(`/api/contacts?q=${encodeURIComponent(name)}`).then(r => r.json());
  } catch { return []; }
}

async function post(url, body) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then(r => r.json()).catch(() => ({}));
}

// ── Suggestions ─────────────────────────────────────────────────
let suggestDebounce = null;

function updateSuggestions() {
  clearTimeout(suggestDebounce);
  suggestDebounce = setTimeout(_doUpdateSuggestions, 120);
}

async function _doUpdateSuggestions() {
  const text = chatInput.value;
  if (text.length < 2) { hideSuggestions(); return; }

  const cmd = parseCommand(text);

  if (cmd && (cmd.action === 'call' || cmd.action === 'text')) {
    // Contact suggestions
    if (cmd.target.length < 2) { hideSuggestions(); return; }
    const contacts = await fetchContacts(cmd.target);
    if (!contacts.length) { hideSuggestions(); return; }
    const actionLabel = cmd.action === 'call' ? 'CALL' : 'TEXT';
    renderSuggestions(contacts.slice(0, 4).map(c => ({
      icon: null,
      label: c.name,
      sub: c.phone,
      action: actionLabel,
      onClick() {
        hideSuggestions();
        chatInput.value = '';
        if (cmd.action === 'call') post('/api/call', { number: c.phone }).then(() => showFeedback(`Calling ${c.name}…`));
        else                       post('/api/sms',  { number: c.phone }).then(() => showFeedback(`Opening SMS to ${c.name}…`));
        closePanel('input');
      },
    })));

  } else if (cmd && cmd.action === 'open') {
    // App suggestions filtered by target
    const q = cmd.target.toLowerCase();
    const matches = allApps.filter(a => a.name.toLowerCase().includes(q)).slice(0, 4);
    if (!matches.length) { hideSuggestions(); return; }
    renderSuggestions(matches.map(a => ({
      icon: a.icon,
      label: a.name,
      action: 'OPEN',
      onClick() { hideSuggestions(); chatInput.value = ''; launchApp(a.package); },
    })));

  } else if (cmd && cmd.action === 'navigate') {
    // Single confirm suggestion
    renderSuggestions([{
      icon: null,
      label: cmd.target,
      action: 'MAPS',
      onClick() {
        hideSuggestions(); chatInput.value = '';
        post('/api/maps', { query: cmd.target }).then(() => showFeedback(`Navigating to ${cmd.target}…`));
        closePanel('input');
      },
    }]);

  } else {
    // Generic app name filter (no command prefix)
    const q = text.toLowerCase();
    const matches = allApps.filter(a =>
      a.name.toLowerCase().startsWith(q) || a.name.toLowerCase().includes(q)
    ).slice(0, 4);
    if (!matches.length) { hideSuggestions(); return; }
    renderSuggestions(matches.map(a => ({
      icon: a.icon,
      label: a.name,
      action: 'OPEN',
      onClick() { hideSuggestions(); chatInput.value = ''; launchApp(a.package); },
    })));
  }
}

function renderSuggestions(items) {
  suggestionsEl.innerHTML = '';
  items.forEach((item, i) => {
    const el = document.createElement('div');
    el.className = 'suggestion-item';

    const iconHtml = item.icon
      ? `<img src="data:image/png;base64,${item.icon}" alt="">`
      : '📱';
    const subHtml = item.sub
      ? `<div class="si-sub">${escHtml(item.sub)}</div>`
      : '';

    el.innerHTML = `
      <div class="si-icon">${item.icon ? `<img src="data:image/png;base64,${item.icon}" alt="">` : '<span>📱</span>'}</div>
      <div class="si-body">
        <div class="si-label">${escHtml(item.label)}</div>
        ${subHtml}
      </div>
      <span class="si-action">${item.action}</span>`;
    el.addEventListener('click', item.onClick);
    el.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
    suggestionsEl.appendChild(el);

    // Stagger animation
    setTimeout(() => el.classList.add('show'), i * 30);
  });
  suggestionsEl.classList.add('visible');
}

function hideSuggestions() {
  suggestionsEl.classList.remove('visible');
  suggestionsEl.innerHTML = '';
}

// ── Status bar ──────────────────────────────────────────────────
async function loadStatus() {
  try {
    const data = await fetch('/api/status').then(r => r.json());
    updateStatusBar(data);
    if (data.ollama_url) ollamaUrlInput.value = data.ollama_url;
    if (data.active_model || data.model) ollamaModelInput.value = data.active_model || data.model;
  } catch { /* backend not ready yet */ }
}

function updateStatusBar(data) {
  if (data.provider && data.model) {
    statusBar.textContent = `${data.provider} · ${data.model}`;
  } else if (data.active_provider) {
    statusBar.textContent = `${data.active_provider} · ${data.active_model}`;
  }
  const active = data.active_provider || data.provider;
  if (active) {
    providerBtns.forEach(btn => {
      btn.classList.toggle('active', btn.dataset.provider === active);
    });
    showProviderControls(active, data.has_key);
  }
}

// ── Circle state ────────────────────────────────────────────────
function setCircleState(state) { circle.setState(state); }

// ── Panel management ─────────────────────────────────────────────
const PANELS = {
  input:    inputPanel,
  response: responsePanel,
  settings: settingsPanel,
  drawer:   appDrawer,
  history:  historyPanel,
  queue:    queuePanel,
};

function showPanel(name) {
  hideTaskOverlay();
  if (activePanel && activePanel !== name) closePanel(activePanel, false);
  PANELS[name]?.classList.add('visible');
  activePanel = name;
  if (name === 'input') {
    inputDisplay.classList.add('visible');
    sendBtn.classList.add('visible');
    refreshInputDisplay();
    setCircleState(CircleState.LISTENING);
    // Default to voice mode; keyboard is opt-in
    showInputVoiceMode();
  } else {
    // Dismiss keyboard whenever any non-input panel opens
    chatInput.blur();
  }
  if (name === 'drawer') loadApps();
  if (name !== 'input') hideSuggestions();
}

function closePanel(name, resetActive = true) {
  PANELS[name]?.classList.remove('visible');
  if (name === 'input') {
    inputDisplay.classList.remove('visible');
    inputDisplay.classList.remove('voice-mode');
    sendBtn.classList.remove('visible');
    micBtnSmall.classList.remove('visible');
    hideSuggestions();
    stopVoice();
    chatInput.blur();
    inputMode = 'idle';
  }
  if (resetActive) {
    activePanel = null;
    if (!isProcessing) setCircleState(CircleState.IDLE);
  }
}

function closeAllPanels() {
  ['input', 'settings', 'drawer', 'history', 'queue'].forEach(k => closePanel(k, false));
  hideSuggestions();
  activePanel = currentResponse ? 'response' : null;
  if (!isProcessing) setCircleState(CircleState.IDLE);
  hintEl.style.opacity = '1';
}

// ── App drawer ──────────────────────────────────────────────────
async function loadApps() {
  if (appGrid.dataset.loaded) return;
  try {
    allApps = await fetch('/api/apps').then(r => r.json());
    renderApps(allApps);
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
        ${app.icon ? `<img src="data:image/png;base64,${app.icon}" alt="">` : '📱'}
      </div>
      <span class="app-icon-label">${escHtml(app.name)}</span>`;
    el.addEventListener('click', () => launchApp(app.package));
    appGrid.appendChild(el);
  });
}

async function launchApp(pkg) {
  closeAllPanels();
  try {
    await post('/api/launch', { package: pkg });
  } catch (e) { console.error('Launch failed', e); }
}

drawerClose.addEventListener('click', () => closePanel('drawer'));
drawerClose.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

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

// ── Settings ─────────────────────────────────────────────────────
const CLOUD_PROVIDERS = ['anthropic', 'openai', 'groq'];

providerBtns.forEach(btn => {
  btn.addEventListener('click', async () => {
    const provider = btn.dataset.provider;
    const model    = btn.dataset.model || null;
    try {
      const payload = { provider, model };
      if (provider === 'ollama') payload.ollama_url = ollamaUrlInput.value.trim() || undefined;
      const data = await post('/api/provider', payload);
      updateStatusBar(data);
      providerBtns.forEach(b => b.classList.toggle('active', b === btn));
      showProviderControls(provider, data.has_key);
    } catch (e) { console.error('Provider switch failed', e); }
  });
});

function showProviderControls(provider, hasKey) {
  if (provider === 'ollama') {
    ollamaModelRow.style.display = 'flex';
    ollamaUrlRow.style.display = 'flex';
    ollamaStatus.textContent = '';
    apiKeyRow.style.display = 'none';
    keyStatus.textContent = '';
  } else if (CLOUD_PROVIDERS.includes(provider)) {
    ollamaModelRow.style.display = 'none';
    ollamaUrlRow.style.display = 'none';
    ollamaStatus.textContent = '';
    apiKeyRow.style.display = 'flex';
    apiKeyInput.value = '';
    keyStatus.textContent = hasKey ? 'Key saved' : 'No key saved';
  } else {
    ollamaModelRow.style.display = 'none';
    ollamaUrlRow.style.display = 'none';
    apiKeyRow.style.display = 'none';
    keyStatus.textContent = '';
    ollamaStatus.textContent = '';
  }
}

// Save model name
saveModelBtn.addEventListener('click', async () => {
  const model = ollamaModelInput.value.trim();
  if (!model) return;
  const data = await post('/api/provider', { provider: 'ollama', model });
  updateStatusBar(data);
  ollamaStatus.textContent = `Model set to ${model}`;
});
ollamaModelInput.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
saveModelBtn.addEventListener('touchstart',    e => e.stopPropagation(), { passive: true });

// Save Ollama URL
ollamaUrlInput.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
ollamaUrlInput.addEventListener('change', async () => {
  const url = ollamaUrlInput.value.trim();
  if (!url) return;
  await post('/api/provider', { provider: 'ollama', ollama_url: url });
  ollamaStatus.textContent = 'URL saved';
});

// Test Ollama connection
testOllamaBtn.addEventListener('click', async () => {
  // Save URL first
  const url = ollamaUrlInput.value.trim();
  if (url) await post('/api/provider', { provider: 'ollama', ollama_url: url });
  ollamaStatus.textContent = 'Testing…';
  try {
    const result = await fetch('/api/ollama/test').then(r => r.json());
    if (result.ok) {
      const models = (result.models || []).join(', ') || 'none found';
      ollamaStatus.textContent = `Connected · Models: ${models}`;
    } else {
      ollamaStatus.textContent = `Failed: ${result.error}`;
    }
  } catch (e) {
    ollamaStatus.textContent = `Error: ${e.message}`;
  }
});
testOllamaBtn.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

saveKeyBtn.addEventListener('click', async () => {
  const key = apiKeyInput.value.trim();
  if (!key) return;
  const activeProvider = document.querySelector('.provider-btn.active')?.dataset?.provider || 'anthropic';
  await post('/api/keys', { provider: activeProvider, key });
  apiKeyInput.value = '';
  keyStatus.textContent = 'Key saved';
});

apiKeyRow.addEventListener('touchstart',  e => e.stopPropagation(), { passive: true });
apiKeyInput.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

clearBtn.addEventListener('click', async () => {
  await fetch('/api/clear', { method: 'POST' });
  conversationHistory = [];
  historyList.innerHTML = '';
  responseText.textContent = '';
  currentResponse = '';
  responsePanel.classList.remove('visible');
  responseActions.classList.remove('visible');
  closeAllPanels();
});

// ── Input handling ──────────────────────────────────────────────
sendBtn.addEventListener('click', () => submitInput());
sendBtn.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

chatInput.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submitInput(); }
});
chatInput.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
chatInput.addEventListener('input', () => {
  refreshInputDisplay();
  if (activePanel === 'input') updateSuggestions();
});

// Tap on the text area (not buttons) switches to keyboard mode
inputDisplay.addEventListener('click', (e) => {
  if (e.target.closest('#mic-btn, #keyboard-btn, #voice-bar')) return;
  if (activePanel === 'input') showInputKeyboardMode();
});

function refreshInputDisplay() {
  const text = chatInput.value;
  inputTyped.textContent = text;
  // Scale font down for longer text
  inputTextLarge.classList.remove('small', 'xsmall');
  if (text.length > 80) inputTextLarge.classList.add('xsmall');
  else if (text.length > 35) inputTextLarge.classList.add('small');
  // Show/hide placeholder
  inputPlaceholder.style.opacity = text.length ? '0' : '1';
}

async function submitInput() {
  const text = chatInput.value.trim();
  if (!text) return;

  chatInput.blur();    // dismiss keyboard immediately
  stopVoice();
  chatInput.dataset.lastQuery = text;
  chatInput.value = '';
  refreshInputDisplay();

  const cmd = parseCommand(text);
  if (cmd) {
    hideSuggestions();
    closePanel('input');
    await executeCommand(cmd);
    return;
  }

  sendMessage(text);
}

// ── Gesture engine ──────────────────────────────────────────────
let touchStartX = 0, touchStartY = 0, touchStartTime = 0;
let longPressTimer = null;
const SWIPE_MIN     = 60;
const SWIPE_MAX_PERP = 80;
const LONG_PRESS_MS  = 500;

function isScrollableTarget(el) {
  const panels = [inputPanel, responseScroll, appDrawer, historyPanel, suggestionsEl];
  return panels.some(p => p.contains(el));
}

document.addEventListener('touchstart', (e) => {
  if (isScrollableTarget(e.target)) return;
  const t = e.touches[0];
  touchStartX = t.clientX; touchStartY = t.clientY;
  touchStartTime = Date.now();
  longPressTimer = setTimeout(() => onLongPress(touchStartX, touchStartY), LONG_PRESS_MS);
}, { passive: true });

document.addEventListener('touchmove', (e) => {
  if (longPressTimer) {
    const t = e.touches[0];
    if (Math.abs(t.clientX - touchStartX) > 10 || Math.abs(t.clientY - touchStartY) > 10) {
      clearTimeout(longPressTimer); longPressTimer = null;
    }
  }
}, { passive: true });

document.addEventListener('touchend', (e) => {
  if (longPressTimer) { clearTimeout(longPressTimer); longPressTimer = null; }
  if (isScrollableTarget(e.target)) return;

  const t   = e.changedTouches[0];
  const dx  = t.clientX - touchStartX;
  const dy  = t.clientY - touchStartY;
  const dt  = Date.now() - touchStartTime;
  const adx = Math.abs(dx), ady = Math.abs(dy);

  if (adx < 25 && ady < 25 && dt < 350) {
    onTap(t.clientX, t.clientY); return;
  }
  if (adx < SWIPE_MIN && ady < SWIPE_MIN) return;

  if (ady > adx && ady > SWIPE_MIN && adx < SWIPE_MAX_PERP) {
    if (dy < 0) onSwipeUp(); else onSwipeDown();
  } else if (adx > ady && adx > SWIPE_MIN && ady < SWIPE_MAX_PERP) {
    if (dx < 0) onSwipeLeft(); else onSwipeRight();
  }
}, { passive: true });

function onTap(x, y) {
  const now = Date.now();
  // Double-tap anywhere to clear conversation
  if (now - lastTap < 400) {
    fetch('/api/clear', { method: 'POST' });
    closeAllPanels();
    responseText.textContent = ''; currentResponse = '';
    responseActions.classList.remove('visible');
    setCircleState(CircleState.IDLE); lastTap = 0; return;
  }
  lastTap = now;
  if (activePanel && activePanel !== 'response') {
    closeAllPanels();
  } else {
    // Open input in voice mode and auto-start voice
    showPanel('input');
    setTimeout(() => { if (inputMode === 'voice' && !speechRec) startVoice(); }, 220);
  }
}

function onSwipeUp() {
  if (activePanel === 'history')  { closePanel('history'); return; }
  if (activePanel === 'settings') { closePanel('settings'); return; }
  if (!activePanel || activePanel === 'response') showPanel('input');
  else if (activePanel === 'input') showPanel('drawer');
  else closeAllPanels();
}

function onSwipeDown() {
  if (activePanel) { closeAllPanels(); return; }
  // No panel open — swipe-down does nothing (settings are in left panel)
}

function onSwipeLeft()  {
  if (activePanel === 'history') { closePanel('history'); return; }
  closeAllPanels();
}

function onSwipeRight() {
  if (activePanel === 'history') { closePanel('history'); return; }
  closeAllPanels();
  showPanel('history');
}

function onLongPress() { showPanel('history'); }

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { closeAllPanels(); return; }
  if (!activePanel && e.key.length === 1 && !e.metaKey && !e.ctrlKey) {
    showPanel('input'); chatInput.value += e.key;
  }
});

// ── Utility ─────────────────────────────────────────────────────
function escHtml(str) {
  return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

// ── Skill indicator ──────────────────────────────────────────────
let skillHideTimer = null;
function showSkillIndicator(skillName) {
  skillIndicator.textContent = skillName.replace(/_/g,' ');
  skillIndicator.classList.add('visible');
  clearTimeout(skillHideTimer);
  skillHideTimer = setTimeout(() => skillIndicator.classList.remove('visible'), 3000);
}

// ── Action queue ─────────────────────────────────────────────────
const QUEUE_POLL_MS = 8_000;
const ACTION_LABELS = {
  draft_email:    { icon: '✉️', label: 'Email draft' },
  send_sms:       { icon: '💬', label: 'SMS message' },
  send_whatsapp:  { icon: '📱', label: 'WhatsApp message' },
  queue_message:  { icon: '📨', label: 'Message' },
};

async function pollQueue() {
  try {
    const items = await fetch('/api/queue').then(r => r.json());
    const pending = items.filter(a => a.status === 'pending');
    if (pending.length > 0) {
      queueBadge.textContent = `${pending.length} pending`;
      queueBadge.classList.add('visible');
    } else {
      queueBadge.classList.remove('visible');
    }
    renderQueuePanel(pending);
  } catch { /* backend not ready */ }
}

function renderQueuePanel(items) {
  queueList.innerHTML = '';
  if (items.length === 0) {
    queueEmpty.style.display = 'block';
    return;
  }
  queueEmpty.style.display = 'none';
  items.forEach(action => {
    const meta = ACTION_LABELS[action.type] || { icon: '⚙️', label: action.type };
    const el = document.createElement('div');
    el.className = 'queue-item';
    el.innerHTML = `
      <div class="qi-type">${meta.icon} ${meta.label}</div>
      <div class="qi-preview">${escHtml(action.preview)}</div>
      <div class="qi-actions">
        <button class="qi-approve" data-id="${escHtml(action.id)}">Send ✓</button>
        <button class="qi-dismiss" data-id="${escHtml(action.id)}">Discard ✕</button>
      </div>`;
    el.querySelector('.qi-approve').addEventListener('click', () => approveAction(action.id));
    el.querySelector('.qi-dismiss').addEventListener('click', () => dismissAction(action.id));
    el.querySelectorAll('button').forEach(b => b.addEventListener('touchstart', e => e.stopPropagation(), { passive: true }));
    queueList.appendChild(el);
  });
}

async function approveAction(id) {
  try {
    await fetch('/api/queue/approve', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id }),
    });
    await pollQueue();
  } catch (e) { console.error('Approve failed', e); }
}

async function dismissAction(id) {
  try {
    await fetch('/api/queue/dismiss', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id }),
    });
    await pollQueue();
  } catch (e) { console.error('Dismiss failed', e); }
}

// Queue panel controls
queueBadge.addEventListener('click', () => {
  queuePanel.classList.add('visible');
  activePanel = 'queue';
});
queueBadge.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
queueClose.addEventListener('click', () => { queuePanel.classList.remove('visible'); activePanel = null; });
queueClose.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
queuePanel.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// Start polling queue every 8s
setInterval(pollQueue, QUEUE_POLL_MS);
setTimeout(pollQueue, 3_000);

// ── Voice input ──────────────────────────────────────────────────

function showInputVoiceMode() {
  inputMode = 'voice';
  inputDisplay.classList.add('voice-mode');
  micBtn.classList.remove('listening');
  micBtnSmall.classList.remove('visible');
  voiceStatus.textContent = 'tap to speak';
}

function showInputKeyboardMode(focusImmediate = false) {
  if (inputMode === 'keyboard') { chatInput.focus(); return; }
  inputMode = 'keyboard';
  stopVoice();
  inputDisplay.classList.remove('voice-mode');
  micBtnSmall.classList.add('visible');
  // Focus immediately when called from a direct touch/click handler so Android
  // shows the keyboard. The setTimeout fallback handles programmatic calls.
  if (focusImmediate) {
    chatInput.focus();
  } else {
    setTimeout(() => chatInput.focus(), 60);
  }
}

function startVoice() {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) {
    voiceStatus.textContent = 'voice unavailable';
    setTimeout(() => showInputKeyboardMode(), 900);
    return;
  }
  speechRec = new SR();
  speechRec.continuous = false;
  speechRec.interimResults = true;
  speechRec.lang = 'en-US';
  speechRec.maxAlternatives = 1;

  micBtn.classList.add('listening');
  voiceStatus.textContent = 'listening…';
  setCircleState(CircleState.LISTENING);

  let finalTranscript = '';

  speechRec.onresult = (e) => {
    clearTimeout(voiceAutoSubmitTimer);
    finalTranscript = '';
    let interim = '';
    for (const result of e.results) {
      if (result.isFinal) finalTranscript += result[0].transcript;
      else interim += result[0].transcript;
    }
    chatInput.value = finalTranscript || interim;
    refreshInputDisplay();
  };

  speechRec.onend = () => {
    micBtn.classList.remove('listening');
    speechRec = null;
    const text = chatInput.value.trim();
    if (text) {
      voiceStatus.textContent = 'sending…';
      voiceAutoSubmitTimer = setTimeout(() => submitInput(), 700);
    } else {
      voiceStatus.textContent = 'tap to speak';
    }
  };

  speechRec.onerror = (e) => {
    micBtn.classList.remove('listening');
    speechRec = null;
    clearTimeout(voiceAutoSubmitTimer);
    if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
      voiceStatus.textContent = 'mic blocked · use ⌨ keyboard';
    } else if (e.error === 'no-speech') {
      voiceStatus.textContent = 'tap to speak';
    } else {
      voiceStatus.textContent = 'try again';
    }
  };

  try {
    speechRec.start();
  } catch {
    micBtn.classList.remove('listening');
    voiceStatus.textContent = 'tap to speak';
    speechRec = null;
  }
}

function stopVoice() {
  clearTimeout(voiceAutoSubmitTimer);
  if (speechRec) {
    try { speechRec.abort(); } catch { /* ignore */ }
    speechRec = null;
  }
  micBtn.classList.remove('listening');
}

// Mic button — tap to start/stop voice
micBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  if (speechRec) { stopVoice(); voiceStatus.textContent = 'tap to speak'; }
  else startVoice();
});
micBtn.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// Keyboard toggle — use touchend for immediate focus (Android keyboard needs direct gesture)
keyboardBtn.addEventListener('touchend', (e) => {
  e.preventDefault();
  e.stopPropagation();
  showInputKeyboardMode(true);
});
keyboardBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  showInputKeyboardMode(true);
});

// Small mic button (shown in keyboard mode) — switch back to voice
micBtnSmall.addEventListener('click', (e) => {
  e.stopPropagation();
  showInputVoiceMode();
});
micBtnSmall.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// ── Response follow-up action buttons ───────────────────────────
followupVoiceBtn.addEventListener('touchend', (e) => {
  e.preventDefault(); e.stopPropagation();
  showPanel('input');
  setTimeout(() => { if (inputMode === 'voice' && !speechRec) startVoice(); }, 220);
});
followupVoiceBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  showPanel('input');
  setTimeout(() => { if (inputMode === 'voice' && !speechRec) startVoice(); }, 220);
});

followupTypeBtn.addEventListener('touchend', (e) => {
  e.preventDefault(); e.stopPropagation();
  showPanel('input');
  showInputKeyboardMode(true);
});
followupTypeBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  showPanel('input');
  showInputKeyboardMode(true);
});

followupClearBtn.addEventListener('touchend', (e) => {
  e.preventDefault(); e.stopPropagation();
  fetch('/api/clear', { method: 'POST' });
  responseText.textContent = '';
  currentResponse = '';
  responsePanel.classList.remove('visible');
  responseActions.classList.remove('visible');
  activePanel = null;
  setCircleState(CircleState.IDLE);
  hintEl.style.opacity = '1';
});
followupClearBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  fetch('/api/clear', { method: 'POST' });
  responseText.textContent = '';
  currentResponse = '';
  responsePanel.classList.remove('visible');
  responseActions.classList.remove('visible');
  activePanel = null;
  setCircleState(CircleState.IDLE);
  hintEl.style.opacity = '1';
});

// Prevent response panel touches from triggering swipe gestures
responseActions.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// ── Task overlay refs ────────────────────────────────────────────
const taskOverlay      = document.getElementById('task-overlay');
const taskOverlayList  = document.getElementById('task-overlay-list');
const taskOverlayCount = document.getElementById('task-overlay-count');
const taskOverlayAdd   = document.getElementById('task-overlay-add');
const taskOverlayClose = document.getElementById('task-overlay-close');

let taskCache       = [];
let taskOverlayOpen = false;
let idleTimer       = null;
const IDLE_SHOW_MS  = 18_000;    // show tasks after 18s idle
const OVERLAY_AUTO_HIDE_MS = 9_000;  // auto-hide after 9s
let overlayHideTimer = null;

// ── Task data helpers ────────────────────────────────────────────
async function loadTaskCache() {
  try {
    taskCache = await fetch('/api/tasks').then(r => r.json());
  } catch { taskCache = []; }
}

async function createTask(title, priority = 'medium', notes = '') {
  try {
    const t = await fetch('/api/tasks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, priority, notes }),
    }).then(r => r.json());
    taskCache.push(t);
    return t;
  } catch { return null; }
}

async function updateTask(id, fields) {
  try {
    const t = await fetch('/api/tasks', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, ...fields }),
    }).then(r => r.json());
    const idx = taskCache.findIndex(x => x.id === id);
    if (idx >= 0) taskCache[idx] = t;
    return t;
  } catch { return null; }
}

async function deleteTask(id) {
  await fetch('/api/tasks', {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  }).catch(() => {});
  taskCache = taskCache.filter(t => t.id !== id);
}

// ── Task overlay UI ──────────────────────────────────────────────
function renderTaskOverlay() {
  taskOverlayList.innerHTML = '';
  const active = taskCache.filter(t => t.status !== 'done');
  const done   = taskCache.filter(t => t.status === 'done');
  const all    = [...active, ...done];

  const pending = active.length;
  taskOverlayCount.textContent = pending ? `${pending} active` : 'all done';

  if (all.length === 0) {
    const el = document.createElement('div');
    el.className = 'tov-empty';
    el.textContent = 'No tasks yet. Ask the agent to add some.';
    taskOverlayList.appendChild(el);
    return;
  }

  all.forEach((task, idx) => {
    const el       = document.createElement('div');
    const isDone   = task.status === 'done';
    const priority = task.priority || 'medium';
    const dotClass = isDone ? 'done' : (task.status === 'in_progress' ? 'in_progress' : priority);

    el.className = `tov-item${isDone ? ' done' : ''}`;

    const notes = task.notes ? escHtml(task.notes.slice(0, 60)) : '';
    el.innerHTML = `
      <span class="tov-dot ${dotClass}"></span>
      <div class="tov-body">
        <div class="tov-title">${escHtml(task.title)}</div>
        ${notes ? `<div class="tov-meta">${notes}</div>` : ''}
      </div>
      <button class="tov-advance" data-id="${task.id}" data-title="${escHtml(task.title)}">▶ advance</button>`;

    // Tap item → advance via agent
    el.addEventListener('click', (e) => {
      if (e.target.classList.contains('tov-advance')) return;
      if (isDone) return;
      advanceTask(task);
    });

    // Advance button
    el.querySelector('.tov-advance')?.addEventListener('click', (e) => {
      e.stopPropagation();
      advanceTask(task);
    });

    taskOverlayList.appendChild(el);
  });

  // Stagger items from bottom→top (last item appears first = rolls up)
  const items = Array.from(taskOverlayList.querySelectorAll('.tov-item'));
  items.reverse().forEach((item, i) => {
    setTimeout(() => item.classList.add('in'), 40 + i * 65);
  });
}

function showTaskOverlay(autoHide = true) {
  if (taskOverlayOpen) return;
  if (taskCache.length === 0 && activePanel) return;  // don't show empty over active UI
  taskOverlayOpen = true;
  renderTaskOverlay();
  taskOverlay.classList.add('visible');
  clearTimeout(overlayHideTimer);
  if (autoHide) {
    overlayHideTimer = setTimeout(hideTaskOverlay, OVERLAY_AUTO_HIDE_MS);
  }
}

function hideTaskOverlay() {
  if (!taskOverlayOpen) return;
  taskOverlayOpen = false;
  taskOverlay.classList.remove('visible');
  clearTimeout(overlayHideTimer);
}

function advanceTask(task) {
  hideTaskOverlay();
  const prompt = `Work on this task and complete or advance it as much as possible: [${task.id}] "${task.title}"${task.notes ? ` — ${task.notes}` : ''}`;
  chatInput.dataset.lastQuery = prompt;
  sendMessage(prompt);
}

// ── Idle timer — shows task overlay after idle period ─────────────
// Re-arms itself so the overlay appears periodically whenever the screen is idle.
function resetIdleTimer() {
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => {
    if (!isProcessing && !activePanel && !taskOverlayOpen) {
      loadTaskCache().then(() => {
        if (taskCache.filter(t => t.status !== 'done').length > 0) showTaskOverlay(true);
      });
    }
    resetIdleTimer();   // always re-arm for periodic display
  }, IDLE_SHOW_MS);
}

// Also show on a fixed periodic schedule regardless of idle state
const TASK_PERIODIC_MS = 120_000;  // every 2 minutes
setInterval(() => {
  if (!isProcessing && !activePanel && !taskOverlayOpen) {
    loadTaskCache().then(() => {
      if (taskCache.filter(t => t.status !== 'done').length > 0) showTaskOverlay(true);
    });
  }
}, TASK_PERIODIC_MS);

// Reset idle timer on any touch/interaction
document.addEventListener('touchstart', resetIdleTimer, { passive: true, capture: true });
document.addEventListener('keydown',    resetIdleTimer, { capture: true });

// Task overlay controls
taskOverlayClose.addEventListener('click', hideTaskOverlay);
taskOverlayClose.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
taskOverlay.addEventListener('touchstart', e => {
  e.stopPropagation();
  // Reset auto-hide timer on any touch within overlay
  clearTimeout(overlayHideTimer);
  overlayHideTimer = setTimeout(hideTaskOverlay, OVERLAY_AUTO_HIDE_MS);
}, { passive: true });

taskOverlayAdd.addEventListener('click', () => {
  hideTaskOverlay();
  showPanel('input');
  chatInput.value = 'Add task: ';
  refreshInputDisplay();
  // Pre-filled text — switch to keyboard so user can dictate or edit
  showInputKeyboardMode();
  setTimeout(() => { chatInput.selectionStart = chatInput.selectionEnd = chatInput.value.length; }, 100);
});
taskOverlayAdd.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// ── Notification card ─────────────────────────────────────────────
const notifCard        = document.getElementById('notif-card');
const notifCardApp     = document.getElementById('notif-card-app');
const notifCardTime    = document.getElementById('notif-card-time');
const notifCardTitle   = document.getElementById('notif-card-title');
const notifCardText    = document.getElementById('notif-card-text');
const notifAskBtn      = document.getElementById('notif-ask-btn');
const notifTaskBtn     = document.getElementById('notif-task-btn');
const notifDismissBtn  = document.getElementById('notif-dismiss-btn');
const notifSettingsBtn = document.getElementById('notif-settings-btn');
const notifSettingsSt  = document.getElementById('notif-settings-status');

let currentNotif    = null;
let notifAutoHide   = null;
let seenNotifKeys   = new Set();
let notifEnabled    = false;

const NOTIF_POLL_MS      = 30_000;
const NOTIF_AUTO_HIDE_MS = 10_000;

function relTime(ms) {
  const diff = Date.now() - ms;
  if (diff < 60_000)  return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  return `${Math.floor(diff / 3_600_000)}h ago`;
}

function showNotifCard(n) {
  currentNotif = n;
  notifCardApp.textContent   = n.app || n.pkg;
  notifCardTime.textContent  = relTime(n.time);
  notifCardTitle.textContent = n.title || '';
  notifCardText.textContent  = n.text  || '';
  notifCard.classList.add('visible');
  clearTimeout(notifAutoHide);
  notifAutoHide = setTimeout(hideNotifCard, NOTIF_AUTO_HIDE_MS);
}

function hideNotifCard() {
  notifCard.classList.remove('visible');
  clearTimeout(notifAutoHide);
}

notifDismissBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  if (currentNotif) {
    fetch('/api/notifications', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: currentNotif.key }),
    }).catch(() => {});
    seenNotifKeys.add(currentNotif.key);
  }
  hideNotifCard();
});

notifAskBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  const n = currentNotif;
  hideNotifCard();
  if (!n) return;
  const prompt = `I got a notification from ${n.app}: "${n.title}"${n.text ? ` — ${n.text}` : ''}. What should I do? Suggest actions and offer to handle it.`;
  sendMessage(prompt);
});

notifTaskBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  const n = currentNotif;
  hideNotifCard();
  if (!n) return;
  const taskTitle = n.title ? `${n.app}: ${n.title}` : n.app;
  createTask(taskTitle, 'medium', n.text || '').then(() => {
    loadTaskCache().then(() => showTaskOverlay(false));
  });
});

notifAskBtn.addEventListener('touchstart',     e => e.stopPropagation(), { passive: true });
notifTaskBtn.addEventListener('touchstart',    e => e.stopPropagation(), { passive: true });
notifDismissBtn.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
notifCard.addEventListener('touchstart',       e => e.stopPropagation(), { passive: true });

// Notification settings button (opens system settings)
notifSettingsBtn.addEventListener('click', () => {
  fetch('/api/notifications/settings', { method: 'POST' }).catch(() => {});
});
notifSettingsBtn.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// Poll notifications
async function pollNotifications() {
  try {
    const data = await fetch('/api/notifications').then(r => r.json());
    notifEnabled = !!data.enabled;
    notifSettingsSt.textContent = notifEnabled ? 'active' : 'tap to enable';

    if (!notifEnabled) return;

    const all = data.notifications || [];
    const fresh = all.filter(n => !seenNotifKeys.has(n.key));
    if (fresh.length > 0) {
      // Mark all as seen before showing — avoids re-showing on next poll
      fresh.forEach(n => seenNotifKeys.add(n.key));
      // Show even mid-session — don't block on isProcessing/activePanel
      // (card slides in above everything; user can dismiss it)
      showNotifCard(fresh[0]);
    }
  } catch {}
}

// Start polling after a short boot delay, then every 30s
setTimeout(() => {
  pollNotifications();
  setInterval(pollNotifications, NOTIF_POLL_MS);
}, 5_000);

// ── Mode toggle ──────────────────────────────────────────────────
modeBtns.forEach(btn => {
  btn.addEventListener('click', async () => {
    currentMode = btn.dataset.mode;
    modeBtns.forEach(b => b.classList.toggle('active', b === btn));
    if (currentMode === 'agent') {
      agentIndicator.classList.add('visible');
      startHeartbeat();
      // Tell server to start probe heartbeat
      await fetch('/api/heartbeat/control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'start' }),
      }).catch(() => {});
    } else {
      agentIndicator.classList.remove('visible');
      stopHeartbeat();
      await fetch('/api/heartbeat/control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'stop' }),
      }).catch(() => {});
    }
  });
  btn.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
});
modeToggle.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// Confirm dialog buttons
confirmYes.addEventListener('click', () => confirmResolve?.(true));
confirmNo.addEventListener('click',  () => confirmResolve?.(false));
confirmYes.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
confirmNo.addEventListener('touchstart',  e => e.stopPropagation(), { passive: true });
confirmDialog.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// ── Heartbeat ────────────────────────────────────────────────────
const HEARTBEAT_INTERVAL = 30_000;

function startHeartbeat() {
  stopHeartbeat();
  runHeartbeat();
  heartbeatTimer = setInterval(runHeartbeat, HEARTBEAT_INTERVAL);
}

function stopHeartbeat() {
  if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
}

function runHeartbeat() {
  if (isProcessing) return;   // don't interrupt active conversation
  agentLabel.textContent = 'checking…';
  const xhr = new XMLHttpRequest();
  xhr.open('GET', '/api/heartbeat');
  let pos = 0;
  let text = '';
  xhr.onprogress = () => {
    const chunk = xhr.responseText.substring(pos);
    pos = xhr.responseText.length;
    text += chunk;
  };
  xhr.onload = () => {
    const remaining = xhr.responseText.substring(pos);
    text += remaining;
    agentLabel.textContent = 'agent active';
    const trimmed = text.trim();
    if (trimmed && trimmed !== '[idle]') {
      showHeartbeatNotice(trimmed);
    }
    // Refresh task cache so overlay is up-to-date
    loadTaskCache();
  };
  xhr.onerror = () => { agentLabel.textContent = 'agent active'; };
  xhr.send();
}

function showHeartbeatNotice(text) {
  showMsgDisplay(text, 7000);
}

// ── Boot ────────────────────────────────────────────────────────
async function boot() {
  initCanvas();
  requestAnimationFrame(frame);
  await loadStatus();
  // Pre-load apps and tasks in background
  fetch('/api/apps').then(r => r.json()).then(apps => { allApps = apps; }).catch(() => {});
  await loadTaskCache();
  resetIdleTimer();
}

boot();

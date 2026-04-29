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
let appsLoadedAt = 0;
const APP_CACHE_TTL_MS = 15 * 60 * 1000;
let currentMode = 'assistant';   // 'assistant' | 'agent'
let heartbeatTimer = null;
let discoveryStatusTimer = null;
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

  // Suppress system lines — device snapshot, result previews, internal markers
  const trimmed = line.trim();
  if (/^[╔╠╚║═]/.test(trimmed)) return;
  if (/^\[RESULT:/i.test(trimmed)) return;
  if (/^\[DEVICE/i.test(trimmed)) return;

  if (skillMatch) {
    showSkillIndicator(skillMatch[1]);
  } else if (toolMatch) {
    showToolBadge(toolMatch[1]);
  } else if (resultMatch) {
    // Already caught above, but keep for safety
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
    get_contact_profile: '🤝', add_relationship_note: '📝',
    log_interaction: '✅', get_relationship_health: '💚',
    suggest_social_outreach: '💌',
    run_shell: '⌨️', get_bridge_setup: '🔧',
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
  responseText.innerHTML = renderMarkdown(currentResponse);
  responseScroll.scrollTop = responseScroll.scrollHeight;
}

// ── Lightweight markdown renderer ─────────────────────────────────
function renderMarkdown(text) {
  // Strip device snapshot blocks — model context, not user-facing
  text = text.replace(/╔═.*?╚═[^\n]*/gs, '').replace(/^\s*╠.*$/gm, '');

  // Escape HTML first, then selectively un-escape markdown constructs
  let h = text
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

  // Code blocks (```...```)
  h = h.replace(/```[\w]*\n?([\s\S]*?)```/g,
    (_, code) => `<pre class="md-code">${code.trim()}</pre>`);

  // Inline code
  h = h.replace(/`([^`]+)`/g, '<code class="md-inline">$1</code>');

  // Headers
  h = h.replace(/^### (.+)$/gm, '<div class="md-h3">$1</div>');
  h = h.replace(/^## (.+)$/gm,  '<div class="md-h2">$1</div>');
  h = h.replace(/^# (.+)$/gm,   '<div class="md-h1">$1</div>');

  // Bold / italic
  h = h.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
  h = h.replace(/\*\*(.+?)\*\*/g,     '<strong>$1</strong>');
  h = h.replace(/\*(.+?)\*/g,         '<em>$1</em>');

  // Bullet lists — group consecutive lines starting with -/•/·
  h = h.replace(/((?:^[ \t]*[-•·] .+\n?)+)/gm, (block) => {
    const items = block.trim().split('\n').map(l =>
      `<li>${l.replace(/^[ \t]*[-•·] /, '').trim()}</li>`).join('');
    return `<ul class="md-ul">${items}</ul>`;
  });

  // Numbered lists
  h = h.replace(/((?:^[ \t]*\d+\. .+\n?)+)/gm, (block) => {
    const items = block.trim().split('\n').map(l =>
      `<li>${l.replace(/^[ \t]*\d+\. /, '').trim()}</li>`).join('');
    return `<ol class="md-ol">${items}</ol>`;
  });

  // Horizontal rule
  h = h.replace(/^---+$/gm, '<hr class="md-hr">');

  // Paragraphs — double newline → paragraph break, single → <br>
  h = h.replace(/\n{2,}/g, '</p><p class="md-p">');
  h = h.replace(/\n/g, '<br>');
  h = `<p class="md-p">${h}</p>`;

  // Clean up empty paragraphs
  h = h.replace(/<p class="md-p"><\/p>/g, '');
  h = h.replace(/<p class="md-p">(<(?:ul|ol|pre|div|hr))/g, '$1');
  h = h.replace(/(>)<\/p>(\s*)<p class="md-p">(<(?:ul|ol|pre|div|hr))/g, '>$3');

  return h;
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
  showMsgDisplay(msg, 7000);
  setCircleState(CircleState.RESPONDING);
  setTimeout(() => { if (!isProcessing) setCircleState(CircleState.IDLE); }, 5000);
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

async function post(url, body, method = 'POST') {
  return fetch(url, {
    method,
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
const peoplePanel  = document.getElementById('people-panel');
const peopleClose  = document.getElementById('people-close');
const peopleList   = document.getElementById('people-list');
const peopleEmpty  = document.getElementById('people-empty');

const PANELS = {
  input:    inputPanel,
  response: responsePanel,
  settings: settingsPanel,
  drawer:   appDrawer,
  history:  historyPanel,
  queue:    queuePanel,
  people:   peoplePanel,
};

function showPanel(name) {
  hideTaskOverlay();
  if (activePanel && activePanel !== name) closePanel(activePanel, false);
  PANELS[name]?.classList.add('visible');
  activePanel = name;
  if (name === 'input') {
    inputDisplay.classList.add('visible');
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
    hideSuggestions();
    stopVoice();
    chatInput.blur();
    inputMode = 'idle';
    updateBottomBar();
  }
  if (resetActive) {
    activePanel = null;
    if (!isProcessing) setCircleState(CircleState.IDLE);
  }
}

function closeAllPanels() {
  ['input', 'settings', 'drawer', 'history', 'queue', 'people'].forEach(k => closePanel(k, false));
  hideSuggestions();
  activePanel = currentResponse ? 'response' : null;
  if (!isProcessing) setCircleState(CircleState.IDLE);
  hintEl.style.opacity = '1';
}

// ── App drawer ──────────────────────────────────────────────────
async function loadApps() {
  if (Date.now() - appsLoadedAt < APP_CACHE_TTL_MS) return;
  try {
    allApps = await fetch('/api/apps').then(r => r.json());
    renderApps(allApps);
    appsLoadedAt = Date.now();
  } catch {
    if (!allApps.length)
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

// ── People / Social CRM panel ────────────────────────────────────

const peopleImportBtn   = document.getElementById('people-import-btn');
const peopleReviewBtn   = document.getElementById('people-review-btn');
const peopleStatTotal   = document.getElementById('people-stat-total');
const peopleStatAttn    = document.getElementById('people-stat-attention');
const peopleSearch      = document.getElementById('people-search');
const peopleFilterChips = document.querySelectorAll('.pf-chip');

let crmActiveType  = 'all';
let crmAllContacts = [];    // full list from last load
let expandedCard   = null;  // DOM element of currently expanded card
let crmLoaded      = false; // true after first successful load; false again after import

// Avatar colour palette
const AVATAR_COLORS = [
  '#2a6fdb','#c4413c','#27843f','#925abc','#b86e00',
  '#1a8a8a','#c44c7e','#5d6cc0','#4a7c59','#7c5d4a'
];
function avatarColor(name) {
  let h = 0; for (const c of name) h = (h * 31 + c.charCodeAt(0)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function avatarInitials(name) {
  const parts = name.trim().split(/\s+/);
  return parts.length >= 2
    ? (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
    : name.slice(0, 2).toUpperCase();
}

function daysBadge(days) {
  if (days < 0)  return { cls: 'none',  label: 'no history' };
  if (days === 0) return { cls: 'fresh', label: 'today' };
  if (days <= 10) return { cls: 'fresh', label: `${days}d ago` };
  if (days <= 30) return { cls: 'warm',  label: `${days}d ago` };
  return               { cls: 'cold',  label: `${days}d ago` };
}

async function openPeoplePanel() {
  showPanel('people');
  if (crmLoaded) return;                  // already populated — panel shows instantly
  if (crmAllContacts.length > 0) {        // stale cache: render immediately, refresh in bg
    peopleList.innerHTML = '';
    crmAllContacts.forEach(c => peopleList.appendChild(buildPersonCard(c)));
    loadPeoplePanel();                    // non-awaited background refresh
  } else {
    await loadPeoplePanel();              // true first load — wait for it
  }
}

async function loadPeoplePanel() {
  // Don't clear the list until data arrives — keeps existing cards visible during refresh
  expandedCard = null;
  peopleEmpty.style.display = 'none';

  let data = null;
  try {
    const params = new URLSearchParams();
    if (crmActiveType === 'followup') {
      params.set('followup', 'true');
    } else if (crmActiveType && crmActiveType !== 'all') {
      params.set('type', crmActiveType);
    }
    const q = peopleSearch.value.trim();
    if (q) params.set('q', q);
    data = await fetch('/api/crm/contacts?' + params).then(r => r.json());
  } catch (e) {
    console.error('CRM load failed', e);
    return;
  }

  // Clear and repopulate only once data is ready
  peopleList.innerHTML = '';
  const contacts = data?.contacts ?? [];
  crmAllContacts = contacts;

  peopleStatTotal.textContent = `${data?.total ?? 0} contacts`;
  const followupCount = contacts.filter(c => c.followUp).length;
  const attn = data?.needsAttention ?? 0;
  if (followupCount > 0) {
    peopleStatAttn.textContent = `· ${followupCount} follow up`;
  } else {
    peopleStatAttn.textContent = attn > 0 ? `· ${attn} need attention` : '';
  }

  crmLoaded = true;

  if (contacts.length === 0) {
    peopleEmpty.style.display = '';
    return;
  }
  contacts.forEach(c => peopleList.appendChild(buildPersonCard(c)));
}

function buildPersonCard(contact) {
  const card = document.createElement('div');
  card.className = 'person-card';

  const { cls: daysCls, label: daysLabel } = daysBadge(contact.daysSince ?? -1);
  const initials   = avatarInitials(contact.name);
  const avatarBg   = avatarColor(contact.name);
  const typeStr    = contact.type || '';
  const tags       = contact.tags || [];
  const notes      = contact.notes || [];         // [{ts, text}]
  const noteCount  = notes.length;
  const lastNote   = notes[noteCount - 1];
  let   followUp   = contact.followUp || false;
  const frequency  = contact.frequency || '';
  const freqOverdue = contact.frequencyOverdue || false;
  const sentiment  = contact.sentiment || {};
  const birthday   = contact.birthday || '';

  // Build type tag HTML
  const typeHtml = typeStr
    ? `<span class="person-type-tag ${typeStr.toLowerCase()}">${escHtml(typeStr)}</span>`
    : '';
  // Extra tags (first 2)
  const extraTagsHtml = tags.slice(0, 2).map(t => `<span class="person-extra-tag">${escHtml(t)}</span>`).join('');
  // Phone small
  const phoneHtml = contact.phone
    ? `<span class="person-phone-small">${escHtml(contact.phone)}</span>`
    : '';

  const sentimentEmoji = { great: '😊', ok: '😐', struggling: '😔', busy: '🏃' };
  const freqBadge = frequency
    ? `<span class="person-extra-tag ${freqOverdue ? 'freq-overdue' : 'freq-ok'}">${freqOverdue ? '⚠ ' : ''}${frequency}</span>`
    : '';
  const sentimentBadge = sentiment.value
    ? `<span class="person-extra-tag">${sentimentEmoji[sentiment.value] || ''} ${sentiment.value}</span>`
    : '';

  card.innerHTML = `
    <div class="person-card-top">
      <div class="person-avatar" style="background:${avatarBg}">${initials}${followUp ? '<span class="person-followup-dot"></span>' : ''}</div>
      <div class="person-card-info">
        <div class="person-card-row1">
          <span class="person-name">${escHtml(contact.name)}</span>
          <span class="person-days ${daysCls}">${daysLabel}</span>
        </div>
        <div class="person-card-row2">
          ${typeHtml}${extraTagsHtml}
          ${!typeStr && !tags.length ? phoneHtml : ''}
          ${freqBadge}${sentimentBadge}
          ${noteCount > 0 ? `<span class="person-extra-tag">📝 ${noteCount}</span>` : ''}
          ${followUp ? '<span class="person-extra-tag followup-badge">🔔</span>' : ''}
          <span class="person-expand-chevron">▾</span>
        </div>
      </div>
    </div>
    ${lastNote ? `<div class="person-note-preview">${escHtml(lastNote.text)}</div>` : ''}
    <div class="person-quick-actions">
      <button class="pqa-btn${followUp ? ' followup-active' : ''}" data-qa="followup" title="Follow-up flag">🔔</button>
      <button class="pqa-btn" data-qa="draft" title="Draft message">✍ Draft</button>
      <button class="pqa-btn" data-qa="call">📞</button>
      <button class="pqa-btn" data-qa="text">💬</button>
      <button class="pqa-btn" data-qa="agent">🤖</button>
      <button class="pqa-btn danger" data-qa="remove">✕</button>
    </div>
    <div class="person-detail">
      <div class="person-detail-label">Relationship</div>
      <div class="person-type-picker">
        ${['friend','family','work','mentor','acquaintance','other'].map(t =>
          `<button class="ptp-btn${typeStr===t?' active':''}" data-rtype="${t}">${t}</button>`
        ).join('')}
      </div>

      <div class="person-detail-label">Contact Frequency</div>
      <div class="person-freq-picker">
        ${['daily','weekly','biweekly','monthly','quarterly'].map(f =>
          `<button class="pfq-btn${frequency===f?' active':''}" data-freq="${f}">${f}</button>`
        ).join('')}
      </div>

      <div class="person-detail-label">Sentiment</div>
      <div class="person-sentiment-picker">
        ${[['great','😊'],['ok','😐'],['struggling','😔'],['busy','🏃']].map(([s,e]) =>
          `<button class="psnt-btn${sentiment.value===s?' active':''}" data-sentiment="${s}">${e} ${s}</button>`
        ).join('')}
      </div>

      <div class="person-detail-label">Birthday <span class="person-detail-hint">(MM-DD)</span></div>
      <input class="person-birthday-input" type="text" placeholder="e.g. 03-15" maxlength="5"
        value="${escHtml(birthday)}" autocomplete="off" autocorrect="off" spellcheck="false">

      <div class="person-detail-label">Tags</div>
      <div class="person-tags-row" data-tagrow>
        ${tags.map(t => `
          <span class="person-tag-pill">
            ${escHtml(t)}
            <button class="person-tag-remove" data-removetag="${escHtml(t)}">×</button>
          </span>`).join('')}
        <input class="person-tag-input" placeholder="+ add tag" maxlength="20" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false">
      </div>

      <div class="person-detail-label">Notes${noteCount > 0 ? ` (${noteCount})` : ''}</div>
      <div class="person-notes-list">
        ${notes.length === 0
          ? '<div style="color:var(--text-dim);font-size:12px;padding:4px 0">No notes yet.</div>'
          : notes.map(n => `
            <div class="person-note-entry">
              ${n.ts ? `<div class="person-note-ts">${escHtml(n.ts)}</div>` : ''}
              <div class="person-note-text">${escHtml(n.text)}</div>
            </div>`).join('')
        }
      </div>
      <div class="person-note-add">
        <textarea class="person-note-textarea" placeholder="Add a note…" rows="2"></textarea>
        <button class="person-note-save" disabled>Save</button>
      </div>
    </div>
  `;

  // ── Top row tap → expand/collapse
  card.querySelector('.person-card-top').addEventListener('click', (e) => {
    e.stopPropagation();
    if (expandedCard && expandedCard !== card) {
      expandedCard.classList.remove('expanded');
    }
    card.classList.toggle('expanded');
    expandedCard = card.classList.contains('expanded') ? card : null;
  });

  // ── Follow-up toggle
  card.querySelector('[data-qa="followup"]').addEventListener('click', async e => {
    e.stopPropagation();
    followUp = !followUp;
    contact.followUp = followUp;
    const btn = e.currentTarget;
    btn.classList.toggle('followup-active', followUp);
    btn.textContent = followUp ? '🔔 On' : '🔔';
    // Update the badge row
    const row2 = card.querySelector('.person-card-row2');
    let badge = row2.querySelector('.followup-badge');
    if (followUp && !badge) {
      badge = document.createElement('span');
      badge.className = 'person-extra-tag followup-badge';
      badge.textContent = '🔔 follow up';
      const chevron = row2.querySelector('.person-expand-chevron');
      row2.insertBefore(badge, chevron);
    } else if (!followUp && badge) {
      badge.remove();
    }
    // Update avatar dot
    const avatar = card.querySelector('.person-avatar');
    let dot = avatar.querySelector('.person-followup-dot');
    if (followUp && !dot) {
      dot = document.createElement('span');
      dot.className = 'person-followup-dot';
      avatar.appendChild(dot);
    } else if (!followUp && dot) {
      dot.remove();
    }
    await post('/api/crm/contact/update', { name: contact.key || contact.name, followUp });
    // Update the stat bar
    const attnCount = crmAllContacts.filter(c => c.followUp).length;
    peopleStatAttn.textContent = attnCount > 0 ? `· ${attnCount} follow up` : '';
  });

  // ── Quick actions
  card.querySelector('[data-qa="call"]').addEventListener('click', e => {
    e.stopPropagation();
    closePanel('people');
    if (contact.phone) { post('/api/call', { number: contact.phone }); showFeedback(`Calling ${contact.name}…`); }
    else sendMessage(`Call ${contact.name}`);
  });
  card.querySelector('[data-qa="text"]').addEventListener('click', e => {
    e.stopPropagation();
    closePanel('people');
    if (contact.phone) { post('/api/sms', { number: contact.phone }); }
    else sendMessage(`Text ${contact.name}`);
  });
  card.querySelector('[data-qa="draft"]').addEventListener('click', e => {
    e.stopPropagation();
    closePanel('people');
    sendMessage(`Draft a warm outreach message to ${contact.name}. Use their profile and notes to make it personal.`);
  });
  card.querySelector('[data-qa="agent"]').addEventListener('click', e => {
    e.stopPropagation();
    closePanel('people');
    sendMessage(`Pull up the full profile for ${contact.name} and help me reach out`);
  });
  card.querySelector('[data-qa="remove"]').addEventListener('click', async e => {
    e.stopPropagation();
    if (!confirm(`Remove ${contact.name} from People?`)) return;
    await post('/api/crm/contact/delete', { name: contact.key || contact.name });
    await loadPeoplePanel();
  });

  // ── Relationship type picker
  card.querySelectorAll('.ptp-btn').forEach(btn => {
    btn.addEventListener('click', async e => {
      e.stopPropagation();
      const type = btn.dataset.rtype;
      card.querySelectorAll('.ptp-btn').forEach(b => b.classList.toggle('active', b === btn));
      await post('/api/crm/contact/update', { name: contact.key || contact.name, type });
      contact.type = type;
      // Update badge in top row
      const row2 = card.querySelector('.person-card-row2');
      const existing = row2.querySelector('.person-type-tag');
      if (existing) existing.remove();
      const newTag = document.createElement('span');
      newTag.className = `person-type-tag ${type}`;
      newTag.textContent = type;
      row2.insertBefore(newTag, row2.firstChild);
    });
  });

  // ── Contact frequency picker
  card.querySelectorAll('.pfq-btn').forEach(btn => {
    btn.addEventListener('click', async e => {
      e.stopPropagation();
      const freq = btn.dataset.freq;
      card.querySelectorAll('.pfq-btn').forEach(b => b.classList.toggle('active', b === btn));
      const freqDays = { daily:1, weekly:7, biweekly:14, monthly:30, quarterly:90 }[freq] || 30;
      await post('/api/crm/contact/update', { name: contact.key || contact.name, frequency: freq, frequencyDays: freqDays });
      contact.frequency = freq;
      contact.frequencyDays = freqDays;
      // Update row2 freq badge
      const row2 = card.querySelector('.person-card-row2');
      let fBadge = row2.querySelector('.freq-ok, .freq-overdue');
      if (!fBadge) {
        fBadge = document.createElement('span');
        row2.insertBefore(fBadge, row2.querySelector('.person-expand-chevron'));
      }
      fBadge.className = 'person-extra-tag freq-ok';
      fBadge.textContent = freq;
    });
  });

  // ── Sentiment picker
  card.querySelectorAll('.psnt-btn').forEach(btn => {
    btn.addEventListener('click', async e => {
      e.stopPropagation();
      const sval = btn.dataset.sentiment;
      card.querySelectorAll('.psnt-btn').forEach(b => b.classList.toggle('active', b === btn));
      await post('/api/crm/contact/update', { name: contact.key || contact.name, sentiment: sval });
      contact.sentiment = { value: sval };
    });
  });

  // ── Birthday input
  const birthdayInput = card.querySelector('.person-birthday-input');
  birthdayInput.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
  birthdayInput.addEventListener('change', async () => {
    const val = birthdayInput.value.trim();
    if (!val || /^\d{2}-\d{2}$/.test(val)) {
      await post('/api/crm/contact/update', { name: contact.key || contact.name, birthday: val });
      contact.birthday = val;
    }
  });

  // ── Tags
  const tagRow = card.querySelector('[data-tagrow]');
  const tagInput = card.querySelector('.person-tag-input');
  tagInput.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
  tagInput.addEventListener('keydown', async e => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      const tag = tagInput.value.trim().replace(/,/g,'');
      if (!tag || tags.includes(tag)) { tagInput.value = ''; return; }
      tags.push(tag);
      tagInput.value = '';
      await post('/api/crm/contact/update', { name: contact.key || contact.name, tags });
      // Add pill before input
      const pill = document.createElement('span');
      pill.className = 'person-tag-pill';
      pill.innerHTML = `${escHtml(tag)}<button class="person-tag-remove" data-removetag="${escHtml(tag)}">×</button>`;
      pill.querySelector('.person-tag-remove').addEventListener('click', async ev => {
        ev.stopPropagation();
        const idx = tags.indexOf(tag); if (idx >= 0) tags.splice(idx, 1);
        pill.remove();
        await post('/api/crm/contact/update', { name: contact.key || contact.name, tags });
      });
      tagRow.insertBefore(pill, tagInput);
    }
  });
  // Remove existing tag pills
  card.querySelectorAll('.person-tag-remove').forEach(btn => {
    btn.addEventListener('click', async e => {
      e.stopPropagation();
      const tag = btn.dataset.removetag;
      const idx = tags.indexOf(tag); if (idx >= 0) tags.splice(idx, 1);
      btn.closest('.person-tag-pill').remove();
      await post('/api/crm/contact/update', { name: contact.key || contact.name, tags });
    });
  });

  // ── Note textarea enable save
  const noteTA   = card.querySelector('.person-note-textarea');
  const noteSave = card.querySelector('.person-note-save');
  noteTA.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
  noteTA.addEventListener('input', () => {
    noteSave.disabled = !noteTA.value.trim();
  });
  noteSave.addEventListener('click', async e => {
    e.stopPropagation();
    const text = noteTA.value.trim();
    if (!text) return;
    noteSave.disabled = true;
    await post('/api/crm/note', { name: contact.key || contact.name, note: text });
    noteTA.value = '';
    // Append note to timeline immediately
    const noteList = card.querySelector('.person-notes-list');
    const ts = new Date().toLocaleDateString('en-US', { month:'2-digit', day:'2-digit', year:'2-digit' });
    const entry = document.createElement('div');
    entry.className = 'person-note-entry';
    entry.innerHTML = `<div class="person-note-ts">${ts}</div><div class="person-note-text">${escHtml(text)}</div>`;
    const placeholder = noteList.querySelector('div[style]');
    if (placeholder) placeholder.remove();
    noteList.appendChild(entry);
    noteList.scrollTop = noteList.scrollHeight;
    // Update preview line
    const preview = card.querySelector('.person-note-preview');
    if (preview) preview.textContent = text;
  });

  return card;
}

// Filter chip click
peopleFilterChips.forEach(chip => {
  chip.addEventListener('click', async () => {
    crmActiveType = chip.dataset.type;
    peopleFilterChips.forEach(c => c.classList.toggle('active', c === chip));
    await loadPeoplePanel();
  });
  chip.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
});

// Search debounce
let searchDebounce = null;
peopleSearch.addEventListener('input', () => {
  clearTimeout(searchDebounce);
  searchDebounce = setTimeout(loadPeoplePanel, 300);
});
peopleSearch.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// Import button
// Review All — triggers full AI relationship review from the panel
peopleReviewBtn.addEventListener('click', () => {
  closePanel('people');
  sendMessage(`Do a full review of my relationships. Check who I need to follow up with, anyone I've been out of touch with, any upcoming birthdays, and whether my social goals are on track. Propose specific actions for each.`);
});
peopleReviewBtn.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

peopleImportBtn.addEventListener('click', async () => {
  peopleImportBtn.classList.add('loading');
  peopleImportBtn.textContent = 'Importing…';
  try {
    const result = await post('/api/crm/import', {});
    const msg = `Imported ${result.added} contacts (${result.skipped} already tracked)`;
    showFeedback(msg);
    crmLoaded = false;
    await loadPeoplePanel();
  } catch (e) {
    showFeedback('Import failed');
  } finally {
    peopleImportBtn.classList.remove('loading');
    peopleImportBtn.textContent = 'Import';
  }
});
peopleImportBtn.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// Absorb all touches inside the panel so the gesture engine never sees them.
// Without this, Android WebView doesn't synthesize click events for div elements
// when touch-action:none is set on the body.
peoplePanel.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// Close button
peopleClose.addEventListener('click', () => closePanel('people'));

// ── Settings ─────────────────────────────────────────────────────
const CLOUD_PROVIDERS = ['anthropic', 'openai', 'grok'];

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

// ── Bottom pill bar ──────────────────────────────────────────────
// Updates Send/Tasks label and tracks keyboard height via visualViewport.
function updateBottomBar() {
  const canSend = inputMode === 'keyboard' && chatInput.value.trim().length > 0;
  sendBtn.textContent = canSend ? '▶ Send' : '☰ Tasks';
}

// Keyboard-height tracking so pills tuck above the soft keyboard
if (window.visualViewport) {
  window.visualViewport.addEventListener('resize', () => {
    const kb = Math.max(0, window.innerHeight - window.visualViewport.height);
    document.documentElement.style.setProperty('--keyboard-height', kb + 'px');
  });
}

// Speak pill — opens voice input from any state
micBtnSmall.addEventListener('click', e => {
  e.stopPropagation();
  if (activePanel !== 'input') showPanel('input');
  showInputVoiceMode();
  setTimeout(() => { if (inputMode === 'voice' && !speechRec) startVoice(); }, 80);
});
micBtnSmall.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// Tasks/Send pill — context-aware
sendBtn.addEventListener('click', e => {
  e.stopPropagation();
  if (inputMode === 'keyboard' && chatInput.value.trim().length > 0) {
    submitInput();
  } else {
    if (activePanel === 'input') closePanel('input');
    showTaskOverlay(false);
  }
});
sendBtn.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

chatInput.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submitInput(); }
});
chatInput.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
chatInput.addEventListener('input', () => {
  refreshInputDisplay();
  updateBottomBar();
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
  const panels = [inputPanel, responseScroll, appDrawer, historyPanel, suggestionsEl, peoplePanel, taskOverlay];
  return panels.some(p => p && p.contains(el));
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
  if (activePanel && activePanel !== 'response' && activePanel !== 'people') {
    closeAllPanels();
  } else if (activePanel === 'people') {
    // Taps inside the people panel are absorbed by the panel's own touchstart listener;
    // this branch only fires if somehow a tap outside the panel reaches onTap while it's open.
    closePanel('people');
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
  if (activePanel === 'people')  { return; }   // don't interfere — right-swipe closes people
  closeAllPanels();
  openPeoplePanel();   // panel slides in from right, matching left-swipe direction
}

function onSwipeRight() {
  if (activePanel === 'people')  { closePanel('people'); return; }  // push it back right
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
  voiceStatus.textContent = 'tap to speak';
  updateBottomBar();
}

function showInputKeyboardMode(focusImmediate = false) {
  if (inputMode === 'keyboard') { chatInput.focus(); return; }
  inputMode = 'keyboard';
  stopVoice();
  inputDisplay.classList.remove('voice-mode');
  updateBottomBar();
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
const IDLE_SHOW_MS  = 60_000;    // show tasks after 60s idle
const OVERLAY_AUTO_HIDE_MS = 8_000;  // auto-hide after 8s
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
  // Only show active tasks — completed tasks are immediately deleted server-side
  const active = taskCache.filter(t => t.status !== 'done');

  taskOverlayCount.textContent = active.length ? `${active.length} active` : 'all done';

  if (active.length === 0) {
    const el = document.createElement('div');
    el.className = 'tov-empty';
    el.textContent = 'No tasks yet. Ask the agent to add some.';
    taskOverlayList.appendChild(el);
    return;
  }

  active.forEach((task) => {
    const el       = document.createElement('div');
    const priority = task.priority || 'medium';
    const dotClass = task.status === 'in_progress' ? 'in_progress' : priority;

    el.className = 'tov-item';

    const notes = task.notes ? escHtml(task.notes.slice(0, 60)) : '';
    el.innerHTML = `<span class="tov-dot ${dotClass}"></span>
       <div class="tov-body">
         <div class="tov-title">${escHtml(task.title)}</div>
         ${notes ? `<div class="tov-meta">${notes}</div>` : ''}
       </div>
       <button class="tov-advance" data-id="${task.id}" data-title="${escHtml(task.title)}">▶ advance</button>`;

    // Tap item → advance via agent
    el.addEventListener('click', (e) => {
      if (e.target.classList.contains('tov-advance')) return;
      advanceTask(task);
    });

    // Advance button — stop propagation on both click and touchstart to prevent swipe trigger
    const advBtn = el.querySelector('.tov-advance');
    advBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      advanceTask(task);
    });
    advBtn.addEventListener('touchstart', (e) => {
      e.stopPropagation();
    }, { passive: true });

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
const TASK_PERIODIC_MS = 300_000;  // every 5 minutes
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
taskOverlay.addEventListener('touchend', e => {
  e.stopPropagation();
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

// ── Onboarding overlay ───────────────────────────────────────────
const onboardOverlay   = document.getElementById('onboard-overlay');
const onboardSkip      = document.getElementById('onboard-skip');
const onboardMessages  = document.getElementById('onboard-messages');
const onboardTextInput = document.getElementById('onboard-text-input');
const onboardSendBtn   = document.getElementById('onboard-send-btn');
const onboardMicBtn    = document.getElementById('onboard-mic-btn');
const onboardSteps     = document.querySelectorAll('.ob-step');

let onboardActive    = false;
let onboardSpeechRec = null;

async function checkOnboarding() {
  try {
    const profile = await fetch('/api/profile').then(r => r.json());
    if (!profile.onboarding_complete) showOnboarding();
  } catch { /* endpoint not ready — skip silently */ }
}

function showOnboarding() {
  onboardActive = true;
  onboardMessages.innerHTML = '';
  onboardOverlay.style.display = '';
  onboardOverlay.classList.add('visible');
  // Kick off the opening question from the server
  setTimeout(() => _sendOnboard(''), 300);
}

function hideOnboarding() {
  onboardActive = false;
  stopOnboardVoice();
  onboardOverlay.classList.remove('visible');
  setTimeout(() => { onboardOverlay.style.display = 'none'; }, 400);
}

function _appendOnboardMsg(role, text) {
  const el = document.createElement('div');
  el.className = `ob-msg ${role}`;
  el.textContent = text;
  onboardMessages.appendChild(el);
  onboardMessages.scrollTop = onboardMessages.scrollHeight;
}

function _setOnboardStep(stepName) {
  const ORDER = ['name', 'projects', 'social', 'schedule'];
  const idx = ORDER.indexOf(stepName);
  onboardSteps.forEach(s => {
    const si = ORDER.indexOf(s.dataset.step);
    s.classList.remove('active', 'done');
    if (si < idx)      s.classList.add('done');
    else if (si === idx) s.classList.add('active');
  });
}

async function _sendOnboard(text) {
  if (text.trim()) _appendOnboardMsg('user', text.trim());

  const thinkEl = document.createElement('div');
  thinkEl.className = 'ob-msg thinking';
  thinkEl.textContent = '…';
  onboardMessages.appendChild(thinkEl);
  onboardMessages.scrollTop = onboardMessages.scrollHeight;

  try {
    const res = await fetch('/api/onboard', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text || '' }),
    }).then(r => r.json());

    thinkEl.remove();
    if (res.reply) _appendOnboardMsg('agent', res.reply);
    if (res.step)  _setOnboardStep(res.step);
    if (res.done) {
      setTimeout(() => {
        hideOnboarding();
        showFeedback('Agent setup complete — your profile is saved.');
      }, 1200);
    }
  } catch {
    thinkEl.remove();
    _appendOnboardMsg('agent', 'Trouble connecting. You can skip and continue.');
  }
}

function _submitOnboardText() {
  const text = onboardTextInput.value.trim();
  if (!text) return;
  onboardTextInput.value = '';
  _sendOnboard(text);
}

// Skip
onboardSkip.addEventListener('click', async () => {
  await post('/api/profile', { onboarding_complete: true });
  hideOnboarding();
  showFeedback('Setup skipped — you can update your profile anytime.');
});
onboardSkip.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// Send button / Enter key
onboardSendBtn.addEventListener('click', _submitOnboardText);
onboardSendBtn.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
onboardTextInput.addEventListener('keydown', e => {
  if (e.key === 'Enter') { e.preventDefault(); _submitOnboardText(); }
});
onboardTextInput.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// Voice mic inside onboarding
onboardMicBtn.addEventListener('click', () => {
  if (onboardSpeechRec) { stopOnboardVoice(); return; }
  startOnboardVoice();
});
onboardMicBtn.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// Absorb all touches so gesture engine ignores the overlay
onboardOverlay.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

function startOnboardVoice() {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) return;
  onboardSpeechRec = new SR();
  onboardSpeechRec.continuous = false;
  onboardSpeechRec.interimResults = true;
  onboardSpeechRec.lang = 'en-US';
  onboardMicBtn.textContent = '🔴';

  onboardSpeechRec.onresult = (e) => {
    let fin = '', inter = '';
    for (const r of e.results) {
      if (r.isFinal) fin += r[0].transcript;
      else inter += r[0].transcript;
    }
    onboardTextInput.value = fin || inter;
  };
  onboardSpeechRec.onend = () => {
    onboardMicBtn.textContent = '🎤';
    onboardSpeechRec = null;
    const t = onboardTextInput.value.trim();
    if (t) { onboardTextInput.value = ''; _sendOnboard(t); }
  };
  onboardSpeechRec.onerror = () => {
    onboardMicBtn.textContent = '🎤';
    onboardSpeechRec = null;
  };
  try { onboardSpeechRec.start(); }
  catch { onboardMicBtn.textContent = '🎤'; onboardSpeechRec = null; }
}

function stopOnboardVoice() {
  if (onboardSpeechRec) {
    try { onboardSpeechRec.abort(); } catch { /* ignore */ }
    onboardSpeechRec = null;
  }
  onboardMicBtn.textContent = '🎤';
}

// ── Mode toggle ──────────────────────────────────────────────────
modeBtns.forEach(btn => {
  btn.addEventListener('click', async () => {
    currentMode = btn.dataset.mode;
    modeBtns.forEach(b => b.classList.toggle('active', b === btn));
    if (currentMode === 'agent') {
      agentIndicator.classList.add('visible');
      startHeartbeat();
      // Tell server to start probe heartbeat (also kicks off background discovery)
      await fetch('/api/heartbeat/control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'start' }),
      }).catch(() => {});
      // Check if onboarding is needed
      checkOnboarding();
      // Show discovery status and poll until index is populated
      startDiscoveryStatusPolling();
    } else {
      agentIndicator.classList.remove('visible');
      stopHeartbeat();
      stopDiscoveryStatusPolling();
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

// ── Discovery status polling ──────────────────────────────────────
// After agent mode activates, poll /api/discovery/status and update
// the agent label to show "indexing…" → "agent active · N docs".

function startDiscoveryStatusPolling() {
  stopDiscoveryStatusPolling();
  let attempts = 0;
  const MAX_FAST_POLLS = 15;  // poll fast for 60s then slow down

  async function tick() {
    if (currentMode !== 'agent') { stopDiscoveryStatusPolling(); return; }
    attempts++;
    try {
      const res = await fetch('/api/discovery/status').then(r => r.json()).catch(() => null);
      if (!res) return;
      const total = res.total_docs || 0;
      if (total === 0) {
        if (agentLabel) agentLabel.textContent = 'indexing…';
      } else {
        if (agentLabel) agentLabel.textContent = `agent active · ${total} docs`;
        // Once indexed, drop to 1-minute polling
        if (attempts >= MAX_FAST_POLLS || total > 20) {
          stopDiscoveryStatusPolling();
          discoveryStatusTimer = setInterval(tick, 60_000);
        }
      }
    } catch { /* silent */ }
  }

  tick();  // immediate first check
  discoveryStatusTimer = setInterval(tick, 4000);
}

function stopDiscoveryStatusPolling() {
  if (discoveryStatusTimer) { clearInterval(discoveryStatusTimer); discoveryStatusTimer = null; }
}

// ── Termux bridge panel ──────────────────────────────────────────
const bridgeStatusEl       = document.getElementById('bridge-status');
const bridgeSetupBtn       = document.getElementById('bridge-setup-btn');
const bridgeInstructions   = document.getElementById('bridge-instructions');

async function checkBridgeStatus() {
  try {
    const data = await fetch('/api/bridge/status').then(r => r.json());
    if (bridgeStatusEl) {
      bridgeStatusEl.textContent = data.ready ? 'dir ready' : 'not ready';
      bridgeStatusEl.style.color = data.ready ? 'var(--accent)' : 'rgba(255,255,255,0.4)';
    }
  } catch { if (bridgeStatusEl) bridgeStatusEl.textContent = 'unavailable'; }
}

bridgeSetupBtn?.addEventListener('click', async () => {
  if (bridgeInstructions.style.display !== 'none') {
    bridgeInstructions.style.display = 'none';
    bridgeSetupBtn.textContent = 'Setup';
    return;
  }
  bridgeSetupBtn.textContent = 'Loading…';
  try {
    const text = await fetch('/api/bridge/setup').then(r => r.text());
    // Show the shell script in a copyable pre block
    bridgeInstructions.innerHTML = '';
    const pre = document.createElement('pre');
    pre.className = 'bridge-script';
    pre.textContent = text;
    bridgeInstructions.appendChild(pre);
    const hint = document.createElement('div');
    hint.className = 'bridge-hint';
    hint.textContent = 'Copy to ~/voiceos-bridge.sh in Termux, then: bash ~/voiceos-bridge.sh';
    bridgeInstructions.appendChild(hint);
    bridgeInstructions.style.display = '';
    bridgeSetupBtn.textContent = 'Hide';
  } catch {
    bridgeSetupBtn.textContent = 'Setup';
  }
});
bridgeSetupBtn?.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });
bridgeInstructions?.addEventListener('touchstart', e => e.stopPropagation(), { passive: true });

// ── Boot ────────────────────────────────────────────────────────
async function boot() {
  initCanvas();
  requestAnimationFrame(frame);
  await loadStatus();
  // Pre-load apps and tasks in background
  fetch('/api/apps').then(r => r.json()).then(apps => { allApps = apps; appsLoadedAt = Date.now(); }).catch(() => {});
  await loadTaskCache();
  resetIdleTimer();
  checkBridgeStatus();
}

boot();

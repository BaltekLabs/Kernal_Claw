/**
 * The agent — the VoiceOS ReAct loop (/api/agent) behind a Cortana-style pane.
 *
 * Streams over XHR onprogress, not fetch(): Android WebView buffers fetch
 * streams. The server interleaves control lines with the reply text:
 *   [SKILL:name]  [TOOL:name]  [RESULT:…]  [CONFIRM:tool:{json}]
 * plus box-drawn device snapshots, which are model context and never shown.
 *
 * Agent mode turns on the server heartbeat (autonomous probes); its findings
 * surface on the agent tile and in the conversation.
 */
import { store, emit, send, load, save, esc, toast, refresh } from './store.js';
import { icon } from './icons.js';

const PREFS = 'mosaic-prefs';
export const prefs = Object.assign({ agentMode: false, speak: false }, load(PREFS, {}));
export function savePrefs() { save(PREFS, prefs); }

const convo = load('mosaic-convo', []);      // [{ role: 'u'|'a'|'n', text, chips: [] }]
let view = null;                               // { body, input, mic } while the pane is open
let busy = false;
let onAnswer = () => {};

export function initAgent(opts) {
  onAnswer = opts.onAnswer || onAnswer;
  setAgentMode(prefs.agentMode, true);
}

function setState(s) { store.agent.state = s; emit('agent'); }
function persist() { save('mosaic-convo', convo.slice(-40)); }

// ── Asking ──────────────────────────────────────────────────────
export function ask(text, tileId = null) {
  text = String(text || '').trim();
  if (!text) return;
  if (busy) { toast('Still working on the last one…'); return; }
  busy = true;
  convo.push({ role: 'u', text });
  const reply = { role: 'a', text: '', chips: [] };
  convo.push(reply);
  setState('thinking');
  renderConvo();

  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/api/agent');
  xhr.setRequestHeader('Content-Type', 'application/json');
  let pos = 0, acc = '';
  const take = final => {
    acc += xhr.responseText.substring(pos);
    pos = xhr.responseText.length;
    const lines = acc.split('\n');
    acc = final ? '' : lines.pop();
    for (const line of lines) handleLine(reply, line + '\n');
    updateReply(reply);
  };
  xhr.onprogress = () => take(false);
  xhr.onload = () => { take(true); done(reply, tileId); };
  xhr.onerror = () => { reply.text += '\n[network error]'; done(reply, tileId); };
  xhr.send(JSON.stringify({ text, mode: prefs.agentMode ? 'agent' : 'assistant' }));
}

function handleLine(reply, line) {
  const t = line.trim();
  if (/^[╔╠╚║═]/.test(t) || /^\[(RESULT|DEVICE)/i.test(t)) return;
  let m;
  if ((m = t.match(/^\[SKILL:([^\]]+)\]$/))) reply.chips.push({ text: m[1] });
  else if ((m = t.match(/^\[TOOL:([^\]]+)\]$/))) reply.chips.push({ text: m[1].replace(/_/g, ' ') });
  else if ((m = t.match(/^\[CONFIRM:([^:]+):(.+)\]$/))) reply.chips.push({ text: `${m[1].replace(/_/g, ' ')} ⚠`, warn: true });
  else reply.text += line;
}

function done(reply, tileId) {
  busy = false;
  reply.text = reply.text.replace(/╔═.*?╚═[^\n]*/gs, '').trim() || '(no reply)';
  updateReply(reply);
  persist();
  store.agent.last = reply.text.slice(0, 220);
  if (!view) store.agent.unseen = (store.agent.unseen || 0) + 1;
  setState('idle');
  if (tileId) onAnswer(tileId, reply.text.slice(0, 220));
  if (prefs.speak) speak(reply.text);
  // Tools may have changed tasks, the queue, etc.
  ['tasks', 'queue', 'followups'].forEach(refresh);
}

export function clearConversation() {
  convo.length = 0; persist();
  store.agent.last = ''; emit('agent');
  send('/api/clear');
  renderConvo();
}

// ── Speech ──────────────────────────────────────────────────────
function speak(text) {
  try {
    speechSynthesis.cancel();
    const plain = text.replace(/[*_`#>]/g, '').slice(0, 600);
    speechSynthesis.speak(new SpeechSynthesisUtterance(plain));
  } catch { /* no TTS */ }
}

let rec = null;
export function listen() {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) { toast('Voice input isn’t available here — type instead'); view?.input.focus(); return; }
  if (rec) { rec.stop(); return; }
  rec = new SR();
  rec.lang = navigator.language || 'en-US';
  rec.interimResults = true;
  rec.continuous = false;
  let finalText = '';
  setState('listening');
  view?.mic.classList.add('on');
  rec.onresult = e => {
    let interim = '';
    for (const r of e.results) (r.isFinal ? (finalText = r[0].transcript) : (interim += r[0].transcript));
    if (view) view.input.value = finalText || interim;
  };
  const end = () => {
    rec = null;
    view?.mic.classList.remove('on');
    if (store.agent.state === 'listening') setState('idle');
    if (finalText.trim()) { if (view) view.input.value = ''; ask(finalText); }
  };
  rec.onend = end;
  rec.onerror = e => { if (e.error !== 'no-speech' && e.error !== 'aborted') toast(`Voice: ${e.error}`); };
  try { rec.start(); } catch { end(); }
}
export function stopListening() { try { rec?.stop(); } catch { /* already stopped */ } }

// ── Agent mode (heartbeat) ──────────────────────────────────────
let hbTimer = null;
export function setAgentMode(on, initial = false) {
  prefs.agentMode = on; savePrefs();
  clearInterval(hbTimer); hbTimer = null;
  const sb = document.getElementById('sb-agent');
  sb.hidden = !on; sb.textContent = on ? 'agent' : '';
  if (on) {
    hbTimer = setInterval(heartbeat, 30_000);
    setTimeout(heartbeat, 4000);
  }
  if (!initial || on) send('/api/heartbeat/control', { action: on ? 'start' : 'stop' });
}

function heartbeat() {
  if (busy || document.hidden) return;
  const xhr = new XMLHttpRequest();
  xhr.open('GET', '/api/heartbeat');
  xhr.onload = () => {
    const text = (xhr.responseText || '').replace(/╔═.*?╚═[^\n]*/gs, '').trim();
    if (!text || text === '[idle]') return;
    store.agent.insight = text.slice(0, 220);
    if (!view) store.agent.unseen = (store.agent.unseen || 0) + 1;
    convo.push({ role: 'n', text }); persist();
    emit('agent'); renderConvo();
    toast(text.slice(0, 120));
    refresh('tasks'); refresh('queue');
  };
  xhr.send();
}

// ── Pane ────────────────────────────────────────────────────────
const SUGGESTIONS = [
  'Who am I overdue to get back to?',
  'What’s on my calendar tomorrow?',
  'Add a task to call the dentist',
  'Text Sam that I’m running 10 minutes late',
  'Set an alarm for 6:30',
];

/** Mount the conversation into the panel body + a composer into its bar. */
export function mountAgent(body, bar, initial) {
  bar.className = 'composer';
  bar.innerHTML = `
    <input id="ag-input" type="text" placeholder="ask anything" autocomplete="off">
    <button data-a="send" aria-label="Send"><i class="ab-ico">${icon('send')}</i></button>
    <button data-a="mic" aria-label="Talk"><i class="ab-ico">${icon('mic')}</i></button>`;
  const input = bar.querySelector('#ag-input');
  view = { body, input, mic: bar.querySelector('[data-a="mic"]') };
  store.agent.unseen = 0; emit('agent');

  bar.onclick = e => {
    const a = e.target.closest('button')?.dataset.a;
    if (a === 'send') { ask(input.value); input.value = ''; }
    if (a === 'mic') listen();
  };
  input.onkeydown = e => { if (e.key === 'Enter') { ask(input.value); input.value = ''; input.blur(); } };
  body.onclick = e => {
    const s = e.target.closest('[data-suggest]');
    if (s) ask(s.dataset.suggest);
    if (e.target.closest('[data-a="clear"]')) clearConversation();
  };

  renderConvo();
  if (initial?.text) ask(initial.text, initial.tileId);
  else if (initial?.listen) listen();
  else if (initial?.prefill) { input.value = initial.prefill; input.focus(); }
}

export function unmountAgent() {
  stopListening();
  if (view) view.body.onclick = null;
  view = null;
}

function renderConvo() {
  if (!view) return;
  const head = `
    <div class="agent-head"><div class="ring ${store.agent.state}"></div>
      <div class="hint">${convo.length ? 'what else?' : 'hi — what can I do for you?'}</div></div>`;
  const items = convo.map((m, i) => msgHtml(m, i)).join('');
  const tail = convo.length
    ? `<button class="btn" data-a="clear" style="margin-top:16px">new conversation</button>`
    : `<div class="suggest">${SUGGESTIONS.map(s => `<button data-suggest="${esc(s)}">${esc(s)}</button>`).join('')}</div>`;
  view.body.innerHTML = `${head}<div class="convo">${items}</div>${tail}`;
  view.body.scrollTop = view.body.scrollHeight;
}

function msgHtml(m, i) {
  if (m.role === 'u') return `<div class="msg-u">${esc(m.text)}</div>`;
  if (m.role === 'n') return `<div class="msg-n">${md(m.text)}</div>`;
  const chips = m.chips?.length
    ? `<div class="chips">${m.chips.map(c => `<span class="chip${c.warn ? ' warn' : ''}">${esc(c.text)}</span>`).join('')}</div>` : '';
  return `<div class="msg-a" data-i="${i}">${chips}<div class="md">${m.text ? md(m.text) : '…'}</div></div>`;
}

function updateReply(reply) {
  if (!view) return;
  const i = convo.indexOf(reply);
  const el = view.body.querySelector(`.msg-a[data-i="${i}"]`);
  if (!el) return renderConvo();
  el.outerHTML = msgHtml(reply, i);
  const ring = view.body.querySelector('.agent-head .ring');
  if (ring) ring.className = `ring ${store.agent.state}`;
  view.body.scrollTop = view.body.scrollHeight;
}

/** Small, safe markdown: escape first, then bold/italic/code/lists/headers. */
function md(text) {
  let h = esc(text);
  h = h.replace(/```[\w]*\n?([\s\S]*?)```/g, (_, c) => `<pre>${c.trim()}</pre>`);
  h = h.replace(/`([^`\n]+)`/g, '<code>$1</code>');
  h = h.replace(/^#{1,3} (.+)$/gm, '<div class="h">$1</div>');
  h = h.replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>');
  h = h.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<i>$2</i>');
  h = h.replace(/(?:^[-•*] .+(?:\n|$))+/gm, block =>
    `<ul>${block.trim().split('\n').map(l => `<li>${l.replace(/^[-•*] /, '')}</li>`).join('')}</ul>`);
  return h.replace(/\n/g, '<br>');
}

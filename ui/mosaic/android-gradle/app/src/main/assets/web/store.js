/**
 * Data layer: thin API helpers + a polled store that live tiles subscribe to.
 *
 * All endpoints come from the shared VoiceOS server, except /api/glance which
 * MosaicServer adds (battery, calendar, recent messages/calls in one call).
 */

export async function getJSON(url, fallback = null) {
  try {
    const r = await fetch(url);
    if (!r.ok) return fallback;
    return await r.json();
  } catch { return fallback; }
}

export async function send(url, body = {}, method = 'POST') {
  try {
    const r = await fetch(url, {
      method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    return await r.json().catch(() => ({}));
  } catch (e) { return { error: e.message }; }
}

export function load(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; }
}
export function save(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* storage unavailable */ }
}

export const esc = s => String(s ?? '').replace(/[&<>"']/g,
  c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// ── Polled store ────────────────────────────────────────────────
const SOURCES = {
  glance:    { url: '/api/glance',        every: 60_000,  fallback: { battery: {}, calendar: [], comms: { recent: [] } } },
  tasks:     { url: '/api/tasks',         every: 30_000,  fallback: [] },
  queue:     { url: '/api/queue',         every: 20_000,  fallback: [] },
  notifs:    { url: '/api/notifications', every: 30_000,  fallback: { enabled: false, notifications: [] } },
  followups: { url: '/api/crm/followups', every: 300_000, fallback: { contacts: [] } },
  profiles:  { url: '/api/profiles',      every: 300_000, fallback: { has_work: false } },
  status:    { url: '/api/status',        every: 300_000, fallback: {} },
};

export const store = Object.fromEntries(Object.entries(SOURCES).map(([k, s]) => [k, s.fallback]));
store.agent = { state: 'idle', last: '', insight: '' };   // set by the agent module

const listeners = new Set();
export function subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); }
export function emit(key) { listeners.forEach(fn => { try { fn(key); } catch (e) { console.error(e); } }); }

export async function refresh(key) {
  const s = SOURCES[key];
  if (!s) return;
  const data = await getJSON(s.url, null);
  if (data != null) { store[key] = data; emit(key); }
}

const timers = {};
export function startPolling() {
  for (const [key, s] of Object.entries(SOURCES)) {
    refresh(key);
    clearInterval(timers[key]);
    timers[key] = setInterval(() => { if (!document.hidden) refresh(key); }, s.every);
  }
}
export function refreshAll() { Object.keys(SOURCES).forEach(refresh); }

// ── Apps (personal + work profile) ──────────────────────────────
let appsCache = null, appsAt = 0;
export async function getApps(force = false) {
  if (!force && appsCache && Date.now() - appsAt < 600_000) return appsCache;
  const [personal, work] = await Promise.all([
    getJSON('/api/apps', []),
    store.profiles.has_work ? getJSON('/api/apps?profile=work', []) : Promise.resolve([]),
  ]);
  appsCache = [
    ...personal.map(a => ({ ...a, profile: 'personal' })),
    ...work.map(a => ({ ...a, profile: 'work' })),
  ];
  appsAt = Date.now();
  emit('apps');
  return appsCache;
}
export function appsNow() { return appsCache || []; }

export async function launch(app) {
  const res = await send('/api/launch', { package: app.package, profile: app.profile });
  if (res.error) toast(`Couldn't open ${app.name || app.package}`);
  return res;
}

// ── Toast ───────────────────────────────────────────────────────
let toastTimer;
export function toast(text, ms = 2600) {
  const el = document.getElementById('toast');
  el.textContent = text;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), ms);
}

// ── Time helpers ────────────────────────────────────────────────
export function ago(ms) {
  const m = Math.round((Date.now() - ms) / 60000);
  if (m < 1) return 'now';
  if (m < 60) return `${m}m`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h`;
  return `${Math.round(h / 24)}d`;
}
export function clockTime(ms) {
  return new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}
export function dayLabel(ms) {
  const d = new Date(ms), t = new Date();
  const days = Math.round((new Date(d.toDateString()) - new Date(t.toDateString())) / 86_400_000);
  if (days === 0) return 'today';
  if (days === 1) return 'tomorrow';
  return d.toLocaleDateString([], { weekday: 'long' }).toLowerCase();
}
export function initialsOf(name) {
  const w = String(name || '?').trim().split(/\s+/);
  return ((w[0]?.[0] || '') + (w.length > 1 ? w[w.length - 1][0] : '')).toUpperCase() || '?';
}

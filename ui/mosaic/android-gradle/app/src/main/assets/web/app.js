/**
 * Mosaic — boot and shell.
 *
 * Start (live tiles) and the app list sit side by side in a pager: swipe left
 * for apps, right to come back. Tiles open into panels; the agent tile opens
 * the agent. Back/Home from Android land in mosaicBack()/mosaicHome().
 */
import { store, subscribe, emit, startPolling, refreshAll, getApps, appsNow, launch,
         load, save, esc, toast, initialsOf } from './store.js';
import { CATALOG, defaultLayout, newId, STARTER_APPS } from './catalog.js';
import { initGrid, render, refreshContent, getLayout, setLayout, pin, exitEdit, isEditing } from './grid.js';
import { initPanels, openPanel, closePanel, panelName } from './panels.js';
import { initAgent, prefs, savePrefs } from './agent.js';
import { icon } from './icons.js';
import { applyTheme } from './theme.js';

applyTheme();

const LAYOUT_KEY = 'mosaic-layout';
const $ = id => document.getElementById(id);

// ── Layout persistence ──────────────────────────────────────────
let layout = load(LAYOUT_KEY, null);
const firstRun = !layout;
if (!layout?.tiles) layout = defaultLayout();
layout.tiles = layout.tiles.filter(t => CATALOG[t.type]);
const saveLayout = l => save(LAYOUT_KEY, l);

// ── Accent ──────────────────────────────────────────────────────
function applyAccent(c) {
  prefs.accent = c; savePrefs();
  document.documentElement.style.setProperty('--accent', c);
}
if (prefs.accent) applyAccent(prefs.accent);

// ── Shell context shared with panels ────────────────────────────
const ctx = {
  ask: (text, tileId, context) => openPanel('agent', { text, tileId, context }),
  panelAgent: opts => openPanel('agent', opts),
  launch: app => app && launch(app),
  toast,
  pin: tile => { exitEdit(); pin(tile); goStart(); },
  layout: getLayout,
  setLayout: l => { layout = l; setLayout(l); saveLayout(l); },
  applyAccent,
  appMenu: app => app && appMenu(app),
  onHold,
};

const tileCtx = { ...ctx, panel: (name, opts) => openPanel(name, opts) };

// ── Boot ────────────────────────────────────────────────────────
initGrid({
  gridEl: $('grid'), scrollEl: $('start-scroll'), pageEl: $('start-page'), layout,
  onSave: saveLayout,
  onOpen: (tile, action) => CATALOG[tile.type].open(tile, tileCtx, action),
});
initPanels(ctx);
initAgent({
  onPickModel: () => openPanel('model', { back: 'agent' }),
  onAnswer: (tileId, text) => {
    const t = getLayout().tiles.find(x => x.id === tileId);
    if (t) { t.lastAnswer = text; saveLayout(getLayout()); refreshContent(); }
  },
});
subscribe(key => {
  refreshContent();
  if (key === 'apps' && $('pager').classList.contains('on-apps')) renderApps();
  if (key === 'glance') statusBar();
  if (key === 'profiles') getApps();
});
startPolling();
getApps().then(apps => { if (firstRun) pinStarterApps(apps); });

$('edit-bar').addEventListener('click', e => {
  const a = e.target.closest('[data-act]')?.dataset.act;
  if (a === 'done') exitEdit();
  if (a === 'add') openPanel('add');
});

// Clock + status strip
function statusBar() {
  $('sb-time').textContent = new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const b = store.glance?.battery;
  $('sb-battery').textContent = b?.pct >= 0 ? `${b.pct}%${b.charging ? ' ⚡' : ''}` : '';
}
statusBar();
setInterval(() => { statusBar(); emit('clock'); }, 15_000);

function pinStarterApps(apps) {
  const have = new Set(getLayout().tiles.map(t => t.pkg));
  let added = 0;
  for (const pkg of STARTER_APPS) {
    const a = apps.find(x => x.package === pkg && x.profile === 'personal');
    if (!a || have.has(pkg) || added >= 6) continue;
    getLayout().tiles.push({ id: newId(), type: 'app', size: added < 2 ? 'm' : 's', pkg, profile: 'personal', name: a.name });
    have.add(pkg); added++;
  }
  if (added) { render(); saveLayout(getLayout()); }
}

// ── Pager: Start ⇄ app list ─────────────────────────────────────
const pager = $('pager');
function goApps() {
  exitEdit();
  pager.classList.add('on-apps');
  renderApps();
  getApps();
}
function goStart() {
  pager.classList.remove('on-apps');
  $('app-search').value = '';
  $('app-search').blur();
}
$('to-apps').onclick = goApps;

(function swipe() {
  let sx = 0, sy = 0, tracking = false, horiz = false, base = 0;
  pager.addEventListener('pointerdown', e => {
    if (isEditing() || panelName() || e.target.closest('input')) return;
    sx = e.clientX; sy = e.clientY; tracking = true; horiz = false;
    base = pager.classList.contains('on-apps') ? -innerWidth : 0;
  });
  pager.addEventListener('pointermove', e => {
    if (!tracking) return;
    const dx = e.clientX - sx, dy = e.clientY - sy;
    if (!horiz) {
      if (Math.abs(dy) > 12) { tracking = false; return; }
      if (Math.abs(dx) > 16) { horiz = true; pager.classList.add('dragging'); }
      else return;
    }
    const x = Math.max(-innerWidth, Math.min(0, base + dx));
    pager.style.transform = `translateX(${x}px)`;
  });
  const end = e => {
    if (!tracking) return;
    tracking = false;
    pager.classList.remove('dragging');
    pager.style.transform = '';
    if (!horiz) return;
    const dx = e.clientX - sx;
    if (base === 0 && dx < -60) goApps();
    else if (base !== 0 && dx > 60) goStart();
  };
  pager.addEventListener('pointerup', end);
  pager.addEventListener('pointercancel', e => { tracking = false; pager.classList.remove('dragging'); pager.style.transform = ''; });
})();

// ── App list ────────────────────────────────────────────────────
const groupOf = name => {
  const c = (name || '#')[0].toLowerCase();
  return /[a-z]/.test(c) ? c : '#';
};

function renderApps() {
  const q = $('app-search').value.trim().toLowerCase();
  const apps = appsNow()
    .filter(a => !q || a.name.toLowerCase().includes(q))
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
  const list = $('app-list');
  if (!appsNow().length) { list.innerHTML = `<div class="empty-note">loading apps…</div>`; return; }

  let html = '', last = null;
  apps.forEach((a, i) => {
    const g = groupOf(a.name);
    if (!q && g !== last) { html += `<button class="letter" data-jump="${g}">${g}</button>`; last = g; }
    html += `<button class="app-row" data-i="${i}">
      <span class="ico">${a.icon ? `<img src="data:image/jpeg;base64,${a.icon}" alt="">` : esc(initialsOf(a.name))}</span>
      <span class="nm">${esc(a.name)}</span>${a.profile === 'work' ? '<span class="tag">work</span>' : ''}</button>`;
  });
  // Nothing matches → hand the words to the agent.
  if (q) html += `<button class="app-row ask" data-ask="1">
      <span class="ico">${icon('mic')}</span><span class="nm">ask mosaic “${esc($('app-search').value.trim())}”</span></button>`;
  list.innerHTML = html;
  list._apps = apps;
  list.querySelectorAll('.app-row[data-i]').forEach(row => onHold(row, () => appMenu(apps[+row.dataset.i])));
}

$('app-search').addEventListener('input', renderApps);
$('app-search').addEventListener('keydown', e => {
  if (e.key !== 'Enter') return;
  const apps = $('app-list')._apps || [];
  const q = $('app-search').value.trim();
  if (!q) return;
  if (apps.length === 1) launch(apps[0]); else { goStart(); ctx.ask(q); }
});

$('app-list').addEventListener('click', e => {
  const jump = e.target.closest('[data-jump]');
  if (jump) return showJumpList();
  const row = e.target.closest('.app-row');
  if (!row || row._held) return;
  if (row.dataset.ask) { const q = $('app-search').value.trim(); goStart(); ctx.ask(q); return; }
  launch($('app-list')._apps[+row.dataset.i]);
});

function showJumpList() {
  const present = new Set(appsNow().map(a => groupOf(a.name)));
  const letters = ['#', ...'abcdefghijklmnopqrstuvwxyz'];
  const jl = $('jump-list');
  jl.querySelector('.jump-grid').innerHTML = letters.map(l =>
    `<button data-l="${l}" ${present.has(l) ? '' : 'disabled'}>${l}</button>`).join('');
  jl.classList.add('show');
}
$('jump-list').addEventListener('click', e => {
  const l = e.target.closest('[data-l]')?.dataset.l;
  $('jump-list').classList.remove('show');
  if (!l) return;
  const target = $('app-list').querySelector(`[data-jump="${CSS.escape(l)}"]`);
  if (target) $('apps-scroll').scrollTop = target.offsetTop - 70;
});

// ── Context menu ────────────────────────────────────────────────
function appMenu(app) {
  const pinned = getLayout().tiles.some(t => t.type === 'app' && t.pkg === app.package && (t.profile || 'personal') === app.profile);
  showMenu([
    pinned ? null : ['pin to start', () => {
      ctx.pin({ id: newId(), type: 'app', size: 'm', pkg: app.package, profile: app.profile, name: app.name });
      toast(`${app.name} pinned`);
    }],
    ['open', () => launch(app)],
    ['ask mosaic about it', () => { goStart(); ctx.ask(`What can you help me do with ${app.name}?`); }],
  ].filter(Boolean));
}

function showMenu(items) {
  const ov = $('ctx'), menu = $('ctx-menu');
  menu.innerHTML = items.map(([label], i) => `<button data-i="${i}">${label}</button>`).join('');
  menu.style.top = `${Math.max(40, innerHeight / 2 - items.length * 26)}px`;
  ov.classList.add('show');
  ov.onclick = e => {
    const i = e.target.closest('[data-i]')?.dataset.i;
    ov.classList.remove('show');
    if (i != null) items[+i][1]();
  };
}

/** Press-and-hold on an element (marks it _held so the following click is ignored). */
function onHold(el, fn, ms = 480) {
  let t = null, sx = 0, sy = 0;
  el.addEventListener('pointerdown', e => {
    el._held = false; sx = e.clientX; sy = e.clientY;
    t = setTimeout(() => { el._held = true; if (navigator.vibrate) navigator.vibrate(15); fn(); }, ms);
  });
  el.addEventListener('pointermove', e => { if (Math.hypot(e.clientX - sx, e.clientY - sy) > 10) clearTimeout(t); });
  ['pointerup', 'pointercancel', 'pointerleave'].forEach(ev => el.addEventListener(ev, () => clearTimeout(t)));
  el.addEventListener('contextmenu', e => e.preventDefault());
}

// ── Android hooks ───────────────────────────────────────────────
window.mosaicBack = () => {
  if ($('ctx').classList.contains('show')) return $('ctx').classList.remove('show');
  if ($('jump-list').classList.contains('show')) return $('jump-list').classList.remove('show');
  if (panelName()) return closePanel();
  if (isEditing()) return exitEdit();
  if (pager.classList.contains('on-apps')) return goStart();
  $('start-scroll').scrollTo({ top: 0, behavior: 'smooth' });
};
window.mosaicHome = () => {
  $('ctx').classList.remove('show');
  $('jump-list').classList.remove('show');
  closePanel(true); exitEdit(); goStart();
  $('start-scroll').scrollTo({ top: 0, behavior: 'smooth' });
};
window.mosaicResume = () => { refreshAll(); statusBar(); };
// Answer to "allow texting" (MosaicActivity). If Android refused without asking,
// it has just opened Mosaic's app info page, where the restricted-settings switch is.
window.mosaicPermission = (what, granted) => {
  if (what !== 'sms') return;
  if (granted) { toast('Texting allowed: you can reply from Messaging now'); refreshAll(); }
  else toast('Android blocked it. On this page tap ⋮ → Allow restricted settings, then Permissions → SMS → Allow.', 9000);
};

// Desktop testing convenience: Escape acts as Back.
document.addEventListener('keydown', e => { if (e.key === 'Escape') window.mosaicBack(); });
document.addEventListener('contextmenu', e => e.preventDefault());


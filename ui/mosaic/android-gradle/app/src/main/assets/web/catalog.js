/**
 * Tile catalog — every kind of tile Mosaic can pin.
 *
 * A definition says which sizes the tile supports, what each "face" shows (a
 * live tile cycles through its faces), its count badge, and what a tap does.
 *
 *   sizes:  's' 1×1, 'm' 2×2, 'w' 4×2 (wide), 'l' 4×4 (large)
 *   faces(tile, size) → [html, …]   first face is the resting face
 *   count(tile)       → number shown bottom-right (0 hides it)
 *   open(tile, ctx)   → ctx = { panel(name, opts), ask(text), launch(app), toast(text) }
 *   anim:  'slide' (default) or 'flip'
 */
import { icon } from './icons.js';
import { modelName } from './models.js';
import { store, esc, ago, clockTime, dayLabel, initialsOf, appsNow } from './store.js';

const glyph = name => `<div class="glyph">${icon(name)}</div>`;
const glyphCount = (name, n) =>
  `<div class="glyph-count">${icon(name)}${n ? `<b>${n}</b>` : ''}</div>`;
const centered = html => ({ html, cls: 'center' });

const pendingTasks = () => (store.tasks || []).filter(t => t.status !== 'done');
const prioRank = { high: 0, medium: 1, normal: 1, low: 2 };
const sortedTasks = () => pendingTasks().slice()
  .sort((a, b) => (prioRank[a.priority] ?? 1) - (prioRank[b.priority] ?? 1));
const followups = () => store.followups?.contacts || [];
const comms = () => store.glance?.comms || { recent: [] };
const msgs = () => comms().recent.filter(e => e.channel !== 'Call' && e.dir !== 'out');
const calls = () => comms().recent.filter(e => e.channel === 'Call');
const notifs = () => (store.notifs?.notifications || []);

export const CATALOG = {

  agent: {
    title: 'ask mosaic', glyph: 'mic', sizes: ['w', 'm', 's', 'l'], defaultSize: 'w',
    blurb: 'Your agent. Tap to talk or type; it can act across the phone.',
    faces(tile, size) {
      const a = store.agent;
      const ring = `<div class="ring ${a.state}"></div>`;
      const front = size === 's' ? centered(ring)
        : { html: `<div style="display:flex;gap:14px;align-items:center;flex:1">${ring}
              <div><div class="t-big">${a.state === 'thinking' ? 'thinking…' : 'ask me'}</div>
              <div class="t-dim">${size === 'm' ? '' : 'anything — tap to talk'}</div></div></div>` };
      const out = [front];
      if (size !== 's' && a.insight) out.push(`<div class="t-dim">noticed</div><div class="t-body">${esc(a.insight)}</div>`);
      if (size !== 's' && a.last) out.push(`<div class="t-dim">last answer</div><div class="t-body">${esc(a.last)}</div>`);
      return out;
    },
    count: () => store.agent.unseen || 0,
    label: tile => tile.size === 'w' || tile.size === 'l'
      ? `ask mosaic · ${modelName(store.status?.active_model).toLowerCase()}` : 'ask mosaic',
    open: (t, ctx) => ctx.panel('agent'),
  },

  clock: {
    title: '', glyph: 'clock', sizes: ['m', 'w', 's'], defaultSize: 'm',
    blurb: 'Time and date.',
    faces(tile, size) {
      const now = new Date();
      const t = now.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }).replace(/\s?[AP]M/i, '');
      const d = now.toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' }).toLowerCase();
      if (size === 's') return [centered(`<div class="t-big">${t}</div>`)];
      return [`<div class="t-fill"></div><div class="t-huge">${t}</div><div class="t-dim" style="margin-top:6px">${d}</div>`];
    },
    open: (t, ctx) => ctx.ask('What alarms or reminders do I have coming up?'),
  },

  calendar: {
    title: 'calendar', glyph: 'calendar', sizes: ['m', 'w', 's'], defaultSize: 'm', anim: 'flip',
    blurb: 'Your next events, live.',
    faces(tile, size) {
      const now = new Date();
      const date = `<div class="t-huge">${now.getDate()}</div><div class="t-dim">${now.toLocaleDateString([], { weekday: 'long' }).toLowerCase()}</div>`;
      if (size === 's') return [centered(`<div class="t-big">${now.getDate()}</div>`)];
      const evs = (store.glance?.calendar || []).slice(0, 3).map(e => `
        <div class="t-head">${esc(e.title)}</div>
        <div class="t-body">${e.location ? esc(e.location) + '<br>' : ''}${e.allDay ? 'all day' : clockTime(e.start)}</div>
        <div class="t-fill"></div><div class="t-dim">${dayLabel(e.start)}</div>`);
      if (!evs.length) return [`<div class="t-fill"></div>${date}`];
      return size === 'w' ? evs : [`<div class="t-fill"></div>${date}`, ...evs];
    },
    open: (t, ctx) => ctx.panel('calendar'),
  },

  messages: {
    title: 'messaging', glyph: 'message', sizes: ['m', 's', 'w'], defaultSize: 'm',
    blurb: 'Texts and chats from every app, newest first.',
    faces(tile, size) {
      const n = comms().unread;
      const front = centered(glyphCount('message', n));
      if (size === 's') return [front];
      return [front, ...msgs().slice(0, 3).map(e =>
        `<div class="t-head">${esc(e.person)}</div><div class="t-body">${esc(e.text)}</div>
         <div class="t-fill"></div><div class="t-dim">${esc(e.channel)} · ${ago(e.time)}</div>`)];
    },
    count: () => comms().unread || 0,
    open: (t, ctx) => ctx.panel('comms', { pivot: 'messages' }),
  },

  phone: {
    title: 'phone', glyph: 'phone', sizes: ['s', 'm', 'w'], defaultSize: 's',
    blurb: 'Missed and recent calls.',
    faces(tile, size) {
      const n = comms().missed;
      const front = centered(glyphCount('phone', n));
      if (size === 's') return [front];
      const c = calls()[0];
      return c ? [front, `<div class="t-head">${esc(c.person)}</div><div class="t-body">${c.dir === 'missed' ? 'missed call' : c.dir === 'in' ? 'incoming' : 'outgoing'}</div>
        <div class="t-fill"></div><div class="t-dim">${ago(c.time)} ago</div>`] : [front];
    },
    count: () => comms().missed || 0,
    open: (t, ctx) => ctx.panel('comms', { pivot: 'calls' }),
  },

  people: {
    title: 'people', glyph: 'people', sizes: ['w', 'm', 'l', 's'], defaultSize: 'w',
    blurb: 'Relationships the agent thinks you should follow up on.',
    faces(tile, size) {
      const f = followups();
      if (size === 's' || !f.length) return [centered(glyph('people'))];
      const cap = size === 'm' ? 4 : size === 'l' ? 12 : 6;
      const cols = size === 'm' ? 2 : 3;
      const grid = `<div class="initials" style="grid-template-columns:repeat(${cols},1fr)">${
        f.slice(0, cap).map(c => `<span>${esc(initialsOf(c.name))}</span>`).join('')}</div>`;
      return [grid, ...f.slice(0, 4).map(c =>
        `<div class="t-dim">reach out to</div><div class="t-big" style="font-size:28px">${esc(c.name)}</div>
         <div class="t-fill"></div><div class="t-dim">${c.daysSince >= 0 ? `${c.daysSince} days since you talked` : 'no recent contact'}</div>`)];
    },
    count: () => followups().length,
    open: (t, ctx) => ctx.panel('people'),
  },

  tasks: {
    title: 'tasks', glyph: 'tasks', sizes: ['m', 'w', 's'], defaultSize: 'm',
    blurb: 'Your to-do list — shared with the agent.',
    faces(tile, size) {
      const p = sortedTasks();
      const front = centered(glyphCount('tasks', p.length));
      if (size === 's' || !p.length) return [front];
      const list = p.slice(0, size === 'w' ? 3 : 4)
        .map(t => `<div class="t-body" style="-webkit-line-clamp:1">□ ${esc(t.title)}</div>`).join('');
      return [front, `<div class="t-head">up next</div>${list}`];
    },
    count: () => pendingTasks().length,
    open: (t, ctx) => ctx.panel('tasks'),
  },

  notifications: {
    title: 'notifications', glyph: 'bell', sizes: ['m', 'w', 's'], defaultSize: 'm',
    blurb: 'Everything that pinged you, in one place.',
    faces(tile, size) {
      const n = notifs();
      const front = centered(glyphCount('bell', n.length));
      if (size === 's') return [front];
      return [front, ...n.slice(0, 3).map(x =>
        `<div class="t-dim">${esc(x.app)}</div><div class="t-head">${esc(x.title)}</div><div class="t-body">${esc(x.text)}</div>`)];
    },
    count: () => notifs().length,
    open: (t, ctx) => ctx.panel('notifications'),
  },

  approvals: {
    title: 'approvals', glyph: 'shield', sizes: ['s', 'm', 'w'], defaultSize: 's',
    blurb: 'Actions the agent queued that need your OK.',
    faces(tile, size) {
      const q = store.queue || [];
      const front = centered(glyphCount('shield', q.length));
      if (size === 's') return [front];
      return [front, ...q.slice(0, 3).map(a => `<div class="t-dim">waiting on you</div><div class="t-body">${esc(a.preview)}</div>`)];
    },
    count: () => (store.queue || []).length,
    open: (t, ctx) => ctx.panel('approvals'),
  },

  battery: {
    title: 'battery', glyph: 'battery', sizes: ['s', 'm'], defaultSize: 's',
    blurb: 'Charge level.',
    faces(tile, size) {
      const b = store.glance?.battery || {};
      const pct = b.pct >= 0 ? `${b.pct}%` : '—';
      return [centered(`<div class="t-big">${pct}</div>${b.charging && size !== 's' ? '<div class="t-dim">charging</div>' : ''}`)];
    },
    open(t, ctx) {
      const b = store.glance?.battery || {};
      ctx.toast(b.pct >= 0 ? `Battery ${b.pct}%${b.charging ? ', charging' : ''}` : 'Battery level unavailable');
    },
  },

  work: {
    title: 'work', glyph: 'work', sizes: ['m', 'w', 's'], defaultSize: 'm',
    blurb: 'Your work-profile apps, kept separate.',
    faces(tile, size) {
      const work = appsNow().filter(a => a.profile === 'work');
      if (size === 's' || !work.length) return [centered(glyph('work'))];
      const icons = work.slice(0, size === 'w' ? 8 : 4).map(a => a.icon
        ? `<img src="data:image/jpeg;base64,${a.icon}" style="width:100%;aspect-ratio:1;border-radius:18%">`
        : `<span>${esc(initialsOf(a.name))}</span>`).join('');
      return [centered(glyph('work')),
        `<div class="initials" style="grid-template-columns:repeat(4,1fr);background:none">${icons}</div>`];
    },
    count: () => 0,
    open: (t, ctx) => ctx.panel('work'),
  },

  settings: {
    title: 'settings', glyph: 'gear', sizes: ['s', 'm'], defaultSize: 's',
    blurb: 'Theme, tiles, AI provider and agent mode.',
    faces: () => [centered(glyph('gear'))],
    open: (t, ctx) => ctx.panel('settings'),
  },

  // An agent shortcut: a tile that runs a saved prompt. Pinnable many times.
  prompt: {
    title: 'shortcut', glyph: 'sun', sizes: ['w', 'm', 's'], defaultSize: 'w', multi: true,
    blurb: 'A one-tap request to the agent, e.g. “brief me on my day”.',
    faces(tile, size) {
      const g = tile.glyph || 'sun';
      if (size === 's') return [centered(glyph(g))];
      const front = { html: `<div class="t-fill">${glyph(g)}</div><div class="t-head" style="margin-bottom:4px">${esc(tile.name || 'shortcut')}</div>` };
      return tile.lastAnswer ? [front, `<div class="t-body">${esc(tile.lastAnswer)}</div>`] : [front];
    },
    label: tile => '',
    open: (t, ctx) => ctx.ask(t.prompt || 'Brief me on my day.', t.id),
  },

  // A pinned app (personal or work profile).
  app: {
    title: '', glyph: 'apps', sizes: ['s', 'm', 'w'], defaultSize: 'm', hidden: true,
    faces(tile) {
      const a = appsNow().find(x => x.package === tile.pkg && x.profile === (tile.profile || 'personal'));
      const ic = a?.icon || tile.icon;
      return [centered(ic ? `<img class="app-icon" src="data:image/jpeg;base64,${ic}" alt="">`
                          : `<div class="app-mono">${esc(initialsOf(tile.name))}</div>`)];
    },
    label: tile => (tile.name || '').toLowerCase() + (tile.profile === 'work' ? ' ⌂' : ''),
    open: (t, ctx) => ctx.launch({ package: t.pkg, profile: t.profile || 'personal', name: t.name }),
  },
};

export function labelFor(tile) {
  const def = CATALOG[tile.type];
  if (!def) return '';
  return def.label ? def.label(tile) : def.title;
}

// ── Default Start layout ────────────────────────────────────────
export const PALETTE = [
  '#1ba1e2', '#0050ef', '#6a00ff', '#aa00ff', '#d80073', '#a20025', '#e51400', '#fa6800',
  '#f0a30a', '#a4c400', '#60a917', '#008a00', '#00aba9', '#647687', '#76608a', '#825a2c',
];

let n = 0;
export const newId = () => `t${Date.now().toString(36)}${(n++).toString(36)}`;

export function defaultLayout() {
  const t = (type, size, extra = {}) => ({ id: newId(), type, size, ...extra });
  return {
    cols: 4,
    tiles: [
      t('agent', 'w'),
      t('clock', 'm'), t('calendar', 'm'),
      t('messages', 'm'), t('phone', 's'), t('approvals', 's'), t('battery', 's'), t('settings', 's'),
      t('people', 'w'),
      t('tasks', 'm'), t('notifications', 'm'),
      t('prompt', 'w', { name: 'my day', glyph: 'sun',
        prompt: 'Brief me on my day: what is on my calendar, who is waiting to hear back from me, and my top tasks. Keep it short.' }),
      t('work', 'm'),
    ],
  };
}

// Common apps worth pinning on first run, if installed (matched by package prefix).
export const STARTER_APPS = [
  'com.google.android.dialer', 'com.android.chrome', 'com.google.android.apps.maps',
  'com.google.android.GoogleCamera', 'com.android.camera', 'com.sec.android.app.camera',
  'com.google.android.apps.photos', 'com.spotify.music', 'com.google.android.gm',
  'com.google.android.youtube',
];

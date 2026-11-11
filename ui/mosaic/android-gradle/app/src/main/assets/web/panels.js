/**
 * Panels — full-screen pivot pages that tiles open into.
 *
 * Each panel: a small app title, big lowercase pivot headers (tap or swipe
 * sideways to switch), a scrolling body, and an app bar. Panels re-render when
 * the store data they show changes (unless you're typing in them).
 *
 *   def = { app, pivots[], render(body, pivot, opts), bar[], act(name, opts),
 *           refreshOn[], mount(body, bar, opts), unmount() }
 */
import { store, subscribe, send, getJSON, refresh, esc, ago, clockTime, dayLabel, initialsOf,
         appsNow, getApps, toast } from './store.js';
import { icon } from './icons.js';
import { CATALOG, PALETTE, newId, defaultLayout } from './catalog.js';
import { PROVIDERS, modelName } from './models.js';
import { createPeoplePanels } from './people.js';
import { theme, LOOKS, WALLPAPERS, setLook, setWallpaper, setDim, setClarity, usePhoto, hasPhoto, photoUrl } from './theme.js';
import { transcript, waitingDigest, notifDigest, addSent } from './inbox.js';
import { threads, messagePivots, channelOf, notifItems, NOTIF_PIVOTS } from './inbox.js';
import { mountAgent, unmountAgent, prefs, savePrefs, setAgentMode } from './agent.js';

let el, appEl, pivotsEl, bodyEl, barEl;
let current = null;     // { name, def, pivot, opts }
let ctx = {};           // { ask, launch, pin, layout(), setLayout(l), appMenu(app), applyAccent(c) }

export function initPanels(c) {
  ctx = c;
  el = document.getElementById('panel');
  appEl = document.getElementById('panel-app');
  pivotsEl = document.getElementById('panel-pivots');
  bodyEl = document.getElementById('panel-body');
  barEl = document.getElementById('panel-bar');

  pivotsEl.onclick = e => {
    const b = e.target.closest('[data-pivot]');
    if (b) setPivot(b.dataset.pivot);
  };
  barEl.addEventListener('click', e => {
    const b = e.target.closest('[data-act]');
    if (b && current?.def.act) current.def.act(b.dataset.act, current.opts);
  });
  bodyEl.addEventListener('click', e => current?.def.click?.(e, current.opts));
  swipePivots();

  subscribe(key => {
    if (!current?.def.refreshOn?.includes(key)) return;
    if (bodyEl.contains(document.activeElement) && document.activeElement.matches('input,select')) return;
    draw();
  });
}

export const panelName = () => current?.name || null;

/** A panel's pivots: a fixed list, or a function when they depend on live data. */
const pivotsOf = d => (typeof d.pivots === 'function' ? d.pivots() : d.pivots);

export function openPanel(name, opts = {}) {
  const def = PANELS[name];
  if (!def) return;
  if (current) teardown();
  def.onOpen?.(opts);
  current = { name, def, opts, pivot: opts.pivot || def.startPivot?.() || pivotsOf(def)?.[0] };
  el.classList.remove('closing');
  el.classList.add('show');
  el.setAttribute('aria-hidden', 'false');
  appEl.textContent = def.appTitle?.(opts) || def.app || 'MOSAIC';
  barEl.className = 'appbar panel-bar';
  barEl.innerHTML = '';
  barEl.onclick = null;
  if (def.mount) def.mount(bodyEl, barEl, opts);
  else {
    if (def.bar?.length) {
      barEl.innerHTML = def.bar.map(b =>
        `<button data-act="${b.a}"><i class="ab-ico">${icon(b.icon)}</i><span>${b.label}</span></button>`).join('');
      barEl.classList.add('show');
    }
    draw();
  }
  drawPivots();
  bodyEl.scrollTop = 0;
  def.refreshOn?.forEach(refresh);
}

/** Back: return to the panel this one was opened from (opts.back); `all` closes everything. */
export function closePanel(all = false) {
  if (!current) return;
  const back = !all && current.opts?.back;
  if (back && PANELS[back]) return openPanel(back, current.opts.backPivot ? { pivot: current.opts.backPivot } : {});
  teardown();
  current = null;
  el.classList.add('closing');
  el.setAttribute('aria-hidden', 'true');
  setTimeout(() => { if (!current) el.classList.remove('show', 'closing'); }, 280);
}

function teardown() {
  current.def.unmount?.();
  document.activeElement?.blur?.();
}

function drawPivots() {
  const d = current.def;
  // Windows Phone pivot: the current header always leads, the rest follow in a loop.
  const ps = pivotsOf(d);
  let heads = ps || [d.title];
  const i = heads.indexOf(current.pivot);
  if (i > 0) heads = [...heads.slice(i), ...heads.slice(0, i)];
  pivotsEl.innerHTML = heads.map(p =>
    `<button data-pivot="${p}" class="${!ps || p === current.pivot ? 'on' : ''}">${p}</button>`).join('');
  pivotsEl.scrollLeft = 0;
}

function setPivot(p) {
  if (!current || !pivotsOf(current.def) || p === current.pivot) return;
  current.pivot = p;
  drawPivots(); draw();
  bodyEl.scrollTop = 0;
}

function draw() {
  if (!current || current.def.mount) return;
  // Live pivots can disappear (e.g. no more Signal messages) — fall back to the first.
  const ps = pivotsOf(current.def);
  if (ps && !ps.includes(current.pivot)) current.pivot = ps[0];
  drawPivots();
  bodyEl.innerHTML = current.def.render(current.pivot, current.opts) || '';
  current.def.after?.(bodyEl, current.opts);
}

function swipePivots() {
  let sx = 0, sy = 0, active = false;
  bodyEl.addEventListener('pointerdown', e => { sx = e.clientX; sy = e.clientY; active = true; });
  bodyEl.addEventListener('pointerup', e => {
    const ps = current && pivotsOf(current.def);
    if (!active || !ps) return;
    active = false;
    const dx = e.clientX - sx, dy = e.clientY - sy;
    if (Math.abs(dx) < 70 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
    const i = ps.indexOf(current.pivot);
    setPivot(ps[(i + (dx < 0 ? 1 : ps.length - 1)) % ps.length]);
  });
  bodyEl.addEventListener('pointercancel', () => { active = false; });
}

// ── Shared bits ─────────────────────────────────────────────────
const sq = (inner, bg) => `<div class="sq"${bg ? ` style="background:${bg}"` : ''}>${inner}</div>`;
const actBtn = (a, ic, data = '') => `<button data-a="${a}" ${data} aria-label="${a}">${icon(ic)}</button>`;
const empty = text => `<div class="note">${text}</div>`;
const dataAttrs = obj => Object.entries(obj).map(([k, v]) => `data-${k}="${esc(v)}"`).join(' ');

const callBack = n => n
  ? `<div class="acts">${actBtn('call', 'phone', dataAttrs({ n }))}${actBtn('sms', 'sms', dataAttrs({ n }))}</div>` : '';
const appIcon = (pkg, name) => {
  const a = appsNow().find(x => x.package === pkg);
  return a?.icon ? `<img src="data:image/jpeg;base64,${a.icon}" alt="">` : esc(initialsOf(name));
};
/** The installed app a chat channel came from ("signal" → Signal). */
const chatApp = channel => appsNow().find(a => a.name.toLowerCase() === channel)
  || appsNow().find(a => a.name.toLowerCase().includes(channel));

// Messaging: one row per conversation; tap to see the latest messages.
const openThreads = new Set();
function threadRow(t, pivot) {
  const evs = pivot === 'all' || pivot === 'unread' ? t.events : t.events.filter(e => channelOf(e) === pivot);
  const last = evs[0];
  const lastOut = evs.find(e => e.dir === 'out')?.time || 0;
  const unread = evs.filter(e => e.unread && e.time > lastOut).length;
  const app = t.number ? null : chatApp(channelOf(last));
  const acts = t.number ? callBack(t.number)
    : app ? `<div class="acts">${actBtn('open-app', 'apps', dataAttrs({ pkg: app.package }))}</div>` : '';
  const open = openThreads.has(t.key);
  const lines = open ? `<div class="thread">
      ${evs.slice(0, 10).reverse().map(e => `
        <div class="bubble${e.dir === 'out' ? ' me' : ''}">${e.group && e.dir !== 'out' ? `<b>${esc(e.person)}</b>` : ''}${esc(e.text)}
          <span>${e.dir === 'out' && e.local ? 'sent from Mosaic · ' : ''}${ago(e.time)}${e.channel && channelOf(e) !== 'texts' ? ` · ${esc(e.channel)}` : ''}</span></div>`).join('')}
      ${composerHtml(t)}
      <button class="btn" data-a="thread-ask" ${dataAttrs({ thread: t.key })}>catch me up</button></div>` : '';
  return `<div class="li${unread ? ' unread' : ''}" ${dataAttrs({ thread: t.key })}>
    ${sq(esc(initialsOf(t.person)))}
    <div class="tx"><div class="t1">${esc(t.person)}</div>
      <div class="t2">${esc(last.channel === 'SMS' || last.channel === 'MMS' ? 'text' : last.channel)} · ${ago(last.time)}${unread ? ` · ${unread} new` : ''}</div>
      <div class="t3">${last.dir === 'out' ? 'you: ' : ''}${esc(last.text)}</div></div>${acts}</div>${lines}`;
}

/**
 * How a reply to this conversation can be sent from Mosaic:
 *   sms    texts, straight through Android
 *   reply  chat apps, through their notification's own Reply action (works while
 *          that notification is still showing)
 *   null   neither: open the app instead
 */
function replyRoute(t) {
  const theirs = t.events.filter(e => e.dir !== 'out');
  const latest = theirs[0] || t.events[0];
  const chat = theirs.find(e => e.canReply);
  const viaSms = t.number && (channelOf(latest) === 'texts' || !chat);
  if (viaSms) return { via: 'sms', number: t.number, channel: 'SMS', label: 'text' };
  if (chat) return { via: 'reply', app: chat.channel, person: chat.person, channel: chat.channel, label: `reply on ${chat.channel}` };
  return null;
}

const drafts = new Map();   // thread key → unsent text (survives list refreshes)

function composerHtml(t) {
  const r = replyRoute(t);
  if (!r) {
    const ch = t.events.find(e => e.dir !== 'out')?.channel || t.events[0]?.channel || 'the app';
    const app = chatApp(channelOf(t.events[0] || {}));
    return `<div class="note small">Replying here works while ${esc(ch)}’s notification for ${esc(t.person)} is showing.
      ${app ? `<button class="btn" data-a="open-app" ${dataAttrs({ pkg: app.package })}>open ${esc(ch)}</button>` : ''}</div>`;
  }
  if (r.via === 'sms' && store.glance?.canSms === false)
    return `<div class="note small">Allow Mosaic to send texts (Settings → Apps → Mosaic → Permissions → SMS) to reply here.
      <button class="btn" data-a="sms" ${dataAttrs({ n: t.number })}>open messages</button></div>`;
  return `<div class="reply-box">
    <input type="text" data-draft="${esc(t.key)}" value="${esc(drafts.get(t.key) || '')}"
      placeholder="${esc(r.label)} ${esc(t.events.some(e => e.group) ? t.person : t.person.split(' ')[0])}" autocomplete="off" enterkeyhint="send">
    <button data-a="send" ${dataAttrs({ thread: t.key })} aria-label="Send">${icon('send')}</button></div>`;
}

async function sendReply(key, input, button) {
  const t = threads().find(x => x.key === key);
  const text = input.value.trim();
  if (!t || !text) return;
  const r = replyRoute(t);
  if (!r) return toast('Can’t reply to this conversation from here');
  button.disabled = input.disabled = true;
  const res = await send('/api/send', r.via === 'sms'
    ? { via: 'sms', number: r.number, text }
    : { via: 'reply', app: r.app, person: r.person, text });
  button.disabled = input.disabled = false;

  if (res.error) {
    const why = {
      sms_permission: 'Mosaic isn’t allowed to send texts yet',
      no_reply_action: `${r.channel}’s notification is gone, so it can’t be answered from here. Open ${r.channel} to reply`,
    }[res.error] || `Couldn’t send: ${res.error}`;
    return toast(why, 4500);
  }
  addSent({ person: t.person, number: t.number || '', channel: r.channel, text });
  drafts.delete(key);
  draw();
  if (res.id) checkSms(res.id, t.person);
  setTimeout(() => refresh('glance'), 4000);
}

/** Texts report back asynchronously; only speak up if one fails. */
async function checkSms(id, person) {
  for (const wait of [2500, 5000, 10000]) {
    await new Promise(ok => setTimeout(ok, wait));
    const s = (await getJSON(`/api/send/status?id=${encodeURIComponent(id)}`, {}))?.status;
    if (s === 'sent') return;
    if (s && s.startsWith('failed')) return toast(`Text to ${person} didn’t send (${s})`, 5000);
  }
}

// Notifications: app notifications plus call-log entries.
function notifRow(n) {
  return `<div class="li" ${dataAttrs({ key: n.key, pkg: n.pkg })}>
    ${sq(appIcon(n.pkg, n.app))}
    <div class="tx"><div class="t1">${esc(n.title || n.app)}</div>
      <div class="t2">${esc(n.app)} · ${ago(n.time)}</div><div class="t3">${esc(n.text)}</div></div>
    <div class="acts">${actBtn('dismiss', 'close')}</div></div>`;
}
function callRow(c) {
  const what = c.dir === 'missed' ? 'missed call' : c.dir === 'in' ? 'incoming call' : 'outgoing call';
  return `<div class="li${c.dir === 'missed' ? ' missed' : ''}">
    ${sq(icon('phone'))}
    <div class="tx"><div class="t1">${esc(c.person)}</div><div class="t2">${what} · ${ago(c.time)}</div></div>
    ${callBack(c.number)}</div>`;
}

// Model picker state (per panel session).
let keyState = null, keysLoading = false, ollamaModels = null;
const fastPick = {};
const providerFor = label => Object.keys(PROVIDERS).find(k => PROVIDERS[k].label === label) || label;
async function loadKeys() {
  if (keysLoading) return;
  keysLoading = true;
  const ks = await Promise.all(Object.keys(PROVIDERS).map(p => getJSON(`/api/keys?provider=${p}`, {})));
  const next = {};
  Object.keys(PROVIDERS).forEach((p, i) => { next[p] = !!ks[i].has_key; });
  keyState = next;
  keysLoading = false;
  if (current?.name === 'model') draw();
}

async function call(n) { const r = await send('/api/call', { number: n }); if (r.error) toast(r.error); }
async function sms(n)  { const r = await send('/api/sms', { number: n });  if (r.error) toast(r.error); }

// ── Panel definitions ───────────────────────────────────────────
const PANELS = {

  agent: {
    title: 'ask', refreshOn: ['status'],
    mount: (body, bar, opts) => mountAgent(body, bar, opts),
    unmount: unmountAgent,
  },

  tasks: {
    pivots: ['to do', 'done'], refreshOn: ['tasks'],
    bar: [{ a: 'plan', icon: 'mic', label: 'plan with agent' }, { a: 'refresh', icon: 'refresh', label: 'refresh' }],
    render(pivot) {
      const all = store.tasks || [];
      const list = all.filter(t => (pivot === 'done') === (t.status === 'done'));
      const add = pivot === 'to do'
        ? `<label class="field"><input id="task-new" type="text" placeholder="add a task" enterkeyhint="done"></label>` : '';
      return add + (list.length ? list.map(t => `
        <div class="li ${t.status === 'done' ? 'done' : ''} prio-${esc(t.priority || 'normal')}" data-task="${esc(t.id)}">
          <button class="check" data-a="toggle">${icon('check')}</button>
          <div class="tx"><div class="t1">${esc(t.title)}</div>
            ${t.notes ? `<div class="t3">${esc(t.notes)}</div>` : ''}</div>
          ${t.status === 'done' ? `<div class="acts">${actBtn('del', 'trash')}</div>` : ''}
        </div>`).join('') : empty(pivot === 'done' ? 'Nothing finished yet.' : 'All clear. Add one above, or just tell the agent.'));
    },
    after(body) {
      const input = body.querySelector('#task-new');
      if (input) input.onkeydown = async e => {
        if (e.key !== 'Enter' || !input.value.trim()) return;
        await send('/api/tasks', { title: input.value.trim() });
        input.value = ''; input.blur(); refresh('tasks');
      };
    },
    async click(e) {
      const row = e.target.closest('[data-task]');
      const a = e.target.closest('[data-a]')?.dataset.a;
      if (!row || !a) return;
      const t = store.tasks.find(x => x.id === row.dataset.task);
      if (!t) return;
      if (a === 'toggle') {
        // Tick it right away; the row moves to the other pivot once the server agrees.
        const status = t.status === 'done' ? 'pending' : 'done';
        row.classList.toggle('done', status === 'done');
        const [r] = await Promise.all([
          send('/api/tasks', { id: t.id, status }, 'PUT'),
          new Promise(ok => setTimeout(ok, 450)),     // let the tick be seen before the row leaves
        ]);
        if (r.error || r.status !== status) toast(`Couldn’t update the task${r.error ? `: ${r.error}` : ''}`);
      }
      if (a === 'del') {
        const r = await send('/api/tasks', { id: t.id }, 'DELETE');
        if (r.error) toast(`Couldn’t delete the task: ${r.error}`);
      }
      refresh('tasks');
    },
    act(a) {
      if (a === 'refresh') refresh('tasks');
      if (a === 'plan') ctx.ask('Look at my task list and help me plan what to do next — what is most important today?');
    },
  },

  // Conversations only: texts plus chat apps (Signal, WhatsApp, RCS, DMs…).
  // Pivots follow the apps you actually have messages from.
  messages: {
    app: 'MESSAGING', pivots: messagePivots, refreshOn: ['glance', 'apps'],
    bar: [{ a: 'catchup', icon: 'mic', label: 'catch me up' }, { a: 'refresh', icon: 'refresh', label: 'refresh' }],
    render(pivot) {
      let ts = threads();
      if (pivot === 'unread') ts = ts.filter(t => t.unread);
      else if (pivot !== 'all') ts = ts.filter(t => t.channels.includes(pivot));
      if (ts.length) return ts.map(t => threadRow(t, pivot)).join('');
      return empty(pivot === 'unread' ? 'No unread messages.'
        : 'No messages this week. Texts need SMS permission. Chat apps like Signal and WhatsApp show up once notification access is on and their notifications show message text.');
    },
    after(body) {
      body.querySelectorAll('[data-draft]').forEach(input => {
        const key = input.dataset.draft;
        input.oninput = () => drafts.set(key, input.value);
        input.onkeydown = e => {
          if (e.key === 'Enter') sendReply(key, input, input.nextElementSibling);
        };
      });
    },
    click(e) {
      if (e.target.closest('.reply-box input')) return;     // typing, not toggling the thread
      const b = e.target.closest('[data-a]');
      const a = b?.dataset.a;
      if (a === 'send') return sendReply(b.dataset.thread, b.previousElementSibling, b);
      if (a === 'call') return call(b.dataset.n);
      if (a === 'sms') return sms(b.dataset.n);
      if (a === 'open-app') return ctx.launch({ package: b.dataset.pkg, profile: 'personal' });
      if (a === 'thread-ask') {
        const t = threads().find(x => x.key === b.dataset.thread);
        if (t) return ctx.ask(`Catch me up on my conversation with ${t.person}`, null, transcript(t.person, t.events));
        return;
      }
      const row = e.target.closest('[data-thread]');
      if (!row) return;
      const k = row.dataset.thread;
      openThreads.has(k) ? openThreads.delete(k) : openThreads.add(k);
      draw();
    },
    act(a) {
      if (a === 'refresh') refresh('glance');
      if (a === 'catchup') ctx.ask('Who’s waiting to hear back from me?', null, waitingDigest());
    },
  },

  calendar: {
    pivots: ['agenda'], refreshOn: ['glance'],
    bar: [{ a: 'add', icon: 'plus', label: 'new event' }, { a: 'ask', icon: 'mic', label: 'ask' }],
    render() {
      const evs = store.glance?.calendar || [];
      if (!evs.length) return empty('Nothing in the next week. (Needs calendar permission.)');
      let last = '', out = '';
      for (const e of evs) {
        const d = dayLabel(e.start);
        if (d !== last) { out += `<div class="sec">${d}</div>`; last = d; }
        out += `<div class="li">${sq(e.allDay ? '•' : clockTime(e.start).replace(/\s?[AP]M/i, ''))}
          <div class="tx"><div class="t1">${esc(e.title)}</div>
          <div class="t2">${e.allDay ? 'all day' : `${clockTime(e.start)} – ${clockTime(e.end)}`}</div>
          ${e.location ? `<div class="t3">${esc(e.location)}</div>` : ''}</div></div>`;
      }
      return out;
    },
    act(a) {
      if (a === 'add') ctx.panelAgent({ prefill: 'Schedule ' });
      if (a === 'ask') ctx.ask('What does my week look like? Anything I should prepare for?');
    },
  },

  notifications: {
    // Swipe by type. "calls" comes from the call log; the rest are app notifications.
    pivots: NOTIF_PIVOTS, refreshOn: ['notifs', 'glance', 'apps'],
    bar: [{ a: 'sum', icon: 'mic', label: 'summarize' }, { a: 'clear', icon: 'trash', label: 'clear' }],
    render(pivot) {
      const enabled = store.notifs?.enabled;
      const grant = enabled || pivot === 'calls' ? ''
        : `<div class="note">Mosaic needs notification access to show (and let the agent read) your notifications.</div>
           <button class="btn accent" data-a="grant">grant access</button>`;
      const items = notifItems(pivot);
      if (items.length) return grant + items.map(i => i.kind === 'call' ? callRow(i.c) : notifRow(i.n)).join('');
      if (grant) return grant;
      return empty({ calls: 'No calls this week. (Needs call-log permission.)', email: 'No email notifications.',
        messages: 'No message notifications.' }[pivot] || 'You’re all caught up.');
    },
    async click(e) {
      const b = e.target.closest('[data-a]');
      const a = b?.dataset.a;
      if (a === 'grant') return send('/api/notifications/settings');
      if (a === 'call') return call(b.dataset.n);
      if (a === 'sms') return sms(b.dataset.n);
      const row = e.target.closest('[data-key]');
      if (a === 'dismiss') {
        await send('/api/notifications', { key: row.dataset.key }, 'DELETE');
        return refresh('notifs');
      }
      if (row?.dataset.pkg) ctx.launch({ package: row.dataset.pkg, profile: 'personal' });
    },
    async act(a) {
      const pivot = current.pivot;
      if (a === 'sum') ctx.ask({
        messages: 'Which message notifications need a reply?',
        calls:    'Who should I call back?',
        email:    'Anything important in my email notifications?',
      }[pivot] || 'What in my notifications actually needs me?', null, notifDigest(pivot));
      if (a === 'clear') {
        const keys = notifItems(pivot).filter(i => i.kind === 'notif').map(i => i.n.key);
        await Promise.all(keys.map(key => send('/api/notifications', { key }, 'DELETE')));
        refresh('notifs');
      }
    },
  },

  approvals: {
    pivots: ['waiting'], refreshOn: ['queue'],
    render() {
      const q = store.queue || [];
      return q.length ? q.map(a => `
        <div class="li" ${dataAttrs({ id: a.id })}>
          ${sq(icon('shield'))}
          <div class="tx"><div class="t1">${esc(a.preview)}</div><div class="t2">${esc((a.type || '').replace(/_/g, ' '))}</div></div>
          <div class="acts">${actBtn('ok', 'check')}${actBtn('no', 'close')}</div></div>`).join('')
        : empty('Nothing waiting. When the agent drafts a message or action that needs your OK, it lands here.');
    },
    async click(e) {
      const a = e.target.closest('[data-a]')?.dataset.a;
      const id = e.target.closest('[data-id]')?.dataset.id;
      if (!a || !id) return;
      const r = await send(a === 'ok' ? '/api/queue/approve' : '/api/queue/dismiss', { id });
      toast(r.error ? `Failed: ${r.error}` : a === 'ok' ? 'Approved' : 'Dismissed');
      refresh('queue');
    },
  },

  work: {
    app: 'WORK', pivots: ['apps'], refreshOn: ['profiles', 'apps'],
    render() {
      const p = store.profiles || {};
      if (!p.has_work) return empty('No work profile on this phone. When your company sets one up, its apps show up here — kept apart from your personal ones.');
      const apps = appsNow().filter(a => a.profile === 'work');
      const paused = p.work_paused
        ? `<div class="note">Work apps are paused.</div><button class="btn accent" data-a="resume">turn on work apps</button>` : '';
      return paused + (apps.length ? `<div class="mini-grid">${apps.map(a => `
        <button ${dataAttrs({ pkg: a.package })}>${a.icon ? `<img src="data:image/jpeg;base64,${a.icon}" alt="">` : ''}
          <span>${esc(a.name)}</span></button>`).join('')}</div>
        <div class="note">Press and hold an app to pin it to Start.</div>` : empty('Loading work apps…'));
    },
    after(body) {
      body.querySelectorAll('[data-pkg]').forEach(b => ctx.onHold(b, () =>
        ctx.appMenu(appsNow().find(a => a.profile === 'work' && a.package === b.dataset.pkg))));
    },
    async click(e) {
      if (e.target.closest('[data-a="resume"]')) {
        await send('/api/profiles/work/resume'); await refresh('profiles'); getApps(true); return;
      }
      const b = e.target.closest('[data-pkg]');
      if (b && !b._held) ctx.launch(appsNow().find(a => a.profile === 'work' && a.package === b.dataset.pkg));
    },
  },

  add: {
    title: 'add a tile', refreshOn: [],
    render() {
      const pinned = new Set(ctx.layout().tiles.map(t => t.type));
      const rows = Object.entries(CATALOG).filter(([, d]) => !d.hidden).map(([type, d]) => {
        const taken = !d.multi && pinned.has(type);
        return `<button class="li" data-type="${type}" ${taken ? 'disabled style="opacity:.4"' : ''}>
          ${sq(icon(d.glyph))}<div class="tx"><div class="t1">${d.title || type}</div>
          <div class="t3">${taken ? 'already on Start' : d.blurb || ''}</div></div></button>`;
      }).join('');
      return `${rows}<div class="sec">apps</div><div class="note">To pin an app, swipe left to the app list, then press and hold it.</div>`;
    },
    click(e) {
      const b = e.target.closest('[data-type]');
      if (!b || b.disabled) return;
      const type = b.dataset.type;
      if (type === 'prompt') return openPanel('shortcut');
      ctx.pin({ id: newId(), type, size: CATALOG[type].defaultSize });
      closePanel();
    },
  },

  shortcut: {
    title: 'agent shortcut',
    render: () => `
      <div class="note">A tile that sends the agent a request with one tap — and shows its latest answer.</div>
      <label class="field"><span>tile name</span><input id="sc-name" type="text" placeholder="my day"></label>
      <label class="field"><span>what should the agent do?</span><input id="sc-prompt" type="text" placeholder="Brief me on my day"></label>
      <div class="sec">icon</div>
      <div class="chips" id="sc-glyph">${['sun', 'calendar', 'people', 'message', 'tasks', 'map', 'note', 'bell', 'work']
        .map((g, i) => `<button class="btn${i ? '' : ' on'}" data-g="${g}" style="padding:6px 10px">${icon(g)}</button>`).join('')}</div>
      <button class="btn accent" data-a="save" style="margin-top:20px">pin to start</button>`,
    click(e) {
      const g = e.target.closest('[data-g]');
      if (g) { g.parentElement.querySelectorAll('.btn').forEach(b => b.classList.toggle('on', b === g)); return; }
      if (!e.target.closest('[data-a="save"]')) return;
      const name = bodyEl.querySelector('#sc-name').value.trim();
      const prompt = bodyEl.querySelector('#sc-prompt').value.trim();
      if (!prompt) return toast('Tell the agent what to do');
      const glyph = bodyEl.querySelector('#sc-glyph .on')?.dataset.g || 'sun';
      ctx.pin({ id: newId(), type: 'prompt', size: 'w', name: name || prompt.slice(0, 24), prompt, glyph });
      closePanel();
    },
  },

  // Model picker: one pivot per provider. Opened from Ask Mosaic's model chip
  // and from Settings → ai; goes back to wherever it came from.
  model: {
    app: 'ASK MOSAIC · MODEL', refreshOn: ['status'],
    onOpen() { keyState = null; },
    startPivot: () => PROVIDERS[store.status?.active_provider]?.label,
    pivots: Object.values(PROVIDERS).map(p => p.label),
    render(pivot, opts) {
      const p = providerFor(pivot), def = PROVIDERS[p];
      const s = store.status || {};
      const live = s.active_provider === p;
      if (!keyState) loadKeys();
      const curFast = fastPick[p] || (live ? s.model_fast : def.fast[0]?.id);
      const models = p === 'ollama' && ollamaModels
        ? ollamaModels.map(id => ({ id, name: id, note: 'installed on your Ollama server' })) : def.main;

      const rows = models.map(m => {
        const on = live && s.active_model === m.id;
        return `<button class="li${on ? ' done' : ''}" data-model="${esc(m.id)}">
          <span class="check">${icon('check')}</span>
          <span class="tx"><span class="t1" style="text-decoration:none;color:var(--fg)">${esc(m.name)}</span>
          <span class="t3">${esc(m.note || m.id)}</span></span></button>`;
      }).join('');
      const custom = !models.some(m => m.id === s.active_model) && live
        ? `<div class="note">In use: <b>${esc(s.active_model)}</b></div>` : '';
      const fast = def.fast.length ? `
        <div class="sec">for quick questions</div>
        <div class="note" style="padding-top:0">Simple asks go to a cheaper, faster model.</div>
        ${def.fast.map(f => `<button class="btn${f.id === curFast ? ' on' : ''}" data-fast="${esc(f.id)}">${esc(f.name)}</button>`).join('')}` : '';
      const has = keyState?.[p];
      const key = def.needsKey ? `
        <div class="sec">api key</div>
        <div class="note" style="padding-top:0">${keyState == null ? 'Checking…' : has ? 'A key is saved for ' + esc(def.label) + '.' : 'Needed before ' + esc(def.label) + ' can answer.'}</div>
        <label class="field"><input id="m-key" type="password" placeholder="${has ? 'paste to replace' : 'paste your key'}" autocomplete="off"></label>
        <button class="btn accent" data-a="save-key">save key</button>${has ? '<button class="btn" data-a="test-key">test</button>' : ''}` : '';
      const ollama = p === 'ollama' ? `
        <div class="sec">server</div>
        <label class="field"><input id="m-url" type="text" value="${esc(s.ollama_url || '')}" placeholder="http://192.168.1.10:11434"></label>
        <button class="btn" data-a="test-ollama">test connection</button>` : '';
      return `${live ? '<div class="note">In use now.</div>' : ''}
        <div class="sec">model</div>${rows}${custom}
        <label class="field" style="display:flex;gap:8px"><input id="m-custom" type="text" placeholder="other model id" autocomplete="off" spellcheck="false">
          <button class="btn" data-a="custom" style="margin:0">use</button></label>
        ${fast}${key}${ollama}`;
    },
    async click(e, opts) {
      const p = providerFor(current.pivot), def = PROVIDERS[p];
      const s = store.status || {};
      const t = e.target;
      const fastBtn = t.closest('[data-fast]');
      if (fastBtn) {
        fastPick[p] = fastBtn.dataset.fast;
        if (s.active_provider === p) { await send('/api/provider', { provider: p, model_fast: fastPick[p] }); await refresh('status'); }
        return draw();
      }
      const row = t.closest('[data-model]');
      const a = t.closest('[data-a]')?.dataset.a;
      let model = row?.dataset.model;
      if (a === 'custom') model = bodyEl.querySelector('#m-custom').value.trim();
      if (model) {
        await send('/api/provider', { provider: p, model,
          model_fast: fastPick[p] || def.fast[0]?.id || undefined });
        await refresh('status');
        if (def.needsKey && keyState && !keyState[p]) { toast(`Add your ${def.label} key below to start`); return draw(); }
        toast(`Now using ${modelName(model)}`);
        return openPanel(opts.back || 'agent');
      }
      if (a === 'save-key') {
        const key = bodyEl.querySelector('#m-key').value.trim();
        if (!key) return toast('Paste a key first');
        await send('/api/keys', { provider: p, key });
        keyState = { ...keyState, [p]: true };
        await refresh('status');
        const r = await getJSON(`/api/keys/test?provider=${p}`, {});
        toast(r.ok ? 'Key saved and working' : `Key saved, but the test failed: ${r.error || 'unknown error'}`, 4000);
        return draw();
      }
      if (a === 'test-key') {
        const r = await getJSON(`/api/keys/test?provider=${p}`, {});
        return toast(r.ok ? `${def.label} key works` : `Key test failed: ${r.error || 'unknown error'}`, 4000);
      }
      if (a === 'test-ollama') {
        const url = bodyEl.querySelector('#m-url').value.trim();
        if (url) await send('/api/provider', { ollama_url: url });   // keeps the current provider
        const r = await getJSON('/api/ollama/test', {});
        if (r.ok) { ollamaModels = r.models || []; toast(`Connected — ${ollamaModels.length} models installed`); }
        else toast(`Can’t reach Ollama: ${r.error || 'no response'}`, 4000);
        await refresh('status');
        return draw();
      }
    },
  },

  settings: {
    pivots: ['start', 'agent', 'ai'], refreshOn: ['status'],
    render(pivot) {
      const L = ctx.layout();
      const toggle = (a, on, t, s) => `<button class="toggle${on ? ' on' : ''}" data-a="${a}">
        <div><div class="tt">${t}</div><div class="ts">${s}</div></div><div class="switch"></div></button>`;
      if (pivot === 'start') return `
        <div class="sec">look</div>
        <div class="pills">${Object.entries(LOOKS).map(([k, label]) => `<button class="pill${theme.look === k ? ' on' : ''}" data-setlook="${k}">${label}</button>`).join('')}</div>
        <div class="note" style="padding-top:6px">${{
          classic: 'Solid tiles on black.',
          glass: 'Frosted, raised tiles floating over your wallpaper.',
          seethru: 'Black Start; your wallpaper shows through the tiles as you scroll.' }[theme.look]}</div>
        ${theme.look === 'glass' ? `
        <div class="sec">tile transparency</div>
        <input id="glass-clarity" type="range" min="0" max="1" step="0.05" value="${theme.clarity}">
        <div class="range-ends"><span>frosted</span><span>clear</span></div>` : ''}
        ${theme.look === 'classic' ? '' : `
        <div class="sec">wallpaper</div>
        <div class="wp-grid">
          ${Object.entries(WALLPAPERS).map(([k, bg]) => `<button data-wp="${k}" class="${theme.wallpaper === k ? 'on' : ''}" style="background:${bg}"><span>${k}</span></button>`).join('')}
          ${hasPhoto() ? `<button data-wp="photo" class="${theme.wallpaper === 'photo' ? 'on' : ''}" style="background-image:url('${photoUrl()}')"><span>photo</span></button>` : ''}
          <button class="wp-add" data-a="wp-photo">${icon('plus')}<span>${hasPhoto() ? 'new photo' : 'your photo'}</span></button>
        </div>
        <input id="wp-file" type="file" accept="image/*" hidden>
        <div class="sec">dim wallpaper</div>
        <input id="wp-dim" type="range" min="0" max="0.8" step="0.05" value="${theme.dim}">`}
        <div class="sec">accent color</div>
        <div class="swatches">${PALETTE.map(c => `<button data-accent="${c}" class="${c === prefs.accent ? 'on' : ''}" style="background:${c}"></button>`).join('')}</div>
        <div class="sec">tiles</div>
        ${toggle('cols', L.cols === 6, 'show more tiles', 'six small tiles across instead of four')}
        <button class="btn" data-a="reset" style="margin-top:16px">reset start</button>
        <div class="sec">about</div>
        <div class="note">Mosaic 0.1 · Baltek Labs<br>Live tiles on the VoiceOS agent. Press and hold a tile to move, resize, recolor or unpin it.</div>`;
      if (pivot === 'agent') return `
        ${toggle('mode', prefs.agentMode, 'agent mode', 'the agent checks in on its own every so often and tells you what it notices')}
        ${toggle('speak', prefs.speak, 'read replies aloud', 'speak the agent’s answers')}
        <div class="sec">permissions</div>
        <button class="btn" data-a="notif">notification access</button>`;
      const s = store.status || {};
      const prov = PROVIDERS[s.active_provider]?.label || s.active_provider || '—';
      return `
        <div class="sec">answering with</div>
        <div class="li"><div class="tx"><div class="t1">${esc(modelName(s.active_model))}</div>
          <div class="t2">${esc(prov)}${s.model_fast ? ` · quick questions: ${esc(modelName(s.model_fast))}` : ''}</div>
          ${PROVIDERS[s.active_provider]?.needsKey && !s.has_key ? '<div class="t3">No API key saved yet.</div>' : ''}</div></div>
        <button class="btn accent" data-a="pick-model">choose model</button>`;
    },
    after(body) {
      const dim = body.querySelector('#wp-dim');
      if (dim) dim.oninput = () => setDim(+dim.value);
      const clarity = body.querySelector('#glass-clarity');
      if (clarity) clarity.oninput = () => setClarity(+clarity.value);
      const file = body.querySelector('#wp-file');
      if (file) file.onchange = async () => { await usePhoto(file.files?.[0]); draw(); };
    },
    async click(e) {
      const look = e.target.closest('[data-setlook]');
      if (look) { setLook(look.dataset.setlook); return draw(); }
      const wp = e.target.closest('[data-wp]');
      if (wp) { setWallpaper(wp.dataset.wp); return draw(); }
      if (e.target.closest('[data-a="wp-photo"]')) return bodyEl.querySelector('#wp-file')?.click();
      const sw = e.target.closest('[data-accent]');
      if (sw) { ctx.applyAccent(sw.dataset.accent); draw(); return; }
      const a = e.target.closest('[data-a]')?.dataset.a;
      if (a === 'cols') { const L = ctx.layout(); L.cols = L.cols === 6 ? 4 : 6; ctx.setLayout(L); draw(); }
      if (a === 'reset') { ctx.setLayout(defaultLayout()); toast('Start reset'); draw(); }
      if (a === 'mode') { setAgentMode(!prefs.agentMode); draw(); }
      if (a === 'speak') { prefs.speak = !prefs.speak; savePrefs(); draw(); }
      if (a === 'notif') send('/api/notifications/settings');
      if (a === 'pick-model') openPanel('model', { back: 'settings' });
    },
  },
};

// People list + contact card live in people.js.
Object.assign(PANELS, createPeoplePanels({
  ctx: () => ctx,
  open: (name, opts) => openPanel(name, opts),
  redraw: () => draw(),
  pivot: () => current?.pivot,
  body: () => bodyEl,
}));

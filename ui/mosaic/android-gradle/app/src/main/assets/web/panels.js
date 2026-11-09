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

export function openPanel(name, opts = {}) {
  const def = PANELS[name];
  if (!def) return;
  if (current) teardown();
  current = { name, def, opts, pivot: opts.pivot || def.pivots?.[0] };
  el.classList.remove('closing');
  el.classList.add('show');
  el.setAttribute('aria-hidden', 'false');
  appEl.textContent = def.app || 'MOSAIC';
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
  def.refreshOn?.forEach(refresh);
}

export function closePanel() {
  if (!current) return;
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
  const heads = d.pivots || [d.title];
  pivotsEl.innerHTML = heads.map(p =>
    `<button data-pivot="${p}" class="${!d.pivots || p === current.pivot ? 'on' : ''}">${p}</button>`).join('');
  pivotsEl.querySelector('.on')?.scrollIntoView({ inline: 'start', block: 'nearest' });
}

function setPivot(p) {
  if (!current?.def.pivots || p === current.pivot) return;
  current.pivot = p;
  drawPivots(); draw();
  bodyEl.scrollTop = 0;
}

function draw() {
  if (!current || current.def.mount) return;
  bodyEl.innerHTML = current.def.render(current.pivot, current.opts) || '';
  current.def.after?.(bodyEl, current.opts);
}

function swipePivots() {
  let sx = 0, sy = 0, active = false;
  bodyEl.addEventListener('pointerdown', e => { sx = e.clientX; sy = e.clientY; active = true; });
  bodyEl.addEventListener('pointerup', e => {
    if (!active || !current?.def.pivots) return;
    active = false;
    const dx = e.clientX - sx, dy = e.clientY - sy;
    if (Math.abs(dx) < 70 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
    const ps = current.def.pivots, i = ps.indexOf(current.pivot);
    setPivot(ps[(i + (dx < 0 ? 1 : ps.length - 1)) % ps.length]);
  });
  bodyEl.addEventListener('pointercancel', () => { active = false; });
}

// ── Shared bits ─────────────────────────────────────────────────
const sq = (inner, bg) => `<div class="sq"${bg ? ` style="background:${bg}"` : ''}>${inner}</div>`;
const actBtn = (a, ic, data = '') => `<button data-a="${a}" ${data} aria-label="${a}">${icon(ic)}</button>`;
const empty = text => `<div class="note">${text}</div>`;
const dataAttrs = obj => Object.entries(obj).map(([k, v]) => `data-${k}="${esc(v)}"`).join(' ');

function commsRow(e) {
  const what = e.channel === 'Call'
    ? (e.dir === 'missed' ? 'missed call' : e.dir === 'in' ? 'incoming call' : 'outgoing call')
    : esc(e.text);
  const acts = e.number
    ? `<div class="acts">${actBtn('call', 'phone', dataAttrs({ n: e.number }))}${actBtn('sms', 'sms', dataAttrs({ n: e.number }))}</div>` : '';
  return `<div class="li" ${dataAttrs({ person: e.person })}>
    ${sq(esc(initialsOf(e.person)))}
    <div class="tx"><div class="t1">${esc(e.person)}${e.unread ? ' •' : ''}</div>
      <div class="t2">${esc(e.channel)} · ${ago(e.time)}</div><div class="t3">${what}</div></div>${acts}</div>`;
}

async function call(n) { const r = await send('/api/call', { number: n }); if (r.error) toast(r.error); }
async function sms(n)  { const r = await send('/api/sms', { number: n });  if (r.error) toast(r.error); }

// ── Panel definitions ───────────────────────────────────────────
const PANELS = {

  agent: {
    title: 'ask',
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
      if (a === 'toggle') await send('/api/tasks', { id: t.id, status: t.status === 'done' ? 'pending' : 'done' }, 'PUT');
      if (a === 'del') await send('/api/tasks', { id: t.id }, 'DELETE');
      refresh('tasks');
    },
    act(a) {
      if (a === 'refresh') refresh('tasks');
      if (a === 'plan') ctx.ask('Look at my task list and help me plan what to do next — what is most important today?');
    },
  },

  comms: {
    app: 'MESSAGING', pivots: ['all', 'messages', 'calls'], refreshOn: ['glance'],
    bar: [{ a: 'catchup', icon: 'mic', label: 'catch me up' }, { a: 'refresh', icon: 'refresh', label: 'refresh' }],
    render(pivot) {
      let ev = store.glance?.comms?.recent || [];
      if (pivot === 'messages') ev = ev.filter(e => e.channel !== 'Call');
      if (pivot === 'calls') ev = ev.filter(e => e.channel === 'Call');
      return ev.length ? ev.map(commsRow).join('')
        : empty('Nothing in the last few days. (Messages and calls need SMS and call-log permission.)');
    },
    click(e) {
      const b = e.target.closest('[data-a]');
      if (b?.dataset.a === 'call') return call(b.dataset.n);
      if (b?.dataset.a === 'sms') return sms(b.dataset.n);
      const row = e.target.closest('[data-person]');
      if (row) ctx.ask(`Catch me up on my recent conversation with ${row.dataset.person}. Is there anything I need to reply to?`);
    },
    act(a) {
      if (a === 'refresh') refresh('glance');
      if (a === 'catchup') ctx.ask('Catch me up on messages and calls I haven’t responded to. Who is waiting on me?');
    },
  },

  people: {
    pivots: ['follow up', 'reach out'], refreshOn: ['followups'],
    bar: [{ a: 'import', icon: 'people', label: 'import contacts' }, { a: 'refresh', icon: 'refresh', label: 'refresh' }],
    render(pivot) {
      if (pivot === 'reach out') return `
        <div class="note">Let the agent look across your messages, calls and notes and suggest who to get back in touch with.</div>
        <button class="btn accent" data-a="suggest">suggest people</button>
        <button class="btn" data-a="health">relationship check-in</button>`;
      const f = store.followups?.contacts || [];
      return f.length ? f.map(c => `
        <div class="li" ${dataAttrs({ person: c.name })}>
          ${sq(esc(initialsOf(c.name)))}
          <div class="tx"><div class="t1">${esc(c.name)}</div>
            <div class="t2">${c.daysSince >= 0 ? `${c.daysSince} days since you talked` : 'no recent contact'}</div>
            ${c.type ? `<div class="t3">${esc(c.type)}</div>` : ''}</div>
          ${c.phone ? `<div class="acts">${actBtn('call', 'phone', dataAttrs({ n: c.phone }))}${actBtn('sms', 'sms', dataAttrs({ n: c.phone }))}</div>` : ''}
        </div>`).join('')
        : empty('No follow-ups flagged. Ask the agent to “remind me to follow up with …”, or import your contacts.');
    },
    click(e) {
      const b = e.target.closest('[data-a]');
      const a = b?.dataset.a;
      if (a === 'call') return call(b.dataset.n);
      if (a === 'sms') return sms(b.dataset.n);
      if (a === 'suggest') return ctx.ask('Who should I reach out to this week, and why? Suggest a short message for each.');
      if (a === 'health') return ctx.ask('Give me a check-in on my relationships: who have I been neglecting?');
      const row = e.target.closest('[data-person]');
      if (row) ctx.ask(`Tell me about ${row.dataset.person} — our recent history and a good way to reach out.`);
    },
    async act(a) {
      if (a === 'refresh') refresh('followups');
      if (a === 'import') { toast('Importing contacts…'); const r = await send('/api/crm/import'); toast(r.message || 'Contacts imported'); refresh('followups'); }
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
    pivots: ['recent'], refreshOn: ['notifs'],
    bar: [{ a: 'sum', icon: 'mic', label: 'summarize' }, { a: 'clear', icon: 'trash', label: 'clear all' }],
    render() {
      const n = store.notifs || {};
      if (!n.enabled) return `<div class="note">Mosaic needs notification access to show (and let the agent read) your notifications.</div>
        <button class="btn accent" data-a="grant">grant access</button>`;
      const list = n.notifications || [];
      return list.length ? list.map(x => `
        <div class="li" ${dataAttrs({ key: x.key })}>
          ${sq(esc(initialsOf(x.app)))}
          <div class="tx"><div class="t1">${esc(x.title || x.app)}</div>
            <div class="t2">${esc(x.app)} · ${ago(x.time)}</div><div class="t3">${esc(x.text)}</div></div>
          <div class="acts">${actBtn('dismiss', 'close')}</div></div>`).join('')
        : empty('You’re all caught up.');
    },
    async click(e) {
      const a = e.target.closest('[data-a]')?.dataset.a;
      if (a === 'grant') return send('/api/notifications/settings');
      if (a === 'dismiss') {
        await send('/api/notifications', { key: e.target.closest('[data-key]').dataset.key }, 'DELETE');
        refresh('notifs');
      }
    },
    async act(a) {
      if (a === 'sum') ctx.ask('Summarize my recent notifications. What actually needs my attention?');
      if (a === 'clear') {
        await Promise.all((store.notifs?.notifications || []).map(x => send('/api/notifications', { key: x.key }, 'DELETE')));
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

  settings: {
    pivots: ['start', 'agent', 'ai'], refreshOn: ['status'],
    render(pivot) {
      const L = ctx.layout();
      const toggle = (a, on, t, s) => `<button class="toggle${on ? ' on' : ''}" data-a="${a}">
        <div><div class="tt">${t}</div><div class="ts">${s}</div></div><div class="switch"></div></button>`;
      if (pivot === 'start') return `
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
      const prov = s.active_provider || 'ollama';
      const models = { ollama: 'mistral:latest', anthropic: 'claude-sonnet-4-6', openai: 'gpt-4o-mini', grok: 'grok-4.20-0309-non-reasoning' };
      return `
        <div class="note">Now using <b>${esc(prov)}</b> · ${esc(s.active_model || '')}${prov !== 'ollama' ? (s.has_key ? ' · key saved' : ' · <b>no key</b>') : ''}</div>
        <label class="field"><span>provider</span><select id="ai-prov">${Object.keys(models).map(p =>
          `<option value="${p}" ${p === prov ? 'selected' : ''}>${p}</option>`).join('')}</select></label>
        <label class="field"><span>model</span><input id="ai-model" type="text" value="${esc(s.active_model || models[prov])}"></label>
        <label class="field" id="ai-key-row"><span>api key</span><input id="ai-key" type="password" placeholder="${s.has_key ? 'saved — enter to replace' : 'paste key'}"></label>
        <label class="field" id="ai-url-row"><span>ollama url</span><input id="ai-url" type="text" value="${esc(s.ollama_url || '')}"></label>
        <button class="btn accent" data-a="save-ai">save</button>`;
    },
    after(body) {
      const sel = body.querySelector('#ai-prov');
      if (!sel) return;
      const models = { ollama: 'mistral:latest', anthropic: 'claude-sonnet-4-6', openai: 'gpt-4o-mini', grok: 'grok-4.20-0309-non-reasoning' };
      const sync = () => {
        body.querySelector('#ai-key-row').hidden = sel.value === 'ollama';
        body.querySelector('#ai-url-row').hidden = sel.value !== 'ollama';
      };
      sel.onchange = () => { body.querySelector('#ai-model').value = models[sel.value]; sync(); };
      sync();
    },
    async click(e) {
      const sw = e.target.closest('[data-accent]');
      if (sw) { ctx.applyAccent(sw.dataset.accent); draw(); return; }
      const a = e.target.closest('[data-a]')?.dataset.a;
      if (a === 'cols') { const L = ctx.layout(); L.cols = L.cols === 6 ? 4 : 6; ctx.setLayout(L); draw(); }
      if (a === 'reset') { ctx.setLayout(defaultLayout()); toast('Start reset'); draw(); }
      if (a === 'mode') { setAgentMode(!prefs.agentMode); draw(); }
      if (a === 'speak') { prefs.speak = !prefs.speak; savePrefs(); draw(); }
      if (a === 'notif') send('/api/notifications/settings');
      if (a === 'save-ai') {
        const q = s => bodyEl.querySelector(s).value.trim();
        const provider = q('#ai-prov');
        await send('/api/provider', { provider, model: q('#ai-model'), ollama_url: provider === 'ollama' ? q('#ai-url') || undefined : undefined });
        if (provider !== 'ollama' && q('#ai-key')) await send('/api/keys', { provider, key: q('#ai-key') });
        await refresh('status');
        toast('AI settings saved');
      }
    },
  },
};

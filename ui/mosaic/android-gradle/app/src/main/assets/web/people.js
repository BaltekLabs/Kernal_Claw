/**
 * People — the relationship list (VoiceOS CRM) in Windows Phone People-hub form.
 *
 *   people  list: search (names, tags, phone), filter pivots (all / follow up /
 *           out of touch / each relationship type), alphabetical letter groups
 *   person  contact card: profile (actions, agent actions, relationship, keep-in-touch,
 *           how they're doing, birthday, tags) · notes · history (messages and calls)
 *
 * Backend (shared VoiceOS server):
 *   GET  /api/crm/contacts            → { contacts: [...], total }
 *   POST /api/crm/contact/update      { name: key, type|tags|followUp|frequency|frequencyDays|sentiment|birthday|phone|displayName }
 *   POST /api/crm/note                { name: key, note }
 *   POST /api/crm/contact/delete      { name: key }
 *   POST /api/crm/import
 */
import { store, emit, send, refresh, esc, toast, ago, initialsOf } from './store.js';
import { icon } from './icons.js';
import { messages, calls, transcript } from './inbox.js';

const TYPES = ['family', 'friend', 'work', 'mentor', 'acquaintance', 'other'];
const FREQ = { daily: 1, weekly: 7, biweekly: 14, monthly: 30, quarterly: 90 };
const MOODS = ['great', 'ok', 'struggling', 'busy'];

export const contacts = () => store.crm?.contacts || [];
export const followUps = () => contacts().filter(c => c.followUp);
const byKey = key => contacts().find(c => c.key === key);
const outOfTouch = c => c.frequencyOverdue || c.daysSince > 30;

function since(c) {
  if (c.daysSince == null || c.daysSince < 0) return 'no recent contact';
  if (c.daysSince === 0) return 'talked today';
  return `${c.daysSince} day${c.daysSince === 1 ? '' : 's'} ago`;
}

/** Save fields for a contact; the server's copy replaces ours when it answers. */
async function update(c, fields) {
  Object.assign(c, fields.sentiment !== undefined ? { ...fields, sentiment: { value: fields.sentiment } } : fields);
  emit('crm');
  const r = await send('/api/crm/contact/update', { name: c.key, ...fields });
  if (r.error || !r.contact) { toast(`Couldn’t save: ${r.error || 'no response'}`); return refresh('crm'); }
  const list = contacts(), i = list.findIndex(x => x.key === c.key);
  if (i >= 0) list[i] = r.contact; else list.push(r.contact);
  emit('crm');
}

const pill = (text, data = '', on = false) =>
  `<button class="pill${on ? ' on' : ''}" ${data}>${esc(text)}</button>`;

export function createPeoplePanels(api) {
  // api = { ctx(), open(name, opts), redraw(), pivot(), body() }
  let query = '';
  let adding = false;
  let removing = null;    // key awaiting a second tap on "remove"

  // ── List ──────────────────────────────────────────────────────
  function listHtml(pivot) {
    const q = query.trim().toLowerCase();
    let list = contacts().filter(c => {
      if (pivot === 'follow up' && !c.followUp) return false;
      if (pivot === 'out of touch' && !outOfTouch(c)) return false;
      if (TYPES.includes(pivot) && c.type !== pivot) return false;
      if (!q) return true;
      return [c.name, c.phone, c.type, ...(c.tags || [])].some(v => String(v || '').toLowerCase().includes(q));
    });
    if (!list.length) return `<div class="note">${q ? `Nobody matches “${esc(query)}”.`
      : pivot === 'follow up' ? 'Nobody flagged for follow-up. Tap the bell on a contact card, or ask the agent to remind you.'
      : 'No one here yet. Import your contacts, or add someone.'}</div>`;

    const alpha = pivot === 'all' || TYPES.includes(pivot);
    list = alpha
      ? list.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))
      : list.sort((a, b) => (b.daysSince ?? -1) - (a.daysSince ?? -1));
    let last = null, html = '';
    for (const c of list) {
      if (alpha && !q) {
        const g = /[a-z]/i.test(c.name[0]) ? c.name[0].toLowerCase() : '#';
        if (g !== last) { html += `<div class="letter">${g}</div>`; last = g; }
      }
      html += row(c);
    }
    return html;
  }

  function row(c) {
    const tags = (c.tags || []).slice(0, 3).map(t => `<span class="chip" data-tag="${esc(t)}">${esc(t)}</span>`).join('');
    const note = c.notes?.length ? c.notes[c.notes.length - 1].text : '';
    return `<button class="li person${c.followUp ? ' flagged' : ''}" data-person="${esc(c.key)}">
      <span class="sq">${esc(initialsOf(c.name))}${c.followUp ? `<i class="flag">${icon('bell')}</i>` : ''}</span>
      <span class="tx"><span class="t1">${esc(c.name)}</span>
        <span class="t2${c.frequencyOverdue ? ' overdue' : ''}">${since(c)}${c.type ? ` · ${esc(c.type)}` : ''}${c.frequencyOverdue ? ` · ${esc(c.frequency)} overdue` : ''}</span>
        ${tags ? `<span class="chips">${tags}</span>` : ''}
        ${note ? `<span class="t3">${esc(note)}</span>` : ''}</span></button>`;
  }

  const people = {
    app: 'PEOPLE',
    pivots: () => ['all', 'follow up', 'out of touch', ...TYPES.filter(t => contacts().some(c => c.type === t))],
    startPivot: () => (followUps().length ? 'follow up' : 'all'),
    refreshOn: ['crm'],
    bar: [
      { a: 'review', icon: 'mic', label: 'review' },
      { a: 'add', icon: 'plus', label: 'add' },
      { a: 'import', icon: 'people', label: 'import' },
    ],
    render(pivot) {
      const all = contacts();
      const add = adding ? `
        <label class="field" style="display:flex;gap:8px"><input id="pp-new" type="text" placeholder="name" autocomplete="off">
          <button class="btn accent" data-a="add-save" style="margin:0">add</button></label>` : '';
      return `${add}
        <label class="field"><input id="pp-q" type="text" placeholder="search names, tags, numbers" value="${esc(query)}" autocomplete="off" spellcheck="false"></label>
        <div class="note" style="padding:0 0 4px">${all.length} people · ${followUps().length} to follow up · ${all.filter(outOfTouch).length} out of touch</div>
        <div id="pp-list">${listHtml(pivot)}</div>`;
    },
    after(body) {
      const q = body.querySelector('#pp-q');
      q.oninput = () => { query = q.value; body.querySelector('#pp-list').innerHTML = listHtml(api.pivot()); };
      const n = body.querySelector('#pp-new');
      if (n) { n.focus(); n.onkeydown = e => { if (e.key === 'Enter') addPerson(n.value); }; }
    },
    click(e) {
      const tag = e.target.closest('[data-tag]');
      if (tag) { query = tag.dataset.tag; return api.redraw(); }       // tap a tag → filter by it
      if (e.target.closest('[data-a="add-save"]')) return addPerson(api.body().querySelector('#pp-new').value);
      const p = e.target.closest('[data-person]');
      if (p) api.open('person', { key: p.dataset.person, back: 'people', backPivot: api.pivot() });
    },
    async act(a) {
      const ctx = api.ctx();
      if (a === 'review') ctx.ask('Do a full review of my relationships: who I need to follow up with, who I’ve been out of touch with, upcoming birthdays, and whether my social goals are on track. Propose specific actions.');
      if (a === 'add') { adding = !adding; api.redraw(); }
      if (a === 'import') {
        toast('Importing contacts…');
        const r = await send('/api/crm/import');
        toast(r.error ? `Import failed: ${r.error}` : `Imported ${r.added ?? 0} (${r.skipped ?? 0} already here)`);
        refresh('crm');
      }
    },
  };

  async function addPerson(name) {
    name = String(name || '').trim();
    if (!name) return toast('Type a name first');
    const r = await send('/api/crm/contact/update', { name, displayName: name });
    if (r.error || !r.contact) return toast(`Couldn’t add: ${r.error || 'no response'}`);
    contacts().push(r.contact);
    adding = false;
    emit('crm');
    api.open('person', { key: r.contact.key, back: 'people' });
  }

  // ── Contact card ──────────────────────────────────────────────
  function history(c) {
    const name = c.name.toLowerCase();
    const digits = String(c.phone || '').replace(/\D/g, '').slice(-9);
    const mine = e => e.person?.toLowerCase() === name
      || (digits && String(e.number || '').replace(/\D/g, '').endsWith(digits));
    return [...messages(), ...calls()].filter(mine).sort((a, b) => b.time - a.time);
  }

  const person = {
    pivots: ['profile', 'notes', 'history'], refreshOn: ['crm', 'glance'],
    appTitle: opts => (byKey(opts.key)?.name || 'person').toUpperCase(),
    render(pivot, opts) {
      const c = byKey(opts.key);
      if (!c) return '<div class="note">This person is no longer in your list.</div>';
      if (pivot === 'notes') return notesHtml(c);
      if (pivot === 'history') return historyHtml(c);
      return profileHtml(c);
    },
    after(body, opts) {
      const c = byKey(opts.key);
      if (!c) return;
      const bind = (sel, fn) => { const el = body.querySelector(sel); if (el) fn(el); };
      bind('#pc-tag', el => el.onkeydown = e => {
        if (e.key !== 'Enter' && e.key !== ',') return;
        e.preventDefault();
        const t = el.value.trim().replace(/,/g, '');
        if (t && !(c.tags || []).includes(t)) update(c, { tags: [...(c.tags || []), t] });
        el.value = '';
      });
      bind('#pc-bday', el => el.onchange = () => {
        const v = el.value.trim();
        if (v && !/^\d{2}-\d{2}$/.test(v)) return toast('Birthday as MM-DD, e.g. 03-15');
        update(c, { birthday: v });
      });
      bind('#pc-phone', el => el.onchange = () => update(c, { phone: el.value.trim() }));
      bind('#pc-note', el => el.oninput = () => { body.querySelector('[data-a="note-save"]').disabled = !el.value.trim(); });
    },
    async click(e, opts) {
      const c = byKey(opts.key);
      if (!c) return;
      const ctx = api.ctx();
      const t = e.target;
      const b = t.closest('[data-a]');
      const a = b?.dataset.a;
      const first = c.name.split(' ')[0];
      if (t.closest('[data-type]')) { const v = t.closest('[data-type]').dataset.type; return update(c, { type: c.type === v ? '' : v }); }
      if (t.closest('[data-freq]')) {
        const f = t.closest('[data-freq]').dataset.freq;
        return update(c, c.frequency === f ? { frequency: '', frequencyDays: 0 } : { frequency: f, frequencyDays: FREQ[f] });
      }
      if (t.closest('[data-mood]')) { const m = t.closest('[data-mood]').dataset.mood; return update(c, { sentiment: c.sentiment?.value === m ? '' : m }); }
      if (t.closest('[data-untag]')) { const x = t.closest('[data-untag]').dataset.untag; return update(c, { tags: (c.tags || []).filter(y => y !== x) }); }
      if (t.closest('[data-a="catchup"]'))   // send the real history + notes, not just a name
        return ctx.ask(`Catch me up on ${c.name}`, null, transcript(c.name, history(c), { notes: c.notes || [] }));
      if (t.closest('[data-bot]')) return ctx.ask(t.closest('[data-bot]').dataset.bot);
      switch (a) {
        case 'call':   return c.phone ? send('/api/call', { number: c.phone }) : ctx.ask(`Call ${c.name}`);
        case 'text':   return c.phone ? send('/api/sms', { number: c.phone }) : ctx.ask(`Text ${c.name}`);
        case 'follow': update(c, { followUp: !c.followUp }); return toast(c.followUp ? `Following up with ${first}` : 'Follow-up cleared');
        case 'draft':  return ctx.ask(`Draft a warm, personal message to ${c.name}, using their profile and notes. Show it to me before sending.`);
        case 'agent':  return ctx.ask(`Pull up everything you know about ${c.name} and help me reach out.`);
        case 'note-save': {
          const ta = api.body().querySelector('#pc-note');
          const note = ta.value.trim();
          if (!note) return;
          b.disabled = true;
          const r = await send('/api/crm/note', { name: c.key, note });
          if (r.error) { b.disabled = false; return toast(`Couldn’t save the note: ${r.error}`); }
          ta.value = '';
          return refresh('crm');
        }
        case 'remove':
          if (removing !== c.key) { removing = c.key; b.textContent = `tap again to remove ${first}`; return; }
          removing = null;
          await send('/api/crm/contact/delete', { name: c.key });
          toast(`${c.name} removed`);
          await refresh('crm');
          return api.open('people');
      }
    },
  };

  function profileHtml(c) {
    const first = c.name.split(' ')[0];
    const round = (a, ic, label, on = false) =>
      `<button data-a="${a}" class="${on ? 'on' : ''}"><i class="ab-ico">${icon(ic)}</i><span>${label}</span></button>`;
    const bots = [
      `Draft a message to ${c.name} to catch up`,
      `Suggest something to do with ${first} soon`,
      `Remind me to follow up with ${first} next week`,
    ];
    return `
      <div class="pc-head">
        <div class="sq big">${esc(initialsOf(c.name))}</div>
        <div><div class="pc-name">${esc(c.name)}</div>
          <div class="t2${c.frequencyOverdue ? ' overdue' : ''}">${since(c)}${c.frequencyOverdue ? ` · ${esc(c.frequency)} overdue` : ''}</div>
          ${c.sentiment?.value ? `<div class="t3">doing ${esc(c.sentiment.value)}</div>` : ''}</div>
      </div>
      <div class="pc-actions">
        ${round('call', 'phone', 'call')}${round('text', 'sms', 'text')}
        ${round('follow', 'bell', c.followUp ? 'following' : 'follow up', c.followUp)}
        ${round('draft', 'note', 'draft')}${round('agent', 'mic', 'ask')}
      </div>

      <div class="sec">ask mosaic to</div>
      <div class="suggest"><button data-a="catchup">Catch me up on ${esc(first)}</button>
        ${bots.map(t => `<button data-bot="${esc(t)}">${esc(t.replace(c.name, first))}</button>`).join('')}</div>

      <div class="sec">relationship</div>
      <div class="pills">${TYPES.map(t => pill(t, `data-type="${t}"`, c.type === t)).join('')}</div>

      <div class="sec">keep in touch</div>
      <div class="pills">${Object.keys(FREQ).map(f => pill(f, `data-freq="${f}"`, c.frequency === f)).join('')}</div>

      <div class="sec">how they’re doing</div>
      <div class="pills">${MOODS.map(m => pill(m, `data-mood="${m}"`, c.sentiment?.value === m)).join('')}</div>

      <div class="sec">tags</div>
      <div class="pills">${(c.tags || []).map(t => `<span class="pill on tag">${esc(t)}<button data-untag="${esc(t)}" aria-label="remove tag">×</button></span>`).join('')}
        <input id="pc-tag" class="pill-input" type="text" placeholder="+ tag" maxlength="24" autocomplete="off" spellcheck="false"></div>

      <div class="sec">details</div>
      <label class="field"><span>phone</span><input id="pc-phone" type="tel" value="${esc(c.phone || '')}" placeholder="add a number"></label>
      <label class="field"><span>birthday (MM-DD)</span><input id="pc-bday" type="text" maxlength="5" value="${esc(c.birthday || '')}" placeholder="03-15"></label>

      <button class="btn" data-a="remove" style="margin-top:24px">remove ${esc(first)}</button>`;
  }

  function notesHtml(c) {
    const notes = [...(c.notes || [])].reverse();
    return `
      <label class="field"><textarea id="pc-note" rows="3" placeholder="add a note about ${esc(c.name.split(' ')[0])}"></textarea></label>
      <button class="btn accent" data-a="note-save" disabled>save note</button>
      ${notes.length ? notes.map(n => `
        <div class="li"><div class="tx">${n.ts ? `<div class="t2">${esc(n.ts)}</div>` : ''}
          <div class="t3" style="-webkit-line-clamp:unset;color:var(--fg)">${esc(n.text)}</div></div></div>`).join('')
        : '<div class="note">No notes yet. Notes help the agent write messages that sound like you know them.</div>'}`;
  }

  function historyHtml(c) {
    const h = history(c);
    const ask = `<button class="btn accent" data-a="catchup">catch me up</button>`;
    if (!h.length) return `<div class="note">No messages or calls with ${esc(c.name)} this week.</div>${ask}`;
    return ask + h.map(e => {
      const isCall = e.channel === 'Call';
      const what = isCall ? (e.dir === 'missed' ? 'missed call' : e.dir === 'in' ? 'incoming call' : 'outgoing call')
        : `${e.dir === 'out' ? 'you: ' : ''}${esc(e.text)}`;
      return `<div class="li">${`<div class="sq">${icon(isCall ? 'phone' : 'message')}</div>`}
        <div class="tx"><div class="t2">${esc(isCall ? 'call' : e.channel === 'SMS' || e.channel === 'MMS' ? 'text' : e.channel)} · ${ago(e.time)}</div>
        <div class="t3" style="color:var(--fg)">${what}</div></div></div>`;
    }).join('');
  }

  return { people, person };
}

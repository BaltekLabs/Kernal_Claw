/**
 * Sorting for the two inboxes:
 *   Messaging     → conversations: SMS/MMS plus chat apps (Signal, WhatsApp, RCS, DMs…)
 *   Notifications → everything else that pinged you, by type: messages, calls, email, other
 */
import { store } from './store.js';

const comms = () => store.glance?.comms || {};
export const messages = () => comms().messages || [];
export const calls = () => comms().calls || [];

// ── Messaging ───────────────────────────────────────────────────
/** "texts" for carrier SMS/MMS, otherwise the chat app's name ("signal"). */
export function channelOf(e) {
  return /^(sms|mms)$/i.test(e.channel) ? 'texts' : String(e.channel || 'chat').toLowerCase();
}

/** One entry per person, newest conversation first. */
export function threads() {
  const by = new Map();
  for (const e of messages()) {           // already newest first
    const k = String(e.person || '?').toLowerCase();
    let t = by.get(k);
    if (!t) by.set(k, t = { key: k, person: e.person, number: '', events: [], channels: new Set(), unread: 0 });
    t.events.push(e);
    t.channels.add(channelOf(e));
    if (e.unread) t.unread++;
    if (!t.number && e.number) t.number = e.number;
  }
  return [...by.values()].map(t => ({ ...t, last: t.events[0], channels: [...t.channels] }));
}

/** Pivots for the messaging panel: all, unread, then each channel that has messages. */
export function messagePivots() {
  const chans = [...new Set(messages().map(channelOf))]
    .sort((a, b) => (a === 'texts' ? -1 : b === 'texts' ? 1 : a.localeCompare(b)));
  return ['all', 'unread', ...chans];
}

// ── Notifications ───────────────────────────────────────────────
const PKG = {
  messages: /(messaging|\.mms|securesms|signal|whatsapp|telegram|facebook\.orca|discord|viber|wire|threema|skype|teams|slack|snapchat|line\.android|kakao|wechat|tencent\.mm|beeper|session)/i,
  calls:    /(dialer|incallui|\.phone$|telecom)/i,
  email:    /(android\.gm$|\.gm\b|outlook|\.email|mail|protonmail|tutanota|spark|readdle|fastmail|hey\.)/i,
};

/** messages | calls | email | other — Android's category first, package name as fallback. */
export function notifType(n) {
  const cat = n.category || '';
  if (cat === 'msg') return 'messages';
  if (cat === 'call' || cat === 'missed_call') return 'calls';
  if (cat === 'email') return 'email';
  const pkg = n.pkg || '';
  if (PKG.email.test(pkg)) return 'email';
  if (PKG.messages.test(pkg)) return 'messages';
  if (PKG.calls.test(pkg)) return 'calls';
  return 'other';
}

export const NOTIF_PIVOTS = ['all', 'messages', 'calls', 'email', 'other'];

/** Notifications worth listing (group summaries just repeat their children). */
export function notifications() {
  const list = store.notifs?.notifications || [];
  const apps = new Set(list.filter(n => !n.summary).map(n => n.pkg));
  return list.filter(n => !n.summary || !apps.has(n.pkg));
}

/**
 * Items for a notifications pivot, newest first. Calls come from the call log
 * (it has numbers to call back), so call-type notifications are left out to
 * avoid showing the same missed call twice.
 */
export function notifItems(pivot) {
  const ns = notifications().map(n => ({ kind: 'notif', type: notifType(n), time: n.time, n }));
  const cs = calls().map(c => ({ kind: 'call', type: 'calls', time: c.time, c }));
  const dayAgo = Date.now() - 2 * 86_400_000;
  let items;
  if (pivot === 'calls') items = cs;
  else if (pivot === 'all') items = [...ns.filter(i => i.type !== 'calls'), ...cs.filter(i => i.time > dayAgo)];
  else items = ns.filter(i => i.type === pivot);
  return items.sort((a, b) => b.time - a.time);
}

// ── Material for scoped agent questions ─────────────────────────
// Sending the agent exactly what's on screen keeps its answer on that, instead
// of it fetching (and then narrating) the whole inbox.

const when = ms => new Date(ms).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' });
const callWhat = e => (e.dir === 'missed' ? 'missed call' : e.dir === 'in' ? 'incoming call' : 'outgoing call');

/** One person's recent messages (and calls), oldest first. */
export function transcript(person, events, { notes = [], limit = 25 } = {}) {
  const lines = events.slice(0, limit).reverse().map(e => {
    const who = e.dir === 'out' ? 'me' : (e.group && e.person ? e.person : person);
    return e.channel === 'Call'
      ? `- ${when(e.time)} · ${callWhat(e)}`
      : `- ${when(e.time)} · ${who} (${e.channel}): ${String(e.text || '').replace(/\s+/g, ' ').slice(0, 300)}`;
  });
  const noteLines = notes.slice(-5).map(n => `- ${n.ts ? n.ts + ': ' : ''}${n.text}`);
  return [
    `My recent conversation with ${person} (oldest first):`,
    lines.length ? lines.join('\n') : '- (nothing in the last week)',
    noteLines.length ? `\nMy notes about ${person}:\n${noteLines.join('\n')}` : '',
    `\nAnswer only about ${person}, from the above, in 2-4 sentences: what's the latest, and do I need to reply? ` +
    'If I do, suggest a short reply. Do not mention any other people, messages, notifications or apps.',
  ].filter(Boolean).join('\n');
}

/** Threads where the last word was theirs: who is waiting on me. */
export function waitingDigest(limit = 15) {
  const waiting = threads().filter(t => t.last.dir !== 'out').slice(0, limit);
  const lines = waiting.map(t =>
    `- ${t.person} (${t.last.channel}, ${when(t.last.time)}): ${String(t.last.text || '').replace(/\s+/g, ' ').slice(0, 160)}`);
  return [
    'Conversations where the last message was from them (newest first):',
    lines.length ? lines.join('\n') : '- (none)',
    '\nUsing only this list: one short line per person saying what they need from me. Skip anyone who clearly needs nothing. ' +
    'Do not mention notifications, email or other apps.',
  ].join('\n');
}

/** A notifications tab's items, for "summarize". */
export function notifDigest(pivot, limit = 25) {
  const lines = notifItems(pivot).slice(0, limit).map(i => i.kind === 'call'
    ? `- ${when(i.c.time)} · ${callWhat(i.c)} · ${i.c.person}`
    : `- ${when(i.n.time)} · ${i.n.app}: ${[i.n.title, i.n.text].filter(Boolean).join(' - ').replace(/\s+/g, ' ').slice(0, 200)}`);
  const what = pivot === 'all' ? 'notifications' : `${pivot} notifications`;
  return [
    `My current ${what} (newest first):`,
    lines.length ? lines.join('\n') : '- (none)',
    `\nUsing only this list, answer briefly. Group or skip routine items; call out only what actually needs me.`,
  ].join('\n');
}

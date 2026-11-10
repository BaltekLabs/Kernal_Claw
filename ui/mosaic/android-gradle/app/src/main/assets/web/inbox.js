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

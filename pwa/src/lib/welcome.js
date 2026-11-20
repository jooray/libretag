// The tag's welcome DM to the owner (PROTOCOL.md §5, SPEC "welcome DM"), sent again from the web app.
// A NIP-17 gift wrap (kind 14 in 13 in 1059): the seal is signed by the tag's own dm key, so the owner's
// client shows the tag as the sender, and relays see only a one-time key and the owner's p tag. It goes
// only to the owner's kind 10050 DM inbox relays (NIP-17), NIP-42 AUTH answered with the wrap's one-time
// key. Transition, same rule as the Android app: when the owner has no 10050 list, or no inbox relay
// took the wrap, the old NIP-04 kind 4 goes to our relays and the owner's NIP-65 read relays (AUTH with
// the tag's key). Plus the tag's kind 0 profile.
import { finalizeEvent, getPublicKey } from 'nostr-tools/pure';
import { encrypt as nip04Encrypt } from 'nostr-tools/nip04';
import { nip19 } from 'nostr-tools';
import { hexToBytes } from './bytes.js';
import { giftWrap } from './nip17.js';

export const KIND_RELAY_LIST = 10002;
export const KIND_DM_RELAYS = 10050;
export const MAX_EXTRA = 6;
export const INBOX_INDEXERS = ['wss://purplepag.es'];
export const PROFILE = { name: 'LibreTag', about: 'Messages from your LibreTag tag (battery alerts).', picture: 'https://cypherpunk.today/libretag/app/icon-512.png' };

export const normalize = (url) => {
  const u = String(url || '').trim().replace(/\/+$/, '');
  return /^wss:\/\//i.test(u) ? 'wss://' + u.slice(6) : null;
};

export function isPublicWss(url) {
  let host;
  try { host = new URL(url).hostname.toLowerCase().replace(/^\[|\]$/g, ''); } catch { return false; }
  if (!/^wss:\/\//i.test(url) || !host) return false;
  if (host === 'localhost' || /\.(localhost|local|internal|lan)$/.test(host)) return false;
  const v4 = host.split('.');
  if (v4.length === 4 && v4.every((x) => /^\d{1,3}$/.test(x) && +x <= 255)) {
    const [a, b] = v4.map(Number);
    return !(a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31)
      || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224);
  }
  if (host.includes(':')) return !(host === '::1' || host === '::' || /^(fe80|fc|fd)/.test(host));
  return host.includes('.');
}

/** { dm: newest 10050 `relay` tags, read: newest 10002 `r` tags without marker or `read` }, public wss only. */
export function inboxLists(events, pubkey) {
  const newest = (kind) => events.filter((e) => e.kind === kind && (!pubkey || e.pubkey === pubkey))
    .sort((a, b) => b.created_at - a.created_at)[0];
  const clean = (l) => [...new Set(l.map(normalize).filter((u) => u && isPublicWss(u)))];
  return {
    dm: clean((newest(KIND_DM_RELAYS)?.tags ?? []).filter((t) => t[0] === 'relay' && t[1]).map((t) => t[1])),
    read: clean((newest(KIND_RELAY_LIST)?.tags ?? []).filter((t) => t[0] === 'r' && t[1] && (!t[2] || t[2] === 'read')).map((t) => t[1])),
  };
}

/** The recipient's inbox relays: newest 10050 (`relay`) then newest 10002 (`r`, no marker or `read`). */
export function inboxRelays(events, pubkey) {
  const { dm, read } = inboxLists(events, pubkey);
  return [...new Set([...dm, ...read])];
}

/** Ours, then up to MAX_EXTRA of the recipient's that we don't have; normalized, no duplicates. */
export function dmTargets(configured, inbox) {
  const own = [...new Set(configured.map(normalize).filter(Boolean))];
  const extra = inbox.map(normalize).filter((u) => u && !own.includes(u));
  return [...own, ...[...new Set(extra)].slice(0, MAX_EXTRA)];
}

/** Owner npub (backup) or hex (signer) to hex; null when unusable. */
export function ownerHex(owner) {
  if (!owner) return null;
  if (/^[0-9a-f]{64}$/i.test(owner)) return owner.toLowerCase();
  try { const d = nip19.decode(owner.trim()); return d.type === 'npub' ? d.data : null; } catch { return null; }
}

/** NIP-04 kind 4 from the tag's dm key: the fallback while owners' clients have no 10050 list. */
export function welcomeEvent(dmSkHex, toHex, text, now) {
  const sk = hexToBytes(dmSkHex);
  return finalizeEvent({ kind: 4, created_at: now, tags: [['p', toHex]], content: nip04Encrypt(sk, toHex, text) }, sk);
}

/** The welcome DM as a NIP-17 gift wrap from the tag's dm key: { wrap, wrapSk, seal, rumor }. */
export const welcomeWrap = (dmSkHex, toHex, text, now, opts) => giftWrap(dmSkHex, toHex, text, now, opts);

/** The tag's npub (from its dm key) and where to follow it: a nostr: link for an installed app, njump.me otherwise. */
export function followLinks(dmSkHex) {
  const npub = nip19.npubEncode(getPublicKey(hexToBytes(dmSkHex)));
  return { npub, app: 'nostr:' + npub, web: 'https://njump.me/' + npub };
}

export const profileEvent = (dmSkHex, now) =>
  finalizeEvent({ kind: 0, created_at: now, tags: [], content: JSON.stringify(PROFILE) }, hexToBytes(dmSkHex));

/** NIP-42: sign the relay's AUTH template with the publishing key (the tag's for kind 0, the one-time key for a gift wrap; never the owner's). */
export const authSigner = (sk) => async (tpl) => finalizeEvent(tpl, typeof sk === 'string' ? hexToBytes(sk) : sk);

/** Publish to each relay; [{ url, ok, reason }]. `authSk`: hex or bytes of the key that answers AUTH. */
export async function publishEach(pool, urls, event, authSk, maxWait = 10000) {
  const ps = pool.publish(urls, event, { maxWait, onauth: authSigner(authSk) });
  return Promise.all(ps.map((p, i) => p.then(
    (reason) => ({ url: urls[i], ok: true, reason: reason || '' }),
    (e) => {
      const reason = String(e?.message ?? e ?? '');
      return { url: urls[i], ok: /^duplicate:/.test(reason), reason };
    })));
}

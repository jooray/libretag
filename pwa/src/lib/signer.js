// "Sign in with Nostr" (SPEC 12.2 B): NIP-07 extension, NIP-46 bunker:// and nostrconnect:// (Amber).
// The signer is only used to NIP-44 encrypt / decrypt the owner's backup to themselves and to sign
// the backup event. Reading reports never needs it. Quirks from the nostr-amber-auth skill:
//  - the signer's event.pubkey is a routing key: always call get_public_key for the real npub;
//  - skip switch_relays (Amber may not answer it), keep the subscription open through both phases;
//  - probe relays first and put only reachable ones in the URI; spaces in name= as %20, not +;
//  - kind 24133 is ephemeral: a missed connect response cannot be recovered, offer a new code.
import { BunkerSigner, createNostrConnectURI } from 'nostr-tools/nip46';
import { generateSecretKey, getPublicKey } from 'nostr-tools/pure';
import { bytesToHex, hexToBytes } from './bytes.js';

export const hasExtension = () => typeof window !== 'undefined' && !!window.nostr?.getPublicKey;

/** Uniform shape: { kind, pubkey, nip44Encrypt(text), nip44Decrypt(payload), signEvent(template), session(), close() } */
function wrap(kind, pubkey, impl, session) {
  return {
    kind, pubkey,
    nip44Encrypt: (text) => impl.nip44Encrypt(pubkey, text),
    nip44Decrypt: (payload) => impl.nip44Decrypt(pubkey, payload),
    signEvent: (tpl) => impl.signEvent(tpl),
    session: () => session,
    close: () => impl.close?.(),
  };
}

export async function connectExtension() {
  const n = window.nostr;
  const pubkey = await n.getPublicKey();
  if (!/^[0-9a-f]{64}$/.test(pubkey)) throw new Error('bad pubkey');
  if (!n.nip44?.encrypt || !n.nip44?.decrypt) throw new Error('nip44 missing');
  return wrap('nip07', pubkey, {
    nip44Encrypt: (pk, text) => n.nip44.encrypt(pk, text),
    nip44Decrypt: (pk, payload) => n.nip44.decrypt(pk, payload),
    signEvent: (tpl) => n.signEvent(tpl),
  }, { kind: 'nip07', pubkey });
}

/** bunker://<pubkey>?relay=...&secret=... only: a NIP-05 address would mean a lookup on someone's server. */
export function parseBunkerURL(s) {
  const text = (s || '').trim();
  if (!/^bunker:\/\//i.test(text)) return null;
  try {
    const u = new URL(text.replace(/^bunker:\/\//i, 'https://'));
    const pubkey = u.hostname.toLowerCase();
    const relays = u.searchParams.getAll('relay').filter((r) => /^wss?:\/\//.test(r));
    if (!/^[0-9a-f]{64}$/.test(pubkey) || !relays.length) return null;
    return { pubkey, relays, secret: u.searchParams.get('secret') };
  } catch { return null; }
}

const bunkerWrap = async (bunker, clientSk) => {
  const pubkey = await bunker.getPublicKey(); // never trust event.pubkey
  return wrap('nip46', pubkey, bunker, { kind: 'nip46', pubkey, clientSk: bytesToHex(clientSk), bp: { ...bunker.bp, secret: null } });
};

export async function connectBunker(url, pool, { onauth, timeout = 40_000 } = {}) {
  const bp = parseBunkerURL(url);
  if (!bp) throw new Error('bad bunker link');
  const clientSk = generateSecretKey();
  const bunker = BunkerSigner.fromBunker(clientSk, bp, { pool, onauth, timeout });
  await bunker.connect();
  return bunkerWrap(bunker, clientSk);
}

/** Restore a NIP-46 session after the automatic reload; null when the signer no longer answers. */
export async function restoreSession(session, pool) {
  try {
    if (session?.kind === 'nip07') { const s = await connectExtension(); return s.pubkey === session.pubkey ? s : null; }
    if (session?.kind === 'nip46') {
      const clientSk = hexToBytes(session.clientSk);
      const bunker = BunkerSigner.fromBunker(clientSk, session.bp, { pool, timeout: 15_000 });
      const s = await bunkerWrap(bunker, clientSk);
      return s.pubkey === session.pubkey ? s : null;
    }
  } catch { /* signer gone */ }
  return null;
}

/**
 * A signer for a stored session that connects only when something needs it (a rename that must be
 * written to copy B). Same shape as the others, plus `lazy` and `online` (null: not tried yet,
 * false: the last attempt failed). Every call retries, so it recovers when the signer is back.
 */
export function lazySigner(session, pool, { ms = 15_000 } = {}) {
  let impl = null;
  const s = wrap(session.kind, session.pubkey, {
    nip44Encrypt: async (pk, text) => (await connect()).nip44Encrypt(pk, text),
    nip44Decrypt: async (pk, payload) => (await connect()).nip44Decrypt(pk, payload),
    signEvent: async (tpl) => (await connect()).signEvent(tpl),
    close: () => { try { impl?.close?.(); } catch { /* ignore */ } },
  }, session);
  s.lazy = true;
  s.online = null;
  const withTimeout = (p) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('signer timeout')), ms))]);
  async function connect() {
    try {
      if (!impl) {
        if (session.kind === 'nip07') {
          const n = window.nostr;
          if (!n?.nip44?.encrypt || (await withTimeout(n.getPublicKey())) !== session.pubkey) throw new Error('no extension');
          impl = { nip44Encrypt: (pk, t) => n.nip44.encrypt(pk, t), nip44Decrypt: (pk, c) => n.nip44.decrypt(pk, c), signEvent: (t) => n.signEvent(t) };
        } else {
          const b = BunkerSigner.fromBunker(hexToBytes(session.clientSk), session.bp, { pool, timeout: ms });
          if ((await withTimeout(b.getPublicKey())) !== session.pubkey) { b.close?.(); throw new Error('other signer'); }
          impl = b;
        }
      }
      s.online = true;
      return impl;
    } catch (e) { s.online = false; throw e; }
  }
  // a failing call on a live connection also marks it offline; the next call reconnects
  for (const k of ['nip44Encrypt', 'nip44Decrypt', 'signEvent']) {
    const f = s[k];
    s[k] = async (...a) => {
      try { const r = await withTimeout(f(...a)); s.online = true; return r; } catch (e) { s.online = false; impl?.close?.(); impl = null; throw e; }
    };
  }
  return s;
}

/** Which of these relays accept a WebSocket within `ms`? If none, all of them. */
export async function probeRelays(urls, ms = 3000) {
  const ok = [];
  await Promise.all(urls.map((u) => new Promise((resolve) => {
    let ws;
    const done = (good) => { if (good) ok.push(u); try { ws?.close(); } catch { /* ignore */ } resolve(); };
    try { ws = new WebSocket(u); } catch { return done(false); }
    const timer = setTimeout(() => done(false), ms);
    ws.onopen = () => { clearTimeout(timer); done(true); };
    ws.onerror = () => { clearTimeout(timer); done(false); };
  })));
  return ok.length ? urls.filter((u) => ok.includes(u)) : urls;
}

/**
 * nostrconnect:// (client-initiated, QR). Returns { uri, promise, cancel }. The promise resolves to a
 * signer after the signer's connect response and get_public_key. `uri` is ready at once.
 */
export function startNostrConnect(relays, pool, { name = 'LibreTag web', url, image, timeout = 180_000 } = {}) {
  const clientSk = generateSecretKey();
  const secret = bytesToHex(generateSecretKey()).slice(0, 32);
  const uri = createNostrConnectURI({
    clientPubkey: getPublicKey(clientSk), relays, secret, name: name,
    perms: ['nip44_encrypt', 'nip44_decrypt', 'sign_event:30078', 'sign_event:5'], url,
    image: image ?? (typeof location !== 'undefined' ? new URL('icon-512.png', location.href).href : undefined),
  }).replace(/\+/g, '%20'); // Amber shows a literal "+" otherwise
  const ctl = new AbortController();
  const promise = (async () => {
    const bunker = await BunkerSigner.fromURI(clientSk, uri, { pool, skipSwitchRelays: true, timeout: 30_000 }, ctl.signal);
    return bunkerWrap(bunker, clientSk);
  })();
  // swallow a rejection we caused by cancelling
  promise.catch(() => {});
  return { uri, promise, cancel: () => ctl.abort(), timeout };
}

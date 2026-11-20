// Legacy: before "stay signed in" (session.js) the keys could be kept in an AES-GCM blob in localStorage,
// locked with a passphrase (scrypt). It is only read now, to convert it once (or forget it).
// The sessionStorage hand-over across the automatic version reload is still used when "stay signed in" is off.
import { hmac } from '@noble/hashes/hmac.js';
import { sha256 } from '@noble/hashes/sha2.js';
import { b64decode, b64encode, concatBytes, utf8ToBytes } from './bytes.js';

const CACHE_KEY = 'lt.cache';
const HANDOFF_KEY = 'lt.handoff';
const AAD = utf8ToBytes('LibreTag web cache v1');

/** The AES key bytes for the cache, from the scrypt output S of the passphrase. */
export const cacheKey = (S) => hmac(sha256, S, utf8ToBytes('LT-web-cache'));

const key = (raw, usage) => crypto.subtle.importKey('raw', raw, 'AES-GCM', false, [usage]);

/** Kept for the migration test: new sessions are never written with it. */
export async function sealCache(keyBytes, data) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: AAD },
    await key(keyBytes, 'encrypt'), utf8ToBytes(JSON.stringify(data))));
  return b64encode(concatBytes(Uint8Array.of(1), iv, ct));
}

/** The data, or null when the key is wrong or the blob is damaged. */
export async function openCache(keyBytes, blob) {
  try {
    const raw = b64decode(blob);
    if (raw[0] !== 1) return null;
    const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: raw.slice(1, 13), additionalData: AAD },
      await key(keyBytes, 'decrypt'), raw.slice(13));
    return JSON.parse(new TextDecoder().decode(pt));
  } catch { return null; }
}

/** What goes into the cache for one tag: keys, local settings and the reports read so far. */
export const serializeTag = (t) => ({
  id: t.id, name: t.name, type: t.type, mark: t.mark, k0: t.k0, share: t.share,
  intervalMin: t.intervalMin ?? null, lastN: t.lastN, cmdSeq: t.cmdSeq ?? null,
  pending: t.pending ?? null, lastCommand: t.lastCommand ?? null,
  modeUntil: t.modeUntil ?? null, modeIntervalS: t.modeIntervalS ?? null,
  updated: t.updated ?? 0, imported: !!t.imported, extra: t.extra ?? {}, zones: t.zones ?? [],
  alarm: t.alarm ?? null, inZone: t.inZone ?? null, syncedTo: t.syncedTo ?? null, mbAnchors: t.mbAnchors ?? {},
  positions: t.positions.slice(-400),
});

const store = {
  get: (k, s = localStorage) => { try { return s.getItem(k); } catch { return null; } },
  set: (k, v, s = localStorage) => { try { s.setItem(k, v); return true; } catch { return false; } },
  del: (k, s = localStorage) => { try { s.removeItem(k); } catch { /* ignore */ } },
};

export const hasCache = () => store.get(CACHE_KEY) != null;
export const readCacheBlob = () => store.get(CACHE_KEY);
export const clearCache = () => store.del(CACHE_KEY);

/**
 * An automatic version refresh reloads the page. The keys cross that one reload in sessionStorage
 * (gone when the tab closes) and are removed the moment the new page reads them.
 */
export const saveHandoff = (data) => store.set(HANDOFF_KEY, JSON.stringify(data), sessionStorage);
export function takeHandoff() {
  const v = store.get(HANDOFF_KEY, sessionStorage);
  store.del(HANDOFF_KEY, sessionStorage);
  try { return v ? JSON.parse(v) : null; } catch { return null; }
}

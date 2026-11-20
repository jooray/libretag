// "Stay signed in on this device": the signer session (NIP-46 client key, remote signer, relays, user
// pubkey, or the NIP-07 choice) and a cache of the tags with their keys live in IndexedDB as one
// AES-GCM blob. The AES key is a WebCrypto CryptoKey made once with extractable=false and stored in
// IndexedDB too: script in this origin can use it in this browser, but nothing can read its bytes.
// Sign-out deletes the whole database, key included.
import { b64decode, b64encode, concatBytes, utf8ToBytes } from './bytes.js';

const DB = 'libretag-session';
const STORE = 'kv';
const AAD = utf8ToBytes('LibreTag web session v1');

const open = () => new Promise((resolve, reject) => {
  const r = indexedDB.open(DB, 1);
  r.onupgradeneeded = () => r.result.createObjectStore(STORE);
  r.onsuccess = () => resolve(r.result);
  r.onerror = () => reject(r.error);
});

async function tx(mode, fn) {
  const db = await open();
  try {
    return await new Promise((resolve, reject) => {
      const t = db.transaction(STORE, mode);
      let out;
      const req = fn(t.objectStore(STORE));
      if (req) req.onsuccess = () => { out = req.result; };
      t.oncomplete = () => resolve(out);
      t.onerror = t.onabort = () => reject(t.error);
    });
  } finally { db.close(); }
}

const idbGet = (k) => tx('readonly', (s) => s.get(k));
const idbSet = (k, v) => tx('readwrite', (s) => s.put(v, k));

export const available = () => typeof indexedDB !== 'undefined' && !!globalThis.crypto?.subtle;

async function aesKey(create) {
  let key = await idbGet('key');
  if (!key && create) {
    key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    await idbSet('key', key);
  }
  return key ?? null;
}

/** Encrypt `data` (JSON-able) and store it. Returns false when the browser refuses (private mode, no IndexedDB). */
export async function saveSession(data) {
  try {
    const key = await aesKey(true);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: AAD }, key, utf8ToBytes(JSON.stringify(data))));
    await idbSet('blob', b64encode(concatBytes(Uint8Array.of(1), iv, ct)));
    return true;
  } catch { return false; }
}

/** The stored data, or null when there is none, the key is gone or the blob is damaged. */
export async function loadSession() {
  try {
    const key = await aesKey(false);
    const blob = await idbGet('blob');
    if (!key || !blob) return null;
    const raw = b64decode(blob);
    if (raw[0] !== 1) return null;
    const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: raw.slice(1, 13), additionalData: AAD }, key, raw.slice(13));
    return JSON.parse(new TextDecoder().decode(pt));
  } catch { return null; }
}

/** Sign-out: delete the session, the cache and the CryptoKey (the whole database). */
export function clearSession() {
  if (typeof indexedDB === 'undefined') return Promise.resolve();
  return new Promise((resolve) => {
    const r = indexedDB.deleteDatabase(DB);
    r.onsuccess = r.onerror = r.onblocked = () => resolve();
  });
}

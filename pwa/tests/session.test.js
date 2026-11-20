import 'fake-indexeddb/auto';
import { afterEach, describe, expect, it } from 'vitest';
import { finalizeEvent, generateSecretKey, getPublicKey } from 'nostr-tools/pure';
import * as nip44 from 'nostr-tools/nip44';
import { App } from '../src/lib/app.js';
import { makeTag, parseKeyInput } from '../src/lib/keys.js';
import { clearSession, loadSession, saveSession } from '../src/lib/session.js';
import { lazySigner } from '../src/lib/signer.js';
import { clearCache, readCacheBlob } from '../src/lib/vault.js';

const sk = generateSecretKey();
const pk = getPublicKey(sk);
let down = false;
const stubNostr = () => ({
  getPublicKey: async () => { if (down) throw new Error('offline'); return pk; },
  signEvent: async (t) => { if (down) throw new Error('offline'); return finalizeEvent(t, sk); },
  nip44: {
    encrypt: async (p, t) => { if (down) throw new Error('offline'); return nip44.v2.encrypt(t, nip44.v2.utils.getConversationKey(sk, p)); },
    decrypt: async (p, c) => nip44.v2.decrypt(c, nip44.v2.utils.getConversationKey(sk, p)),
  },
});
afterEach(async () => { delete globalThis.window; down = false; await clearSession(); });

const quietApp = () => {
  const app = new App();
  app.pool.querySync = async () => [];
  app.pool.publish = () => [Promise.resolve('ok')];
  app.pool.subscribeMany = () => ({ close() {} });
  return app;
};
const addTag = (app, name) => {
  const { chain, tag } = makeTag(parseKeyInput('00'.repeat(31) + '01'), [], name);
  app.tags.push({ ...tag, chain, positions: [{ n: 1, receivedAt: 5 }], lastN: 1, pending: null, extra: {}, zones: [], intervalMin: null, updated: 1 });
};

describe('persistent session', () => {
  it('stores an encrypted blob and a non-extractable key', async () => {
    expect(await saveSession({ v: 1, secret: 'topsecret-value' })).toBe(true);
    const db = await new Promise((res) => { const r = indexedDB.open('libretag-session'); r.onsuccess = () => res(r.result); });
    const get = (k) => new Promise((res) => { const q = db.transaction('kv').objectStore('kv').get(k); q.onsuccess = () => res(q.result); });
    const key = await get('key');
    expect(key.extractable).toBe(false);
    expect(await get('blob')).not.toContain('topsecret');
    db.close();
    expect(await loadSession()).toEqual({ v: 1, secret: 'topsecret-value' });
  });

  it('restores tags and the NIP-46 session without any signer round-trip, and sign-out wipes it', async () => {
    const a = quietApp();
    addTag(a, 'Car');
    a.signer = { pubkey: pk, session: () => ({ kind: 'nip46', pubkey: pk, clientSk: '11'.repeat(32), bp: { pubkey: '22'.repeat(32), relays: ['wss://x.example'], secret: null } }), close() {} };
    a.backupInfo.b = 1234;
    expect(await a.enableStay()).toBe(true);

    const b = quietApp();
    expect(await b.restoreStay()).toBe(true);
    expect(b.tags.map((x) => x.name)).toEqual(['Car']);
    expect(b.tags[0].positions).toHaveLength(1);
    expect(b.signer.lazy).toBe(true);
    expect(b.signer.pubkey).toBe(pk);
    expect(b.signer.online).toBeNull(); // nothing contacted yet
    expect(b.signerOffline).toBe(false);
    expect(b.backupInfo.b).toBe(1234);

    await b.forgetAll();
    expect(await loadSession()).toBeNull();
    expect(await quietApp().restoreStay()).toBe(false);
  });

  it('without the checkbox nothing is written', async () => {
    const a = quietApp();
    addTag(a, 'Car');
    a.persist(); await a.persistNow();
    expect(await loadSession()).toBeNull();
  });

  it('a lost key makes the blob unreadable', async () => {
    await saveSession({ v: 1, tags: [] });
    await new Promise((res) => { const r = indexedDB.open('libretag-session'); r.onsuccess = () => { const tx = r.result.transaction('kv', 'readwrite'); tx.objectStore('kv').delete('key'); tx.oncomplete = () => { r.result.close(); res(); }; }; });
    expect(await loadSession()).toBeNull();
  });

  it('the old passphrase blob is left alone by sign-out only through clearCache', () => {
    expect(readCacheBlob()).toBeNull();
    clearCache();
  });
});

describe('lazy signer', () => {
  it('connects only when used, reports offline, recovers', async () => {
    globalThis.window = { nostr: stubNostr() };
    const s = lazySigner({ kind: 'nip07', pubkey: pk }, null, { ms: 300 });
    expect(s.online).toBeNull();
    down = true;
    await expect(s.nip44Encrypt('hi')).rejects.toThrow();
    expect(s.online).toBe(false);
    down = false;
    const c = await s.nip44Encrypt('hi');
    expect(s.online).toBe(true);
    expect(await s.nip44Decrypt(c)).toBe('hi');
  });

  it('a rename while the signer is offline is kept and written when it is back', async () => {
    globalThis.window = { nostr: stubNostr() };
    const app = quietApp();
    addTag(app, 'Car');
    app.signer = lazySigner({ kind: 'nip07', pubkey: pk }, null, { ms: 300 });
    app.meta.d = 'ab'.repeat(16);
    await app.enableStay();
    down = true;
    const r = await app.editTag(app.tags[0].id, { name: 'Bike' });
    expect(r.deferred).toBe(true);
    expect(app.backupPending).toBe(true);
    expect(app.signerOffline).toBe(true);
    expect(app.tags[0].name).toBe('Bike');
    down = false;
    await app.retryPending();
    expect(app.backupPending).toBe(false);
    expect(app.signerOffline).toBe(false);
    expect(app.backupInfo.b).toBeGreaterThan(0);
  });
});

import { afterEach, describe, expect, it } from 'vitest';
import { finalizeEvent, generateSecretKey, getPublicKey } from 'nostr-tools/pure';
import * as nip44 from 'nostr-tools/nip44';
import { connectExtension, hasExtension, parseBunkerURL } from '../src/lib/signer.js';

afterEach(() => { delete globalThis.window; });

describe('NIP-07 extension', () => {
  const sk = generateSecretKey();
  const pk = getPublicKey(sk);
  const stub = (nip44ok = true) => ({
    getPublicKey: async () => pk,
    signEvent: async (t) => finalizeEvent(t, sk),
    ...(nip44ok ? { nip44: {
      encrypt: async (p, t) => nip44.v2.encrypt(t, nip44.v2.utils.getConversationKey(sk, p)),
      decrypt: async (p, c) => nip44.v2.decrypt(c, nip44.v2.utils.getConversationKey(sk, p)),
    } } : {}),
  });

  it('is detected, and encrypts to the owner themselves', async () => {
    globalThis.window = { nostr: stub() };
    expect(hasExtension()).toBe(true);
    const s = await connectExtension();
    expect(s.pubkey).toBe(pk);
    const c = await s.nip44Encrypt('secret plaintext');
    expect(await s.nip44Decrypt(c)).toBe('secret plaintext');
    const ev = await s.signEvent({ kind: 30078, created_at: 1, tags: [['d', 'x']], content: c });
    expect(ev.pubkey).toBe(pk);
    expect(s.session()).toEqual({ kind: 'nip07', pubkey: pk });
  });
  it('refuses an extension without NIP-44, and reports none when absent', async () => {
    globalThis.window = { nostr: stub(false) };
    await expect(connectExtension()).rejects.toThrow('nip44 missing');
    globalThis.window = {};
    expect(hasExtension()).toBe(false);
  });
});

describe('bunker links', () => {
  const pk = 'ab'.repeat(32);
  it('parses bunker:// with relays and a secret, and refuses everything else', () => {
    const b = parseBunkerURL(`bunker://${pk}?relay=wss%3A%2F%2Frelay.example&relay=wss://b.example&secret=s3`);
    expect(b).toEqual({ pubkey: pk, relays: ['wss://relay.example', 'wss://b.example'], secret: 's3' });
    expect(parseBunkerURL('alice@example.com')).toBeNull();   // NIP-05 would mean a lookup on someone's server
    expect(parseBunkerURL(`bunker://${pk}`)).toBeNull();      // no relay
    expect(parseBunkerURL('bunker://zz?relay=wss://a')).toBeNull();
    expect(parseBunkerURL('nsec1abcdef')).toBeNull();
  });
});

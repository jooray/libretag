import { describe, expect, it } from 'vitest';
import { generateSecretKey, getPublicKey, finalizeEvent } from 'nostr-tools/pure';
import * as nip44 from 'nostr-tools/nip44';
import { generateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english.js';
import { checkMnemonic, normalizeMnemonic } from '../src/lib/mnemonic.js';
import { mergePayloads, parsePlaintext, plaintextOf } from '../src/lib/merge.js';
import { buildBackupBEvent, findBackupB, looksLikeNip44, randomLocator, writeRelays } from '../src/lib/backupB.js';
import { derive, restore, buildBackupEvent } from '../src/lib/backup.js';

const K = (c) => c.repeat(32);

describe('BIP-39 phrase (backup A)', () => {
  const good = 'abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about';
  it('accepts valid 12 and 24 word mnemonics, normalising case and spaces', () => {
    expect(checkMnemonic(good)).toBe('ok');
    expect(checkMnemonic('  ABANDON  abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon ABOUT ')).toBe('ok');
    expect(checkMnemonic(generateMnemonic(wordlist, 256))).toBe('ok');
    expect(normalizeMnemonic(' Abandon  about ')).toBe('abandon about');
  });
  it('refuses a bad checksum, an unknown word, wrong lengths and free-form passphrases', () => {
    expect(checkMnemonic(good.replace('about', 'abandon'))).toBe('invalid');
    expect(checkMnemonic(good.replace('about', 'zzzzz'))).toBe('invalid');
    expect(checkMnemonic('abandon abandon about')).toBe('wrong_length');
    expect(checkMnemonic(good + ' about')).toBe('wrong_length');
    expect(checkMnemonic('correct horse battery staple is a long passphrase')).toBe('wrong_length');
    expect(checkMnemonic('')).toBe('wrong_length');
  });
  it('a generated phrase round-trips through derive and restore', async () => {
    const phrase = generateMnemonic(wordlist, 128);
    const d = await derive(normalizeMnemonic(phrase), { N: 1 << 8 });
    const pt = plaintextOf({ created: 100, owner: null, d: 'a'.repeat(32), tags: [{ name: 'Car', k0: K('0a'), type: 'vehicle', mark: { color: 'red', symbol: 'stripe' }, updated: 90 }] });
    const ev = await buildBackupEvent(d, pt, 100);
    const r = await restore(d, [ev]);
    expect(r.state).toBe('found');
    expect(r.payload.tags[0]).toMatchObject({ name: 'Car', updated: 90 });
  });
});

describe('merge (12.2)', () => {
  const now = 2_000_000_000;
  const tag = (k, name, updated) => ({ k0: K(k), name, type: 'thing', mark: 'red-stripe', updated });
  it('newest updated wins per tag, a newer tombstone removes, a newer tag resurrects', () => {
    const a = { v: 1, d: 'dd', tags: [tag('01', 'Old name', 10), tag('02', 'Keep', 50), { k0: K('03'), removed: 30 }] };
    const b = { v: 1, tags: [tag('01', 'New name', 20), { k0: K('02'), removed: 40 }, tag('03', 'Back', 60)] };
    const m = mergePayloads([a, b], now);
    const byK = Object.fromEntries(m.tags.map((e) => [e.k0.slice(0, 2), e]));
    expect(byK['01'].name).toBe('New name');
    expect(byK['02'].name).toBe('Keep');
    expect(byK['03'].name).toBe('Back');
    expect(m.d).toBe('dd');
  });
  it('drops tombstones after 90 days and keeps younger ones', () => {
    const m = mergePayloads([{ v: 1, tags: [{ k0: K('01'), removed: now - 91 * 86400 }, { k0: K('02'), removed: now - 89 * 86400 }] }], now);
    expect(m.tags.map((e) => e.k0.slice(0, 2))).toEqual(['02']);
  });
  it('a removal beats a tag with the same timestamp', () => {
    const m = mergePayloads([{ tags: [tag('01', 'x', 5)] }, { tags: [{ k0: K('01'), removed: 5 }] }], 10);
    expect(m.tags[0].removed).toBe(5);
  });
  it('plaintextOf keeps the 12.1 key order and parsePlaintext separates tombstones', () => {
    const s = plaintextOf({ created: 7, owner: null, d: 'ab', tags: [{ name: 'A', k0: K('01'), type: 'thing', mark: { color: 'blue', symbol: 'circle' }, updated: 3 }], tombstones: [{ k0: K('02'), removed: 4 }] });
    expect(Object.keys(JSON.parse(s))).toEqual(['v', 'created', 'owner', 'd', 'tags']);
    const p = parsePlaintext(s);
    expect(p.tags[0]).toMatchObject({ name: 'A', mark: 'blue-circle', updated: 3 });
    expect(p.tombstones).toEqual([{ k0: K('02'), removed: 4 }]);
    expect(parsePlaintext('{"v":2,"tags":[]}')).toBeNull();
    expect(parsePlaintext('nope')).toBeNull();
  });
});

// A signer that holds a secret key, like an extension or a bunker would.
function fakeSigner(sk) {
  const pubkey = getPublicKey(sk);
  const ck = nip44.v2.utils.getConversationKey(sk, pubkey);
  return {
    pubkey,
    nip44Encrypt: async (t) => nip44.v2.encrypt(t, ck),
    nip44Decrypt: async (p) => nip44.v2.decrypt(p, ck),
    signEvent: async (tpl) => finalizeEvent(tpl, sk),
  };
}
const fakePool = (events) => ({ querySync: async (_r, f) => events.filter((e) => e.kind === f.kinds[0] && (!f.authors || f.authors.includes(e.pubkey))) });

describe('copy B (NIP-44 to self)', () => {
  const sk = generateSecretKey();
  const signer = fakeSigner(sk);
  const pt = (d, name = 'Car') => plaintextOf({ created: 100, owner: null, d, tags: [{ name, k0: K('0a'), type: 'vehicle', mark: { color: 'red', symbol: 'stripe' }, updated: 90 }] });

  it('builds an event with no alt tag and nothing that says LibreTag, and finds it again', async () => {
    const d = randomLocator();
    const ev = await buildBackupBEvent(signer, d, pt(d), 100);
    expect(ev.kind).toBe(30078);
    expect(ev.tags).toEqual([['d', d]]);
    expect(JSON.stringify(ev).toLowerCase()).not.toContain('libretag');
    expect(looksLikeNip44(ev.content)).toBe(true);
    const found = await findBackupB(fakePool([ev]), [], signer);
    expect(found.d).toBe(d);
    expect(found.payload.tags[0].name).toBe('Car');
  });
  it('skips other app data, undecryptable events and a plaintext whose d does not match', async () => {
    const d = randomLocator();
    const other = finalizeEvent({ kind: 30078, created_at: 300, tags: [['d', 'x']], content: '{"some":"app data"}' }, sk);
    const wrongKey = fakeSigner(generateSecretKey());
    const foreign = await buildBackupBEvent(wrongKey, 'ff'.repeat(16), pt('ff'.repeat(16)), 250);
    const mismatch = await buildBackupBEvent(signer, 'aa'.repeat(16), pt('bb'.repeat(16)), 220);
    const good = await buildBackupBEvent(signer, d, pt(d, 'Right'), 100);
    const found = await findBackupB(fakePool([other, foreign, mismatch, good]), [], signer);
    expect(found.payload.tags[0].name).toBe('Right');
    expect(await findBackupB(fakePool([other]), [], signer)).toBeNull();
  });
  it('the newest of two backups wins, and reads at most 10 candidates', async () => {
    const d1 = randomLocator(); const d2 = randomLocator();
    const a = await buildBackupBEvent(signer, d1, pt(d1, 'First'), 100);
    const b = await buildBackupBEvent(signer, d2, pt(d2, 'Second'), 200);
    expect((await findBackupB(fakePool([a, b]), [], signer)).payload.tags[0].name).toBe('Second');
    let calls = 0;
    const counting = { ...signer, nip44Decrypt: async () => { calls++; throw new Error('no'); } };
    const many = [];
    for (let i = 0; i < 15; i++) many.push(await buildBackupBEvent(signer, randomLocator(), 'x', 100 + i));
    expect(await findBackupB(fakePool(many), [], counting)).toBeNull();
    expect(calls).toBeLessThanOrEqual(10);
  });
  it('reads write relays from a kind 10002 event', () => {
    expect(writeRelays({ tags: [['r', 'wss://a'], ['r', 'wss://b', 'read'], ['r', 'wss://c', 'write']] })).toEqual(['wss://a', 'wss://c']);
  });
});

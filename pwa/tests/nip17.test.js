import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { finalizeEvent, getPublicKey, verifyEvent } from 'nostr-tools/pure';
import { nip19 } from 'nostr-tools';
import { decrypt, encrypt, getConversationKey } from 'nostr-tools/nip44';
import { bytesToHex, hexToBytes } from '../src/lib/bytes.js';
import { KeyChain } from '../src/lib/lt2.js';
import { KIND_CHAT, KIND_GIFT_WRAP, KIND_SEAL, giftWrap, rumor, unwrap } from '../src/lib/nip17.js';
import { App } from '../src/lib/app.js';
import { welcomeWrap } from '../src/lib/welcome.js';

const X = JSON.parse(readFileSync(new URL('./vectors/nip17-examples.json', import.meta.url)));
const V44 = JSON.parse(readFileSync(new URL('./vectors/nip44.vectors.json', import.meta.url))).v2.valid;

describe('NIP-44 v2 official vectors (what the seal and the wrap are encrypted with)', () => {
  it('conversation keys', () => {
    for (const v of V44.get_conversation_key) expect(bytesToHex(getConversationKey(hexToBytes(v.sec1), v.pub2))).toBe(v.conversation_key);
  });
  it('encrypt with a given nonce, and decrypt', () => {
    for (const v of V44.encrypt_decrypt) {
      const ck = hexToBytes(v.conversation_key);
      expect(bytesToHex(getConversationKey(hexToBytes(v.sec1), getPublicKey(hexToBytes(v.sec2))))).toBe(v.conversation_key);
      expect(encrypt(v.plaintext, ck, hexToBytes(v.nonce))).toBe(v.payload);
      expect(decrypt(v.payload, ck)).toBe(v.plaintext);
    }
  });
});

describe('NIP-59 / NIP-17 official examples open', () => {
  it('NIP-59 example: the wrap opens to the seal, the seal to the rumor', () => {
    const { recipient_sk, author_sk, wrap, seal, content } = X.nip59;
    const sk = hexToBytes(recipient_sk);
    const opened = JSON.parse(decrypt(wrap.content, getConversationKey(sk, wrap.pubkey)));
    expect(opened.id).toBe(seal.id);
    expect(verifyEvent(opened)).toBe(true);
    const rumor = JSON.parse(decrypt(opened.content, getConversationKey(sk, opened.pubkey)));
    expect(rumor.content).toBe(content);
    expect(rumor.pubkey).toBe(getPublicKey(hexToBytes(author_sk)));
  });
  it('NIP-17 example: both wraps (receiver and sender copy) open with unwrap() to "Hola, que tal?"', () => {
    const sender = nip19.decode(X.nip17.sender_nsec).data;
    const receiver = nip19.decode(X.nip17.receiver_nsec).data;
    const [toReceiver, toSender] = X.nip17.wraps;
    const a = unwrap(toReceiver, receiver);
    expect(a.rumor.content).toBe(X.nip17.content);
    expect(a.rumor.kind).toBe(KIND_CHAT);
    expect(a.sender).toBe(getPublicKey(sender));
    expect(unwrap(toSender, sender).rumor.content).toBe(X.nip17.content);
    expect(unwrap(toReceiver, sender)).toBeNull(); // not for the sender's key
  });
});

describe('our gift wrap', () => {
  const chain = new KeyChain(hexToBytes('11'.repeat(32)));
  const ownerSk = hexToBytes('22'.repeat(32));
  const owner = getPublicKey(ownerSk);
  const now = 1_800_000_000;
  it('the welcome DM: kind 14 sealed by the tag dm key, wrapped by a one-time key, readable by the owner', () => {
    const { wrap, wrapSk, seal, rumor } = welcomeWrap(chain.dmSk, owner, 'Your LibreTag tag "Keys" is paired.', now);
    expect(wrap.kind).toBe(KIND_GIFT_WRAP);
    expect(seal.kind).toBe(KIND_SEAL);
    expect(verifyEvent(wrap) && verifyEvent(seal)).toBe(true);
    expect(wrap.tags).toEqual([['p', owner]]);
    expect(wrap.pubkey).toBe(getPublicKey(wrapSk));
    expect(wrap.pubkey).not.toBe(seal.pubkey);
    expect(seal.pubkey).toBe(getPublicKey(hexToBytes(chain.dmSk)));
    expect(seal.tags).toEqual([]);
    expect(rumor.sig).toBeUndefined();
    // timestamps up to two days in the past, never in the future
    for (const e of [wrap, seal]) { expect(e.created_at).toBeLessThanOrEqual(now); expect(e.created_at).toBeGreaterThanOrEqual(now - 2 * 86400); }
    const o = unwrap(wrap, ownerSk);
    expect(o.sender).toBe(seal.pubkey);
    expect(o.rumor.content).toBe('Your LibreTag tag "Keys" is paired.');
    expect(o.rumor.tags).toEqual([['p', owner]]);
    expect(o.rumor.created_at).toBe(now);
    // the relay sees nothing of the tag: no tag pubkey anywhere in the wrap
    expect(JSON.stringify(wrap)).not.toContain(seal.pubkey);
  });
  it('expiration goes on the seal and on the wrap', () => {
    const { wrap, seal } = giftWrap(chain.dmSk, owner, 'batt 9%', now, { expiration: now + 1209600 });
    expect(wrap.tags).toContainEqual(['expiration', String(now + 1209600)]);
    expect(seal.tags).toEqual([['expiration', String(now + 1209600)]]);
  });
  it('a rumor whose author is not the seal signer is refused (impersonation)', () => {
    const attacker = hexToBytes('33'.repeat(32));
    const r = rumor(chain.dmSk, owner, 'I am your tag', now); // claims the tag as author
    const seal = finalizeEvent({ kind: KIND_SEAL, created_at: now, tags: [], content: encrypt(JSON.stringify(r), getConversationKey(attacker, owner)) }, attacker);
    const wk = hexToBytes('44'.repeat(32));
    const wrap = finalizeEvent({ kind: KIND_GIFT_WRAP, created_at: now, tags: [['p', owner]], content: encrypt(JSON.stringify(seal), getConversationKey(wk, owner)) }, wk);
    expect(unwrap(wrap, ownerSk)).toBeNull();
    const ok = giftWrap(chain.dmSk, owner, 'x', now);
    expect(unwrap({ ...ok.wrap, content: 'AA' + ok.wrap.content.slice(2) }, ownerSk)).toBeNull();
  });
});

describe('sending the welcome message', () => {
  const chain = new KeyChain(hexToBytes('11'.repeat(32)));
  const owner = getPublicKey(hexToBytes('22'.repeat(32)));
  const run = async (lists) => {
    const app = new App();
    app.relays = ['wss://ours.example.com'];
    app.meta.owner = owner;
    const sent = [];
    app.pool = { querySync: async () => lists, publish: (urls, ev) => urls.map((u) => { sent.push([u, ev.kind]); return Promise.resolve('ok'); }),
      subscribeMany: () => ({ close() {} }), listConnectionStatus: () => new Map(), close() {} };
    const r = await app.sendWelcome({ k0: '11'.repeat(32), chain }, 'hi');
    return { r, sent };
  };
  it('owner with a 10050 list: gift wrap to those relays only, no kind 4', async () => {
    const { r, sent } = await run([{ kind: 10050, created_at: 1, pubkey: owner, tags: [['relay', 'wss://inbox.example.com']] }]);
    expect(r.nip17).toBe(true);
    expect(sent.filter((x) => x[1] === 1059)).toEqual([['wss://inbox.example.com', 1059]]);
    expect(sent.some((x) => x[1] === 4)).toBe(false);
  });
  it('without a 10050 list: NIP-04 to our relays and the read relays (transition)', async () => {
    const { r, sent } = await run([{ kind: 10002, created_at: 1, pubkey: owner, tags: [['r', 'wss://read.example.com']] }]);
    expect(r.nip17).toBe(false);
    expect(sent.filter((x) => x[1] === 4).map((x) => x[0])).toEqual(['wss://ours.example.com', 'wss://read.example.com']);
    expect(sent.some((x) => x[1] === 1059)).toBe(false);
  });
});

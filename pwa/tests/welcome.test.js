import { describe, expect, it } from 'vitest';
import { getPublicKey, verifyEvent } from 'nostr-tools/pure';
import { nip19 } from 'nostr-tools';
import { hexToBytes } from '../src/lib/bytes.js';
import { KeyChain } from '../src/lib/lt2.js';
import { MAX_EXTRA, dmTargets, followLinks, inboxRelays, isPublicWss, ownerHex, profileEvent } from '../src/lib/welcome.js';

const ev = (kind, at, tags, pubkey = 'aa') => ({ kind, created_at: at, tags, pubkey });

describe('welcome DM relays', () => {
  it('inbox: newest 10050 then newest 10002 read relays, public wss only', () => {
    const evs = [
      ev(10002, 100, [['r', 'wss://old.example.com']]),
      ev(10002, 200, [['r', 'wss://read.example.com/'], ['r', 'wss://write.example.com', 'write'], ['r', 'wss://marked.example.com', 'read'],
        ['r', 'ws://plain.example.com'], ['r', 'wss://localhost:7777'], ['r', 'wss://192.168.1.5']]),
      ev(10050, 150, [['relay', 'wss://inbox.example.com'], ['relay', 'wss://read.example.com']]),
      ev(10050, 300, [['relay', 'wss://other.example.com']], 'bb'),
    ];
    expect(inboxRelays(evs, 'aa')).toEqual(['wss://inbox.example.com', 'wss://read.example.com', 'wss://marked.example.com']);
  });
  it('targets: ours, then the owner inbox relays we lack (at most MAX_EXTRA)', () => {
    const ours = ['wss://nostr.cypherpunk.today', 'wss://relay.primal.net', 'wss://nos.lol'];
    const inbox = ['wss://relay.primal.net', 'wss://nos.lol', 'wss://nostr.cypherpunk.today', 'wss://auth.nostr1.com', 'wss://relay.damus.io'];
    expect(dmTargets(ours, inbox)).toEqual([...ours, 'wss://auth.nostr1.com', 'wss://relay.damus.io']);
    expect(dmTargets(['wss://a.example.com/', 'wss://a.example.com'], ['wss://a.example.com'])).toEqual(['wss://a.example.com']);
    const many = Array.from({ length: 10 }, (_, i) => `wss://r${i}.example.com`);
    expect(dmTargets(['wss://own.example.com'], many)).toHaveLength(1 + MAX_EXTRA);
  });
  it('public hosts', () => {
    expect(isPublicWss('wss://nos.lol')).toBe(true);
    expect(isPublicWss('wss://10.0.0.2')).toBe(false);
    expect(isPublicWss('wss://relay.local')).toBe(false);
  });
});

describe('welcome DM event', () => {
  const chain = new KeyChain(hexToBytes('11'.repeat(32)));
  const owner = getPublicKey(hexToBytes('22'.repeat(32)));
  it('profile: name, about and picture only, same bytes as the Android app', () => {
    const p = profileEvent(chain.dmSk, 1);
    expect(p.kind).toBe(0);
    expect(Object.keys(JSON.parse(p.content)).sort()).toEqual(['about', 'name', 'picture']);
    expect(p.content).toBe('{"name":"LibreTag","about":"Messages from your LibreTag tag (battery alerts).","picture":"https://cypherpunk.today/libretag/app/icon-512.png"}');
  });
  it('follow links carry the npub of the tag dm key (the DM sender)', () => {
    const f = followLinks(chain.dmSk);
    expect(nip19.decode(f.npub).data).toBe(getPublicKey(hexToBytes(chain.dmSk)));
    expect(f.app).toBe('nostr:' + f.npub);
    expect(f.web).toBe('https://njump.me/' + f.npub);
  });
  it('owner from npub or hex', () => {
    expect(ownerHex(nip19.npubEncode(owner))).toBe(owner);
    expect(ownerHex(owner.toUpperCase())).toBe(owner);
    expect(ownerHex('nonsense')).toBe(null);
    expect(ownerHex(null)).toBe(null);
  });
});

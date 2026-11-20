import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { verifyEvent } from 'nostr-tools/pure';
import { bytesToHex, hexToBytes } from '../src/lib/bytes.js';
import * as B from '../src/lib/backup.js';

const V = JSON.parse(readFileSync(new URL('../../docs/vectors/key-backup-v1.json', import.meta.url)));

describe('key backup v1 vector', () => {
  let derived;
  it('normalises the passphrase', () => {
    expect(B.normalizePassphrase(V.passphrase)).toBe(V.normalized);
  });
  it('derives S, d, sk, pubkey, kEnc', async () => {
    derived = await B.derive(V.passphrase);
    expect(bytesToHex(derived.S)).toBe(V.S);
    expect(derived.d).toBe(V.d);
    expect(bytesToHex(derived.sk)).toBe(V.sk);
    expect(derived.pubkey).toBe(V.pubkey);
    expect(bytesToHex(derived.kEnc)).toBe(V.kEnc);
  });
  it('reproduces content, event id, filter and deletion tag', async () => {
    const nonce = hexToBytes(V.nonce);
    expect(await B.encryptBackup(derived, V.plaintext, nonce)).toBe(V.content);
    const ev = await B.buildBackupEvent(derived, V.plaintext, V.created_at, nonce);
    expect(ev.id).toBe(V.event_id);
    expect(verifyEvent(ev)).toBe(true);
    expect(B.backupFilter(derived)).toEqual(V.filter);
    expect(B.deletionA(derived)).toBe(V.deletion_a);
  });
  it('decrypts the vector and restores both tags', async () => {
    expect(await B.decryptBackup(derived, V.content)).toBe(V.plaintext);
    const ev = await B.buildBackupEvent(derived, V.plaintext, V.created_at, hexToBytes(V.nonce));
    const r = await B.restore(derived, [ev]);
    expect(r.state).toBe('found');
    expect(r.payload.tags.map((t) => t.name)).toEqual(['Car', 'Backpack']);
    expect(r.payload.tags[0].k0).toBe('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f');
    expect(r.payload.owner).toMatch(/^npub1/);
  });
  it('a wrong passphrase finds nothing readable (tag fails)', async () => {
    const other = await B.derive('Granite owl counts seven fig', { N: 1 << 10 });
    expect(await B.decryptBackup(other, V.content)).toBeNull();
  });
  it('rejects short passphrases', () => {
    for (const p of V.short_passphrases) expect(B.passphraseStrength(p)).toBe('too_short');
    expect(B.passphraseStrength(V.passphrase)).toBe('ok');
  });
  it('newest wins, empty content means deleted, ties go to the lowest id', async () => {
    const mk = (content, at) => B.buildBackupEvent(derived, content, at, hexToBytes(V.nonce));
    const older = await mk(V.plaintext, 100);
    const del = (await import('nostr-tools/pure')).finalizeEvent(
      { kind: 30078, created_at: 200, content: '', tags: [['d', derived.d]] }, derived.sk);
    expect((await B.restore(derived, [older, del])).state).toBe('deleted');
    expect((await B.restore(derived, [del, older])).state).toBe('deleted');
    expect((await B.restore(derived, [])).state).toBe('none');
  });
});

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { verifyEvent } from 'nostr-tools/pure';
import { bytesToHex, hexToBytes } from '../src/lib/bytes.js';
import * as B from '../src/lib/backup.js';
import { checkMnemonic, normalizeMnemonic } from '../src/lib/mnemonic.js';
import { parsePlaintext } from '../src/lib/merge.js';

const V = JSON.parse(readFileSync(new URL('../../docs/vectors/key-backup-v1.json', import.meta.url)));
const X = V.bip39;

describe('key-backup-v1.json bip39 case (SPEC 12.2 A)', () => {
  let d;
  it('normalises and validates the input phrase', () => {
    expect(normalizeMnemonic(X.input)).toBe(X.mnemonic);
    expect(checkMnemonic(X.input)).toBe('ok');
  });
  it('derives every field', async () => {
    d = await B.derive(normalizeMnemonic(X.input));
    expect(bytesToHex(d.S)).toBe(X.S);
    expect(d.d).toBe(X.d);
    expect(bytesToHex(d.sk)).toBe(X.sk);
    expect(d.pubkey).toBe(X.pubkey);
    expect(bytesToHex(d.kEnc)).toBe(X.kEnc);
  });
  it('reproduces content, event id, filter and deletion tag, and restores the v1.1 plaintext', async () => {
    expect(await B.encryptBackup(d, X.plaintext, hexToBytes(X.nonce))).toBe(X.content);
    const ev = await B.buildBackupEvent(d, X.plaintext, X.created_at, hexToBytes(X.nonce));
    expect(ev.id).toBe(X.event_id);
    expect(verifyEvent(ev)).toBe(true);
    expect(B.backupFilter(d)).toEqual(X.filter);
    expect(B.deletionA(d)).toBe(X.deletion_a);
    const r = await B.restore(d, [ev]);
    expect(r.state).toBe('found');
    expect(r.payload.d).toBe('0f1e2d3c4b5a69788796a5b4c3d2e1f0');
    expect(r.payload.tags[0]).toMatchObject({ name: 'Keys', updated: 1800000500 });
    expect(r.payload.tombstones).toEqual([{ k0: 'ee'.repeat(32), removed: 1800000300 }]);
    expect(parsePlaintext(X.plaintext).owner).toMatch(/^npub1/);
  });
  it('refuses the invalid cases', () => {
    for (const [kind, list] of Object.entries(V.bip39_invalid)) for (const m of list) {
      expect(checkMnemonic(m), `${kind}: ${m}`).not.toBe('ok');
    }
  });
});

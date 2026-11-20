import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { verifyEvent } from 'nostr-tools/pure';
import { b64decode, bytesToHex, hexToBytes } from '../src/lib/bytes.js';
import * as L from '../src/lib/lt2.js';
import {
  ANCHOR_AHEAD, anchorCounters, anchorEntries, anchorTtlS, buildCommand, cancelEvents, keyFor, lateReport, mailboxEntries,
  mailboxEvents, newGroup, nextSeq, pruneAnchors, sentOf, signEntry,
} from '../src/lib/mailbox.js';

const V = JSON.parse(readFileSync(new URL('../../host/tests/vectors/lt2.json', import.meta.url)));
const chain = new L.KeyChain(hexToBytes(V.k0));
const hexOf = (b) => bytesToHex(b);
const pkOf = (n) => L.mailboxPk(chain.cmd(L.epoch(n)), n);
const pos = (o) => ({ n: 200, receivedAt: 1_800_000_000_000, hasFix: true, lat: 48.7, lon: 21.25, fixTime: 1, battery: 80,
  mode: 'IDLE', cmdAck: false, cmdSeq: 0, quiet: false, checks: false, nextInS: 3600, ...o });
const NOW = 1_800_000_000;

describe('commands as the app builds them', () => {
  it('Lost, Track, Beep and Normal bodies', () => {
    expect(hexOf(buildCommand('LOST', 1))).toBe('220101035802a005000000000000000000');
    expect(hexOf(buildCommand('BEEP', 2))).toBe('2202040a00000000000000000000000000');
    expect(hexOf(buildCommand('NORMAL', 3))).toBe('2203010000000000000000000000000000');
    expect(hexOf(buildCommand('TRACK', 4))).toBe('2204010' + '12c013c00000000000000000000');
  });
  it('picks a sequence that is not the one the tag last executed', () => {
    const tag = { cmdSeq: 4, positions: [pos({ cmdSeq: 5 })] };
    expect(nextSeq(tag) & 15).not.toBe(5);
    expect(nextSeq({ cmdSeq: 255, positions: [] })).toBe(1);
  });
});

describe('mailbox events (signed mailbox, PROTOCOL.md 4.2)', () => {
  it('seals the command for the next four reports, each signed by sk_n of its report', async () => {
    const tag = { chain, lastN: 200, positions: [pos()] };
    const body = buildCommand('LOST', 7);
    const evs = await mailboxEvents(tag, { kind: 'LOST', body: hexOf(body) }, NOW + 3600);
    expect(evs).toHaveLength(4);
    for (const [i, ev] of evs.entries()) {
      const n = 201 + i;
      expect(ev.kind).toBe(4244);
      expect(verifyEvent(ev)).toBe(true);
      expect(ev.pubkey).toBe(pkOf(n));
      expect(ev.n).toBe(n);
      const ys = ev.tags.filter((t) => t[0] === 'y').map((t) => t[1]);
      expect(ys).toEqual([0, 1].map((j) => hexOf(L.reportTag(chain.view(L.epoch(n)), n, j))));
      expect(ev.tags).toContainEqual(['v', '2']);
      expect(ev.tags).toContainEqual(['route', 'flood']);
      expect(ev.tags.some((t) => t[0] === 'p' || t[0] === 'r' || t[0] === 'nonce')).toBe(false);
      expect(ev.tags).toContainEqual(['expiration', String(NOW + 3600 + 48 * 3600)]);
      const opened = await L.openCommand(chain.cmd(L.epoch(n)), n, b64decode(ev.content));
      expect(hexOf(L.encodeCommand(opened.seq, opened.op, opened.args))).toBe(hexOf(body));
    }
  });
  it('the key fits the commitment of the real report', async () => {
    const n = 201;
    const p = await L.sealReportChain(chain, n, 0, hexToBytes(V.reports[0].pt));
    const ev = (await mailboxEvents({ chain, lastN: 200, positions: [pos()] }, { kind: 'BEEP', body: hexOf(buildCommand('BEEP', 1)) }, NOW + 3600))[0];
    expect(hexOf(L.reportCommit(hexToBytes(ev.pubkey), p))).toBe(hexOf(p.slice(4, 7)));
  });
  it('adds timed entries for the next checks of a quiet stretch, signed by sk_n of report via', async () => {
    const t0 = 1_800_000_000_000;
    const tag = { chain, lastN: 201, intervalMin: 60,
      positions: [pos({ n: 201, receivedAt: t0, quiet: true, quietReason: 'zone', checks: true, nextInS: 28800 })] };
    const body = buildCommand('BEEP', 9);
    const g = newGroup();
    const evs = await mailboxEntries(tag, { kind: 'BEEP', body: hexOf(body), g }, t0 / 1000 + 600);
    const timed = evs.filter((e) => e.tags.some((t) => t[0] === 'at'));
    expect(evs.slice(0, 4).every((e) => e.at === null)).toBe(true);
    expect(timed.map((e) => e.at)).toEqual([...timed.map((e) => e.at)].sort((a, b) => a - b));
    for (const e of evs) expect(e.tags.filter((x) => x[0] === 'g')).toEqual([['g', g]]);
    expect(timed.length).toBeGreaterThan(5);
    expect(timed.length).toBeLessThanOrEqual(12);
    const ce = chain.cmd(L.epoch(201));
    const offs = L.checkOffsets(ce, 201, 3600, 12, 28800);
    const first = timed[0];
    expect(first.n).toBe(201);
    expect(first.tags).toContainEqual(['at', String(t0 / 1000 + offs[0] + 3)]);
    expect(first.tags).toContainEqual(['via', hexOf(L.reportTag(chain.view(L.epoch(201)), 201, 0))]);
    expect(first.tags).toContainEqual(['y', hexOf(L.commandTag(ce, 201, 1))]);
    expect(await L.openCommand(ce, 201, b64decode(first.content), 1)).not.toBeNull();
    const ev = signEntry(first, chain, t0 / 1000 + 600);
    expect(verifyEvent(ev)).toBe(true);
    expect(ev.pubkey).toBe(pkOf(201));
  });
  it('without command keys (a view-only share) nothing is sent and no key exists', async () => {
    const share = L.KeyChain.fromShare(3, chain.view(3), null);
    expect(await mailboxEvents({ chain: share, lastN: 200, positions: [] }, { kind: 'BEEP', body: '00' })).toEqual([]);
    expect(keyFor(share, 201)).toBeNull();
    expect(anchorEntries({ chain: share, lastN: 200, positions: [] }, {}, NOW)).toEqual([]);
  });
});

describe('late entry (PROTOCOL.md 4.2)', () => {
  const body = hexOf(buildCommand('BEEP', 5));
  it('a command queued inside the listen window also goes out for the report just heard', async () => {
    const tag = { chain, lastN: 200, positions: [pos({ receivedAt: (NOW - 30) * 1000 })] };
    const evs = await mailboxEvents(tag, { kind: 'BEEP', body }, NOW);
    expect(evs.map((e) => e.n)).toEqual([200, 201, 202, 203, 204]);
    expect(evs[0].pubkey).toBe(pkOf(200));
    expect(evs[0].tags.filter((t) => t[0] === 'y').map((t) => t[1]))
      .toEqual([0, 1].map((j) => hexOf(L.reportTag(chain.view(L.epoch(200)), 200, j))));
    expect(await L.openCommand(chain.cmd(L.epoch(200)), 200, b64decode(evs[0].content))).not.toBeNull();
  });
  it('not after 90 s, and not for a report with an approximate time', async () => {
    expect(lateReport({ lastN: 200, positions: [pos({ receivedAt: (NOW - 89) * 1000 })] }, NOW)).not.toBeNull();
    expect(lateReport({ lastN: 200, positions: [pos({ receivedAt: (NOW - 90) * 1000 })] }, NOW)).toBeNull();
    expect(lateReport({ lastN: 200, positions: [pos({ receivedAt: (NOW - 10) * 1000, approxUntil: NOW * 1000 })] }, NOW)).toBeNull();
    const evs = await mailboxEvents({ chain, lastN: 200, positions: [pos({ receivedAt: (NOW - 120) * 1000 })] }, { kind: 'BEEP', body }, NOW);
    expect(evs.map((e) => e.n)).toEqual([201, 202, 203, 204]);
  });
});

describe('anchors (PROTOCOL.md 4.2)', () => {
  it('for the next 8 counters and the next epoch start, empty content, signed by sk_n', () => {
    const tag = { chain, lastN: 200, positions: [pos({ nextInS: 3600 })] };
    expect(anchorCounters(tag)).toEqual([201, 202, 203, 204, 205, 206, 207, 208, 256]);
    const es = anchorEntries(tag, {}, NOW, () => 0.5);
    expect(es).toHaveLength(ANCHOR_AHEAD + 1);
    for (const e of es) {
      const ev = signEntry(e, chain, NOW);
      expect(verifyEvent(ev)).toBe(true);
      expect(ev.content).toBe('');
      expect(ev.pubkey).toBe(pkOf(e.n));
      expect(ev.created_at).toBe(NOW - 150);
      expect(ev.tags).toEqual([['v', '2'], ...[0, 1].map((j) => ['y', hexOf(L.reportTag(chain.view(L.epoch(e.n)), e.n, j))]),
        ['expiration', String(NOW + 3 * 3600)]]);
    }
    // the next epoch start inside the 8 ahead is not doubled
    expect(anchorCounters({ lastN: 250 })).toEqual([251, 252, 253, 254, 255, 256, 257, 258]);
    expect(anchorCounters({ lastN: -1 })).toEqual([]);
  });
  it('lifetime: three report intervals, 2 h to 26 h, 12 h when unknown', () => {
    expect(anchorTtlS({ positions: [pos({ nextInS: 600 })] })).toBe(2 * 3600);
    expect(anchorTtlS({ positions: [pos({ nextInS: 3600 })] })).toBe(3 * 3600);
    expect(anchorTtlS({ positions: [pos({ nextInS: 86400 })] })).toBe(26 * 3600);
    expect(anchorTtlS({ positions: [pos({ nextInS: null })] })).toBe(12 * 3600);
  });
  it('refreshed only when missing or past half their life; pruned once reported', () => {
    const tag = { chain, lastN: 200, positions: [pos({ nextInS: 3600 })] };
    const have = {};
    for (const e of anchorEntries(tag, have, NOW)) have[e.n] = NOW + 3 * 3600;
    expect(anchorEntries(tag, have, NOW + 3600)).toEqual([]);           // 2 h left of 3 h
    expect(anchorEntries(tag, have, NOW + 5400 + 1).length).toBe(9);    // under half left
    const next = { ...tag, lastN: 201 };
    const pruned = pruneAnchors(have, 201, NOW + 60);
    expect(Object.keys(pruned).map(Number)).not.toContain(201);
    expect(anchorEntries(next, pruned, NOW + 60).map((e) => e.n)).toEqual([209]);
  });
});

describe('cancelling a command (NIP-09 with the derived mailbox keys)', () => {
  it('signs a kind 5 with sk_n of each event, without storing any key', async () => {
    const tag = { chain, lastN: 200, positions: [pos({ receivedAt: (NOW - 600) * 1000 })] };
    const evs = await mailboxEvents(tag, { kind: 'BEEP', body: hexOf(buildCommand('BEEP', 3)) }, NOW);
    const sent = JSON.parse(JSON.stringify(sentOf(evs)));
    expect(sent).toHaveLength(4);
    expect(sent.every((x) => !('sk' in x) && x.n >= 201)).toBe(true);
    const dels = cancelEvents(sent, chain, NOW + 100);
    expect(dels).toHaveLength(4);
    dels.forEach((d, i) => {
      expect(d.kind).toBe(5);
      expect(d.pubkey).toBe(evs[i].pubkey);
      expect(d.tags).toEqual([['e', evs[i].id], ['k', '4244'], ['expiration', String(NOW + 3600)]]);
      expect(verifyEvent(d)).toBe(true);
    });
    expect(cancelEvents(sent, chain, NOW + 7200)).toHaveLength(0);       // expired: nothing to delete
    expect(cancelEvents(sent, L.KeyChain.fromShare(3, chain.view(3)), NOW + 100)).toHaveLength(0);
    expect(cancelEvents([{ id: 'x', sk: '11'.repeat(32), exp: 0, ys: [] }], chain, NOW)).toHaveLength(0); // old record
  });
  it('group id is 16 lowercase hex and new for every command', () => {
    const a = newGroup(), b = newGroup();
    expect(a).toMatch(/^[0-9a-f]{16}$/);
    expect(a).not.toBe(b);
  });
});

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { verifyEvent } from 'nostr-tools/pure';
import { b64decode, bytesToHex, hexToBytes } from '../src/lib/bytes.js';
import * as L from '../src/lib/lt2.js';
import * as Q from '../src/lib/quietmesh.js';
import {
  ANCHOR_AHEAD, anchorCounters, anchorEntries, anchorTtlS, buildCommand, cancelEvents, keyFor, lateReport, mailboxEntries,
  mailboxEvents, newGroup, nextSeq, pruneAnchors, sentOf, signEntry,
} from '../src/lib/mailbox.js';

const V = JSON.parse(readFileSync(new URL('../../host/tests/vectors/lt2.json', import.meta.url)));
const chain = new L.KeyChain(hexToBytes(V.k0));
const hexOf = (b) => bytesToHex(b);
const pkOf = (n) => L.mailboxPk(chain.cmd(L.epoch(n)), n);
const pos = (o) => ({ n: 200, receivedAt: 1_800_000_000_000, hasFix: true, lat: 48.7, lon: 21.25, fixTime: 1, battery: 80,
  mode: 'IDLE', cmdAck: false, cmdSeq: 0, quiet: false, sync: false, nextInS: 3600, ...o });
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
    // R6: random, unlike the tag's last executed bits and this browser's previous command
    for (let i = 0; i < 200; i++) {
      const s2 = nextSeq({ cmdSeq: 0x31, positions: [pos({ cmdSeq: 2 })] });
      expect(s2).toBeGreaterThanOrEqual(1); expect(s2).toBeLessThanOrEqual(255);
      expect(s2 & 15).not.toBe(2); expect(s2 & 15).not.toBe(1);
    }
    expect(nextSeq({ cmdSeq: 0x31, positions: [pos({ cmdSeq: 2 })] }, () => 0) & 15).not.toBe(1);
  });
});

describe('mailbox events (signed mailbox, PROTOCOL.md 4.2)', () => {
  it('seals the command for the next ten reports, each signed by sk_n of its report', async () => {
    const tag = { chain, lastN: 200, positions: [pos()] };
    const body = buildCommand('LOST', 7);
    const evs = await mailboxEvents(tag, { kind: 'LOST', body: hexOf(body) }, NOW + 3600);
    expect(evs).toHaveLength(10);
    for (const [i, ev] of evs.entries()) {
      const n = 201 + i;
      expect(ev.kind).toBe(4244);
      expect(verifyEvent(ev)).toBe(true);
      expect(ev.pubkey).toBe(pkOf(n));
      expect(ev.n).toBe(n);
      const ys = ev.tags.filter((t) => t[0] === 'y').map((t) => t[1]);
      expect(ys).toEqual(L.J_ON_AIR.map((j) => hexOf(L.reportTag(chain.view(L.epoch(n)), n, j))));
      expect(ev.tags).toContainEqual(['v', '2']);
      expect(ev.tags).toContainEqual(['route', 'both']); // Lost and Track ask for the fallback after a zero-hop send
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
  it('adds timed entries for the next slots of the anchor, sealed from its slot key, signed by sk of the anchor', async () => {
    const t0 = 1_800_000_000_000;
    const tag = { chain, lastN: 201, intervalMin: 60, anchor: { a: { n: 201, t0, vias: ['aabbccdd'] }, cands: [], lastN: 201, seen: {}, known: true },
      positions: [pos({ n: 201, receivedAt: t0, quiet: true, quietReason: 'zone', nextInS: 28800 })] };
    const body = buildCommand('BEEP', 9);
    const g = newGroup();
    const evs = await mailboxEntries(tag, { kind: 'BEEP', body: hexOf(body), g, queuedAt: t0 + 600_000 }, t0 / 1000 + 600);
    const timed = evs.filter((e) => e.tags.some((t) => t[0] === 'at'));
    expect(evs.slice(0, 10).every((e) => e.at === null)).toBe(true);
    expect(timed.map((e) => e.at)).toEqual([...timed.map((e) => e.at)].sort((a, b) => a - b));
    for (const e of evs) expect(e.tags.filter((x) => x[0] === 'g')).toEqual([['g', g]]);
    expect(timed.length).toBe(1); // a Beep lives 1 h: only slot 1 falls inside it
    const ks = Q.slotKey(chain.cmd(L.epoch(201)), 201);
    const offs = Q.slotOffsets(ks, 3600, 3);
    const first = timed[0];
    expect(first.n).toBe(201);
    expect(first.tags).toContainEqual(['at', String(t0 / 1000 + offs[0] + 1)]);
    expect(first.tags).toContainEqual(['via', 'aabbccdd']);
    expect(first.tags).toContainEqual(['y', hexOf(Q.slotCommandTag(ks, 1))]);
    expect(first.tags).toContainEqual(['route', 'flood']);
    expect(await Q.openSlotCommand(ks, 1, b64decode(first.content))).not.toBeNull();
    const ev = signEntry(first, chain, t0 / 1000 + 600);
    expect(verifyEvent(ev)).toBe(true);
    expect(ev.pubkey).toBe(pkOf(201));
    // Lost lives 48 h: the next 3 slots, then every 3rd, at most 12; later slots ask for the fallback (route both)
    const lost = (await mailboxEntries(tag, { kind: 'LOST', body: hexOf(buildCommand('LOST', 9)), queuedAt: t0 + 600_000 }, t0 / 1000 + 600))
      .filter((e) => e.at != null);
    expect(lost.length).toBe(12);
    expect(lost.every((e) => e.tags.some((t) => t[0] === 'route' && t[1] === 'both'))).toBe(true);
    expect(lost.some((e) => e.tags.some((t) => t[0] === 'fb'))).toBe(false);
  });
  it('a candidate grid gets its own entries, only the latest of one try keeps the fallback', async () => {
    const t0 = 1_800_000_000_000;
    const tag = { chain, lastN: 202, intervalMin: 60, positions: [pos({ n: 202, receivedAt: t0 + 3600_000 })],
      anchor: { a: { n: 201, t0 }, cands: [{ n: 202, t0: t0 + 3600_000 }], lastN: 202, seen: {}, known: true } };
    const evs = await mailboxEntries(tag, { kind: 'BEEP', body: hexOf(buildCommand('BEEP', 9)), queuedAt: t0 + 3700_000 }, t0 / 1000 + 3700);
    const timed = evs.filter((e) => e.at != null);
    const byTry = timed.filter((e) => e.tags.some((t) => t[0] === 'fb'));
    expect(byTry.length).toBeGreaterThan(0);
    expect(new Set(timed.map((e) => e.n))).toEqual(new Set([201, 202]));
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
    expect(evs.map((e) => e.n)).toEqual([200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210]);
    expect(evs[0].pubkey).toBe(pkOf(200));
    expect(evs[0].tags.filter((t) => t[0] === 'y').map((t) => t[1]))
      .toEqual(L.J_ON_AIR.map((j) => hexOf(L.reportTag(chain.view(L.epoch(200)), 200, j))));
    expect(await L.openCommand(chain.cmd(L.epoch(200)), 200, b64decode(evs[0].content))).not.toBeNull();
  });
  it('not after 40 s (P6), and not for a report with an approximate time', async () => {
    expect(lateReport({ lastN: 200, positions: [pos({ receivedAt: (NOW - 39) * 1000 })] }, NOW)).not.toBeNull();
    expect(lateReport({ lastN: 200, positions: [pos({ receivedAt: (NOW - 40) * 1000 })] }, NOW)).toBeNull();
    expect(lateReport({ lastN: 200, positions: [pos({ receivedAt: (NOW - 10) * 1000, approxUntil: NOW * 1000 })] }, NOW)).toBeNull();
    const evs = await mailboxEvents({ chain, lastN: 200, positions: [pos({ receivedAt: (NOW - 120) * 1000 })] }, { kind: 'BEEP', body }, NOW);
    expect(evs.map((e) => e.n)).toEqual([201, 202, 203, 204, 205, 206, 207, 208, 209, 210]);
  });
});

describe('anchors (PROTOCOL.md 4.2)', () => {
  it('for the next 16 counters, the two reboot checkpoints and the next epoch start, empty content, signed by sk_n', () => {
    const tag = { chain, lastN: 200, positions: [pos({ nextInS: 3600 })] };
    const want = [...Array(16).keys()].map((i) => 201 + i).concat([224, 256]);
    expect(anchorCounters(tag)).toEqual(want);
    const es = anchorEntries(tag, {}, NOW, () => 0.5);
    expect(es).toHaveLength(want.length);
    for (const e of es) {
      const ev = signEntry(e, chain, NOW);
      expect(verifyEvent(ev)).toBe(true);
      expect(ev.content).toBe('');
      expect(ev.pubkey).toBe(pkOf(e.n));
      expect(ev.created_at).toBe(NOW - 150);
      expect(ev.tags).toEqual([['v', '2'], ...L.J_ON_AIR.map((j) => ['y', hexOf(L.reportTag(chain.view(L.epoch(e.n)), e.n, j))]),
        ['expiration', String(NOW + 16 * 3600)]]);
    }
    // the checkpoints and the next epoch start inside the 16 ahead are not doubled
    expect(anchorCounters({ lastN: 250, positions: [] })).toEqual([...Array(16).keys()].map((i) => 251 + i).concat([272]));
    expect(anchorCounters({ lastN: -1, positions: [] })).toEqual([]);
    // Lost and Track: 24 ahead (201..224 holds both checkpoints), then the epoch
    expect(anchorCounters({ lastN: 200, positions: [pos({ mode: 'LOST' })] }).length).toBe(24 + 1);
  });
  it('lifetime: covers the counters ahead, 2 h to 50 h, 12 h when unknown', () => {
    expect(anchorTtlS({ positions: [pos({ nextInS: 300 })] })).toBe(2 * 3600);
    expect(anchorTtlS({ positions: [pos({ nextInS: 3600 })] })).toBe(16 * 3600);
    expect(anchorTtlS({ positions: [pos({ nextInS: 86400 })] })).toBe(50 * 3600);
    expect(anchorTtlS({ positions: [pos({ nextInS: null })] })).toBe(12 * 3600);
  });
  it('refreshed only when missing or past half their life; pruned once reported', () => {
    const tag = { chain, lastN: 200, positions: [pos({ nextInS: 3600 })] };
    const have = {};
    for (const e of anchorEntries(tag, have, NOW)) have[e.n] = NOW + 16 * 3600;
    expect(anchorEntries(tag, have, NOW + 3600)).toEqual([]);              // 15 h left of 16 h
    expect(anchorEntries(tag, have, NOW + 8 * 3600 + 1).length).toBe(18);  // under half left
    const next = { ...tag, lastN: 201 };
    const pruned = pruneAnchors(have, 201, NOW + 60);
    expect(Object.keys(pruned).map(Number)).not.toContain(201);
    expect(anchorEntries(next, pruned, NOW + 60).map((e) => e.n)).toEqual([217]);
  });
});

describe('cancelling a command (NIP-09 with the derived mailbox keys)', () => {
  it('signs a kind 5 with sk_n of each event, without storing any key', async () => {
    const tag = { chain, lastN: 200, positions: [pos({ receivedAt: (NOW - 600) * 1000 })] };
    const evs = await mailboxEvents(tag, { kind: 'BEEP', body: hexOf(buildCommand('BEEP', 3)) }, NOW);
    const sent = JSON.parse(JSON.stringify(sentOf(evs)));
    expect(sent).toHaveLength(10);
    expect(sent.every((x) => !('sk' in x) && x.n >= 201)).toBe(true);
    const dels = cancelEvents(sent, chain, NOW + 100);
    expect(dels).toHaveLength(10);
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

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { hexToBytes } from '../src/lib/bytes.js';
import * as L from '../src/lib/lt2.js';
import { setLang } from '../src/lib/i18n.js';
import { checkPlan, checkSOf, inferIntervalMin, intervalMinOf, reportEveryOf, nextAt, nextCheck, positionText, slip, sortTags, statusOf, statusText, statusSub, timelineDays, queuedText } from '../src/lib/status.js';

const V = JSON.parse(readFileSync(new URL('../../host/tests/vectors/lt2.json', import.meta.url)));
const chain = new L.KeyChain(hexToBytes(V.k0));
const HOUR = 3_600_000;
const NOW = 1_800_000_000_000;
const pos = (o) => ({ n: 1, receivedAt: NOW - 12 * 60_000, hasFix: true, lat: 48.7, lon: 21.25, fixTime: NOW / 1000 - 720, haccM: 8, battery: 80,
  mode: 'IDLE', cmdAck: false, cmdSeq: 0, lowBatt: false, quiet: false, quietReason: null, checks: false, nextInS: 3600, nbTotal: 2, ...o });
const tag = (positions, o = {}) => ({ id: 'a', name: 'Car', type: 'vehicle', mark: { color: 'red', symbol: 'stripe' }, chain, positions, lastN: 1, ...o });

setLang('en');

describe('status priority and sentences', () => {
  it('OK: heard 12 min ago, next from next_in', () => {
    const t = tag([pos()]);
    const s = statusOf(t, NOW);
    expect(s.kind).toBe('OK');
    expect(nextAt(t, NOW)).toBe(NOW - 12 * 60_000 + HOUR);
    expect(statusText(t, s, NOW)).toMatch(/^Heard 12 min ago \(\d\d:\d\d\) · next ~/);
  });
  it('SILENT after 30 min past the deadline, with the missed count', () => {
    const t = tag([pos({ receivedAt: NOW - 4 * HOUR })]);
    const s = statusOf(t, NOW);
    expect(s.kind).toBe('SILENT');
    expect(statusText(t, s, NOW)).toBe('Silent for 4 h · expected hourly');
    expect(statusSub(t, s, NOW)).toMatch(/4 reports missed\.$/);
  });
  it('LOST beats SILENT beats LOW_BATTERY; low battery from the flag or <= 20 %', () => {
    expect(statusOf(tag([pos({ mode: 'LOST', nextInS: 600 })]), NOW).kind).toBe('LOST');
    expect(statusOf(tag([pos({ battery: 18 })]), NOW).kind).toBe('LOW_BATTERY');
    expect(statusOf(tag([pos({ lowBatt: true, battery: 90 })]), NOW).kind).toBe('LOW_BATTERY');
    expect(statusOf(tag([pos({ mode: 'TRACK', nextInS: 300 })]), NOW).kind).toBe('TRACKING');
  });
  it('quiet stays quiet until its heartbeat, then it is silent', () => {
    const q = pos({ quiet: true, quietReason: 'zone', nextInS: 86400, receivedAt: NOW - 3 * HOUR });
    expect(statusOf(tag([q]), NOW).kind).toBe('QUIET');
    expect(statusOf(tag([{ ...q, receivedAt: NOW - 25 * HOUR }]), NOW).kind).toBe('SILENT');
  });
  it('sorts Lost and Silent to the top', () => {
    const a = tag([pos()], { id: 'ok' });
    const b = tag([pos({ receivedAt: NOW - 5 * HOUR })], { id: 'silent' });
    const c = tag([pos({ mode: 'LOST', nextInS: 600 })], { id: 'lost' });
    expect(sortTags([a, b, c], NOW).map((x) => x.id)).toEqual(['lost', 'silent', 'ok']);
  });
});

describe('position sentences are honest about the web version', () => {
  it('GPS with accuracy, last GPS when old, no area estimate on the web', () => {
    expect(positionText(pos(), true, NOW)).toBe('GPS ±8 m · 48.70000, 21.25000');
    expect(positionText(pos({ receivedAt: NOW - 3 * HOUR, fixTime: NOW / 1000 - 3 * 3600 }), true, NOW)).toMatch(/^Last GPS 3 h ago \((yesterday )?\d\d:\d\d\) · /);
    expect(positionText(pos({ hasFix: false, fixTime: 0, nbTotal: 3 }), true, NOW)).toBe('No GPS · area estimate is not available on the web');
    expect(positionText(pos({ hasFix: false, fixTime: 0, nbTotal: 0 }), true, NOW)).toBe('No GPS · no repeater heard');
    expect(positionText(null)).toBe('No position yet');
  });
});

describe('0.7 quiet with checks: two times', () => {
  const q = (o) => pos({ n: 201, quiet: true, quietReason: 'zone', checks: true, nextInS: 28800, receivedAt: NOW - 60_000, ...o });
  it('computes the next check from the keyed schedule and shows it before the heartbeat', () => {
    const t = tag([q()], { lastN: 201, intervalMin: 60 });
    const plan = checkPlan(t);
    const offs = L.checkOffsets(chain.cmd(L.epoch(201)), 201, 3600, 12, 28800);
    expect(plan.offsetsS.slice(0, 3)).toEqual(offs.slice(0, 3));
    expect(nextCheck(plan, NOW).at).toBe(NOW - 60_000 + offs[0] * 1000);
    const s = statusOf(t, NOW);
    expect(s.nextCheckAt).toBe(NOW - 60_000 + offs[0] * 1000);
    expect(s.nextAt).toBe(NOW - 60_000 + 28800_000);
    expect(queuedText(t, 'BEEP', s, NOW)).toMatch(/^Beep goes out at the next check, /);
    expect(plan.checkS).toBe(30);
  });
  it('listening at checks: check_s from the backup, 30 s when it has none', () => {
    expect(checkSOf(tag([q()], { extra: {} }))).toBe(30);
    expect(checkSOf(tag([q()], { extra: { check_s: 30 } }))).toBe(30);
    expect(checkSOf(tag([q()], { extra: { check_s: 0 } }))).toBe(0);
  });
  it('without a known interval there is no check time, only the heartbeat', () => {
    const t = tag([q()], { lastN: 201 });
    expect(checkPlan(t)).toBeNull();
    expect(statusOf(t, NOW).nextCheckAt).toBeNull();
  });
  it('infers the interval from a normal IDLE report', () => {
    expect(inferIntervalMin([pos({ nextInS: 3600 })])).toBe(60);
    expect(inferIntervalMin([pos({ nextInS: 1800 })])).toBe(30);
    expect(inferIntervalMin([pos({ nextInS: 777 })])).toBeNull();
    expect(inferIntervalMin([pos({ nextInS: 3600 }), q()])).toBe(60);
  });
  it('0.7.3 report every 3rd wake-up: checks between normal reports, next report at next_in', () => {
    const p = pos({ n: 201, checks: true, nextInS: 10800, receivedAt: NOW - 60_000 });
    const t = tag([p], { lastN: 201, intervalMin: 60, extra: { report_every: 3 } });
    expect(reportEveryOf(t)).toBe(3);
    const offs = L.checkOffsets(chain.cmd(L.epoch(201)), 201, 3600, 12, 10800);
    const plan = checkPlan(t);
    expect(plan.offsetsS).toEqual(offs);
    expect(offs.length).toBe(2);
    const s = statusOf(t, NOW);
    expect(s.kind).toBe('OK');
    expect(s.nextCheckAt).toBe(NOW - 60_000 + offs[0] * 1000);
    expect(s.nextAt).toBe(NOW - 60_000 + 10800_000);
    expect(queuedText(t, 'BEEP', s, NOW)).toMatch(/^Beep goes out at the next check, /);
    // reporting at every wake-up (no report_every): nothing in between
    expect(checkPlan(tag([p], { lastN: 201, intervalMin: 60 }))).toBeNull();
    // the interval inferred from next_in is the report period: divided by report_every
    expect(intervalMinOf(tag([p], { extra: { report_every: 3 } }))).toBe(60);
    expect(reportEveryOf(tag([p], { extra: { report_every: 'x' } }))).toBe(1);
  });
  it('a parked tag says so', () => {
    const t = tag([q({ quietReason: 'parked' })], { lastN: 201, intervalMin: 60 });
    expect(statusText(t, statusOf(t, NOW), NOW)).toMatch(/^Parked, not moving/);
  });
});

describe('command slips and timeline', () => {
  it('waiting, failed and confirmed', () => {
    const t = tag([pos()], { pending: { kind: 'BEEP', seq: 3, queuedAt: NOW } });
    expect(slip(t, statusOf(t, NOW), NOW).state).toBe('wait');
    t.pending.notConfirmed = true;
    expect(slip(t, statusOf(t, NOW), NOW).text).toMatch(/not confirmed/);
    const c = tag([pos()], { lastCommand: { kind: 'BEEP', confirmedAt: NOW - 60_000 } });
    expect(slip(c, statusOf(c, NOW), NOW).state).toBe('done');
  });
  it('collapses long stretches into one row: quiet after a quiet report, a gap otherwise', () => {
    const ps = [pos({ n: 1, receivedAt: NOW - 10 * HOUR }), pos({ n: 2, receivedAt: NOW - 9 * HOUR, quiet: true, quietReason: 'owner' }), pos({ n: 3, receivedAt: NOW - 2 * HOUR }), pos({ n: 4, receivedAt: NOW - 1 * HOUR })];
    const items = timelineDays(tag(ps), NOW).flatMap((d) => d.items).map((i) => i.type);
    expect(items).toContain('quiet');
    expect(items.filter((x) => x === 'report')).toHaveLength(4);
  });
});

describe('battery at 10 % or below', () => {
  it('is its own critical state, above low battery, below silent', () => {
    const t = tag([pos({ battery: 9, lowBatt: true })]);
    const s = statusOf(t, NOW);
    expect(s.kind).toBe('BATTERY_CRITICAL');
    expect(statusText(t, s, NOW)).toBe('Battery 9 % · almost empty');
    expect(statusSub(t, s, NOW)).toMatch(/Charge it now/);
    expect(statusOf(tag([pos({ battery: 10 })]), NOW).kind).toBe('BATTERY_CRITICAL');
    expect(statusOf(tag([pos({ battery: 11 })]), NOW).kind).toBe('LOW_BATTERY');
    const low = tag([pos({ battery: 15 })], { id: 'b' });
    expect(sortTags([low, t], NOW)[0]).toBe(t);
  });
  it('a silent tag whose last report said 8 % has probably run out', () => {
    const t = tag([pos({ battery: 8, receivedAt: NOW - 4 * HOUR })]);
    const s = statusOf(t, NOW);
    expect(s.kind).toBe('SILENT');
    expect(statusSub(t, s, NOW)).toMatch(/battery at 8 %\. It has probably run out\./);
  });
});

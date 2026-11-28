import { describe, expect, it } from 'vitest';
import { App } from '../src/lib/app.js';
import { makeTag, parseKeyInput } from '../src/lib/keys.js';
import { setLang } from '../src/lib/i18n.js';
import { distance, setUnits } from '../src/lib/format.js';
import * as L from '../src/lib/lt2.js';
import { buildCommand, splitForPeriod, wakeSplit } from '../src/lib/mailbox.js';
import { allowedMissed, levelOf, missedOf, slip, statusOf, statusSub, statusText } from '../src/lib/status.js';
import { bootsOf, classify, counterBack, lossCount, lossRate } from '../src/lib/gaps.js';
import { DAY_MS, estimate, samplesOf } from '../src/lib/battery.js';
import { band, cardText, causeText, checklist, diagnose, findingText, findings, restarting, rowText, shown } from '../src/lib/diagnosis.js';

setLang('en');
const HOUR = 3_600_000;
const NOW = 1_800_000_000_000;
const pos = (o) => ({ n: 1, receivedAt: NOW - 12 * 60_000, hasFix: true, lat: 48.7, lon: 21.25, fixTime: NOW / 1000 - 720, haccM: 8, battery: 80,
  mode: 'IDLE', cmdAck: false, cmdSeq: 0, lowBatt: false, quiet: false, quietReason: null, checks: false, nextInS: 3600, nbTotal: 2, boot: false, ...o });
const K0 = '07'.repeat(32);
const mk = (positions, o = {}) => {
  const { chain, tag } = makeTag(parseKeyInput(K0), []);
  return { ...tag, chain, positions, lastN: positions.at(-1)?.n ?? -1, intervalMin: 60, zones: [], extra: {}, ...o };
};
const hourly = (count, endAgoMs, o = (i) => ({})) => Array.from({ length: count }, (_, i) => pos({ n: i + 1, receivedAt: NOW - endAgoMs - (count - 1 - i) * HOUR, ...o(i) }));
const ctx = (o = {}) => ({ online: true, relaysUp: 3, relaysAll: 3, syncing: false, ...o });

describe('one missed report is not "silent" (Android 0.12)', () => {
  it('tolerates one missed hourly report and projects the next one', () => {
    const t = mk([pos({ receivedAt: NOW - 110 * 60_000 })]); // due 50 min ago
    expect(missedOf(t, NOW)).toBe(1);
    const s = statusOf(t, NOW);
    expect(s.kind).toBe('OK');
    expect(s.missed).toBe(1);
    expect(s.nextAt).toBe(NOW - 110 * 60_000 + 2 * HOUR); // projected one period on
    expect(statusText(t, s, NOW)).toMatch(/^1 report missed · next ~\d\d:\d\d/);
    expect(levelOf(s)).toBe('');
  });
  it('is silent at the second missed report', () => {
    const t = mk([pos({ receivedAt: NOW - 3 * HOUR })]);
    expect(missedOf(t, NOW)).toBe(2);
    expect(statusOf(t, NOW).kind).toBe('SILENT');
  });
  it('tolerates none in Lost, Track, quiet or guarded, and two on a lossy path', () => {
    expect(allowedMissed(mk([pos({ mode: 'LOST', nextInS: 600 })]), NOW)).toBe(0);
    expect(allowedMissed(mk([pos({ mode: 'TRACK', nextInS: 300 })]), NOW)).toBe(0);
    expect(allowedMissed(mk([pos({ quiet: true, quietReason: 'zone' })]), NOW)).toBe(0);
    expect(allowedMissed(mk([pos()], { extra: { guard: { lat: 48.7, lon: 21.25, r_m: 300, since: 1 } } }), NOW)).toBe(0);
    expect(allowedMissed(mk([pos()]), NOW)).toBe(1);
    // every other hourly report lost over the last days
    const lossy = Array.from({ length: 24 }, (_, i) => pos({ n: 1 + i * 2, receivedAt: NOW - 3 * HOUR - (23 - i) * 2 * HOUR }));
    expect(lossRate(lossy, { periodMin: 60 }, NOW)).toBeGreaterThanOrEqual(0.25);
    expect(allowedMissed(mk(lossy), NOW)).toBe(2);
    expect(statusOf(mk([...lossy.slice(0, -1), { ...lossy.at(-1), receivedAt: NOW - 2 * HOUR }]), NOW).kind).toBe('OK');
  });
  it('says whose a shared tag is where nothing else needs the line', () => {
    const t = mk([pos()], { share: { epoch: 0, ve: 'a'.repeat(64), ce: 'b'.repeat(64), uk: 'c'.repeat(64), id: '0'.repeat(16) }, sharedBy: 'Anna' });
    expect(statusSub(t, statusOf(t, NOW), NOW)).toBe('Shared with you by Anna · full access');
  });
});

describe('counter gaps (CounterGaps.kt)', () => {
  const plan = { periodMin: 60, modeIntervalS: null };
  it('tells a lost report from a restart and an epoch skip', () => {
    const a = pos({ n: 10, receivedAt: NOW - 5 * HOUR });
    expect(classify(a, pos({ n: 11, receivedAt: NOW - 4 * HOUR }), plan).kind).toBe('NORMAL');
    const lost = classify(a, pos({ n: 13, receivedAt: NOW - 2 * HOUR }), plan);
    expect(lost).toMatchObject({ kind: 'LOST', lost: 2 });
    expect(classify(a, pos({ n: 16, receivedAt: NOW - 4 * HOUR + 60_000 }), plan).kind).toBe('RESTART'); // checkpoint soon after
    expect(classify(a, pos({ n: 20, receivedAt: NOW - 4 * HOUR, boot: true }), plan).kind).toBe('RESTART');
    expect(classify(pos({ n: 40, receivedAt: NOW - 20 * HOUR, quiet: true }), pos({ n: 64, receivedAt: NOW }), plan).kind).toBe('EPOCH_SKIP');
  });
  it('counts losses over 7 days and ignores too little history', () => {
    const r = [1, 2, 4, 5, 6, 8].map((n, i) => pos({ n, receivedAt: NOW - (6 - i) * HOUR }));
    expect(lossCount(r, plan, NOW)).toEqual({ lost: 2, of: 8 });
    expect(lossRate(r.slice(0, 3), plan, NOW)).toBe(0);
  });
  it('lists boots from the flag and from the counters, and sees a counter that went back', () => {
    const r = [pos({ n: 1, receivedAt: NOW - 5 * HOUR }), pos({ n: 2, receivedAt: NOW - 4 * HOUR, boot: true, bootCause: 'brownout', bootResets: 3 }), pos({ n: 32, receivedAt: NOW - 3 * HOUR + 1000 })];
    expect(bootsOf(r, plan).map((b) => [b.n, b.inferred, b.resets])).toEqual([[2, false, 3], [32, true, null]]);
    expect(counterBack([pos({ n: 5 }), pos({ n: 6 })], pos({ n: 5, battery: 50 }))).toBe(true);
    expect(counterBack([pos({ n: 5 }), pos({ n: 6 })], pos({ n: 5 }))).toBe(false);
  });
});

describe('battery estimate (BatteryModel.kt)', () => {
  const series = (days, from, perDay, extra = (i, v) => v) => Array.from({ length: days * 24 }, (_, i) => ({ timeMs: NOW - (days * 24 - i) * HOUR, batteryPct: Math.round(extra(i, from - (perDay * i) / 24)), mode: 'IDLE' }));
  it('needs two weeks of reports', () => {
    expect(estimate(series(10, 90, 1))).toBeNull();
  });
  it('fits the pace and says how long is left', () => {
    const e = estimate(series(20, 95, 1));
    expect(e.capped).toBe(false);
    expect(e.ratePctPerDay).toBeGreaterThan(0.8);
    expect(e.ratePctPerDay).toBeLessThan(1.2);
    expect(e.remainingHours / 24).toBeGreaterThan(60); // about 76 % left at ~1 % a day
    expect(e.confidence).toBeGreaterThanOrEqual(1);
  });
  it('calls a flat line "more than 6 months" and ignores a charge', () => {
    expect(estimate(series(20, 80, 0)).capped).toBe(true);
    const withCharge = series(20, 90, 2, (i, v) => (i >= 10 * 24 ? v + 30 : v));
    const e = estimate(withCharge);
    expect(e.ratePctPerDay).toBeGreaterThan(1.5);
    expect(e.ratePctPerDay).toBeLessThan(2.5);
  });
  it('takes the samples from the reports and cuts Track out', () => {
    expect(samplesOf([pos({ battery: null }), pos({ battery: 70, mode: 'TRACK' })])).toEqual([{ timeMs: NOW - 12 * 60_000, batteryPct: 70, mode: 'TRACK' }]);
    expect(DAY_MS).toBe(86_400_000);
  });
});

describe('why a tag is late (Diagnosis.kt, the web part)', () => {
  const late = (extra = {}, o = {}) => mk(hourly(5, 5 * HOUR, () => ({})).map((p, i, a) => ({ ...p, ...(extra.each?.(i, a) ?? {}) })), o);
  it('is nothing while the tag is on time', () => {
    expect(diagnose(mk([pos()]), ctx(), NOW)).toBeNull();
  });
  it('blames the browser before the tag: offline, then relays down, then a catch-up running', () => {
    const t = late();
    expect(diagnose(t, ctx({ online: false }), NOW).cause).toBe('PHONE_OFFLINE');
    expect(diagnose(t, ctx({ relaysUp: 0 }), NOW)).toMatchObject({ cause: 'RELAYS_DOWN', facts: { count: 3 } });
    expect(diagnose(t, ctx({ syncing: true }), NOW).cause).toBe('JUST_BACK');
    const s = statusOf(t, NOW);
    const d = diagnose(t, ctx({ syncing: true }), NOW);
    expect(causeText(t, s, d, NOW)).toBe('Checking for missed reports…');
    expect(band(d, s, levelOf(s))).toBe(false); // a calm note explains the silence: no warning band
  });
  it('finds restarts from boot reports and shows a WARN cause only once the tag is silent', () => {
    const r = [pos({ n: 1, receivedAt: NOW - 8 * HOUR }), pos({ n: 2, receivedAt: NOW - 5 * HOUR, boot: true, bootCause: 'watchdog', bootResets: 3 })];
    const t = mk(r);
    const d = diagnose(t, ctx(), NOW);
    expect(d).toMatchObject({ cause: 'RESTARTING', confidence: 'HIGH', facts: { count: 3, word: 'watchdog' } });
    expect(shown(d, statusOf(mk(r), NOW))).toBe(true);
    expect(shown(d, { kind: 'OK' })).toBe(false);
    expect(rowText(t, statusOf(t, NOW), d, NOW)).toMatch(/^Keeps restarting · 3 times since /);
    expect(cardText(t, statusOf(t, NOW), d, NOW)).toMatch(/restarted 3 times in a row \(watchdog\)/);
  });
  it('knows an empty battery, an out-of-range tag and a schedule that differs', () => {
    expect(diagnose(mk(hourly(3, 5 * HOUR, (i) => (i === 2 ? { battery: 4 } : {}))), ctx(), NOW)).toMatchObject({ cause: 'BATTERY_EMPTY', confidence: 'HIGH' });
    expect(diagnose(mk(hourly(3, 5 * HOUR, () => ({ nbTotal: 0 }))), ctx(), NOW).cause).toBe('OUT_OF_RANGE');
    const mism = diagnose(mk(hourly(3, 5 * HOUR, () => ({ nextInS: 7200 })), { intervalMin: 30 }), ctx(), NOW);
    expect(mism).toMatchObject({ cause: 'SCHEDULE_MISMATCH', facts: { periodS: 7200, period2S: 1800 } });
  });
  it('a zone or a known device is a guess with a low confidence, and nothing else is "unknown"', () => {
    const z = mk(hourly(3, 5 * HOUR), { zones: [{ name: 'School', lat: 48.7, lon: 21.25, r_m: 100 }] });
    expect(diagnose(z, ctx(), NOW)).toMatchObject({ cause: 'MAYBE_QUIET_PLACE', confidence: 'LOW', facts: { name: 'School' } });
    expect(diagnose(mk(hourly(3, 5 * HOUR), { extra: { anchors: [{ label: 'Watch', kind: 'static' }] } }), ctx(), NOW).cause).toBe('MAYBE_WITH_YOU');
    expect(diagnose(mk(hourly(3, 5 * HOUR)), ctx(), NOW)).toMatchObject({ cause: 'UNKNOWN', confidence: 'NONE' });
  });
  it('findings: battery cliff, no GPS run, counter back, restarts; the checklist lists the inputs', () => {
    const r = [pos({ n: 1, receivedAt: NOW - 8 * HOUR }), pos({ n: 2, receivedAt: NOW - 7 * HOUR, battery: 90 }),
      ...[3, 4, 5].map((n) => pos({ n, receivedAt: NOW - (8 - n) * HOUR, hasFix: false, battery: 90 })), pos({ n: 6, receivedAt: NOW - 2 * HOUR, hasFix: false, battery: 60 })];
    const f = findings(mk(r, { counterBackAt: NOW - HOUR }), ctx(), NOW).map((x) => x.finding);
    expect(f).toEqual(expect.arrayContaining(['BATTERY_CLIFF', 'NO_GPS_RUN', 'COUNTER_BACK']));
    expect(findingText(findings(mk(r), ctx(), NOW)[0], NOW)).toBe('The battery fell 30 points since the report before.');
    const rows = checklist(mk(r), ctx({ relaysUp: 2 }), NOW);
    expect(rows.map((x) => x.check)).toEqual(['ONLINE', 'RELAYS', 'LAST_REPORT', 'LOST', 'BATTERY', 'RESTARTS', 'SCHEDULE']);
    expect(rows[1].facts).toEqual({ count: 2, of: 3 });
    expect(restarting(mk(r), NOW)).toBeNull();
  });
});

describe('report interval as a command (SET_IDLE)', () => {
  it('splits a period into wake-up interval and report_every like the Android app', () => {
    expect(wakeSplit(120, 60)).toEqual([60, 2]);
    expect(wakeSplit(60, 60)).toEqual([60, 1]);
    expect(wakeSplit(150, 60)).toEqual([150, 1]);
    expect(wakeSplit(180, 45)).toEqual([45, 4]);
    expect(splitForPeriod(360, 60, 1)).toEqual([60, 6]);
    expect(splitForPeriod(120, 30, 4)).toEqual([30, 4]); // a finer listen interval that still fits is kept
    expect(splitForPeriod(30, 60, 1)).toEqual([30, 1]);
  });
  it('builds the command: interval, listen, check window and report_every', () => {
    const c = L.decodeCommand(buildCommand('INTERVAL', 9, 60, 0, { every: 2, checkS: 30 }));
    expect(c).toMatchObject({ seq: 9, op: L.Op.SET_IDLE });
    expect([...c.args.slice(0, 6)]).toEqual([60, 0, 0, 0, 30, 2]);
    expect(L.decodeCommand(buildCommand('INTERVAL', 9, 60, 0, { every: 1, checkS: 0 })).args[4]).toBe(L.CHECK_OFF);
  });
  it('is queued, shown in the slip, and written to the tag and the backup when the tag confirms it', async () => {
    const app = new App();
    app.publishPending = async () => ({ ok: 1, events: 1 });
    app.publishAnchors = async () => 0;
    app.persist = () => {};
    app.saveBackup = async () => ({});
    const t = mk([pos({ n: 1, receivedAt: NOW - 10 * 60_000, nextInS: 3600 })], { extra: { check_s: 20 } });
    t.anchor = { a: { n: 1, t0: NOW - 10 * 60_000 }, cands: [], lastN: 1, seen: {}, known: true };
    app.tags.push(t);
    app.firstSyncDone.add(t.id);
    const out = await app.queueCommand(t.id, 'INTERVAL', 60, 0, { every: 2 });
    expect(out.ok).toBe(1);
    expect(t.pending).toMatchObject({ kind: 'INTERVAL', wakeMin: 60, every: 2, checkS: 30 });
    expect(slip(t, statusOf(t, NOW), NOW).text).toMatch(/^Reports every 2 h · waiting/);
    await app._addReport(t, 2, { ...pos({ n: 2 }), neighbours: [], cmdAck: true, cmdSeq: t.pending.seq & 15, nextInS: 7200 }, Date.now());
    expect(t.pending).toBeNull();
    expect(t.intervalMin).toBe(60);
    expect(t.extra).toMatchObject({ report_every: 2, check_s: 30 });
  });
});

describe('units', () => {
  it('shows feet and miles when asked', () => {
    setUnits('imperial');
    expect(distance(8)).toBe('20 ft');
    expect(distance(2000)).toBe('1.2 mi');
    setUnits('metric');
    expect(distance(8)).toBe('8 m');
    expect(distance(1200)).toBe('1.2 km');
  });
});

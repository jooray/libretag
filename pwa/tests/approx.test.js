// 0.8.1/0.8.2: reports a bridge drained late from a companion node's queue (["approx", …], PROTOCOL.md 4)
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { b64encode, bytesToHex, hexToBytes } from '../src/lib/bytes.js';
import * as L from '../src/lib/lt2.js';
import { App } from '../src/lib/app.js';
import { makeTag } from '../src/lib/keys.js';
import { setLang } from '../src/lib/i18n.js';
import { mergeReport, reportTimeMs, upgrades } from '../src/lib/reports.js';
import { checkPlan, effectText, nextAt, statusOf, statusSub, statusText } from '../src/lib/status.js';

setLang('en');
const V = JSON.parse(readFileSync(new URL('../../host/tests/vectors/lt2.json', import.meta.url)));
const chain = new L.KeyChain(hexToBytes(V.k0));
const HOUR = 3_600_000;
const NOW = 1_800_000_000_000;
const pos = (o) => ({ n: 12, receivedAt: NOW - 5 * HOUR, hasFix: true, lat: 48.7, lon: 21.25, fixTime: 0, battery: 77,
  mode: 'IDLE', cmdAck: false, cmdSeq: 0, lowBatt: false, quiet: true, quietReason: 'owner', checks: true, nextInS: 8 * 3600, nbTotal: 1, ...o });
const tag = (positions, o = {}) => ({ id: 'a', name: 'Car', type: 'vehicle', mark: { color: 'red', symbol: 'stripe' }, chain, positions,
  lastN: 12, intervalMin: 60, ...o });

describe('the approx tag', () => {
  it('created_at is the latest time, the approx value the earliest (0.8.2)', () => {
    expect(reportTimeMs({ created_at: 1000, tags: [['y', 'ab']] })).toEqual({ at: 1_000_000, until: null });
    expect(reportTimeMs({ created_at: 1000, tags: [['approx', '400']] })).toEqual({ at: 400_000, until: 1_000_000 });
    // malformed or missing: still approximate, an empty range at created_at
    expect(reportTimeMs({ created_at: 1000, tags: [['approx', 'x']] })).toEqual({ at: 1_000_000, until: 1_000_000 });
    expect(reportTimeMs({ created_at: 1000, tags: [['approx']] })).toEqual({ at: 1_000_000, until: 1_000_000 });
  });
  it('follows the shared vectors (lt2.json report_time)', () => {
    for (const v of V.report_time) {
      expect(reportTimeMs(v), v.name).toEqual({ at: v.from * 1000, until: v.until == null ? null : v.until * 1000 });
    }
  });
});

describe('status of an approximate report', () => {
  it('is shown, but without checks; next report "by" from the drain time', () => {
    const p = pos({ approxUntil: NOW - HOUR });
    const t = tag([p]);
    expect(checkPlan(t)).toBeNull();
    const s = statusOf(t, NOW);
    expect(s.kind).toBe('QUIET');
    expect(s.approx).toBe(true);
    expect(s.battery).toBe(77);
    expect(s.nextCheckAt ?? null).toBeNull();
    expect(s.nextAt).toBe(NOW - HOUR + 8 * HOUR);
    expect(nextAt(t, NOW)).toBe(s.nextAt);
    expect(statusSub(t, s, NOW)).toContain('~');
  });
  it('the same report with an exact time has its checks (once its anchor is set)', () => {
    const t = tag([pos()], { anchor: { a: { n: 12, t0: NOW - 5 * HOUR }, cands: [], lastN: 12, seen: {}, known: true } });
    expect(checkPlan(t)).not.toBeNull();
    expect(checkPlan(t).t0).toBe(NOW - 5 * HOUR);
  });
  it('a normal report: no "next ~" claim, only "by"', () => {
    const t = tag([pos({ quiet: false, checks: false, nextInS: 3600, receivedAt: NOW - 3 * HOUR, approxUntil: NOW - 30 * 60_000 })]);
    const s = statusOf(t, NOW);
    expect(s.kind).toBe('OK'); // not silent: the drain time bounds it
    expect(s.nextAt).toBe(NOW + 30 * 60_000);
    expect(statusText(t, s, NOW)).not.toMatch(/next ~/);
    expect(statusText(t, s, NOW)).toMatch(/\(~/);
    expect(effectText(t, s, NOW)).toBe(effectText(t, { ...s, kind: "QUIET" }, NOW)); // the "check-in by" wording
  });
});

describe('copies of one report', () => {
  it('an exact copy replaces an approximate one and upgrades the newest report', () => {
    const a = pos({ approxUntil: NOW - HOUR });
    const t = tag([a]);
    const exact = pos({ receivedAt: NOW - 2 * HOUR });
    expect(upgrades(t, exact)).toBe(true);
    expect(mergeReport(t.positions, exact, 100)).toBe(true);
    expect(t.positions).toEqual([exact]);
    // no anchor ever from the approximate copy; the exact one (app._addReport) sets it
    expect(checkPlan(t)).toBeNull();
    // a later approximate copy changes nothing
    expect(mergeReport(t.positions, pos({ approxUntil: NOW }), 100)).toBe(false);
    expect(t.positions).toEqual([exact]);
    expect(upgrades(t, exact)).toBe(false);
  });
  it('two approximate copies keep the range both allow', () => {
    const ps = [pos({ receivedAt: NOW - 5 * HOUR, approxUntil: NOW - HOUR })];
    mergeReport(ps, pos({ receivedAt: NOW - 3 * HOUR, approxUntil: NOW - 10 * 60_000 }), 100);
    expect(ps[0].receivedAt).toBe(NOW - 3 * HOUR);
    expect(ps[0].approxUntil).toBe(NOW - HOUR);
  });
});

describe('the app with relay events', () => {
  const PT = hexToBytes(V.reports[0].pt);
  let idc = 0;
  const ev = async (n, at, tags = []) => {
    const p = await L.sealReportChain(chain, n, 0, PT);
    return { id: (++idc).toString(16).padStart(64, '0'), kind: 4243, created_at: at, tags: [['y', bytesToHex(p.slice(0, 4))], ...tags], content: b64encode(p), pubkey: 'ee'.repeat(32), sig: '' };
  };
  const setup = () => {
    const app = new App();
    const { tag: tg, chain: c } = makeTag({ k0: V.k0, name: 'T', type: 'thing' }, []);
    const rec = { ...tg, chain: c, positions: [], lastN: 9, pending: null, zones: [], extra: {} };
    app.tags.push(rec);
    return { app, rec };
  };
  it('approximate first, then the exact copy from another bridge wins', async () => {
    const { app, rec } = setup();
    expect(await app._onEvent(rec, await ev(10, 4000, [['approx', '1000']]), true)).toBe(true);
    expect(rec.positions).toHaveLength(1);
    expect(rec.positions[0].approxUntil).toBe(4_000_000);
    expect(await app._onEvent(rec, await ev(10, 3000), true)).toBe(true);
    expect(rec.positions).toHaveLength(1);
    expect(rec.positions[0].receivedAt).toBe(3_000_000);
    expect(rec.positions[0].approxUntil).toBeUndefined();
    // and the window closes after the exact copy
    expect(await app._onEvent(rec, await ev(10, 3500), true)).toBe(false);
  });
  it('exact first: a late approximate copy is not taken', async () => {
    const { app, rec } = setup();
    expect(await app._onEvent(rec, await ev(10, 3000), true)).toBe(true);
    expect(await app._onEvent(rec, await ev(10, 4000, [['approx', '1000']]), true)).toBe(false);
    expect(rec.positions[0].receivedAt).toBe(3_000_000);
    expect(rec.positions[0].approxUntil).toBeUndefined();
  });
});

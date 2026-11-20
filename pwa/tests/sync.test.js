import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { b64encode, bytesToHex, hexToBytes } from '../src/lib/bytes.js';
import * as L from '../src/lib/lt2.js';
import { fetchAllReports, findBeyondWindow } from '../src/lib/sync.js';
import { App } from '../src/lib/app.js';
import { makeTag } from '../src/lib/keys.js';

const V = JSON.parse(readFileSync(new URL('../../host/tests/vectors/lt2.json', import.meta.url)));
const chain = new L.KeyChain(hexToBytes(V.k0));
const PT = hexToBytes(V.reports[0].pt);
let idc = 0;
const reportEv = async (c, n, at, j = 0) => {
  const p = await L.sealReportChain(c, n, j, PT);
  return { id: (++idc).toString(16).padStart(64, '0'), kind: 4243, created_at: at, tags: [['y', bytesToHex(p.slice(0, 4))]], content: b64encode(p), pubkey: 'ee'.repeat(32), sig: '' };
};
const item = (ev) => ({ ev });
const noise = (k) => Array.from({ length: k }, (_, i) => item({ id: 'n' + i, kind: 4243, created_at: 1, content: b64encode(Uint8Array.from({ length: 45 }, (_, b) => (i * 31 + b * 7) & 255)) }));

describe('resync past the window, in blocks', () => {
  it('finds a report 5000 counters ahead among unrelated events', async () => {
    const ev = await reportEv(chain, 100 + 5000, 10);
    const hit = await findBeyondWindow(chain, 100, [...noise(200), item(ev)]);
    expect(hit.n).toBe(5100);
    expect(hit.item.ev).toBe(ev);
    expect(hit.report.type ?? hit.report).toBeTruthy();
  });
  it('a report inside the normal window is not its business, one past the cap is not found', async () => {
    const inWin = await reportEv(chain, 100 + 100, 10);
    expect(await findBeyondWindow(chain, 100, [item(inWin)])).toBeNull();
    const far = await reportEv(chain, 100 + 1 + 8192, 10);
    expect(await findBeyondWindow(chain, 100, [item(far)], { cap: 8192 })).toBeNull();
    expect((await findBeyondWindow(chain, 100, [item(far)], { cap: 8193 })).n).toBe(100 + 1 + 8192);
  });
  it('a payload with the right 4-byte tag but a wrong MAC is not a hit', async () => {
    const ev = await reportEv(chain, 4000, 10);
    const bad = Uint8Array.from(Buffer.from(ev.content, 'base64'));
    bad[20] ^= 1;
    expect(await findBeyondWindow(chain, 100, [item({ ...ev, content: b64encode(bad) })])).toBeNull();
  });
  it('a tag that never reported searches past the first-sync span; a share from its epoch', async () => {
    const ev = await reportEv(chain, 4096 + 300, 10);
    expect((await findBeyondWindow(chain, -1, [item(ev)])).n).toBe(4396);
    const share = L.KeyChain.fromShare(100, chain.view(100));
    const ev2 = await reportEv(chain, 6400 + 4096 + 10, 10);
    expect((await findBeyondWindow(share, -1, [item(ev2)])).n).toBe(6400 + 4106);
  });
});

/** A pool whose relays hold given events and answer filters like a relay (since, until, limit, #y). */
function fakePool(byRelay) {
  const filters = [];
  return {
    filters,
    querySync: async (relays, f) => {
      filters.push(f);
      const out = [];
      for (const r of relays) {
        let evs = (byRelay[r] ?? []).filter((e) => f.kinds.includes(e.kind) && (f.since == null || e.created_at >= f.since)
          && (f.until == null || e.created_at <= f.until) && (!f['#y'] || e.tags.some((t) => t[0] === 'y' && f['#y'].includes(t[1]))));
        evs = evs.sort((a, b) => b.created_at - a.created_at);
        if (f.limit) evs = evs.slice(0, f.limit);
        out.push(...evs);
      }
      return out;
    },
    subscribeMany: (_r, f) => { filters.push(f); return { close() {} }; },
    publish: () => [Promise.resolve('ok')],
    listConnectionStatus: () => new Map(),
    close() {},
  };
}

describe('fetching every report since the last sync', () => {
  it('pages each relay with until/limit and merges by id', async () => {
    const a = Array.from({ length: 1234 }, (_, i) => ({ id: 'a' + i, kind: 4243, created_at: 1000 + Math.floor(i / 3), tags: [], content: '' }));
    const b = [a[5], a[700], { id: 'b1', kind: 4243, created_at: 1100, tags: [], content: '' }];
    const pool = fakePool({ 'wss://a': a, 'wss://b': b });
    const r = await fetchAllReports(pool, ['wss://a', 'wss://b'], 1000, { page: 100 });
    expect(r.overflow).toBe(false);
    expect(r.events).toHaveLength(1235);
    expect(pool.filters.every((f) => !f['#y'])).toBe(true);
    expect(pool.filters.filter((f) => f.until != null).length).toBeGreaterThan(10);
  });
  it('says overflow when a relay holds more than the limit', async () => {
    const a = Array.from({ length: 300 }, (_, i) => ({ id: 'a' + i, kind: 4243, created_at: 1000 + i, tags: [], content: '' }));
    const r = await fetchAllReports(fakePool({ 'wss://a': a }), ['wss://a'], 1000, { page: 100, max: 250 });
    expect(r.overflow).toBe(true);
  });
});

describe('the app reads reports without telling the relays which tags are whose', () => {
  it('matches locally, follows a tag 3000 counters past its window, never sends #y', async () => {
    const now = Math.floor(Date.now() / 1000);
    const evs = [await reportEv(chain, 10, now - 3 * 86400), await reportEv(chain, 11, now - 3 * 86400 + 3600),
      await reportEv(chain, 11 + 2048 + 3000, now - 100), await reportEv(chain, 11 + 2048 + 3001, now - 50, 1)];
    const other = new L.KeyChain(hexToBytes('42'.repeat(32)));
    evs.push(await reportEv(other, 12, now - 60));
    const app = new App();
    app.pool = fakePool({ 'wss://a': evs });
    app.relays = ['wss://a'];
    const { tag, chain: c } = makeTag({ k0: V.k0, name: 'T', type: 'thing' }, []);
    const rec = { ...tag, chain: c, positions: [], lastN: 9, pending: null, zones: [], extra: {} };
    app.tags.push(rec);
    await app.syncTag(rec);
    expect(rec.positions.map((p) => p.n)).toEqual([10, 11, 5059, 5060]);
    expect(rec.lastN).toBe(5060);
    expect(app.pool.filters.some((f) => f['#y'])).toBe(false);
    expect(rec.syncedTo).toBeGreaterThanOrEqual(now);
    expect(app.queryMode).toBe('all');
  });
  it('falls back to #y windows when the relay holds too much', async () => {
    const app = new App();
    const many = Array.from({ length: 50 }, (_, i) => ({ id: 'x' + i, kind: 4243, created_at: Math.floor(Date.now() / 1000) - i, tags: [], content: '' }));
    app.pool = fakePool({ 'wss://a': many });
    app.relays = ['wss://a'];
    const mod = await import('../src/lib/app.js');
    expect(mod.FETCH_ALL_MAX).toBe(20000);
    const r = await fetchAllReports(app.pool, app.relays, 0, { max: 10, page: 20 });
    expect(r.overflow).toBe(true);
  });
});

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { b64decode, bytesToHex, hexToBytes } from '../src/lib/bytes.js';
import * as L from '../src/lib/lt2.js';
import * as Q from '../src/lib/quietmesh.js';
import { ackEntries, ackPlan, moreRepublish, still } from '../src/lib/acks.js';

// docs/vectors/quiet-mesh-v1.json: the contract the firmware, both bridges and the Android app share
const V = JSON.parse(readFileSync(new URL('../../docs/vectors/quiet-mesh-v1.json', import.meta.url)));
const chain = new L.KeyChain(hexToBytes(V.k0));
const hex = (b) => bytesToHex(b);
const ce = chain.cmd(L.epoch(300));

describe('copies on air (0, 1, 9, 10, 11)', () => {
  it('x, sealing v2, the window opens them and reads SYNC', async () => {
    for (const c of V.copies) {
      const ve = chain.view(L.epoch(c.n));
      for (const j of L.J_ON_AIR) expect(hex(L.reportTag(ve, c.n, j))).toBe(c.tags[String(j)]);
      for (const cp of c.copies) {
        expect(hex(await L.sealReportChain(chain, c.n, cp.j, hexToBytes(c.pt)))).toBe(cp.p);
        const w = new L.ReportWindow(chain, c.n - 1);
        const hit = await w.open(hexToBytes(cp.p));
        expect([hit.n, hit.j]).toEqual([c.n, cp.j]);
        expect(hit.report.sync).toBe(true);
      }
    }
  });
});

describe('ACK (PROTOCOL.md 3.3)', () => {
  it('near form vectors and refusals', () => {
    for (const a of V.acks.valid) {
      expect(hex(Q.ackTag(ce, a.n, a.k))).toBe(a.tag);
      expect(hex(Q.ackSeal(ce, a.n, a.k, a.hold, a.more))).toBe(a.pa);
      expect(Q.ackOpen13(ce, a.n, a.k, hexToBytes(a.pa))).toEqual({ hold: a.hold, more: a.more, routed: false });
    }
    for (const a of V.acks.invalid) {
      const d = hexToBytes(a.datagram);
      expect(d.length === 13 ? Q.ackOpen13(ce, a.n, a.k, d) : null).toBeNull();
    }
    for (const a of V.acks_routed.valid) expect(hex(Q.ackSeal(ce, a.n, a.k, 4, false, true))).toBe(a.pa);
  });
  it('ACK entry contents in the three forms', () => {
    for (const e of V.ack_entries) expect(Q.ackEntryContent(ce, e.n, e.k, e.form, e.hold, e.more)).toBe(e.content);
    expect(Q.formFor(9, true)).toBe('near');
    expect(Q.formFor(0, true)).toBe('both');
    expect(Q.formFor(0, false)).toBe('near');
    expect(Q.formFor(11, true)).toBe('routed');
  });
  it('hold', () => {
    for (const [h, p, want] of V.hold) expect(Q.holdS(h, p)).toBe(want);
  });
});

describe('slots (PROTOCOL.md 2.6)', () => {
  it('slot keys, offsets, commands, windows, ACKs at a slot', async () => {
    for (const k of V.slots.keys) {
      const ks = Q.slotKey(chain.cmd(L.epoch(k.n_a)), k.n_a);
      expect(hex(ks)).toBe(k.ks);
      expect(Q.slotOffsets(ks, k.base_s, 6)).toEqual(k.offsets);
      expect(Q.slotOffsets(ks, 900, 4)).toEqual(k.offsets_900);
      expect(Q.slotOffsets(ks, k.base_s, 3).map((o) => Q.slotSendAt(1763500000, o))).toEqual(k.send_at);
    }
    const ks = Q.slotKey(ce, 300);
    for (const c of V.slots.commands) {
      expect(hex(Q.slotCommandTag(ks, c.k))).toBe(c.tag);
      expect(hex(await Q.sealSlotCommand(ks, c.k, hexToBytes(c.pt)))).toBe(c.pc);
      expect(await Q.openSlotCommand(ks, c.k, hexToBytes(c.pc))).not.toBeNull();
      expect(await Q.openSlotCommand(ks, c.k + 1, hexToBytes(c.pc))).toBeNull();
    }
    for (const w of V.slots.windows) expect(Q.slotGuardMs(w.offset_s)).toBe(w.guard_ms);
    for (const a of V.slots.acks_at_slot) expect(hex(Q.ackSeal(chain.cmd(L.epoch(a.n)), a.n, a.k, 4))).toBe(a.pa);
  });
});

describe("the app's anchor (R1, R32)", () => {
  for (const sc of V.app_anchor) {
    it(sc.name, () => {
      let s = Q.emptyAnchor();
      for (const st of sc.steps) {
        const ev = st.event;
        s = ev[0] === 'confirmed' ? Q.anchorOnConfirmed(s, ev[1]) : Q.anchorOnReport(s, ev[1], ev[2], ev[3], ev[4], ev[5], ev[6] ?? true, ev[7] ?? false);
        expect(s.a ? [s.a.n, s.a.t0] : null).toEqual(st.a);
        expect(s.cands.map((c) => [c.n, c.t0])).toEqual(st.cands);
        expect(s.known).toBe(st.known);
      }
    });
  }
});

describe('ACK entries while the web app is open (SPEC.md 3.2)', () => {
  const NOW = 1_800_000_000_000;
  const pos = (o) => ({ n: 300, j: 9, x: 'aabbccdd', receivedAt: NOW - 3000, hasFix: true, lat: 48.70, lon: 21.25, haccM: 5, ...o });
  const tag = (o = {}) => ({ chain, positions: [], intervalMin: 60, extra: {}, ...o });
  it('a hello under 40 s old: one live near entry, y = x of the copy, signed by sk_n', () => {
    const plan = ackPlan(tag(), null, pos(), NOW);
    expect(plan).toMatchObject({ n: 300, form: 'near', live: true, hold: 4 });
    const [e] = ackEntries(tag(), plan, NOW / 1000);
    expect(e.n).toBe(300);
    expect(e.tags).toEqual([['v', '2'], ['a', '1'], ['y', 'aabbccdd'], ['route', 'near'], ['expiration', String(NOW / 1000 + 600)]]);
    expect(hex(b64decode(e.content))).toBe(hex(Q.ackSeal(ce, 300, 0, 4)));
  });
  it('a standing flood gets both forms on route direct; a moved one the near form; a guarded tag hold 0', () => {
    const prev = pos({ n: 299, lat: 48.7001 });
    expect(still(prev, pos({ j: 0 }))).toBe(true);
    expect(ackPlan(tag(), prev, pos({ j: 0 }), NOW)).toMatchObject({ form: 'both' });
    expect(ackPlan(tag(), pos({ n: 299, lat: 48.75 }), pos({ j: 0 }), NOW)).toMatchObject({ form: 'near' });
    expect(ackPlan(tag({ extra: { guard: { lat: 48.7, lon: 21.25 } } }), null, pos(), NOW).hold).toBe(0);
  });
  it('late: timed entries for the next two slots of the anchor, signed by the anchor key, never copy 11, never a view share', () => {
    const t = tag({ anchor: { a: { n: 298, t0: NOW - 1800_000, vias: ['11223344'] }, cands: [], lastN: 300, seen: {}, known: true },
      positions: [pos({ receivedAt: NOW - 120_000 })] });
    const plan = ackPlan(t, null, pos({ receivedAt: NOW - 120_000 }), NOW);
    expect(plan.live).toBe(false);
    const es = ackEntries(t, plan, NOW / 1000);
    expect(es).toHaveLength(2);
    for (const e of es) {
      expect(e.n).toBe(298);
      expect(e.tags).toContainEqual(['via', '11223344']);
      expect(e.tags.some((x) => x[0] === 'at')).toBe(true);
    }
    expect(ackPlan(t, null, pos({ j: 11 }), NOW)).toBeNull();
    expect(ackPlan(tag({ chain: L.KeyChain.fromShare(4, chain.view(4), null) }), null, pos(), NOW)).toBeNull();
  });
});

describe('timed ACKs and a command queued after them (PROTOCOL.md 3.3)', () => {
  const plan = { n: 300, j: 9, x: '0000aa01', form: 'near', live: false, hold: 4, more: false };
  const set = { plan, sent: [{ id: 'e1', n: 300, exp: 2000, ys: ['aa'] }], lastAt: 1500 };
  it('are published again with MORE while a slot is ahead', () => {
    expect(moreRepublish(set, { pending: { seq: 1 }, lastN: 300 }, 1000)).toEqual({ ...plan, more: true });
  });
  it('not without a command, after a newer report, once MORE is set, or when the slots passed', () => {
    expect(moreRepublish(set, { pending: null, lastN: 300 }, 1000)).toBeNull();
    expect(moreRepublish(set, { pending: { seq: 1 }, lastN: 301 }, 1000)).toBeNull();
    expect(moreRepublish({ ...set, plan: { ...plan, more: true } }, { pending: { seq: 1 }, lastN: 300 }, 1000)).toBeNull();
    expect(moreRepublish(set, { pending: { seq: 1 }, lastN: 300 }, 1499)).toBeNull();
    expect(moreRepublish({ ...set, plan: { ...plan, live: true } }, { pending: { seq: 1 }, lastN: 300 }, 1000)).toBeNull();
  });
});

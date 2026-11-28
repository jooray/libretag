// The ACK sender while the web app is open (SPEC.md 3.2 "PWA", PROTOCOL.md 3.3, 4.3): the same ACK entries as
// the Android app. A live entry for a report under 40 s old, else timed entries for the next slots of the
// anchor's grids (2 for the near form). Copy 11 (along a path) needs the path record and the bridge of the last
// routed ACK, which only the Android app follows; the web app leaves it to it. Never a flood.
import { bytesToHex } from './bytes.js';
import { epoch } from './lt2.js';
import { distanceM } from './geo.js';
import { HOLD_DEFAULT, J_ROUTED, LIVE_ACK_MAX_AGE_MS, MOVE_M, ackEntryContent, ackRoute, ackTag, formFor, slotSendAt } from './quietmesh.js';
import { guardOf, sendGrids } from './status.js';

export const ACK_LIVE_TTL_S = 600, ACK_TIMED_TTL_S = 120;

/** The tag stands where it stood: a fix in both reports within max(300 m, 3 x accuracy), and no BOOT. */
export function still(prev, cur) {
  if (!prev || !cur || cur.boot || !cur.hasFix || !prev.hasFix) return false;
  return distanceM(prev.lat, prev.lon, cur.lat, cur.lon) <= Math.max(MOVE_M, 3 * Math.max(prev.haccM ?? 0, cur.haccM ?? 0));
}

/**
 * What to ACK for a new report opened with an exact time: { n, j, x, form, live, hold, more } or null (a view
 * share, a copy 11, or a copy that is not a report copy). `now` in ms; the age is the report's radio time.
 */
export function ackPlan(t, prev, pos, now) {
  if (!t.chain?.canCommand || pos.j == null || pos.j < 0 || pos.j === J_ROUTED || ![0, 1, 9, 10].includes(pos.j)) return null;
  const form = formFor(pos.j, still(prev, pos));
  const live = now - pos.receivedAt < LIVE_ACK_MAX_AGE_MS;
  return { n: pos.n, j: pos.j, x: pos.x ?? null, form, live, hold: guardOf(t) ? 0 : HOLD_DEFAULT, more: !!t.pending };
}

/** The unsigned ACK entries of a plan: { n (whose key signs it), at, ys, tags, content }. */
export function ackEntries(t, plan, nowS) {
  if (!plan || !t.chain?.canCommand) return [];
  const ce = t.chain.cmd(epoch(plan.n));
  if (!ce) return [];
  if (plan.live) {
    const x = plan.x ?? t.positions.findLast?.((p) => p.n === plan.n)?.x;
    if (!x) return [];
    return [{ n: plan.n, at: null, ys: [x], content: ackEntryContent(ce, plan.n, 0, plan.form, plan.hold, plan.more),
      tags: [['v', '2'], ['a', '1'], ['y', x], ['route', ackRoute(plan.form)], ['expiration', String(nowS + ACK_LIVE_TTL_S)]] }];
  }
  // timed: the next slots on every grid, 2 for the near and the 26 B forms
  const out = [];
  for (const g of sendGrids(t, nowS * 1000)) {
    let c = 0;
    for (let i = 0; i < g.offsetsS.length && c < 2; i++) {
      const at = slotSendAt(Math.floor(g.t0 / 1000), g.offsetsS[i]);
      if (at <= nowS + 10) continue;
      const k = i + 1;
      const xa = bytesToHex(ackTag(ce, plan.n, k));
      const vias = g.vias?.length ? g.vias : [];
      if (!vias.length) continue;
      out.push({ n: g.n, at, ys: [xa], content: ackEntryContent(ce, plan.n, k, plan.form, plan.hold, plan.more),
        tags: [['v', '2'], ['a', '1'], ['y', xa], ['route', ackRoute(plan.form)], ['at', String(at)], ...vias.map((v) => ['via', v]),
          ['expiration', String(at + ACK_TIMED_TTL_S)]] });
      c++;
    }
  }
  return out;
}

/**
 * A command was queued after timed ACK entries without MORE went out (`set`: { plan, sent, lastAt }): a tag at a
 * listen-only slot closes its receiver right after such an ACK and misses the command 300 ms behind it. Returns
 * the plan to publish again with MORE (after deleting `set.sent`), or null when nothing is left to fix.
 */
export function moreRepublish(set, t, nowS) {
  if (!set || set.plan.live || set.plan.more || !t?.pending || t.lastN !== set.plan.n || !(set.lastAt > nowS + 2)) return null;
  return { ...set.plan, more: true };
}

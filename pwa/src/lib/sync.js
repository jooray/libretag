// Report sync helpers (audit 3.1 and 3.2): fetch every kind 4243 since a time, paginated per relay,
// and look for a tag's reports past its window (the tag moved on more than 2048 counters while the app
// was closed: a week of Lost, Track every 30 s, restarts that skip 16 counters each).
import { b64decode, bytesToHex } from './bytes.js';
import { EPOCH_REPORTS, FIRST_SYNC, NOSTR_REPORT_KIND, WINDOW_AHEAD, epoch, openReport, reportTag } from './lt2.js';

/**
 * Every kind 4243 with created_at >= since on each relay, newest first per relay with `until` and
 * `limit`, then merged and deduplicated by id. A page that brings nothing new ends a relay (relays may
 * cap `limit` lower than asked). { events, overflow }: overflow = some relay had more than `max`.
 */
export async function fetchAllReports(pool, relays, since, { max = 20_000, page = 500, maxWait = 8000 } = {}) {
  const byId = new Map();
  let overflow = false;
  await Promise.all(relays.map(async (url) => {
    let until = null;
    let count = 0;
    const seen = new Set();
    for (;;) {
      const f = { kinds: [NOSTR_REPORT_KIND], since, limit: page };
      if (until != null) f.until = until;
      let evs;
      try { evs = await pool.querySync([url], f, { maxWait }); } catch { return; }
      let fresh = 0;
      let oldest = Infinity;
      for (const ev of evs) {
        if (ev.kind !== NOSTR_REPORT_KIND || ev.created_at < since) continue;
        oldest = Math.min(oldest, ev.created_at);
        if (seen.has(ev.id)) continue;
        seen.add(ev.id); fresh++; count++;
        if (!byId.has(ev.id)) byId.set(ev.id, ev);
      }
      if (count > max) { overflow = true; return; }
      if (!fresh || evs.length < Math.min(page, 50) || oldest === Infinity) return;
      // `until` is inclusive: events of the oldest second come again and are skipped by id
      until = oldest;
    }
  }));
  return { events: [...byId.values()], overflow };
}

/**
 * Look for a report of this chain past its window: counters from the end of the window up to
 * `cap` past the newest one, in blocks of `block`. items: [{ x (hex of the first 4 bytes), p (payload) }].
 * A 4-byte tag alone collides easily over 130 000 counters, so a hit counts only when the report opens
 * (MAC checked). Returns the lowest { n, j, report, item } or null. Yields between blocks.
 */
export async function findBeyondWindow(chain, lastN, items, { cap = 65_536, block = 2048, pause = () => new Promise((r) => setTimeout(r, 0)) } = {}) {
  const floor = chain.minEpoch * EPOCH_REPORTS;
  const base = lastN < 0 ? floor + FIRST_SYNC : lastN + 1 + WINDOW_AHEAD;
  const end = (lastN < 0 ? floor : lastN + 1) + cap;
  const byX = new Map();
  for (const it of items) {
    if (!it.p) { try { it.p = b64decode(it.ev.content); } catch { continue; } }
    const x = it.x ?? bytesToHex(it.p.slice(0, 4));
    if (!byX.has(x)) byX.set(x, []);
    byX.get(x).push(it);
  }
  if (!byX.size) return null;
  for (let lo = base; lo < end; lo += block) {
    const hi = Math.min(end, lo + block);
    for (let n = lo; n < hi; n++) {
      const v = chain.view(epoch(n));
      for (const j of [0, 1]) {
        const cands = byX.get(bytesToHex(reportTag(v, n, j)));
        if (!cands) continue;
        for (const item of cands) {
          const report = await openReport(v, n, j, item.p);
          if (report) return { n, j, report, item };
        }
      }
    }
    await pause();
  }
  return null;
}

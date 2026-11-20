// Copies of one report (0.8.1): from several bridges, relay backlogs and resyncs. A bridge that
// drained a report late from a companion node's queue publishes it with created_at = the drain
// (the latest time it can have been heard, so a `since` query still sees it) and
// ["approx", "<earliest unix s>"] (0.8.2, PROTOCOL.md 4). Such a copy is shown, but an exact copy
// of the same report always wins over it, and it never anchors the check schedule (status.js checkPlan).

/**
 * When a kind 4243 was heard (ms): { at, until }. `until` is null for an exact report; for an
 * approximate one the time lies in [at, until]. A missing or malformed approx value gives an empty
 * range at created_at that still counts as approximate. Order-insensitive: bridge 0.3.0 put the
 * earliest time in created_at and the drain time in the tag.
 */
export function reportTimeMs(ev) {
  const ca = ev.created_at;
  const tag = (ev.tags ?? []).find((x) => x[0] === 'approx');
  if (!tag) return { at: ca * 1000, until: null };
  const v = typeof tag[1] === 'string' && /^[0-9]{1,12}$/.test(tag[1]) ? Number(tag[1]) : ca;
  return { at: Math.min(v, ca) * 1000, until: Math.max(v, ca) * 1000 };
}

export const isApprox = (p) => p?.approxUntil != null;

/** counters known from an exact copy: the receive window never reopens these */
export const exactNs = (positions) => positions.filter((p) => !isApprox(p)).map((p) => p.n);

/** The first exact copy of the newest report, which so far was known only approximately. */
export function upgrades(tag, pos) {
  if (isApprox(pos) || pos.n !== tag.lastN) return false;
  return isApprox(tag.positions.find((x) => x.n === pos.n));
}

/**
 * Put pos into positions (sorted by n, in place). An approximate copy never replaces an exact one;
 * two approximate copies keep the range both allow. Returns false when nothing changed.
 */
export function mergeReport(positions, pos, max) {
  const i = positions.findIndex((x) => x.n === pos.n);
  if (i < 0) {
    positions.push(pos);
    positions.sort((a, b) => a.n - b.n);
    if (positions.length > max) positions.splice(0, positions.length - max);
    return true;
  }
  const old = positions[i];
  if (!isApprox(old)) return false;
  if (!isApprox(pos)) { positions[i] = pos; return true; }
  const from = Math.max(old.receivedAt, pos.receivedAt), until = Math.min(old.approxUntil, pos.approxUntil);
  positions[i] = from <= until ? { ...pos, receivedAt: from, approxUntil: until } : pos;
  return true;
}

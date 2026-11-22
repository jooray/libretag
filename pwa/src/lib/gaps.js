// What the gaps between report counters say (port of the Android app's CounterGaps.kt, docs/design/tag-diagnosis.md 4.3).
// At boot the tag continues at max(state n_next, key page n_floor, 64 * epoch): the state checkpoint moves in steps of 16,
// after a quiet stretch of 12 h or more n jumps to the next epoch (a multiple of 64). Pure, so it is tested.

export const EPOCH = 64;
export const CHECKPOINT = 16;
export const QUIET_SKIP_MS = 12 * 3600_000;
export const MAX_LOST_PER_GAP = 8;
export const LOSS_WINDOW_MS = 7 * 86_400_000;
export const LOSSY_RATE = 0.25;

/** The reports with a counter, in counter order, one per counter. */
export function reportsOf(positions) {
  const seen = new Set();
  return positions.filter((p) => p.n >= 0 && !seen.has(p.n) && seen.add(p.n)).sort((a, b) => a.n - b.n);
}

/**
 * The time between two reports the tag meant (ms), from the first report of the pair.
 * `plan` = { periodMin, modeIntervalS }: the app's idea of the normal period and of Lost or Track.
 */
export function periodMs(a, plan) {
  if (!a.quiet && a.nextInS > 0) return a.nextInS * 1000;
  const s = a.mode === 'LOST' ? (plan.modeIntervalS ?? 600) : a.mode === 'TRACK' ? (plan.modeIntervalS ?? 300) : plan.periodMin * 60;
  return Math.max(60_000, s * 1000);
}

/** kind: NORMAL | EPOCH_SKIP | RESTART | LOST (d - 1 reports lost on the way) */
export function classify(a, b, plan) {
  const d = b.n - a.n;
  const dt = b.receivedAt - a.receivedAt;
  let kind = 'LOST';
  if (d <= 1) kind = 'NORMAL';
  else if (b.n % EPOCH === 0 && a.quiet && dt >= QUIET_SKIP_MS) kind = 'EPOCH_SKIP';
  else if (b.boot) kind = 'RESTART'; // the boot report says so (firmware 0.9.4, BOOT bit)
  else if (b.n % CHECKPOINT === 0 && dt < ((d - 1) * periodMs(a, plan)) / 2) kind = 'RESTART';
  return { a, b, kind, d, lost: kind === 'LOST' ? Math.min(d - 1, MAX_LOST_PER_GAP) : 0 };
}

export function gapsOf(positions, plan) {
  const r = reportsOf(positions);
  const out = [];
  for (let i = 1; i < r.length; i++) out.push(classify(r[i - 1], r[i], plan));
  return out;
}

const inWindow = (positions, now) => reportsOf(positions).filter((p) => now - p.receivedAt >= 0 && now - p.receivedAt <= LOSS_WINDOW_MS);

/** { lost, of }: reports lost on the way and reports expected, over the last 7 days. */
export function lossCount(positions, plan, now) {
  const r = inWindow(positions, now);
  let lost = 0;
  for (let i = 1; i < r.length; i++) lost += classify(r[i - 1], r[i], plan).lost;
  return { lost, of: r.length + lost };
}

/** The share of reports lost on the way over the last 7 days; 0 with too little history. */
export function lossRate(positions, plan, now) {
  if (inWindow(positions, now).length < 4) return 0;
  const { lost, of } = lossCount(positions, plan, now);
  return lost / of;
}

/** Restarts the counters or the boot flag show, oldest first: { at, n, cause, resets, inferred }. */
export function bootsOf(positions, plan) {
  const seen = new Set();
  const out = [];
  for (const p of positions) {
    if (p.boot && p.n >= 0 && !seen.has(p.n)) { seen.add(p.n); out.push({ at: p.receivedAt, n: p.n, cause: p.bootCause ?? null, resets: p.bootResets ?? null, inferred: false }); }
  }
  for (const g of gapsOf(positions, plan)) {
    if (g.kind === 'RESTART' && !seen.has(g.b.n)) { seen.add(g.b.n); out.push({ at: g.b.receivedAt, n: g.b.n, cause: null, resets: null, inferred: true }); }
  }
  return out.sort((x, y) => x.at - y.at);
}

/**
 * A report that opens with a counter below the newest one and other content than the one stored under the same
 * counter: the tag's counter went back (a restored state, a second tag with the same key). A late copy of the same
 * report is not.
 */
export function counterBack(existing, p) {
  if (p.n < 0) return false;
  const newest = Math.max(-1, ...existing.filter((x) => x.n >= 0).map((x) => x.n));
  if (newest < 0 || p.n > newest - 1) return false;
  const same = existing.find((x) => x.n === p.n);
  if (!same) return false;
  return same.fixTime !== p.fixTime || same.battery !== p.battery || same.mode !== p.mode
    || (same.hasFix && p.hasFix && (same.lat !== p.lat || same.lon !== p.lon));
}

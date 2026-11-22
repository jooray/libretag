// Battery life estimate (port of the Android app's BatteryModel.kt, SPEC 11.10). Nothing until the reports cover two
// weeks. Then the drop rate in IDLE is fitted robustly (Theil-Sen) on the last 14 days, since the last charge (a rise of
// more than CHARGE_JUMP points). Stretches in TRACK, LISTEN or LOST are cut out: the drop across them does not count and
// neither does their time. The percentage comes from voltage and is flat above ~80 %, which lowers confidence.

export const DAY_MS = 86_400_000;
export const MIN_DAYS = 14;
export const CHARGE_JUMP = 5;
const CHARGE_K = 3;
/** "more than 6 months" */
export const MAX_REMAINING_HOURS = 6 * 30 * 24;
const MIN_FIT_DAYS = 2;
const MIN_FIT_SAMPLES = 5;
const MIN_PAIR_DAYS = 0.25; // pairs closer than 6 h only measure the 1 % quantisation
const MAX_FIT_POINTS = 400;

const valid = (samples) => samples.filter((s) => s.batteryPct >= 0 && s.batteryPct <= 100).sort((a, b) => a.timeMs - b.timeMs);

/** Samples { timeMs, batteryPct, mode } from the reports of a tag. */
export const samplesOf = (positions) => positions.filter((p) => p.battery != null && p.receivedAt > 0)
  .map((p) => ({ timeMs: p.receivedAt, batteryPct: p.battery, mode: p.mode }));

/** Days between the first and the last usable sample. */
export function daysOfData(samples) {
  const v = valid(samples);
  return v.length < 2 ? 0 : (v[v.length - 1].timeMs - v[0].timeMs) / DAY_MS;
}

const med = (l) => { const s = l.map((x) => x.batteryPct).sort((a, b) => a - b); return (s[Math.floor((s.length - 1) / 2)] + s[Math.floor(s.length / 2)]) / 2; };

/** A charge starts at i when the reading rises by more than CHARGE_JUMP points and the medians around it agree. */
function isCharge(s, i) {
  const before = s.slice(Math.max(0, i - CHARGE_K), i);
  const after = s.slice(i, Math.min(s.length, i + CHARGE_K));
  return s[i].batteryPct - s[i - 1].batteryPct > CHARGE_JUMP && med(after) - med(before) > CHARGE_JUMP;
}

/** Cumulative IDLE time vs cumulative IDLE drop, then the Theil-Sen slope: { rate %/day, days, drop } or null. */
function fit(seg) {
  const xs = [], ys = [];
  let x = 0, y = 0, started = false;
  for (let i = 1; i < seg.length; i++) {
    const a = seg[i - 1], b = seg[i];
    if (a.mode.toUpperCase() !== 'IDLE' || b.mode.toUpperCase() !== 'IDLE') continue;
    if (!started) { xs.push(x); ys.push(y); started = true; }
    x += (b.timeMs - a.timeMs) / DAY_MS;
    y += b.batteryPct - a.batteryPct;
    xs.push(x); ys.push(y);
  }
  if (xs.length < MIN_FIT_SAMPLES || x < MIN_FIT_DAYS) return null;
  const step = Math.max(1, Math.floor(xs.length / MAX_FIT_POINTS));
  const px = xs.filter((_, i) => i % step === 0), py = ys.filter((_, i) => i % step === 0);
  const slopes = [];
  for (let i = 0; i < px.length; i++) for (let j = i + 1; j < px.length; j++) {
    const dx = px[j] - px[i];
    if (dx >= MIN_PAIR_DAYS) slopes.push((py[j] - py[i]) / dx);
  }
  if (!slopes.length) return null;
  slopes.sort((a, b) => a - b);
  const m = slopes.length;
  const median = m % 2 === 1 ? slopes[(m - 1) / 2] : (slopes[m / 2 - 1] + slopes[m / 2]) / 2;
  return { rate: -median, days: x, drop: Math.abs(median * x) };
}

/** { remainingHours, ratePctPerDay, basedOnDays, confidence 0..2, capped } or null with under two weeks of data. */
export function estimate(samples) {
  const v = valid(samples);
  if (daysOfData(v) < MIN_DAYS) return null;
  const end = v[v.length - 1].timeMs;
  const recent = v.filter((s) => s.timeMs >= end - MIN_DAYS * DAY_MS);

  // segments between charges, newest first
  const segments = [];
  let start = 0, i = 1;
  while (i < recent.length) {
    if (isCharge(recent, i)) {
      segments.unshift(recent.slice(start, i));
      let top = i; // a slow charge seen in several reports: the new segment starts at the top
      while (top + 1 < recent.length && recent[top + 1].batteryPct > recent[top].batteryPct) top++;
      start = top;
      i = Math.max(top + 1, i + CHARGE_K);
    } else i++;
  }
  segments.unshift(recent.slice(start));

  for (let age = 0; age < segments.length; age++) {
    const seg = segments[age];
    const f = fit(seg);
    if (!f) continue;
    const current = recent[recent.length - 1].batteryPct;
    const capped = f.rate <= (current / MAX_REMAINING_HOURS) * 24;
    const hours = capped ? MAX_REMAINING_HOURS : Math.min(MAX_REMAINING_HOURS, (current / f.rate) * 24);
    let level = f.days >= 7 && f.drop >= 5 ? 2 : f.days >= 4 && f.drop >= 2 ? 1 : 0;
    if (age > 0) level--; // the pace before the last charge
    if (seg.reduce((s, x) => s + x.batteryPct, 0) / seg.length > 80) level--; // flat part of the Li-ion curve
    if (capped && f.days >= 7) level = Math.max(level, 1); // a flat line over a week is itself solid
    return { remainingHours: hours, ratePctPerDay: Math.max(0, f.rate), basedOnDays: f.days, confidence: Math.min(2, Math.max(0, level)), capped };
  }
  return null;
}

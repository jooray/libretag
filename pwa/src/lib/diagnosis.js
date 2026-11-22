// Why a tag is late (docs/design/tag-diagnosis.md; port of the part of the Android app's Diagnosis.kt and DiagText.kt that
// can be known from reports and relays alone). The web version has no cable, no Bluetooth and no node of its own, so
// those causes are left out. Pure: the browser's own state (online, relays) comes in as `ctx`.
import { distanceM } from './geo.js';
import { estimate, samplesOf } from './battery.js';
import { bootsOf, lossCount, periodMs } from './gaps.js';
import { t } from './i18n.js';
import { at, clock, duration, every } from './format.js';
import { gapPlan, lastPos, nextAt, reportPeriodMin, modeNow, LOW_BATTERY_PCT } from './status.js';

export const RESTART_WINDOW_MS = 3600_000;
export const BOOTS_WINDOW_MS = 6 * 3600_000;
export const EMPTY_PCT = 5;
export const STOPS_PCT = 7;
export const ZONE_EDGE_M = 50;
const DAY = 86_400_000;

const LEVEL = { PHONE_OFFLINE: 'NOTE', RELAYS_DOWN: 'NOTE', JUST_BACK: 'NOTE', RESTARTING: 'WARN', BATTERY_EMPTY: 'WARN',
  SCHEDULE_MISMATCH: 'NOTE', OUT_OF_RANGE: 'WARN', MAYBE_WITH_YOU: 'WARN', MAYBE_QUIET_PLACE: 'WARN', UNKNOWN: 'WARN' };

/** A tag is late: its expected report time has passed. A quiet report inside its heartbeat is not late. */
export function late(tag, now) {
  const next = nextAt(tag, now);
  return next != null && now > next;
}

/** The zone the last fix lies in, or within ZONE_EDGE_M of its edge. */
export function zoneNear(tag, p) {
  if (!tag.zones?.length || !p || !(p.hasFix || p.fixTime > 0)) return null;
  return tag.zones.find((z) => distanceM(p.lat, p.lon, z.lat, z.lon) <= z.r_m + ZONE_EDGE_M) ?? null;
}

/**
 * The tag restarts again and again: a boot report with two resets in a row, or two boots in 6 h (from the boot flag or
 * from the counters). Also while it still reports.
 */
export function restarting(tag, now) {
  const boots = bootsOf(tag.positions, gapPlan(tag)).filter((b) => now - b.at >= 0 && now - b.at <= BOOTS_WINDOW_MS);
  const reported = [...boots].reverse().find((b) => !b.inferred && (b.resets ?? 0) >= 2);
  if (reported) return { cause: 'RESTARTING', level: LEVEL.RESTARTING, confidence: 'HIGH', facts: { times: boots.map((b) => b.at), at: boots[0].at, count: reported.resets, word: reported.cause } };
  if (boots.length >= 2) return { cause: 'RESTARTING', level: LEVEL.RESTARTING, confidence: boots.some((b) => !b.inferred) ? 'HIGH' : 'MEDIUM', facts: { times: boots.map((b) => b.at), at: boots[0].at } };
  return null;
}

function batteryEmpty(tag, now) {
  const p = lastPos(tag);
  if (!p) return null;
  const b = p.battery;
  const mk = (confidence) => ({ cause: 'BATTERY_EMPTY', level: LEVEL.BATTERY_EMPTY, confidence, facts: { at: p.receivedAt, count: b } });
  if (b != null && b <= EMPTY_PCT) return mk('HIGH');
  if (p.lowBatt && b != null) {
    const est = estimate(samplesOf(tag.positions));
    if (est && !est.capped && est.ratePctPerDay > 0) {
      const days = (now - p.receivedAt) / DAY;
      if (b - est.ratePctPerDay * days <= STOPS_PCT) return mk('MEDIUM');
    }
  }
  return null;
}

/** The tag announces a period that is not the one the app works with. */
function scheduleMismatch(tag, now) {
  const p = lastPos(tag);
  if (!p || p.mode !== 'IDLE' || p.quiet || !p.nextInS || modeNow(tag, now) !== 'IDLE') return null;
  const appS = reportPeriodMin(tag) * 60;
  if (Math.abs(p.nextInS - appS) > appS * 0.15 && p.nextInS < 3600 * 34) {
    return { cause: 'SCHEDULE_MISMATCH', level: LEVEL.SCHEDULE_MISMATCH, confidence: 'MEDIUM', facts: { periodS: p.nextInS, period2S: appS } };
  }
  return null;
}

/** the last two reports heard no repeater */
function outOfRange(tag) {
  const r = tag.positions.slice(-2);
  return r.length === 2 && r.every((p) => p.nbTotal === 0);
}

/**
 * The headline cause for a late tag, first match in the order of the design, or null when the tag is not late (or never
 * heard). `ctx` = { online, relaysUp, relaysAll, syncing }. A WARN cause shows once the status is SILENT, a NOTE at once.
 */
export function diagnose(tag, ctx, now = Date.now()) {
  if (!late(tag, now)) return null;
  const p = lastPos(tag);
  if (!p) return null;
  const res = (cause, confidence, facts = {}) => ({ cause, level: LEVEL[cause], confidence, facts });
  if (!ctx.online) return res('PHONE_OFFLINE', 'HIGH', { at: p.receivedAt });
  if (ctx.relaysUp === 0 && ctx.relaysAll > 0) return res('RELAYS_DOWN', 'HIGH', { at: p.receivedAt, count: ctx.relaysAll });
  if (ctx.syncing) return res('JUST_BACK', 'HIGH');
  const r = restarting(tag, now);
  if (r) return r;
  const empty = batteryEmpty(tag, now);
  if (empty) return empty;
  const mism = scheduleMismatch(tag, now);
  if (mism) return mism;
  if (outOfRange(tag)) return res('OUT_OF_RANGE', 'LOW', { periodS: reportPeriodMin(tag) * 60 });
  const zone = zoneNear(tag, p);
  const anchors = tag.extra?.anchors?.length > 0;
  if (!zone && anchors && tag.extra?.use !== 'people' && !p.quiet) return res('MAYBE_WITH_YOU', 'LOW');
  if (zone && !p.quiet) return res('MAYBE_QUIET_PLACE', 'LOW', { name: zone.name });
  return res('UNKNOWN', 'NONE', { at: p.receivedAt });
}

/** The cause is shown now: NOTE as soon as late, WARN once the tag is SILENT. */
export const shown = (d, s) => !!d && (d.level !== 'WARN' || s.kind === 'SILENT');

/** The row's band: a WARN or CRIT status, unless a shown NOTE cause explains a silence. */
export function band(d, s, level) {
  if (level === 'crit') return true;
  if (level !== 'warn') return false;
  return !(s.kind === 'SILENT' && d && d.level !== 'WARN');
}

// ------------------------------------------------------------------ findings and checks

/** Things worth a hint even while the tag reports normally. */
export function findings(tag, ctx, now = Date.now()) {
  const out = [];
  const plan = gapPlan(tag);
  const r = tag.positions.filter((p) => p.n >= 0).sort((a, b) => a.n - b.n);
  if (r.length >= 2) {
    const a = r[r.length - 2], b = r[r.length - 1];
    if (a.battery != null && b.battery != null && a.battery - b.battery >= 25 && !b.boot && b.receivedAt - a.receivedAt < 3 * periodMs(a, plan)) {
      out.push({ finding: 'BATTERY_CLIFF', facts: { count: a.battery - b.battery, at: b.receivedAt } });
    }
  }
  let k = r.length;
  while (k > 0 && !r[k - 1].hasFix) k--;
  const noFix = r.slice(k);
  if (noFix.length >= 3 && r.length > noFix.length) out.push({ finding: 'NO_GPS_RUN', facts: { at: noFix[0].receivedAt, count: noFix.length } });
  if (tag.counterBackAt && now - tag.counterBackAt < 7 * DAY) out.push({ finding: 'COUNTER_BACK', facts: { at: tag.counterBackAt } });
  const p = lastPos(tag);
  const ns = p?.nextInS;
  if (p && ns && (p.mode === 'TRACK' || p.mode === 'LOST') && ns >= 3600 && (tag.modeIntervalS ?? (p.mode === 'LOST' ? 600 : 300)) < 3600) {
    out.push({ finding: 'FLOOD_CAP', facts: { periodS: ns } });
  }
  const rs = restarting(tag, now);
  if (rs) out.push({ finding: 'RESTARTING', facts: rs.facts });
  return out;
}

/** "All checks": one row per input { check, state: OK | WARN | UNKNOWN, facts }. */
export function checklist(tag, ctx, now = Date.now()) {
  const p = lastPos(tag);
  const plan = gapPlan(tag);
  const out = [];
  out.push({ check: 'ONLINE', state: ctx.online ? 'OK' : 'WARN', facts: {} });
  out.push({ check: 'RELAYS', state: ctx.relaysAll === 0 ? 'UNKNOWN' : ctx.relaysUp === 0 ? 'WARN' : 'OK', facts: { count: ctx.relaysUp, of: ctx.relaysAll } });
  out.push({ check: 'LAST_REPORT', state: !p ? 'UNKNOWN' : late(tag, now) ? 'WARN' : 'OK', facts: { at: p?.receivedAt, count: p?.n } });
  const loss = lossCount(tag.positions, plan, now);
  out.push({ check: 'LOST', state: loss.of === 0 ? 'UNKNOWN' : loss.lost * 4 >= loss.of ? 'WARN' : 'OK', facts: { count: loss.lost, of: loss.of } });
  out.push({ check: 'BATTERY', state: p?.battery == null ? 'UNKNOWN' : p.battery <= LOW_BATTERY_PCT || p.lowBatt ? 'WARN' : 'OK', facts: { count: p?.battery, at: p?.receivedAt } });
  const boots = bootsOf(tag.positions, plan);
  out.push({ check: 'RESTARTS', state: restarting(tag, now) ? 'WARN' : boots.length ? 'OK' : 'UNKNOWN', facts: { at: boots.at(-1)?.at, count: boots.length } });
  out.push({ check: 'SCHEDULE', state: scheduleMismatch(tag, now) ? 'WARN' : 'OK', facts: { periodS: reportPeriodMin(tag) * 60, period2S: lastPos(tag)?.nextInS ?? null } });
  return out;
}

// ------------------------------------------------------------------ words

const pct = (n) => t('batt.value', { n });
const when = (ms, now) => (ms == null ? '?' : at(ms, now));
const silentFor = (tag, s, now) => duration(s.silentForMs > 0 ? s.silentForMs : now - (lastPos(tag)?.receivedAt ?? now));

/** The row sentence (and the plate's first line) for a shown cause. */
export function rowText(tag, s, d, now = Date.now()) {
  const f = d.facts;
  const heard = when(lastPos(tag)?.receivedAt, now);
  switch (d.cause) {
    case 'PHONE_OFFLINE': return t('dg.row_phone_offline', { t: heard });
    case 'RELAYS_DOWN': return t('dg.row_relays_down', { t: heard });
    case 'JUST_BACK': return t('dg.row_just_back');
    case 'RESTARTING': { const n = Math.max(f.times?.length ?? 0, f.count ?? 2); return t('dg.row_restarting', { n, t: when(f.at ?? f.times?.[0], now) }); }
    case 'BATTERY_EMPTY': return f.count != null ? t('dg.row_battery_empty', { pct: pct(f.count), t: when(f.at, now) }) : t('dg.row_battery_empty_unknown');
    case 'SCHEDULE_MISMATCH': return t('dg.row_schedule_mismatch', { a: every(f.periodS), b: every(f.period2S) });
    case 'OUT_OF_RANGE': return t('dg.row_out_of_range', { d: silentFor(tag, s, now) });
    case 'MAYBE_WITH_YOU': return t('dg.row_maybe_with_you', { d: silentFor(tag, s, now) });
    case 'MAYBE_QUIET_PLACE': return t('dg.row_maybe_quiet_place', { d: silentFor(tag, s, now), zone: f.name });
    default: return null;
  }
}

/** The Why card's evidence for a cause. */
export function cardText(tag, s, d, now = Date.now()) {
  const f = d.facts;
  const heard = when(lastPos(tag)?.receivedAt, now);
  switch (d.cause) {
    case 'PHONE_OFFLINE': return t('dg.card_phone_offline', { t: heard });
    case 'RELAYS_DOWN': return t('dg.card_relays_down', { n: f.count ?? 1 });
    case 'JUST_BACK': return t('dg.card_just_back');
    case 'RESTARTING': {
      const times = (f.times?.length ? f.times : [f.at]).filter((x) => x != null).map((x) => clock(x, now)).join(', ');
      return f.count ? t('dg.card_restarting_resets', { n: f.count, cause: f.word ?? '?', t: times }) : t('dg.card_restarting', { t: times });
    }
    case 'BATTERY_EMPTY': return t('dg.card_battery_empty', { t: when(f.at, now), pct: f.count != null ? pct(f.count) : '?' });
    case 'SCHEDULE_MISMATCH': return t('dg.card_schedule_mismatch', { a: every(f.periodS), b: every(f.period2S) });
    case 'OUT_OF_RANGE': return t('dg.card_out_of_range', { every: every(f.periodS) });
    case 'MAYBE_WITH_YOU': return t('dg.card_maybe_with_you');
    case 'MAYBE_QUIET_PLACE': return t('dg.card_maybe_quiet_place', { zone: f.name });
    default: return t('dg.card_unknown', { t: heard });
  }
}

/** The calm "one report missed" card. */
export const missedCard = (s, now = Date.now()) => t('dg.card_missed', { t: clock(s.nextAt ?? now, now) });

/** Actions the web version can do about a cause, at most two. */
export function actionsOf(d, tag) {
  switch (d.cause) {
    case 'RELAYS_DOWN': return ['relays', 'retry'];
    case 'SCHEDULE_MISMATCH': return tag.chain?.canCommand ? ['use_tag_schedule', 'send_mine'] : ['use_tag_schedule'];
    case 'BATTERY_EMPTY': case 'OUT_OF_RANGE': case 'MAYBE_QUIET_PLACE': return ['map'];
    default: return [];
  }
}

export const findingText = (f, now = Date.now()) => {
  const x = f.facts;
  switch (f.finding) {
    case 'BATTERY_CLIFF': return t('dg.find_battery_cliff', { n: x.count });
    case 'NO_GPS_RUN': return t('dg.find_no_gps_run', { t: when(x.at, now) });
    case 'COUNTER_BACK': return t('dg.find_counter_back', { t: when(x.at, now) });
    case 'FLOOD_CAP': return t('dg.find_flood_cap', { every: every(x.periodS ?? 3600) });
    default: return t('dg.find_restarting', { t: when(x.at, now) });
  }
};

/** One checklist row as [key, value]. */
export function checkText(r, now = Date.now()) {
  const f = r.facts;
  const unknown = t('dg.val_unknown');
  switch (r.check) {
    case 'ONLINE': return [t('dg.chk_online'), t(r.state === 'OK' ? 'dg.val_yes' : 'dg.val_no')];
    case 'RELAYS': return [t('dg.chk_relays'), t('dg.val_relays', { n: f.count ?? 0, m: f.of ?? 0 })];
    case 'LAST_REPORT': return [t('dg.chk_last_report'), f.at ? t('dg.val_last_report', { t: at(f.at, now), n: f.count }) : unknown];
    case 'LOST': return [t('dg.chk_lost'), f.of ? t('dg.val_lost', { a: f.count, b: f.of }) : unknown];
    case 'BATTERY': return [t('dg.chk_battery'), f.count != null ? t('dg.val_battery', { pct: pct(f.count), t: when(f.at, now) }) : unknown];
    case 'RESTARTS': return [t('dg.chk_restarts'), f.count ? t('dg.val_restarts', { n: f.count, t: when(f.at, now) }) : t('dg.val_none_known')];
    default: return [t('dg.chk_schedule'), t('dg.val_schedule', { a: every(f.periodS ?? 3600), b: f.period2S ? every(f.period2S) : unknown })];
  }
}

/** The status sentence a row and the plate show: the cause when it is shown, else null (the caller keeps the status). */
export const causeText = (tag, s, d, now = Date.now()) => (shown(d, s) && d.cause !== 'UNKNOWN' ? rowText(tag, s, d, now) : null);

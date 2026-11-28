// Status of a tag: port of the Android app's Status.kt / TagText.kt / Timeline.kt, pure and tested.
import { slotKey, slotOffsets } from './quietmesh.js';
import { clock, ageShort, agoAt, at, heardRow, coord, distance, duration, every } from './format.js';
import { t } from './i18n.js';
import { distanceM, zoneOf } from './geo.js';
import { LOSSY_RATE, lossRate } from './gaps.js';

export const LOW_BATTERY_PCT = 20;
/** Firmware 0.7.6 sends a report when the battery crosses 10 %: from there on it is nearly empty. */
export const CRITICAL_BATTERY_PCT = 10;
export const SILENT_MARGIN_MS = 30 * 60_000;
export const HEARTBEAT_MS = 24 * 3600_000;
export const LOST_INTERVAL_S = 600;
export const TRACK_INTERVAL_S = 300;
export const IDLE_CHOICES_MIN = [15, 30, 60, 120, 180, 360];
export const GAP_MIN_MS = 2 * 3600_000;
/** Listening at a check, seconds (firmware 0.7.3 default; 60 in 0.7.2, 20 before). */
export const CHECK_S_DEFAULT = 30;

/** The report interval the tag announces in IDLE (next_in of a normal report), snapped to the app's choices. */
export function inferIntervalMin(positions) {
  for (let i = positions.length - 1; i >= 0; i--) {
    const p = positions[i];
    if (p.mode === 'IDLE' && !p.quiet && p.nextInS) {
      const m = p.nextInS / 60;
      const hit = IDLE_CHOICES_MIN.find((c) => Math.abs(c - m) <= c * 0.12);
      if (hit) return hit;
    }
  }
  return null;
}

/** 0.7.3: the tag reports at every n-th wake-up and only listens for commands at the others (backup report_every). */
export const reportEveryOf = (tag) => {
  const r = tag.extra?.report_every;
  return Number.isInteger(r) && r >= 1 ? Math.min(r, 24) : 1;
};
/** minutes between wake-ups: the user's choice, else the detected one. null = unknown (then no check times). */
export const intervalMinOf = (tag) => {
  if (tag.intervalMin != null) return tag.intervalMin;
  const m = inferIntervalMin(tag.positions); // next_in of a normal report: the report period
  return m == null ? null : Math.max(1, Math.round(m / reportEveryOf(tag)));
};
/** The tag's listening time at checks from the backup (check_s); a backup without it means the default. */
export const checkSOf = (tag) => Number.isFinite(tag.extra?.check_s) ? tag.extra.check_s : CHECK_S_DEFAULT;
/** The app's idea of the normal report period (min): the wake-up interval times report_every, 60 when unknown. */
export const reportPeriodMin = (tag) => (intervalMinOf(tag) ?? 60) * reportEveryOf(tag);
/** What gaps.js needs to tell a lost report from a restart. */
export const gapPlan = (tag) => ({ periodMin: reportPeriodMin(tag), modeIntervalS: tag.modeIntervalS ?? null });

/** Moving more than this from the guarded spot raises the alarm (Android: GUARD_M). */
export const GUARD_M = 300;
/** A departure from a place shows as a neutral state this long (Android: Places.LEFT_SHOW_MS). */
export const LEFT_SHOW_MS = 12 * 3600_000;
/** The guarded spot { lat, lon, r_m, since } of the backup, or null. */
export const guardOf = (tag) => { const g = tag.extra?.guard; return g && Number.isFinite(g.lat) && Number.isFinite(g.lon) ? g : null; };
export const lastPos = (tag) => tag.positions.length ? tag.positions[tag.positions.length - 1] : null;

/** "LOST", "TRACK" or "IDLE": the mode the tag runs now (a Lost or Track that ran out is IDLE). */
export function modeNow(tag, now) {
  const m = lastPos(tag)?.mode;
  return modeLive(tag, now) && (m === 'LOST' || m === 'TRACK') ? m : 'IDLE';
}

function modeLive(tag, now) { return tag.modeUntil == null || now < tag.modeUntil; }

export function intervalS(tag, p, now) {
  const mode = p?.mode ?? 'IDLE';
  const live = modeLive(tag, now);
  if (mode === 'LOST' && live) return tag.modeIntervalS ?? LOST_INTERVAL_S;
  if (mode === 'TRACK' && live) return tag.modeIntervalS ?? TRACK_INTERVAL_S;
  return (intervalMinOf(tag) ?? 60) * reportEveryOf(tag) * 60;
}

/** When the next report is expected (ms): next_in when the report carried it, else the interval. */
export function nextAt(tag, now) {
  const p = lastPos(tag);
  if (!p) return null;
  // an approximate report (0.8.1) was heard at the latest when the bridge drained it
  const from = p.approxUntil ?? p.receivedAt;
  if (p.nextInS) return from + p.nextInS * 1000;
  if (p.quiet) return from + HEARTBEAT_MS;
  return from + intervalS(tag, p, now) * 1000;
}

/**
 * Expected reports that did not come (docs/design/tag-diagnosis.md 6): 0 while now is within SILENT_MARGIN_MS of the
 * expected time, then one more for every period the tag kept since.
 */
export function missedOf(tag, now) {
  const p = lastPos(tag);
  const next = nextAt(tag, now);
  if (!p || next == null || now <= next + SILENT_MARGIN_MS) return 0;
  const per = intervalS(tag, p, now) * 1000;
  if (p.quiet || per <= 0) return 1;
  return Math.min(1000, Math.floor(1 + (now - next - SILENT_MARGIN_MS) / per));
}

/**
 * How many missed reports are tolerated before "silent": none for a guarded tag, in Lost or Track, or after a quiet
 * report (a heartbeat); one otherwise; two on a lossy path (a quarter of the reports lost over 7 days).
 */
export function allowedMissed(tag, now) {
  const p = lastPos(tag);
  if (!p) return 0;
  if (guardOf(tag) || p.quiet) return 0;
  if (modeNow(tag, now) !== 'IDLE') return 0;
  return lossRate(tag.positions, gapPlan(tag), now) >= LOSSY_RATE ? 2 : 1;
}

/** The slot grid of anchor report n at t0 (ms) for this tag's interval (PROTOCOL.md 2.6), or null without keys. */
export function gridOf(tag, g) {
  const im = intervalMinOf(tag);
  if (!g || im == null || !tag.chain?.canCommand) return null;
  let ce;
  try { ce = tag.chain.cmd(Math.floor(g.n / 64)); } catch { return null; }
  if (!ce) return null;
  tag._grids ??= new Map();
  const key = g.n + ':' + g.t0 + ':' + im;
  if (!tag._grids.has(key)) tag._grids.set(key, slotOffsets(slotKey(ce, g.n), im * 60, Math.min(400, Math.floor(50 * 60 / im) + 2)));
  return { n: g.n, t0: g.t0, intervalMin: im, checkS: checkSOf(tag), offsetsS: tag._grids.get(key), vias: g.vias ?? [] };
}

/** Every grid a command is sent on (the confirmed anchor and its candidates), IDLE only. */
export function sendGrids(tag, now = Date.now()) {
  const a = tag.anchor;
  if (!a || modeNow(tag, now) !== 'IDLE') return [];
  return [...(a.a ? [a.a] : []), ...(a.cands ?? [])].map((g) => gridOf(tag, g)).filter(Boolean);
}

/** The tag's listening slots: the confirmed grid of its anchor, while known and in IDLE (else no time is shown). */
export function checkPlan(tag, now = Date.now()) {
  const a = tag.anchor;
  if (!a?.a || a.known === false || modeNow(tag, now) !== 'IDLE') return null;
  return gridOf(tag, a.a);
}

/** "schedule not known": the browser has no grid it is sure of. */
export const scheduleUnknown = (tag, now = Date.now()) =>
  !!lastPos(tag) && tag.chain?.canCommand && modeNow(tag, now) === 'IDLE' && (!tag.anchor?.a || tag.anchor.known === false);

export function nextCheck(plan, now) {
  if (!plan) return null;
  for (let i = 0; i < plan.offsetsS.length; i++) {
    const at = plan.t0 + plan.offsetsS[i] * 1000;
    if (at >= now) return { k: i + 1, at };
  }
  return null;
}

/**
 * kind: LOST > ALARM (guard) > SILENT > BATTERY_CRITICAL (<= 10 %) > LOW_BATTERY > TRACKING > QUIET > OK, or WAITING.
 * Zones come from the backup; a quiet report in none of them is shown as Home. LEFT is a neutral event (left a place, 12 h).
 */
export function statusOf(tag, now = Date.now()) {
  const p = lastPos(tag);
  const next = nextAt(tag, now);
  // approx: heardAt is only the earliest time, nextAt the latest the next report is due ("by")
  const base = { kind: 'OK', heardAt: p?.receivedAt ?? null, nextAt: next, battery: p?.battery ?? null, approx: p?.approxUntil != null };
  if (!p) return { ...base, kind: 'WAITING' };
  const live = modeLive(tag, now);
  if (p.mode === 'LOST' && live) return { ...base, kind: 'LOST', until: tag.modeUntil, intervalS: intervalS(tag, p, now) };
  if (tag.alarm?.guard) return { ...base, kind: 'ALARM', alarm: tag.alarm };
  const missed = missedOf(tag, now);
  if (missed > allowedMissed(tag, now)) {
    return { ...base, kind: 'SILENT', nextAt: null, silentForMs: now - p.receivedAt,
      intervalS: p.quiet ? HEARTBEAT_MS / 1000 : intervalS(tag, p, now) };
  }
  // one report lost on the way is normal on a shared mesh: the next one is projected
  const projected = missed > 0 ? { missed, nextAt: next + missed * intervalS(tag, p, now) * 1000 } : {};
  Object.assign(base, projected);
  if (p.battery != null && p.battery <= CRITICAL_BATTERY_PCT) return { ...base, kind: 'BATTERY_CRITICAL' };
  if (p.lowBatt || (p.battery != null && p.battery <= LOW_BATTERY_PCT)) return { ...base, kind: 'LOW_BATTERY' };
  if (p.mode === 'TRACK' && live) return { ...base, kind: 'TRACKING', until: tag.modeUntil, intervalS: intervalS(tag, p, now) };
  if (p.quiet) {
    const plan = checkPlan(tag);
    const nc = nextCheck(plan, now);
    return { ...base, kind: 'QUIET', quietWhere: p.quietReason,
      nextCheckAt: nc && (next == null || nc.at < next) ? nc.at : null,
      checkEveryS: plan ? plan.intervalMin * 60 : null };
  }
  // left a place a while ago and not at one again: an event, shown as a neutral state
  const left = tag.leftPlace && tag.leftAt && !tag.inZone && now - tag.leftAt >= 0 && now - tag.leftAt < LEFT_SHOW_MS
    ? { ...base, kind: 'LEFT', leftPlace: tag.leftPlace, leftAt: tag.leftAt } : base;
  const plan = checkPlan(tag);
  const nc = nextCheck(plan, now);
  if (nc && (next == null || nc.at < next) && !left.missed) return { ...left, nextCheckAt: nc.at, checkEveryS: plan.intervalMin * 60 };
  return left;
}

/** Name of the zone the tag's last fix is in, else "Home" (the backup may carry no zones). */
export const zoneName = (tag, p) => zoneOf(tag.zones, p)?.name ?? t('zone.home');

export const priorityOf = (s) => ({ LOST: 0, ALARM: 1, SILENT: 2, BATTERY_CRITICAL: 3, LOW_BATTERY: 4 }[s.kind] ?? 9);
export const levelOf = (s) => (s.kind === 'LOST' || s.kind === 'ALARM' || s.kind === 'BATTERY_CRITICAL' ? 'crit' : s.kind === 'SILENT' || s.kind === 'LOW_BATTERY' ? 'warn' : '');

export function sortTags(tags, now = Date.now()) {
  return tags.map((x) => [x, statusOf(x, now)])
    .sort((a, b) => priorityOf(a[1]) - priorityOf(b[1]) || (b[1].heardAt ?? 0) - (a[1].heardAt ?? 0))
    .map((x) => x[0]);
}

// ------------------------------------------------------------------ sentences

/** The one status sentence. compact: a list row (one short "heard", no next report). */
export function statusText(tag, s, now = Date.now(), compact = false) {
  const zone = zoneName(tag, lastPos(tag));
  switch (s.kind) {
    case 'ALARM': return t('st.alarm_guard');
    case 'LEFT': return t('st.left', { zone: s.leftPlace, t: at(s.leftAt, now) });
    case 'LOST': return t('st.lost', { every: every(s.intervalS ?? LOST_INTERVAL_S) });
    case 'SILENT': return t('st.silent', { dur: duration(s.silentForMs), every: every(s.intervalS ?? 3600) });
    case 'BATTERY_CRITICAL': return t('st.battery_critical', { n: s.battery });
    case 'LOW_BATTERY': return s.battery != null ? t('st.low_battery', { n: s.battery }) : t('st.low_battery_unknown');
    case 'TRACKING': {
      const ev = every(s.intervalS ?? TRACK_INTERVAL_S);
      return s.until ? t('st.tracking_until', { every: ev, t: clock(s.until, now) }) : t('st.tracking', { every: ev });
    }
    case 'QUIET': {
      const check = s.nextAt ? clock(s.nextAt, now) : '?';
      if (s.quietWhere === 'owner') return t('st.quiet_owner', { t: check });
      if (s.quietWhere === 'parked') return t('st.quiet_parked', { t: check });
      return t('st.quiet_zone', { zone, t: check });
    }
    case 'WAITING': return t('st.waiting');
    default: {
      if (compact) return heardRow(s.heardAt, now);
      if (s.missed) return t('st.missed', { n: s.missed, t: '~' + clock(s.nextAt, now) });
      const heard = agoAt(s.heardAt, now, s.approx);
      // an approximate report: only the latest time of the next one, in the detail
      if (s.nextAt == null || s.approx) return t('st.ok_no_next', { heard });
      return t(s.nextAt < now ? 'st.ok_late' : 'st.ok', { heard, t: clock(s.nextAt, now) });
    }
  }
}

export function statusSub(tag, s, now = Date.now()) {
  const heard = s.heardAt ? agoAt(s.heardAt, now, s.approx) : '';
  const heardNext = s.nextAt && !s.approx ? t('sub.heard_next', { heard, t: clock(s.nextAt, now) }) : t('sub.heard', { heard });
  switch (s.kind) {
    case 'SILENT': {
      const iv = Math.max(60, s.intervalS ?? 3600);
      const missed = Math.floor(s.silentForMs / 1000 / iv);
      if (s.battery != null && s.battery <= CRITICAL_BATTERY_PCT) return t('sub.silent_battery', { t: at(s.heardAt ?? now, now), n: s.battery });
      return t('sub.silent', { t: at(s.heardAt ?? now, now), missed: t('sub.missed', { n: missed }) });
    }
    case 'QUIET': {
      const since = (s.approx ? '~' : '') + at(s.heardAt ?? now, now);
      const checks = s.checkEveryS != null;
      if (s.quietWhere === 'parked') return t('sub.quiet_parked', { t: since });
      if (s.quietWhere === 'owner') return t(checks ? 'sub.quiet_owner_checks' : 'sub.quiet_owner', { t: since });
      return t(checks ? 'sub.quiet_zone_checks' : 'sub.quiet_zone', { zone: zoneName(tag, lastPos(tag)), t: since });
    }
    case 'LOST': return s.until ? t('sub.lost_until', { base: heardNext, t: clock(s.until, now) }) : heardNext;
    case 'BATTERY_CRITICAL': return t('sub.battery_critical', { base: heardNext });
    case 'ALARM': return t('sub.guard_left', { t: at(s.alarm.at, now) });
    case 'LOW_BATTERY': case 'TRACKING': return heardNext;
    case 'LEFT': return heardNext;
    case 'WAITING': return t('sub.waiting');
    default: return tag.share ? sharedLabel(tag) : t('sub.ok');
  }
}

/** Whose a shared tag is, for the detail: "Shared with you by Anna · full access". */
export function sharedLabel(tag) {
  const level = tag.share.uk ? 'full' : tag.share.ce ? 'control' : 'view';
  return tag.sharedBy ? t('shared.by_' + level, { by: tag.sharedBy }) : t('shared.label_' + level);
}

/** Where and how sure ("GPS ±8 m · 48.70120, 21.25410", "No GPS · last GPS 3 h ago"). */
export function positionText(p, latest = true, now = Date.now()) {
  if (!p) return t('pos.none');
  if (p.hasFix) {
    const fixMs = p.fixTime > 0 ? p.fixTime * 1000 : p.receivedAt;
    const c = coord(p.lat, p.lon);
    if (latest && now - p.receivedAt > 90 * 60_000) return t('pos.last_gps', { ago: agoAt(fixMs, now), coord: c });
    return p.haccM != null && p.haccM !== 255
      ? t('pos.gps', { acc: distance(p.haccM), coord: c }) : t('pos.gps_noacc', { coord: c });
  }
  if (p.fixTime > 0) return t('pos.no_gps_last', { ago: agoAt(p.fixTime * 1000, now) });
  if (p.nbTotal === 0) return t('pos.no_repeater');
  return t('pos.web_area');
}

/** When a position was taken: the fix time when the report carries one, else when it was received. */
export const fixAt = (p) => (p.hasFix && p.fixTime > 0 ? p.fixTime * 1000 : p.receivedAt);

/** Where to put the tag on the map: the fix, else the last fix it carries, else the newest older fix. */
export function mapTarget(tag, p) {
  if (!p) return null;
  if (p.hasFix) return { lat: p.lat, lon: p.lon, exact: true, at: fixAt(p) };
  if (p.fixTime > 0) return { lat: p.lat, lon: p.lon, exact: false, at: p.fixTime * 1000 };
  const prev = [...tag.positions].reverse().find((x) => x.hasFix && x.n < p.n);
  return prev ? { lat: prev.lat, lon: prev.lon, exact: false, at: fixAt(prev) } : null;
}

export function cmdName(kind) {
  return t({ LOST: 'cmd.lost', TRACK: 'cmd.track', BEEP: 'cmd.beep', NORMAL: 'cmd.normal', INTERVAL: 'cmd.interval' }[kind]);
}

export function effectText(tag, s, now = Date.now()) {
  if (s.kind === 'WAITING') return t('eff.first');
  if (s.kind === 'SILENT') return t('eff.silent', { name: tag.name, every: every(s.intervalS ?? 3600) });
  if (s.nextCheckAt) return t('eff.check', { t: clock(s.nextCheckAt, now) });
  if ((s.kind === 'QUIET' || s.approx) && s.nextAt) return t('eff.quiet', { t: clock(s.nextAt, now) });
  if (s.nextAt) return t('eff.next', { t: clock(s.nextAt, now) });
  return t('eff.first');
}

export function queuedText(tag, kind, s, now = Date.now()) {
  const c = cmdName(kind);
  if (s.kind === 'SILENT') return t('snack.cmd_silent', { c, name: tag.name });
  if (s.nextCheckAt) return t('snack.cmd_check', { c, t: clock(s.nextCheckAt, now) });
  if ((s.kind === 'QUIET' || s.approx) && s.nextAt) return t('snack.cmd_quiet', { c, t: clock(s.nextAt, now) });
  if (s.nextAt) return t('snack.cmd_next', { c, t: clock(s.nextAt, now) });
  return t('snack.cmd_first', { c });
}

/** The slip under a row: waiting, confirmed or failed. */
export function slip(tag, s, now = Date.now()) {
  const p = tag.pending;
  if (p) {
    const c = p.kind === 'TRACK' && p.durationMin ? t('cmd.track_for', { d: duration(p.durationMin * 60_000), every: every(p.intervalS ?? TRACK_INTERVAL_S) })
      : p.kind === 'INTERVAL' && p.wakeMin ? t('cmd.interval_to', { every: every(p.wakeMin * (p.every ?? 1) * 60) }) : cmdName(p.kind);
    if (p.notConfirmed) return { state: 'fail', text: t('slip.failed', { c }) };
    if (p.sentAt) return { state: 'sent', text: t('slip.sent', { c, t: at(p.sentAt, now) }) };
    if (s.kind === 'SILENT') return { state: 'wait', text: t('slip.waiting_silent', { c, name: tag.name }) };
    if (s.nextCheckAt) return { state: 'wait', text: t('slip.waiting_check', { c, t: clock(s.nextCheckAt, now) }) };
    // 2.6: no grid the browser is sure of: no time; entries still go out on the grids it has
    if (scheduleUnknown(tag, now)) return { state: 'wait', text: t('slip.schedule_unknown', { name: tag.name }) };
    if (s.nextAt) return { state: 'wait', text: t('slip.waiting', { c, t: clock(s.nextAt, now) }) };
    return { state: 'wait', text: t('slip.waiting_first', { c }) };
  }
  const l = tag.lastCommand;
  if (l && now - l.confirmedAt <= 6 * 3600_000) return { state: 'done', text: t('slip.confirmed', { c: cmdName(l.kind), t: at(l.confirmedAt, now) }) };
  return null;
}

export function quietTimes(s, now = Date.now()) {
  const out = [];
  if (s.nextAt) out.push(t('when.checkin', { t: clock(s.nextAt, now) }));
  if (s.nextCheckAt && s.checkEveryS) out.push(t('when.checks', { every: every(s.checkEveryS), t: clock(s.nextCheckAt, now) }));
  return out;
}

// ------------------------------------------------------------------ timeline (Timeline.kt)

/** Days (newest first) of items oldest first: report | quiet {from,to,reason} | gap {from,to}. */
export function timelineDays(tag, now = Date.now(), maxDays = 14) {
  const ps = tag.positions.filter((p) => p.receivedAt > 0).sort((a, b) => a.receivedAt - b.receivedAt);
  if (!ps.length) return [];
  const gapMs = Math.max(GAP_MIN_MS, ((intervalMinOf(tag) ?? 60) * 60_000 * 5) / 2);
  const dayKey = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`; };
  const byDay = new Map();
  const get = (ms) => { const k = dayKey(ms); if (!byDay.has(k)) byDay.set(k, { at: ms, items: [] }); return byDay.get(k); };
  let prev = null;
  for (const p of ps) {
    const d = get(p.receivedAt);
    if (prev && p.receivedAt - prev.receivedAt > gapMs) {
      d.items.push(prev.quiet
        ? { type: 'quiet', from: prev.receivedAt, to: p.receivedAt, reason: prev.quietReason, zone: zoneName(tag, prev) }
        : { type: 'gap', from: prev.receivedAt, to: p.receivedAt });
    }
    d.items.push({ type: 'report', p });
    prev = p;
  }
  if (prev.quiet) get(now).items.push({ type: 'quiet', from: prev.receivedAt, to: null, reason: prev.quietReason, zone: zoneName(tag, prev) });
  return [...byDay.values()].sort((a, b) => b.at - a.at).slice(0, maxDays);
}

export const stationText = (p) => p.hasFix
  ? (p.haccM != null && p.haccM !== 255 ? t('stn.gps', { acc: distance(p.haccM) }) : t('stn.gps_noacc'))
  : t('stn.no_gps');
export { ageShort };

// Commands over the Nostr mailbox (PROTOCOL.md 4, 4.1, 4.2; checks-0.7.md): kind 4244, no login.
// Signed mailbox: every event for report n is signed with sk_n = HMAC(C_e(n), "LT2-mb" || n) mod N, the
// key report n commits to, so bridges send only what the owner (or a control share) signed. Entries are
// format 2 (["v", "2"]), open (the PWA names no bridge), route flood; every entry of one command carries
// the same ["g", <16 hex>]. Anchors (content "") name the next reports' tags ahead of time. No proof of
// work. Mirrors LibreTagApp.publishMailbox / publishCheckSlots.
import { finalizeEvent } from 'nostr-tools/pure';
import { b64encode, bytesToHex } from './bytes.js';
import { CHECK_OFF, Command, EPOCH_REPORTS, J_ON_AIR, Mode, NOSTR_COMMAND_KIND, epoch, mailboxSk, reportTag, sealCommand } from './lt2.js';
import { CHECK_S_DEFAULT, lastPos, sendGrids } from './status.js';
import { commandSlots, pairFallback, sealSlotCommand, slotCommandTag, slotKey, slotSendAt } from './quietmesh.js';

/** untimed entries reach at least 10 counters ahead (R34) */
export const MAILBOX_AHEAD = 10;
export const MAILBOX_CHECKS = 12;
export const CHECK_SLOT_TTL_S = 600;
export const LOST_MIN = 1440, TRACK_MIN = 60;
export const COMMAND_KINDS = ['LOST', 'TRACK', 'BEEP', 'NORMAL'];
/** every kind the app can queue: the buttons above, and the report interval (SET_IDLE) from the interval setting */
export const QUEUE_KINDS = [...COMMAND_KINDS, 'INTERVAL'];
/** anchors for this many counters after the last report (P9: 16 in IDLE, 24 in TRACK and LOST), plus the two
 *  checkpoints a reboot lands on and the next epoch's first counter */
export const ANCHOR_AHEAD = 16, ANCHOR_AHEAD_MOVING = 24;
export const ANCHOR_MIN_S = 2 * 3600, ANCHOR_MAX_S = 50 * 3600, ANCHOR_DEFAULT_S = 12 * 3600;
/** a late entry only while the report is under 40 s old: bridges take one until then (late_max_s, P6) */
export const LISTEN_S = 40;

/** mailbox expiry: Beep 1 h, Track and Normal 12 h, Lost and the report interval 48 h */
export const ttlS = (kind) => (kind === 'BEEP' ? 3600 : kind === 'LOST' || kind === 'INTERVAL' ? 48 * 3600 : 12 * 3600);

/** Track: how often (s) and how long (min), as the Android app offers them */
export const TRACK_INTERVALS_S = [120, 300, 600, 900, 1800];
export const TRACK_DURATIONS_MIN = [30, 60, 120, 240, 480, 720, 1440];
/** the tag raises an interval below 120 s to 120 s and floods at most 200 times in 24 h (PROTOCOL.md 2.5) */
export const TRACK_SAFE_24H = 160;
export const trackReports = (intervalS, durationMin) => Math.floor(durationMin * 60 / Math.max(intervalS, 120));
export const trackHitsCap = (intervalS, durationMin) => Math.floor(Math.min(durationMin, 1440) * 60 / Math.max(intervalS, 120)) > TRACK_SAFE_24H;
/** minutes until the daily limit slows the run down to hourly, null when it does not */
export const trackCapAfterMin = (intervalS, durationMin) =>
  trackHitsCap(intervalS, durationMin) ? Math.floor(TRACK_SAFE_24H * Math.max(intervalS, 120) / 60) : null;

/**
 * The report interval: the wake-up interval in minutes, reported at every `every`-th wake-up (so the period is
 * wake * every), and how long the tag listens at checks (seconds, CHECK_OFF turns that off).
 */
export function buildInterval(seq, wakeMin, every = 1, checkS = CHECK_S_DEFAULT) {
  return Command.setIdle(seq, wakeMin, 0, checkS > 0 ? checkS : CHECK_OFF, every);
}

/** (wake-up interval, report_every) for a report period and a check interval, both in minutes. */
export function wakeSplit(reportMin, checkMin) {
  if (checkMin <= 0 || checkMin >= reportMin || reportMin % checkMin !== 0) return [reportMin, 1];
  return [checkMin, Math.min(24, reportMin / checkMin)];
}
export const DEFAULT_LISTEN_MIN = 60;
/** the split after the owner picks a new period: keep a finer listen interval that still fits, else listen every hour */
export function splitForPeriod(periodMin, curWakeMin, curEvery) {
  const keep = curEvery > 1 && curWakeMin > 0 && curWakeMin < periodMin && periodMin % curWakeMin === 0;
  return wakeSplit(periodMin, keep ? curWakeMin : DEFAULT_LISTEN_MIN);
}

export function buildCommand(kind, seq, arg = 0, durationMin = 0, opts = {}) {
  switch (kind) {
    case 'INTERVAL': return buildInterval(seq, arg, opts.every ?? 1, opts.checkS ?? CHECK_S_DEFAULT);
    case 'LOST': return Command.setMode(seq, Mode.LOST, 600, LOST_MIN);
    case 'TRACK': return Command.setMode(seq, Mode.TRACK, arg > 0 ? arg : 300, durationMin > 0 ? durationMin : TRACK_MIN);
    case 'BEEP': return Command.buzz(seq, 10);
    case 'NORMAL': return Command.setMode(seq, Mode.IDLE, 0, 0);
    default: throw new Error('unknown command ' + kind);
  }
}

/**
 * R6: a random 1..255 whose low four bits differ from the tag's newest report's cmd_seq and from this
 * browser's previous command, so a second or restored device never repeats the tag's last executed number.
 */
export function nextSeq(tag, rnd = () => crypto.getRandomValues(new Uint8Array(1))[0]) {
  const avoid = new Set([lastPos(tag)?.cmdSeq, tag.cmdSeq ? tag.cmdSeq & 15 : null].filter((x) => x != null).map((x) => x & 15));
  for (let i = 0; i < 64; i++) {
    const s = 1 + (rnd() % 255);
    if (!avoid.has(s & 15)) return s;
  }
  for (let s = 1; s < 256; s++) if (!avoid.has(s & 15)) return s;
  return 1;
}

/** ["g", 16 lowercase hex]: one random group id per command, on every entry of it (bridges stop a group once one entry went out). */
export const newGroup = () => bytesToHex(crypto.getRandomValues(new Uint8Array(8)));

/** What to remember about published events to cancel them later (and not publish twice): [{ id, n, exp, ys }].
 *  No key: a deletion's key is derived again from the chain (sk_n of report n). */
export const sentOf = (events) => events.map((e) => ({ id: e.id, n: e.n, exp: Number(e.tags.find((t) => t[0] === 'expiration')?.[1] ?? 0),
  ys: e.tags.filter((t) => t[0] === 'y').map((t) => t[1]) }));

/** Entries not out yet: an entry is out while a published, unexpired event carries all of its y tags. */
export function notYetOut(entries, sent, nowS) {
  const live = new Set((sent ?? []).filter((x) => !x.exp || x.exp > nowS + 60).flatMap((x) => x.ys ?? []));
  return entries.filter((e) => !e.ys.every((y) => live.has(y)));
}

/** sk_n of report n (null without command keys, or before a shared epoch). */
export function keyFor(chain, n) {
  if (!chain?.canCommand || !Number.isInteger(n) || n < 0) return null;
  try {
    const ce = chain.cmd(epoch(n));
    return ce ? mailboxSk(ce, n) : null;
  } catch { return null; }
}

/** NIP-09 deletions, each signed by the key of the mailbox event it deletes (sk_n, derived again), expiring with it. */
export function cancelEvents(sent, chain, nowS = Math.floor(Date.now() / 1000)) {
  const out = [];
  for (const x of sent ?? []) {
    if (x.exp && x.exp <= nowS) continue;
    const sk = keyFor(chain, x.n);
    if (!sk) continue;
    out.push(finalizeEvent({ kind: 5, created_at: nowS, content: '',
      tags: [['e', x.id], ['k', String(NOSTR_COMMAND_KIND)], ...(x.exp ? [['expiration', String(x.exp)]] : [])] }, sk));
  }
  return out;
}

const ysOf = (chain, n) => {
  const ve = chain.view(epoch(n));
  return J_ON_AIR.map((j) => bytesToHex(reportTag(ve, n, j)));
};

/** The last report when it came in less than LISTEN_S ago (exact time only): a late entry can still reach the tag. */
export function lateReport(tag, nowS) {
  const last = (tag.positions ?? []).filter((p) => p.n === tag.lastN).slice(-1)[0];
  if (!last || last.approxUntil || !Number.isFinite(last.receivedAt)) return null;
  const age = nowS - last.receivedAt / 1000;
  return age >= 0 && age < LISTEN_S ? last : null;
}

/**
 * The unsigned mailbox entries for a pending command {kind, body (hex), g}: a late entry for the last
 * report while the tag still listens (bridges send it at once), one per next report counter (both
 * copies j = 0, 1 as y tags), then timed entries for the next checks of a quiet stretch. Order =
 * publishing order: untimed first (the late one first), then ascending `at`.
 * Each: { n (whose sk_n signs it), at | null, ys, tags, content }.
 */
export async function mailboxEntries(tag, pending, nowS = Math.floor(Date.now() / 1000)) {
  const chain = tag.chain;
  if (!chain?.canCommand) return [];
  const pt = Uint8Array.from(pending.body.match(/../g).map((h) => parseInt(h, 16)));
  const g = pending.g ? [['g', pending.g]] : [];
  const exp = ['expiration', String(nowS + ttlS(pending.kind))];
  const counters = [];
  if (lateReport(tag, nowS)) counters.push(tag.lastN);
  for (let k = 1; k <= MAILBOX_AHEAD; k++) counters.push(Math.max(tag.lastN ?? -1, -1) + k);
  const out = [];
  for (const n of counters) {
    if (!keyFor(chain, n)) continue;
    const pc = await sealCommand(chain.cmd(epoch(n)), n, pt);
    const ys = ysOf(chain, n);
    const route = pending.kind === 'LOST' || pending.kind === 'TRACK' ? 'both' : 'flood';
    out.push({ n, at: null, ys, tags: [['v', '2'], ...ys.map((y) => ['y', y]), ['route', route], ...g, exp], content: b64encode(pc) });
  }
  const timed = await checkSlotEntries(tag, pt, nowS, g, pending.kind, pending.queuedAt ? Math.floor(pending.queuedAt / 1000) + ttlS(pending.kind) : nowS + ttlS(pending.kind));
  return [...out, ...timed.sort((a, b) => a.at - b.at)];
}

/**
 * Timed entries (PROTOCOL.md 2.6, 4): for every grid the browser holds, the next 3 slots, then every 3rd until
 * the command's lifetime (LOST every slot for 6 h), at most 12 per grid, each sealed from the slot key of its
 * anchor, signed by sk of the anchor, at = t_k + 1, via = x of the anchor copies, the first slot `flood` (both
 * for Lost and Track) and the later ones `both`, and ["fb", "0"] on all but the latest grid of one try.
 */
export async function checkSlotEntries(tag, pt, nowS, g = [], kind = '', untilS = nowS + 12 * 3600) {
  const grids = sendGrids(tag).filter((gr) => keyFor(tag.chain, gr.n));
  if (!grids.length) return [];
  const lt = kind === 'LOST' || kind === 'TRACK';
  const per = grids.map((gr) => {
    const fromK = gr.offsetsS.findIndex((o) => Math.floor(gr.t0 / 1000) + o + 1 > nowS + 5) + 1;
    return [gr, fromK > 0 ? commandSlots(gr.offsetsS, fromK, untilS - Math.floor(gr.t0 / 1000), kind === 'LOST', MAILBOX_CHECKS) : []];
  });
  const out = [];
  const tries = Math.max(...per.map(([, ks]) => ks.length));
  for (let i = 0; i < tries; i++) {
    const sends = per.filter(([, ks]) => ks[i] != null).map(([gr, ks]) => [gr, ks[i]]);
    const fbs = pairFallback(sends.map(([gr, k]) => Math.floor(gr.t0 / 1000) + gr.offsetsS[k - 1]), lt);
    for (let si = 0; si < sends.length; si++) {
      const [gr, k] = sends[si];
      const e = epoch(gr.n);
      const ks = slotKey(tag.chain.cmd(e), gr.n);
      const atS = slotSendAt(Math.floor(gr.t0 / 1000), gr.offsetsS[k - 1]);
      const y = bytesToHex(slotCommandTag(ks, k));
      const vias = gr.vias?.length ? gr.vias : [bytesToHex(reportTag(tag.chain.view(e), gr.n, 0))];
      const pc = await sealSlotCommand(ks, k, pt);
      out.push({ n: gr.n, at: atS, ys: [y], tags: [['v', '2'], ['y', y], ['route', lt || i > 0 ? 'both' : 'flood'], ...g,
        ['expiration', String(atS + CHECK_SLOT_TTL_S)], ['at', String(atS)], ...vias.map((v) => ['via', v]),
        ...(fbs[si] ? [] : [['fb', '0']])], content: b64encode(pc) });
    }
  }
  return out;
}

/** Sign one entry (or anchor) with sk_n of its report. The counter rides along (not enumerable) for cancelling. */
export function signEntry(entry, chain, nowS = Math.floor(Date.now() / 1000)) {
  const sk = keyFor(chain, entry.n);
  if (!sk) throw new Error('no mailbox key for report ' + entry.n);
  const ev = finalizeEvent({ kind: NOSTR_COMMAND_KIND, created_at: entry.createdAt ?? nowS, tags: entry.tags, content: entry.content }, sk);
  Object.defineProperty(ev, 'n', { value: entry.n, enumerable: false });
  return ev;
}

/** All entries signed (tests and tools). */
export async function mailboxEvents(tag, pending, nowS = Math.floor(Date.now() / 1000)) {
  return (await mailboxEntries(tag, pending, nowS)).map((e) => signEntry(e, tag.chain, nowS));
}

// ------------------------------------------------------------------ anchors (PROTOCOL.md 4.2)

const moving = (tag) => ['LOST', 'TRACK'].includes(lastPos(tag)?.mode);

/** Anchor lifetime: covers the counters ahead at the report interval (next_in of the last report), 2 h to 50 h. */
export function anchorTtlS(tag) {
  const next = lastPos(tag)?.nextInS;
  if (!next) return ANCHOR_DEFAULT_S;
  return Math.min(ANCHOR_MAX_S, Math.max(ANCHOR_MIN_S, (moving(tag) ? ANCHOR_AHEAD_MOVING : ANCHOR_AHEAD) * next));
}

/** Counters that need an anchor: the next 16 (24 moving) after the last report, the next two multiples of 16
 *  (a reboot lands there, firmware 0.10.0) and the next epoch's first one (a long quiet stretch). */
export function anchorCounters(tag) {
  if (!Number.isInteger(tag.lastN) || tag.lastN < 0) return [];
  const out = [];
  const ahead = moving(tag) ? ANCHOR_AHEAD_MOVING : ANCHOR_AHEAD;
  for (let k = 1; k <= ahead; k++) out.push(tag.lastN + k);
  const c16 = (Math.floor(tag.lastN / 16) + 1) * 16;
  for (const c of [c16, c16 + 16]) if (!out.includes(c)) out.push(c);
  const nextEpoch = (epoch(tag.lastN) + 1) * EPOCH_REPORTS;
  if (!out.includes(nextEpoch)) out.push(nextEpoch);
  return out;
}

/**
 * Unsigned anchors still to publish: counters without one, or whose anchor has less than half its
 * lifetime left. `have` = { n: expiration } of what was published. Nothing for a view share.
 * created_at is spread up to 300 s back, so a burst of anchors links less.
 */
export function anchorEntries(tag, have = {}, nowS = Math.floor(Date.now() / 1000), rnd = Math.random) {
  if (!tag.chain?.canCommand) return [];
  const ttl = anchorTtlS(tag);
  const out = [];
  for (const n of anchorCounters(tag)) {
    if (!keyFor(tag.chain, n)) continue;
    if (have[n] && have[n] - nowS >= ttl / 2) continue;
    const ys = ysOf(tag.chain, n);
    out.push({ n, at: null, ys, anchor: true, createdAt: nowS - Math.floor(rnd() * 300),
      tags: [['v', '2'], ...ys.map((y) => ['y', y]), ['expiration', String(nowS + ttl)]], content: '' });
  }
  return out;
}

/** Drop the anchors of counters already reported, and expired ones. */
export function pruneAnchors(have, lastN, nowS) {
  const out = {};
  for (const [n, exp] of Object.entries(have ?? {})) if (Number(n) > lastN && exp > nowS) out[n] = exp;
  return out;
}

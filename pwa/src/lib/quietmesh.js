// Quiet mesh (LT2, firmware 0.10.0, PWA 0.5.0): the ACK, schedule anchors and slots, as the web app needs
// them. Reference: host/src/libretag/quietmesh.py. Checked against docs/vectors/quiet-mesh-v1.json.
// PROTOCOL.md 2.6 (anchor, slots), 3.3 (ACK), 4.3 (ACK entries); SPEC.md 3.2.
import { b64encode, bytesToHex, concatBytes, equalBytes, u16, u32, utf8ToBytes } from './bytes.js';
import { CMD_P_LEN, TAG_LEN, decodeCommand, hm, open, seal } from './lt2.js';

export const J_NEAR = 9, J_WIDE = 10, J_ROUTED = 11;
export const J_FLOODS = [0, 1, 10];
export const HOLD_DEFAULT = 4; // 12 h
export const HOLD_UNIT_S = 3 * 3600;
export const LIVE_ACK_MAX_AGE_MS = 40_000;
export const MAX_CANDIDATES = 2;
export const MOVE_M = 300;
const ascii = utf8ToBytes;

// ------------------------------------------------------------------ the ACK (PROTOCOL.md 3.3)

const ck = (n, k) => concatBytes(u32(n), u16(k));
export const ackKey = (cmdE, n, k) => hm(cmdE, ascii('LT2-a'), ck(n, k));
export const ackTag = (cmdE, n, k) => hm(cmdE, ascii('LT2-ax'), ck(n, k)).slice(0, TAG_LEN);

/** Pa(n, k) = xa || ct || mac, 13 B. info: hold (0..15, units of 3 h) | MORE 0x10 | ROUTED 0x20. */
export function ackSeal(cmdE, n, k, hold = HOLD_DEFAULT, more = false, routed = false) {
  const ka = ackKey(cmdE, n, k), xa = ackTag(cmdE, n, k);
  const info = (hold & 0x0f) | (more ? 0x10 : 0) | (routed ? 0x20 : 0);
  const ct = Uint8Array.of(info ^ ka[0]);
  return concatBytes(xa, ct, hm(ka.slice(16), xa, ct).slice(0, 8));
}

/** The tag's check of a 13 B Pa (for tests): { hold, more, routed } or null. */
export function ackOpen13(cmdE, n, k, pa) {
  if (pa.length !== 13 || !equalBytes(pa.slice(0, 4), ackTag(cmdE, n, k))) return null;
  const ka = ackKey(cmdE, n, k);
  if (!equalBytes(hm(ka.slice(16), pa.slice(0, 5)).slice(0, 8), pa.slice(5))) return null;
  const i = pa[4] ^ ka[0];
  if (i & 0xc0) return null;
  return { hold: i & 0x0f, more: !!(i & 0x10), routed: !!(i & 0x20) };
}

/** ACK entry content (PROTOCOL.md 4.3): 'near' 13 B, 'both' 26 B (near || routed), 'routed' 13 B. */
export function ackEntryContent(cmdE, n, k, form, hold = HOLD_DEFAULT, more = false) {
  const near = ackSeal(cmdE, n, k, hold, more, false), routed = ackSeal(cmdE, n, k, hold, more, true);
  return b64encode(form === 'both' ? concatBytes(near, routed) : form === 'routed' ? routed : near);
}

/** The form for copy j: a hello near; a flood both forms when the tag stands, else near; copy 11 routed. */
export const formFor = (j, standing) => (j === J_ROUTED ? 'routed' : J_FLOODS.includes(j) && standing ? 'both' : 'near');
export const ackRoute = (form) => (form === 'near' ? 'near' : 'direct');

/** hold in seconds for a NEAR tag (null for 0: every report has to arrive); never under one period + 5 min. */
export const holdS = (hold, periodS) => (hold === 0 ? null : Math.max(hold * HOLD_UNIT_S, periodS + 300));

// ------------------------------------------------------------------ slots (PROTOCOL.md 2.6)

export const slotKey = (cmdE, nA) => hm(cmdE, ascii('LT2-slot'), u32(nA));
const slotKd = (ks, k) => hm(ks, ascii('LT2-c'), u16(k));
export const slotCommandTag = (ks, k) => hm(ks, ascii('LT2-cx'), u16(k)).slice(0, TAG_LEN);
export const sealSlotCommand = (ks, k, pt) => seal(slotKd(ks, k), slotCommandTag(ks, k), pt);

export async function openSlotCommand(ks, k, pc) {
  if (pc.length !== CMD_P_LEN || !equalBytes(pc.slice(0, TAG_LEN), slotCommandTag(ks, k))) return null;
  const pt = await open(slotKd(ks, k), pc);
  return pt && decodeCommand(pt);
}

export function slotJitter(ks, i, baseS) {
  const span = Math.floor(baseS / 5);
  if (span === 0) return 0;
  const h = hm(ks, ascii('LT2-w'), u16(i));
  return ((h[0] | (h[1] << 8)) % span) - Math.floor(span / 2);
}

/** t_k - t0 (s) for k = 1..count. */
export function slotOffsets(ks, baseS, count) {
  const out = [];
  let t = 0;
  for (let k = 1; k <= count; k++) { t += baseS + slotJitter(ks, k, baseS); out.push(t); }
  return out;
}

export const slotGuardMs = (offsetS) => 2000 + Math.floor(offsetS / 10);
export const slotSendAt = (t0S, offsetS) => t0S + offsetS + 1;

/** fb per send of one try over several grids: only the latest keeps the fallback, except Lost and Track (R3, R35). */
export function pairFallback(times, lostOrTrack) {
  if (lostOrTrack || !times.length) return times.map(() => true);
  const last = Math.max(...times);
  return times.map((t) => t === last);
}

/** Slots for timed command entries: the next 3, then every 3rd, until untilS; LOST every slot for 6 h; at most max. */
export function commandSlots(offsets, fromK, untilS, lost, max = 12) {
  const out = [];
  const start = offsets[fromK - 1];
  if (start == null) return out;
  let i = 0;
  for (let k = fromK; k <= offsets.length; k++) {
    const off = offsets[k - 1];
    if (off > untilS) break;
    if (i < 3 || (lost && off - start < 6 * 3600) || (i - 2) % 3 === 0) out.push(k);
    i++;
    if (out.length >= max) break;
  }
  return out;
}

// ------------------------------------------------------------------ the app's anchor (R1, R32)

/** { a: {n, t0} | null, cands: [{n, t0}], lastN, seen: {n: t0}, known } */
export const emptyAnchor = () => ({ a: null, cands: [], lastN: -1, seen: {}, known: true });

const grids = (s) => [...(s.a ? [s.a] : []), ...s.cands];
function addCand(s, g) {
  if (s.cands.length >= MAX_CANDIDATES) return { ...s, known: false };
  return { ...s, cands: [...s.cands, g] };
}

/** The anchor after report n, copy j, exact time t (ms). Pure: returns the new state (quietmesh.AppAnchor.on_report). */
export function anchorOnReport(s0, n, j, t, sync, liveAck = false, still = true, dueInGap = false) {
  const s = { ...emptyAnchor(), ...s0 };
  const flood = J_FLOODS.includes(j);
  if (n < s.lastN) return s;
  if (n === s.lastN) {
    // the tag's t0 is its first flood copy: an A already on n keeps its time (copy 10, copy 1)
    if (flood) return { ...s, a: s.a?.n === n ? s.a : { n, t0: t }, cands: [], known: true };
    if (liveAck && !s.cands.some((c) => c.n === n) && s.a?.n !== n) return addCand(s, { n, t0: s.seen[n] ?? t });
    return s;
  }
  let r = { ...s };
  const m = s.lastN;
  if (sync) {
    let g = grids(s).find((x) => x.n === n - 1) ?? null;
    if (!g && s.seen[n - 1] != null) g = { n: n - 1, t0: s.seen[n - 1] };
    r = { ...r, a: g, cands: [], known: g != null };
  } else if (m >= 0) {
    if (n === m + 1) r = { ...r, cands: r.cands.filter((c) => c.n !== m) };
    else if (n > m + 2 && !(still && !dueInGap)) r = { ...r, known: false };
  }
  const seen = {};
  for (const [k, v] of Object.entries({ ...r.seen, [n]: t })) if (Number(k) >= n - 8) seen[k] = v;
  r = { ...r, seen, lastN: n };
  if (flood) return { ...r, a: { n, t0: t }, cands: [], known: true };
  if (liveAck) return addCand(r, { n, t0: t });
  return r;
}

/** A confirmed command settles the grids: the send it answered was on grid nGrid. */
export function anchorOnConfirmed(s, nGrid) {
  const g = grids(s).find((x) => x.n === nGrid);
  return g ? { ...s, a: g, cands: [], known: true } : s;
}

export const anchorGrids = grids;
export { bytesToHex };

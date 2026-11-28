// LibreTag wire protocol v2 (PROTOCOL.md), the parts a reader and commander needs.
// Reference: host/src/libretag/lt2.py. Checked against host/tests/vectors/lt2.json.
import { hmac } from '@noble/hashes/hmac.js';
import { sha256 } from '@noble/hashes/sha2.js';
import { getPublicKey } from 'nostr-tools/pure';
import { bytesToHex, concatBytes, equalBytes, u16, u32, utf8ToBytes } from './bytes.js';

export const EPOCH_REPORTS = 64;
export const TAG_LEN = 4;
export const MAC_LEN = 8; // plain seal: commands, history records, BLE and Wi-Fi packets
export const RMAC_LEN = 6; // report sealing v2 (j = 0, 1 on air)
export const COMMIT_LEN = 3; // commitment c to the mailbox key pk_n, at P[4..7)
export const COMMIT_OFF = 4;
export const REPORT_PT_LEN = 33;
export const REPORT_PT_LEN_STEALTH = 39;
export const REPORT_P_LEN = TAG_LEN + REPORT_PT_LEN + MAC_LEN; // 45
export const REPORT_P_LEN_STEALTH = TAG_LEN + REPORT_PT_LEN_STEALTH + MAC_LEN; // 51
export const CMD_PT_LEN = 17;
export const CMD_P_LEN = TAG_LEN + CMD_PT_LEN + MAC_LEN; // 29
export const TYPE_REPORT = 0x21;
export const TYPE_COMMAND = 0x22;
export const NOSTR_REPORT_KIND = 4243;
export const NOSTR_COMMAND_KIND = 4244;
export const WINDOW_BACK = 256;
export const WINDOW_AHEAD = 2048;
export const FIRST_SYNC = 4096;

export const F_HAS_FIX = 0x01, F_CMD_ACK = 0x02, F_LOW_BATT = 0x04, F_NB_DISCOVER = 0x08,
  F_QUIET = 0x10, F_QUIET_OWNER = 0x20, F_BLE_SUM = 0x40, F_SYNC = 0x80;
/** copies on air (0.10.0): 0, 1 floods, 9 zero-hop hello, 10 a wider flood, 11 along a path */
export const J_ON_AIR = [0, 1, 9, 10, 11];
export const J_FLOODS = [0, 1, 10];
export const B2_PARKED = 0x08;
// 0.9.4: byte 2 bits 0..1 are the mode, bit 2 BOOT (the first report since a boot)
export const B2_MODE = 0x03, B2_BOOT = 0x04;
// 0.9.4: byte 14 of a BOOT report without a fix (not in the Wi-Fi seed2 layout): cause | resets << 3
export const BOOT_CAUSES = ['none', 'pin', 'power', 'soft', 'debug', 'brownout', 'watchdog', 'crash'];
export const NB_WIFI = 0x80;

export const Mode = { IDLE: 0, TRACK: 1, LISTEN: 2, LOST: 3 };
export const MODE_NAMES = ['IDLE', 'TRACK', 'LISTEN', 'LOST'];
export const CHECK_OFF = 0xFF;
export const Op = { SET_MODE: 0x01, SET_IDLE: 0x03, BUZZ: 0x04, BLE_SCAN: 0x05, BLE_CONSOLE: 0x06 };

const SECP256K1_N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141n;

export const hm = (key, ...parts) => hmac(sha256, key, concatBytes(...parts));
const ascii = utf8ToBytes;

// ------------------------------------------------------------------ keys (PROTOCOL.md 1)

/**
 * Everything derived from K0. `view(e)` and `cmd(e)` walk the ratchet and cache what they computed.
 * A tag shared with us has no K0, only V_e (and C_e) from epoch e: `KeyChain.fromShare`.
 */
export class KeyChain {
  constructor(k0) {
    if (!(k0 instanceof Uint8Array) || k0.length !== 32) throw new Error('K0 must be 32 bytes');
    this.k0 = k0;
    this.minEpoch = 0;
    this.id = hm(k0, ascii('LT2-id')).slice(0, 8);
    this._v = [hm(k0, ascii('LT2-view'))];
    this._c = [hm(k0, ascii('LT2-cmd'))];
  }

  /** Read-only (cmdE = null) or full-control share from epoch `e`; the past before it stays closed. */
  static fromShare(e, viewE, cmdE = null) {
    const c = Object.create(KeyChain.prototype);
    c.k0 = null; c.id = null; c.minEpoch = e;
    c._v = []; c._v[e] = viewE;
    c._c = cmdE ? [] : null; if (cmdE) c._c[e] = cmdE;
    return c;
  }
  get canCommand() { return this._c !== null; }
  get idHex() { return this.id ? bytesToHex(this.id) : ''; }
  get usbKey() { return hm(this.k0, ascii('LT2-usb')); }
  get dmSk() {
    const v = BigInt('0x' + bytesToHex(hm(this.k0, ascii('LT2-dm')))) % SECP256K1_N;
    return v.toString(16).padStart(64, '0');
  }
  _at(list, e) {
    if (e < this.minEpoch) throw new Error('epoch before the shared one');
    if (list[e] === undefined) {
      let i = e - 1;
      while (list[i] === undefined) i--;
      for (i++; i <= e; i++) list[i] = hm(list[i - 1], ascii('LT2-next'));
    }
    return list[e];
  }
  view(e) { return this._at(this._v, e); }
  cmd(e) { return this._c ? this._at(this._c, e) : null; }
}

export const epoch = (n) => Math.floor(n / EPOCH_REPORTS);

export const reportTag = (viewE, n, j) => hm(viewE, ascii('LT2-x'), u32(n), Uint8Array.of(j)).slice(0, TAG_LEN);
const ck = (n, k) => (k ? concatBytes(u32(n), u16(k)) : u32(n));
export const commandTag = (cmdE, n, k = 0) => hm(cmdE, ascii('LT2-cx'), ck(n, k)).slice(0, TAG_LEN);
const reportKd = (viewE, n, j) => hm(viewE, ascii('LT2-r'), u32(n), Uint8Array.of(j));
const reportKd2 = (viewE, n, j) => hm(viewE, ascii('LT2-r2'), u32(n), Uint8Array.of(j));

/** Mailbox key of report n (PROTOCOL.md 4), 32 B: every kind 4244 event for report n is signed with it.
 *  Needs the command chain C_e(n), so a view share cannot derive it. */
export function mailboxSk(cmdE, n) {
  const v = BigInt('0x' + bytesToHex(hm(cmdE, ascii('LT2-mb'), u32(n)))) % SECP256K1_N;
  const hexv = (v === 0n ? 1n : v).toString(16).padStart(64, '0');
  return Uint8Array.from(hexv.match(/../g).map((b) => parseInt(b, 16)));
}
/** pk_n, x-only hex */
export const mailboxPk = (cmdE, n) => getPublicKey(mailboxSk(cmdE, n));

/** c = SHA-256("LT2-mbc" || pk_n || x || ct || mac)[0:3]; pk as 32 B */
export const reportCommit = (pk, p) =>
  sha256(concatBytes(ascii('LT2-mbc'), pk, p.slice(0, TAG_LEN), p.slice(COMMIT_OFF + COMMIT_LEN))).slice(0, COMMIT_LEN);
const commandKd = (cmdE, n, k = 0) => hm(cmdE, ascii('LT2-c'), ck(n, k));

// ------------------------------------------------------------------ SIV seal / open

async function ctr(key16, mac8, data) {
  const k = await crypto.subtle.importKey('raw', key16, 'AES-CTR', false, ['encrypt']);
  const counter = concatBytes(mac8, new Uint8Array(16 - mac8.length));
  const out = await crypto.subtle.encrypt({ name: 'AES-CTR', counter, length: 64 }, k, data);
  return new Uint8Array(out);
}

export async function seal(kd, tag, pt) {
  const mac = hm(kd.slice(16), tag, pt).slice(0, MAC_LEN);
  return concatBytes(tag, await ctr(kd.slice(0, 16), mac, pt), mac);
}

export async function open(kd, p) {
  const tag = p.slice(0, TAG_LEN), ct = p.slice(TAG_LEN, p.length - MAC_LEN), mac = p.slice(p.length - MAC_LEN);
  const pt = await ctr(kd.slice(0, 16), mac, ct);
  return equalBytes(hm(kd.slice(16), tag, pt).slice(0, MAC_LEN), mac) ? pt : null;
}

// ------------------------------------------------------------------ small codes

export function nextInDecode(v) {
  if (v === 0) return null;
  return v <= 120 ? v * 30 : 3600 + (v - 120) * 900;
}
export function nextInEncode(sec) {
  if (sec == null || sec <= 0) return 0;
  if (sec <= 3600 + 450) return Math.max(1, Math.min(120, Math.floor((sec + 15) / 30)));
  return Math.min(255, 120 + Math.floor((sec - 3600 + 450) / 900));
}
export const energyDecode = (v) => (v === 0 ? null : (v - 1) * 10);

// ------------------------------------------------------------------ report (PROTOCOL.md 2)

const s24 = (b, o) => {
  const v = b[o] | (b[o + 1] << 8) | (b[o + 2] << 16);
  return v & 0x800000 ? v - (1 << 24) : v;
};

export function decodeReport(pt) {
  if ((pt.length !== REPORT_PT_LEN && pt.length !== REPORT_PT_LEN_STEALTH) || pt[0] !== TYPE_REPORT) return null;
  const dv = new DataView(pt.buffer, pt.byteOffset, pt.length);
  const flags = pt[1], modeB = pt[2], batt = pt[3];
  const t = dv.getUint32(4, true);
  const counts = pt[15];
  const wifiLayout = !!(counts & NB_WIFI);
  const bleLayout = !!(flags & F_BLE_SUM) && !wifiLayout;
  // a second tail in the repeater's slot: byte 30 bit 7 (PROTOCOL.md 2.2)
  const tail2Layout = wifiLayout && !!(pt[30] & 0x80);
  const maxNb = wifiLayout ? (tail2Layout ? 0 : 1) : bleLayout ? 4 : 5;
  const neighbours = [];
  for (let i = 0; i < Math.min((counts >> 4) & 7, maxNb); i++) {
    const o = 16 + 3 * i;
    neighbours.push({ hash: bytesToHex(pt.slice(o, o + 2)), snrDb: dv.getInt8(o + 2) / 4 });
  }
  // Wi-Fi layout (PROTOCOL.md 2.2): seed, tail and, without any fix time, a second full BSSID in bytes 8..14
  let wifi = null;
  if (wifiLayout) {
    const q = (v) => -95 + 2 * (v & 0x1f);
    const tail = pt[29] & 0x80 ? bytesToHex(pt.slice(26, 29)) : null;
    const seed2 = t === 0 && pt.slice(8, 14).some((b) => b !== 0);
    wifi = {
      seed: bytesToHex(pt.slice(19, 25)), seedRssi: q(pt[25]), apCount: pt[25] >> 5,
      tail, tailRssi: tail ? q(pt[29]) : -95,
      seed2: seed2 ? bytesToHex(pt.slice(8, 14)) : null, seed2Rssi: seed2 ? q(pt[14]) : -95,
      tail2: tail2Layout ? bytesToHex(pt.slice(16, 19)) : null, tail2Rssi: tail2Layout ? q(pt[30]) : -95,
    };
  }
  const quiet = !!(flags & F_QUIET);
  const owner = !!(flags & F_QUIET_OWNER);
  const parked = quiet && !owner && !!(modeB & B2_PARKED);
  const mode = modeB & B2_MODE;
  const boot = !!(modeB & B2_BOOT);
  const bootInfo = boot && !(flags & F_HAS_FIX) && !wifi?.seed2;
  return {
    hasFix: !!(flags & F_HAS_FIX),
    fixTime: t,
    lat: t ? (s24(pt, 8) * 90) / (1 << 23) : 0,
    lon: t ? (s24(pt, 11) * 180) / (1 << 23) : 0,
    // byte 14 is the second seed's RSSI then, not an accuracy
    // (and the reset information in a boot report without a fix)
    haccM: wifi?.seed2 || bootInfo ? 255 : pt[14],
    boot,
    bootCause: bootInfo ? BOOT_CAUSES[pt[14] & 7] : null,
    bootResets: bootInfo ? pt[14] >> 3 : null,
    battery: batt === 255 ? null : batt,
    mode: MODE_NAMES[mode] ?? 'IDLE',
    cmdAck: !!(flags & F_CMD_ACK),
    cmdSeq: modeB >> 4,
    lowBatt: !!(flags & F_LOW_BATT),
    nbTotal: counts & 15,
    nbDiscover: !!(flags & F_NB_DISCOVER),
    neighbours,
    quiet,
    quietReason: !quiet ? null : owner ? 'owner' : parked ? 'parked' : 'zone',
    sync: !!(flags & F_SYNC),
    nextInS: nextInDecode(pt[31]),
    energyMah: energyDecode(pt[32]),
    bleDevices: bleLayout ? pt[28] : null,
    bleStable: bleLayout ? pt[29] : null,
    wifi,
  };
}

const onAir = (j) => J_ON_AIR.includes(j);

/** j = 0, 1, 9, 10, 11 (on air): sealing v2, needs pk = pk_n (32 B); j = 2, 3 (history): the plain seal. */
export async function sealReport(viewE, n, j, pt, stealth = false, pk = null) {
  const body = stealth ? concatBytes(pt, new Uint8Array(6)) : pt;
  if (!onAir(j)) return seal(reportKd(viewE, n, j), reportTag(viewE, n, j), body);
  if (!pk) throw new Error('a report on air needs pk_n');
  const kd = reportKd2(viewE, n, j), x = reportTag(viewE, n, j), pt1 = body.slice(1);
  const mac = hm(kd.slice(16), x, pt1).slice(0, RMAC_LEN);
  const tail = concatBytes(await ctr(kd.slice(0, 16), mac, pt1), mac);
  return concatBytes(x, reportCommit(pk, concatBytes(x, new Uint8Array(COMMIT_LEN), tail)), tail);
}

export async function openReport(viewE, n, j, p) {
  if (p.length !== REPORT_P_LEN && p.length !== REPORT_P_LEN_STEALTH) return null;
  if (!equalBytes(p.slice(0, TAG_LEN), reportTag(viewE, n, j))) return null;
  if (!onAir(j)) {
    const pt = await open(reportKd(viewE, n, j), p);
    return pt && decodeReport(pt);
  }
  const kd = reportKd2(viewE, n, j), mac = p.slice(p.length - RMAC_LEN);
  const pt1 = await ctr(kd.slice(0, 16), mac, p.slice(COMMIT_OFF + COMMIT_LEN, p.length - RMAC_LEN));
  if (!equalBytes(hm(kd.slice(16), p.slice(0, TAG_LEN), pt1).slice(0, RMAC_LEN), mac)) return null;
  return decodeReport(concatBytes(Uint8Array.of(TYPE_REPORT), pt1.slice(0, REPORT_PT_LEN - 1)));
}

/** What the tag does: seal report n copy j with the keys of the chain (needs C_e for j = 0, 1). */
export async function sealReportChain(chain, n, j, pt, stealth = false) {
  const e = epoch(n);
  const pk = onAir(j) ? hexBytes(mailboxPk(chain.cmd(e), n)) : null;
  return sealReport(chain.view(e), n, j, pt, stealth, pk);
}
const hexBytes = (h) => Uint8Array.from(h.match(/../g).map((b) => parseInt(b, 16)));

// ------------------------------------------------------------------ command (PROTOCOL.md 3, 0.7)

export function encodeCommand(seq, op, args = new Uint8Array(0)) {
  const out = new Uint8Array(CMD_PT_LEN);
  out[0] = TYPE_COMMAND; out[1] = seq & 0xff; out[2] = op;
  out.set(args.slice(0, 14), 3);
  return out;
}

export function decodeCommand(pt) {
  if (pt.length !== CMD_PT_LEN || pt[0] !== TYPE_COMMAND) return null;
  return { seq: pt[1], op: pt[2], args: pt.slice(3) };
}

const le16 = (n) => [n & 255, (n >> 8) & 255];
export const Command = {
  setMode: (seq, mode, intervalS = 0, durationMin = 0) =>
    encodeCommand(seq, Op.SET_MODE, Uint8Array.of(mode, ...le16(intervalS), ...le16(durationMin))),
  /** 0 keeps the current value; checkS 1..180 s listening at checks, 0xFF turns it off; reportEvery 1..24 (0.7.3) */
  setIdle: (seq, intervalMin, listenS = 0, checkS = 0, reportEvery = 0) =>
    encodeCommand(seq, Op.SET_IDLE, Uint8Array.of(...le16(intervalMin), ...le16(listenS), checkS === CHECK_OFF ? CHECK_OFF : Math.max(0, Math.min(180, checkS)), Math.max(0, Math.min(24, reportEvery)))),
  buzz: (seq, seconds) => encodeCommand(seq, Op.BUZZ, Uint8Array.of(Math.max(1, Math.min(60, seconds)))),
};

/** A command for report n: k = 0 the window right after it, k >= 1 check k after it (0.7). */
export const sealCommand = (cmdE, n, pt, k = 0) => seal(commandKd(cmdE, n, k), commandTag(cmdE, n, k), pt);

export async function openCommand(cmdE, n, p, k = 0) {
  if (p.length !== CMD_P_LEN || !equalBytes(p.slice(0, TAG_LEN), commandTag(cmdE, n, k))) return null;
  const pt = await open(commandKd(cmdE, n, k), p);
  return pt && decodeCommand(pt);
}

export const checkGuardMs = (offsetS) => 2000 + Math.floor(offsetS / 10);
/** senders transmit at t_k + 1 s (0.10.0) */
export const SEND_AFTER_S = 1;

// ------------------------------------------------------------------ receiver window (PROTOCOL.md 2)

export class ReportWindow {
  constructor(chain, lastN = -1, seen = []) {
    this.chain = chain;
    this.lastN = lastN;
    this.seen = new Set(seen);
    this._build();
  }

  _build() {
    const floor = this.chain.minEpoch * EPOCH_REPORTS;
    const lo = Math.max(floor, this.lastN < 0 ? 0 : this.lastN - WINDOW_BACK);
    // a share starts at its epoch: the first-sync span counts from there, not from 0
    const hi = this.lastN < 0 ? floor + FIRST_SYNC : this.lastN + 1 + WINDOW_AHEAD;
    this.table = new Map();
    for (let n = lo; n < hi; n++) {
      if (this.seen.has(n)) continue;
      const v = this.chain.view(epoch(n));
      for (const j of J_ON_AIR) this.table.set(bytesToHex(reportTag(v, n, j)), [n, j]);
    }
  }

  /** hex tags of the next `count` tags ahead of the newest counter (for a relay filter), n ascending */
  tagsAhead(count) {
    const start = Math.max(this.lastN + 1, 0);
    const items = [...this.table.entries()].filter(([, [n]]) => n >= start)
      .sort((a, b) => a[1][0] - b[1][0] || a[1][1] - b[1][1]);
    return items.slice(0, count).map(([t]) => t);
  }

  /** every hex tag of counters lo..hi (inclusive), every copy on air; `seen` ones too */
  tagsRange(lo, hi) {
    const out = [];
    for (let n = Math.max(this.chain.minEpoch * EPOCH_REPORTS, lo); n <= hi; n++) {
      const v = this.chain.view(epoch(n));
      for (const j of J_ON_AIR) out.push(bytesToHex(reportTag(v, n, j)));
    }
    return out;
  }

  rebuild() { this._build(); }

  /**
   * open a report payload: {n, j, report} or null. slide=false skips the table rebuild (batches call rebuild()).
   * keep: the copy has only an approximate time (0.8.1), n stays open for an exact copy.
   */
  async open(p, slide = true, keep = false) {
    const hit = this.table.get(bytesToHex(p.slice(0, TAG_LEN)));
    if (!hit) return null;
    const [n, j] = hit;
    const report = await openReport(this.chain.view(epoch(n)), n, j, p);
    if (!report) return null;
    if (!keep) this.seen.add(n);
    if (n > this.lastN) {
      this.lastN = n;
      this.seen = new Set([...this.seen].filter((s) => s >= this.lastN - WINDOW_BACK));
      if (slide) this._build();
    } else if (!keep) {
      for (const jj of J_ON_AIR) this.table.delete(bytesToHex(reportTag(this.chain.view(epoch(n)), n, jj)));
    }
    return { n, j, report };
  }
}

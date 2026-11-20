// Getting a tracker's keys into the web app without any login: a pasted or scanned K0, a bare
// 64-hex string, a `libretag://k0?...` link (whole tag) or a `libretag://share?...` link (a tag
// shared with us: V_e and C_e from an epoch, never K0). Format: docs in the PWA README / report.
import { bytesToHex, hexToBytes } from './bytes.js';
import { KeyChain } from './lt2.js';

export const COLORS = ['red', 'blue', 'green', 'yellow'];
export const SYMBOLS = ['stripe', 'diag', 'triangle', 'circle', 'square'];
const HEX64 = /^[0-9a-f]{64}$/;

/** Link for a whole tag: libretag://k0?v=1&k=<K0 hex>&name=..&type=person|thing|vehicle&mark=<color>-<symbol> */
export function k0Link(tag) {
  const q = new URLSearchParams({ v: '1', k: tag.k0, name: tag.name, type: tag.type, mark: `${tag.mark.color}-${tag.mark.symbol}` });
  return `libretag://k0?${q}`;
}

const markOf = (s) => {
  const [c, y] = (s || '').split('-');
  return { color: COLORS.includes(c) ? c : '', symbol: SYMBOLS.includes(y) ? y : '' };
};
const typeOf = (s) => (['person', 'thing', 'vehicle'].includes(s) ? s : 'thing');

/**
 * Parse pasted text. Returns { k0?, shared?:{epoch,ve,ce}, name, type, mark } or null.
 * `libretag:k0?...` (single colon) is accepted too.
 */
export function parseKeyInput(text) {
  const s = (text || '').trim();
  if (!s) return null;
  const hex = s.toLowerCase().replace(/\s+/g, '');
  if (HEX64.test(hex)) return { k0: hex, name: '', type: 'thing', mark: { color: '', symbol: '' } };
  let u;
  try { u = new URL(s.replace(/^libretag:(?!\/\/)/i, 'libretag://')); } catch { return null; }
  if (u.protocol !== 'libretag:') return null;
  const q = u.searchParams;
  const meta = { name: q.get('name') || '', type: typeOf(q.get('type')), mark: markOf(q.get('mark')) };
  if (u.hostname === 'k0') {
    const k = (q.get('k') || '').toLowerCase();
    return HEX64.test(k) ? { k0: k, ...meta } : null;
  }
  if (u.hostname === 'share') {
    const ve = (q.get('ve') || '').toLowerCase();
    const ce = (q.get('ce') || '').toLowerCase();
    const epoch = parseInt(q.get('e') || '0', 10);
    if (!HEX64.test(ve) || !(epoch >= 0)) return null;
    return { shared: { epoch, ve, ce: HEX64.test(ce) ? ce : null }, ...meta };
  }
  return null;
}

/** Build the stored tag record (no secrets beyond the keys, which only live in memory or the sealed cache). */
export function makeTag(parsed, usedMarks = [], fallbackName = 'Tag') {
  let chain, id, rec;
  if (parsed.k0) {
    chain = new KeyChain(hexToBytes(parsed.k0));
    id = chain.idHex;
    rec = { k0: parsed.k0 };
  } else {
    const { epoch, ve, ce } = parsed.shared;
    chain = KeyChain.fromShare(epoch, hexToBytes(ve), ce ? hexToBytes(ce) : null);
    id = bytesToHex(chain.view(epoch).slice(0, 8)); // a stable local id; the real id is not derivable
    rec = { share: { epoch, ve, ce } };
  }
  let mark = parsed.mark?.color && parsed.mark?.symbol ? parsed.mark : null;
  if (!mark) {
    const used = new Set(usedMarks.map((m) => `${m.color}-${m.symbol}`));
    outer: for (const symbol of SYMBOLS) for (const color of COLORS) if (!used.has(`${color}-${symbol}`)) { mark = { color, symbol }; break outer; }
    mark ??= { color: 'yellow', symbol: 'triangle' };
  }
  return { chain, tag: { id, name: parsed.name || fallbackName, type: parsed.type || 'thing', mark, ...rec } };
}

/** Tags of a decrypted backup payload to stored records. */
export function tagsFromBackup(payload) {
  const out = [];
  for (const b of payload.tags) {
    const [color, symbol] = (b.mark || '').split('-');
    const parsed = { k0: b.k0, name: b.name, type: typeOf(b.type), mark: markOf(b.mark) };
    const built = makeTag(parsed, out.map((o) => o.tag.mark), b.name);
    built.tag.updated = b.updated || 0;
    built.tag.extra = b.extra ?? {};
    built.tag.zones = Array.isArray(b.extra?.zones) ? b.extra.zones : [];
    built.tag.intervalMin = Number.isFinite(b.extra?.interval_min) ? b.extra.interval_min : null;
    out.push(built);
  }
  return out;
}

// ------------------------------------------------------------------ share out (byte-compatible with the Android app)

const enc = (s) => encodeURIComponent(s); // same unreserved set as android.net.Uri.Builder: A-Z a-z 0-9 - _ . ! ~ * ' ( )

/**
 * `libretag://share?v=1&name=..&type=..&mark=..&e=<epoch>&ve=<V_e hex>[&ce=<C_e hex>]`, parameters in
 * exactly the order the Android app writes them (ShareImport.link). `chain` must hold K0.
 */
export function shareLink(tag, chain, epoch, control) {
  const q = [['v', '1'], ['name', tag.name], ['type', tag.type], ['mark', `${tag.mark.color}-${tag.mark.symbol}`],
    ['e', String(epoch)], ['ve', bytesToHex(chain.view(epoch))]];
  if (control) q.push(['ce', bytesToHex(chain.cmd(epoch))]);
  return 'libretag://share?' + q.map(([k, v]) => `${k}=${enc(v)}`).join('&');
}

export const SHARE_WEB_BASE = 'https://cypherpunk.today/libretag/app/';

/** https fallback: the same query, percent-encoded, in the URL fragment (never sent to a server). */
export function shareWebLink(link, base = SHARE_WEB_BASE) {
  return base + '#share=' + enc(link.slice(link.indexOf('?') + 1));
}

/** `#share=<payload>` of an opened web link back to a `libretag://share?...` string, or null. */
export function shareFromHash(hash) {
  const m = /^#share=([^&]+)$/.exec(hash || '');
  if (!m) return null;
  try { return 'libretag://share?' + decodeURIComponent(m[1]); } catch { return null; }
}

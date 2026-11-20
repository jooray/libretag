// Backup copy B (SPEC 12.2): the same plaintext NIP-44 v2 encrypted to the owner's own pubkey,
// kind 30078, a random `d` chosen at first save, no `alt` tag and nothing that says LibreTag.
import { b64decode } from './bytes.js';
import { parsePlaintext } from './merge.js';
import { BACKUP_KIND } from './backup.js';

/** A NIP-44 v2 payload is base64 with a first byte of 2 (and at least 99 bytes). */
export function looksLikeNip44(content) {
  if (typeof content !== 'string' || content.length < 132 || !/^[A-Za-z0-9+/]+={0,2}$/.test(content)) return false;
  try { return b64decode(content)[0] === 2; } catch { return false; }
}

/** Write relays of a kind 10002 event ("r" tags, marker write or none). */
export const writeRelays = (ev) => (ev?.tags ?? []).filter((t) => t[0] === 'r' && t[1] && (!t[2] || t[2] === 'write')).map((t) => t[1]);

export async function ownerRelays(pool, relays, pubkey) {
  let extra = [];
  try {
    const evs = await pool.querySync(relays, { kinds: [10002], authors: [pubkey], limit: 5 }, { maxWait: 4000 });
    evs.sort((a, b) => b.created_at - a.created_at);
    extra = writeRelays(evs[0]).filter((u) => /^wss:\/\//.test(u)).slice(0, 6);
  } catch { /* defaults only */ }
  return [...new Set([...relays, ...extra])];
}

/**
 * Find copy B: the owner's kind 30078 events whose content is a NIP-44 v2 payload, newest first (at most
 * `max`), decrypt through the signer, take the first plaintext that is the v1 JSON with a matching "d".
 * Returns { payload, event, d } or null.
 */
export async function findBackupB(pool, relays, signer, max = 10, knownId = null) {
  const evs = await pool.querySync(relays, { kinds: [BACKUP_KIND], authors: [signer.pubkey] }, { maxWait: 8000 });
  const cands = evs.filter((e) => looksLikeNip44(e.content)).sort((a, b) => b.created_at - a.created_at || (a.id < b.id ? -1 : 1));
  if (knownId && cands[0]?.id === knownId) return { unchanged: true };
  const seen = new Set();
  let tried = 0;
  for (const e of cands) {
    const dtag = e.tags.find((t) => t[0] === 'd')?.[1];
    if (seen.has(dtag)) continue; // an older copy of the same replaceable event
    seen.add(dtag);
    if (++tried > max) break;
    let text;
    try { text = await signer.nip44Decrypt(e.content); } catch { continue; }
    const payload = parsePlaintext(text);
    if (payload && payload.d && payload.d === dtag) return { payload, event: e, d: payload.d };
  }
  return null;
}

/** Signed copy-B event (through the signer). `empty` makes the deletion replacement. */
export async function buildBackupBEvent(signer, d, plaintext, createdAt, empty = false) {
  const content = empty ? '' : await signer.nip44Encrypt(plaintext);
  return signer.signEvent({ kind: BACKUP_KIND, created_at: createdAt, tags: [['d', d]], content });
}

export const buildDeletionB = (signer, pubkey, d, createdAt) =>
  signer.signEvent({ kind: 5, created_at: createdAt, tags: [['a', `${BACKUP_KIND}:${pubkey}:${d}`], ['k', String(BACKUP_KIND)]], content: '' });

export const randomLocator = () => [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, '0')).join('');

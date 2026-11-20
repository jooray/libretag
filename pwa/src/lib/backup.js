// Key backup v1 (SPEC.md 12.1): found with only a passphrase. Vector: docs/vectors/key-backup-v1.json.
import { scryptAsync } from '@noble/hashes/scrypt.js';
import { hmac } from '@noble/hashes/hmac.js';
import { sha256 } from '@noble/hashes/sha2.js';
import { finalizeEvent, getPublicKey, getEventHash, verifyEvent } from 'nostr-tools/pure';
import { parsePlaintext } from './merge.js';
import { b64decode, b64encode, bytesToHex, concatBytes, hexToBytes, utf8ToBytes } from './bytes.js';

export const BACKUP_KIND = 30078;
export const SCRYPT = { N: 1 << 16, r: 8, p: 1, dkLen: 64 };
const SALT = 'LibreTag key backup v1';
const SECP256K1_N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141n;

/** NFKC, every whitespace run to one space, trimmed. Case is kept. */
export const normalizePassphrase = (p) => p.normalize('NFKC').replace(/\s+/g, ' ').trim();

/** 'ok' or 'too_short' (fewer than 4 words or fewer than 20 code points after normalising). */
export function passphraseStrength(p) {
  const n = normalizePassphrase(p);
  const words = n === '' ? 0 : n.split(' ').length;
  return words < 4 || [...n].length < 20 ? 'too_short' : 'ok';
}

const lab = (S, label) => hmac(sha256, S, utf8ToBytes(label));

/** d, sk, pubkey and kEnc from S (so the session can keep S alone). */
export function deriveFromS(S) {
  const d = bytesToHex(lab(S, 'LT-backup-d')).slice(0, 32);
  const skInt = BigInt('0x' + bytesToHex(lab(S, 'LT-backup-sk'))) % SECP256K1_N;
  if (skInt === 0n) throw new Error('derived key is zero');
  const sk = hexToBytes(skInt.toString(16).padStart(64, '0'));
  return { S, d, sk, pubkey: getPublicKey(sk), kEnc: lab(S, 'LT-backup-enc') };
}

/** Derive S, d, sk, pubkey and kEnc. Slow on purpose (64 MiB scrypt). `opts.N` is for tests only. */
export async function derive(passphrase, opts = {}) {
  const S = await scryptAsync(utf8ToBytes(normalizePassphrase(passphrase)), utf8ToBytes(SALT),
    { ...SCRYPT, ...opts, asyncTick: 20, maxmem: 256 * 1024 * 1024 });
  return deriveFromS(S);
}

const aesKey = (raw, usage) => crypto.subtle.importKey('raw', raw, 'AES-GCM', false, [usage]);

/** base64(0x01 || nonce || AES-GCM(kEnc, nonce, plaintext, AAD = d)). `nonce` is random unless given (tests). */
export async function encryptBackup(derived, plaintext, nonce = crypto.getRandomValues(new Uint8Array(12))) {
  const ct = new Uint8Array(await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce, additionalData: utf8ToBytes(derived.d), tagLength: 128 },
    await aesKey(derived.kEnc, 'encrypt'), utf8ToBytes(plaintext)));
  return b64encode(concatBytes(Uint8Array.of(1), nonce, ct));
}

/** The plaintext string, or null when the version byte is unknown or the GCM tag fails. */
export async function decryptBackup(derived, content) {
  let raw;
  try { raw = b64decode(content); } catch { return null; }
  if (raw.length < 1 + 12 + 16 || raw[0] !== 1) return null;
  try {
    const pt = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: raw.slice(1, 13), additionalData: utf8ToBytes(derived.d), tagLength: 128 },
      await aesKey(derived.kEnc, 'decrypt'), raw.slice(13));
    return new TextDecoder().decode(pt);
  } catch { return null; }
}

/** Filter for the REQ that finds the backup. */
export const backupFilter = (derived) => ({ kinds: [BACKUP_KIND], authors: [derived.pubkey], '#d': [derived.d] });

/** Signed backup event (for tests and for a later "update backup" in the web app). */
export async function buildBackupEvent(derived, plaintext, createdAt, nonce) {
  const content = await encryptBackup(derived, plaintext, nonce);
  return finalizeEvent({
    kind: BACKUP_KIND, created_at: createdAt, content,
    tags: [['d', derived.d], ['alt', 'LibreTag encrypted key backup']],
  }, derived.sk);
}

export const deletionA = (derived) => `${BACKUP_KIND}:${derived.pubkey}:${derived.d}`;

/**
 * Pick the newest valid backup event among relay answers. Returns
 * { state: 'found', payload, event } | { state: 'deleted' } | { state: 'none' } | { state: 'unreadable' }.
 */
export async function restore(derived, events) {
  const valid = events.filter((e) => e && e.kind === BACKUP_KIND && e.pubkey === derived.pubkey
    && e.tags?.some((t) => t[0] === 'd' && t[1] === derived.d) && verifyEvent(e));
  if (!valid.length) return { state: 'none' };
  valid.sort((a, b) => b.created_at - a.created_at || (a.id < b.id ? -1 : 1));
  const ev = valid[0];
  if (ev.content === '') return { state: 'deleted' };
  const text = await decryptBackup(derived, ev.content);
  if (text == null) return { state: 'unreadable' };
  const payload = parsePlaintext(text);
  if (!payload) return { state: 'unreadable' };
  return { state: 'found', event: ev, payload };
}

export { getEventHash };

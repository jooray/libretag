// NIP-17 private direct messages: rumor kind 14 (unsigned), sealed in kind 13 (signed by the sender,
// NIP-44 to the recipient), gift-wrapped in kind 1059 (signed by a one-time key, NIP-44 to the recipient).
// The web app's only sender is the tag's own dm key (PROTOCOL §5, the Android app does the same), which
// the app holds, so nothing here goes through the user's signer. Timestamps of the seal and the wrap are
// moved up to two days into the past (NIP-59) so they do not give away when the message was written.
import { finalizeEvent, generateSecretKey, getEventHash, getPublicKey, verifyEvent } from 'nostr-tools/pure';
import { decrypt, encrypt, getConversationKey } from 'nostr-tools/nip44';
import { hexToBytes } from './bytes.js';

export const KIND_CHAT = 14;
export const KIND_SEAL = 13;
export const KIND_GIFT_WRAP = 1059;
const TWO_DAYS = 2 * 86400;

const randomPast = (now, rnd) => Math.round(now - rnd() * TWO_DAYS);
const toBytes = (k) => (k instanceof Uint8Array ? k : hexToBytes(k));

/** The kind 14 rumor: unsigned, with its id. */
export function rumor(senderSk, toHex, text, now, extraTags = []) {
  const r = { kind: KIND_CHAT, created_at: now, tags: [['p', toHex], ...extraTags], content: text, pubkey: getPublicKey(toBytes(senderSk)) };
  r.id = getEventHash(r);
  return r;
}

/**
 * The gift wrap for one recipient. Returns { wrap, wrapSk, seal, rumor }. `expiration` (unix s, optional)
 * goes on the seal and on the wrap (NIP-17 disappearing messages). `rnd` and `wrapSk` are for tests.
 */
export function giftWrap(senderSk, toHex, text, now, { expiration = null, rnd = Math.random, wrapSk = generateSecretKey() } = {}) {
  const sk = toBytes(senderSk);
  const r = rumor(sk, toHex, text, now);
  const exp = expiration ? [['expiration', String(expiration)]] : [];
  const seal = finalizeEvent({ kind: KIND_SEAL, created_at: randomPast(now, rnd), tags: exp,
    content: encrypt(JSON.stringify(r), getConversationKey(sk, toHex)) }, sk);
  const wk = toBytes(wrapSk);
  const wrap = finalizeEvent({ kind: KIND_GIFT_WRAP, created_at: randomPast(now, rnd), tags: [['p', toHex], ...exp],
    content: encrypt(JSON.stringify(seal), getConversationKey(wk, toHex)) }, wk);
  return { wrap, wrapSk: wk, seal, rumor: r };
}

/**
 * Open a gift wrap with the recipient's key: { rumor, sender } or null. Checks the wrap's and the seal's
 * signatures and that the rumor's author is the seal's signer (NIP-17: otherwise anyone could impersonate).
 */
export function unwrap(wrap, recipientSk) {
  try {
    const sk = toBytes(recipientSk);
    if (wrap.kind !== KIND_GIFT_WRAP || !verifyEvent(wrap)) return null;
    const seal = JSON.parse(decrypt(wrap.content, getConversationKey(sk, wrap.pubkey)));
    if (seal.kind !== KIND_SEAL || !verifyEvent(seal)) return null;
    const r = JSON.parse(decrypt(seal.content, getConversationKey(sk, seal.pubkey)));
    if (r.pubkey !== seal.pubkey || getEventHash(r) !== r.id) return null;
    return { rumor: r, sender: seal.pubkey };
  } catch { return null; }
}

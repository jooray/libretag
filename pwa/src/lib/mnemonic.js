// Backup A (SPEC 12.2): the backup phrase is a BIP-39 mnemonic (English, 12 or 24 words, valid
// checksum). Free-form passphrases are refused. The normalised mnemonic is the 12.1 "passphrase".
import { validateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english.js';

/** Words lowercase, single spaces (NFKD is what BIP-39 itself uses; the English list is plain ASCII). */
export const normalizeMnemonic = (s) => s.normalize('NFKD').toLowerCase().replace(/\s+/g, ' ').trim();

/** 'ok' | 'wrong_length' | 'invalid' (unknown word or bad checksum) */
export function checkMnemonic(input) {
  const m = normalizeMnemonic(input || '');
  const n = m === '' ? 0 : m.split(' ').length;
  if (n !== 12 && n !== 24) return 'wrong_length';
  return validateMnemonic(m, wordlist) ? 'ok' : 'invalid';
}

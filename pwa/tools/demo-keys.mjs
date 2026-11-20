// Fixed secret keys for the local demo only (synthetic). Never use these for anything real.
import { sha256 } from '@noble/hashes/sha2.js';
import { utf8ToBytes } from '@noble/hashes/utils.js';
export const DEMO_USER_SK = sha256(utf8ToBytes('libretag web demo user'));
export const DEMO_SIGNER_SK = sha256(utf8ToBytes('libretag web demo remote signer'));

// A tiny NIP-46 remote signer for testing "Sign in with Nostr" against tools/devrelay.mjs.
//   node tools/fake-signer.mjs 'nostrconnect://...'
// It answers connect, get_public_key, nip44_encrypt/decrypt and sign_event for the demo user key.
import { finalizeEvent, getPublicKey, generateSecretKey } from 'nostr-tools/pure';
import * as nip44 from 'nostr-tools/nip44';
import { DEMO_SIGNER_SK, DEMO_USER_SK } from './demo-keys.mjs';

const uri = new URL(process.argv[2]);
const clientPub = uri.hostname;
const secret = uri.searchParams.get('secret');
const relay = uri.searchParams.getAll('relay')[0];
const signerPub = getPublicKey(DEMO_SIGNER_SK);
const userPub = getPublicKey(DEMO_USER_SK);
const ck = nip44.v2.utils.getConversationKey(DEMO_SIGNER_SK, clientPub);
const ws = new WebSocket(relay);
const send = (arr) => ws.send(JSON.stringify(arr));
const reply = (id, result, error) => {
  const ev = finalizeEvent({ kind: 24133, created_at: Math.floor(Date.now() / 1000), tags: [['p', clientPub]],
    content: nip44.v2.encrypt(JSON.stringify({ id, result, ...(error ? { error } : {}) }), ck) }, DEMO_SIGNER_SK);
  send(['EVENT', ev]);
};
ws.onopen = () => {
  send(['REQ', 'sig', { kinds: [24133], '#p': [signerPub] }]);
  reply(Math.random().toString(36).slice(2), secret); // the unsolicited connect response of nostrconnect://
  console.log('signer: connect response sent for', clientPub.slice(0, 8));
};
ws.onmessage = (m) => {
  const msg = JSON.parse(m.data);
  if (msg[0] !== 'EVENT') return;
  const ev = msg[2];
  if (ev.pubkey !== clientPub) return;
  let req;
  try { req = JSON.parse(nip44.v2.decrypt(ev.content, ck)); } catch { return; }
  const [a, b] = req.params;
  try {
    if (req.method === 'connect') reply(req.id, 'ack');
    else if (req.method === 'get_public_key') reply(req.id, userPub);
    else if (req.method === 'ping') reply(req.id, 'pong');
    else if (req.method === 'nip44_encrypt') reply(req.id, nip44.v2.encrypt(b, nip44.v2.utils.getConversationKey(DEMO_USER_SK, a)));
    else if (req.method === 'nip44_decrypt') reply(req.id, nip44.v2.decrypt(b, nip44.v2.utils.getConversationKey(DEMO_USER_SK, a)));
    else if (req.method === 'sign_event') reply(req.id, JSON.stringify(finalizeEvent(JSON.parse(a), DEMO_USER_SK)));
    else reply(req.id, '', 'unsupported');
    console.log('signer:', req.method);
  } catch (e) { reply(req.id, '', String(e.message)); }
};

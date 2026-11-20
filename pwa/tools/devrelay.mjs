// A tiny in-memory Nostr relay for trying the web app locally with synthetic data. Not for production.
//   node tools/devrelay.mjs [port]
// Serves tools/demo-reports.json (see gen_demo.py) as kind 4243 events and the key backup for the
// demo passphrase. Prints every kind 4244 (command mailbox) event it receives.
import http from 'node:http';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { finalizeEvent, generateSecretKey, verifyEvent } from 'nostr-tools/pure';
import { buildBackupEvent, derive } from '../src/lib/backup.js';
import { buildBackupBEvent } from '../src/lib/backupB.js';
import { plaintextOf } from '../src/lib/merge.js';
import * as nip44 from 'nostr-tools/nip44';
import { getPublicKey } from 'nostr-tools/pure';
import { DEMO_USER_SK } from './demo-keys.mjs';

const port = Number(process.argv[2] || 7777);
const data = JSON.parse(readFileSync(new URL('./demo-reports.json', import.meta.url)));
const events = [];
const now = Math.floor(Date.now() / 1000);

for (const tag of data.tags) {
  for (const r of tag.reports) {
    events.push(finalizeEvent({ kind: 4243, created_at: now - r.ago_s, tags: [['y', r.x], ['expiration', String(now + 7 * 86400)]], content: r.content }, generateSecretKey()));
  }
}
// per-tag settings the Android app keeps in the backup (synthetic zones)
const EXTRA = {
  Car: { interval_min: 60, zones: [{ name: 'Garage', lat: 48.7012, lon: 21.2541, r_m: 150 }] },
  Backpack: { interval_min: 60, zones: [{ name: 'Home', lat: 48.7100, lon: 21.2300, r_m: 200 }] },
  Anna: { interval_min: 15, zones: [{ name: 'School', lat: 48.7060, lon: 21.2200, r_m: 200 }] },
};
const d = await derive(data.passphrase);
const payload = JSON.stringify({ v: 1, created: now, owner: null, tags: data.tags.map((t) => ({ name: t.name, k0: t.k0, type: t.type, mark: t.mark, updated: now - 7200, ...(EXTRA[t.name] ?? {}) })) });
events.push(await buildBackupEvent(d, payload, now - 3600));
// copy B: the same tags NIP-44 encrypted to the demo user (see fake-signer.mjs)
const userPub = getPublicKey(DEMO_USER_SK);
const ck = nip44.v2.utils.getConversationKey(DEMO_USER_SK, userPub);
const dB = 'b'.repeat(32);
const textB = plaintextOf({ created: now - 1800, owner: null, d: dB, tags: data.tags.map((t) => { const [color, symbol] = t.mark.split('-'); return { name: t.name, k0: t.k0, type: t.type, mark: { color, symbol }, updated: now - 7200, extra: EXTRA[t.name] ?? {} }; }) });
const demoSigner = { pubkey: userPub, nip44Encrypt: async (x) => nip44.v2.encrypt(x, ck), signEvent: async (tpl) => finalizeEvent(tpl, DEMO_USER_SK) };
events.push(await buildBackupBEvent(demoSigner, dB, textB, now - 1800));
console.log(`demo relay: ${events.length} events, backup passphrase: "${data.passphrase}"`);

const match = (f, e) => {
  if (f.kinds && !f.kinds.includes(e.kind)) return false;
  if (f.authors && !f.authors.includes(e.pubkey)) return false;
  if (f.ids && !f.ids.includes(e.id)) return false;
  if (f.since && e.created_at < f.since) return false;
  if (f.until && e.created_at > f.until) return false;
  for (const k of Object.keys(f)) {
    if (k[0] !== '#') continue;
    const want = f[k];
    if (!e.tags.some((t) => t[0] === k.slice(1) && want.includes(t[1]))) return false;
  }
  return true;
};

function frame(opcode, payload) {
  const len = payload.length;
  const head = len < 126 ? Buffer.from([0x80 | opcode, len]) : len < 65536 ? Buffer.from([0x80 | opcode, 126, len >> 8, len & 255])
    : (() => { const b = Buffer.alloc(10); b[0] = 0x80 | opcode; b[1] = 127; b.writeBigUInt64BE(BigInt(len), 2); return b; })();
  return Buffer.concat([head, payload]);
}

const allSubs = new Set(); // { send, id, filters } across connections
const server = http.createServer((req, res) => { res.writeHead(200, { 'content-type': 'application/nostr+json' }); res.end('{"name":"libretag demo relay"}'); });
server.on('upgrade', (req, socket) => {
  const key = req.headers['sec-websocket-key'];
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')}\r\n\r\n`);
  const subs = new Map();
  const mine = new Set();
  const send = (arr) => socket.write(frame(1, Buffer.from(JSON.stringify(arr))));
  let buf = Buffer.alloc(0);
  const onMsg = async (text) => {
    let m; try { m = JSON.parse(text); } catch { return; }
    if (process.env.DEBUG_RELAY) console.log('<-', text.slice(0, 160));
    if (m[0] === 'REQ') {
      const [, id, ...filters] = m;
      subs.set(id, filters);
      for (const o of [...mine]) if (o.id === id) { mine.delete(o); allSubs.delete(o); }
      const reg = { send, id, filters }; mine.add(reg); allSubs.add(reg);
      for (const f of filters) {
        const hits = events.filter((e) => match(f, e)).sort((a, b) => b.created_at - a.created_at).slice(0, f.limit ?? 5000);
        for (const e of hits) send(['EVENT', id, e]);
      }
      send(['EOSE', id]);
    } else if (m[0] === 'CLOSE') { subs.delete(m[1]); for (const o of [...mine]) if (o.id === m[1]) { mine.delete(o); allSubs.delete(o); } }
    else if (m[0] === 'EVENT') {
      const e = m[1];
      const ok = verifyEvent(e);
      if (ok) {
        events.push(e);
        if (e.kind === 4244) console.log(`mailbox event: ${e.tags.filter((t) => t[0] === 'y').length} y, route ${e.tags.find((t) => t[0] === 'route')?.[1]}, at ${e.tags.find((t) => t[0] === 'at')?.[1] ?? '-'}`);
        for (const o of allSubs) if (o.filters.some((f) => match(f, e))) o.send(['EVENT', o.id, e]);
      }
      send(['OK', e.id, ok, ok ? '' : 'invalid']);
    }
  };
  socket.on('data', (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    for (;;) {
      if (buf.length < 2) return;
      const op = buf[0] & 15; let len = buf[1] & 127; let off = 2;
      if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
      const masked = !!(buf[1] & 128);
      if (buf.length < off + (masked ? 4 : 0) + len) return;
      const mask = masked ? buf.subarray(off, off + 4) : null; off += masked ? 4 : 0;
      const payload = Buffer.from(buf.subarray(off, off + len));
      if (mask) for (let i = 0; i < len; i++) payload[i] ^= mask[i & 3];
      buf = buf.subarray(off + len);
      if (op === 1) onMsg(payload.toString('utf8'));
      else if (op === 9) socket.write(frame(10, payload));
      else if (op === 8) { socket.end(frame(8, Buffer.alloc(0))); return; }
    }
  });
  socket.on('error', () => {});
  socket.on('close', () => { for (const o of mine) allSubs.delete(o); });
});
server.listen(port, '127.0.0.1', () => console.log(`ws://127.0.0.1:${port}`));

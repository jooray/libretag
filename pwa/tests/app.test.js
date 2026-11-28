import { describe, expect, it } from 'vitest';
import { App } from '../src/lib/app.js';
import { makeTag, parseKeyInput } from '../src/lib/keys.js';
import { setLang } from '../src/lib/i18n.js';
import { slip, statusOf } from '../src/lib/status.js';
import { cancelEvents } from '../src/lib/mailbox.js';
import * as L from '../src/lib/lt2.js';

setLang('en');

function appWithTag() {
  const app = new App();
  const { chain, tag } = makeTag(parseKeyInput('00'.repeat(31) + '01'), []);
  const rec = { ...tag, chain, positions: [], lastN: 5, pending: { kind: 'BEEP', seq: 3, queuedAt: 1_000_000, ys: ['aa11bb22', 'cc33dd44'], sent: [] } };
  app.tags.push(rec);
  return { app, rec };
}

describe('a bridge claim marks the command as sent', () => {
  it('shows it as sent, and it can no longer be cancelled', async () => {
    const { app, rec } = appWithTag();
    expect(slip(rec, statusOf(rec), 2_000_000).state).toBe('wait');
    expect(app.markSentFromClaim({ created_at: 1500, tags: [['y', 'cc33dd44'], ['hops', '1']] })).toBe(rec.id);
    expect(rec.pending.sentAt).toBe(1_500_000);
    expect(slip(rec, statusOf(rec), 2_000_000)).toMatchObject({ state: 'sent' });
    expect(await app.cancelCommand(rec.id)).toBe(0);
    expect(rec.pending).not.toBeNull();
  });
  it('ignores an "ack sent", miss or done note that shares the y', () => {
    const { app, rec } = appWithTag();
    for (const t of [['ack', '1'], ['miss', 'radio'], ['done', 'cc33dd44']]) {
      expect(app.markSentFromClaim({ created_at: 1500, tags: [['y', 'cc33dd44'], t] })).toBeNull();
    }
    expect(rec.pending.sentAt).toBeUndefined();
  });
  it('ignores a claim for something that is not ours', () => {
    const { app, rec } = appWithTag();
    expect(app.markSentFromClaim({ created_at: 1500, tags: [['y', 'ffffffff']] })).toBeNull();
    expect(rec.pending.sentAt).toBeUndefined();
  });
  it('cancelling a waiting command clears it and nothing needs the relays when no event was sent', async () => {
    const { app, rec } = appWithTag();
    expect(cancelEvents([])).toEqual([]);
    await app.cancelCommand(rec.id);
    expect(rec.pending).toBeNull();
  });
});

describe('a shared tag stays out of the owner backup', () => {
  it('is marked imported, has no K0, and is not in the backup payload', () => {
    const app = new App();
    const ve = '11'.repeat(32);
    const [sh] = app.addFromParsed(parseKeyInput(`libretag://share?v=1&name=Bike&type=thing&mark=red-diag&e=2&ve=${ve}`));
    expect(sh.imported).toBe(true);
    expect(sh.k0).toBeUndefined();
    expect(sh.share.ve).toBe(ve);
    expect(app._localPayload().tags).toEqual([]);
  });
});

describe('publishing a command entry by entry', () => {
  const setup = () => {
    const { app, rec } = appWithTag();
    rec.pending = null;
    rec.positions = [{ n: 5, receivedAt: Date.now() - 60_000, mode: 'IDLE', cmdAck: false, cmdSeq: 0, quiet: false, checks: false, nextInS: 3600, hasFix: false }];
    const published = [];
    let gate = null;
    app.pool = { publish: (_r, ev) => [(gate ?? Promise.resolve()).then(() => { published.push(ev); return 'ok'; })], subscribeMany: () => ({ close() {} }),
      querySync: async () => [], listConnectionStatus: () => new Map(), close() {} };
    return { app, rec, published, hold: () => { let open; gate = new Promise((r) => { open = r; }); return () => { gate = null; open(); }; } };
  };
  const settle = async (app, id) => { for (let i = 0; i < 200 && app._queues.has(id); i++) await new Promise((r) => setTimeout(r, 5)); };

  const commands = (published) => published.filter((e) => e.kind === 4244 && e.content !== '');
  const anchors = (published) => published.filter((e) => e.kind === 4244 && e.content === '');
  const pkOf = (rec, n) => L.mailboxPk(rec.chain.cmd(L.epoch(n)), n);

  it('answers after the first entry, then publishes the rest, one group tag, signed by sk_n, no work', async () => {
    const { app, rec, published } = setup();
    const r = await app.queueCommand(rec.id, 'BEEP');
    expect(r).toEqual({ ok: 1, events: 1 });
    await settle(app, rec.id);
    await new Promise((r2) => setTimeout(r2, 20));
    // report 5 came 60 s ago: too late for a late entry (P6, 40 s), so the next ten counters
    const cmds = commands(published);
    expect(cmds).toHaveLength(10);
    const ns = [6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
    cmds.forEach((e, i) => {
      expect(e.pubkey).toBe(pkOf(rec, ns[i]));
      expect(e.tags).toContainEqual(['v', '2']);
      expect(e.tags.some((t) => t[0] === 'nonce')).toBe(false);
    });
    const gs = new Set(cmds.map((e) => e.tags.find((t) => t[0] === 'g')[1]));
    expect(gs.size).toBe(1);
    expect([...gs][0]).toBe(rec.pending.g);
    expect(rec.pending.sent).toHaveLength(10);
    expect(rec.pending.sent.every((x) => !('sk' in x) && Number.isInteger(x.n))).toBe(true);
    // anchors for 6..21, the reboot checkpoint 32 (16 is among them) and the next epoch's first counter (64)
    const want = [...Array(16).keys()].map((i) => 6 + i).concat([32, 64]);
    const an = anchors(published);
    expect(an.map((e) => e.pubkey).sort()).toEqual(want.map((n) => pkOf(rec, n)).sort());
    expect(Object.keys(rec.mbAnchors).map(Number).sort((a, b) => a - b)).toEqual(want);
  });
  it('anchors are not published again while they live, and a view share publishes none', async () => {
    const { app, rec, published } = setup();
    rec.pending = null;
    expect(await app.publishAnchors(rec)).toBeGreaterThan(0);
    const n = published.length;
    expect(await app.publishAnchors(rec)).toBe(0);
    expect(published).toHaveLength(n);
    const share = { ...rec, id: 'v', chain: L.KeyChain.fromShare(0, rec.chain.view(0)), mbAnchors: {} };
    expect(await app.publishAnchors(share)).toBe(0);
  });
  it('resuming (next open) publishes only what is not out yet', async () => {
    const { app, rec, published } = setup();
    await app.queueCommand(rec.id, 'BEEP');
    await settle(app, rec.id);
    published.length = 0;
    rec.lastN = 6; // one report later: one more counter ahead
    await app.publishPending(rec);
    await settle(app, rec.id);
    expect(commands(published)).toHaveLength(1);
    expect(commands(published)[0].tags.find((t) => t[0] === 'g')[1]).toBe(rec.pending.g);
  });
  it('cancelling stops the queue: nothing more goes out', async () => {
    const { app, rec, published, hold } = setup();
    const release = hold();
    const first = app.queueCommand(rec.id, 'BEEP');
    await new Promise((r) => setTimeout(r, 50));
    release();
    await first;
    await app.cancelCommand(rec.id);
    const n = published.filter((e) => e.kind === 4244).length;
    await new Promise((r) => setTimeout(r, 100));
    expect(published.filter((e) => e.kind === 4244).length).toBe(n);
    expect(app._queues.has(rec.id)).toBe(false);
  });
});

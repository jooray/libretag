import { describe, expect, it } from 'vitest';
import { App } from '../src/lib/app.js';
import { derive, restore } from '../src/lib/backup.js';
import { distanceM, zoneStep } from '../src/lib/geo.js';
import { setLang } from '../src/lib/i18n.js';
import { makeTag, parseKeyInput, tagsFromBackup } from '../src/lib/keys.js';
import { mergePayloads, parsePlaintext, plaintextOf } from '../src/lib/merge.js';
import { checkMnemonic, normalizeMnemonic } from '../src/lib/mnemonic.js';
import { levelOf, statusOf, statusSub, statusText } from '../src/lib/status.js';

setLang('en');
const K0 = (c) => c.repeat(32);
const zones = [{ name: 'School', lat: 48.7060, lon: 21.2200, r_m: 200 }];
const extraTag = (updated, o = {}) => ({ name: 'Anna', k0: K0('11'), type: 'person', mark: 'green-circle', updated,
  interval_min: 30, check_s: 20, still: { after: 3, hb_h: 6 }, use: 'people', fw: '0.7.0', zones,
  anchors: [{ label: 'Pixel', kind: 'irk' }], future_field: { x: 1 }, ...o });

describe('per-tag settings in the backup plaintext (12.2 and later)', () => {
  it('keeps every field, also the ones this app does not know', () => {
    const text = JSON.stringify({ v: 1, created: 5, owner: null, d: 'ab', tags: [extraTag(10)] });
    const p = parsePlaintext(text);
    expect(p.tags[0].extra).toMatchObject({ interval_min: 30, check_s: 20, use: 'people', fw: '0.7.0', future_field: { x: 1 } });
    const back = JSON.parse(plaintextOf({ created: 5, owner: null, d: 'ab', tags: p.tags.map((t) => ({ ...t, mark: { color: 'green', symbol: 'circle' } })) }));
    expect(back.tags[0]).toEqual(extraTag(10));
  });
  it('a newer entry brings its settings along when merged', () => {
    const a = { v: 1, tags: [extraTag(10)] };
    const b = { v: 1, tags: [extraTag(20, { interval_min: 60 })] };
    const m = mergePayloads([a, b].map((x) => parsePlaintext(JSON.stringify(x))), 100);
    expect(m.tags[0].extra.interval_min).toBe(60);
    expect(m.tags[0].extra.future_field).toEqual({ x: 1 });
  });
  it('a restored tag gets its interval and zones', () => {
    const [{ tag }] = tagsFromBackup(parsePlaintext(JSON.stringify({ v: 1, tags: [extraTag(10)] })));
    expect(tag.intervalMin).toBe(30);
    expect(tag.zones).toEqual(zones);
  });
});

describe('zones', () => {
  it('distance is metres, and leaving needs radius + 50 m', () => {
    expect(distanceM(48.7, 21.25, 48.7009, 21.25)).toBeGreaterThan(95);
    const inside = { lat: 48.7060, lon: 21.2200 };
    const edge = { lat: 48.7060 + 220 / 110_540, lon: 21.2200 };   // 220 m: outside r, inside r + 50
    const far = { lat: 48.7060 + 400 / 110_540, lon: 21.2200 };
    let s = zoneStep(zones, null, inside);
    expect(s).toEqual({ inZone: 'School', left: null });
    s = zoneStep(zones, 'School', edge);
    expect(s.inZone).toBe('School');
    s = zoneStep(zones, 'School', far);
    expect(s).toEqual({ inZone: null, left: 'School' });
  });
});

function tagWithZones() {
  const app = new App();
  const { chain, tag } = makeTag(parseKeyInput(K0('11')), []);
  const rec = { ...tag, chain, zones, positions: [], lastN: 0, pending: null, inZone: null, alarm: null, intervalMin: 60 };
  app.tags.push(rec);
  app.firstSyncDone.add(rec.id);
  return { app, rec };
}
const rep = (o) => ({ hasFix: true, fixTime: 1, lat: 48.7060, lon: 21.2200, haccM: 6, battery: 70, mode: 'IDLE', cmdAck: false, cmdSeq: 0,
  lowBatt: false, nbTotal: 2, nbDiscover: false, neighbours: [], quiet: false, quietReason: null, checks: false, nextInS: 3600, energyMah: null, ...o });

describe('leaving a place', () => {
  it('shows "Left School 14:02" as a neutral state for 12 h, below silence and battery, and ends when it is back', async () => {
    const { app, rec } = tagWithZones();
    const now = Date.now();
    await app._addReport(rec, 1, rep({}), now - 7_200_000);
    expect(rec.inZone).toBe('School');
    expect(rec.leftPlace).toBeNull();
    await app._addReport(rec, 2, rep({ lat: 48.7120 }), now - 60_000);
    expect(rec.leftPlace).toBe('School');
    expect(rec.alarm).toBeNull();
    const s = statusOf(rec, now);
    expect(s.kind).toBe('LEFT');
    expect(levelOf(s)).toBe('');
    expect(statusText(rec, s, now)).toMatch(/^Left School \d/);
    expect(statusOf(rec, now + 13 * 3600_000).kind).not.toBe('LEFT');
    await app._addReport(rec, 3, rep({}), now + 60_000);
    expect(rec.leftPlace).toBeNull();
  });
  it('leaving together with your own device is no departure', async () => {
    const { app, rec } = tagWithZones();
    const now = Date.now();
    await app._addReport(rec, 1, rep({}), now - 7_200_000);
    await app._addReport(rec, 2, rep({ lat: 48.7120, quiet: true, quietReason: 'owner' }), now - 60_000);
    expect(rec.leftPlace).toBeNull();
  });
  it('a guarded spot raises the guard alarm once, and Stop guarding clears it', async () => {
    const { app, rec } = tagWithZones();
    app.saveBackup = async () => ({});
    const now = Date.now();
    await app._addReport(rec, 1, rep({}), now - 7_200_000);
    await app.guardHere(rec.id);
    expect(rec.extra.guard).toMatchObject({ lat: 48.706, lon: 21.22, r_m: 300 });
    await app._addReport(rec, 2, rep({ lat: 48.7061 }), now - 3_600_000);
    expect(rec.alarm).toBeNull();
    await app._addReport(rec, 3, rep({ lat: 48.7120 }), now - 60_000);
    expect(rec.alarm).toMatchObject({ guard: true });
    const s = statusOf(rec, now);
    expect(s.kind).toBe('ALARM');
    expect(statusText(rec, s, now)).toBe('Moved while guarded');
    expect(statusSub(rec, s, now)).toMatch(/^Left the guarded spot \d/);
    await app.guardOff(rec.id);
    expect(rec.extra.guard).toBeNull();
    expect(statusOf(rec, now).kind).not.toBe('ALARM');
  });
  it('quiet in a zone names it', async () => {
    const { app, rec } = tagWithZones();
    const now = Date.now();
    await app._addReport(rec, 1, rep({ quiet: true, quietReason: 'zone', nextInS: 86400 }), now - 60_000);
    expect(statusText(rec, statusOf(rec, now), now)).toMatch(/^Quiet at School/);
  });
});

describe('making a backup from the web', () => {
  it('generates a valid 12-word phrase and writes copy A that restores', async () => {
    const app = new App();
    const published = [];
    app.pool = { querySync: async () => [], publish: (_r, ev) => { published.push(ev); return [Promise.resolve('ok')]; }, listConnectionStatus: () => new Map(), close() {}, subscribeMany: () => ({ close() {} }) };
    const { chain, tag } = makeTag({ ...parseKeyInput(K0('0a')), name: 'Car', type: 'vehicle' }, []);
    app.tags.push({ ...tag, chain, positions: [], lastN: -1, extra: { zones: [], future_field: 7 }, intervalMin: 120 });
    const phrase = app.newPhrase();
    expect(phrase.split(' ')).toHaveLength(12);
    expect(checkMnemonic(phrase)).toBe('ok');
    const out = await app.createBackup(phrase, 1_800_000_000);
    expect(out.a).toBe(1);
    expect(out.b).toBeNull();
    const d = await derive(normalizeMnemonic(phrase));
    const r = await restore(d, published);
    expect(r.state).toBe('found');
    expect(r.payload.tags[0]).toMatchObject({ name: 'Car', k0: K0('0a'), type: 'vehicle', updated: 1_800_000_000 });
    expect(r.payload.tags[0].extra).toMatchObject({ interval_min: 120, future_field: 7 });
  });
  it('a single-tag import is not in the backup until it is added', async () => {
    const app = new App();
    const { chain, tag } = makeTag(parseKeyInput(K0('0b')), []);
    app.tags.push({ ...tag, chain, positions: [], lastN: -1, imported: true });
    expect(app._backupTags()).toHaveLength(0);
    expect(await app.addToBackup(tag.id)).toBe(false); // nothing to save to yet
    expect(app._backupTags()).toHaveLength(1);
  });
});

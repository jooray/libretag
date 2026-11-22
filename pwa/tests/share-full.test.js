import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { fullKeys, makeTag, parseKeyInput, shareEpoch, shareLevels, shareLink } from '../src/lib/keys.js';
import { epoch as epochOf } from '../src/lib/lt2.js';

// the vectors the Python and Kotlin sides use (PROTOCOL.md 1, sharing)
const V = JSON.parse(readFileSync(new URL('../../host/tests/vectors/share.json', import.meta.url)));
const [color, symbol] = V.mark.split('-');
const meta = { name: V.name, type: V.type, mark: { color, symbol } };

describe('full-access share links (vectors/share.json)', () => {
  const { chain, tag } = makeTag({ k0: V.k0, ...meta });

  it('K0 derives the console key and id, so the web writes the same three links as the app', () => {
    expect(shareLink(tag, chain, V.epoch, 'view')).toBe(V.view);
    expect(shareLink(tag, chain, V.epoch, 'control')).toBe(V.control);
    expect(shareLink(tag, chain, V.epoch, 'full')).toBe(V.full);
  });

  it('parses all three levels, and full access keeps uk and id', () => {
    const v = parseKeyInput(V.view).shared, c = parseKeyInput(V.control).shared, f = parseKeyInput(V.full).shared;
    expect(v).toMatchObject({ epoch: 3, ce: null });
    expect(v.uk).toBeUndefined();
    expect(c.ce).toMatch(/^[0-9a-f]{64}$/);
    expect(c.uk).toBeUndefined();
    expect(f).toMatchObject({ epoch: 3, uk: expect.stringMatching(/^[0-9a-f]{64}$/), id: 'bebef88c94a515c6' });
    expect(parseKeyInput(V.full)).toMatchObject({ name: V.name, type: V.type, mark: { color, symbol } });
  });

  it('refuses every invalid link of the vectors', () => {
    for (const bad of V.invalid) expect(parseKeyInput(bad), bad).toBeNull();
  });

  it('a share imported with full access stores uk and id and can pass the link on unchanged', () => {
    const built = makeTag(parseKeyInput(V.full));
    expect(built.tag.k0).toBeUndefined();
    expect(built.tag.share).toMatchObject({ uk: expect.any(String), id: 'bebef88c94a515c6' });
    expect(built.chain.canCommand).toBe(true);
    expect(fullKeys(built.tag, built.chain)).toEqual({ uk: built.tag.share.uk, id: 'bebef88c94a515c6' });
    const t = { ...built.tag, chain: built.chain, lastN: V.epoch * 64 };
    expect(shareLevels(t)).toEqual(['view', 'control', 'full']);
    expect(shareLink(t, built.chain, shareEpoch(t, epochOf), 'full')).toBe(V.full);
    expect(shareLink(t, built.chain, shareEpoch(t, epochOf), 'view')).toBe(V.view);
  });

  it('the levels follow the keys held: a view share hands on view only, a control share no full access', () => {
    const view = makeTag(parseKeyInput(V.view));
    const ctl = makeTag(parseKeyInput(V.control));
    expect(shareLevels({ ...view.tag, chain: view.chain })).toEqual(['view']);
    expect(shareLevels({ ...ctl.tag, chain: ctl.chain })).toEqual(['view', 'control']);
    expect(() => shareLink({ ...ctl.tag, chain: ctl.chain }, ctl.chain, V.epoch, 'full')).toThrow();
  });

  it('a share never goes below the epoch it starts at, even for a tag that has not reported yet', () => {
    const built = makeTag(parseKeyInput(V.full));
    expect(shareEpoch({ chain: built.chain, lastN: -1 }, epochOf)).toBe(V.epoch);
  });
});

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { parseKeyInput, makeTag, k0Link, tagsFromBackup, shareLink, shareWebLink, shareFromHash } from '../src/lib/keys.js';

const V = JSON.parse(readFileSync(new URL('../../host/tests/vectors/lt2.json', import.meta.url)));

describe('key input', () => {
  it('accepts a bare K0, with spaces and capitals', () => {
    const spaced = V.k0.toUpperCase().replace(/(.{8})/g, '$1 ');
    expect(parseKeyInput(spaced).k0).toBe(V.k0);
    expect(makeTag(parseKeyInput(V.k0)).tag.id).toBe(V.id);
  });
  it('round-trips a libretag://k0 link, and takes the single-colon form', () => {
    const { tag } = makeTag({ k0: V.k0, name: 'Kajak', type: 'thing', mark: { color: 'blue', symbol: 'circle' } });
    const link = k0Link(tag);
    expect(link.startsWith('libretag://k0?')).toBe(true);
    const back = parseKeyInput(link);
    expect(back).toMatchObject({ k0: V.k0, name: 'Kajak', type: 'thing', mark: { color: 'blue', symbol: 'circle' } });
    expect(parseKeyInput(link.replace('libretag://', 'libretag:')).k0).toBe(V.k0);
  });
  it('rejects garbage and wrong lengths', () => {
    for (const s of ['', 'hello', V.k0.slice(2), 'libretag://k0?k=zz', 'https://x/?k=' + V.k0]) expect(parseKeyInput(s)).toBeNull();
  });
  it('parses an Android share link (V_e from an epoch)', () => {
    const p = parseKeyInput(`libretag://share?v=1&name=Bike&type=vehicle&mark=red-diag&e=3&ve=${V.view2}&ce=${V.cmd2}`);
    expect(p.shared).toEqual({ epoch: 3, ve: V.view2, ce: V.cmd2 });
    expect(p.mark).toEqual({ color: 'red', symbol: 'diag' });
    expect(parseKeyInput(`libretag://share?v=1&e=3&ve=${V.view2}`).shared.ce).toBeNull();
  });
  it('picks a free blaze when none is given', () => {
    const a = makeTag(parseKeyInput(V.k0)).tag;
    const b = makeTag(parseKeyInput('11'.repeat(32)), [a.mark]).tag;
    expect(a.mark).toEqual({ color: 'red', symbol: 'stripe' });
    expect(b.mark).toEqual({ color: 'blue', symbol: 'stripe' });
  });
  it('turns a backup payload into tags', () => {
    const tags = tagsFromBackup({ tags: [{ name: 'Car', k0: V.k0, type: 'vehicle', mark: 'red-stripe' }] });
    expect(tags[0].tag).toMatchObject({ name: 'Car', type: 'vehicle', id: V.id });
  });

  describe('share out', () => {
    const F = JSON.parse(readFileSync(new URL('../../docs/vectors/share-link-v1.json', import.meta.url)));
    const [color, symbol] = F.mark.split('-');
    const { chain, tag } = makeTag({ k0: F.k0, name: F.name, type: F.type, mark: { color, symbol } });
    it('writes the exact link the Android app writes', () => {
      expect(shareLink(tag, chain, F.epoch, false)).toBe(F.view_link);
      expect(shareLink(tag, chain, F.epoch, true)).toBe(F.control_link);
    });
    it('is parsed back identically, view and control', () => {
      expect(parseKeyInput(F.view_link)).toEqual({ shared: { epoch: 2, ve: V.view2, ce: null }, name: F.name, type: 'thing', mark: { color, symbol } });
      expect(parseKeyInput(F.control_link).shared).toEqual({ epoch: 2, ve: V.view2, ce: V.cmd2 });
    });
    it('the https fallback carries the same payload in the fragment only', () => {
      const w = shareWebLink(F.control_link);
      expect(w.startsWith('https://cypherpunk.today/libretag/app/#share=')).toBe(true);
      expect(w.split('#')[0]).not.toContain('ve=');
      expect(shareFromHash('#' + w.split('#')[1])).toBe(F.control_link);
      expect(shareFromHash('#/t/abc')).toBeNull();
    });
    it('a share imported from it never holds K0', () => {
      const built = makeTag(parseKeyInput(F.view_link));
      expect(built.tag.k0).toBeUndefined();
      expect(built.chain.canCommand).toBe(false);
    });
  });
});

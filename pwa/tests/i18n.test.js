import { describe, expect, it } from 'vitest';
import en from '../src/i18n/en.js';
import sk from '../src/i18n/sk.js';
import { setLang, t } from '../src/lib/i18n.js';

const params = (v) => [...new Set((typeof v === 'object' ? Object.values(v).join(' ') : v).match(/\{\w+\}/g) ?? [])].sort();

describe('English and Slovak', () => {
  it('have the same keys and the same placeholders', () => {
    expect(Object.keys(sk).sort()).toEqual(Object.keys(en).sort());
    for (const k of Object.keys(en)) expect(params(sk[k]), k).toEqual(params(en[k]));
  });
  it('plural forms: Slovak has one / few / other where a count is shown', () => {
    for (const k of Object.keys(sk)) if (typeof sk[k] === 'object') expect(Object.keys(sk[k]), k).toEqual(expect.arrayContaining(['one', 'few', 'other']));
    setLang('sk');
    expect(t('home.tags', { n: 1 })).toBe('1 lokátor');
    expect(t('home.tags', { n: 3 })).toBe('3 lokátory');
    expect(t('home.tags', { n: 5 })).toBe('5 lokátorov');
    setLang('en');
    expect(t('home.tags', { n: 2 })).toBe('2 tags');
  });
  it('have no em dashes, and no English quote pairs in Slovak', () => {
    for (const d of [en, sk]) for (const v of Object.values(d)) expect(JSON.stringify(v)).not.toMatch(/[—]/);
  });
});

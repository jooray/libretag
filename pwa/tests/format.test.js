import { afterAll, describe, expect, it } from 'vitest';
import { setLang } from '../src/lib/i18n.js';
import { agoAt, at, date, heardRow } from '../src/lib/format.js';

// local times, so the test holds in any time zone
const NOW = new Date(2026, 10, 16, 18, 0).getTime(); // Mon 16 Nov 2026, 18:00
const today = new Date(2026, 10, 16, 14, 32).getTime();
const yesterday = new Date(2026, 10, 15, 23, 10).getTime();
const older = new Date(2026, 10, 13, 9, 5).getTime(); // Fri 13 Nov
const lastYear = new Date(2025, 11, 30, 7, 0).getTime();

afterAll(() => setLang('en'));

describe('past times: clock time, with the day when not today', () => {
  it('English', () => {
    setLang('en');
    expect(at(today, NOW)).toBe('14:32');
    expect(at(yesterday, NOW)).toBe('yesterday 23:10');
    expect(at(older, NOW)).toBe('Fri 13/11 09:05');
    expect(at(lastYear, NOW)).toMatch(/30\/12\/2025 07:00$/);
    expect(agoAt(today, NOW)).toBe('3 h ago (14:32)');
    expect(agoAt(yesterday, NOW)).toBe('18 h ago (yesterday 23:10)');
    expect(agoAt(older, NOW)).toBe('3 days ago (Fri 13/11 09:05)');
  });
  it('Slovak', () => {
    setLang('sk');
    expect(at(today, NOW)).toBe('14:32');
    expect(at(yesterday, NOW)).toBe('včera 23:10');
    expect(at(older, NOW)).toBe('pi 13. 11. 09:05');
    expect(date(lastYear, NOW)).toMatch(/30\. 12\. 2025$/);
    expect(agoAt(today, NOW)).toBe('pred 3 h (14:32)');
    expect(agoAt(new Date(2026, 10, 16, 17, 48).getTime(), NOW)).toBe('pred 12 min (17:48)');
  });
  it('just after midnight, a time before midnight is yesterday', () => {
    setLang('en');
    const n = new Date(2026, 10, 16, 0, 5).getTime();
    expect(at(new Date(2026, 10, 15, 23, 55).getTime(), n)).toBe('yesterday 23:55');
    expect(at(new Date(2026, 10, 14, 23, 55).getTime(), n)).toBe('Sat 14/11 23:55');
  });
});

describe('list rows: relative under an hour, then the clock time', () => {
  const MIN = 60_000;
  it('English', () => {
    setLang('en');
    expect(heardRow(NOW - 12 * MIN, NOW)).toBe('Heard 12 min ago');
    expect(heardRow(NOW - 59 * MIN, NOW)).toBe('Heard 59 min ago');
    expect(heardRow(NOW - 60 * MIN, NOW)).toBe('Heard at 17:00');
    expect(heardRow(today, NOW)).toBe('Heard at 14:32');
    expect(heardRow(yesterday, NOW)).toBe('Heard yesterday 23:10');
    expect(heardRow(older, NOW)).toBe('Heard Fri 13/11 09:05');
  });
  it('Slovak', () => {
    setLang('sk');
    expect(heardRow(NOW - 12 * MIN, NOW)).toBe('Zachytené pred 12 min');
    expect(heardRow(today, NOW)).toBe('Zachytené o 14:32');
    expect(heardRow(yesterday, NOW)).toBe('Zachytené včera 23:10');
    expect(heardRow(older, NOW)).toBe('Zachytené pi 13. 11. 09:05');
  });
  it('across midnight', () => {
    setLang('en');
    const n = new Date(2026, 10, 16, 0, 5).getTime();
    expect(heardRow(new Date(2026, 10, 15, 23, 55).getTime(), n)).toBe('Heard 10 min ago');
    expect(heardRow(new Date(2026, 10, 15, 22, 55).getTime(), n)).toBe('Heard yesterday 22:55');
  });
});

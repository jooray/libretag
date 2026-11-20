import { describe, expect, it } from 'vitest';
import { buildCommand, TRACK_MIN, trackCapAfterMin, trackHitsCap, trackReports } from '../src/lib/mailbox.js';
import { Command } from '../src/lib/lt2.js';
import { slip } from '../src/lib/status.js';

describe('Track: how often and how long', () => {
  it('carries the duration in SET_MODE (u16 duration_min after u16 interval_s)', () => {
    const want = Command.setMode(3, 1, 600, 240);
    expect([...buildCommand('TRACK', 3, 600, 240)]).toEqual([...want]);
    // one tap: every 5 min for 1 h
    expect([...buildCommand('TRACK', 3)]).toEqual([...Command.setMode(3, 1, 300, TRACK_MIN)]);
    expect([...buildCommand('TRACK', 3, 600, 240)]).not.toEqual([...buildCommand('TRACK', 3, 600)]);
  });
  it('counts reports and warns before the daily limit', () => {
    expect(trackReports(300, 60)).toBe(12);
    expect(trackReports(60, 60)).toBe(30);
    expect(trackHitsCap(300, 720)).toBe(false);
    expect(trackHitsCap(300, 1440)).toBe(true);
    expect(trackCapAfterMin(300, 1440)).toBe(800);
    expect(trackCapAfterMin(1800, 1440)).toBe(null);
  });
  it('the slip says the chosen duration', () => {
    const tag = { name: 'Car', pending: { kind: 'TRACK', seq: 3, queuedAt: 0, intervalS: 600, durationMin: 240 }, positions: [] };
    expect(slip(tag, { kind: 'WAITING' }, 1000).text).toContain('Track 4 h, every 10 min');
  });
});

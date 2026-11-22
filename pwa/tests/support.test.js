import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import * as E from '../src/lib/support.js';
import { SupportAsk } from '../src/lib/support.js';

// the shared vectors of the support widget (as the Android app's SupportEngineTest replays them)
const V = JSON.parse(readFileSync(new URL('./vectors/support-engine-vectors.json', import.meta.url)));

describe('support engine', () => {
  for (const c of V.cases) {
    it(c.name, () => {
      let s = E.initialState();
      for (const [op, a, b] of c.steps) {
        if (op === 'launch') s = E.onLaunch(s, a);
        else if (op === 'action') for (let i = 0; i < a; i++) s = E.onAction(s);
        else if (op === 'shown') s = E.onShown(s, a);
        else if (op === 'tapped') s = E.onSupportTapped(s);
        else if (op === 'declined') s = E.onDeclined(s);
        else if (op === 'ask') expect(E.shouldAsk(s, a, V.trigger), `${c.name} @ ${a.today}`).toBe(b);
        else throw new Error('unknown op ' + op);
      }
    });
  }
});

const mem = () => { const m = new Map(); return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => m.set(k, v) }; };

describe("LibreTag's ask: 7 days, 10 actions, a moment of value, never next to Lost, Alarm or Silent", () => {
  const used = () => {
    const a = new SupportAsk(mem());
    for (let d = 1; d <= 7; d++) a.launch(`2026-11-${String(d).padStart(2, '0')}`);
    for (let i = 0; i < 10; i++) a.action();
    return a;
  };
  it('is not asked before the trigger', () => {
    const a = new SupportAsk(mem());
    a.launch('2026-11-01');
    for (let i = 0; i < 10; i++) a.action();
    expect(a.consider('cmd:1', false, '2026-11-01')).toBe(false);
  });
  it('asks once at the moment of value and keeps the strip up for that command until it is answered', () => {
    const a = used();
    expect(a.consider('cmd:1', true, '2026-11-07')).toBe(false); // a tag in Lost, Alarm or Silent
    expect(a.consider('cmd:1', false, '2026-11-07')).toBe(true);
    expect(a.consider('cmd:1', false, '2026-11-07')).toBe(true);
    expect(a.consider('cmd:2', false, '2026-11-07')).toBe(false);
    expect(a.load().asks).toBe(1);
    a.dismiss();
    expect(a.consider('cmd:3', false, '2026-11-08')).toBe(false); // cool-down of 14 days
    expect(a.consider('cmd:3', false, '2026-11-22')).toBe(true);
  });
  it("never again after Don't ask again, nor after Support; a broken store is a fresh start", () => {
    const a = used();
    a.consider('cmd:1', false, '2026-11-07');
    a.declined();
    expect(a.consider('cmd:9', false, '2027-03-01')).toBe(false);
    const st = mem(); st.setItem(E.KEY, '{nope');
    expect(new SupportAsk(st).load()).toEqual(E.initialState());
    expect(new SupportAsk(null).consider('x', false)).toBe(false);
  });
});

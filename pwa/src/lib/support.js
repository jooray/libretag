// The support ask (SPEC and AGENTS.md "Support ask"): a dismissable strip after 7 days of use and 10 actions, at a moment of
// value only (a command the tag confirmed), never next to a tag in Lost, Alarm or Silent state. Port of the shared engine
// (support-widget spec/engine.mjs, as the Android app's SupportEngine.kt) and of SupportAsk.kt. It decides locally and
// keeps its state in localStorage `support.v1`; it sends nothing. The checkout opens in a new tab (main.js).

export const LIBRE_TAG = { minDays: 7, minActions: 10, cooldownDays: [14, 45], maxAsks: 3 };
export const KEY = 'support.v1';
export const REF_COMMAND_CONFIRMED = 'command-confirmed';
export const REF_MENU = 'menu';

export const initialState = () => ({ activeDays: 0, lastActiveDay: null, actions: 0, asks: 0, lastAskDay: null, supporter: false, declined: false });

const dayNumber = (iso) => { const [y, m, d] = iso.split('-').map(Number); return Math.floor(Date.UTC(y, m - 1, d) / 86400000); };
export const daysBetween = (from, to) => dayNumber(to) - dayNumber(from);

/** The app came to the foreground. One day of use per calendar day, never per launch. */
export const onLaunch = (s, today) => (s.lastActiveDay === today ? s : { ...s, activeDays: s.activeDays + 1, lastActiveDay: today });
/** One successful core action: a tag detail opened, a command sent. */
export const onAction = (s) => ({ ...s, actions: s.actions + 1 });
/** The ask was put on screen. Counted when shown, so a dismissal needs no extra bookkeeping. */
export const onShown = (s, today) => ({ ...s, asks: s.asks + 1, lastAskDay: today });
export const onSupportTapped = (s) => ({ ...s, supporter: true });
export const onDeclined = (s) => ({ ...s, declined: true });

export function shouldAsk(s, ctx, trigger = LIBRE_TAG) {
  if (s.supporter || s.declined || ctx.isSupporter) return false;
  if (!ctx.momentOfValue || ctx.busy) return false;
  if (s.activeDays < trigger.minDays || s.actions < trigger.minActions) return false;
  if (s.asks >= trigger.maxAsks) return false;
  if (s.asks > 0) {
    const wait = trigger.cooldownDays[Math.min(s.asks - 1, trigger.cooldownDays.length - 1)];
    if (s.lastAskDay === null || daysBetween(s.lastAskDay, ctx.today) < wait) return false;
  }
  return true;
}

/** Local calendar date as ISO ("2026-11-14"). */
export const todayIso = (d = new Date()) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/** Anything unreadable is a fresh start: the worst case is one ask too many. */
export function decode(raw) {
  try {
    const o = JSON.parse(raw);
    const d = initialState();
    return { activeDays: Number.isInteger(o.activeDays) ? o.activeDays : d.activeDays, lastActiveDay: typeof o.lastActiveDay === 'string' ? o.lastActiveDay : null,
      actions: Number.isInteger(o.actions) ? o.actions : 0, asks: Number.isInteger(o.asks) ? o.asks : 0, lastAskDay: typeof o.lastAskDay === 'string' ? o.lastAskDay : null,
      supporter: o.supporter === true, declined: o.declined === true };
  } catch { return initialState(); }
}

/** The stateful side: storage plus "the strip is up for this key until answered". */
export class SupportAsk {
  constructor(storage = null) {
    this.storage = storage;
    this.showingFor = null;
  }

  load() { try { return decode(this.storage?.getItem(KEY)); } catch { return initialState(); } }
  _update(f) { try { this.storage?.setItem(KEY, JSON.stringify(f(this.load()))); } catch { /* storage blocked: no ask is the safe side */ } }

  launch(today = todayIso()) { this._update((s) => onLaunch(s, today)); }
  action() { this._update(onAction); }

  /** At a moment of value: true if the strip should be up for `key`. Counts as shown the first time; `busy` never asks. */
  consider(key, busy, today = todayIso()) {
    if (busy) return false;
    if (this.showingFor === key) return true;
    if (this.showingFor != null) return false;
    if (!this.storage) return false;
    if (!shouldAsk(this.load(), { today, momentOfValue: true, busy: false }, LIBRE_TAG)) return false;
    this._update((s) => onShown(s, today));
    this.showingFor = key;
    return true;
  }

  dismiss() { this.showingFor = null; }
  supportTapped() { this._update(onSupportTapped); this.dismiss(); }
  declined() { this._update(onDeclined); this.dismiss(); }
}

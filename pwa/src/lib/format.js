// Times, ages, distances and coordinates in the app's vocabulary ("Heard 12 min ago", "±1.2 km").
import { locale, t } from './i18n.js';

const MIN = 60_000, HOUR = 3_600_000, DAY = 86_400_000;
const sameDay = (a, b) => new Date(a).toDateString() === new Date(b).toDateString();

export const ageShort = (ms, now = Date.now()) => {
  const d = Math.max(0, now - ms);
  if (d < MIN) return t('age.now');
  if (d < HOUR) return t('age.min', { n: Math.floor(d / MIN) });
  if (d < DAY) return t('age.h', { n: Math.floor(d / HOUR) });
  return t('age.d', { n: Math.floor(d / DAY) });
};

export const agoLong = (ms, now = Date.now()) => {
  const d = Math.max(0, now - ms);
  if (d < MIN) return t('ago.just_now');
  if (d < HOUR) return t('ago.min', { n: Math.floor(d / MIN) });
  if (d < 3 * HOUR && (d % HOUR) / MIN >= 10) return t('ago.h_min', { h: Math.floor(d / HOUR), m: Math.floor((d % HOUR) / MIN) });
  if (d < DAY) return t('ago.h', { n: Math.floor(d / HOUR) });
  return t('ago.days', { n: Math.floor(d / DAY) });
};

export const duration = (ms) => {
  const d = Math.max(0, ms);
  if (d < HOUR) return t('age.min', { n: Math.floor(d / MIN) });
  if (d < 2 * DAY) return t('age.h', { n: Math.floor(d / HOUR) });
  return t('age.d', { n: Math.floor(d / DAY) });
};

export const every = (s) => {
  if (s === 3600) return t('every.hourly');
  if (s === 86400) return t('every.daily');
  if (s < 3600) return t('every.min', { n: Math.round(s / 60) });
  return t('every.h', { n: Math.round(s / 3600) });
};

export const time = (ms) => new Intl.DateTimeFormat(locale(), { hour: '2-digit', minute: '2-digit' }).format(ms);

/** "14:31", "14:31 tomorrow", or "Tue 08:00". */
export const clock = (ms, now = Date.now()) => {
  const tm = time(ms);
  if (sameDay(ms, now)) return tm;
  if (sameDay(ms, now + DAY)) return t('clock.tomorrow', { t: tm });
  return `${new Intl.DateTimeFormat(locale(), { weekday: 'short' }).format(ms)} ${tm}`;
};

const startOfDay = (ms) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); };

/** "Mon 16/11" (en), "po 16. 11." (sk); with the year when it is not this year. */
export const date = (ms, now = Date.now()) => new Intl.DateTimeFormat(locale(), {
  weekday: 'short', day: 'numeric', month: 'numeric',
  ...(new Date(ms).getFullYear() === new Date(now).getFullYear() ? {} : { year: 'numeric' }),
}).format(ms);

/**
 * A past moment (a fix, a report): "14:32" today, "yesterday 14:32", "Mon 16/11 14:32" further
 * back. clock() is for times ahead ("14:31 tomorrow").
 */
export const at = (ms, now = Date.now()) => {
  const tm = time(ms);
  const d0 = startOfDay(ms), n0 = startOfDay(now);
  if (d0 >= n0) return tm;
  // the day before by the calendar (DST-safe: noon of the day before today)
  if (d0 === startOfDay(n0 - 12 * HOUR)) return t('clock.yesterday', { t: tm });
  return `${date(ms, now)} ${tm}`;
};

/**
 * List rows: "Heard 12 min ago" under an hour, then the clock time only ("Heard at 14:19",
 * "Heard yesterday 14:19", "Heard Fri 13/11 09:05"): for older reports the time is what matters.
 */
export const ROW_AGO_MS = 60 * 60_000;
export const heardRow = (ms, now = Date.now()) => {
  if (now - ms < ROW_AGO_MS) return t('st.ok_no_next', { heard: agoLong(ms, now) });
  if (startOfDay(ms) >= startOfDay(now)) return t('st.heard_at', { t: time(ms) });
  return t('st.ok_no_next', { heard: at(ms, now) });
};

/** Relative age plus the exact time: "5 h ago (14:32)", "pred 5 h (14:32)". */
/** approx: "~" before the time (0.8.1, a report a bridge drained late) */
export const agoAt = (ms, now = Date.now(), approx = false) => t('ago.at', { ago: agoLong(ms, now), t: (approx ? '~' : '') + at(ms, now) });

export const day = (ms) => new Intl.DateTimeFormat(locale(), { weekday: 'short', day: 'numeric', month: 'short' }).format(ms);

/** Units of the app (Settings, like the Android app): 'metric' or 'imperial'. */
let units = null;
export const getUnits = () => {
  if (units == null) { try { units = localStorage.getItem('lt.units') === 'imperial' ? 'imperial' : 'metric'; } catch { units = 'metric'; } }
  return units;
};
export const setUnits = (u) => { units = u === 'imperial' ? 'imperial' : 'metric'; try { localStorage.setItem('lt.units', units); } catch { /* ignore */ } };

export const distance = (m) => {
  if (getUnits() === 'imperial') {
    const ft = m * 3.28084;
    return ft < 1000 ? t('dist.ft', { n: Math.max(1, Math.floor(ft / 10) * 10) }) : t('dist.mi', { n: (m / 1609.344).toFixed(1).replace('.', locale() === 'sk-SK' ? ',' : '.') });
  }
  if (m < 100) return t('dist.m', { n: Math.max(1, Math.round(m)) });
  if (m < 1000) return t('dist.m', { n: Math.round(m / 10) * 10 });
  return t('dist.km', { n: (m / 1000).toFixed(1).replace('.', locale() === 'sk-SK' ? ',' : '.') });
};

export const coord = (lat, lon) => `${lat.toFixed(5)}, ${lon.toFixed(5)}`;
export const npubShort = (npub) => `${npub.slice(0, 10)}…${npub.slice(-4)}`;

import en from '../i18n/en.js';
import sk from '../i18n/sk.js';

const DICTS = { en, sk };
export const LANGS = [['en', 'English'], ['sk', 'Slovenčina']];
let lang = 'en';

export function detectLang() {
  try {
    const saved = localStorage.getItem('lt.lang');
    if (saved && DICTS[saved]) return saved;
  } catch { /* storage may be blocked */ }
  const nav = (typeof navigator !== 'undefined' && navigator.languages) || [];
  for (const l of nav) { const c = l.slice(0, 2).toLowerCase(); if (DICTS[c]) return c; }
  return 'en';
}

export function setLang(l) {
  lang = DICTS[l] ? l : 'en';
  try { localStorage.setItem('lt.lang', lang); } catch { /* ignore */ }
  if (typeof document !== 'undefined') document.documentElement.lang = lang;
}
export const getLang = () => lang;
export const locale = () => (lang === 'sk' ? 'sk-SK' : 'en-GB');

/** t('key', {n: 3}). A plural entry is chosen by Intl.PluralRules on params.n. */
export function t(key, params = {}, forceLang = lang) {
  let v = DICTS[forceLang][key] ?? DICTS.en[key];
  if (v == null) return key;
  if (typeof v === 'object') {
    const cat = new Intl.PluralRules(forceLang === 'sk' ? 'sk' : 'en').select(params.n ?? 0);
    v = v[cat] ?? v.other;
  }
  return v.replace(/\{(\w+)\}/g, (_, k) => (params[k] ?? `{${k}}`));
}

export const DICT_KEYS = (l) => Object.keys(DICTS[l]);

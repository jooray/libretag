// Trail blazes (docs/design/prototype.html): colour x symbol on a white plate with a graphite edge.
export function blaze(color, symbol, h = 28, grey = false) {
  const col = grey ? '#9a9a9a' : `var(--b-${color || 'yellow'})`;
  let inner;
  if (symbol === 'stripe') inner = `<rect x="1" y="8" width="18" height="8" fill="${col}"/>`;
  else if (symbol === 'diag') inner = `<path d="M1 16.5 13.5 1H19v5.5L6.5 23H1Z" fill="${col}"/>`;
  else if (symbol === 'triangle') inner = `<path d="M10 5.5 16.5 17.5h-13Z" fill="${col}"/>`;
  else if (symbol === 'circle') inner = `<circle cx="10" cy="12" r="5.5" fill="${col}"/>`;
  else inner = `<rect x="4.5" y="6.5" width="11" height="11" fill="${col}"/>`;
  return `<svg class="blz" width="${Math.round((h * 20) / 24)}" height="${h}" viewBox="0 0 20 24" aria-hidden="true"><rect x="1" y="1" width="18" height="22" fill="#fff"/>${inner}<rect x=".75" y=".75" width="18.5" height="22.5" rx="1" fill="none" stroke="var(--blaze-edge)" stroke-width="1.5"/></svg>`;
}

export const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const P = {
  back: 'M19 12H5M12 5l-7 7 7 7', more: 'M12 5v.01M12 12v.01M12 19v.01', chevL: 'M15 5l-7 7 7 7', chevR: 'M9 5l7 7-7 7',
  close: 'M6 6l12 12M18 6L6 18', alert: 'M12 4l9 16H3zM12 10v4M12 17v.01', clock: 'M12 7v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  moon: 'M20 14A8 8 0 1 1 10 4a6 6 0 0 0 10 10z', pin: 'M12 21s7-6.2 7-12a7 7 0 1 0-14 0c0 5.8 7 12 7 12zM12 11.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5',
  battery: 'M3 8h15v8H3zM20 11v2', plus: 'M12 5v14M5 12h14', qr: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 18h2v2h-2z',
  walk: 'M12 6a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3M10 21l1-6-2-2 1-4 3 2 2 3', send: 'M4 12l16-8-6 16-3-7z',
  check: 'M5 12l5 5 9-10', info: 'M12 8v.01M12 11v5M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0', map: 'M9 4L3 6v14l6-2 6 2 6-2V4l-6 2zM9 4v14M15 6v14',
  edit: 'M4 20h4L19 9l-4-4L4 16zM13 7l4 4',
  share: 'M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6M6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6M18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6M8.6 13.5l6.8 4M15.4 6.5l-6.8 4',
  heart: 'M12 20s-7-4.6-7-10a4 4 0 0 1 7-2.5A4 4 0 0 1 19 10c0 5.4-7 10-7 10z', wifi: 'M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5v.01',
};
export const ic = (name, cls = '') => `<svg class="i ${cls}" viewBox="0 0 24 24" aria-hidden="true"><path d="${P[name] ?? ''}"/></svg>`;

export function batteryGlyph(pct, low) {
  const w = pct == null ? 0 : Math.max(1, Math.round((pct / 100) * 11));
  return `<svg width="20" height="12" viewBox="0 0 22 12" aria-hidden="true"><rect x=".75" y=".75" width="17.5" height="10.5" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.5"/><rect x="19" y="3.5" width="2" height="5" rx=".5" fill="currentColor"/>${pct == null ? '<path d="M7 4.5c0-1 1-1.6 2-1.6s2 .6 2 1.6c0 1.2-2 1.2-2 2.6M9 9v.01" stroke="currentColor" fill="none" stroke-width="1.3"/>' : `<rect x="2.5" y="2.5" width="${w}" height="7" fill="${low ? 'var(--crit)' : 'currentColor'}"/>`}</svg>`;
}

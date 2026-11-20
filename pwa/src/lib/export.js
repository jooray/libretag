// A tag's history as GPX (positions with a GPS fix, one track) and CSV (every report the app holds).
// Built here in the browser; nothing leaves the device until the user saves or shares the file.
import { fixAt } from './status.js';

const iso = (ms) => new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z');
const xml = (s) => String(s).replace(/[<>&"']/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' }[c]));
const sorted = (tag) => [...(tag.positions ?? [])].sort((a, b) => a.n - b.n);
const accOf = (p) => (p.haccM != null && p.haccM !== 255 ? p.haccM : null);

export function toGPX(tag, app = 'LibreTag web') {
  const pts = sorted(tag).filter((p) => p.hasFix && Number.isFinite(p.lat) && Number.isFinite(p.lon));
  const name = xml(tag.name ?? 'Tag');
  const trkpts = pts.map((p) => {
    const ext = [`<lt:n>${p.n}</lt:n>`, p.battery != null ? `<lt:battery>${p.battery}</lt:battery>` : '', accOf(p) != null ? `<lt:hacc>${accOf(p)}</lt:hacc>` : '', p.mode ? `<lt:mode>${xml(p.mode)}</lt:mode>` : ''].join('');
    return `      <trkpt lat="${p.lat.toFixed(6)}" lon="${p.lon.toFixed(6)}"><time>${iso(fixAt(p))}</time>`
      + `<extensions>${ext}</extensions></trkpt>`;
  });
  return '<?xml version="1.0" encoding="UTF-8"?>\n'
    + `<gpx version="1.1" creator="${xml(app)}" xmlns="http://www.topografix.com/GPX/1/1" xmlns:lt="https://cypherpunk.today/libretag/gpx/1">\n`
    + `  <metadata><name>${name}</name>${pts.length ? `<time>${iso(fixAt(pts.at(-1)))}</time>` : ''}</metadata>\n`
    + `  <trk><name>${name}</name>\n    <trkseg>\n${trkpts.join('\n')}${trkpts.length ? '\n' : ''}    </trkseg>\n  </trk>\n</gpx>\n`;
}

export const CSV_HEADER = ['n', 'received_utc', 'fix_utc', 'lat', 'lon', 'accuracy_m', 'battery_pct', 'low_battery', 'mode', 'quiet', 'quiet_reason', 'command_ack', 'repeaters_heard'];

const cell = (v) => {
  if (v == null) return '';
  const s = String(v);
  return /[",\n\r]/.test(s) || /^[=+\-@]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
};

export function toCSV(tag) {
  const rows = sorted(tag).map((p) => [
    p.n, p.receivedAt ? iso(p.receivedAt) : '', p.hasFix && p.fixTime > 0 ? iso(p.fixTime * 1000) : '',
    p.hasFix ? p.lat.toFixed(6) : '', p.hasFix ? p.lon.toFixed(6) : '', p.hasFix ? accOf(p) : '',
    p.battery, p.lowBatt ? 1 : 0, p.mode ?? '', p.quiet ? 1 : 0, p.quiet ? p.quietReason ?? '' : '', p.cmdAck ? 1 : 0, p.neighbourCount ?? '',
  ]);
  return [CSV_HEADER, ...rows].map((r) => r.map(cell).join(',')).join('\r\n') + '\r\n';
}

/** "libretag-red-bike-2026-11-17.gpx" */
export function exportName(tag, ext, now = Date.now()) {
  const slug = String(tag.name ?? 'tag').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'tag';
  return `libretag-${slug}-${new Date(now).toISOString().slice(0, 10)}.${ext}`;
}

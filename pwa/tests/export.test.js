import { describe, expect, it } from 'vitest';
import { CSV_HEADER, exportName, toCSV, toGPX } from '../src/lib/export.js';

const tag = { name: 'Red <Bike> & "Co"', positions: [
  { n: 12, receivedAt: Date.UTC(2026, 10, 17, 8, 5, 0), hasFix: true, lat: 48.70123, lon: 21.25456, fixTime: Date.UTC(2026, 10, 17, 8, 4, 50) / 1000, haccM: 8, battery: 9, lowBatt: true, mode: 'LOST', quiet: false, cmdAck: true, neighbourCount: 2 },
  { n: 10, receivedAt: Date.UTC(2026, 10, 17, 7, 0, 0), hasFix: true, lat: 48.7, lon: 21.25, fixTime: 0, haccM: 255, battery: 11, mode: 'IDLE', quiet: false, neighbourCount: 1 },
  { n: 11, receivedAt: Date.UTC(2026, 10, 17, 7, 30, 0), hasFix: false, lat: 0, lon: 0, fixTime: 0, battery: null, mode: 'IDLE', quiet: true, quietReason: 'zone', neighbourCount: 0 },
] };

describe('history export', () => {
  it('GPX: one track of the fixes, in counter order, escaped name, fix time', () => {
    const g = toGPX(tag);
    expect(g).toContain('<gpx version="1.1"');
    expect(g).toContain('<name>Red &lt;Bike&gt; &amp; &quot;Co&quot;</name>');
    const pts = [...g.matchAll(/<trkpt lat="([\d.]+)" lon="([\d.]+)"><time>([^<]+)<\/time>/g)].map((m) => [m[1], m[2], m[3]]);
    expect(pts).toEqual([['48.700000', '21.250000', '2026-11-17T07:00:00Z'], ['48.701230', '21.254560', '2026-11-17T08:04:50Z']]);
    expect(g).toContain('<lt:battery>9</lt:battery>');
    expect(g).not.toContain('<lt:hacc>255');
  });
  it('GPX of a tag without fixes is still a valid empty track', () => {
    expect(toGPX({ name: 'X', positions: [] })).toContain('<trkseg>\n    </trkseg>');
  });
  it('CSV: header and one row per report, oldest first', () => {
    const lines = toCSV(tag).trim().split('\r\n');
    expect(lines[0]).toBe(CSV_HEADER.join(','));
    expect(lines).toHaveLength(4);
    expect(lines[1]).toBe('10,2026-11-17T07:00:00Z,,48.700000,21.250000,,11,0,IDLE,0,,0,1');
    expect(lines[2]).toBe('11,2026-11-17T07:30:00Z,,,,,,0,IDLE,1,zone,0,0');
    expect(lines[3]).toBe('12,2026-11-17T08:05:00Z,2026-11-17T08:04:50Z,48.701230,21.254560,8,9,1,LOST,0,,1,2');
  });
  it('file names', () => {
    expect(exportName({ name: 'Červený bicykel' }, 'gpx', Date.UTC(2026, 10, 17))).toBe('libretag-cerveny-bicykel-2026-11-17.gpx');
    expect(exportName({ name: '!!!' }, 'csv', Date.UTC(2026, 10, 17))).toBe('libretag-tag-2026-11-17.csv');
  });
});

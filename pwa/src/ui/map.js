// Leaflet map with OpenStreetMap tiles. No geocoding and nothing about the tags leaves the page:
// only tile coordinates are requested from the tile server, as for any map.
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { t } from '../lib/i18n.js';
import { fixAt, mapTarget, lastPos, statusOf, levelOf } from '../lib/status.js';
import { agoLong, at } from '../lib/format.js';
import { blaze, esc } from './blaze.js';

const HOME = [48.70, 21.25]; // only where an empty map starts
/** a GPS fix opens at street level: this much is kept around it (about zoom 16.5-17 on a phone) */
const STREET_M = 90;
const FOCUS_ZOOM = 17;
const reduceMotion = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

/**
 * Circles to fit when a tag is opened: a GPS fix at street level, or its accuracy circle when that
 * is larger; a last-known fix (no GPS now) the same way. The web has no area estimates.
 */
export function focusCircle(p, target) {
  if (!target) return null;
  const acc = p && p.hasFix && p.haccM != null && p.haccM !== 255 ? p.haccM : 0;
  return { lat: target.lat, lon: target.lon, r: Math.max(STREET_M, acc) };
}

export function createMap(el) {
  const map = L.map(el, { zoomControl: true, attributionControl: true, worldCopyJump: true }).setView(HOME, 11);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a>',
    referrerPolicy: 'origin',
  }).addTo(map);
  const layer = L.layerGroup().addTo(map);
  let lastFitKey = '';
  let placed = false;
  // time chips on the trail and the areas they must keep clear of
  let chips = [];
  let keepClear = [];

  const pinIcon = (tag, target, now, selected) => {
    const s = statusOf(tag, now);
    const lvl = levelOf(s);
    const grey = !target.exact;
    return L.divIcon({
      className: 'pin-wrap',
      html: `<span class="pin ${grey ? 'grey' : lvl} ${selected ? 'sel' : ''}"><span class="lab">${blaze(tag.mark.color, tag.mark.symbol, 20, grey)}<b>${esc(tag.name)}</b><i>${esc(at(target.at, now))}</i></span><span class="stem"></span><span class="dot"></span></span>`,
      iconSize: [0, 0], iconAnchor: [0, 0],
    });
  };

  /**
   * Show the time chips that fit: the newest and the oldest first, then the others newest first,
   * each only if it doesn't overlap a chip already shown or a tag's label. The rest stay dots;
   * hovering or tapping a dot shows its time.
   */
  function layoutChips() {
    const taken = keepClear.map((k) => {
      const pt = map.latLngToContainerPoint(k.latlng);
      return { l: pt.x - k.w / 2, r: pt.x + k.w / 2, t: pt.y - 46, b: pt.y + 8 };
    });
    for (const c of chips) {
      const pt = map.latLngToContainerPoint(c.latlng);
      const w = c.text.length * 6.6 + 14;
      const box = { l: pt.x - w / 2 - 3, r: pt.x + w / 2 + 3, t: pt.y - 29, b: pt.y - 6 };
      const hit = taken.some((o) => box.l < o.r && box.r > o.l && box.t < o.b && box.b > o.t);
      const elc = c.marker.getElement();
      if (elc) elc.style.visibility = hit ? 'hidden' : '';
      if (!hit) taken.push(box);
    }
  }
  map.on('zoomend moveend', layoutChips);

  function focus(c, animate) {
    const b = L.latLng(c.lat, c.lon).toBounds(c.r * 2);
    const opts = { padding: [36, 36], maxZoom: FOCUS_ZOOM };
    if (animate && placed && !reduceMotion()) map.flyToBounds(b, { ...opts, duration: 0.7 });
    else if (animate && !reduceMotion()) {
      // nothing shown yet: start just above it and zoom in
      const z = map.getBoundsZoom(b, false, L.point(72, 72));
      map.setView([c.lat, c.lon], Math.max(3, Math.min(FOCUS_ZOOM, z) - 2), { animate: false });
      map.flyToBounds(b, { ...opts, duration: 0.6 });
    } else map.fitBounds(b, { ...opts, animate: false });
    placed = true;
  }

  return {
    invalidate() { map.invalidateSize(); },
    /** tags: all; selectedId: tag to draw a trail for; focusN: report number to centre on */
    update({ tags, selectedId, focusN, now = Date.now(), fit = false }) {
      layer.clearLayers();
      chips = []; keepClear = [];
      const pts = [];
      let focusC = null;
      for (const tag of tags) {
        const target = mapTarget(tag, lastPos(tag));
        if (!target) continue;
        const sel = tag.id === selectedId;
        if (sel) {
          for (const z of tag.zones ?? []) L.circle([z.lat, z.lon], { radius: z.r_m, color: '#626262', weight: 1.5, dashArray: '4 4', fillOpacity: 0.05 }).bindTooltip(z.name).addTo(layer);
          const trail = tag.positions.filter((p) => p.hasFix).slice(-40);
          if (trail.length > 1) {
            const colour = getComputedStyle(document.documentElement).getPropertyValue(`--b-${tag.mark.color || 'yellow'}`).trim() || '#1f5fb4';
            L.polyline(trail.map((p) => [p.lat, p.lon]), { color: colour, weight: 3, dashArray: '9 6', opacity: 0.85 }).addTo(layer);
            const stations = trail.slice(0, -1);
            for (const p of stations) {
              // the dot's tooltip: clock time, how long ago, and small the report number
              L.circleMarker([p.lat, p.lon], { radius: 5, color: '#1e1e1e', weight: 1.5, fillColor: '#fff', fillOpacity: 1 })
                .bindTooltip(`<b>${esc(at(fixAt(p), now))}</b> · ${esc(agoLong(fixAt(p), now))}<br><small>${esc(t('map.point_n', { n: p.n }))}</small>`, { direction: 'top', offset: [0, -6], className: 'pt-tip' })
                .addTo(layer);
            }
            const order = stations.length <= 2 ? stations.slice().reverse()
              : [stations[stations.length - 1], stations[0], ...stations.slice(1, -1).reverse()];
            for (const p of order) {
              const text = at(fixAt(p), now);
              const marker = L.marker([p.lat, p.lon], {
                icon: L.divIcon({ className: 'tchip-wrap', html: `<span class="tchip" style="--c:${colour}">${esc(text)}</span>`, iconSize: [0, 0], iconAnchor: [0, 0] }),
                keyboard: false, interactive: false,
              }).addTo(layer);
              chips.push({ latlng: L.latLng(p.lat, p.lon), text, marker });
            }
          }
        }
        let latlng = [target.lat, target.lon];
        let fp = lastPos(tag);
        let ft = target;
        if (sel && focusN != null) {
          const p = tag.positions.find((x) => x.n === focusN);
          if (p && (p.hasFix || p.fixTime)) {
            latlng = [p.lat, p.lon]; fp = p;
            ft = { lat: p.lat, lon: p.lon, exact: !!p.hasFix, at: p.hasFix ? fixAt(p) : p.fixTime * 1000 };
          }
        }
        if (sel) focusC = focusCircle(fp, ft);
        L.marker(latlng, { icon: pinIcon(tag, ft, now, sel), keyboard: false, zIndexOffset: sel ? 1000 : 0 }).addTo(layer);
        keepClear.push({ latlng: L.latLng(latlng[0], latlng[1]), w: (tag.name.length + at(ft.at, now).length) * 7.5 + 50 });
        pts.push(latlng);
      }
      const key = pts.map((p) => p.join(',')).join(';') + '|' + selectedId + '|' + focusN;
      if (pts.length && (fit || key !== lastFitKey)) {
        const changedSel = !lastFitKey.endsWith('|' + selectedId + '|' + focusN);
        lastFitKey = key;
        map.invalidateSize();
        // a tag was opened (or a report picked): go to where it is, animated
        if (selectedId && focusC) focus(focusC, changedSel || fit);
        else if (pts.length === 1) { map.setView(pts[0], 14, { animate: false }); placed = true; }
        else { map.fitBounds(L.latLngBounds(pts), { padding: [60, 60], maxZoom: 15, animate: placed && fit && !reduceMotion() }); placed = true; }
      }
      layoutChips();
      return pts.length;
    },
    fit() { this.lastFit = ''; lastFitKey = ''; },
  };
}

// Distances and zones (a port of the Android app's Geo and Status.zoneOf).
export function distanceM(lat1, lon1, lat2, lon2) {
  const kx = 111_320 * Math.cos((((lat1 + lat2) / 2) * Math.PI) / 180);
  return Math.hypot((lon2 - lon1) * kx, (lat2 - lat1) * 110_540);
}

export const ZONE_HYST_M = 50;

/** The zone a position lies in: its fix, or the last fix it carries (lat/lon are set when fixTime > 0). */
export function zoneOf(zones, p) {
  if (!zones?.length || !p || !(p.hasFix || p.fixTime > 0)) return null;
  return zones.find((z) => distanceM(p.lat, p.lon, z.lat, z.lon) <= z.r_m) ?? null;
}

/**
 * Leaving a zone (SPEC 11.1): inside at d < r, outside only at d > r + 50 m. Given the zone name we were
 * in and a new fix, returns { inZone, left } where `left` is the zone just left, if any.
 */
export function zoneStep(zones, inZone, fix) {
  const d = (z) => distanceM(fix.lat, fix.lon, z.lat, z.lon);
  const prev = zones.find((z) => z.name === inZone);
  const z = prev && d(prev) <= prev.r_m + ZONE_HYST_M ? prev : zones.find((x) => d(x) <= x.r_m);
  return { inZone: z?.name ?? null, left: !z && inZone ? inZone : null };
}

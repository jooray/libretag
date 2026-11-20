// Backup plaintext v1 with the 12.2 additions: "d" (locator of copy B), "updated" per tag and
// removal tombstones. Merge: per k0 the newest timestamp wins; a tombstone beats an older tag.
export const TOMBSTONE_DAYS = 90;

const stamp = (e) => (e.removed != null ? e.removed : e.updated ?? 0);

/** payload.tags -> Map k0 -> entry (tag or tombstone) */
function entries(payload) {
  const m = new Map();
  for (const e of payload?.tags ?? []) {
    if (!e || typeof e.k0 !== 'string') continue;
    const cur = m.get(e.k0);
    if (!cur || stamp(e) > stamp(cur)) m.set(e.k0, e);
  }
  return m;
}

/** Merge several payloads (local first). Returns a new payload; `d` is the first one found. */
export function mergePayloads(payloads, now = Math.floor(Date.now() / 1000)) {
  const all = payloads.filter(Boolean);
  const out = new Map();
  for (const p of all) {
    for (const [k0, e] of entries(p)) {
      const cur = out.get(k0);
      // ties: a tombstone wins over a tag (a removal is deliberate), otherwise keep the first
      if (!cur || stamp(e) > stamp(cur) || (stamp(e) === stamp(cur) && e.removed != null && cur.removed == null)) out.set(k0, e);
    }
  }
  const cutoff = now - TOMBSTONE_DAYS * 86400;
  const tags = [...out.values()].filter((e) => e.removed == null || e.removed >= cutoff);
  const d = all.map((p) => p.d).find((x) => typeof x === 'string');
  const owner = all.map((p) => p.owner).find((x) => x) ?? null;
  const res = { v: 1, created: now, owner, tags };
  if (d) res.d = d;
  return res;
}

/** Plaintext JSON for saving (keys in the 12.1 order, then the 12.2 additions). */
export function plaintextOf({ created, owner, d, tags, tombstones = [] }) {
  const o = { v: 1, created, owner: owner ?? null };
  if (d) o.d = d;
  o.tags = [
    // t.extra holds every other field of the tag (interval_min, check_s, still, use, fw, zones, anchors, unknown ones)
    ...tags.map((t) => ({ name: t.name, k0: t.k0, type: t.type, mark: `${t.mark.color}-${t.mark.symbol}`, updated: t.updated ?? created, ...(t.extra ?? {}) })),
    ...tombstones.map((x) => ({ k0: x.k0, removed: x.removed })),
  ];
  return JSON.stringify(o);
}

/** Parse the 12.1 JSON (+12.2). null when it is not a v1 backup. Tombstones are kept apart. */
export function parsePlaintext(text) {
  let p;
  try { p = JSON.parse(text); } catch { return null; }
  if (!p || p.v !== 1 || !Array.isArray(p.tags)) return null;
  const tags = [], tombstones = [];
  for (const e of p.tags) {
    if (!e || typeof e.k0 !== 'string' || !/^[0-9a-f]{64}$/.test(e.k0)) continue;
    if (e.removed != null) tombstones.push({ k0: e.k0, removed: Number(e.removed) });
    else {
      const { name, k0, type, mark, updated, ...extra } = e;
      tags.push({ name: name ?? 'Tag', k0, type: type ?? 'thing', mark: mark ?? '', updated: Number(updated ?? 0), extra });
    }
  }
  return { v: 1, created: p.created ?? 0, owner: p.owner ?? null, d: typeof p.d === 'string' ? p.d : null, tags, tombstones };
}

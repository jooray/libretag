# /// script
# requires-python = ">=3.10"
# dependencies = ["pyshp>=2.3", "numpy>=1.26"]
# ///
"""Generate firmware/lt_core/lt_country_grid.c: country raster + radio preset table (SPEC 6.4).

Input: Natural Earth 1:10m admin-0 countries (public domain),
https://naciscdn.org/naturalearth/10m/cultural/ne_10m_admin_0_countries.zip

    uv run firmware/tools/gen_country_grid.py path/to/ne_10m_admin_0_countries.shp

Grid: Europe, lat 34..72, lon -25..45, cells of RES degrees. Each row is run-length
encoded as u16 words: country index (6 bits) << 10 | run length (10 bits). Index 0 is
"unknown" (sea, or a country without a known preset): the tracker keeps its last preset.

Presets: https://api.meshcore.nz/api/v1/config, suggested_radio_settings, read 2026-11-14.
Countries not listed there use "EU/UK (Narrow)" only when they are EU/EEA/UK/CH/microstates
(same 869.4-869.65 MHz rules). Everyone else (UA, BY, RU, TR, Balkans, ...) is unknown.
Regions (flood scope): only "sk" is known. Unknown region = unscoped flood.
"""

import sys
from pathlib import Path

import numpy as np
import shapefile

LAT0, LAT1, LON0, LON1, RES = 34.0, 72.0, -25.0, 45.0, 0.05
COAST_CELLS = 3  # ~5 km of sea around known coasts still counts as that country
OTHER_LAND = 255

# name, freq MHz, bw kHz, sf, cr, path hash size (bytes)
PRESETS = [
    ("EU/UK (Narrow)", 869.618, 62.5, 8, 8, 1),
    ("Slovakia", 869.618, 62.5, 7, 5, 2),
    ("Hungary", 869.618, 62.5, 7, 5, 2),
    ("Czech Republic (Narrow)", 869.432, 62.5, 7, 5, 2),
    ("Netherlands", 869.618, 62.5, 7, 5, 1),
    ("Portugal 868", 869.618, 62.5, 7, 6, 1),
    ("Switzerland", 869.618, 62.5, 8, 8, 1),
]
P = {p[0]: i for i, p in enumerate(PRESETS)}

# ISO 3166 alpha-2, Natural Earth ADM0_A3, preset, region (None = unscoped)
EU_NARROW = """AT AUT DE DEU PL POL FR FRA IT ITA ES ESP BE BEL LU LUX DK DNK SE SWE NO NOR FI FIN
IE IRL GB GBR SI SVN HR HRV RO ROU BG BGR GR GRC EE EST LV LVA LT LTU IS ISL LI LIE MT MLT
CY CYP MC MCO SM SMR VA VAT AD AND""".split()
COUNTRIES = [
    ("SK", "SVK", P["Slovakia"], "sk"),
    ("HU", "HUN", P["Hungary"], None),
    ("CZ", "CZE", P["Czech Republic (Narrow)"], None),
    ("NL", "NLD", P["Netherlands"], None),
    ("PT", "PRT", P["Portugal 868"], None),
    ("CH", "CHE", P["Switzerland"], None),
] + [(EU_NARROW[i], EU_NARROW[i + 1], P["EU/UK (Narrow)"], None) for i in range(0, len(EU_NARROW), 2)]
assert len(COUNTRIES) < 63


def rings_of(shape):
    pts = shape.points
    parts = list(shape.parts) + [len(pts)]
    for a, b in zip(parts, parts[1:]):
        yield np.asarray(pts[a:b], dtype=np.float64)


def main(shp_path):
    reader = shapefile.Reader(shp_path)
    fields = [f[0] for f in reader.fields[1:]]
    ia3 = fields.index("ADM0_A3")
    by_a3 = {c[1]: i + 1 for i, c in enumerate(COUNTRIES)}

    nrows = round((LAT1 - LAT0) / RES)
    ncols = round((LON1 - LON0) / RES)
    grid = np.zeros((nrows, ncols), dtype=np.uint8)
    xs = LON0 + (np.arange(ncols) + 0.5) * RES
    found = set()

    for sr in reader.iterShapeRecords():
        idx = by_a3.get(sr.record[ia3], OTHER_LAND)
        bb = sr.shape.bbox
        if bb[2] < LON0 or bb[0] > LON1 or bb[3] < LAT0 or bb[1] > LAT1:
            continue
        if idx != OTHER_LAND:
            found.add(sr.record[ia3])
        edges = []
        for ring in rings_of(sr.shape):
            edges.append(np.column_stack([ring[:-1], ring[1:]]))  # x0 y0 x1 y1
        e = np.concatenate(edges)
        x0, y0, x1, y1 = e.T
        for r in range(nrows):
            y = LAT0 + (r + 0.5) * RES
            m = (y0 <= y) != (y1 <= y)
            if not m.any():
                continue
            t = (y - y0[m]) / (y1[m] - y0[m])
            cx = np.sort(x0[m] + t * (x1[m] - x0[m]))
            for a, b in zip(cx[0::2], cx[1::2]):
                sel = (xs >= a) & (xs < b)
                grid[r, sel] = idx

    # coastal cells: let known countries spill a few cells into the sea (not into other land)
    for _ in range(COAST_CELLS):
        g = grid.copy()
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            src = np.roll(grid, (dr, dc), axis=(0, 1))
            if dr:  # no wrap-around at the grid edges
                src[0 if dr > 0 else -1, :] = 0
            if dc:
                src[:, 0 if dc > 0 else -1] = 0
            m = (g == 0) & (src != 0) & (src != OTHER_LAND)
            g[m] = src[m]
        grid = g
    grid[grid == OTHER_LAND] = 0

    missing = {c[1] for c in COUNTRIES} - found
    if missing:
        print("warning: not found in the grid:", sorted(missing), file=sys.stderr)

    words, offsets = [], []
    for r in range(nrows):
        offsets.append(len(words))
        row = grid[r]
        i = 0
        while i < ncols:
            j = i
            while j < ncols and row[j] == row[i] and j - i < 1023:
                j += 1
            words.append((int(row[i]) << 10) | (j - i))
            i = j
    offsets.append(len(words))
    assert len(words) < 65536

    out = [
        "/* Generated by firmware/tools/gen_country_grid.py from Natural Earth 1:10m admin-0",
        " * (public domain) and the MeshCore suggested radio settings (api.meshcore.nz, 2026-11-14).",
        " * Do not edit; regenerate. */",
        '#include "lt_region.h"',
        "",
        f"const lt_grid_t lt_country_grid = {{{LAT0 * 1e7:.0f}, {LON0 * 1e7:.0f}, {RES * 1e7:.0f}, {nrows}, {ncols}}};",
        "",
        "const lt_preset_t lt_presets[] = {",
    ]
    for name, f, bw, sf, cr, hs in PRESETS:
        out.append(f'\t{{"{name}", {f:.3f}f, {bw}f, {sf}, {cr}, {hs}}},')
    out += ["};", f"const size_t lt_preset_count = {len(PRESETS)};", "",
            "/* index 0 = unknown */", "const lt_country_t lt_countries[] = {", '\t{"??", -1, NULL},']
    for iso, a3, p, scope in COUNTRIES:
        s = f'"{scope}"' if scope else "NULL"
        out.append(f'\t{{"{iso}", {p}, {s}}},')
    out += ["};", f"const size_t lt_country_count = {len(COUNTRIES) + 1};", ""]
    out.append(f"const uint16_t lt_grid_rows[{len(offsets)}] = {{")
    for i in range(0, len(offsets), 16):
        out.append("\t" + ", ".join(str(v) for v in offsets[i:i + 16]) + ",")
    out.append("};")
    out.append(f"const uint16_t lt_grid_runs[{len(words)}] = {{")
    for i in range(0, len(words), 12):
        out.append("\t" + ", ".join(f"0x{v:04x}" for v in words[i:i + 12]) + ",")
    out.append("};")

    dst = Path(__file__).resolve().parents[1] / "lt_core" / "lt_country_grid.c"
    dst.write_text("\n".join(out) + "\n")
    print(f"{dst}: {nrows}x{ncols} cells, {len(words)} runs, "
          f"{2 * (len(words) + len(offsets))} B of grid data", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1])

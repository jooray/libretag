# Region and radio preset by GPS: research

Status: 2026-11-14. Code source: `firmware/zephcore/zephcore` (hereafter `ZC/`). What I could not verify is marked **[UNVERIFIED]**.

## 1. How the region is written on the air

### 1.1 Header and transport codes
- The route type is the 2 low bits of `header` (`ZC/include/mesh/Packet.h:9,14-18`): `0x00` TRANSPORT_FLOOD, `0x01` FLOOD, `0x02` DIRECT, `0x03` TRANSPORT_DIRECT. `hasTransportCodes()` is true for 0x00 and 0x03 (`Packet.h:71`). `isRouteFlood()` holds for both FLOOD and TRANSPORT_FLOOD (`Packet.h:68`).
- A packet carries `uint16_t transport_codes[2]` (`Packet.h:52`). The first code is the scope (region code). Clients send the second code as 0 (`CompanionMesh.cpp:150-157`). A repeater treats codes `{0,0}` as "share" (`RepeaterMesh.cpp:778-781`).
- The code is derived not from the name alone but from the name **and the packet content**:
  1. Region key: `SHA256("#" + name)`, the first 16 B are used (`TransportKey.key[16]`). For a name without `#`, the `#` is added automatically (`ZC/helpers/RegionMap.cpp:173-189`, `TransportKeyStore.cpp::getAutoKeyFor`). A name `$...` is a private region with its own key (the keystore is not implemented yet, `TransportKeyStore.cpp::loadKeysFor`, TODO).
  2. Packet code: `HMAC-SHA256(key, [payload_type][payload])`, 2 B are taken from the output. The values 0x0000 and 0xFFFF are reserved and are shifted by 1 (`TransportKeyStore.cpp:4-18`).
  3. Consequence: the code must be computed **only after the final payload** and is different for every packet. It cannot be precomputed as a region constant.
- Region names: lowercase letters, digits, hyphen, max 29 B UTF-8, hyphen and `$`/`#` allowed (`RegionMap.cpp:52-55`; MeshCore blog 2026-01-20, https://blog.meshcore.io/2026/01/20/region-filtering). So the region `sk` means the key `SHA256("#sk")[0..15]`. The naming convention in the EU is not settled (Florida docs: "still being worked out"), there is a proposal for ISO 3166 / UN/LOCODE (issue #2495 in meshcore-dev/MeshCore, content **[UNVERIFIED]**, the page could not be loaded).

### 1.2 Sending
- `Mesh::sendFlood(pkt, uint16_t* transport_codes, delay, path_hash_size)` sets the route type to TRANSPORT_FLOOD and copies the codes (`ZC/src/Mesh.cpp:746-762`). Without codes, `sendFlood(pkt, delay, ...)` sends a plain FLOOD (`Mesh.cpp:717`).
- Companion: `sendFloodScoped` computes `codes[0] = scope.calcTransportCode(pkt); codes[1] = 0` (`CompanionMesh.cpp:148-158`). If the key is zero, an unscoped flood goes out.
- Companion protocol: `CMD_SET_FLOOD_SCOPE_KEY 0x36` (`ZC/app/CompanionProtocol.h:58`; `CompanionMesh.cpp:3289-3312`): `[cmd][0][16 B key]` = scope for sending, `[cmd][0]` = cancel the override (the default applies), `[cmd][1]` = hard unscoped. `CMD_SET_DEFAULT_FLOOD_SCOPE 0x3F` / `GET 0x40` (v11+): `[cmd][name 31 B][key 16 B]`, stored in prefs `default_scope_name/key` (`CompanionMesh.cpp:3314-3347`, `NodePrefs.h:175-176`). The new default scope was introduced by firmware 1.15.0 (blog.meshcore.io/2026/04/17/default-scope).
- For the tracker this means: neither the app nor the companion protocol is needed. The tracker computes the key itself (SHA-256 of `"#sk"`, ~50 B of table per region, or the 16 B key directly in the table) and calls `sendFlood` with the codes. SHA-256/HMAC is already in the firmware (`SHA256.h`).

### 1.3 Do transport codes reduce the maximum payload? **No.**
- `MAX_PACKET_PAYLOAD = 184` is the size of the `payload[]` array (`ZC/include/mesh/MeshCore.h:24`, `Packet.h:54`). It counts only the payload, without the header, codes and path.
- `MAX_TRANS_UNIT = 255` is the limit of the whole frame on the air (`MeshCore.h:27`).
- Serialisation (`ZC/src/Packet.cpp:69-79`, `Dispatcher.cpp:432-445`): `[header 1][codes 4 B only for a transport route][path_len 1][path 0..64][payload]`. The codes are **extra header bytes**; `getRawLength()` adds `+4` (`Packet.cpp:47-49`). The sender refuses only if `len + payload_len > 255` (`Dispatcher.cpp:442`).
- Worst case: 1 + 4 + 1 + 64 (full path) + 184 = 254 B <= 255. So even a completely full packet fits.
- Our packets on the air for a tracker flood (the path has 0 hops at sending): 37 B -> 41 B, 53 B -> 57 B, ~181 B -> ~185 B. All far below 255. If 181 B was meant as the payload, it is still <= 184, so it fits. Repeaters add 1-3 B per hop to the path (hash size 1-3), which is the same with and without transport codes.
- The cost is airtime: 4 extra B. With the preset 869.618/62.5 kHz/SF7/CR5 this is roughly +4 B * ~8.2 ms/B = ~33 ms per packet (a rough estimate from the bit rate, **not measured**). For a 37 B packet (~0.2-0.3 s) this is on the order of +10 %.
- Beware of collisions with our 2-layer "concealment": from an observer's point of view, a scoped flood is one more header with a 2 B hash (the code depends on the region), so it reveals the region, not the identity.

## 2. How repeaters decide on forwarding

Code: `RepeaterMesh::onRecvPacket` (`ZC/app/RepeaterMesh.cpp:677-696`) and `allowPacketForward` (`RepeaterMesh.cpp:569-578`), `RegionMap::findMatch` (`RegionMap.cpp:191-207`), `RoutingPolicy.h:22-26,40-47`. The same logic is in `RoomServerMesh.cpp:365-371`.

| Incoming flood | What the repeater does |
|---|---|
| Unscoped: ROUTE_FLOOD, or TRANSPORT_FLOOD with `codes[0]==0` | Corresponds to the "null/wildcard region". It is forwarded **unless** the admin set `region denyf *` (flag `REGION_DENY_FLOOD` on the wildcard, `RegionMap.h:17`). In addition `flood.max.unscoped` applies (a hop limit for unscoped, default 64, `NodePrefs.h:91,415`), which the admin can lower down to 0. |
| Scoped, the repeater has a region with the same name and without DENY_FLOOD | `findMatch` recomputes the HMAC for every configured region and compares `codes[0]`. A match -> forward (with the limit `flood.max`). The codes stay unchanged, and the packet is forwarded on (`Mesh.cpp:415-431`). |
| Scope of a foreign region (the repeater does not have it) | `findMatch` returns NULL -> `recv_pkt_region == NULL` -> **dropped** (`RepeaterMesh.cpp:575-578`). This is the intent of the filtering. |
| Scoped, but the region has DENY_FLOOD | Skipped in `findMatch` -> NULL -> dropped. |
| A repeater without `region` configuration (wildcard only) | Scoped: drops. Unscoped: forwards. |
| An old repeater (before v1.10.0) | Does not know filtering and forwards everything, so the scope "leaks" also into neighbouring networks (blog 2026-01-20, Florida docs). |

Consequences for the tracker:
- A scope `sk` gets through only via repeaters that have the region `sk` defined (`region put sk` + `region allowf sk`). If the community does not sit on `sk`, the scope is **worse** than unscoped. Unscoped, in turn, will not go through repeaters with `denyf *` or through those with a low `flood.max.unscoped`. See part 5 (the strategy for v1).
- **Dedup:** repeaters and nodes deduplicate by the hash of `type + payload`, without transport codes (`Packet.cpp::calculatePacketHash`, used in `Mesh.cpp` at `wasSeen`/`markSeen`). If the tracker sent **the same payload** to two scopes, in the overlap of networks the second copy would not push it through nodes that already saw the first. So when sending twice, always change the payload (for us the channel hash and the nonce rotate with the report counter, SPEC 6.1; it is enough to send a new report with a new counter or a different 1 B channel hash for the second send).
- The repeaters' response to requests does not concern us: the tracker asks for nothing.

## 3. Offline country detection from GPS

Requirements: Europe, accuracy ~km, one computation per fix (once per minutes to hours), so mainly the **flash size** matters, not the CPU. A Cortex-M4F @64 MHz handles even a naive polygon in milliseconds.

### 3.1 Options

**A. Simplified polygons (Natural Earth).**
- Natural Earth admin-0 countries: 1:110m ~ 177 countries, the world ~10 thousand points (order of magnitude; **not measured**); 1:50m ~ on the order of 100 thousand points, 1:10m more. The data are public domain (https://www.naturalearthdata.com/about/terms-of-use/).
- 110m is too coarse for borders (deviations up to ~10 km, mini-states are missing); for a band of 10-20 km it would suffice, but the SK/HU border on the Danube and SK/PL in the mountains could come out on the wrong side in a band around ±5 km.
- Size (estimate): Europe 50m ~20-30 thousand points * 4 B (2x int16 delta/fixed) = 80-120 KB, with delta coding and simplification (Douglas-Peucker to ~1 km) ~10-25 KB; world 110m ~40 KB, Europe 110m ~8-12 KB.
- CPU: point-in-polygon (ray casting) after a bbox filter, ~3-5 thousand segments * ~30 cycles = ~0.1-0.2 M cycles = 2-3 ms. Distance to the border: minimum point-segment distance over the same segments, ~0.3 M cycles (with `sqrtf`, FPU) = a few ms. The cost is negligible, but the code is more complex and the antimeridian has to be handled (not needed for us, Europe).
- Disadvantage: the table is hard to fix without regeneration, and it depends on the polygons and their orientation.

**B. A precomputed grid (raster) with the country in a cell. Recommended.**
- Range Europe/surroundings: lon -12..45, lat 34..72. At 0.1° = 570 x 380 = 217 thousand cells; at 0.05° = 870 thousand cells.
- A 0.1° cell is ~11 km (lat) x ~7.5 km (lon at 48°N), 0.05° is ~5.5 x 3.7 km. For "accuracy on the order of km, band 10-20 km", 0.1° is enough.
- Raw: 4 bits per cell (<=15 countries + sea/other) = 108 KB at 0.1°, 435 KB at 0.05°. Too much for flash without compression.
- RLE by rows (each row = a sequence of `(length, country)`): borders cross a row on the order of 5-15 times, so 380 rows * ~10 runs * 2 B ~ 8 KB (0.1°) and ~16-20 KB (0.05°, 2x rows, similar runs/row). Estimate **[not measured, needs generating]**; the real value will be 6-25 KB depending on the number of countries in the table.
- Lookup: a row index from lat, then a linear search for the run (~10 steps) = <1 µs on the order of magnitude. The row index (380 * 2 B offset) = 0.8 KB.
- Quadtree: for areas with a single country (sea, Russia) significantly fewer cells, but more complex code; at 0.05° it can go under 10 KB. RLE is simpler and enough for v1.
- Generation: a script on the host (Python + shapely/geopandas) rasterises Natural Earth 10m/50m into RLE rows and generates `region_grid.c`. The source is public domain, no licence restriction on the derived raster.
- Cells without a country (sea) -> "unknown" -> keep the last known region.

**C. Geohash prefixes.** In practice the same as a grid, only with uneven cells (geohash 4 = 39 x 19.5 km, 5 = 4.9 x 4.9 km). A prefix -> country table at 5 characters covering Europe has on the order of 10^5-10^6 entries, so without compression it is worse than B. An advantage only with a hierarchical tree (limited to borders). Disadvantage: computing geohash from lat/lon. I do not recommend it.

**D. Bounding boxes.** ~20-30 B per country, but the boxes of SK/CZ/AT/HU/PL overlap strongly (SK lies in the bbox of HU, PL and CZ). Unusable alone, good only as a quick prefilter or as an emergency fallback if the grid fails.

**E. Hybrid:** bboxes + a list of border "corridors" (manual). Maintenance is laborious, accuracy low. I do not recommend it.

### 3.2 A border band (dual-region) cheaply
From the raster, without distance geometry:
1. Find the country of the point `C0`.
2. Check 8 points on a circle of radius R (R = 15 km, +-0.14° lat, +-0.2° lon at 48°N) and possibly another 8 at R/2. For each, one lookup into the raster (<1 µs).
3. The set of distinct countries from these 9-17 points (without "sea") is the list of networks to transmit on. If there is only one, one network applies.
Cost: 17 lookups < 20 µs. Error: with R=15 km and 8 directions, a narrow protrusion of a foreign country (e.g. a spur) can be missed, which does not matter for our purposes.

An alternative from the raster: at generation time, precompute 1 bit "near border" for a cell (dilation) and store it as another bit of the RLE value (country+bit). There will be about 2x more runs. What the other country is has to be known, which is why the 8-point test is simpler.

From polygons: the point-segment distance over all segments of the neighbouring countries' borders (~0.3 M cycles) also suffices, but it requires a projection (longitude vs. latitude: `dx = dlon * cos(lat) * 111.32 km`, `dy = dlat * 110.57 km`).

Hysteresis (SPEC 6.4) is handled on top of that: the base country changes only after 2 consecutive fixes.

### 3.3 Existing libraries and data (licences)
- **Natural Earth** (admin-0 countries, 110m/50m/10m): public domain, no attribution. Disputed borders are shown "de facto" (Crimea), which does not matter for radio. https://www.naturalearthdata.com/
- **OpenStreetMap borders (admin_level=2, `boundary=administrative`)**: ODbL 1.0. A derived database has to be distributed under ODbL (share-alike) and with the attribution "© OpenStreetMap contributors". A table of countries and rasters generated from OSM is a "Derived Database", so it has to be published under ODbL; the firmware code itself is not a "database" and ODbL does not apply to it, **if** the data can be separated (a separate file/source, not slipped into the code without the source). This is not legal advice. The simplest is to use Natural Earth and the problem does not arise.
- **geoBoundaries** (CC BY 4.0, admin-0 gbOpen): requires attribution, not share-alike, but the data are extensive; the current licence **[UNVERIFIED]**, see https://www.geoboundaries.org.
- **westnordost/country-boundaries** (Java/Rust, https://github.com/westnordost/country-boundaries-rust): a precomputed raster of countries and regions from OSM, code under Apache-2.0/MIT, data ODbL (the exact licence of the data **[UNVERIFIED]**, check in the repo). The embedded database of ~37 MB is unusable for an MCU, but the idea (raster + RLE) is the same as B.
- **coordinates2country** (Java, <100 kB, ~50 ms lookup, https://github.com/coordinates2country/coordinates2country): interesting as a reference (a compressed raster), the licence of the data **[UNVERIFIED]**.
- For an MCU **I did not find a ready-made C library**; the search returned only desktop/server/mobile solutions. A custom generator + lookup is ~100 lines.

## 4. Table of presets and region names

The best source I found: the official list of recommended settings in the MeshCore configurator (`config.meshcore.io`), also available as JSON at `https://api.meshcore.nz/api/v1/config` (read 2026-11-14). It is a community list, not a standard. State as of today:

| Country | freq MHz | BW kHz | SF | CR | Source / status |
|---|---|---|---|---|---|
| SK | 869.618 | 62.5 | 7 | 5 | api.meshcore.nz ("Slovakia"); mesh.om3kff.sk: "Slovakia" applies since 8.8.2026 (`set radio 869.618,62.5,7,5`). The older SK setting **[UNVERIFIED]**, the community had a migration (Discord channel #migracie). |
| CZ | 869.432 | 62.5 | 7 | 5 | api.meshcore.nz ("Czech Republic (Narrow)"). Confirmation from meshcore.cz **[UNVERIFIED]** (the page links to a subpage "Nastavit rádio pre Česko" ("Set up the radio for Czechia"), which I did not load). A possible migration to an SK-like setting **[UNVERIFIED]**. |
| HU | 869.618 | 62.5 | 7 | 5 | api.meshcore.nz ("Hungary") |
| AT | ? | ? | ? | ? | No separate entry in api.meshcore.nz. DACH communities state "EU/UK (Narrow)" 869.618/62.5/SF8/CR8; separate confirmation for AT **[UNVERIFIED]**. |
| DE | 869.618 | 62.5 | 8 | 8 | "EU/UK (Narrow)"; "in all of Germany" according to the potatomesh pages (Berlin Chaos Mesh) and the DARC presentation by DL9SAU 2026-03-08 (I did not see the values in the text). Partially verified. |
| PL | ? | ? | ? | ? | I found nothing specific for PL. Probably "EU/UK (Narrow)" 869.618/62.5/8/8 **[UNVERIFIED]**. For PL the band/duty cycle (UKE) needs checking. |
| UA | ? | ? | ? | ? | Nothing found for MeshCore. Meshtastic: the UA_868 band 868.0-868.6 MHz, 1 % duty, 14 dBm; 869.618 is outside that band **[UNVERIFIED]**, the regulation is changing (draft NCEC 2026-08-31). Verify before use. |
| EU/UK fallback | 869.618 | 62.5 | 8 | 8 | api.meshcore.nz ("EU/UK (Narrow)"); NL, CH, PT use 869.618 with SF7/CR5 or SF8/CR8 depending on the entry. Obsolete: 869.525/250/11/5 ("Deprecated"). |

Notes:
- SK and HU have SF7/CR5, DE/CH/EU have SF8/CR8. Different SF/CR on the same frequency do not hear each other, so for the SK/AT border this is a concrete problem: AT probably behaves like DE/CH (SF8), SK as SF7. **[UNVERIFIED]** for AT.
- The original question in SPEC.md:404 (which preset for SK/CZ) is thereby mostly answered, but because of migrations the values should be taken as date-dependent.

Region names (scope):
- I found no official table of names. The SK community (mesh.om3kff.sk) does not state the region name on its main page. The CZ site meshcore.cz has a subpage "Nastavit regiony" ("Set up regions"), which I did not load.
- The convention I picked up: an ISO 3166 prefix in lowercase, e.g. `de`, `nl`; in the USA `us`, `us-{state}`, `us-{state}-{subregion}` (areyoumeshingwith.us). My hypothesis for our countries: `sk`, `cz`, `at`, `hu`, `pl`, `de`, `ua`. **[UNVERIFIED]**, to be checked directly on repeaters (e.g. the `region` command in the repeater CLI, or with the administrators).
- Note: a hierarchy (`de` -> `de-by`) is supported. Does a repeater with the region `de` also forward the children? `RegionMap` has `parent`, but `findMatch` only compares the codes of all regions, so a child requires its own code; a scope `de` is forwarded by repeaters that have `de` (directly). **[UNVERIFIED]** whether it includes parent/child, keep reading `RegionMap.cpp`.

## 5. Recommendation for v1

1. **A country table in the firmware (flash):** an array `{iso, freq, bw, sf, cr, scope_key[16]}` for SK, CZ, AT, HU, PL, DE (+ possibly UA later). ~40 B/entry, ~0.3 KB. `scope_key` precomputed on the host from `SHA256("#<name>")[0..15]` (or computed at runtime, only a few SHA-256).
2. **Country detection:** a 0.1° raster (Europe 34-72 N, -12-45 E) in RLE by rows, generated from Natural Earth 10m by a script in the repo (`tools/gen_region_grid.py`), ~8-15 KB flash, lookup < 1 µs. Border band: the 8-point test at R=15 km. In total ~10-20 KB flash, ~0 KB RAM (const), negligible out of 1 MB flash and ~260 KB used.
3. **Sending:** for each country in the set (1 or 2): switch the radio to the country's preset, build the packet with a new counter, compute `codes[0] = HMAC(...)` from the scope_key, `sendFlood(pkt, codes, ...)`. The second report in the border band is a new packet (a new channel hash and nonce, because of dedup). In total 2 packets, 4 B extra each. After sending, return the radio to the "home" preset for reception (the tracker does not need reception except for ACK/commands, **[to be clarified]**).
4. **Hysteresis:** the base country changes after 2 consecutive fixes (already in SPEC 6.4). Without a fix the last country applies. Outside the table: do not transmit, or only on the last known preset without a scope (in line with SPEC.md:297).
5. **Later (v2):** a quadrant tree for 0.05°, updating the table via a command, alternating presets after a long outage.

Size summary (estimate, not measured): raster 8-15 KB, table 0.3 KB, code ~1-2 KB, RAM ~0.

### Open questions
- What region names do the SK/CZ/AT/HU/PL/DE repeaters really use? Without agreement on names the scope is useless. A real configuration from repeaters (`region` in the CLI) or from the communities is needed.
- What is better for report survival: scoped only, unscoped only, or both? Unscoped passes through repeaters without regions (most of those that have not set any), scoped only through those with a region. I propose for v1: **scoped if the region is known, and in `LOST` mode also unscoped** (or an A/B experiment). Delivery success needs to be measured.
- The behaviour of `flood.max.unscoped`: if communities lower the limit to 0-2, unscoped reports die right at the first hop. This is already happening now (`RoutingPolicy.h:44-45`, `RepeaterMesh.cpp:560`), so a scope will become mandatory over time.
- SF7 vs SF8 on borders (SK/AT/DE): a double report on two presets means 2 radio switches and a longer airtime.
- Which countries to add to v1 besides SK/CZ (AT, HU, PL, DE are necessary because of SK's neighbours; UA has a different band and regulatory uncertainty).
- The tracker runs its own application code (`firmware/lt_core`), not the full `CompanionMesh`; it needs to be verified that it can call `Mesh::sendFlood` with transport codes and switch `radio` at runtime (frequency/SF/BW/CR) without a reboot (the companion has `_radio_reconfig_cb` for that, `CompanionMesh.cpp:140`, **[to be verified]**).
- Legal: the band 869.4-869.65 MHz has a duty cycle limit of 10 % and 500 mW ERP in the EU (ERC 70-03, band g3); other countries (UA, possibly PL) may differ. **[UNVERIFIED]**, check before deployment.

## Sources
- Code: files in `ZC/` with the line numbers above.
- https://blog.meshcore.io/2026/01/20/region-filtering (repeater behaviour, region names)
- https://blog.meshcore.io/2026/04/17/default-scope
- https://api.meshcore.nz/api/v1/config (list of presets)
- https://mesh.om3kff.sk (SK setting, since 8.8.2026)
- https://meshcore.cz, https://docs.meshcore.io/faq/ (nothing specific on regions/transport codes)
- https://areyoumeshingwith.us/docs/meshcore/regional-settings/ (US convention)
- https://www.naturalearthdata.com/about/terms-of-use/ (public domain)

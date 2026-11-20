# MeshCore flood latency per hop (measured 2026-11-16)

Read-only measurement of the public CoreScope analyzer at analyzer.meshcore.cz (Czech network, v3.15.0, 49 observers, about 820k packets per 24 h). No account, about 70 GET requests spaced 1+ s, generic User-Agent.

## Method

- `GET /api/packets?limit=60&type=5|6|4` gave candidate packets. Chosen: GRP_TXT (5) and GRP_DATA (6) with at least 50 observations, 45 packets, 3999 observations in total.
- `GET /api/traces/{hash}` returns every sighting of one packet: `observer`, `path_json` (list of repeater hash prefixes, so hop count = its length), `time` (observer's clock, **whole seconds**), `rssi`, `snr`.
- `GET /api/observers/clock-skew` gives an estimated clock offset per observer (45 observers, offsets -1.9 to +3 s, median absolute 0.9 s).
- For each packet: reference t0 = earliest sighting at the lowest hop count seen (hop 0 for only 18 of 45 packets, so the reference is often hop 1-3 and the delays below are slightly too small). Delay = sighting time - t0. Seconds per hop = delay / (hops - lowest hop). Repeated with skew-corrected times.
- Radio: 869.432 MHz, BW 62.5 kHz, SF7, CR 4/5 on 45 of 49 observers (the rest report no preset). Airtime of a roughly 100-byte packet is about 0.35 s; MeshCore repeaters add a random send delay on top.

## Results

Seconds per additional hop (3914 observations): median 1.0 s, p90 1.5 s (skew-corrected: 1.0 / 1.6). Mean per-hop is lower at long distances because sightings at many hops share the same time (see caveats).

Delay from the first sighting by hop count (raw median / p90 seconds, n sightings):

| Hops | Median | p90 | n |
|---|---|---|---|
| 1 | 1 | 2 | 64 |
| 2 | 1.5 | 4 | 112 |
| 3 | 2 | 4 | 252 |
| 4 | 3 | 5 | 395 |
| 5 | 4 | 6 | 494 |
| 6 | 4 | 7 | 505 |
| 7 | 5 | 8 | 470 |
| 8 | 6 | 10 | 394 |
| 9 | 8 | 12 | 269 |
| 10 | 9 | 13 | 222 |
| 11 | 10 | 15 | 150 |
| 12 | 11 | 17 | 141 |
| 14 | 7.5 | 20 | 100 |
| 16 | 10 | 22 | 60 |
| 20 | 9 | 14 | 17 |
| 28-29 | 13.5-16 | 16-18 | 20 |

Linear region (1-12 hops): about 0.9 to 1.0 s per hop. Typical total: 5 hops about 4 s (p90 6), 10 hops about 9 s (p90 13), 12 hops about 11 s (p90 17).

Packets seen at 10+ hops (41 packets, farthest observer): delay from first sighting to the farthest sighting median about 10 s, range 4 to 33 s; the full spread of the packet (first to last sighting of any observer) 5 to 33 s, median about 13 s. Typical 14-17 hop sightings: 11 to 24 s. 20+ hops are rare (n small): 8 to 18 s.

## Caveats

- Timestamps are whole seconds, so each delay has about +/-1 s of quantization noise; per-hop values for 1-3 hops are the least reliable.
- Clock skew: no negative delays appeared in 3999 sightings (the reference is the earliest low-hop sighting), but the analyzer's own skew estimate is up to 3 s (median 0.9 s), the same size as the 1-3 hop delays. Correction changed medians by under 1 s. Comparing across the same observer pair over many packets was not done; the analyzer's per-observer offset was used instead.
- Beyond about 12 hops the delay stops growing (median 8-10 s at 14-21 hops). Long-path sightings are survivorship-biased: packets that reach a far observer by a long detour are usually also seen quicker by shorter routes, and the first sighting (not the last duplicate) defines t0. Do not extrapolate 1 s per hop to 20 hops (that would give 20 s); observed 20-hop sightings were mostly 8 to 14 s, with a tail to 18 s and more.
- Delay includes observer upload and timestamping, not only radio. Observers stamp on receipt, so this is small, but it is not zero.
- Only GRP_TXT/GRP_DATA with 50+ observations were sampled, so they are heavily relayed floods in a dense network (urban, short links, SF7). Sparse networks or a slower preset (SF10+ is roughly 8 times the airtime) will be much slower per hop.

## Slovakia / Hungary (map.meshcore.hu, "EmpireMesh")

Same method, measured 2026-11-16. The site is CoreScope (same API as the Czech one: `/api/spec`, `/api/packets`, `/api/traces/{hash}`, `/api/observers/clock-skew`), 23 observers, about 182k packets, about 980 nodes. About 55 GET requests spaced 1.1 s, no account.

- Sample: 45 packets with 50+ observations (36 GRP_TXT, 9 GRP_DATA), 4118 sightings. Hop 0 was seen for only 9 of 45 packets.
- Radio: all 400 sampled packets report `869.618 MHz, BW 62.5, SF7, CR 4/5` (the Slovak/Hungarian preset, 186 kHz away from the Czech 869.432 MHz, same modulation, so airtime is the same).
- Clock skew: 22 observers listed, estimated offsets about -4 to +4 s (median absolute not computed). With raw times 512 of 4118 sightings came out up to 4 s earlier than the reference; skew correction did not remove them (653). So the whole-second timestamps and skew are as large as the per-hop delay here.

Seconds per additional hop (4032 sightings): median 0.67 s, p90 1.7 s (skew-corrected 0.58 / 1.6). Lower than the Czech 1.0 / 1.5, but the data is noisier.

Delay from the first sighting by hop count (raw median / p90 seconds, n):

| Hops | Median | p90 | n |
|---|---|---|---|
| 1 | 0 | 2 | 102 |
| 2 | 1 | 3 | 290 |
| 3 | 1 | 3 | 370 |
| 5 | 3 | 6 | 559 |
| 8 | 2 | 7 | 250 |
| 10 | 4 | 8 | 195 |
| 12 | 3 | 9 | 145 |
| 15 | 3 | 7 | 37 |
| 20 | 2 | 6 | 19 |

At 5 hops about 3 s (p90 6), 10 hops about 4 s (p90 8), 20 hops about 2 s (p90 6, n small). Sightings at 10+ hops: median 3 s, p90 7 s (n 978). Spread of a packet (first to last sighting of any observer): median 7 s, p90 12 s, range 1 to 33 s.

Reading: the delay does not grow with hop count beyond about 5 hops. Because the reference is the earliest low-hop sighting and many observers are close together, a long path is mostly a detour that arrives at the same time as the short path (same survivorship bias as for the Czech data), and the second-level timestamps cannot resolve it. Use the Czech table (about 1 s per hop up to 12 hops) as the conservative estimate; both datasets agree on a typical packet reaching the whole network in under 15 s.

### Exposure of unknown-channel GRP_DATA

Yes. `/api/packets?type=6` lists GRP_DATA with `raw_hex`, `path_json`, `observation_count` and a decoded block with `channelHashHex` and `decryptionStatus` (`decryption_failed` for channels the server has no key for), so unknown channels are visible as raw bytes. Of the 163 GRP_DATA packets with 30+ observations among the latest 200, 70 had channel hash byte `04` (the #libretag hash; one byte, so other channels can collide). A packet had 61 bytes of raw data and 75 observations. Usable as a bridge source, read-only.

## Slower presets (SF8 and up)

Measured 2026-11-16 on public CoreScope-style analyzers that cover the "EU/UK Narrow" preset (869.618 MHz, BW 62.5, SF8, CR 4/8). Same method as above (delay from the first sighting at the lowest hop count; seconds per hop = delay / (hops - lowest hop)). About 150 GET requests in total, 1 s apart, generic User-Agent. Only SF8 and SF7 data were found; no network on SF9 or slower was reachable.

### Sources

- **meshcore-analyzer.eu** (EU-wide aggregate, 3407 observers listed, about 1275 active, 480k distinct packets per day). `GET /api/packets/{hash}` returns every observation with `heard_at` at **microsecond resolution** and the path (`hops`) seen by that observer. Sampled about 170 flood packets (about 125 used after classification) (route type flood or transport-flood, 40 to 460 observations each, five time windows across 24 h). Observer radio strings split them cleanly by country: German observers are SF8/CR8 (185 of 400 active DE observers state it, 156 state nothing, 46 SF8/CR5, 10 SF7), Polish observers are SF8 (41 of 49), Dutch observers are SF7/CR5 (109 of 133), so the Dutch packets act as an SF7 control measured with the identical method. Each packet was classified by the majority radio of its observers.
- **live.saarmesh.de** (Saarland, CoreScope v3.17, 29 observers, 23 of them SF8/CR8 or unspecified). Whole-second timestamps, `/api/traces/{hash}` and `/api/observers/clock-skew` as at the Czech instance. 45 packets, 2328 observations.

### Seconds per additional hop

| Network (preset) | Packets | Observations | Median s/hop | p90 s/hop |
|---|---|---|---|---|
| CZ (SF7/CR5), from above | 45 | 3914 | 1.0 | 1.5 |
| NL via meshcore-analyzer.eu (SF7/CR5), control | 42 | 9156 | 1.05 | 1.75 |
| DE-dominated via meshcore-analyzer.eu (SF8/CR8) | 42 | 7242 | 1.78 | 3.5 |
| PL via meshcore-analyzer.eu (SF8/CR8, at least 60 % PL observers) | 39 | 2767 | 1.6 | 2.9 |
| Saarland, live.saarmesh.de (SF8/CR8) | 45 | 2235 | 1.75 | 4.0 |

So SF8/CR8 costs about 1.6 to 1.7 s per hop (median), p90 about 3 to 3.5 s, against about 1.05 s at SF7/CR5. Ratio vs SF7: 1.5 to 1.7.

### Delay from the first sighting by hop count (median / p90 seconds)

| Hops | DE SF8 | PL SF8 | NL SF7 (control) |
|---|---|---|---|
| 2 | 3.0 / 9.6 | 1.9 / 4.1 | 0.6 / 2.9 |
| 3 | 4.1 / 8.8 | 3.2 / 6.2 | 1.5 / 3.9 |
| 5 | 6.2 / 12 | 5.8 / 9.7 | 2.6 / 5.5 |
| 8 | 9.5 / 16 | 9.7 / 15 | 5.4 / 8.3 |
| 10 | 14.7 / 23 | 13.1 / 22 | 7.4 / 10.8 |
| 12 | 19 / 31 | 18 / 24 | 9.7 / 13.5 |
| 15 | 28 / 44 | 24.5 / 41 | 14.7 / 20 |
| 20 | 35 / 38 (n=15) | 36 / 41 (n=9) | 23 / 26 |

Saarland (shallow network, mostly 1-6 hops): 3 hops 4 s (p90 10), 5 hops 4 s (p90 11), 8 hops 5 s (p90 14). The whole-second timestamps and a small hop range make it less precise.

Rule of thumb for SF8/CR8: 5 hops about 6 s (p90 10-12), 10 hops about 14 s (p90 22), 20 hops about 35 s (few samples). At SF7/CR5: 3 / 7.5 / 23 s.

### Compare to airtime

LoRa airtime with explicit header, CRC, 8 preamble symbols, BW 62.5 kHz: 36 B 154 ms (SF7/CR5) vs 411 ms (SF8/CR8), x2.7; 100 B 349 ms vs 935 ms, x2.7; 140 B 461 ms vs 1263 ms, x2.7. SF8 with CR5 would be only x1.8. The measured latency ratio per hop (1.5 to 1.7) is lower than the airtime ratio (2.7), so a part of the per-hop delay does not scale with airtime (processing, the observers' own timing, first sighting vs. later duplicates). For planning, use about 1.7 s per hop for SF8/CR8 (1.6 to 1.8 times the SF7 hop, not 2.7 times). SF9 and slower presets were not found in public data; airtime alone suggests about x5.4 (SF9/CR8) and x11 (SF10/CR8) versus SF7/CR5, but the latency scaling above says to expect less.

### Caveats

- **Timestamps differ by instance.** meshcore-analyzer.eu `heard_at` has microsecond resolution and very few negative delays (DE 66 of 7322 observations, PL 1 of 2840, NL 42 of 9241), so it is most likely the analyzer's own receive time, not the observer's clock. That removes clock skew but adds MQTT/uploader latency and jitter (tens to hundreds of ms, and possibly seconds for observers that batch). Not verifiable from the API, as the API does not say which clock it is.
- Saarland: whole seconds, skew estimates up to 7 s (median 0.9 s, 23 observers); applying them did not improve the result (more negative delays, 255 vs 153 of 2328), so only raw values are given.
- Classification by radio string is approximate: 156 German observers report no radio and a few packets were bridged between presets. Packets with min hop above 5 were dropped (two packets with a first sighting at hop 16 and 21 gave nonsense).
- Delays beyond 15 hops are sparse and unreliable: sightings at 20+ hops include loops and long detours (medians 45-49 s at 26-28 hops come from 2 packets).
- Same survivorship bias as the Czech section; the most-observed packets are floods in dense areas (Hamburg, Frankfurt, Cologne, Braunschweig, Krakow, Warsaw). Sparse regions will be slower.
- Types were mixed (flood DMs, group text, adverts, requests, about 125 packets); per-hop timing does not depend on type except through airtime.

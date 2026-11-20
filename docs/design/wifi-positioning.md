# Location from Wi-Fi APs (Fable proposal, 2026-11-16)

Markers: [O] verified in source/document, [Ú] inference, [N] unverified. The endpoint was not tested live.

## Hardware with Wi-Fi scanning

| Board | Wi-Fi | GPS | ZephCore | Note |
|---|---|---|---|---|
| Seeed T1000-E (nRF52840 + LR1110 + AG3335, 700 mAh) | LR1110 passive 2.4 GHz scan | yes | `t1000_e` [O] | driver `lr11xx_lora.c` has no Wi-Fi, `lr11xx_wifi_*` must be added [Ú]; scan of 6 MACs ~70 ms, ~10 mA [O] |
| Heltec Wireless Tracker V1.1/V2 (ESP32-S3 + SX1262) | yes | yes | yes [O] | the tracker role runs on the ESP32-S3 (see the next row); this board not tried |
| Heltec Wireless Stick Lite V3 / WiFi LoRa 32 V3 (ESP32-S3 + SX1262) | yes | no | `heltec_wifi_lora32_v3` [O] | tracker with Wi-Fi scans and Wi-Fi zones, tested on the bench: `docs/design/fw-wifi-esp32.md` |
| LilyGO T-Beam (ESP32) | yes | yes | `ttgo_tbeam` [O] | |
| ThinkNode M9 (ESP32-S3 + LR1110) | yes | yes | source only [O] | |
| Wio L1, X1, T-Echo, RAK4631 | no | yes | yes | add-on: ESP32-C3 on UART (AT+CWLAP, ~0.05 mAh/scan) or nRF7000 [Ú/N] |

## GrapheneOS network location [O, platform_packages_apps_NetworkLocation, grapheneos.org nginx.conf]

- The client in the phone sends only the BSSID (no RSSI) and computes the position locally. Proxy `POST https://gs-loc.apple.grapheneos.org/clls/wloc` (Apple directly `gs-loc.apple.com`), nginx `proxy_pass`, no authentication, body max 1 kB (~40 BSSIDs), the body is not logged.
- Request: headers `Content-Type: application/x-www-form-urlencoded`, UA `locationd/...`; body big-endian `u16 1, u16+"en-US_US", u16+"com.apple.locationd", u16+"15.4.24E248", u32 1, u32 len, protobuf ALSLocationRequest { 2: wireless_aps[{1: mac "aa:bb:cc:dd:ee:ff"}], 4: number_of_surrounding_wifis (100), 31: bands [1,2], 32: 2, 33: meta }`.
- Response: skip 10 B, `ALSLocationResponse { wireless_aps[{ mac_id, location { lat, lon (int64 × 1e-8), accuracy m, ... } }] }`; unknown BSSID has `lat == -18000000000`; MAC without leading zeros in the octets; it also returns hundreds of surrounding APs.
- A third-party app cannot use it through the Android API (`exported=false`), but technically can over HTTP directly; the terms are not defined (it is polite to ask). Alternatives: Apple directly (Apple sees the owner's IP), BeaconDB (`api.beacondb.net/v1/geolocate`, min. 2 full MACs, `considerIp:false`, worse coverage), offline database (none yet).

## Proposal

- The service takes only a full MAC. The tracker sends **1 full BSSID ("seed") + the other APs as the last 3 B**; the app asks for the seed, gets 100–400 neighbours and matches the tails locally (false match ~0.002 %).
- Selection in the tracker: by RSSI, without `_nomap`/`_optout`, without hotspots, seed only with a globally unique MAC, one BSSID per radio.
- Report without a new packet: byte 15 bit 7 = Wi-Fi layout; 16–21 two repeaters; 22–27 full BSSID A; 28 meta A (RSSI `−95 + 2q` in 5 bits, number of APs in the scan in 3 bits); 29–31 tail B; 32 meta B. The 8 best APs rotate across reports.
- Optional second packet (LOST/on command): 1 full + 6 tails, `j = 2`.
- Scan only on wake-up without a fix; optionally also with a fix, to build an own AP map (fingerprints).
- Estimate in the app: fine grid 5–10 m ±300 m, log-distance (P0 ≈ −40 dBm, n = 3, σ = 8 dB), AP position error, ε-mixture for moved routers, product with the distribution from the repeaters; with 1 AP no less than ~50 m / 120 m.
- Privacy: the BSSID travels over the mesh only encrypted; a query tells the service about interest in AP X (the proxy sees both IP and BSSID). Lookup only when needed/LOST, cache, own map.

## Questions for the owner

1. Hardware: T1000-E, an ESP32 board, or an add-on for the L1?
2. May the app call the GrapheneOS proxy (ask the project), or Apple directly / BeaconDB?
3. 2 repeaters + 1 full + 1 tail, or 1 repeater + 2 full (BeaconDB)?
4. Lookup automatically, or on tap and in LOST?
5. Second packet only in LOST?
6. Scan also with a fix, for an own AP map?
7. Does the Grove on the L1 have switched power?

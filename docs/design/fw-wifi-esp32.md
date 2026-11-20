# Tracker on an ESP32-S3 stick: Wi-Fi instead of GPS

Status: 2026-11-17. Runs on a Heltec Wireless Stick Lite V3 (ESP32-S3, SX1262, no GPS) on the bench.
Home zones, quiet, reports and the trip over the mesh to Nostr were tested on that board. The
"outside a zone" cases are synthetic, because the stick never moved (see "What the owner should
test").

## What runs

The tracker role builds for `heltec_wifi_lora32_v3/esp32s3/procpu` with the same `tracker.conf` as
the Wio. CMake adds `tracker_wifi.conf` (Wi-Fi scan, `CONFIG_LIBRETAG_WIFI`) and `pm_esp32.conf`
(light sleep), because the board manifest declares `wifi` and `light_sleep`.

```sh
west build -b heltec_wifi_lora32_v3/esp32s3/procpu zephcore -d build-esp-tracker -- -DEXTRA_CONF_FILE="boards/common/tracker.conf"
west flash -d build-esp-tracker --esp-device /dev/cu.usbserial-0001
```

The toolchain is `xtensa-espressif_esp32s3_zephyr-elf` (`zephyr-sdk/setup.sh -t ...`), the
Espressif blobs come from `west blobs fetch hal_espressif`, and `esptool` (5.x) must be on the PATH.
Flash 1.0 MB, DRAM 318 KB of 399 KB (80 %).

The stick is a Wireless Stick Lite V3, but the WiFi LoRa 32 V3 board definition fits it. The SX1262
sits on the same pins (NSS 8, SCK 9, MOSI 10, MISO 11, RESET 12, BUSY 13, DIO1 14). I checked this on
the air, not only from the board files: `meshscan` heard a repeater, the boot report was relayed by
a repeater, and the bridge published it to the relays as kind 4243. The missing OLED is harmless.
The console is UART0 through the CP2102 at 115200 baud.

| Item | Status |
|---|---|
| Keys and state in flash, sealed LT2 reports, commands, checks | as on the nRF (same code); reports relayed and seen on Nostr |
| Wi-Fi scan (`lt_wifi_scan`, `src/lt_wifi_esp32.c`) | on hardware |
| Wi-Fi zones (`wzone`), quiet in a zone, leaving a zone | on hardware (leaving simulated with a threshold of 100) |
| Parked by Wi-Fi | in lt_core tests; on hardware the score is computed and logged |
| Report with two full BSSIDs | on hardware (history flag `wifi`); decoded by `libretag.lt2` |
| Light sleep between wake-ups, console wakes the SoC | on hardware (see "Power") |
| Battery | reads 4316 mV on USB without a cell (charger voltage); not tested on a cell |

## Wi-Fi scan

One blocking scan per wake-up through Zephyr's Wi-Fi management API (`NET_REQUEST_WIFI_SCAN`, the
results as `NET_EVENT_WIFI_SCAN_RESULT` events). After the scan the driver's radio is stopped again
(`esp_wifi_stop`). While the Wi-Fi radio runs, the Wi-Fi library holds a sleep lock and the SoC never
light-sleeps. Before each scan the station gets a new random locally administered MAC. An active
scan sends probe requests, and with the factory MAC every probe would tell a Wi-Fi sniffer that the
same tag is here again. `wifi passive` sends nothing at all.

Measured on the bench (one room, the stick lying still, 30 active and 20 passive scans 10 s apart;
the raw data stays outside the repo):

| | active | passive |
|---|---|---|
| scan time | 2421 to 2422 ms | 5055 to 5056 ms |
| APs per scan | 3 to 4 | 3 to 4 |
| strongest AP | seen 30/30, −65 dBm, sd 0.5 dB | 20/20, −65 dBm, sd 0.4 dB |
| 2nd (global MAC) | 29/30, −83 dBm, sd 0.6 dB | 20/20, −84 dBm, sd 0.6 dB |
| 3rd (locally administered) | 26/30, −86 dBm, sd 0.8 dB | 20/20, −86 dBm, sd 0.9 dB |
| 4th (global MAC) | 28/30, −91 dBm, sd 1.3 dB | 19/20, −91 dBm, sd 1.2 dB |

The scan time does not vary because the driver uses ESP-IDF's default dwell times (13 channels,
about 185 ms each active, 390 ms passive), whatever the scan finds. Nothing moved in the room, so the
RSSI is very stable (spread at most 5 dB). The APs that drop out are the weak ones near −90 dBm. This
is a quiet place with few APs. A town flat will see 15 to 40 BSSIDs, many of them weak and coming and
going. The fingerprint is built to cope with that (below), but this bench did not test it.

Zephyr's `wifi_scan_params` has dwell times, but the ESP32 driver ignores them. A shorter dwell
(e.g. 60 ms active) would cut the scan to about 1 s and the energy by half. That needs a change in
Zephyr's driver (`esp32_wifi_scan`: `scan_config.scan_time`), so it is left for later.

## Wi-Fi zones

`wzone snap <name> [scans]` takes 5 scans (default), and the tracker keeps the place as a
fingerprint: up to 16 APs that were seen in at least half of the scans, each with a keyed 32-bit hash
of the BSSID (HMAC with a salt that never leaves the tracker), the mean RSSI, how often it was seen,
and the spread. The BSSIDs themselves are not stored. Up to 8 zones, 1.2 KB in `/lfs/libretag/wzones`
(own magic and CRC, written atomically like the state). Only globally administered BSSIDs count. A
phone hotspot uses a random locally administered MAC, and a zone must not travel with the owner's
phone (that is what anchors are for).

**Score.** Each AP weighs `w = clamp(rssi + 95, 0, 60)`, in the fingerprint times seen / scans.
Score = 100 × Σ min(w_zone, w_scan) / Σ max(w_zone, w_scan) over the union of both sets (a weighted
Jaccard index), but an AP of the scan that the zone does not have counts only half. A new router next
door or a car with a hotspot appears at home far more often than a strange place holds all of our
APs, and full weight let two new strong APs push a real home scan under the threshold. Of a scan only
the 16 strongest usable APs count. Integer math, so C (`lt_wfp_score`) and Python
(`libretag.wifizone.score`) agree to the digit; vectors `wifi_zone` in `lt2.json`.

**Decision** (`lt_wzone_match`): the tracker is inside a zone when the score is at least 40 and
at least 2 APs are in common (1 for a zone with a single AP). It stays inside the zone it was in
while the score is at least 30. A scan with no usable AP is never a zone, the same fail-open rule as
"no fix" (a Faraday bag, a jammer and a basement look the same). A fix wins over Wi-Fi. Inside counts
as a home zone in `lt_wake_decide`: the first wake-up sends a report with `QUIET`, then the tracker
is silent until the heartbeat (24 h) or until it leaves, and leaving is a normal report at the next
wake-up, as with GPS zones. `wzone thr <enter> <stay> [min_match]` changes the thresholds.

**Parked without GPS.** The 0.7 parked rule needs fixes within 100 m. Without a fix the tracker
compares each scan with the first scan of the stretch (a one-scan fingerprint) and counts it as the
same place at a score of 40 or more. So a tag left in a car or a bag at a friend's goes quiet after
3 wake-ups, as with GPS.

**Measured scores** (fingerprint of the first 5 scans, scored against every later scan):

| case | score | inside? |
|---|---|---|
| same place, real scans (25 active, 15 passive) | 71 to 100, median 95 (active); 89 to 100 (passive) | always |
| one scan against the next (parked) | 71 to 100 | always |
| on the stick after `wzone snap home`: `wzone test`, then 3 checks | 93 to 97 | yes |
| same place, 2 new strong APs added (synthetic) | median 58, max 67 | enters 25/25 |
| next door: −12 dB and 3 new APs (synthetic) | median 21, max 26 | enters 0/25, stays 2/25 |
| across the street: −20 dB, 5 new (synthetic) | median 8, max 11 | never |
| 100 m away: −30 dB, 6 new (synthetic) | 0 to 2 | never |
| elsewhere: none of ours (synthetic) | 0 | never |

The synthetic "outside" scans take each real scan, lower every RSSI by the given amount (±3 dB of
noise), drop what falls below −92 dBm and add new APs at −55 to −80 dBm. Leaving a zone was also
tested on the stick by setting the thresholds to 100: the next check sent a normal report (no
`QUIET`), and the history shows it relayed with the Wi-Fi layout.

A Wi-Fi zone reaches roughly as far as the home APs are strong, about the
flat and the stairwell, not the street. Through one wall (−12 dB) the tag may stay inside the zone
it was already in but does not enter it. These thresholds come from one quiet room. They need checking
in a dense block of flats first.

## Reports

Without a fix the report uses the Wi-Fi layout of PROTOCOL.md 2.2 (byte 15 bit 7): 1 repeater, the
strongest globally administered BSSID in full ("seed"), its RSSI and the AP count, and the last 3
bytes of another AP that rotate with `n` over the next best. The scan of the wake-up is reused, so a
wake-up scans once.

**Change: a second full BSSID.** A tracker that never had a fix sends fix time 0, so bytes 8 to 14
(position and accuracy) carry nothing. With the Wi-Fi layout and fix time 0 they now hold a second
full BSSID (bytes 8 to 13) and its RSSI (byte 14, bit 7 set), and the tail rotates over the APs after
both seeds. Why: BeaconDB answers only for at least 2 full BSSIDs, and the app's
`WifiObservation` already has a list for more full MACs (`others`). One full BSSID per report
was only enough for the Apple/GrapheneOS lookup. Older decoders show nothing there, because fix time 0
means no position. A board with GPS that once had a fix keeps the old layout. `lt_wifi_select_ex`,
`libretag.lt2.wifi_select(..., seed2_in_report=True)`, vectors `wifi_nofix`. The tail and seed come
from the same rules as before: `_nomap`/`_optout` SSIDs are never sent, and a locally administered
MAC is never a seed.

**Change: a second tail.** With at least two tail candidates the repeater's 3 bytes (16 to 18) carry
a second tail, its RSSI goes to byte 30 (bit 7 set, a byte older firmware always sent as 0), and the
report lists no repeater (the count in byte 15 stays). The tails take `rest[2n]` and `rest[2n + 1]`, so
the 7 next-best APs still come round within four reports. The repeater gave a rough area without GPS;
two tails per report give the Apple lookup more neighbours to match, which places the tag far better.
With only two usable APs the old layout stays. Only one extra tail fits: the Wi-Fi layout has 3 bytes
of repeater plus the spare byte 30. `lt_wifi_select_ex`, `libretag.lt2.wifi_select`, vectors `wifi`,
`wifi_nofix`, `wifi_few`; built for the stick, not flashed yet.

In LOST the Wi-Fi packet (`j = 4`, 2.4) follows the report as before.

**In the app (0.8.6, PWA 0.2.3).** Bytes 8 to 14 decode as `seed2` when `WIFI` is set, the fix time
is 0 and the bytes are not all zero (`LibreTag.kt` `Report.decode`, `pwa/src/lib/lt2.js`). The app
passes it as `WifiObservation.others` (`WifiMerge.observation`), so a BeaconDB lookup works from a
single report. Byte 14 is then no accuracy, and fix time 0 means no last fix.

Wi-Fi zones are set over USB only, because `wzone snap` must run where the zone is, with the tag
there. Since 0.9 they are places in the Places list of Tag settings (`PlacesView`, also on the Add
tag wizard's place step): name, then a snap sheet (scans with progress) and an immediate
`wzone test 1` shown by the tag's own thresholds, plus Check here and Delete. The app keeps a copy of `wzone list` (names and AP counts) for
the status text.

How the app knows a Wi-Fi tag: over the cable `help` lists `wzone` and the unlocked `status` prints
`wifi zones=<n> enter=<e> stay=<s> min_match=<m>`; from the air, any report with the `WIFI` layout
(only boards with a scanner send it). Either sets `Tracker.wifiTag`.

A quiet "zone" report without a fix is a Wi-Fi zone (a GPS zone needs a fix of that wake-up). The
status shows the zone's name when the tag has one Wi-Fi zone, else "a Wi-Fi zone". Leaving is the next
normal IDLE report after it (`WifiZoneTransit`); a quiet-owner report ends it without an alarm, as for
GPS zones. A reboot inside the zone that sends a normal boot report would raise a false "left" alarm.

The Heltec boards talk through a CP2102 bridge, not CDC-ACM. `UsbSerial` drives it with the CP210x
vendor requests (enable, 8N1, 115200) and never sets DTR or RTS, which reset the ESP32. Before a
command after 20 s of silence the console sends two empty lines to wake the SoC from light sleep.
None of this has run on hardware yet.

## Power

The tracker sleeps with Zephyr's light sleep (`pm_esp32.conf`): RAM, timers and the radio
configuration survive, the RTC timer and the SX1262 DIO1 wake the SoC. Deep sleep would cost a reboot
at every wake-up (the tracker's state machine, keys in RAM and the check schedule would have to move
to RTC memory or flash), so it is not used. The first 10 minutes after a boot the console window
keeps the SoC awake (ZephCore's `pm_esp32_console.c`).

Console in light sleep: opening the CP2102 port resets the board (DTR/RTS), and every reset counts
toward the tracker's reset backoff (2, 4, 8 … minutes to the first report). So typing at the console
now wakes the SoC (`src/lt_pm_uart_esp32.c`). The ESP32's UART wake source did nothing on this board,
with the port on APB or on XTAL, so the RX pad gets the treatment ZephCore gives the radio's DIO1: for
each sleep it is a GPIO level wake at low, with its input kept live. After a wake from it, and after
any console input, the tracker stays awake 30 s. In practice the first line after a sleep is lost
and often the second as well: a host sends a line break, waits a second, and repeats the command
until it is echoed. `sleep off` holds the SoC awake until reboot, `sleep` prints the statistics.

Measured with `sleep` (the SoC's own counters; no current meter was available): in a zone with the
interval set to 2 minutes, the SoC was asleep 540 s of 583 s left alone (92.6 %). That is about 10 s
awake per check, of which 2.4 s is the Wi-Fi scan; the rest is the 30 s check window, where the
SoC sleeps between radio interrupts (33 radio wake-ups in that time, packets on the mesh). Over the
first 12 minutes after a boot, console window included, it was 67 %. At the normal hourly interval the
same 10 s per check means the SoC is asleep about 99.7 % of the time. Wake sources seen: the RTC
timer, the SX1262 DIO1 and the console pad, so the radio's sleep holds (NSS, RESET) work on this
board as they do for the ZephCore repeater.

Energy estimate (datasheet figures, not measured on this board):

| | current | per wake-up |
|---|---|---|
| active scan, 2.42 s | ~100 mA (RX ~90 mA plus probe bursts) | 0.067 mAh |
| check window ~35 s (radio RX, SoC mostly asleep) | ~6 mA | 0.058 mAh |
| report (TX 22 dBm, ~0.2 s) + 60 s listen | ~125 mA / ~6 mA | 0.1 mAh |
| light sleep (SoC ~0.25 mA, SX1262 asleep, regulator) | 0.3 to 1 mA (to be measured) | 7 to 24 mAh a day |

At hourly wake-ups in a zone that is about 3 mAh a day for the wake-ups. The light-sleep floor
dominates by far, so the real budget depends on the board's sleep current, which needs a meter
(PPK2). The tracker's own energy estimate uses these figures on ESP32 (`UA_*` in `main_tracker.cpp`).

## Open questions

1. Dense places: do 40/30 hold where a scan sees 30 BSSIDs that come and go? `wifiscan 30 10` in
   a flat, then `wzone test` in the stairwell, on the street and next door.
2. 5 GHz: the ESP32-S3 has 2.4 GHz only. A home whose router has a weak 2.4 GHz radio gives a poor
   fingerprint.
3. Mesh routers and multi-BSSID APs: one radio often has 2 to 4 BSSIDs (main, guest, IoT). They all count
   as separate APs in the fingerprint (correlated, so they weigh more). The "one BSSID per radio"
   rule of wifi-positioning.md is used only for what goes on air.
4. A router that is replaced changes the zone. The tag then reports as "outside" until a new `wzone snap`.
   Re-learning (moving the fingerprint slowly towards what the tag sees while it is inside) is
   possible, but it could also let the zone drift to wherever the tag is taken, so it is not done.
5. Shorter dwell times (driver change) and passive scanning by default: passive costs 2.6 s more but
   sends nothing.
6. Battery measurement on a cell, the sleep current, and deep sleep if light sleep turns out too
   expensive.
7. BLE presence scan (anchors) at the same time as a Wi-Fi scan uses the ESP32 coexistence; not tested.

## What the owner should test

1. Take the stick (on a battery) out of the zone: through a wall to a neighbour, down the stairs, onto
   the street, 100 m away. At each spot run `wzone test 3`, or wait for a check, and note the score.
   That gives real "outside" numbers in place of the synthetic ones.
2. `wzone snap` in a busy place (a flat in a block, an office) and `wifiscan 30 10` there, to tune 40/30.
3. A battery-powered run of a day with `sleep on` and a current meter (or the cell voltage before and
   after) for the sleep current.
4. In the app: a report from this tag should decode with a seed and a second seed, and a lookup
   should return a position (after the decoder change above).

## 0.7.8: reboot inside a zone, GPS probe, owner step

- A reboot inside a Wi-Fi zone no longer looks like leaving it. The state keeps the zone of the last decision (`last_wzone`, a former padding byte), so the first wake-up after a boot matches that zone with the stay threshold and its report goes out quiet. Each wake-up now scans Wi-Fi before it starts the Bluetooth presence scan, so the two radios do not share the antenna during the scan, and an empty scan runs once more after 500 ms. Report 771 (normal, no Wi-Fi data) came from a wake-up whose scan placed nothing; which of the two causes it was is not known. App 0.8.8 also keeps the zone when a normal report without Wi-Fi data follows a counter jump (a reset moves `n` to the state's checkpoint), and lets the next report decide. The report has no boot flag.
- GPS: ZephCore's boot probe already marks the GPS absent when no NMEA sentence arrives within 10 s of power-on, and the first report comes at least 30 s after a boot. On the stick `selftest` showed `gps=yes` at 13 s and `gps=none nmea=0` at 60 s, so its wake-ups take the no-GPS path, including the 3 s Bluetooth window. The 0.7.7 note that it ran the 300 s first-fix search was wrong. A search that is still running when the probe gives up (a console `report` in the first seconds) now ends at once. Boards with a GPS send NMEA indoors too, so the probe does not affect them.
- `version` prints a second line, and the new `selftest` prints the same line, on a locked console too. It has no keys and no position: `selftest nostr=ok gps=none nmea=0 wifi=1 scan(s) first=4AP empty_retried=0 owner=set uptime=60s`.
- The pairing wizard's owner step did store the owner on the stick (`owner=set`). The app reported a failure because the tag answers `owner <npub>` with the line the app sent, and the app dropped every line equal to the command as the echo. App 0.8.8 drops only the first one, and sends the owner npub again on an unlocked USB session when the tag shows none.

Bench, 2026-11-17: 0.7.8 flashed over the locked, paired stick (keys, pairing and the zone `home` kept), then one port open: `nostr=ok`, `gps=none` after the probe, the first wake-up scan saw 4 APs, `owner=set`.

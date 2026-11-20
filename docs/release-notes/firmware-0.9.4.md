# LibreTag tracker firmware 0.9.4 (first public release, preview)

The tracker firmware is a `tracker` role for ZephCore, the Zephyr port of MeshCore. The tag sleeps, wakes about once an hour, takes a GPS fix and sends one encrypted 45-byte report into the MeshCore network on the `#libretag` channel. After the report it listens briefly for a command from its owner. Nobody but the owner can read the report: it is sealed with keys made at pairing, and the tag carries no permanent MeshCore identity.

This is a preview. It runs on the owner's tags and on the bench, but battery life has not been measured, and some paths below are marked as untested.

## Hardware

- **Seeed Wio Tracker L1 / L1 Pro** (nRF52840, SX1262, GPS). This is the main target.
- **Heltec Wireless Stick Lite V3** (ESP32-S3, SX1262, no GPS). Bench only: it knows it is at home from the Wi-Fi networks it hears, and its reports carry Wi-Fi networks instead of a GPS fix.

## What it does

- Reports every hour (±10 %), with the GPS search capped at 90 s. TRACK mode reports every 2 to 30 minutes for an hour, LOST mode every 10 minutes for 24 hours, and LOST survives a restart.
- Stays quiet when nothing needs saying: inside a home zone (a GPS circle or a Wi-Fi place), while one of the owner's Bluetooth devices is near, or when it has not moved. It still sends a heartbeat at least every 24 hours, and without a fix it always reports.
- Checks for commands between reports: at each wake-up it listens on the radio, and since 0.9.3 it also runs a 1.5 s Bluetooth scan for a command advertised by the owner's phone nearby.
- Reports without GPS carry up to five repeaters it heard and the last fix with its time, so the app can draw an area instead of a point.
- Picks the radio settings by country after each fix (SK, CZ, HU, NL, PT, CH presets, EU/UK Narrow elsewhere). Near a border it sends a second copy to the neighbouring network, but only when the home network is quiet.
- Sends a Nostr direct message to the owner when the battery falls below 10 %, signed by the tag's own key and carried by any bridge. At 25 % the app sends the alert.
- Caps its own airtime: at most 200 floods a day, at most one report per 120 s in TRACK and LOST, and no transmission below 3250 mV.
- After pairing, the USB console is locked: the key has left the tag, settings need the app's challenge and response. For 3 minutes after a boot the console is also reachable over Bluetooth with sealed frames, and the owner can reopen that window remotely with the `BLE_CONSOLE` command (0.9.2).

## Changes in 0.9.x

- 0.9.4: the first report after a restart is marked as such. Without a GPS fix it also carries why the tag restarted (reset button, power, a software reset, brown-out, watchdog or a crash) and how many times in a row it has restarted. The app uses this to tell a restart apart from lost reports.
- 0.9.3: every wake-up looks for the phone's command advert, including the listen-only wake-ups between reports.
- 0.9.2: `BLE_CONSOLE` command (opcode 0x06) opens the Bluetooth console window.
- 0.9.1: a reset by the owner (button, power-on, USB serial, `reboot`, `dfu`) no longer counts as a reset loop.
- 0.9.0: report sealing v2. Reports commit to the mailbox key of their counter, so bridges only deliver commands signed for that report. Apps before 0.11.0 and bridges before 0.5.0 (Python) or 0.4.0 (Android) cannot handle these reports.

## Install

See "Installing the tracker firmware" in `README.md`: UF2 drag-and-drop or serial DFU for the Wio Tracker L1, esptool for the Heltec. The app can also update a Wio tag over the USB cable.

## Known limits

- Battery life is an estimate (`uv run libretag energy`), not a measurement.
- Physical access to the tag gives access to its keys: APPROTECT is off and the bootloader accepts unsigned images. Zones and the last position are stored in plain form on the tag.
- Not tested on air: the phone's command advert (0.9.3), and the Heltec's command scan.
- The boot report (0.9.4) has only been tested against the reference vectors so far, not on a tag.

## Source

ZephCore commit `da3513aa6d08` (pinned in `firmware/zephcore.lock`) plus the 40 patches in `firmware/zephcore-patches/`. Licences of the bundled code: `THIRD_PARTY_NOTICES.md`.

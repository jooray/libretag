# Changelog

LibreTag has four parts with their own version numbers. The first public release is a preview of all of them at once. The development history before it is not public; the main steps are summarised below. Details per version: `firmware/README.md` for the firmware, `docs/release-notes/` for each component.

## 0.13.0: the shared MeshCore Local API client

| Part | Version |
|---|---|
| Android app | 0.13.0 |
| Android bridge ("Nostr bridge") | 0.5.0 |
| Python bridge (`nostrmesh`) | 0.6.0 |
| Tracker firmware | 0.9.4 (unchanged) |

All three Local API clients now use the [MeshCore Local API](https://github.com/jooray/meshcore-local-api) libraries (protocol 1). Pairing signs only `meshcore-local-api-pair-v1:<nonce>`, so they need MeshCore Open test build `localapi-test-10` or later, or a `meshcored` that sends the new prefix. Features are used only when `hello.capabilities` lists them. Release notes: `docs/release-notes/app-0.13.0.md`, `bridge-android-0.5.0.md`, `bridge-python-0.6.0.md`.

## First public release (preview)

| Part | Version |
|---|---|
| Tracker firmware (ZephCore `tracker` role) | 0.9.4 |
| Android app | 0.12.0 |
| Android bridge ("Nostr bridge") | 0.4.0 |
| Python bridge (`nostrmesh`) and tools (`libretag`) | 0.5.0 |
| Web app (already published) | 0.3.1 |
| Wire protocol | LT2, sealing v2 |

Release notes: `docs/release-notes/firmware-0.9.4.md`, `app-0.12.0.md`, `bridge-android-0.4.0.md`, `bridge-python-0.5.0.md`.

## Tracker firmware

- **0.9.4**: the first report after a boot is marked, and without a fix it also carries the reset cause and the number of resets in a row.
- **0.9.3**: every wake-up runs a 1.5 s Bluetooth scan for a command advertised by the owner's phone, also the listen-only wake-ups between reports.
- **0.9.2**: `BLE_CONSOLE` command opens the Bluetooth console window remotely.
- **0.9.1**: resets by the owner (button, power-on, USB serial, `reboot`, `dfu`) do not count as a reset loop.
- **0.9.0**: report sealing v2; reports commit to the mailbox key, so bridges only deliver commands signed for that report. Not compatible with apps before 0.11.0.
- **0.8.0**: the console is reachable over Bluetooth for 3 minutes after a boot (sealed frames, private address).
- **0.7.x**: checks (listening for commands between reports on a keyed schedule), report every n-th wake-up, quiet in home zones, near known Bluetooth devices and when parked, an airtime cap, the ESP32-S3 build without GPS with Wi-Fi places, commands over a Bluetooth advert, state written atomically.
- **0.6.x**: home zones, Bluetooth anchors, a report history ring, a BLE survey.
- **0.5.x**: LT2 reports, low battery at 25 %, the locked USB console.

## Android app

- **0.13.0**: MeshCore Local API through the shared `meshcore-local-api` library; needs MeshCore Open `localapi-test-10` or later. Pairing is kept.
- **0.12.0**: when a tag is late the app says why (update mode on the cable, restarts, the phone offline, a report a bridge heard but your relays never got, an empty battery, a schedule mismatch) and offers the action that settles it; one missed report is no longer "silent"; the background Bluetooth scan notices a tag restarting nearby; Track takes a duration as well as an interval; command buttons no longer break words.
- **0.11.x**: the signed mailbox, opening the tag's Bluetooth console remotely, command routing that prefers the phone's Bluetooth advert when the phone is near, map labels that never overlap.
- **0.10.0**: connect to a tag over Bluetooth after a restart.
- **0.9.x**: hourly command checks by default, mailbox entries removed after an acknowledgement.
- **0.8.x**: NIP-17 DMs, secrets wrapped with the Android Keystore, the 10 % battery alert, GPX and CSV export, Wi-Fi places for tags without GPS.
- **0.7.x**: checks and the next check in the UI, sharing a tag, the welcome DM and the tag's Nostr profile, translations (Slovak, Czech, German, Spanish, Dutch).
- **0.6.0**: the Trail Blaze interface: map with a signpost list, tag detail with a timeline, layouts for tablets and foldables.
- **0.5.x**: positions without GPS from heard repeaters, the key backup.

## Bridges

- **Android bridge 0.5.0, Python bridge 0.6.0**: the shared MeshCore Local API libraries, strict capabilities (LoRa preset only with `radio_stats`, region check only with `regions`); need MeshCore Open `localapi-test-10` or later. Pairing is kept.
- **Android bridge 0.4.0, Python bridge 0.5.0**: the signed mailbox (only entries signed for the tag's report are sent), proof of work removed.
- **Android bridge 0.3.x**: an outbox across restarts, a radio watchdog, a heartbeat file, a per-first-hop limit on `#libretag`.
- **Bridge 0.2.x**: LibreTag commands zero-hop when the tag was heard directly, timed entries for checks, parity between the Android and Python bridges.

## Web app

- **0.3.1**: reads the boot flag in firmware 0.9.4 reports.
- **0.3.0**: the signed mailbox.
- **0.2.x**: NIP-17 welcome DM, the 10 % battery alert, GPX and CSV export, Wi-Fi reports.
- **0.1.x**: reports, map, commands and key backup over Nostr, sharing a tag, automatic updates.

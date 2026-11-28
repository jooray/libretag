# Changelog

LibreTag has four parts with their own version numbers. The first public release is a preview of all of them at once. The development history before it is not public; the main steps are summarised below. Details per version: `firmware/README.md` for the firmware, `docs/release-notes/` for each component.

## 0.14.2: the quiet mesh

| Part | Version |
|---|---|
| Tracker firmware | 0.10.2 (includes 0.9.7 and 0.9.8) |
| Android app | 0.14.2 |
| Android bridge ("Nostr bridge") | 0.6.0 |
| Python bridge (`nostrmesh`) and tools (`libretag`) | 0.7.0 |
| Web app | 0.5.0 |

The tag relies on the community repeaters as little as it can. Its regular report is a zero-hop hello that your own node or a nearby Nostr bridge hears; your app confirms it with a short ACK that goes back zero-hop. A flood is the fallback, when a report has to arrive and nobody confirmed it, and it starts in the narrowest scope. Commands arrive in listening slots the app works out. Every part has to be updated together: none of them works with the 0.9 tags or the 0.13 apps (SPEC 3.2, PROTOCOL.md 2.6, 2.7, 3.3 to 3.5, 4.3, `docs/design/quiet-mesh.md`). Also: the app wakes before each expected report and reconnects relays that went quiet; the tag's Track interval runs from wake-up to wake-up; an expired Track or Lost says IDLE in its last report; the Python bridge no longer reboots the node for late answers by default; field-test logs on the tag, the bridges and the app (`libretag fieldlog`, `libretag fieldeval`). Release notes: `docs/release-notes/firmware-0.10.2.md`, `app-0.14.2.md`, `bridge-android-0.6.0.md`, `bridge-python-0.7.0.md`.

## Firmware 0.9.7: reset records, an event log and a watchdog (released with 0.14.2)

| Part | Version |
|---|---|
| Tracker firmware | 0.9.7 |

After a tag went silent for 14 hours with nothing on it to say why, the tracker now writes a record at every boot (raw RESETREAS, cause, n, battery, and the previous run's breadcrumb from RAM the boot keeps: phase, activity, uptime, n, flags; a recorded fatal error with PC, LR and thread) and an event log (quiet, mode, network, still and moving, transmit floor, flood cap, clock, console window, commands, an hourly alive record). An nRF WDT (240 s) is reloaded only until a deadline tied to the main loop's phase, so a tag stuck in one phase resets as `watchdog` instead of staying silent. Fix: a packet the radio gave up on no longer leaves the wake-up waiting in `PH_TX` for good. Console `boots`, `events`, new lines in `status` (`firmware/README.md` 0.9.7). No wire format change. Fix: a press of the user button or the joystick no longer crashes the tracker (the button UI's display redraw was scheduled without the UI ever being set up; `crash: reason=35 pc=0x00000000 thread=sysworkq`). Release notes: `docs/release-notes/firmware-0.9.7.md`.

## Firmware 0.9.6: back across a border without a fix

| Part | Version |
|---|---|
| Tracker firmware | 0.9.6 |

A tag that switched to a neighbouring country's network and came back without a GPS fix (in a bag, a car, indoors) used to stay on the foreign network. Now it remembers the network it was on before its last switch. When a report has no fix and the home network has been silent for 3 listen windows, the report also goes out as copy `j = 1` on that previous network, unless the network is set by hand or the tag is above its daily flood cap. `status` and `network` show the previous network (SPEC 6.4, `firmware/README.md` 0.9.6). Release notes: `docs/release-notes/firmware-0.9.6.md`.

## 0.13.3: share a tag with full access

| Part | Version |
|---|---|
| Android app | 0.13.3 |
| Tracker firmware | 0.9.5 or newer; bundles 0.9.6 |
| Web app | 0.4.0 |

**Share** has a third level, **Full access**, for your own devices. The link also carries the tracker's `usb_key` and `id` (`uk`, `id`; PROTOCOL.md 1), never `K0`, so the other device unlocks the USB console and opens the Bluetooth console like the owner's phone. The app warns before it shows the code: whoever has it can re-key or unpair the tag. On a laptop, `libretag share import` stores the keys in a mode 600 file and `libretag console` (USB) or `tools/blecon.py --tag` (Bluetooth) use them to unlock. Also: **Copy link** in the share sheet, the **Track** button without "1 h", and the firmware update screen translated. Release notes: `docs/release-notes/app-0.13.3.md`.

## 0.13.1: wake a nearby tag over Bluetooth

| Part | Version |
|---|---|
| Android app | 0.13.1 |
| Tracker firmware | 0.9.5 or newer (required) |

**Open Bluetooth remotely** now also sends a wake advert from the phone (manufacturer data 0xFFFF, type 0x26, salt and a tag from the usb_key). It doesn't depend on the report counter, so it reaches a tag whose reports the app missed. Firmware 0.9.5 looks for it in every wake-up scan and opens the console window for 180 s on a match; a salt already taken in this boot is refused (`firmware/README.md` 0.9.5, PROTOCOL.md 3.2). The phone waits for up to two report periods (at least 2 hours), then connects by itself when the tag's console advert shows up. Fixes: "Checking for missed reports…" shows only while the backfill runs, and a failed sync retries; the console scan no longer leaks after `SCAN_FAILED_ALREADY_STARTED`. The app needs tracker firmware 0.9.5 or newer and no longer has code for older firmware; an older tag shows "Update firmware". Release notes: `docs/release-notes/app-0.13.1.md`.

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

- **0.9.6**: without a fix and with the home network silent, a second copy of the report goes to the network the tag was on before its last switch.
- **0.9.5**: the phone's console wake advert opens the Bluetooth console of a tag whose reports the phone missed.
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

- **0.4.0**: parity with the Android app for everything that works over Nostr: share with full access (`uk`, `id`), share on from a shared tag, invite by Nostr DM, a Shared with list; a missed report is tolerated, leaving a place is an event, guard; why a tag is late with All checks; battery estimate; report interval sent as a command; units; mark in Edit; support ask. Area estimate and Wi-Fi lookup stay out: the services allow no requests from a browser.
- **0.3.1**: reads the boot flag in firmware 0.9.4 reports.
- **0.3.0**: the signed mailbox.
- **0.2.x**: NIP-17 welcome DM, the 10 % battery alert, GPX and CSV export, Wi-Fi reports.
- **0.1.x**: reports, map, commands and key backup over Nostr, sharing a tag, automatic updates.

# LibreTag

An open tracker on top of a MeshCore LoRa network: once an hour it sends an encrypted position and listens for commands for a short time. It has no permanent MeshCore identity, and only the owner can see the content.

Current versions (first public release, a preview): tracker firmware 0.9.4 (runs on the Wio Tracker L1; an ESP32-S3 build without GPS runs on the bench), LibreTag app 0.12.0, web app 0.3.1 (`pwa/`), Android bridge 0.4.0, Python bridge 0.5.0, protocol LT2. Changes: `CHANGELOG.md`. What is done and what is open: SPEC.md, "Prototype status". Licences of bundled code: `THIRD_PARTY_NOTICES.md`.

## Install

Everything is on the project's GitHub releases page. Battery life has not been measured yet, so treat runtimes as estimates.

### Android app

- Download `libretag-<version>.apk` from the release, or add the repository to [Obtainium](https://github.com/ImranR98/Obtainium) (turn on pre-releases while releases are marked as such). Zapstore (`app.libretag`) is planned.
- Check the signing certificate before you install: `apksigner verify --print-certs libretag-<version>.apk` must show SHA-256 `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`. The same value is in the app's menu. Updates only install over an APK signed with this certificate.
- To use your own MeshCore node from the phone you need **MeshCore Open with the Local API**. Until the changes are merged upstream (zjs81/meshcore-open pull requests [#619](https://github.com/zjs81/meshcore-open/pull/619), [#628](https://github.com/zjs81/meshcore-open/pull/628), [#670](https://github.com/zjs81/meshcore-open/pull/670)), install the fork build from https://github.com/jooray/meshcore-open/releases/tag/localapi-test-10 (or later; LibreTag 0.13 and the bridges no longer pair with test-9 and older). Without it the app still works over Nostr, through bridges run by others.
- The Local API itself (the loopback WebSocket MeshCore Open and `meshcored` serve) is defined in [MeshCore Local API](https://github.com/jooray/meshcore-local-api). The app, the Android bridge and the Python bridge use its client libraries.

### Tracker firmware: Wio Tracker L1 / L1 Pro

- **UF2 (no tools):** connect the tag over USB and press reset twice quickly. A USB drive appears. Copy `libretag-tracker-<version>-wio_tracker_l1.uf2` onto it; the tag reboots into the new firmware.
- **Serial DFU:** `uvx adafruit-nrfutil dfu serial --package libretag-tracker-<version>-wio_tracker_l1-dfu.zip -p /dev/ttyACM0 -b 115200 --singlebank` (macOS: `/dev/cu.usbmodem*`). The app does the same over the USB cable from the tag's Service screen.
- Check with `version` on the console (`screen /dev/ttyACM0 115200`): it prints the LibreTag version and the ZephCore commit.

### Tracker firmware: Heltec Wireless Stick Lite V3 (ESP32-S3, no GPS, bench only)

`pip install esptool` (5.x), then `esptool --chip esp32s3 -p /dev/ttyUSB0 write-flash 0x0 libretag-tracker-<version>-heltec_wifi_lora32_v3.bin`. The image and offset are given with each release; when building yourself, `west flash` does this for you.

### Bridges

- **Android:** `nostrmesh-bridge-<version>.apk` from the release (same signing certificate), next to MeshCore Open with the Local API. Steps below.
- **Python:** from a checkout with `uv`, as a systemd or launchd service: below, and `docs/nostr-mesh-bridge.md` section 8.11.


- `SPEC.md`: specification, decisions, research, what is still to be found out
- `PROTOCOL.md`: wire format v2 (LT2)
- `firmware/README.md`: pinned ZephCore base and the firmware update procedure
- `host/`: base station, tracker simulator, battery calculator (Python, uv)
- `firmware/lt_core/`: C protocol core for the firmware (ZephCore)
- `host/src/nostrmesh/`: Nostr ↔ MeshCore bridge (`docs/nostr-mesh-bridge.md`)
- `android/`: the LibreTag app; `pwa/`: the web app over Nostr; `android-bridge/`: the Nostr bridge as an Android app
- `docs/guide.html`: the user guide

## Quick start (no hardware)

```sh
cd host
uv sync
uv run pytest
uv run libretag demo        # tracker + base station + 3-hop mesh in memory
uv run libretag energy      # estimated runtime of X1 and Wio Tracker L1 Pro

# report through a pipe (hex on stdio instead of the radio)
export LIBRETAG_DB=/tmp/lt.db
uv run libretag pair auto
uv run libretag sim auto | uv run libretag base
uv run libretag list
```

## With a radio

The base station needs any MeshCore/ZephCore node with KISS modem firmware on a serial port:

```sh
uv run libretag base --kiss /dev/cu.usbmodem1101 --traccar http://localhost:5055
uv run libretag cmd auto track --interval 120 --duration 60   # goes out after the next report; 120 s is the minimum the firmware accepts
```

Until the tracker firmware is finished, a second node in KISS mode can play the tracker:

```sh
uv run libretag sim auto --kiss /dev/cu.usbmodem2101 --listen 60
```

The simulator prints whether a command arrived and how long it took. This is how to measure latency on a real network.

## C core

```sh
cd host && uv run libretag vectors && python3 ../firmware/lt_core/test/gen_vectors.py   # lt2.json, then the C headers
python3 ../tools/check_vectors.py      # every copy of a vector file equals its source
make -C ../firmware/lt_core/test test   # lt_core + lt_nostr (libsecp256k1 is in firmware/third_party)
```

## Tracker firmware (ZephCore, Wio Tracker L1 / L1 Pro)

Version 0.9.4. Release files: the UF2 (drive) and the serial DFU package, see "Install" above. Changes per version are in `firmware/README.md`.

Build (workspace `firmware/zephcore`; the changes against ZephCore are a series of patches in `firmware/zephcore-patches/`, the base is pinned in `firmware/zephcore.lock`; how the base changes and what to check after each change is in `firmware/README.md`):

```sh
cd firmware/zephcore && . ../.venv/bin/activate
export ZEPHYR_SDK_INSTALL_DIR=~/zephyr-sdk-1.0.1
west build -b wio_tracker_l1 zephcore -d build-tracker --pristine -- -DEXTRA_CONF_FILE="boards/common/tracker.conf"
```

The tracker console is on USB (`screen /dev/cu.usbmodem* 115200`), command `help`. After pairing with the app the console is **locked**: the key has left the tracker and it refuses settings (`ERR locked`) until the app unlocks it itself (`challenge` / `auth`). A tracker locked by another phone can be reset with the `unpair` command.

| Command | What it does |
|---|---|
| `status`, `version` | state (key only before locking), counter, radio, battery; firmware version and ZephCore commit |
| `lock`, `unpair`, `challenge`, `auth <hex>` | lock the USB console after pairing; reset pairing (new key); unlock for one connection (the app does this) |
| `network auto\|manual` | **default auto:** after every GPS fix the tracker determines the country and sets its radio, path hash size and region (SK: 869.618 / 62.5 / SF7 / CR5, 2 B, region `sk`). Near a border (3 km) it sends a second copy to the neighbouring network, and only when the home network was silent. Outside known countries it stays on the last network |
| `where <lat> <lon>` | shows which country and networks the tracker would choose at that location |
| `radio <MHz> <bw kHz> <sf> <cr>`, `pathhash 1\|2\|3`, `region <name>\|none` | manual network setup (switches to `network manual`) |
| `interval <min>`, `listen <s>`, `fixto <s>`, `check <s>` | wake-up interval, RX window after a report, GPS wait, listening at checks (default 30 s, longer on slower presets) |
| `track <interval_s> <minutes>`, `idle` | TRACK (at least every 120 s), back to IDLE |
| `zone ...`, `anchor ...`, `still ...`, `heartbeat ...`, `quiet` | home zones, known Bluetooth devices, parked quiet, heartbeats, the current quiet state (`PROTOCOL.md` 6.1) |
| `report every <n>` | report at every n-th wake-up and only listen for commands at the others (default 1) |
| `report`, `test <n> <s>` | send a report now; a series of test reports |
| `discover`, `meshscan <s>`, `blescan <s>`, `noise` | nearby repeaters, passive listening, Bluetooth, radio noise (diagnostics for positioning without GPS) |
| `gps`, `buzz <s>` | satellites and GPS state; buzzer |
| `selftest` | Nostr crypto, GPS, Wi-Fi scan and owner check, also on a locked console, without keys or position |
| `blecmd`, `blecmd adv <hex>` | the command advert the next wake-up would take from the owner's phone; feed a payload for a bench test |
| `wzone ...`, `wifiscan`, `sleep` | ESP32 only: Wi-Fi zones, raw Wi-Fi scans, light sleep (`docs/design/fw-wifi-esp32.md`) |
| `transport channel\|stealth` | the `#libretag` channel (for the app) or stealth (for a KISS base station) |
| `dfu [serial\|ble\|uf2]`, `reboot` | bootloader (serial DFU by default, BLE OTA or the UF2 drive) / restart |

`status` also prints a `txcap` line: floods in the last 24 h against the cap of 200, the low-battery alert state and the transmit voltage floor. Since 0.7.6 TRACK and LOST report at most every 120 s, above 200 floods a day the tracker reports at most hourly (command answers still go out), LOST halves its rate after 2 h at one place, and below 3250 mV it does not transmit (`PROTOCOL.md` 2.5).

### ESP32-S3 without GPS

The same role builds for the Heltec Wireless Stick Lite V3 (ESP32-S3, SX1262, no GPS). It knows it is at home from the Wi-Fi networks it hears: `wzone snap <name>` stores a place, and the tag stays quiet inside it. Its reports carry two full BSSIDs, which the app can turn into a position through a lookup service. It runs on the bench; leaving a zone has only been simulated and the battery was not tested on a cell. Build, commands and open questions: `firmware/README.md` and `docs/design/fw-wifi-esp32.md`.

## Android app

Version 0.13.0, signed with the certificate SHA-256 `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d` (see "Install"). Procedure:

1. In MeshCore Open (the jooray/meshcore-open fork with the Local API) turn on the Local API and connect to your companion node.
2. Open LibreTag. MeshCore Open asks whether to allow it (the fingerprint is in the LibreTag menu), then whether to add the `#libretag` channel.
3. **Pairing (Add tag, four steps: plug in, name, where it rests, done):** connect the tracker to the phone with a USB cable (OTG). Android opens LibreTag, the app reads the key, sets the channel, interval, network to auto and your Nostr npub, and then **locks** the tracker: the USB console no longer gives the key out, it exists only in the app. In the third step you can add the place where the tag usually rests. Later settings over USB are unlocked by the app itself (challenge/auth). A tracker locked by another phone can be reset ("Reset pairing" / `unpair`). The app data is excluded from backup and from device-to-device transfer; to move to a new phone use the key backup (menu "Backup": a 12-word recovery phrase and a copy encrypted to your npub on Nostr relays). The tracker has USB ID `2fe3:0007`.
4. After the first report the tracker appears in the list, and tapping it opens a map (OpenStreetMap) with the position, the next report time and a timeline of its history.
5. **Commands:** "Lost" (a report every 10 min for 24 h, survives a restart), "Track 1 h" (pick every 2, 5, 10, 15 or 30 min; 5 is preselected), "Beep", and "Stop Lost mode" / "Stop tracking" while one runs. "Bluetooth scan" is under Known devices. The tracker carries them out at its next report or check (otherwise it sleeps); the app says when the command goes out and when the tag confirmed it. The app sends them from its own radio and also puts them in the Nostr mailbox (kind 4244), so any bridge can deliver them too. For a tag nearby with known devices (firmware 0.7.7 or newer) the phone also advertises the command over Bluetooth (`PROTOCOL.md` 3.1).
6. **Tag settings** (one screen): how often it reports when away, how often it listens for commands between reports (checks), when it stays quiet (places, while your devices are near, when it has not moved), and what to tell you (not heard when expected, low battery).
7. **Places:** GPS places drawn on the map and, for tags without GPS, Wi-Fi places snapped with the tag on the cable at the place itself. The tag stays quiet inside a place and reports when it leaves. Each place has its own switches for a notification when the tag leaves and when it arrives. These are notifications, not alarms.
8. **Alarms and alerts:** only "Guard" is an alarm (the tag moved more than 300 m from the guarded spot). Lost mode always notifies. Per tag: "not heard when expected" and low battery (from 25 %: a notification and a Nostr DM to the owner from the tracker's own key, sent by the app; below 10 % the tracker sends the DM itself over the mesh). The menu "Tracker's Nostr contact" shows its npub, and the app sends a welcome DM at pairing.
9. **Position without GPS:** a report carries up to 5 heard repeaters and the last GPS fix with its time. From these and from the downloaded repeater map (menu "Repeater map", ~60 MB one time, after 30 days it suggests a refresh) the app draws a 68 % and a 95 % area. It is an area, not a point. Reports with Wi-Fi networks (ESP32 tags) can be looked up through BeaconDB, the GrapheneOS proxy (Apple data) or Apple directly. In Settings, "Position from Wi-Fi" picks the service and when to look up: only on tap, in Lost mode, or every report without GPS. Lookups are cached and rate-limited, and reports from your own places are not looked up.
10. Reports heard by any bridge are also fetched by the app from Nostr relays (kind 4243), so you can see the tracker even outside the range of your own nodes.

The web app (`pwa/`, published at `cypherpunk.today/libretag/app/`) works over Nostr only: it restores tags from the backup, shows them on a map and sends or cancels commands. It has no USB, no area estimate and no Wi-Fi lookup.

Build: `cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew lint testDebugUnitTest assembleRelease`

### Privacy and security

LT2: the report is encrypted in SIV mode (a repeated counter reveals nothing) and has a rotating 4-byte tag. The keys for reading and for commands are separate, and a ratchet advances them every 64 reports, so a stolen tracker does not reveal old reports. After pairing the key leaves the tracker (USB lock). It has to be said openly, though: the `#libretag` channel is public, and a radio observer sees that a LibreTag report was sent and roughly from where (the first repeater in the path). They cannot see which tracker it was or what the report contains. Details are in `PROTOCOL.md`.

## Nostr ↔ MeshCore bridge (`nostrmesh`)

Signed Nostr events in a single MeshCore channel frame (`#nostr`, data_type `0xFF4E`) plus Roadbeacon/Roadstr frames (`#roadstr`, `0x0150`). The design and status are in `docs/nostr-mesh-bridge.md`, the vectors in `docs/vectors/nostr-frames-v1.json`.

The bridge needs its **own** companion node on USB (it pulls the message queue from it). The mesh → relays direction is on, the relays → mesh direction is off by default.

```sh
cd host
cp bridge.example.toml bridge.toml        # port, relays, optionally [downlink] and [registry]
uv run nostrmesh listen -p /dev/cu.usbmodem1101 --add-channels   # what can be heard on the radio
uv run nostrmesh run -c bridge.toml

# a test device (second node): key, DM to the owner, note
uv run nostrmesh keygen -o device.nsec
uv run nostrmesh dm --key device.nsec --to npub1… -p /dev/cu.usbmodem2101 "sensor1 61 C"
uv run nostrmesh note --key device.nsec -p /dev/cu.usbmodem2101 "ahoj z mesh"
uv run nostrmesh decode <hex>    # / encode event.json / vectors
```

`dm4.ref` (a DM up to 63 B in a single frame) needs the key registry in the bridge (`devices.example.toml`). Longer DMs are split into fragments, at most 4.

### Android app "Nostr bridge" (`android-bridge/`)

Version 0.5.0. It does what the Python bridge does: LT2 reports and BLE packets go up as kind 4243, the command mailbox handles timed entries (0.7 checks) and cancelling, low-battery DMs (`dm4x`) go up from any tracker, and DMs also reach the recipient's inbox relays. Since 0.3.2 it also keeps unpublished events in an outbox across restarts, reconnects the radio after 30 min of silence, writes an hourly heartbeat (`status.json`) and limits `#libretag` uplink per first hop (10 per minute). The bridge runs directly on the phone through MeshCore Open (the radio stays in MeshCore Open, chat keeps working):

1. In MeshCore Open turn on the Local API, open the Nostr bridge and turn on the switch. MeshCore Open asks whether to allow it and whether to add `#nostr` (and `#roadstr`).
2. Radio → Nostr works right away: Roadstr reports, messages from registered devices, and LibreTag reports from `#libretag`, which the bridge publishes as kind 4243 (it decrypts nothing and does not know the tracker's key; it signs with a random key and does not publish what is already on the relays).
3. Nostr → radio: commands from kind 4244 for trackers (sent once after a report is caught, and the bridge closest to the tracker transmits) and DMs for registered devices (off by default; in Settings add a device: the tracker's npub and your npub).

Build: `cd android-bridge && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew lint testDebugUnitTest assembleRelease`

Python bridge through MeshCore Open (e.g. on a PC with `adb forward tcp:8787 tcp:8787`):

```sh
uv run nostrmesh pairing                                  # the fingerprint MeshCore Open shows
uv run nostrmesh listen --adapter local_api               # [radio] adapter = "local_api" in bridge.toml
```

With a KISS node (`adapter = "kiss"`) DMs to a device go direct along the path its last message arrived on.

To run the Python bridge as a service, `host/deploy/` has a systemd user unit and a launchd template (`docs/nostr-mesh-bridge.md` 8.11). The airtime budget assumes the live preset SF7 / 62.5 kHz / CR 4/5 and uses the node's own preset when the adapter reports it.

### Tracker and Nostr

The tracker console (`help` shows everything), Nostr settings:

| Command | What it does |
|---|---|
| `nostr` | the tracker's npub, owner, settings |
| `owner <npub>` | owner: the tracker accepts DMs only from them and sends alerts only to them |

Every wake-up sends **one packet**: an encrypted report on `#libretag` (near a border possibly also a copy to the neighbouring network). The public report in the firmware was removed: LT2 reports are published by bridges as kind 4243 (`PROTOCOL.md` 4). The tracker wakes once an hour (±10 %), waits for GPS at most 90 s, and listens for 60 s after sending (`listen`). While quiet (home zone, owner nearby, parked) it reports nothing, but every wake-up is a check: it listens for a command for 30 s (`check`, 0–180), longer on slower radio presets and for as long as the GPS searches. With `report every <n>` it reports only at every n-th wake-up and only listens at the others, without GPS, which saves battery and mesh traffic while a command still gets through within one interval. On low battery the app sends the DM to the owner, below 10 % the tracker does it itself (`PROTOCOL.md` 5).

The tracker does not accept DMs: it is controlled from the app (commands go after its report from the app's own radio and through the Nostr mailbox, PROTOCOL.md 4). It sends a DM itself only when the battery is below 10 %; at 25 % the app sends it.

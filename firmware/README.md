# Tracker firmware

The tracker is the `tracker` role in [ZephCore](https://github.com/liquidraver/ZephCore) (MeshCore on Zephyr). The protocol core is in `lt_core/` (portable C, host tests in `lt_core/test`); everything that depends on ZephCore is a series of patches in `zephcore-patches/`.

## What we build on

- `zephcore.lock`: the upstream URL and the exact ZephCore commit. Zephyr and all modules are pinned through `zephcore/west.yml` in that same commit, so this one line determines the whole tree.
- `VERSION`: the LibreTag firmware version. The tracker prints it with the `version` command and in `status`, together with the ZephCore commit.
- The clone `firmware/zephcore/` is in `.gitignore`. Our changes are in it on the `libretag` branch as commits on top of the locked commit; `zephcore-patches/` is their export (`git format-patch`).

## Procedure

```sh
firmware/tools/zephcore.sh setup     # clone at the locked commit + series + west update
firmware/tools/zephcore.sh status    # base, our commits, uncommitted changes, how far ahead upstream is

# build (Wio Tracker L1)
cd firmware/zephcore && source ../.venv/bin/activate && export ZEPHYR_SDK_INSTALL_DIR=$HOME/zephyr-sdk-1.0.1
west build -b wio_tracker_l1 zephcore -d build-tracker -- -DEXTRA_CONF_FILE="boards/common/tracker.conf"

# after a change: commit on the libretag branch in the clone, then export to the repo
git -C firmware/zephcore commit -am "..."
firmware/tools/zephcore.sh export
```

We split the commits in the clone by topic (radio, GPS, USB, tracker role), so that rebase conflicts are resolved in small pieces.

## 0.9.5: the phone's console wake advert

- A new advert from the phone (PROTOCOL.md 3.2): manufacturer data, company `0xFFFF`, 13 B `0x26 || salt[6] || HMAC(usb_key, "LT2-bcon-wake" || salt)[0:6]`. Every scan that looks for a command advert (presence, LOST, the 0.9.3 command scan) also looks for it. A match opens the Bluetooth console window for 180 s, as a `BLE_CONSOLE` command with argument 0 does: a locked tag, above the transmit floor, not closed after 5 failed handshakes. `blecon boot off` and the resets in a row do not apply. If the window is already open, the advert is ignored. The tag sends no report for it and needs no `n`. Patch 0041; `lt_bcon_wake`, `lt_bcon_wake_match`, `lt_bcon_wake_find` in lt_core, `bcon_wake*` in the Python reference, vectors `bcon_wake` in `lt2.json`.
- Why: a field failure on 2026-11-22. The tag had been out of mesh range for about 42 hours and the owner's node was down, so no report was heard. Next to the tag, the owner asked the app to open its console. The `BLE_CONSOLE` command advert is sealed for the last report the phone heard. The tag's `n` had moved on, probably into a new epoch whose `C_e` it had erased, so its filter never matched. Keeping old `n` on the tag would not help across epochs. `usb_key` stays on the tag as long as it is paired.
- Cost: the scan callback walks the AD structures and compares company, length and type, as for the command advert. The HMAC runs in the main loop, only for a `0x26` advert whose salt was not taken in this boot, and at most 16 times a scan. The last 8 salts taken stay in RAM, so a replay in the same boot is refused. After a reboot a replay opens a window that still needs the `usb_key` handshake.
- Console: `blecmd` adds `wake_seen=` (wake adverts checked) and `wake_taken=` (windows opened). `blecmd adv <26 hex>` feeds a wake payload to the next scan (unlocked console).
- Sizes: Wio Tracker L1 flash 450704 B (65 %), RAM 110832 B; ESP32-S3 DRAM 319416 B of 399108 B.
- Flashed on a Wio Tracker L1 with a locked console on 2026-11-22: a 1200 baud touch put it into the UF2 bootloader (`INFO_UF2.TXT`: UF2 Bootloader 0.9.2, Seeed TRACKER L1, S140 7.3.0), and copying the UF2 to the drive kept the pairing (`version` 0.9.5, `owner=set`, the same LIBRETAG ID, still locked). The UF2 blocks cover 0x27000 to 0x95100, far below the key pages at 0xD0000. **Needs a bench check** of the radio path with the app: the wake advert reaching a scan and the window opening.

## 0.9.4: the boot report

- The first report after a boot sets `BOOT` (byte 2 bit 2; the mode is now bits 0–1), and so does its history record (PROTOCOL.md 2). Without a fix, byte 14 carries the reset cause and the resets in a row at boot (`lt_boot_info(lt_boot_cause(boot_rst), boot_resets)`) in place of the last fix's accuracy. With a fix, or in the Wi-Fi seed2 layout of a tag that never had one, only the bit is set. The cause codes follow the `last_reset=` words of `status`: 0 none, 1 pin, 2 power, 3 soft, 4 debug, 5 brownout, 6 watchdog, 7 crash. A silent history record (`j = 3`) does not use the bit up. Patch 0040; lt_core, the Python reference, the PWA and the app decode it, with vectors `boot_reports`.
- Why: the app can tell a restart from lost reports, and a reset loop from one restart, without the cable (`docs/design/tag-diagnosis.md` 8.1).
- Sizes: Wio Tracker L1 flash 449820 B (65 %), RAM 110768 B; ESP32-S3 DRAM 319328 B of 399108 B.
- **Needs a bench check.** Built for both boards (host tests of lt_core, the reference and the decoders pass); nothing was flashed or run on hardware. To check: a boot report after a reset button press (cause pin, 1), after `reboot` (soft, 1), and a brown-out or watchdog loop (resets counting up), opened by the app and by `host` with the bench keys.

## 0.9.3: the phone's command advert at every wake-up

- Every wake-up now looks for the owner's command advert (PROTOCOL.md 3.1). A wake-up with a presence scan (IDLE with known devices) or a LOST scan uses that scan, as before. Every other wake-up runs a command scan: passive, 100 % duty, 1.5 s, started with the wake-up. That covers the listen-only wake-ups between reports (`report_every > 1`), checks and reports of a tag without known devices, TRACK, and LOST when its scan is not due. The scan runs alongside the LoRa window or the GPS search, so the wake-up does not get longer. On a board without GPS the decision waits for the 1.5 s. The scan keeps no device table and only compares the 4 B tag of this wake-up's `(n, k)`.
- A match at a listen-only wake-up is handled like a mesh command there: the tag looks at once (GPS, Wi-Fi, presence) and sends report `n + 1` with `CMD_ACK`.
- ESP32-S3: the SoC does not light-sleep during the 1.5 s (sleep lock). A listen-only wake-up runs no Wi-Fi scan, so Wi-Fi and Bluetooth do not overlap there. At a wake-up that looks, the Wi-Fi scan comes first, then the Bluetooth scan, as for the presence scan since 0.7.8.
- Why 1.5 s: the app advertises every 250 ms (Android `INTERVAL_MEDIUM`, plus the 0 to 10 ms random delay), so 1.5 s holds 5 or 6 advertising events. Bench, 2026-11-19, Wio Tracker L1, `blescan 10 passive full` (the same scan parameters, 60 ms interval and window) three times while the Heltec advertised its console window (connectable, 100 to 150 ms): 64, 60 and 60 adverts in 10 s, out of about 67 to 77 sent, so about 80 % of advertising events arrive. With 5 events the chance of missing all of them is 0.2^5, about 0.03 %. A 1 s scan would hold 3 or 4 events (about 1 % missed). The phone side was not measured on air (see below).
- Battery, per hourly wake-up (duty 1.5 s per hour, 0.04 %): nRF52840 about 6 mA for 1.5 s = 2.5 µAh, so 0.06 mAh a day at 24 wake-ups. That is 0.3 % of the Wio Tracker L1 Pro estimate (17 mAh a day with a report every 3 h, `uv run --project host libretag energy --report-every`, column `ble`). ESP32-S3: Bluetooth RX with the CPU awake about 80 to 90 mA (datasheet) against about 6 mA for the light-sleeping SoC with the LoRa receiver on, so about 33 µAh a wake-up, 0.8 mAh a day. Against the Heltec's 12 mAh a day of sleep alone that is about 6 %; current was not measured.
- Console: `blecmd` adds `scans=` (command scans since boot) and `first_ms=` (scan start to the last advert taken), and `scan=check` for a wake-up that uses the command scan. `blescan [s] [passive] [full]`: `full` scans at 100 % duty like the wake-up scans. `blecmd adv <hex>` now feeds any wake-up's scan.
- App 0.11.3 counts on the advert at the very next listening wake-up when the phone is near (`mesh/CommandRoute.kt`).
- Bench, 2026-11-19, Wio Tracker L1 (2 anchors, `report_every 3`, the interval set to 15 min for the test and back to 60 after): a `BLE_CONSOLE` advert (60 s window) sealed by the Python reference for report 2482, check 1, fed with `blecmd adv`. Check 1 was a listen-only wake-up; its command scan took the advert 6 ms after it started (`scan=check scans=1 seen=1 taken=1 first_ms=6`), the console window opened (`blecon state=open`), the tag looked and sent report 2483 with `CMD_ACK` and the command's `cmd_seq` (history record opened with the reference). `blecmd adv` goes through the same filter as an advert on air but not through the radio. The radio path from the phone was not tested (the phone was not attached), and the Heltec was flashed and answers `version` 0.9.3 but its console is locked, so its command scan was not run.
- Sizes: Wio Tracker L1 flash 449.7 KB of 676 KB, RAM 110.8 KB of 256 KB; ESP32-S3 flash 1.0 MB, DRAM 319 KB of 399 KB.

## 0.9.2: opening the Bluetooth console by a command

- Mesh command `BLE_CONSOLE` (opcode `0x06`, PROTOCOL.md 3): the wake-up that takes it (the window after a report, a check, or a command advert from the owner's phone, 3.1) opens the console window as after a boot. Argument u8 in 10 s (0 = 180 s, clamped to 30…600 s, `lt_bcon_cmd_window_s`, vectors `ble_console_cmd`). Locked tags only, above the transmit floor; `blecon boot off` and the reset count do not apply, the 5-failure lock does. Acknowledged with `CMD_ACK` in the next report as usual.
- Where the tag hears it: in every listening window (after a report, at each keyed check). It takes the Bluetooth advert form only in a presence or LOST scan, so not at a listen-only wake-up (between reports with `report_every > 1`); with hourly listening and a report every third wake-up that is one wake-up in three, and only with known devices.
- Bench, 2026-11-18, Wio Tracker L1: rebooted, `blecon close`, then signed untimed mailbox entries with `BLE_CONSOLE` (600 s) for the next counters (the Python reference, keys from the bench file). The test bridge locked the key on report 2416, heard it directly and sent the command zero-hop; the tag opened the window in that report's listening window (`blecon state=open 593s`), and `host/tools/blecon.py run` connected over Bluetooth (`version` 0.9.2, a session). The Heltec was flashed and answers `version` 0.9.2; its command path was not run on the bench. The app path was not tried on the phone (it was locked).
- Sizes: Wio Tracker L1 flash 449.1 KB of 676 KB, RAM 110.8 KB of 256 KB (as 0.9.1).

## 0.9.1: a reboot by the owner always opens the Bluetooth window

- `boot_resets` (the reset-loop count behind the Bluetooth window rule `≤ 3` and the first-report backoff 2, 4, 8 ... min) now counts only boots the owner did not cause. The reset cause comes from ZephCore's `boot_info` (RESETREAS on the nRF52, the ROM reset reason on the ESP32, the crash record). Reset button, power-on (on the ESP32 also EN through the CP2102, so every serial port open), debugger, `reboot` and `dfu` set it to 1; brown-out, watchdog, lockup, panic, a crash and an unannounced software reset add one. An nRF52 boot without any flag (power-on and brown-out look the same there) counts as deliberate at 3350 mV or more, on USB or without a battery reading. `lt_reset_deliberate()`, `lt_boot_resets_next()` in lt_core, vectors `boot_resets` in `lt2.json`, details in `docs/design/ble-console.md` 3.
- `status`: the `txcap` line has `last_reset=pin|power|soft|debug|brownout|watchdog|crash|none`; the boot log says `reset <cause>`.
- Bench, 2026-11-18: before the fix the Heltec gave `boot_resets` 5 and no advert after a flash, a reboot and a few port opens. With 0.9.1 the advert was there after a flash and a port open in a row, and after one more port open the app (0.11.1) connected over Bluetooth: `status` showed `resets=1 last_reset=power boot_delay=30s`, history sync, `wzone list` and the deny list (`dfu`: `ERR ble: use the cable`) worked, and hourly listening written from Tag settings read back as `interval=60min report_every=3`.
- Sizes: Wio Tracker L1 flash 449.0 KB of 676 KB, RAM 110.8 KB of 256 KB; ESP32-S3 flash 1.0 MB, DRAM 319 KB of 399 KB.

## 0.9.0: reports commit to the mailbox key

- Copies 0 and 1 of a report use sealing v2 (PROTOCOL.md 2): no type byte, a 6 B mac, and a 3 B `c` that commits to the mailbox key `pk_n` of the report. Bridges send only mailbox entries signed with that key (PROTOCOL.md 4.2). History records, Wi-Fi and BLE packets keep the plain seal. The report is still 45 B on air.
- The tracker computes one public key per sealed copy (`lt_mailbox_sk`, `lt_secp_pubkey`). `selftest` prints `pk=ok pk_us=<one key on a fixed public test key> pk_last_us=<the last report's>`. Not yet measured on hardware (the tags were not connected when 0.9.0 was built).
- A tracker build needs `CONFIG_LIBRETAG_NOSTR` (libsecp256k1). Without it the build stops with an error.
- Apps before 0.11.0 and the PWA before 0.3.0 cannot open these reports, and bridges before 0.5.0 send no commands for them.
- Sizes: Wio Tracker L1 flash 448.7 KB of 676 KB, RAM 110.8 KB of 256 KB (about the same as 0.8.0); ESP32-S3 flash 1.0 MB, DRAM 319 KB of 399 KB.

## 0.8.0: console over Bluetooth after a boot

- A paired tag listens on Bluetooth for 3 minutes after a boot (`docs/design/ble-console.md`, PROTOCOL.md 6.2): a connectable advert from a private address (`CONFIG_BT_PRIVACY` now in `tracker.conf`; the Device Information Service is off), a GATT service with two characteristics, a handshake with `usb_key` and sealed frames (`lt_bcon_*` in lt_core). The console is the same dispatcher; output goes to the console that sent the last line (patch 0034, no behaviour change on USB). No window when switched off (`blecon boot off`), below the transmit floor or after more than 3 resets in a row (since 0.9.1 only faults count, see above). On the ESP32 the window holds the sleep lock.
- `blecon [boot on|off | open [s] | close]` (unlocked), a `blecon` line in `status`. Over Bluetooth `dfu`, `key <hex>`, `newkey`, `lock`, `unpair`, `challenge`, `auth`, `blepair` answer `ERR ble: use the cable`.
- Bench client: `uv run --project host --with bleak python host/tools/blecon.py scan|run|bad-proof|hold|replay` (key from a file, never printed).
- Bench, 2026-11-18, both boards (locked, owner's keys unknown to the bench): the advert is there after a reboot (14 B, `left` counting down from 180 s), a second reboot gives a new private address and a new salt, HELLO arrives when notifications are enabled, a connection that stays silent is dropped after 10 s, a wrong proof is answered by a disconnect, after 5 wrong proofs the advert is gone until the next boot, the window closes after 180 s. The authenticated session (status, settings, Wi-Fi zones, history, deny list) needs the owner's key and is to be checked through the app.
- Building from a second checkout: clone ZephCore into `firmware/zephcore`, apply the series, symlink `zephyr/` and `modules/` from the main workspace, and run `west build` from the main workspace with the app directory of the clone as the source (the ESP32 pitfall below does not bite that way).
- Sizes: Wio Tracker L1 flash 448 KB of 676 KB (+6.7 KB), RAM 110.7 KB of 256 KB (+1.2 KB); ESP32-S3 flash 1.0 MB, DRAM 319 KB of 399 KB.

## 0.7.8: fixes from pairing the Heltec stick

- A reboot inside a Wi-Fi zone sends a quiet boot report: the zone of the last decision survives in the state and is matched with the stay threshold. The Wi-Fi scan runs before the Bluetooth presence scan, and an empty scan is repeated once.
- `version` and the new `selftest` (both on a locked console) print `selftest nostr=… gps=… nmea=… wifi=… owner=set|none uptime=…`, without keys or position.
- `owner` prints its `owner <npub>` line even when the Nostr self-test failed. After `unpair`, `newkey` or `key` the npub follows the new key without a reboot.
- A GPS search that is still running when ZephCore's boot probe finds no GPS ends at once.
- Details and the bench result: `docs/design/fw-wifi-esp32.md`. Sizes: Wio Tracker L1 flash 441 KB, RAM 109 KB; ESP32-S3 DRAM 318 KB of 399 KB.

## 0.7.7: commands over Bluetooth

- A command the owner's phone advertises (manufacturer data `0xFFFF`, 21 B, PROTOCOL.md 3.1) is taken during the presence scan (IDLE with anchors) or the LOST scan of a wake-up. The scan callback compares a 4 B tag, `xc(n_report, k)` for the check of this wake-up or `k = 0xFFFF` otherwise. Only a match is opened (`lt_ble_command_open`, the same MAC check as a mesh command). It is then taken like a command at a check: report `n + 1` with `CMD_ACK`.
- Radio time: after an anchor answers, the presence scan runs on for at most 2 s, waiting for the phone's command advert. On boards without GPS the presence scan gets 3 s of its own after the Wi-Fi scan when anchors exist. Nothing else changes.
- Console: `blecmd` (what the next wake-up would take), `blecmd adv <hex>` (feed a payload into the next wake-up's scan, for the bench).
- Bench, 2026-11-17, Heltec stick (quiet in a Wi-Fi zone, one dummy anchor): `blecmd` printed the same tag as the host reference for `(n, k = 0xFFFF)` and for the next check `(n, 1)`. A `BUZZ` payload sealed by `libretag.lt2.ble_command` for `(623, 1)` and fed with `blecmd adv` was taken (`seen=1 taken=1`). Report 624 went out with `CMD_ACK` and `cmd_seq` 78 & 15, and the history marked it `check`. The radio path (a phone's advert received by the scan) was not tested, because nothing on the bench advertises manufacturer data. `blescan` on the stick lists the adverts around it as before. (Correction in 0.7.8: ZephCore's boot probe marks this board's GPS absent 10 s after power-on, so its wake-ups do take the no-GPS path.)
- Sizes: Wio Tracker L1 flash 441 KB of 676 KB, RAM 109 KB of 256 KB; ESP32-S3 flash 1.0 MB, DRAM 318 KB of 399 KB.
- Building the ESP32 from a second west workspace whose `zephyr/` is a symlink fails to link (`l32r: literal placed after use` in `xtensa_asm2_util.S`, the Xtensa linker script matches library names that then contain `..__..`). Run `west build` from the workspace that holds `zephyr/` and pass the app directory as the source.

## What the tracker role adds (0.7.6)

- State and counter (0.7.6): the state is written to `state.tmp` and renamed over `state`, with a CRC in an extension that 0.7.5 ignores. The key page also stores an upper bound of the counter. At boot the counter is the largest of the state, that bound and the key epoch; a lost state moves it at most to the next epoch. Both are saved before a counter goes on air.
- Battery (0.7.6): no transmission below 3250 mV (again from 3350 mV or on USB). The first report after a boot comes after 30 s, and after resets in a row after 2, 4, 8 ... minutes, at most an hour. Below 10 % the tracker sends one report (even while quiet) and the low-battery DM, once per crossing; the flags are in the state and are armed again at 20 %.
- Limits (0.7.6): TRACK and LOST at least every 120 s; at most 200 floods in a rolling 24 h, above that `max(IDLE interval, 1 h)` until the count drops (command answers still go out); LOST doubles its interval after 2 h while the fixes stay within 100 m.
- `dfu [serial|ble|uf2]` (0.7.6, unlocked console): prints `dfu: rebooting to <mode>` and resets into the bootloader's serial DFU (`0x4E`, default), BLE OTA (`0xA8`) or UF2 drive (`0x57`). `status` has a `txcap ...` line. Details: `docs/design/fw-0.7.6.md`.
- Sizes on the Wio Tracker L1 (0.7.6): flash 438 KB of 676 KB, RAM 109 KB of 256 KB.

## Earlier (0.7.5)

- Fixes (0.7.5): `blescan` printed garbage rows (empty table slots as `00:00:00:00:00:00`) when a device sent more than 16 bytes of maker data, because the hex buffer of the `D` line was too small and overran the sort index. It now fits all 24 bytes. `anchor del` and `zone del` reject a negative index (it wrote before the array), `anchor list` cannot overrun its buffer on a damaged state file, and zone and anchor counts are clamped when the state is loaded.
- Checks listen 30 s by default again (0.7.3, `check` 0–180). The receiver also stays on for the whole GPS search, so a command that crosses many repeaters still gets in while the tracker looks for a fix. A state that already has a `check` value keeps it; only a migrated older state gets the new default.
- Window by preset (0.7.3, factor since 0.7.4): `check` is meant for SF7 / 62.5 kHz / CR 4/5. On a slower preset the tracker listens `check × (0.4 + 0.6 r)`, `r` the airtime ratio of a 35 B command packet (`lt_check_window_s`), because the measured flood delay per hop grows less than airtime (SF8/CR8: about ×2 at p90 for `r` = 2.6, `docs/research/flood-latency.md`). 30 s becomes 59 s on SF8 / CR 4/8 (EU/UK Narrow, Switzerland), at most 180 s and never less than `check`. `status` and `quiet` show the window in use.
- Report every n-th wake-up (0.7.3): `report every <n>` (1–24, default 1) or `SET_IDLE` byte 8. In IDLE the tracker still wakes every `interval` on the keyed schedule, but only every n-th wake-up looks (GPS, Bluetooth scan) and decides; the others only listen for a command and go back to sleep without transmitting. A command there makes the tracker look and send report `n + 1` at once. The report's `next_in` is the time of wake-up n. Quiet rules apply at the wake-ups that look. Kept in the v7 state (a former padding byte, 0 = 1).
- Checks listen longer (0.7.2): `check` defaulted to 60 s (0–180), because a command flooded from the owner's own node far away crosses many repeaters and can take a minute or more. When the GPS search or the Bluetooth scan of that wake-up runs past the window, the receiver stays on until they end (the MCU is awake anyway) and a command is still taken. The decision waits for whichever of the two ends later.
- Discover (0.7.1): the tracker sends the zero-hop discover alongside the GPS search only when the previous wake-up had no fix. In LOST it no longer goes out at every wake-up. If the search ends without a fix and no discover went out in this wake-up, the tracker sends one then and waits 15 s for the answers before it reports. With a fix, LOST sends none. A quiet tracker sends nothing.
- Checks (0.7): while quiet in IDLE, every wake-up after report `n` falls at a time the app computes from `C_e(n)` (keyed ±10 % jitter, `lt_check_offset`); the receiver is on for `check` seconds plus a clock-drift guard, alongside the GPS search and the Bluetooth scan. A command sealed for check `k` is executed and report `n + 1` goes out in the same wake-up. PROTOCOL.md 2.1, 3.
- Parked quiet (0.7): after `still_after` wake-ups at the same place (100 m) the tracker reports once with `QUIET` and the parked bit, then only every `still_hb` hours. Replaces the 0.5 interval doubling (`still <max_min>`).
- Settings: `check <s>`, `still <after> <h>` / `still off`, `heartbeat zone|owner <h>`; radio `SET_IDLE` carries `check_s`. State file v7 (older states migrate with the defaults 30 s since 0.7.3, 3, 6 h, 24 h, 8 h).
- Clock: the Wio Tracker L1 runs the 32.768 kHz crystal (the `lfclk` binding default `k32src = "xtal"`, not overridden by the board), so the 100 ppm guard is ample.
- Sizes on the Wio Tracker L1 (0.7.5): flash 436 KB of 676 KB, RAM 108 KB of 256 KB.

## Added in 0.6.0

- Wake decision `lt_wake_decide` (lt_core): home zones, owner presence by Bluetooth anchors, heartbeat, epoch skip; PROTOCOL.md 2.1. Configured over USB only (`zone`, `anchor`, PROTOCOL.md 6.1).
- Bluetooth: a passive scan alongside each GPS search when anchors exist; `BLE_SCAN` and LOST send BLE packets (PROTOCOL.md 2.3). `blepair` makes the tracker a connectable, bondable peripheral for a short window only (`CONFIG_BT_PERIPHERAL`, `CONFIG_BT_SMP` in `tracker.conf`; bonds in RAM, no BT settings).
- Wi-Fi: no supported board has a Wi-Fi radio. `CONFIG_LIBRETAG_WIFI` enables the report layout and LOST packet, with `lt_wifi_scan()` as a weak hook a board driver overrides (T1000-E LR1110, ESP32 boards, an add-on scanner).
- Sizes on the Wio Tracker L1 (0.6.0): flash 432 KB of 676 KB, RAM 108 KB of 256 KB. 0.5.2 was 291 KB / 71 KB; LE Secure Connections pairing for `blepair` (PSA/mbedTLS P-256) is most of the difference.

## New ZephCore / MeshCore version

```sh
firmware/tools/zephcore.sh update master   # or tag/commit
```

The script shows which upstream changes touch the files we modify, and runs `git rebase --onto <new> <old> libretag`. On a conflict: resolve it in the clone, `git rebase --continue`, edit `commit=` in `zephcore.lock`, `export`. Then always:

1. `make -C firmware/lt_core/test test` (protocol) and a tracker build.
2. On hardware: `version`, `status` (the key and settings survived the state migration), `discover`, `meshscan 60`, `test 1 30` and a report in the app.
3. Check changes in the MeshCore protocol (packet format, headers, path hash, control packets) against `lt_core` (`lt_packet_set_route`, the channel envelope) and `mesh_scan_record`.
4. Bump `VERSION`, export, commit in this repo.

## New board

The tracker role needs: a LoRa radio, GNSS through `ZephyrGPSManager`, battery measurement, USB CDC (console) and LittleFS `/lfs`. If ZephCore supports the board (`zephcore/boards/...`), the procedure is:

1. Build with `-b <board>` and `tracker.conf`; add to `tracker.conf` or to `boards/<board>.conf` only what differs (e.g. display disabled, a different GPS driver).
2. Verify: `version` (correct `CONFIG_BOARD`), `noise` (noise on battery around −115 to −120 dBm), `gps` (sees satellites outdoors), `status` (battery in %), `blescan 10`, wake-up and a report.
3. The USB VID/PID is shared (`2fe3:0007`, "LibreTag Tracker"); the app does not distinguish boards.
4. Add the board to the table below and the UF2/hex to `dist/`.

| Board | Status |
|---|---|
| Seeed Wio Tracker L1 (Pro) | verified on hardware 2026-11-15 |
| Heltec Wireless Stick Lite V3 (ESP32-S3, no GPS) | Wi-Fi zones and reports verified on the bench 2026-11-17, `docs/design/fw-wifi-esp32.md` |

## ESP32-S3 without GPS (Wi-Fi)

The same `tracker.conf` builds for `heltec_wifi_lora32_v3/esp32s3/procpu` (the Wireless Stick Lite V3 uses that definition). CMake adds `tracker_wifi.conf` and light sleep (`pm_esp32.conf`) from the board manifest. It needs the `xtensa-espressif_esp32s3_zephyr-elf` toolchain (`zephyr-sdk-1.0.1/setup.sh -t xtensa-espressif_esp32s3_zephyr-elf`), `west blobs fetch hal_espressif` and `esptool` 5.x on the PATH.

```sh
west build -b heltec_wifi_lora32_v3/esp32s3/procpu zephcore -d build-esp-tracker -- -DEXTRA_CONF_FILE="boards/common/tracker.conf"
west flash -d build-esp-tracker --esp-device /dev/cu.usbserial-0001
```

New commands: `wzone snap <name> [scans]`, `wzone list|show|del|clear|test|thr`, `wifiscan [count] [gap_s]`, `wifi passive|active`, `sleep [on|off]` (PROTOCOL.md 6.1). Opening the CP2102 port resets the board. Typing also wakes it from light sleep, but the first lines after a sleep get lost: repeat a command every second or two until it is echoed, then the console stays awake while used (30 s after the last input). `sleep off` keeps it awake until the next reboot.
| Seeed SenseCAP MeshTracker X1 | planned (SPEC 2.0) |

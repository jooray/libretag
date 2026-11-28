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

## 0.10.2: next_in is to the start of the next wake-up

- In TRACK and LOST, `next_in` is again the time to the start of the next wake-up (PROTOCOL.md byte 31), without this wake-up's GPS search. 0.10.1 added the search, which doesn't predict the next one (8 to 90 s on the same walk), so the app's wake-up before a report and the Bluetooth command advert could come after the report.

## 0.10.1: field test 2026-11-27

- Console `track` wakes the tag at once, as a mesh command does; before, the mode's first report waited for the next IDLE wake-up.
- A TRACK or LOST that ran out ends before the report is built: the report says IDLE with the IDLE `next_in`. Before, the last report said TRACK with the TRACK `next_in`, and an app without the end time raised Silent.
- The TRACK and LOST interval runs from wake-up to wake-up (`planned_due_ms`), at least the listen window after the report; before, it was counted from after the report and the listen window.

## 0.10.0: the quiet mesh

- What: the tag uses the MeshCore repeaters as little as it can. Design and reasoning: `docs/design/quiet-mesh.md`; behaviour, defaults and budgets: `SPEC.md` 3.2; wire formats: `PROTOCOL.md` 2.6, 2.7, 3.3 to 3.5. Nothing is compatible with 0.9 bridges or apps.
- Copies: the regular IDLE report is copy 9, zero-hop (DIRECT, empty path). A report that has to arrive (boot, command answer, mode change or its end, low battery, console, `hold` ran out, a guarded tag, every TRACK and LOST report) goes zero-hop first in NEAR, along the stored path in ROUTED (copy 11), as a flood in FAR. Without an ACK by the decision point (12 s, 12 s + 1.5 s per hop) the flood follows (copy 0 at the tag's scope level, copy 1 with it near a border). 8 s after copy 0 without a repeater heard repeating it, an ACK or a command, a flood that matters gets copy 10 one level wider. The 8 s retransmission of the same bytes is gone. Zero-hop and routed copies are not counted as floods.
- The ACK (`lt_ack_open`): 13 B sealed with `C_e(n)`, taken in the listen window of report `n` (`k` = 0) and at every slot of the grid (`k` of that slot), near and routed forms, channel and stealth. A routed ACK is taken only from a DIRECT packet with an empty path and stores the path (at most 2 hashes). Early sleep after an ACK without `MORE`: 20 s for a report that tells the owner something, 5 s for a routine hello. At a slot that only listens, an ACK without `MORE` closes the receiver 1 s later (`CHECK_ACK_TAIL_MS`), so a command a bridge sends 300 ms behind it still lands; bench: an ACK without `MORE`, then a command 300 ms later at a listen-only slot, is answered.
- Link policy: `lt_core/lt_link.c` (`lt_link_decide`, `lt_link_on_ack`, `lt_link_on_unconfirmed`, `lt_link_on_window_end`, `lt_link_on_check_end`, `lt_link_on_flood_sent`, `lt_link_on_flood_result`, `lt_level_next`), pure C, tested by `lt_core/test/link_test.c` (the tables of SPEC 3.2 and multi-day scenarios that count floods and repeater transmissions). NEAR, ROUTED, FAR; `hold` (12 h default, never under a period + 5 min, 0 = guarded); moved (300 m or 3 x accuracy, not in a zone); `ack_patience` 1 (a moved tag decides at the next check) or 2 (after 12 s); moved without GPS by a discover at the next check against the repeaters of the last ACK; no-fix backoff 1, 2, 4, 8 periods up to 24 h; parked flood at the second report in one place; heartbeat 24 h; 8 IDLE fallback floods in 24 h with `next_in` of the last one saying when the budget frees; the extra flood after an unrepeated `hold` flood (R29).
- Schedule: the anchor moves on a flood copy sent and on an ACK in the window of its own report; slots from the slot key `ks` (`lt_slot_key`, `lt_slot_offset`), commands at a slot sealed from `ks` (mesh and the Bluetooth advert); after a flood the previous grid is kept for 3 slots; `SYNC` (flags bit 7) in every report; no grid after a boot until the boot report.
- Scope ladder: area rows (4 in the state file from `area add` or `SET_AREA`, one baked row: the first bridge's city, 25 km, scope `sk-ba`), then the country's region, then unscoped (in IDLE at most once in 24 h). `lvl` widens after a flood without evidence and returns after an ACK, on entering or leaving an area, and 24 h after it widened.
- `SET_AREA` is refused for a slot above 3, a radius of 0, or a scope name that is not 1 to 6 of `a-z 0-9 - _` followed only by zero bytes (`lt_set_area_decode`, the same rules as the app's encoder).
- Commands: accepted under data type `0xFF4C` and `0xFF6C`; they change no link state. Answer timing option C (PROTOCOL.md 3.5): within 2 s with the last position, by the link; also while a GPS search runs (the search is suspended in the firmware's phases, not in the receiver: the GNSS keeps searching and the search resumes with the time it had left). Lost and Track that came as a flood: the mode's first report after the search carries `CMD_ACK`; on a DIRECT route the quick answer, then that report. `CMD_ACK` stays set until a report with it was ACKed, at most 3 reports (R4).
- Counter: checkpoints at multiples of 16 for the state and the key page floor, moved only when a counter goes on air, so a boot lands on one of two known counters and a run that sent nothing costs none.
- Phase deadlines in the main loop (PH_TX 15 s per packet, the listen window + 60 s, a check window + 60 s, a Bluetooth scan 30 s, the search + 60 s): past one the phase ends the normal way with `stall` (b = 2) in the event log, no reset.
- Defaults: report every 3, parked at the second report (`still 2 24`). A state from 0.9.8 keeps its settings; the new fields start FAR, `hold` 12 h, `ack_patience` 1.
- Console: `link` (also in `status`), `area add|list|del|clear`, `scopetest`, `ackpatience 1|2`; `log` adds `near routed flood wide acked answer lvl= why=`; `history` flags gain bits 16 to 21 (near, routed, acked, wide, flood, answer) and 24 to 27 (level + 1); events `link` and `lvl`.
- Field log: per wake-up that sent a report three records (FL_SUM1..3: why, links, level, copies with their ms, fix, moved, floods, budget, repeat, ACK, command, anchor, slot, next_in, path and its age, battery, energy, boot cause and resets, TX failures, stalls) and one record per slot window (FL_CHK: slot, grid, ACK, command, pending, receiver time and how early it opened). Layout in the "field-log records per wake-up" comment in `main_tracker.cpp`; the host decoder needs the four new types.
- Not done here: the check window airtime with preamble 32 (PROTOCOL.md 2.1 and the `check_window` vectors in `lt2.json` still say 16); the Python mirror of the link policy in `host/` (the C tests carry the expectations).
- Sizes: Wio Tracker L1 flash 476540 B (68.8 %), RAM 114648 B; ESP32-S3 DRAM 323312 B of 399108 B.
- Built for both boards, not flashed.

## 0.9.8: a field-test log

- Why: the quiet-mesh design needs two numbers nobody has measured: how often a tag is one or two repeaters from a bridge, and how reliable a reversed path is. A walk around town and a courier trip should answer that from the tag's side, without extra transmissions.
- Each record is 48 B (`src/lt_fieldlog.c`, layout in the "field log (0.9.8)" comment in `main_tracker.cpp`). **TX**: every packet the tag sends (report copies, Wi-Fi and BLE packets, DMs, the retransmission, discover), with n, copy, header, path length, length, region, estimated airtime, battery, `floods_24h`, fix state and age, satellites and a payload hash. **RX**: every packet heard in any window, with header, path length and the path hashes (all of them, or the first 9 and last 3 bytes of a longer path), SNR, RSSI, transport code, hash, and whether it was a copy of our own report (same hash as the TX record), a discover answer (key, how the repeater heard us) or an advert (key, type). **Windows**: wake-up, listen, check and discover, each opening and closing, with durations and packet counts. **GPS**: start, fix (position, altitude, search time), timeout, stop. **Events**: a copy of every event-log record, plus the new `txdrop` (TX refused 30 times).
- The passive aid: repeats of our own flood show up as RX records with `match=own`. Their path length is the hop count of the repeater that relayed them, and the last path entry is that repeater, so the distance to the nearest repeaters can be read without sending anything extra.
- Storage: records collect in RAM (32) and are written at the end of every wake-up, before a reboot or a stall, and whenever the buffer fills. On the Wio Tracker L1 they go to the empty 2 MB QSPI flash, `/ext/fl0` and `/ext/fl1`: 2 × 8192 records (768 KB), so the newest 8192 to 16384. The flash is mounted only for a write and goes back into deep power-down after it. Elsewhere (Heltec V3) they go to `/lfs/libretag/fl0|1`, 2 × 640 records. A wake-up writes roughly 5 to 15 records, so 8192 records cover about 3 to 7 days of TRACK every 5 minutes.
- The log holds positions. It stays on the tag and leaves only over the unlocked USB console (`fieldlog` is refused over Bluetooth). Dumps belong outside the repository.
- Console: `fieldlog` (count, sequence, lost and errors, free space on the volume), `fieldlog dump [seq]` (`FL <hex>` lines, then `END <count>`), `fieldlog clear`. Host: `uv run libretag fieldlog --port <port> --out file.jsonl [--raw dump.txt] [--since seq] [--clear]` writes JSON lines with ISO times (estimated within a boot from any record that had the clock) and the last fix attached to each TX and RX record. `--decode dump.txt` decodes a saved dump.
- No change to the wire format or the state file.
- Sizes: Wio Tracker L1 flash 461768 B (66.7 %), RAM 113368 B; ESP32-S3 DRAM 321984 B of 399108 B.
- Flashed on the owner's Wio Tracker L1 on 2026-11-26 over UF2 from 0.9.7. Pairing, n, mode, intervals, zones and the region were kept. The first wake-up logged the boot, the GPS start, the discover TX, three discover answers and two repeater adverts over two hops. The Heltec build was not flashed.

## 0.9.7: reset records, an event log and a watchdog

- Why: on 2026-11-23 a tag went silent for about 14 hours (no report, no history record, n did not move, the battery fell from 98 % to 73 %) and came back after a reset that `status` called `pin`. Nothing on the tag said whether it hung, rebooted or sat in a state that never reports, because only sent reports were logged.
- **Breadcrumb** (`src/lt_diag.c`): a record in RAM that the boot does not clear (`__noinit`, magic and checksum). It survives a pin, soft, watchdog or lockup reset and the bootloader (checked on the bench through a serial DFU), not a power loss. The main loop writes its phase (`sleep`, `gps`, `tx`, `listen`, `blescan`, `check`, `boot`), what the main thread is busy with (`wait`, `event`, `cli`, `flash`, `rx`, `blecon`, `gps`, `dfu`, `reboot`), n, mode, quiet, battery, the clock, the events of the last loop pass and flags (GPS acquiring, LoRa RX or TX, Bluetooth scan, console window, USB power, below the transmit floor). A 30 s timer writes the uptime.
- **Boot records** (`/lfs/libretag/boot0`, `boot1`, 80 B each, the newest 16 to 32): the raw RESETREAS register (all bits, read in `helpers/boot_info.c` before hwinfo clears it), the decoded cause, n and battery at boot, whether the previous run announced the reset (`reboot`, `dfu`), the previous run's breadcrumb, and the fatal error that `helpers/fatal_reboot.c` recorded (reason, PC, LR, thread; `CONFIG_THREAD_NAME=y` so the thread has a name). `t=` is an estimate of the reset time from the breadcrumb's clock, 0 when the previous run had no clock.
- **Watchdog**: ZephCore had none (`CONFIG_WATCHDOG` off, no WDT code; fatal errors already rebooted). 0.9.7 starts the nRF WDT with 240 s, running while the CPU sleeps, through the HAL (no driver). A timer reloads it every 30 s, but only until a deadline the main loop sets: on a phase change now + 1200 s (a GPS search is at most 600 s, a listen window 600 s), and while asleep the end of the planned sleep (`report_timer`) + 600 s. The deadline never moves on a plain event, so a loop that keeps handling packets while stuck in one phase reaches it as well as a blocked thread. Past it, the loop (if it still runs) writes a `stall` event, the WDT is not reloaded and the SoC resets with RESETREAS DOG. A soft reset keeps the WDT running with its old settings; the next run only reloads it (`wdt=2`), and the Adafruit bootloader reloads it while in DFU.
- **Event log** (`/lfs/libretag/ev0`, `ev1`, 32 B each, the newest 128 to 256): boot, an hourly `alive` record (phase, flags, battery, listen windows without the home network, resets in a row, energy estimate, seconds to the deadline), quiet begin and end with reason and zone, mode changes, network switches, still and moving, below or above the transmit floor, over or under the flood cap, clock known, console reboot and dfu, Bluetooth console window, commands taken, a packet lost in the radio, stall. About 30 records a day: append-only like the history, so the flash wear is small next to the reports.
- **Fix**: when the radio's TX wait thread gave up on a packet (no TX_DONE within its budget, or the driver reported a failure), it signalled done without a completion, and the main loop ignored that event: the wake-up stayed in `PH_TX` for good, with no next wake-up and the receiver on. Now the packet counts as lost (`txfail` event, history flag `DROPPED` for the report) and the wake-up goes on. Whether this caused the 14 hours is not known; it matches the symptoms (no history record, no report, receiver drain).
- Console: `boots [count]` (`B` lines, then `before:` and `crash:` lines), `events [count]` (`E` lines), `status` has `reset ... resetreas=`, `previous run:` and `watchdog ... deadline in` lines. `wdtest` (USB only) stops the main loop to test the watchdog path.
- No change to the wire format or the state file.
- Sizes: Wio Tracker L1 flash 457964 B (66 %), RAM 111640 B. The Heltec V3 builds (no watchdog there; the breadcrumb and the records work the same).
- Bench, 2026-11-24, on the owner's tag (flashed over serial DFU from 0.9.6, state, keys, n and zones kept): after the DFU from 0.9.6 `boots` showed `soft`, RESETREAS 0x4 (SREQ), no breadcrumb (0.9.6 wrote none); a second DFU left a valid breadcrumb across the bootloader (`act=dfu`, `wdt=2`); `wdtest` gave `reset=watchdog resetreas=0x00000002(DOG)` about 4 minutes later with `up=241s act=cli flags=0x71` (starved); after `reboot` the next wake-up reported (n=7333, relayed) and the resets in a row went back to 0. Not yet seen: a real stall, a `txfail`, a crash record, the hourly `alive` record over a night.
- **Fix (after the bench, still 0.9.7)**: a press of the user button or the joystick crashed the tracker. ZephCore's `helpers/ui-button/ui_task.c` registers its input callback for every input device, but the tracker role never calls `ui_init()`, so `render_work` stayed zeroed with a NULL handler. The callback woke the display and scheduled that work, and the system work queue jumped to address 0: `crash: reason=35 pc=0x00000000 lr=<work_queue_main> thread=sysworkq`. Reason 35 is `K_ERR_ARM_USAGE_ILLEGAL_EPSR`, the usage fault for a branch to an even address (INVSTATE). The owner's tag logged 17 of these on 2026-11-24/25, many a few seconds after boot, while it was carried around (most likely the joystick being pressed in a bag). The callback and `schedule_render()` now return when `ui_init()` has not run, so on the tracker the buttons do nothing. 0.9.6 had the same code; it rebooted on a fatal error too but kept no crash record, so its resets would have looked like soft ones. The Heltec V3 tracker build compiles the same file (its PRG button). The display stays off on the tracker. Nobody has pressed the joystick on the bench yet to reproduce it; the cause is read from the code and the ELF (`ui_input_cb` linked, `ui_init` absent, `render_work` in `.bss`).

## 0.9.6: back across a border without a fix

- The tracker keeps the **previous network**: the radio preset, path hash size, region and country it left at its last automatic switch (`adopt_net`, only when the radio changes and the old radio is a known preset). It is saved in the state file next to the home network (`prev_preset`, `prev_country`, `prev_path_hash_size`, `prev_scope`; extension magic `LT96`; a 0.7.6 to 0.9.5 state with `LT76` is read with its own CRC and starts with no previous network).
- A report **without a fix** also goes out as copy `j = 1` on the previous network when the home network was silent in the last 3 listen windows (`HOME_SILENT_WINDOWS`, as for the border copy), the previous network uses another radio than home, the network is not manual (`cfg.region_manual`) and the daily flood cap is not reached. It reuses the border copy path: `alt_net`, `txq_alt`, the radio override and `radio_home()`, the region key, and the history flag 32. Log: `no fix: copy j=1 on CZ's network (previous)`.
- The home network still changes only with a fix. After the copy the radio goes back home for the listen window, so the tag does not hear the previous network and cannot tell whether the copy got through. Switching home back on an answer would need a listen window on the other radio; that was left out.
- Why: a field test on 2026-11-22. A tag crossed from Slovakia into Czechia and took the Czech network, then came back in a bag without a fix and kept sending on the Czech network, where nobody heard it. SPEC 6.4 said "try presets in turn" for this case; it was never implemented, and this replaces it.
- Cost: one more flood per report without a fix while the home network is silent, so at most doubling the reports in that state, within the 200 floods a day cap.
- Console: `status` has a `previous network:` line; `network` (no argument) prints the home network with its radio and the previous one.
- Sizes: Wio Tracker L1 flash 451764 B (65 %), RAM 110896 B; ESP32-S3 DRAM 312 KB of 399108 B.
- Patch 0042. **Needs a bench check:** built for both boards, not flashed or run on hardware yet. To check: `where` in two countries, a `status` after a switch, then a report without a fix after 3 silent windows (log line, two floods in `txcap`).

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

# Tracker firmware 0.7.6: notes for PROTOCOL.md and the apps

Source: `firmware/zephcore-patches/0026-*.patch` (main_tracker.cpp) and `firmware/lt_core/lt_core.{c,h}` (section "0.7.6: transmit policy"). Host tests: `make -C firmware/lt_core/test test` (`test_076`). The report format, the command format and the keys do not change. No new report flag.

## 1. Counter and state

- The state file is written to `/lfs/libretag/state.tmp`, closed, and renamed over `/lfs/libretag/state`. LittleFS renames atomically, so a reset leaves either the old state or the new one. At boot the tracker also reads a complete `state.tmp` that was never renamed (it is the newest).
- The state keeps the v7 magic `LTG7` and adds a 16 B extension: magic `LT76`, `low_flags`, `boot_resets`, `boot_deliberate`, a pad byte and a CRC-32 of everything before it. 0.7.5 reads the v7 part and ignores the rest, so a downgrade keeps the settings. 0.7.5 writes only the v7 part, and 0.7.6 then reads it as v7 with an empty extension.
- The key page (raw flash, two copies, CRC) gets a tail after the record: magic `LTN1`, `n_floor`, CRC-32. `n_floor` is above every counter sent with these keys. The tracker raises it by 32 before a report would reach it, and with every key ratchet. The record before the tail is byte for byte the 0.7.5 one, so 0.7.5 still reads its keys.
- At boot `n = max(state n_next, key page n_floor, 64 × epoch)` (`lt_boot_counter`). If the state is missing or damaged while keys exist: at least `n_floor`, and if the key page has no bound either (written by 0.7.5), the start of the next epoch, `64 × (epoch + 1)`. No counter in that epoch was used beyond it, because the tracker ratchets before it seals.
- Before a counter goes on air, both the state (`n_next`, step 16) and the key page (`n_floor`, step 32) are already past it. 0.7.5 saved its checkpoint only after the transmission and ignored a failed save.
- Effect for the app: a reboot moves `n` forward by up to 32 instead of 16. A damaged state moves it at most to the next epoch (≤ 64 ahead). Both are well inside the 2048-ahead window.

## 2. Low battery and brown-out

**Transmit floor.** Below 3250 mV the tracker does not transmit. It sleeps through its wake-ups without GPS or radio and starts again at 3350 mV or when USB power is present (`lt_tx_allowed`). The reading comes either at the start of a wake-up (MCU only, close to open-circuit) or just before the report (GNSS on, about 25 mA).

Why 3250 mV: the L1 Pro TX peak is about 118 mA, plus about 25 mA of GNSS. At an assumed 0.3 to 1 Ω for a nearly empty, aged cell with its protection FET and wiring, the sag is 45 to 150 mV, so the cell sits at about 3.10 to 3.20 V during TX. The 3.3 V regulator then gives about 2.9 to 3.0 V. That is still above the L76K minimum (2.7 V) and far above the nRF52840 (1.7 V) and SX1262 (1.8 V). Below about 3.2 V at rest the sag reaches the point where the GNSS or the cell's protection drops out, and that is the reset loop the audit describes. On the board curve, 3250 mV is about 7 %. It leaves the band from 10 % (3300 mV) down to the floor for the low-battery report and DM. The internal resistance is an estimate, not a measurement. A PPK2 measurement at 3.3 / 3.2 / 3.1 V would confirm the number. It is a single constant, `LT_TX_MIN_MV`.

**Resets in a row.** `boot_resets` in the state counts boots without a completed transmitting wake-up. It is cleared after a wake-up whose TX queue went out completely and whose listen window ended, or after an hour of uptime. `reboot` and `dfu` from the console set `boot_deliberate`, so they do not count. The first report after a boot comes after `lt_boot_delay_s(boot_resets)`:

| resets in a row | first report after |
|---:|---|
| 0 or 1 (normal boot) | 30 s (was 5 s) |
| 2 | 2 min |
| 3 | 4 min |
| 4 | 8 min |
| 5 | 16 min |
| 6 | 32 min |
| 7 and more | 1 h |

The rest of the schedule is unchanged. Together with the floor, a cell that browns out under TX can do at most one transmission per hour, and none below 3250 mV.

**Low-battery alert, once per crossing.** Two persisted flags: `REPORTED` and `DM`. The first wake-up whose reading is below 10 % (`LT_LOW_REPORT_PCT`):

- looks and reports even when quiet (the report then carries `QUIET` as usual) or when it would only listen (`report_every`), through the same path as a console `report` (`forced` in `lt_wake_decide`); the check window of that wake-up stays open as usual;
- sets `REPORTED` and saves the state before the transmission, so a reset during it cannot repeat it;
- sends the existing low-battery DM (`batt 9%`, dm4x.full) in the same wake-up. `DM` is set only when the DM was queued, and an unsent DM (no owner, no clock yet) is tried again with the next normal report but never forces one.

Both flags are cleared only by a battery reading of at least 20 % (`LT_LOW_REARM_PCT`) that is not taken on USB power, since the charger's voltage says nothing about the cell. A reading that wobbles around 10 % therefore gives one report and one DM. A reboot gives none, because the flags are in flash and 0.7.5 kept them in RAM. A failed ADC reading (0 mV) neither forces nor arms. Host test: `test_076` feeds a wobbling sequence and expects exactly two reports for two real crossings.

The report itself has nothing new: byte 3 holds the battery %, and `LOW_BATT` (flags bit 2) is set below 25 % as before. For apps and bridges, a report with battery < 10 % is the signal. Bridges publish it like any report, so the app (or a watcher) can notify from it without the DM. Below 10 % TRACK and LOST still end and the tracker goes back to IDLE (unchanged).

## 3. Transmission limits

- **TRACK/LOST interval at least 120 s** (`LT_TRACK_MIN_S`; `SET_MODE` interval, console `track`, a saved mode resumed at boot). 0.7.5 allowed 30 s.
- **Daily flood cap: 200 per rolling 24 h** (`LT_FLOOD_CAP_24H`). Counted packets: every packet the tracker floods (report, border copy, BLE and Wi-Fi packets, public Nostr report, DMs, inbox poll) and the retransmission after 8 s. The zero-hop discover is not counted. The window is 24 hourly buckets in RAM (`lt_txcap_*`); a reboot starts it from zero, and the reset backoff covers reboot loops. While over the cap:
  - the next interval in every mode is `max(IDLE interval, 1 h)` (`lt_base_interval_s`), and `next_in` in the report says so;
  - in IDLE on the keyed schedule (quiet or `report_every` > 1), a wake-up that would report only listens; the heartbeat still reports;
  - **still allowed:** the answer to a command (report `n + 1` with `CMD_ACK`, and `BLE_SCAN` packets), console `report`/`test`, and the low-battery report. Commands are rare and each one is the owner waiting for an answer. Blocking the ack would make the app resend the command (another flood from the owner's side), and a `SET_MODE IDLE` or a longer interval sent to fix the flood rate would never be confirmed.
  - It ends as soon as the 24 h sum falls below 200 again (hour by hour).
  - TRACK every 120 s reaches the cap after about 6.5 h and then runs hourly until the oldest hours age out. In the host test, 24 h of TRACK at 120 s sends at most 224 floods.
- **LOST slows down after 2 h at one place.** The set interval (10 min from the app) for the first 2 h of LOST, then ×2 (20 min) while each fix stays within the still radius (100 m, the parked radius) of the first fix of that stretch. A fix outside it sets a new reference and goes back to 10 min at once (the `next_in` of that report already says 10 min). A wake-up without a fix changes nothing, so a tag that never gets a fix stays at 10 min. The 2 h restart after a reboot (LOST start is not kept). `lt_lost_note_fix`, `lt_lost_interval_s`. **App:** in LOST, expect `next_in` up to 2 × the LOST interval. An app that waits for 10 min reports in LOST should use `next_in`.

## 4. DFU from the app

Console command (needs the USB unlock like every setting; `ERR locked: ...` otherwise):

```
dfu [serial|ble|uf2]        default: serial
→ dfu: rebooting to serial   (or ble, uf2), then the USB device goes away
→ ERR dfu serial|ble|uf2     for anything else
```

The answer is written, then the tracker waits about 300 ms, saves its state, detaches USB and resets with GPREGRET0 set for the Adafruit nRF52 bootloader. That is Seeed's build on the Wio Tracker L1, with SoftDevice S140 7.3.0 kept below the app at 0x27000:

| mode | GPREGRET0 | the bootloader comes up as |
|---|---|---|
| `serial` | `0x4E` (`DFU_MAGIC_SERIAL_ONLY_RESET`) | USB CDC only, no mass-storage drive; Nordic legacy serial DFU (HCI/SLIP), the protocol of `adafruit-nrfutil dfu serial` (zip from `adafruit-nrfutil dfu genpkg`) |
| `ble` | `0xA8` (`DFU_MAGIC_OTA_RESET`) | BLE OTA, legacy Nordic DFU; the same value ZephCore's buttonless BLE DFU writes |
| `uf2` | `0x57` (`DFU_MAGIC_UF2_RESET`) | mass-storage drive plus CDC (what `dfu` did up to 0.7.5, and what the 1200 baud touch does) |

Why serial-only for the app: it is the mode Adafruit's own tools use for an application-initiated update (Arduino core `enterSerialDfu()`, `--touch 1200`). The phone then sees a single CDC device, so no mass-storage volume gets mounted and no media scan touches the flash during the update. In the bootloader the device has the bootloader's USB VID/PID, not `2fe3:0007`, so the app must ask for USB permission again. Not verified on hardware yet: whether this bootloader build has a timeout in serial-only mode (it may return to the app after a while without traffic), and the exact BLE advertising name in OTA mode.

## 5. `status`

One new line after `energy used since full`, with keys no existing parser matches:

```
txcap floods_24h=12 cap=200 over=no low_alert=armed resets=0 boot_delay=30s tx_floor=3250mV tx=ok
```

- `low_alert`: `armed`, `reported` (the low report went out, no DM yet), `sent` (report and DM).
- `tx`: `ok` or `low_voltage` (below the floor, waiting for 3350 mV or USB).
- In LOST the line ends with ` lost=fast` or ` lost=slow`.

The other lines and their order are unchanged. The `help` text has a new line for `dfu`.

## 6. For the other parts

All three are done (2026-11-17):

- `host/src/libretag/sim.py` follows the firmware: the policy is mirrored in `host/src/libretag/policy.py` (`TRACK_MIN_S`, the 24 h flood cap, the LOST slow-down), tested in `host/tests/test_sim.py`.
- PROTOCOL.md: section 3 (`SET_MODE` interval minimum 120 s), section 5 (once per crossing, persisted, re-armed at 20 %, plus the report), section 6 (`dfu`, the `txcap` line) and the new section 2.5 on the floor, backoff and cap.
- The firmware README has a 0.7.6 section.

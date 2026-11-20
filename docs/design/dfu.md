# Firmware update from the app (DFU)

Status: draft, 2026-11-17. The code builds and passes its unit tests. Nothing has run against a
device yet. The test plan is at the end.

## What the tag has

- Seeed Wio Tracker L1, nRF52840. Factory bootloader: Adafruit nRF52 bootloader (UF2), Seeed build,
  with the S140 7.3.0 SoftDevice kept in flash at 0x1000-0x27000. Our Zephyr firmware does not use
  the SoftDevice (it has its own BLE controller), but the bootloader needs it for BLE DFU.
- Seeed's MeshCore guide flashes `wio_tracker_l1_bootloader-0.10.0_s140_7.3.0.zip` with
  `adafruit-nrfutil ... --singlebank --touch 1200`. So the bootloader on current boards is probably
  0.10.0 (or the 0.9.x it shipped with), built single-bank. **Not verified on our boards**: read
  `INFO_UF2.TXT` from the UF2 drive (double-tap reset on a computer) and write the version here.
- USB IDs: the firmware is Zephyr's `2fe3:0007`. The bootloader re-enumerates with Seeed's VID
  `0x2886` and its own PID. PlatformIO/Meshtastic list `2886:1667` and `2886:1668` for this board,
  and the bootloader's USB product string is the board name ("TRACKER L1"). Which PID belongs to
  UF2 mode and which to CDC-only mode is not verified. `DfuDevices` accepts both, plus any device
  with VID 0x2886 whose product string contains "TRACKER L1", plus Adafruit's VID 0x239A.
- Ways into the bootloader:
  - `dfu serial` on the unlocked console (new firmware command, built in parallel). It sets
    GPREGRET to 0x4E (`DFU_MAGIC_SERIAL_ONLY_RESET`, CDC only, no drive) and resets. The app
    expects `dfu: rebooting to serial` back.
  - plain `dfu` (firmware up to 0.7.5): GPREGRET 0x57, UF2 mode with CDC + drive. Serial DFU works
    there too, so the app falls back to it when `dfu serial` is unknown.
  - `dfu ble` (new): GPREGRET 0xA8, BLE OTA DFU.
  - double-tap reset. The bootloader then waits with no timeout.
  - 1200 baud touch (ZephCore, not behind the USB lock; see the audit).
  Entered via GPREGRET 0x4E/0x57, the bootloader goes back to the app after 3 s unless USB enumerates.
  A phone enumerates it right away, so it stays. Older bootloader versions may behave differently:
  test it.

## Protocol (serial)

Nordic SDK 11 "legacy" serial DFU, kept by Adafruit (`lib/sdk11/.../dfu_transport_serial.c`,
`lib/sdk/.../hci/hci_transport.c`), and what `adafruit-nrfutil dfu serial` speaks:

- Each packet is a reliable HCI vendor packet: 4-byte header (seq, ack = seq+1, data integrity
  bit, reliable bit, type 14, 12-bit length, header checksum), payload, CRC16-CCITT (init 0xFFFF)
  little endian. The whole thing is SLIP framed (0xC0 at both ends, 0xC0/0xDB escaped as
  0xDB 0xDC / 0xDB 0xDD). The first sequence number is 1.
- The bootloader acks every valid packet with a 4-byte header (ack field = next seq it expects),
  sent *before* it processes the packet. A packet with a bad CRC gets no ack. A repeat of an old
  sequence number gets an ack for the current one and is not processed again. So stop-and-wait
  with a resend after 1 s is safe. adafruit-nrfutil does not actually check the ack number. Our
  sender does, and resends up to 4 times.
- Payloads (little-endian u32 first):
  - START `3`, mode `4` (application), sizes SoftDevice 0, bootloader 0, application N
  - INIT `1`, the init packet (.dat), 2 bytes of padding
  - DATA `4`, up to 512 bytes of the image
  - STOP_DATA `5`
- Timing as adafruit-nrfutil: after START the bootloader erases `ceil(N/4096)` pages with the CPU
  stalled. Wait `(N/4096 + 1) × 90 ms` (about 10 s for 436 KB) before INIT. Pause 103 ms after
  every 8th DATA packet, which is when a 4 KB page is written. After STOP it checks the CRC16
  from the init packet, writes its settings page (bank 0 valid, size, CRC) and resets into the
  application.
- Init packet (dfu_version 0.5): `u16 device_type = 0x0052` (checked: anything else is refused),
  `u16 device_rev = 0xFFFF` (checked only for SoftDevice/bootloader updates), `u32 app_version =
  0xFFFFFFFF`, `u16 n, u16 sd_req[n] = {0xFFFE}` (any SoftDevice), `u16 crc16(image)`.
  `host/tools/dfu.py` output is byte-identical to Seeed's `sd_bl.dat` for the same fields, and its
  frames are byte-identical to adafruit-nrfutil's `HciPacket` on 500 random payloads.
- No signature. The bootloader can be built with `SIGNED_FW` (ECDSA P-256 over the init packet with
  SHA-256 of the image, dfu_version 0.8), but Seeed's isn't. See Risks.

## Package

`host/tools/dfu.py pack firmware/zephcore/build-tracker/zephyr/zephyr.hex -o dist/libretag-tracker-wio_tracker_l1-dfu.zip`

The .zip is in adafruit-nrfutil's format (`manifest.json`, `application.bin`, `application.dat`),
plus `libretag.json` (board, version, size, SHA-256 of the image). Entries have fixed timestamps,
so the same build gives the same zip. Checks before packing, and again in the app:

- the .hex starts at 0x27000 (a .bin carries no address)
- initial SP in RAM, reset vector inside the image (a build for another load address fails here)
- size ≤ 0xA9000 (the application partition, see below)
- the version string from `firmware/VERSION` occurs in the image
- the package is an application only. A package that carries a SoftDevice or bootloader is refused.

`android/app/build.gradle.kts` takes `dist/libretag-tracker-wio_tracker_l1-dfu.zip` (or
`-Plibretag.dfuZip=…`) and copies it to the APK assets as `firmware/tracker-dfu.zip`. Its SHA-256
and version go into `BuildConfig.FIRMWARE_DFU_SHA256` / `FIRMWARE_DFU_VERSION`. At run time the app
compares the asset's SHA-256 with the pinned one before it parses anything. Without the file the
build carries no firmware, and the screen points to the computer guide. Nothing is ever
downloaded.

Release step (once tested): build the firmware, `dfu.py pack`, then `assembleRelease`. Publish the
zip's SHA-256 next to the UF2's.

## Keys survive (from the sources)

Flash of the Wio Tracker L1 build (`zephcore/boards/common/nrf52_partitions_sdv7.dtsi`, confirmed
in `build-tracker/zephyr/zephyr.dts`):

| Range | What |
|---|---|
| 0x00000-0x01000 | MBR |
| 0x01000-0x27000 | S140 7.3.0 (`boot_partition` "softdevice", read-only) |
| 0x27000-0xD0000 | `code_partition`, the application (0xA9000 = 676 KB; 0.7.5 uses 436 KB) |
| 0xD0000-0xD4000 | `storage_partition`: the two raw 4 KB key pages (`keyrec`, magic "LTK2": K0, ratchet keys, lock flag), patch 0002 |
| 0xD4000-0xF4000 | `lfs_partition`: LittleFS `/lfs`, state in `/lfs/libretag` (settings, zones, anchors, history ring) |
| 0xF4000-0x100000 | bootloader, its config, MBR params page, settings page |

What the bootloader erases (Adafruit_nRF52_Bootloader, single bank, the default `DUALBANK_FW=OFF`):
`dfu_single_bank.c` erases `ceil(N/4096)` pages starting at `CODE_REGION_1_START` (0x27000, the
SoftDevice size) when START arrives, then writes N bytes there. Nothing else is touched. So an
image of N ≤ 0xA9000 bytes never reaches 0xD0000. The Zephyr linker already refuses a larger
image (code_partition), and `dfu.py` and the app check it once more.

The bootloader itself would accept up to 0xC3000 bytes (it only reserves 40 KB under
0xF4000 for "app data", the CircuitPython layout). Our own size check, not the bootloader,
keeps the keys safe.

If the board's bootloader were built **dual-bank**, it would write the image to bank 1 at
0x27000 + 0x61000 = 0x88000 and copy it down afterwards. Bank 1 overlaps the key pages and LittleFS
for any image over 288 KB. Ours (436 KB) is larger than the 388 KB bank, so a dual-bank bootloader
refuses it outright (NRF_ERROR_DATA_SIZE). The keys would still be safe, but the update would
fail. Seeed's own instructions use `--singlebank`, which only makes sense for a single-bank
bootloader. Confirm on hardware: the update going through and the tag unlocking with the old key
afterwards is the proof.

UF2 drag and drop (the current way) writes only the blocks in the .uf2, which is why updating from
a computer has kept the pairing.

The app checks it at the end: after the update it unlocks the tag with the stored key. "Still
paired with this phone" means the key page survived. If the tag comes back unpaired, the screen
says so.

## App flow

`FirmwareUpdateActivity`, opened from Service (USB) ("Update firmware", filled when the tag is
older than the bundled firmware). `FirmwareUpdate` runs in the app scope, so leaving the screen does
not stop it. The screen stays on while it runs.

1. Shows the tag's version and the bundled one (newer on the tag: says installing would go back).
2. Unlock again with the stored `usb_key` (the console session expires), then `dfu serial`. On
   `ERR unknown` it sends plain `dfu`. Any other `ERR …` stops with "refused the update command".
3. Waits up to 20 s for a bootloader on the cable, then asks for USB permission (it is a new device).
4. Serial DFU over the same CDC driver (`UsbSerial`), with progress per percent.
5. Waits up to 45 s for `2fe3:0007`, asks for permission again, connects through the shared
   `UsbTagSession` (status, version, unlock) and compares the version.
6. Done: version, and whether it is still paired.

A bootloader already on the cable (double-tap reset, or an update that broke off) is offered as
"Continue the update" on the update screen and on the Service screen's plug-in card. The app cannot
tell which tag a bootloader belongs to (it has no ID), so the text says to continue only with a
LibreTag tag. While the update runs, the screens' `UsbHelper`s leave the cable alone and never open
a bootloader as a console. `UsbActivity` (attach intent) finishes at once.

Failure states, each with its own recovery text (`strings_dfu.xml`): no/invalid bundled firmware;
tag not connected; command refused; no bootloader appeared; permission refused; transfer broke off
(the tag stays in its bootloader with its keys, Continue); tag did not come back; wrong version.

After a broken transfer the application is gone. With no valid app the bootloader stays in DFU
mode, with no timeout, also on battery, so the tag cannot be bricked this way: Continue, or UF2
from a computer.

All new strings are English only, in `res/values/strings_dfu.xml`, marked for translation.

## BLE (second option, not built)

`dfu ble` → GPREGRET 0xA8 → the bootloader starts the SoftDevice and advertises the legacy Nordic
DFU service (`00001530-1212-efde-1523-785feabcd123`, name like "AdaDFU"). The same .zip (dfu_version
0.5, init packet with CRC16) goes through `no.nordicsemi.android:dfu` in legacy mode
(`DfuServiceInitiator`, `setForeground`, `setKeepBond(false)`, PRN 8 to 10, `disableMtuRequest` for old
bootloaders). It needs BLUETOOTH_SCAN/CONNECT permissions, a foreground service and about 2 min for
436 KB. Worth it only for tags in the field without a cable. Also, BLE DFU with an unsigned
bootloader lets anyone nearby flash a tag that sits in BLE DFU mode, so the window has to be short
and only opened from the unlocked console.

## Risks

- **Unverified IDs and bootloader version** (above). If the PIDs differ, the wait for the
  bootloader times out with "didn't show up in update mode". Fix `DfuDevices` after the first test.
- **Unsigned bootloader**: anyone with the tag and a cable can flash anything (known, audit
  2026-11-17). DFU from the app does not make that worse. Fixing it needs a `SIGNED_FW` bootloader
  and dfu_version 0.8 packages, and that is a bootloader update (risky, SWD for recovery).
- **Android USB stack**: bulk writes during the erase may time out. The sender treats a failed write
  as a lost packet and sends it again, but a phone that drops the device on a stalled endpoint
  would break the transfer (recoverable: Continue).
- Bootloaders that time out without USB activity (older than the 3 s / cancel-on-mount logic)
  might return to the app while the permission dialog is open. Then "didn't show up" or
  "transfer broke off", and a retry.
- The same Seeed VID is used by Meshtastic/MeshCore on this board. On such a device the transfer
  only gets no answer and fails, nothing is written. But if someone double-taps a Meshtastic
  board and continues, LibreTag gets installed on it. That is acceptable (it is an install), but
  the board check is only the product string.
- Power: the phone powers the tag over USB-C. A tag on a nearly dead battery is fine while
  plugged in.

## Test plan (real device, by the coordinator)

On a computer first, then the phone. Use a tag whose K0 is in the app and backed up.

1. Double-tap reset on a Mac. Note `INFO_UF2.TXT` (bootloader version, Board-ID) and the USB IDs in
   UF2 mode (`ioreg -p IOUSB -l | grep -E 'idVendor|idProduct|USB Product Name'`). Then
   `dfu serial` on the unlocked console (new firmware). Note the IDs in CDC-only mode. Update
   `DfuDevices` if needed.
2. Mac reference: `uv run --project host python host/tools/dfu.py pack … && … dfu.py flash … -p
   /dev/cu.usbmodemXXXX`. Expect about 30 s. After that the tag comes up as 2fe3:0007,
   `version` shows the new version, and `status` is still locked to the same id (keys kept).
   Compare with `uvx adafruit-nrfutil --verbose dfu serial --package … --singlebank`.
3. Build the APK with the package (`-Plibretag.dfuZip=…` or the file in `dist/`). Service → Update
   firmware → Install. Allow USB twice. Watch: progress, done, "still paired". Then Service:
   history sync works, settings were read, a test report goes out.
4. Install the same version again (reinstall path).
5. Pull the cable at about 50 % → "transfer broke off". Plug back in → "Continue the update" →
   done, still paired.
6. Double-tap reset with the phone attached → Service shows "A tag in update mode…" → Continue.
7. Deny the USB permission once → the permission failure text, Try again.
8. Firmware 0.7.5 (no `dfu serial`): the plain `dfu` fallback (UF2 mode) works too.
9. Read `/sdcard/Android/data/app.libretag/files/libretag.log` for `FirmwareUpdate` lines (resends,
   bootloader IDs).

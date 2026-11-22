# Bluetooth console after a reboot

Status: built in firmware 0.8.0 and app 0.10.0; the reset rule changed in firmware 0.9.1, the app
always offers the connect button since 0.11.1. Step 2 (`BLE_CONSOLE`, section 5) in firmware 0.9.2 and
app 0.11.2.

## Goal

After a reboot the tag listens on Bluetooth for 3 minutes. In that window the owner's app can do
what it does over the cable today: read and write settings, snap Wi-Fi zones, download history,
scan for known devices, write places. Restarting the tag is easier than finding a cable. On the
Heltec stick it is also better than the cable, because its UART console loses lines in light sleep
and a wrong DTR resets the board.

Not over Bluetooth, ever: firmware update, and the first activation (reading `K0`, `lock`), re-key
and reset of the pairing.

The console itself does not change. Same commands, same answers, same parsers in the app. What is
new is a transport under it.

## Decisions in short

| # | Question | Decision |
|---|---|---|
| 1 | Transport | One GATT service with two characteristics (write, notify), own UUIDs. One ATT value is one frame. The app's `TrackerConsole` gets a `SerialLink` interface with a USB and a Bluetooth implementation |
| 2 | Security | No Bluetooth pairing or bonding. A handshake with `usb_key` proves both sides, then every frame is encrypted and authenticated with a session key. Before the handshake the tag answers nothing at all |
| 3 | Window | 180 s after boot, only on a locked (paired) tag. Stays open while a session is active. Off switch `blecon boot off`. The advert carries a salted keyed tag only the owner's app recognises, from a private address |
| 4 | Power | Negligible on the nRF52840. On the ESP32-S3 the SoC stays awake for the window, about 2 mAh per reboot. No window below the transmit floor or in a reset loop |
| 5 | On demand | A mesh command `BLE_CONSOLE` opens the same window at the next wake-up. Second step, after the boot window works. No button |
| 6 | App | "Connect over Bluetooth" wherever the app asks for the cable today, plus a strip when the app sees the tag listening. Android 12 and newer only |
| 7 | Budget | About +5 KB flash and +1.5 KB RAM on the Wio Tracker L1 (estimate) |

## What exists today (checked in the sources)

- `tracker.conf` already has `CONFIG_BT_PERIPHERAL`, `CONFIG_BT_SMP`, `CONFIG_BT_MAX_CONN=1`,
  `CONFIG_BT_SETTINGS=n`. `blepair` is the only user of the peripheral role
  (`pair_start()` in `main_tracker.cpp`): it advertises the name "LibreTag" from the identity
  address, bonds with Just Works, keeps the phone's IRK and forgets the bond.
- Both tracker builds already have `CONFIG_BT_L2CAP_TX_MTU=247` and 251 B ACL buffers
  (`zephcore_common.conf`). The ESP32 build has `CONFIG_BT_PRIVACY=y`, the nRF build does not.
- The tracker build does not compile MeshCore's companion BLE interface
  (`adapters/ble/ZephyrBLE.cpp`, a Nordic UART Service with a passkey). CMake adds it only to the
  companion role. So there is no conflict and nothing to reuse: that code speaks companion frames.
- The console is one dispatcher, `cli_exec()`. Output goes through `out()` and `ble_write()` to the
  one stream `cli` (USB CDC on the Wio, UART0 behind a CP2102 on the Heltec). The lock is
  `keys.locked` plus `usb_unlocked()` (`challenge`, `auth`, 10 minutes, ends on a DTR drop).
- A locked USB console still answers `help`, `status` (prints the stable `LIBRETAG ID`), `version`,
  `selftest`, `challenge`, `auth`, `unpair`, `buzz`.
- App: `TrackerConsole(port: UsbSerial)` does blocking `write` and `read(timeoutMs)`.
  `UsbTagSession` (`app.usb`) holds one console under a mutex and is used by the Add tag wizard,
  Service, Tag settings, Places, Known devices, the zone editor and the firmware update.
  `TagOps` holds the operations. The manifest has `BLUETOOTH_ADVERTISE` only: the app does not
  scan and does not connect today.
- SPEC 5 says the tracker "does not transmit BLE" and stays invisible. `blepair` is the one
  exception, and only on request. This feature adds a second one. See "Privacy".

## 1. Transport

### GATT layout

| Item | UUID | Properties | Permissions |
|---|---|---|---|
| Service "LibreTag console" | `4C540001-7A1E-4B0C-9D2F-6C6962726574` | primary | |
| RX (app to tag) | `4C540002-7A1E-4B0C-9D2F-6C6962726574` | Write, Write Without Response | plain write |
| TX (tag to app) | `4C540003-7A1E-4B0C-9D2F-6C6962726574` | Notify | CCC plain read and write |

The service is static (`BT_GATT_SERVICE_DEFINE`, no dynamic database). It is reachable only while
the tag advertises, which is only inside a window. Writes outside a console window (for example
during `blepair`) are refused with `BT_ATT_ERR_WRITE_NOT_PERMITTED`. The UUIDs are not in the
advert.

Why not the Nordic UART UUIDs: the frames are sealed, so a generic Bluetooth terminal could not
talk to it anyway, and tools that auto-detect NUS (MeshCore apps among them) would take the tag for
something it is not.

No link encryption is required on the characteristics. Section 2 says why.

### Frames

GATT keeps message boundaries, so one written value or one notification is one frame. No SLIP, no
length field. Byte 0 is the type.

| Type | Direction | Body | When |
|---|---|---|---|
| `0x01` HELLO | tag to app | `ver` (u8, `0x01`), `tag_nonce` (16 B) | when the app enables notifications |
| `0x02` AUTH | app to tag | `app_nonce` (16 B), `proof_a` (16 B) | the app's answer |
| `0x03` READY | tag to app | `proof_t` (16 B) | the proof was right |
| `0x10` DATA | both | `ct` (1 B or more), `mac` (8 B) | console bytes, sealed |

A frame is at most `ATT_MTU − 3` bytes. AUTH is 33 B, so the default MTU of 23 is not enough: the
app requests MTU 247 right after connecting (`requestMtu(247)`), and the tag closes a connection
whose MTU is below 64 when AUTH should arrive. Both tracker builds already allow 247. DATA then
carries up to 235 B of console text.

The plaintext inside DATA is the console byte stream, unchanged: command lines end with `\r`,
answers end with `\r\n`, listings end with `END`. Frames cut the stream anywhere. The app joins the
bytes and splits lines as it does for USB.

Two differences from USB, both on the tag:

- No echo. The app's `withoutEcho()` already copes with a missing echo, and echo would double the
  radio traffic.
- The Bluetooth line buffer is its own (`bcon_line[160]`), so a half-typed USB line and a Bluetooth
  line never mix.

### Flow control

- App to tag: Write With Response. The app sends one command line and waits for the answer, so
  there is never more than one line in flight. The tag queues incoming frames in a 512 B ring
  (1 B length + frame) and handles them in the main loop. A full ring answers
  `BT_ATT_ERR_INSUFFICIENT_RESOURCES`.
- Tag to app: `out()` appends to a 235 B frame buffer. The buffer is sealed and notified when it is
  full, when an `out()` call ends with `\n` and the last flush is 50 ms or more ago, and before the
  main loop waits for events. So a history dump goes out in full frames and a progress line
  (`SNAP …`) goes out at once.
- `bt_gatt_notify()` called from the main thread waits for a free ATT buffer. That is the flow
  control: a long listing runs at the speed of the link, as the USB path waits for room in the CDC
  ring. To verify on the pinned Zephyr: if it returns `-ENOMEM` instead of waiting, retry with
  `k_msleep(5)` for at most 2 s, then drop the connection. Lines are never dropped silently,
  because a missing frame breaks the counter (below).
- At session start the tag asks for connection parameters 30 to 50 ms, latency 0, supervision
  timeout 6 s (`bt_conn_le_param_update`). For a history download the app asks for
  `CONNECTION_PRIORITY_HIGH` and goes back to balanced afterwards.

Expected: 512 history records are about 60 KB of text. Over USB that is about 2 s. Over Bluetooth
expect 5 to 20 s. To be measured.

### The app side: `SerialLink`

```kotlin
interface SerialLink : Closeable {
    fun write(bytes: ByteArray)              // throws IOException when the link is gone
    fun read(timeoutMs: Int): ByteArray      // empty on timeout
    val wakeBeforeWrite: Boolean             // CP210x: the tag may be in light sleep
    val minQuietMs: Int                      // USB 0, Bluetooth 600
}
```

`UsbSerial` implements it (`wakeBeforeWrite = isCp210x`). `BleSerial` implements it over
`BluetoothGatt`. `TrackerConsole` takes a `SerialLink` and uses `max(quietMs, link.minQuietMs)`,
because a Bluetooth answer can pause longer between two chunks than a USB one. Everything above
(`TagOps`, `TagSettingsRead`, `ConsoleCommands`, `ConsoleParsers`, `WifiZones`) stays as it is.

## 2. Security

### Who the attacker is

Someone in radio range during a window. Windows are rare: a reboot needs a hand on the tag, a dead
battery coming back, or a crash. Whoever has the tag in hand already has the USB port, and per the
2026-11-17 audit a lot more (unsigned bootloader, no APPROTECT). So the new exposure is a remote
attacker who waits near the tag for a reboot, or sits next to the owner while the owner uses the
feature at home. That attacker must get nothing: no identifier, no position, no home zone, no way
to change a setting.

### Why not Bluetooth pairing

The obvious plan is: LE Secure Connections pairing, then the plain text console with `challenge`
and `auth` as on USB. It loses on three counts.

1. Just Works does not stop a man in the middle, and the tag has no display or keys for anything
   stronger. A relay between the app and the tag passes `challenge` and `auth` through untouched,
   and after `OK unlocked` it reads everything and adds its own commands. Android gives an app no
   link key to bind the `auth` answer to, so this cannot be fixed on top of OS pairing. A fixed
   passkey derived from `usb_key` would need the owner to type six digits, and a static passkey
   leaks to an active attacker in a few tries.
2. Bonds live in RAM and the address changes with every window, so Android would show its pairing
   dialog at every session. Keeping bonds needs BT settings in flash, a stable identity on the
   tag and a "LibreTag" entry in the phone's Bluetooth settings.
3. It protects nothing the keys do not already protect better. The app holds `usb_key`. The tag
   holds `usb_key`. That is a shared secret an attacker cannot have.

So the session is secured one layer up, with the key both sides already hold. No pairing dialog,
no bond, nothing stored on either side. The same scheme works later from iOS or Web Bluetooth.

This adds a framing layer (four frame types). It does not add a console protocol. The owner's "no
other protocol" holds where it matters: one dispatcher, one set of commands, one set of parsers.

### Handshake and session

```
app                                              tag
 |  connect, MTU 247, discover, enable notify     |
 |<---------------- HELLO ver, tag_nonce ---------|   fresh random per connection
 |  proof_a = HMAC(usb_key, "LT2-bcon-a" || tag_nonce || app_nonce)[0:16]
 |----------------- AUTH app_nonce, proof_a ----->|   constant-time compare
 |<---------------- READY proof_t ----------------|   proof_t = HMAC(usb_key, "LT2-bcon-t" || tag_nonce || app_nonce)[0:16]
 |  app checks proof_t (this is my tag, not a decoy)
 |<================ DATA frames ================>|
```

Keys, with the HMAC-SHA-256 and AES-128-CTR that `lt_core` already has:

```
ks   = HMAC(usb_key, "LT2-bcon-k" || tag_nonce || app_nonce)        # 32 B
kd_a = HMAC(ks, "LT2-bcon-a2t")     # app to tag: [0:16] AES key, [16:32] MAC key
kd_t = HMAC(ks, "LT2-bcon-t2a")     # tag to app, same split (as seal() splits kd)
```

DATA frame number `c` in its direction (u32, starts at 0, +1 per frame):

```
iv8 = u32le(c) || 00 00 00 00
ct  = AES-128-CTR(kd[0:16], iv8, plaintext)          # aes_ctr() of lt_core
mac = HMAC(kd[16:32], u32le(c) || ct)[0:8]
```

The receiver checks the MAC with the counter it expects, then decrypts. A wrong MAC ends the
connection.

What this gives:

- **Authentication both ways.** Only a holder of `usb_key` produces `proof_a`. Only the real tag
  produces `proof_t`, so a decoy cannot collect the owner's commands.
- **No replay.** `tag_nonce` is fresh per connection, so a recorded AUTH is useless. `app_nonce` is
  fresh, so recorded tag frames are useless. Inside a session the counter orders the frames: a
  repeated, dropped or reordered frame fails its MAC.
- **Confidentiality and integrity** of everything on the console, against a sniffer, a relay and
  packet injection. A relay can only pass frames along or cut the link.
- **A successful AUTH is the unlock.** There is no locked state on Bluetooth and no `challenge` or
  `auth`. The session ends with the connection.

### Limits on attempts

- A connection that has not passed AUTH 10 s after connecting is dropped. Advertising resumes.
- One AUTH per connection. A wrong proof, a bad MAC or a malformed frame drops the connection and
  counts as a failure.
- After 5 failures the window closes and stays closed until the next boot. `proof_a` is 128 bits,
  so the limit is there for the battery, not against guessing.
- Time spent connected but not authenticated does not extend the window.

What is left: someone in range can keep taking the tag's single connection slot for 10 s at a time
and so keep the owner out for the 3 minutes. That is a denial of service only, and the cable still
works. Not defended.

### What an attacker in range can do

| Situation | Result |
|---|---|
| Before AUTH | Nothing. No command is read. HELLO carries a random nonce. The GAP name "LibreTag" and company id `0xFFFF` say "a LibreTag-like device", not which one |
| Wrong proof | Disconnected, counted |
| Sniffing an owner session | Sees frame sizes and timing. No content |
| Relay between app and tag | Passes frames or breaks the link. Cannot read, change or add |
| Replays an old advert | The app connects to nothing or to the attacker, the handshake fails, the app says "couldn't verify the tag" |

### Commands over Bluetooth

Before AUTH: none. Not `status`, not `version`, not `help`. On USB those are open because a
stranger with the tag in hand may as well read its id. Over the air the id would be a stable
identifier for anyone nearby, `unpair` would be a remote factory reset and `buzz` a way to find a
hidden tag.

After AUTH the session has the rights of an unlocked USB console, minus this list. A denied
command answers `ERR ble: use the cable`.

| Denied over Bluetooth | Why |
|---|---|
| `dfu`, `dfu serial`, `dfu ble`, `dfu uf2` | firmware update stays on the cable (unsigned bootloader, and `dfu ble` would open an open DFU service) |
| `key <hex64>`, `newkey` | set or create `K0`: first activation |
| `lock` | first activation |
| `unpair` | reset of the pairing: needs the tag in hand and the cable |
| `challenge`, `auth` | the USB unlock, meaningless here |
| `blepair` | needs the peripheral role and the one connection the console is using (see "Later") |

Allowed after AUTH, which is everything else in `cli_exec()` today:

| Group | Commands |
|---|---|
| State | `help`, `status`, `key` (no argument, an alias of `status`; a locked tag has no `K0` to print), `version`, `selftest`, `quiet`, `log`, `history`, `nostr`, `blecmd`, `neighbours`, `gps`, `noise` |
| Reporting settings | `interval`, `listen`, `fixto`, `check`, `report every`, `still`, `heartbeat`, `lostble`, `transport` |
| Radio and network | `radio`, `power`, `pathhash`, `region`, `network`, `where` |
| Places and devices | `zone …`, `anchor …`, `wzone …`, `wifiscan`, `wifi`, `blescan`, `blesurvey`, `blestop`, `blepkt`, `blecmd adv` |
| Owner | `owner`, `dmalert` |
| Actions | `report`, `test`, `track`, `idle`, `buzz`, `meshscan`, `discover`, `reboot`, `sleep` (ESP32) |
| New | `blecon` (below) |

The list is a deny list in one function, `cli_ble_denied()`. Rule for later commands: anything
that touches keys or the bootloader goes on it.

The window opens only when `keys.locked` is set. An unpaired tag prints `K0` in `status` and has no
owner who could prove anything, so it never advertises. That is also how "first activation is
cable only" is enforced.

If the keys change while a session is open (`unpair`, `key`, `newkey` on the USB console), the tag
closes the session and the window.

Read-only shares (`V_e` without `K0`) have no `usb_key` and cannot use the console. Correct.

## 3. The window

### When it opens

At boot, right after the state is loaded, if all of these hold:

- the tag is locked (paired);
- `blecon boot` is on (default; one byte of the state's `pad7`, 0 = on, so an older state migrates
  to on);
- the battery is not below the transmit floor (`lt_tx_allowed()`, 3250 mV, or USB power);
- `boot_resets ≤ 3`, so a reset loop on a dying cell does not advertise at every round.

`boot_resets` counts only boots the owner did not cause (0.9.1, `lt_reset_deliberate()`,
`lt_boot_resets_next()`). Each boot reads the SoC's reset cause (ZephCore's `boot_info`: RESETREAS on
the nRF52, the ROM reset reason on the ESP32, plus the crash record the last run left):

| Cause | Counts as |
|---|---|
| reset button (nRF52 RESETPIN) | deliberate: the count is 1 |
| power-on the SoC tells apart from a brown-out (ESP32 POWERON, which is also EN pulled by the USB-serial chip, so every serial port open on the Heltec) | deliberate |
| debugger, USB-JTAG | deliberate |
| no flag at all (the nRF52 reports power-on and brown-out alike that way) | deliberate when the battery reads 3350 mV or more, on USB power, or with no battery reading; else one more |
| brown-out, watchdog, lockup, panic, a recorded fatal error | one more |
| software reset that `reboot` or `dfu` did not announce | one more |

`reboot` and `dfu` set `boot_deliberate` as before. So reboots, flashing, power cycles and port opens
always open the window, and only faults in a row close it. The transmit floor still keeps a dying
cell quiet. The first-report backoff (`lt_boot_delay_s()`: 30 s, then 2, 4, 8 ... min) reads the
same count, so an owner's reboot reports after 30 s. `status` shows the cause in its `txcap` line
(`last_reset=pin|power|soft|debug|brownout|watchdog|crash|none`).

Bug that led here (0.8.0 to 0.9.0): the rule counted every boot. Flashing the Heltec, then a
reboot by the owner and a few serial port opens (each one resets that board) gave `boot_resets` 5
and no advert.

A successful AUTH sets `boot_resets` to 1, as `reboot` does through `boot_deliberate`: the owner is
there and the resets were theirs. Without this, three restarts within an hour at home (quiet
wake-ups do not clear the count) would end the windows.

The window is 180 s from boot (`LT_BCON_WINDOW_S`). It does not depend on the first report, which
comes 30 s after boot or later.

### How long it stays

| Constant | Value | Meaning |
|---|---|---|
| `LT_BCON_WINDOW_S` | 180 | advertising after boot |
| `LT_BCON_AUTH_S` | 10 | connect to AUTH |
| `LT_BCON_IDLE_S` | 120 | authenticated session without a received frame |
| `LT_BCON_SESSION_MAX_S` | 1800 | longest session |
| `LT_BCON_GRACE_S` | 30 | advertising again after an authenticated session lost its link |
| `LT_BCON_FAILS` | 5 | failures before the window closes |

- While a session is authenticated the window cannot close under it. The session ends on
  disconnect, after 120 s without a frame from the app, or after 30 minutes.
- Before the tag closes a session itself it prints `BLECON closing idle|max|keys` (sealed, like
  any console line) and disconnects.
- When the app disconnects cleanly, the tag advertises again only for what is left of the 180 s.
- When an authenticated session ends by link loss (supervision timeout), the tag advertises for
  30 s more, or for the rest of the 180 s if that is longer, so the app can reconnect and finish.
- The app keeps a session alive with an empty line every 45 s while a screen of that tag is in
  front. An empty line runs no command.

The duration is a compile-time constant, not a setting. The session extends itself, so the owner
never needs a longer window, and one setting fewer is one thing fewer to explain.

### `blecon` (new console command, unlocked)

| Command | Answer |
|---|---|
| `blecon` | `BLECON boot=on\|off state=closed\|open <s>s\|session <s>s fails=<n>` |
| `blecon boot on\|off` | `OK blecon boot on\|off`, kept in the state |
| `blecon open [s]` | opens a window now for `s` seconds (30 to 600, default 180). `OK blecon open <s>`. For the bench and for a tag on the cable |
| `blecon close` | closes the window and any session. `OK` |

`status` (unlocked) gets one line: `blecon boot=on|off state=…`. `help` lists `blecon`, which is
how the app on the cable learns the firmware has it.

### The advert

Connectable, undirected, legacy, interval 100 to 150 ms. No name, no service UUID. Flags, then one
manufacturer specific AD with company id `0xFFFF` and 14 B:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `0x25` (Bluetooth console; `0x21` to `0x24` are taken) |
| 1 | 1 | `left`: seconds until the window closes, divided by 2 and rounded up. 255 = more than 508 s |
| 2 | 6 | `salt`, random per window |
| 8 | 6 | `HMAC(usb_key, "LT2-bcon-adv" || salt)[0:6]` |

21 B in all, inside the 31 B of a legacy advert. `left` is refreshed every 10 s with
`bt_le_adv_update_data()`. It is not authenticated: it only feeds the countdown in the app.

Recognising it: the app filters scans on company id `0xFFFF` and first byte `0x25`, then computes
the 6 B tag from the salt for each of its tags and compares. A handful of HMACs per new salt.

Why a salt and not `xc(n, k)` as in PROTOCOL 3.1: a reboot moves `n` to the next checkpoint and the
app may not have heard a report since. The salt needs no shared counter.

### Privacy

- **Address.** The advert must not come from the identity address (on the nRF52840 that is the
  factory address, the same forever). Turn on `CONFIG_BT_PRIVACY` in `tracker.conf` (the ESP32
  build already has it) and advertise without `BT_LE_ADV_OPT_USE_IDENTITY`: the tag then uses a
  resolvable private address that only a holder of its IRK can resolve, and nobody gets the IRK
  because the console never bonds. `blepair` keeps `USE_IDENTITY` as today. If privacy on the nRF
  build disturbs `blepair` or the active scan address, the fallback is a second identity
  (`CONFIG_BT_ID_MAX=2`) reset to a new random static address with `bt_id_reset()` before each
  window.
- **Payload.** The salt and the tag change with every window. Two windows of one tag cannot be
  linked without `usb_key`. Inside one window the advert is constant apart from `left`.
- **What an observer learns.** For up to 3 minutes after a reboot, "a LibreTag-like device is
  here and just restarted". That breaks the rule in SPEC 5 that the tracker never transmits
  Bluetooth. The cost is small: the window is short, a thief who can restart the tag has already
  found it, and the signal names no tag. Owners who want none of it set `blecon boot off` and use
  the cable or the mesh command. SPEC 5 and PROTOCOL 7 need a sentence each.
- **After connecting**, anyone can read the GAP device name ("LibreTag") and see the console
  service. Nothing else.

## 4. Power and radio

### Boot sequence

```
t = 0       boot, state loaded, bt_enable(), window opens, advertising
t = 30 s    first wake-up (later after resets in a row): Wi-Fi scan (ESP32), GPS search,
            presence scan, report, listen window
t = 180 s   window closes unless a session is open
```

Console lines from Bluetooth run in the main loop between events, exactly like USB lines. The
Bluetooth callbacks only queue bytes and post `EV_BCON`. So a session during the first wake-up
adds no new concurrency: whatever a USB session may do during a wake-up, a Bluetooth session may
do. The first report is not delayed for a session.

A long listing blocks the main loop while it prints, as on USB, only longer (a full history dump
perhaps 10 to 20 s). A LoRa packet that arrives meanwhile waits in the radio. Accepted: it is the
owner's own action, once.

### Radio sharing

- **Advertising or a connection next to the tag's own scans** (presence scan, `blescan`, LOST
  scan, command adverts of PROTOCOL 3.1): the tag is observer and peripheral at once. Both
  controllers support it, and connection events take priority over scan windows. `blescan` over
  Bluetooth is a main use (Known devices), so it is a bench item on both boards.
- **`blepair`** uses the same peripheral role and the one connection. `blepair` on the USB console
  closes the Bluetooth window first. `pair_connected()` ignores connections while the console
  window is open, and the console callbacks ignore connections while `pair_on`.
- **ESP32-S3, one 2.4 GHz radio.** `CONFIG_ESP32_SW_COEXIST_ENABLE=y` is already in the build. A
  Wi-Fi scan (2.4 s active, about 5 s passive) takes the radio channel by channel, and connection
  events fit in between if coexistence does its job. Not tested (open point 7 of
  `fw-wifi-esp32.md`). `wzone snap` over Bluetooth is the main reason for this feature on the
  Heltec, so it is the first bench test there. The 6 s supervision timeout is there for this. If
  the link still drops during scans: the 30 s grace lets the app reconnect and run the command
  again, and the next step would be to scan channel by channel with pauses.
- **Commands by advert (3.1)** are not affected: the tag only receives them.

### Sleep and battery

- **nRF52840.** Advertising and a connection run from the controller while the CPU idles. A
  180 s window at 100 ms costs on the order of 0.01 mAh, a 10 minute session about 0.1 mAh
  (estimates). Nothing to manage.
- **ESP32-S3 (Heltec).** The tracker light-sleeps between wake-ups. For the window and for a
  session the tag holds a sleep lock (`zc_pm_block_sleep()`, the call `lt_pm_console_touch()`
  uses) and releases it when the window closes. Awake with the Bluetooth modem on is roughly 30
  to 40 mA, so about 2 mAh per reboot and 5 to 7 mAh per 10 minute session (estimates, to be
  measured). Sleeping between advertising events, as the companion role does, is a later
  optimisation.
- The 30 minute session cap is for the ESP32: a phone left connected must not empty the cell.
- No window below the transmit floor and none after more than 3 faults in a row (above).

## 5. Opening the window without a reboot

Worth it, as a second step. A tag built into a car cannot be restarted without taking things
apart, and the owner standing next to it still has the mesh.

- **Mesh command `BLE_CONSOLE`, opcode `0x06`**, argument u8 = window in units of 10 s (0 = 180 s,
  at most 60). `apply_command()` calls `bcon_open()`. The command is sealed and bound to `(n, k)`
  like every command, so only the owner can open the window. Its arguments end before offset 8, so
  it also fits the Bluetooth advert form of 3.1: next to the tag, with known devices set, the
  phone hands it over at the tag's next scan. The tag acknowledges with `CMD_ACK` in the next
  report as usual.
- The app shows when the tag will listen (it knows the wake-up schedule) and starts scanning
  around that time while the screen is open.
- **USB:** `blecon open` (above).
- **Button: no.** The tracker firmware uses no button today, both boards have a reset button that
  already does the job, and a button on a hidden tag is as unreachable as the reset.

The window opened this way follows the same rules, including the battery checks.

**As built (firmware 0.9.2, app 0.11.2).** Argument as above (`lt_bcon_cmd_window_s`, 30…600 s).
The boot switch and the reset count do not apply to a window the owner asked for; the 5-failure
lock does. The acknowledgement stays a normal report: it is one 45 B packet, and without it bridges
would keep flooding the timed entries at the following checks.

The owner asked that a local send never floods the mesh (opening Bluetooth for a tag in the same
room). The app (`mesh/CommandRoute.kt`) picks, in this order: the phone's command advert when the
tag's next listening wake-up scans Bluetooth; the owner's node zero-hop, only when the Local API
server lists `tx_path_data` in `hello.capabilities` (meshcored does; MeshCore Open does not yet, so
there this way is off and nothing goes out of the owner's radio); otherwise the Nostr mailbox, where
bridges flood as usual. A local way that reaches the next listening wake-up wins and no mailbox
entry is published.

Coverage of the advert way up to 0.9.2: the tag looked for command adverts only in the presence
scan (IDLE with known devices) and the LOST scan. A listen-only wake-up (the ones between reports
with `report_every > 1`, or above the daily flood cap) ran no scan. With hourly listening and a
report every third wake-up, the advert reached the tag at one wake-up in three, and a tag without
known devices never took adverts.

**Firmware 0.9.3, app 0.11.3: every wake-up scans.** A wake-up without a presence or LOST scan runs
a passive scan for the command advert only, 1.5 s at 100 % duty, alongside the LoRa window (a
listen-only wake-up) or the GPS search. It keeps no device table and matches only the 4 B tag of
this wake-up's `(n, k)`. A match at a listen-only wake-up is taken like a mesh command there: the tag
looks at once and reports `n + 1` with `CMD_ACK`. The phone advertises every 250 ms, so 1.5 s covers
5 to 6 advertising events; on the bench a Wio received about 80 % of a neighbour's adverts with
this scan, so a whole scan misses the phone with a probability below 0.1 % (firmware/README.md
0.9.3). Cost: about 0.06 mAh a day on the nRF52840 at hourly wake-ups, about 0.8 mAh a day on the
ESP32-S3.

The app now treats the phone's advert as the first way at the very next listening wake-up, a
listen-only check included, whenever the phone is near: the tag has known devices, or its last
report was quiet because one of them was there (`CommandRoute.isNear`). A tag without known devices
gets the advert too (the phone advertises anyway, it costs the mesh nothing), but the app cannot
tell that the phone is next to it, so zero-hop or the mailbox carries the command. If a local way
has not delivered it by the wake-up it was meant for (no `CMD_ACK` in a report received a minute or
more after that time), the app hands the command to the mailbox at that report
(`CommandRoute.mailboxFallback`).

**Firmware 0.9.5, app 0.13.1: the console wake advert (PROTOCOL 3.2).** A field failure on
2026-11-22 showed the gap. The tag had been out of mesh range for about 42 hours, and no report
was heard. The owner stood next to it and asked the app to open Bluetooth. The command advert
was sealed for the last `n` the phone knew. The tag had sent many reports since, probably into a
new epoch whose `C_e` it had erased, so its `xc` filter never matched. A window of old `n` on the
tag would not help across epochs.

The phone now advertises `0x26 || salt[6] || HMAC(usb_key, "LT2-bcon-wake" || salt)[0:6]`
(company `0xFFFF`, legacy, non-connectable), with a new random salt for every advertising session.
`usb_key` already keys the tag's own console advert and stays on the tag as long as it is paired.
Every wake-up scan that looks for command adverts also looks for this one. The scan callback only
compares company, length and type; the main loop skips a salt taken before in this boot (a ring of
8), computes one HMAC (at most 16 a scan) and, on a match, opens the window for 180 s as a
`BLE_CONSOLE` command with argument 0 would: paired, above the transmit floor, the 5-failure lock
applies, the boot switch and the reset count do not. An open window ignores it. The tag sends no report and
needs no `n`; the app connects when the console advert appears.

Replay: inside one boot the salt ring refuses a copy. After a reboot a copy opens a window, which
still needs the handshake with `usb_key`, so it gives an attacker nothing beyond 180 s of the tag
advertising. Console: `blecmd` shows `wake_seen=` and `wake_taken=`, and `blecmd adv <26 hex>`
feeds a wake payload to the next scan.

## 6. App

### Permissions

Android 12 and newer: `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"` and
`BLUETOOTH_CONNECT`. Asked once, when the owner first taps "Connect over Bluetooth". Together with
the existing `BLUETOOTH_ADVERTISE` it is one system dialog ("Nearby devices").

Android 8 to 11 can only scan with the location permission. PRODUCT.md says to avoid it, so on
those versions the feature is not offered and the cable texts stay.

To check on a phone: with `neverForLocation` Android drops some beacon formats from scan results.
Our advert must come through on stock Android and on GrapheneOS. If it does not, move the 14 B to
service data.

### Entry points

1. **Tag settings**, and every screen that today shows a "connect the cable" state (Service,
   Places, Known devices, the zone editor): the card gets a second action, "Connect over
   Bluetooth". It opens a sheet:
   - "Restart %1$s: press its reset button once. It listens on Bluetooth for 3 minutes."
   - "Press once. A double press puts the tag in update mode." (On the Wio a double-tap of reset
     enters the bootloader and stays there with no timeout.)
   - a waiting line "Looking for %1$s…", then "Found. Connecting…", then the sheet closes and the
     screen behaves as with the cable in.
   - after 4 minutes without the advert: "Didn't see %1$s. Restart it and keep the phone close."
2. **Strip.** While one of those screens is in front and the permission is granted, the app scans.
   When it sees the tag's advert: "%1$s is listening on Bluetooth. %2$d s left." with "Connect".
   So an owner who restarts the tag first and opens the app second needs one tap.
3. Offered only for tags whose firmware is 0.8.0 or newer (known from reports or the last cable
   session). Older: "Needs firmware 0.8.0. Update it over the cable."

The app never scans in the background and never scans on screens that have no use for the console.
The cable wins: with a tag on the cable the app does not scan or connect.

### Once connected

`app.usb.state` becomes `Connected` with `via = BLE`. Everything that reacts to a connected tag
runs unchanged: settings are read and adopted, changes that "wait for the cable" are written,
history syncs in Service, Places and Known devices enable their actions. The title says
"Connected over Bluetooth".

Cable only, with the reason shown when the tag is on Bluetooth:

| Where | Text |
|---|---|
| Service, Update firmware | "Firmware updates need the cable." |
| Service, re-key and reset | "Needs the cable." |
| Add tag wizard | not reachable: an unpaired tag never listens on Bluetooth |
| Known devices, pair this phone (`blepair`) | "Needs the cable." |

Strings that say "cable" where Bluetooth now also works change to neutral wording, for example
`ts_waits_cable` "waits for cable" becomes "waits for the tag", `place_needs_cable` gains "or
restart it and connect over Bluetooth". Full list in "App changes". English first, then the five
translations by a Sonnet subagent, all through the humanizer.

### When the link goes away in the middle

- A running operation fails as it does when the cable is pulled: `read` throws, the session
  returns null, `TagOps` reports `FAILED`. The existing dirty flags (`cableDirty`, `zonesOnTag`,
  `knownOnTag`) stay set, so a half-written list is written again at the next connection. History
  merges by counter, so a broken download is simply repeated.
- The app tries one automatic reconnect inside the tag's 30 s grace: scan for the tag, connect,
  handshake, then show "Reconnected". The interrupted operation is not replayed automatically,
  except the ones that already repeat themselves on connect (pending settings, history).
- If that fails: "Bluetooth connection lost. Restart the tag to connect again, or use the cable."
- The window cannot close during an operation: a session keeps it open. The tag ends a session
  only after 120 s of silence or 30 minutes. At 30 minutes the app shows "The tag closed the
  session. Restart it to continue."

## 7. Budget and tests

### Size

| Build | Today (0.7.8) | Expected change | Look again if |
|---|---|---|---|
| Wio Tracker L1 flash | 441 KB of 676 KB | +5 KB (GATT service, frames, window logic, `CONFIG_BT_PRIVACY`) | more than +12 KB |
| Wio Tracker L1 RAM | 109 KB of 256 KB | +1.5 KB (512 B ring, 160 B line, two 247 B frame buffers, session keys) | more than +4 KB |
| ESP32-S3 DRAM | 318 KB of 399 KB | +2 KB | more than +6 KB |

The peripheral role, SMP and the crypto primitives are already in. No new Zephyr subsystem. The
application partition limit for DFU (0xA9000) is far away.

### Tests

**lt_core, host (`make -C firmware/lt_core/test test`).** New vectors `ble_console` in `lt2.json`,
generated by `gen_vectors.py` from the Python reference and checked in C, Python and Kotlin:

- advert tag for fixed `usb_key` and salt; the full 14 B payload;
- `proof_a`, `proof_t`, `ks`, `kd_a`, `kd_t` for fixed nonces;
- DATA frames for counters 0, 1 and 0xFFFFFFFF, lengths 1, 16, 17 and 235;
- open rejects: wrong counter, flipped bit in `ct`, in `mac`, wrong direction key, truncated frame;
- `lt_bcon_window_ok()`: every combination of locked, off switch, voltage, resets.

**Bench tool.** `host/tools/blecon.py` (Python, `bleak`, run with `uv`): scans, recognises a tag
from its `K0`, connects, does the handshake and gives a terminal. Also `--bad-proof`, `--replay`
(sends a recorded AUTH) and `--hold` (connects and stays silent), for the limits.

**Bench, both boards.**

1. Reboot a locked tag: advert present for 180 s, private address, 21 B, `left` counts down. A
   second reboot: different address, different salt and tag.
2. Unpaired tag: no advert. `blecon boot off`: no advert. Battery below the floor: no advert.
3. `blecon.py`: handshake, `status`, `help`, every denied command answers `ERR ble: use the cable`.
4. Writes before AUTH are ignored, the connection drops after 10 s. `--bad-proof` five times: the
   window closes. `--replay`: refused.
5. `history` with a full ring (512 records): complete, `END 512`, time noted. The same during the
   first wake-up (GPS search and report running).
6. `blescan 30` over Bluetooth: the list arrives, the link survives.
7. Heltec: `wzone snap test 5` and `wifiscan 3` over Bluetooth, the link survives the Wi-Fi scans.
   `sleep` afterwards shows the SoC asleep again once the window closed.
8. Idle 120 s: `BLECON closing idle`, disconnect. Walk out of range in a session: the tag
   advertises 30 s more, reconnect works.
9. `blepair` on USB while the window is open: the window closes, pairing works. USB console and
   Bluetooth session at the same time: each gets its own answers.
10. `unpair` on USB during a Bluetooth session: the session ends.
11. Sizes of both builds into `firmware/README.md`. A current meter on the Heltec for the window.

**App unit tests.**

- `FakeLink : SerialLink` scripted with lines and pauses. `TrackerConsoleTest`: answer without
  echo, answer split across reads at any byte, `minQuietMs`, a link that throws in the middle.
  The same scripts as USB (with echo) and as Bluetooth (without).
- `BleConsoleTest`: the `ble_console` vectors (advert match among several tags, proofs, seal,
  open, the rejects), frame chunking at MTU 23, 64 and 247.
- `UsbTagSessionTest` with a `FakeLink`: the Bluetooth handshake path gives `MINE` without
  `challenge`, a foreign id gives `Failed`, a USB detach does not end a Bluetooth session.
- `TagOps` over a `FakeLink`: `syncHistory`, `writeSettings`, `snapWifiZone` scripts recorded from
  a real tag.

**App on a phone.** Pixel with GrapheneOS and one stock Android 12+ phone: permission dialog,
strip with countdown, connect, settings read, a pending change written, history sync, Known
devices scan, a Wi-Fi place snapped on the Heltec, link loss and reconnect, the cable-only texts.

## Firmware changes, function by function

`firmware/lt_core/lt_core.h`, `lt_core.c` (portable, host tested):

| Function | Does |
|---|---|
| `lt_bcon_adv(usb_key, salt, left_s, out[14])` | builds the advert payload |
| `lt_bcon_adv_match(usb_key, payload, len)` | true when the payload is this key's (used by the tests and the reference) |
| `lt_bcon_wake(usb_key, salt, out[13])`, `lt_bcon_wake_match(usb_key, payload, len)`, `lt_bcon_wake_find(ad, len)` | 0.9.5: the phone's console wake advert (PROTOCOL 3.2): build, constant-time check, find in AD bytes |
| `lt_bcon_proof(usb_key, who, tag_nonce, app_nonce, out[16])` | `who` = `'a'` or `'t'` |
| `lt_bcon_session(usb_key, tag_nonce, app_nonce, lt_bcon_t *s)` | derives `kd_a`, `kd_t`, zeroes both counters |
| `lt_bcon_seal(s, dir, pt, len, out)` | DATA frame, returns its length, advances the counter |
| `lt_bcon_open(s, dir, frame, len, pt)` | checks the MAC, decrypts, advances the counter; `-1` on failure |
| `lt_bcon_window_ok(locked, off, tx_allowed, resets)` | the four boot conditions |

`firmware/zephcore/zephcore/src/main_tracker.cpp`:

| Function | Change |
|---|---|
| `con_cur`, `con_write()` (new) | which console the current line came from (`CON_USB`, `CON_BLE`). `con_write()` sends to `cli` or to `bcon_tx()`. Output that comes later (scan results, `SNAP` lines, `PAIR` events) goes to the console that sent the last line |
| `out()`, `ble_write()` | call `con_write()` instead of `cli.write()`. The wait for room stays in the USB branch |
| `cli_poll()` | sets `con_cur = CON_USB` before `cli_exec()`. Otherwise unchanged |
| `con_unlocked()` (new) | USB: `usb_unlocked()`. Bluetooth: the session is authenticated. `cli_exec()` and `print_status()` use it in place of `usb_unlocked()` |
| `cli_ble_denied()` (new) | the deny list. `cli_exec()` checks it first when `con_cur == CON_BLE` |
| `cli_exec()` | the `blecon` command. `help` lists it. `unpair`, `key`, `newkey` call `bcon_close()` |
| GATT service (new) | `BT_GATT_SERVICE_DEFINE(bcon_svc, …)`, `bcon_rx_write()` (queues the frame, posts `EV_BCON`), `bcon_ccc_changed()` (posts `EV_BCON` to send HELLO) |
| `bcon_open(sec)`, `bcon_close()` (new) | enable Bluetooth if needed, new salt, start or stop advertising, timer, sleep lock on the ESP32, failure counter |
| `bcon_adv_start()`, `bcon_adv_refresh()` (new) | the advert without the identity address, `left` every 10 s |
| `bcon_connected()`, `bcon_disconnected()` (new, own `BT_CONN_CB_DEFINE`) | ignore connections when the window is not open. Start the 10 s AUTH deadline. On disconnect: wipe the session keys, advertise again by the rules of section 3 |
| `bcon_handle()` (new, main loop on `EV_BCON`) | sends HELLO, checks AUTH, sends READY, opens DATA frames, feeds `bcon_line`, runs `cli_exec()` with `con_cur = CON_BLE`, handles the timer (window end, AUTH deadline, idle, session cap) |
| `bcon_tx()`, `bcon_flush()` (new) | the 235 B frame buffer, seal, `bt_gatt_notify()` |
| `pair_start()`, `pair_connected()` | `pair_start()` calls `bcon_close()` first. `pair_connected()` returns when the console window is open |
| `apply_command()` | step 2: opcode `0x06` calls `bcon_open()` |
| `struct persisted` | `bcon_off` in `pad7[0]`. No new state version |
| `print_status()` | the `blecon` line |
| `main()` | after `load_cfg()`: `if (lt_bcon_window_ok(…)) bcon_open(LT_BCON_WINDOW_S)`. `EV_BCON` (role base + 12) added to `EV_ALL`, `bcon_tmr`. `bcon_flush()` before `k_event_wait()` |

`firmware/zephcore/zephcore/boards/common/tracker.conf`: `CONFIG_BT_PRIVACY=y`, and the comment at
the top (the peripheral is no longer only for `blepair`). No change to SMP: `blepair` still needs
it.

All of it goes in as patches in `firmware/zephcore-patches/` on the `libretag` branch, split as
before: one for the console routing (no behaviour change), one for the Bluetooth console.

## App changes, file by file

| File | Change |
|---|---|
| `usb/SerialLink.kt` (new) | the interface above |
| `usb/UsbSerial.kt` | implements `SerialLink` |
| `usb/TrackerConsole.kt` | takes a `SerialLink`. `port()` returns it. `wake()` asks `wakeBeforeWrite`. Quiet time floor from the link. `unlock()` unchanged (USB only) |
| `proto/BleConsole.kt` (new, pure) | constants and UUIDs, `advMatch()`, `proof()`, `Session` (seal, open, counters), chunking |
| `ble/BleSerial.kt` (new) | `BluetoothGatt` client: connect (`TRANSPORT_LE`, no auto connect), `requestMtu(247)`, discover, enable notifications, handshake, then `write` (seal, Write With Response, wait for the callback) and `read` (a queue of decrypted bytes). Throws `IOException` after a disconnect. Calls back when the link is lost |
| `ble/BleConsoleScanner.kt` (new) | scan with a manufacturer data filter (`0xFFFF`, first byte `0x25`), match against the tags' `usb_key`, a `StateFlow` of "tag X seen, `left`, RSSI, when". Runs only while a screen asks for it |
| `usb/UsbTagSession.kt` | the part of `connect()` after the port is open moves to `handshake(console, via, known)`. New `connectBle(seen)`: the tag is known from the advert, so no `challenge`: `find = MINE` when `status` prints the expected id, else `Failed`. `UsbTag.via` (`USB`, `BLE`). `disconnected()` from a USB detach leaves a Bluetooth session alone. Keepalive and the one reconnect |
| `usb/TagOps.kt` | `relock()` returns false on Bluetooth (no relock needed). `blePair()` and the callers of `unpair`, `key`, `newkey` return `WriteResult.NEEDS_CABLE` (new) on Bluetooth. Longer `maxMs` for `history` on Bluetooth |
| `usb/dfu/FirmwareUpdate.kt` | starts only when `via == USB` |
| `ui/BleConsoleHelper.kt` (new) | lifecycle observer next to `UsbHelper`: permission request, start and stop of the scanner with the screen, `connect()` |
| `ui/TagSettingsActivity.kt`, `ServiceActivity.kt`, `PlacesView.kt`, `KnownDevicesActivity.kt`, `ZoneEditor.kt` | the "connect the cable" state gets "Connect over Bluetooth" and the strip. Cable-only rows show their reason on Bluetooth |
| `ui/TagSheets.kt` | the restart sheet |
| `ui/FirmwareUpdateActivity.kt`, `AddTagActivity.kt` | require `via == USB` |
| `AndroidManifest.xml` | `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`. The comment "no scanning" goes |
| `res/values/strings_blecon.xml` (new) | the new texts. Changed: `ts_waits_cable`, `ts_changes_wait` plurals, `place_needs_cable`, `place_waits_cable`, `place_waits_cable_sub`, `place_delete_cable`, `snap_no_answer`, `wz2_partial_text`, `svc_plug`, `svc_plug_text`, `svc_cable_settings`, `svc_write_failed`, `kd_need_cable`, `kd_scan_failed`. Then `values-sk`, `-cs`, `-de`, `-es`, `-nl` |
| `test/…/usb/`, `test/…/proto/BleConsoleTest.kt` | the tests of section 7 |

The names `UsbTagSession`, `UsbTag` and `app.usb` stay for now. Over a hundred call sites use
them, and a rename is a separate, mechanical commit after the feature works.

## Implementation plan

Each step ends in something that can be checked, and is committed on its own.

1. **lt_core.** `lt_bcon_*`, the Python reference in `host/src/libretag/lt2.py`, vectors
   `ble_console` in `lt2.json` and `lt2_vectors.h`, host tests green in C and Python.
2. **Firmware, console routing.** `con_cur`, `con_write()`, `con_unlocked()`. No Bluetooth yet.
   Check on both boards that the USB console behaves as before (the app's connect, history,
   `blescan`, `wzone snap`).
3. **Firmware, Bluetooth console.** GATT service, window, advert, handshake, deny list, `blecon`,
   `CONFIG_BT_PRIVACY`, the `blepair` exclusion, the ESP32 sleep lock. Build both boards, note the
   sizes.
4. **Bench.** `host/tools/blecon.py`, then bench tests 1 to 11. Fix what the radio sharing tests
   on the Heltec show before going on.
5. **App, link interface.** `SerialLink`, `UsbSerial`, `TrackerConsole`, `FakeLink` and the
   console tests. No behaviour change. Check the cable flows once on a phone.
6. **App, crypto.** `proto/BleConsole.kt` with the vector tests.
7. **App, transport.** `BleSerial`, `BleConsoleScanner`, `UsbTagSession.connectBle()`, the session
   tests. A debug entry in Service is enough to prove a real session: settings read, history.
8. **App, screens.** Permissions, `BleConsoleHelper`, the sheet, the strip, the cable-only texts,
   the changed strings. English through the humanizer, then the translations by a Sonnet subagent,
   also through the humanizer.
9. **Docs.** PROTOCOL.md: section 6.2 "Console over Bluetooth" (GATT, frames, keys, advert,
   limits, the command lists), `blecon` in 6.1, a bullet in 7. SPEC.md 5: the tracker advertises
   for 3 minutes after a reboot unless switched off. `firmware/README.md`: the 0.8.0 section and
   sizes. `docs/guide.html`: "Without a cable".
10. **Release.** Firmware 0.8.0 (UF2 and DFU package), app 0.10.0. The app offers Bluetooth only
    to tags on 0.8.0.
11. **Second step.** Mesh command `BLE_CONSOLE` (`0x06`) in lt_core, firmware, the app and the
    PWA's command list, with its vector.

## Later

- `blepair` over the console connection itself: the app bonds on the link it already has, the tag
  reads the IRK, the bond is forgotten. Then "pair this phone" also works without the cable.
- Light sleep between advertising events on the ESP32.
- Lower transmit power for the console advert. The owner is within arm's reach, and less range is
  less exposure.
- The same console from the PWA over Web Bluetooth. The handshake needs nothing from the OS.

## Open questions for the owner

1. **Sealed frames instead of Bluetooth pairing.** The request said "paired Bluetooth" and "no
   other protocol". This design keeps the console as it is but does not use OS pairing: it
   authenticates and encrypts with `usb_key`. The reasons are in section 2. Is that acceptable?
2. **On by default.** Every reboot makes the tag visible on Bluetooth for up to 3 minutes, which
   bends the "invisible" rule of SPEC 5. Default on with `blecon boot off`, or default off and the
   owner turns it on per tag over the cable?
3. **Android 12 and newer only**, because older versions need the location permission to scan.
   Fine, or ask for location on old phones?

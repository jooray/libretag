# LibreTag: tracker over MeshCore (working specification)

Status: working draft, updated 2026-11-17 (first version 2026-11-13). Based on the owner's initial notes, the first round of research and the prototype. What is built: "Prototype status" in section 10. Current versions: tracker firmware 0.7.8, Android app 0.8.8, web app (PWA) 0.2.3, Android bridge 0.3.2, protocol LT2 (`PROTOCOL.md`).

## 1. What we are building

Custom firmware for a LoRa tracker that sleeps most of the time and occasionally sends an encrypted position into the MeshCore network. A node of our own (at home, on a repeater, on a server) receives the position and stores it. The owner can send the tracker a command over the radio to make it report its position more often.

The goal is an open alternative to Apple AirTag and Google Find My tags. It works without Apple and Google, and also where there are no phones but there is MeshCore coverage (mountains, countryside, "out in the field").

Use cases:

- **Car**: tracker in the car, position once an hour, switches to frequent reporting on theft. The car can hold a larger device powered from a 12 V socket in the trunk, a power bank or a solar panel.
- **Person / backpack / jacket**: small device, possibly sewn into a jacket, charged over USB. Needs weeks of battery life.

## 2. Hardware

Devices supported in v1:

| | Seeed SenseCAP MeshTracker X1 | Seeed Wio Tracker L1 Pro |
|---|---|---|
| Role | target device | **development and tests** (X1 is not in stock yet) |
| MCU | nRF52840 | nRF52840 |
| BLE | yes (BLE 5) | **yes (BLE 5)** |
| LoRa | LR2021 | SX1262 |
| GNSS | AG3335M L1+L5 | Quectel L76KB (GPS+BDS+GLONASS) |
| Battery | 1100 mAh | 2000 mAh |
| Display | no | 1.3" OLED SH1106 |
| Other | barometer, RTC, haptics | joystick, buzzer, Grove I2C, solar input, 2 MB QSPI flash |
| ZephCore board | `meshtracker_x1` | `wio_tracker_l1` (in releases) |

Everything except the radio driver can be developed on the L1 Pro. The SX1262 is the radio best tested in ZephCore. The MCU, BLE and ZephCore are the same as on the X1.

**Watch out for GNSS on the L1:** ZephCore controls the L76K only through the WAKEUP pin (standby). The module's power is never cut. For the similar Quectel L76, the datasheet gives standby ~0.5 mA and backup ~7 µA. If the L76K really stays in standby, it will be the largest draw during sleep (~12 mAh/day, so with 2000 mAh about 3–4 months of battery life from this alone). We need to check the schematic: whether the GNSS supply has a switch, or whether the L76K can be put into backup mode.

For comparison: the community power-saving MeshCore firmware EasySkyMesh reports 18 days of battery life on the L1 Pro with MeshCore communication retained.

### 2.0 Seeed SenseCAP MeshTracker X1

| | |
|---|---|
| MCU | Nordic nRF52840 (BLE 5) |
| LoRa | Semtech LR2021, 863–928 MHz, max 22 dBm |
| GNSS | Airoha AG3335M, L1+L5 |
| Battery | 1100 mAh, high-voltage cell (charged to 4.37 V) |
| Sensors | barometer + temperature; **no accelerometer yet** (pins for a 3- and 6-axis sensor are marked "future version") |
| Other | RGB LED, buzzer, vibration motor (DRV2605L), button, USB-C |
| Power pins | `PWR_EN` (P0.29), sensors (P1.07), `RTC_PWR_EN` (P1.13) |
| Wi-Fi | **no** |
| Dimensions | 90 × 57 × 8 mm, 45 g, IP66, operating range −20 to 60 °C |

Consequences:

- **There will be no Wi-Fi positioning on the X1**, because unlike the LR1110, the LR2021 does not scan Wi-Fi, and the X1 has no ESP32 either. It is an idea for other devices (section 2.1).
- **Without an accelerometer** we cannot cheaply detect "the car moved". Movement can only be estimated from the difference between GNSS fixes or from the barometer. Check whether an X1 revision with an IMU exists or will exist.
- The MeshCore port for the X1 (Arduino) is upstream since version 1.17.0.

### ZephCore and the X1 (found 2026-11-13)

**ZephCore supports the X1.** The board `zephcore/boards/nrf52840/meshtracker_x1` is in the release build (companion and repeater roles) and in the list of supported boards. It has its own out-of-tree LR20xx driver (`adapters/radio/radio_ops_lr2021.cpp`). The repo is active, last commit 2026-11-01.

The catch: `docs/supported_boards.md` says *"Untested on hardware — first ZephCore board to use a real LR2021"*. `docs/ARCHITECTURE.md`, on the other hand, says "LR2021 (validated on the MeshTracker X1)". The documentation contradicts itself, so we need to ask the author or verify it. The only other LR2021 board (ProMicro LR2021) was destroyed by 5 V on the input. So probably nobody has run ZephCore on the X1 yet. If we get it running, it is a contribution to upstream.

What follows from the board files for us:

- Hardware added compared to the wiki: RTC YSN8900 (RX8900 clone) on its own supply branch, 8 MB QSPI flash (`/ext`, suitable for a report buffer), BQ24045 charger.
- **GNSS has separately controlled power and a backup domain**: `GPS Enable` (P1.11), `GPS VRTC Enable` (P1.13), reset, sleep and RTC-int pins. So we can switch off the main GNSS supply and leave the backup domain with ephemeris on. That is a precondition for a hot start.
- The sensors (barometer, haptics) and the flash also have switchable branches. The divider for battery measurement is switched on only for the measurement. So the board is designed for low sleep current, but the real figure has to be measured.
- The RTC branch is never switched off (so that time is not lost). Useful: the external RTC can wake the tracker even from System OFF.
- The GNSS driver is a passive NMEA listener. The power-saving mode of the AG3335M (PAIR commands, AGNSS) has to be written.
- ZephCore has an optional RX duty cycle ("sniff mode": the radio sleeps between preamble checks); ADR 0006 is about SX126x. We need to check whether it also works on LR20xx. It could significantly lower the cost of the RX window.

### 2.1 Other devices: ideas for later

- **Wi-Fi positioning** for a device with an ESP32 or LR1110, typically a powered variant for the car. Without a GNSS fix (garage, underground parking, container), the tracker sends the BSSIDs of 2–3 visible Wi-Fi networks. The receiving side translates them to a position via the GrapheneOS network location proxy or BeaconDB, so the tracker needs no internet. Candidates: Heltec Wireless Tracker V2 (ESP32-S3 + SX1262 + GNSS, supported by ZephCore), T1000-E (LR1110 can scan Wi-Fi).
- **Device with an accelerometer** (e.g. Heltec Mesh Node T1 has an IMU): wake on motion instead of a fixed interval.
- **Car with 12 V power**: the radio can listen all the time and GNSS can run more often, since energy is no problem there. The network load stays the same, though, so still report rarely (section 3).

## 3. Duty cycle

```
          ┌──────────── SLEEP (radio and GNSS off) ──────────────┐
          │                                                      │
  every T_report (default 60 min ± jitter)                       │
          ▼                                                      │
   GNSS fix (timeout e.g. 60 s)                                  │
          ▼                                                      │
   encrypt report, TX via MeshCore (flood)                       │
          ▼                                                      │
   RX window T_listen (default 60–120 s, by measurement)         │
          │── nothing arrived by end of window ──────────────────┘
          │
      command from owner (signature verified)
          ▼
   execute: change mode / send position now / confirm (ACK)
```

### Modes

| Mode | Behavior | Defaults |
|---|---|---|
| `IDLE` | report every `T_report`, then RX window | 60 min, window 60–120 s |
| `TRACK` | report every `T_fast`, GNSS always on | 2–5 min (the firmware allows no less than 2 min since 0.7.6), time limit (e.g. 2 h), then back to IDLE |
| `LISTEN` | radio stays in RX, replies with the position on request | time limit |
| `LOST` (optional) | like TRACK + buzzer on command, so the tag can be found | |

Every mode other than IDLE must have a time limit, so that a single command cannot drain the battery. On low battery the tracker returns to IDLE by itself and reports it.

### Battery and warning

Every report carries the battery level (1 B, %) and a `LOW_BATT` flag (≤ 20 %, configurable). On the first report with a low battery, the app **itself** shows a high-priority warning ("Car: battery running low, 18 %"), even when it is in the background. It warns again only after the battery has been charged above 30 %. Below 10 % the tracker ends TRACK/LOST mode by itself, to last as long as possible.

### Commands (v1)

- `SET_MODE {mode, interval, duration}`
- `PING` (send the position now)
- `SET_CONFIG {T_report, T_listen, ...}`
- `BUZZ {seconds}`
- `ROTATE_KEYS` / `UNPAIR` (service)

A command is authenticated with an HMAC derived from the pairing secret and bound to a specific report (replay protection). The tracker acknowledges the command with a flag in the next report and does not send a separate ACK.

### Command delivery

**Principle: minimum transmission.** Most packets pass through many repeaters, and every packet loads the whole network. So in normal mode the tracker transmits only the report and the base station **transmits nothing**. No ACK for a report and no "nothing for you". The base station transmits only when it has a command for the tracker, and the tracker answers (ACK, position) only to a command.

The owner does not know when the tracker is awake. So we turn it around, like LoRaWAN Class A: commands wait in a queue on the **base station** (the node that receives the reports). When the base station receives a report and has a command for the tracker, it sends it immediately. If it has no command, it stays silent and the tracker goes to sleep when the window ends.

**MeshCore is slow.** A report goes by flood through repeaters, and each repeater waits a random time before it forwards the packet. The command takes the same long way back. With 3–5 hops it can take tens of seconds. Therefore:

- `T_listen` is configurable and its initial value is set by measuring latency in a real network, not by an estimate.
- The command should not go out by flood again. A flood packet carries the path it took, so the base station can reply directly along it (direct routing). That is faster and loads only the repeaters on the path.
- The window does not have to be 5 minutes. Even 2 minutes cost about half the energy of 5 minutes (section 8). The RX duty cycle in ZephCore can help (section 2).
- Consider whether to open the RX window after every report or only after every n-th. It saves battery but lengthens the reaction to a command.
- In TRACK mode, report only as much as needed. Every report is a flood across the whole network (section 9, decent network load).

**Near the owner (0.7.7).** When the owner's phone is close, the mesh is not needed. While a command waits, the app puts it in the phone's Bluetooth advertising: the same sealed command, 21 B in a legacy advert, sealed for the tag's next wake-up that scans. The tag takes it from the Bluetooth scan it already runs for its known devices and acknowledges it in the next report, as with a mesh command. The mailbox runs in parallel, and the same `cmd_seq` on both paths means the slower one is only a repeat. PROTOCOL.md 3.1.

### 3.1 To think about: transmit less when not needed, and more when it matters

Goal: spam the network as little as possible. Everything should be configurable in detail through a **usage profile** and changeable remotely (a command from the app or a DM). For now only a draft, none of it is implemented.

**1. Do not transmit when the position has not changed.**
- A report is sent only on a move of more than `move_m` (e.g. 100 m, but at least 2× `hacc`), or once per `heartbeat_h` (e.g. 6–24 h), so the owner knows the tracker is alive and has battery. Done in 0.7 as parked quiet: after 3 wake-ups within 100 m, one report every 6 h (PROTOCOL.md 2.1).
- Catch: the X1 and the L1 have no accelerometer, so we learn about movement only from a GPS fix. A fix costs energy even without transmitting. The saving is on the network, smaller on the battery.
- Since 0.7 a tracker that does not transmit still listens: every wake-up without a report is a check with a short receiver window at a time the app computes from the key, so a command reaches a quiet or parked tracker within one interval (PROTOCOL.md 2.1, 3). The window is 30 s by default (0.7.3; 60 s in 0.7.2), longer on slower radio presets (scaled by the airtime of a command packet, at most 180 s), and the receiver stays on while the GPS searches. At hourly checks it costs about 2 mAh a day. Since 0.7.3 the tracker can also report only at every n-th wake-up and only listen at the others (no GPS, no transmission), and commands go by flood unless the sender heard the report directly.
- Privacy: silence reveals that the tracker is standing still. This is negligible compared to what a regular report reveals.

**2. Do not transmit when the owner is near it (against car theft).**
- A short BLE scan (e.g. 2–4 s) before the report. If the owner's phone is visible, the report is not sent: the car is moving, but the owner is driving.
- Phones change their BLE address, so one of two things is needed:
  - **(a)** the tracker and the phone pair (bond) and the tracker recognizes the random address through the phone's IRK, or
  - **(b)** the LibreTag app broadcasts its own BLE beacon with a rotating token derived from `K`, which the tracker can verify.
- (b) is more robust, because it does not depend on OS pairing. On iOS, however, an app in the background can broadcast BLE only in a limited way.
- Energy: a scan costs a few mA for a few seconds. At an hourly interval it is negligible, but it needs measuring.
- Risk: a thief holding the owner's phone (a robbery) switches reporting off. That is why the emergency mode below, which is triggered independently, should work.

**3. Usage profiles.** Presets that set everything at once and can be changed one parameter at a time:

| Profile | Interval | Movement / heartbeat | Owner presence | Note |
|---|---|---|---|---|
| Car (anti-theft) | 60 min, 2–5 min on movement without the owner | yes / 24 h | yes, silent | powered from 12 V, battery is a backup |
| Jacket / backpack | 60 min | yes / 12 h | optional | saves battery |
| Guard (child, senior) | 15–30 min | no | no | regularity matters more |
| Luggage | 60 min, silent on a plane | yes / 6 h | no | switch the radio off on acceleration or altitude (if there is a sensor) |
| Animal | 10–30 min | yes / 6 h | no | |

**4. LOST and "I am being abducted" (emergency mode): the opposite, more reporting and with context.**
- More frequent reports (e.g. every 2–5 min) and context along with the position:
  - names and count of BLE devices around and the strongest RSSI,
  - Wi-Fi networks (SSID/BSSID) on devices that can do it: ESP32, or LoRa chips with Wi-Fi scanning like the LR1110. For the LR2021 in the X1 this needs to be checked. The Wio L1 has no Wi-Fi.
- It also helps where GPS cannot see the sky (building, garage, cellar).
- The context does not fit into a 20 B report. We need a new encrypted frame type ("context report"), or several frames. Still encrypted for the owner.
- "I am being abducted" should be possible to switch on from the tracker as well (e.g. a long or triple press of the button on the L1), not only by command, and it should not be possible to switch it off by the presence of the owner's phone.
- Everything configurable remotely, so that battery can be saved:
  - interval,
  - how long the mode lasts,
  - what is collected (BLE, Wi-Fi, nothing),
  - battery limit ("below 15 % back to the power-saving mode").
- Mind law and privacy: collecting names and addresses of other people's devices is personal data (GDPR). Do it only in emergency mode, store nothing, send only to the owner, and document it.

**5. Remote configuration.**
- Instead of more fixed commands, one general command `SET_PARAM` (key u8, value u16/u32), plus `SET_PROFILE`.
- Via DM the same as text: `set move_m 150`, `profile car`.
- The tracker confirms the change in the next report, as today (cmd_seq).

## 4. Report format

Original sketch below. The binding format is in `PROTOCOL.md` (20 B plaintext, 37 B whole packet).

Content before encryption (goal: fit into one MeshCore packet):

| Field | Size | Note |
|---|---|---|
| version | 1 B | |
| counter / sequence | 4 B | replay, ordering, gap detection |
| fix time | 4 B | Unix time from GNSS |
| lat, lon | 2×4 B | int32, 1e-7 ° |
| altitude | 2 B | GNSS or barometer |
| accuracy (HDOP / estimate in m) | 1 B | |
| speed, heading | 2 B | optional |
| battery | 1 B | % or mV/20 |
| mode, flags | 1 B | e.g. "no fix", "movement detected" |

About 25 B of plaintext in total; with the header, ephemeral key and MAC on the order of 60–80 B. Transmission takes ~0.7 s (depending on the preset).

## 5. Cryptography and privacy

Goals:

1. Only the owner (and devices the owner authorizes) can read the position.
2. An outside observer of the network cannot link two reports from the same tracker (unlinkability). Otherwise a person's movement could be assembled from public MeshCore packets even though the position is encrypted.
3. Only the owner can send commands. A thief who finds the tracker cannot abuse it to track the owner.

### What follows from the MeshCore source (`src/Mesh.cpp`)

- **`RAW_CUSTOM` and unknown payload types are not forwarded by repeaters in a flood** ("don't flood route these (yet)"). So a custom packet type does not get through the network.
- `ADVERT`, `ACK`, `PATH`/`REQ`/`RESPONSE`/`TXT_MSG`, `ANON_REQ` and `GRP_TXT`/`GRP_DATA` are forwarded by flood. The repeater does not check the content (except the signature of an advert), a correct structure is enough.
- **Direct-routed packets** are forwarded by the repeater regardless of type, if it is next on the path.
- `GRP_DATA` = `[channel hash 1 B][MAC 2 B][encrypted text, multiple of 16 B]`. It carries no identity of the sender.
- The retransmission delay on a repeater is random, 0 to 5 × airtime × `tx_delay_factor` (default 0.5 for flood, 0.3 for direct). With ~0.4 s airtime that is on average ~0.5 s of waiting + 0.4 s of transmission per hop. With 5 hops this comes to ~5–8 s one way, if the channel is free.
- A repeater can have non-regional flood forbidden (`REGION_DENY_FLOOD` on the wildcard region). Then `TRANSPORT_FLOOD` with a region code has to be sent.

### Decision v0 (details in `PROTOCOL.md`)

- **The tracker has no MeshCore identity** and never sends an advert. The rotating-identity problem goes away, because there is nothing to rotate.
- **Report = flood `GRP_DATA`**, externally indistinguishable from an ordinary group message. The channel hash is a pseudorandom byte derived from the secret and the report counter, so it changes with every report. The encrypted block has the same shape as MeshCore encryption (2 + 16·n B).
- The keys for encryption and MAC are derived from the pairing secret `K` and the report counter. So every report has its own key. The base station keeps a window of expected counters.
- **Command = direct packet** along the reversed path of the report. Externally it is an ordinary `REQ` with random hashes. It is authenticated by an HMAC from `K`, bound to the counter of the report it answers, which also protects it against replay.
- **No separate ACK.** The tracker acknowledges the command with a flag in the next report.

### ⚠ Open problems we have to solve

A rotating identity alone is not enough. The tracker also reveals the following, and every item has to be solved, otherwise encrypting the position is pointless:

1. **Hashes in the MeshCore packet header.** The header carries short hashes of the destination and source, or the channel hash (1 B). If the base station or the channel has a stable hash, all reports go "to the same address" and can be linked. *Proposal v0:* `GRP_DATA` with a channel hash rotated on every report. `RAW_CUSTOM` is out of the question, repeaters do not forward it by flood. Still to verify:
   - How rare `GRP_DATA` packets are in a real network. If there are few, even a rotating hash gives them away. The alternative is `GRP_TXT`, which is far more common.
   - A collision with an existing channel (1/256 per report). A node with such a channel will try to decrypt the packet, and the 2-byte MAC passes with probability 1/65536. Then the app would show it a meaningless message. Rare, but possible.
2. **Regular period.** A packet at exactly every 60 min is a fingerprint even with a changing identity. We need random jitter on `T_report` (e.g. ±10 %) and have to verify whether it is enough when there are few other nodes nearby.
3. **Command from the base station.** A direct-route command after a report links the tracker with the base station. It is rare (normally the base station is silent), but it has to be designed so that it does not create a stable pattern.
4. **Radio parameters and behavior.** The same preset and preamble as ordinary nodes, no advert name, no advert flood on identity change.
5. **Path in a flood packet.** The path a report took reveals the approximate position of the transmitter to everyone who sees the packet. This is a MeshCore property and has to be at least documented in the threat model.

### Misuse for tracking people (decided 2026-11-15)

The tracker is invisible to phones (it does not transmit BLE outside the short windows below) and gives the tracked person no warning, so it can be misused to track a person. We accept this: MeshCore is a permissionless network, and anyone can build the same tracker without any protection from commonly available hardware. A protection in LibreTag (e.g. a DULT advert) would not really prevent misuse and would weaken the main purpose, protection against theft. The tracker stays invisible for all profiles.

Two exceptions, both short and both naming no tracker: `blepair` advertises only when the owner asks for it on the cable, and since firmware 0.8.0 a paired tracker advertises its Bluetooth console for 3 minutes after a reboot (PROTOCOL.md 6.2), from a private address with a salted tag only the owner's app recognises. An observer learns "a LibreTag-like device just restarted here". Owners who want none of it set `blecon boot off` and use the cable.

## 6. Receiving side

- **Base station**: a MeshCore node (companion or room server) with custom firmware, or a PC/Raspberry Pi connected to a companion node over USB/BLE/TCP. Tasks: recognize rotating identities, decrypt, store, keep the command queue.
- More base stations (at home, at a friend's, a server connected to the MQTT bridge of the MeshCore network) raise the chance that somebody catches the report.
- **Storage and display: Traccar.** An open-source server for tracking GPS devices (Java, self-hosted). It has a web map, route history, geofences and alerts (e.g. "car left the garage" → notification), apps for Android and iOS, and supports hundreds of protocols. The simplest is the OsmAnd protocol: one HTTP GET with `id`, `lat`, `lon`, `timestamp`. So the base station only decrypts the report and sends it to Traccar, and there is no need to write our own map. Traccar also has a project `google-find-hub-sync`, which pulls positions from Google Find Hub into it, so it could show both the MeshCore and the Google positions of the same tag.
- Commands: first a CLI on the base station, later an app/PWA.

### 6.1 Map straight from your own mesh node (idea from Roadbeacon)

Roadbeacon reads MeshCore channel datagrams through the **MeshCore Open local API** (`ws://127.0.0.1:8787/api/v1`, fork jooray/meshcore-open, branch `feat/local-api`). The companion node stores channel messages, and the app can pick them up later (`since` cursor). For LibreTag:

- Reports in "public mode" (6.2) go to the hashtag channel `#libretag` with their own `data_type`. During development from the dev range `0xFF00–0xFFFF`, then request an allocation like Roadbeacon (`0x0150`).
- The app (web/Android, possibly as another layer in Roadbeacon) subscribes to `#libretag` through the local API, decrypts reports with the key `K` and shows them on a map. It works without internet, your own companion node is enough.
- Stealth reports (without a channel) do not work this way, the companion firmware drops them (channel hash does not match). They need the KISS base station.

### 6.2 Public mode: any node posts the report to Nostr

Problem: the tracker leaves Slovakia for Czechia, to a different network with different parameters. My base stations do not hear it there. Solution: a **gateway** = any node with internet that receives the report and sends it to Nostr relays. The gateway cannot decrypt anything, it only forwards.

**Isomorphic event (like Roadbeacon):** the compact frame on the radio can be reconstructed byte by byte into a signed Nostr event. So every gateway produces the same event with the same `id`, and relays discard duplicates on their own.

- Key for report `n`: `sk_n = HMAC(K, "LT1-nostr" || u32(n))` mod n(secp256k1), `pk_n` is the corresponding x-only pubkey. **Every report has a different pubkey.** An outsider cannot link them together, the owner can compute them in advance and asks relays with the filter `authors=[pk_n … pk_n+W]`.
- Event: own regular kind (TBD), `created_at` = report time, fixed tags (e.g. `expiration`), `content` = base64 of the sealed report (the same 35 B block as in stealth mode, without the hint byte). Everything is determined by the triple `(K, n, report)`.
- BIP-340 signature with zero `aux_rand`, so it is deterministic. The tracker produces the signature itself (the nRF52840 can handle secp256k1).
- Frame on the radio: `flags 1 | pk_n 32 | created_at 4 | blob 34 | sig 64` = **135 B**. In the `#libretag` channel with MeshCore channel encryption the payload is ~147 B (compared with 35 B in stealth mode), airtime about 3–4× longer.
- Before publishing, the gateway verifies the signature (against spam), like the Roadbeacon bridge. Take rate limits over from Roadbeacon.
- **Gift wrap (NIP-59) is not needed and does not fit.** The random ephemeral key and time in a gift wrap break determinism. Unlinkability is provided here by the rotating `pk_n`, and the content is unreadable anyway.
- Who will be a gateway: a LibreTag base station in `--gateway` mode, the Roadbeacon bridge (extended with `#libretag`), MeQ/MQTT observer nodes.

**Reassessment (2026-11-15): isomorphic event vs. event signed by the gateway.**

The isomorphic event costs 101 B of overhead on the radio for every packet (`pk_n` 32 + `sig` 64 + time 4 + flags 1). In a maximum MeshCore packet (184 B of payload, ~176 B of plaintext after channel encryption) only ~70 B of content then fits. With several "surroundings" packets (6.6), the overhead is paid for each. The tracker also needs a clock for `created_at`, and without a GPS fix it does not have one, which is exactly when it sends the "surroundings".

Variants:

| | A: isomorphic (so far) | B: gateway signs with its own/ephemeral key | C: convergent key (proposal) |
|---|---|---|---|
| overhead on the radio | 101 B | ~4 B (tag) | ~4 B (tag) |
| same event from all gateways | yes | no (relays store N copies, dedup is done by the app by tag) | yes, within the time window |
| tracker signs (secp256k1, energy, code) | yes | no | no |
| tracker needs a clock | yes | no | no |
| integrity for the owner | signature + MAC | MAC | MAC |

**C: convergent event.** From the received packet `P` (LibreTag payload without the channel envelope), the gateway builds the event completely deterministically:

- `sk = HMAC-SHA256("LibreTag gateway v1", P)` mod n, BIP-340 signature with zero `aux_rand`;
- `content = base64(P)`, tags `[["x", hex(tag)]]` (+ `expiration`), fixed kind;
- `created_at` = time of reception rounded down to 15 min.

All gateways that heard the packet in the same 15-min window produce **the same event with the same `id`**, and relays discard duplicates as in A. At a window boundary at most two versions arise; the app merges them by tag.

- `tag = HMAC(K, "LT1-x" || u32(n) || u8(j))[0:4]` travels in the packet (4 B). The owner computes the tags in advance and asks relays with the filter `#x`. An outsider cannot link them together. 4 B is enough: the owner asks for dozens of tags, and false matches with foreign packets are rejected by the MAC.
- The signature proves nothing about origin here (anyone who heard the packet can derive the key), but it did not in A either: anyone can make a key pair. Against spam it is protected by the fact that a gateway publishes only what it received over the radio (airtime is the limit), plus rate limits. The owner verifies the authenticity of the content with the MAC, as in A.
- If relays rejected ephemeral keys, the gateway adds NIP-13 PoW; the nonce search from zero is deterministic too, so the `id` stays the same.
- The gateway does not need to know anything except the envelope of the `#libretag` channel (public). Stealth packets (6.4, random channel hash) do not go through a gateway, as in A.

Thanks to this, one packet can carry ~150 B of content instead of ~70 B, or 55–75 B with 60–80 B packets (fewer losses in collisions).

**⚠ Weakness of C: deletion by outsiders.** The event key can be derived by anyone who read the event on a relay (content = `P`). So they can sign a NIP-09 deletion request (kind 5), and relays that honor deletion drop the event. The attack costs nothing and can be done en masse on all LibreTag events. In A this is not possible (the key is known only to the tracker and the owner), and in B neither (only the gateway has the key).

**B with deduplication in the gateway** (proposal by J. B.): before publishing, the gateway checks its relays for an existing event with the same `x` **and the same content**, and if there is one (on at least 2 relays), it does not publish. So relays usually get 1–2 copies and nobody outside can delete them. The content has to be compared too, not only the tag: otherwise an attacker would publish junk with a stolen tag and honest gateways would stay silent. The attack "I publish first and then delete" remains; hence "at least on 2 relays", and copies from multiple gateways do no harm.

**Recommendation (corrected): B with deduplication in the gateway.** Overhead on the radio as C (4 B), the tracker does not sign and does not need a clock, and outsiders cannot delete events. Drop A, not C.

**Cryptography for packet `j` of report `n` (draft LT2, replaces part of sections 2–3 of PROTOCOL.md):**

```
kd(n, j)  = HMAC(K, "LT2-pkt" || u32(n) || u8(j))     # 32 B
  k_enc   = kd[0:16]
  k_mac   = kd[16:32]
x(n, j)   = HMAC(K, "LT2-x"   || u32(n) || u8(j))[0:4]  # lookup tag
ct        = AES-128-CTR(k_enc, IV=0, plaintext)
mac       = HMAC(k_mac, "LT2" || x || ct)[0:10]
P         = x || ct || mac                              # LibreTag payload
```

- **How the app recognizes its packet:** it computes `x(n, j)` in advance for the window `n ∈ last_n+1 … last_n+W` and `j ∈ 0 … J−1` (with W = 64, J = 4 that is 256 tags). It asks relays with the filter `{"kinds":[…], "#x":[…]}`, and on the radio looks for `P[0:4]` in the same table (replaces the 1 B `report_hint`). Match → verify `mac` (constant time) → decrypt. When the app was offline for a long time and `n` ran past the window, it moves the window forward until it finds a match.
- **Confidentiality:** every `(n, j)` has its own key, so the zero IV in CTR does no harm. Condition: the tracker never sends **different** content under the same `(n, j)`. A retransmission must be byte-for-byte the same and the counter must survive a restart (checkpoint +16 in flash). Today's bug of formatting the flash on every start would, with the key kept, mean repeating `n` and reusing the keystream; what happened instead was that a new key was created, which is safe, it only broke the pairing.
- **Integrity:** a 10 B MAC = 2⁻⁸⁰ per attempt; forgery offline is not possible (without `K` it cannot be verified). A gateway or relay cannot change the content, only drop, delay or repeat it.
- **Replay:** a repeated old packet is genuine, only old. The app orders by `n`, not by the event's `created_at`; "last seen" = highest `n`. So an attacker cannot forge a newer position, only delay messages.
- **Unlinkability:** `x`, `ct` and `mac` all look random and change with every packet; the packet length is fixed. An observer sees only "a LibreTag packet", the time (rounded) and, from the path in the mesh, the approximate area (as so far).
- **A 4 B tag is enough:** the chance of a foreign packet randomly matching one of the 256 tags is 256/2³² ≈ 6·10⁻⁸ per packet, and the MAC rejects it anyway.
- **Theft of the tracker (open):** a thief who reads out the flash has `K` and reads the **entire past history** (including the owner's home) and can send false reports. I propose a ratchet: `K_{d+1} = HMAC(K_d, "LT2-ratchet")` once a day (or every 64 `n`), the tracker deletes the old key. The owner keeps `K_0` or ratchets too. The past is then protected (forward secrecy); future reports after a theft cannot be protected.

**Requirement: one universal bridge (2026-11-15).** One or two bridges should run in the network and support everything, not a separate bridge for each project. The change to variant B concerns **LibreTag reports**, not the bridge as a whole:

- **Ready-made signed events stay.** The bridge continues to publish events that were signed by the sender and can be reconstructed from the frame byte by byte (isomorphic format): e.g. a DM from the tracker to the owner on low battery (readable in any Nostr client) and **Roadbeacon** (kinds 1315/1316, compact frame 117–163 B, the roadbeacon project). It verifies the signature and publishes without change.
- **LibreTag LT2 reports (variant B):** the bridge publishes under a random key for each event with a lookup tag, with deduplication.
- **Direction from the internet to the mesh** (commands, replies) is likewise shared. State today (`android-bridge/.../Bridge.kt`): it exists only for NIP-04 DMs (kind 4) addressed to a registered device from an allowed author, **off** by default (`downlinkDm = false`). The bridge keeps a DM in a queue (max 8 per device, 24 h) and sends it when it catches a report from that device, so to recognize the report it needs the **whole `K`** of the tracker (`libretagKey`): so it can read positions and send commands. Fragmentation, limits (6 DMs/h, 4 per device), deduplication with other bridges (if somebody has already sent the DM, it does not send). Roadbeacon today goes only mesh → Nostr.
- **What the universal bridge should be able to do in the Nostr → mesh direction:**
  - **LT2 command mailbox:** the app seals a command for a future `n` and publishes it under tag `x(n)` (unreadable without `K`). The bridge keeps the tags of waiting commands, and when it catches a report with the same `x` in the mesh, it sends the command in the RX window. The bridge does not need `K` (solves points 7 and 8b of the review).
  - **DMs for devices in the mesh** (Roadbeacon users without internet, other nodes): a NIP-17 gift wrap is large for the radio (~1.2 kB, 7–8 packets), NIP-04 reveals relationships. Decide case by case; for sleeping devices rendezvous via a tag, not via `K`.
  - **Roadbeacon relay → mesh:** broadcast reports from relays for an area (geohash) into `#roadstr` for drivers without internet, with a limit and only for the area where the bridge stands.
  - All with airtime limits and deduplication between bridges (listen whether somebody has already sent it).
- Structure: a shared core (reception from the mesh, verification, deduplication, rate limits, queue and publishing to relays, ledger) + a handler for each frame type (`data_type` / channel). A new project = a new handler, not a new bridge.
- Privacy for events signed by the tracker (a DM links the owner's npub with the device) is the owner's choice; the bridge only carries it.

**DM on low battery (decided 2026-11-15):**

- The tracker sends a DM **only** on a really low battery (once below 10 %, once below 5 %), never otherwise. Gift wrap (NIP-17) no: ~1.2 kB = 7–8 packets, practically 12 with losses, the network cannot bear that.
- **A known key, not a one-time one:** `dm_sk = HMAC(K_0, "LT2-dm")` mod n, derived at pairing from the original `K` and stored in the tracker separately (the ratchet does not change it). A NIP-04 DM with short text = 1–2 packets, isomorphic frame, published by any bridge.
- **The app sends the welcome DM at pairing directly over the internet** (not through the bridge and mesh): NIP-04 kind 4 signed with the same `dm_sk`, no expiration ("Your LibreTag tag \"<name>\" is paired. Follow this account so low-battery alerts reach you and don't land among message requests."). It goes to the app's relays plus the owner's inbox relays (kind 10050 `relay`, kind 10002 read relays; at most 6 extra, public `wss://` only), one short connection each; a relay that asks for NIP-42 AUTH gets an AUTH event signed with `dm_sk`. With it the app publishes a minimal kind 0 for the tracker key, `{"name":"LibreTag","about":"Messages from your LibreTag tag (battery alerts).","picture":"https://cypherpunk.today/libretag/app/icon-512.png"}` (no tag name, no location, nothing else), again on every "Send welcome message again" (kind 0 is replaceable). The app keeps `welcomeSentAt` per tag, set only when at least one relay accepted the DM; the tag menu has "Send welcome message again" (shows how many relays took it, or each relay's reason), and the app offers it once for owned tags that never had a welcome accepted (from 0.7.5; the web app has the same action in Edit tag from 0.1.4). Some clients (Primal) hide DMs from accounts the user doesn't follow, so the last step of Add tag and the tag menu ("Follow on Nostr", from 0.7.9) show a "Follow your tag on Nostr" card: the tag's npub, Copy, and "Open in a Nostr app" (`nostr:npub…` intent, njump.me when no app takes it). The web app shows the same card in Edit tag (from 0.1.8: `nostr:` link plus an njump.me link).
- Cost: on relays it is visible that the tracker's key occasionally writes to the owner's npub (content encrypted). With one or two messages per battery life, acceptable. A stolen tracker can send false DMs with `dm_sk`; future reports after a theft cannot be protected anyway.

**Bridge decisions (2026-11-15):**

- **Downlink by command type.** Since 0.7.3 every command, LOST or not: zero-hop if the bridge heard the report directly, otherwise flood, scoped to the region (PROTOCOL.md 4). Before, an ordinary command went direct along the reversed path up to 3 hops and nothing further away. Commands are rare, so the flood costs little, and the path may have changed since the report.
- **Bridges agree through Nostr** (an ephemeral event "I take x, I heard it over h hops", 1–2 s, the fewest hops wins), not through the mesh.
- **Roadbeacon relay → mesh:** off by default; if on, only on request, as a summary from the bridge (6–8 B per report, 5 in one 53 B packet), never voices, zero-hop or region, with hard limits. Assigned to the Roadbeacon session (no PR, spec + implementation in Roadbeacon and the roadstr bridge).
- **Command mailbox:** the bridge sends only a command it knew before catching the report; 1 downlink per `x`.
- **Low battery, two levels:** at ~25 % only a flag in the report and the DM is sent by the app (or a notifier) over the internet, 0 B extra in the mesh, so that the user has time to charge. Below 10 % (the app probably not running) a DM signed by the tracker with the known key over the mesh (see above).
- **The main bridge runs on a server with a KISS node** (sees paths, can do direct and zero-hop); the phone is only supplementary (uplink, zero-hop downlink).
- **What runs where (agreed with the `meshcored` project):** the daemon `meshcored` (Rust, a separate project) holds the MeshCore node over USB (companion or KISS) and exposes Local API v1 like the meshcore-open fork; it knows nothing about Nostr or LT2. The bridge logic = Python `nostrmesh` (`host/src/nostrmesh/`, already has companion, Local API and KISS adapters) as a Local API client, as a service (launchd or systemd). Android `android-bridge` supplementary, shared JSON vectors. Requirements for the Local API: `path`, `pathHashSize`, `hops`, `snr`, `rssi`, `routeType` on reception; optional `path` in `channel.sendData` (missing = flood, empty = zero-hop, otherwise direct); `packet.raw` / `packet.sendRaw` in KISS mode (stealth reports and direct REQ); airtime per 60 s / 1 h and a hard cap; send result; noise floor and radio state.
  - **Agreed with `meshcored` (2026-11-16):** fields `snrDb`, `rssiDbm`. `hello.capabilities` is a top-level array of strings: `tx_path_data`, `tx_path_text`, `rx_path`, `rx_meta`, `raw_rx` (`packet.raw`), `raw_tx` (`packet.sendRaw`), `tx_result`, `airtime`, `duty_cap` (error `budget`), `noise_floor`. Without the capability, do not use the extension (an old server ignores `path` and sends a flood). Radio: **companion firmware primarily** (upstream since v1.16 can do raw RX of all packets, raw send and sending with a path, 0 = zero-hop), KISS only in v2. Roadbeacon's bridge uses the same names.

**When public mode:** it is more expensive (airtime, network load) and reveals that somebody here is transmitting a LibreTag report (though not which tracker). Proposal: stealth is the default. Public mode is switched on by a command, in LOST mode, outside "home" regions, or on every k-th report.

### 6.3 GART (gart.io)

According to the research (NIP-GART, kind 694), GART is an **emergency SOS app, not a tracker**. The plaintext is JSON `{status, lat, lng, ts, msg}` encrypted with NIP-44 for the group's shared key (`p` tag). The sender has their own key, gift wrap is not used. The recipient sees the position only as text with an "Open in Maps" button. The app is proprietary and in beta. The NIP is public, but without a license.

It does not suit ordinary position reporting: status `ACTIVE` triggers a full-screen alarm. It would make sense as an **alarm**: when the tracker goes into LOST, or when the base station detects a theft (geofence), the **base station** (which knows the plaintext) sends kind 694 `ACTIVE` to the GART group. The tracker does not need to do anything for this. Low priority.

### 6.4 Automatic switching of radio parameters by position

Networks in different countries run on different presets (frequency, BW, SF, CR) and may require a regional transport code. The tracker has to change this **autonomously from the GNSS position**:

- the firmware has a table of regions (polygon or bbox → preset + region/transport code), updatable by a command or when flashing the firmware,
- after every fix the region is evaluated, with hysteresis (e.g. 2 consecutive fixes or 5 km beyond the border), so that it does not jump back and forth when traveling along a border,
- in the band near a border the tracker can send the report in both presets,
- without a fix the last known preset stays; after a long gap it tries presets in turn,
- base stations and gateways at the border can listen on several presets (two KISS radios).

**We want to do this, including the region.** Switching the radio is not enough. The tracker should also send with the **region of the country** it is in (MeshCore flood scope, transport codes), so that local repeaters forward it according to the rules of the local network and so that it behaves politely. For example in Slovakia Slovak radio settings and the region `sk`, in Czechia Czech settings and the region `cz`.

- **Country from GPS:** a function that determines the country from coordinates, directly in the firmware and offline. It need not be accurate to meters, a simplified border map or a grid is enough (e.g. Europe, or only neighboring countries in the first version).
- **Table:** country → radio preset (frequency, BW, SF, CR) + region name. Updatable by firmware, later also by a command.
- **Border area:** near borders two networks often work. In the band around the border (e.g. 10–20 km) it is fine if the tracker sends the report **on both** networks (two presets, two regions). It costs a second packet, but only at the border.
- **Size (verified, `docs/research/region-research.md`):** the region does **not** cut anything from the message content. Transport codes are 4 B extra in the header. `MAX_PACKET_PAYLOAD` (184) counts only the payload, the limit for the whole packet is 255 B. Our packets will be 41, 57 and ~185 B and all fit. The cost is only a 4 B longer transmission time.
- **Careful with two regions with the same radio settings (verified in ZephCore):**
  - The tracker will happily send both copies. It sends with the raw radio, nothing stops it. Even `Mesh::sendFlood` does not refuse, it only marks the packet as seen.
  - The problem is in the repeaters. For GRP_DATA a repeater first records the hash of the packet (type + payload, without transport codes) as seen (`Mesh.cpp:303–304`) and only then, when forwarding, decides by region (`routeRecvPacket` → `allowPacketForward`, `RepeaterMesh.cpp:575`).
  - So a region B repeater that heard the copy for region A **did not forward** it (foreign region), but remembered it. It then drops the copy for region B as a duplicate. So "if it heard it, it already forwarded it" holds only for repeaters that forward both regions (or old versions without regions).
  - Solution: the second copy must have a different payload, that is another counter `n` (the app can handle that). Or in such a border area send **one copy without a region**, which both networks forward unless they forbid it.
  - Where the networks differ in radio settings (e.g. SK and CZ in a different frequency, SK and AT/DE in a different SF), nodes of one network do not hear the other copy at all and the problem does not arise. But the tracker has to switch the radio between the copies.
- **Region:** in Slovakia definitely `sk`. **If we are not sure about the region, we send without a region** (unscoped flood). Repeaters forward it unless explicitly forbidden (`region denyf *`).
- **Presets:** the source is the official configuration that the MeshCore app draws on: `https://api.meshcore.nz/api/v1/config`, key `suggested_radio_settings`. State as of 2026-11-14:

  | Country | Frequency / BW / SF / CR | Hash in path | Region |
  |---|---|---|---|
  | Slovakia | 869.618 / 62.5 / 7 / 5 | 2 B | `sk` |
  | Hungary | 869.618 / 62.5 / 7 / 5 | 2 B | ? (no region) |
  | Czechia (Narrow) | 869.432 / 62.5 / 7 / 5 | 2 B | ? (no region) |
  | Switzerland | 869.618 / 62.5 / 8 / 8 | 1 B | ? |
  | EU/UK (Narrow), default for other EU countries | 869.618 / 62.5 / 8 / 8 | 1 B | no region |

  Austria, Germany, Poland and Ukraine are not in the list, EU/UK (Narrow) without a region applies until we learn more. MeshCore Open has its own older, hard-coded list (without SK, with CR 5 for EU Narrow). Go by the official configuration. Generate the table in the firmware from it and update it at builds.
- **Hash size in the path:** SK, HU and CZ use 2-byte hashes in the packet path (`path_len = 0x40` on send). The tracker takes it from the country preset. Python (`Packet`), the C core and the return path of the command (the base station copies the hash size and transport codes from the report) all handle it.
- **Without a fix:** the last known country applies. After a long gap try presets in turn.
- **Without a table for the country:** stay on the last preset without a region (or with the default), never transmit on an unknown setting.
- **Consequence for the receiving side:** the app and bridges must know that a tracker in a foreign country can be heard only on the local network. Public mode and the Nostr bridge help there.

**Implementation v1 (2026-11-14):**

- Country map: raster 0.05° (lat 34–72, lon −25–45), rows RLE-coded, 20 KB flash. Generated by `firmware/tools/gen_country_grid.py` from Natural Earth 1:10m (public domain). Known countries extend ~3 cells into the sea, so that harbors work too.
- Countries: SK, HU, CZ, NL, PT and CH with their own preset, other EU/EEA, UK and microstates get EU/UK (Narrow). The others (UA, BY, RU, TR, Balkans outside the EU) are unknown: the tracker stays on the last network.
- After every fix: the country at the point and on two circles (15 km and 7.5 km, 8 points each). Countries with the same radio settings are merged into one network; if they have different regions, **one copy without a region** goes out. A network with a different radio gets a second copy with a new `n` through a temporary radio override, then the tracker returns to the home network and listens there. Commands are accepted for both `n`.
- The home network (radio, hash, region, country) is saved to flash, so it applies after a restart without a fix as well.
- **Change 2026-11-15 (border area):** in a test city near a national border, with the 15 km band the second copy (EU Narrow for the neighbouring country) went out on almost every wake-up and districts near the border lost the home region. Now:
  1. the band is **3 km** (only the outskirts next to the border, not the whole city);
  2. **hysteresis:** when both networks are candidates, the tracker stays on the one it is on; it switches only when it is more than 3 km inside another country;
  3. **second copy only when the home network is quiet:** if in the last 3 listening windows the tracker caught no packet on the home radio. Repeaters of the home network can often be heard just across the border, so a home-network copy is enough there (and a home-network bridge sends it to Nostr). After a network change the state is not known, the first report at the border goes to both.
- `radio`, `pathhash`, `region` switch to manual mode, `network auto` turns it off. `where <lat> <lon>` shows the decision for any place.

### 6.5 Position without GPS: repeaters and Bluetooth (experiment 2026-11-15)

Indoors (garage, apartment, car trunk) GPS gets not a single satellite (`gps`: `sat:0` for a full 15 minutes). The tracker, however, hears the mesh and Bluetooth even there. We verified both on the Wio Tracker L1 indoors at a test location, with the commands `meshscan` and `blescan` (so far only console output, nothing is transmitted).

**Repeaters (`meshscan 180`):** in 3 minutes 25 packets, two direct neighbors: `a5e1` (RSSI around −90, about 10×) and `b7d2` (RSSI around −90, about 10×). The neighbor is the last hash in the path of a flood packet (the repeater that has just transmitted the packet), for zero-hop adverts the sender itself. No advert came from them in that time: repeaters send them rarely, so the position has to be supplied by the phone.

- The public map `https://map.meshcore.io/api/v1/nodes` (72 thousand nodes, 56 MB) had several candidates for each of the two hashes. Radio parameters and the country from the last known position left one each. (The hashes here are placeholders, not the real ones.) So a 2-byte hash is enough; with a 1-byte one (EU Narrow) there would be many more collisions and only context would help.
- Estimate: a weighted center of the neighbors (weight by SNR/RSSI), accuracy on the order of hundreds of meters to kilometers. Trilateration from RSSI is unreliable with LoRa in a city (reflections, buildings), so more "around these repeaters" than a point. Better with more neighbors and with reports from several wake-ups.
- Privacy: the phone should not send the query "where is repeater a5e1" (it would reveal where the tracker is). Either contacts from your own MeshCore node (repeaters with positions from adverts), or download the whole map and search locally.

**Bluetooth (`blescan 20`, active scan):** several hundred adverts from about a dozen devices. Most are Apple (`004C`) with a rotating address (RPA, changes ~every 15 min): they reveal no position, only "there are people/iPhones here". A printer (public address) and one accessory (static) had a stable address.

- Stable addresses (public, random static) and names can be compared with databases like WiGLE (which also collects BT/BLE with position), but the query reveals the addresses to a third party and needs an account. Better than nothing for a theft; not for ordinary reports.
- More useful than position: a list of devices in the surroundings ("in the garage there is a car with Bluetooth XY, a phone from manufacturer Z") as a lead for the police. This is sensitive (personal data of third parties), so only on command in LOST mode.
- An active scan sends scan requests with the tracker's random address; a passive one (`blescan 20 passive`) is invisible, but names often arrive only in the scan response.

**Proposal (not implemented):** when the tracker has no fix, a second "surroundings" packet is added to the report: up to 6 neighbors (hash 2–3 B, RSSI, SNR, count) and in LOST mode up to 6 stable BLE addresses with RSSI and a count of RPAs by manufacturer. ~40–60 B, encrypted like the report. The phone makes the estimate and shows it on the map as a circle, not a point. The BLE controller added 28 KB of flash; a 10–20 s scan is small in energy terms compared with 90 s of GPS.

**Verification of the estimate (2026-11-15, 10 min `meshscan`, true position known):** still only 2 repeaters heard (one under a kilometre away, one a few km away, similar RSSI), dozens of active ones in the surroundings not heard. A model with the same power for all repeaters (attenuation `n = 3`, σ = 8 dB) gave a center more than a kilometre from the truth and the true point **outside** the 95 % circle: the farther repeater stands on high ground with a high antenna, so at the same RSSI it is several times farther. A cautious model (σ = 12 dB, weaker evidence from not hearing) gives 68 % / 95 % circles of roughly 1 / 3 km and the true point inside. Lesson: from RSSI and the repeater map only a rough area; accuracy comes only with fingerprints (below). A third repeater heard from a different direction would narrow the area considerably.

**Requirements (from the test):**

- **App: map with circles when the tracker has no GPS.** A position estimate from heard repeaters (and not-heard active ones nearby) as 68 % and 95 % circles plus a probability density, heard repeaters with RSSI/SNR, not-heard ones nearby in gray. Clearly distinguished from a GPS position. Prototype: a local Leaflet page from the script `locate.py` (50 m grid, log-distance model, marginalization over unknown power, probability of not catching the non-heard ones).
- **Place fingerprints:** every report with a GPS fix + the list of heard repeaters with RSSI is a calibration sample. The app stores fingerprints (position → neighbors, RSSI) and, without a fix, compares them with the current neighbors. First a "known place" (home, work, garage), later calibration of the power of individual repeaters. Optionally shared fingerprints (privacy!).
- **Repeater database in the tracker:** the tracker stores the adverts with positions that it hears (in one 10-minute test 4 came, from a neighbouring country), and when pairing over USB the app uploads the repeaters from the area to it (public map, ~12 B per repeater, QSPI 2 MB). The tracker then sends the estimate itself and the phone need not ask anyone about specific repeaters.

**Last fix in a report without a fix:** the tracker does not need a clock. It sends the position of the last fix and its **UTC time from GPS** (a fix always carries it); the app computes the age as reception time − fix time and draws a circle of reach. Store the last fix with its time in flash so that it survives a restart. In IDLE the fix is usually up to an hour old and the car may have moved far in the meantime, so a rough guide; more precise in TRACK and LOST.

**Fixed along the way:** after our wait expired, GPS kept searching (every request for a fresh fix extended its window), in the test 15 minutes in one go. Now the tracker puts it to sleep when the wait expires (`gps_stop_acquiring`) and the long cold-start window (300 s) applies only once.

### 6.6 Encoding the "surroundings" into several packets (proposal)

Requirements: (a) every received packet carries something useful even when the others are lost; (b) redundancy that fills in lost packets. Both at once are met by **Priority Encoding Transmission** (PET, Albanese et al. 1996): the data is divided into levels by importance and each level is encoded with an MDS code (Reed-Solomon over GF(256)) with a different threshold.

- A level `i` with threshold `k_i` can be decoded from **any** `k_i` received packets (which ones does not matter).
- Level 1 has `k_1 = 1`: it is whole in every packet, so **any single packet** gives the summary.
- Every packet carries the summary + one "share" from each higher level. The more packets arrive, the more levels can be assembled.

A plain systematic RS (3 data + 2 parity) does not meet this: if exactly the 2 parity packets arrive, nothing can be made of them. PET is a generalization: repetition of the most important part and RS with a rising threshold for the rest.

**Example: 4 packets of 60 B useful content**

| Level | Threshold | Content | Size | In a packet |
|---|---|---|---|---|
| 1 | 1 of 4 | summary: no fix, age of the last fix, 2 strongest repeaters, BLE counts (total / Apple RPA / stable) | 12 B | 12 B |
| 2 | 2 of 4 | next 6 repeaters, 4 strongest stable BLE addresses | 46 B | 23 B |
| 3 | 3 of 4 | other BLE addresses, short names | 75 B | 25 B |

1 packet: the summary. 2: + repeaters and main BLE. 3 or 4: everything. Level 3 tolerates the loss of 1 packet, level 2 of two, the summary of three. Cost: 240 B of transmission carries 133 B of information (without encoding, 3 packets would carry 180 B, but the loss of any of them would permanently take its third).

**Further**

- **Every packet separately encrypted and authenticated** (key from `n` and the packet index `j`), of the same length (stealth). RS is computed over the plaintext before encryption. Header inside: version/type, `j`, number of packets, level thresholds (the layout is fixed by version, so 1–2 B is enough).
- **Spread in time:** outages in the mesh are correlated (collision, busy repeater). Packets will go out with random gaps of several seconds, not right after each other.
- **Carousel across wake-ups:** the surroundings change slowly. In level 3, which items go out shifts on every wake-up, and the app assembles the surroundings from the reports of the last ~30 min. Even without encoding the whole list arrives over time; encoding speeds up the completeness of a single wake-up.
- **No delta encoding against the previous report:** the tracker gets no acknowledgments, so a delta against a lost report would be unreadable. At most "same as report `n−1`" as 1 bit in the summary when nothing changed (saves whole packets).

**Compact notation of items (general compressors like deflate give nothing on 60 B):**

- Repeater: hash 2 B + 1 B (RSSI 4 bits: −130…−55 dBm in 5 dB steps; SNR 4 bits: −20…+10 dB in 2 dB steps) = **3 B**. In networks with a 1 B hash the tracker does not know the 2nd byte from the path (only from a zero-hop advert); the app then distinguishes by context.
- Stable BLE address: 6 B + 1 B (RSSI 4 bits, address kind 2 bits, "has a name" 1 bit) = **7 B**. Public addresses: OUI (3 B) through a table of the ~250 most common manufacturers → 1 B index, 5 B in total.
- BLE with a rotating address: only counts by manufacturer (Apple, Samsung, Google, Microsoft, other) of 4 bits each = 2–3 B.
- Names: up to 8 characters in a 6-bit alphabet (6 B), only for top devices, in the lowest level.

**Ceiling and cost:** a MeshCore packet has at most 184 B of payload, after the channel header, MAC and LibreTag header ~160 B remain; shorter packets (60–80 B), however, are lost less often in collisions. Every packet goes by flood across the whole network, so the "surroundings" only when there is no fix, not on every wake-up (e.g. every 3rd), and more often in LOST mode. RS for `k ≤ 4` is a few hundred bytes of code on the nRF52 (a Cauchy matrix over GF(256)); the app decodes by inverting a small matrix.

## 7. Compatibility with Apple Find My / Google Find Hub (phase 2)

Both AirTag and Google tags work over **BLE**, not LoRa. Phones around catch the BLE advertisement of the tag and send the encrypted position to the cloud. The X1 has BLE (nRF52840), so it can do this alongside MeshCore.

**Apple Find My.** An iPhone forwards the position of every advertisement in the right format. It cannot verify it, because it is encrypted with the tag's public key. The format has been reverse engineered (OpenHaystack, 2021). Positions are downloaded via an Apple ID and Anisette (a Mac, or macless-haystack without a Mac). It is unofficial and Apple can block the account. For nRF52 there is `heystack-nrf5x`. In MeshCore there is an open PR #2731 (June 2026) that adds from it a Find My beacon with daily key rotation to MeshCore nodes. That is exactly our case and we can build on it.

**Google Find Hub (FMDN).** The protocol is publicly specified only partially and the rest is reverse engineered (GoogleFindMyTools by Leon Böttger). The tag has to be registered to your own Google account through a reverse-engineered API. Firmware for ESP32 works, for Zephyr/nRF52 it was untested at the time of writing. The author calls it very experimental, and in early 2025 it was reported that the registration expires every 3 days. According to the PETS 2025 study, Android by default reports tags only in busier areas where several phones see it, so in the countryside the Google network adds little.

**Both networks at once: yes.** The project **Everytag** (nRF52/54, Zephyr) emulates both an AirTag (up to 40 keys, rotation every 10 min) and Google FMDN at the same time, by alternating advertisements. The Google key does not rotate there yet, so it is a stable identifier. Commercial tags usually choose one network at pairing.

Risks: Apple/Google can block it at any time, ToS, and anti-stalking alerts (DULT) may announce "an unknown tracker is moving with you" to precisely the car thief. A BLE advertisement costs little (on the order of tens of µA at a 2 s interval), but it needs measuring.

### 7.1 How we would implement it

Common: on the nRF52840 in Zephyr the BLE stack is available. Each network gets its own **extended advertising set** (`CONFIG_BT_EXT_ADV`, `BT_EXT_ADV_MAX_ADV_SET`) with its own random address and its own interval (~2 s). The payload changes on key rotation. On a tracker without a phone the MeshCore BLE companion does not run, so BLE is free.

**Apple Find My (offline finding):**

1. At pairing, the base station / app generates a set of P-224 key pairs (Everytag: up to 40, rotation every 10 min, cyclic) or a master key with derivation like the original AirTag (ANSI X9.63 KDF + P-224 multiplication on the device, Everytag does not do this).
2. The tracker transmits: BLE address = first 6 B of the public key (top 2 bits set to 1), manufacturer data `0x004C`, type `0x12`, length `0x19`, status byte, the remaining 22 B of the key, 2 top bits of the first byte, hint.
3. An iPhone nearby encrypts its position with the public key (ECIES on P-224) and uploads it to the Apple cloud under the SHA-256 of the key.
4. The base station downloads reports via the Apple endpoint (Apple ID + Anisette, e.g. macless-haystack in Docker), decrypts them with the private keys and sends them to Traccar as another position source.
5. Code: build on `heystack-nrf5x` / MeshCore PR #2731 (beacon), Everytag (Zephyr, rotation), macless-haystack (download).

**Google Find Hub (FMDN):**

1. Registration: GoogleFindMyTools (Python) creates a new device in the Google account and generates its *Ephemeral Identity Key* (EIK, 32 B).
2. The tracker transmits Eddystone service data (`0xFEAA`), frame type `0x40`, a 20 B EID and hashed flags. EID = the x coordinate of a point on SECP160R1 derived from AES-256(EIK, time counter). It changes every 1024 s. Everytag so far transmits a static EID, GoogleFindMyTools for ESP32 has the rotation.
3. Android phones nearby upload the encrypted position to Google.
4. The base station downloads reports via GoogleFindMyTools or Traccar `google-find-hub-sync`.

**Order:** Apple first (verified by several projects, static keys are enough for the first tests). Google only afterwards, because the registration is fragile (expiry after 3 days, reported in 2025) and in the countryside the Google network adds little.

**Privacy:** BLE beacons can be tracked passively just like LoRa reports. Rotating the keys and the address is mandatory, a static key is not acceptable.

## 8. Energy budget (X1, estimate)

Inputs: Seeed table of measured X1 currents (for Meshtastic) and the LR2021 datasheet.

| Component | Current | Source |
|---|---|---|
| battery | 1100 mAh, I count 85 % usable | Seeed |
| LoRa RX (whole device) | 8.55 mA | Seeed measurement |
| LoRa TX EU868 (22 dBm) | 110.5 mA | Seeed measurement |
| GNSS (AG3335M) | 15.8 mA | Seeed measurement |
| fix (Seeed assumption) | 15 s | Seeed |
| LR2021 sleep | 0.47 µA (1.28 µA with RTC) | datasheet |
| MCU during GNSS fix | ~3 mA | estimate |
| **sleep of the whole X1** | **unknown** | I assume 20 / 50 / 300 µA |

For comparison: Seeed gives a battery life of "up to 5 days". This comes almost entirely from MeshCore/Meshtastic keeping the radio in RX all the time (8.55 mA × 24 h ≈ 205 mAh/day). So sleep makes the whole difference.

IDLE, report once an hour, TX 2 × 0.22 s (the report and its retransmission on the live preset SF7 / 62.5 kHz / CR 4/5; the first version of this table used SF11 / 250 kHz, 0.64 s, which no live network uses). Recomputed 2026-11-17 with `host/src/libretag/energy.py` (`daily_mah`, X1, `tx_count=2`, GNSS idle 10 µA):

| Scenario | mAh/day | Battery life |
|---|---:|---:|
| pessimistic: RX 5 min, fix 60 s, sleep 300 µA | 32.4 | ~4 weeks |
| original proposal: RX 5 min, fix 30 s, sleep 50 µA | 22.6 | ~41 days |
| RX 2 min, fix 30 s, sleep 50 µA | 12.4 | ~2.5 months |
| RX 60 s, fix 15 s, sleep 50 µA | 7.1 | ~4.5 months |
| RX 30 s, fix 15 s, sleep 20 µA | 4.6 | ~6.5 months |
| RX 30 s, fix 15 s, sleep 300 µA | 11.4 | ~2.7 months |

The shorter airtime saves about 0.6 mAh a day in every row. RX, GNSS and sleep are unchanged, so the conclusions below still hold.

Other modes:

| Mode | Battery life from a full battery |
|---|---:|
| TRACK (GNSS always on, TX every 2 min; the 0.7.6 flood cap makes it hourly after about 6.5 h) | ~2 days |
| LISTEN (RX always, GNSS on request) | ~4.5 days |
| LISTEN + GNSS always | ~1.5 days |

Conclusions:

- **With a 5 min RX window, RX is the largest item** (0.71 mAh per hour, GNSS ~0.16 with a 30 s fix, TX ~0.014). Shortening the window is the biggest saving.
- The second biggest unknown is the **sleep current of the whole board**. If GNSS, barometer and the charging circuit are not properly disconnected, it can easily be hundreds of µA and those start to dominate.
- A GNSS hot start shortens the fix from tens of seconds to a few seconds, if the chip keeps the ephemeris in the backup domain (probably `RTC_PWR_EN`). Ephemeris is valid for ~2–4 h, so with an hourly interval the fix should be mostly hot or warm.
- The real limitation for a six-month battery life: Li-Po self-discharge (~2–3 %/month) and temperature. In a car in summer it is commonly above 60 °C inside, which is over the X1 operating limit. In winter the capacity drops.
- For the car: X1 from 12 V via a USB adapter, the battery serves only as a backup when the socket is cut off after the engine is switched off.

## 9. What needs to be researched

### Hardware (X1)
- [ ] **Measure the sleep current of the whole X1** (Nordic PPK2) under ZephCore with all branches off (GNSS, sensors, flash, battery divider).
- [x] Does the GNSS have a backup domain for hot start? **Yes**, `GPS VRTC Enable` (P1.13) separately from `GPS Enable` (P1.11). The current in this state remains to be measured.
- [ ] AG3335M: TTFF cold/warm/hot, current in individual modes, AGNSS / EPO (Airoha) support, protocol (NMEA + PAIR commands).
- [ ] Measure real TX current and time with a MeshCore preset (not Meshtastic LongFast).
- [ ] An X1 revision with an accelerometer: when and whether it will exist.
- [ ] How the X1 is flashed and recovered (UF2 bootloader, SWD pins), so that it cannot be bricked.

### ZephCore / MeshCore
- [x] Does ZephCore support the X1 and LR2021? **Yes**, but untested on hardware (section 2). First step: flash ZephCore onto the X1 and verify that the LR2021 can transmit and receive at all.
- [ ] Does RX duty cycle (sniff mode) also work on the LR20xx driver? What is the average RX current with it?
- [ ] Can ZephCore be put into System OFF and woken by the external RTC (YSN8900), or is System ON idle enough?
- [ ] Power consumption of ZephCore vs. Arduino MeshCore on the nRF52.
- [x] MeshCore packet format and what is in the plaintext → section 5, `PROTOCOL.md`.
- [x] Do repeaters forward custom payload types? **No** (`RAW_CUSTOM` and unknown types are not flooded). Direct yes.
- [x] MeshCore cryptography: AES-128-ECB + HMAC-SHA256 truncated to 2 B. Weak for our purposes (ECB, 2 B MAC). We use our own scheme in the same shape (`PROTOCOL.md`).
- [x] Changing identity at runtime → not needed, the tracker has no identity.
- [ ] Does anything drop frequent reports in TRACK mode (rate limiting, loop detect, regions)?
- [ ] How many repeaters in SK/CZ have non-regional flood forbidden (`REGION_DENY_FLOOD`)? If many, `TRANSPORT_FLOOD` with a region code is needed.
- [ ] How often `GRP_DATA` vs. `GRP_TXT` occur in a real network (for blending in with the traffic).
- [ ] **Latency of flood and direct reply over 1–5 hops** (measure in a real network, tool `libretag sim` through a KISS modem) → `T_listen`.
- [x] Retransmission delay in repeaters: random 0 to 5 × airtime × factor (0.5 flood / 0.3 direct).
- [ ] L1 Pro: can the L76K be fully disconnected or put into backup mode (schematic, CASIC commands)?
- [ ] Which MeshCore preset the SK/CZ network uses (frequency, BW, SF, CR) → airtime.
- [ ] Capturing reports through the MeshCore MQTT bridge / internet gateways.
- [ ] Do similar projects exist in MeshCore/Meshtastic (sensor/tracker role, power-saving firmware on the T1000-E)?

### Privacy (for the open problems in section 5)
- [ ] How to rotate the destination/channel hash so that the base station still receives the reports.
- [ ] How much jitter is enough at a given traffic density.
- [ ] Threat model: an outside observer of the network, a thief with the tracker in hand, misuse of the tracker to track another person.

### Nostr / gateway / regions
- [ ] Presets and regional codes of MeshCore networks in SK, CZ, AT, HU, PL (frequency, BW, SF, CR) → region table.
- [ ] Where to get country borders in a compact form for an MCU (simplified polygons, a few kB).
- [ ] Nostr kind number for a LibreTag report (regular, non-colliding), procedure as with Roadbeacon (kind 1315/1316).
- [ ] Deterministic BIP-340 signature on the nRF52840: library (secp256k1 from Bitcoin Core, micro-ecc cannot do Schnorr), signing time and energy.
- [ ] What the owner's query `authors=[64 pubkeys]` reveals to a relay and how to spread it (multiple relays, overlapping windows).
- [ ] Allocation of `data_type` for `#libretag` in the MeshCore registry.
- [ ] Extend the Roadbeacon bridge or the MeshCore Open local API with a second channel / data type.
- [ ] GART: does it reject GART kind 694 from an unknown ButtonNpub? (only if an alarm through GART makes sense)

### Regulation and network
- [ ] EU868: duty cycle and ERP in the MeshCore EU band (869.525 / 869.618 MHz), maximum report frequency in TRACK.
- [ ] Load on the shared network: how many floods per hour are still decent.

### Apple / Google networks
- [ ] State of MeshCore PR #2731 (Find My beacon), does the Apple network work with custom tags in 2026?
- [ ] Everytag on the nRF52840: can it be merged with MeshCore/ZephCore, power consumption.
- [ ] GoogleFindMyTools: registration expiry, EID rotation, state of the Zephyr firmware.
- [ ] When an iPhone/Android shows a warning about an unknown tracker and how this concerns custom tags.

### Receiving side
- [ ] Traccar: OsmAnd protocol, google-find-hub-sync, notifications.
- [x] Where the base station logic will be: **on a PC/RPi in Python over a KISS modem**. Any MeshCore/ZephCore node with KISS modem firmware sends raw packets to the host and the host transmits through it. So the base station needs no firmware of its own.

### Physical variants
- [ ] Car: 12 V in the trunk, summer temperature, antenna placement (the body shields both LoRa and GNSS).
- [ ] Jacket: shape, charging, water resistance, signal attenuation by the body.

### Law
- [ ] Tracking own property vs. people, GDPR when storing positions.

## 10. Milestones

1. **M0: research**: get ZephCore running on the X1 (LR2021 is untested), sleep current, packet metadata, network latency.
2. **M1: dumb beacon**: ZephCore on the X1, once an hour a GNSS fix + unencrypted position, sleep. Measure consumption and compare with section 8.
3. **M2: encryption + rotating identity + base station + Traccar.**
4. **M3: RX window and commands (Class-A delivery), TRACK/LISTEN modes.**
5. **M4: regions and public mode**: automatic preset switching (6.4), Nostr gateway (6.2), map from a mesh node (6.1).
6. **M5: car variant** (12 V, possibly another device with Wi-Fi positioning, section 2.1).
7. **M6: Find My / Find Hub** (build on PR #2731 and Everytag).

### Prototype status (2026-11-17)

Tracker firmware 0.7.8 (Wio Tracker L1 flashed and running; ESP32-S3 stick without GPS on the bench), Android app 0.8.8 with the UI redesign on `main` (not yet released), web app (PWA) 0.2.3, Android bridge 0.3.2, Python bridge `nostrmesh`, protocol LT2 (`PROTOCOL.md`).

Protocol, host and firmware:

- [x] `PROTOCOL.md`: wire format v2 (LT2): SIV, chains for reads and commands with a ratchet, 4 B tags, neighbors in every report, USB lock. The Python reference, the C core and Kotlin are verified with shared vectors.
- [x] `host/`: Python reference, base station over a KISS modem, command queue, SQLite, Traccar, tracker simulator, energy calculator. The simulator follows the 0.7.6 transmit policy (`policy.py`), and the energy model defaults to the live preset SF7 / 62.5 kHz / CR 4/5.
- [x] ZephCore role `tracker` (`firmware/zephcore-patches/`, base pinned in `firmware/zephcore.lock`, procedure in `firmware/README.md`), runs on the Wio Tracker L1: report, commands, channel `#libretag`, buzzer, slowing down when stationary, keys in a raw flash page, USB lock (`lock`, `challenge`/`auth`, `unpair`).
- [x] Switching of the radio, path hash and region by the country from GPS (6.4), 3 km border area with hysteresis and a second copy only when the home network is quiet. `where` and `network auto|manual`.
- [x] Position without GPS (6.5): `discover`, `meshscan`, `noise`, report with up to 5 repeaters and the last fix with time; the app draws a 68 % / 95 % area from the repeater map (~60 MB, refreshed after 30 days). Accuracy is only an area.
- [x] Low battery: below 25 % a DM from the app from the tracker's key, below 10 % a report and a DM from the tracker over the mesh (`dm4`), once per crossing since 0.7.6.
- [x] Tracker firmware 0.6.0 (protocol side of section 11): quiet in home zones and near the owner's Bluetooth anchors with heartbeat 24 h / cap 8 h, `QUIET` and `next_in` in the report, energy byte, one wake decision `lt_wake_decide` with vectors and a multi-day simulator, `BLE_SCAN` and BLE packets in LOST with full names and maker data, history sync format (`history`, `j = 2/3`), USB unlock ends with the port, `blepair` (IRK experiment). Wi-Fi: protocol and selection.
- [x] Tracker firmware 0.7.0 to 0.7.3: checks (keyed wake-up times from `C_e(n)`, receiver window alongside the GPS, commands sealed for check `k`, report `n + 1` right after), parked quiet (STILL), `CHECKS` flag and parked bit, `check`/`still`/`heartbeat` settings and `SET_IDLE check_s`; timed mailbox entries (`at`, `via`) for bridges. 0.7.1: LOST sends a discover only without a fix. 0.7.3: checks listen 30 s by default, scaled to the radio preset; report every n-th wake-up with listen-only wake-ups in between (`report every <n>`, `SET_IDLE` byte 8); bridges and the app send commands by flood unless they heard the report directly.
- [x] Tracker firmware 0.7.5: fixes a `blescan` buffer overflow with long maker data (garbage rows), and negative indexes in `anchor del` / `zone del`.
- [x] Tracker firmware 0.7.6: transmit policy (PROTOCOL.md 2.5): TRACK and LOST at least every 120 s, at most 200 floods in a rolling 24 h, LOST slows to ×2 after 2 h at one place; no transmission below 3250 mV and a backoff after resets in a row; atomic state with a counter bound in the key page; low-battery report and DM once per crossing; `dfu serial|ble|uf2`; `txcap` line in `status`. Details: `docs/design/fw-0.7.6.md`. Waits for the battery test before it is flashed.
- [x] Tracker firmware 0.7.7: commands over Bluetooth advertising (PROTOCOL.md 3.1). The tag takes a command the owner's phone advertises during a scan it already runs (presence in IDLE with anchors, or LOST). On the bench a payload fed with `blecmd adv` was taken and acknowledged; the radio path from a real phone is not tested yet.
- [x] Tracker firmware 0.7.8: fixes from pairing the Heltec stick: a reboot inside a Wi-Fi zone gives a quiet boot report, an empty Wi-Fi scan is repeated once, `selftest` on a locked console, `owner` answers even when the Nostr self-test failed.
- [x] Tracker firmware 0.9.4: the first report after a boot carries `BOOT`, and without a fix the reset cause and the resets in a row (PROTOCOL.md 2). Built, not yet run on hardware.
- [x] ESP32-S3 tracker without GPS (Heltec Wireless Stick Lite V3, `docs/design/fw-wifi-esp32.md`): Wi-Fi scan, Wi-Fi zones (`wzone`), quiet in a zone, reports with two full BSSIDs, light sleep. On the bench only; leaving a zone was simulated, and the battery was not tested on a cell.

Apps and bridges:

- [x] LibreTag app 0.5 to 0.8.8: pairing over USB with locking of the tracker and automatic unlocking, map and history with a timeline, commands Lost (10 min / 24 h), Track (2, 5, 10, 15 or 30 min for 1 h), Beep, Bluetooth scan, Normal, checks and report intervals, home zones on the map and known Bluetooth devices, Wi-Fi zones for tags without GPS (0.8.6), commands over Bluetooth advertising for a tag nearby (0.8.7), Wi-Fi positioning through BeaconDB, the GrapheneOS proxy or Apple, key backup with a recovery phrase and a copy for the owner's npub (12.2), NIP-17 DMs, signed mailbox entries (0.11.0, PROTOCOL.md 4.2; mailbox PoW before), Keystore-wrapped secrets, GPX/CSV export (0.8.0). Signed release APK (cert `79750fb4…a24d`).
- [x] App UI redesign on `main` (after 0.8.8, not released): status vocabulary in which only Guard is an alarm and places notify on leaving and arriving (each place has its own switches); one Places list for GPS and Wi-Fi places; one Tag settings screen; Wi-Fi lookup set by service and by when (only on tap, in Lost mode, every report without GPS) with a cache and a request budget; a four-step Add tag wizard (plug in, name, where it rests, done).
- [x] Web app (PWA) 0.3.0, over Nostr only (section 12): tags, map, commands and cancelling (signed mailbox, anchors, late entries), backup restore, NIP-17, export. No Wi-Fi lookup.
- [x] Nostr ↔ MeshCore bridge `nostrmesh` (Python) and the Android "Nostr bridge" 0.3.2: LT2 uplink kind 4243, command mailbox kind 4244 with the "knew it before catching" rule, bridge choice, zero-hop/direct/flood downlink, airtime budget on the live preset (Python reads the node's preset when the adapter reports it), a per-first-hop limit on `#libretag` uplink (10 per minute by default). The Android bridge has an outbox kept across restarts, a radio watchdog and an hourly heartbeat (`status.json`), like the Python bridge. Service templates for systemd and launchd in `host/deploy/` (`docs/nostr-mesh-bridge.md` 8.11).
- [ ] Measure on hardware: consumption (PPK2), command latency in a real network, delivery through a foreign bridge, display of NIP-04 in real clients, Bluetooth command delivery from a real phone, the ESP32 stick on a battery and outside its zones.
- [ ] Tracker: repeater database, place fingerprints, BLE surroundings (6.5, 6.6), `SET_PARAM` remotely.
- [ ] Firmware update from the app over USB (serial DFU of the bootloader): the app code is a draft that builds and passes its tests, nothing has run against a device (`docs/design/dfu.md`).
- [ ] Region names of other countries (so far only `sk`), Seeed X1, Zapstore, Find My / Find Hub (M6).
- [ ] To think about (3.1): transmit only on movement, usage profiles.


## 11. Ideas 2026-11-16 (to be worked out)

Brief from the owner, for now only a sketch.

### Decisions 2026-11-16 (after critique, round 2)

These take precedence over the text below where they conflict.

- **Quiet = no report.** A quiet tracker wakes up, takes a fix, makes the decision and goes back to sleep without TX (0.7: it listens briefly at each wake-up, a check; see 11.8). `QUIET` (bit 4 in `flags`, bit 5 reason: zone / owner presence) is carried only by the **last report before the quiet**, so that the app knows the quiet is intentional and the "not responding" alarm waits for the heartbeat.
- **`next_in`** (byte 31) is only in transmitted reports: when the tracker will next be heard from. With `QUIET` it is the heartbeat time. Wake-ups without transmission do not interest the app.
- **Zones:** heartbeat in a zone once per 24 h. Leaving a zone is reported **on the normal schedule** (the next regular wake-up), no extra reports. Without a fix the tracker always reports (fail-open). TRACK and LOST ignore zones. After a long quiet, `n` jumps to the next ratchet epoch.
- **Entering zones by tapping on the map needs no location permission**: the map is centered on the last position of the tracker, the owner taps on the house. Location permission is only for the optional "my location" button.
- **Owner presence (11.2): no beacon from the app.** Primarily passive recognition of **existing** BLE devices of the owner (a ring, watch, other wearables, a beacon in the garage) that broadcast a stable identity. The owner picks the list from the result of the survey (`blesurvey`, in progress). Cap on quiet near the owner **8 h**, then a report regardless of presence.
  Since 0.7.7 the app advertises only while a command waits (PROTOCOL.md 3.1), to deliver it. Delivery does not depend on the advertiser's address: the tag filters on the 4 B command tag. Whether the advert also makes a phone paired with `blepair` count as present is an assumption still to be tested on hardware. It holds only if Android sends this non-connectable advert from a resolvable private address made with the phone's IRK, and a normal app cannot choose the address type. If the address is non-resolvable, advertising a command does not make the phone an anchor. Field test: `docs/design/app-0.8.md`, "Bluetooth command advert".
- **GPS during quiet near the owner:** the recommendation is to take a fix and store it in the history without transmitting (~0.08 mAh per wake-up, a complete track after USB synchronization). Configurable.
- **`BLE_SCAN` command and BLE surroundings in LOST:** candidate for the next release, first the result of the survey (what packet would go out).
- **Listening for commands without a report (11.8 step 2): done in 0.7 as checks.** Zero-hop in a zone: postponed. At home the tracker hardly transmits anyway; a permanent test bridge next to a repeater node is running, but we do not see the benefit now.
- **APPROTECT:** enable where the hardware can do it (nRF52840: Wio Tracker L1, T1000-E, SenseCAP X1, RAK4631). The ESP32-S3 (Heltec V3/V4) has only irreversible eFuses (flash encryption, secure boot), not there for now. APPROTECT alone is not enough while the UF2 bootloader accepts unsigned firmware (which can read out the flash); a signed bootloader or DFU with signatures only is also needed. For the manual: zones and the last position are in the tracker in plain form.
- **Expiration on Nostr** according to Fable: report (4243) 14 d, commands (4244) by type: Beep 1 h, Track and Normal 12 h, Lost 48 h. The relay's write policy has to be moved to 15 d for 4243.
- **Live test of the bridge** (a fixed bridge next to a repeater node): tracker out of range of the mobile node, within range of the network; the app sees it only through Nostr.

### 11.1 Home areas

When setting up the tracker (USB) the owner enters circles (center, radius, e.g. 150 m): house, garage, cottage. When the tracker has a fix in a zone, it **transmits nothing** (default), optionally once per 24 h "I am home, alive". A car parked in front of the house has no reason to report its position.

- Zones are in the tracker (a list in flash, max ~8), the app sends them over USB (`zone add <lat> <lon> <r>`, `zone list`, `zone clear`), not over the radio.
- Quiet in a zone must not switch off a Lost tracker: the `LOST` command ignores zones.
- Open: a zone without a fix (garage without GPS). Decide by the fingerprint of repeaters (6.5) or by the last fix, if it has not moved since according to the accelerometer (the L1 has no accelerometer; the X1 does).
- Related to 3.1 (transmit less when not needed).

### 11.2 Quiet near known Bluetooth devices

A list of the owner's BLE devices (a smart ring, phone, watch, other wearables). If the tracker saw any of them **during the current wake-up** (the BLE scan runs in parallel with the fix search, so the wake-up is not lengthened), it does not send the report: the owner is near it, e.g. driving.

- Identification: phones and most wearables rotate the MAC (RPA). Only what the device itself broadcasts is stable: name, manufacturer data, service UUID, possibly the IRK (if it could be obtained at pairing, which it cannot without bonding). We need to find out what exactly Oura, hearing aids (ASHA/LE Audio) and GrapheneOS broadcast.
- An alternative that works reliably: the LibreTag app on the owner's phone itself broadcasts a BLE beacon derived from `K0` and the time (rotating, `HMAC(K0, "LT2-ble" || floor(t/15 min))[0:8]`), and the tracker recognizes it. An outsider cannot track it, the tracker can. Family members get their own key to share.
- Note: quiet near the owner = if the car is stolen with the owner's phone in it (carjacking), it does not report. Acceptable, LOST overrides it.

### 11.3 Timeline

App: a time slider under the map, a list of reports with time, the selected report is highlighted on the map (point, estimate, repeaters). Day filter. Purely in the app, it already has the data.

### 11.4 Bridge: verify live, event expiration

Test the bridge in a real network (a foreign phone with the bridge, a tracker out of range of the mobile node, the app reading from Nostr). Expiration (NIP-40) already exists: kind 4243 `expiration` +30 d, kind 4244 +48 h, kind 24244 is ephemeral. Decided 2026-11-16: first 7 d, after critique 14 d (a week without the app is exactly the case when the track is needed); see the decisions above.

### 11.5 Command "scan the Bluetooth surroundings"

A new opcode `BLE_SCAN` (u8 seconds): at the next wake-up the tracker does a BLE scan and sends the result (number of devices, the strongest N with a hash of the address or name and RSSI) in the next report or an extra packet (6.6). A button in the app.

### 11.6 History synchronization over USB

The tracker stores every transmitted (sealed) report `P` together with diagnostics (time since boot, how long it searched for a fix, whether TX went through, retransmit) in a circular file in LittleFS (e.g. 1024 records). After USB is connected, the app downloads the history (`history <from n>`) and opens it with the key `K0`: the tracker decrypts nothing, needs no ratchet, only ciphertext is in the tracker. This fills in reports that did not arrive through the network. Diagnostics (without position) are readable directly too (`log`).

### 11.7 Firmware version and service connection

`version` over USB already exists (`LibreTag tracker 0.5.0 on ZephCore …`). After connecting, the app: synchronizes the history, compares the version with the one it bundles (UF2 in the APK), and offers an update. The phone also charges the tracker over USB-C ("service charging"). Until there is DFU from the app, the app at least says "a newer firmware is needed, update from a computer".

### 11.8 Deterministic transmit and listen schedule

**Implemented in 0.7 as checks** (PROTOCOL.md 2.1): the times count from the transmission of the last report, with the jitter from `C_e(n)` (`"LT2-w"`), not from GPS time, and the window grows by 100 ppm of the time since that report. The text below is the original proposal.

Before 0.7 the interval jitter was random (±10 %). Proposal: jitter derived from the key, `offset(n) = HMAC(V_e, "LT2-t" || n) mod jitter`. An observer without the key sees randomness, the app and bridges (with `V_e`) know **when** the tracker transmits and when it listens.

- Even when the tracker is quiet (zone, BLE), it wakes up on the schedule and opens an RX window. The app knows when to send it a command although it has heard nothing from it. The bridge gets in the mailbox (kind 4244) the transmit time (`["at", "<unix>"]`) instead of waiting for a report.
- Listening can be more frequent than reporting (e.g. an RX window every 15 min, a report once per hour), configurable. Cost: RX ~5 mA × 60 s, which at 15 min gives ~0.33 mAh/h extra (compare with the budget in section 8).
- Clock desynchronization: the tracker has time from GPS; without a fix the RTC runs, which drifts (~20–50 ppm, ~2–4 s/day). The schedule is tied to time, not to `n`; the RX window is widened by an uncertainty that grows with the age of the last fix. The app shows the estimate with this uncertainty. If the tracker has had no GPS time for a long time, it switches to "listen after every report" (today's behavior).
- A home zone can be handled with a zero-hop report instead of quiet (no repeater forwards it, only the home node hears it). The question is whether we want that at all.

### 11.9 UI: when I hear it, when it accepts a command

On the tracker's screen: "next report around 14:32 (±2 min)", "can accept a command at 14:32–14:33". From the schedule in 11.8; without it only from the interval and the last report.

### 11.10 Battery life estimate

The app has a series (time, battery %, mode). A regression of the % drop per hour in the current mode (the last days), or a consumption model (section 8) calibrated to the real drop. Li-ion % from voltage is nonlinear (flat middle of the curve), so accuracy will be poor above 80 % and good in the middle. A better input: the tracker sends mV instead of %, or counts wake-ups / TX (a coulomb count by estimate).

### 11.11 Position estimate without any fix

If the tracker never had a fix and the app has seen no fix from it, the prior point for repeater hash collisions is: the position of your own MeshCore node (from the Local API, if the node publishes it in its advert), otherwise the flood path by which the report arrived. Without location permission in Android (the app does not use BLE for location; Android 12+ `BLUETOOTH_SCAN` with `neverForLocation` does not need location). Low priority.


## 12. Web version (PWA), over Nostr only (idea 2026-11-16)

A web app for when the owner does not have their phone (lost it, it ran out of battery, it was stolen together with the car) or wants to watch the tracker from a computer. It has no radio and no USB, and communicates **only over Nostr**: it reads reports (kind 4243) and sends commands to the mailbox (kind 4244). Delivery depends on bridges near the tracker.

- **Features:** list of trackers and a map, area estimate without GPS (planned; 0.2.3 does not download the repeater map and shows no area), timeline, commands (Lost, Track, Beep, Normal) through the mailbox, delivery status ("waiting for the next report", "confirmed"). No pairing and no USB settings (zones, BLE anchors, history synchronization).
- **Keys:** the PWA needs the tracker's `K0` (or `V_e` + `C_e` from a given epoch). Ways it can get there:
  1. **Backup on Nostr** (for the scenario "I lost my phone"): after pairing, the app stores the trackers' keys as a NIP-44 encrypted event for the owner's npub (e.g. parameterized replaceable kind 30078, `d` tag without the tracker's name). The PWA logs in with a signer (NIP-07 in the browser, NIP-46 bunker, Amber via nostrconnect) and decrypts the backup. A new app on a new phone restores it the same way.
  2. A QR code from the app (a one-time transfer into the browser on a computer).
  3. A manually entered backup code (password + encrypted blob).
- **In the browser:** keys in IndexedDB, optionally locked with a password (WebCrypto). Logging out deletes everything. No server except static hosting (e.g. nsite) and relays; position estimation and decryption run in the browser, the position goes to no service (not even a geocoder).
- **New version:** the PWA versions its releases, checks for a new version and refreshes itself (a rule for all PWAs), with a short update notice.
- **Risks:** a key in a browser is protected less than in the app (extensions, a shared computer), hence the optional password, a short session on a foreign computer and the option to share read-only (`V_e` without `C`). The ratchet protects the past only in the tracker, not in the backup (the backup holds `K0`).
- **Design:** the same visual and communication language as the Android app (PRODUCT.md), order of work: visual language of the app → presentation website → PWA.

### 12.1 Key backup format (v1)

Option 1 above, changed so that the backup can be found with **only a passphrase**: no npub, no signer, no login. Implemented in `android/app/src/main/kotlin/app/libretag/backup/` (`Scrypt.kt`, `KeyBackup.kt`); test vector `docs/vectors/key-backup-v1.json` (one full example at real parameters, checked by `KeyBackupTest` and cross-checked against Python's `hashlib.scrypt`).

**Passphrase.** Normalised as: Unicode NFKC, then every run of whitespace (the ECMAScript `\s` set: tab, LF, VT, FF, CR, space, U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000, U+FEFF) replaced by one space, then leading and trailing whitespace removed. Case is kept. In JavaScript: `p.normalize("NFKC").replace(/\s+/g, " ").trim()`. The app refuses (strength `TOO_SHORT`) a passphrase with fewer than 4 words (split on the single spaces) or fewer than 20 code points after normalisation.

**Derivation.** All strings UTF-8.

```
S    = scrypt(P = normalised passphrase, salt = "LibreTag key backup v1", N = 2^16, r = 8, p = 1, dkLen = 64)
d    = hex(HMAC-SHA256(S, "LT-backup-d"))[0:32]          (16 bytes as 32 lowercase hex chars)
sk   = int(HMAC-SHA256(S, "LT-backup-sk")) mod n          (secp256k1 order, 32 bytes big-endian; 0 is rejected)
pk   = BIP-340 x-only public key of sk (hex)
kEnc = HMAC-SHA256(S, "LT-backup-enc")                    (AES-256 key)
```

`HMAC(S, label)` uses the 64-byte `S` as the key and the ASCII label as the message. scrypt at these parameters needs 64 MiB and about 0.4 s on a laptop JVM; the app runs it off the main thread.

**Plaintext.** UTF-8 JSON, keys in this order (readers ignore unknown keys):

```
{"v":1,"created":<unix s>,"owner":"<npub1…>" or null,
 "tags":[{"name":"…","k0":"<64 lowercase hex>","type":"person|thing|vehicle","mark":"<color>-<symbol>"}]}
```

Since 12.2 (v1.1, still `"v":1`; readers ignore unknown keys and keep them when they rewrite a tag they don't otherwise change): top-level `"d"` (the `d` tag of the npub copy, 32 lowercase hex) after `owner`, and per tag, after `mark`, `"updated"` (unix s) and then the tag's settings for the web version, all covered by `updated`. Exact shapes, in this order:

```
"updated":      <unix s>
"interval_min": <int>                          report interval set on the tag
"check_s":      <int>                          listening at checks, seconds (0 = off)
"still":        {"after":<int>,"hb_h":<int>}   parked quiet after N reports in one place, heartbeat in hours; null = off
"use":          "things" | "people"
"fw":           "<x.y.z>" | null               firmware the tag last reported over USB
"zones":        [{"name":"…","lat":<float>,"lon":<float>,"r_m":<int>}]   app 0.9 may add "notify_leave":<bool>, "notify_arrive":<bool> (left out = the type's default)
"anchors":      [{"label":"…","kind":"public|static|rpa|nrpa|irk"}]     labels and kinds only, never addresses or IRKs
"alerts":       {"silent":<bool>,"low_battery":<bool>,"left_zone":<bool>,"guard":<bool>}   silent = overdue report
"guard":        {"lat":<float>,"lon":<float>,"r_m":<int>,"since":<unix s> | null} | null   null = guard off
```

A removed tag is `{"k0":…,"removed":<unix s>}`. The settings are written all together or not at all (an older writer leaves them out); `d` and `updated` are left out when unknown. Example: `docs/vectors/key-backup-v1.json`, case `bip39` (without settings).

**Content.** `base64( 0x01 || nonce || AES-256-GCM(kEnc, nonce, plaintext, AAD = UTF-8 of d) )`: version byte 1, a random 12-byte nonce, the ciphertext with its 16-byte GCM tag appended (WebCrypto's output layout). Standard base64 with padding.

**Event.** Kind 30078 (NIP-78, parameterized replaceable), `pubkey = pk`, `created_at` = now, tags `[["d", d], ["alt", "LibreTag encrypted key backup"]]`, content as above, signed with `sk`. Saving again replaces the previous event on the relays (same pubkey and `d`).

**Restore.** Derive `S`, `d`, `pk`, `kEnc` from the passphrase; REQ `{"kinds":[30078],"authors":[pk],"#d":[d]}`; among the returned events keep those with kind 30078, `pubkey == pk`, the `d` tag equal to `d` and a valid id and signature; take the newest (`created_at`, ties broken by the lowest id). Empty content means the backup was deleted (older events must not bring it back). Otherwise check the version byte and decrypt; a failing GCM tag means "unreadable". Then validate each `k0` before using it.

**Delete.** Publish an empty replacement (kind 30078, same `d` and tags, content `""`) and a NIP-09 kind 5 event `[["a", "30078:<pk>:<d>"], ["k", "30078"]]`, both signed with `sk`. Relays that ignore NIP-09 still keep only the empty replacement.

**Threat model.** Because the backup is findable with the passphrase alone, `d` (and `pk`) is a public oracle: anyone can collect every kind 30078 event with an `alt` of LibreTag and test passphrase guesses offline against `d`, without talking to a relay. The only defence is the cost of each guess (scrypt, 64 MiB per attempt, which hurts GPUs) and the size of the passphrase space, hence the rule of at least 4 words and 20 characters (the app should suggest a generated 5–6 word diceware phrase). The backup holds `K0`, so the ratchet does not help: whoever cracks the passphrase reads all past and future reports of those trackers and can send commands. The passphrase also controls the backup (anyone who knows it can replace or delete it). Nothing ties the event to the owner's npub; the `owner` field is only inside the ciphertext.

**PWA.** `scrypt-js` (or `@noble/hashes/scrypt`) for `S`; WebCrypto `HMAC` (SHA-256) for `d`, the `sk` seed and `kEnc`; the reduction mod n with `BigInt`; `@noble/secp256k1` (`schnorr.getPublicKey`, `schnorr.verify`, `schnorr.sign`) for `pk`, verification and new events; WebCrypto `AES-GCM` (`iv` = nonce, `additionalData` = UTF-8 `d`, `tagLength` 128) to decrypt and encrypt. The PWA must reproduce every field of `docs/vectors/key-backup-v1.json`, including `event_id` (the vector signs with all-zero auxiliary randomness).

### 12.2 Backup v1.1: generated phrase, and a second copy for your npub (decided 2026-11-16)

Two ways to restore, both carrying the same plaintext (12.1). The app keeps both copies up to date after every pairing, rename, removal or settings change that the plaintext holds. Both events are kind 30078 (parameterized replaceable), so relays keep only the newest per `(pubkey, d)`: there are no old copies to clean up.

**A. Phrase (no account, works when the phone with the signer is gone).**

- The phrase is a **BIP-39 mnemonic** (English wordlist, 12 or 24 words, valid checksum). People choose weak passwords, so a free-form passphrase is no longer accepted. The app **generates** a fresh 12-word phrase (128 bits) and shows it once, with a check that the user wrote it down (pick word 3, 7, 11).
- Alternative for people who already keep a Bitcoin seed safe: derive a **BIP-85** child mnemonic (application 39', English, 12 words, an index of their choice) in their own wallet or signing device and type that child phrase into LibreTag. LibreTag never asks for, derives from or accepts a wallet's master seed. The UI says so in plain words: "Never type your wallet's seed here. Use the phrase LibreTag made, or a BIP-85 child phrase from your wallet."
- LibreTag cannot tell a child phrase from a master seed. Mitigation: the derivation salt is LibreTag-specific (a phrase typed here does not reveal a wallet's keys to anyone who sees the backup event), the phrase is never stored by the app or the PWA (only the derived `S` for the session, wiped on logout), and the warning above.
- Derivation, plaintext, content, event, restore and delete are exactly 12.1, with the normalised mnemonic as the passphrase (words lowercase, single spaces). The 12.1 strength rule is replaced by "valid BIP-39 mnemonic". With 128 bits of entropy offline guessing against `d` is hopeless; scrypt stays (cheap insurance, and the vector stays valid).
- New vector: `docs/vectors/key-backup-v1.json` gains a `bip39` case (a generated test mnemonic, never a real wallet's), plus invalid-checksum and wrong-length cases that must be refused.

**B. Nostr login (sign in and see every tracker without setting anything up).**

- The same plaintext JSON, encrypted with **NIP-44 v2 to the owner's own pubkey** (conversation key with itself) through the owner's signer: NIP-55 (Amber, Android app), NIP-07 (browser extension), NIP-46 (`bunker://`, `nostrconnect://` QR). Amber's quirks (no result, instant cancel, permissions, reconnection) are the hard part, not the crypto.
- Event: kind 30078, `pubkey` = owner, tags `[["d", <32 random hex chosen at first save, kept in the plaintext as "d">]]`, content = NIP-44 payload. **No `alt` tag, nothing that says LibreTag**: from outside it is one of many app-data events.
- Finding it on a new device: REQ `{"kinds":[30078],"authors":[owner]}` on the owner's relays (NIP-65 write relays, plus the app's defaults), keep events whose content is a NIP-44 v2 payload (base64, version byte 2), ask the signer to decrypt them newest first (at most 10), accept the first whose plaintext parses as the 12.1 JSON with `"v":1` and a matching `"d"`. Ask Amber for a remembered `nip44_decrypt` permission so this is not a prompt per event.
- Publish to the owner's write relays and the app's default relays. Empty content = deleted; delete also sends NIP-09 kind 5 with the `a` coordinate.
- Merge between devices: each tag in the plaintext gains `"updated": <unix s>`; on save, the app first reads the current backup and merges per tag (newest `updated` wins, removals kept as `{"k0":…,"removed":<unix s>}` tombstones for 90 days), then writes. Both copies (A and B) carry the result.

**What the PWA and the app offer:** "Sign in with Nostr" (B) as the first, easy choice; "Restore with your phrase" (A) as the one that works when the signer was on the lost phone; a single tracker by QR or pasted key. Reading needs only `K0`, never a login: B is about finding the keys, not about reading reports.

## Sources

- Seeed wiki X1: https://wiki.seeedstudio.com/meshtracker_x1_intro/
- Seeed X1 consumption table: https://files.seeedstudio.com/wiki/SenseCAP/MeshTrackerX1/X1_for_Meshtastic_Consumption_Test_and_Battery_Life_Calculation.xlsx
- X1 review (AG3335M, MeshCore port): https://nodakmesh.org/blog/seeed-sensecap-meshtracker-x1-review
- LR2021 datasheet: https://www.mouser.com/pdfDocs/61979758LR2021_V1_1_datasheet.pdf
- ZephCore: https://github.com/liquidraver/ZephCore
- MeshCore PR #2731 (Find My): https://github.com/meshcore-dev/MeshCore/pull/2731
- GoogleFindMyTools: https://github.com/leonboe1/GoogleFindMyTools
- Everytag: https://github.com/vasimv/Everytag
- heystack-nrf5x: https://github.com/pix/heystack-nrf5x
- Google FMDN, PETS 2025: https://crysp.petsymposium.org/popets/2025/popets-2025-0147.php

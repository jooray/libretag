# Tag diagnosis: why is it quiet, what happened (design, 2026-11-20)

Status: implemented in app 0.12.0 and firmware 0.9.4 (2026-11-20), except the "here" advert (8.2),
which the owner postponed. Decisions on the open questions are in 10. Items marked "bench check" in
10 were built and unit tested but not yet tried on hardware.
Reads on top of `docs/design/app-brief.md` §3 (one status sentence per tag), `data/Status.kt`,
`data/StatusLines.kt`, `PROTOCOL.md` 2.1, 2.5, 3.1, 4.1, 6, 6.2 and `docs/design/dfu.md`.
The English copy below is a draft: it goes through the humanizer and the Sonnet translation pass
before it lands in `strings.xml` (AGENTS.md).

## 1. Problem

Today a late report has one outcome: 30 minutes after the expected time `Status.of` returns
`SILENT`, the row gets an orange band ("Silent for 3 h · expected hourly") and
`LibreTagApp.checkSilent` posts a notification. The app does not look at anything it knows besides
the last report. Two cases from this week:

1. The owner thought the Wio was quiet because it saw his phone. When the tag goes quiet near the
   owner it sends one `QUIET` report with `QUIET_OWNER` and then nothing for up to 8 h. If that one
   flood is lost, the app sees an overdue tag while the tag behaves as designed. The app has no way
   to check the theory.
2. The real cause was different: the tag sat in its UF2 bootloader for 10 hours after a flash. The
   bootloader waits with no timeout (`dfu.md`). The app already recognises a bootloader on the cable
   (`DfuDevices.isBootloader`), but only the Service screen says so, and nothing links it to the
   silent tag.

The same holds for reset loops, an empty battery, a report a bridge heard but the app never got,
relays the phone cannot reach, a disconnected own node and a schedule the app has wrong.

Goal: when a tag is late, the app says the most likely reason in one sentence, shows what it based
that on, and offers the one action that settles it. It warns only when a warning is earned.

## 2. Principles

1. **Only evidence downgrades a warning.** A silence becomes calm when the phone itself observed
   something (the tag's Bluetooth advert, the tag on the cable, a bridge receipt) or the tag said so
   (a `QUIET` report inside its heartbeat). A guess ("it has known devices, so it is probably with
   you") changes the sentence and the offered action, never the level. A stolen tag must not be
   hidden behind a friendly guess.
2. **The phone's own blindness is not the tag's silence.** With no working receive path the app
   cannot know, says that, and holds the "silent" notification until a path is back and the relays
   were asked again.
3. **One sentence, one cause.** The headline is the first matching cause in the order of §5. The
   others are listed in the checklist of the detail, not stacked on the row.
4. **Nothing new on LoRa.** All inputs are local or already on the relays. The two firmware
   additions (§8) use a free bit and a byte that is redundant in one report, and Bluetooth.
5. **Pure and tested.** Like `Status` and `StatusLines`, the model is a pure function of a snapshot,
   so every rule has a unit test and the list, the detail, the map and the notification agree.

## 3. Inputs

Everything is collected into one immutable snapshot. Nothing here needs a new permission except
`ACCESS_NETWORK_STATE` (normal permission, no prompt).

| Input | Source today | New work |
|---|---|---|
| last report: flags, mode, battery, `next_in`, quiet reason, repeaters, `via`, hops | `Tracker.positions` (`Position`) | none |
| next expected time, check plan | `Status.nextAt`, `Status.checkPlan` | none |
| schedule the app believes | `Tracker.intervalMin`, `reportEvery`, `checkS`, `cableDirty`, `settingsReadAt` | none |
| pending command | `Tracker.pending` | none |
| battery trend | `BatteryModel` | a "projected empty before now" helper |
| history, counter gaps | `positions[].n` | `CounterGaps` (§4.3) |
| relay health | `RelayPool.connected`, `RelayPool.status` | remember since when 0 relays are connected |
| phone online | none | `ConnectivityManager` default network callback in `LibreTagApp` |
| own node link | `mesh.state` (`MeshApiState`), last packet time (Connections screen) | none |
| receive path history | none | `PathLog`: since when some path has worked without a break |
| bridge receipts for newer reports | `BridgeLookup`, `BridgeTargets` (only for reports already seen) | query for the next counters (§4.4) |
| Bluetooth sighting of the tag | `BleConsoleScanner.seen` (foreground, in memory) | persist per tag, background filtered scan (§4.1) |
| USB attach state | `UsbHelper`, `UsbState`, `FirmwareUpdate.bootloaderOnCable` | app-wide `CableWatch`, ESP32 download mode probe (§4.2) |
| tag health over the cable or the Bluetooth console | `TrackerStatus` (no `txcap` fields yet) | parse `resets=`, `last_reset=`, `boot_delay=`, `tx=`, `n=`; keep as `Tracker.health` |
| firmware version | `Tracker.firmware`, `LibreTagApp.REPORT_V2_FIRMWARE`, `BundledFirmware.version` | none |
| phone clock sanity | fix time of a report with `HAS_FIX`, `created_at` of relay events | compare with the phone's time on receipt |

```kotlin
// data/Diagnosis.kt (pure)
data class DiagContext(
    val online: Boolean,
    val relaysUp: Int, val relaysAll: Int,
    val mesh: MeshLink,                 // NEVER (not set up), UP, DOWN
    val pathUpSince: Long?,             // some receive path works since (ms); null = none right now
    val backfillAt: Long?,              // when the relays last answered the query for this tag's window
    val cable: Cable,                   // NONE, TAG (this tag, console open), BOOTLOADER, ESP_DOWNLOAD
    val cableOther: Boolean,            // a bootloader is on the cable and it may be this tag (§4.2)
    val receipt: ReceiptSeen?,          // a known bridge heard a report newer than lastN: (atMs, hops)
    val clockSkewMs: Long?,             // phone clock against the last trusted time source, null = unknown
    val minFirmware: String,            // REPORT_V2_FIRMWARE
)
```

Kept on `Tracker` (serialised, all optional):

```kotlin
val health: TagHealth? = null       // from `status` over USB or Bluetooth
val bleSeen: BleSeenRec? = null     // last advert of this tag the phone heard
val bleBoots: List<Long> = emptyList()  // times of distinct console windows (new salt), newest 6
val dfuStartedAt: Long? = null      // FirmwareUpdate.start() for this tag
val bootRecs: List<BootRec> = emptyList() // boots learned from reports (§8.1) or counter gaps, newest 6

data class TagHealth(val at: Long, val n: Long?, val resets: Int?, val lastReset: String?,
    val bootDelayS: Int?, val txOk: Boolean?, val quiet: String?, val intervalMin: Int?, val reportEvery: Int?)
data class BleSeenRec(val at: Long, val kind: String /* "console" | "here" */, val rssi: Int, val info: Int = 0)
data class BootRec(val at: Long, val n: Long, val cause: String? /* last_reset word */, val resets: Int?, val inferred: Boolean)
```

## 4. How each observation is made

### 4.1 Bluetooth: the phone sees the tag

What exists: for 180 s after a boot, and after a `BLE_CONSOLE` command, a locked tag sends the
console advert (company `0xFFFF`, type `0x25`, salt, 6 B tag from `usb_key`). `BleConsoleScanner`
recognises it, but only while a console screen is in front, and forgets it.

Changes:

- **Persist.** Every recognised advert writes `Tracker.bleSeen`. A new salt for the same tag is a
  new window: append its time to `bleBoots`, unless the app has a `BLE_CONSOLE` command pending or
  confirmed for that tag in the last 15 min (then the window is ours, not a boot).
- **Background.** One filtered scan with a `PendingIntent` (`BluetoothLeScanner.startScan(filters,
  settings, pendingIntent)`, `SCAN_MODE_LOW_POWER`, filter on manufacturer data `0xFFFF` with first
  byte `0x25` or `0x26`), delivered to a new `BleSightReceiver`. It runs while the owner has at
  least one tag with keys, Bluetooth is on and Nearby devices is granted (`BLUETOOTH_SCAN` is
  already declared `neverForLocation`). No location permission. A switch in Settings turns it off
  ("Notice my tags over Bluetooth"). The filter is matched in the controller, so the phone does not
  wake for other adverts.
- **What a console advert means without us asking:** the tag restarted within the last 3 minutes,
  within Bluetooth range of this phone. Two or more windows in an hour mean it restarts again and
  again (§5, `RESTARTING`).
- **Active check, "Check if it's here".** The existing remote console (`BleConsoleHelper.openRemote`,
  `BLE_CONSOLE` by the phone's command advert, PROTOCOL 3.1) is the probe: the tag takes the command
  at its next wake-up (every wake-up scans since firmware 0.9.3), opens its window, the phone sees
  the advert, connects and reads `status` and `quiet`. That settles "with you", the quiet reason,
  the reset counters and the schedule in one go. Known hole: the phone seals the command for the
  last report it knows. If the tag sent report `n + 1` and it was lost (the missed `QUIET` report of
  case 1), the tag ignores a sealing for `n`. Fix in the app, no firmware change: while a tag is
  overdue, `BleCommandBeacon` also advertises the same command sealed for `n + 1` (`k = 0xFFFF` and,
  with a known interval, the checks `k` computed from `t0` = the expected time of `n + 1`, the
  current one and its two neighbours). That is up to four more advertising sets; phones with fewer
  sets rotate every 2 s instead of 8 s for an overdue tag. The tag's 1.5 s scan holds 5 or 6
  advertising events per set, so rotation still gets through within a few wake-ups.
- **Passive confirmation needs firmware** (§8.2, optional): a 3 s "here" advert (type `0x26`) at
  every wake-up at which the tag saw one of the owner's devices. Then "the tag sees the phone and
  the phone sees the tag" is recorded without the owner doing anything.

### 4.2 The cable

Today each activity owns a `UsbHelper`. A new app-scoped `CableWatch` (in `usb/`) listens to
`ACTION_USB_DEVICE_ATTACHED/DETACHED` while the app process lives and looks at `deviceList` on
start. It classifies without opening anything:

| On the cable | Detection | Result |
|---|---|---|
| nRF52 bootloader | `DfuDevices.isBootloader(vid, pid, productName)` (Seeed `0x2886` with the Wio L1 PIDs or product "TRACKER L1", Adafruit `0x239A`) | `Cable.BOOTLOADER` |
| tag firmware | `UsbSerial.VID/PID_TRACKER` or CDC; the console answers `status` (existing `UsbTagSession`) | `Cable.TAG` plus `TagHealth` |
| CP2102 (Heltec ESP32) that does not answer `status` in 3 s | send the ROM loader's SLIP `SYNC` frame (`c0 00 08 24 00 00 00 00 00 07 07 12 20` + 32 × `55` + `c0`); an answer starting `c0 01 08` is the ROM | `Cable.ESP_DOWNLOAD` |

The bootloader has no tag identity. It is attributed to a tag in this order: the tag with
`dfuStartedAt` in the last 24 h; else the only overdue nRF tag (`board` not an ESP32, not
`wifiTag`); else the only nRF tag. If none fits, it is not attributed and the home screen shows a
tag-less strip (§7.4).

Without a cable the bootloader cannot be seen (serial and UF2 mode do not advertise). The app can
only hint: `dfuStartedAt` or `lastUsbAt` is later than the last report and nothing came since
(`UPDATE_UNFINISHED`, `AFTER_CABLE`).

Leaving the states:

- nRF bootloader with a valid application: one press of the reset button starts the firmware.
  "Finish the update" opens `FirmwareUpdateActivity` with `fromBootloader = true` (exists).
- ESP32 download mode: "Restart the tag" pulses EN through the CP2102 (`SET_MHS`: DTR clear so IO0
  stays high, RTS set for 100 ms, then cleared). `UsbSerial` never touches these lines otherwise;
  this is the one place where a reset is the point. It counts as power-on in the firmware
  (deliberate, PROTOCOL 2.5), so it adds no reset backoff.

### 4.3 Counter gaps

From `fw-0.7.6.md`: at boot `n = max(state n_next, key page n_floor, 64 × epoch)`, the state
checkpoint moves in steps of 16, the floor in steps of 32. After a quiet stretch of 12 h or more `n`
jumps to the start of the next epoch (multiple of 64). So for two consecutive opened reports
`a → b` with `d = b.n − a.n`:

| Pattern | Meaning |
|---|---|
| `d == 1` | normal |
| `b.n % 64 == 0` and `a` was `QUIET` and the gap is ≥ 12 h | epoch skip after quiet, normal |
| `b.n % 16 == 0`, `d ≥ 2` and the time gap is shorter than `(d − 1)` report periods × 0.5 | a restart in between (inferred `BootRec`) |
| `2 ≤ d ≤ 8`, none of the above | `d − 1` reports lost on the way |
| a report opens with `n ≤ lastN − 1` and other content than the stored one | counter went back: `COUNTER_BACK` |

`CounterGaps.lossRate(t)` is the share of lost reports over the last 7 days (last row only). It
feeds the tolerance in §6. Two inferred boots within 6 h feed `RESTARTING`.

Over the cable or the Bluetooth console `status` prints `n=`. If `health.n − lastN > 2048` the
tag's reports fall outside the app's window (`COUNTER_AHEAD`); "Catch up" sets `lastN = health.n − 1`.

### 4.4 Bridge receipts for reports the app did not get

A bridge with receipts on publishes kind `4245` with `["y", x]` for every report it hears. The app
can compute `x` for counters it has not seen. When a tag becomes overdue (and then at most every
15 min while it stays overdue and the detail is open, every hour otherwise) the app asks once:

```
{"kinds": [4245, 4243], "#y": [x(n, j) for n in candidates, j in {0, 1}]}
candidates = lastN+1 … lastN+4, the next two multiples of 16 above lastN, the next multiple of 64
```

(at most 14 values of `y`; `BridgeTargets.recentYs` gets a sibling `nextYs`). A receipt counts only from a
bridge with a valid announcement, as in `BridgeTargets.choose`. A receipt for a counter the app has
not opened is strong evidence that the tag transmitted: nobody without the view key can produce
that `x` unless the tag sent it. The app then fetches the report itself from the relays in that
bridge's announcement (`RelayOnce`), and if it arrives the diagnosis disappears by itself. If it
does not, the cause is `NOT_DELIVERED` with the receipt's time and hop class.

The `4243` half of the same query covers relays that were unreachable when the report was live.

### 4.5 Path log and blindness

`PathLog` (in `LibreTagApp`) keeps, in memory and in `AppPrefs`, since when at least one receive
path works: a relay connected with the report subscription open, or `MeshApiState.Connected`. A
drop of all paths clears it. On return it is set only after the backfill query for the tags'
windows came back (`backfillAt`). `blind = pathUpSince == null`. `wasBlind(since)` =
`pathUpSince > since`.

### 4.6 Phone clock

On every report with `HAS_FIX` received live (mesh or a relay event that is not `approx`):
`skew = receivedAt − fixTime·1000 − 5 s`. A fix time in the phone's future by more than 2 min, or a
relay event `created_at` more than 2 min ahead of the phone, sets `clockSkewMs`. A wrong phone clock
shifts every expected time and makes timed mailbox entries miss the check windows. The tag has no
wall clock; its crystal drift (100 ppm guard, PROTOCOL 2.1) is covered by the check window and is
not diagnosed.

## 5. Causes

`Diagnosis.of(t: Tracker, s: TagStatus, c: DiagContext, now)` returns the headline cause, its level
and confidence, the facts for the copy and the actions. It is evaluated for `SILENT`, `WAITING`
older than one period, and `OK` that is late (`StatusLines.late`). Lost and guard alarm keep their
plates; a diagnosis may only add the "Why" card below them. First match wins.

Levels: **CALM** (no band, no notification), **NOTE** (no band, no notification, a hint line with
an info icon), **WARN** (orange band and notification, as `SILENT` today). Diagnosis never raises
CRIT.

"After the last report" below means later than `t.last.receivedAt`.

| # | Cause | Detected when | Conf. | Level |
|---|---|---|---|---|
| 1 | `BOOTLOADER` | `Cable.BOOTLOADER` attributed to this tag | high | NOTE |
| 2 | `ESP_DOWNLOAD` | `Cable.ESP_DOWNLOAD` and the tag is an ESP32 tag | high | NOTE |
| 3 | `TX_FLOOR` | `Cable.TAG` or fresh `health` (< 15 min): `tx=low_voltage` | high | NOTE |
| 4 | `RESET_LOOP` | fresh `health`: `resets ≥ 2` and `last_reset` in brownout, watchdog, crash, soft | high | WARN |
| 5 | `ON_CABLE` | `Cable.TAG`, none of the above | high | CALM |
| 6 | `FIRMWARE_TOO_OLD` | `t.firmware` known and older than `minFirmware`, no report opened since the app's update | high | NOTE |
| 7 | `PHONE_OFFLINE` | `!online` and mesh not UP | high | NOTE |
| 8 | `RELAYS_DOWN` | `online`, `relaysUp == 0`, `relaysAll > 0`, mesh not UP | high | NOTE |
| 9 | `JUST_BACK` | a path is up, but `wasBlind(nextAt)` and `backfillAt` is null or younger than 2 min | high | NOTE |
| 10 | `NOT_DELIVERED` | `receipt` for a counter above `lastN`, and the fetch from the bridge's relays failed | high | NOTE |
| 11 | `WITH_YOU` | `bleSeen.kind == "here"` after the last report and younger than `ownerHbH` h + 30 min; or a session over the Bluetooth console read `quiet = owner` | high | CALM |
| 12 | `RESTARTED_HERE` | `bleSeen.kind == "console"` unprompted, after the last report, exactly one window in the last hour | high | NOTE |
| 13 | `RESTARTING` | ≥ 2 entries of `bleBoots` in the last hour, or ≥ 2 `bootRecs` in 6 h, or a boot report with `resets ≥ 2` (§8.1) | medium (high with §8.1) | WARN |
| 14 | `UPDATE_UNFINISHED` | `dfuStartedAt` after the last report and no cable now | medium | WARN |
| 15 | `BATTERY_EMPTY` | last battery ≤ 5 %, or `critBattNotified`, or `LOW_BATT` and `BatteryModel` projects empty before now | high / medium | WARN |
| 16 | `SCHEDULE_PENDING` | a pending or just confirmed `SET_IDLE` whose new period is longer than the old one, and now is inside the new period + margin | medium | NOTE |
| 17 | `SCHEDULE_MISMATCH` | fresh `health.intervalMin × reportEvery ≠ t.reportPeriodMin`; or the last IDLE non-quiet report's `next_in` differs from `reportPeriodMin` by more than 15 %; or `cableDirty` | high / medium | NOTE |
| 18 | `OUT_OF_RANGE` | the last two reports heard no repeater (`nbTotal == 0`) or the best SNR was below −10 dB, and arrived by Nostr over 4+ hops or not at all by mesh | low | WARN |
| 19 | `MAYBE_WITH_YOU` | the tag has known devices on it (`knownOnTag`), use is "things", last fix not in a zone | low | WARN |
| 20 | `MAYBE_QUIET_PLACE` | the last fix lies inside a zone (or within 50 m of its edge) and the report was not `QUIET` | low | WARN |
| 21 | `AFTER_CABLE` | `lastUsbAt` after the last report, no report since | low | WARN |
| 22 | `PHONE_CLOCK` | `abs(clockSkewMs) > 2 min` | medium | NOTE |
| 23 | `UNKNOWN` | nothing above | none | WARN |

Findings that never explain a silence but are listed in the checklist and may show a hint while the
tag reports normally:

| Finding | Detected when | Where |
|---|---|---|
| `NODE_DOWN` | mesh was UP in the last 7 days, is DOWN now, and this tag's last 5 reports all came `via == "mesh"` | hint on the Path row; headline only when overdue and no cause above 18 matched |
| `BATTERY_CLIFF` | battery fell ≥ 25 points between two reports less than 3 periods apart, not on a boot | Battery row |
| `NO_GPS_RUN` | 3 or more reports in a row without a fix | Where row (existing "No GPS" line gets "since 09:12") |
| `COUNTER_BACK` | §4.3 | checklist, Diagnostics |
| `COUNTER_AHEAD` | §4.3 | "Why" card when overdue, with "Catch up" |
| `FIRMWARE_BEHIND` | `t.firmware` older than `BundledFirmware.version` | Service (exists), checklist |
| `FLOOD_CAP` | the last report's `next_in` in TRACK or LOST is ≥ 1 h while `modeIntervalS` is shorter | hint: the tag slowed down to protect the mesh |

Expected silence needs no diagnosis and keeps today's plates: inside the schedule (`OK`), a `QUIET`
report inside its heartbeat (`QUIET_PLACE`, `QUIET_WITH_YOU`, `QUIET_STILL`, `QUIET_PARKED`),
late inside the margin (`HEARD_LATE`).

## 6. Tolerance: when "late" becomes "silent"

One lost flood is normal on a shared mesh, and "Silent" after a single missed hourly report is the
most common false alarm. The `SILENT` threshold becomes:

```
missed   = number of expected reports that did not come (0 while now ≤ nextAt + SILENT_MARGIN_MS)
allowed  = 0  when the tag is guarded, in LOST or TRACK, or the last report was QUIET (a heartbeat)
           1  otherwise
           2  when CounterGaps.lossRate(t) ≥ 0.25
SILENT   when missed > allowed
```

Between "late" and "silent" there is a new calm plate `MISSED` (`StatusKind.OK`, level NONE):
"1 report missed · next ~15:31". For the second and later expected times the app assumes the tag
kept its period (`nextAt + k × period`), which is exact with `report_every` and close otherwise.
TRACK and LOST use `allowed = 0` but their own margin stays 30 min, which is already several
intervals.

A known cause shortens nothing: a `WARN` cause is shown and notified only once `SILENT` is reached.
The causes at level NOTE and CALM show as soon as the report is late, because their evidence does
not depend on waiting.

### Level and notification

| Situation | Row | Notification |
|---|---|---|
| CALM cause | plain status line with the cause | none; a posted "silent" notification is cancelled |
| NOTE cause | plain status line with the cause, info icon | none, with two exceptions below |
| WARN cause | orange band with the cause | the "silent" notification, text names the cause |
| `UNKNOWN` | orange band, today's sentence | today's notification |

- `PHONE_OFFLINE`, `RELAYS_DOWN`, `JUST_BACK`: the "silent" notification is held. When a path is
  back and the backfill finished, the diagnosis runs again and notifies if the tag is still silent.
  A guarded tag is the exception: after 2 h of blindness with a guarded tag the app posts
  "LibreTag can't receive reports" once (channel Alerts, default importance), because the owner
  relies on it.
- `BOOTLOADER`, `ESP_DOWNLOAD`: no notification (the owner holds the cable). `UPDATE_UNFINISHED`
  notifies once, after 30 min: "Car may still be in update mode".
- `RESET_LOOP`, `RESTARTING`: notify once per episode, even when the tag still reports
  ("Car keeps restarting"). An episode ends after 6 h without a boot.
- `silentAlerted` stays the latch. It is set only when a notification was really posted, so a held
  one is posted later. A new report clears it (as today).
- `TagStatus.blocksSupport` stays true for `SILENT` whatever the cause.

## 7. UI

Trail Blaze rules stay: one status sentence per row, band only for WARN and CRIT, no jargon in the
first layer. Reset causes, counters and SNR live in the checklist and in Diagnostics.

### 7.1 Tag row (list)

`TagRowView.bind` gets the diagnosis next to `TagStatus`. The band shows when
`diag.level == WARN` (or the status is CRIT, or `LOW_BATTERY`). For CALM and NOTE the sentence goes
to the normal status line; NOTE uses `ic_info` in `ink2` as the line's icon. The age on the right
stays, so "3 h" is always visible. Sentences (compact form, `%1$s` as marked):

| Cause | Row sentence |
|---|---|
| `MISSED` (plate) | 1 report missed · next ~15:31 |
| `BOOTLOADER` | In update mode · can't report |
| `ESP_DOWNLOAD` | In download mode · can't report |
| `TX_FLOOR` | Battery too low to report · charging |
| `RESET_LOOP` | Keeps restarting · 4 times in a row |
| `ON_CABLE` | On the cable |
| `FIRMWARE_TOO_OLD` | Firmware 0.8.2 is too old for this app |
| `PHONE_OFFLINE` | Phone is offline · heard 3 h ago |
| `RELAYS_DOWN` | Can't reach the relays · heard 3 h ago |
| `JUST_BACK` | Checking for missed reports… |
| `NOT_DELIVERED` | Reported 14:31 · the report didn't reach your relays |
| `WITH_YOU` | With you · quiet on purpose |
| `RESTARTED_HERE` | Restarted near you 14:02 · first report soon |
| `RESTARTING` | Keeps restarting · 3 times since 13:10 |
| `UPDATE_UNFINISHED` | Silent since the update started 22:10 |
| `BATTERY_EMPTY` | Battery probably empty · 4 % at 09:12 |
| `SCHEDULE_PENDING` | New schedule on its way · next report by 16:30 |
| `SCHEDULE_MISMATCH` | Reports every 2 h, the app expected 1 h |
| `OUT_OF_RANGE` | Silent for 3 h · last report heard no repeater |
| `MAYBE_WITH_YOU` | Silent for 3 h · may be quiet near your phone |
| `MAYBE_QUIET_PLACE` | Silent for 3 h · may be quiet at Home |
| `AFTER_CABLE` | Silent since it was on the cable 22:10 |
| `PHONE_CLOCK` | Phone clock looks wrong · times may be off |
| `UNKNOWN` | Silent for 3 h · expected hourly (unchanged) |

### 7.2 Tag detail

The plate (`StatusLines.Plate`) gets `MISSED` and one generic `EXPLAINED` plate whose first line is
the row sentence without the "heard" part and whose second line is the existing `NOT_HEARD` sub
("Last heard 11:31"). Under the plate, above the Where / When / Battery / Path rows, a **Why card**
(`ui/WhyCard.kt`, a signpost plate: `plate_bg`, 1 dp `plate` edge, a 4 dp left edge in `warn` for
WARN and in `ink3` otherwise). It shows:

1. one or two sentences of evidence;
2. at most two buttons (first filled tonal, second text);
3. "All checks" (text button) that expands the checklist.

The card is hidden when there is no diagnosis and no finding.

| Cause | Card text | Actions |
|---|---|---|
| `MISSED` | One report didn't arrive. That happens on a shared mesh. The next one is due around 15:31. | none |
| `BOOTLOADER` | The tag on the cable is waiting for firmware. It can't report until it restarts. If you didn't mean to update, press its reset button once. | Finish the update · How to restart |
| `ESP_DOWNLOAD` | The tag on the cable is in download mode and its firmware isn't running. | Restart the tag · Finish the update |
| `TX_FLOOR` | The battery is below 3.25 V, so the tag stays silent to avoid restarting. It reports again by itself above 3.35 V or on a charger. | none |
| `RESET_LOOP` | The tag restarted 4 times in a row (brown-out). After each restart it waits longer before the first report, now 16 min. A weak battery or a loose connector is the usual reason. | Read the log · Restart the tag |
| `ON_CABLE` | Connected to this phone. Last report 11:31, next one in about 12 min. | Send a report now |
| `FIRMWARE_TOO_OLD` | This app reads reports from firmware 0.9.0 and newer. The tag has 0.8.2. Update it over the cable. | Update firmware |
| `PHONE_OFFLINE` | This phone has no internet and no MeshCore node, so it can't receive reports. Last heard 11:31. The app checks again when the connection is back. | Connections |
| `RELAYS_DOWN` | None of your 3 relays answers. Reports that bridges published wait there. | Connections · Try again |
| `JUST_BACK` | The phone was offline from 12:05 to 14:40. Asking the relays for reports from that time. | none |
| `NOT_DELIVERED` | A bridge heard the tag at 14:31 (1 to 3 hops), but the report isn't on your relays. The tag works. Add one of the bridge's relays to get its reports. | Add relay · Connections |
| `WITH_YOU` | This phone heard the tag over Bluetooth at 14:02, and the tag sees one of your devices. It stays quiet while you are near and reports by 19:31 at the latest. | Open over Bluetooth |
| `RESTARTED_HERE` | This phone heard the tag start up at 14:02. The first report comes about 30 s after a restart. | Open over Bluetooth |
| `RESTARTING` | The tag restarted at 13:10, 13:24 and 13:52. A weak battery is the usual reason. | Open over Bluetooth · Plug it in to check |
| `UPDATE_UNFINISHED` | An update started at 22:10 and the tag hasn't reported since. It may still be in update mode, where it waits without a time limit. Plug it into this phone to check. | How to restart |
| `BATTERY_EMPTY` | The last report at 09:12 said 4 %. Below about 7 % the tag stops reporting. Charge it. | Open in map |
| `SCHEDULE_PENDING` | You changed the schedule to every 2 h. Until the tag confirms it, the next report can come on either schedule, by 16:30 at the latest. | none |
| `SCHEDULE_MISMATCH` | The tag reports every 2 h and listens every 1 h. The app has every 1 h. Commands may miss the tag until both agree. | Use the tag's schedule · Send mine again |
| `OUT_OF_RANGE` | The last two reports heard no repeater, so the tag may be out of mesh range. It keeps trying every hour. | Open in map |
| `MAYBE_WITH_YOU` | The tag goes quiet when it sees your devices, and the report that says so may have been lost. This phone can ask it over Bluetooth at its next wake-up, around 15:31. | Check if it's here |
| `MAYBE_QUIET_PLACE` | The last position was inside Home. The tag goes quiet there, and the report that says so may have been lost. | Check if it's here · Open in map |
| `AFTER_CABLE` | Nothing since the tag was on the cable at 22:10. If it was updated from a computer, it may still be in update mode. Plug it into this phone to check. | How to restart |
| `PHONE_CLOCK` | The phone's clock is about 7 min off. Expected times and timed commands depend on it. | Date & time settings |
| `COUNTER_AHEAD` | The tag's report counter is far ahead of what the app knows, so the app can't find its reports. | Catch up |
| `UNKNOWN` | No report since 11:31 and nothing explains it. Position and battery below are from then. | Check if it's here (when offered) · All checks |

Actions map to existing code:

| Action | Does |
|---|---|
| Finish the update, Update firmware | `FirmwareUpdateActivity` (`fromBootloader` as detected) |
| How to restart | bottom sheet: "Press the reset button once. If the tag stays in update mode, its firmware is incomplete: plug it into this phone and choose Finish the update." with the board's button drawing where one exists |
| Restart the tag (ESP32) | `UsbSerial.cpReset()` (§4.2), then `UsbHelper.reconnect()` |
| Restart the tag (console open) | `reboot` over the console (`BleConsoleHelper.restartSheet` exists) |
| Read the log | `log 20` over the open console, shown in Diagnostics |
| Open over Bluetooth | `BleConsoleHelper.connect(t)` when an advert is current, else `openRemote(t)` |
| Check if it's here | `BleConsoleHelper.openRemote(t)`; offered when `remoteOffered(t)` and Bluetooth is on. While it waits the card says "Asking at its next wake-up, around 15:31. Keep the phone near the tag." Not seen by then + 2 min: "Not here: the tag didn't answer over Bluetooth at 15:31." and the cause falls back to the next match (this negative result is kept for one period and removes `MAYBE_WITH_YOU`) |
| Send a report now | `report` over the console |
| Connections | Connections screen |
| Try again | `RelayPool.setRelays` with the same lists (reconnect), then the backfill query |
| Add relay | adds the bridge announcement's first relay to the subscribe list after a confirmation that shows the URL |
| Use the tag's schedule | copies `health.intervalMin`, `reportEvery` (and `checkS`) into the `Tracker` (what `UsbTagSession.adoptSettings` does on connect) |
| Send mine again | queues `SET_IDLE` with the app's values |
| Catch up | `lastN = health.n − 1`, then the relay query for the new window |
| Date & time settings | `Settings.ACTION_DATE_SETTINGS` |

**Checklist ("All checks").** Rows with a state mark (check, warning triangle, dash for unknown),
key on the left, value on the right, in the detail rows' style:

```
Phone online            yes
Relays                  3 of 3 connected
Own node                not connected since 12:05
Bridges that heard it   2 · last 13:31, 1 to 3 hops
Last report             11:31 · via Nostr · no. 1284
Reports lost (7 days)   3 of 161
Battery then            82 % · falling 2 % a day
Bluetooth               not heard by this phone
Cable                   last connected 18 Nov 22:10
Restarts                none known
Schedule                every 1 h, listens every 1 h · confirmed by the tag 18 Nov
Firmware                0.9.3 · current
Phone clock             fine
```

The existing Diagnostics entry in the tag menu shows the same list plus raw values (`last_reset`
word, `resets`, `boot_delay`, counter gaps).

### 7.3 Map pill

The pill keeps the name. It gets a short suffix only where the drawn position would mislead:
`WITH_YOU` "· with you", `ON_CABLE`, `BOOTLOADER`, `ESP_DOWNLOAD` "· on the cable". For a WARN
diagnosis the pill keeps today's silent style (faded with age). No icons for NOTE causes on the map.

### 7.4 Home strip for an unattributed bootloader

When `CableWatch` sees a bootloader that fits no tag, the home list shows one plate above the rows:
"A tag on the cable is in update mode. It can't report until it restarts." with "Finish the update"
and "How to restart". It disappears when the cable is pulled.

### 7.5 Connection indicator

The top bar indicator ("mesh" / "Nostr only" / "offline") already exists. With `PHONE_OFFLINE` or
`RELAYS_DOWN` it is the same fact the rows state, so the rows say it only for tags that are late;
tags inside their schedule stay as they are.

### 7.6 States and transitions

```
OK ──nextAt passed──▶ HEARD_LATE ──+30 min──▶ MISSED ──missed > allowed──▶ SILENT(cause)
                           │                     │                            │
                           └── NOTE/CALM cause shown from here on ────────────┤
any state ──report opens──▶ OK (diagnosis and held notification cleared, silentAlerted = false)
SILENT(WARN cause) ──evidence arrives (advert, cable, receipt, path back)──▶ CALM/NOTE, notification cancelled
SILENT(NOTE: blind) ──path back + backfill, still nothing──▶ SILENT(next cause), notification posted
CALM(WITH_YOU) ──sighting older than owner heartbeat + 30 min──▶ SILENT(next cause)
```

The diagnosis is recomputed on the same ticks as `Status` (store change, minute tick) and when
`DiagContext` changes (path, cable, sighting, receipt). `LibreTagApp` exposes
`diag: StateFlow<Map<String, Diagnosis>>` so the list, the detail and `checkSilent` read one value.

## 8. Firmware and protocol additions

Neither is needed for steps 1 to 9 of the plan. Both add 0 bytes on LoRa.

### 8.1 Boot report (firmware 0.10, PROTOCOL 2)

Byte 2 of the report plaintext keeps 3 bits for `mode`, but only values 0 to 3 exist. Change:
`mode` = bits 0–1, **bit 2 = `BOOT`**: this is the first report since a boot. In a `BOOT` report
byte 14 (horizontal accuracy) carries the reset information instead:

```
byte 14, when BOOT: bits 0–2 cause (0 none, 1 pin, 2 power, 3 soft, 4 debug, 5 brownout, 6 watchdog, 7 crash)
                    bits 3–7 resets in a row (boot_resets, capped at 31)
```

The cause words are the ones `status` prints as `last_reset=`. The app shows a `BOOT` report's fix
without "±" (`pos_gps_noacc` exists). The app appends a `BootRec`; `resets ≥ 2` with a fault cause
is `RESTARTING` at high confidence, and "restarted, power-on" explains a counter gap with no
alarm. History records (`j = 2`) carry it too, so a USB history sync shows past boots.

- `lt_core`: `lt_report_pack/unpack` (mode mask, `boot` field, `boot_info`), `lt_boot_info(cause,
  resets)`; tests in `firmware/lt_core/test/host_test.c`.
- `host/src/libretag/lt2.py` and vectors `boot_reports` in `host/tests/vectors/lt2.json`.
- Kotlin `proto/LibreTag.kt` (decode), `Position.boot: BootInfo?`.

Privacy: the bit is inside the sealed plaintext. Nothing changes on air.

### 8.2 "Here" advert (firmware 0.10, PROTOCOL 6.2)

At a wake-up at which the presence scan matched one of the owner's devices, a locked tag above the
transmit floor sends, for 3 s after the scan, a legacy non-connectable advert at 100 ms from a
fresh private address: manufacturer data, company `0xFFFF`, 14 B:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `0x26` |
| 1 | 1 | `info`: bits 0–1 quiet reason now (0 none, 1 zone, 2 owner, 3 parked), bit 2 this wake-up reports, bits 4–7 resets in a row (capped at 15) |
| 2 | 6 | `salt`, random per wake-up |
| 8 | 6 | `HMAC(usb_key, "LT2-here" ‖ salt ‖ info)[0:6]` |

About 30 advertising events per wake-up, a few µAh. It is sent only where an owner's device is in
range, so it tells an observer "a LibreTag-like device woke here", the same as the console advert,
and never anywhere the owner is not. `blecon boot off` turns it off as well. `BleConsole.kt` gets
`hereMatch(usbKey, payload)`; vectors `ble_here` in `lt2.json`.

Without 8.2 the `WITH_YOU` cause is reached only through "Check if it's here" or a console session.

### 8.3 Console

`status` already prints everything needed (`n=`, `interval=`, `report_every`, `txcap … resets=
last_reset= boot_delay= tx=`). No change. The app must parse the `txcap` line (it does not today).

## 9. Implementation plan

Each step compiles and ships alone. Tests live in `android/app/src/test/kotlin/app/libretag/…`.

1. **Counter gaps and loss rate.** New `data/CounterGaps.kt` (pure). Tests `data/CounterGapsTest.kt`:
   normal run, 2 lost, epoch skip after 12 h quiet, restart landing on a multiple of 16, restart
   versus 15 lost reports (time gap decides), counter back, loss rate over 7 days.
2. **Tolerance and the `MISSED` plate.** `data/Status.kt`: `missed(t, now)`, `allowed(t)`, `SILENT`
   only when `missed > allowed`. `data/StatusLines.kt`: `Plate.MISSED`, `NextKind.REPORT` with the
   projected time. `ui/TagText.kt`, `strings.xml` (`st_missed`). Update `StatusTest`,
   `StatusLinesTest`, `Checks07StatusTest` (guarded, LOST, TRACK and QUIET stay at 0; lossy tag
   gets 2).
3. **`TagHealth` from the console.** `usb/TrackerConsole.kt`: parse `n=`, `resets=`, `last_reset=`,
   `boot_delay=`, `tx=` into `TrackerStatus`; `usb/UsbTagSession.kt` `probe` stores
   `Tracker.health` (USB and Bluetooth sessions), reads `quiet` when the command exists.
   `data/TrackerStore.kt`: the new `Tracker` fields of §3. Tests: `usb/TrackerStatusTest.kt` with a
   0.9.1 `status` sample (with and without `last_reset`), `TagSettingsReadTest`.
4. **`DiagContext` plumbing.** `LibreTagApp.kt`: network callback (`online`), `PathLog` (new
   `data/PathLog.kt`, pure, with `AppPrefs` persistence), `backfillAt` set where the window query
   finishes, `diagContext(t)`. `AndroidManifest.xml`: `ACCESS_NETWORK_STATE`. Test
   `data/PathLogTest.kt`: path drops and returns, backfill gating, `wasBlind`.
5. **The model.** New `data/Diagnosis.kt`: `Cause`, `Level`, `Confidence`, `Diagnosis`, `of()`,
   `findings()`, `checklist()`. Table-driven `data/DiagnosisTest.kt`: one case per cause of §5, the
   order (bootloader beats offline beats with-you beats battery), "a guess never lowers the level",
   expiry of `WITH_YOU`, nothing for a tag inside its schedule or a `QUIET` heartbeat.
6. **Notification gate.** `LibreTagApp.checkSilent` (line ~1475): use `Diagnosis`; hold for blind
   causes, cause in the text (`notif_silent_cause`), blind notice for guarded tags, restart
   notification. Extract the decision into `data/SilentNotify.kt` (pure: `decide(prev, diag, s) →
   Post | Hold | Cancel | None`) with `data/SilentNotifyTest.kt`.
7. **Row, plate and Why card.** `ui/TagRowView.kt` (level from the diagnosis, info icon),
   `ui/TagText.kt` (`diagRow`, `diagCard`, `aria`), new `ui/WhyCard.kt`, `ui/TagBodyView.kt` (card
   under the plate, `DetailHost.diagAction(t, action)`), `ui/MainActivity.kt` and `ui/Panes.kt`
   (collect `app.diag`, actions), `ui/map/MapOverlays.kt` (pill suffix), `strings.xml`. Tests
   `ui/DiagTextTest.kt` (Robolectric or the existing pure `TagText` test pattern): every cause has
   a row sentence and a card, no sentence longer than the row at font scale 1.3 in DE (length
   budget 44 characters for the row sentence).
8. **Cable watch.** New `usb/CableWatch.kt` (app scope, classification and attribution pure in
   `usb/CableClassify.kt`), `FirmwareUpdate.start` records `dfuStartedAt`, ESP32 `SYNC` probe and
   `UsbSerial.cpReset()`, home strip in `MainActivity`. Tests `usb/CableClassifyTest.kt`
   (VID/PID table, attribution order, no nRF tag owned) and `usb/EspSyncTest.kt` (frame bytes,
   answer match).
9. **Receipts for the next counters.** `nostr/BridgeTargets.kt`: `nextYs(lastN, x)`;
   `nostr/BridgeLookup.kt`: `ahead(ys)` returning receipts and reports; `LibreTagApp`: query on
   becoming overdue and on the schedule of §4.4, fetch from the announcement's relays with
   `RelayOnce`, `Add relay` action. Tests `nostr/BridgeAheadTest.kt`: candidate counters, receipt
   from an unannounced key ignored, receipt plus fetched report clears the cause.
10. **Bluetooth sightings.** `ble/BleConsoleScanner.kt` (persist `bleSeen`, `bleBoots` with the
    "our own window" rule), new `ble/BleSightReceiver.kt` and the `PendingIntent` scan, Settings
    switch, `BleCommandBeacon.kt` sealings for `n + 1` while overdue, the "not here" result in
    `BleConsoleHelper`. Tests `ble/BleBootsTest.kt` (new salt = new window, own window excluded,
    two in an hour) and `proto/BleCommandAheadTest.kt` (candidate `(n, k)` list against the
    `checks` vectors).
11. **Phone clock.** `LibreTagApp` receive path: `clockSkewMs`; test in `DiagnosisTest`.
12. **Firmware 0.10 (optional).** §8.1 then §8.2: `firmware/lt_core`, ZephCore patch, Python mirror
    and vectors, Kotlin decode, `PROTOCOL.md` 2 and 6.2, `SPEC.md` checklist. App: `BootRec` from
    reports, `hereMatch` in the scanner. Kotlin vector tests next to the existing `lt2.json` tests.
13. **Text.** Humanizer over the new English strings, Sonnet translations (sk, cs, de, es, nl),
    `docs/guide.html` section "When a tag is late", release notes.

Steps 1 to 7 fix the false alarms and explain the common cases with data the app already has.
Step 8 covers case 2 (bootloader). Steps 9 and 10 cover case 1 and delivery. 12 makes restarts and
"with you" visible without the owner doing anything.

## 10. Decisions (owner, 2026-11-20)

1. **Tolerance of one missed report**: as designed (6), no per-tag setting.
2. **Background Bluetooth scan**: on by default, with the switch "Notice my tags over Bluetooth" in
   Settings (Android 12+, Nearby devices, no location).
3. **"Here" advert (8.2)**: not now. The n + 1 sealings in the phone's command advert (4.1) cover
   the lost QUIET report; `WITH_YOU` comes from "Check if it's here" or a console session reading
   `quiet = owner`.
4. **Boot report (8.1)**: the `BOOT` bit is in every first report since a boot (firmware 0.9.4).
   Byte 14 carries the reset cause and the resets in a row only when the report has no fix (and is
   not the Wi-Fi seed2 layout); with a fix it keeps the accuracy and only the bit is set
   (PROTOCOL.md 2).
5. **ESP32 download mode probe and the EN pulse** (4.2): implemented (`EspSync`, `UsbSerial.cpReset`).
   **Needs a bench check** on the Heltec V3: the SYNC frame against a busy firmware, and the EN pulse
   through `SET_MHS` with the board's auto-reset circuit.
6. **Bootloader exit**: the card says "press the reset button once" (Wio). Whether Seeed's
   bootloader leaves DFU by software is still to be checked on the bench.
7. **n + 1 advertising rotation** (4.1): implemented (`BleBeaconPlan.ahead`, rotation every 2 s for
   a late tag on phones with too few sets). **Needs a bench check**: the test phones' set count and
   whether the rotation reaches the tag's 1.5 s scan.
8. **"Add relay" from a bridge announcement**: offered only for a bridge with receipts for this
   tag on at least two different days, after a confirmation that shows the URL.

Implementation notes: the receipts query also takes kind 4243 for the same `y` values; an
announcement's `["relay", url]` tags are kept (`Announcement.relays`). Diagnostics shows the
checklist inside the Why card ("All checks"); the raw `last_reset`, `resets` and `boot_delay`
values appear there through the reset-loop card and the Read the log action.

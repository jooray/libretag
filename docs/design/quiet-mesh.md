# Quiet mesh: fewer floods, confirmed delivery

Firmware 0.10.0, app 0.14.0, Android bridge 0.6.0, Python bridge 0.7.0, PWA 0.5.0, released together. Nothing stays compatible with older versions. Wire formats: `PROTOCOL.md` 2.6, 2.7, 3.3 to 3.5 and 4.3. Behaviour, defaults and budgets: `SPEC.md` 3.2. Reference code: `host/src/libretag/quietmesh.py`. Vectors: `docs/vectors/quiet-mesh-v1.json`.

## Why

Until 0.9 every report was a flood. In a country scope one flood can make a couple of hundred repeaters transmit, a district scope a few dozen, and the tag's own history showed that most of its floods were not repeated by anything nearby: the repeaters in range did not carry the scope, or nothing heard them. A tag at home without a GPS fix flooded every report period, and nobody might have been listening. The tag also retransmitted the same bytes after 8 s, which repeaters that heard the first copy drop as a duplicate.

The goal of this release is to use the MeshCore repeaters as little as possible. Zero-hop and a bridge near the tag come first. Floods, and anything else that makes repeaters transmit (direct routes included), are kept to the minimum. Listening for commands about once an hour is enough. Each choice below was weighed by how many repeater transmissions it causes.

## The design in five points

1. **Zero-hop first.** The regular IDLE report is copy 9, sent zero-hop. It is a full report (position, battery, `next_in`). No repeater forwards it; a bridge or the owner's node in range hears it.
2. **Only the owner confirms.** The app opens the report and answers with a 13 B ACK sealed with the command chain. The bridge that heard the report directly, or the owner's node, sends it zero-hop. A bridge holds no key and cannot make one. A confirmed tag is NEAR and sends nothing more for `hold` (12 h by default) unless it moves.
3. **A flood is the fallback, on events.** The tag floods when it moved and nobody confirmed, when `hold` ran out, when a report has to arrive (boot, command answer, mode change, low battery, tracking), and every 24 h when nothing confirms it. Never on a shorter timer for a tag that stands still.
4. **Narrow scope, widened on evidence.** A flood starts in the scope of a bridge area the tag is in, then the country, then unscoped, and widens only when no repeater was heard repeating it. Each step is a new copy (`j` 10), because repeaters drop a payload they have seen, including one they dropped for a scope they do not carry.
5. **A path, in one narrow case.** A standing tag one or two repeaters from a bridge learns that path from the ACK to its flood (the bridge appends it) and sends the next reports that must arrive along it (copy 11). Two repeaters transmit instead of dozens. The tag forgets the path the first time it goes unconfirmed, and it floods then.

What this costs. A tag out of range that stands still is heard once a day instead of every few hours. A moved IDLE tag out of range is reported up to one check interval later, because it waits for a late ACK at the next check before it floods. A guarded tag (`hold` 0) decides after 12 s. Commands are not affected: the tag still listens at every hourly check.

## Reasoning behind the main choices

**Why the ACK and not "the bridge stays silent".** Without an answer the tag cannot know whether anyone heard its zero-hop report, so it would have to flood anyway. One 21 B zero-hop packet, which no repeater forwards, saves the repeaters up to a day of floods.

**Why only the app makes ACKs.** A bridge-made "heard" ACK would tell the tag that some bridge has its report, not that the owner does. A bridge can drop a report after acknowledging it. With the ACK sealed by the command key, a bad bridge can at most make the tag flood (more airtime, not less visibility).

**Why two decision points and not a timeout.** Measured on one phone over 8 days, a report reached the app either within about 7 s (about three quarters of reports) or minutes later (socket lost, reconnecting, waiting for the periodic sync). Nothing came in between. So the tag decides 12 s after the hello where it cannot wait (tracking, Lost, command answers, guarded tags) and at the next check otherwise. A late app sends its ACK for that check.

**Why a schedule anchor.** Commands at checks need the app to know when the tag listens and which key to seal for. If every hello restarted the schedule, a hello nobody heard would move it without the app knowing. The anchor moves only on a flood (which the app gets or the tag keeps the old grid for 3 more slots) or an ACK in the report's own window (which the app made). The `SYNC` bit in every report tells the app whether its last ACK landed. Wall-clock slots would remove the remaining hole (a lost flood), but they need a clock rule for a tag without GPS; that waits for the field data.

**Why the jitter now comes from the slot key.** An anchor can be older than the current epoch, and the tag deletes old epoch keys. The tag keeps one derived key per anchor (`ks`), and the jitter, the command keys and the slot tags all come from it.

**Why the second channel data type.** A command is the same bytes on every try. Repeaters that heard (or overheard) the first try drop the second. The fallback flood of a command therefore uses data type `0xFF6C`, which changes the encrypted envelope and the packet hash. The tag accepts both.

**Why a bridge never knows the hop count of a DIRECT packet.** A zero-hop packet and a packet that came along a path both arrive with an empty path. Reading that as 0 hops would make a bridge answer zero-hop into the air around itself while the tag sits two repeaters away. The bridge answers a DIRECT arrival zero-hop only when the app's entry says `near` (the app knows the copy was 9), and along a stored path when the entry names one.

**Why paths only for a standing tag, and at most two repeaters.** A path needs an ACK back, so it costs twice its length per confirmed report: 2 transmissions over one repeater, 4 over two, 6 over three. A small district flood costs about the same as three, and three links in a row fail more often. A moving tag would pay for a path it never uses. The owner's node never routes, because a route that ends at a phone is wrong as soon as the phone moves.

**Why receipts only for reports heard directly.** The app needs the key of the bridge that heard the hello, to address the ACK to it. For a flood heard over hops a receipt would publish where a tag is for no gain; the claim round and the "ack sent" note find the right bridge there.

**Why the bridge announces its radio state hourly.** A bridge radio once stayed dead for days while the bridge software and the node service kept running. Every tag near it looked silent, and nothing told the operator. The announcement now carries the radio's own state, the app uses it before raising "silent", and the bridge runs an alert hook for the operator. It costs no airtime.

**What an observer sees.** A 53 B DIRECT packet every few hours from a standing tag and a 21 B answer. A routed copy shows a path in the clear, but a flood already carries the same hashes in its header to every repeater it reaches; along a path fewer nodes see them. A standing tag that always uses one path is linkable by it, as it is today by the first repeater of its floods.

## Implementation contract

Labels such as R4 or P3 name individual fixes; each is described where it is listed below, in `SPEC.md` 3.2 or in `PROTOCOL.md`. Everyone implements against the vectors in `docs/vectors/quiet-mesh-v1.json` (copy the file into your tree; `tools/check_vectors.py` keeps copies identical). The obsolete parts of `host/tests/vectors/lt2.json` are `check_commands` and `checks` (the 0.7 check derivation from `C_e(n)`); tests that use them move to `slots` in the new file, and the keys are removed from `lt2.json` once no component reads them. No fallbacks for old formats anywhere.

### Vector sections

| Key | What it pins | Used by |
|---|---|---|
| `copies` | `x` of copies 0, 1, 9, 10, 11; sealed `P`; packet bytes for zero-hop, a path of 1 and 2 entries, a scoped and an unscoped flood | firmware, both bridges, app, PWA |
| `acks`, `acks_routed` | `Pa` for `(n, k)` with `hold` and `MORE`; channel payload, 21 B packet, stealth form; routed datagrams with 1 and 2 entries and hash sizes 1 to 3, the packet along the path and its last hop; refused cases | firmware, app, PWA, both bridges (routed builder) |
| `ack_entries` | content of ACK entries in the near, 26 B and routed forms | app, PWA, both bridges |
| `retry` | one command under `0xFF4C` and `0xFF6C` | firmware, both bridges |
| `slots` | `ks` per anchor, slot offsets, `at`, slot commands, windows with early opening, ACKs at a slot | firmware, app, PWA, both bridges (`at` only) |
| `tag_schedule` | the tag's anchor and `SYNC` over event sequences | firmware |
| `app_anchor` | the app's confirmed grid and candidates (R1, R32) | app, PWA |
| `areas` | area lookup by home network, level lists, ladder steps | firmware |
| `transport` | scope key and `code[0]` | firmware, both bridges |
| `plan` | the bridge's send per entry and hearing | both bridges |
| `hold` | effective `hold` per value and period | firmware, app |

### Interfaces between components

- **Tag → bridge, app:** report copies on `#libretag` (PROTOCOL 2, 3.4). Flags bit 7 is `SYNC`.
- **App → bridge:** kind 4244 entries: anchors with five `y`; commands with `route` `flood` or `both`, `fb`, `via` for a ROUTED tag, untimed entries 10 counters ahead; ACK entries (`a`); timed entries at `t_k + 1`; blind LOST entries. NIP-09 deletions as before (PROTOCOL 4, 4.3).
- **Bridge → app:** kind 4243 reports (fast publish for direct hearings, `approx` for old or queued frames), kind 4245 receipts for direct hearings only, kind 38244 announcements hourly with repeated `region` and `radio`, ephemeral kind 24244 claims, done claims, "ack sent" notes (`ack`, `e`, `y`, `at`) and miss notes (`miss`, `e`, `g`, `at`).
- **Bridge → tag:** near ACK zero-hop, routed ACK along the reversed path, commands by the plan, fallback with `0xFF6C`.
- **App → own node (MeshCore Open Local API):** `channel.sendData` with `"path": ""` only; needs `tx_path_data`, `rx_meta`; uses `tx_now`/`sentAt` and `rx_queued` when offered.
- **Bridge ↔ node service:** received packets with `routeType` (four values), `path`, `pathHashSize`, `transportCode` or `scope` (`rx_meta`, `rx_path`, `rx_scope`), `queued`; the radio's true state; `radio.stats` as a probe. The Kotlin client library passes `transportCode`, `scope` and `queued` from meshcore-local-api 0.2.0; both Android projects build it from a pinned commit (README, "Android app").

### Firmware (`firmware/lt_core`, the ZephCore patch series)

1. **Core and vectors.** Copies 9, 10, 11 with sealing v2; `lt_packet_set_zero_hop()`, `lt_packet_set_path()`; `SYNC`. ACK: `lt_ack_seal`, `lt_ack_open`, `lt_ack_tag`, the routed parser (refusals as in the vectors), channel and stealth forms. Slots: `lt_slot_key`, slot offsets from `ks`, slot command seal and open, windows with early opening. `LT_CHANNEL_DATA_TYPE_RETRY 0xFF6C`, accepted beside `0xFF4C`. Areas: `lt_area_t`, baked row, four state rows, `lt_area_at` by home network, `lt_levels_at` with the same-radio border, `SET_AREA`. Counter checkpoints at multiples of 16 for the state and the key page floor; no advance when the previous run sent nothing. Check window airtime with preamble 16 (PROTOCOL 2.1, vectors `check_window`).
2. **Link policy as one pure function** with a Python mirror in `host/src/libretag/` (like `lt_wake_decide`): `lt_link_decide`, `lt_link_on_ack`, `lt_link_on_flood_result`, `lt_level_next`, covering the tables of SPEC 3.2: effective `hold`, `ack_patience`, ROUTED, moved by neighbours without a fix, copy 10, `home_silent` after full windows only, the IDLE budget and its `next_in` (R12), the extra flood after an unrepeated `hold` flood (R29), sticky `CMD_ACK` (R4), the answer timing (R18, option C). Vectors `link` of your own; extend `sim.simulate_days` to count floods and repeater transmissions per scenario.
3. **Transmit path:** routes per `txq` entry (near, path, flood at a level); copy 9 then the decision timer; the 8 s retransmission removed; copy 10 for floods that matter; routed copy with its timer (12 s + 1.5 s per hop); routed copies outside the flood counters.
4. **Receive path:** match `xa` for `(n_report, k)` in the listen window and at every slot; near and routed ACKs; commands change no link state; commands under both data types; repeat detection compares with the flood copy actually sent (0 or 10); the anchor rules, the previous grid for 3 slots, `SYNC`; the route type and data type of a received command kept for the answer rule and the log.
5. **Policy inputs and state:** `ref_fix`, `moved`, parked at the second report, hellos while quiet, `next_in` per SPEC 3.2, no-fix backoff, discover only when FAR or ROUTED, state file with `link`, `hold`, `lvl`, path, `path_fix`, area rows; low-battery DM decided at the ACK decision point (R27).
6. **Answer timing** (option C, R18, P3): within 2 s from the looking phase too, without stopping or starting the GPS; Lost and Track by route.
7. **Console:** `area`, `link`, `scopetest`, `ackpatience`, the `status` line, history flags, events. Phase deadlines (`stall` instead of a reset). Early sleep last.
8. **Tag field log** (one record per wake-up): `seq`, `up_s`, `n`, `copies` (copy and ms offset, e.g. `9@0,0@12040,10@20100`), `why` (`period`, `moved`, `hold`, `heartbeat`, `nofix`, `boot`, `cmd`, `low`, `mode`, `console`, `budget_last`), `mode`, `link_before`, `link_after`, `lvl`, `fix`, `search_s`, `hacc_m`, `fix_utc`, `moved_m`, `rep_ms`, `rep_hash`, `ack_form`, `ack_k`, `ack_ms`, `ack_snr`, `cmd_op`, `cmd_seq`, `cmd_k`, `cmd_route`, `cmd_type` (`4C`, `6C`), `cmd_ms`, `cmd_dup`, `anchor_n`, `sync`, `prev_slots_left`, `slot_k`, `rx_open_ms`, `rx_ms`, `path`, `path_age_s`, `floods_24h`, `idle_budget_left`, `next_in_s`, `batt_mv`, `batt_pct`, `energy_mah`, `listen_ms`, `early_sleep`, `stall`, `txfail`, `boot_cause`, `boot_resets`; plus a compact record (or an hourly counter) per check without anything received.

Bench tests: a hello with no receiver and `ack_patience` 2 shows one DIRECT packet then a flood 12 s later, with the default the flood comes at the next check; an ACK from the Python reference over a second radio gives `link near`; wrong `k` or `n` is ignored; an ACK at a check sends nothing; a routed ACK gives `link routed 1`, the next event report is one DIRECT packet with one path entry, and with the repeater off the flood follows after 13 s; a Beep 10 s after a report is answered within 5 s; a Beep at a reporting slot during a GPS search is answered within 2 s and the bridge sends no fallback; five resets within a minute move `n` by 16 once; a blocked `TX_DONE` ends in `stall` without a reset; at a listen-only slot an ACK without `MORE` followed by a command 300 ms later is answered (the receiver stays on 1 s after the ACK), and with no command the receiver closes about 1 s after the ACK.

### Python bridge (`host/src/nostrmesh`) and meshcored

1. **Arrivals (R36, P5):** all four route types; hop count null for DIRECT, 0 only for a flood with an empty path; `heard_direct`; the KISS adapter keeps DIRECT; the companion-serial adapter takes path length `0xFF` as DIRECT without a path after a check against the firmware, wires up the path argument, and says at start when it cannot serve ACKs.
2. **Reports:** DIRECT `GRP_DATA` as live reports; lock and anchors with five `y`; fast publish (0.3 to 0.8 s) for direct hearings and those over 1 or 2 hops with no untimed entry waiting; receipts for `heard_direct` only, published with the report, on by default; the outbox written at hearing time (R21); `approx` for frames older than 20 s or `queued` (R22).
3. **Entries:** ACK entries (`a`, 13 or 26 B; pass `accept_open` off; lock and path store only, R25); `quietmesh.plan` for every ACK and command entry; near ACK jitter and on-air dedup; routed ACK claim round 2 s and the skip on an "ack sent" note; ACK before command 300 ms; ACK budget; "ack sent" and miss notes; route `both`; fallback after 12 s with `0xFF6C`, `fb`; group done by a `y` of another untimed entry on any route (R34); the 10-minute gap not for ACKs or the second grid (R3); Lost and Track keep the fallback on every slot (R35); path records 48 h from the last use, renewing their lock (R33); `direct_max_hops` 2; blind LOST entries; reply scope from `transportCode` or `scope` against `[radio] regions`; `late_max_s` 40; timed entries answered after the radio took the frame, retried to `at + 20 s` (R20); claims without waiting.
4. **Announcement:** hourly, repeated `region`, `["radio", state, since]`, republished within a minute of a change (R13).
5. **Radio health (R37, P14):** detection from the radio's own traffic and a 5-minute `radio.stats` probe, `radio_degraded`, `radio_down`/`radio_up`, no exit, miss notes with `radio`, `[watchdog] alert_cmd` and `recover_cmd` hooks, the recovery order of SPEC 3.2. meshcored (its own repository): the true radio state when the port cannot be opened, late answers and unanswered heartbeats exposed to clients, reboot by command while the node stalls, `transportCode` and `routeType` in receive events (`rx_scope`, `rx_meta`), the resolved `scope` name. On the bridge host: test whether the hub can switch the node's port power with `uhubctl`.
6. **Statistics (B6):** per report the route type and hop class (0, 1, 2, 3+, unknown); per path send whether a report or ACK followed.
7. **Relay policy** (`host/deploy/strfry-mailbox-policy.py`): burst 120, then 60 a minute per address, anchors and ACK entries counted apart from commands; unit test; deployed with the bridges; check that 13 B content and short expirations pass.
8. **Bridge field log** (`fieldlog.py`): `rx` (`x`, `len`, `route`, `hops` empty for DIRECT, `path`, `phs`, `scope` or `transport`, `snr`, `rssi`, `rx_time`, `stale` or `queued`, `first`, `heard_direct`), `lock` (`x`, `keys`, `lead_s`), `report_pub` (`x`, `id`, `created_at`, `delay_s`, `approx`, per relay `ok` and `ms`, `receipt`), `mailbox` (`id`, entry kind `anchor`/`cmd`/`late`/`timed`/`ack`, `y`, `via`, `at`, `route`, `fb`, `g`, `p`, `created_at`, `known_at`, result), `claim` (`y`, own or rival, `hops`, won, `wait_ms`), `tx` (`xc` or `xa`, what `cmd`/`ack_near`/`ack_routed`/`fallback`, `how`, `path`, `region`, `dtype`, `at`, `late_ms`, `airtime_s`, `ok`, `error`, `tries`), `tx_skip` (`reason`: `dup_on_air`, `via_gap`, `hourly_cap`, `airtime`, `missed`, `no_path`, `not_direct`, `cancelled`, `group_done`, `radio`), `fallback` (`xc`, armed, fired or cancelled, the cancelling `x`, ms), `answer_seen`, `path_use` (`x`, `via`, hops, confirmed), `radio_up`, `radio_down`, `radio_degraded` (reasons `api_says_down`, `send_failed`, `rx_silence`, `probe_timeout`, `late_answers`), `recover` (step, result), `relay_up`, `relay_down`, `start` (version, adapter, capabilities), `stop`, `clock` (hourly offset against the relays).

Tests on the shared vectors: an ACK entry accepted; refused when the report came over 3 hops; refused from an unlocked key; never flooded; a routed ACK for 1 and 2 hops, not for 3; near-only entry for a moved tag sends no routed form; the fallback flood has other bytes; no fallback when an answer arrived in time; an answer along the path (DIRECT, empty path) cancels the fallback and closes the group; a report 5 s after `via` with a `y` of the next counter closes the group; a day-3 entry with a day-1 `via` still finds the path after a day-2 use; a radio error at `at` then success 4 s later sends once; a 20-minute-old replay is published with `approx` and locks nothing; a wedged fake serial gives `radio_down` within 2 minutes, the announcement, a miss note, the hooks once per step, `radio_up` on return.

### Android bridge (`android-bridge`)

The same as the Python bridge, items 1 to 4, 6 and 8, on the same vectors (`Bridge.kt`, `Radio.kt`, `Lt2.kt`): `RxMeta.hopCount()` null for DIRECT; `downlinkRoute()`, `firstHopKey()`, `sameWay()` and the receipt follow from it; `groupAck()` by R34. Its own items: claims published without waiting (R23); nos.lol out of the default relays; an exact alarm (`setExactAndAllowWhileIdle`) for the next due entry and a wake lock from 10 s before `at` until the send returned (R24); radio health from MeshCore Open (`device.connected` false, a failed send, no frame for the limit) with the announcement and miss notes, without recovery hooks. Test: with one relay unreachable a timed entry leaves within 1 s of `at`.

### App (`android`) and PWA

1. **Decoder and window** for copies 9, 10, 11; anchors with five `y`, 16 ahead in IDLE and 24 in TRACK and LOST, the two checkpoint counters and the next epoch, expirations to 50 h; publish order ACK, command, anchors, 150 ms apart, 20 s back-off on `rate-limited:` (P9).
2. **ACK sender** (SPEC 3.2, the state machine Opened → LiveNode, LiveNostr, Timed → Sent → Settled): own node for copy 9 or a 0-hop flood, only with `rx_meta`; ACK entries from relays at once, `p` from a receipt within 500 ms; forms by copy and the standing test; timed ACKs on the anchor's slots (2 near, 1 routed, none after a newer report); age from radio time with the measured clock offset (R17); `MORE` while a command is queued, the tag's screen is open, and for 30 s after a notification. ACK latency log.
3. **Anchor and command plan:** `AppAnchor` of the vectors (R1, R32); entries for every grid with `fb` 0 on all but the latest, none for Lost and Track (R3, R35); `both` for Lost and Track and after an unconfirmed slot; slots for the next 3 checks then every 3rd (LOST every check for 6 h); untimed entries 10 ahead; `via` on every entry for a ROUTED tag (R34, R36); blind LOST behind a confirmation; own-node slot sends by alarm at `t_k + 1 s` with retries (R9, P7).
4. **Hygiene** (P12): `queuedAtN` and the confirmed rule with the mode check (R5); random `cmd_seq` (R6); lifetime and the "did not reach" notice (R7); old reports change only the history (R8); republish at start, retry until a relay accepts (R10); frames in the first 20 s after the node connects or marked `queued` are approximate (R11); guard by max(300 m, 3 × accuracy) on exact reports (R16); the second sighting of a report within 120 s (P8); one log line per datagram and the DM dedup (P2); `isNear` (P10); `LISTEN_S` 60 and late entries to 40 s without a receipt wait (P6).
5. **Status and notices** (A3): silence deadlines by link; "bridge offline" from announcements (R13); "reports limited" (R12); "battery probably empty" (R14); "Lost mode ended" (R15); "not sent" from miss notes (R20); "heard but gets no answers"; "Your MeshCore node is off"; "schedule not known"; `scheduleMismatch()` only for hellos and the first flood after a move. Unit tests for each row.
6. **Settings** (A4, A5): "Check in over the wider mesh after" (0, 6, 12, 24 h; 0 while guarding), "Report at once when it moves" (`ack_patience` 2), the link line in the tag detail; the wizard writes `interval 60`, `report every 3`, parked 24 h; the parked sheet offers 24 h; `SET_AREA` plumbing without UI. English first, translations by the Sonnet subagent, the humanizer on all text.
7. **App field log** (`DiagLog.ev`): `report` (tag, `n`, `j`, `x`, `via` node or relay, relay URL, `created_at`, `rx_time`, `age_ms`, `approx`, `is_new`, `second_sighting`, `hops`, `route`, `sync`, `cmd_ack`, `cmd_seq`, `mode`, `fix`, `hacc_m`, `next_in_s`, `batt`, flags), `anchor` (A, candidates, counter gap, reason, or `unknown`), `ack` (`n`, `xa`, form, live or timed, `k`, `at`, path node or nostr, `p`, `hold`, `more`, `report_age_ms`, publish per relay, `sent_note_ms`, `settled`), `cmd_queued`, `cmd_plan`, `cmd_sent`, `claim_seen`, `miss_seen`, `cmd_confirmed`, `cmd_ended`, `notify` (kind, trigger, what cancelled it), `status`, `node_state`, `relay_up`, `relay_down`, `publish`, `sync`, `clock`, `doze`.
8. **PWA:** decoder and window for the new copies; ACK entries while open with the same forms; `AppAnchor`; status wording reduced. Versioned release with the automatic update check.

Unit tests: an old report with a matching number does not confirm; a restored device does not reuse the tag's last `cmd_seq`; an expired Beep posts one notice; an out-of-order report does not clear the silent latch; a relay copy then a node copy triggers the own-node send once; five tags reconnecting get no event refused.

### MeshCore Open (our fork, and the upstream pull request)

Fork branch with the Local API; the upstream pull request is amended with the same.

1. Zero-hop and path sends of channel data (`tx_path_data`) in the pull request too.
2. Receive events: `routeType` (`flood`, `direct`, `tflood`, `tdirect`) under `rx_meta`, `path` and `pathHashSize` under `rx_path` (paired from the raw packet push), `transportCode` under `rx_scope`, and the resolved `scope` name when the node knows it. `hops` stays null for DIRECT.
3. `queued: true` (`rx_queued`) on every event drained before the first live push after a connect.
4. `"priority": "now"` on `channel.sendData` (`tx_now`) for payloads of at most 40 B on air, and `sentAt` in the result.
5. Payloads of at most 32 B exempt from the 2 s send gap (keep 30 a minute).
6. Held channel-data frames after a connect released to the API regardless of the image-message setting.
7. Traffic log per send (request time, lock wait, quiet wait, firmware ack time, path length, result) and per event (`routeType`, `hops`, `path`, `queued`, `receivedAt`), link changes with the reason.

Tests: `sendData` with `"path": ""` gives a frame with path length 0; an event for a DIRECT packet has `routeType: "direct"` and `hops: null`; a queued event has `queued: true`. Bench: the app sends an ACK through the phone's radio; a second radio sees one 21 B DIRECT packet and no flood.

### Acceptance tests

Two field trips come first, with all four logs on and the phone's clock set from the network:

- **A walk of about three hours** in TRACK at 3 minutes, with one parked hour in IDLE at `interval 15`, `report every 4`: out of the bridge's direct range and back, a stop one or two repeaters from the bridge (two floods, then a routed ACK, then routed copies), the phone in flight mode for 10 minutes, the owner's node switched on half way, commands at slots in the parked hour. Worked out: confirmations by route, hello-to-ACK time (90 % under 9 s with the app open, else raise the 12 s), floods repeated per level, hop counts at the bridge, path sends confirmed (three in four or better, else `direct_max_hops` 1 or 0), fallbacks fired and whether needed, slot timing (0.5 to 10 s into the window), queue-to-confirm per command, false or missing notices, anchor agreement.
- **A courier trip across the country and back** in TRACK at 15 minutes, with the phone and the bridge staying home: where reports stop and start being heard and over how many hops, the ladder at the edge of the area, what the app shows during the gap, how long a queued Lost takes after the tag is back, transmissions for commands that could not arrive (none expected), floods in 24 h (under 200) and battery used.

Both trips together decide whether wall-clock slots go into the next release. Then the acceptance list:

1. At home, bridge on, phone normal, 48 h: `floods_24h` 0, no silence notice.
2. Phone in flight mode for 8 h: no flood. For 14 h: one flood, and the app recovers by itself.
3. Bridge stopped for 24 h: one flood after about 12 to 15 h.
4. Drive across town and park out of range: a flood on the way, one after parking, the quiet flood, then hellos only.
5. TRACK next to the bridge for 30 minutes: no flood, a position every 5 minutes.
6. Reboot the tag in range: the boot report goes out zero-hop and is ACKed.
7. `scopetest` at three places in the area; adjust the area radius.
8. Park out of range, queue a Beep: the tag beeps within about an hour, the bridge log shows one scoped flood per try.
9. Drive off from home with the phone in flight mode: one flood at the first check after the hello. The same with the app open: no flood.
10. Two weeks of the app's ACK latency log before the release.
11. Park one or two repeaters from the bridge: two floods on arrival, then `link routed`; the next day's heartbeat arrives without a flood; a queued Beep arrives at the next check with a path send and no flood in the bridge log.
12. Same place, LOST for an hour: floods stop after the second report; the bridge log shows 6 routed reports and 6 routed ACKs.
13. Same place, bridge stopped: the next routed report is followed by a flood 13 to 14 s later (LOST) or at the next check (IDLE), and the tag shows `link far`.
14. `both` on the bench: a Lost for a tag taken out of the bridge's range goes zero-hop, then as a flood 12 s later, and a repeater next to the bridge forwards the flood.
15. Two weeks of bridge statistics: the share of reports heard over 1 or 2 hops and of path sends confirmed; below about three in four, set `direct_max_hops` to 1 or 0.
16. Pull the bridge radio's USB cable for 10 minutes with the bridge running: `radio_down` within 2 minutes, the announcement says `radio down`, a Beep for a slot in that time shows "Not sent", the app names the bridge as offline and raises no "silent". Cable back: `radio_up`, and the next hello is ACKed.
17. At the place of item 11, queue a Beep: the answer comes back along the path, and the bridge closes the group with no fallback flood.

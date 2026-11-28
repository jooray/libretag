# Field-test logs

The tag, the bridges and the app each write a JSON-lines log during a field test (a walk around town with the tag, a courier trip with it), and `libretag fieldeval` merges them into one timeline per report copy. All logs share three keys: `t` (Unix time in ms, UTC), `src` (`app` or `bridge`; tag records have `type` and `seq` instead) and `ev`. Across components a report copy is joined by `x`, the first 4 bytes of the packet and the `y` tag of its kind 4243 event. A command is joined by `xc`, an ACK by `xa`. Every `x`, `xc`, `xa` and path hash is lowercase hex. A bridge never knows `n` or `j`. The app logs them next to `x` in its `open` lines, which links the tag's own records to the bridges' hearings.

The tag does not log `x`: it is `HMAC(V_e(n), "LT2-x" || u32(n) || u8(j))[0:4]` and needs the view key. `libretag fieldlog` adds it to every TX record of a report copy or extra packet when it has the key (`--k0-file`, or a stored share of any level, also with `--decode`); the key never goes into the output. Without the key, tag records join the others on `n` and `j` through the app's `open` lines. Every tag record that names a report counter calls it `n` (TX records, check windows, `chk`, `wake`, events); the event record's own value is `v`.

No keys and no decrypted payloads go into any log. Positions appear only in the tag's log and in the app's `report` lines. Keep the files on the devices and in a scratchpad, never in the repository.

## Where each log is and how to get it

| Component | File | How to get it |
|---|---|---|
| Python bridge (`nostrmesh run`) | `~/.config/nostrmesh/fieldlog/bridge-YYYYMMDD.jsonl`, one file per UTC day, kept 30 days. `[fieldlog] dir` changes the place, `dir = ""` turns it off | `rsync host:.config/nostrmesh/fieldlog/ ./bridge/` |
| Android bridge (`app.nostrmesh`) | `/sdcard/Android/data/app.nostrmesh/files/fieldlog/bridge-YYYYMMDD.jsonl`, 30 days | `adb pull /sdcard/Android/data/app.nostrmesh/files/fieldlog` |
| LibreTag app | `/sdcard/Android/data/app.libretag/files/fieldlog/app-YYYYMMDD.jsonl`, 30 days, next to `libretag.log` | `adb pull /sdcard/Android/data/app.libretag/files/fieldlog`, or Settings > Share log (sends `libretag.log` and the JSONL files) |
| Tag (firmware 0.9.8) | the tag's field log | `uv run libretag fieldlog --out tag.jsonl` (with the tag's key stored or `--k0-file`, TX records get `x`) |

## Bridge events

| `ev` | Fields |
|---|---|
| `start`, `stop` | version, adapter, region, relays, bridge pubkey, Local API capabilities; the reason for stopping |
| `rx` | every `#libretag` packet the radio passes up: `x`, `len`, `kind` (`report`, `ble`, `cmd`, `other`), `hops`, `route` (`flood` or `direct`), `path` (hashes, first hop first), `phs`, `snr`, `rssi`, `rx_time`, `stale`, `first`, `heard_direct`, `scope` or `transportCode` when the server gives them, and `result`: the bridge counters the packet moved (`rx_lt2_report`, `lt_rx_duplicate`, `lt_lock`, `lt_cmd_unsigned`, …) |
| `lock` | `x`, keys locked for it |
| `report_pub` | the kind 4243 for `x`: event `id`, `created_at`, `heard_at`, `delay_s`, `approx`; or `skipped` when enough relays already had it |
| `publish` | one relay's answer to one event: `id`, `kind`, `url`, `ok`, `msg`, `ms` (the last try), `total_s`, `tries` |
| `mailbox` | a kind 4244 or anchor this bridge has not seen before: `id`, `url`, `pubkey`, `created_at`, `y`, `at`, `via`, `route`, `g`, `p`, `anchor`, `result` (counters moved: `lt_mailbox_added`, `lt_mailbox_unsigned`, …). An anchor resent after a relay reconnect is not logged again |
| `claim` | own (`won`, `rivals`, `wait_ms`) or another bridge's (`pubkey`, `known`); `y`, `hops` |
| `cmd_tx`, `tx_skip` | a mailbox entry sent (`id`, `y`, `xc`, `how`, `path`, `at`, `late_ms`, `ok`) or held back (`reason`) |
| `tx` | every own transmission: `channel`, `dtype` (an integer, 65356 for `0xFF4C`, in both bridges), `len`, `xc`, `how` (`flood`, `zero-hop`, `direct`), `path`, `region`, `airtime_s`, `ok`, `error` |
| `radio_up`, `radio_down`, `relay_up`, `relay_down` | link changes, with the state or reason |

The Android bridge writes `rx`, `tx`, `publish`, `mailbox`, `claim`, `relay_report` (a kind 4243 from a relay), `relay_up`, `relay_down` and `start`. Without `rx_path` or `rx_meta` on the MeshCore Open Local API it leaves out the path, route and RSSI and keeps working.

## App events

| `ev` | Fields |
|---|---|
| `mesh_rx` | every datagram the owner's node passes up through MeshCore Open: `size`, `x`, `rx_ms`, `received_at`, `hops`, `route`, `path`, `phs`, `snr`, `rssi` (as far as the Local API gives them) |
| `open`, `open_fail` | a report copy opened: `tag`, `via` (`node` or `relay`), `n`, `j`, `x`, and for a relay copy the event `id`, `pubkey`, `created_at`, `approx_until_ms` |
| `report` | the decoded report: `tag`, `n`, `via`, `rx_time`, `age_ms`, `is_new`, `fix`, `lat`, `lon`, `hacc`, `fix_time`, `batt`, `mode`, `cmd_ack`, `cmd_seq`, `quiet`, `parked`, `checks`, `next_in_s`, `boot`, `low` |
| `relay_event` | every kind 4243, 4244, 4245 or 24244 a relay delivers, with the arrival time: `url`, `kind`, `id`, `pubkey`, `created_at`, `y`, `approx`, `at`, `via`, `hops`, `route`, `query` |
| `windows` | the check schedule the app worked out from a report: `n`, `t0`, `interval_min`, `hb_s`, `offsets_s` |
| `cmd_queued`, `cmd_sent`, `cmd_confirmed`, `claim_seen` | a command's life: `seq`, `kind`, the path (`node` with `route` and `k`, `mailbox` with the event `id`, `y`, `at`, `late`, or `ble`), the confirming report `n` |
| `notify` | every notification: `tag`, `kind`, `channel`, `alarm` |
| `relay_up`, `relay_down`, `publish` | per relay, with `ms` for a publish |
| `node_state` | MeshCore Open link state |

## Evaluation

```
uv run libretag fieldeval tag.jsonl bridge/ phone/fieldlog/ --timeline /path/to/scratchpad/timeline.jsonl
```

It prints, for all report copies: who heard each one (bridges and the owner's node), the bridges' hop counts and route types, latency per stage (tag to bridge, bridge to publish, relay OK, relay to app, `created_at` to app, end to end) as p50, p90 and maximum, the delivery rate, the share of relay deliveries that took over 60 s, floods per hour (the tag's and the bridges'), each command from queued to confirmed with the bridge transmissions it caused, and coverage by distance band from `--ref lat,lon` (default: the first position) without coordinates. `--json` prints the same as JSON. `--timeline` writes one line per report copy, positions included.

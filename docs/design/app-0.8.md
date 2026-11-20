# Android app 0.8.0: protocol-relevant notes

Status: 2026-11-17. Things in the app that other implementations (bridges, PWA) have to match,
or should know about. PROTOCOL.md is owned elsewhere; once these are agreed, they belong there.

## Mailbox PoW (kind 4244)

Superseded in app 0.11.0 (2026-11-18): the signed mailbox (PROTOCOL.md 4.2) replaced proof of work; `Pow.kt` and its vectors are gone. The section below is history.

Every mailbox entry the app publishes carries NIP-13 proof of work.

- Tag `["nonce", "<counter>", "<target>"]`. The app puts it last; position is free. The target is the
  committed difficulty (NIP-13), always the required value below.
- Difficulty = number of leading zero bits of the event id.
- Required: untimed entries (no `at`) 16 bits. Timed entries by lead `L = at − created_at`:
  `L ≤ 300 s` 16, `≤ 1800 s` 20, `≤ 7200 s` 22, `≤ 28800 s` 24, else 26. A negative lead counts as `≤ 300 s`.
- Accept iff `difficulty ≥ required` **and** `target ≥ required` (a lucky low-target event is rejected).
- Group tag `["g", "<16 lowercase hex>"]`: random per command, the same on every entry of that
  command (untimed and timed, and on timed entries published later after a new quiet report).
  The app keeps it in `PendingCommand.group`.
- Order: untimed first (16 bits, milliseconds), then timed by ascending `at`. Each entry is mined just
  before it is published, with `created_at` = start of its mining, so the nearest check is on the
  relays within seconds. An entry whose `at` passed while earlier ones were mined is skipped.
- Mining: own SHA-256 with the fixed prefix compressed once (midstate), all cores
  (`availableProcessors()` threads), off the main thread, one command at a time, under a partial
  wake lock (10 min cap) in the process `TrackerService` keeps in the foreground. Publishing does
  not wait for slow relays (the pool retries in the background). Cancel (NIP-09 kind 5) unchanged.
- Rough cost: 2^bits hashes on average. 26 bits ≈ 67 M; at a few million hashes/s on a phone that is
  tens of seconds per entry. The log line `mailbox entry … bits in … ms` records the real figure.
- Vectors: `docs/vectors/mailbox-pow-v1.json` (lead table, accept/reject events; ids only, no
  signatures). Kotlin: `PowVectorsTest`, `PowTest` (also the NIP-13 example note).

## Private DMs (NIP-17)

Welcome DM, the 25 % battery DM and share invitations go as NIP-17: kind 14 rumor (with `p`),
kind 13 seal signed by the tag's `dm_sk`, kind 1059 wrap signed by a fresh key with one `p` tag.
Seal and wrap get independent random `created_at` up to two days back. The 25 % DM carries
`["expiration", now + 14 d]` on rumor, seal and wrap.

- Targets: only the recipient's kind 10050 relays (newest event, at most 6, public `wss://`).
  NIP-42 AUTH is answered with the wrap's one-time key, not the tag's key.
- Fallback: no kind 10050 found, or no inbox relay accepted the wrap → NIP-04 kind 4 to our relays
  and the recipient's 10002 read relays, exactly as before 0.8.0. NIP-17 says not to send a wrap
  without a 10050; NIP-04 keeps those recipients reachable during the transition.
- The tag's own radio DM (`dm4x.full`, bridges publish kind 4) stays NIP-04.
- The tag's kind 0 is unchanged (still public, still named LibreTag; audit 3.1 open point).
- Primal: a Nostr Compass newsletter (2026-04-08) lists Primal among clients that use NIP-17 as
  their primary DM protocol. Not verified on a device.

## 10 % battery

Notification "battery almost empty", once per crossing, from either signal:

- a report (Nostr or mesh) with battery ≤ 10 % (firmware 0.7.6 reports at the 10 % crossing even
  when quiet);
- the tag's own kind 4 (author = tag `dm_sk` pubkey). The app subscribes `{"kinds":[4],"authors":[dm pubkeys],"since": now − 14 d}`,
  decrypts with `dm_sk` and the `p` pubkey (NIP-04 ECDH is symmetric) and reads `^batt (\d+) ?%`.
  The app's own NIP-04 fallback texts don't match.

The crossing ends when a report shows > 20 %; DMs created before that moment are ignored.
Shared tags (no `dm_sk`) only get the report signal.

## Report query (kind 4243)

Before 0.8.0 the app sent one REQ per tag with 128–512 `#y` values. A relay could link those tags
to one tag and to the owner's IP address. Now:

- Live: one subscription `{"kinds":[4243],"since": now − 60}`, renewed every 30 min.
- Backlog: after start, every reconnect and every 30 min, all kind 4243 since the last sync
  (−1 h overlap for bridges that publish late), paged backwards with `until`/`limit 500`,
  capped at 20 000 events per sync. Events are matched on the phone (table lookup on `x`; the
  Schnorr signature is checked only for matches).
- A new tag (never heard) triggers a backlog of the last 14 days.
- Thresholds: one report ≈ 300 B. 1000 active tags reporting hourly ≈ 24 000 events ≈ 7 MB a
  day, so the 20 000 cap is about a day of such a network. Past the cap the rest of the range is
  fetched with the old per-tag `#y` query. If the live stream exceeds 3000 events/h
  (≈ 72 000 a day, ≈ 21 MB), the app switches to the per-tag window query for 24 h, then tries again.
- Bridges and relays need nothing new. Relays must allow `limit`/`until` queries for 4243 without
  `#y` (strfry and nostr-rs-relay do).

## Window resync

The window is still `lastN − 256 … lastN + 2048` (4096 for a tag never heard). Reports that no
window opens stay in memory (up to 20 000, by `x`). When a tag is overdue (past its expected
report + 30 min, Lost included) or never heard for an hour, the app searches forward from the
window's end in blocks of 2048 up to 65 536 past `lastN` against those unopened reports (on the
first attempt per process it first refills them with the last 14 days). In window-query mode it
asks the relays block by block (`#y` chunks of 256). On a hit the window jumps there and the
later cached reports of that tag open too. At most once per 6 h per tag.

## Secrets at rest

K0, shared `V_e`/`C_e`, mailbox event keys, backup-derived keys (`aSk`, `aEnc`, the backup book
with K0s, `backupSk`) and the NIP-46 client key are stored as `ks1:` + base64(iv ‖ AES-256-GCM)
with a non-exportable Android Keystore key (`libretag-secrets-v1`). Plain values from earlier
versions are rewritten wrapped at first start. If Keystore fails, values stay plain (logged).
A value that can't be unwrapped is kept as it is, never overwritten; restore from the backup then.
No app lock (not decided).

## Silent-tag backup alarm

AlarmManager `setAndAllowWhileIdle` (inexact, no exact-alarm permission) at the next moment a tag
would become silent, at most an hour away. The receiver fetches the backlog (≤ 7 s) before
judging, so a process the system killed doesn't raise a false alarm, then runs the same check as
the in-app loop. Settings shows whether notifications are allowed and when the check last ran and
runs next.

## Export

Tag menu > Export history: GPX 1.1 (GPS fixes only, one track, time = GPS time) or CSV (every
report: n, received/fix time UTC, fix, lat/lon, accuracy, battery, mode, via, hops, quiet,
estimate lat/lon/r68/r95), shared through `FileProvider` from `cache/export/`.

## Bluetooth command advert (0.8.7): field test still to do

The tag takes an advertised command by its 4 B tag `xc` and never looks at the address, so
delivery does not depend on the address type. SPEC 11 (decisions) and PROTOCOL.md 3.1 used to say
the phone advertises from a resolvable private address, so a phone paired with `blepair` would also
count as present. Nobody has checked that. A normal app cannot choose the address type
(`AdvertisingSetParameters.setOwnAddressType` is a system API), and the stack may use a
non-resolvable address for a non-connectable set. Until the test below, treat presence through the
command advert as not guaranteed.

The owner's test, with a tag that has an anchor and a pending command:

1. Queue a command for the tag and watch `blecmd` on its console: `seen` and `taken` after the
   next wake-up that scans. This is the radio path the bench did not cover.
2. On a second phone, run nRF Connect and note the address type of the LibreTag advert
   (company id `0xFFFF`): resolvable private, non-resolvable private or public.
3. If it is resolvable and the phone was paired with `blepair`: check whether `quiet` on the tag
   reports owner presence while the phone advertises and no other anchor is near.

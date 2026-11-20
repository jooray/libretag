# PWA 0.2.0: design notes

Owner decisions from the audit of 2026-11-17 (3.1, 3.2, 10), implemented in `pwa/`. Shared rules with the Android
app and the bridges are marked "shared".

## 1. NIP-17 DMs (shared)

What the web app sends as a DM: only the tag's welcome message ("Send welcome message again" in Edit tag). It has no
battery warning and no invitation DM (sharing is a link/QR only), so nothing else needed converting. It reads no DMs,
so "keep reading NIP-04" has nothing to act on in the PWA.

Sender. The seal (kind 13) must be signed by the sender. The sender is the tag's dm key (`dm_sk`, derived from K0),
which the web app holds for every tag with K0, exactly like the Android app (`Nip17.dm(keys.dmSk, …)` in
`LibreTagApp.sendDm`). So no signer is involved. This matters because the NIP-46 flow (Amber) would not allow it
anyway: `signer.js` exposes only `nip44Encrypt/Decrypt` with the user's *own* pubkey as peer (backup copy B to self)
and the nostrconnect permissions ask for `nip44_encrypt`, `nip44_decrypt`, `sign_event:30078`, `sign_event:5`. A seal
from the user would need `sign_event:13` plus NIP-44 to a third party, i.e. new permissions and a signer round trip
per message. Not needed.

Layers (`src/lib/nip17.js`): rumor kind 14 (`p` = owner, unsigned, with id) → seal kind 13 signed by `dm_sk`,
NIP-44 v2 to the owner → gift wrap kind 1059 signed by a one-time key, NIP-44 to the owner, tag `p`. Seal and wrap
`created_at` are random within the last two days (NIP-59). Optional `expiration` goes on seal and wrap (NIP-17
"disappearing"); the welcome DM has none. `unwrap()` checks both signatures and that the rumor author equals the seal
signer.

Relays (shared rule, matches Android `sendDm`): look up the owner's kinds 10050 and 10002 on our relays and
`wss://purplepag.es`. If a 10050 list exists, the wrap goes only there (at most 6), AUTH (NIP-42) answered with the
wrap's one-time key so the relay learns nothing about the tag. If there is no 10050 list, or no inbox relay accepted
the wrap, the old NIP-04 kind 4 goes to our relays plus the owner's read relays (AUTH with the tag key). The UI says
so ("…no relays set for private messages…"). The kind 0 profile of the tag is still published (unchanged, see open
questions).

Tests: official NIP-44 v2 vectors (`tests/vectors/nip44.vectors.json`, paulmillr/nip44: conversation keys,
encrypt with fixed nonce, decrypt), the NIP-59 and NIP-17 examples from the NIPs repo
(`tests/vectors/nip17-examples.json`: both wraps open to "Hola, que tal?"), our own wrap round trip, impersonation
refused, and both relay paths of `sendWelcome`.

## 2. Battery at 10 % or below

Firmware 0.7.6 sends a report at the 10 % crossing. `statusOf` has a new kind `BATTERY_CRITICAL` (battery ≤ 10 %):
level `crit` (red band on the card and detail), priority after SILENT and before LOW_BATTERY (20 %). Card battery
figure in red, detail row "9 % · almost empty" with "Charge it now…". A SILENT tag whose last report said ≤ 10 % gets
the sub line "Heard … with the battery at 8 %. It has probably run out."

## 3. Mailbox 4244: PoW and group (shared spec)

Superseded in PWA 0.3.0 (2026-11-18): entries are signed with the mailbox key `sk_n` and carry no work (PROTOCOL.md 4.2, `docs/design/signed-mailbox.md`). The section below is history.

- `["g", <16 lowercase hex>]`: one random value per command (`pending.g`), on every entry of it (untimed and timed,
  also entries published later for new report counters and on resume).
- NIP-13: `["nonce", "<counter>", "<target>"]`, difficulty = leading zero bits of the id. Target = required bits:
  untimed 16; timed by lead L = at − created_at: ≤ 5 min 16, ≤ 30 min 20, ≤ 2 h 22, ≤ 8 h 24, above 26
  (`src/lib/pow.js` `requiredBits`). `created_at` = the moment the entry is signed, just before mining.
- Miner: own SHA-256 compression with a midstate. The nonce tag is last, the counter is 13 fixed-width digits, so
  everything before the counter's 64-byte block is hashed once and each attempt compresses one or two blocks
  (~1.3 M hashes/s per core in Node; 26 bits ≈ 67 M hashes, a few seconds on an 8-core desktop, under a minute on a
  phone). `powrun.js` runs one Web Worker per `navigator.hardwareConcurrency` (max 16), each on its own stride;
  first answer wins, all workers are terminated. Without Worker (tests) it mines on the main thread in slices.
- Order: `mailboxEntries` returns untimed entries first, then timed in ascending `at`. `App.publishPending` mines
  and publishes them one at a time; `queueCommand` resolves after the first entry is out (16 bits, well under a
  second), the UI shows its usual toast then, without progress text. The rest go on in the background. A timed
  entry whose `at` is ≤ now + 5 s is skipped. If the first entry finds no relay, the queue stops (retried later).
- Tab closed mid-queue: the unpublished entries are simply lost. On the next open, after the first sync of the tag,
  `publishPending` runs again: it recomputes the entries from the current last report and check plan and publishes
  only those not yet out (an entry is "out" while a published, unexpired event carries all of its `y` tags,
  `pending.sent[].ys`). So checks still ahead and the next report counters are resumed; passed checks are not. The
  same rule stops the old behaviour of republishing all four untimed entries with new keys on every new report.
- New report while the queue runs: it recomputes after the current entry. Ack (`CMD_ACK`): the queue stops and the
  remaining published entries are deleted (NIP-09, own keys), so bridges do not flood checks that are no longer
  needed. Cancel: the queue (and any mining) stops, deletions as before.
- NIP-09 deletions now carry the `expiration` of the event they delete (PROTOCOL §4 asked for it; the PWA had none).
- Tests: required-bits table and leading-zero examples, SHA-256 against noble at padding edges, NIP-01 serialization
  identity, mined ids match nostr-tools, abort, the shared vectors in `host/tests/vectors/mailbox-pow.json`, group
  tag on all entries, ordering, resume publishes only the new entry, cancel stops the queue.

## 4. Private report query

`App.syncTag` coalesces into one sync for all tags that asked. Default mode `all`: `fetchAllReports`
(`src/lib/sync.js`) asks each relay for `{kinds:[4243], since, limit:500}` and pages with `until` = oldest
`created_at` of the page (inclusive; repeats skipped by id; a page with nothing new ends that relay). `since` = the
oldest `syncedTo` of the tags being synced minus 10 min overlap, or 8 days back for a tag never synced (persisted per
tag). Events are matched locally against each tag's window (first 4 bytes of the payload), repeated until the window
stops sliding. Live: one subscription `{kinds:[4243], since: now − 120}` for all tags, matched locally. No `#y` ever
leaves the browser in this mode.

Fallback threshold: if one relay has more than 20 000 events in the span (`FETCH_ALL_MAX`), the sync switches to
the old per-tag `#y` windows (250 tags per REQ) and per-tag live subscriptions for the rest of the session. Why
20 000: at ~300 B per event (JSON with signature) that is ~6 MB per relay and sync, about a day of 800 tags reporting
hourly (audit: 1000 tags ≈ 24 000 events/day ≈ 7 MB). Below it the download is cheaper than what the `#y` filter
reveals; above it, on a phone, it is not. Changing relays resets `syncedTo` (a new relay may hold older reports).

## 5. Resync past the window

`findBeyondWindow` (`sync.js`) searches counters from the end of the normal window (`lastN + 1 + 2048`, or
`floor + 4096` for a tag never heard) up to `lastN + 1 + 65536`, in blocks of 2048, yielding between blocks. A hit
needs the report to open (MAC), since a 4-byte tag collides easily over 130 000 counters. After a hit the window is
rebuilt there and the batch is matched again.

- `all` mode: runs only for a tag that is SILENT or WAITING after matching, on events not matched by anything and
  newer than what it already checked this session. Cost ≈ 131 000 HMACs (~0.5 s desktop) per stale tag.
- `y` mode: once per session, for a tag with nothing new in this sync and silent for 12 h, query block by block
  (~17 REQs per block) and stop at the first hit.

Also fixed: `ReportWindow` for a share from epoch e with no report yet covered counters 0…4096 instead of
`64e … 64e + 4096`, so shares from a later epoch never found their first report.

## 6. GPX and CSV export

Under the timeline: "Save as GPX" (GPS fixes as one track, time = fix time, `lt:` extensions for n, battery,
accuracy, mode) and "Save as CSV" (every report the app holds: n, received, fix time, lat, lon, accuracy, battery,
low flag, mode, quiet, quiet reason, ack, repeaters heard; cells starting with `= + - @` quoted). Built in the
browser, saved with a download link. Limited to what the app holds (up to 1000 reports in memory, 400 kept across
reloads).

## 7. About

The privacy paragraph names `purplepag.es` (used only for the welcome message's inbox lookup).

## Not done / open questions

- The tag's kind 0 still says `name: LibreTag` with the same picture on every tag key (audit P1 suggests no name or
  no kind 0). Combined with NIP-04 fallback DMs it still marks owners; with NIP-17 only the kind 0 remains. Needs a
  decision shared with Android.
- NIP-17 says not to send at all without a 10050 list; we send NIP-04 then (transition, same as Android). When to
  drop the fallback?
- PoW cost on low-end phones for 24–26 bit entries is estimated, not measured on a device.
- The `all` mode's live subscription receives every report on the network; fine today, revisit with the threshold.
- The relay may cap `limit` below 50 per page; then pagination stops after the first page for that relay.

# Bridge market: who transmits a command, spam limits, discovery and paid downlink

Status, 2026-11-18: the quick fixes (A.6) and step B are implemented in both bridges and in the app, and the reference Python bridge runs them. Step C (paid downlink, reviews) is deferred. Sections A and B below describe app 0.9.1 and the bridges as they were before that work, and the reasoning behind it. The wire format is in PROTOCOL.md 4.1, which is the reference. Kinds `4245` and `38244` are not in the NIP list as of 2026-11-18.

## What was built (2026-11-18)

| Item | Done | Where it differs from the proposal below |
|---|---|---|
| Q1 first-seen entry wins | Python, Android | |
| Q2 stable bridge key, claims from known keys only | Python, Android, app | "Known" means a configured peer, or an announced bridge with a hearing proof. Timed claims count only from peers: a timed entry has no secret to prove hearing with, and an ignored claim costs at most a second identical packet. |
| Q3 hearing proof on untimed claims | Python, Android | Report publish delay is now 2 to 5 s, above the 1.5 s claim wait. |
| Q4 larger mailbox, relay policy | Python, Android; `host/deploy/strfry-mailbox-policy.py` | The cap is 200,000 tags. The owner's relay runs a strfry write policy with the same rate limit (burst 120, then 60 a minute, anchors and ACK entries in their own bucket) since 2026-11-26. |
| Q5 first hearing only | Python, Android | |
| Q6 airtime share for untimed | Python, Android | Timed entries may use at most half of the `#libretag` budget. |
| Q7 region on every bridge | Python (checked against the node's catalogue at start), example config, test bridge | |
| B.1 identity, announcements, receipts | Python, Android | The announcement (kind 38244) has no price, mint or geohash tags; those belong to C. Receipts are off by default and on at the test bridge. |
| B.2 `p` and `r` targeting, filtered subscriptions | Python, Android, app | The app takes `r` from the announcements of the bridges that heard the tag, not from a country table (the app has none). Lost commands stay open. Bridges subscribe by `#p` and `#r` only when they serve no open entries, which they still do while app 0.9.1 is around. |
| B.3 binding `route` | Python, Android | Entries carry `["v", "2"]` so a bridge can tell them from 0.9.1 entries, which keep the old behaviour. Owner decision: the app asks for `flood` on every command. |
| B.4 work by cost | Python, Android, app | Bits are `max(route, lead) + open`, not `route + lead`, capped at 28: twelve timed entries at 30 bits would take a phone tens of minutes. Open floods are capped at 6 an hour. Format 1 entries keep the old table until `accept_legacy` is turned off, so the higher price of an open flood only bites after that. |
| C.5 item 2, the owner's own node | app (checked) | That path sends `Pc` over MeshCore Open's Local API without any Nostr event, so it mines nothing. The own-bridge pass (C.5 item 3) was not built. |

Not done: the PWA still publishes format 1 entries. The bridge list screen (D, step 8) does not exist.

Known weak spot: a fake bridge can publish receipts for every report it sees on the relays and so get named in `p`. It cannot win an untimed claim without the hearing proof, and its timed claims count nowhere, so it cannot stop honest bridges that are also named. It can still push honest bridges out of the app's top three. The app's own delivery record (C.4) is the fix.

Not verified: the configuration of the owner's live bridge (it is not in the repo), GPU hash rates (general knowledge, not measured), and the exact text of the Cashu NUTs (read them again before building). The attacks in A.5 were read from the code. None was run.

## Short answers

- **Do we do PoW?** Yes. Every kind 4244 entry carries NIP-13 work. 16 bits for untimed entries, 16 to 26 bits for timed entries by how far ahead they are. Both bridges enforce it.
- **Is the PoW worth anything?** Against a phone, a little. Against one GPU, nothing: the gap is three to four orders of magnitude. What limits spam today is the airtime budget and the "heard the report" rule, not the work.
- **Does a command go to all bridges?** Every bridge in the world receives and stores it. Only a bridge whose radio heard the tag's report transmits it. A bridge in another city on another mesh sends nothing.
- **Is a command bound to a region or a bridge?** No. The event names neither. The `route` tag is ignored. The flood is as wide as the transmitting bridge's radio makes it, which usually means the whole connected mesh.
- **Worst gaps:** anyone can block a command without a radio (A.5, items 1 to 3), and one GPU minute empties every mailbox on every bridge (item 4).

## A. Current state

### A.1 What the app computes

- The table is in `android/app/src/main/kotlin/app/libretag/nostr/Pow.kt:16-29`: untimed 16 bits; timed by lead `at - created_at`: up to 5 min 16, up to 30 min 20, up to 2 h 22, up to 8 h 24, more 26.
- Mining: `Pow.kt:61-99`, midstate SHA-256 on all cores. Called from `LibreTagApp.kt:1147-1183`, bits chosen at line 1164, one entry at a time, untimed first.
- One command becomes many events. Untimed entries for the next 4 reports (`MAILBOX_AHEAD`, `LibreTagApp.kt:1406`), two `y` tags each (`LibreTagApp.kt:1082-1083`). Timed entries for up to 12 checks (`MAILBOX_CHECKS`, line 1408), each with `at` and `via` (`LibreTagApp.kt:1098-1120`). Each event has its own random key and the command's group tag `g`.
- Lifetime: Beep 1 h, Lost and Interval 48 h, the rest 12 h (`LibreTagApp.kt:79`).
- Cost on a phone: under a second at 16 bits, tens of seconds at 26 bits (`docs/design/app-0.8.md:26`, `PROTOCOL.md:270`).
- The table is hard-coded in the app. A bridge operator can raise `pow_untimed_bits` in the config. The app cannot learn that, and its entries are then dropped without a word.
- The command also goes out from the owner's own radio through MeshCore Open, with no Nostr and no PoW (`LibreTagApp.kt:900-910`), and over Bluetooth advertising when the phone is near (PROTOCOL.md 3.1, line 212).

### A.2 What a bridge enforces

On arrival of a 4244 event (`host/src/nostrmesh/lt2svc.py:299-364`):

| Check | Where | Default |
|---|---|---|
| 29 B content, 1 to 16 `y` tags, valid `g` | `lt2svc.py:301-316` | |
| `created_at` at most 60 s ahead | `lt2svc.py:320` | `future_max_s` |
| NIP-13: difficulty and committed target | `lt2svc.py:340-344`, `pow.py:31-58` | `config.py:101-103` |
| `at` within 25 h after report `via`, if heard | `lt2svc.py:345`, `366-372` | `timed_max_after_report_s` |
| signature | `lt2svc.py:348` | |
| 12 unsent timed entries per `via`, most work stays | `ledger.py:203-208` | `timed_per_via_max` |
| one relay fills at most 34 % of the mailbox | `ledger.py:209-210` | `mailbox_relay_share` |
| 10,000 tags in all, least work evicted first | `ledger.py:211`, `216-228` | `mailbox_cap` |

Before transmitting:

| Check | Where | Default |
|---|---|---|
| untimed: the bridge's radio hears a report with that `x`, live, at most 30 s old, and knew the entry before | `lt2svc.py:495-509`, `ledger.py:230-235` | `report_max_age_s` |
| timed: the bridge heard report `via` in the last 48 h | `lt2svc.py:604-607` | `report_path_ttl_s` |
| one downlink per `y`, ever | `ledger.py:320-325` | |
| an identical `Pc` once, also when heard on air | `lt2svc.py:177-183`, `557-560` | |
| timed: one per `via` per 10 min, 20 per hour | `lt2svc.py:536-546` | `timed_via_gap_s`, `timed_per_hour` |
| bridges agree who sends (kind 24244, 1.5 s) | `lt2svc.py:511-524` | `claim_wait_s` |
| `#libretag` airtime 0.1 % of an hour = 3.6 s | `bridge.py:121-122`, `346-355`, `policy.py:31-52` | `airtime_pct` |

A command packet is about 0.2 to 0.3 s on air at SF7 / 62.5 kHz (`docs/research/region-research.md`, 1.3). So the airtime budget allows roughly a dozen command transmissions per hour per bridge. This is the hard ceiling for everything below.

On the receive side: 30 new `#libretag` packets per minute, 10 per first repeater (`lt2svc.py:199-210`).

The Android bridge has the same rules (`android-bridge/.../bridge/Bridge.kt:76-95`, `818`).

### A.3 Region, bridge, scope

- The 4244 event has no region and no bridge. Tags are `y`, `route`, `expiration`, `g`, `nonce`, and for timed entries `at` and `via` (`PROTOCOL.md:253-255`).
- `route` is parsed (`lt2svc.py:310-312`) and then ignored. The plan is: zero-hop if the report was heard directly and the adapter can do it, else flood (`lt2svc.py:489-493`). The app still sets `flood` only for Lost (`LibreTagApp.kt:1083`), with no effect.
- `via` is not a bridge. It is the public tag of report `n`. It binds a timed entry to "bridges that heard that report".
- The app cannot tell which bridge heard a report. Kind 4243 is signed with a fresh random key each time (`lt2svc.py:258`, `PROTOCOL.md:251`) and carries no hop count.
- The flood scope comes from the transmitting radio, not from the command:
  - Local API adapter (meshcored, MeshCore Open): the `[radio] region` setting if present (`config.py:24`, `localapi.py:358-365`), else the node's default scope. `host/bridge.example.toml` has no `region` line, so a bridge set up from the example uses whatever the node defaults to.
  - KISS adapter: a plain unscoped flood (`radio.py:413-421`).
  - Companion serial: flood only, the node's default scope (`radio.py:129`).
- The tag itself reports with region `sk` in Slovakia and unscoped elsewhere (`README.md:74`, SPEC 6.4).
- The owner's own node: the app's `meshRegion` setting, empty means MeshCore Open's own scope (`android/.../mesh/LocalApi.kt`, `MeshLink.sendChannelData`). MeshCore Open accepts a region per message (`meshcore-open/documentation/LOCAL_API.md:133-147`), and meshcored does the same (`meshcored/DESIGN.md:124`).
- A MeshCore flood has no radius. A scoped flood passes every repeater that carries that region. An unscoped flood passes every repeater that does not forbid it, up to `flood.max.unscoped` hops, default 64 (`docs/research/region-research.md:31-46`). In practice one flooded command crosses the whole connected mesh. The Slovak and Hungarian mesh had about 980 nodes on 2026-11-16 (`docs/research/flood-latency.md:52`).

### A.4 N bridges in different cities, today

- **Different meshes** (another country, another preset, no radio path): only the bridges that hear the tag transmit. The others store the entry and never send it. A command does not spread to other networks. The tag sends a second copy to the neighbouring network within 3 km of a border; that copy has its own `x`, so one bridge in each network may answer. That is intended.
- **Same mesh, same relays:** every bridge hears the flooded report. They publish claims. Fewest hops wins, one bridge transmits. If it heard the report over one or more hops, it floods the whole mesh.
- **Same mesh, different relay sets, or a claim slower than 1.5 s:** each bridge transmits. The wrapped packet is deterministic (`host/src/nostrmesh/grp.py:17-18`, ECB), and repeaters deduplicate by type and payload (`docs/research/region-research.md:46`). So two floods of the same `Pc` merge into about one. The cost is the extra originating transmissions. If the two bridges use different scopes, a repeater that saw the first copy and did not forward it will also drop the second (SPEC 6.4, `SPEC.md:390-391`).
- **Storage is global.** Every bridge subscribes to all 4244 events with no filter (`lt2svc.py:150`) and keeps up to 10,000 tags. A mailbox in one country fills with commands for tags on another continent.

### A.5 How well it is done

What holds:

- A stranger cannot make a bridge transmit at will. A transmission needs a real report heard on that bridge's radio.
- A stranger cannot command a tag. `Pc` is sealed for `(n, k)` with the command chain. A replayed `Pc` is useless and goes on air once.
- The airtime budget is separate from other channels and survives a restart. It caps the damage of every attack at about a dozen floods per hour per bridge.

Gaps, most serious first:

1. **Squatting on `y` (untimed).** The owner's `y` tags are public once published. The bridge picks the newest entry for a heard `x` (`ledger.py:234`, `lt2svc.py:504`) and then marks `y` as answered for good (`lt2svc.py:506`). An attacker copies the `y` tags into an own event with a later `created_at` (60 s ahead is allowed), 29 junk bytes and 16 bits of work. The bridge transmits the junk and never the owner's command. No radio needed. Cost: milliseconds.
2. **Outbidding timed entries.** `via` is public as soon as the report is on the relays. Twelve junk entries with 30 bits evict the owner's twelve (`ledger.py:203-208`). Or one junk entry timed just before the owner's uses the 10 min gap per `via` (`lt2svc.py:541-543`). A GPU mines 30 bits in a fraction of a second. A phone needs minutes.
3. **Claim jamming.** A claim is signed by any random key, needs no work and no proof of hearing (`lt2svc.py:398-412`). Lower `SHA-256(pubkey || y)` wins a tie, and the attacker can grind keys. A claim with `hops = 0` for every pending `y`, repeated every 25 s, makes every honest bridge lose (`lt2svc.py:523-524`). The loser has already marked `y` answered, so it never retries. The app shows the command as sent on any claim (`LibreTagApp.kt:1043-1056`).
4. **Global mailbox eviction.** The cap is 10,000 tags and the least work goes first (`ledger.py:221-223`). About 640 events with 16 `y` tags each and 30 bits of work, spread over three relays, fill it. That is roughly one GPU minute. Every 16 bit entry from a phone is then evicted on arrival, on every bridge, for up to 48 h.
5. **Junk floods without a radio.** A timed entry needs only a `via` that the bridge heard, from any tag. So anyone can make every bridge that hears any LibreTag flood 29 chosen bytes, up to the timed caps and the airtime budget. This is also an anonymous broadcast channel on someone else's radio. And it spends the budget, so real commands get `tx_airtime_exhausted`.
6. **A late copy of a report triggers a command.** `_mailbox_check` runs for every copy, not only the first (`lt2svc.py:219-222`), and compares the entry's arrival with the copy's time. An attacker who sees the 4243 on a relay can publish an entry before a later flood copy or the tag's retransmission arrives. Plausible, not tested. Adapters that pass only the first copy are not affected.
7. **PoW favours the attacker.** A phone does a few million hashes per second. A single GPU does on the order of billions. To cost a GPU one second, an entry would need about 33 bits, which is around an hour on a phone. Work cannot price a flood.
8. **A malicious bridge** cannot read or forge commands. It can win every claim and send nothing, publish false done claims (PROTOCOL.md 4, already documented), and learn that someone commands a tag it hears. Nobody can tell, because bridges have no identity.
9. **No cost difference.** A zero-hop transmission is one packet. A flood is one packet per repeater, hundreds in a large mesh. Both cost the sender the same 16 bits.

### A.6 Quick fixes, no new protocol

| # | Fix | Where | Effort |
|---|---|---|---|
| Q1 | For a heard `x`, send the entry seen first, not the newest. A replaced command is already deleted by the app with NIP-09, so the old one disappears. Fixes gap 1 except for a narrow relay race. | `ledger.py:234`, Android `Mailbox`, vectors | 0.5 d |
| Q2 | Count a claim only from a known key. Bridges get a stable key (the config already has `auth_key` for NIP-42, `bridge.example.toml:13`) and a list of peer keys. A claim from an unknown key is ignored. Worst case is a double transmission, which repeaters merge. The app shows "sent" only for a known key. | `lt2svc.py:398-412`, `511-524`, `LibreTagApp.kt:1043` | 1 to 2 d |
| Q3 | For untimed claims, also require `SHA-256(P || claim pubkey)`. `P` is on the relays only after the 0 to 5 s publish delay. Raise the delay floor above the claim wait and an outsider cannot claim in time. | `lt2svc.py:244`, `515` | 0.5 d |
| Q4 | Raise `mailbox_cap` to several hundred thousand tags (SQLite does not mind). Let the owner's relay demand committed NIP-13 and a rate limit on kind 4244. The eviction attack then has to get past relay limits. | config, relay write policy | 0.5 d |
| Q5 | Only the first hearing of `x` triggers an untimed command. | `lt2svc.py:219-222` | 0.5 d |
| Q6 | Split the `#libretag` airtime: keep a share for untimed entries, so timed junk cannot spend all of it. | `bridge.py:121`, `lt2svc.py:536` | 0.5 d |
| Q7 | Set and document the region on every bridge. Warn at start when the adapter floods unscoped. | `config.py:24`, example config | 0.5 d |

Gap 2 has no cheap fix. A bridge has no key to tell the owner from a stranger. Ordering by work loses to a GPU, ordering by arrival loses to whoever watches the relays. Payment fixes it (C.3): bids are then in sats, which cost a phone and a GPU the same.

## B. Near term: bind a command to a place and to a cost

### B.1 Bridge identity and "heard" receipts

- Each bridge has one stable Nostr key. It signs claims and receipts with it. Reports (kind 4243) keep their random key.
- When a bridge hears a report, it also publishes a small receipt: kind `4245` (placeholder), tags `["y", x]`, `["hops", "0" | "1-3" | "4+"]`, `["expiration", now + 48 h]`, signed by the bridge key.
- The app asks `{"kinds":[4245], "#y":[x of my tag's last reports]}`. It learns which bridges hear the tag and how near they are. It keeps this per tag: "bridge B heard 9 of the last 10 reports, mostly directly".
- Privacy: an outsider learns that some unknown tag was near bridge B at that time. `x` changes with every report and links to nothing. Public packet dashboards already show the same (`docs/research/flood-latency.md:78-80`). The hop count is coarse on purpose. Receipts are opt-in for the operator.
- A fake bridge can copy `x` from the relays and publish receipts for everything. Receipts alone are not proof. They become proof together with deliveries the app can check (C.4).

### B.2 Target tags on the entry

- `["p", <bridge pubkey>]`, one or more: only these bridges may transmit. The first is primary. A later one sends only if it sees no claim from an earlier one within the claim wait.
- `["r", <network>]`: the mesh the tag is in, for example `sk` or `eu-narrow`. The app derives it from the tag's last position with the same table the firmware uses. A bridge serves the networks in its config.
- Bridges subscribe with `#p` (own key) and `#r` (own networks) instead of all 4244. A mailbox then holds local entries only. This removes the global storage problem (A.4) and shrinks gap 4 to one network.
- An entry with no `p` is open: any bridge that hears the tag may send. Open entries stay possible for a lost tag that may be anywhere. They cost more work (B.4) or go last.
- The app's choice per command: bridges that heard the last report, best first. If the tag has moved, the untimed entries for later reports name no bridge that hears it. So the app adds one open entry as a fallback for Lost.
- With Q2 and `p` tags, the election is only needed between open senders.

### B.3 Make `route` mean something again

Today everything not heard directly floods. Proposal: the entry states the most it asks for, and pays for that.

| `route` | The bridge may | Packets in the mesh |
|---|---|---|
| `near` | zero-hop only | 1 |
| `direct` | zero-hop, or along the reversed path of a report heard at most 3 hops away and at most 30 s ago | up to 4 |
| `flood` | all of the above, else a flood in the bridge's scope | one per repeater in scope |

- This partly takes back 0.7.3, which made every command flood because direct delivery beyond 3 hops failed and paths went stale. The difference: the app now decides, and a flood has a price. Timed entries have an hours-old path, so for them only `near` and `flood` make sense.
- Which commands ask for `flood`: Beep, Lost, Track now. Settings changes ask for `near` and otherwise wait for Bluetooth or the owner's own radio. This matches the plan to move settings to Bluetooth.
- Direct sending needs an adapter with `tx_path_data` and `rx_path` (Local API). Others fall back to `near` or `flood`.
- Smaller scopes: the Local API catalogue has city-level regions (`LOCAL_API.md:256-265`). A bridge could flood into a sub-region when the tag's position falls inside one. It only works where repeaters carry that region, so it is an operator setting, not a default (`docs/research/region-research.md`, section 2).

### B.4 Work scaled by cost

| Entry | Bits today | Proposed |
|---|---|---|
| `near`, untimed | 16 | 16 |
| `direct` | 16 | 18 |
| `flood`, targeted with `p` | 16 | 24 |
| `flood`, open | 16 | 28 |
| timed | 16 to 26 | the above plus the existing lead steps, capped at 30 |

- 28 bits is about a minute on a phone. It is acceptable for a rare Lost command and useless against a GPU. So work alone must never buy much: the free flood tier gets a small fixed share of the airtime (for example 4 floods per hour) and the lowest priority.
- Send order when the budget is short: the owner's own bridge (C.5), then paid, then free by work.
- The required bits move into the bridge announcement (C.1), so the app stops guessing.

## C. Future: discovery, reputation, paid downlink (deferred)

Deferred by the owner on 2026-11-18. Nothing in this section is built. To pick it up again:

- a Cashu wallet in the app (mint, hold, P2PK send, refund after locktime), item 9 in D; check for a Kotlin binding first;
- the price, mint and `cashu` tags added to the kind 38244 announcement that B.1 already publishes;
- the payment event (kind 4246 placeholder, check the NIP list again) and the paid order in both bridges;
- the app's own delivery record (C.4), which is also what closes the fake-receipt gap above;
- a decision on reviews and their privacy cost (C.6) before any code.

Uplink stays free for good. Mesh to Nostr costs the mesh nothing extra and is the reason people run bridges. Only Nostr to mesh has a price.

### C.1 Bridge announcement

One addressable event per bridge and network. Kind `38244` (placeholder), author = the bridge key, modelled on NIP-87 mint announcements (kind 38172).

```
kind 38244
tags:
  ["d", "<network>"]                      e.g. "sk"
  ["k", "4244"]                           service: LibreTag mailbox (other services later)
  ["preset", "869618000", "62500", "7", "5"]
  ["region", "sk"]                        MeshCore scopes it floods into, may repeat
  ["g", "u2x"]                            coarse geohash, 3 or 4 characters, may repeat
  ["relay", "wss://..."]                  where it reads entries, may repeat
  ["pow", "near", "16"], ["pow", "direct", "18"], ["pow", "flood", "24"]
  ["price", "near", "64", "sat"], ["price", "direct", "256", "sat"], ["price", "flood", "2048", "sat"]
  ["mint", "https://..."]                 accepted mints, may repeat
  ["cashu", "<P2PK pubkey>"]              a key separate from the Nostr key
  ["limit", "flood", "12", "3600"]        at most this many per window, paid or not
  ["p", "<operator npub>"]                optional
  ["expiration", "<now + 7 d>"]
content: JSON {name, about, version, adapter, zero_hop, stats: {since, reports_24h, sent_24h, airtime_pct_24h}}
```

- Republished daily. The expiration makes a dead bridge disappear by itself.
- Location is never exact. A 4 character geohash is the finest allowed. The operator may publish only the network name.
- Everything in it is the bridge's own claim. The app checks what it can: receipts for its own tags (B.1) and its own deliveries (C.4).
- Prices in powers of two keep a payment to one Cashu proof and the event small.

### C.2 Reviews and reputation

- Review: NIP-87 style, kind `38000` with `["k", "38244"]` and an `a` tag to the announcement. Content starts with `[4/5]`, as mint reviews do, followed by counts the app fills in: `delivered=7 paid=8 refunded=1`.
- Reuse the mint-discovery rules, ported to Kotlin (the PWA can load the JS as it is):
  - the latest review per author only;
  - a Bayesian average with two imaginary 3 star reviews;
  - a bridge counts as known only with an announcement or reviews from two authors, and the list is capped;
  - the warning with a checkbox before the first choice, reworded: reviews can be bought, pay small amounts.
- Added, because mint-discovery treats every author the same: a web of trust. Reviews by people the owner follows weigh most, their follows less, strangers least. The app only needs the owner's npub and kind 3 lists for that.
- The strongest signal needs no reviewer at all. It is the app's own record: did this bridge hear my tag, and did my tag acknowledge what this bridge sent. The list sorts by: hears my tag now, own delivery rate, weighted reviews, price.
- Order in the app: Tag settings, "Remote commands", a list of bridges that hear this tag, each with price, delivery rate, review score, and a detail view like a Cashu mint detail view. The owner picks one or leaves it on automatic.

### C.3 Paid downlink with Cashu

One payment covers one command (one group `g`), not each of its 16 or so entries.

**Payment event.** Kind `4246` (placeholder), signed by a fresh key:

```
tags: ["p", <bridge>], ["g", <group>], ["expiration", <as the entries>], ["nonce", ..., "16"]
content: NIP-44 to the bridge key of
  {"g": <group>, "route": "flood", "mint": "https://...", "unit": "sat",
   "proofs": [...], "refund": "<fresh pubkey>"}
```

- The proofs are locked with NUT-11 P2PK to the bridge's Cashu key, with a `locktime` a few hours after the last entry expires and a `refund` key that only the app holds. Until the locktime only the bridge can spend them. After it, only the app can.
- Why encrypt when the proofs are locked anyway: an outsider could otherwise attach the same proofs to an own junk command and have the owner's money pay for it. Encrypted, only the bridge sees them, and the inner `g` must equal the outer one. Encryption also hides the amount and the mint.
- The entries of the group carry `["p", bridge]` and 16 bits of work as before.

**Bridge side.**

1. Decrypt. Check the lock, the amount against its price for `route`, the mint against its list.
2. Verify the mint's signature offline with the DLEQ proof (NUT-12). A forged token costs no network call.
3. Ask the mint for the state once (NUT-07). Remember the proofs, so one token cannot back two groups.
4. Mark the group paid. Paid entries are not evicted by work, and they order before free ones. This closes gap 2: to push the owner out, an attacker must pay more each time.
5. Transmit when the rule says so (report heard, or `at`). Redeem the proofs right after transmitting, not before.
6. Never transmitted, because the tag was not heard or the command was cancelled: do not redeem. The app takes the money back after the locktime.

This is pay on transmission. The bridge is paid for airtime, which is its cost, not for the tag's answer, which it cannot guarantee. Naming two bridges costs one payment in the normal case: only the one that transmits redeems.

**The airtime cap stays.** A paid bridge still sends at most its published limit. Money sets the order, not the volume. Otherwise paying turns into a licence to flood, and the payment goes to the bridge while the repeaters carry the load. Repeater owners are not paid by this design. The price is there to keep volume low and to fund the bridge. Operators who oversell will meet `denyf` on the repeaters around them.

**Other transports, and why not.**

- NUT-24 (HTTP 402 with an `X-Cashu` header): the bridge would need a public HTTP address. Many will sit behind NAT at home. It also shows the payer's IP to the bridge. Relays are already there.
- NUT-18 payment requests: the announcement can carry a `creqA` per price class with a Nostr transport, and the payment content above can be the NUT-18 payload (`id` = group, `mint`, `unit`, `proofs`). That lets a stock wallet library build it.
- NIP-61 nutzaps: the same idea of P2PK proofs in an event, but public and tied to a user's profile. Useful for one thing: a voluntary "tip this bridge" button, since uplink earns nothing.

**Price.** About 3000 sats for a flood through a foreign bridge is a sensible start. As powers of two: `near` 64, `direct` 256, `flood` 2048 or 4096. Each operator sets it. The free tier by work (B.4) stays as the alternative, with its small share.

### C.4 Did it arrive: receipts and refunds

- The tag confirms a command in its next report (`CMD_ACK`). The report is authenticated by the tag's keys, so the app knows for certain. Nobody else can check it without the view key, and the app must never publish that key.
- So a delivery receipt is the app's signed statement, not a public proof. It feeds the app's own record and, in summary, a review.
- The app can see the facts that matter without trusting the bridge:
  - proofs spent (NUT-07) and an ACK: delivered;
  - proofs spent, a claim seen, no ACK: sent but not received, counted as a miss;
  - proofs spent, no claim and no ACK: the bridge took the money. Automatic 1 star review with the count;
  - proofs unspent after the locktime: the app takes the refund. Neutral.
- A bridge that takes money and sends nothing gains one payment per victim and then sinks in every list. Small prices and pay on transmission keep that loss small. There is no escrow, and none is worth building for these amounts.

### C.5 The free paths

In order of preference, all without a foreign bridge:

1. Bluetooth, when the phone is near the tag (PROTOCOL.md 3.1). Settings should go this way.
2. The owner's own node through MeshCore Open or meshcored. The app already does this (`LibreTagApp.kt:900-910`) with a region per message. It costs nothing and asks nobody.
3. The owner's own bridge, anywhere. A pass instead of a payment: the bridge config holds a secret, the app holds the bridge key and the secret, and the payment event carries `{"g": ..., "pass": HMAC(secret, g)}`. Same code path as a paid group, top priority, no sats. This can ship before any Cashu code.
4. A foreign bridge: paid, or free by work with the small share.

### C.6 Privacy

- Ecash is blind. The mint cannot link the tokens it issued to the ones the bridge redeems. The bridge learns the amount, the mint and the time, and nothing about who paid.
- Fresh keys everywhere: the event key (as today), the refund key, the NIP-44 sender key. Nothing ties two commands of one owner together except what is already public (`g`, the `y` tags).
- Paying does not link the owner to the tag more than publishing the command already does. The weak points are elsewhere: the phone's IP at the relays and at the mint (use Tor or a proxy if it matters), and reviews.
- Reviews are the real risk. A review signed by the owner's npub right after a command says: this person has a tag near bridge B, and these `y` tags are theirs. So: reviews are off by default, sent as a weekly summary with no command reference, at a random time. An owner who wants web-of-trust weight signs with their npub through a signer app. Otherwise the app keeps its record to itself.
- A targeted entry tells everyone which bridge the tag is near. The open entry does not. For a tag that must stay unlocated, use open entries and pay in work.
- Receipts (B.1) show where anonymous tags are heard. Operators can leave them off and still serve open entries.

### C.7 Abuse that remains

- Fake bridges with fake reviews: limited by the app's own evidence (receipts for my tag, my deliveries) and by the web of trust. A new bridge with no history gets small payments only.
- A bridge that redeems and does not send: C.4.
- A mint that fails: the bridge chooses its mints and carries that risk for a short time, since it redeems on transmission. The app holds only pocket money.
- Payment event spam: 16 bits of work, an offline DLEQ check first, one mint call per unseen proof.
- Paying to jam: an attacker can still buy junk floods, at full price, inside the bridge's published limit. That is the point of the price.

## D. Recommendation

**Now (about a week, no new event kinds).**

1. Q1, Q5: first-seen entry wins, first hearing triggers. Closes the cheapest way to block a command. 1 d for both bridges and vectors.
2. Q2, Q3: a stable bridge key, claims only from known keys, a hearing proof on untimed claims, the app shows "sent" only for a known key. 2 d.
3. Q4, Q6, Q7: larger mailbox, relay policy for kind 4244, split airtime, region set and documented on every bridge. 1.5 d.
4. Decide B.3 (should ordinary commands still flood?) before touching the app. It is a product decision, not code.

**Next (2 to 3 weeks).**

5. Announcement (C.1) and receipts (B.1) from both bridges. 3 d.
6. `p` and `r` tags, filtered subscriptions, `route` with meaning, work by cost (B.2 to B.4). Bridges 3 d, app and PWA 3 d, vectors 1 d.
7. Own-bridge pass (C.5 item 3). 1 d. This already gives "pick which bridge" for the owner's own hardware.
8. A read-only bridge list in the app with the app's own record. 3 d plus copy and translations.

**Later (4 to 8 weeks, mostly the wallet).**

9. A Cashu wallet in the app: mint, hold, P2PK send, refund after locktime. The largest item. Check whether an existing Kotlin binding fits before writing one. 2 to 4 weeks.
10. Paid groups on both bridges (C.3), including redeem on transmission and the paid order. 1 week.
11. Reviews with web of trust and the automatic record (C.2, C.4). 1 week.
12. Write the kinds up as a short NIP-style note once two bridges that are not the owner's run it.

Steps 1 to 3 are worth doing even if nothing else happens: today one person with a laptop can stop every command for every user, and neither the work nor a payment changes that until the election and the mailbox order are fixed.

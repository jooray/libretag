# Nostr over MeshCore: general framework and MeshCore<->Nostr bridge (proposal)

Status: 2026-11-16. Proposal and v1 implementation in `host/src/nostrmesh/` (section 8), LibreTag on LT2 (section 8.9). Not yet verified on the radio. Applies to LibreTag, Roadbeacon/Roadstr and autonomous devices. Builds on `SPEC.md` 6.1–6.4, `PROTOCOL.md`, `roadbeacon/nips/road-events.md` (Compact Binary Representation) and `roadbeacon/docs/meshcore.md`.

Frame sizes are confirmed by test vectors (`docs/vectors/nostr-frames-v1.json`): note 63 B = 165, `dm4.full` 15 B = 165, `dm4.ref` 63 B = 157. Estimates for NIP-44 and NIP-17 remain manual.

## 0. Summary

- One `data_type` ("Nostr Event Frame") carries signed Nostr events in a frame whose **first byte selects the profile**. The profile can reconstruct tags and content exactly from compact fields. A `generic` profile exists too. The rule from Roadbeacon applies everywhere: *the encoder rejects everything that does not reconstruct bit for bit*.
- The existing `0x0150` / `#roadstr` stays unchanged. The MeshCore<->Nostr bridge treats it as the profiles `road.report` and `road.vote`, just without the profile byte.
- One frame in GRP_DATA carries 163 B (companion/BLE) or 165 B (own radio, KISS). The fixed overhead of a signed event (pubkey 32 + sig 64 + created_at 4) is 100 B. Little is left for content.
- **A NIP-04 DM fits only with a trick**: the sender's and recipient's pubkeys are replaced in the frame by a 4 B reference into the bridge registry. Then the message is ≤ 63 B in one frame. Without the trick it is ≤ 15 B and only on a 165 B channel.
- **NIP-44 never fits into one frame** (nonce 32 + MAC 32). It fits into 2 fragments. Full NIP-17 (gift wrap) is ~1.1 kB, so 7 fragments: unrealistic.
- Allow fragmentation only for DMs, at most 4 fragments, at the application level inside GRP_DATA.
- The bridge is a Python daemon. The mesh→relays direction is on by default (verifies the signature, dedupes by id, publishes). The relays→mesh direction is **off by default**, with a key allowlist, a geofence and strict limits.

## 1. General frame

### 1.1 Frame and data_type

We propose a new `data_type`:

| | |
|---|---|
| Name | Nostr Event Frame |
| Development | `0xFF4E` (dev range FF00–FFFF, LibreTag has `0xFF4C` for encrypted reports) |
| Production | request an allocation next to `0x0150` (e.g. `0x0151`), after validation on a real radio |
| Channel | `#nostr` (hashtag, key `SHA-256("#nostr")[0:16]`) |
| Content | exactly one frame (or fragment, section 3) |

First byte:

```
bits 7-6  frame version (0)
bits 5-0  profile (0–63)
```

The byte is not covered by the signature. Whoever changes it gets a reconstructed event with a different id and the signature fails, so the frame is dropped. This is not an attack, just corruption. It is the same as with flags in Roadbeacon.

Numbers in frames are **big-endian** (as in Roadbeacon frames). The inside of GRP_DATA (`data_type` u16 LE, `data_len` u8) is defined by MeshCore.

### 1.2 Profile registry

| ID | Name | Kind | Note |
|---|---|---|---|
| 0x00 | `generic` | u16 in the frame | fallback, 1.4 |
| 0x01 | `note` | 1 | short text note |
| 0x02 | `road.report` | 1315 | same body as Roadbeacon (after the profile byte) |
| 0x03 | `road.vote` | 1316 | same body as Roadbeacon |
| 0x04 | reserved (was `libretag.public`, LT1) | | removed with LT2; LibreTag reports go as kind 4243 (8.9) |
| 0x10 | `dm4.full` | 4 | NIP-04, full pubkeys |
| 0x11 | `dm4.ref` | 4 | NIP-04, pubkeys as a 4 B reference into the registry |
| 0x12 | `dm4x.full` | 4 | like `dm4.full`, the event also expires after 14 days |
| 0x13 | `dm4x.ref` | 4 | like `dm4.ref`, the event also expires after 14 days |
| 0x3E | `frag` | | fragment, section 3 |
| 0x3F | reserved | | extension (multi-byte profile ID) |

The road profiles in the new `data_type` exist only for completeness of the registry. Road events are still sent through `0x0150` on `#roadstr` (section 4). The new channel must not duplicate them.

### 1.3 Common rules (apply to every profile)

1. **Isomorphism.** `decode(frame)` produces a complete event (`id`, `pubkey`, `created_at`, `kind`, `tags`, `content`, `sig`). The `id` is computed per NIP-01 from the serialization `[0,pubkey,created_at,kind,tags,content]` and the signature is **always verified** (BIP-340). A frame without a valid signature is not an event.
2. **The encoder rejects.** `event → frame → event'` holds with `event'.id == event.id`, the same `pubkey` and `sig`. We take the stable error codes from Roadbeacon (`NON_CANONICAL`, `BAD_LENGTH`, `SIGNATURE_INVALID`, `COMMENT_TOO_LONG`, …) and add `NON_CANONICAL_BASE64`, `BAD_IV`, `UNKNOWN_PROFILE`.
3. **Integers in text** (e.g. `lat`, `expiration`) are formatted as plain integers, without locale, as in Roadbeacon.
4. **Control characters.** NIP-01 escapes only `\n \" \\ \r \t \b \f`. The `road.*` profiles reject all control characters. The `note` and `generic` profiles allow only `\n`, `\t` (and `\"`, `\\`); they reject everything else in U+0000–U+001F and U+007F. Invalid UTF-8 is always rejected.
5. **Length is derived from the frame** where possible (the last field is fixed, so `ct` in a DM has no length field).
6. **A signature with zero `aux_rand`** is required for profiles where the key is derived (LibreTag): the owner can compute the event in advance and the test vectors reproduce. For profiles signed once (DM), any `aux_rand` is enough, because all gateways receive the same frame with the finished `sig`. Determinism across several gateways comes from the **signature being in the frame**, not from a zero aux.

### 1.4 Profiles in detail

**`note` (0x01), kind 1 without tags**

```
0   1   profile
1   32  pubkey
33  4   created_at
37  1   M, content length in B
38  M   content (UTF-8)
38+M 64 sig
```

Frame length = 102 + M.

**`generic` (0x00)**

```
0   1   profile
1   2   kind
3   32  pubkey
35  4   created_at
39  1   ntags
..  ... tags
..  1   M
..  M   content (UTF-8)
..  64  sig
```

A tag = `nitems u8` + items. An item starts with a type byte:

| Type | Body | Rebuild |
|---|---|---|
| 0x00 | `len u8` + UTF-8 | string |
| 0x01 | 32 B | lowercase hex (64 characters) |
| 0x02 | u32 BE | decimal number without leading zeros |
| 0x04 | 1 B character | single-letter tag name (`t`, `p`, `e`, `g`, …) |

Size examples: `["t","ab"]` = 1 + 2 + (1+1+2) = 7 B. `["p",<hex>]` = 1 + 2 + 33 = 36 B. `["expiration","1790000000"]` = 1 + 12 + 5 = 18 B.

The encoder rejects: a tag with non-lowercase hex where type 0x01 would be used (the rebuild would give a different letter case), a number with a leading zero, tags with more than 255 items, an event with more than 15 tags (makes no sense at 165 B).

**`dm4.full` (0x10), kind 4, NIP-04**

```
0   1   profile
1   32  sender pubkey
33  4   created_at
37  32  recipient pubkey (p tag)
69  16  iv (raw)
85  L   ct, L = multiple of 16, L >= 16
85+L 64 sig
```

Rebuild: `tags = [["p", hex(recipient)]]`, `content = base64(ct) + "?iv=" + base64(iv)`, standard alphabet, with `=` padding, no line wrapping. Frame length = 149 + L.

**`dm4.ref` (0x11)**: like `dm4.full`, but `pubkey` and the recipient are 4 B each (`pk_ref = pubkey[0:4]`). The bridge turns them into full pubkeys from its registry (5.5). Frame length = 93 + L. An unknown `pk_ref` is dropped. On a collision the bridge tries all candidates and the signature decides.

**`dm4x.full` (0x12), `dm4x.ref` (0x13)**: byte for byte the same frame as `dm4.full` / `dm4.ref`. The rebuilt event has one more tag: `tags = [["p", hex(recipient)], ["expiration", decimal(created_at + 1209600)]]` (NIP-40, 14 days, implicit, 0 B on air). A kind 4 with any other expiration has no dm4 form. Policy switches and the maximum age are those of the dm4 twin. The LibreTag low-battery DM uses `dm4x.full` with at most 15 B of text (165 B frame), so any bridge can rebuild it without a registry (`PROTOCOL.md` 5).

**DMs also go to the recipient's inbox relays.** When the bridge publishes a DM (any dm4/dm4x frame), it publishes to its own relays and also to the recipient's inbox relays: the newest kind 10050 (`relay` tags, NIP-17) and the read or unmarked `r` tags of the newest kind 10002 (NIP-65), looked up on its subscribe relays and cached for 6 h (10 min when nothing was found). Only public `wss://` URLs, never localhost, `.local`/`.lan` or private addresses, at most `uplink.dm_inbox_max` (6) beyond its own set, one try each over a short-lived connection. Switch: `uplink.dm_inbox_relays`.

**0x04** was `libretag.public` (isomorphic LT1 report, 135 B). With LT2 it is removed (SPEC 6.2, variant B); the decoder rejects it as `UNKNOWN_PROFILE`.

## 2. Size budget

Limits: **163 B** over companion BLE and local API, **165 B** on the radio (serial/KISS/own firmware). The values are bytes of app data including the profile byte (or flags for `0x0150`).

Note: `MAX_GROUP_DATA_LENGTH = 184 - 16 - 3 = 165` is conservative. If the 184 B payload is split as channel_hash 1 + MAC 2 + 181 B of ciphertext, then 11 blocks of 16 B (176 B) hold 176 - 3 = 173 B of application data. It must be verified in `Mesh.cpp` whether the 165 limit is hard or only a convenient constant (open question 7.1). This document uses 165.

### 2.1 Formulas

- Fixed overhead of a signed event: pubkey 32 + sig 64 + created_at 4 = **100 B**.
- NIP-04: `ct = 16·(⌊m/16⌋ + 1)` (PKCS#7, even m = 0 gives 16 B). In the text content of the event: `4·⌈ct/3⌉ + 4 + 24` characters (`?iv=` + 24 base64 characters from 16 B). In the frame it is the raw `ct` + 16 B iv, so there is no base64 overhead on the radio.
- NIP-44 v2: `wire = 1 (version) + 32 (nonce) + ct + 32 (MAC)`, `ct = 2 + padded(m)`. `padded` is 32 for m ≤ 32, otherwise multiples of 32 up to 256 B (`33–64 → 64`, `65–96 → 96`, …). `content` holds base64 of `wire`: `4·⌈wire/3⌉`. Minimum `wire` = 1+32+34+32 = **99 B**.

### 2.2 Table (one frame)

| Case | Frame (B) | Fits 163 | Fits 165 | Left for content |
|---|---|---|---|---|
| Road report (0x0150) | 117 + N | yes, N ≤ 46 | yes (decoder up to N = 48) | comment 46 B |
| Road vote | 134 | yes | yes | 29 B free (unused) |
| Kind-1 note (`note`) | 102 + M | M ≤ 61 | M ≤ 63 | 61 / 63 B |
| `generic` kind 1, no tags | 105 + M | M ≤ 58 | M ≤ 60 | 58 / 60 B |
| `generic` kind 1 + 1 tag `t` (7 B) | 112 + M | M ≤ 51 | M ≤ 53 | 51 / 53 B |
| **NIP-04, `dm4.full`** | 149 + L | **no** (149+16 = 165 > 163) | L = 16: **m ≤ 15** | 0 / 15 B |
| NIP-04, `dm4.ref` sender ref only (recipient full) | 121 + L | L = 32: m ≤ 31 | L = 32: m ≤ 31 | 31 B |
| **NIP-04, `dm4.ref`** (both ref) | 93 + L | L = 64 (157): **m ≤ 63** | L = 64: m ≤ 63 (L = 80 → 173 > 165) | 63 B |
| NIP-44 kind-4-like, full keys | 198 + ct | **no** (min. 232) | **no** | 0 |
| NIP-44, ref keys | 142 + ct | **no** (min. 176) | **no** | 0 |

How to read the NIP-04 rows: `dm4.full` = 1 + 32 + 4 + 32 + 16 + 64 = 149 plus `L`. For `m = 15` it gives `L = 16`, frame 165. For `m = 16` it is already `L = 32`, frame 181: it fits nowhere. The `dm4.ref` both-ref row: 1 + 4 + 4 + 4 + 16 + 64 = 93 + L. For m = 63: L = 64, frame 157. For m = 64: L = 80, frame 173: it does not fit.

For a road report the exact relation 117 + N holds without the profile byte (flags in the Roadbeacon frame replace the byte).

### 2.3 Two fragments and more

A fragment has a 4 B header (3.1), so the useful data is **161 B** (165 - 4), or 159 B over companion (163 - 4). Concatenated, they unpack into the original frame (including the profile byte).

| Case | 1 frame | 2 fragments (≤ 322 B) | 3 fragments (≤ 483 B) |
|---|---|---|---|
| NIP-04 `dm4.full` | m ≤ 15 | `149+L ≤ 322` → L = 160, **m ≤ 159** | L = 320, **m ≤ 319** |
| NIP-04 `dm4.ref` | m ≤ 63 | `93+L ≤ 322` → L = 224, **m ≤ 223** | m ≤ 383 |
| NIP-44 `dm44.full` (own profile) | no | `198+ct ≤ 322` → ct ≤ 124 → padded 96, **m ≤ 96** | ct ≤ 285 → padded 256 → m ≤ 256 |
| NIP-44 `dm44.ref` | no | `142+ct ≤ 322` → ct ≤ 180 → padded 160, **m ≤ 160** | m ≤ 256 |

(NIP-44 rows: the `dm44.*` profile is not defined in this document; it would suffice as an extension 0x12/0x13 without changing the rules. It means NIP-44 content in a kind-4 event, which is not standard, see 6.)

### 2.4 NIP-17 / NIP-59: estimate

Estimate for a message m ≈ 40 B, hex text is in the JSON:

1. *Rumor* (kind 14, unsigned): `{"id":"<64>","pubkey":"<64>","created_at":N,"kind":14,"tags":[["p","<64>"]],"content":"…"}` ≈ **277 + m** B of JSON.
2. NIP-44 padding for 277+m ≤ 320 → 320. `wire` = 1 + 32 + (2+320) + 32 = **387 B**, in the seal base64 **516 characters**.
3. *Seal* (kind 13, signed by the author): JSON with `id`, `pubkey`, `created_at`, `kind`, `tags:[]`, `content` (516), `sig` (128 hex) ≈ **856 B** → padding 896 → `wire` = 1+32+898+32 = **963 B**.
4. *Gift wrap* (kind 1059, signed by a random key): pubkey 32 + sig 64 + created_at 4 + p-tag 32 + 963 = **~1095 B** binary (in the JSON `content` is 1284 base64 characters).

That is 7 fragments of ~161 B each, so 3.5–24 s of air time per hop (SF8–SF11), and that is **without repeaters**. Encrypted content cannot be compressed. Unrealistic as a direct transfer.

Variant "the bridge wraps": the gift wrap (outer layer) is signed by a random key, so it **authenticates nothing** and the bridge can produce it itself. The device would send only the *seal* (~456–520 B, 3–4 fragments, with a simplified rumor without `id`, which is outside the specification). Drawbacks: the wrap is not deterministic (several bridges = several wraps, the client must dedupe by rumor id), the bridge must know the recipient's DM relays (kind 10050) and the recipient must be in the frame unencrypted (unsigned metadata). Not scheduled for now.

### 2.5 Airtime

Estimate for ~170 B on air (payload + MeshCore header + path), BW 125 kHz, CR 4/5, preamble 8:

| SF | ~time of one frame |
|---|---|
| 8 | 0.5 s |
| 9 | 0.9 s |
| 10 | 1.6 s |
| 11 | 3.5 s |

In a flood this repeats at every hop (each repeater transmits once), so the network load is of the order of `n_repeaters × time`. That is another reason for: one frame, short messages, no needless fragments.

## 3. Fragmentation (application level)

`PAYLOAD_TYPE_MULTIPART` exists in MeshCore, but `Mesh.cpp` handles only multipart ACK. So we fragment ourselves, inside GRP_DATA.

### 3.1 Fragment format (profile 0x3E)

```
0   1   0x3E (version 0, profile frag)
1   2   msg_id = SHA-256(original frame)[0:2]
3   1   idx (bity 7-4) | n (bity 3-0)
4   ... data (up to 161 B)
```

- `n` = number of data fragments (2–4). `idx` = 0..n-1. `idx = 15` is an XOR parity fragment (optional, only for n = 2..4; the data is padded with zeros to equal length before the XOR, the real length is known from the assembled frame).
- Assembly: data in idx order. When `SHA-256(frame)[0:2] != msg_id`, the frame is dropped. Then the usual decoding path (signature).
- `msg_id` depends on the content, so an attacker cannot "insert" a fragment with foreign content without failing the check. They can, however, send a fake fragment with the same `msg_id`, which cancels the message. The receiver therefore keeps at most 2 variants for each `(msg_id, idx)` and tries combinations.

### 3.2 Rules

| Parameter | Value |
|---|---|
| Largest n | 4 (≈ 640 B) |
| Reassembly timeout | 60 s from the first fragment |
| Spacing of fragments when transmitting | ≥ 3× the airtime of one frame (so repeaters can finish the first flood), at SF9 that is at least ~3 s |
| Messages in progress at once | 32 (LRU) |
| Rate limit | counted per **message**, not per frame |

### 3.3 Cost

- Delivery probability: if one frame arrives with probability p, a message with n fragments arrives with `p^n`. At p = 0.8 and n = 2: 0.64. With parity (2 of 3) it comes to `3p² - 2p³` = 0.896 at the cost of 50 % more air time.
- Airtime: n × frame time, in the whole flood.
- State on the receiver and complexity (timers, `msg_id` collisions).
- The order of fragments in a flood is not guaranteed. Assembly tolerates that.

### 3.4 When to allow

| Profile | Fragmentation |
|---|---|
| `road.*` | **no** (designed for 1 frame) |
| `note` / `generic` | off by default. Optionally behind a bridge setting (`allow_fragments`) |
| `dm4.*` (and a possible `dm44.*`) | **yes**, n ≤ 4 (in practice 2) |

When transmitting into the mesh (relays→mesh), the bridge fragments only when the profile allows it and the message does not otherwise fit.

## 4. Channel strategy

| Option | Plus | Minus |
|---|---|---|
| **One channel `#nostr`** for all new profiles | the bridge joins 1–2 channels, few slots on the companion | all devices on the channel receive all frames |
| A channel per profile (`#nostr-dm`, `#nostr-note`, …) | each receiver picks what it wants | slots (a companion has few channels), more members, needless complexity |

Important note: **repeaters forward GRP_DATA regardless of channel**. The channel decides only who decrypts the frame and delivers it to the application. Splitting channels therefore does not reduce the air load, only the endpoint load.

**Recommendation:**

1. `#nostr` (new `data_type`) for the profiles `note`, `generic`, `dm4.*`.
2. `#roadstr` + `0x0150` stays and the bridge understands it as an **alias** of the profiles `road.report` / `road.vote`. Existing Roadbeacon and Roadstr builds do not change. Do not send new road frames into both channels. The bridge enforces this with a ledger by `id`.
3. `#libretag` + `0xFF4C` carries LT2 reports (45 B) and commands (29 B). The bridge does not decrypt them: it publishes the report as kind 4243 and sends the command from the mailbox (kind 4244) right after the report (8.9).
4. The channel is public by construction (key from the name). It is neither privacy nor authorization.

## 5. MeshCore<->Nostr bridge

### 5.1 Architecture

```
          radio                                   internet
 ┌───────────────────┐   ┌─────────────────────────────────────────────┐
 │ companion (USB)   │   │ nostr-mesh-bridge (Python)                  │
 │   or              │◄─►│ radio adapter ─► decode ─► verify ─► ledger │──► relays (WS)
 │ KISS modem        │   │      ▲                          │            │
 └───────────────────┘   │      │ TX queue + hold/jitter ◄─┤ policy     │◄── relay subscriptions
                         └─────────────────────────────────────────────┘
```

Modules (proposal in `host/src/nostrmesh/`):

| Module | Role |
|---|---|
| `radio/` | adapters: `companion_serial`, `local_api` (MeshCore Open WS), `kiss` |
| `frames/` | profiles 1.2, fragments, vectors |
| `nostr.py` | NIP-01 serialization, id, BIP-340 (verification, signing only in tests) |
| `ledger.py` | SQLite: `event_id`, `first_seen`, `source` (radio/relay/own), `published_to`, `sent_to_radio_at` |
| `publisher.py` | relay pool, `OK`/`NOTICE`, retries, backoff |
| `downlink.py` | subscriptions, filters, encoding, hold, TX queue |
| `policy.py` | limits and allowlists per profile |
| `registry.py` | device registry (5.5) |

### 5.2 Mesh → relays (on by default)

1. Receive data (`data_type` ∈ {new, `0x0150`}, channel ∈ {`#nostr`, `#roadstr`}). Assemble any fragments.
2. Decode by profile, reject before any cryptography if the length, version, ranges or UTF-8 do not fit (same as Roadbeacon).
3. Reconstruct the event, verify the id and the **BIP-340 signature**.
4. Time checks: `created_at` no more than 5 min in the future; drop anything older than the profile maximum (road: category lifetime; vote: 30 days; DM: 7 days, configurable; LibreTag: 30 days).
5. Ledger: if `event_id` already exists, stop (only mark `heard_on_radio`).
6. Policy: whether the profile is allowed for publishing, key allowlist (DM), limits (5.6).
7. Publish to all configured relays. The response `OK true` and also `duplicate:` is success (the relay already has the event). `OK false` with `rate-limited:`/`blocked:` is recorded and retried with backoff; `invalid:` is not retried.

Several gateways publish the same event with the same id at the same time: relays filter it out. So there is **no hold** here (unlike relays→mesh). Optional saving: before publishing, the gateway checks `REQ ids` on one relay. Not needed.

### 5.3 Relays → mesh (OFF by default)

It is switched on per profile and with the operator's explicit consent. Different rules for each profile:

| Profile | Subscription | Condition for transmitting |
|---|---|---|
| `road.*` | kinds 1315/1316, geohash tags in the radius | the event is encodable, within **40 km** of the radio position (fix younger than 10 min), a vote uses the position of the report |
| `dm4.*` (to a device) | `{"kinds":[4],"#p":[<registered pubkeys>],"since":<now>}` | the author is in the device's allowlist (`allow_from`), the message fits into `dm4.ref` (max n fragments), the device is in range according to the registry |
| `note` | per configuration (authors) | usually do not enable |
| LT2 command (kind 4244) | `{"kinds":[4244],"since":<now − 48 h>}` | only after a fresh report with the same `x` that the bridge heard **after** the command arrived; zero-hop if heard directly, otherwise flood; bridge agreement kind 24244 (8.9). With `at` and `via` (0.7 checks): at time `at`, zero-hop or flood, only by a bridge that caught report `via` |
| LT2 cancellation (kind 5, NIP-09) | `{"kinds":[5],"#k":["4244"],"since":<now − 48 h>}` | never transmitted; drops an unsent mailbox entry whose 4244 event has the same pubkey (8.9) |

Common rules:

1. The event must encode exactly (otherwise it is dropped, without repair).
2. **Hold 3–15 s at random**, and transmit only if the event has **not been heard on the radio** in the meantime (ledger). Of several bridges in range, usually one transmits. For a DM the hold is extended by a position jitter (e.g. hash(bridge_id, event_id) mod 12 s), so the same two bridges do not keep colliding.
3. Limits (defaults, lower than Roadbeacon, because this is a general frame): 20 / h in total, 4 / author / h, a 20 s gap between transmissions, DM 6 / h in total and 4 / device / h.
4. The ledger prevents a mesh→relay→mesh loop: an event with `heard_on_radio` or `sent_to_radio_at` is not transmitted.
5. **Transmit only when the device can listen.** Autonomous devices sleep. The proposed rendezvous (as in LibreTag PROTOCOL.md 4): after an uplink frame the device listens for a few seconds. The bridge keeps the DM in a waiting queue (TTL 24 h, 8 messages per device) and sends it after the next uplink from that `pk_ref`. With a KISS adapter it can also be sent **direct** along the reverse path of the uplink (direct routing is forwarded regardless of type, so the whole network is not flooded). This is not possible through the companion API (channel data only); there flood remains.
6. The bridge has its own transmit budget: by default 0.1 % of time over a sliding hour (3.6 s), computed with the LoRa airtime formula (`libretag/meshcore.py lora_airtime_s`) for every own packet (DM, road, LT2 command). After it is used up, it stays silent. `[airtime]` in the configuration.

### 5.4 Connecting to the radio

| | Companion over USB | KISS modem |
|---|---|---|
| What it sees | only the channels the node has (`channel.sendData`, received data, max 163 B) | all raw packets |
| GRP_DATA decryption | done by the firmware | done by the host (channel key, MAC) |
| Transmitting | channel data only, flood | raw packets including direct, 165 B |
| Complexity | low (the local API/Companion protocol exists) | high: the host must implement the MeshCore header, packet hash, LBT, duty cycle |
| Flood dedup | handled by the firmware | host (packet hash) |
| Setup | add `#nostr`, `#roadstr` | keys in the configuration |

Recommendation: **v1 over companion** (USB serial or MeshCore Open local API), because it is the least work and Roadbeacon already works that way. Add KISS in v2 for direct replies to devices and 165 B frames. `host/src/libretag/transport.py` already has KISS framing, `meshcore.py` is a good base for the adapter. The exact companion protocol command codes (channel data = `0x3E`, as stated in `meshcore.md`) must be verified in the MeshCore source during implementation.

Caution with companion: a sent command is acknowledged, not delivered. Delivery confirmation for flood does not exist. The bridge must not present it as delivery. The companion also stores received messages in a queue, so after a long outage the bridge gets old frames. The ledger and time checks handle that.

### 5.5 Device registry (`dm4.ref`)

The registry is a local bridge file (or a shared list), not something that is transmitted:

```toml
[[device]]
name    = "sensor1"
pubkey  = "<hex>"          # pk_ref = first 4 B
owner   = "<hex npub owner>"      # default recipient
allow_from = ["<hex npub owner>"] # who may write to the device (relays→mesh)
```

The frame does not carry full keys, so the bridge **does not have to trust anything**: it still verifies the signature against the reconstructed `pubkey`. A fake `pk_ref` ends in `SIGNATURE_INVALID`. The cost: the bridge must know the keys (manual registration, or the device sends a `dm4.full` / `generic` frame with the full key once and the bridge remembers it). For privacy see 5.7.

### 5.6 Limits and abuse resistance

The channel is public and anyone knows the key, so anyone in range can inject a frame. We do not know the source of a frame (GRP_DATA has no sender), only the `pubkey`, which costs nothing.

| Defense | Detail |
|---|---|
| Signature | nothing goes further without a valid signature |
| Format check before cryptography | length, version, UTF-8, ranges |
| Global limit on receiving from the radio | 60 frames / min (as Roadbeacon) |
| Limit per `pubkey` | 6 / min, 60 / h (LT2 reports have no pubkey on the radio: global limit only) |
| Allowlist | DM (publishing and transmitting) only for registered keys. `note` and `generic` off by default until the operator enables them |
| Time window | `created_at` ±5 min into the future, maximum age by profile |
| Size | profile maximums, reject `generic` with > 15 tags |
| Relays | respect `OK false`, NIP-42 auth and paid relays are handled in the configuration, never create new identities |
| Optional NIP-13 PoW | `id` with N zero bits. Can be used without changing the format (nonce tag in `generic`), consider it for public `note` |
| Fragments | a cap on messages in progress and on memory |
| Radius | the bridge transmits only in a configured area (geofence) |

The bridge **never signs or modifies an event**. (The only exception is the future NIP-17 wrap variant, 2.4, where the bridge signs the outer layer with a throwaway key.)

### 5.7 Privacy

- The bridge sees metadata: the sender's `pubkey`, the recipient (p tag), `created_at`, size, time of receipt, RSSI/hops (depending on the adapter). Relays know them too: DM metadata is public for kind 4.
- A bridge with the radio's position and a `pk_ref` registry knows that a device is in its vicinity. Do not log long-term, clean the ledger (e.g. 30 days, only `event_id` and times).
- The device registry is sensitive (who owns which device). Keep the file with permissions 600 and do not put it into a public configuration repository.
- LibreTag LT2: the tag `x` and the content change with every report, the event is signed by a random key. But the bridge knows where the frame was captured, and a claim (kind 24244) reveals that this bridge heard it (a random key per claim).
- Radio side: everything on `#nostr` is public by the construction of the channel.

### 5.8 Configuration (sketch)

```toml
[radio]
adapter = "companion_serial"      # | "local_api" | "kiss"
port    = "/dev/ttyACM0"
channels = ["#nostr", "#roadstr", "#libretag"]

[relays]
publish = ["wss://relay.example.org", "wss://relay2.example.net"]
subscribe = ["wss://relay.example.org"]

[uplink]                           # mesh -> relays, on by default
enabled = true
rate_global_per_min = 60
profiles = { "road.report"=true, "road.vote"=true, "dm4.ref"=true, "dm4.full"=true, "note"=false, "generic"=false }

[downlink]                         # relays -> mesh, OFF by default
enabled = false
hold_min_s = 3
hold_max_s = 15
rate_global_per_hour = 20
rate_per_author_per_hour = 4
min_gap_s = 20
geofence = { lat = 48.70, lon = 21.25, radius_km = 40, fix_max_age_s = 600 }
profiles = { "road.report"=false, "road.vote"=false, "dm4.ref"=false }

[ledger]
path = "ledger.sqlite"
retention_days = 30
```

## 6. DMs from autonomous devices

### 6.1 Which NIP is realistic

| | NIP-04 (kind 4) | NIP-44 (in kind 4 or own kind) | NIP-17/59 |
|---|---|---|---|
| Min. frame (full) | 149 + 16 = 165 B | 232 B | ~1.1 kB |
| Min. frame (ref) | 93 + 16 = 109 B | 176 B | |
| In one frame | yes (ref: 63 B) | no | no |
| Fragments | 1 (2 for ≤ 223 B ref) | 2 | 7 |
| Privacy | metadata public, content encrypted, no forward secrecy | same, better cryptography (HKDF, ChaCha20, HMAC), metadata public | metadata hidden (gift wrap) |
| Standard | deprecated, but still widely supported (client support in 2026 to be verified) | non-standard for kind 4, a client would not decrypt it | current standard |
| Computation on the MCU | ECDH once, AES-CBC | + ChaCha20, HMAC | + 2× NIP-44, 2× signature, ECDH with an ephemeral key |

### 6.2 Recommendation

1. **Phase 1: NIP-04 over `dm4.ref`**, one frame, message ≤ 63 B. Covers sensors, alarms, short statuses. Encrypts the content, metadata is public (that is the price of NIP-04).
2. **Phase 2: fragmentation** (n = 2, optional parity) for ≤ 223 B (`ref`) or ≤ 159 B (`full`).
3. **Do not do NIP-44 in kind 4**: clients will not recognize it. If it is ever introduced for a custom kind, it will be a new profile, not NIP-04.
4. **NIP-17**: only if a real need shows up. A sensible path is a wrap on the bridge side (2.4), 3–4 fragments, and it requires a decision on a rumor without `id`. Do not include in v1.
5. For disputed cases (metadata must be hidden) the device does not speak Nostr DM at all: it sends its own encrypted channel frame (like LibreTag `0xFF4C`) and the receiver (the owner's app) forwards it itself.

### 6.3 Key management on the device

- The device has its own `nsec` (secp256k1) in flash, generated at first power-on from the HW RNG, and it **never leaves the device**. Only the `npub` is exported over USB/BLE. An alternative is derivation from the owner's secret (`sk = HMAC(K, "NM1-device" || id)`, as with LibreTag `pk_n`): the owner can recover it, but a leak of `K` also reveals the device key. Choose according to the threat model.
- The device knows the recipient's (owner's) `npub` and **computes the shared key once** (ECDH, x-coordinate 32 B, no hash, used as the AES-256 key). The NIP-04 key is constant for a pair, so it is stored. For each message: a random IV (16 B) from the HW RNG, AES-256-CBC with PKCS#7, signature.
- **Signature**: BIP-340 Schnorr, one-off per event. Speed on the nRF52840 with libsecp256k1 or a micro-ecc-like library is on the order of tens to hundreds of ms. Must be measured (7.2).
- **Receiving** (relays→mesh): the device receives a `dm4.ref` frame, checks `created_at` > the last seen (high-water mark) and a ring of recent ids, verifies the signature, verifies that the author is in its allowed list, decrypts. Unused messages do not store the key.
- **Time**: without a correct `created_at` relays reject the event or the client shows it wrongly. The device needs an RTC/GNSS or time sync from the mesh. The bridge cannot fix the time because it is in the signature (7.3).
- Replay protection on receiving is only as good as the monotonic `created_at`. The DM itself has no counter.

## 7. Open questions and what to research

### 7.1 MeshCore

1. Is the 165 B limit for GRP_DATA hard? The calculation suggests up to 173 B (2.). Verify in `Mesh.cpp` and on hardware; if 173, recompute the tables.
2. What does the companion command for channel data look like, what are the limits of the received message queue and how many channels fit (we need `#nostr`, `#roadstr`, `#libretag`)?
3. How do flood scope/region behave for `#nostr`? Should the bridge send with a region?
4. Request a `data_type` allocation for "Nostr Event Frame" (after validation on a real radio). Until then `0xFF4E`.
5. Can the bridge send GRP_DATA as direct along the reverse path of the uplink over KISS? (PROTOCOL.md does this for commands like REQ.)
6. What are the real airtime figures in the EU presets in practice and how many repeaters take part in a flood? Measure in the field.

### 7.2 Cryptography and devices

1. Speed and code size of libsecp256k1 (Schnorr + ECDH) on the nRF52840 and ESP32. Does the CC310 support secp256k1?
2. How to decide whether the device can store the `nsec` safely (flash protection, readback protection)?
3. Test vectors: a generator in the style of `gen_road_event_vectors.py` (BIP-340 reference code, fixed keys, zero aux) for each profile, including `encode_invalid` (non-canonical base64, wrong IV, `ct` not a multiple of 16).
4. Verify that real clients display a NIP-04 `content` in the form `base64(ct)?iv=base64(iv)` without line wrapping and with `=` padding (libraries differ).

### 7.3 Time and trust

1. Time source for devices without GNSS (MeshCore time sync?).
2. The maximum `created_at` deviation that common relays accept (many reject events that are too old or in the future).
3. How to register a device in the bridge securely (without manually copying keys)?

### 7.4 Network and policy

1. NIP-04 clients in 2026: which still accept kind 4? If many do not, pressure toward NIP-17 will come fairly quickly and a bridge wrap will be needed.
2. Public `#nostr`: the MeshCore community may have an opinion on blanket randomly thrown messages. Agree on rules (limits, leave out `note` by default).
3. Shared network and spam: is 60 frames / min per bridge enough, or is PoW (NIP-13) needed for `note`?
4. Relay policy: which relays accept signed events without an account? Who pays for relays for the bridge?
6. Long term: is it better to fragment in MeshCore (at the protocol level) than in the application? Propose upstream.

### 7.5 Order of work

1. `frames` library + vectors (`note`, `dm4.full`, `dm4.ref`, road alias).
2. Bridge v1: companion adapter, mesh→relays, ledger, limits.
3. DM device (prototype on X1 or ESP32): `dm4.ref`, 1 frame.
4. Fragmentation + parity.
5. Relays→mesh (road with a geofence, DM with an allowlist and rendezvous).
6. KISS adapter and direct replies.
7. NIP-17 bridge wrap (depending on demand).

## 8. Implementation v1 (`host/src/nostrmesh/`)

| Module | What it does |
|---|---|
| `secp.py` | BIP-340 (signing, verification) and ECDH for NIP-04, pure Python. Reproduces signatures from the Roadbeacon vectors, ECDH is verified against the `cryptography` library |
| `nostr.py` | NIP-01 serialization and id, NIP-04 (AES-256-CBC, canonical `base64?iv=base64`) |
| `road.py` | Roadbeacon frames `0x0150`. Passes the whole `road-events-v1.json` (valid, decode_only, decode_invalid, encode_invalid) |
| `frames.py` | profiles `note`, `generic`, `road.*`, `dm4.full`, `dm4.ref`. After encoding, the encoder decodes the frame again and compares it with the event |
| `frag.py` | fragments `0x3E`, n ≤ 4, XOR parity, max. 2 variants per index, timeout 60 s, 32 messages in progress |
| `radio.py` | `CompanionSerial` (USB, flood only; reopens the port with backoff, marks the first queue drain after a connect as stale), `KissRadio` (direct and zero-hop; the receive thread survives a lost port), `MemoryRadio`; `supports_direct` says whether the adapter can send along a path |
| `localapi.py` | `LocalApiRadio`: MeshCore Open / `meshcored` local API, a thin wrapper over `meshcore_localapi.LocalApiClient` (P-256 pairing, subscribe/join, cursor, capabilities `tx_path_data`, `rx_path`, `rx_meta`, `duty_cap`, …) |
| `relays.py` | relay pool, `OK`/`duplicate:` as success, retries 5 s / 30 s / 2 min / 10 min, final rejections (`invalid:`, `blocked:`, …) are not retried; optional NIP-42 AUTH (`[relays] auth_key`) |
| `inbox.py` | inbox relays of a DM recipient (10050, 10002 read): only lists with a valid signature, only hosts that resolve to public addresses |
| `market.py` | bridge identity, announcements, receipts, format 2 targeting, claim proofs; the vectors in `host/tests/vectors/mailbox-v2.json` (also the signed mailbox: which keys a report locks) |
| `ledger.py` | SQLite (`~/.config/nostrmesh/ledger.sqlite`, 30 days): events (`heard_on_radio`, `published_at`, `sent_to_radio_at`), LT2 reports by SHA-256(P), command mailbox (signed, source relay, group), deletions of mailbox events (kind 5), answered `x` tags, transmitted `Pc` hashes, timed transmissions, closed groups, airtime spent, the outbox of unpublished events |
| `bridge.py` | mesh → relays, relays → mesh (hold, "did I hear it meanwhile?", limits, geofence, DM queue for sleeping devices), airtime budgets (one for `#libretag`, one for the rest), outbox retries, heartbeat (8.10) |
| `radio_health.py` | the radio's own state: down and degraded detection, the announcement and miss notes that follow it, the alert and recover hooks, the recovery steps (8.10) |
| `lt2svc.py` | LibreTag LT2: uplink kind 4243 with deduplication and receipts, mailbox kind 4244, signed (PROTOCOL.md 4.2: anchors, locks, late entries) with limits; the quiet mesh (4.3): ACK entries, the route plan of `libretag.quietmesh`, the `0xFF6C` fallback, timed sends with retries and miss notes, groups, bridge agreement, "ack sent" and "done" notes kind 24244 (8.9) |
| `vectors.py` | generator of `docs/vectors/nostr-frames-v1.json` |

Configuration: `host/bridge.example.toml`, device registry `host/devices.example.toml`.

### 8.1 What the ZephCore source showed (companion protocol)

- Frames over USB: app → node `'<' len16LE payload`, node → app `'>' len16LE payload`. This holds for upstream MeshCore too.
- `CMD_SEND_CHANNEL_DATA` (0x3E): `idx, path_len (0xFF = flood), data_type LE16, data`. The firmware rejects data above `MAX_GROUP_DATA_LENGTH` = **165 B**. A companion protocol frame would carry 167 B, but the radio limit is the hard one. Question 7.1.1 is therefore answered: 165 in practice.
- Received channel data arrives as `PACKET_CHANNEL_DATA_RECV` (0x1B): `snr*4, 2 reserved, idx, path_len, data_type LE16, len, data`. In a flood `path_len` is the hop count.
- Received messages are stored in an offline queue. Push `0x83` (MSG_WAITING) and then `CMD_SYNC_NEXT_MESSAGE` until `NO_MORE_MSGS`. The bridge therefore also pulls chat messages from the queue, so it **needs its own node**, not the one the phone uses.
- Channels: `CMD_GET_CHANNEL` returns the name and the 16 B key. The bridge takes only hashtag channels, where key = `SHA-256(name)[0:16]`. With `--add-channels` it adds a missing channel into a free slot (`CMD_SET_CHANNEL`).

### 8.2 Decisions in the implementation

- The profiles `road.report`/`road.vote` in the `0xFF4E` frame: the profile byte replaces the flags byte from Roadbeacon, the body is the same. We transmit them only through `#roadstr`/`0x0150`, though.
- `generic` picks the shortest item type: 1 character → `0x04`, lowercase hex 32 B → `0x01`, canonical number < 2³² → `0x02`, otherwise string. Uppercase hex and numbers with a leading zero stay a string, so nothing is lost.
- Rendezvous: a DM for a device with `rendezvous = true` waits in the queue (24 h, 8 messages). It is sent 0.5–2 s after the next frame the device signs. For devices that listen all the time it goes immediately (with hold).
- DMs are published only when the sender or recipient is in the registry (`unregistered_dm = false`). Exception: expiring DMs (`dm4x.*`, gone from relays after 14 days) from any key (`unregistered_expiring_dm = true`), within the per-pubkey rate limit. This is how a tracker's low-battery DM (`dm4x.full`) reaches its owner through any bridge.
- Tests: `uv run pytest` (Roadbeacon vectors, own vectors, a fake companion node, KISS, two bridges on a shared "ether", a local WebSocket relay).

### 8.3 MeshCore Open (phone)

`adapter = "local_api"` connects to `ws://127.0.0.1:8787/api/v1`. The bridge then runs in Termux on the same phone, or on a PC through `adb forward tcp:8787 tcp:8787`. The radio stays in MeshCore Open, so chat continues. On the first connection MeshCore Open shows "Allow nostrmesh (AB12 CD34 …)?"; the fingerprint is printed by `nostrmesh pairing`. The key and cursor (`receivedAt`) are in `~/.config/nostrmesh/meshcore-open.json`. A missing `#nostr`/`#roadstr` is requested by the bridge through `channels.join`. The limit is 163 B (BLE), DMs are fragmented accordingly.

### 8.4 Direct replies (KISS)

The KISS adapter sees the flood path of every packet. When a frame signed by a device from the registry arrives, the bridge stores the reversed path (15 min). A DM for that device then goes as **direct** GRP_DATA along that path. Repeaters forward direct packets regardless of type, so only those on the path transmit, not the whole flood. Without a fresh path, or over companion/local API, flood is sent. Disable with `direct_replies = false`.

### 8.5 Tracker firmware

The tracker firmware no longer sends a public report, inbox poll or own Nostr events, except a DM on a low battery (below 10 %, a `dm4` frame). LT2 reports are published by bridges as kind 4243, see `PROTOCOL.md` 4 and 8.9.

### 8.6 One packet per wake-up

With LT2, one 51 B report goes out on `#libretag` at wake-up (at the boundary possibly a copy with `j = 1`). The public report on `#nostr` (`libretag.public`) is removed: any bridge publishes the same report as kind 4243 (8.9). The bridge never needs the tracker key; the device registry no longer has `libretag_key`.

### 8.7 Android app "Nostr bridge" (`android-bridge/`)

A native app (Kotlin, Material 3) that runs through the MeshCore Open local API on the same phone ([protocol](https://github.com/jooray/meshcore-local-api), through the library's `MeshApiSession` since 0.5.0):

- **Channels:** `#nostr` and, depending on settings, `#roadstr` and `#libretag` (LT2, 8.9).
- **Radio → Nostr:** all profiles including `dm4x.*`, with signature verification, dedupe and limits. A `dm4x` frame follows the switches and the maximum age of its `dm4` twin; with `unregisteredExpiringDm` (on by default) it goes up from any key. Every DM also goes to up to 6 inbox relays of the recipient (kind 10050 `relay` tags, then kind 10002 `r` tags marked read or unmarked; only public `wss://`, no local names or private addresses; the lookup is cached for 6 h, or 10 min when nothing was found).
- **LibreTag (8.9):** 45 B reports and 141 B BLE packets go up as kind 4243 (expiration +14 d). The mailbox has plain and timed entries and honours NIP-09 cancels, with the same rules as `lt2svc.py`. A ticker checks timed entries twice a second.
- **Nostr → radio:** LT2 commands, and DMs only for devices from the registry from allowed authors. For sleeping devices it waits for them to report. A relay DM with the +14 d expiration goes down as `dm4x.ref`. The app does no road downlink; Roadbeacon has that.
- **Radio path:** zero-hop and direct need the MeshCore Open Local API capability `tx_path_data` (and `rx_path` for direct, to know the path). Without them the app follows the Python rules for an adapter without a path: only `route=flood` entries go out (as flood), everything else waits. The path of each caught report is stored for timed entries only when the server reports it (`rx_path`); with only a hop count, a timed entry can still go zero-hop.
- **Cryptography:** secp256k1-kmp (as Roadbeacon).
- **Limits (0.3.0; signed mailbox in 0.5.0, no PoW since):** the same signed-mailbox rules (anchors, locks, late entries), timed-entry limits, eviction, per-relay quota, once-per-`Pc` rule, command groups and done claims as `lt2svc.py` (8.9), with the same defaults in `BridgeConfig`. `#libretag` has its own receive limit (`ltRxPerMinute`), a share of it per first repeater (`ltRxPerFirstHopPerMinute`, 0.3.2) and its own airtime budget (`ltAirtimeFraction`).
- **Staying up (0.3.0):** starts after a reboot or an app update when the bridge was on (`BootReceiver`), asks once for the battery optimisation exemption when the bridge is switched on, and saves its state (`bridge-state.json`) on every mailbox change, not once a minute.
- **Outbox, watchdog, heartbeat (0.3.2):** as in the host bridge (8.10). An event no relay takes waits in the outbox, which is saved in `bridge-state.json`. The app retries it from its once-a-minute tick on the same schedule (15 min, 15 min, 30 min, then hourly) until it expires after 14 days, or earlier if the event's own `expiration` says so. After 30 minutes with no datagram and no message from MeshCore Open, the app restarts its Local API client inside the service instead of exiting (counter `watchdog_reconnect`). Every hour it logs a heartbeat line and writes `status.json` in app storage with the host bridge's keys. The app does not show the status yet.
- **Tests:** JVM tests run `nostr-frames-v1.json` (including the `dm4x` and `encode_invalid` cases), `lt2.json`, `mailbox-v2.json` and the Roadbeacon vectors, plus bridge tests for the inbox relays, timed entries, cancelling, the signed mailbox, limits and groups. The copies of the vectors must equal the canonical files: `python3 tools/check_vectors.py` (also run by `uv run pytest` in `host/`).
- **UI:** MeshCore Open and relay status, statistics, activity list, settings (relays, profiles, device registry, flood region).

### 8.8 Not done yet

- Receiving fragmented DMs in the firmware. The device takes only DMs in one frame (up to 63 B), which is enough for commands.
- `CompanionSerial` does not yet send with a path (the `path_len` argument of `CMD_SEND_CHANNEL_DATA` is not verified), so over USB companion an LT2 command goes only with `route=flood`. KISS and Local API with `tx_path_data` have full support.
- Verification on the radio, signing times on the nRF52840 and the display of NIP-04 in real clients (7.2.4).

### 8.9 LibreTag LT2 in the bridge (`lt2svc.py`, quiet mesh, bridge 0.7.0)

The bridge decrypts nothing and does not know `K0` or `V_e`. The wire formats are in PROTOCOL.md 4 to 4.3, the behaviour in SPEC 3.2 ("Bridges", "Radio health"), the reasoning in `docs/design/quiet-mesh.md`. Every send decision for an ACK or command entry comes from `libretag.quietmesh.plan`, and the tests run it against `docs/vectors/quiet-mesh-v1.json`.

**How a packet arrived.** The adapters report the route type (`flood`, `tflood`, `direct`, `tdirect`). A flood's hop count is the length of its path. A DIRECT packet that reaches the bridge has an empty path whether the tag sent it zero-hop (copy 9) or it came along a path (copy 11), so its hop count is unknown and stays empty in the logs. "Heard directly" means a DIRECT arrival or a flood with 0 hops. Whether a DIRECT arrival is answered zero-hop or along a path is decided by the entry: `near` means zero-hop, a `via` with a stored path means along that path.

| Adapter | Route type | Path of a flood | Scope of a flood | Sends |
|---|---|---|---|---|
| Local API | with `rx_meta` | with `rx_path` | `transportCode` or `scope` with `rx_scope` | flood in a region (`regions`), zero-hop and path with `tx_path_data`, `"priority": "now"` for ACKs with `tx_now` |
| KISS | from the packet | from the packet | transport code from the packet | flood in a region (TRANSPORT_FLOOD), zero-hop, path |
| companion serial | path length `0xFF` = DIRECT (ZephCore `onChannelDataRecv`) | hop count only | none | flood in the node's own scope, zero-hop; no routed ACKs or path sends, because no received path |

**Uplink.** A 45 B datagram on `#libretag` (`0xFF4C`) becomes kind **4243**: `["y", hex(P[0:4])]`, `["expiration", now + 14 d]`, `content = base64(P)`, signed by a new random key, `created_at` = when the radio heard it. The event is signed and written to the outbox when the packet is heard, so a crash during the wait loses nothing; the outbox entry is due a minute after the wait. The wait is 0.3 to 0.8 s for a report heard directly or over 1 or 2 hops with a path when no untimed command waits for it, else 2 to 5 s (above the claim round, so only a bridge that heard the report can prove it). If an event with the same `y` and the same content is already on `dedup_min_relays` relays, it is not published. A frame the node marks `queued`, or whose receive time is more than 20 s old (`approx_after_s`), or the first drain after a companion reconnect, goes out with `created_at` = now and `["approx", earliest]`, and never locks a key, triggers a send or gets a receipt (`lt_rx_stale`).

**Receipts** (kind 4245, on by default) go out with the report event, only for a report heard directly: `["route", "direct"]` for a DIRECT arrival, `["hops", "0"]` for a flood with 0 hops. None for a flood heard over repeaters.

**Mailbox.** Kind 4244 entries need `["v", "2"]`, a signature by the key the report commits to (the signed mailbox of 4.2: a key locks when an event of it named `x` at least `anchor_lead_s` before the first live hearing and it fits `c`), and the targeting tags of 4.1. Anchors name five `y` per counter. An untimed command known before the report goes out on its first live hearing; a late one (from a locked key) while the report is at most `late_max_s` (40 s) old.

| `route` | The bridge sends a command |
|---|---|
| `near` | zero-hop only, for a report heard directly |
| `direct` | zero-hop, or along a path of 1 or 2 repeaters (`direct_max_hops`); never a flood |
| `flood` | the cheapest way: zero-hop for a copy heard directly, along the reversed path for a flood heard over 1 or 2 repeaters or along the path stored for its `via` (a ROUTED tag), else a flood in the report's scope |
| `both` | as `flood`, and the fallback after a zero-hop send too (Lost, Track, a command not confirmed at the previous slot) |

**Fallback.** After a zero-hop send with `both`, or any send along a path, the bridge waits 12 s (`fallback_after_s`) for the tag's answer: a live report whose `x` is a `y` of another untimed entry of the same group `g`, on any route. None heard: the same `Pc` goes once more as a flood in the report's scope (for a DIRECT arrival the narrowest configured region), with channel data type **`0xFF6C`**, because repeaters that heard the first send drop the same bytes. `["fb", "0"]` turns it off for an entry. An entry without `g` gets no fallback (the answer cannot be recognised).

**ACK entries** (`["a", "1"]`, 13 or 26 B). They pass a bridge that takes no open entries and need no `p`, because they never flood. Such a bridge still subscribes to every kind 4244 and keeps the anchors too (they carry no tag a filter could select, and without them no key locks); only open command entries are dropped on arrival. They are stored only when their key can lock, and they are sent from the persisted lock and path store.

- Near form (`route` `near`, 13 B; the first half of a 26 B entry): zero-hop, only from a bridge that heard that copy directly, after 100 to 600 ms, and only if the same `Pa` was not heard on air in that time.
- Routed form (second half of a 26 B entry for a flood over 1 or 2 repeaters; or 13 B with `route` `direct` and a `via` for a copy 11): along the reversed path with the path appended (`quietmesh.ack_routed`). The first routed ACK of a flood waits for a 2 s claim round and is skipped when an "ack sent" note for the same `y` comes in. A send with `via` stores the path under the new `x`, and every use restarts the 48 h of the path record and of its key lock.
- One live ACK per locked key and report, one per timed slot, at most 120 an hour, outside the command airtime budget. A command for the same report or slot goes 300 ms after the ACK.
- After each ACK an ephemeral kind 24244 "ack sent" note: `["y", x]`, `["ack", "1"|"2"]`, `["e", entry]`, for a routed one `["via", x of the path record it used]` (the app names it as `via` in its next routed entries), and `["at", at]` for a timed one. The 120 an hour count only ACKs that went on air.

**Timed entries.** `["at", t]` and one `["via", x]` per copy of the anchor. The bridge uses the `via` it heard; one it never heard means it is not near the tag, and it sends nothing. At `at` it sends by the plan for that `via` (commands get the claim round first). An entry counts as sent only when the radio took the frame. On a radio error it tries again every 2 s until `at + 20 s`. When an entry is not sent it publishes a **miss note** (kind 24244, `["e", id]`, `["g", g]`, `["at", at]`, `["miss", radio | blocked | late | no_path]`), so the app shows "not sent". `no_path` only for an entry that names this bridge in `p`; of an open entry other bridges hold copies. At most one timed command per `via` per 10 minutes (not for ACKs) and 20 an hour. A **blind** LOST entry (`["blind", "1"]`, naming this bridge in `p`) floods at `at` in the country scope (the last of `[radio] regions`) without `via` heard: at most 3 per `via` and 6 in all per 24 h.

**Groups.** A live report whose `x` is a `y` of an untimed entry of group `g` is the tag's answer, however it arrived, when something of the group came before it (a send, or the `via` of a timed entry) and it belongs to another entry than those: the report an untimed entry waits for is not an answer to that entry. A sibling copy (the same entry as a heard `via` or as the report a send was for) counts only 120 s or more after it, and never for a `via` the bridge did not hear. The bridge then drops the group's unsent timed entries, cancels its fallbacks and publishes a done claim (`["g", g]`, `["done", x]`). A done claim from another bridge counts only with this bridge's own evidence of report `x`.

**Bridge agreement.** Claims (kind 24244, `["y", x]`, `["hops", h]`, a hearing proof for untimed entries) are published without waiting for a relay; 1.5 s round, fewest hops wins, then the smallest `SHA-256(pubkey || x)`. Only claims from peers or announced bridges with a valid proof count (PROTOCOL.md 4.1).

**Reply scope.** A flood that answers a report goes in that report's scope: the name the node resolved (`scope`), or the first of `[radio] regions` whose transport code matches the report's `code[0]` (the channel envelope has no nonce, so the bridge rebuilds the GRP_DATA payload from `P`). An unscoped report gets an unscoped reply, a DIRECT arrival the narrowest region, and a scope the bridge cannot name the narrowest region too (least mesh load). The fallback flood needs a `g`: an entry without one gets none. The hourly cap on open floods (`open_floods_per_hour`) is checked before the claim round, and a fallback flood of an open entry counts in it.

**Announcement** (kind 38244, with a bridge key): at start, every hour and at once when the radio state changes, with one `["region", …]` per configured scope and `["radio", "up" | "down", "<since>"]`.

**Cancelling (NIP-09).** A kind 5 signed by the 4244 event's own key, with `["e", id]` and `["k", "4244"]`, drops an unsent entry and its armed fallback (`lt_mailbox_cancelled`); one already sent stays sent (`lt_mailbox_cancel_too_late`). Deletions are remembered, so a 4244 that arrives after its deletion is not added.

**Local API.** The protocol is [MeshCore Local API](https://github.com/jooray/meshcore-local-api) (protocol 1), through the `meshcore-localapi` client. Capabilities are strict: a feature is used only when `hello.capabilities` lists it. The bridge subscribes to `#libretag` with data types `0xFF4C` and `0xFF6C`, so it also hears other bridges' fallback floods. At start it warns about each missing capability that matters (`tx_path_data`, `rx_meta`, `rx_path`, `rx_scope`, `rx_queued`). Each name in `[radio] regions` is checked against `regions.list`, and an unknown one is dropped.

**Limits.** Counters with the prefix `lt_spam_` count what was refused as abuse.

| Rule | Default | Counter |
|---|---|---|
| `created_at` at most 60 s ahead | `future_max_s` | `lt_spam_future` |
| `at` between `heard_at(via)` and `+ 25 h` | `timed_max_after_report_s` | `lt_spam_timed_window` |
| ≤ 12 unsent timed entries per `via` | `timed_per_via_max` | `lt_spam_evicted` |
| full mailbox: oldest inserted go first; one source relay ≤ 34 % | `mailbox_cap`, `mailbox_relay_share` | `lt_spam_evicted` |
| one timed command per `via` per 10 min, 20 an hour | `timed_via_gap_s`, `timed_per_hour` | `lt_cmd_timed_via_gap`, `lt_cmd_timed_hourly_cap` |
| ACKs per hour | `acks_per_hour` (120) | `lt_ack_hourly_cap` |
| an identical `Pc` or `Pa` once (also when heard on air) | | `lt_cmd_dup_payload`, `lt_ack_dup_on_air` |
| new `#libretag` packets per minute, and per first repeater | `rx_per_min` (30), `rx_per_first_hop_per_min` (10) | `lt_rx_rate_limited`, `lt_rx_first_hop_limited` |
| `#libretag` command airtime, timed entries at most half of it; open floods 6 an hour | `airtime_pct`, `timed_airtime_share`, `open_floods_per_hour` | `tx_airtime_exhausted`, `lt_cmd_timed_airtime_share`, `lt_cmd_open_flood_cap` |

**Statistics.** Per report heard: `lt_rx_route_<route>` and `lt_rx_hops_<0|1|2|3plus|unknown|direct>`. Per path send: `lt_path_cmd_confirmed` / `lt_path_cmd_unconfirmed` (an answer within the fallback wait) and `lt_path_ack_confirmed` (a routed ACK entry later named the path's `x` as its `via`, so the tag used the path). The field log has the same as `path_use` lines.

**Configuration:** `[libretag]` and `[radio] regions`, see `host/bridge.example.toml`. `max_hops` and `ack_window_s` are gone and only logged; `[radio] region` is gone and refused, use `regions = ["<area>", "<country>"]`.

### 8.10 Staying up (Python)

- **Outbox.** Every event the bridge publishes (signed frames from the radio, LT2 reports) goes into the ledger's `outbox` first and leaves it when a relay accepts it. If none does, `tick` retries it with growing pauses (15 min to 1 h) until it expires: its `expiration` tag, at most `[ledger] outbox_ttl_s` (14 days). A restart keeps the outbox.
- **Radio health** (`radio_health.py`, SPEC 3.2 "Radio health"). The bridge watches the radio itself, not the session to the node service. The radio is down when the adapter says so (Local API `device.connected` false, or the session not paired; a broken companion or KISS link), when a send comes back `not_connected` or two sends in a row time out, when two probes in a row go unanswered, or when nothing came from the radio for `[watchdog] rx_silence_s` (30 min): a received packet, or the answer to a probe every `probe_s` (5 min; `radio.stats` on the Local API, a node request on companion serial, every minute while down). A node service that reports late answers or unanswered heartbeats in `radio.stats` (`link.lateReplies`, `link.slowHeartbeats`) makes the radio `degraded`. On `radio_down` the bridge logs the reason, publishes its announcement with `radio down` at once, answers due timed entries with a miss note (`radio`) and runs `[watchdog] alert_cmd`. It does not exit. Recovery runs one round per back-off (5, 15, 60 min, then every hour), each step once per round with `step_wait_s` between: reboot by command while the node still answers, reopen the port, the RTS/DTR reset (both done by meshcored itself for a Local API node), then `[watchdog] recover_cmd`. After the third round the alert hook runs once more with `power_cycle_needed`. A node degraded for 10 minutes while it still answers is rebooted by command. On `radio_up` the alert hook runs again and the announcement says `up`. The hooks get `NOSTRMESH_RADIO_STATE` (`down`, `up`, `recover`, `power_cycle_needed`), `NOSTRMESH_RADIO_REASON` and `NOSTRMESH_BRIDGE` in the environment.
- **Serial port.** `CompanionSerial` treats a read or write error, or three unanswered requests in a row, as a lost link: it reopens the port and repeats the handshake after 1, 2, 5, 10, 30, 60 s. The KISS receive thread logs an error and reopens instead of dying.
- **Heartbeat.** Every `[status] interval_s` (1 h) the bridge logs one line and writes `[status] path` (default `~/.config/nostrmesh/status.json`): last hour and totals, airtime per budget, radio silence, the radio state (`up`/`down`, since, reason, degraded), mailbox and outbox size, all counters. `nostrmesh status [-c bridge.toml | --path FILE] [--json]` prints it and exits 1 when the file is older than two intervals.
- **Inbox relays.** Kind 10002/10050 lists count only with a valid signature by the recipient, and a host name must resolve to public addresses only.
- **NIP-42.** With `[relays] auth_key` the pool answers `AUTH` challenges, re-sends its subscriptions after a successful AUTH and retries a publish refused with `auth-required:` once.

### 8.11 Run it as a service

`nostrmesh run` expects a supervisor that restarts it after a crash. A dead radio does not make it exit (8.10). `host/deploy/` has two templates. Nothing in the repo installs them.

1. Check out the repo and install the environment: `cd host && uv sync --frozen`.
2. Copy `host/bridge.example.toml` to `~/.config/nostrmesh/bridge.toml` and set the radio port or Local API URL, the relays, and `[airtime]` to match your node. Start it once by hand (`uv run nostrmesh run -c ~/.config/nostrmesh/bridge.toml`) to approve the pairing in MeshCore Open or to check the serial port.
3. Linux, systemd user unit: copy `host/deploy/nostrmesh.service` to `~/.config/systemd/user/`, fix the checkout and `uv` paths in it, then run `systemctl --user daemon-reload`, `systemctl --user enable --now nostrmesh` and `loginctl enable-linger $USER` so it keeps running after you log out. The unit restarts the bridge 30 s after a failure and never stops retrying. Log: `journalctl --user -u nostrmesh -f`.
4. macOS, launchd user agent: copy `host/deploy/local.nostrmesh.plist` to `~/Library/LaunchAgents/`, replace `/Users/YOU` with your home directory (launchd does not expand `~`), then run `launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/local.nostrmesh.plist`. `KeepAlive` with `SuccessfulExit = false` restarts it after any failed exit, at most every 30 s (`ThrottleInterval`). Log: `~/Library/Logs/nostrmesh.log`.
5. Check it: `uv run nostrmesh status -c ~/.config/nostrmesh/bridge.toml` prints the last heartbeat and exits 1 when it is older than two hours, so a cron job can alert on it.

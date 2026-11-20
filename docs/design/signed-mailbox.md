# Signed mailbox: entries a stranger cannot forge, with no extra bytes on air

Status: implemented 2026-11-18 (firmware 0.9.0, app 0.11.0, PWA 0.3.0, Python bridge 0.5.0, Android bridge; PROTOCOL.md 2 and 4.2 are the reference). Owner decisions: no backward compatibility (v1 sealing of copies 0 and 1, format 1 entries and proof of work are gone), strict from the start, mac 6 B, anchors always on, late entries. What was built differs from the text below in these points:

- One `data_type` (`0xFF4C`). With v1 gone every 45 B report on air is v2, so a bridge is strict without a new type.
- Proof of work is removed, not kept as a fallback: nothing unsigned is ever sent, and work never priced anything for a GPU. Storage is bounded by `keys_per_y` (8 keys per tag, first seen stay), the mailbox and anchor caps, the per-relay share and targeting.
- A bridge refuses an entry for a report it already heard when the key can never lock (neither locked nor known `anchor_lead_s` before), and drops stored ones of such keys. Each locked key gets one transmission per report, so a copy of `P` with a changed `c` cannot push the owner's command out.
- Anchor expiration: three report intervals (`next_in`), at least 2 h, at most 26 h, renewed at half life; the counters are the next 8 and the start of the next epoch.
- History records, the Wi-Fi packet and BLE packets keep the plain seal (type byte, 8 B mac), as proposed.

The analysis follows unchanged.

Analysis, 2026-11-18. It builds on `docs/design/bridge-market.md` (gaps 1 to 6, quick fixes Q1 to Q7, step B) and on PROTOCOL.md 2 to 4. Labels, tag names and constants are proposals.

Not verified: GPU rates (general knowledge: about 2 × 10⁹ secp256k1 keys per second and about 10¹⁰ PBKDF2-SHA256 iterations per second on one top card), the time of one public key computation on the nRF52840 (estimated 20 to 50 ms, to be measured), flood latency beyond the figures in `docs/research/flood-latency.md`. Airtime figures come from `lt_lora_quarter_symbols`.

## Short answers

- **Recommended:** report sealing v2. Drop the constant type byte, cut the mac from 8 to 6 B, and put a 3 B commitment `c` to a per-report key `pk_n` in the freed space. The app signs every 4244 event for report `n` with `sk_n`. A bridge trusts a key only if it had an event from that key before the report was on air ("anchor").
- **On-air cost:** 0 bytes. The report stays 45 B, the command stays 29 B.
- **Security:** a forger gets one blind guess with chance 2⁻²⁴ per event stored at a bridge before the report. Search does not help, because `c` depends on the ciphertext, which does not exist earlier. Report integrity goes from 2⁻⁶⁴ to 2⁻⁴⁸ per forged report.
- **The 8 B proposal really costs 16 B.** The channel plaintext is exactly 3 AES blocks today. Any 1 to 16 extra bytes add one block: 51 B becomes 67 B payload, +22 % airtime on every hop.
- **Control shares work, view shares do not.** `sk_n` comes from `C_e`. The share format does not change.
- **The tag** does one public key computation per report. It already links libsecp256k1 (`CONFIG_LIBRETAG_NOSTR`, default on).
- **Old tags and old apps** keep today's rules (work, first seen, step B). Old bridges keep relaying v2 reports.
- **Is it worth it:** yes, but after Q1, Q5 and step B. It is the only fix for gap 2 that needs no payment, and it makes late entries possible.

## 1. What a bridge needs

A bridge has no key of the tag. It hears `P` on the radio and reads events from relays. To tell the controller of the tag from a stranger it needs a value that:

1. is public, in or next to the report it heard,
2. only a holder of `C_e(n)` can answer for,
3. binds the whole event, so a copy with other content fails,
4. links no two reports.

Point 3 rules out revealed secrets (hash chains, one-time preimages): a revealed secret can be copied into another event. A signature binds the content and costs nothing on air, because every Nostr event already carries one. Today the signing key is random. The whole question is how a bridge learns which key belongs to report `n`.

```
sk_n = HMAC(C_e(n), "LT2-mb" || u32(n)) mod N      (0 is replaced by 1)
pk_n = sk_n · G, x-only (BIP-340), 32 B
```

Every 4244 event for report `n` has `pubkey = pk_n`: untimed entries with `y = x(n, j)`, timed entries with `via = x(n, 0)`, anchors (3.2), and the NIP-09 deletions. The keys of two reports are unrelated HMAC outputs, so they link nothing. The app no longer has to store a random key per event.

## 2. Why "8 B" means 16 B

`lt_channel_wrap` encrypts `data_type (2) || len (1) || P` in 16 B blocks. With `P` = 45 B that is 48 B, exactly 3 blocks, no padding. One more byte in `P` gives 4 blocks.

| | Payload | Packet with region | SF7 / 62.5 kHz / CR 4/5 | SF8 / 62.5 kHz / CR 4/8 |
|---|---|---|---|---|
| today | 51 B | 57 B | 232 ms | 607 ms |
| `P` + 1 to 16 B | 67 B | 73 B | 283 ms | 738 ms |

So a commitment of 4, 6 or 8 B costs the same 16 B and the same +22 % airtime, at the tag and at every repeater that forwards the flood. If the owner ever pays for a block, it should carry 8 B or more. The question "is 4 to 6 B enough" only matters for bytes that are already there.

Stealth transport is different: its plaintext has 6 zero bytes of padding (39 B). Bridges do not serve stealth reports today, so this is not used below.

## 3. Options compared

"Late search" is the work to find a key that matches the commitment after the report is public. It is what an attacker needs when no anchor rule protects the entry. `c` is always bound to the ciphertext (section 4), so no table can be built in advance.

| Option | Extra on air | Commitment | Late search, one GPU | Needs anchor | Notes |
|---|---|---|---|---|---|
| A. Today plus Q1, Q5, B | 0 | none | none needed | | first seen and targeting only |
| B. Proposal on the table: `H(pk_n)[0:8]` appended | 16 B | 64 bit | about 290 years | no | simple, no timing rules |
| C. Same, 6 B appended | 16 B | 48 bit | about 39 h | for timed entries | same airtime as B, weaker |
| D. Same, 4 B appended | 16 B | 32 bit | about 2 s | yes | pointless |
| **E. v2 sealing: type byte and 2 B of mac become `c`** | **0** | 24 bit | milliseconds | **yes** | recommended |
| F. E with mac 5 B, `c` 4 B | 0 | 32 bit | about 2 s | yes | report integrity 2⁻⁴⁰ |
| G. E plus a slow hash (2¹⁶ iterations) | 0 | 24 bit × 2¹⁶ | about 2 min | mostly | tag 2 to 3 s CPU per report, bridge 65 ms per candidate |
| H. `x` derived from `pk_n` | 0 | 32 bit | about 2 s, or a table | yes | breaks view shares and lookup, see 7 |
| I. Blind `y` for a bridge key, no firmware change | 0 | none, 32 bit secret | not applicable | yes | old tags too; view shares can also produce entries, see 8 |

What "late search" has to beat: 60 s for a late entry in the listen window, up to 25 h for a timed entry. Option C holds 60 s against about 2,000 GPUs but loses to two GPUs over 25 h. Only B is safe without any timing rule. E is not safe against late search at all, and does not need to be, because of the anchor rule.

The trade is plain: B pays 16 B per report forever and needs no anchor. E pays nothing on air and needs the app to publish small events ahead of the reports.

## 4. Recommended scheme (E)

### 4.1 Report sealing v2

The type byte `0x21` is constant. The lookup of `x` already gives `(n, j)`, and `j` gives the kind of packet (0 and 1 a report, 4 a Wi-Fi packet). So the byte carries nothing.

```
pt'   = report plaintext without byte 0                      # 32 B
kd    = HMAC(V_e(n), "LT2-r2" || u32(n) || u8(j))
k_enc = kd[0:16], k_mac = kd[16:32]
x     = HMAC(V_e(n), "LT2-x" || u32(n) || u8(j))[0:4]        # unchanged
mac   = HMAC(k_mac, x || pt')[0:6]
ct    = AES-128-CTR(k_enc, IV = mac || 10×0x00, pt')
c     = SHA-256("LT2-mbc" || pk_n || x || ct || mac)[0:3]
P     = x || c || ct || mac                                  # 4 + 3 + 32 + 6 = 45 B
```

| Offset | Size | v1 | v2 |
|---|---|---|---|
| 0 | 4 | `x` | `x` (same derivation) |
| 4 | 3 | ciphertext | `c` |
| 7 | 32 | ciphertext | `ct` (fields of bytes 1 to 32 of the v1 plaintext) |
| 39 | 6 | ciphertext, mac | `mac` |

- Same length, same `data_type`, same `x`. Relay filters, `ReportWindow`, the bridges' uplink and kind 4243 do not change. An old bridge relays a v2 report without knowing.
- The app opens a v2 report with the `LT2-r2` keys and a v1 report with the old ones. For a tag whose firmware it does not know (a share) it tries both. The mac decides.
- `c` is not under the mac, because it is computed from it. Only bridges read `c`. Section 6 covers a changed `c`.
- mac 6 B: a forged report passes with 2⁻⁴⁸ per attempt, and each attempt has to reach the app through a relay or the radio. SIV still holds: the key is per `(n, j)`, the IV only has to differ for different content under that one key.
- The Wi-Fi packet (`j = 4`), BLE packets and the history records (`j = 2, 3`) keep v1 sealing. No command answers them.
- Command `Pc`, the Bluetooth command advert and the check schedule do not change.

### 4.2 Anchors

An anchor is any 4244 event signed by `pk_n` that names `y = x(n, j)` and reached the bridge before the report. An untimed command entry is one already. When no command waits, the app publishes an empty one:

```
kind 4244, pubkey pk_n, content ""
tags: ["y", hex(x(n,0))], ["y", hex(x(n,1))], ["expiration", ...], ["nonce", ..., "16"]
      plus the "p" / "r" target tags of step B
```

Old bridges drop it (content is not 29 B). The app keeps anchors for the next reports it expects: by default the next 8 counters, plus the counters a reboot or a long quiet stretch can produce (the saved counter and the start of the next epoch, `lt_boot_counter`, PROTOCOL.md 2.1 rule 1). It refreshes them whenever it syncs. No `g` tag, so anchors of different reports share nothing but their timing. The app should spread their `created_at` and, where it can, their publishing.

### 4.3 Bridge rules

When a bridge first hears `P` (45 B, live, on its own radio) at time `t`:

1. Take every stored 4244 event with `y = P[0:4]` that the bridge knew at `t − Δ` or earlier. `Δ` = 60 s by default. If a kind 4243 with this `y` reached the bridge from a relay before the radio did, `t` is that earlier time.
2. For each, check `SHA-256("LT2-mbc" || event.pubkey || P[0:4] || P[7:45])[0:3] == P[4:7]`. One hash per event.
3. A key that passes is **locked** for this `x`. More than one key may lock.
4. If a lock exists, only events from locked keys count for this report: untimed (`y = x`), timed (`via = x`) and late ones. Everything else that names this `x` is dropped on arrival. Locked entries go first in the send order and need no more than 16 bits of work, whatever their lead.
5. If no lock exists, the bridge applies today's rules to this report (work, first seen, limits, step B). A bridge may be set to strict, see 6.

**Late entry.** A 4244 event with `y = x`, signed by a locked key, that arrives after the report. The bridge transmits it at once if the report was heard at most `late_max_s` ago (default 60 s; the tag listens 90 s). The app publishes one only while the tag's `listen_s` has not run out. This lifts today's "known before the report" rule for locked keys only.

**Timed entry.** Signed by `pk_n` of its `via` report. The per-`via` limits (12 unsent, one per 10 min) now apply per locked key, so junk cannot take the owner's slots.

**Deletion.** NIP-09 as today, signed by the same key, which is now `pk_n`.

`Δ` is there because a flood takes seconds. An attacker near the tag hears `P` first, finds a key in milliseconds and publishes it before a distant bridge hears the flood or the tag's repeat after 8 s. An event younger than `Δ` therefore proves nothing.

### 4.4 What a forger faces

- Before the report, `ct` and `mac` do not exist. A key cannot be chosen to match `c`. Each stored event is one guess with chance 2⁻²⁴. Filling a whole 10,000 tag mailbox with guesses for one `y` gives 0.06 % per report, and that flood is a bigger problem than its result.
- After the report, a matching key takes 2²⁴ trials, under a second. It is worthless: the bridge did not know it `Δ` before.
- Copying the owner's `y` into an own event (gap 1) or outbidding the owner's timed entries with work (gap 2) fails as soon as the owner's key is locked. Junk for a locked report (gap 5) is dropped.
- The owner's anchor shows `pk_n` and `y` in advance. That reveals nothing about `c`.

## 5. Shared control, view shares, revocation

| Who | Holds | Can compute `x` | Can derive `sk_n` | Gets a key locked |
|---|---|---|---|---|
| Owner | `K0` | yes | yes | yes |
| View and control share | `V_e`, `C_e` from epoch `e` | yes | yes, for `n ≥ 64e` | yes |
| View only share | `V_e` | yes | no | no, see below |
| Bridge, stranger | nothing | no | no | no |

- The share format does not change. A control share already carries `C_e`, and `sk_n` is derived from it like the command keys.
- Owner and control share derive the same `pk_n`. A bridge cannot tell them apart, both can queue and both can delete the other's entries. That matches "all commands including Lost".
- A view share can publish an event that names `y`, but its key does not match `c`. One residual case: a view holder can open reports, so for a tag that stands still without a fix it can guess the next plaintext, compute `ct` and `mac` and fit a key to it. Each guess is one event. The prize is one junk packet on air. The tag still rejects it, because `Pc` needs `C_e`.
- Revocation stays what it is: a new key over USB (`newkey`). The ratchet does not revoke, the share ratchets forward too. A stolen tag holds the current `C_e` and can sign for future reports, as it can already forge commands for itself. Past `sk_n` are gone with the old epoch.

## 6. Interactions

- **Unlinkability.** `c`, `ct` and `mac` change with every report. `pk_n` links only events that `y`, `via` and `g` link already. Standing anchors add one thing: several events published in one burst. Relays and anyone watching `created_at` can group them. Spread them.
- **Changed `c` on air.** Anyone with a radio can re-send `P` with another `c` that fits an own key posted `Δ` earlier. The bridge checks stored keys against every variant it hears, so the owner's key still locks on the real `P` and is not displaced. The attacker gains one junk packet through the bridge, which costs him a transmission of his own. The same holds for jam and replay. Bridges never take `c` from a relay copy.
- **First seen, claims.** Q1 and Q5 stay for reports without a lock. Claim jamming (gap 3) is not touched by signatures. It needs bridge keys (Q2, B.1).
- **Step B.** Anchors carry the same `p` and `r` tags as entries, so only local bridges store them. A bridge announcement should state that it locks keys, its `Δ` and `late_max_s`, and whether it is strict. Receipts (4245) tell the app which bridges to anchor at.
- **Mailbox eviction (gap 4).** Anchors can be evicted like entries. An attacker who empties the mailbox pushes the owner back to today's rules for the next reports. Q4 and the `r` filter of B.2 are still needed. Anchors should have their own table and cap, since a row is only `y`, pubkey and two times.
- **Strict mode.** A bridge cannot tell a v2 report from a v1 report, so without a lock it must fall back to today's rules, and junk for unanchored reports (gap 5) stays possible. A new `data_type` for v2 reports would let a bridge refuse everything unlocked for them. Cost: old bridges would ignore v2 reports, and an owner whose anchors ran out would wait for the next report. Not proposed for the first version.
- **Old tags, old apps.** Their events never lock (chance 2⁻²⁴). They run on work and first seen as now. A new app with an old tag may sign with `sk_n` anyway, it changes nothing. A share holder needs an app that opens v2 before the tag is updated.
- **After a reboot.** The cheapest fix is already available: untimed entries (and anchors) for the counters a reboot can produce. Late entries then cover the rest.
- **Work.** For locked keys the lead table (20 to 26 bits) can go. The phone saves up to a minute per command.

## 7. Zero-byte ideas that do not work

- **`x` as the commitment (`x = H(pk_n)[0:4]`).** A view share must compute `x` and must not get `sk_n`. That needs public key derivation per epoch and per report, a new share format, and an elliptic curve operation for each of about 4,600 window tags in the app and the PWA. `x` must be known in advance, so it cannot depend on the ciphertext, and a table of 2³² keys then forges every tag at once. Too much change for 32 bits.
- **Binding `x` to the ciphertext.** Same reason: the app subscribes to `x` before the report exists. Binding works for `c`, which nobody has to predict.
- **Revealed secret or hash chain.** The reveal is public once published and can be copied into another event. It does not bind the content. A low-entropy secret behind a hash is also searchable.
- **A MAC the bridge checks after the tag's answer.** The bridge has already transmitted by then. It cannot read `CMD_ACK` either. Useful for a bridge's reputation, not as a gate.
- **A pre-published owner key without `c`.** Anyone can copy the `y` tags into an event of their own. The bridge then sees two keys and no way to choose. It needs `c` (section 4) or blinding (section 8).
- **The tag's Nostr key `dm_sk`.** One fixed npub would link every report. It also comes from `K0`, not from the command chain, so shares could not use it.
- **Hash-based one-time signatures instead of secp256k1.** Same cost on the tag (about a thousand hashes), but `pk_n` signs many events. Not one-time.

## 8. Without a firmware change: blind `y` (option I)

For tags that stay on v1. It needs the bridge keys of step B.

- The app does not publish `y`. For each target bridge `B` it publishes `["yb", hex(HMAC(ECDH(sk_event, pk_B), "LT2-yb" || x)[0:8])]`.
- The bridge computes the shared secret when the event arrives. For each report it hears it runs one HMAC per stored blind tag. With mailboxes filtered by `p` that is a few thousand at most.
- A stranger never learns `y` before the report and cannot copy the tag under another key. A key whose blind tag matched `Δ` before the report is locked, and the rules of 4.3 apply.
- Limits: it proves knowledge of `x`, which a view share also has. It works only at bridges the app named in advance, so not for a lost tag in an unknown place. A named bridge can search the 32 bit `x` and forge anchors at other bridges.

Worth building only if v2 sealing is refused or delayed. Otherwise update the tags.

## 9. Cost on the tag and elsewhere

- **Tag:** one `lt_secp_pubkey` and one SHA-256 per report (not per copy). Estimated 20 to 50 ms on the nRF52840, less on the ESP32-S3. Against a GPS search of tens of seconds it does not show in the energy count. No new code size: libsecp256k1 is linked for the low-battery DM. A build with `CONFIG_LIBRETAG_NOSTR=n` cannot do v2.
- **App and PWA:** derive `sk_n`, sign with it instead of a random key, open v2 reports, keep anchors, publish late entries.
- **Bridges:** one hash per stored event of a heard `y`, a lock table, `Δ`, the late rule, per-key timed limits.
- **Nothing** for relays and repeaters.

## 10. Is this the right direction

- **Rate limits and airtime budgets** cap the damage per bridge. They cannot tell the owner from a stranger, so a stranger can still take the owner's slot. They stay.
- **Paid downlink** (C.3) prices volume. It does not say whose tag it is: without a lock a payer can still buy junk against a real `via`, and the owner must outbid. With a lock a bridge sells airtime for a report only to its key. The two fit together.
- **Receipts and bridge keys** (B.1) judge bridges, not senders.
- **Signatures** are the only one of these that answers "is this the tag's controller". They close gaps 1, 2 and 5 for anchored reports and allow late entries. They do not close gaps 3 and 4.

Not overkill at zero bytes, but not first. Q1 and Q5 remove the cheapest attack this week. Step B gives bridges an identity. v2 sealing belongs in the next firmware that touches the report anyway. Option B (16 B on air) buys only freedom from anchors and is not worth 22 % airtime.

Effort, about 12 days:

| Part | Days |
|---|---|
| `lt_core` v2 seal and open, `sk_n`, firmware, vectors (`reports_v2`, `mailbox_keys`) | 2 |
| Python reference and bridge (lock, `Δ`, late, per-key limits), vectors `mailbox_lock` | 3 |
| Android bridge | 2 |
| App: v2 open, signing, anchors, late entries | 3 |
| PWA | 1.5 |
| PROTOCOL.md, bench and field test (time of `lt_secp_pubkey`, flood latency against `Δ`) | 1 |

Open points for the owner: mac 6 B is acceptable or not; the anchor horizon (8 reports) and whether anchors run when no command waits; strict mode with a new `data_type` later.

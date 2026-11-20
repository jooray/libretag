"""LibreTag LT2 in the universal bridge (PROTOCOL.md 4, SPEC 6.2 variant B). Needs no tracker key.

Uplink: a 45 B report P heard on #libretag (data_type 0xFF4C) becomes a kind 4243 event,
tags ["y", hex(P[0:4])] and ["expiration", now + 14 d], content base64(P), signed with a fresh random
key. The bridge waits 0-5 s and does not publish when the same y with the same content is already on
`dedup_min_relays` relays (comparing content too, so a stolen tag with junk cannot silence it).

Command mailbox: kind 4244 events (content base64(Pc), 29 B; a ["y", x] tag per report copy; ["v", "2"]
and the targeting tags of PROTOCOL.md 4.1; expiration, capped at 48 h) are kept in the ledger.

Signed mailbox (PROTOCOL.md 4.2, 2026-11-18): every report carries c = SHA-256("LT2-mbc" || pk_n || x ||
ct || mac)[0:3], a commitment to the mailbox key of report n, and the app signs every 4244 event for
report n with sk_n (only holders of the command chain have it). When the radio first hears a report P
live, every key that named x = P[0:4] at least `anchor_lead_s` before (an anchor, content "", or an
untimed entry) and fits c is locked for x. Only events from locked keys ever go out: untimed entries
for x, timed entries with via = x, and late entries (published after the report, sent at once while
the report is at most `late_max_s` old). Without a lock nothing goes out for that report. A key cannot
be chosen to fit c in advance (c depends on the ciphertext), so a stranger gets one 2^-24 guess per
event stored before the report; a key found after the report is worthless, because it was not here
`anchor_lead_s` before. There is no proof of work.

When the radio delivers a report whose x matches a command from a locked key known BEFORE the report
was heard, the command goes out once per key: zero-hop when the report was heard directly (and the
adapter can send zero-hop), otherwise as its route allows. Bridges agree first over Nostr:
each publishes an ephemeral kind 24244 claim (tags y, hops), waits ~1.5 s; fewest hops wins, then the
lowest SHA-256(pubkey || x). Only the winner transmits.

Timed entries (0.7 checks): a 4244 event may also carry ["at", unix s] and ["via", x of report n, j=0].
The bridge keeps the hop count of every report it catches for `report_path_ttl_s` (48 h), keyed by x
(only the 4-byte tag, hop count and path bytes). Only a bridge that caught report `via` sends the entry
(it is near the tracker): at `at`, zero-hop if it heard report `via` directly, else flood; never in
reaction to a report. The claim (tag y = the command's y) goes out `timed_claim_lead_s` before `at` and
is decided at `at`. Entries with `at` more than `timed_max_late_s` in the past when they arrive are
dropped. Without `at` nothing changes. A missed check is not fatal: each check has its own entry (own
y), so the next one goes out at its own time, and the plain entries wait for the next reports.

Cancelling: the app keeps the random key of each 4244 event it publishes. To cancel, it publishes a
NIP-09 deletion (kind 5, ["e", id] per event, ["k", "4244"]) signed by that same key. The bridge
subscribes to kind 5 with #k 4244 and drops an unsent entry when the deletion's pubkey equals the
event's pubkey (both plain and timed entries); a delivery waiting for its claim or its `at` checks
again before transmitting. A deletion is remembered in the ledger, so a 4244 event that arrives after
its deletion is not added. An entry already transmitted cannot be taken back; that is only logged.

Limits (PROTOCOL.md 4, "Limits"): created_at at most 60 s ahead; `at` no later than heard_at(via) +
25 h (checked on arrival when the bridge has heard `via`, and again before sending); at most 12 timed
entries per `via` (earlier seen stay); at most `keys_per_y` keys per report tag (first seen stay);
one timed transmission per `via` per 10 min and 20 an hour in all; an identical Pc goes out once; a
full mailbox evicts the oldest inserted, and one relay fills at most a third of it. #libretag has its own receive limit and airtime budget; before that limit, new packets
that entered the mesh through the same first repeater (or were heard directly) share
`rx_per_first_hop_per_min`, so one noisy repeater cannot use up the whole receive limit.

Groups (["g", 16 hex], all entries of one command): once the tracker has answered, the rest of the
group's timed entries are useless (they are bound to report n, and the tracker has moved on to n+1).
The bridge drops them when (1) it hears a report whose x is a `y` of an untimed entry of the group,
heard well after report `via` (it is the tracker's next report), or (2) within `ack_window_s` after
its own timed transmission of the group it hears a report the same way as report `via` (both
directly, or the same first hop and hop count). It then publishes a "done" claim (kind 24244,
["g", g], ["done", x], ["at", at]) that other bridges accept only with their own evidence of report x.
"""
from __future__ import annotations

import asyncio
import base64
import binascii
import hashlib
import json
import logging
import os
from collections import OrderedDict, defaultdict
from dataclasses import dataclass

from libretag.lt2 import (BLE_P_LEN, CMD_P_LEN, NOSTR_CLAIM_KIND, NOSTR_COMMAND_KIND, NOSTR_REPORT_KIND, REPORT_P_LEN,
                          TAG_LEN, commit_matches, report_time)

from pathlib import Path

from . import bech32, frames, market, secp
from .ledger import MailboxEntry
from .nostr import Event, is_lower_hex, sign_event
from .policy import RateLimit

log = logging.getLogger("nostrmesh.lt2")

REPORT_TTL_S = 14 * 86400
MAX_Y_TAGS = 16          # per mailbox event (one per copy j and a few counters ahead)
CLAIM_MAX_AGE_S = 30.0
UNKNOWN_HOPS = 255
TIMED_TICK_S = 0.5       # how often the timed scheduler looks for due entries
DELETION_KIND = 5        # NIP-09
MAX_DELETE_IDS = 64      # e tags looked at per deletion
GROUP_HEX = 8            # ["g", 16 lowercase hex]
ACK_AFTER_VIA_S = 120.0  # a report this soon after report `via` may be a copy of it, never its answer
BRIDGE_VERSION = "0.5.0"  # announced in kind 38244 (0.4: keys, receipts, targeted entries; 0.5: signed mailbox)
REJECTED_MAX = 4096     # mailbox events already turned down, remembered so a relay's resend is not counted again


def first_hop_key(dg) -> tuple | None:
    """Rate-limit key of a heard #libretag packet: ("direct",) when heard without a repeater,
    (hash size, first repeater hash) when the adapter reports the path, None when it does not
    (companion serial without a path: only the global limit applies)."""
    if getattr(dg, "hops", None) == 0:
        return ("direct",)
    path = getattr(dg, "path", None)
    if not path:
        return None
    return (getattr(dg, "path_hash_size", 1), bytes(path[0]))


def report_key(p: bytes) -> str:
    """Ledger id of a report: same y and same content <=> same key."""
    return "lt2:" + hashlib.sha256(p).hexdigest()


def claim_score(pubkey_hex: str, x: bytes) -> bytes:
    return hashlib.sha256(bytes.fromhex(pubkey_hex) + x).digest()


def _fresh_key() -> bytes:
    return secp.seckey_from_bytes(os.urandom(32))


def read_bridge_key(value: str) -> bytes | None:
    """[bridge] key: a file holding a hex key or nsec, or the value itself; None when unset."""
    if not value:
        return None
    p = Path(value).expanduser()
    if p.exists():
        value = p.read_text().strip()
    return secp.seckey_from_bytes(bytes.fromhex(bech32.to_hex(value)))


def _pubkey_hex(v: str) -> str | None:
    try:
        return bech32.to_hex(v)
    except (ValueError, KeyError):
        return None


def _b64(content: str) -> bytes | None:
    try:
        return base64.b64decode(content, validate=True)
    except (ValueError, binascii.Error):
        return None


def _y(v) -> bool:
    return isinstance(v, str) and is_lower_hex(v, TAG_LEN)


def _g(v) -> bool:
    return isinstance(v, str) and is_lower_hex(v, GROUP_HEX)


@dataclass
class Plan:
    how: str                 # zero-hop | direct | flood
    route: list[bytes] | None
    path_hash_size: int = 1


class LibreTagService:
    def __init__(self, bridge):
        self.b = bridge
        self.cfg = bridge.cfg.libretag
        self.sightings: OrderedDict[str, set[str]] = OrderedDict()  # report key -> relays that have it
        self.claims: dict[str, list[tuple[float, int, bytes, str]]] = defaultdict(list)  # y -> (t, hops, score, id)
        self.own_ids: OrderedDict[str, None] = OrderedDict()
        self._publishing: set[str] = set()
        self.rx_limit = RateLimit(self.cfg.rx_per_min, 60)
        self.rx_first_hop = RateLimit(self.cfg.rx_per_first_hop_per_min, 60)
        self.relay_reports: OrderedDict[str, int] = OrderedDict()  # y of verified 4243 on relays -> created_at
        self.timed_sent: dict[str, tuple[float, MailboxEntry]] = {}  # group -> (when, entry) of own timed tx
        # fingerprints of 4244 events already turned down: every relay reconnect sends the same events
        # again (`since` = mailbox TTL), and counting them again made lt_mailbox_bad and
        # lt_mailbox_timed_late grow with each reconnect instead of with each event
        self.rejected: OrderedDict[str, None] = OrderedDict()
        # PROTOCOL.md 4.1: the stable bridge key (None: claims under fresh keys, as before 2026-11-18)
        self.key = read_bridge_key(getattr(getattr(bridge.cfg, "bridge", None), "key", ""))
        self.pubkey = secp.pubkey(self.key).hex() if self.key else None
        self.peers = {h for h in (_pubkey_hex(v) for v in self.cfg.peers) if h}
        self.networks = [n for n in self.cfg.networks if market.is_network(n)]
        self.announced: dict[str, float] = {}       # bridge pubkey -> expiration of its announcement
        self.heard_p: OrderedDict[str, bytes] = OrderedDict()  # x -> report P heard on our radio (claim proofs)
        self.receipt_limit = RateLimit(self.cfg.receipts_per_hour, 3600)
        self.last_announce = 0.0

    # ------------------------------------------------------------ subscriptions

    def subscriptions(self, now: float) -> dict[str, list[dict]]:
        subs = {}
        if self.cfg.uplink and self.b.cfg.uplink.enabled:
            subs["nm-lt-report"] = [{"kinds": [NOSTR_REPORT_KIND], "since": int(now) - 600}]
        if self.cfg.mailbox:
            since = int(now - self.cfg.mailbox_ttl_s)
            if self.cfg.accept_open:  # format 1 entries are open too
                subs["nm-lt-cmd"] = [{"kinds": [NOSTR_COMMAND_KIND], "since": since}]
            else:  # only entries for this bridge or its networks (PROTOCOL.md 4.1)
                f = []
                if self.pubkey:
                    f.append({"kinds": [NOSTR_COMMAND_KIND], "#p": [self.pubkey], "since": since})
                if self.networks:
                    f.append({"kinds": [NOSTR_COMMAND_KIND], "#r": list(self.networks), "since": since})
                if f:
                    subs["nm-lt-cmd"] = f
            subs["nm-lt-ann"] = [{"kinds": [market.ANNOUNCE_KIND], "#k": [str(NOSTR_COMMAND_KIND)],
                                  "since": int(now - market.ANNOUNCE_TTL_S - 86400)}]
            subs["nm-lt-del"] = [{"kinds": [DELETION_KIND], "#k": [str(NOSTR_COMMAND_KIND)],
                                  "since": int(now - self.cfg.mailbox_ttl_s)}]
            if self.cfg.claim:
                subs["nm-lt-claim"] = [{"kinds": [NOSTR_CLAIM_KIND], "since": int(now) - 60}]
        return subs

    def prune(self, now: float) -> None:
        if self.cfg.timed_commands:
            self.b.ledger.report_paths_prune(now - self.cfg.report_path_ttl_s)
        for y in list(self.claims):
            self.claims[y] = [c for c in self.claims[y] if now - c[0] < 120]
            if not self.claims[y]:
                del self.claims[y]
        for g in [g for g, (t, _) in self.timed_sent.items() if now - t > self.cfg.ack_window_s]:
            del self.timed_sent[g]
        for pk in [pk for pk, exp in self.announced.items() if exp <= now]:
            del self.announced[pk]
        if self.key and self.cfg.announce and now - self.last_announce >= self.cfg.announce_interval_s:
            self.announce(now)

    def _own(self, eid: str) -> None:
        self.own_ids[eid] = None
        while len(self.own_ids) > 4096:
            self.own_ids.popitem(last=False)

    # ------------------------------------------------------------ radio

    def on_datagram(self, dg, now: float) -> None:
        stats, ledger = self.b.stats, self.b.ledger
        p = bytes(dg.payload)
        if len(p) == CMD_P_LEN:
            # a command on the air; if it is one we hold, another bridge (or the owner) delivered it
            ledger.payload_mark_sent(p, now)
            for eid in ledger.mailbox_by_payload(p):
                ledger.mailbox_mark_sent(eid, now)
                stats.inc("lt_cmd_heard_on_air")
            return
        if len(p) not in (REPORT_P_LEN, BLE_P_LEN):  # report or BLE packet (PROTOCOL.md 2.3)
            stats.inc("lt_rx_bad_length")
            return
        stale = bool(getattr(dg, "stale", False))
        if stale:
            # drained from the node's queue: heard at an unknown time, no earlier than the last drain
            lo = getattr(dg, "rx_time_min", None)
            if lo is None:
                kv = ledger.kv_get("radio_last_drained")
                lo = float(kv) if kv else None
            heard_at = lo if lo is not None and lo <= now else now
        else:
            heard_at = dg.rx_time if dg.rx_time is not None else now
        key = report_key(p)
        row = ledger.get(key)
        first = not (row and row["heard_on_radio"])
        if first:
            # one first hop (where the packet entered the mesh) cannot use up the whole #libretag limit
            fh = first_hop_key(dg)
            if fh is not None and not self.rx_first_hop.allow(now, fh):
                stats.inc("lt_rx_first_hop_limited")
                return
            if not self.rx_limit.hit(now):
                stats.inc("lt_rx_rate_limited")  # a flood of new packets on #libretag: own limit
                return
            if fh is not None:
                self.rx_first_hop.hit(now, fh)
        ledger.add(key, "radio", now, "lt2.report")
        ledger.mark_heard(key, now)
        x = p[:TAG_LEN].hex()
        if self.cfg.mailbox:  # timed entries and late entries need it
            ledger.report_path_put(x, dg.hops, dg.path if dg.hops is not None else None,
                                   dg.path_hash_size, heard_at)
        if first and not stale and len(p) == REPORT_P_LEN:
            self.heard_p[x] = p  # for the hearing proof of our claims and to check rivals' proofs
            while len(self.heard_p) > 2048:
                self.heard_p.popitem(last=False)
            if self.cfg.receipts and self.key and self.receipt_limit.hit(now):
                self.b._spawn(self._publish_receipt(x, dg.hops, heard_at))
        if stale:
            stats.inc("lt_rx_stale")  # never triggers a command (PROTOCOL.md 4)
        elif self.cfg.mailbox and first:
            # only the first hearing of P triggers (a late flood copy or the tag's retransmission does
            # not: an entry published after the report reached the relays could ride on it)
            if len(p) == REPORT_P_LEN:
                self._lock(p, heard_at, now)
            if self.cfg.group_ack and len(p) == REPORT_P_LEN:
                self._group_ack(x, dg, heard_at, now)
            self._mailbox_check(p, dg, heard_at, now)
        if not first:
            stats.inc("lt_rx_duplicate")
            return
        stats.inc("rx_lt2_report")
        if self.cfg.uplink and self.b.cfg.uplink.enabled and not (row and row["published_at"]) \
                and key not in self._publishing:
            self._publishing.add(key)
            self.b._spawn(self._publish_report(p, key, heard_at, approx_until=now if stale else None))

    # ------------------------------------------------------------ uplink (kind 4243)

    def _dedup_need(self) -> int:
        n = len(getattr(self.b.pool, "subscribe_urls", ()) or ()) or 1
        return max(1, min(self.cfg.dedup_min_relays, n))

    async def _publish_report(self, p: bytes, key: str, heard_at: float | None = None,
                              approx_until: float | None = None) -> None:
        """`approx_until` set: a stale frame, heard between `heard_at` (or unknown) and then (the drain).
        created_at is that latest possible time, so apps that query with `since` still see the event, and
        the tag ["approx", "<earliest>"] says the time is only a range (PROTOCOL.md 4)."""
        try:
            await asyncio.sleep(self._publish_delay())
            if len(self.sightings.get(key, ())) >= self._dedup_need():
                self.b.stats.inc("lt_up_dedup")
                return
            now = int(self.b.clock())
            tags = [["y", p[:TAG_LEN].hex()], ["expiration", str(now + REPORT_TTL_S)]]
            if approx_until is not None:
                heard = min(int(approx_until), now)
                earliest = int(heard_at) if heard_at is not None and heard_at <= heard else heard
                tags.append(["approx", str(earliest)])
            else:
                # created_at = when the radio heard it, not after the random delay: apps anchor the
                # tracker's check schedule on it (0.7), so it must be as close to the transmission as we know
                heard = int(heard_at) if heard_at is not None and 0 <= now - heard_at < 600 else now
            ev = sign_event(_fresh_key(), heard, NOSTR_REPORT_KIND, tags, base64.b64encode(p).decode(),
                            os.urandom(32))
            self._own(ev.id)
            await self.b.publish_durable(ev.to_dict(), key, "lt_published", now + REPORT_TTL_S)
        finally:
            self._publishing.discard(key)

    def _publish_delay(self) -> float:
        lo = min(self.cfg.publish_delay_min_s, self.cfg.publish_delay_max_s)
        return self.b.rng.uniform(lo, self.cfg.publish_delay_max_s)

    # ------------------------------------------------------------ identity (PROTOCOL.md 4.1)

    def _sign(self, created_at: int, kind: int, tags: list, content: str = "") -> Event:
        """Signed with the bridge key when there is one, else with a fresh key (bridges before 2026-11-18)."""
        return sign_event(self.key or _fresh_key(), created_at, kind, tags, content, os.urandom(32))

    async def _publish_receipt(self, x: str, hops: int | None, heard_at: float) -> None:
        await asyncio.sleep(self._publish_delay())
        ev = self._sign(int(heard_at), market.RECEIPT_KIND, market.receipt_tags(x, hops, int(heard_at)))
        self._own(ev.id)
        res = await self.b.pool.publish(ev.to_dict(), retry=False)
        self.b.stats.inc("lt_receipt_published" if any(ok for ok, _ in res.values()) else "lt_receipt_failed")

    def announcement(self, now: float) -> list[Event]:
        """Kind 38244, one per network served ("" when none is named)."""
        radio = self.b.radio
        preset = None
        node = getattr(radio, "preset", None)
        freq = getattr(radio, "freq_hz", None)
        if node and freq:
            preset = (freq, node[1], node[0], node[2])
        content = json.dumps({"name": getattr(self.b.cfg.bridge, "name", "") or "nostrmesh",
                              "version": BRIDGE_VERSION, "adapter": self.b.cfg.radio.adapter,
                              "zero_hop": bool(getattr(radio, "supports_direct", False)),
                              "direct": bool(getattr(radio, "supports_direct", False))}, separators=(",", ":"))
        out = []
        for net in self.networks or [""]:
            tags = market.announce_tags(net, int(now), region=getattr(radio, "region", None), preset=preset,
                                        relays=list(getattr(self.b.pool, "subscribe_urls", ()) or ()),
                                        accept_open=self.cfg.accept_open, receipts=self.cfg.receipts,
                                        anchor_lead_s=self.cfg.anchor_lead_s, late_max_s=self.cfg.late_max_s)
            out.append(self._sign(int(now), market.ANNOUNCE_KIND, tags, content))
        return out

    def announce(self, now: float) -> None:
        self.last_announce = now
        for ev in self.announcement(now):
            self._own(ev.id)
            self.b._spawn(self._publish_announcement(ev))

    async def _publish_announcement(self, ev: Event) -> None:
        res = await self.b.pool.publish(ev.to_dict())
        ok = sum(1 for good, _ in res.values() if good)
        self.b.stats.inc("lt_announced" if ok else "lt_announce_failed")
        log.info("announcement %s (network %r) accepted by %d relay(s)", ev.id[:12], ev.tags[0][1], ok)

    def _on_announcement(self, ev: Event, now: float) -> None:
        a = market.parse_announcement(ev, now)
        if a is None or not ev.verify():
            return
        if a["expires"] > self.announced.get(ev.pubkey, 0):
            self.announced[ev.pubkey] = a["expires"]

    def known(self, pubkey: str) -> bool:
        return pubkey in self.peers or pubkey in self.announced or pubkey == self.pubkey

    # ------------------------------------------------------------ relays

    def on_relay_event(self, ev: Event, url: str, now: float) -> None:
        if ev.kind == market.ANNOUNCE_KIND:
            self._on_announcement(ev, now)
        elif ev.kind == NOSTR_REPORT_KIND:
            self._on_report_event(ev, url)
        elif ev.kind == NOSTR_COMMAND_KIND:
            self._on_command_event(ev, now, url)
        elif ev.kind == NOSTR_CLAIM_KIND:
            self._on_claim(ev, now)
        elif ev.kind == DELETION_KIND and self.cfg.mailbox:
            self._on_deletion(ev, now)

    def _on_report_event(self, ev: Event, url: str) -> None:
        p = _b64(ev.content) if isinstance(ev.content, str) else None
        if p is None or len(p) not in (REPORT_P_LEN, BLE_P_LEN):
            return
        y = [t[1] for t in ev.tags if isinstance(t, list) and len(t) >= 2 and t[0] == "y"]
        if y != [p[:TAG_LEN].hex()] or not ev.verify():
            self.b.stats.inc("lt_relay_bad_report")
            return
        key = report_key(p)
        self.sightings.setdefault(key, set()).add(url)
        self.sightings.move_to_end(key)
        while len(self.sightings) > 5000:
            self.sightings.popitem(last=False)
        if isinstance(ev.created_at, int):
            y = p[:TAG_LEN].hex()
            # an ["approx", …] report counts with its earliest possible time
            seen = report_time(ev.created_at, ev.tags)[0]
            self.relay_reports[y] = max(seen, self.relay_reports.get(y, 0))
            self.relay_reports.move_to_end(y)
            while len(self.relay_reports) > 5000:
                self.relay_reports.popitem(last=False)

    @staticmethod
    def _fingerprint(ev: Event) -> str:
        """All fields, not the claimed id alone: a forged copy under a real event's id must not shadow it."""
        raw = [ev.id, ev.pubkey, ev.created_at, ev.kind, ev.tags, ev.content, ev.sig]
        return hashlib.sha256(repr(raw).encode()).hexdigest()

    def _on_command_event(self, ev: Event, now: float, url: str = "") -> None:
        ledger = self.b.ledger
        if ledger.mailbox_pending(ev.id) or ledger.mailbox_sent(ev.id):
            return  # the same event again (another relay, or a resubscription): already in the mailbox
        fp = self._fingerprint(ev)
        if fp in self.rejected:
            return  # turned down before and counted then
        if not self._take_command_event(ev, now, url):
            self.rejected[fp] = None
            while len(self.rejected) > REJECTED_MAX:
                self.rejected.popitem(last=False)

    def _take_command_event(self, ev: Event, now: float, url: str = "") -> bool:
        """Validate and store one 4244 event. False: turned down for good (counted once by the caller);
        True: added, or a case that may change later (cancelled, evicted, a group already done)."""
        stats, cfg, ledger = self.b.stats, self.cfg, self.b.ledger
        anchor = ev.content == ""  # PROTOCOL.md 4.2: names the next reports' tags, carries no command
        pc = b"" if anchor else _b64(ev.content) if isinstance(ev.content, str) else None
        if pc is None or (not anchor and len(pc) != CMD_P_LEN) or not is_lower_hex(ev.pubkey, 32):
            stats.inc("lt_mailbox_bad")
            return False
        tags = [t for t in ev.tags if isinstance(t, list) and len(t) >= 2 and isinstance(t[1], str)]
        ys = sorted({t[1] for t in tags if t[0] == "y"})
        if not ys or len(ys) > MAX_Y_TAGS or not all(_y(y) for y in ys):
            stats.inc("lt_mailbox_bad")
            return False
        tg = market.targeting(tags)
        if tg is None:
            stats.inc("lt_mailbox_bad")  # malformed, or without ["v", "2"] (format 1 is gone)
            return False
        if tg.p and self.pubkey not in tg.p:
            stats.inc("lt_mailbox_not_ours")  # names other bridges only
            return False
        if not tg.p and not cfg.accept_open:
            stats.inc("lt_mailbox_open_off")
            return False
        if tg.r is not None and self.networks and tg.r not in self.networks:
            stats.inc("lt_mailbox_other_network")
            return False
        route = tg.route
        g = next((t[1] for t in tags if t[0] == "g"), None)
        if g is not None and not _g(g):
            stats.inc("lt_mailbox_bad")
            return False
        cap = now + cfg.mailbox_ttl_s
        exp = next((t[1] for t in tags if t[0] == "expiration"), None)
        expires = min(int(exp), cap) if exp is not None and exp.isdigit() else cap
        if not isinstance(ev.created_at, int) or ev.created_at > now + cfg.future_max_s:
            stats.inc("lt_spam_future")
            return False
        if expires <= now:
            stats.inc("lt_mailbox_expired")
            return False
        at_s = next((t[1] for t in tags if t[0] == "at"), None)
        at = via = None
        if at_s is not None:
            via = next((t[1] for t in tags if t[0] == "via"), None)
            if not at_s.isdigit() or len(at_s) > 12 or not _y(via):
                stats.inc("lt_mailbox_bad")
                return False
            if anchor:
                stats.inc("lt_mailbox_bad")  # an anchor names reports, never a check
                return False
            if not cfg.timed_commands:
                stats.inc("lt_mailbox_timed_off")
                return False
            at = int(at_s)
            if at < now - cfg.timed_max_late_s:
                stats.inc("lt_mailbox_timed_late")
                return False
        # signed mailbox: once a report was heard, only a key that locked or still may lock (another variant
        # of P, PROTOCOL.md 4.2) can ever send for it
        if at is not None:
            if not self._may_lock(via, ev.pubkey, now):
                stats.inc("lt_mailbox_unsigned")
                return False
        else:
            live = [y for y in ys if self._may_lock(y, ev.pubkey, now)]
            if not live:
                stats.inc("lt_mailbox_unsigned")
                return False
            # at most keys_per_y keys per report tag, the first seen stay (the owner's anchor names y first)
            live = [y for y in live if ev.pubkey in (ks := ledger.keys_of_y(y)) or len(ks) < cfg.keys_per_y]
            if not live:
                stats.inc("lt_mailbox_y_full")
                return False
            ys = live
        if at is not None and not self._at_plausible(at, via, now):
            stats.inc("lt_spam_timed_window")
            return False
        if not ev.verify():
            stats.inc("lt_mailbox_bad_signature")
            return False
        if ledger.mailbox_cancelled(ev.id, ev.pubkey):
            stats.inc("lt_mailbox_cancelled_before")  # the deletion came first
            return True
        if at is not None and g is not None and ledger.group_done(g):
            stats.inc("lt_group_done_before")  # the tracker already answered this command
            return True
        if anchor:
            added = sum(ledger.anchor_add(y, ev.pubkey, ev.id, now, expires, url or None, cfg.mailbox_cap) for y in ys)
            stats.inc("lt_anchor_added" if added else "lt_anchor_again")
            return True
        if ledger.mailbox_add(ev.id, ys, pc, route, ev.created_at, expires, now, cfg.mailbox_cap, at, via, ev.pubkey,
                              0, url or None, g, cfg.timed_per_via_max if at is not None else None,
                              cfg.mailbox_relay_share, tg.fmt, " ".join(tg.p)):
            stats.inc("lt_mailbox_added_timed" if at is not None else "lt_mailbox_added")
            if at is None:
                for y in ys:
                    self._late(y, ev.pubkey, now)
        elif ledger.mailbox_pending(ev.id) or ledger.mailbox_sent(ev.id):
            pass  # the same event from another relay
        else:
            stats.inc("lt_spam_evicted")  # did not make it past the per-via, per-relay or total cap
        return True

    def _at_plausible(self, at: int, via: str, now: float) -> bool:
        """A1: a check comes after report `via` and before its heartbeat (24 h + 1 h). Unknown when the
        bridge has not heard `via` (it will send nothing for it anyway)."""
        rp = self.b.ledger.report_path(via, now - self.cfg.report_path_ttl_s)
        if rp is None:
            return True
        return rp.heard_at - self.cfg.timed_max_late_s <= at <= rp.heard_at + self.cfg.timed_max_after_report_s

    def _on_deletion(self, ev: Event, now: float) -> None:
        """NIP-09 kind 5 for mailbox events: cancels an entry signed by the same key (PROTOCOL.md 4)."""
        stats = self.b.stats
        tags = [t for t in ev.tags if isinstance(t, list) and len(t) >= 2 and isinstance(t[1], str)]
        ks = {t[1] for t in tags if t[0] == "k"}
        if ks and str(NOSTR_COMMAND_KIND) not in ks:
            return
        ids = list(dict.fromkeys(t[1] for t in tags if t[0] == "e" and is_lower_hex(t[1], 32)))[:MAX_DELETE_IDS]
        if not ids or not isinstance(ev.created_at, int) or ev.created_at > now + 300:
            return
        if not ev.verify():
            stats.inc("lt_mailbox_cancel_bad_signature")
            return
        for eid in ids:
            res = self.b.ledger.mailbox_cancel(eid, ev.pubkey, now, self.cfg.mailbox_cap)
            if res == "cancelled":
                stats.inc("lt_mailbox_cancelled")
                log.info("LT2 mailbox entry %s cancelled", eid[:12])
            elif res == "sent":
                stats.inc("lt_mailbox_cancel_too_late")
                log.info("LT2 mailbox entry %s cancelled, but it was already transmitted", eid[:12])
            elif res == "other_key":
                stats.inc("lt_mailbox_cancel_bad_key")

    def _on_claim(self, ev: Event, now: float) -> None:
        if ev.id in self.own_ids:
            return
        t = {x[0]: x[1] for x in ev.tags if isinstance(x, list) and len(x) >= 2}
        if "done" in t:
            self._on_done_claim(ev, t, now)
            return
        y, hops = t.get("y"), t.get("hops", "")
        if not _y(y) or not hops.isdigit() or not isinstance(ev.created_at, int) \
                or abs(now - ev.created_at) > CLAIM_MAX_AGE_S:
            return
        if any(c[3] == ev.id for c in self.claims.get(y, ())) or not ev.verify():
            return
        proof = t.get("proof")
        proof = proof if isinstance(proof, str) and is_lower_hex(proof, 32) else None
        self.claims[y].append((now, min(int(hops), UNKNOWN_HOPS), claim_score(ev.pubkey, bytes.fromhex(y)), ev.id,
                               ev.pubkey, proof))
        self.b.stats.inc("lt_claim_seen")

    def _on_done_claim(self, ev: Event, t: dict, now: float) -> None:
        """Another bridge says the tracker answered group g with report x. Accepted only when this bridge
        has its own evidence of report x (heard on its radio, or a verified kind 4243 on the relays)
        heard after the group's command could have been executed, and x either is a `y` of an untimed
        entry of g (the tracker's own next report) or came after the claimed transmission `at`."""
        stats, ledger = self.b.stats, self.b.ledger
        g, x, at_s = t.get("g"), t.get("done"), t.get("at", "")
        if not self.cfg.group_ack or not _g(g) or not _y(x) or not isinstance(ev.created_at, int) \
                or abs(now - ev.created_at) > CLAIM_MAX_AGE_S or ledger.group_done(g):
            return
        timed = [e for e in ledger.group_timed(g) if ledger.mailbox_pending(e.event_id)]
        if not timed or not ev.verify():
            return
        rp = ledger.report_path(x, now - self.cfg.report_path_ttl_s)
        seen = [rp.heard_at] if rp is not None else []
        if x in self.relay_reports:
            seen.append(self.relay_reports[x])
        own_y = ledger.group_has_untimed_y(g, x)
        at = int(at_s) if at_s.isdigit() else None
        if own_y:
            evidence = any(s_ <= now + 5 for s_ in seen)
        else:
            evidence = at is not None and at <= now and any(at - 5 <= s_ <= at + self.cfg.ack_window_s for s_ in seen) \
                and any(e.at <= at + 5 for e in ledger.group_timed(g))
        if not evidence:
            stats.inc("lt_group_done_claim_ignored")
            return
        n = ledger.group_close(g, now)
        stats.inc("lt_group_closed_by_claim")
        log.info("LT2 group %s answered (claim from another bridge): %d timed entries dropped", g, n)

    def _group_ack(self, x: str, dg, heard_at: float, now: float) -> None:
        """C: a fresh report that shows the tracker has answered (or at least moved past report `via`)."""
        ledger = self.b.ledger
        for g in ledger.groups_of_y(x):  # (1) the report is the tracker's own next one
            if ledger.group_done(g):
                continue
            vias = {e.via for e in ledger.group_timed(g)}
            rps = [ledger.report_path(v, now - self.cfg.report_path_ttl_s) for v in vias]
            if any(rp is not None and heard_at < rp.heard_at + ACK_AFTER_VIA_S for rp in rps):
                continue  # too close to report `via`: could be its retransmission or a sibling copy
            self._close_group(g, x, None, now, "next report")
        for g, (t_sent, entry) in list(self.timed_sent.items()):  # (2) after our own timed transmission
            if ledger.group_done(g) or not (t_sent < heard_at <= t_sent + self.cfg.ack_window_s):
                continue
            rp = ledger.report_path(entry.via, now - self.cfg.report_path_ttl_s)
            if rp is not None and self._same_way(rp, dg):
                self._close_group(g, x, entry.at, now, "report after our transmission")

    @staticmethod
    def _same_way(rp, dg) -> bool:
        """Heard the way report `via` was: both directly, or the same first hop and hop count."""
        if rp.hops is None or dg.hops is None:
            return False
        if rp.hops == 0 and dg.hops == 0:
            return True
        return bool(rp.hops == dg.hops and rp.path and dg.path and rp.hash_size == dg.path_hash_size
                    and bytes(rp.path[0]) == bytes(dg.path[0]))

    def _close_group(self, g: str, x: str, at: int | None, now: float, why: str) -> None:
        n = self.b.ledger.group_close(g, now)
        self.timed_sent.pop(g, None)
        self.b.stats.inc("lt_group_answered")
        log.info("LT2 group %s answered (%s, report %s): %d timed entries dropped", g, why, x, n)
        if self.cfg.claim:
            tags = [["g", g], ["done", x]] + ([["at", str(at)]] if at is not None else [])
            ev = self._sign(int(now), NOSTR_CLAIM_KIND, tags)
            self._own(ev.id)
            self.b._spawn(self.b.pool.publish(ev.to_dict(), retry=False))

    # ------------------------------------------------------------ downlink (mailbox)

    def plan(self, entry: MailboxEntry, dg) -> Plan | None:
        return self._plan(entry, dg.hops, getattr(dg, "path", None), getattr(dg, "path_hash_size", 1), timed=False)

    def _plan(self, entry: MailboxEntry | None, hops: int | None, path=None, hash_size: int = 1,
              timed: bool = False) -> Plan | None:
        """How the entry may go out, or None when its route does not allow it from here (PROTOCOL.md 4.1).
        Zero-hop when the report was heard directly and the adapter can send it; `flood` otherwise
        floods; `direct` (untimed only) along the reversed path of a report up to 3 hops away."""
        can_direct = getattr(self.b.radio, "supports_direct", False)
        if hops == 0 and can_direct:
            return Plan("zero-hop", [])
        route = "flood" if entry is None else entry.route
        if route == "flood":
            return Plan("flood", None)
        if route == "direct" and not timed and can_direct and hops is not None \
                and 1 <= hops <= self.cfg.direct_max_hops and path and len(path) == hops:
            return Plan("direct", [bytes(h) for h in reversed(path)], hash_size)
        return None

    def _lock(self, p: bytes, heard_at: float, now: float) -> set[str]:
        """PROTOCOL.md 4.2: lock every key that named x at least anchor_lead_s before the report and fits
        its commitment c. The report's time is when the radio heard it, or when a kind 4243 with this y
        reached us from a relay, whichever was first (a flood takes seconds; someone near the tag could
        fit a key to c in that time). Every variant of P (another c) is checked the same way, so a copy
        with a changed c can add a key but never removes the owner's."""
        stats, ledger = self.b.stats, self.b.ledger
        x = p[:TAG_LEN].hex()
        t = min(heard_at, self.relay_reports.get(x, heard_at))
        found = {pk for pk in ledger.lock_candidates(x, t - self.cfg.anchor_lead_s, now)
                 if commit_matches(bytes.fromhex(pk), p)}
        for pk in found:
            if ledger.lock_add(x, pk, now):
                log.info("LT2 report %s: key %s locked", x, pk[:12])
        keys = ledger.locked(x)
        if keys:
            stats.inc("lt_lock")
            # entries from keys that can never lock for x: neither locked nor here anchor_lead_s before
            dropped = ledger.drop_unlocked(x, keys | ledger.lock_candidates(x, t - self.cfg.anchor_lead_s, now))
            if dropped:
                stats.inc("lt_mailbox_unsigned_dropped", dropped)
        else:
            stats.inc("lt_lock_none")
        return keys

    def _may_lock(self, x: str, pubkey: str, now: float) -> bool:
        """Can an event from `pubkey` still go out for report x? Yes before x is heard; afterwards only for a
        locked key, or one that was here anchor_lead_s before the report (a variant of P with another c
        may lock it later)."""
        ledger = self.b.ledger
        rp = ledger.report_path(x, now - self.cfg.report_path_ttl_s)
        if rp is None or pubkey in ledger.locked(x):
            return True
        t = min(rp.heard_at, self.relay_reports.get(x, rp.heard_at))
        return pubkey in ledger.lock_candidates(x, t - self.cfg.anchor_lead_s, now)

    def _mailbox_check(self, p: bytes, dg, heard_at: float, now: float) -> None:
        stats, ledger = self.b.stats, self.b.ledger
        y = p[:TAG_LEN].hex()
        entries = ledger.mailbox_match(y, heard_at, now)
        if not entries:
            return
        if now - heard_at > self.cfg.report_max_age_s:
            stats.inc("lt_cmd_report_stale")
            return
        keys = ledger.locked(y)
        signed = [e for e in entries if e.pubkey in keys]
        if not signed:
            stats.inc("lt_cmd_unsigned")  # no key locked, or entries from strangers only: nothing goes out
            return
        for pk in dict.fromkeys(e.pubkey for e in signed):
            # per locked key, the entry seen first whose route allows sending from here
            mine = [e for e in signed if e.pubkey == pk]
            chosen = next(((e, pl) for e in mine for pl in [self.plan(e, dg)] if pl is not None), None)
            if chosen is None:
                stats.inc("lt_cmd_route_too_far")  # left for a nearer bridge: y stays unanswered here
                continue
            entry, plan = chosen
            if not ledger.answer(f"{y}:{pk}", now):
                stats.inc("lt_cmd_already_answered")
                continue
            self.b._spawn(self._deliver(entry, plan, dg.hops, p if len(p) == REPORT_P_LEN else None))

    def _late(self, y: str, pubkey: str, now: float) -> None:
        """A late entry (PROTOCOL.md 4.2): an untimed entry from a key report y locked, arriving after the
        report. It goes out at once while the report is at most late_max_s old (the tag listens 90 s)."""
        stats, ledger = self.b.stats, self.b.ledger
        if pubkey not in ledger.locked(y):
            return
        p = self.heard_p.get(y)  # first live hearing on our radio, this run
        rp = ledger.report_path(y, now - self.cfg.late_max_s)
        if p is None or rp is None or now - rp.heard_at > self.cfg.late_max_s:
            stats.inc("lt_cmd_late_too_late")
            return
        entries = [e for e in ledger.mailbox_match(y, now + 1, now) if e.pubkey == pubkey]
        chosen = next(((e, pl) for e in entries
                       for pl in [self._plan(e, rp.hops, rp.path, rp.hash_size, timed=False)] if pl is not None), None)
        if chosen is None:
            stats.inc("lt_cmd_route_too_far")
            return
        if not ledger.answer(f"{y}:{pubkey}", now):
            return  # this key already got its command out for this report
        stats.inc("lt_cmd_late")
        entry, plan = chosen
        self.b._spawn(self._deliver(entry, plan, rp.hops, p))

    def _rank(self, entry: MailboxEntry, pubkey: str, hops: int, score: bytes) -> tuple:
        ps = entry.p
        return (ps.index(pubkey) if pubkey in ps else (len(ps) if ps else 0), hops, score)

    def _counts(self, entry: MailboxEntry, pubkey: str, proof: str | None, p: bytes | None) -> bool:
        """A rival claim counts from a configured peer; for an untimed entry also from an announced bridge
        (or one the entry names) that proves it heard the report. Timed entries have no secret to prove
        hearing with, so only peers count there. An ignored claim costs at worst a second identical
        transmission, which repeaters merge (PROTOCOL.md 4.1, "Claims")."""
        if pubkey in self.peers:
            return True
        if entry.at is not None or p is None or proof is None:
            return False
        if not (pubkey in self.announced or pubkey in entry.p):
            return False
        return proof == market.claim_proof(p, pubkey)

    async def _claim(self, entry: MailboxEntry, hops: int | None, wait_s: float, p: bytes | None = None) -> bool:
        """Publish a kind 24244 claim for the entry's `y`, wait `wait_s`, True if this bridge won."""
        y = entry.y
        hops = hops if hops is not None else UNKNOWN_HOPS
        now = self.b.clock()
        tags = [["y", y], ["hops", str(hops)]]
        if p is not None and self.key and entry.at is None:
            tags.append(["proof", market.claim_proof(p, self.pubkey)])
        claim = self._sign(int(now), NOSTR_CLAIM_KIND, tags)
        mine = self._rank(entry, claim.pubkey, hops, claim_score(claim.pubkey, bytes.fromhex(y)))
        self._own(claim.id)
        self.b._spawn(self.b.pool.publish(claim.to_dict(), retry=False))
        await asyncio.sleep(wait_s)
        now = self.b.clock()
        rivals = [self._rank(entry, pk, h, s) for t, h, s, eid, pk, proof in self.claims.get(y, ())
                  if now - t <= CLAIM_MAX_AGE_S and eid != claim.id and pk != claim.pubkey
                  and self._counts(entry, pk, proof, p)]
        ignored = sum(1 for c in self.claims.get(y, ()) if now - c[0] <= CLAIM_MAX_AGE_S and c[3] != claim.id) \
            - len(rivals)
        if ignored > 0:
            self.b.stats.inc("lt_claim_ignored", ignored)
        return not rivals or mine < min(rivals)

    async def _deliver(self, entry: MailboxEntry, plan: Plan, hops: int | None, p: bytes | None = None) -> None:
        stats = self.b.stats
        if not self.b.ledger.mailbox_pending(entry.event_id):
            stats.inc("lt_cmd_cancelled_meanwhile")
            return
        if self.cfg.claim and not await self._claim(entry, hops, self.cfg.claim_wait_s, p):
            stats.inc("lt_cmd_lost_claim")
            return
        await self._send(entry, plan)

    def _timed_blocked(self, entry: MailboxEntry, now: float) -> str | None:
        """Why a timed entry may not go out now (A3, A4, groups), or None."""
        ledger, cfg = self.b.ledger, self.cfg
        if entry.grp is not None and ledger.group_done(entry.grp):
            return "lt_cmd_group_done"
        last = ledger.timed_tx_last(entry.via)
        if last is not None and now - last < cfg.timed_via_gap_s:
            return "lt_cmd_timed_via_gap"
        if ledger.timed_tx_count(now - 3600) >= cfg.timed_per_hour:
            return "lt_cmd_timed_hourly_cap"
        return None

    def _open_flood(self, entry: MailboxEntry, plan: Plan) -> bool:
        """A flood for an entry that names no bridge: the free tier with its own cap."""
        return plan.how == "flood" and not (self.pubkey and self.pubkey in entry.p)

    def _send_blocked(self, entry: MailboxEntry, plan: Plan, now: float) -> str | None:
        """Airtime order (PROTOCOL.md 4.1): timed entries use at most a share of the #libretag budget, so
        answers to a report keep the rest; floods of open entries have their own hourly cap."""
        cfg = self.cfg
        if entry.at is not None and cfg.timed_airtime_share < 1:
            budget = self.b.lt_airtime
            secs = self.b.airtime_s(len(entry.payload), len(plan.route or ()), plan.path_hash_size)
            if budget.used(now) + secs > budget.cap_s * cfg.timed_airtime_share + 1e-9:
                return "lt_cmd_timed_airtime_share"
        if self._open_flood(entry, plan) and self.b.ledger.open_tx_count(now - 3600) >= cfg.open_floods_per_hour:
            return "lt_cmd_open_flood_cap"
        return None

    async def _send(self, entry: MailboxEntry, plan: Plan) -> bool:
        stats, ledger = self.b.stats, self.b.ledger
        if ledger.mailbox_sent(entry.event_id):
            stats.inc("lt_cmd_heard_meanwhile")
            return False
        if not ledger.mailbox_pending(entry.event_id):
            stats.inc("lt_cmd_cancelled_meanwhile")  # deleted (NIP-09) while waiting for the claim or `at`
            return False
        now = self.b.clock()
        if ledger.payload_sent(entry.payload):
            ledger.mailbox_mark_sent(entry.event_id, now)
            stats.inc("lt_cmd_dup_payload")  # A6: this Pc already went out (a copy under another key)
            return False
        if entry.at is not None:
            why = self._timed_blocked(entry, now)
            if why:
                stats.inc(why)
                return False
        why = self._send_blocked(entry, plan, now)
        if why:
            stats.inc(why)
            log.warning("LT2 command for %s not sent: %s", entry.y, why)
            return False
        ok = await self.b.radio_send(frames.CHANNEL_LIBRETAG, frames.DATA_TYPE_LIBRETAG, entry.payload,
                                     plan.route, plan.path_hash_size)
        if not ok:
            return False
        now = self.b.clock()
        ledger.payload_mark_sent(entry.payload, now)
        if self._open_flood(entry, plan):
            ledger.open_tx_add(now)
        if entry.at is not None:
            ledger.timed_tx_add(entry.via, now)
            if entry.grp is not None:
                self.timed_sent[entry.grp] = (now, entry)
        ledger.mailbox_mark_sent(entry.event_id, now)
        stats.inc(f"lt_cmd_sent_{plan.how}")
        log.info("LT2 command for %s sent %s%s%s%s", entry.y, plan.how,
                 f" via {len(plan.route)} hops" if plan.route else "", " (timed)" if entry.at is not None else "",
                 "" if entry.p else " (open)")
        return True

    # ------------------------------------------------------------ timed entries (0.7 checks)

    async def timed_loop(self) -> None:
        while True:
            self.timed_tick(self.b.clock())
            await asyncio.sleep(TIMED_TICK_S)

    def timed_tick(self, now: float) -> None:
        """Start delivery of every timed entry whose claim time (`at` - claim lead) has come."""
        ledger, cfg = self.b.ledger, self.cfg
        groups: dict[str, list[MailboxEntry]] = {}
        for e in ledger.mailbox_timed_due(now + cfg.timed_claim_lead_s, now):
            groups.setdefault(e.event_id, []).append(e)
        for rows in groups.values():
            entry = rows[0]
            rp = ledger.report_path(entry.via, now - cfg.report_path_ttl_s)
            plan = self._plan(entry, rp.hops, timed=True) if rp is not None else None
            if rp is not None and plan is None:
                # its route does not allow it from here: leave the tags to a nearer bridge
                if not ledger.mailbox_pending(entry.event_id):
                    continue
                ledger.mailbox_mark_sent(entry.event_id, now)  # never due again here
                self.b.stats.inc("lt_cmd_route_too_far")
                continue
            # one delivery per event and per tag: the answered table makes it stick across restarts
            if not all([ledger.answer(e.y, now) for e in rows]):
                self.b.stats.inc("lt_cmd_already_answered")
                continue
            if entry.at < now - cfg.timed_max_late_s:
                self.b.stats.inc("lt_cmd_timed_missed")
                continue
            if rp is None:
                self.b.stats.inc("lt_cmd_timed_no_path")  # never caught report `via`: not near the tracker
                continue
            if entry.pubkey not in ledger.locked(entry.via):
                self.b.stats.inc("lt_cmd_unsigned")  # not signed by the key report `via` committed to
                continue
            if not (rp.heard_at - cfg.timed_max_late_s <= entry.at <= rp.heard_at + cfg.timed_max_after_report_s):
                self.b.stats.inc("lt_spam_timed_window")  # A1, for entries that came before report `via`
                continue
            if ledger.payload_sent(entry.payload):
                ledger.mailbox_mark_sent(entry.event_id, now)
                self.b.stats.inc("lt_cmd_dup_payload")
                continue
            why = self._timed_blocked(entry, now)
            if why:
                self.b.stats.inc(why)  # no claim either: we would not send
                continue
            self.b._spawn(self._deliver_timed(entry, plan, rp.hops))

    async def _deliver_timed(self, entry: MailboxEntry, plan: Plan, hops: int | None) -> None:
        stats = self.b.stats
        wait = max(entry.at - self.b.clock(), 0.0)
        if self.cfg.claim:
            if not await self._claim(entry, hops, max(wait, self.cfg.claim_wait_s)):
                stats.inc("lt_cmd_lost_claim")
                return
        elif wait:
            await asyncio.sleep(wait)
        if self.b.clock() > entry.at + self.cfg.timed_max_late_s:
            stats.inc("lt_cmd_timed_missed")
            return
        if await self._send(entry, plan):
            stats.inc("lt_cmd_timed_sent")

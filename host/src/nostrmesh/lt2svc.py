"""LibreTag LT2 in the universal bridge (PROTOCOL.md 4, 4.1 to 4.3; SPEC 3.2 "Bridges"). Needs no tracker key.

Uplink: a 45 B report P heard on #libretag (data type 0xFF4C) becomes a kind 4243 event, tags ["y", hex(P[0:4])]
and ["expiration", now + 14 d], content base64(P), signed with a fresh random key. The event is signed and
written to the outbox at hearing time with a not-before time (R21: a crash in the publish delay loses
nothing), then published after 0.3 to 0.8 s for a report heard directly or over 1 or 2 hops with a path and
no untimed command waiting for it, else after 2 to 5 s, unless the same y with the same content is already
on `dedup_min_relays` relays. A frame that the node marks `queued`, or whose receive time is more than
`approx_after_s` old, is published with created_at = now and ["approx", earliest] and never locks or sends
anything (R22). A report heard directly (DIRECT route, or a flood with 0 hops) also gets a kind 4245 receipt.

Arrivals (R36): route types flood, tflood, direct, tdirect; the hop count of a DIRECT arrival is unknown,
so whether it is answered zero-hop or along a path is decided by the entry (`near`, or a `via` with a
stored path). The plan for every ACK and command entry is `libretag.quietmesh.plan`.

Signed mailbox (PROTOCOL.md 4.2): every report commits to the mailbox key of report n; the first live
hearing locks every key that named x at least `anchor_lead_s` before and fits the commitment. Only events
from locked keys go out: untimed entries (known before the report), late entries (at most `late_max_s`
after it), ACK entries, and timed entries whose `via` locked the key.

Commands: route near (zero-hop only), direct (zero-hop or a path of 1 or 2 repeaters), flood (the cheapest
way from where the tag was heard: zero-hop, a path, or a flood in the report's scope), both (flood, and a
fallback after a zero-hop send too). After a zero-hop send with `both` or any path send the bridge waits
`fallback_after_s` (12 s) for an answer, a live report whose x is a y of another untimed entry of the
group; without one it floods the same Pc once with channel data type 0xFF6C (`["fb", "0"]` turns it off; an
entry without `g` gets no fallback, since nothing could tell its answer).
Bridges agree first over Nostr: an ephemeral kind 24244 claim, fewest hops wins, then the lowest
SHA-256(pubkey || x); claims are published without waiting.

ACK entries (["a", "1"], 13 or 26 B): near form zero-hop only, from a bridge that heard that copy directly,
after 100 to 600 ms and only if the same Pa was not heard on air meanwhile; routed form along the reversed
path of a flood heard over 1 or 2 repeaters (after a 2 s claim round, skipped on an "ack sent" note) or
along the path stored for `via`, which is then stored under the new x. Never a flood. A use of a path
record restarts its 48 h and those of its key lock (R33). One live ACK per locked key and report, at most
`acks_per_hour`; a command for the same report and window goes `ack_before_cmd_s` after the ACK. After
each ACK an ephemeral "ack sent" note (kind 24244, ["ack", "1"|"2"], ["e", entry], for a routed one ["via", x of
the path record it used]).

Timed entries (["at", t], ["via", x] per copy of the anchor): sent at `at` by the plan for `via`. A timed
entry counts as sent only after the radio took the frame; it is retried every 2 s until at + 20 s (R20). When
it is not sent, a "miss" note (kind 24244, ["e"], ["g"], ["at"], ["miss", radio|blocked|late|no_path]); no_path
only for an entry that names this bridge in "p".
At most one timed command per `via` per 10 min (not for ACKs) and `timed_per_hour` in all. A blind LOST
entry (["blind", "1"], naming this bridge in "p") floods at `at` in the country scope without `via` heard.

Groups (["g", 16 hex]): a live report whose x is a y of an untimed entry of the group (any route, any hop
count) is the tracker's answer, unless it is a sibling copy of the report a send was for (the same entry as
`via`, within 120 s): the group's remaining timed entries are dropped and a done claim goes out (R34).

Cancelling: NIP-09 deletions (kind 5) signed by the entry's own key drop an unsent entry.
"""
from __future__ import annotations

import asyncio
import base64
import binascii
import hashlib
import json
import logging
import os
import statistics
from collections import OrderedDict, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

from libretag import quietmesh
from libretag.lt2 import (BLE_P_LEN, CMD_P_LEN, NOSTR_CLAIM_KIND, NOSTR_COMMAND_KIND, NOSTR_REPORT_KIND, REPORT_P_LEN,
                          TAG_LEN, channel_key, commit_matches, report_time)
from libretag.meshcore import PAYLOAD_GRP_DATA

from . import bech32, fieldlog, frames, grp, market, secp
from .ledger import MailboxEntry, ReportPath
from .nostr import Event, is_lower_hex, sign_event
from .policy import RateLimit

log = logging.getLogger("nostrmesh.lt2")

REPORT_TTL_S = 14 * 86400
MAX_Y_TAGS = 16          # per mailbox event (one per copy j and a few counters ahead)
MAX_VIA_TAGS = 8         # ["via", x] per timed entry (one per copy of the anchor)
CLAIM_MAX_AGE_S = 30.0
UNKNOWN_HOPS = 255
TIMED_TICK_S = 0.5       # how often the timed scheduler looks for due entries
DELETION_KIND = 5        # NIP-09
MAX_DELETE_IDS = 64      # e tags looked at per deletion
GROUP_HEX = 8            # ["g", 16 lowercase hex]
ACK_AFTER_VIA_S = 120.0  # a sibling copy (same entry as `via`) this soon after `via` is never its answer
BRIDGE_VERSION = "0.7.0"  # announced in kind 38244 (0.5: signed mailbox; 0.6: Local API client; 0.7: quiet mesh)
REJECTED_MAX = 4096     # mailbox events already turned down, remembered so a relay's resend is not counted again
DATA_TYPE_RETRY = quietmesh.CHANNEL_DATA_TYPE_RETRY  # 0xFF6C: the fallback send of a command
ACK_LEN = quietmesh.ACK_LEN
ROUTED_ACK_LENS = range(15, 21)  # 13 + 1 + (1 or 2) x hash size 1..3
NOTE_ACK_NEAR, NOTE_ACK_ROUTED = "1", "2"
LT_KEY = channel_key(frames.CHANNEL_LIBRETAG)


def first_hop_key(dg) -> tuple | None:
    """Rate-limit key of a heard #libretag packet: ("direct",) when heard without a repeater we know of (a
    DIRECT arrival or a flood with 0 hops), (hash size, first repeater hash) when the path is reported,
    None when it is not (companion serial: only the global limit applies)."""
    if getattr(dg, "heard_direct", False) or getattr(dg, "hops", None) == 0:
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


def hops_class(hops: int | None) -> str:
    """B6 statistics: 0, 1, 2, 3+, unknown."""
    if hops is None:
        return "unknown"
    return str(hops) if hops <= 2 else "3plus"


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


def arrival(dg) -> dict | None:
    """How a datagram arrived, as quietmesh.plan takes it. Route "unknown" (no route type, no hop count) and a
    flood whose hop count is known but whose path is not (companion serial) have "path" None: such a
    report may be answered zero-hop (0 hops) or by a flood, never along a path, and gets no ACK."""
    r = dg.route
    if r is None:
        return {"route": "unknown", "path": None, "hops": None}
    if r in ("direct", "tdirect"):
        return {"route": r, "path": []}
    if dg.hops is None:
        return None
    if dg.hops > 0 and not dg.path:
        return {"route": r, "path": None, "hops": dg.hops}
    return {"route": r, "path": [bytes(h).hex() for h in (dg.path or [])]}


@dataclass
class Plan:
    how: str                          # zero-hop | path | flood
    route: list[bytes] | None         # send order (bridge to tag); None for a flood, [] for zero-hop
    path_hash_size: int = 1
    scope: str | None = None          # region of a flood (None: unscoped / the node's default)
    fallback: bool = False            # a 0xFF6C flood after fallback_after_s without an answer
    fallback_scope: str | None = None
    form: str | None = None           # ACKs: near | routed
    tag_path: list[bytes] = field(default_factory=list)  # routed ACK: the path appended, tag-to-bridge order
    hops: int | None = None           # for the claim's ranking
    path_x: str | None = None         # a path send: x of the path record whose path it uses (the "ack sent" via)


class LibreTagService:
    def __init__(self, bridge):
        self.b = bridge
        self.cfg = bridge.cfg.libretag
        self.sightings: OrderedDict[str, set[str]] = OrderedDict()  # report key -> relays that have it
        self.claims: dict[str, list[tuple]] = defaultdict(list)  # y -> (t, hops, score, id, pubkey, proof)
        self.own_ids: OrderedDict[str, None] = OrderedDict()
        self._publishing: set[str] = set()
        self.rx_limit = RateLimit(self.cfg.rx_per_min, 60)
        self.rx_first_hop = RateLimit(self.cfg.rx_per_first_hop_per_min, 60)
        self.relay_reports: OrderedDict[str, int] = OrderedDict()  # y of verified 4243 on relays -> created_at
        # fingerprints of 4244 events already turned down: every relay reconnect sends the same events
        # again (`since` = mailbox TTL), and counting them again made lt_mailbox_bad and
        # lt_mailbox_timed_late grow with each reconnect instead of with each event
        self.rejected: OrderedDict[str, None] = OrderedDict()
        # PROTOCOL.md 4.1: the stable bridge key (None: claims under fresh keys that count nowhere)
        self.key = read_bridge_key(getattr(getattr(bridge.cfg, "bridge", None), "key", ""))
        self.pubkey = secp.pubkey(self.key).hex() if self.key else None
        self.peers = {h for h in (_pubkey_hex(v) for v in self.cfg.peers) if h}
        self.networks = [n for n in self.cfg.networks if market.is_network(n)]
        self.announced: dict[str, float] = {}       # bridge pubkey -> expiration of its announcement
        self.heard_p: OrderedDict[str, bytes] = OrderedDict()  # x -> report P heard on our radio (claim proofs)
        self.receipt_limit = RateLimit(self.cfg.receipts_per_hour, 3600)
        self.last_announce = 0.0
        self.ack_notes: dict[str, list[tuple[float, str]]] = defaultdict(list)  # y -> (t, pubkey) "ack sent"
        self.ack_inflight: dict[str, asyncio.Future] = {}  # report key (y, or via@at) -> ACK being sent
        self.ack_done: OrderedDict[str, float] = OrderedDict()  # report key -> when its ACK was done
        self.fallbacks: dict[str, dict] = {}         # entry id -> armed fallback
        self.send_refs: dict[str, dict[str, float]] = defaultdict(dict)  # g -> {x a send was for: heard_at}
        # g -> {x: heard_at} of live first hearings of the group's untimed y: a timed entry that falls due later
        # checks them against its own via (R34), also when nothing was there to answer at the hearing
        self.group_heard: dict[str, dict[str, float]] = defaultdict(dict)
        self.path_sends: OrderedDict[str, dict] = OrderedDict()  # x a routed ACK went along a path for (B6)
        self.timed_busy: set[str] = set()            # timed entries being delivered (not answered yet: R20)
        self.clock_samples: list[float] = []         # now - created_at of live ephemeral events (s)
        self.last_clock = 0.0

    # ------------------------------------------------------------ subscriptions

    def subscriptions(self, now: float) -> dict[str, list[dict]]:
        subs = {}
        if self.cfg.uplink and self.b.cfg.uplink.enabled:
            subs["nm-lt-report"] = [{"kinds": [NOSTR_REPORT_KIND], "since": int(now) - 600}]
        if self.cfg.mailbox:
            since = int(now - self.cfg.mailbox_ttl_s)
            # every 4244, also with accept_open off (PROTOCOL.md 4.1): anchors carry no tag a filter could
            # select, and without them no key locks, so ACK entries (4.3) would never go out; open command
            # entries are dropped on arrival
            subs["nm-lt-cmd"] = [{"kinds": [NOSTR_COMMAND_KIND], "since": since}]
            subs["nm-lt-ann"] = [{"kinds": [market.ANNOUNCE_KIND], "#k": [str(NOSTR_COMMAND_KIND)],
                                  "since": int(now - market.ANNOUNCE_TTL_S - 86400)}]
            subs["nm-lt-del"] = [{"kinds": [DELETION_KIND], "#k": [str(NOSTR_COMMAND_KIND)],
                                  "since": int(now - self.cfg.mailbox_ttl_s)}]
            if self.cfg.claim or self.cfg.acks:
                subs["nm-lt-claim"] = [{"kinds": [NOSTR_CLAIM_KIND], "since": int(now) - 60}]
        return subs

    def prune(self, now: float) -> None:
        if self.cfg.timed_commands or self.cfg.acks:
            self.b.ledger.report_paths_prune(now - self.cfg.report_path_ttl_s)
        for y in list(self.claims):
            self.claims[y] = [c for c in self.claims[y] if now - c[0] < 120]
            if not self.claims[y]:
                del self.claims[y]
        for y in list(self.ack_notes):
            self.ack_notes[y] = [c for c in self.ack_notes[y] if now - c[0] < 120]
            if not self.ack_notes[y]:
                del self.ack_notes[y]
        for g in list(self.send_refs):
            self.send_refs[g] = {x: t for x, t in self.send_refs[g].items() if now - t < 86400}
            if not self.send_refs[g]:
                del self.send_refs[g]
        for g in list(self.group_heard):
            self.group_heard[g] = {x: t for x, t in self.group_heard[g].items()
                                   if now - t < self.cfg.timed_max_after_report_s}
            if not self.group_heard[g]:
                del self.group_heard[g]
        while self.path_sends and now - next(iter(self.path_sends.values()))["t"] > 2 * 86400:
            self.path_sends.popitem(last=False)
        for pk in [pk for pk, exp in self.announced.items() if exp <= now]:
            del self.announced[pk]
        if self.key and self.cfg.announce and now - self.last_announce >= self.cfg.announce_interval_s:
            self.announce(now)
        if now - self.last_clock >= 3600:
            self.last_clock = now
            if self.clock_samples:
                off = statistics.median(self.clock_samples)
                fieldlog.ev("clock", offset_ms=round(off * 1000), n=len(self.clock_samples))
                self.clock_samples = []

    def _own(self, eid: str) -> None:
        self.own_ids[eid] = None
        while len(self.own_ids) > 4096:
            self.own_ids.popitem(last=False)

    # ------------------------------------------------------------ radio

    def on_datagram(self, dg, now: float) -> None:
        if not fieldlog.enabled():
            return self._on_datagram(dg, now)
        before = dict(self.b.stats.c)
        try:
            return self._on_datagram(dg, now)
        finally:
            p = bytes(dg.payload)
            kind = {REPORT_P_LEN: "report", BLE_P_LEN: "ble", CMD_P_LEN: "cmd", ACK_LEN: "ack"}.get(len(p), "other")
            if len(p) in ROUTED_ACK_LENS:
                kind = "ack"
            moved = fieldlog.counters_moved(before, self.b.stats.c)
            fieldlog.ev("rx", now, channel=dg.channel, dtype=dg.data_type, len=len(p), kind=kind, x=p[:TAG_LEN],
                        first="lt_rx_duplicate" not in moved, heard_direct=dg.heard_direct,
                        hops=dg.hops, route=dg.route,
                        path=[bytes(h) for h in dg.path] if dg.path else None,
                        phs=dg.path_hash_size if dg.path else None, snr=dg.snr, rssi=dg.rssi,
                        scope=dg.scope, transport=dg.transport_code,
                        rx_time=dg.rx_time, stale=bool(dg.stale) or None, queued=bool(dg.queued) or None,
                        **(dg.meta or {}), result=moved)

    def _stale(self, dg, now: float) -> bool:
        """A frame whose hearing time is not live (R22): queued by the node, drained after a reconnect, or
        with a receive time more than approx_after_s old. Published with approx; never locks or sends."""
        if dg.stale or dg.queued:
            return True
        return dg.rx_time is not None and now - dg.rx_time > self.cfg.approx_after_s

    def _scope_of(self, dg, p: bytes) -> str | None:
        """The scope a report came in: "" unscoped, a region name, None for a DIRECT arrival or unknown.
        The name is the node's (rx_scope) or found by testing the configured regions against code[0]:
        the channel envelope has no nonce, so the GRP_DATA payload can be rebuilt from P."""
        r = dg.route
        if r == "flood":
            return ""
        if r != "tflood":
            return None if r in ("direct", "tdirect") or r is None else ""
        regs = self.b.regions()
        if dg.scope:
            return dg.scope
        if dg.transport_code is not None:
            payload = grp.wrap(LT_KEY, dg.data_type, p)
            for name in regs:
                if quietmesh.transport_code(name, PAYLOAD_GRP_DATA, payload) == dg.transport_code:
                    return name
        return regs[0] if regs else ""  # a scope we do not know: the narrowest we serve (least mesh load)

    def _on_datagram(self, dg, now: float) -> None:
        stats, ledger = self.b.stats, self.b.ledger
        p = bytes(dg.payload)
        if dg.data_type == DATA_TYPE_RETRY:
            if len(p) == CMD_P_LEN:  # another bridge's fallback flood of a command
                ledger.payload_mark_sent(b"fb" + p, now)
                stats.inc("lt_fallback_heard_on_air")
            return
        if len(p) == ACK_LEN or len(p) in ROUTED_ACK_LENS:
            ledger.payload_mark_sent(b"ack" + p[:ACK_LEN], now)  # another bridge or the owner's node sent it
            stats.inc("lt_ack_heard_on_air")
            return
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
        stale = self._stale(dg, now)
        earliest = None
        if stale:
            # heard at an unknown time, no earlier than the last drain (or its stated, old receive time)
            if dg.rx_time is not None and dg.rx_time <= now:
                earliest = dg.rx_time
            else:
                lo = dg.rx_time_min
                if lo is None:
                    kv = ledger.kv_get("radio_last_drained")
                    lo = float(kv) if kv else None
                earliest = lo if lo is not None and lo <= now else now
            heard_at = earliest
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
        if self.cfg.mailbox:  # path record: timed entries, late entries, ACKs
            ledger.report_path_put(x, None if dg.is_direct else dg.hops,
                                   [bytes(h) for h in dg.path] if dg.path and not dg.is_direct else None,
                                   dg.path_hash_size, heard_at, dg.route, self._scope_of(dg, p))
        live_report = first and not stale and len(p) == REPORT_P_LEN
        if live_report:
            stats.inc(f"lt_rx_route_{dg.route or 'unknown'}")  # B6: how reports reach this bridge
            stats.inc(f"lt_rx_hops_{'direct' if dg.is_direct else hops_class(dg.hops)}")
            self.heard_p[x] = p  # for the hearing proof of our claims and to check rivals' proofs
            while len(self.heard_p) > 2048:
                self.heard_p.popitem(last=False)
        receipt = live_report and dg.heard_direct and self.cfg.receipts and self.key is not None \
            and self.receipt_limit.hit(now)
        if stale:
            stats.inc("lt_rx_stale")  # never locks, never triggers a send (PROTOCOL.md 4)
        elif self.cfg.mailbox and first:
            # only the first hearing of P triggers (a late flood copy or a repeat does not: an entry
            # published after the report reached the relays could ride on it)
            if len(p) == REPORT_P_LEN:
                self._lock(p, heard_at, now)
                self._answer_seen(x, heard_at, now)
            self._mailbox_check(p, dg, heard_at, now)
            if len(p) == REPORT_P_LEN:
                self._ack_check(x, now)
        if not first:
            stats.inc("lt_rx_duplicate")
            return
        stats.inc("rx_lt2_report")
        if self.cfg.uplink and self.b.cfg.uplink.enabled and not (row and row["published_at"]) \
                and key not in self._publishing:
            self._queue_report(p, key, dg, heard_at, stale, receipt, now)

    # ------------------------------------------------------------ uplink (kind 4243, receipts)

    def _dedup_need(self) -> int:
        n = len(getattr(self.b.pool, "subscribe_urls", ()) or ()) or 1
        return max(1, min(self.cfg.dedup_min_relays, n))

    def _publish_delay(self, fast: bool = False) -> float:
        if fast:
            lo, hi = self.cfg.fast_publish_min_s, self.cfg.fast_publish_max_s
        else:
            lo, hi = self.cfg.publish_delay_min_s, self.cfg.publish_delay_max_s
        return self.b.rng.uniform(min(lo, hi), hi)

    def _queue_report(self, p: bytes, key: str, dg, heard_at: float, stale: bool, receipt: bool, now: float) -> None:
        """Sign the kind 4243 now and put it in the outbox with a not-before time (R21), publish it after the
        delay. created_at is the hearing time; for a stale frame it is now, with ["approx", earliest]."""
        x = p[:TAG_LEN].hex()
        if stale:
            created = int(now)
            tags = [["y", x], ["expiration", str(created + REPORT_TTL_S)], ["approx", str(min(int(heard_at), created))]]
        else:
            created = int(heard_at) if 0 <= now - heard_at < 600 else int(now)
            tags = [["y", x], ["expiration", str(created + REPORT_TTL_S)]]
        path_ok = dg.route in ("flood", "tflood") and dg.hops in (1, 2) and bool(dg.path)
        waiting = bool(self.b.ledger.mailbox_match(x, heard_at + 1, now)) if self.cfg.mailbox else False
        fast = not stale and (dg.heard_direct or path_ok) and not waiting
        delay = self._publish_delay(fast)
        ev = sign_event(_fresh_key(), created, NOSTR_REPORT_KIND, tags, base64.b64encode(p).decode(), os.urandom(32))
        self._own(ev.id)
        d = ev.to_dict()
        self.b.ledger.outbox_put(d, key, "lt_published", now + REPORT_TTL_S, now, next_try=now + delay + 60)
        self._publishing.add(key)
        self.b._spawn(self._publish_report(d, p, key, heard_at, delay, stale, receipt, dg.is_direct))

    async def _publish_report(self, ev: dict, p: bytes, key: str, heard_at: float, delay: float, approx: bool,
                              receipt: bool, direct: bool) -> None:
        ledger = self.b.ledger
        x = p[:TAG_LEN]
        try:
            await asyncio.sleep(delay)
            if len(self.sightings.get(key, ())) >= self._dedup_need():
                self.b.stats.inc("lt_up_dedup")
                ledger.outbox_done(ev["id"])
                fieldlog.ev("report_pub", x=x, skipped="dedup", relays=sorted(self.sightings.get(key, ())))
                if receipt:
                    self.b._spawn(self._publish_receipt(x.hex(), direct, heard_at))
                return
            jobs = [self.b.pool.publish(ev)]
            if receipt:
                jobs.append(self._publish_receipt(x.hex(), direct, heard_at))
            res = (await asyncio.gather(*jobs))[0]
            now = self.b.clock()
            ok = any(good for good, _ in res.values())
            if ok:
                ledger.outbox_done(ev["id"])
                ledger.mark_published(key, now)
                self.b.stats.inc("lt_published")
            else:
                ledger.outbox_failed(ev["id"], now)
                self.b.stats.inc("publish_deferred")
                log.warning("publish %s failed, kept in the outbox: %s", ev["id"][:12], res)
            fieldlog.ev("report_pub", x=x, id=ev["id"], created_at=ev["created_at"], heard_at=heard_at,
                        delay_s=delay, approx=approx or None, receipt=receipt,
                        relays={u: ok_ for u, (ok_, _) in res.items()})
        finally:
            self._publishing.discard(key)

    # ------------------------------------------------------------ identity (PROTOCOL.md 4.1)

    def _sign(self, created_at: int, kind: int, tags: list, content: str = "") -> Event:
        """Signed with the bridge key when there is one, else with a fresh key."""
        return sign_event(self.key or _fresh_key(), created_at, kind, tags, content, os.urandom(32))

    async def _publish_receipt(self, x: str, direct: bool, heard_at: float) -> None:
        ev = self._sign(int(heard_at), market.RECEIPT_KIND, market.receipt_tags(x, direct, int(heard_at)))
        self._own(ev.id)
        res = await self.b.pool.publish(ev.to_dict(), retry=False)
        self.b.stats.inc("lt_receipt_published" if any(ok for ok, _ in res.values()) else "lt_receipt_failed")

    def _note(self, tags: list, now: float) -> None:
        """An ephemeral kind 24244 note ("ack sent", "miss"), signed with the bridge key; nothing waits for it."""
        if not self.key:
            return
        ev = self._sign(int(now), NOSTR_CLAIM_KIND, tags)
        self._own(ev.id)
        self.b._spawn(self.b.pool.publish(ev.to_dict(), retry=False))

    def _miss(self, entry: MailboxEntry, reason: str, now: float) -> None:
        """A timed entry fell due and was not sent: the app shows the slot as "not sent" (PROTOCOL.md 4.3)."""
        self.b.stats.inc(f"lt_miss_{reason}")
        fieldlog.ev("tx_skip", id=entry.event_id, y=entry.y, at=entry.at, reason=reason, miss=True,
                    **({"xa": entry.payload[:4]} if entry.ack else {"xc": entry.payload[:4]}))
        tags = [["e", entry.event_id]] + ([["g", entry.grp]] if entry.grp else []) + \
               [["at", str(entry.at)], ["miss", reason]]
        self._note(tags, now)

    def _miss_no_path(self, entry: MailboxEntry, now: float) -> None:
        """`no_path` is a miss only for an entry that names this bridge in "p": of an open entry many bridges
        hold a copy, and the one that cannot reach the tag says nothing about the slot (PROTOCOL.md 4.3)."""
        if self.pubkey and self.pubkey in entry.p:
            self._miss(entry, "no_path", now)
        else:
            self.b.stats.inc("lt_no_path_open")
            fieldlog.ev("tx_skip", id=entry.event_id, y=entry.y, at=entry.at, reason="no_path",
                        **({"xa": entry.payload[:4]} if entry.ack else {"xc": entry.payload[:4]}))

    def announcement(self, now: float) -> list[Event]:
        """Kind 38244, one per network served ("" when none is named), with the radio's own state."""
        radio = self.b.radio
        preset = None
        node = getattr(radio, "preset", None)
        freq = getattr(radio, "freq_hz", None)
        if node and freq:
            preset = (freq, node[1], node[0], node[2])
        h = self.b.health
        content = json.dumps({"name": getattr(self.b.cfg.bridge, "name", "") or "nostrmesh",
                              "version": BRIDGE_VERSION, "adapter": self.b.cfg.radio.adapter,
                              "zero_hop": bool(getattr(radio, "supports_direct", False)),
                              "direct": bool(getattr(radio, "supports_path", False))}, separators=(",", ":"))
        out = []
        for net in self.networks or [""]:
            tags = market.announce_tags(net, int(now), regions=self.b.regions(), preset=preset,
                                        relays=list(getattr(self.b.pool, "subscribe_urls", ()) or ()),
                                        accept_open=self.cfg.accept_open, receipts=self.cfg.receipts,
                                        anchor_lead_s=self.cfg.anchor_lead_s, late_max_s=self.cfg.late_max_s,
                                        radio=(h.state, int(h.since)))
            out.append(self._sign(int(now), market.ANNOUNCE_KIND, tags, content))
        return out

    def announce(self, now: float) -> None:
        self.last_announce = now
        for ev in self.announcement(now):
            self._own(ev.id)
            self.b._spawn(self._publish_announcement(ev))

    def on_radio_state(self, state: str, now: float) -> None:
        """The radio went down or came back: announce it now, not at the next hour (R13)."""
        if self.key and self.cfg.announce:
            self.announce(now)

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
            if isinstance(ev.created_at, int) and abs(now - ev.created_at) < 300 and ev.id not in self.own_ids:
                self.clock_samples.append(now - ev.created_at)
                del self.clock_samples[:-500]
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
        before = dict(self.b.stats.c) if fieldlog.enabled() else None
        took = self._take_command_event(ev, now, url)
        moved = fieldlog.counters_moved(before, self.b.stats.c) if before is not None else []
        if before is not None and moved != ["lt_anchor_again"]:  # an anchor again: every relay reconnect resends them
            tags = [t for t in ev.tags if isinstance(t, list) and len(t) >= 2]
            one = lambda n: next((t[1] for t in tags if t[0] == n), None)  # noqa: E731
            ys = [t[1] for t in tags if t[0] == "y"]
            if ev.content == "":
                kind = "anchor"
            elif one("a") == "1":
                kind = "ack"
            elif one("at") is not None:
                kind = "timed"
            elif any(_y(y) and ledger.report_path(y, now - self.cfg.late_max_s) for y in ys):
                kind = "late"
            else:
                kind = "cmd"
            fieldlog.ev("mailbox", now, id=ev.id, kind=kind, url=url, pubkey=ev.pubkey, created_at=ev.created_at,
                        known_at=now, y=ys, at=one("at"), via=[t[1] for t in tags if t[0] == "via"] or None,
                        route=one("route"), fb=one("fb"), g=one("g"), p=[t[1] for t in tags if t[0] == "p"] or None,
                        blind=one("blind"), result=moved)
        if not took:
            self.rejected[fp] = None
            while len(self.rejected) > REJECTED_MAX:
                self.rejected.popitem(last=False)

    def _take_command_event(self, ev: Event, now: float, url: str = "") -> bool:
        """Validate and store one 4244 event. False: turned down for good (counted once by the caller);
        True: added, or a case that may change later (cancelled, evicted, a group already done)."""
        stats, cfg, ledger = self.b.stats, self.cfg, self.b.ledger
        tags = [t for t in ev.tags if isinstance(t, list) and len(t) >= 2 and isinstance(t[1], str)]
        anchor = ev.content == ""  # PROTOCOL.md 4.2: names the next reports' tags, carries no command
        is_ack = any(t[0] == "a" and t[1] == "1" for t in tags)
        pc = b"" if anchor else _b64(ev.content) if isinstance(ev.content, str) else None
        size_ok = anchor or (len(pc) in (ACK_LEN, 2 * ACK_LEN) if is_ack else len(pc) == CMD_P_LEN) if pc is not None \
            else False
        if pc is None or not is_lower_hex(ev.pubkey, 32) or (anchor and is_ack) or not size_ok:
            stats.inc("lt_mailbox_bad")
            return False
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
        if is_ack and tg.route not in ("near", "direct"):
            stats.inc("lt_mailbox_bad")  # an ACK never floods (PROTOCOL.md 4.3)
            return False
        if is_ack and not cfg.acks:
            stats.inc("lt_mailbox_acks_off")
            return False
        if not tg.p and not cfg.accept_open and not is_ack and not anchor:
            # ACK entries pass: they can never cause a flood; anchors only name keys, so ACKs can lock
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
        fb = next((t[1] for t in tags if t[0] == "fb"), "1") != "0"
        blind = any(t[0] == "blind" and t[1] == "1" for t in tags)
        cap = now + cfg.mailbox_ttl_s
        exp = next((t[1] for t in tags if t[0] == "expiration"), None)
        expires = min(int(exp), cap) if exp is not None and exp.isdigit() else cap
        if not isinstance(ev.created_at, int) or ev.created_at > now + cfg.future_max_s:
            stats.inc("lt_spam_future")
            return False
        if expires <= now:
            stats.inc("lt_mailbox_expired")
            return False
        vias = list(dict.fromkeys(t[1] for t in tags if t[0] == "via"))
        if len(vias) > MAX_VIA_TAGS or not all(_y(v) for v in vias) or (anchor and vias):
            stats.inc("lt_mailbox_bad")
            return False
        at_s = next((t[1] for t in tags if t[0] == "at"), None)
        at = None
        if at_s is not None:
            if not at_s.isdigit() or len(at_s) > 12 or not vias or anchor:
                stats.inc("lt_mailbox_bad")  # a timed entry names its anchor; an anchor names reports, never a slot
                return False
            if not cfg.timed_commands:
                stats.inc("lt_mailbox_timed_off")
                return False
            at = int(at_s)
            if at < now - cfg.timed_max_late_s:
                stats.inc("lt_mailbox_timed_late")
                return False
        if blind and (at is None or is_ack or not self.pubkey or self.pubkey not in tg.p):
            stats.inc("lt_mailbox_bad")  # a blind LOST entry is timed and names this bridge
            return False
        # the via this bridge heard, of the copies of the anchor the app names
        heard_via = next((v for v in vias if ledger.report_path(v, now - cfg.report_path_ttl_s)), None)
        via = heard_via or (vias[0] if vias else None)
        # signed mailbox: once a report was heard, only a key that locked or still may lock (another variant
        # of P, PROTOCOL.md 4.2) can ever send for it
        if at is not None:
            if not blind and not self._may_lock(via, ev.pubkey, now):
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
        for v in vias:  # B6: a routed ACK entry naming x as via means the tag used the path learned there
            ps = self.path_sends.get(v)
            if is_ack and ps is not None and not ps.get("confirmed"):
                ps["confirmed"] = True
                self.b.stats.inc("lt_path_ack_confirmed")
                fieldlog.ev("path_use", x=v, via=ps.get("via"), hops=ps.get("hops"), what="ack_routed", confirmed=True)
        if ledger.mailbox_add(ev.id, ys, pc, route, ev.created_at, expires, now, cfg.mailbox_cap, at, via, ev.pubkey,
                              0, url or None, g, cfg.timed_per_via_max if at is not None else None,
                              cfg.mailbox_relay_share, tg.fmt, " ".join(tg.p), ack=is_ack, fb=fb, blind=blind,
                              vias=vias):
            stats.inc(("lt_ack_added_timed" if at is not None else "lt_ack_added") if is_ack else
                      ("lt_mailbox_added_timed" if at is not None else "lt_mailbox_added"))
            if at is None:
                for y in ys:
                    if is_ack:
                        self._ack_check(y, now)
                    else:
                        self._late(y, ev.pubkey, now)
        elif ledger.mailbox_pending(ev.id) or ledger.mailbox_sent(ev.id):
            pass  # the same event from another relay
        else:
            stats.inc("lt_spam_evicted")  # did not make it past the per-via, per-relay or total cap
        return True

    def _at_plausible(self, at: int, via: str, now: float) -> bool:
        """A1: a slot comes after report `via` and before its heartbeat (24 h + 1 h). Unknown when the
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
                fb = self.fallbacks.pop(eid, None)
                if fb is not None:
                    fb["cancelled"] = "deleted"
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
        if "miss" in t:
            return  # for the apps
        y = t.get("y")
        if not _y(y) or not isinstance(ev.created_at, int) or abs(now - ev.created_at) > CLAIM_MAX_AGE_S:
            return
        if "ack" in t:  # "ack sent" note: a bridge already answered this report copy
            if t["ack"] in (NOTE_ACK_NEAR, NOTE_ACK_ROUTED) and self.known(ev.pubkey) and ev.verify():
                self.ack_notes[y].append((now, ev.pubkey))
                fieldlog.ev("ack_note", y=y, pubkey=ev.pubkey, form=t["ack"])
            return
        hops = t.get("hops", "")
        if not hops.isdigit():
            return
        if any(c[3] == ev.id for c in self.claims.get(y, ())) or not ev.verify():
            return
        proof = t.get("proof")
        proof = proof if isinstance(proof, str) and is_lower_hex(proof, 32) else None
        self.claims[y].append((now, min(int(hops), UNKNOWN_HOPS), claim_score(ev.pubkey, bytes.fromhex(y)), ev.id,
                               ev.pubkey, proof))
        fieldlog.ev("claim", now, id=ev.id, pubkey=ev.pubkey, y=y, own=False, hops=int(hops), created_at=ev.created_at,
                    known=self.known(ev.pubkey))
        self.b.stats.inc("lt_claim_seen")

    def _on_done_claim(self, ev: Event, t: dict, now: float) -> None:
        """Another bridge says the tracker answered group g with report x. Accepted only with this bridge's
        own evidence of report x (heard on its radio, or a verified kind 4243 on the relays) when x is a `y`
        of an untimed entry of g (the tracker's own report)."""
        stats, ledger = self.b.stats, self.b.ledger
        g, x = t.get("g"), t.get("done")
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
        if not (ledger.group_has_untimed_y(g, x) and any(s_ <= now + 5 for s_ in seen)):
            stats.inc("lt_group_done_claim_ignored")
            return
        n = ledger.group_close(g, now)
        stats.inc("lt_group_closed_by_claim")
        log.info("LT2 group %s answered (claim from another bridge): %d timed entries dropped", g, n)

    # ------------------------------------------------------------ answers (R34) and the fallback

    def _refs(self, g: str, now: float) -> dict[str, float | None]:
        """Reports a send of group g was for: the x of its sends (untimed and timed) and the vias of its timed
        entries, with their hearing time (None: a via this bridge did not hear)."""
        ledger = self.b.ledger
        out: dict[str, float | None] = dict(self.send_refs.get(g, {}))
        for e in ledger.group_timed(g):
            for v in e.via_list:
                rp = ledger.report_path(v, now - self.cfg.report_path_ttl_s)
                if out.get(v) is None:
                    out[v] = rp.heard_at if rp is not None else None
        return out

    def _answer_seen(self, x: str, heard_at: float, now: float) -> None:
        """A live first hearing of report x: is it the tracker's answer to a send of some group? It is when x is
        a y of an untimed entry of the group that is not the entry of the report the send was for (any route,
        any hop count); a sibling copy of that report counts only 120 s after it (R34)."""
        ledger = self.b.ledger
        for g in ledger.groups_of_y(x):
            self.group_heard[g][x] = heard_at
            containing = [ys for ys in ledger.group_untimed(g).values() if x in ys]
            for fid, fb in list(self.fallbacks.items()):
                if fb["g"] == g and any(fb["ref"] not in ys for ys in containing):
                    self.fallbacks.pop(fid, None)
                    fb["cancelled"] = x
                    ms = round((now - fb["t"]) * 1000)
                    self.b.stats.inc("lt_fallback_cancelled")
                    fieldlog.ev("fallback", id=fid, xc=fb["xc"], state="cancelled", x=x, ms=ms)
                    fieldlog.ev("answer_seen", id=fid, xc=fb["xc"], x=x, ms=ms)
                    if fb["how"] == "path":
                        self.b.stats.inc("lt_path_cmd_confirmed")
                        fieldlog.ev("path_use", x=fb["ref"], via=fb.get("via"), hops=fb.get("hops"), what="cmd",
                                    confirmed=True)
            if not self.cfg.group_ack or ledger.group_done(g):
                continue
            # an answer needs something it answers: a send of the group or a timed entry's via. Without one, x is
            # the report an untimed entry waits for (the first one), not the next report: closing the group
            # there would refuse the timed entries the app publishes for x's own grid (R34)
            refs = self._refs(g, now)
            answer = bool(refs) and any(
                all(r != x and rh is not None and heard_at - rh >= ACK_AFTER_VIA_S for r, rh in refs.items() if r in ys)
                for ys in containing)
            if answer:
                self._close_group(g, x, now, "next report")

    def _answered_before(self, entry: MailboxEntry, rp: ReportPath, now: float) -> str | None:
        """A report of the group heard after this timed entry's via that answers it (R34): a y of another untimed
        entry than the via's, or a sibling copy 120 s or more after it. Its x, or None."""
        g = entry.grp
        if g is None or not self.cfg.group_ack:
            return None
        untimed = list(self.b.ledger.group_untimed(g).values())
        for x, t in self.group_heard.get(g, {}).items():
            if x == rp.x or t <= rp.heard_at:
                continue
            same = any(x in ys and rp.x in ys for ys in untimed)
            if not same or t - rp.heard_at >= ACK_AFTER_VIA_S:
                return x
        return None

    def _close_group(self, g: str, x: str, now: float, why: str) -> None:
        n = self.b.ledger.group_close(g, now)
        self.b.stats.inc("lt_group_answered")
        log.info("LT2 group %s answered (%s, report %s): %d timed entries dropped", g, why, x, n)
        if self.cfg.claim:
            ev = self._sign(int(now), NOSTR_CLAIM_KIND, [["g", g], ["done", x]])
            self._own(ev.id)
            self.b._spawn(self.b.pool.publish(ev.to_dict(), retry=False))

    def _arm_fallback(self, entry: MailboxEntry, plan: Plan, ref: str, now: float) -> None:
        if not plan.fallback or not entry.fb:
            return
        if entry.grp is None:
            self.b.stats.inc("lt_fallback_no_group")  # no way to recognise the answer: no fallback
            return
        fb = {"g": entry.grp, "ref": ref, "t": now, "xc": entry.payload[:4], "how": plan.how,
              "via": entry.via, "hops": len(plan.route or ()) or None}
        self.fallbacks[entry.event_id] = fb
        fieldlog.ev("fallback", id=entry.event_id, xc=entry.payload[:4], state="armed", ref=ref)
        self.b._spawn(self._fallback(entry, plan, fb))

    async def _fallback(self, entry: MailboxEntry, plan: Plan, fb: dict) -> None:
        await asyncio.sleep(self.cfg.fallback_after_s)
        if self.fallbacks.get(entry.event_id) is not fb:
            return  # answered (or cancelled) meanwhile
        self.fallbacks.pop(entry.event_id, None)
        stats, ledger = self.b.stats, self.b.ledger
        now = self.b.clock()
        ms = round((now - fb["t"]) * 1000)
        if fb["how"] == "path":
            stats.inc("lt_path_cmd_unconfirmed")
            fieldlog.ev("path_use", x=fb["ref"], via=fb.get("via"), hops=fb.get("hops"), what="cmd", confirmed=False)
        if ledger.payload_sent(b"fb" + entry.payload):
            stats.inc("lt_fallback_dup")
            fieldlog.ev("tx_skip", id=entry.event_id, xc=entry.payload[:4], what="fallback", reason="dup_on_air")
            return
        flood = Plan("flood", None, scope=plan.fallback_scope)
        why = self._send_blocked(entry, flood, now)
        if why:
            stats.inc(why)
            fieldlog.ev("tx_skip", id=entry.event_id, xc=entry.payload[:4], what="fallback", reason=self._reason(why))
            return
        ok = await self.b.radio_send(frames.CHANNEL_LIBRETAG, DATA_TYPE_RETRY, entry.payload, None, region=flood.scope,
                                     log_fields={"what": "fallback", "id": entry.event_id, "y": entry.y,
                                                 "at": entry.at, "g": entry.grp})
        fieldlog.ev("fallback", id=entry.event_id, xc=entry.payload[:4], state="fired" if ok else "failed", ms=ms)
        if ok:
            ledger.payload_mark_sent(b"fb" + entry.payload, self.b.clock())
            if self._open_flood(entry, flood):
                ledger.open_tx_add(self.b.clock())
            stats.inc("lt_fallback_sent")
            log.info("LT2 command for %s: no answer in %.0f s, fallback flood (0xFF6C)", entry.y,
                     self.cfg.fallback_after_s)

    # ------------------------------------------------------------ planning (quietmesh.plan)

    def _region(self, which: str | None, report_scope: str | None) -> str | None:
        regs = self.b.regions()
        if which == "narrowest":
            return regs[0] if regs else None
        if which == "country":
            return regs[-1] if regs else None
        if report_scope is None:  # a DIRECT arrival carries no scope: the narrowest we serve
            return regs[0] if regs else None
        return report_scope or None

    def _via_record(self, entry: MailboxEntry, now: float) -> ReportPath | None:
        for v in entry.via_list:
            rp = self.b.ledger.report_path(v, now - self.cfg.report_path_ttl_s)
            if rp is not None:
                return rp
        return None

    def _plan(self, kind: str, entry: MailboxEntry, heard: dict | None, scope: str | None, hash_size: int,
              via_rp: ReportPath | None, blind: bool = False) -> Plan | None:
        """The send for an entry and how its report arrived (`heard`), by quietmesh.plan; None: not from here."""
        radio = self.b.radio
        can_zero = bool(getattr(radio, "supports_direct", False))
        can_path = bool(getattr(radio, "supports_path", False))
        route = entry.route
        if heard is not None and heard.get("path") is None:
            # the route or the path is not known (no route type; companion serial): never a path, never an ACK
            hops = heard.get("hops")
            if kind == "ack":
                return None
            if hops is None:
                if route in ("flood", "both"):
                    return Plan("flood", None, scope=self._region(None, scope or ""), hops=UNKNOWN_HOPS)
                return None
            heard = {"route": heard["route"], "path": ["00"] * hops}  # only its length counts: no path send
            can_path = False
        via_path = [h.hex() for h in via_rp.path] if via_rp is not None and via_rp.path else None
        hs = via_rp.hash_size if (heard and heard["route"] in ("direct", "tdirect") and via_rp is not None) else hash_size
        if kind == "ack":
            forms = "both" if len(entry.payload) == 2 * ACK_LEN else "near" if route == "near" else "routed"
            q = quietmesh.plan("ack", heard, route, via_path=via_path, forms=forms, max_hops=self.cfg.direct_max_hops,
                               can_zero_hop=can_zero, can_path=can_path)
        else:
            q = quietmesh.plan("cmd", heard, route, via_path=via_path, fb=bool(entry.fb),
                               max_hops=self.cfg.direct_max_hops, can_zero_hop=can_zero, can_path=can_path,
                               blind=blind)
        if q["send"] == "none":
            return None
        path = [bytes.fromhex(h) for h in q["path"]]
        if q["send"] == "path" and any(len(h) != hs for h in path):
            return None
        how = q["send"]
        flood_scope = self._region(q.get("scope"), scope)
        pl = Plan(how, None if how == "flood" else path, hs, scope=flood_scope if how == "flood" else None,
                  fallback=bool(q.get("fallback")), fallback_scope=flood_scope, form=q.get("form"),
                  tag_path=[bytes.fromhex(h) for h in q.get("tag_path", [])])
        pl.hops = 0 if how == "zero-hop" else len(path) if how == "path" else \
            (len(heard["path"]) if heard and heard.get("path") is not None and heard["route"] in ("flood", "tflood")
             else UNKNOWN_HOPS)
        return pl

    def _plan_heard(self, kind: str, entry: MailboxEntry, dg, p: bytes, now: float) -> Plan | None:
        via_rp = self._via_record(entry, now) if dg.is_direct else None
        pl = self._plan(kind, entry, arrival(dg), self._scope_of(dg, p), dg.path_hash_size, via_rp)
        if pl is not None and pl.how == "path":
            pl.path_x = via_rp.x if via_rp is not None and dg.is_direct else p[:TAG_LEN].hex()
        return pl

    def _plan_record(self, kind: str, entry: MailboxEntry, rp: ReportPath, now: float) -> Plan | None:
        via_rp = None
        if rp.is_direct:
            via_rp = rp if rp.path else self._via_record(entry, now)
        pl = self._plan(kind, entry, rp.heard, rp.scope, rp.hash_size, via_rp)
        if pl is not None and pl.how == "path":
            pl.path_x = via_rp.x if via_rp is not None else rp.x
        return pl

    # ------------------------------------------------------------ signed mailbox (PROTOCOL.md 4.2)

    def _lock(self, p: bytes, heard_at: float, now: float) -> set[str]:
        """Lock every key that named x at least anchor_lead_s before the report and fits its commitment c.
        The report's time is when the radio heard it, or when a kind 4243 with this y reached us from a
        relay, whichever was first. Every variant of P (another c) is checked the same way, so a copy with
        a changed c can add a key but never removes the owner's."""
        stats, ledger = self.b.stats, self.b.ledger
        x = p[:TAG_LEN].hex()
        t = min(heard_at, self.relay_reports.get(x, heard_at))
        found = {pk for pk in ledger.lock_candidates(x, t - self.cfg.anchor_lead_s, now)
                 if commit_matches(bytes.fromhex(pk), p)}
        for pk in found:
            if ledger.lock_add(x, pk, now):
                log.info("LT2 report %s: key %s locked", x, pk[:12])
        keys = ledger.locked(x)
        lead = [t - k for pk in found if (k := ledger.key_known_at(x, pk)) is not None]
        fieldlog.ev("lock", x=x, keys=len(keys), new=len(found), lead_s=round(min(lead)) if lead else None)
        if keys:
            stats.inc("lt_lock")
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

    # ------------------------------------------------------------ untimed commands

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
            chosen = next(((e, pl) for e in mine for pl in [self._plan_heard("cmd", e, dg, p, now)] if pl is not None),
                          None)
            if chosen is None:
                stats.inc("lt_cmd_route_too_far")  # left for a nearer bridge: y stays unanswered here
                fieldlog.ev("tx_skip", id=mine[0].event_id, y=y, xc=mine[0].payload[:4],
                            reason="not_direct" if mine[0].route in ("near", "direct") else "no_path")
                continue
            entry, plan = chosen
            if not ledger.answer(f"{y}:{pk}", now):
                stats.inc("lt_cmd_already_answered")
                continue
            self.b._spawn(self._deliver(entry, plan, p if len(p) == REPORT_P_LEN else None, y, heard_at))

    def _late(self, y: str, pubkey: str, now: float) -> None:
        """A late entry (PROTOCOL.md 4.2): an untimed entry from a key report y locked, arriving after the
        report. It goes out at once while the report is at most late_max_s old (the tag listens 60 s)."""
        stats, ledger = self.b.stats, self.b.ledger
        if pubkey not in ledger.locked(y):
            return
        p = self.heard_p.get(y)  # first live hearing on our radio, this run (the claim proof needs it)
        rp = ledger.report_path(y, now - self.cfg.report_path_ttl_s)
        if p is None or rp is None or now - rp.heard_at > self.cfg.late_max_s:
            stats.inc("lt_cmd_late_too_late")
            return
        entries = [e for e in ledger.mailbox_match(y, now + 1, now) if e.pubkey == pubkey]
        chosen = next(((e, pl) for e in entries for pl in [self._plan_record("cmd", e, rp, now)] if pl is not None),
                      None)
        if chosen is None:
            stats.inc("lt_cmd_route_too_far")
            return
        if not ledger.answer(f"{y}:{pubkey}", now):
            return  # this key already got its command out for this report
        stats.inc("lt_cmd_late")
        entry, plan = chosen
        self.b._spawn(self._deliver(entry, plan, p, y, rp.heard_at))

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
            return pubkey in self.peers or (entry.ack and (pubkey in self.announced or pubkey in entry.p))
        if not (pubkey in self.announced or pubkey in entry.p):
            return False
        return proof == market.claim_proof(p, pubkey)

    async def _claim(self, entry: MailboxEntry, hops: int | None, wait_s: float, p: bytes | None = None,
                     y: str | None = None) -> bool:
        """Publish a kind 24244 claim for the entry's `y` (without waiting for the relays), wait `wait_s`,
        True if this bridge won."""
        y = y or entry.y
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
        won = not rivals or mine < min(rivals)
        fieldlog.ev("claim", id=claim.id, y=y, own=True, hops=hops, won=won, rivals=len(rivals),
                    ignored=max(ignored, 0) or None, wait_ms=round(wait_s * 1000), at=entry.at)
        return won

    def _inflight(self, key: str) -> None:
        """Mark an ACK for this report (y, or via@at for a slot) as on its way: a command for it waits."""
        try:
            self.ack_inflight[key] = asyncio.get_running_loop().create_future()
        except RuntimeError:
            pass

    async def _after_ack(self, key: str) -> None:
        """A command for the same report and window goes ack_before_cmd_s after its ACK (the app set MORE): it
        waits for an ACK still on its way, and keeps the gap also after one that has just gone out (a slot's
        ACK and command fall due at the same `at`)."""
        fut = self.ack_inflight.get(key)
        if fut is not None and not fut.done():
            await fut
        done = self.ack_done.get(key)
        wait = done + self.cfg.ack_before_cmd_s - self.b.clock() if done is not None else 0
        if wait > 0:
            await asyncio.sleep(wait)
            self.b.stats.inc("lt_cmd_after_ack")

    def _ack_finished(self, key: str) -> None:
        self.ack_done[key] = self.b.clock()
        self.ack_done.move_to_end(key)
        while len(self.ack_done) > 2048:
            self.ack_done.popitem(last=False)
        fut = self.ack_inflight.pop(key, None)
        if fut is not None and not fut.done():
            fut.set_result(None)

    async def _deliver(self, entry: MailboxEntry, plan: Plan, p: bytes | None, ref: str, heard_at: float) -> None:
        stats = self.b.stats
        if not self.b.ledger.mailbox_pending(entry.event_id):
            stats.inc("lt_cmd_cancelled_meanwhile")
            return
        why = self._send_blocked(entry, plan, self.b.clock())
        if why:  # before the claim: a bridge that may not send must not win the round and silence the others
            stats.inc(why)
            fieldlog.ev("tx_skip", id=entry.event_id, y=ref, xc=entry.payload[:4], reason=self._reason(why))
            return
        if self.cfg.claim and not await self._claim(entry, plan.hops, self.cfg.claim_wait_s, p, ref):
            stats.inc("lt_cmd_lost_claim")
            fieldlog.ev("tx_skip", id=entry.event_id, y=ref, xc=entry.payload[:4], reason="lost_claim")
            return
        await self._after_ack(ref)
        ok, _ = await self._send(entry, plan)
        if ok:
            if entry.grp:
                self.send_refs[entry.grp][ref] = heard_at
            self._arm_fallback(entry, plan, ref, self.b.clock())

    def _timed_blocked(self, entry: MailboxEntry, now: float) -> str | None:
        """Why a timed command may not go out now (A3, A4, groups), or None. ACKs have their own budget."""
        ledger, cfg = self.b.ledger, self.cfg
        if entry.grp is not None and ledger.group_done(entry.grp):
            return "lt_cmd_group_done"
        if entry.ack:
            return None
        last = ledger.timed_tx_last(entry.via)
        if last is not None and now - last < cfg.timed_via_gap_s:
            return "lt_cmd_timed_via_gap"
        if ledger.timed_tx_count(now - 3600) >= cfg.timed_per_hour:
            return "lt_cmd_timed_hourly_cap"
        if entry.blind and (ledger.blind_tx_count(now - 86400, entry.via) >= cfg.blind_per_via_24h
                            or ledger.blind_tx_count(now - 86400) >= cfg.blind_per_24h):
            return "lt_cmd_blind_cap"
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
            secs = self.b.airtime_s(len(entry.payload), len(plan.route or ()), plan.path_hash_size, plan.scope)
            if budget.used(now) + secs > budget.cap_s * cfg.timed_airtime_share + 1e-9:
                return "lt_cmd_timed_airtime_share"
        if self._open_flood(entry, plan) and self.b.ledger.open_tx_count(now - 3600) >= cfg.open_floods_per_hour:
            return "lt_cmd_open_flood_cap"
        return None

    @staticmethod
    def _reason(stat: str) -> str:
        """Field log tx_skip reason for a counter."""
        return {"lt_cmd_timed_via_gap": "via_gap", "lt_cmd_timed_hourly_cap": "hourly_cap",
                "lt_cmd_timed_airtime_share": "airtime", "lt_cmd_open_flood_cap": "hourly_cap",
                "lt_cmd_group_done": "group_done", "lt_cmd_blind_cap": "hourly_cap",
                "lt_ack_hourly_cap": "hourly_cap"}.get(stat, stat)

    async def _send(self, entry: MailboxEntry, plan: Plan) -> tuple[bool, str | None]:
        """Transmit a command entry by its plan. (True, None) when the radio took it; else (False, why):
        "radio" (the radio refused or is down: a timed entry retries), "blocked", "dup", "cancelled",
        "group_done"."""
        stats, ledger = self.b.stats, self.b.ledger
        if ledger.mailbox_sent(entry.event_id):
            stats.inc("lt_cmd_heard_meanwhile")
            fieldlog.ev("tx_skip", id=entry.event_id, y=entry.y, xc=entry.payload[:4], reason="dup_on_air")
            return False, "dup"
        if not ledger.mailbox_pending(entry.event_id):
            stats.inc("lt_cmd_cancelled_meanwhile")  # deleted (NIP-09) while waiting for the claim or `at`
            fieldlog.ev("tx_skip", id=entry.event_id, y=entry.y, xc=entry.payload[:4], reason="cancelled")
            return False, "cancelled"
        now = self.b.clock()
        if ledger.payload_sent(entry.payload):
            ledger.mailbox_mark_sent(entry.event_id, now)
            stats.inc("lt_cmd_dup_payload")  # A6: this Pc already went out (a copy under another key)
            fieldlog.ev("tx_skip", id=entry.event_id, y=entry.y, xc=entry.payload[:4], reason="dup_on_air")
            return False, "dup"
        why = self._timed_blocked(entry, now) if entry.at is not None else None
        why = why or self._send_blocked(entry, plan, now)
        if why:
            stats.inc(why)
            fieldlog.ev("tx_skip", id=entry.event_id, y=entry.y, xc=entry.payload[:4], how=plan.how, at=entry.at,
                        reason=self._reason(why))
            log.warning("LT2 command for %s not sent: %s", entry.y, why)
            return False, "group_done" if why == "lt_cmd_group_done" else "blocked"
        if entry.at is not None and not self.b.health.can_send():
            fieldlog.ev("tx_skip", id=entry.event_id, y=entry.y, xc=entry.payload[:4], at=entry.at, reason="radio")
            return False, "radio"
        late_ms = round((self.b.clock() - entry.at) * 1000) if entry.at is not None else None
        ok = await self.b.radio_send(frames.CHANNEL_LIBRETAG, frames.DATA_TYPE_LIBRETAG, entry.payload,
                                     plan.route, plan.path_hash_size, region=plan.scope,
                                     log_fields={"what": "cmd", "id": entry.event_id, "y": entry.y, "at": entry.at,
                                                 "late_ms": late_ms, "via": entry.via, "g": entry.grp,
                                                 "open": not entry.p or None})
        if not ok:
            return False, "radio"
        now = self.b.clock()
        ledger.payload_mark_sent(entry.payload, now)
        if self._open_flood(entry, plan):
            ledger.open_tx_add(now)
        if entry.at is not None:
            ledger.timed_tx_add(entry.via, now)
            if entry.blind:
                ledger.blind_tx_add(entry.via, now)
        if plan.how == "path":
            for v in entry.via_list:
                ledger.report_path_touch(v, now)  # R33
        ledger.mailbox_mark_sent(entry.event_id, now)
        stats.inc(f"lt_cmd_sent_{'direct' if plan.how == 'path' else plan.how}")
        log.info("LT2 command for %s sent %s%s%s%s", entry.y, plan.how,
                 f" via {len(plan.route)} hops" if plan.route else "", " (timed)" if entry.at is not None else "",
                 "" if entry.p else " (open)")
        return True, None

    # ------------------------------------------------------------ ACK entries (PROTOCOL.md 4.3)

    def _ack_check(self, y: str, now: float) -> None:
        """Live ACK entries for report y: send each locked key's first one (once per key and report) while the
        report is at most late_max_s old. Works from the persisted lock and path store (R25)."""
        if not self.cfg.acks:
            return
        ledger, stats = self.b.ledger, self.b.stats
        entries = ledger.ack_match(y, now)
        if not entries:
            return
        rp = ledger.report_path(y, now - self.cfg.report_path_ttl_s)
        if rp is None:
            return  # not heard (yet): a copy that reaches us later triggers it
        if now - rp.heard_at > self.cfg.late_max_s:
            stats.inc("lt_ack_too_late")
            return
        keys = ledger.locked(y)
        for e in entries:
            if e.pubkey not in keys:
                stats.inc("lt_ack_unsigned")
                continue
            plan = self._plan_record("ack", e, rp, now)
            if plan is None:
                stats.inc("lt_ack_not_from_here")  # heard over 3+ hops, a DIRECT copy without near, no path
                fieldlog.ev("tx_skip", id=e.event_id, y=y, xa=e.payload[:4],
                            reason="no_path" if e.route == "direct" else "not_direct")
                ledger.answer(f"ack:{e.event_id}", now)
                ledger.mailbox_mark_sent(e.event_id, now)  # never sent from here: never due again
                continue
            if not ledger.answer(f"ack:{y}:{e.pubkey}", now):
                stats.inc("lt_ack_already")
                continue
            self._inflight(y)
            self.b._spawn(self._deliver_ack(e, plan, y, rp, None))

    def _ack_datagram(self, entry: MailboxEntry, plan: Plan) -> tuple[bytes, bytes]:
        """(the 13 B Pa this send carries, the datagram on air)."""
        two = len(entry.payload) == 2 * ACK_LEN
        if plan.form == "near":
            pa = entry.payload[:ACK_LEN]
            return pa, pa
        pa = entry.payload[ACK_LEN:] if two else entry.payload[:ACK_LEN]
        return pa, quietmesh.ack_routed(pa, plan.path_hash_size, plan.tag_path)

    async def _deliver_ack(self, entry: MailboxEntry, plan: Plan, y: str, rp: ReportPath, at: int | None,
                           key: str | None = None) -> bool:
        key = key or y
        try:
            return await self._deliver_ack_inner(entry, plan, y, rp, at)
        finally:
            self._ack_finished(key)

    async def _deliver_ack_inner(self, entry: MailboxEntry, plan: Plan, y: str, rp: ReportPath,
                                 at: int | None) -> bool:
        stats, ledger, cfg = self.b.stats, self.b.ledger, self.cfg
        pa, dg = self._ack_datagram(entry, plan)
        what = "ack_near" if plan.form == "near" else "ack_routed"
        skip = lambda reason: fieldlog.ev("tx_skip", id=entry.event_id, y=y, xa=pa[:4], what=what,  # noqa: E731
                                          at=at, reason=reason)
        t0 = self.b.clock()
        if at is not None:
            wait = at - self.b.clock()
            if wait > 0:
                await asyncio.sleep(wait)
        elif plan.form == "near":
            await asyncio.sleep(self.b.rng.uniform(cfg.ack_jitter_min_s, cfg.ack_jitter_max_s))
        elif not rp.is_direct and cfg.claim:
            # the first routed ACK of a flood: several bridges may have heard it over hops
            won = await self._claim(entry, plan.hops, cfg.claim_routed_ack_s, None, y)
            if not won:
                stats.inc("lt_ack_lost_claim")
                skip("lost_claim")
                return False
            if any(t >= t0 - 1 for t, _ in self.ack_notes.get(y, ())):
                stats.inc("lt_ack_note_seen")  # a bridge in direct range answered already
                skip("ack_note")
                return False
        if ledger.payload_sent(b"ack" + pa):
            stats.inc("lt_ack_dup_on_air")
            skip("dup_on_air")
            return False
        if not ledger.mailbox_pending(entry.event_id):
            skip("cancelled")
            return False
        if ledger.ack_tx_count(self.b.clock() - 3600) >= cfg.acks_per_hour:
            stats.inc("lt_ack_hourly_cap")
            skip("hourly_cap")
            if at is not None:
                self._miss(entry, "blocked", self.b.clock())  # a timed ACK that falls due and is not sent
            return False
        until = (at + cfg.timed_retry_until_s) if at is not None else None
        max_tries = self._max_tries()
        tries = 0
        while True:
            tries += 1
            ok = self.b.health.can_send() and await self.b.radio_send(
                frames.CHANNEL_LIBRETAG, frames.DATA_TYPE_LIBRETAG, dg, plan.route, plan.path_hash_size, spend=False,
                priority_now=True, log_fields={"what": what, "xa": pa[:4], "id": entry.event_id, "y": y, "at": at,
                                               "tries": tries,
                                               "late_ms": round((self.b.clock() - at) * 1000) if at else None})
            if ok or until is None or self.b.clock() + cfg.timed_retry_s > until or tries >= max_tries:
                break
            await asyncio.sleep(cfg.timed_retry_s)
        if not ok:
            if at is not None:
                self._miss(entry, "radio", self.b.clock())
            return False
        now = self.b.clock()
        ledger.ack_tx_add(now)
        ledger.payload_mark_sent(b"ack" + pa, now)
        ledger.mailbox_mark_sent(entry.event_id, now)
        stats.inc("lt_ack_sent_near" if plan.form == "near" else "lt_ack_sent_routed")
        if plan.form == "routed":
            ledger.report_path_touch(y, now)
            for v in entry.via_list:
                ledger.report_path_touch(v, now)  # R33: the via record and its lock live on
            if rp.is_direct and not rp.path:
                ledger.report_path_learn(y, plan.tag_path, plan.path_hash_size, now)  # the path under the new x
            self.path_sends[y] = {"t": now, "via": entry.via, "hops": len(plan.tag_path)}
            while len(self.path_sends) > 2048:
                self.path_sends.popitem(last=False)
        log.info("LT2 ACK for %s sent %s%s", y, plan.form, f" along {len(plan.route)} hops" if plan.route else "")
        tags = [["y", y], ["ack", NOTE_ACK_NEAR if plan.form == "near" else NOTE_ACK_ROUTED], ["e", entry.event_id]]
        if plan.form == "routed" and plan.path_x:
            tags.append(["via", plan.path_x])  # the path record the tag's next routed copy names (PROTOCOL.md 4.3)
        if at is not None:
            tags.append(["at", str(at)])
        self._note(tags, now)
        return True

    # ------------------------------------------------------------ timed entries (slots, PROTOCOL.md 2.6)

    async def timed_loop(self) -> None:
        while True:
            self.timed_tick(self.b.clock())
            await asyncio.sleep(TIMED_TICK_S)

    def _max_tries(self) -> int:
        """Tries of a timed send within its retry window (also a bound when the clock does not move)."""
        return int(self.cfg.timed_retry_until_s / max(self.cfg.timed_retry_s, 0.001)) + 1

    def _final(self, rows: list[MailboxEntry], now: float) -> None:
        for e in rows:
            self.b.ledger.answer(e.y, now)

    def timed_tick(self, now: float) -> None:
        """Start the delivery of every timed entry whose claim time (`at` - claim lead) has come. An entry is
        answered (never due again) only once its outcome is known: sent, missed, or not for this bridge."""
        ledger, cfg = self.b.ledger, self.cfg
        groups: dict[str, list[MailboxEntry]] = {}
        for e in ledger.mailbox_timed_due(now + cfg.timed_claim_lead_s, now):
            if e.event_id not in self.timed_busy:
                groups.setdefault(e.event_id, []).append(e)
        # ACKs first: a command for the same slot waits for its ACK
        for rows in sorted(groups.values(), key=lambda r: (r[0].at, 0 if r[0].ack else 1)):
            entry = rows[0]
            rp = self._via_record(entry, now)
            if rp is None and not entry.blind:
                self.b.stats.inc("lt_cmd_timed_no_path")  # never caught report `via`: not near the tracker
                self._final(rows, now)
                continue
            if rp is not None and rp.x != entry.via:
                ledger.entry_mark_vias(entry.event_id, rp.x)
                entry.via = rp.x
            if now > entry.at + cfg.timed_retry_until_s:
                self._miss(entry, "late", now)  # the bridge was down (or busy) at `at`
                self._final(rows, now)
                continue
            self.timed_busy.add(entry.event_id)
            if entry.ack:
                self._inflight(f"{entry.via}@{entry.at}")
            self.b._spawn(self._deliver_timed(entry, rows, rp))

    async def _deliver_timed(self, entry: MailboxEntry, rows: list[MailboxEntry], rp: ReportPath | None) -> None:
        stats, ledger, cfg = self.b.stats, self.b.ledger, self.cfg
        key = f"{entry.via}@{entry.at}"
        try:
            now = self.b.clock()
            if rp is not None and entry.pubkey not in ledger.locked(entry.via):
                stats.inc("lt_cmd_unsigned")  # not signed by the key report `via` committed to
                return
            if rp is not None and not (rp.heard_at - cfg.timed_max_late_s <= entry.at
                                       <= rp.heard_at + cfg.timed_max_after_report_s):
                stats.inc("lt_spam_timed_window")  # A1, for entries that came before report `via`
                return
            if entry.grp is not None and not ledger.group_done(entry.grp) and rp is not None:
                x = self._answered_before(entry, rp, now)
                if x is not None:  # the tracker reported again after this entry's anchor: the slot is useless
                    self._close_group(entry.grp, x, now, "next report, seen at the slot")
            if entry.grp is not None and ledger.group_done(entry.grp):
                stats.inc("lt_cmd_group_done")
                fieldlog.ev("tx_skip", id=entry.event_id, y=entry.y, at=entry.at, reason="group_done")
                return
            if entry.ack:
                plan = self._plan_record("ack", entry, rp, now)
                if plan is None:
                    self._miss_no_path(entry, now)
                    return
                if not ledger.answer(f"ack:{entry.via}@{entry.at}:{entry.pubkey}", now):
                    return  # one per timed slot
                if await self._deliver_ack(entry, plan, entry.y, rp, entry.at, key=key):
                    stats.inc("lt_ack_timed_sent")
                return
            if not entry.ack and ledger.payload_sent(entry.payload):
                ledger.mailbox_mark_sent(entry.event_id, now)
                stats.inc("lt_cmd_dup_payload")
                return
            plan = self._plan("cmd", entry, None, None, 1, None, blind=True) if rp is None else \
                self._plan_record("cmd", entry, rp, now)
            if plan is None:
                stats.inc("lt_cmd_route_too_far")
                self._miss_no_path(entry, now)
                return
            why = self._timed_blocked(entry, now) or self._send_blocked(entry, plan, now)
            if why:
                stats.inc(why)
                if why == "lt_cmd_group_done":
                    return
                self._miss(entry, "blocked", now)
                return
            wait = max(entry.at - self.b.clock(), 0.0)
            if cfg.claim:
                if not await self._claim(entry, plan.hops, max(wait, cfg.claim_wait_s)):
                    stats.inc("lt_cmd_lost_claim")
                    return
            elif wait:
                await asyncio.sleep(wait)
            await self._after_ack(key)
            until = entry.at + cfg.timed_retry_until_s
            tries = 0
            while True:
                tries += 1
                ok, why = await self._send(entry, plan)
                if ok or why != "radio" or self.b.clock() + cfg.timed_retry_s > until or tries >= self._max_tries():
                    break
                stats.inc("lt_cmd_timed_retry")
                await asyncio.sleep(cfg.timed_retry_s)
            if ok:
                stats.inc("lt_cmd_timed_sent")
                if entry.grp:
                    self.send_refs[entry.grp][entry.via] = rp.heard_at if rp else self.b.clock()
                self._arm_fallback(entry, plan, entry.via, self.b.clock())
            elif why in ("radio", "blocked"):
                self._miss(entry, why, self.b.clock())
        finally:
            self._final(rows, self.b.clock())
            self.timed_busy.discard(entry.event_id)
            fut = self.ack_inflight.pop(key, None) if entry.ack else None  # an ACK that never started
            if fut is not None and not fut.done():
                fut.set_result(None)

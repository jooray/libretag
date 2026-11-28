"""Quiet mesh (LT2, firmware 0.10.0): the shared wire-level parts every component implements against.

PROTOCOL.md 2.6, 2.7, 3.3 to 3.5 and 4.3; design and reasoning in docs/design/quiet-mesh.md. This module is
the reference for the encodings and the pure rules; the vectors it writes are the contract:

    uv run python -m libretag.quietmesh vectors > ../docs/vectors/quiet-mesh-v1.json

What is here: report copies 9, 10, 11 and their packets (zero-hop, along a path, scoped flood), the
SYNC flag, the 13 B ACK sealed with C_e(n) in its near and routed forms, the second channel data type of
a command, schedule anchors (slot key, slot offsets, windows, the tag's and the app's anchor rules),
bridge areas and the scope ladder, the hold field, and the bridge's route plan per packet.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import math
import struct
import sys
from dataclasses import dataclass, field, replace

from . import lt2
from .lt2 import (CHANNEL_DATA_TYPE, CMD_P_LEN, J_BORDER, J_HOME, J_NEAR, J_ON_AIR, J_ROUTED, J_WIDE, TAG_LEN,
                  Command, Keys, Mode, Report, channel_unwrap, channel_wrap, epoch, hmac_sha256, report_tag,
                  seal_command, seal_report_keys)
from .meshcore import (PAYLOAD_GRP_DATA, ROUTE_DIRECT, ROUTE_FLOOD, ROUTE_TRANSPORT_DIRECT, ROUTE_TRANSPORT_FLOOD,
                       Packet)

# ---------------------------------------------------------------- constants (SPEC.md 3.2, defaults)

J_FLOODS = (J_HOME, J_BORDER, J_WIDE)        # copies that go out as floods
J_DIRECT = (J_NEAR, J_ROUTED)                # copies that go out on a DIRECT route
CHANNEL_DATA_TYPE_RETRY = 0xFF6C             # the fallback (second) send of a command
ACK_LEN = 13
ACK_INFO_HOLD = 0x0F                         # bits 0..3: hold in units of 3 h (0 = every report)
ACK_INFO_MORE = 0x10                         # a command follows: keep the window open
ACK_INFO_ROUTED = 0x20                       # routed form: the bridge appended a path
ACK_INFO_RESERVED = 0xC0
HOLD_UNIT_S = 3 * 3600
HOLD_DEFAULT = 4                             # 12 h
HOLD_MIN_EXTRA_S = 300                       # hold never shorter than one period + 5 min (unless 0)
PATH_MAX_HOPS = 2                            # direct_max_hops default; the tag takes 1 or 2 entries
STEALTH_NEAR_LEN = ACK_LEN + 6               # 19 B, the length of a channel ACK payload
STEALTH_CMD_LEN = 35                         # a routed ACK in stealth is padded to a command's payload
ACK_DECIDE_S = 12                            # decision point after a hello (immediate cases)
ACK_PER_HOP_MS = 1500                        # + 1.5 s per hop after a routed copy (field walk 2026-11-26: 0.6-1.2 s a hop)
WIDE_AFTER_S = 8                             # copy 10 when copy 0 was not heard repeated within 8 s
FALLBACK_AFTER_S = 12                        # bridge: flood fallback of a command after a cheap send
SLOT_SEND_AFTER_S = 1                        # senders aim at t_k + 1 s (never earlier)
SLOT_GUARD_BASE_MS = 2000
PREV_GRID_SLOTS = 3                          # previous grid kept for 3 slots after a flood moved the anchor
MAX_CANDIDATES = 2                           # app: confirmed anchor plus at most two candidates
LATE_MAX_S = 40
MOVE_M = 300
IDLE_FLOOD_BUDGET_24H = 8
FLOOD_CAP_24H = 200
TIMED_ACK_SLOTS_NEAR = 2
TIMED_ACK_SLOTS_ROUTED = 1
CLAIM_ROUTED_ACK_S = 2.0
PATH_RECORD_TTL_S = 48 * 3600                # from the last use (R33)
UNTIMED_AHEAD = 10                           # untimed command entries reach 10 counters ahead (R34)
HASH_SIZES = (1, 2, 3)


# ---------------------------------------------------------------- copies and their packets (PROTOCOL.md 2, 2.6)

def report_tags(view_e: bytes, n: int) -> dict[int, bytes]:
    """x of every report copy on air: what an anchor names as `y` (five per counter)."""
    return {j: report_tag(view_e, n, j) for j in J_ON_AIR}


def scope_key(name: str) -> bytes:
    """MeshCore region key: SHA-256("#" + name)[0:16] (a leading '#' is not doubled)."""
    n = name if name.startswith("#") else "#" + name
    return hashlib.sha256(n.encode()).digest()[:16]


def transport_code(name: str, payload_type: int, payload: bytes) -> int:
    """code[0] = HMAC-SHA256(key, payload_type || payload)[0:2] LE, 0 -> 1, 0xFFFF -> 0xFFFE."""
    h = hmac.new(scope_key(name), bytes([payload_type]) + payload, hashlib.sha256).digest()
    c = h[0] | h[1] << 8
    return 1 if c == 0 else 0xFFFE if c == 0xFFFF else c


def scoped_flood(payload: bytes, scope: str | None, hash_size: int) -> Packet:
    """A GRP_DATA flood; with a scope TRANSPORT_FLOOD with code[0] for it and code[1] = 0."""
    if scope is None:
        return Packet(ROUTE_FLOOD, PAYLOAD_GRP_DATA, payload, path_hash_size=hash_size)
    tc = struct.pack("<HH", transport_code(scope, PAYLOAD_GRP_DATA, payload), 0)
    return Packet(ROUTE_TRANSPORT_FLOOD, PAYLOAD_GRP_DATA, payload, path_hash_size=hash_size, transport_codes=tc)


def direct(payload: bytes, path: list[bytes], hash_size: int) -> Packet:
    """A plain DIRECT GRP_DATA (never with transport codes: DIRECT is not region filtered). An empty
    path is the zero-hop form; path_len then carries the hash size bits with count 0."""
    return Packet(ROUTE_DIRECT, PAYLOAD_GRP_DATA, payload, path=list(path), path_hash_size=hash_size)


def report_copy_packet(keys: Keys, n: int, j: int, report: Report, *, scope: str | None = None,
                       path: list[bytes] | None = None, hash_size: int = 2) -> Packet:
    """Copy j of report n as the tag sends it: 9 zero-hop, 11 DIRECT along `path` (tag to bridge order,
    1 or 2 entries), 0, 1 and 10 as floods in `scope` (None = unscoped)."""
    payload = channel_wrap(seal_report_keys(keys, n, j, report))
    if j == J_NEAR:
        return direct(payload, [], hash_size)
    if j == J_ROUTED:
        if not path or not 1 <= len(path) <= PATH_MAX_HOPS:
            raise ValueError("copy 11 needs a path of 1 or 2 entries")
        return direct(payload, path, hash_size)
    if j in J_FLOODS:
        return scoped_flood(payload, scope, hash_size)
    raise ValueError(f"copy {j} is not a report copy on air")


def arrival(pkt: Packet) -> dict:
    """What a receiver may conclude from how a packet arrived (R36): a flood with an empty path has 0
    hops; a DIRECT packet that reached us (empty path) has an unknown hop count, because a packet that
    came along a path ends with an empty path too. heard_direct: DIRECT, or a flood with 0 hops."""
    route = {ROUTE_FLOOD: "flood", ROUTE_TRANSPORT_FLOOD: "tflood", ROUTE_DIRECT: "direct",
             ROUTE_TRANSPORT_DIRECT: "tdirect"}[pkt.route_type]
    is_direct = route in ("direct", "tdirect")
    if is_direct and pkt.path:
        return {"route": route, "for_us": False, "hops": None, "heard_direct": False, "path": []}
    hops = None if is_direct else len(pkt.path)
    return {"route": route, "for_us": True, "hops": hops, "heard_direct": is_direct or hops == 0,
            "path": [h.hex() for h in pkt.path]}


# ---------------------------------------------------------------- the ACK (PROTOCOL.md 3.3)

def _ck(n: int, k: int) -> bytes:
    """The ACK always appends u16(k), also for k = 0."""
    return struct.pack("<IH", n & 0xFFFFFFFF, k & 0xFFFF)


def ack_key(cmd_e: bytes, n: int, k: int) -> bytes:
    return hmac_sha256(cmd_e, b"LT2-a" + _ck(n, k))


def ack_tag(cmd_e: bytes, n: int, k: int) -> bytes:
    """xa: the 4 B the tag compares before any other work."""
    return hmac_sha256(cmd_e, b"LT2-ax" + _ck(n, k))[:TAG_LEN]


def ack_info(hold: int, more: bool = False, routed: bool = False) -> int:
    if not 0 <= hold <= ACK_INFO_HOLD:
        raise ValueError("hold is 0..15 (units of 3 h)")
    return hold | (ACK_INFO_MORE if more else 0) | (ACK_INFO_ROUTED if routed else 0)


def ack_seal(cmd_e: bytes, n: int, k: int, hold: int = HOLD_DEFAULT, more: bool = False,
             routed: bool = False, *, info: int | None = None) -> bytes:
    """Pa(n, k) = xa || ct || mac (13 B). `info` overrides the field (vectors with reserved bits)."""
    ka = ack_key(cmd_e, n, k)
    xa = ack_tag(cmd_e, n, k)
    i = ack_info(hold, more, routed) if info is None else info & 0xFF
    ct = bytes([i ^ ka[0]])
    mac = hmac_sha256(ka[16:32], xa + ct)[:8]
    return xa + ct + mac


@dataclass(frozen=True)
class Ack:
    hold: int
    more: bool
    routed: bool
    hash_size: int = 0
    path: tuple[bytes, ...] = ()   # routed form: repeater hashes in the order tag to bridge


def ack_open13(cmd_e: bytes, n: int, k: int, pa: bytes) -> Ack | None:
    """Open a 13 B Pa for (n, k): tag, mac, reserved bits. The form (near or routed) is in the bit."""
    if len(pa) != ACK_LEN or not hmac.compare_digest(pa[:TAG_LEN], ack_tag(cmd_e, n, k)):
        return None
    ka = ack_key(cmd_e, n, k)
    if not hmac.compare_digest(hmac_sha256(ka[16:32], pa[:5])[:8], pa[5:13]):
        return None
    i = pa[4] ^ ka[0]
    if i & ACK_INFO_RESERVED:
        return None
    return Ack(hold=i & ACK_INFO_HOLD, more=bool(i & ACK_INFO_MORE), routed=bool(i & ACK_INFO_ROUTED))


def path_len_byte(hash_size: int, count: int) -> int:
    if hash_size not in HASH_SIZES or not 0 <= count <= 63:
        raise ValueError("bad path")
    return (hash_size - 1) << 6 | count


def ack_routed(pa: bytes, hash_size: int, path: list[bytes]) -> bytes:
    """Routed form, built by the bridge: Pa (with ROUTED) || pl || path, path in the order tag to bridge.
    The path is not under the mac (the app does not know it, and it never goes through Nostr)."""
    if len(pa) != ACK_LEN or not 1 <= len(path) <= PATH_MAX_HOPS or any(len(h) != hash_size for h in path):
        raise ValueError("routed ACK needs Pa and 1 or 2 path entries of the hash size")
    return pa + bytes([path_len_byte(hash_size, len(path))]) + b"".join(path)


def ack_open(cmd_e: bytes, n: int, k: int, datagram: bytes, stealth: bool = False) -> Ack | None:
    """The tag's check of an ACK datagram (channel content, or the stealth GRP_DATA payload).
    Near form: exactly 13 B with ROUTED 0 (stealth: 19 B, the last 6 random). Routed form: ROUTED 1,
    then pl and 1 or 2 entries of 1..3 B, nothing after (stealth: random padding to 35 B). Refused: a
    wrong tag or mac, reserved bits, ROUTED without a path, a path after a near form, 0 or 3+ entries,
    a length that does not match pl. Whether the packet came on a DIRECT route with an empty path is
    checked by the caller (a routed ACK is taken only that way)."""
    if len(datagram) < ACK_LEN:
        return None
    a = ack_open13(cmd_e, n, k, datagram[:ACK_LEN])
    if a is None:
        return None
    rest = datagram[ACK_LEN:]
    if not a.routed:
        if stealth:
            return a if len(datagram) == STEALTH_NEAR_LEN else None
        return a if not rest else None
    if not rest:
        return None
    hs, count = (rest[0] >> 6) + 1, rest[0] & 0x3F
    if hs not in HASH_SIZES or not 1 <= count <= PATH_MAX_HOPS:
        return None
    need = 1 + hs * count
    if len(rest) < need or (not stealth and len(rest) != need) or (stealth and len(datagram) != STEALTH_CMD_LEN):
        return None
    path = tuple(rest[1 + i * hs:1 + (i + 1) * hs] for i in range(count))
    return replace(a, hash_size=hs, path=path)


def ack_stealth(datagram: bytes, pad: bytes) -> bytes:
    """Stealth payload: near form + 6 random bytes (19 B); routed form padded to 35 B with random bytes."""
    want = STEALTH_NEAR_LEN if len(datagram) == ACK_LEN else STEALTH_CMD_LEN
    if len(pad) < want - len(datagram):
        raise ValueError("pad too short")
    return datagram + pad[:want - len(datagram)]


def hold_s(hold: int, period_s: int) -> int | None:
    """How long a NEAR tag may go without the next ACK before a report has to arrive. None for hold 0:
    every report has to arrive (a guarded tag). Otherwise hold x 3 h, never less than one report period
    plus 5 minutes, so a hold shorter than the period cannot make every report a flood."""
    if hold == 0:
        return None
    return max(hold * HOLD_UNIT_S, period_s + HOLD_MIN_EXTRA_S)


def ack_wait_ms(hops: int = 0) -> int:
    """Decision point after the cheap copy where the decision is immediate: 12 s, + 1.5 s per hop."""
    return ACK_DECIDE_S * 1000 + ACK_PER_HOP_MS * hops


# ---------------------------------------------------------------- command channel data types (PROTOCOL.md 3)

def command_channel_payload(pc: bytes, retry: bool = False) -> bytes:
    """First send 0xFF4C; the fallback send 0xFF6C, so repeaters that saw the first do not drop it."""
    return channel_wrap(pc, data_type=CHANNEL_DATA_TYPE_RETRY if retry else CHANNEL_DATA_TYPE)


def command_from_channel(payload: bytes) -> tuple[bytes, int] | None:
    """The tag accepts a command (29 B) under either data type: (Pc, data type) or None."""
    for dt in (CHANNEL_DATA_TYPE, CHANNEL_DATA_TYPE_RETRY):
        inner = channel_unwrap(payload, data_type=dt)
        if inner is not None:
            return (inner, dt) if len(inner) == CMD_P_LEN else None
    return None


# ---------------------------------------------------------------- schedule anchor and slots (PROTOCOL.md 2.1)

def slot_key(cmd_e: bytes, n_a: int) -> bytes:
    """ks = HMAC(C_e(n_a), "LT2-slot" || u32(n_a)): kept by the tag, so the ratchet may move on."""
    return hmac_sha256(cmd_e, b"LT2-slot" + struct.pack("<I", n_a & 0xFFFFFFFF))


def _slot_kd(ks: bytes, k: int) -> bytes:
    return hmac_sha256(ks, b"LT2-c" + struct.pack("<H", k & 0xFFFF))


def slot_command_tag(ks: bytes, k: int) -> bytes:
    return hmac_sha256(ks, b"LT2-cx" + struct.pack("<H", k & 0xFFFF))[:TAG_LEN]


def seal_slot_command(ks: bytes, k: int, cmd: Command) -> bytes:
    """A command for slot k >= 1 of the anchor's grid (k = 0 is the listen window of a report and keeps
    lt2.seal_command(C_e(n), n, cmd))."""
    if not 1 <= k <= 0xFFFF:
        raise ValueError("slot k >= 1")
    return lt2._seal(_slot_kd(ks, k), slot_command_tag(ks, k), cmd.encode())


def open_slot_command(ks: bytes, k: int, pc: bytes) -> Command | None:
    if len(pc) != CMD_P_LEN or pc[:TAG_LEN] != slot_command_tag(ks, k):
        return None
    pt = lt2._open(_slot_kd(ks, k), pc)
    if pt is None:
        return None
    try:
        return Command.decode(pt)
    except ValueError:
        return None


def slot_jitter(ks: bytes, i: int, base_s: int) -> int:
    """jit_i = (u16_le(HMAC(ks, "LT2-w" || u16(i))[0:2]) mod span) - floor(span / 2), span = base / 5."""
    span = base_s // 5
    if span == 0:
        return 0
    h = hmac_sha256(ks, b"LT2-w" + struct.pack("<H", i & 0xFFFF))
    return (h[0] | h[1] << 8) % span - span // 2


def slot_offsets(ks: bytes, base_s: int, count: int) -> list[int]:
    """t_k - t0 for k = 1..count on the anchor's grid."""
    out, t = [], 0
    for k in range(1, count + 1):
        t += base_s + slot_jitter(ks, k, base_s)
        out.append(t)
    return out


def slot_guard_ms(offset_s: int) -> int:
    """g_k = 2 s + (t_k - t0) x 100 ppm."""
    return SLOT_GUARD_BASE_MS + offset_s // 10


def slot_window_ms(offset_s: int, window_s: int, early_s: int = 0) -> tuple[int, int]:
    """Receiver on, relative to t_k in ms: from -(g_k + early) to window + g_k. `early` is the wait
    between the first copy and the flood that set the anchor (12, 13 or 14 s), else 0."""
    g = slot_guard_ms(offset_s)
    return -(g + early_s * 1000), window_s * 1000 + g


def slot_send_at(t0: int, offset_s: int) -> int:
    """`at` of a timed entry, unix s: t_k + 1 s (the fallback leaves 12 s later)."""
    return t0 + offset_s + SLOT_SEND_AFTER_S


@dataclass(frozen=True)
class Grid:
    n: int          # the anchor report
    t0: int         # ms: its first transmission (hello, routed copy) or the flood's transmission
    early_s: int = 0


@dataclass
class TagSchedule:
    """The tag's anchor (PROTOCOL.md 2.1): moves on a flood copy sent and on an ACK in the listen window
    of its own report, nothing else. After a flood the previous grid stays for 3 slots."""
    anchor: Grid | None = None
    prev: Grid | None = None
    prev_slots_left: int = 0

    def sync_for(self, n: int) -> bool:
        """SYNC of report n: the anchor before n went out is n - 1."""
        return self.anchor is not None and self.anchor.n == n - 1

    def on_flood_sent(self, n: int, t_flood: int, t_first: int | None = None) -> None:
        if self.anchor is not None and self.anchor.n == n:
            return  # copy 10 or copy 1 of the same report: the anchor is already the first flood copy
        early = 0 if t_first is None else math.ceil((t_flood - t_first) / 1000)
        if self.anchor is not None:
            self.prev, self.prev_slots_left = self.anchor, PREV_GRID_SLOTS
        self.anchor = Grid(n, t_flood, early)

    def on_window_ack(self, n: int, t_first: int) -> None:
        """An ACK for (n, 0) in the listen window of report n: the app has n, so nothing old is kept."""
        if self.anchor is None or self.anchor.n != n:
            self.anchor = Grid(n, t_first, 0)
        self.prev, self.prev_slots_left = None, 0

    def on_prev_slot(self) -> None:
        """A slot of the previous grid passed (listened or skipped)."""
        if self.prev is not None:
            self.prev_slots_left -= 1
            if self.prev_slots_left <= 0:
                self.prev, self.prev_slots_left = None, 0

    def on_reboot(self) -> None:
        self.anchor, self.prev, self.prev_slots_left = None, None, 0


@dataclass
class AppAnchor:
    """The app's view of a tag's anchor (R1, R32): one confirmed grid A and at most two candidates,
    so three grids at most. `known` False: show "schedule not known" (grids are still used for sends)."""
    a: Grid | None = None
    cands: list[Grid] = field(default_factory=list)
    last_n: int = -1
    seen: dict[int, int] = field(default_factory=dict)   # n -> t0 (ms) of the first copy the app has
    known: bool = True

    def grids(self) -> list[Grid]:
        return ([self.a] if self.a else []) + list(self.cands)

    def on_report(self, n: int, j: int, t: int, sync: bool, live_ack: bool = False, still: bool = True,
                  due_in_gap: bool = False) -> None:
        """Report n, copy j, exact time t (ms; approximate reports are not passed in). `live_ack`: the app
        sent a live ACK for it; `still`: a fix within MOVE_M of the last one and no BOOT flag;
        `due_in_gap`: a heartbeat or hold flood fell due between the last report and this one."""
        flood = j in J_FLOODS
        if n < self.last_n:
            return  # older than the newest: history only (R8)
        if n == self.last_n:  # second sighting of the newest report
            if flood:
                # the tag's t0 is its first flood copy (copy 10 or 1 does not move it): an A already on
                # n (set by an earlier flood copy) keeps its time; a hello or routed copy seen first does not
                if not (self.a and self.a.n == n):
                    self.a = Grid(n, t)
                self.cands, self.known = [], True
            elif live_ack and not any(c.n == n for c in self.cands) and not (self.a and self.a.n == n):
                self._add_cand(Grid(n, self.seen.get(n, t)))
            return
        m = self.last_n
        if sync:
            g = next((x for x in self.grids() if x.n == n - 1), None)
            if g is None and n - 1 in self.seen:
                g = Grid(n - 1, self.seen[n - 1])
            self.a, self.cands = g, []
            self.known = g is not None
        elif m >= 0:
            if n == m + 1:
                self.cands = [c for c in self.cands if c.n != m]
            elif n > m + 2 and not (still and not due_in_gap):
                self.known = False
        self.seen[n] = t
        self.seen = {k: v for k, v in self.seen.items() if k >= n - 8}
        self.last_n = n
        if flood:
            self.a, self.cands, self.known = Grid(n, t), [], True
        elif live_ack:
            self._add_cand(Grid(n, t))

    def _add_cand(self, g: Grid) -> None:
        if len(self.cands) >= MAX_CANDIDATES:
            self.known = False
            return
        self.cands.append(g)

    def on_command_confirmed(self, n_grid: int) -> None:
        """A confirmed command settles the grids: the send it answered was on grid n_grid."""
        g = next((x for x in self.grids() if x.n == n_grid), None)
        if g is not None:
            self.a, self.cands, self.known = g, [], True


def pair_fallback(slot_times: list[int], lost_or_track: bool) -> list[bool]:
    """Fallback flag per send of one command over several grids: only the latest slot keeps the flood
    fallback (the others carry ["fb","0"]), except Lost and Track, where every slot keeps it (R3, R35)."""
    if lost_or_track or not slot_times:
        return [True] * len(slot_times)
    last = max(slot_times)
    return [t == last for t in slot_times]


# ---------------------------------------------------------------- bridge areas and the scope ladder (PROTOCOL.md 2.7)

@dataclass(frozen=True)
class Area:
    lat: float
    lon: float
    radius_km: float
    scope: str
    network: str        # the home network this row is for (country row and radio preset)


EARTH_R_KM = 6371.0


def distance_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_R_KM * math.asin(min(1.0, math.sqrt(a)))


def area_at(lat: float | None, lon: float | None, areas: list[Area], home_network: str) -> Area | None:
    """The first row (state-file rows first, then baked rows) whose circle holds the last fix and whose
    network is the tag's home network. No fix ever: no area."""
    if lat is None or lon is None:
        return None
    for a in areas:
        if a.network == home_network and distance_km(lat, lon, a.lat, a.lon) <= a.radius_km:
            return a
    return None


def levels(area_scope: str | None, country_scope: str | None, same_radio_border: bool) -> list[str | None]:
    """Flood scopes narrow to wide: the area's, the country's (skipped near a border to a neighbour on
    the same radio with another or no region), then unscoped (None), which is always last."""
    out: list[str | None] = []
    if area_scope:
        out.append(area_scope)
    if country_scope and not same_radio_border and country_scope not in out:
        out.append(country_scope)
    out.append(None)
    return out


@dataclass
class Ladder:
    """`lvl`: index into levels() of the next flood. Widened after a flood without evidence (an ACK, a
    command, or a repeater heard repeating it); back to 0 after an ACK, on entering or leaving an area,
    and 24 h after it widened."""
    lvl: int = 0
    widened_at: int | None = None   # s


def ladder_after_flood(lad: Ladder, n_levels: int, evidence: bool, important: bool, now: int) -> tuple[int | None, Ladder]:
    """After flood copy 0 at lad.lvl. Returns (level of copy 10 to send now or None, the new ladder).
    `important`: a flood that matters (moved, hold ran out, heartbeat, boot, command answer, low
    battery, every TRACK and LOST report) gets copy 10 one level wider in the same wake-up; the repeated
    floods of a tag without a fix do not, and their next flood goes one level wider."""
    if evidence:
        return None, lad
    wider = min(lad.lvl + 1, n_levels - 1)
    if wider == lad.lvl:
        return None, lad
    return (wider if important else None), Ladder(wider, now)


def ladder_reset(lad: Ladder, now: int, ack: bool = False, area_changed: bool = False) -> Ladder:
    if ack or area_changed or (lad.widened_at is not None and now - lad.widened_at >= 24 * 3600):
        return Ladder(0, None)
    return lad


def idle_unscoped_ok(last_unscoped_idle: int | None, now: int) -> bool:
    """In IDLE an unscoped flood goes out at most once in 24 h; otherwise the widest scoped level is used."""
    return last_unscoped_idle is None or now - last_unscoped_idle >= 24 * 3600


# ---------------------------------------------------------------- the bridge's route plan (PROTOCOL.md 4.3)

def plan(kind: str, heard: dict | None, entry_route: str, *, via_path: list[str] | None = None,
         forms: str = "near", fb: bool = True, max_hops: int = PATH_MAX_HOPS, can_zero_hop: bool = True,
         can_path: bool = True, blind: bool = False) -> dict:
    """How a bridge sends an ACK or command entry for the report (or `via`) it heard.

    heard: {"route": flood|tflood|direct|tdirect, "path": [hex, ...]} as the packet arrived, None when the
    bridge did not hear it. entry_route: commands near|direct|flood|both, ACK entries near|direct.
    via_path: the path stored for `via` (tag to bridge order), for a report that arrived DIRECT.
    forms (ACK entries): "near" (13 B near form), "both" (26 B: near and routed), "routed" (13 B routed).
    Returns {"send": none|zero-hop|path|flood, "path": [...] (send order, bridge to tag), "form": near|
    routed (ACKs), "fallback": bool (a 0xFF6C flood 12 s later without an answer), "scope": report|
    narrowest|country (the flood's or fallback's scope)}.
    """
    none = {"send": "none", "path": [], "fallback": False}
    paths_ok = can_path and max_hops > 0
    if heard is None:
        if kind == "cmd" and blind:
            return {"send": "flood", "path": [], "fallback": False, "scope": "country"}
        return none
    route, path = heard["route"], list(heard.get("path") or [])
    is_direct = route in ("direct", "tdirect")
    hops = None if is_direct else len(path)
    stored = list(via_path or []) if is_direct else path
    usable = paths_ok and 1 <= len(stored) <= max_hops

    if kind == "ack":
        if not is_direct and hops == 0:
            if forms in ("near", "both") and can_zero_hop:
                return {"send": "zero-hop", "path": [], "form": "near", "fallback": False}
            return none
        if is_direct and entry_route == "near":
            if forms in ("near", "both") and can_zero_hop:
                return {"send": "zero-hop", "path": [], "form": "near", "fallback": False}
            return none
        if entry_route == "direct" and forms in ("both", "routed") and usable:
            return {"send": "path", "path": list(reversed(stored)), "form": "routed", "fallback": False,
                    "tag_path": stored}
        return none  # never a flood for an ACK

    # commands: near = zero-hop only; direct = zero-hop or path, never a flood; flood = the cheapest
    # way from where the report was heard; both = flood plus the fallback after a zero-hop send
    may_flood = entry_route in ("flood", "both")
    if is_direct or hops == 0:
        if is_direct and usable and entry_route != "near":
            return {"send": "path", "path": list(reversed(stored)), "fallback": fb and may_flood,
                    "scope": "report"}
        scope = "narrowest" if is_direct else "report"   # a DIRECT arrival carries no scope
        if can_zero_hop:
            return {"send": "zero-hop", "path": [], "fallback": fb and entry_route == "both", "scope": scope}
        return {"send": "flood", "path": [], "fallback": False, "scope": scope} if may_flood else none
    if entry_route == "near":
        return none
    if usable:
        return {"send": "path", "path": list(reversed(stored)), "fallback": fb and may_flood, "scope": "report"}
    return {"send": "flood", "path": [], "fallback": False, "scope": "report"} if may_flood else none


# ---------------------------------------------------------------- ACK entries (PROTOCOL.md 4.3)

def ack_entry_content(cmd_e: bytes, n: int, k: int, form: str, hold: int = HOLD_DEFAULT, more: bool = False) -> str:
    """base64 content of an ACK entry: near (13 B near form), both (26 B: near || routed), routed (13 B)."""
    near = ack_seal(cmd_e, n, k, hold, more, routed=False)
    routed = ack_seal(cmd_e, n, k, hold, more, routed=True)
    raw = {"near": near, "both": near + routed, "routed": routed}[form]
    return base64.b64encode(raw).decode()


# ---------------------------------------------------------------- vectors

AREAS_FOR_VECTORS = [
    # synthetic rows: a test area and a smaller one inside it on another scope (state-file row first)
    Area(48.7350, 21.2900, 2.0, "sk-test2", "sk"),
    Area(48.7000, 21.2500, 12.0, "sk-test", "sk"),
    Area(48.7000, 21.2500, 12.0, "hu-test", "hu"),
]


def _hexs(b: bytes) -> str:
    return b.hex()


def vector_copies(keys: Keys) -> list[dict]:
    rep = Report(has_fix=True, fix_time=1763500000, lat=48.701000, lon=21.252000, hacc_m=6, battery_pct=80,
                 sync=True, next_in_s=3 * 3600, energy_mah=120, nb_total=1,
                 neighbours=[(bytes.fromhex("a5e1"), 8.5)])
    out = []
    p1, p2 = [bytes.fromhex("a5e1")], [bytes.fromhex("a5e1"), bytes.fromhex("b7d2")]
    for n in (300, 301):
        ve = keys.view(epoch(n))
        c = {"n": n, "pt": rep.encode().hex(), "pk": lt2.mailbox_pk(keys.cmd(epoch(n)), n).hex(),
             "tags": {str(j): x.hex() for j, x in report_tags(ve, n).items()}, "copies": []}
        for j, kw in ((J_HOME, {"scope": "sk-test"}), (J_BORDER, {"scope": None}), (J_NEAR, {}),
                      (J_WIDE, {"scope": "sk"}), (J_ROUTED, {"path": p1}), (J_ROUTED, {"path": p2})):
            pkt = report_copy_packet(keys, n, j, rep, hash_size=2, **kw)
            c["copies"].append({"j": j, "scope": kw.get("scope"), "path": [h.hex() for h in kw.get("path", [])],
                                "p": seal_report_keys(keys, n, j, rep).hex(), "packet": pkt.to_bytes().hex(),
                                "header": pkt.to_bytes()[0]})
        out.append(c)
    return out


def vector_acks(keys: Keys) -> dict:
    ce = keys.cmd(epoch(300))
    valid = []
    for n, k, hold, more in ((300, 0, 4, False), (300, 1, 4, False), (300, 7, 0, True), (301, 0, 8, True),
                             (300, 0xFFFF, 15, False)):
        pa = ack_seal(ce, n, k, hold, more)
        valid.append({"n": n, "k": k, "hold": hold, "more": more, "routed": False, "tag": ack_tag(ce, n, k).hex(),
                      "pa": pa.hex(), "channel_payload": channel_wrap(pa).hex(),
                      "packet": direct(channel_wrap(pa), [], 2).to_bytes().hex(),
                      "stealth": ack_stealth(pa, bytes(range(0xA0, 0xA6))).hex()})
    good = ack_seal(ce, 300, 1, 4)
    cmd = seal_command(ce, 300, Command.buzz(1, 5))
    invalid = [
        {"why": "wrong k", "n": 300, "k": 2, "datagram": good.hex()},
        {"why": "wrong n", "n": 301, "k": 1, "datagram": good.hex()},
        {"why": "a command's xc and bytes", "n": 300, "k": 0, "datagram": cmd[:ACK_LEN].hex()},
        {"why": "12 B", "n": 300, "k": 1, "datagram": good[:12].hex()},
        {"why": "14 B", "n": 300, "k": 1, "datagram": (good + b"\x00").hex()},
        {"why": "mac flipped", "n": 300, "k": 1, "datagram": (good[:12] + bytes([good[12] ^ 1])).hex()},
        {"why": "reserved bit 6 set", "n": 300, "k": 1, "datagram": ack_seal(ce, 300, 1, info=0x44).hex()},
        {"why": "reserved bit 7 set", "n": 300, "k": 1, "datagram": ack_seal(ce, 300, 1, info=0x84).hex()},
    ]
    return {"n_epoch_cmd_key": "cmd of epoch(300)", "valid": valid, "invalid": invalid}


def vector_acks_routed(keys: Keys) -> dict:
    ce = keys.cmd(epoch(300))
    valid = []
    for n, k, hs, path in ((300, 0, 2, ["a5e1"]), (300, 0, 2, ["a5e1", "b7d2"]), (300, 3, 1, ["c3", "d4"]),
                           (301, 0, 3, ["e8b9aa"])):
        pa = ack_seal(ce, n, k, 4, routed=True)
        hashes = [bytes.fromhex(h) for h in path]
        dg = ack_routed(pa, hs, hashes)
        valid.append({"n": n, "k": k, "hash_size": hs, "path": path, "pa": pa.hex(), "datagram": dg.hex(),
                      "channel_payload": channel_wrap(dg).hex(),
                      "packet": direct(channel_wrap(dg), list(reversed(hashes)), hs).to_bytes().hex(),
                      "last_hop_packet": direct(channel_wrap(dg), [], hs).to_bytes().hex(),
                      "stealth": ack_stealth(dg, bytes(range(0xB0, 0xC4))).hex()})
    pa_r = ack_seal(ce, 300, 0, 4, routed=True)
    pa_n = ack_seal(ce, 300, 0, 4, routed=False)
    two = bytes.fromhex("a5e1b7d2")
    invalid = [
        {"why": "ROUTED set, no path", "n": 300, "k": 0, "datagram": pa_r.hex()},
        {"why": "path after a near form", "n": 300, "k": 0, "datagram": (pa_n + bytes([0x41]) + two[:2]).hex()},
        {"why": "0 entries", "n": 300, "k": 0, "datagram": (pa_r + bytes([0x40])).hex()},
        {"why": "3 entries", "n": 300, "k": 0, "datagram": (pa_r + bytes([0x43]) + two + b"\xc3\xf4").hex()},
        {"why": "length does not match pl", "n": 300, "k": 0, "datagram": (pa_r + bytes([0x42]) + two[:3]).hex()},
        {"why": "reserved hash size 4", "n": 300, "k": 0, "datagram": (pa_r + bytes([0xC1]) + two).hex()},
        {"why": "mac flipped", "n": 300, "k": 0,
         "datagram": (pa_r[:12] + bytes([pa_r[12] ^ 0x80]) + bytes([0x41]) + two[:2]).hex()},
    ]
    return {"valid": valid, "invalid": invalid}


def vector_retry(keys: Keys) -> list[dict]:
    out = []
    for n, c in ((300, Command.buzz(21, 5)), (300, Command.set_mode(22, Mode.LOST, 600, 1440))):
        pc = seal_command(keys.cmd(epoch(n)), n, c)
        out.append({"n": n, "pc": pc.hex(), "pt": c.encode().hex(),
                    "payload_ff4c": command_channel_payload(pc).hex(),
                    "payload_ff6c": command_channel_payload(pc, retry=True).hex()})
    return out


def vector_slots(keys: Keys) -> dict:
    out = {"keys": [], "commands": [], "windows": [], "acks_at_slot": []}
    for n_a in (300, 305, 64):
        ks = slot_key(keys.cmd(epoch(n_a)), n_a)
        out["keys"].append({"n_a": n_a, "ks": ks.hex(), "base_s": 3600,
                            "offsets": slot_offsets(ks, 3600, 6),
                            "offsets_900": slot_offsets(ks, 900, 4),
                            "send_at": [slot_send_at(1763500000, o) for o in slot_offsets(ks, 3600, 3)]})
    ks = slot_key(keys.cmd(epoch(300)), 300)
    for k, c in ((1, Command.buzz(31, 5)), (2, Command.set_mode(32, Mode.LOST, 600, 1440)),
                 (3, Command.set_idle(33, 60, 0, 30, 3))):
        pc = seal_slot_command(ks, k, c)
        out["commands"].append({"n_a": 300, "k": k, "tag": slot_command_tag(ks, k).hex(), "pt": c.encode().hex(),
                                "pc": pc.hex(), "payload": command_channel_payload(pc).hex(),
                                "payload_retry": command_channel_payload(pc, retry=True).hex()})
    for off, early in ((3600, 0), (3600, 12), (86400, 0), (7200, 14)):
        lo, hi = slot_window_ms(off, 30, early)
        out["windows"].append({"offset_s": off, "window_s": 30, "early_s": early, "guard_ms": slot_guard_ms(off),
                               "open_ms": lo, "close_ms": hi})
    # an ACK at slot k_a of the anchor 300's grid for the tag's last report 302 (a hello nobody ACKed live)
    ce = keys.cmd(epoch(302))
    for k_a in (1, 2):
        pa = ack_seal(ce, 302, k_a, 4)
        out["acks_at_slot"].append({"n_a": 300, "n": 302, "k": k_a, "pa": pa.hex(), "tag": ack_tag(ce, 302, k_a).hex()})
    return out


def vector_tag_schedule() -> list[dict]:
    """Event sequences for the tag's anchor and the SYNC bit (times in ms)."""
    H = 3600_000
    scen = []

    def run(name, events):
        st = TagSchedule()
        steps = []
        for ev in events:
            kind = ev[0]
            if kind == "flood":
                st.on_flood_sent(ev[1], ev[2], ev[3] if len(ev) > 3 else None)
            elif kind == "ack":
                st.on_window_ack(ev[1], ev[2])
            elif kind == "prev_slot":
                st.on_prev_slot()
            elif kind == "reboot":
                st.on_reboot()
            elif kind == "sync":
                steps.append({"event": list(ev), "sync": st.sync_for(ev[1])})
                continue
            a, p = st.anchor, st.prev
            steps.append({"event": list(ev), "anchor": None if a is None else [a.n, a.t0, a.early_s],
                          "prev": None if p is None else [p.n, p.t0, p.early_s], "prev_slots_left": st.prev_slots_left})
        scen.append({"name": name, "steps": steps})

    run("flood moves the anchor, previous grid kept 3 slots",
        [("flood", 100, 0), ("sync", 101), ("flood", 101, 3 * H), ("prev_slot",), ("prev_slot",), ("prev_slot",),
         ("sync", 102)])
    run("hello ACKed in its window moves the anchor and drops the previous grid",
        [("flood", 100, 0), ("flood", 101, 3 * H), ("ack", 102, 6 * H), ("sync", 103), ("sync", 104)])
    run("hello unACKed: nothing; hello then flood 12 s later opens 12 s early",
        [("ack", 100, 0), ("sync", 101), ("flood", 101, 3 * H + 12_040, 3 * H), ("sync", 102)])
    run("routed copy then flood after 14 s; late ACK in the window keeps the flood's origin",
        [("ack", 100, 0), ("flood", 101, 3 * H + 13_500, 3 * H), ("ack", 101, 3 * H), ("sync", 102)])
    run("copy 10 of the same report does not move the anchor",
        [("flood", 100, 0), ("flood", 100, 8_200), ("sync", 101)])
    run("reboot clears the schedule",
        [("flood", 100, 0), ("reboot",), ("sync", 101)])
    return scen


def vector_app_anchor() -> list[dict]:
    """The app's anchor rule (R1, R32) over report sequences. Each step: report (n, j, t, sync, live_ack,
    still, due_in_gap) or ("confirmed", n_grid); expected A, candidates, known."""
    H = 3600_000
    scen = []

    def run(name, events):
        st = AppAnchor()
        steps = []
        for ev in events:
            if ev[0] == "confirmed":
                st.on_command_confirmed(ev[1])
            else:
                n, j, t, sync, live = ev[1:6]
                still = ev[6] if len(ev) > 6 else True
                due = ev[7] if len(ev) > 7 else False
                st.on_report(n, j, t, sync, live, still, due)
            steps.append({"event": list(ev), "a": None if st.a is None else [st.a.n, st.a.t0],
                          "cands": [[c.n, c.t0] for c in st.cands], "known": st.known})
        scen.append({"name": name, "steps": steps})

    run("flood sets A; ACKed hello is a candidate; SYNC in the next report confirms it",
        [("r", 100, 0, 0, False, False), ("r", 101, 9, 3 * H, False, True), ("r", 102, 9, 6 * H, True, True)])
    run("next report without SYNC drops the candidate",
        [("r", 100, 0, 0, False, False), ("r", 101, 9, 3 * H, False, True), ("r", 102, 9, 6 * H, False, False)])
    run("one report lost after the candidate: both grids kept (R32)",
        [("r", 100, 0, 0, False, False), ("r", 101, 9, 3 * H, False, True), ("r", 103, 9, 9 * H, False, False)])
    run("gap of two while standing still: grids kept, still known",
        [("r", 100, 0, 0, False, False), ("r", 104, 9, 12 * H, False, False, True, False)])
    run("gap of two after a move: schedule not known",
        [("r", 100, 0, 0, False, False), ("r", 104, 9, 12 * H, False, False, False, False)])
    run("SYNC names a report the app never got: not known until a flood",
        [("r", 100, 0, 0, False, False), ("r", 103, 9, 9 * H, True, False), ("r", 104, 0, 12 * H, False, False)])
    run("three grids at most, a fourth makes it unknown; a confirmed command settles them",
        [("r", 100, 0, 0, False, False), ("r", 101, 9, 3 * H, False, True), ("r", 103, 9, 9 * H, False, True),
         ("r", 105, 9, 15 * H, False, True), ("confirmed", 103)])
    run("hello then its flood copy: A is the flood",
        [("r", 100, 0, 0, False, False), ("r", 101, 9, 3 * H, False, True), ("r", 101, 0, 3 * H + 12_000, False, False)])
    run("flood copy 0 then copy 10 of the same report: A keeps the time of copy 0 (the tag's t0)",
        [("r", 100, 0, 0, False, False), ("r", 101, 0, 3 * H, False, False), ("r", 101, 10, 3 * H + 8_000, False, False)])
    run("an older report changes nothing",
        [("r", 100, 0, 0, False, False), ("r", 101, 9, 3 * H, False, True), ("r", 99, 0, 4 * H, False, False)])
    return scen


def vector_areas() -> dict:
    pts = [
        ("centre of sk-test", 48.7000, 21.2500, "sk", False),
        ("inside sk-test2 (state row first)", 48.7360, 21.2890, "sk", False),
        ("8 km north, inside sk-test", 48.7720, 21.2500, "sk", False),
        ("20 km east, outside", 48.7000, 21.5230, "sk", False),
        ("centre, home network hu", 48.7000, 21.2500, "hu", False),
        ("centre, home network at: no row", 48.7000, 21.2500, "at", False),
        ("inside, same-radio border: country level skipped", 48.6400, 21.2500, "sk", True),
        ("no fix ever", None, None, "sk", False),
    ]
    country = {"sk": "sk", "hu": None, "at": None}
    cases = []
    for name, lat, lon, net, border in pts:
        a = area_at(lat, lon, AREAS_FOR_VECTORS, net)
        cases.append({"name": name, "lat": lat, "lon": lon, "home_network": net, "same_radio_border": border,
                      "area": None if a is None else a.scope,
                      "levels": levels(a.scope if a else None, country[net], border)})
    ladder = []
    lad = Ladder()
    for ev in (("flood", True, True, 0), ("flood", False, True, 100), ("flood", False, False, 3600),
               ("flood", False, True, 7200), ("reset", 90000), ("flood", False, True, 90100),
               ("ack", 90200)):
        if ev[0] == "flood":
            wide, lad = ladder_after_flood(lad, 3, ev[1], ev[2], ev[3])
            ladder.append({"event": list(ev), "wide_lvl": wide, "lvl": lad.lvl, "widened_at": lad.widened_at})
        elif ev[0] == "reset":
            lad = ladder_reset(lad, ev[1])
            ladder.append({"event": list(ev), "lvl": lad.lvl, "widened_at": lad.widened_at})
        else:
            lad = ladder_reset(lad, ev[1], ack=True)
            ladder.append({"event": list(ev), "lvl": lad.lvl, "widened_at": lad.widened_at})
    return {"rows": [[a.lat, a.lon, a.radius_km, a.scope, a.network] for a in AREAS_FOR_VECTORS],
            "country_scope": country, "cases": cases, "ladder_levels": 3,
            "ladder_events": "flood: evidence, important, now_s; reset: now_s; ack: now_s", "ladder": ladder}


def vector_transport() -> list[dict]:
    out = []
    for name, payload in (("sk", bytes(range(19))), ("sk-test", bytes(range(51))), ("#sk", bytes(range(19)))):
        out.append({"scope": name, "key": scope_key(name).hex(), "payload_type": PAYLOAD_GRP_DATA,
                    "payload": payload.hex(), "code": transport_code(name, PAYLOAD_GRP_DATA, payload)})
    return out


def vector_plan() -> list[dict]:
    p1, p2, p3 = ["a5e1"], ["a5e1", "b7d2"], ["a5e1", "b7d2", "c3f4"]
    cases = [
        # kind, heard, entry route, kwargs
        ("ack", {"route": "flood", "path": []}, "near", {"forms": "near"}),
        ("ack", {"route": "tflood", "path": []}, "direct", {"forms": "both"}),
        ("ack", {"route": "direct", "path": []}, "near", {"forms": "near"}),
        ("ack", {"route": "direct", "path": []}, "direct", {"forms": "routed", "via_path": p2}),
        ("ack", {"route": "direct", "path": []}, "direct", {"forms": "routed"}),
        ("ack", {"route": "tflood", "path": p1}, "direct", {"forms": "both"}),
        ("ack", {"route": "tflood", "path": p2}, "direct", {"forms": "both"}),
        ("ack", {"route": "tflood", "path": p3}, "direct", {"forms": "both"}),
        ("ack", {"route": "tflood", "path": p1}, "near", {"forms": "near"}),
        ("ack", {"route": "tflood", "path": p1}, "direct", {"forms": "both", "max_hops": 0}),
        ("ack", None, "near", {"forms": "near"}),
        ("cmd", {"route": "flood", "path": []}, "flood", {}),
        ("cmd", {"route": "flood", "path": []}, "both", {}),
        ("cmd", {"route": "flood", "path": []}, "both", {"fb": False}),
        ("cmd", {"route": "direct", "path": []}, "flood", {}),
        ("cmd", {"route": "direct", "path": []}, "both", {}),
        ("cmd", {"route": "direct", "path": []}, "flood", {"via_path": p1}),
        ("cmd", {"route": "tflood", "path": p2}, "flood", {}),
        ("cmd", {"route": "tflood", "path": p2}, "flood", {"fb": False}),
        ("cmd", {"route": "tflood", "path": p3}, "flood", {}),
        ("cmd", {"route": "tflood", "path": p3}, "direct", {}),
        ("cmd", {"route": "tflood", "path": p2}, "direct", {}),
        ("cmd", {"route": "tflood", "path": p1}, "near", {}),
        ("cmd", {"route": "flood", "path": []}, "flood", {"can_zero_hop": False}),
        ("cmd", {"route": "tflood", "path": p1}, "flood", {"can_path": False}),
        ("cmd", None, "flood", {}),
        ("cmd", None, "flood", {"blind": True}),
    ]
    return [{"kind": k, "heard": h, "route": r, "args": a, "plan": plan(k, h, r, **a)} for k, h, r, a in cases]


def vector_hold() -> list[list]:
    return [[h, p, hold_s(h, p)] for h, p in ((0, 10800), (1, 10800), (2, 10800), (4, 10800), (4, 3600),
                                               (8, 10800), (4, 86400), (15, 3600))]


def vector_ack_entries(keys: Keys) -> list[dict]:
    ce = keys.cmd(epoch(300))
    return [{"n": 300, "k": 0, "form": f, "hold": 4, "more": m, "content": ack_entry_content(ce, 300, 0, f, 4, m)}
            for f, m in (("near", False), ("both", False), ("routed", False), ("near", True))]


def vectors() -> dict:
    keys = Keys(bytes(range(32)))
    return {
        "description": ("Quiet mesh (LT2, firmware 0.10.0): report copies 9, 10, 11 and their packets, the SYNC flag, "
                        "the 13 B ACK (near and routed forms), the second command data type 0xFF6C, schedule anchors "
                        "and slots, the tag's and the app's anchor rules, bridge areas and the scope ladder, transport "
                        "codes, the bridge's route plan, hold. K0 = bytes 0..31 as in lt2.json. Synthetic places and "
                        "repeater hashes only. Generated by `uv run python -m libretag.quietmesh vectors`; do not "
                        "edit by hand. PROTOCOL.md 2.6, 2.7, 3.3 to 3.5, 4.3."),
        "version": 1,
        "k0": bytes(range(32)).hex(),
        "view4": keys.view(epoch(300)).hex(),
        "cmd4": keys.cmd(epoch(300)).hex(),
        "copies": vector_copies(keys),
        "acks": vector_acks(keys),
        "acks_routed": vector_acks_routed(keys),
        "ack_entries": vector_ack_entries(keys),
        "retry": vector_retry(keys),
        "slots": vector_slots(keys),
        "tag_schedule": vector_tag_schedule(),
        "app_anchor": vector_app_anchor(),
        "areas": vector_areas(),
        "transport": vector_transport(),
        "plan": vector_plan(),
        "hold": vector_hold(),
    }


if __name__ == "__main__":
    if sys.argv[1:] == ["vectors"]:
        json.dump(vectors(), sys.stdout, indent=1)
        print()
    else:
        print(__doc__)

"""The tag's link policy (firmware 0.10.0, `firmware/lt_core/lt_link.c`) in Python.

A line-by-line mirror of the pure C functions, like `wake.py` mirrors `lt_wake_decide`. SPEC.md 3.2 has
the tables; `firmware/lt_core/test/link_test.c` writes the expectations out, and `tests/test_link.py`
runs the same rows and the same multi-day scenarios here (through `sim.simulate_days(link=...)`).

    decide            a reporting wake-up: which copy first, whether a decision follows
    on_ack            a near or routed ACK for the last report
    on_unconfirmed    the decision point (12 s, 12 s + 1.5 s per hop) passed without an ACK
    on_window_end     the listen window of a hello closed: a moved NEAR tag becomes pending
    on_check_end      the check after a pending hello: flood now or not
    on_flood_sent     copy 0 went out: reference, budget, backoff, the parked flood
    on_flood_result   8 s after copy 0: the ladder, copy 10, the extra flood of R29
    level_next        the scope level of the next flood

Times are uptime seconds, positions 1e-7 degrees, as in the C code.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from enum import IntEnum
from typing import Callable

from .lt2 import Mode
from .quietmesh import (ack_wait_ms, HOLD_DEFAULT, Ladder, PATH_MAX_HOPS, hold_s,
                        idle_unscoped_ok, ladder_after_flood, ladder_reset)

MOVE_M = 300
IDLE_BUDGET = 8             # IDLE fallback floods in a rolling 24 h
HEARTBEAT_S = 86400         # FAR or ROUTED: a flood every 24 h when nothing confirms
PARKED_NEXT_S = 86400
BACKOFF_MAX_S = 86400
DAY = 86400


class Link(IntEnum):
    FAR = 0
    NEAR = 1
    ROUTED = 2


class Send(IntEnum):
    NONE = 0
    NEAR = 1
    PATH = 2
    FLOOD = 3


# events: a report that has to arrive (LT_EV_*)
EV_BOOT = 0x01
EV_CMD = 0x02
EV_LOW = 0x04
EV_MODE = 0x08
EV_CONSOLE = 0x10


class Why(IntEnum):
    PERIOD = 0
    MOVED = 1
    HOLD = 2
    HEARTBEAT = 3
    NOFIX = 4
    BOOT = 5
    CMD = 6
    LOW = 7
    MODE = 8
    CONSOLE = 9
    BUDGET_LAST = 10
    PARKED = 11
    TRACK = 12
    AGAIN = 13


WHY_NAMES = ["period", "moved", "hold", "heartbeat", "nofix", "boot", "cmd", "low", "mode", "console",
             "budget_last", "parked", "track", "again"]
LINK_NAMES = ["far", "near", "routed"]


def distance_m(lat0_e7: int, lon0_e7: int, lat_e7: int, lon_e7: int) -> float:
    """lt_distance_m: equirectangular, good to a few metres at the distances that matter here."""
    kx = 111320.0 * math.cos(lat0_e7 * 1e-7 * 0.017453292519943295)
    dx = (lon_e7 - lon0_e7) * 1e-7 * kx
    dy = (lat_e7 - lat0_e7) * 1e-7 * 110540.0
    return math.sqrt(dx * dx + dy * dy)


def moved(ref_lat_e7: int, ref_lon_e7: int, lat_e7: int, lon_e7: int, acc_m: int) -> tuple[bool, int]:
    """(moved, distance m): farther than max(MOVE_M, 3 x accuracy) from the reference."""
    d = distance_m(ref_lat_e7, ref_lon_e7, lat_e7, lon_e7)
    thr = 3 * acc_m if acc_m != 255 and 3 * acc_m > MOVE_M else MOVE_M
    return d > thr, int(min(d, 4e9))


@dataclass
class Ack:
    """What on_ack needs of an opened ACK (lt_ack_t)."""
    hold: int = HOLD_DEFAULT
    routed: bool = False
    hash_size: int = 0
    path: list[bytes] = field(default_factory=list)

    @property
    def count(self) -> int:
        return len(self.path)


@dataclass
class LinkState:
    link: Link = Link.FAR
    hold: int = HOLD_DEFAULT
    path_hs: int = 0
    path: list[bytes] = field(default_factory=list)
    path_fix: bool = False
    path_lat_e7: int = 0
    path_lon_e7: int = 0
    ref_fix: bool = False
    ref_lat_e7: int = 0
    ref_lon_e7: int = 0
    t_ack_ok: bool = False
    t_ack_s: int = 0
    flood_ok: bool = False
    last_flood_s: int = 0
    bk: int = 0                 # no-fix backoff step: the gap is period << bk, at most 24 h
    pending: bool = False       # NEAR, moved, hello not ACKed: decided at the next check
    pending_nofix: bool = False
    again: bool = False         # R29
    park_due: bool = False
    budget_t: list[int] = field(default_factory=list)
    unscoped_ok: bool = False
    last_unscoped_s: int = 0
    lad: Ladder = field(default_factory=Ladder)

    @property
    def path_n(self) -> int:
        return len(self.path)


@dataclass
class LinkIn:
    now_s: int
    mode: Mode = Mode.IDLE
    events: int = 0
    has_fix: bool = False
    lat_e7: int = 0
    lon_e7: int = 0
    acc_m: int = 255
    in_zone: bool = False
    check_failed: bool = False
    nb_moved: bool = False
    period_s: int = 3 * 3600
    ack_patience: int = 1
    home_changed: bool = False


@dataclass
class LinkOut:
    first: Send = Send.NEAR
    must: bool = False
    flood_allowed: bool = False
    important: bool = False
    idle_fallback: bool = False
    why: Why = Why.PERIOD
    decide_ms: int = 0
    at_check: bool = False
    moved: bool = False
    moved_known: bool = False
    moved_m: int = 0
    parked: bool = False
    budget_hit: bool = False
    budget_last: bool = False
    budget_free_s: int = 0
    path_dropped: bool = False


def _budget_prune(s: LinkState, now: int) -> None:
    s.budget_t = [t for t in s.budget_t if now - t < DAY][:IDLE_BUDGET]


def budget_left(s: LinkState, now: int) -> int:
    _budget_prune(s, now)
    return IDLE_BUDGET - len(s.budget_t)


def _why_of_events(ev: int) -> Why:
    return (Why.BOOT if ev & EV_BOOT else Why.CMD if ev & EV_CMD else Why.LOW if ev & EV_LOW else
            Why.MODE if ev & EV_MODE else Why.CONSOLE)


def _drop_path(s: LinkState) -> None:
    s.path, s.path_hs, s.path_fix = [], 0, False


def _backoff_gap(period_s: int, bk: int) -> int:
    return min((period_s or 3600) << min(bk, 7), BACKOFF_MAX_S)


def _decide_ms(s: LinkState) -> int:
    hops = s.path_n if s.link == Link.ROUTED else 0
    return ack_wait_ms(hops)


def decide(s: LinkState, i: LinkIn) -> LinkOut:
    """lt_link_decide: at a wake-up that reports. May drop the path (ROUTED to FAR)."""
    o = LinkOut()
    now = i.now_s
    if i.has_fix:
        o.moved_known = True
        if s.ref_fix:
            o.moved, o.moved_m = moved(s.ref_lat_e7, s.ref_lon_e7, i.lat_e7, i.lon_e7, i.acc_m)
        else:
            o.moved = True  # no reference yet: a new place
        if i.in_zone:
            o.moved = False
    elif i.check_failed and i.nb_moved:
        o.moved = o.moved_known = True
    if s.link == Link.ROUTED:
        away = (i.has_fix and s.path_fix and
                moved(s.path_lat_e7, s.path_lon_e7, i.lat_e7, i.lon_e7, i.acc_m)[0])
        if away or (i.has_fix and o.moved) or i.home_changed or not s.path_n:
            _drop_path(s)
            s.link = Link.FAR
            o.path_dropped = True
    near = s.link == Link.NEAR
    far = not near
    routed = s.link == Link.ROUTED

    if i.mode != Mode.IDLE:
        o.must = o.important = o.flood_allowed = True
        o.why = _why_of_events(i.events) if i.events else Why.TRACK
        o.first = Send.NEAR if near else Send.PATH if routed else Send.FLOOD
        o.decide_ms = 0 if o.first == Send.FLOOD else _decide_ms(s)
        return o
    hs = hold_s(s.hold, i.period_s)
    hold_out = near and (s.hold == 0 or not s.t_ack_ok or now - s.t_ack_s >= (hs or 0))
    hb_due = far and (not s.flood_ok or now - s.last_flood_s >= HEARTBEAT_S)
    nofix_due = far and not i.has_fix and s.flood_ok and now - s.last_flood_s >= _backoff_gap(i.period_s, s.bk)
    park = far and s.park_due and i.has_fix and not o.moved

    if i.events:
        o.why, o.must, o.important = _why_of_events(i.events), True, True
    elif i.check_failed:
        o.why = Why.MOVED
        o.must = o.important = o.idle_fallback = True
    elif hold_out:
        o.why = Why.HOLD
        o.must = o.important = o.idle_fallback = True
    elif far and o.moved:
        o.why = Why.MOVED
        o.must = o.important = o.idle_fallback = True
    elif nofix_due:
        o.why = Why.NOFIX
        o.must = o.idle_fallback = True
    elif hb_due:
        o.why = Why.HEARTBEAT
        o.must = o.important = o.idle_fallback = True
    elif park:
        o.why = Why.PARKED
        o.must = o.idle_fallback = o.parked = True
    elif far and s.again:
        o.why = Why.AGAIN
        o.must = o.idle_fallback = True
    elif near and o.moved:
        o.why = Why.MOVED
        if i.ack_patience >= 2:
            o.must = o.important = o.idle_fallback = True
        else:
            o.at_check = True
    elif near and not i.has_fix:
        o.at_check = True
    if not o.must:
        return o
    o.flood_allowed = True
    if o.idle_fallback:
        left = budget_left(s, now)
        if left in (0, 1):
            o.budget_free_s = s.budget_t[0] + DAY - now if s.budget_t else 0
        if left == 0:
            o.budget_hit = True
            o.flood_allowed = False
        elif left == 1:
            o.budget_last = True
    if near:
        o.first, o.decide_ms = Send.NEAR, _decide_ms(s)
    elif routed:
        o.first, o.decide_ms = Send.PATH, _decide_ms(s)
    else:
        o.first = Send.FLOOD if o.flood_allowed else Send.NEAR
    return o


def on_ack(s: LinkState, i: LinkIn, a: Ack, near_taken: bool = False) -> bool:
    """lt_link_on_ack. Returns True if the ACK changed the link."""
    if a.routed:
        if near_taken or not 1 <= a.count <= PATH_MAX_HOPS or not 1 <= a.hash_size <= 3:
            return False
        s.link = Link.ROUTED
        s.path_hs = a.hash_size
        s.path = list(a.path)
        s.path_fix = i.has_fix
        if i.has_fix:
            s.path_lat_e7, s.path_lon_e7 = i.lat_e7, i.lon_e7
            s.ref_fix, s.ref_lat_e7, s.ref_lon_e7 = True, i.lat_e7, i.lon_e7
        s.flood_ok, s.last_flood_s = True, i.now_s
    else:
        s.link = Link.NEAR
        s.hold = a.hold
        s.t_ack_ok, s.t_ack_s = True, i.now_s
        _drop_path(s)
        s.park_due = False
        if i.has_fix:
            s.ref_fix, s.ref_lat_e7, s.ref_lon_e7 = True, i.lat_e7, i.lon_e7
    s.pending = s.pending_nofix = s.again = False
    s.bk = 0
    s.lad = ladder_reset(s.lad, i.now_s, ack=True)
    return True


def on_unconfirmed(s: LinkState, i: LinkIn, o: LinkOut) -> bool:
    """lt_link_on_unconfirmed. True when a flood of the report goes out now."""
    if not o.must:
        return False
    if s.link == Link.ROUTED:
        _drop_path(s)
    s.link = Link.FAR
    return o.flood_allowed


def on_window_end(s: LinkState, o: LinkOut, acked: bool) -> None:
    if not acked and o.at_check and s.link == Link.NEAR:
        s.pending = True
        s.pending_nofix = not o.moved_known


def on_check_end(s: LinkState, acked: bool, nb_moved: bool = False) -> bool:
    """True: flood report n + 1 now."""
    if not s.pending:
        return False
    nofix = s.pending_nofix
    s.pending = s.pending_nofix = False
    if acked:
        return False
    return not nofix or nb_moved


def on_flood_sent(s: LinkState, i: LinkIn, o: LinkOut, unscoped: bool = False) -> None:
    now = i.now_s
    if s.link == Link.ROUTED:
        _drop_path(s)
    s.link = Link.FAR
    s.flood_ok, s.last_flood_s = True, now
    if i.has_fix:
        s.ref_fix, s.ref_lat_e7, s.ref_lon_e7 = True, i.lat_e7, i.lon_e7
        s.bk = 0
    elif s.bk < 7:
        s.bk += 1
    if i.mode == Mode.IDLE:
        if o.idle_fallback:
            _budget_prune(s, now)
            if len(s.budget_t) < IDLE_BUDGET:
                s.budget_t.append(now)
        if unscoped:
            s.unscoped_ok, s.last_unscoped_s = True, now
        s.park_due = o.why == Why.MOVED
    else:
        s.park_due = False
    s.again = s.pending = s.pending_nofix = False


def _unscoped_ok(s: LinkState, now: int) -> bool:
    return idle_unscoped_ok(s.last_unscoped_s if s.unscoped_ok else None, now)


def on_flood_result(s: LinkState, i: LinkIn, n_levels: int, evidence: bool, o: LinkOut) -> int | None:
    """8 s after copy 0. Returns the level of copy 10 to send now, or None."""
    wide, s.lad = ladder_after_flood(s.lad, n_levels, evidence, o.important, i.now_s) if n_levels else (None, s.lad)
    if not evidence and o.why == Why.HOLD:
        s.again = True
    if (wide is not None and i.mode == Mode.IDLE and wide == n_levels - 1 and n_levels > 1
            and not _unscoped_ok(s, i.now_s)):
        wide = None
    return wide


def level_next(s: LinkState, now: int, mode: Mode, n_levels: int) -> int:
    if not n_levels:
        return 0
    s.lad = ladder_reset(s.lad, now)
    lvl = min(s.lad.lvl, n_levels - 1)
    if mode == Mode.IDLE and n_levels > 1 and lvl == n_levels - 1 and not _unscoped_ok(s, now):
        lvl = n_levels - 2
    return lvl


def flood_next_in(s: LinkState, i: LinkIn, o: LinkOut) -> int:
    """next_in of a flood copy of this report: the next flood the tag already knows about."""
    if o.budget_last or o.budget_hit:
        return o.budget_free_s or HEARTBEAT_S
    if i.mode != Mode.IDLE:
        return i.period_s
    if o.parked:
        return PARKED_NEXT_S
    if not i.has_fix:
        return _backoff_gap(i.period_s, min(s.bk + 1, 7))
    if o.moved:
        return i.period_s
    return HEARTBEAT_S


# ---------------------------------------------------------------- the world around the tag (scenarios)

def _always(t: int) -> bool:
    return True


def _hops_none(t: int) -> int:
    return 9


@dataclass
class LinkEnv:
    """Who answers at time t, as in link_test.c's `struct env`.

    app(t): the app is online and makes ACKs; near(t): a bridge (or the node) hears the tag zero-hop;
    hops(t): a flood reaches a bridge over this many hops (0 = directly, 9 = 3 or more / nobody);
    repeated: a repeater repeats the tag's floods; flood_cost: repeater transmissions per flood."""
    app: Callable[[int], bool] = _always
    near: Callable[[int], bool] = _always
    hops: Callable[[int], int] = _hops_none
    repeated: bool = True
    flood_cost: int = 40
    hold: int = HOLD_DEFAULT
    start_near: bool = False    # the state file kept NEAR at home, ACKed just before the boot
    n_levels: int = 3


@dataclass
class LinkCount:
    floods: int = 0
    wide: int = 0       # copies 10
    hellos: int = 0
    routed: int = 0     # copies 11
    acks: int = 0
    repeater_tx: int = 0
    log: list[dict] = field(default_factory=list)


_PATH2 = [bytes.fromhex("a5e1"), bytes.fromhex("b7d2")]


def _routed_ack(hops: int) -> Ack:
    return Ack(routed=True, hash_size=2, path=_PATH2[:hops])


def sim_report(s: LinkState, env: LinkEnv, i: LinkIn, c: LinkCount) -> LinkOut:
    """One report at i.now_s with the calls in the firmware's order (link_test.c `report`)."""
    t = i.now_s
    o = decide(s, i)
    app, hops = env.app(t), env.hops(t)
    acked = False
    if o.first == Send.NEAR:
        c.hellos += 1
        if app and env.near(t):
            on_ack(s, i, Ack(hold=env.hold))
            c.acks += 1
            acked = True
    elif o.first == Send.PATH:
        c.routed += 1
        c.repeater_tx += s.path_n
        if app and hops and hops <= 2:
            on_ack(s, i, _routed_ack(hops))
            c.acks += 1
            c.repeater_tx += hops
            acked = True
    flood = o.first == Send.FLOOD
    if not acked and not flood and o.decide_ms:
        flood = on_unconfirmed(s, i, o)
    if not acked and not flood:
        on_window_end(s, o, False)
    wide = None
    if flood:
        c.floods += 1
        c.repeater_tx += env.flood_cost
        on_flood_sent(s, i, o)
        heard = app and hops != 9
        if heard:
            on_ack(s, i, Ack(hold=env.hold) if hops == 0 else _routed_ack(hops))
            c.acks += 1
            c.repeater_tx += hops
        wide = on_flood_result(s, i, env.n_levels, heard or env.repeated, o)
        if wide is not None:
            c.wide += 1
            c.repeater_tx += env.flood_cost
    c.log.append({"t": t, "first": o.first.name.lower(), "why": WHY_NAMES[o.why], "flood": flood,
                  "copy10": wide is not None, "acked": acked, "link": LINK_NAMES[s.link]})
    return o


def sim_check(s: LinkState, env: LinkEnv, i: LinkIn, c: LinkCount) -> bool:
    """The check after a pending hello: a late ACK settles it, else report n + 1 floods now."""
    t = i.now_s
    ack = env.app(t) and env.near(t)
    if ack:
        on_ack(s, i, Ack(hold=env.hold))
        c.acks += 1
    if on_check_end(s, ack):
        sim_report(s, env, LinkIn(**{**i.__dict__, "check_failed": True}), c)
        return True
    return False


@dataclass
class LinkSim:
    """The link policy riding on `sim.simulate_days`: every wake-up that looks is a report for the link
    (0.10.0: quiet still sends its hello), the first one is the boot report, a mode change and a
    command answer are events, and the check after a pending hello comes `check_after_s` later."""
    env: LinkEnv = field(default_factory=LinkEnv)
    state: LinkState = field(default_factory=LinkState)
    count: LinkCount = field(default_factory=LinkCount)
    check_after_s: int = 3600
    ack_patience: int = 1
    acc_m: int = 5
    start_at: tuple[int, int] | None = None  # with env.start_near: where the kept ACK was taken
    booted: bool = False
    mode: Mode = Mode.IDLE

    def __post_init__(self) -> None:
        if self.env.start_near and not self.booted:
            a = self.start_at
            on_ack(self.state, LinkIn(now_s=0, has_fix=a is not None, lat_e7=a[0] if a else 0,
                                      lon_e7=a[1] if a else 0), Ack(hold=self.env.hold))

    def link_in(self, t: int, mode: Mode, at: tuple[int, int] | None, period_s: int, in_zone: bool = False,
                events: int = 0) -> LinkIn:
        return LinkIn(now_s=t, mode=mode, events=events, has_fix=at is not None, lat_e7=at[0] if at else 0,
                      lon_e7=at[1] if at else 0, acc_m=self.acc_m, in_zone=in_zone, period_s=period_s,
                      ack_patience=self.ack_patience)

    def wake(self, t: int, mode: Mode, at: tuple[int, int] | None, period_s: int, in_zone: bool = False,
             cmd: bool = False) -> LinkOut:
        ev = 0
        if not self.booted:
            ev |= EV_BOOT
            self.booted = True
        if mode != self.mode:
            ev |= EV_MODE
            self.mode = mode
        if cmd:
            ev |= EV_CMD
        return sim_report(self.state, self.env, self.link_in(t, mode, at, period_s, in_zone, ev), self.count)

    def check(self, t: int, at: tuple[int, int] | None, period_s: int) -> bool:
        if not self.state.pending:
            return False
        return sim_check(self.state, self.env, self.link_in(t, Mode.IDLE, at, period_s), self.count)

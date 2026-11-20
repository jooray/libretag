"""What the tracker does at a wake-up (PROTOCOL.md 2.1): report, report and go quiet, or stay silent.

Python mirror of `lt_wake_decide` in firmware/lt_core/lt_core.c; the vectors in lt2.json ("wake",
"checks") pin both. Order of the rules: TRACK/LOST/LISTEN or a forced report -> report; no reason
for quiet -> report (and end a quiet stretch); a reason (home zone with a fix, else an owner's
device seen, else parked: the fix has stayed within STILL_RADIUS_M for `still_after` wake-ups in a
row) -> one report with QUIET when the stretch begins or the reason changes, then silent until the
heartbeat (zone 24 h, owner 8 h, parked 6 h), which sends another QUIET report.

0.7: every wake-up without a report is a check (the tracker listens for a command for `check_s`).
Checks fall at times the app can compute from C_e(n): `check_offsets`.
"""

from __future__ import annotations

import math
import struct
from dataclasses import dataclass, field
from enum import IntEnum

from .lt2 import Mode, hmac_sha256

HEARTBEAT_S = 24 * 3600   # in a home zone: one report a day
OWNER_CAP_S = 8 * 3600    # owner nearby: still one report every 8 h (phone stolen with the car)
EPOCH_SKIP_S = 12 * 3600  # a quiet stretch this long ends with n jumping to the next epoch
HYSTERESIS_M = 50         # inside below r, outside only beyond r + 50 m
STILL_AFTER = 3           # parked: this many wake-ups in a row at the same place (0 = off)
STILL_HB_S = 6 * 3600     # parked: one report every 6 h
STILL_RADIUS_M = 100      # parked: fixes within this distance of the first one count as the same place
CHECK_S = 30              # 0.7.3: listening at a check, seconds (0 = no listening); 60 in 0.7.2, 20 before
CHECK_S_MAX = 180         # 0.7.2: a command flooded from far away crosses many repeaters
REPORT_EVERY = 1          # 0.7.3: report at every report_every-th wake-up in IDLE (1 = every wake-up)
REPORT_EVERY_MAX = 24
CHECK_REF = (7, 62500, 5)  # 0.7.3: check_s is defined for SF7 / BW 62.5 kHz / CR 4/5
CHECK_REF_LEN = 35        # a command packet on the channel, bytes


class Action(IntEnum):
    REPORT = 0
    REPORT_QUIET = 1  # report with QUIET, then quiet
    SILENT = 2        # no TX, no RX window


class Reason(IntEnum):
    NONE = 0
    ZONE = 1
    OWNER = 2
    STILL = 3  # 0.7: parked


@dataclass
class Zone:
    lat_e7: int
    lon_e7: int
    r_m: int


@dataclass
class WakeCfg:
    heartbeat_s: int = HEARTBEAT_S
    owner_cap_s: int = OWNER_CAP_S
    epoch_skip_s: int = EPOCH_SKIP_S
    hysteresis_m: int = HYSTERESIS_M
    still_after: int = STILL_AFTER
    still_hb_s: int = STILL_HB_S
    still_radius_m: int = STILL_RADIUS_M

    def heartbeat(self, reason: "Reason") -> int:
        return {Reason.ZONE: self.heartbeat_s, Reason.OWNER: self.owner_cap_s,
                Reason.STILL: self.still_hb_s}.get(reason, 0)


@dataclass
class WakeIn:
    now_s: int
    mode: Mode = Mode.IDLE
    forced: bool = False
    has_fix: bool = False
    lat_e7: int = 0
    lon_e7: int = 0
    owner_seen: bool = False
    zones: list[Zone] = field(default_factory=list)
    # Wi-Fi (trackers without GPS, fw-wifi-esp32.md); used only without a fix
    wifi_valid: bool = False   # a scan ran and saw at least one usable AP
    wifi_zone: int = -1        # wifizone.match() of that scan
    wifi_still: bool = False   # the scan matches the parked reference (the first scan of the stretch)


WZONE_BASE = 16  # WakeState.zone of Wi-Fi zone i


@dataclass
class WakeState:
    quiet: bool = False
    reason: Reason = Reason.NONE
    zone: int = -1          # zone the last fix was in (hysteresis)
    quiet_since_s: int = 0
    last_tx_s: int = 0
    still_count: int = 0    # IDLE wake-ups in a row with a fix within still_radius_m of still_lat/lon
    still_lat_e7: int = 0
    still_lon_e7: int = 0


@dataclass
class WakeOut:
    action: Action
    reason: Reason = Reason.NONE
    quiet_next_s: int = 0        # with REPORT_QUIET: when the tracker reports again at the latest
    epoch_skip: bool = False     # jump n to the start of the next epoch before this report
    reset_home_silent: bool = False
    zone: int = -1


def distance_m(lat0_e7: int, lon0_e7: int, lat_e7: int, lon_e7: int) -> float:
    kx = 111320.0 * math.cos(lat0_e7 * 1e-7 * 0.017453292519943295)
    dx = (lon_e7 - lon0_e7) * 1e-7 * kx
    dy = (lat_e7 - lat0_e7) * 1e-7 * 110540.0
    return math.sqrt(dx * dx + dy * dy)


def zone_distance_m(z: Zone, lat_e7: int, lon_e7: int) -> float:
    return distance_m(z.lat_e7, z.lon_e7, lat_e7, lon_e7)


def zone_at(cfg: WakeCfg, zones: list[Zone], prev: int, lat_e7: int, lon_e7: int) -> int:
    """Index of the zone the position is in, with hysteresis for the zone it was in; -1 = none."""
    if 0 <= prev < len(zones):
        z = zones[prev]
        if zone_distance_m(z, lat_e7, lon_e7) < z.r_m + cfg.hysteresis_m:
            return prev
    for i, z in enumerate(zones):
        if zone_distance_m(z, lat_e7, lon_e7) < z.r_m:
            return i
    return -1


def _still_update(cfg: WakeCfg, w: WakeIn, st: WakeState) -> None:
    """Count IDLE wake-ups in a row whose fix stays within still_radius_m of the first of them."""
    if w.mode != Mode.IDLE or not (w.has_fix or w.wifi_valid):
        st.still_count = 0
        return
    if not w.has_fix:
        # Wi-Fi only: the caller keeps the reference scan; a count of 1 tells it to take a new one
        st.still_count = min(st.still_count + 1, 255) if st.still_count and w.wifi_still else 1
        return
    if st.still_count and distance_m(st.still_lat_e7, st.still_lon_e7, w.lat_e7, w.lon_e7) < cfg.still_radius_m:
        st.still_count = min(st.still_count + 1, 255)
    else:
        st.still_count = 1
        st.still_lat_e7, st.still_lon_e7 = w.lat_e7, w.lon_e7


def decide(cfg: WakeCfg, w: WakeIn, st: WakeState) -> WakeOut:
    placed = w.has_fix or w.wifi_valid  # this wake-up knows where it is
    if w.has_fix:
        st.zone = zone_at(cfg, w.zones, st.zone, w.lat_e7, w.lon_e7)
    elif w.wifi_valid:
        st.zone = WZONE_BASE + w.wifi_zone if w.wifi_zone >= 0 else -1
    _still_update(cfg, w, st)
    out = WakeOut(Action.REPORT, zone=st.zone if placed else -1)

    cand = Reason.NONE
    if w.mode == Mode.IDLE:
        if placed and st.zone >= 0:
            cand = Reason.ZONE
        elif w.owner_seen:
            cand = Reason.OWNER
        elif cfg.still_after and st.still_count >= cfg.still_after:
            cand = Reason.STILL

    if cand == Reason.NONE:
        if st.quiet:
            out.epoch_skip = w.now_s - st.quiet_since_s >= cfg.epoch_skip_s
            out.reset_home_silent = True
            st.quiet = False
            st.reason = Reason.NONE
        out.action = Action.REPORT
    else:
        hb = cfg.heartbeat(cand)
        out.reason = cand
        out.quiet_next_s = hb
        if not st.quiet:
            st.quiet = True
            st.quiet_since_s = w.now_s
            st.reason = cand
            out.action = Action.REPORT_QUIET
        elif st.reason != cand:
            st.reason = cand
            out.action = Action.REPORT_QUIET
        elif w.forced or w.now_s - st.last_tx_s >= hb:
            out.action = Action.REPORT_QUIET
        else:
            out.action = Action.SILENT
    if out.action != Action.SILENT:
        st.last_tx_s = w.now_s
    return out


# ---------------------------------------------------------------- checks (0.7)

def check_jitter(cmd_e: bytes, n: int, i: int, base_s: int) -> int:
    """jit_i = (u16_le(HMAC(C_e(n), "LT2-w" || u32(n) || u16(i))[0:2]) mod span) - span/2, span = base/5."""
    span = base_s // 5
    if span == 0:
        return 0
    h = hmac_sha256(cmd_e, b"LT2-w" + struct.pack("<IH", n & 0xFFFFFFFF, i & 0xFFFF))
    return (h[0] | h[1] << 8) % span - span // 2


def check_offsets(cmd_e: bytes, n: int, base_s: int, count: int, until_s: int | None = None) -> list[int]:
    """t_k - t0 for checks k = 1..count after report n (t0 = its transmission start). With `until_s`
    (the heartbeat, next_in) only the checks strictly before it: checks at or after it are not made."""
    out, t = [], 0
    for k in range(1, count + 1):
        t += base_s + check_jitter(cmd_e, n, k, base_s)
        if until_s is not None and t >= until_s:
            break
        out.append(t)
    return out


def check_guard_ms(offset_s: int) -> int:
    """g_k = 2 s + (t_k - t0) x 100 ppm, in ms: the receiver is on from t_k - g_k to t_k + check_s + g_k."""
    return 2000 + offset_s // 10


SEND_AFTER_S = 3  # senders transmit at t_k + 3 s


# ---------------------------------------------------------------- report every n-th wake-up (0.7.3)

def wake_reports(k: int, every: int) -> bool:
    """Wake-up k (1, 2, ...) after the last report is a report wake-up (GPS, Bluetooth, decision);
    the others only listen for a command (no GPS, no Bluetooth scan, no transmission)."""
    return every <= 1 or k % every == 0


def report_next_in(cmd_e: bytes, n: int, base_s: int, every: int) -> int:
    """next_in of a normal IDLE report with report_every > 1: wake-up `every` on the keyed schedule."""
    return check_offsets(cmd_e, n, base_s, every)[-1]


# ---------------------------------------------------------------- check window by radio preset (0.7.3)

def _quarter_symbols(n_bytes: int, sf: int, bw_hz: int, cr: int, preamble: int = 16) -> int:
    """LoRa time on air in quarter symbols (explicit header, CRC on), integer only (lt_core mirror)."""
    de = 1 if (1 << sf) * 1000 > 16 * bw_hz else 0  # symbol longer than 16 ms: low data rate optimisation
    num = 8 * n_bytes - 4 * sf + 28 + 16
    d = 4 * (sf - 2 * de)
    ps = 8 + (((num + d - 1) // d) * cr if num > 0 else 0)
    return 4 * preamble + 17 + 4 * ps


def check_window_s(check_s: int, sf: int, bw_hz: int, cr: int) -> int:
    """check_s x (0.4 + 0.6 r), r = airtime of a command packet on this preset / on SF7 / 62.5 kHz /
    CR 4/5, rounded up, never below check_s, at most 180 s. Measured flood delay per hop grows less
    than airtime (SF8/CR8: median x1.5-1.7, p90 x2.0 for r = 2.6; docs/research/flood-latency.md)."""
    if not check_s or not 5 <= sf <= 12 or bw_hz <= 0 or not 5 <= cr <= 8:
        return check_s
    rsf, rbw, rcr = CHECK_REF
    a = _quarter_symbols(CHECK_REF_LEN, sf, bw_hz, cr) << sf
    r = _quarter_symbols(CHECK_REF_LEN, rsf, rbw, rcr) << rsf
    n, d = a * rbw, r * bw_hz  # r = n / d
    num, den = check_s * (2 * d + 3 * n), 5 * d  # check_s x (0.4 + 0.6 r)
    w = (num + den - 1) // den
    return max(check_s, min(w, CHECK_S_MAX))


def vector_scenarios() -> list[dict]:
    """Wake-decision vectors for lt2.json: each scenario is a list of steps (input, expected output)
    run against one state from the start."""
    home = Zone(487000000, 212500000, 150)   # synthetic (PROTOCOL.md examples)
    work = Zone(487200000, 212700000, 100)
    zones = [home, work]
    near = (487005000, 212500000)    # ~55 m north of home: inside
    edge = (487017000, 212500000)    # ~188 m: outside r but inside r + 50 (hysteresis)
    away = (487100000, 212500000)    # ~1.1 km
    H = 3600

    def step(t, **kw):
        lat_lon = kw.pop("at", None)
        if lat_lon is not None:
            kw.update(has_fix=True, lat_e7=lat_lon[0], lon_e7=lat_lon[1])
        return WakeIn(now_s=t, zones=zones, **kw)

    scen = {
        "zone_arrive_heartbeat_leave": [step(0, at=away), step(H, at=near), step(2 * H, at=near),
                                        step(3 * H, at=edge), step(24 * H, at=near), step(25 * H, at=near),
                                        step(26 * H, at=away), step(27 * H, at=away)],
        "zone_nofix_fail_open": [step(0, at=near), step(H), step(2 * H, at=near), step(3 * H, at=near)],
        "edge_needs_inside_first": [step(0, at=edge), step(H, at=near), step(2 * H, at=edge)],
        "owner_cap": [step(0, owner_seen=True), step(H, owner_seen=True), step(8 * H, owner_seen=True),
                      step(9 * H, owner_seen=True), step(10 * H), step(11 * H, at=away, owner_seen=True)],
        "owner_then_zone": [step(0, at=away, owner_seen=True), step(H, at=away, owner_seen=True),
                            step(2 * H, at=near, owner_seen=True), step(3 * H, at=near)],
        "lost_and_track_ignore": [step(0, at=near), step(H, at=near, mode=Mode.LOST),
                                  step(H + 120, at=near, mode=Mode.TRACK), step(2 * H, at=near),
                                  step(3 * H, at=near, owner_seen=True)],
        "forced_while_quiet": [step(0, at=near), step(H, at=near, forced=True), step(2 * H, at=near),
                               step(3 * H, forced=True)],
        "long_quiet_epoch_skip": [step(0, at=near), step(11 * H, at=near), step(11 * H + 60, at=away),
                                  step(12 * H, at=near), step(36 * H, at=near), step(37 * H, at=away)],
    }
    # 0.7: parked (reason STILL); defaults still_after 3, still_hb 6 h, radius 100 m
    park = (487150000, 212650000)    # ~1.7 km from home, no zone
    park30 = (487152714, 212650000)  # ~30 m from park: the same place (GPS wander)
    park_s = (487148000, 212650500)  # ~23 m the other way
    moved = (487177140, 212650000)   # ~300 m from park: moved
    scen.update({
        "still_park_overnight_then_move": [
            step(0, at=park), step(H, at=park30), step(2 * H, at=park_s), step(3 * H, at=park),
            step(7 * H, at=park30), step(8 * H, at=park), step(9 * H, at=park), step(14 * H, at=park),
            step(14 * H + 1200, at=park, forced=True), step(15 * H, at=moved), step(16 * H, at=moved),
            step(17 * H, at=moved), step(18 * H, at=moved)],
        "still_nofix_fail_open": [
            step(0, at=park), step(H, at=park), step(2 * H, at=park), step(3 * H), step(4 * H, at=park),
            step(5 * H, at=park), step(6 * H, at=park), step(7 * H, at=park)],
        "still_zone_beats_owner_beats_still": [
            step(0, at=park), step(H, at=park), step(2 * H, at=park), step(3 * H, at=park, owner_seen=True),
            step(4 * H, at=park, owner_seen=True), step(5 * H, at=park), step(6 * H, at=near, owner_seen=True),
            step(7 * H, at=near), step(8 * H, at=near), step(9 * H, at=near), step(10 * H, at=near, mode=Mode.TRACK),
            step(11 * H, at=near)],
        "still_lost_resets_count": [
            step(0, at=park), step(H, at=park), step(H + 120, at=park, mode=Mode.LOST), step(2 * H, at=park),
            step(3 * H, at=park), step(4 * H, at=park)],
    })
    cfgs = {"still_off": {"still_after": 0}}
    scen["still_off"] = [step(0, at=park), step(H, at=park), step(2 * H, at=park), step(3 * H, at=park)]
    out = []
    for name, steps in scen.items():
        cfg = WakeCfg(**cfgs.get(name, {}))
        st = WakeState()
        rows = []
        for w in steps:
            o = decide(cfg, w, st)
            rows.append({"t": w.now_s, "mode": int(w.mode), "forced": w.forced, "fix": w.has_fix,
                         "lat_e7": w.lat_e7, "lon_e7": w.lon_e7, "owner": w.owner_seen,
                         "action": int(o.action), "reason": int(o.reason), "quiet_next_s": o.quiet_next_s,
                         "epoch_skip": o.epoch_skip, "reset_home": o.reset_home_silent, "zone": o.zone})
        sc = {"name": name, "steps": rows}
        if name in cfgs:
            sc["cfg"] = cfgs[name]
        out.append(sc)
    return out


def vector_wifi_scenarios() -> list[dict]:
    """Wake decisions of a tracker without GPS placed by Wi-Fi (fw-wifi-esp32.md): the scan's zone
    match and its parked comparison are inputs here (wifizone.match, wifizone.score)."""
    home = Zone(487000000, 212500000, 150)
    away = (487100000, 212500000)
    H = 3600

    def step(t, z=None, still=False, scan=True, **kw):
        lat_lon = kw.pop("at", None)
        if lat_lon is not None:
            kw.update(has_fix=True, lat_e7=lat_lon[0], lon_e7=lat_lon[1])
        return WakeIn(now_s=t, zones=[home], wifi_valid=scan, wifi_zone=-1 if z is None else z,
                      wifi_still=still, **kw)

    scen = {
        "wifi_zone_arrive_leave": [step(0), step(H, z=0), step(2 * H, z=0), step(3 * H, scan=False),
                                   step(4 * H, z=0), step(28 * H, z=0), step(29 * H, z=1), step(30 * H, z=1),
                                   step(31 * H)],
        "wifi_parked": [step(0), step(H, still=True), step(2 * H, still=True), step(3 * H, still=True),
                        step(4 * H, still=True), step(5 * H), step(6 * H, still=True), step(7 * H, scan=False),
                        step(8 * H, still=True)],
        "wifi_fix_wins": [step(0, z=0, at=away), step(H, z=0), step(2 * H, z=0, at=away), step(3 * H, z=0)],
        "wifi_modes_and_owner": [step(0, z=0), step(H, z=0, mode=Mode.LOST), step(2 * H, z=0),
                                 step(3 * H, owner_seen=True), step(4 * H, z=0, owner_seen=True),
                                 step(5 * H, z=0, forced=True)],
    }
    out = []
    for name, steps in scen.items():
        cfg = WakeCfg()
        st = WakeState()
        rows = []
        for w in steps:
            o = decide(cfg, w, st)
            rows.append({"t": w.now_s, "mode": int(w.mode), "forced": w.forced, "fix": w.has_fix,
                         "lat_e7": w.lat_e7, "lon_e7": w.lon_e7, "owner": w.owner_seen,
                         "wifi": w.wifi_valid, "wifi_zone": w.wifi_zone, "wifi_still": w.wifi_still,
                         "action": int(o.action), "reason": int(o.reason), "quiet_next_s": o.quiet_next_s,
                         "epoch_skip": o.epoch_skip, "reset_home": o.reset_home_silent, "zone": o.zone,
                         "still_count": st.still_count})
        out.append({"name": name, "zone": [home.lat_e7, home.lon_e7, home.r_m], "steps": rows})
    return out


def vector_cfg() -> dict:
    c = WakeCfg()
    return {"heartbeat_s": c.heartbeat_s, "owner_cap_s": c.owner_cap_s, "epoch_skip_s": c.epoch_skip_s,
            "hysteresis_m": c.hysteresis_m, "still_after": c.still_after, "still_hb_s": c.still_hb_s,
            "still_radius_m": c.still_radius_m}


def vector_checks(keys) -> list[dict]:
    """Check schedule vectors: for (n, base) the offsets t_k - t0, the jitters and the guards, k = 1..6."""
    from .lt2 import epoch
    out = []
    for n, base, hb in ((5, 3600, 8 * 3600), (130, 900, 6 * 3600), (64, 7200, 24 * 3600), (201, 3600, 6 * 3600),
                        (1000, 60, 24 * 3600)):
        ce = keys.cmd(epoch(n))
        offs = check_offsets(ce, n, base, 6)
        out.append({"n": n, "base_s": base, "heartbeat_s": hb,
                    "jitter": [check_jitter(ce, n, k, base) for k in range(1, 7)],
                    "offsets": offs, "guard_ms": [check_guard_ms(o) for o in offs],
                    "before_heartbeat": check_offsets(ce, n, base, 64, until_s=hb)})
    return out


def vector_report_every(keys) -> list[dict]:
    """report_every vectors: which wake-ups after report n report, and next_in of a normal report."""
    from .lt2 import epoch
    out = []
    for n, base, every in ((5, 3600, 3), (130, 900, 4), (64, 3600, 6), (201, 1800, 2), (77, 3600, 1)):
        ce = keys.cmd(epoch(n))
        count = 2 * every + 1
        offs = check_offsets(ce, n, base, count)
        out.append({"n": n, "base_s": base, "every": every, "offsets": offs,
                    "reports": [wake_reports(k, every) for k in range(1, count + 1)],
                    "next_in": report_next_in(ce, n, base, every)})
    return out


def vector_check_window() -> list[dict]:
    """Effective listening window at a check for a few presets (lt_check_window_s)."""
    out = []
    for check_s, sf, bw, cr in ((30, 7, 62500, 5), (30, 8, 62500, 8), (30, 7, 62500, 6), (30, 9, 125000, 5),
                                (30, 10, 250000, 5), (30, 11, 250000, 5), (30, 12, 125000, 8), (60, 8, 62500, 8),
                                (120, 8, 62500, 8), (30, 5, 62500, 5), (0, 8, 62500, 8), (20, 7, 125000, 5)):
        out.append({"check_s": check_s, "sf": sf, "bw_hz": bw, "cr": cr,
                    "window_s": check_window_s(check_s, sf, bw, cr)})
    return out


ZONES_FOR_VECTORS = [Zone(487000000, 212500000, 150), Zone(487200000, 212700000, 100)]

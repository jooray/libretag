"""Tracker simulator: the same state machine the firmware runs, in Python (LT2).

Used for (1) end-to-end tests without hardware and (2) on-air latency tests:
a second MeshCore node in KISS modem mode plays the tracker.
"""

from __future__ import annotations

import random
import struct
import time
from dataclasses import dataclass, field

from .lt2 import Command, Keys, Mode, Op, Report, epoch, open_command
from .meshcore import Packet, PacketError
from .policy import TRACK_MIN_S, LostState, OverCap, TxCap, base_interval_s, clamp_track_s, report_allowed
from .proto import command_payload, report_packet

DEFAULT_IDLE_MIN = 60
DEFAULT_LISTEN_S = 90
DEFAULT_TRACK_INTERVAL_S = TRACK_MIN_S
DEFAULT_TRACK_DURATION_MIN = 120
JITTER = 0.10
LOW_BATT_PCT = 25


@dataclass
class TrackerState:
    k0: bytes
    n: int = 0
    mode: Mode = Mode.IDLE
    idle_interval_min: int = DEFAULT_IDLE_MIN
    listen_s: int = DEFAULT_LISTEN_S
    track_interval_s: int = DEFAULT_TRACK_INTERVAL_S
    mode_until: float = 0.0
    last_cmd_seq: int | None = None
    pending_ack: bool = False
    buzz_s: int = 0
    last_fix: tuple[int, float, float] = (0, 0.0, 0.0)
    log: list[str] = field(default_factory=list)
    txcap: TxCap = field(default_factory=TxCap)      # 0.7.6: floods in the last 24 h
    lost: LostState = field(default_factory=LostState)  # 0.7.6: LOST slow-down at one place
    lost_since: float = 0.0                           # 0.7.6: LOST began (not kept over a reboot)
    planned_base_s: int | None = None                 # the base interval fixed when the last report was built

    def __post_init__(self) -> None:
        self.track_interval_s = clamp_track_s(self.track_interval_s)  # a saved mode resumed at boot

    @property
    def keys(self) -> Keys:
        return Keys(self.k0)

    def _expire(self, now: float) -> None:
        if self.mode != Mode.IDLE and now >= self.mode_until:
            self.log.append(f"mode {self.mode.name} expired -> IDLE")
            self.mode = Mode.IDLE

    def next_interval_s(self, now: float) -> float:
        """The sleep after a wake-up. After a report it is the interval its next_in announced, which
        the firmware computes before the report's own flood is counted."""
        self._expire(now)
        base = self.planned_base_s if self.planned_base_s is not None else self.base_interval_s(now)
        self.planned_base_s = None
        return base * random.uniform(1 - JITTER, 1 + JITTER)

    def over_cap(self, now: float) -> bool:
        return self.txcap.over(now)

    def base_interval_s(self, now: float) -> int:
        """0.7.6 (lt_base_interval_s): LOST x2 after 2 h at one place; over the flood cap every mode
        runs at max(IDLE interval, 1 h)."""
        elapsed = now - self.lost_since if self.mode == Mode.LOST else 0
        return base_interval_s(self.mode, self.idle_interval_min * 60, self.track_interval_s, self.over_cap(now),
                               self.lost, elapsed)

    def report_allowed(self, now: float, why: OverCap | None = None) -> bool:
        """Over the cap a wake-up that would report only listens, except for an ack, a console
        report or the low-battery report."""
        if why is None and self.pending_ack:
            why = OverCap.CMD_ACK
        return report_allowed(self.over_cap(now), why)

    def count_flood(self, now: float, n: int = 1) -> None:
        """A flooded packet that make_report does not build: the retransmission after 8 s, a DM,
        BLE or Wi-Fi packets."""
        self.txcap.add(now, n)

    def make_report(self, fix: dict | None, battery_pct: int | None, now: float, j: int = 0,
                    neighbours: list[tuple[bytes, float]] | None = None, stealth: bool = False) -> Packet:
        if fix is not None:
            self.last_fix = (int(fix.get("time", now)), fix["lat"], fix["lon"])
            if self.mode == Mode.LOST:
                # before next_in is computed, as the firmware does
                self.lost.note_fix(round(fix["lat"] * 1e7), round(fix["lon"] * 1e7))
        t, lat, lon = self.last_fix
        nbs = list(neighbours or [])
        rep = Report(
            has_fix=fix is not None, fix_time=t, lat=lat, lon=lon,
            hacc_m=fix.get("hacc", 255) if fix else 255, battery_pct=battery_pct, mode=self.mode,
            cmd_ack=self.pending_ack, cmd_seq=self.last_cmd_seq or 0,
            low_batt=battery_pct is not None and battery_pct < LOW_BATT_PCT,
            nb_total=len(nbs), neighbours=nbs,
        )
        pkt = report_packet(self.keys, self.n, j, rep, stealth=stealth)
        if j == 0:
            self._expire(now)
            self.planned_base_s = self.base_interval_s(now)  # next_in: before this flood is counted
        self.txcap.add(now)  # every packet built here is flooded once (the copy j=1 too)
        self.pending_ack = False
        self.n += 1
        return pkt

    def try_command(self, raw: bytes, now: float) -> Command | None:
        """Called during the RX window after report n-1 was sent."""
        try:
            pkt = Packet.from_bytes(raw)
        except PacketError:
            return None
        pc = command_payload(pkt)
        if pc is None or self.n == 0:
            return None
        n = self.n - 1
        cmd = open_command(self.keys.cmd(epoch(n)), n, pc)
        if cmd is None:
            return None
        if cmd.seq == self.last_cmd_seq:
            self.pending_ack = True  # already applied: only acknowledge again
            return cmd
        self.apply(cmd, now)
        return cmd

    def apply(self, cmd: Command, now: float) -> None:
        a = cmd.args
        if cmd.op == Op.SET_MODE:
            mode, interval_s, duration_min = struct.unpack_from("<BHH", a)
            was = self.mode
            self.mode = Mode(mode) if mode <= Mode.LOST else Mode.IDLE
            if interval_s:
                self.track_interval_s = clamp_track_s(interval_s)  # 0.7.6: at least 120 s (was 30 s)
            if self.mode == Mode.LOST and was != Mode.LOST:
                self.lost_since, self.lost = now, LostState()  # the first 2 h at the set interval
            dur = duration_min or DEFAULT_TRACK_DURATION_MIN
            self.mode_until = now + dur * 60
        elif cmd.op == Op.SET_IDLE:
            interval_min, listen_s = struct.unpack_from("<HH", a)
            if interval_min:
                self.idle_interval_min = interval_min
            if listen_s:
                self.listen_s = listen_s
        elif cmd.op == Op.BUZZ:
            self.buzz_s = a[0]
        self.last_cmd_seq = cmd.seq
        self.pending_ack = True
        self.log.append(f"applied {cmd.op.name} seq={cmd.seq}")


def run_on_air(transport, k0: bytes, n: int, fix: dict | None, battery: int | None, listen_s: int) -> dict:
    """Send one report via transport, listen for a command, return timing info."""
    st = TrackerState(k0=k0, n=n, listen_s=listen_s)
    t0 = time.time()
    pkt = st.make_report(fix and {**fix, "time": int(t0)}, battery, t0)
    transport.send(pkt.to_bytes())
    result = {"n": n, "sent_at": t0, "command": None, "rtt_s": None}
    for raw in transport.receive(timeout=listen_s):
        cmd = st.try_command(raw, time.time())
        if cmd is not None:
            result["command"] = cmd
            result["rtt_s"] = time.time() - t0
            break
    result["next_n"] = st.n
    return result


# ---------------------------------------------------------------- quiet stretches over days (accelerated time)

@dataclass
class DayEvent:
    """What the world looks like from `t` seconds on: a position (None = no fix), an owner's device in range."""

    t: int
    at: tuple[int, int] | None
    owner: bool = False
    mode: Mode = Mode.IDLE


@dataclass
class SimReport:
    t: int
    n: int
    quiet: bool
    owner: bool
    next_in_s: int | None
    reason: int = 0          # wake.Reason of a QUIET report
    cmd_ack: bool = False    # 0.7: sent right after a command taken at a check
    k: int = 0               # 0.7: the check (after report n - 1) at which it was sent; 0 = a regular wake-up
    floods_24h: int = 0      # 0.7.6: floods in the 24 h before this report (the cap counts these)


@dataclass
class SimCheck:
    """A wake-up without a report (0.7: a check): after report `n`, number `k`, receiver window."""

    t: int
    n: int
    k: int
    rx_from: float = 0.0
    rx_to: float = 0.0
    looked: bool = True      # 0.7.3: False = a listen-only wake-up between reports (no GPS, no Bluetooth)


def simulate_days(events: list[DayEvent], days: float, zones=None, interval_s: int = 3600, jitter: float = 0.0,
                  seed: int = 1, cfg=None, keys: Keys | None = None, check_s: int | None = None,
                  commands: list[int] | None = None, checks_out: list[SimCheck] | None = None,
                  report_every: int = 1, track_s: int = TRACK_MIN_S, lost_s: int | None = None,
                  floods_per_report: int = 1) -> tuple[list[SimReport], list[int]]:
    """The firmware's wake loop with `wake.decide`, minus the radio: returns the reports sent (with
    their n after epoch skips) and the times of silent wake-ups. Sleep while quiet is cut so the
    heartbeat wake-up falls exactly on last report + heartbeat (that is what next_in promises).

    0.7: with `keys`, IDLE wake-ups follow the keyed check schedule (wake.check_offsets) from the last
    report instead of a random jitter, so every silent wake-up is check k after report n at exactly
    the time the app computes. `commands`: times at which the app has a command ready; it is taken
    at the first check whose window opens after that time (check_s > 0), executed, and report n + 1
    goes out in the same wake-up with CMD_ACK. Silent wake-ups are also appended to `checks_out`.

    0.7.3 `report_every` (with `keys`): in IDLE only every report_every-th wake-up after a report looks
    (GPS, Bluetooth) and decides; the others only listen for a command (looked=False in checks_out),
    and a command there makes that wake-up a report. A normal report's next_in is then the offset
    of wake-up report_every.

    0.7.6 transmit policy (policy.py, lt_core "0.7.6: transmit policy"): `track_s` (TRACK) and `lost_s`
    (LOST, default track_s) are clamped to 120 s. Every report adds `floods_per_report` to the rolling
    24 h flood count (2 with the retransmission after 8 s or a border copy). While 200 or more: the next
    interval in every mode is max(interval_s, 1 h), and on the keyed IDLE schedule a wake-up that
    would report only listens (the heartbeat and a command's ack still report). LOST doubles its
    interval after 2 h while the fixes stay within 100 m of the first fix of the stretch."""
    from .wake import (CHECK_S, Action, Reason, WakeCfg, WakeIn, WakeState, check_guard_ms, check_jitter, decide,
                       report_next_in, wake_reports)

    track_s = clamp_track_s(track_s)
    lost_s = clamp_track_s(lost_s if lost_s is not None else track_s)
    cap = TxCap()
    lost = LostState()
    lost_since = 0
    prev_mode = Mode.IDLE
    hb_wake = False  # this wake-up is the quiet heartbeat (the firmware's chk_hb)
    kbase = None     # base of the keyed check schedule, fixed at the last report

    if check_s is None:
        check_s = CHECK_S

    cfg = cfg or WakeCfg()
    rnd = random.Random(seed)
    st = WakeState()
    reports: list[SimReport] = []
    silent: list[int] = []
    pending = sorted(commands or [])
    n = 0
    t = 0
    t0 = 0          # transmission start of the last report
    k = 0           # checks since the last report
    sched = 0       # offset of check k from t0 (keyed schedule)
    end = int(days * 86400)
    ev = sorted(events, key=lambda e: e.t)
    while t < end:
        cur = [e for e in ev if e.t <= t]
        e = cur[-1] if cur else DayEvent(0, None)
        if e.mode == Mode.LOST and prev_mode != Mode.LOST:
            lost_since, lost = t, LostState()
        prev_mode = e.mode
        over = cap.over(t)
        w = WakeIn(now_s=t, mode=e.mode, has_fix=e.at is not None, lat_e7=e.at[0] if e.at else 0,
                   lon_e7=e.at[1] if e.at else 0, owner_seen=e.owner, zones=list(zones or []))
        cmd = False
        if keys is not None and k > 0 and check_s > 0 and reports:
            g = check_guard_ms(t - t0) / 1000
            if pending and pending[0] <= t - g:
                pending.pop(0)
                cmd = True
        listen_only = (keys is not None and e.mode == Mode.IDLE and k > 0 and reports
                       and not wake_reports(k, report_every))
        # 0.7.6: over the cap a keyed IDLE wake-up that would report only listens (not the heartbeat)
        capped = (keys is not None and e.mode == Mode.IDLE and k > 0 and bool(reports) and over and not hb_wake
                  and (st.quiet or report_every > 1))
        if (listen_only or capped) and not cmd:
            o = None  # no look, no decision: the state does not change
        else:
            if listen_only or capped:
                w.forced = True  # a command at a listen-only wake-up: look and report now (CMD_ACK)
            o = decide(cfg, w, st)
        if o is None:
            silent.append(t)
            if checks_out is not None:
                g = check_guard_ms(t - t0) / 1000
                checks_out.append(SimCheck(t, n - 1, k, t - g, t + check_s + g, looked=False))
        elif cmd and o.action == Action.SILENT:
            o.action = Action.REPORT_QUIET  # command taken at a check: report n + 1 at once (CMD_ACK)
            st.last_tx_s = t
        if o is None:
            pass
        elif o.action == Action.SILENT:
            silent.append(t)
            if checks_out is not None:
                g = check_guard_ms(t - t0) / 1000
                checks_out.append(SimCheck(t, n - 1, k, t - g, t + check_s + g))
        else:
            if o.epoch_skip:
                n = (epoch(n) + 1) * 64
            reports.append(SimReport(t, n, o.action == Action.REPORT_QUIET, o.reason == Reason.OWNER, 0,
                                     int(o.reason), cmd, k, cap.total(t)))
            if e.mode == Mode.LOST and e.at is not None:
                lost.note_fix(*e.at)  # before next_in, as the firmware does
        # next_in is computed before the report's own floods are counted (send_report, then TX)
        base = base_interval_s(e.mode, interval_s, lost_s if e.mode == Mode.LOST else track_s, over, lost,
                               t - lost_since if e.mode == Mode.LOST else 0)
        if o is not None and o.action != Action.SILENT:
            cap.add(t, floods_per_report)
            n += 1
            t0, k, sched, kbase = t, 0, 0, (base if e.mode == Mode.IDLE else None)
        if keys is not None and e.mode == Mode.IDLE:
            k += 1
            kb = kbase or base
            sched += kb + check_jitter(keys.cmd(epoch(n - 1)), n - 1, k, kb)
            nxt = t0 + sched
        else:
            k += 1
            nxt = t + (int(base * rnd.uniform(1 - jitter, 1 + jitter)) if jitter else base)
        hb_wake = False
        if st.quiet:
            hb = st.last_tx_s + cfg.heartbeat(st.reason)
            hb_wake = hb <= nxt
            nxt = max(t + 1, min(nxt, hb))
        if o is not None and o.action != Action.SILENT:
            r = reports[-1]
            if o.action == Action.REPORT_QUIET:
                r.next_in_s = o.quiet_next_s
            elif keys is not None and e.mode == Mode.IDLE and report_every > 1:
                r.next_in_s = report_next_in(keys.cmd(epoch(n - 1)), n - 1, base, report_every)
            else:
                r.next_in_s = nxt - t
        t = nxt
    return reports, silent

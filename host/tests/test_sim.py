"""Transmit policy of firmware 0.7.6+ in the simulator (docs/design/fw-0.7.6.md section 3).

The policy cases repeat `test_076` in firmware/lt_core/test/host_test.c with the same inputs. The
exact counts (218, 121, 419, 208, 209 and the times the cap is reached) come from running
lt_base_interval_s / lt_txcap_* of lt_core over the same loops; the C test itself only asserts the
bound of 224 (cap + 24) for TRACK at 120 s.
"""
import json
from pathlib import Path

import pytest

from libretag import policy
from libretag.lt2 import Command, Keys, Mode
from libretag.policy import FLOOD_CAP_24H, LostState, OverCap, TxCap, base_interval_s
from libretag.sim import DayEvent, TrackerState, simulate_days

VEC = json.loads((Path(__file__).parent / "vectors" / "lt2.json").read_text())
KEYS = Keys(bytes.fromhex(VEC["k0"]))
K0 = bytes.fromhex(VEC["k0"])
H = 3600
AWAY = (487100000, 212500000)  # synthetic, 48.71 / 21.25


# ---------------------------------------------------------------- policy.py = lt_core (test_076)

def test_constants_match_lt_core():
    h = (Path(__file__).resolve().parents[2] / "firmware/lt_core/lt_core.h").read_text()
    for name, value in (("TRACK_MIN_S", 120), ("FLOOD_CAP_24H", 200), ("CAP_FALLBACK_S", 3600),
                        ("LOST_FAST_S", 7200), ("LOST_SLOW_FACTOR", 2), ("TX_MIN_MV", 3250),
                        ("TX_RESUME_MV", 3350), ("LOW_REPORT_PCT", 10), ("LOW_REARM_PCT", 20),
                        ("BOOT_DELAY_S", 30), ("BOOT_BACKOFF_MAX_S", 3600)):
        assert getattr(policy, name) == value
        assert f"#define LT_{name} {value}" in h, name


def test_txcap_rolling_24h():
    c = TxCap()
    for t in range(0, 86400, 120):
        c.add(t)
    assert c.total(86399) == 720
    assert c.total(86400 + 3600) == 720 - 60  # the oldest hour is gone
    assert c.total(3 * 86400) == 0


def _track_day(track_s=120, idle_s=3600, per=1, days=1):
    """The loop of test_076: TRACK, over-cap check before the report's own floods are counted."""
    c, t, sent, over_at = TxCap(), 0, 0, None
    while t < days * 86400:
        over = c.over(t)
        if over and over_at is None:
            over_at = t
        s = base_interval_s(Mode.TRACK, idle_s, track_s, over)
        c.add(t, per)
        sent += 1
        t += s
    return sent, over_at


@pytest.mark.parametrize("args,sent,over_at", [
    ({}, 218, 24000),                        # test_076: TRACK every 120 s for 24 h, <= 224
    ({"per": 2}, 121, 12000),                # each report with its retransmission
    ({"days": 2}, 419, 24000),
    ({"track_s": 300}, 208, 60000),
    ({"idle_s": 7200}, 209, 24000),          # over the cap: max(IDLE 2 h, 1 h)
])
def test_track_under_the_cap_equals_lt_core(args, sent, over_at):
    assert _track_day(**args) == (sent, over_at)
    assert sent <= FLOOD_CAP_24H * args.get("days", 1) + 24


def test_base_interval():
    assert base_interval_s(Mode.IDLE, 300, 120, True) == 3600     # cap: IDLE 5 min -> 1 h
    assert base_interval_s(Mode.IDLE, 7200, 120, True) == 7200    # cap: IDLE 2 h stays
    assert base_interval_s(Mode.IDLE, 3600, 120, False) == 3600
    assert base_interval_s(Mode.TRACK, 3600, 120, False) == 120


def test_lost_slow_down_equals_lt_core():
    lo = LostState()
    assert base_interval_s(Mode.LOST, 3600, 600, False, lo, 10000) == 600     # no fix yet
    lo.note_fix(487000000, 212500000)
    lo.note_fix(487000300, 212500300)                                         # ~40 m
    assert base_interval_s(Mode.LOST, 3600, 600, False, lo, 3600) == 600      # first 2 h
    assert base_interval_s(Mode.LOST, 3600, 600, False, lo, 7200) == 1200     # still after 2 h
    lo.note_fix(487100000, 212500000)                                         # 1.1 km
    assert base_interval_s(Mode.LOST, 3600, 600, False, lo, 9000) == 600      # moved
    lo.note_fix(487100100, 212500000)
    assert base_interval_s(Mode.LOST, 3600, 600, False, lo, 9600) == 1200     # still again
    assert base_interval_s(Mode.LOST, 3600, 600, True, lo, 9600) == 3600      # over the cap


def test_low_battery_once_per_crossing():
    flags, forced = 0, 0
    for pct in (30, 15, 11, 9, 10, 9, 8, 9, 10, 11, 9, 7, 5, 19, 9, 18, 20, 15, 9, 8, 9, 255, 9, 40):
        flags = policy.low_rearm(flags, pct)
        if policy.low_report_due(flags, pct) and pct != 255:
            forced += 1
            flags |= policy.LOW_REPORTED
    assert forced == 2


def test_boot_delay():
    assert [policy.boot_delay_s(r) for r in (0, 1, 2, 3, 6, 7, 255)] == [30, 30, 120, 240, 1920, 3600, 3600]


def test_over_cap_exceptions():
    assert policy.report_allowed(False)
    assert not policy.report_allowed(True)
    assert all(policy.report_allowed(True, why) for why in OverCap)


# ---------------------------------------------------------------- TrackerState

def test_set_mode_30s_becomes_120s():
    tr = TrackerState(k0=K0)
    tr.apply(Command.set_mode(1, Mode.TRACK, 30, 60), now=0)
    assert tr.track_interval_s == 120
    assert tr.base_interval_s(0) == 120
    tr.apply(Command.set_mode(2, Mode.TRACK, 300, 60), now=0)
    assert tr.track_interval_s == 300
    assert TrackerState(k0=K0, track_interval_s=30).track_interval_s == 120  # a saved mode at boot


def test_tracker_counts_floods_and_falls_back_over_the_cap():
    tr = TrackerState(k0=K0)
    tr.apply(Command.set_mode(1, Mode.TRACK, 120, 24 * 60), now=0)
    tr.pending_ack = False
    fix = {"lat": 48.71, "lon": 21.25, "hacc": 5}
    t, sent = 0.0, 0
    while t < 86400:
        tr.make_report(fix, 80, t)
        sent += 1
        t += tr.planned_base_s  # the interval next_in announced, without jitter
        tr.planned_base_s = None
    assert sent == 218  # lt_core, same loop
    before = tr.txcap.total(t)
    tr.count_flood(t)  # the retransmission after 8 s counts too
    assert tr.txcap.total(t) == before + 1


def test_over_the_cap_a_command_is_still_acknowledged():
    tr = TrackerState(k0=K0, idle_interval_min=5)
    tr.txcap.add(0, FLOOD_CAP_24H)
    assert tr.over_cap(10)
    assert tr.base_interval_s(10) == 3600            # IDLE 5 min -> 1 h
    assert not tr.report_allowed(10)                 # a normal report only listens
    assert tr.report_allowed(10, OverCap.CONSOLE)
    assert tr.report_allowed(10, OverCap.LOW_BATTERY)
    tr.apply(Command.set_mode(7, Mode.IDLE), now=10)  # a command arrives
    assert tr.pending_ack and tr.report_allowed(10)  # its ack goes out
    pkt = tr.make_report(None, 80, 10)
    assert pkt is not None and not tr.pending_ack
    assert tr.txcap.total(10) == FLOOD_CAP_24H + 1
    tr.apply(Command.set_mode(8, Mode.TRACK, 120, 60), now=20)
    assert tr.base_interval_s(20) == 3600            # TRACK over the cap: max(IDLE, 1 h)
    assert not tr.over_cap(24 * H)                   # the flooded hour aged out
    assert tr.base_interval_s(24 * H) == 120


def test_tracker_lost_slows_down_after_2h_and_back_on_movement():
    tr = TrackerState(k0=K0)
    tr.apply(Command.set_mode(1, Mode.LOST, 600, 600), now=0)
    here = {"lat": 48.70, "lon": 21.25, "hacc": 5}
    near = {"lat": 48.7003, "lon": 21.25, "hacc": 5}  # ~33 m
    far = {"lat": 48.71, "lon": 21.25, "hacc": 5}     # ~1.1 km
    tr.make_report(here, 80, 0)
    assert tr.planned_base_s == 600
    for t in range(600, 7200, 600):
        tr.make_report(near, 80, t)
        assert tr.planned_base_s == 600              # first 2 h at the set interval
    tr.make_report(near, 80, 7200)
    assert tr.planned_base_s == 1200                 # still after 2 h: x2
    tr.make_report(far, 80, 8400)
    assert tr.planned_base_s == 600                  # moved: back at once
    tr.make_report(None, 80, 9000)
    assert tr.planned_base_s == 600                  # no fix changes nothing
    tr.make_report(far, 80, 9600)
    assert tr.planned_base_s == 1200
    tr.apply(Command.set_mode(2, Mode.LOST, 600, 600), now=9700)  # still LOST: the 2 h keep running
    assert tr.base_interval_s(9700) == 1200
    tr.apply(Command.set_mode(3, Mode.IDLE), now=9800)
    tr.apply(Command.set_mode(4, Mode.LOST, 600, 600), now=9900)  # LOST again: a new 2 h
    assert tr.base_interval_s(9900) == 600


# ---------------------------------------------------------------- simulate_days

def test_sim_track_day_equals_lt_core():
    reps, _ = simulate_days([DayEvent(0, AWAY, mode=Mode.TRACK)], 1)
    assert len(reps) == 218
    assert max(r.floods_24h for r in reps) <= FLOOD_CAP_24H + 24  # hourly reports go on over the cap
    first_hourly = next(r for r in reps if r.next_in_s == 3600)
    assert first_hourly.t == 24000 and first_hourly.floods_24h == FLOOD_CAP_24H
    reps30, _ = simulate_days([DayEvent(0, AWAY, mode=Mode.TRACK)], 1, track_s=30)
    assert [r.t for r in reps30] == [r.t for r in reps]  # 30 s is clamped to 120 s
    reps2, _ = simulate_days([DayEvent(0, AWAY, mode=Mode.TRACK)], 1, floods_per_report=2)
    assert len(reps2) == 121
    reps5, _ = simulate_days([DayEvent(0, AWAY, mode=Mode.TRACK)], 1, track_s=300)
    assert len(reps5) == 208


def test_sim_lost_slows_down():
    ev = [DayEvent(0, AWAY, mode=Mode.LOST), DayEvent(4 * H, (487200000, 212500000), mode=Mode.LOST)]
    reps, _ = simulate_days(ev, 6 * H / 86400, lost_s=600)
    gaps = [(r.t, r.next_in_s) for r in reps]
    assert all(g == 600 for t, g in gaps if t < 7200)
    assert all(g == 1200 for t, g in gaps if 7200 <= t < 4 * H)
    moved = next(i for i, (t, _) in enumerate(gaps) if t >= 4 * H)
    assert gaps[moved][1] == 600                      # the first report after the move
    assert all(g == 1200 for t, g in gaps[moved + 1:])  # still again, more than 2 h into LOST


def test_sim_keyed_idle_over_the_cap_only_listens_but_acks():
    """IDLE every 2 min, report every 2nd wake-up: over the cap the reporting wake-ups only listen,
    a command at one of them still gets its report with CMD_ACK."""
    checks = []
    reps, _ = simulate_days([DayEvent(0, None)], 1, interval_s=120, keys=KEYS, report_every=2,
                            checks_out=checks, commands=[20 * H])
    day = [r for r in reps if r.t < 86400]
    assert len(day) <= FLOOD_CAP_24H + 24
    assert max(r.floods_24h for r in reps if not r.cmd_ack) <= FLOOD_CAP_24H
    ack = [r for r in reps if r.cmd_ack]
    assert len(ack) == 1 and ack[0].t >= 20 * H and ack[0].floods_24h >= FLOOD_CAP_24H
    last = max(r.t for r in reps if r.t < ack[0].t)
    assert ack[0].t - last > 6 * H                       # hours over the cap without a report
    capped = [c for c in checks if last < c.t < ack[0].t]
    assert capped and all(not c.looked for c in capped)  # the reporting wake-ups only listened


# ---------------------------------------------------------------- energy.py: the live preset by default

def test_energy_defaults_to_the_live_preset():
    from libretag import energy
    from libretag.meshcore import lora_airtime_s
    from libretag.proto import REPORT_GRP_LEN

    sc = energy.Scenario()
    assert (sc.sf, sc.bw_hz, sc.cr) == (7, 62_500, 5)
    p = energy.daily_mah(energy.X1, sc)
    assert (p["sf"], p["bw_hz"], p["cr"]) == (7, 62_500, 5)
    assert p["airtime_s"] == lora_airtime_s(2 + REPORT_GRP_LEN, 7, 62_500, 5)
    assert energy.default_table().splitlines()[0].startswith("report every hour, SF7/62.5 kHz/CR4/5")
    assert "SF7/62.5 kHz/CR4/5" in energy.report_every_table().splitlines()[0]

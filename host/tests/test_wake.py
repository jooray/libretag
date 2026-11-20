"""Wake decision (PROTOCOL.md 2.1): vectors shared with lt_core, plus multi-day runs of the simulator."""
import json
from pathlib import Path

from libretag.lt2 import Mode, epoch
from libretag.sim import DayEvent, simulate_days
from libretag.wake import CHECK_S, Action, Reason, WakeCfg, WakeIn, WakeState, Zone, decide, vector_scenarios, zone_distance_m

VEC = json.loads((Path(__file__).parent / "vectors" / "lt2.json").read_text())
HOME = Zone(487000000, 212500000, 150)
IN = (487005000, 212500000)
AWAY = (487100000, 212500000)
H = 3600


def test_vectors_match():
    assert VEC["wake"]["scenarios"] == vector_scenarios()


def test_distance():
    assert abs(zone_distance_m(HOME, 487009000, 212500000) - 99.5) < 0.5
    assert abs(zone_distance_m(HOME, 487000000, 212513500) - 99.4) < 0.5


def test_every_rule_once():
    cfg, st = WakeCfg(), WakeState()
    z = [HOME]
    d = lambda t, **k: decide(cfg, WakeIn(now_s=t, zones=z, **k), st)
    fix = dict(has_fix=True, lat_e7=IN[0], lon_e7=IN[1])
    assert d(0).action == Action.REPORT                                   # no fix, nothing known
    o = d(H, **fix)
    assert (o.action, o.reason, o.quiet_next_s) == (Action.REPORT_QUIET, Reason.ZONE, 24 * H)  # arrival
    assert d(2 * H, **fix).action == Action.SILENT                        # quiet
    assert d(3 * H, mode=Mode.LOST, **fix).action == Action.REPORT        # LOST ignores zones
    assert not st.quiet
    assert d(4 * H, **fix).action == Action.REPORT_QUIET                  # back to quiet
    o = d(5 * H)                                                          # no fix: fail-open
    assert o.action == Action.REPORT and o.reset_home_silent and not o.epoch_skip
    o = d(6 * H, owner_seen=True)
    assert (o.action, o.reason, o.quiet_next_s) == (Action.REPORT_QUIET, Reason.OWNER, 8 * H)
    assert d(13 * H, owner_seen=True).action == Action.SILENT
    assert d(14 * H, owner_seen=True).action == Action.REPORT_QUIET       # 8 h cap
    assert d(15 * H, forced=True, owner_seen=True).action == Action.REPORT_QUIET
    o = d(19 * H, has_fix=True, lat_e7=AWAY[0], lon_e7=AWAY[1])
    assert o.action == Action.REPORT and o.epoch_skip                     # 13 h of quiet


def test_three_days_at_home_then_leave():
    # day 0 08:00 drive home, then 3 days parked, then leaves at 10:00 on day 3
    ev = [DayEvent(0, AWAY), DayEvent(1 * H, IN), DayEvent(3 * 86400 + 10 * H, AWAY)]
    reps, silent = simulate_days(ev, 3.6, zones=[HOME])
    quiet = [r for r in reps if r.quiet and r.reason == Reason.ZONE]
    assert reps[0].t == 0 and not reps[0].quiet
    assert quiet[0].t == H                                                 # arrival, the next wake-up
    hb = [r.t for r in quiet[1:]]
    assert hb == [H + 24 * H, H + 48 * H, H + 72 * H]                      # exactly every 24 h
    assert all(r.next_in_s == 24 * H for r in quiet)
    leave = [r for r in reps if r.t >= 3 * 86400 + 10 * H][0]
    assert leave.t == 3 * 86400 + 10 * H and not leave.quiet               # on the normal schedule
    assert epoch(leave.n) > epoch(quiet[-1].n) and leave.n % 64 == 0       # epoch skip after the quiet
    assert len(silent) > 60


def test_owner_cap_on_a_long_drive_and_gps_dark_garage():
    ev = [DayEvent(0, AWAY, owner=True), DayEvent(20 * H, None), DayEvent(30 * H, None, owner=True)]
    reps, _ = simulate_days(ev, 2, zones=[HOME])
    owner_q = [r.t for r in reps if r.quiet and r.owner]
    assert owner_q[:3] == [0, 8 * H, 16 * H]
    plain = [r.t for r in reps if not r.quiet]
    assert plain[0] == 20 * H and all(t in plain for t in range(20 * H, 30 * H, H))  # garage: every hour
    assert 30 * H in owner_q and 38 * H in owner_q


def test_lost_breaks_quiet_and_returns():
    ev = [DayEvent(0, IN), DayEvent(10 * H, IN, mode=Mode.LOST), DayEvent(12 * H, IN)]
    reps, _ = simulate_days(ev, 1, zones=[HOME])
    lost = [r for r in reps if 10 * H <= r.t < 12 * H]
    assert len(lost) == 60 and not any(r.quiet for r in lost)             # every 2 min
    after = [r for r in reps if r.t >= 12 * H]
    assert after[0].quiet and len(after) == 1


def test_jittered_schedule_keeps_heartbeat():
    reps, _ = simulate_days([DayEvent(0, IN)], 5, zones=[HOME], jitter=0.2, seed=7)
    q = [r.t for r in reps if r.quiet]
    assert [b - a for a, b in zip(q, q[1:])] == [24 * H] * (len(q) - 1) and len(q) == 5


# ---------------------------------------------------------------- 0.7: parked quiet and checks

from libretag.lt2 import Keys, open_command, seal_command, Command  # noqa: E402
from libretag.sim import SimCheck  # noqa: E402
from libretag.wake import check_guard_ms, check_jitter, check_offsets, vector_cfg, vector_checks  # noqa: E402

PARK = (487150000, 212650000)
MOVED = (487177140, 212650000)
KEYS = Keys(bytes.fromhex(VEC["k0"]))


def test_check_vectors_match():
    assert VEC["checks"] == vector_checks(KEYS)
    assert VEC["wake"]["cfg"] == vector_cfg()


def test_check_jitter_bounds_and_schedule():
    ce = KEYS.cmd(0)
    for base in (60, 900, 3600, 7200, 21600):
        js = [check_jitter(ce, 7, k, base) for k in range(1, 200)]
        span = base // 5
        assert min(js) >= -(span // 2) and max(js) < span - span // 2
        assert len(set(js)) > 10
    offs = check_offsets(ce, 7, 3600, 6)
    assert offs[0] == 3600 + check_jitter(ce, 7, 1, 3600)
    assert all(b - a == 3600 + check_jitter(ce, 7, k + 2, 3600) for k, (a, b) in enumerate(zip(offs, offs[1:])))
    assert check_guard_ms(86400) == 10640 and check_guard_ms(0) == 2000
    assert check_offsets(ce, 7, 3600, 64, until_s=8 * H)[-1] < 8 * H


def test_parked_overnight_quiet_then_move():
    # drive (moving), park at 18:00 for the night, leave at 08:00 next day
    ev = [DayEvent(0, (487000000 + 0, 212000000)), DayEvent(H, (487050000, 212300000)),
          DayEvent(2 * H, PARK), DayEvent(16 * H, MOVED)]
    reps, silent = simulate_days(ev, 1, zones=[HOME])
    parked = [r for r in reps if r.quiet and r.reason == Reason.STILL and r.t < 16 * H]
    assert parked[0].t == 4 * H                                            # 3rd report at the same place
    assert [r.t for r in parked] == [4 * H, 10 * H]                        # then every 6 h
    assert all(r.next_in_s == 6 * H for r in parked)
    moved = [r for r in reps if r.t >= 16 * H][0]
    assert moved.t == 16 * H and not moved.quiet                           # first check after moving
    assert {t for t in silent if t < 16 * H} == {t for t in range(5 * H, 16 * H, H)} - {10 * H}
    assert [r.t for r in reps if r.quiet and r.t > 16 * H] == [18 * H]  # parked again at the new place


def test_checks_follow_the_keyed_schedule_and_take_a_command():
    ev = [DayEvent(0, IN)]
    checks: list[SimCheck] = []
    reps, silent = simulate_days(ev, 1.4, zones=[HOME], keys=KEYS, checks_out=checks, commands=[5 * H + 600])
    first = reps[0]
    assert first.quiet and first.next_in_s == 24 * H
    # the app's view: check times from report 0's C_e
    expect = check_offsets(KEYS.cmd(0), 0, H, 64, until_s=24 * H)
    got = [c.t - first.t for c in checks if c.n == 0]
    ack = [r for r in reps if r.cmd_ack]
    assert len(ack) == 1 and ack[0].n == 1 and ack[0].quiet
    k_ack = ack[0].k
    assert ack[0].t - first.t == expect[k_ack - 1] and got == expect[:k_ack - 1]
    assert ack[0].t >= 5 * H + 600 and ack[0].t - (5 * H + 600) < 2 * H    # within one interval
    # after report 1 the schedule restarts from it; the heartbeat is 24 h after report 1
    later = [c for c in checks if c.n == 1]
    assert [c.t - ack[0].t for c in later] == check_offsets(KEYS.cmd(0), 1, H, len(later))
    hb = [r for r in reps if r.n == 2][0]
    assert hb.t == ack[0].t + 24 * H and hb.quiet
    for c in checks:
        if c.n > 1:
            continue
        g = check_guard_ms(c.t - (first.t if c.n == 0 else ack[0].t)) / 1000
        assert c.rx_from == c.t - g and c.rx_to == c.t + CHECK_S + g


def test_command_sealed_for_a_check_opens_only_there():
    ce = KEYS.cmd(0)
    p = seal_command(ce, 3, Command.buzz(1, 5), 2)
    assert open_command(ce, 3, p, 2) is not None
    assert open_command(ce, 3, p) is None and open_command(ce, 4, p, 2) is None


def test_still_off_and_moving_never_parks():
    cfg = WakeCfg(still_after=0)
    reps, silent = simulate_days([DayEvent(0, PARK)], 1, cfg=cfg)
    assert not silent and not any(r.quiet for r in reps)
    # a drive: every fix 1 km further, never parks
    ev = [DayEvent(i * H, (487000000 + i * 90000, 212000000)) for i in range(12)]
    reps, silent = simulate_days(ev, 0.5)
    assert not silent


# ---------------------------------------------------------------- 0.7.3: report every n-th wake-up, window by preset


def test_report_every_and_check_window_vectors():
    from libretag.lt2 import Keys, epoch as ep
    from libretag.wake import check_offsets, check_window_s, report_next_in, vector_check_window, vector_report_every
    keys = Keys(bytes.fromhex(VEC["k0"]))
    assert VEC["report_every"] == vector_report_every(keys)
    assert VEC["check_window"] == vector_check_window()
    for v in VEC["report_every"]:
        ce = keys.cmd(ep(v["n"]))
        assert v["offsets"] == check_offsets(ce, v["n"], v["base_s"], len(v["offsets"]))
        assert v["next_in"] == v["offsets"][v["every"] - 1] == report_next_in(ce, v["n"], v["base_s"], v["every"])
    # the reference preset keeps check_s; slower presets lengthen it, capped at 180 s, never shorter
    assert check_window_s(30, 7, 62500, 5) == 30 and check_window_s(30, 8, 62500, 8) == 59
    assert check_window_s(120, 8, 62500, 8) == 180 and check_window_s(30, 5, 62500, 5) == 30
    assert check_window_s(0, 12, 125000, 8) == 0


def test_report_every_three_listens_between_without_looking():
    cfg = WakeCfg(still_after=0)          # moving about, never parked
    checks: list[SimCheck] = []
    reps, silent = simulate_days([DayEvent(0, AWAY)], 1, cfg=cfg, keys=KEYS, checks_out=checks, report_every=3)
    assert not any(r.quiet for r in reps)
    assert all(not c.looked for c in checks) and len(checks) == len(silent)
    # every report is followed by two listen-only wake-ups, and next_in points at the third
    for a, b in zip(reps, reps[1:]):
        between = [c for c in checks if a.t < c.t < b.t]
        assert [c.k for c in between] == [1, 2] and b.k == 3
        assert b.t - a.t == a.next_in_s == check_offsets(KEYS.cmd(epoch(a.n)), a.n, H, 3)[-1]
    assert 7 <= len(reps) <= 9 and len(checks) >= 2 * (len(reps) - 1)


def test_report_every_command_at_a_listen_only_wake_up():
    cfg = WakeCfg(still_after=0)
    checks: list[SimCheck] = []
    reps, _ = simulate_days([DayEvent(0, AWAY)], 0.5, cfg=cfg, keys=KEYS, checks_out=checks, report_every=3,
                            commands=[600])
    ack = [r for r in reps if r.cmd_ack]
    assert len(ack) == 1 and ack[0].k == 1 and ack[0].n == 1   # the first wake-up after report 0 takes it
    assert ack[0].t == check_offsets(KEYS.cmd(0), 0, H, 1)[0]


def test_report_every_while_quiet_looks_only_every_third_wake_up():
    ev = [DayEvent(0, IN), DayEvent(10 * H, AWAY)]
    checks: list[SimCheck] = []
    reps, _ = simulate_days(ev, 1, zones=[HOME], keys=KEYS, checks_out=checks, report_every=3)
    assert reps[0].quiet and reps[0].next_in_s == 24 * H
    quiet = [c for c in checks if c.n == 0]
    assert [c.looked for c in quiet] == [c.k % 3 == 0 for c in quiet]
    # it left at 10 h; the move is noticed at the next looking wake-up, not before
    left = [r for r in reps if r.t >= 10 * H][0]
    assert left.k % 3 == 0 and not left.quiet
    assert all(not c.looked for c in quiet if c.t >= 10 * H)


def test_energy_report_every_three_saves_against_hourly_reports():
    from libretag.energy import L1_PRO, X1, Scenario, daily_mah
    for dev in (L1_PRO, X1):
        hourly = daily_mah(dev, Scenario(report_interval_min=60, sf=7, bw_hz=62500, cr=5))
        every3 = daily_mah(dev, Scenario(report_interval_min=180, sf=7, bw_hz=62500, cr=5, check_interval_min=60))
        assert hourly["checks"] == 0 and every3["listen_only_per_day"] == 16
        assert every3["total"] < hourly["total"] and every3["days"] > hourly["days"]

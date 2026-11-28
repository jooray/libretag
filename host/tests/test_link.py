"""The tag's link policy in Python (`libretag.link`) against firmware/lt_core/test/link_test.c.

The table rows repeat `test_tables` of link_test.c with the same inputs; the multi-day scenarios run
through `sim.simulate_days(link=...)` and must give the flood counts the C test asserts (SPEC 3.2
acceptance list): home 48 h 0, phone off 8 h 0 and 14 h 1, bridge off 24 h 1 (2 when nothing repeats
the hold flood), drive and park 5, two hops for 3 days the boot flood only, out of range 1 a day.
Positions are synthetic (48.70, 21.25)."""
import re
from pathlib import Path

import pytest

from libretag import link as L
from libretag.link import (EV_BOOT, EV_CMD, Ack, Link, LinkEnv, LinkIn, LinkSim, LinkState, Send, Why)
from libretag.lt2 import Mode
from libretag.sim import DayEvent, simulate_days
from libretag.wake import WakeCfg

H = 3600
PERIOD = 3 * H
HOME = (487000000, 212500000)
LT_CORE_H = Path(__file__).resolve().parents[2] / "firmware/lt_core/lt_core.h"


def inp(now, fix=True, lat=HOME[0], lon=HOME[1], **kw):
    return LinkIn(now_s=now, has_fix=fix, lat_e7=lat if fix else 0, lon_e7=lon if fix else 0, acc_m=5,
                  period_s=PERIOD, **kw)


def near_at_home(t):
    s = LinkState()
    L.on_ack(s, inp(t), Ack(hold=4))
    return s


ROUTED2 = Ack(routed=True, hash_size=2, path=[b"\xa5\xe1", b"\xb7\xd2"])


def test_constants_match_lt_core():
    h = LT_CORE_H.read_text()

    def c(name):
        return int(re.search(rf"#define {name} (\d+)U?", h).group(1))
    assert c("LT_IDLE_BUDGET") == L.IDLE_BUDGET
    assert c("LT_HEARTBEAT_S") == L.HEARTBEAT_S
    assert c("LT_PARKED_NEXT_S") == L.PARKED_NEXT_S
    assert c("LT_BACKOFF_MAX_S") == L.BACKOFF_MAX_S
    assert c("LT_MOVE_M") == L.MOVE_M
    assert c("LT_ACK_DECIDE_MS") == 12000 and c("LT_ACK_PER_HOP_MS") == 1500
    for name, v in (("LT_EV_BOOT", 1), ("LT_EV_CMD", 2), ("LT_EV_LOW", 4), ("LT_EV_MODE", 8), ("LT_EV_CONSOLE", 16)):
        assert int(re.search(rf"#define {name} 0x([0-9a-fA-F]+)", h).group(1), 16) == v
    names = re.search(r"lt_why_names\[LT_WHY_COUNT\] = \{([^}]*)\}",
                      (LT_CORE_H.parent / "lt_link.c").read_text()).group(1)
    assert re.findall(r'"(\w+)"', names) == L.WHY_NAMES


# ---------------------------------------------------------------- SPEC 3.2 tables (link_test.c test_tables)

def test_boot_far_floods_outside_budget():
    s = LinkState()
    o = L.decide(s, inp(100, fix=False, events=EV_BOOT))
    assert s.link == Link.FAR and o.first == Send.FLOOD and o.must and o.important and o.why == Why.BOOT
    assert not o.idle_fallback


def test_near_rows():
    s = near_at_home(1000)
    assert s.link == Link.NEAR and s.t_ack_ok and s.t_ack_s == 1000 and s.hold == 4 and s.ref_fix and not s.path
    i = inp(1000 + PERIOD)
    o = L.decide(s, i)
    assert o.first == Send.NEAR and not o.must and not o.at_check and o.why == Why.PERIOD
    i.events = EV_CMD
    o = L.decide(s, i)
    assert o.first == Send.NEAR and o.must and o.decide_ms == 12000 and o.why == Why.CMD and not o.idle_fallback
    assert L.on_unconfirmed(s, i, o) and s.link == Link.FAR


def test_hold_and_guarded():
    s = near_at_home(1000)
    o = L.decide(s, inp(1000 + 12 * H))
    assert o.must and o.why == Why.HOLD and o.decide_ms == 12000 and o.idle_fallback and o.important
    assert not L.decide(s, inp(1000 + 12 * H - 1)).must
    s = LinkState()
    L.on_ack(s, inp(500), Ack(hold=0))
    o = L.decide(s, inp(500 + PERIOD))
    assert o.must and o.decide_ms == 12000 and o.why == Why.HOLD
    from libretag.quietmesh import hold_s
    assert hold_s(1, PERIOD) == PERIOD + 300 and hold_s(0, PERIOD) is None


def test_near_moved_patience():
    north = HOME[0] + 50000  # 550 m
    s = near_at_home(1000)
    i = inp(1000 + PERIOD, lat=north)
    o = L.decide(s, i)
    assert o.moved and o.at_check and not o.must and o.why == Why.MOVED
    L.on_window_end(s, o, False)
    assert s.pending and not s.pending_nofix
    assert L.on_check_end(s, False, False)
    i.check_failed = True
    o = L.decide(s, i)
    assert o.must and o.why == Why.MOVED and o.idle_fallback and o.first == Send.NEAR and o.decide_ms == 12000
    # a late ACK at the check settles it
    s = near_at_home(1000)
    i = inp(1000 + PERIOD, lat=north)
    o = L.decide(s, i)
    L.on_window_end(s, o, False)
    L.on_ack(s, i, Ack(hold=4))
    assert not s.pending and not L.on_check_end(s, True, False)
    # ack_patience 2
    s = near_at_home(1000)
    o = L.decide(s, inp(1000 + PERIOD, lat=north, ack_patience=2))
    assert o.must and o.decide_ms == 12000 and not o.at_check
    # 440 m with 200 m accuracy is not moved; a zone is never moved
    s = near_at_home(1000)
    i = inp(1000 + PERIOD, lat=HOME[0] + 4000)
    i.acc_m = 200
    o = L.decide(s, i)
    assert not o.moved and not o.at_check
    assert not L.decide(s, inp(1000 + PERIOD, lat=north, in_zone=True)).moved


def test_moved_without_gps():
    s = near_at_home(1000)
    i = inp(1000 + PERIOD, fix=False)
    o = L.decide(s, i)
    assert o.at_check and not o.moved_known and not o.must
    L.on_window_end(s, o, False)
    assert s.pending and s.pending_nofix
    assert not L.on_check_end(s, False, False)
    L.on_window_end(s, o, False)
    assert L.on_check_end(s, False, True)
    i.check_failed = i.nb_moved = True
    o = L.decide(s, i)
    assert o.moved and o.must and o.why == Why.MOVED


def test_routed_rows():
    s = LinkState()
    i = inp(2000)
    assert not L.on_ack(s, i, Ack(routed=True, hash_size=2, path=[b"\0\0"]), near_taken=True)
    assert L.on_ack(s, i, ROUTED2) and s.link == Link.ROUTED and s.path_n == 2 and s.path_hs == 2 and s.path_fix
    i = inp(2000 + PERIOD)
    o = L.decide(s, i)
    assert o.first == Send.NEAR and not o.must
    i.events = EV_CMD
    o = L.decide(s, i)
    assert o.first == Send.PATH and o.decide_ms == 15000
    assert L.on_unconfirmed(s, i, o) and s.link == Link.FAR and not s.path
    # moved, home network changed: FAR; a near ACK after a routed one drops the path
    L.on_ack(s, inp(2000), ROUTED2)
    o = L.decide(s, inp(2000 + PERIOD, lat=HOME[0] + 50000))
    assert s.link == Link.FAR and o.path_dropped
    L.on_ack(s, inp(2000), ROUTED2)
    o = L.decide(s, inp(2000 + PERIOD, home_changed=True))
    assert s.link == Link.FAR and o.path_dropped
    L.on_ack(s, inp(2000), ROUTED2)
    L.on_ack(s, inp(2000), Ack(hold=4))
    assert s.link == Link.NEAR and not s.path
    # routed hash sizes and counts out of range are refused
    assert not L.on_ack(LinkState(), inp(0), Ack(routed=True, hash_size=4, path=[b"\0" * 4]))
    assert not L.on_ack(LinkState(), inp(0), Ack(routed=True, hash_size=1, path=[b"\1", b"\2", b"\3"]))


def test_far_heartbeat_and_nofix_backoff():
    s = LinkState()
    i = inp(0, events=EV_BOOT)
    L.on_flood_sent(s, i, L.decide(s, i))
    o = L.decide(s, inp(PERIOD))
    assert o.first == Send.NEAR and not o.must
    o = L.decide(s, inp(24 * H))
    assert o.first == Send.FLOOD and o.why == Why.HEARTBEAT and o.important
    s = LinkState()
    i = inp(0, fix=False, events=EV_BOOT)
    L.on_flood_sent(s, i, L.decide(s, i))
    t, gaps = 0, []
    for r in range(1, 41):
        if len(gaps) == 6:
            break
        i = inp(r * PERIOD, fix=False)
        o = L.decide(s, i)
        if o.first == Send.FLOOD:
            assert o.why == Why.NOFIX and not o.important
            gaps.append(i.now_s - t)
            t = i.now_s
            L.on_flood_sent(s, i, o)
    assert gaps[:5] == [2 * PERIOD, 4 * PERIOD, 8 * PERIOD, 24 * H, 24 * H]


def test_far_moved_then_parked():
    s = LinkState()
    i = inp(0, events=EV_BOOT)
    L.on_flood_sent(s, i, L.decide(s, i))
    i = inp(PERIOD, lat=HOME[0] + 100000)
    o = L.decide(s, i)
    assert o.first == Send.FLOOD and o.why == Why.MOVED
    L.on_flood_sent(s, i, o)
    i = inp(2 * PERIOD, lat=HOME[0] + 100000)
    o = L.decide(s, i)
    assert o.first == Send.FLOOD and o.why == Why.PARKED and o.parked and L.flood_next_in(s, i, o) == 86400
    L.on_flood_sent(s, i, o)
    o = L.decide(s, inp(3 * PERIOD, lat=HOME[0] + 100000))
    assert o.first == Send.NEAR and not o.must


def test_r29_extra_flood():
    s = near_at_home(0)
    i = inp(12 * H)
    o = L.decide(s, i)
    assert L.on_unconfirmed(s, i, o)
    L.on_flood_sent(s, i, o)
    assert L.on_flood_result(s, i, 3, False, o) == 1
    o = L.decide(s, inp(12 * H + PERIOD))
    assert o.first == Send.FLOOD and o.why == Why.AGAIN and o.idle_fallback


def test_idle_budget():
    s = LinkState()
    base, floods = 100000, 0
    for k in range(10):
        i = inp(base + k * H, lat=HOME[0] + (k + 1) * 100000)
        o = L.decide(s, i)
        if k == 7:
            assert o.budget_last and o.budget_free_s == 86400 - 7 * H
            assert L.flood_next_in(s, i, o) == o.budget_free_s
        if o.first == Send.FLOOD:
            floods += 1
            L.on_flood_sent(s, i, o)
        else:
            assert k >= 8 and o.budget_hit
    assert floods == 8 and L.budget_left(s, base + 9 * H) == 0
    assert L.budget_left(s, base + 86400) == 1
    i.events, i.now_s = EV_CMD, base + 9 * H + 10
    o = L.decide(s, i)
    assert o.first == Send.FLOOD and not o.idle_fallback


def test_track_and_lost():
    s = near_at_home(0)
    i = inp(600, mode=Mode.TRACK)
    i.period_s = 300
    o = L.decide(s, i)
    assert o.first == Send.NEAR and o.must and o.decide_ms == 12000 and o.why == Why.TRACK and o.important
    s = LinkState()
    o = L.decide(s, i)
    assert o.first == Send.FLOOD and o.important
    L.on_ack(s, i, Ack(routed=True, hash_size=2, path=[b"\xa5\xe1"]))
    i.now_s += 300
    o = L.decide(s, i)
    assert o.first == Send.PATH and o.decide_ms == 13500


def test_ladder():
    s = LinkState()
    assert L.level_next(s, 0, Mode.IDLE, 3) == 0
    s.lad = L.Ladder(2, 0)
    assert L.level_next(s, 10, Mode.IDLE, 3) == 2
    s.unscoped_ok, s.last_unscoped_s = True, 5
    assert L.level_next(s, 10, Mode.IDLE, 3) == 1 and L.level_next(s, 10, Mode.LOST, 3) == 2
    assert L.level_next(s, 86400 + 1, Mode.IDLE, 3) == 0


# ---------------------------------------------------------------- multi-day scenarios through simulate_days

def run(env: LinkEnv, days: float, events=None, start_near=False):
    env.start_near = start_near
    ls = LinkSim(env=env, start_at=HOME)
    # every 3 h, no jitter, the wake decision's parked stretch off (0.10.0: hellos go out anyway)
    simulate_days(events or [DayEvent(0, HOME)], days, interval_s=PERIOD, cfg=WakeCfg(still_after=0), link=ls)
    return ls


def window(a, b):
    return lambda t: t < a or t >= b


def test_scenario_home_48h():
    ls = run(LinkEnv(), 2, start_near=True)
    assert ls.count.floods == 0 and ls.state.link == Link.NEAR and ls.count.repeater_tx == 0


@pytest.mark.parametrize("off_h,floods", [(8, 0), (14, 1)])
def test_scenario_phone_off(off_h, floods):
    on = window(6 * H, 6 * H + off_h * H)
    ls = run(LinkEnv(app=on, hops=lambda t: 0 if on(t) else 9), 2, start_near=True)
    assert ls.count.floods == floods
    assert ls.state.link == Link.NEAR  # the app recovers by itself


def test_scenario_bridge_off_24h():
    on = window(3 * H, 27 * H)
    env = LinkEnv(near=on, hops=lambda t: 0 if on(t) else 9)
    ls = run(env, 3, start_near=True)
    assert ls.count.floods == 1
    flood = [r for r in ls.count.log if r["flood"]]
    last_ack = max(r["t"] for r in ls.count.log if r["acked"] and r["t"] < flood[0]["t"])
    assert flood[0]["t"] - last_ack == 12 * H  # hold 12 h from the last ACK before the stop
    env.repeated = False  # nothing repeats the hold flood: R29 adds one, copy 10 goes one level wider
    ls = run(env, 3, start_near=True)
    assert ls.count.floods == 2 and ls.count.wide >= 1


def test_scenario_drive_and_park():
    ev = [DayEvent(0, HOME), DayEvent(3 * PERIOD, (HOME[0] + 30000, HOME[1])),
          DayEvent(4 * PERIOD, (HOME[0] + 60000, HOME[1])), DayEvent(5 * PERIOD, (HOME[0] + 90000, HOME[1]))]
    near = lambda t: t // PERIOD < 3
    ls = run(LinkEnv(near=near, hops=lambda t: 0 if near(t) else 9), 2, ev, start_near=True)
    # moved (at the check), moved, arrived, parked, heartbeat 24 h later
    assert ls.count.floods == 5
    assert [r["why"] for r in ls.count.log if r["flood"]] == ["moved", "moved", "moved", "parked", "heartbeat"]


def test_scenario_two_hops():
    ls = run(LinkEnv(near=lambda t: False, hops=lambda t: 2), 3)
    assert ls.state.link == Link.ROUTED and ls.count.floods == 1  # the boot flood only
    assert ls.count.routed >= 2 and ls.count.repeater_tx < 3 * 40


def test_scenario_out_of_range():
    ls = run(LinkEnv(near=lambda t: False, hops=lambda t: 9), 3)
    assert ls.count.floods == 3 and ls.state.link == Link.FAR  # one a day


def test_scenario_far_moving_budget():
    """A tag moving out of range in IDLE: at most 8 fallback floods in 24 h, then hellos only."""
    ev = [DayEvent(k * PERIOD // 3, (HOME[0] + k * 100000, HOME[1])) for k in range(30)]
    ls = LinkSim(env=LinkEnv(near=lambda t: False, hops=lambda t: 9))
    simulate_days(ev, 1, interval_s=H, cfg=WakeCfg(still_after=0), link=ls)
    assert ls.count.floods == 1 + 8  # the boot flood is an event, outside the budget

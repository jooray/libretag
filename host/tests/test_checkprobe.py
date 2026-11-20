"""Schedule glue of host/tools/checkprobe.py against the shared vectors (lt2.json `checks`, `report_every`)."""
import base64
import importlib.util
import json
import sys
from pathlib import Path

from libretag.lt2 import Command, Keys, Mode, Op, Report, command_tag, epoch, open_command, report_tag
from nostrmesh import secp

VEC = json.loads((Path(__file__).parent / "vectors" / "lt2.json").read_text())
_spec = importlib.util.spec_from_file_location("checkprobe", Path(__file__).parents[1] / "tools" / "checkprobe.py")
cp = importlib.util.module_from_spec(_spec)
sys.modules["checkprobe"] = cp
_spec.loader.exec_module(cp)

KEYS = Keys(bytes.fromhex(VEC["k0"]))


def _quiet(next_in):
    return Report(quiet=True, checks=True, next_in_s=next_in)


def test_plan_matches_checks_vectors():
    t0 = 1_800_000_000.0
    for v in VEC["checks"]:
        ce = KEYS.cmd(epoch(v["n"]))
        plan = cp.check_plan(ce, v["n"], _quiet(v["heartbeat_s"]), t0, v["base_s"], every=1, max_checks=64)  # as the vectors
        assert [s.offset_s for s in plan] == v["before_heartbeat"]
        assert [s.offset_s for s in plan[:6]] == v["offsets"][:len(plan[:6])]
        assert [s.guard_ms for s in plan[:6]] == v["guard_ms"][:len(plan[:6])]
        assert all(s.at == int(round(t0 + s.offset_s)) + 3 for s in plan)
        assert [s.k for s in plan] == list(range(1, len(plan) + 1))


def test_plan_report_every_vectors():
    t0 = 1_800_000_000.0
    for v in VEC["report_every"]:
        ce = KEYS.cmd(epoch(v["n"]))
        # a normal IDLE report with report_every > 1: checks until its next_in (wake-up `every`)
        rep = Report(checks=True, mode=Mode.IDLE, next_in_s=v["next_in"])
        plan = cp.check_plan(ce, v["n"], rep, t0, v["base_s"], v["every"])
        if v["every"] <= 1:
            assert plan == []  # every wake-up reports: nothing to listen for in between
            continue
        assert [s.offset_s for s in plan] == v["offsets"][:v["every"] - 1]
        assert [s.report_wakeup for s in plan] == v["reports"][:v["every"] - 1]
        # quiet: the same keyed wake-ups; the report wake-ups among them are flagged, not probed
        qp = cp.check_plan(ce, v["n"], _quiet(10 ** 6), t0, v["base_s"], v["every"], max_checks=len(v["offsets"]))
        assert [s.offset_s for s in qp] == v["offsets"]
        assert [s.report_wakeup for s in qp] == v["reports"]


def test_no_plan_without_checks_or_when_reporting_every_wakeup():
    ce = KEYS.cmd(0)
    assert cp.check_plan(ce, 5, Report(checks=False, quiet=True, next_in_s=28800), 0, 3600, 3) == []
    assert cp.check_plan(ce, 5, Report(checks=True, mode=Mode.TRACK, next_in_s=300), 0, 3600, 3) == []
    assert cp.check_plan(ce, 5, Report(checks=True, next_in_s=3600), 0, 3600, 1) == []


def test_choose_slot_skips_report_wakeups_close_and_too_soon():
    v = VEC["report_every"][0]  # n 5, base 3600, every 3
    ce = KEYS.cmd(epoch(v["n"]))
    t0 = 1_800_000_000.0
    plan = cp.check_plan(ce, v["n"], _quiet(10 ** 6), t0, v["base_s"], v["every"], max_checks=7)
    s = cp.choose_slot(plan, t0, None, 3000, 60)
    assert s.k == 1
    s2 = cp.choose_slot(plan, t0, s.t_k, 3000, 60, after_k=1)
    assert s2.k == 2 and not s2.report_wakeup
    s3 = cp.choose_slot(plan, t0, s2.t_k, 3000, 60, after_k=2)
    assert s3.k == 4  # k 3 is a report wake-up
    assert cp.choose_slot(plan, plan[0].at - 30, None, 3000, 60).k == 2  # k 1 too close to publish
    assert cp.choose_slot(plan, t0, plan[0].t_k, 7200, 60, after_k=1).t_k - plan[0].t_k >= 7200


def test_next_seq():
    assert cp.next_seq(None, 200, 250, 210, None, None) == 210
    assert cp.next_seq(210, 200, 250, 210, None, None) == 211
    assert cp.next_seq(250, 200, 250, 210, None, None) == 200
    assert cp.next_seq(210, 200, 250, 210, 211, None) == 212
    assert cp.next_seq(210, 200, 250, 210, None, 211 & 0x0F) == 212
    seen = set()
    s = None
    for _ in range(200):
        s = cp.next_seq(s, 200, 250, 210, 220, 7)
        assert 200 <= s <= 250 and s != 220 and s & 0x0F != 7
        seen.add(s)
    assert len(seen) > 30


def test_build_probe_matches_check_commands_derivation():
    for v in VEC["check_commands"]:
        n, k = v["n"], v["k"]
        sk = secp.seckey_from_bytes(bytes(31) + b"\x07")
        ev, y, via = cp.build_probe(KEYS, n, k, at=1_800_000_123, seq=213, created_at=1_800_000_000, sk=sk)
        assert y == v["tag"] == command_tag(KEYS.cmd(epoch(n)), n, k).hex()
        assert via == report_tag(KEYS.view(epoch(n)), n, 0).hex()
        tags = {t[0]: t[1] for t in ev.tags}
        assert tags == {"y": y, "route": "near", "expiration": str(1_800_000_123 + cp.ENTRY_TTL_S),
                        "at": "1800000123", "via": via}
        assert ev.kind == 4244 and ev.verify()
        cmd = open_command(KEYS.cmd(epoch(n)), n, base64.b64decode(ev.content), k)
        assert cmd.encode() == Command.ble_scan(213, 8, False, 1).encode() and cmd.op == Op.BLE_SCAN
        assert open_command(KEYS.cmd(epoch(n)), n, base64.b64decode(ev.content), k + 1) is None
        de = cp.build_deletion(ev, sk, 1_800_000_050)
        assert de.kind == 5 and de.pubkey == ev.pubkey and ["e", ev.id] in de.tags and de.verify()


def test_expiration_capped():
    sk = secp.seckey_from_bytes(bytes(31) + b"\x09")
    ev, _, _ = cp.build_probe(KEYS, 5, 1, at=1_800_000_000 + 50 * 3600, seq=210, created_at=1_800_000_000, sk=sk)
    exp = int(next(t[1] for t in ev.tags if t[0] == "expiration"))
    assert exp - 1_800_000_000 <= 47 * 3600


def test_ack_matches():
    assert cp.ack_matches(Report(cmd_ack=True, cmd_seq=213 & 0x0F), 213)
    assert not cp.ack_matches(Report(cmd_ack=False, cmd_seq=213 & 0x0F), 213)
    assert not cp.ack_matches(Report(cmd_ack=True, cmd_seq=4), 213)


def test_sub_tags_cover_next_reports_ble_and_epoch_skip():
    tags = cp.sub_tags(KEYS, 70)
    assert report_tag(KEYS.view(1), 71, 0).hex() in tags
    assert report_tag(KEYS.view(1), 71, 6).hex() in tags
    assert report_tag(KEYS.view(2), 128, 0).hex() in tags
    assert len(tags) == len(set(tags)) <= 64


def test_parse_bridge_log_and_summary():
    lines = ["2026-11-16 14:07:43,377 INFO nostrmesh.lt2: LT2 command for a1929e51 sent direct via 1 hops",
             "2026-11-17 01:02:03,500 INFO nostrmesh.lt2: LT2 command for 0badf00d sent zero-hop (timed)"]
    t, how = cp.parse_bridge_log(lines, "0badf00d")
    assert how == "zero-hop (timed)" and abs(t % 1 - 0.5) < 1e-6
    assert cp.parse_bridge_log(lines, "deadbeef") is None
    probes = [
        {"n": 5, "k": 1, "seq": 210, "t_k": 1000.0, "status": "hit", "ack_recv": 1012.0, "bridge_sent": 1003.0,
         "claims": [{"created_at": 998}], "ble": [{"recv": 1030.0}]},
        {"n": 6, "k": 1, "seq": 211, "t_k": 5000.0, "status": "miss", "why": "no claim, not sent"},
        {"n": 6, "k": 2, "seq": 212, "t_k": 9000.0, "status": "superseded"},
    ]
    s = cp.summarize(probes)
    assert (s["probes"], s["hits"], s["misses"], s["superseded"]) == (2, 1, 1, 1)
    assert s["ack_recv_minus_tk_s"]["median"] == 12.0 and s["bridge_send_minus_tk_s"]["max"] == 3.0
    text = cp.summary_text_sk(s, 0, 3600)
    assert "Sondy: 2" in text and "n=6 k=1" in text


def test_summary_not_at_start_after_summary_time():
    import datetime as dt
    start = dt.datetime(2026, 11, 16, 22, 38).timestamp()
    assert cp.next_summary_time(start, "07:30") == dt.datetime(2026, 11, 17, 7, 30).timestamp()
    early = dt.datetime(2026, 11, 17, 3, 0).timestamp()
    assert cp.next_summary_time(early, "07:30") == dt.datetime(2026, 11, 17, 7, 30).timestamp()

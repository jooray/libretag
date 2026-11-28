"""Quiet mesh reference (libretag.quietmesh) against its shared vectors docs/vectors/quiet-mesh-v1.json."""
import base64
import json
from pathlib import Path

import pytest

from libretag import lt2
from libretag import quietmesh as q
from libretag.lt2 import Command, Keys, Report, channel_unwrap, epoch, open_report
from libretag.meshcore import Packet

VPATH = Path(__file__).resolve().parents[2] / "docs" / "vectors" / "quiet-mesh-v1.json"
V = json.loads(VPATH.read_text())
KEYS = Keys(bytes.fromhex(V["k0"]))
CE = KEYS.cmd(epoch(300))


def test_vectors_are_current():
    assert q.vectors() == V, "regenerate: uv run python -m libretag.quietmesh vectors > ../docs/vectors/quiet-mesh-v1.json"


def test_keys():
    assert KEYS.view(epoch(300)).hex() == V["view4"] and CE.hex() == V["cmd4"]


# ---------------------------------------------------------------- copies

def test_copies_open_and_packets():
    for c in V["copies"]:
        n = c["n"]
        ve = KEYS.view(epoch(n))
        assert set(c["tags"]) == {"0", "1", "9", "10", "11"}
        assert len(set(c["tags"].values())) == 5
        for cp in c["copies"]:
            j = cp["j"]
            p = bytes.fromhex(cp["p"])
            assert p[:4].hex() == c["tags"][str(j)] and len(p) == lt2.REPORT_P_LEN
            r = open_report(ve, n, j, p)
            assert r is not None and r.encode().hex() == c["pt"] and r.sync
            assert lt2.commit_matches(bytes.fromhex(c["pk"]), p)
            pkt = Packet.from_bytes(bytes.fromhex(cp["packet"]))
            assert channel_unwrap(pkt.payload) == p
            if j == 9:
                assert cp["header"] == 0x1A and pkt.path == [] and pkt.path_hash_size == 2
            elif j == 11:
                assert cp["header"] == 0x1A and [h.hex() for h in pkt.path] == cp["path"]
            elif cp["scope"] is None:
                assert cp["header"] == 0x19
            else:
                assert cp["header"] == 0x18
                code = int.from_bytes(pkt.transport_codes[:2], "little")
                assert code == q.transport_code(cp["scope"], pkt.payload_type, pkt.payload)
                assert pkt.transport_codes[2:] == b"\0\0"
            # other copies of the same report never open under this j
            for jj in (0, 1, 9, 10, 11):
                if jj != j:
                    assert open_report(ve, n, jj, p) is None


def test_report_window_takes_all_five_copies():
    w = lt2.ReportWindow(KEYS, last_n=299)
    for cp in V["copies"][0]["copies"]:
        assert w.table[bytes.fromhex(cp["p"])[:4]] == (300, cp["j"])


def test_arrival_hops():
    zh = q.direct(b"\0" * 19, [], 2)
    assert q.arrival(zh) == {"route": "direct", "for_us": True, "hops": None, "heard_direct": True, "path": []}
    on_way = q.direct(b"\0" * 19, [bytes.fromhex("a5e1")], 2)
    assert not q.arrival(on_way)["for_us"]
    fl = q.scoped_flood(b"\0" * 19, None, 2)
    assert q.arrival(fl)["hops"] == 0 and q.arrival(fl)["heard_direct"]
    fl2 = Packet(fl.route_type, fl.payload_type, fl.payload, path=[b"\xa1", b"\x0b"], path_hash_size=1)
    a = q.arrival(fl2)
    assert a["hops"] == 2 and not a["heard_direct"] and a["path"] == ["a1", "0b"]


# ---------------------------------------------------------------- ACK

def test_acks_valid():
    for a in V["acks"]["valid"]:
        pa = bytes.fromhex(a["pa"])
        assert len(pa) == 13 and pa[:4].hex() == a["tag"]
        assert q.ack_seal(CE, a["n"], a["k"], a["hold"], a["more"]) == pa
        got = q.ack_open(CE, a["n"], a["k"], pa)
        assert got == q.Ack(a["hold"], a["more"], False)
        assert channel_unwrap(bytes.fromhex(a["channel_payload"])) == pa
        raw = bytes.fromhex(a["packet"])
        assert len(raw) == 21 and raw[0] == 0x1A and raw[1] == 0x40
        st = bytes.fromhex(a["stealth"])
        assert len(st) == 19 and q.ack_open(CE, a["n"], a["k"], st, stealth=True) == got
        assert q.ack_open(CE, a["n"], a["k"], st) is None


def test_acks_invalid():
    for a in V["acks"]["invalid"]:
        assert q.ack_open(CE, a["n"], a["k"], bytes.fromhex(a["datagram"])) is None, a["why"]


def test_acks_routed():
    for a in V["acks_routed"]["valid"]:
        dg = bytes.fromhex(a["datagram"])
        got = q.ack_open(CE, a["n"], a["k"], dg)
        assert got is not None and got.routed and got.hash_size == a["hash_size"]
        assert [h.hex() for h in got.path] == a["path"]
        assert 15 <= len(dg) <= 20
        pkt = Packet.from_bytes(bytes.fromhex(a["packet"]))
        assert [h.hex() for h in pkt.path] == list(reversed(a["path"])) and pkt.route_type == lt2_direct()
        last = Packet.from_bytes(bytes.fromhex(a["last_hop_packet"]))
        assert last.path == [] and channel_unwrap(last.payload) == dg
        assert len(bytes.fromhex(a["last_hop_packet"])) == 37
        st = bytes.fromhex(a["stealth"])
        assert len(st) == 35 and q.ack_open(CE, a["n"], a["k"], st, stealth=True) == got
    for a in V["acks_routed"]["invalid"]:
        assert q.ack_open(CE, a["n"], a["k"], bytes.fromhex(a["datagram"])) is None, a["why"]


def lt2_direct():
    from libretag.meshcore import ROUTE_DIRECT
    return ROUTE_DIRECT


def test_routed_bit_is_sealed():
    near = q.ack_seal(CE, 300, 0, 4)
    routed = q.ack_seal(CE, 300, 0, 4, routed=True)
    assert near[:4] == routed[:4] and near != routed
    # flipping the bit without the key breaks the mac
    forged = near[:4] + bytes([near[4] ^ q.ACK_INFO_ROUTED]) + near[5:]
    assert q.ack_open13(CE, 300, 0, forged) is None


def test_ack_entries():
    for e in V["ack_entries"]:
        raw = base64.b64decode(e["content"])
        assert len(raw) == (26 if e["form"] == "both" else 13)
        first = q.ack_open13(CE, e["n"], e["k"], raw[:13])
        assert first.routed == (e["form"] == "routed") and first.more == e["more"]
        if e["form"] == "both":
            assert q.ack_open13(CE, e["n"], e["k"], raw[13:]).routed


def test_hold():
    for h, period, want in V["hold"]:
        assert q.hold_s(h, period) == want
    assert q.ack_wait_ms() == 12000 and q.ack_wait_ms(2) == 15000


# ---------------------------------------------------------------- commands: second data type, slots

def test_retry_data_type():
    for r in V["retry"]:
        pc = bytes.fromhex(r["pc"])
        a, b = bytes.fromhex(r["payload_ff4c"]), bytes.fromhex(r["payload_ff6c"])
        assert a != b
        assert q.command_from_channel(a) == (pc, 0xFF4C)
        assert q.command_from_channel(b) == (pc, 0xFF6C)
        assert lt2.open_command(CE, r["n"], pc).encode().hex() == r["pt"]


def test_slots():
    for s in V["slots"]["keys"]:
        ks = q.slot_key(KEYS.cmd(epoch(s["n_a"])), s["n_a"])
        assert ks.hex() == s["ks"]
        offs = q.slot_offsets(ks, 3600, 6)
        assert offs == s["offsets"]
        assert all(3600 * 0.9 <= b - a <= 3600 * 1.1 for a, b in zip([0] + offs, offs))
        assert q.slot_offsets(ks, 900, 4) == s["offsets_900"]
        assert s["send_at"] == [1763500000 + o + 1 for o in offs[:3]]
    ks = q.slot_key(CE, 300)
    for c in V["slots"]["commands"]:
        pc = bytes.fromhex(c["pc"])
        assert pc[:4].hex() == c["tag"] and len(pc) == 29
        assert q.open_slot_command(ks, c["k"], pc).encode().hex() == c["pt"]
        assert q.open_slot_command(ks, c["k"] + 1, pc) is None
        assert lt2.open_command(CE, 300, pc, c["k"]) is None  # the 0.7 check derivation is gone
        for p in (c["payload"], c["payload_retry"]):
            assert q.command_from_channel(bytes.fromhex(p))[0] == pc
    for w in V["slots"]["windows"]:
        assert q.slot_window_ms(w["offset_s"], w["window_s"], w["early_s"]) == (w["open_ms"], w["close_ms"])
    for a in V["slots"]["acks_at_slot"]:
        ce = KEYS.cmd(epoch(a["n"]))
        assert q.ack_open(ce, a["n"], a["k"], bytes.fromhex(a["pa"])) is not None


def test_slot_command_k0_refused():
    with pytest.raises(ValueError):
        q.seal_slot_command(q.slot_key(CE, 300), 0, Command.buzz(1, 1))


def test_hello_plus_flood_window_covers_both_senders():
    """A sender timed from the hello and a fallback sender timed from the flood both land in the window."""
    early = 12
    lo, hi = q.slot_window_ms(3600, 30, early)          # relative to t_k of the flood-based grid
    hello_send = -early * 1000 + 1000                    # t_k(hello) + 1 s
    flood_fallback = 1000 + 12_000 + 300                 # first send + 12 s + airtime
    assert lo <= hello_send <= hi and lo <= flood_fallback <= hi


def test_tag_schedule_vectors():
    for sc in V["tag_schedule"]:
        st = q.TagSchedule()
        for step in sc["steps"]:
            ev = step["event"]
            if ev[0] == "sync":
                assert st.sync_for(ev[1]) == step["sync"], sc["name"]
                continue
            {"flood": lambda: st.on_flood_sent(ev[1], ev[2], ev[3] if len(ev) > 3 else None),
             "ack": lambda: st.on_window_ack(ev[1], ev[2]),
             "prev_slot": st.on_prev_slot, "reboot": st.on_reboot}[ev[0]]()
            a = None if st.anchor is None else [st.anchor.n, st.anchor.t0, st.anchor.early_s]
            assert a == step["anchor"] and st.prev_slots_left == step["prev_slots_left"], sc["name"]


def test_app_anchor_vectors():
    for sc in V["app_anchor"]:
        st = q.AppAnchor()
        for step in sc["steps"]:
            ev = step["event"]
            if ev[0] == "confirmed":
                st.on_command_confirmed(ev[1])
            else:
                st.on_report(*ev[1:])
            assert (None if st.a is None else [st.a.n, st.a.t0]) == step["a"], sc["name"]
            assert [[c.n, c.t0] for c in st.cands] == step["cands"] and st.known == step["known"], sc["name"]
            assert len(st.grids()) <= 3


def test_pair_fallback():
    assert q.pair_fallback([100, 200], False) == [False, True]
    assert q.pair_fallback([200, 100, 150], False) == [True, False, False]
    assert q.pair_fallback([100, 200], True) == [True, True]


# ---------------------------------------------------------------- areas, ladder, transport, plan

def test_areas():
    rows = [q.Area(*r) for r in V["areas"]["rows"]]
    for c in V["areas"]["cases"]:
        a = q.area_at(c["lat"], c["lon"], rows, c["home_network"])
        assert (a.scope if a else None) == c["area"], c["name"]
        assert q.levels(c["area"], V["areas"]["country_scope"][c["home_network"]], c["same_radio_border"]) == c["levels"]
        assert c["levels"][-1] is None


def test_ladder():
    lad = q.Ladder()
    for s in V["areas"]["ladder"]:
        ev = s["event"]
        if ev[0] == "flood":
            wide, lad = q.ladder_after_flood(lad, V["areas"]["ladder_levels"], ev[1], ev[2], ev[3])
            assert wide == s["wide_lvl"]
        else:
            lad = q.ladder_reset(lad, ev[1], ack=ev[0] == "ack")
        assert lad.lvl == s["lvl"] and lad.widened_at == s["widened_at"]
    assert q.idle_unscoped_ok(None, 0) and not q.idle_unscoped_ok(0, 3600) and q.idle_unscoped_ok(0, 86400)


def test_transport_codes():
    for t in V["transport"]:
        assert q.scope_key(t["scope"]).hex() == t["key"]
        assert q.transport_code(t["scope"], t["payload_type"], bytes.fromhex(t["payload"])) == t["code"]
        assert 1 <= t["code"] <= 0xFFFE
    assert V["transport"][0]["key"] == V["transport"][2]["key"]  # "sk" and "#sk" are the same region


def test_plan_vectors():
    for c in V["plan"]:
        assert q.plan(c["kind"], c["heard"], c["route"], **c["args"]) == c["plan"], c
        if c["kind"] == "ack":
            assert c["plan"]["send"] != "flood"   # never a flood for an ACK


def test_plan_path_limit():
    heard = {"route": "tflood", "path": ["a5e1", "b7d2"]}
    assert q.plan("cmd", heard, "flood", max_hops=1)["send"] == "flood"
    assert q.plan("cmd", heard, "flood", max_hops=2)["send"] == "path"

"""Base station, store, simulator and MeshCore packet helpers on LT2."""
import hashlib
import hmac

import pytest

from libretag import lt2, store as store_mod
from libretag.base import FRESH_S, BaseStation
from libretag.lt2 import Command, Keys, Mode, Report
from libretag.meshcore import (PAYLOAD_GRP_DATA, ROUTE_DIRECT, ROUTE_FLOOD, ROUTE_TRANSPORT_FLOOD, Packet,
                               lora_airtime_s)
from libretag.proto import command_packet, command_payload, report_packet, report_payload
from libretag.sim import TrackerState
from libretag.store import Store
from libretag.transport import KissDecoder, kiss_encode

K0 = bytes(range(32))
KEYS = Keys(K0)
FIX = {"time": 1_763_200_000, "lat": 48.7000, "lon": 21.2500, "hacc": 4}


class Radio:
    def __init__(self):
        self.out = []

    def send(self, raw):
        self.out.append(raw)


class Clock:
    def __init__(self, t=1_763_200_000.0):
        self.t = t

    def __call__(self):
        return self.t


def setup(tmp_path=None, hops=0):
    st = Store(tmp_path / "db.sqlite" if tmp_path else ":memory:")
    st.add_tracker("car", K0)
    radio, clock = Radio(), Clock()
    base = BaseStation(st, radio, clock=clock)
    tr = TrackerState(k0=K0)
    path = [bytes([0x10 + i]) for i in range(hops)]

    def report(fix=FIX, j=0, **kw):
        pkt = tr.make_report(fix, 80, clock.t, j=j, **kw)
        pkt.path = list(path)
        return pkt.to_bytes()

    def deliver(raw):
        """What the tracker hears of a command packet: repeaters consume a direct path."""
        p = Packet.from_bytes(raw)
        if p.route_type == ROUTE_DIRECT:
            assert p.path == list(reversed(path))
            p.path = []
        return tr.try_command(p.to_bytes(), clock.t)

    return st, radio, clock, base, tr, report, deliver


def test_report_packet_shapes():
    r = Report(has_fix=True, fix_time=1, lat=48.1, lon=17.1)
    pkt = report_packet(KEYS, 3, 0, r)
    raw = pkt.to_bytes()
    assert raw[0] == 0x19 and len(pkt.payload) == 51 and len(raw) == 53
    assert report_payload(pkt) == lt2.seal_report_keys(KEYS, 3, 0, r)
    st = report_packet(KEYS, 3, 0, r, stealth=True)
    assert len(st.payload) == 51 and report_payload(st) == lt2.seal_report_keys(KEYS, 3, 0, r, stealth=True)
    scoped = report_packet(KEYS, 3, 0, r, transport_codes=b"\x01\x02\x00\x00", path_hash_size=2)
    q = Packet.from_bytes(scoped.to_bytes())
    assert q.route_type == ROUTE_TRANSPORT_FLOOD and q.path_hash_size == 2 and report_payload(q)


def test_window_recognises_channel_and_stealth(tmp_path):
    st, radio, clock, base, tr, report, _ = setup(tmp_path)
    got = base.handle_raw(report())
    assert (got.n, got.j, got.report.has_fix) == (0, 0, True)
    got = base.handle_raw(tr.make_report(None, 70, clock.t, stealth=True).to_bytes())
    assert got.n == 1 and not got.report.has_fix and abs(got.report.lat - 48.7000) < 1e-4  # last fix travels
    assert base.handle_raw(Packet(ROUTE_FLOOD, PAYLOAD_GRP_DATA, bytes(51)).to_bytes()) is None
    t = st.get_tracker("car")
    assert t.last_n == 1 and t.seen == {0, 1}
    # a restart reloads the window, seen counters stay closed
    base2 = BaseStation(Store(tmp_path / "db.sqlite"), radio, clock=clock)
    assert base2.windows["car"].last_n == 1 and base2.windows["car"].seen == {0, 1}


def test_border_copy_merges_and_late_report_slots_into_history():
    st, radio, clock, base, tr, report, _ = setup()
    a = report(j=0)
    tr.n -= 1
    b = report(j=1)  # the same n on the neighbouring network
    assert base.handle_raw(a).n == 0
    assert base.handle_raw(b) is None  # seen: merged by n
    tr.n = 10
    late_n = 5
    newer = report()
    tr.n = late_n
    late = report()
    assert base.handle_raw(newer).n == 10
    got = base.handle_raw(late)
    assert got.n == 5 and got.late
    assert [row[0] for row in st.positions("car")] == [10, 5, 0]
    assert st.get_tracker("car").last_n == 10


def test_command_zero_hop_then_ack_only_from_later_report():
    st, radio, clock, base, tr, report, deliver = setup(hops=0)
    st.queue_command("car", lambda s: Command.set_mode(s, Mode.TRACK, 60, 10))
    base.handle_raw(report())
    assert len(radio.out) == 1
    pkt = Packet.from_bytes(radio.out[0])
    assert pkt.route_type == ROUTE_DIRECT and pkt.path == []  # zero-hop
    assert len(pkt.payload) == 35 and len(command_payload(pkt)) == 29
    assert deliver(radio.out[0]).seq == 1 and tr.mode == Mode.TRACK
    radio.out.clear()
    base.handle_raw(report())  # carries CMD_ACK + seq 1
    assert radio.out == [] and st.pending_command("car") is None


def test_command_direct_along_reversed_path():
    st, radio, clock, base, tr, report, deliver = setup(hops=3)
    st.queue_command("car", lambda s: Command.buzz(s, 5))
    base.handle_raw(report())
    pkt = Packet.from_bytes(radio.out[0])
    assert pkt.route_type == ROUTE_DIRECT and pkt.path == [b"\x12", b"\x11", b"\x10"]
    assert deliver(radio.out[0]).op == lt2.Op.BUZZ and tr.buzz_s == 5


def test_lost_may_flood():
    st, radio, clock, base, tr, report, _ = setup(hops=2)
    st.queue_command("car", lambda s: Command.set_mode(s, Mode.LOST, 300, 600))
    base.handle_raw(report())
    pkt = Packet.from_bytes(radio.out[0])
    assert pkt.is_flood and pkt.path == []
    assert tr.try_command(radio.out[0], clock.t).op == lt2.Op.SET_MODE and tr.mode == Mode.LOST


def test_ack_needs_cmd_ack_flag_matching_seq_and_later_n():
    st = Store(":memory:")
    st.add_tracker("x", K0)
    st.queue_command("x", lambda s: Command.buzz(s, 1))
    p = st.pending_command("x")
    st.mark_sent(p.id, 7)
    assert st.ack_commands("x", 7, Report(cmd_ack=True, cmd_seq=1)) == 0       # the report it answered
    assert st.ack_commands("x", 8, Report(cmd_ack=False, cmd_seq=1)) == 0      # no CMD_ACK flag
    assert st.ack_commands("x", 8, Report(cmd_ack=True, cmd_seq=2)) == 0       # other seq
    assert st.ack_commands("x", 8, Report(cmd_ack=True, cmd_seq=1)) == 1
    assert st.pending_command("x") is None


def test_unacked_command_is_resent_with_new_seal_and_given_up():
    st, radio, clock, base, tr, report, _ = setup()
    st.queue_command("car", lambda s: Command.buzz(s, 2))
    st.queue_command("car", lambda s: Command.set_mode(s, Mode.IDLE))
    for _ in range(store_mod.MAX_SENDS):
        base.handle_raw(report())  # the tracker never hears the command
    payloads = {Packet.from_bytes(r).payload for r in radio.out}
    assert len(radio.out) == store_mod.MAX_SENDS and len(payloads) == store_mod.MAX_SENDS
    radio.out.clear()
    base.handle_raw(report())  # first one given up, the next goes out
    assert len(radio.out) == 1
    pc = command_payload(Packet.from_bytes(radio.out[0]))
    n = tr.n - 1
    assert lt2.open_command(KEYS.cmd(lt2.epoch(n)), n, pc).seq == 2
    log = st.command_log("car")
    assert log[1][6] is not None and log[1][3] == store_mod.MAX_SENDS  # seq 1 failed after MAX_SENDS


def test_command_only_right_after_a_fresh_new_report():
    st, radio, clock, base, tr, report, _ = setup()
    base.handle_raw(report())
    tr.n = 10
    newer = report()
    tr.n = 5
    late = report()
    tr.n = 11
    stale = report()
    base.handle_raw(newer)
    st.queue_command("car", lambda s: Command.buzz(s, 1))
    base.handle_raw(late)  # a late report does not open the RX window
    assert radio.out == []
    base.handle_raw(stale, rx_time=clock.t - FRESH_S - 1)  # heard too long ago
    assert radio.out == []
    tr.n = 12
    base.handle_raw(report())
    assert len(radio.out) == 1


def test_resent_seq_is_only_acked_again():
    st, radio, clock, base, tr, report, deliver = setup()
    st.queue_command("car", lambda s: Command.buzz(s, 3))
    base.handle_raw(report())
    deliver(radio.out[0])
    tr.pending_ack = False  # the acking report gets lost
    base.handle_raw(report())
    assert len(radio.out) == 2
    deliver(radio.out[1])
    assert tr.log == ["applied BUZZ seq=1"] and tr.pending_ack


def test_store_refuses_lt1_database(tmp_path):
    import sqlite3

    db = sqlite3.connect(tmp_path / "old.db")
    db.execute("CREATE TABLE trackers (name TEXT PRIMARY KEY, key BLOB NOT NULL)")
    db.commit()
    with pytest.raises(RuntimeError, match="v1"):
        Store(tmp_path / "old.db")
    with pytest.raises(ValueError):
        Store(":memory:").add_tracker("short", b"\x01" * 16)


def test_cli_pair_takes_k0(tmp_path, capsys):
    from libretag.cli import main

    db = str(tmp_path / "db.sqlite")
    main(["--db", db, "pair", "car", K0.hex()])
    assert KEYS.id.hex() in capsys.readouterr().out
    main(["--db", db, "cmd", "car", "lost"])
    main(["--db", db, "list"])
    out = capsys.readouterr().out
    assert "cmd seq=1 SET_MODE queued" in out
    with pytest.raises(SystemExit):
        main(["--db", db, "pair", "bad", "abcd"])


def test_packet_parse_roundtrip_with_transport_codes_and_2byte_hashes():
    p = Packet(0x00, 0x06, b"x" * 35, path=[b"ab", b"cd"], path_hash_size=2, transport_codes=b"\x01\x02\x03\x04")
    assert Packet.from_bytes(p.to_bytes()) == p


def test_command_packet_copies_scope_and_hash_size():
    rpt = Packet(ROUTE_TRANSPORT_FLOOD, PAYLOAD_GRP_DATA, bytes(51), path=[b"ab", b"cd"], path_hash_size=2,
                 transport_codes=b"\x01\x02\x03\x04")
    c = command_packet(bytes(29), rpt)
    assert c.path == [b"cd", b"ab"] and c.path_hash_size == 2 and c.transport_codes == b"\x01\x02\x03\x04"
    assert Packet.from_bytes(c.to_bytes()) == c
    assert command_payload(c) is None  # still travelling


def test_kiss_escaping():
    data = bytes([0xC0, 0xDB, 0x01, 0xC0])
    frames = KissDecoder().feed(b"\x00junk" + kiss_encode(data) + kiss_encode(b"abc"))
    assert frames == [(0, data), (0, b"abc")]


def test_airtime_sane():
    t = lora_airtime_s(53, sf=11, bw_hz=250_000, cr=5)
    assert 0.3 < t < 0.9


def test_scoped_packet_from_firmware():
    """lt_packet_set_route (firmware/lt_core/test/region_test.c): region "sk", 2-byte path hashes."""
    payload = bytes(range(37))
    key = hashlib.sha256(b"#sk").digest()[:16]
    code = hmac.new(key, bytes([PAYLOAD_GRP_DATA]) + payload, hashlib.sha256).digest()[:2]
    raw = bytes([0x18]) + code + b"\x00\x00" + bytes([0x40]) + payload
    assert code == bytes.fromhex("5842")
    pkt = Packet.from_bytes(raw)
    assert pkt.route_type == ROUTE_TRANSPORT_FLOOD and pkt.payload_type == PAYLOAD_GRP_DATA
    assert pkt.path_hash_size == 2 and pkt.path == [] and pkt.transport_codes == code + b"\x00\x00"
    assert pkt.to_bytes() == raw


def test_traccar_url_uses_fix_time():
    from libretag.traccar import osmand_url

    u = osmand_url("http://t:5055", "car", Report(has_fix=True, fix_time=123, lat=1.5, lon=2.5, battery_pct=50))
    assert "timestamp=123" in u and "batt=50" in u and "lat=1.5000000" in u

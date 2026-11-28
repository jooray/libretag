"""Field-test log decoding (firmware 0.9.8 `fieldlog dump`). Synthetic records only."""
import json
import struct

from libretag import fieldlog


def hdr(typ, seq, up_ms, unix=0, phase=0, mode=0):
    return struct.pack("<BBBBIII", 0xF1, typ, phase, mode, seq, up_ms, unix)


def tx(seq, up_ms, unix=0):
    body = struct.pack("<IBBBBBBHHBBIHH", 1234, 1, 0, (6 << 2) | 1, 0x40, 60, 2 | 16, 300, 4100, 7, 9,
                       0xDEADBEEF, 3, 0) + b"sk-ba\0\0\0"
    return hdr(1, seq, up_ms, unix, phase=2) + body


def rx_own(seq, up_ms):
    body = bytes([(6 << 2) | 1, 0x41, 64, 1]) + struct.pack("<bBHI", -26, 101, 0, 0xDEADBEEF)
    body += bytes.fromhex("a1b2") + bytes(10) + bytes(8)
    return hdr(2, seq, up_ms, phase=3) + body


def gps_fix(seq, up_ms, unix):
    body = struct.pack("<BBBBIiihHIH", 1, 8, 0, 0, 25000, 487000000, 212500000, 210, 0, unix, 0) + bytes(6)
    return hdr(4, seq, up_ms, unix, phase=1) + body


def test_records_decode_and_annotate():
    recs = [gps_fix(0, 10_000, 1_800_000_000), tx(1, 11_000), rx_own(2, 12_500)]
    assert all(len(r) == fieldlog.REC for r in recs)
    lines = ["FIELDLOG 48"] + ["FL " + r.hex() for r in recs] + ["END 3"]
    out = [json.loads(l) for l in fieldlog.to_jsonl(lines)]
    g, t, r = out
    assert g["gps"] == "fix" and abs(g["lat"] - 48.70) < 1e-6 and g["t_est"] is False
    assert t["kind"] == "report" and t["route"] == "flood" and t["region"] == "sk-ba"
    assert t["hash_size"] == 2 and t["path_len"] == 0 and t["airtime_ms"] == 300 and t["fix_state"] == "this_wake"
    assert t["t"] == "2027-01-15T08:00:01.000Z" and t["t_est"] is True
    assert t["pos"]["lat"] == g["lat"] and t["pos"]["age_s"] == 1.0
    assert r["match"] == "own" and r["path"] == ["a1b2"] and r["last_hop"] == "a1b2"
    assert r["snr"] == -6.5 and r["rssi"] == -101 and r["hash"] == t["hash"]


def test_cut_path_and_new_boot():
    body = bytes([(6 << 2) | 1, 0x0A, 80, 0x80]) + struct.pack("<bBHI", 0, 90, 0, 1) + bytes(range(12)) + bytes(8)
    a = hdr(2, 5, 50_000) + body
    b = hdr(5, 6, 1_000) + bytes([1]) + bytes(31)  # boot event, uptime went back
    out = [json.loads(l) for l in fieldlog.to_jsonl(["FL " + a.hex(), "FL " + b.hex()])]
    assert out[0]["path_len"] == 10 and out[0]["path"][-2:] == ["..", "0b"]
    assert out[1]["evt"] == "boot"


# ---------------------------------------------------------------- 0.10.0: wake-up summaries and slot windows

def sum1(seq, up_ms, n):
    copies = struct.pack("<BH", 9, 0) + struct.pack("<BH", 0, 12040) + struct.pack("<BH", 10, 20100) + bytes(3)
    body = struct.pack("<IBBBBHBB", n, 1, 1 | (0 << 4), 0, 1 | 16, 34, 6, 3) + copies
    body += struct.pack("<IHBB", 1_800_000_000, 550, 2, 7)
    return hdr(6, seq, up_ms, phase=3) + body


def sum2(seq, up_ms, n, cmd=True):
    body = struct.pack("<IH", n, 8100) + bytes.fromhex("a5e1b7")
    body += struct.pack("<BHbBBBHHIBBHHBB", 0xFF, 0xFFFF, 0, 0x04 if cmd else 0, 0x5A, (1 | 4 | 8) if cmd else 0, 2,
                        4300, n - 1, 2, 125, 60000, 2, 2 | 8, 0)
    return hdr(7, seq, up_ms, phase=3) + body


def sum3(seq, up_ms, n):
    body = struct.pack("<IB", n, 2 | (2 << 4)) + bytes.fromhex("a5e1b7d20000")
    body += struct.pack("<BIHHBBB", 81, 3600, 3920, 412, 6, 0, 1) + b"\0" + struct.pack("<I", 86400) + bytes(4)
    return hdr(8, seq, up_ms, phase=3) + body


def chk(seq, up_ms):
    body = struct.pack("<IHBBIIiHB", 77, 2, 2 | 8, 1, 75, 31000, 2400, 3900, 1) + bytes(9)
    return hdr(9, seq, up_ms, phase=5) + body


def test_wake_summary_merged():
    recs = [sum1(10, 100_000, 77), sum2(11, 100_001, 77), sum3(12, 100_002, 77), chk(13, 3_700_000)]
    assert all(len(r) == fieldlog.REC for r in recs)
    out = [json.loads(l) for l in fieldlog.to_jsonl(["FL " + r.hex() for r in recs])]
    assert [o["type"] for o in out] == ["wake", "chk"]
    w, c = out
    assert w["n"] == 77 and w["seq"] == 10 and w["up_s"] == 100.0
    assert w["why"] == "moved" and w["link_before"] == "near" and w["link_after"] == "far" and w["lvl"] == 0
    assert w["copies"] == "9@0,0@12040,10@20100" and w["n_copies"] == 3
    assert w["fix"] and w["sync"] and not w["acked"] and w["ack_form"] is None
    assert w["search_s"] == 34 and w["hacc_m"] == 6 and w["moved_m"] == 550 and w["fix_utc"].startswith("2027-01-15")
    assert w["floods_24h"] == 2 and w["idle_budget_left"] == 7
    assert w["rep_ms"] == 8100 and w["rep_hash"] == "a5e1b7" and w["ack_k"] is None and w["ack_ms"] is None
    assert w["cmd_op"] == 4 and w["cmd_seq"] == 0x5A and w["cmd_route"] == "direct" and w["cmd_type"] == "6C"
    assert w["cmd_k"] == 2 and w["cmd_ms"] == 4300 and w["cmd_dup"] is False
    assert w["anchor_n"] == 76 and w["prev_slots_left"] == 2 and w["listen_ms"] == 60000 and w["slot_k"] == 2
    assert w["events"] == ["cmd", "mode"] and w["stall"] == 0
    assert w["path"] == ["a5e1", "b7d2"] and w["path_hs"] == 2 and w["path_age_s"] == 3600
    assert w["batt_pct"] == 81 and w["batt_mv"] == 3920 and w["energy_mah"] == 412
    assert w["boot_cause"] == "watchdog" and w["txfail"] == 1 and w["next_in_s"] == 86400
    assert c["n"] == 77 and c["slot_k"] == 2 and c["grid"] == "prev" and c["cmd"] and not c["acked"]
    assert c["anchor_n"] == 75 and c["rx_ms"] == 31000 and c["rx_open_ms"] == 2400 and c["link"] == "near"


def test_wake_summary_acked_no_command_and_cut():
    s1 = bytearray(sum1(1, 5_000, 9))
    s1[23] = 1 | 2 | 4 | 8  # fix, ACKed, routed ACK, early sleep
    s2 = bytearray(sum2(2, 5_001, 9, cmd=False))
    s2[25] = 0
    s2[26:28] = struct.pack("<H", 3100)
    s2[28] = 0xF6  # SNR -2.5
    out = [json.loads(l) for l in fieldlog.to_jsonl(["FL " + bytes(s1).hex(), "FL " + bytes(s2).hex()])]
    assert len(out) == 1  # sum3 missing: the record keeps what it has
    w = out[0]
    assert w["acked"] and w["ack_form"] == "routed" and w["early_sleep"]
    assert w["ack_k"] == 0 and w["ack_ms"] == 3100 and w["ack_snr"] == -2.5 and "cmd_op" not in w
    assert "path" not in w


def test_tx_report_gets_x_from_the_view_key():
    """x of a TX report record from V_e(n) (PROTOCOL.md 2), lowercase; nothing without the key."""
    from libretag.lt2 import Keys, epoch, report_tag
    keys = Keys(bytes(range(32)))
    lines = ["FL " + tx(1, 11_000).hex()]
    plain = json.loads(fieldlog.to_jsonl(lines)[0])
    assert "x" not in plain and plain["n"] == 1234 and plain["j"] == 0
    t = json.loads(fieldlog.to_jsonl(lines, keys.view)[0])
    assert t["x"] == report_tag(keys.view(epoch(1234)), 1234, 0).hex() and t["x"] == t["x"].lower()
    assert "x" not in json.loads(fieldlog.to_jsonl(lines, lambda e: None)[0])

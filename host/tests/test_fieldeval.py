"""libretag fieldeval on synthetic logs (coordinates in the 48.70, 21.25 test area only)."""
import json

from libretag import cli, fieldeval

T = 1_795_700_000_000  # ms


def iso(ms):
    import datetime as dt
    return dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def write(path, recs):
    path.write_text("".join(json.dumps(r) + "\n" for r in recs))


def scenario(tmp_path, with_app=True):
    tag, bridge, app = [], [], []
    for i, n in enumerate(range(100, 106)):
        t = T + i * 180_000
        lat = 48.70 + i * 0.002  # about 220 m a step
        x = f"{n:08x}"
        tag.append({"seq": i, "type": "tx", "n": n, "j": 0, "kind": "report", "route": "flood",
                    "t": iso(t), "pos": {"lat": lat, "lon": 21.25, "age_s": 5}})
        if n == 104:
            continue  # lost: nobody heard it
        hops = i % 3
        bridge.append({"t": t + 400, "src": "bridge", "bridge": "b1", "ev": "rx", "kind": "report", "x": x,
                       "hops": hops, "route": "flood", "path": ["2a"] * hops, "snr": 5.0, "rx_time": (t + 400) / 1000})
        if n == 101:
            bridge.append({"t": t + 900, "src": "bridge", "bridge": "b1", "ev": "rx", "kind": "report", "x": x,
                           "hops": 2, "route": "flood"})  # a second copy
        eid = f"e{n}"
        bridge.append({"t": t + 3400, "src": "bridge", "bridge": "b1", "ev": "report_pub", "x": x, "id": eid,
                       "created_at": (t + 400) // 1000, "delay_s": 3.0})
        bridge.append({"t": t + 3600, "src": "bridge", "bridge": "b1", "ev": "publish", "id": eid, "kind": 4243,
                       "url": "wss://r1", "ok": True, "ms": 200})
        delay = 120_000 if n == 103 else 1500
        app.append({"t": t + 3400 + delay, "src": "app", "ev": "relay_event", "kind": 4243, "url": "wss://r1",
                    "id": eid, "y": [x], "created_at": (t + 400) // 1000})
        app.append({"t": t + 3500 + delay, "src": "app", "ev": "open", "tag": "tg", "via": "relay", "n": n, "j": 0,
                    "x": x, "id": eid, "created_at": (t + 400) // 1000})
        app.append({"t": t + 3500 + delay, "src": "app", "ev": "report", "tag": "tg", "n": n, "via": "relay",
                    "fix": True, "lat": lat, "lon": 21.25})
    app += [{"t": T + 10_000, "src": "app", "ev": "cmd_queued", "tag": "tg", "kind": "BUZZ", "seq": 7},
            {"t": T + 11_000, "src": "app", "ev": "cmd_sent", "tag": "tg", "seq": 7, "path": "mailbox", "id": "c7"},
            {"t": T + 400_000, "src": "app", "ev": "cmd_confirmed", "tag": "tg", "seq": 7}]
    bridge.append({"t": T + 190_000, "src": "bridge", "bridge": "b1", "ev": "cmd_tx", "id": "c7", "how": "flood", "ok": True})
    bridge.append({"t": T + 190_100, "src": "bridge", "bridge": "b1", "ev": "tx", "how": "flood", "ok": True, "len": 29})
    write(tmp_path / "tag.jsonl", tag)
    write(tmp_path / "bridge-20261126.jsonl", bridge)
    if with_app:
        (tmp_path / "phone").mkdir()
        write(tmp_path / "phone" / "app-20261126.jsonl", app)


def test_merge_and_summary(tmp_path):
    scenario(tmp_path)
    recs = fieldeval.read([tmp_path])
    copies = fieldeval.merge(recs)
    s = fieldeval.summarize(recs, copies)
    assert s["copies"] == 6 and s["copies_sent_by_tag"] == 6
    assert s["reports"] == 6 and s["reports_delivered"] == 5
    assert s["delivery_rate"] == round(5 / 6, 3) and s["heard_by_bridge_rate"] == round(5 / 6, 3)
    assert s["bridge_hops"] == {"0": 2, "1": 1, "2": 2}
    lat = s["latency_ms"]
    assert lat["mesh_ms"]["p50"] == 400 and lat["bridge_publish_ms"]["p50"] == 3000
    assert lat["relay_ok_ms"]["max"] == 200 and lat["relay_to_app_ms"]["max"] == 120_000
    assert s["relay_delay_over_60s"] == 0.2
    assert sum(v["tag"] for v in s["floods_per_hour"].values()) == 6
    assert sum(v["bridge"] for v in s["floods_per_hour"].values()) == 1
    cmd = s["commands"][0]
    assert cmd["confirmed"] and cmd["queued_to_confirmed_s"] == 390 and cmd["sends"] == {"mailbox": 1}
    assert cmd["bridge_tx"][0]["how"] == "flood"
    cov = s["coverage"]
    assert sum(b["copies"] for b in cov) == 6 and sum(b["delivered"] for b in cov) == 5
    assert all("lat" not in json.dumps(b) for b in cov)  # no coordinates in the summary
    rows = [fieldeval.timeline_row(c) for c in copies]
    r101 = next(r for r in rows if r["n"] == 101)
    assert r101["bridges"]["b1"]["dups"] == 1 and r101["x"] == f"{101:08x}"


def test_tag_and_bridge_matched_by_time_without_app(tmp_path):
    scenario(tmp_path, with_app=False)
    recs = fieldeval.read([tmp_path])
    s = fieldeval.summarize(recs, fieldeval.merge(recs))
    assert s["copies"] == 6 and s["heard_by_bridge_rate"] == round(5 / 6, 3)
    assert s["latency_ms"]["mesh_ms"]["count"] == 5


def test_cli(tmp_path, capsys):
    scenario(tmp_path)
    cli.main(["fieldeval", str(tmp_path), "--timeline", str(tmp_path / "out.jsonl")])
    out = capsys.readouterr().out
    assert "delivery rate" in out and "coverage" in out
    assert len((tmp_path / "out.jsonl").read_text().splitlines()) == 6


# ---------------------------------------------------------------- quiet mesh (0.10.0)

def test_quiet_mesh_section(tmp_path):
    """A hello ACKed through a bridge, a moved hello that floods, and a Beep at a slot: the tag's wake and
    chk records joined with the app's open/ack and the bridge's tx by n, x, xa and time."""
    t0 = T
    tag = [
        {"seq": 1, "type": "tx", "n": 200, "j": 9, "kind": "report", "route": "direct", "t": iso(t0)},
        {"seq": 2, "type": "wake", "n": 200, "why": "period", "copies": "9@0", "link_before": "near",
         "link_after": "near", "acked": True, "ack_form": "near", "ack_k": 0, "ack_ms": 3100, "lvl": None,
         "early_sleep": True, "t": iso(t0 + 9000), "up_ms": 9000, "unix": None},
        {"seq": 3, "type": "wake", "n": 201, "why": "moved", "copies": "9@0,0@12040,10@20100",
         "link_before": "near", "link_after": "far", "acked": False, "ack_form": None, "lvl": 0, "rep_ms": None,
         "t": iso(t0 + 3 * 3600_000 + 60_000), "up_ms": 1, "unix": None},
        # slot window: receiver on 34 s, opened 2 s before t_k; written at its close
        {"seq": 4, "type": "chk", "n": 201, "slot_k": 1, "rx_ms": 34_000, "rx_open_ms": 2000, "cmd": True,
         "acked": False, "t": iso(t0 + 4 * 3600_000 + 32_000), "up_ms": 2, "unix": None},
    ]
    t_k = t0 + 4 * 3600_000
    bridge = [
        {"t": t0 + 300, "src": "bridge", "bridge": "b1", "ev": "rx", "kind": "report", "x": "0000aa01",
         "route": "direct", "heard_direct": True},
        {"t": t0 + 3000, "src": "bridge", "bridge": "b1", "ev": "tx", "xa": "a1a2a3a4", "what": "ack_near",
         "how": "zero-hop", "ok": True},
        {"t": t_k + 1100, "src": "bridge", "bridge": "b1", "ev": "tx", "xc": "c1c2c3c4", "what": "cmd",
         "how": "zero-hop", "at": (t_k + 1000) // 1000, "ok": True},
        {"t": t_k + 13_100, "src": "bridge", "bridge": "b1", "ev": "fallback", "id": "e1", "state": "cancelled"},
        {"t": t_k + 13_100, "src": "bridge", "bridge": "b1", "ev": "path_use", "confirmed": True},
        {"t": t_k + 20_000, "src": "bridge", "bridge": "b1", "ev": "radio_down", "reason": "rx_silence"},
    ]
    app = [
        {"t": t0 + 2000, "src": "app", "ev": "open", "tag": "T1", "via": "relay", "n": 200, "j": 9, "x": "0000aa01"},
        {"t": t0 + 2100, "src": "app", "ev": "report", "tag": "T1", "n": 200, "j": 9, "x": "0000aa01", "sync": True},
        {"t": t0 + 2200, "src": "app", "ev": "ack", "tag": "T1", "n": 200, "xa": "a1a2a3a4", "form": "near",
         "timed": False, "path": "nostr"},
        {"t": t0 + 4000, "src": "app", "ev": "ack_note", "tag": "T1", "note_ms": 1800},
        {"t": t_k + 30_000, "src": "app", "ev": "miss_seen", "reason": "radio"},
    ]
    for name, recs in (("tag.jsonl", tag), ("bridge-x.jsonl", bridge), ("app-x.jsonl", app)):
        write(tmp_path / name, recs)
    recs = fieldeval.read([tmp_path])
    q = fieldeval.summarize(recs, fieldeval.merge(recs))["quiet_mesh"]
    tg = q["tag"]
    assert tg["wakes"] == 2 and tg["why"] == {"period": 1, "moved": 1} and tg["floods"] == 1
    assert tg["floods_by_why"] == {"moved": 1} and tg["copies_10"] == 1
    assert tg["link_changes"] == {"near>far": 1} and tg["acked"] == {"near": 1, "none": 1}
    assert tg["hello_to_ack_ms"]["p50"] == 3100 and tg["hello_ack_under_9s"] == 1.0 and tg["early_sleep"] == 1
    assert tg["flood_levels"] == {"0": {"floods": 1, "repeated": 0}}
    sl = q["slots"]
    assert sl["windows"] == 1 and sl["timed_sends"] == 1 and sl["outside_window"] == 0
    assert abs(sl["after_t_k_s"]["p50"] - 1.1) < 0.01 and sl["in_0_5_to_10s"] == 1.0
    assert q["acks"]["app_acks"] == {"nostr:near:live": 1} and q["acks"]["bridge_sent"] == {"ack_near": 1}
    assert q["acks"]["taken_by_tag"] == "1/1" and q["acks"]["ack_note_ms"]["p50"] == 1800
    b = q["bridges"]
    assert b["tx"] == {"ack_near:zero-hop:ok": 1, "cmd:zero-hop:ok": 1} and b["fallback"] == {"cancelled": 1}
    assert b["path_sends_confirmed"] == "1/1" and b["radio"] == {"radio_down:rx_silence": 1}
    assert q["app"]["miss_notes"] == {"radio": 1} and q["app"]["reports_with_sync"] == 1
    rows = [fieldeval.timeline_row(c) for c in fieldeval.merge(recs)]
    hello = next(r for r in rows if r["n"] == 200)
    assert hello["x"] == "0000aa01" and hello["why"] == "period" and hello["tag_acked"] == "near"
    assert hello["heard_by"] == ["b1"]
    assert "quiet mesh, tag:" in fieldeval.render(fieldeval.summarize(recs, fieldeval.merge(recs)))

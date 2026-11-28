"""Bridge field-test log (nostrmesh/fieldlog.py): rx, report_pub, mailbox, cmd_tx and tx lines."""
import json

from nostrmesh import fieldlog

from test_lt2bridge import command, heard, mailbox_event, make, report, run


def lines(d):
    out = []
    for f in sorted(d.glob("bridge-*.jsonl")):
        out += [json.loads(x) for x in f.read_text().splitlines()]
    return out


def test_field_log_rx_publish_mailbox_tx(tmp_path):
    fieldlog.configure(tmp_path, "test")
    try:
        p5 = report(5)

        async def go():
            br = make()
            await br.start()
            await br.on_relay_event(mailbox_event(command(5), [p5[:4]]).to_dict(), "wss://a")
            br.clock_ref["t"] += 1
            await br.on_datagram(heard(p5, 2))
            await br.on_datagram(heard(p5, 1))
            await br.drain()
            await br.stop()

        run(go())
    finally:
        fieldlog.configure(None)
    recs = lines(tmp_path)
    by = {}
    for r in recs:
        by.setdefault(r["ev"], []).append(r)
        assert r["src"] == "bridge" and r["bridge"] == "test" and isinstance(r["t"], int)
    rx = by["rx"]
    assert len(rx) == 2 and rx[0]["x"] == p5[:4].hex() and rx[0]["kind"] == "report"
    assert rx[0]["hops"] == 2 and rx[0]["path"] == ["20", "21"] and rx[0]["snr"] == 8.0
    assert "rx_lt2_report" in rx[0]["result"] and "lt_rx_duplicate" in rx[1]["result"]
    assert by["report_pub"][0]["x"] == p5[:4].hex() and by["report_pub"][0]["id"]
    assert by["mailbox"][0]["y"] == [p5[:4].hex()] and "lt_mailbox_added" in by["mailbox"][0]["result"]
    # heard over 2 repeaters with the path: the entry ("flood") goes along the reversed path
    tx = by["tx"][0]
    assert tx["how"] == "path" and tx["path"] == ["21", "20"] and tx["ok"] is True and tx["what"] == "cmd"
    assert tx["len"] == len(command(5)) and tx["id"] == by["mailbox"][0]["id"] and by["mailbox"][0]["kind"] == "cmd"
    assert by["lock"][0]["x"] == p5[:4].hex() and by["lock"][0]["keys"] == 1


def test_field_log_off_writes_nothing(tmp_path):
    fieldlog.configure(None)
    fieldlog.ev("rx", size=1)
    assert not list(tmp_path.iterdir())


def test_field_log_prunes_old_days(tmp_path):
    old = tmp_path / "bridge-20000101.jsonl"
    old.write_text("{}\n")
    fieldlog.configure(tmp_path, "x", keep_days=30)
    try:
        fieldlog.ev("start")
    finally:
        fieldlog.configure(None)
    assert not old.exists() and len(list(tmp_path.glob("bridge-*.jsonl"))) == 1

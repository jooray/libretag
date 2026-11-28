import json
from pathlib import Path

from libretag import lt2
from libretag.lt2 import Command, Keys, Mode, Report, ReportWindow

KEYS = Keys(bytes(range(32)))
VEC = json.loads((Path(__file__).parent / "vectors" / "lt2.json").read_text())


def test_vectors_are_current():
    assert lt2.vectors() == VEC


def test_report_roundtrip_and_siv():
    r = Report(has_fix=True, fix_time=1763200000, lat=48.700000, lon=21.250000, battery_pct=50,
               neighbours=[(b"\xa5\xe1", 11.0)], nb_total=1)
    ve = KEYS.view(0)
    p = lt2.seal_report_keys(KEYS, 7, 0, r)
    assert len(p) == 45 and p == lt2.seal_report_keys(KEYS, 7, 0, r)  # deterministic
    other = lt2.seal_report_keys(KEYS, 7, 0, Report(has_fix=False, battery_pct=50))
    assert other[7:-6] != p[7:-6]  # same (n, j), other content: other IV, no keystream reuse
    d = lt2.open_report(ve, 7, 0, p)
    assert d.has_fix and abs(d.lat - 48.700000) < 2e-5 and d.neighbours == [(b"\xa5\xe1", 11.0)]
    assert lt2.open_report(ve, 8, 0, p) is None
    assert lt2.open_report(KEYS.view(1), 7, 0, p) is None
    bad = bytearray(p); bad[10] ^= 1
    assert lt2.open_report(ve, 7, 0, bytes(bad)) is None
    st = lt2.seal_report_keys(KEYS, 7, 1, r, stealth=True)
    assert len(st) == 51 and lt2.open_report(ve, 7, 1, st).battery_pct == 50


def test_v2_layout_commitment_and_mac():
    r = Report(has_fix=True, fix_time=1763200000, lat=48.7, lon=21.25, battery_pct=50)
    ce = KEYS.cmd(0)
    pk = lt2.mailbox_pk(ce, 7)
    p = lt2.seal_report_keys(KEYS, 7, 0, r)
    assert p[:4] == lt2.report_tag(KEYS.view(0), 7, 0)
    assert lt2.commit_matches(pk, p)  # c = SHA-256("LT2-mbc" || pk || x || ct || mac)[0:3]
    assert not lt2.commit_matches(lt2.mailbox_pk(ce, 8), p)  # the next report's key does not fit
    # c is not under the mac: a changed c still opens, but no longer fits pk_n
    moved = bytearray(p); moved[5] ^= 0x40
    assert lt2.open_report(KEYS.view(0), 7, 0, bytes(moved)) is not None
    assert not lt2.commit_matches(pk, bytes(moved))
    # every byte of ct and mac is covered
    for i in list(range(7, 45)):
        bad = bytearray(p); bad[i] ^= 1
        assert lt2.open_report(KEYS.view(0), 7, 0, bytes(bad)) is None
    # sk_n comes from the command chain: a view key alone gives another key
    assert lt2.mailbox_sk(ce, 7) != lt2.mailbox_sk(KEYS.view(0), 7)
    from nostrmesh.secp import pubkey
    assert pubkey(lt2.mailbox_sk(ce, 7)) == pk


def test_history_records_keep_the_plain_seal():
    r = Report(has_fix=True, fix_time=1763200000, lat=48.7, lon=21.25, battery_pct=50)
    for j in (lt2.J_HIST, lt2.J_HIST_SILENT):
        p = lt2.seal_report_keys(KEYS, 9, j, r)
        assert len(p) == 45 and lt2.open_report(KEYS.view(0), 9, j, p).battery_pct == 50
        pt = lt2._open(lt2._report_kd(KEYS.view(0), 9, j), p)
        assert pt[0] == lt2.TYPE_REPORT  # type byte and the 8 B mac stay
    try:
        lt2.seal_report(KEYS.view(0), 9, 0, r)
        assert False, "a report on air needs pk_n"
    except ValueError:
        pass


def test_ratchet_is_one_way_chain():
    assert KEYS.view(3) == lt2.hmac_sha256(KEYS.view(2), b"LT2-next")
    assert KEYS.view(0) != KEYS.cmd(0)


def test_window_ahead_behind_and_seen():
    w = ReportWindow(KEYS, last_n=100)
    p = lt2.seal_report_keys(KEYS, 1500, 0, Report(battery_pct=1))
    n, j, r = w.open(p)
    assert (n, j, r.battery_pct) == (1500, 0, 1) and w.last_n == 1500
    assert w.open(p) is None  # seen
    late = lt2.seal_report_keys(KEYS, 1400, 1, Report(battery_pct=2))  # border copy, late
    assert w.open(late)[:2] == (1400, 1) and w.last_n == 1500
    too_old = lt2.seal_report_keys(KEYS, 1000, 0, Report())
    assert w.open(too_old) is None


def test_command_roundtrip():
    c = Command.set_mode(9, Mode.LOST, 600, 1440)
    p = lt2.seal_command(KEYS.cmd(lt2.epoch(70)), 70, c)
    assert len(p) == 29
    d = lt2.open_command(KEYS.cmd(lt2.epoch(70)), 70, p)
    assert d.op == lt2.Op.SET_MODE and d.seq == 9 and d.args[:5] == c.args[:5]
    assert lt2.open_command(KEYS.cmd(lt2.epoch(70)), 71, p) is None
    assert lt2.open_command(KEYS.view(lt2.epoch(70)), 70, p) is None  # view key cannot command


def test_channel_sizes():
    p = lt2.seal_report_keys(KEYS, 1, 0, Report())
    assert len(lt2.channel_wrap(p)) == 51  # 1 + 2 + 48, no padding
    assert lt2.channel_unwrap(lt2.channel_wrap(p)) == p
    c = lt2.seal_command(KEYS.cmd(0), 1, Command.buzz(1, 5))
    assert len(lt2.channel_wrap(c)) == 35


def test_new_report_fields_roundtrip():
    r = Report(has_fix=True, fix_time=1763300000, lat=48.7, lon=21.25, quiet=True, quiet_owner=True,
               next_in_s=8 * 3600, energy_mah=345, ble=lt2.BleSummary(17, 5, -61),
               neighbours=[(b"\xa5\xe1", 1.0)] * 5, nb_total=5)
    d = Report.decode(r.encode())
    assert d.quiet and d.quiet_owner and d.next_in_s == 8 * 3600 and d.energy_mah == 340
    assert d.ble == lt2.BleSummary(17, 5, -61) and len(d.neighbours) == 4  # 5th slot holds the summary
    old = Report.decode(bytes.fromhex(VEC["reports"][0]["pt"]))
    assert old.next_in_s is None and old.energy_mah is None and not old.quiet  # 0.5 firmware: bytes 31, 32 = 0


def test_next_in_steps():
    for sec, v, back in VEC["next_in"]:
        assert lt2.next_in_encode(sec) == v and lt2.next_in_decode(v) == back
    assert lt2.next_in_decode(212) == 86400  # the zone heartbeat is exact


def test_ble_packets_open_through_window():
    w = ReportWindow(KEYS, last_n=200)
    for case in VEC["ble"]:
        for pk in case["packets"]:
            hit = w.open_extra(bytes.fromhex(pk["p"]))
            assert hit is not None and hit[1] == pk["j"] and isinstance(hit[2], lt2.BlePacket)
            assert lt2.channel_unwrap(bytes.fromhex(pk["channel_payload"])) == bytes.fromhex(pk["p"])
            assert len(bytes.fromhex(pk["channel_payload"])) == 147
    names = [d.name for pk in VEC["ble"][0]["packets"] for d in lt2.BlePacket.decode(bytes.fromhex(pk["pt"])).devices]
    assert names[:3] == ["Printer 0001", "Bike Trainer 01", "Lock_00000002"] and len(names) == 6
    one = lt2.BlePacket.decode(bytes.fromhex(VEC["ble"][1]["packets"][0]["pt"]))
    assert one.truncated and one.total == 1 and one.survey.devices == 17
    assert w.open(bytes.fromhex(VEC["ble"][0]["packets"][0]["p"])) is None  # not a report


def test_wifi_select_rules():
    aps = [lt2.WifiAp(bytes.fromhex(b), r, s) for b, r, s in VEC["wifi"]["aps"]]
    f, pk = lt2.wifi_select(aps, 0)
    assert f.seed.hex() == "0024a5112233"         # strongest globally administered, not the hotspot
    assert all(t != b"\x00\x00\x04" for t, _ in pk.tails)  # _nomap left out
    assert lt2.WifiPacket.decode(pk.encode()).encode() == pk.encode()
    assert lt2.wifi_select([lt2.WifiAp(bytes.fromhex("02aabb000001"), -40)], 0) == (None, None)


def test_wifi_second_tail():
    """Two tail candidates or more: a second tail replaces the repeater (byte 30 bit 7)."""
    aps = [lt2.WifiAp(bytes.fromhex(b), r, s) for b, r, s in VEC["wifi"]["aps"]]
    seen = set()
    for n in range(4):  # the two tails take turns over the 7 candidates (2n, 2n + 1)
        f, _ = lt2.wifi_select(aps, n)
        assert f.tail2 is not None and f.tail != f.tail2
        seen |= {f.tail, f.tail2}
    assert len(seen) == 7
    for c in VEC["wifi_nofix"]:
        pt = bytes.fromhex(c["pt"])
        r = Report.decode(pt)
        assert r.encode() == pt
        assert r.wifi.tail2.hex() == c["tail2"] and pt[30] & 0x80
        assert r.neighbours == [] and r.nb_total == 2   # the repeater count stays, the entry goes
        assert r.wifi.seed2.hex() == c["seed2"]
    few = VEC["wifi_few"]
    pt = bytes.fromhex(few["pt"])
    r = Report.decode(pt)
    assert r.wifi.tail2 is None and r.wifi.tail.hex() == few["tail"] and pt[30] == 0
    assert [h.hex() for h, _ in r.neighbours] == ["a1b2"]
    aps2 = [lt2.WifiAp(bytes.fromhex(b), rs, s) for b, rs, s in few["aps"]]
    f, _ = lt2.wifi_select(aps2, few["n"])
    assert Report(has_fix=False, hacc_m=255, battery_pct=77, nb_total=2, neighbours=[(bytes.fromhex("a1b2"), 6.25)],
                  sync=True, next_in_s=3600, wifi=f).encode() == pt


def test_rpa():
    irk = bytes.fromhex(VEC["rpa"]["irk"])
    assert lt2.rpa_matches(irk, bytes.fromhex(VEC["rpa"]["match"]))
    assert not lt2.rpa_matches(irk, bytes.fromhex(VEC["rpa"]["other"]))


def test_ble_scan_command():
    c = Command.ble_scan(5, 10, True, 3)
    assert c.encode()[:6].hex() == "220505" + "0a0103"


def test_07_report_flags():
    """Flags bit 7 (CHECKS in 0.7, SYNC since 0.10.0) and parked (byte 2 bit 3, mode stays in bits 0..2)."""
    by_n = {r["n"]: Report.decode(bytes.fromhex(r["pt"])) for r in VEC["reports"]}
    parked, owner, plain = by_n[203], by_n[204], by_n[205]
    assert parked.quiet and parked.quiet_parked and not parked.quiet_owner and parked.sync
    assert parked.mode == lt2.Mode.IDLE and bytes.fromhex(VEC["reports"][-3]["pt"])[2] & 0x0F == 0x08
    assert owner.quiet and owner.quiet_owner and not owner.quiet_parked and owner.cmd_seq == 6
    assert plain.sync and not plain.quiet
    for r in VEC["reports"][:6]:
        rep = Report.decode(bytes.fromhex(r["pt"]))
        assert not rep.sync and not rep.quiet_parked
        assert rep.encode().hex() == r["pt"]


def test_07_commands_at_checks():
    keys = lt2.Keys(bytes.fromhex(VEC["k0"]))
    for c in VEC["check_commands"]:
        n, k = c["n"], c["k"]
        ce = keys.cmd(lt2.epoch(n))
        assert lt2.command_tag(ce, n, k).hex() == c["tag"] and lt2.command_tag(ce, n).hex() == c["tag_k0"]
        assert c["tag"] != c["tag_k0"]
        p = bytes.fromhex(c["p"])
        cmd = lt2.open_command(ce, n, p, k)
        assert cmd is not None and cmd.encode().hex() == c["pt"]
        assert lt2.seal_command(ce, n, cmd, k).hex() == c["p"]
        assert lt2.open_command(ce, n, p) is None and lt2.open_command(ce, n, p, k + 1) is None
        assert lt2.channel_wrap(p).hex() == c["channel_payload"]
    si = lt2.Command.decode(bytes.fromhex(VEC["check_commands"][2]["pt"]))
    assert si.op == lt2.Op.SET_IDLE and si.args[:5] == bytes([120, 0, 0, 0, 30])
    off = lt2.Command.decode(bytes.fromhex(VEC["check_commands"][3]["pt"]))
    assert off.args[4] == lt2.CHECK_OFF


def test_report_time_vectors():
    for v in VEC["report_time"]:
        assert lt2.report_time(v["created_at"], v["tags"]) == (v["from"], v["until"]), v["name"]


def test_077_ble_commands():
    """Commands over Bluetooth advertising (PROTOCOL.md 3.1): the same sealed command, compact."""
    for c in VEC["ble_commands"]:
        n, k = c["n"], c["k"]
        ce = KEYS.cmd(lt2.epoch(n))
        cmd = lt2.Command.decode(bytes.fromhex(c["pt"]))
        adv = bytes.fromhex(c["adv"])
        assert lt2.ble_command(ce, n, cmd, k) == adv and len(adv) == lt2.BLE_CMD_LEN
        assert lt2.seal_command(ce, n, cmd, k).hex() == c["p"]
        # the advert expands to exactly the mesh command, which opens as one
        assert lt2.ble_command_expand(ce, n, k, adv).hex() == c["p"]
        got = lt2.open_ble_command(ce, n, adv, k)
        assert got is not None and got.encode().hex() == c["pt"]
        assert adv[1:5].hex() == c["tag"] == lt2.command_tag(ce, n, k).hex()
        ad = bytes.fromhex(c["ad"])
        assert lt2.ble_command_ad(adv) == ad and len(ad) == 25
        assert lt2.ble_command_find(bytes.fromhex("020106") + ad) == adv
        # bound to (n, k): another check, another report, or k = 0 does not open it
        assert lt2.open_ble_command(ce, n, adv, (k + 1) & 0xFFFF) is None
        assert lt2.open_ble_command(ce, n + 1, adv, k) is None
        assert lt2.open_ble_command(ce, n, adv, 0) is None
        # any flipped bit fails the tag or the MAC
        for i in range(1, lt2.BLE_CMD_LEN):
            bad = bytearray(adv)
            bad[i] ^= 0x01
            assert lt2.open_ble_command(ce, n, bytes(bad), k) is None


def test_077_ble_command_too_long_or_foreign():
    ce = KEYS.cmd(0)
    long_args = lt2.Command(1, lt2.Op.SET_IDLE, bytes(6) + b"\x01")  # a byte at plaintext offset 9
    assert lt2.ble_command(ce, 5, long_args, 1) is None
    assert lt2.ble_command_find(bytes.fromhex("0bff4c00") + bytes(8)) is None  # Apple maker data
    assert lt2.ble_command_find(b"\x05\xff\xff\xff\x22\x00") is None           # too short
    assert lt2.ble_command_find(b"\x19\xff\xff\xff\x22") is None                # truncated AD


def test_080_ble_console_handshake_and_frames():
    v = VEC["ble_console"]
    uk = KEYS.usb_key
    salt, tn, an = (bytes.fromhex(v[k]) for k in ("salt", "tag_nonce", "app_nonce"))
    for a in v["adv"]:
        p = lt2.bcon_adv(uk, salt, a["left_s"])
        assert p.hex() == a["payload"] and len(p) == lt2.BCON_ADV_LEN
        assert lt2.bcon_adv_match(uk, p)
        assert not lt2.bcon_adv_match(bytes(32), p)
        bad = bytearray(p)
        bad[3] ^= 1  # salt changed: the tag no longer matches
        assert not lt2.bcon_adv_match(uk, bytes(bad))
    assert lt2.bcon_adv_ad(bytes.fromhex(v["adv"][0]["payload"])).hex() == v["adv_ad"]
    assert lt2.bcon_proof(uk, "a", tn, an).hex() == v["proof_a"]
    assert lt2.bcon_proof(uk, "t", tn, an).hex() == v["proof_t"]
    assert v["proof_a"] != v["proof_t"]
    s = lt2.BconSession(uk, tn, an)
    assert s.kd[0].hex() == v["kd_a"] and s.kd[1].hex() == v["kd_t"]
    for f in v["frames"]:
        d, c, pt, fr = f["dir"], f["c"], bytes.fromhex(f["pt"]), bytes.fromhex(f["frame"])
        assert len(fr) == 1 + len(pt) + lt2.BCON_MAC_LEN and fr[0] == lt2.BCON_DATA
        s.ctr[d] = c
        assert s.seal(d, pt) == fr and s.ctr[d] == (c + 1) & 0xFFFFFFFF
        r = lt2.BconSession(uk, tn, an)
        r.ctr[d] = c
        assert r.open(d, fr) == pt and r.ctr[d] == (c + 1) & 0xFFFFFFFF
        # rejects: wrong counter, a flipped bit in ct and in mac, the other direction, truncated
        for ctr, frame, dd in ((c + 1, fr, d), (c, fr[:1] + bytes([fr[1] ^ 1]) + fr[2:], d),
                               (c, fr[:-1] + bytes([fr[-1] ^ 0x80]), d), (c, fr, 1 - d), (c, fr[:-1], d),
                               (c, fr[:9], d), (c, b"\x11" + fr[1:], d)):
            r = lt2.BconSession(uk, tn, an)
            r.ctr[dd] = ctr & 0xFFFFFFFF
            assert r.open(dd, frame) is None and r.ctr[dd] == ctr & 0xFFFFFFFF
    for lk, off, tx, res, ok in v["window_ok"]:
        assert lt2.bcon_window_ok(bool(lk), bool(off), bool(tx), res) == bool(ok)
    assert sum(w[4] for w in v["window_ok"]) == 2  # locked, on, allowed, resets 1 or 3


def test_095_bcon_wake_advert():
    v = VEC["bcon_wake"]
    uk = KEYS.usb_key
    assert v["company"] == lt2.BLE_CMD_COMPANY and v["type"] == lt2.BCON_WAKE_TYPE == 0x26
    assert len(v["valid"]) >= 2
    for c in v["valid"]:
        salt = bytes.fromhex(c["salt"])
        p = lt2.bcon_wake(uk, salt)
        assert p.hex() == c["payload"] and len(p) == lt2.BCON_WAKE_LEN == 13
        assert lt2.hmac_sha256(uk, b"LT2-bcon-wake" + salt)[:6].hex() == c["tag"]
        assert lt2.bcon_wake_match(uk, p)
        assert not lt2.bcon_wake_match(KEYS.view(0), p)
        assert not lt2.bcon_adv_match(uk, p)
        ad = bytes.fromhex(c["ad"])
        assert lt2.bcon_wake_ad(p) == ad and len(ad) == 17 and ad[:4] == bytes.fromhex("10ffffff")
        assert lt2.bcon_wake_find(ad) == p
        assert lt2.ble_command_find(ad) is None
        for i in range(1, 13):  # any flipped bit of salt or tag fails
            bad = bytearray(p)
            bad[i] ^= 0x80
            assert not lt2.bcon_wake_match(uk, bytes(bad))
    for c in v["invalid"]:
        assert not lt2.bcon_wake_match(uk, bytes.fromhex(c["payload"])), c["why"]
    for f in v["find"]:
        got = lt2.bcon_wake_find(bytes.fromhex(f["ad"]))
        assert (got.hex() if got is not None else None) == f["payload"], f["ad"]


def test_092_ble_console_command():
    c = lt2.Command.ble_console(14)
    assert c.encode()[:4].hex() == "220e0600" and not any(c.encode()[4:])
    assert lt2.Command.ble_console(1, 300).encode()[3] == 30
    assert lt2.Command.ble_console(1, 9999).encode()[3] == 255
    for arg, s in VEC["ble_console_cmd"]:
        assert lt2.bcon_cmd_window_s(arg) == s
    assert lt2.bcon_cmd_window_s(0) == 180 and lt2.bcon_cmd_window_s(1) == 30 and lt2.bcon_cmd_window_s(255) == 600
    # it fits the Bluetooth advert form (3.1)
    ce = KEYS.cmd(0)
    assert lt2.ble_command(ce, 5, c, lt2.BLE_K_NEXT) is not None


def test_094_boot_reports():
    for v in VEC["boot_reports"]:
        e = lt2.epoch(v["n"])
        r = lt2.open_report(KEYS.view(e), v["n"], v["j"], bytes.fromhex(v["p"]))
        assert r is not None and r.encode().hex() == v["pt"]
        assert (int(r.mode), r.has_fix, r.boot, r.boot_cause, r.boot_resets) == \
            (v["mode"], v["has_fix"], v["boot"], v["boot_cause"], v["boot_resets"])
        assert (None if r.hacc_m == 255 else r.hacc_m) == v["hacc_m"]
        assert bytes.fromhex(v["pt"])[2] & lt2.B2_BOOT
    # byte 14 = cause | resets << 3, capped at 31
    assert lt2.boot_info("brownout", 4) == 0x25 and lt2.boot_info("crash", 99) == 0xFF
    assert lt2.boot_info("none", 0) == 0
    # the word as last_reset= picks it: faults first
    assert lt2.boot_cause_from_rst(0) == "none"
    assert lt2.boot_cause_from_rst(0x01 | 0x10) == "brownout"
    assert lt2.boot_cause_from_rst(0x02 | 0x20) == "watchdog"
    assert lt2.boot_cause_from_rst(0x02 | 0x04) == "power"
    assert lt2.boot_cause_from_rst(0x40 | 0x20) == "crash"
    # reports without the bit keep byte 14 as accuracy and decode as before
    for v in VEC["reports"]:
        assert not lt2.Report.decode(bytes.fromhex(v["pt"])).boot

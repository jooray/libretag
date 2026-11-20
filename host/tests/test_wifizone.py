"""Wi-Fi zones (docs/design/fw-wifi-esp32.md): the Python mirror of lt_core's fingerprint matching."""

import json
from pathlib import Path

from libretag.lt2 import Mode, WifiAp
from libretag.wake import Action, Reason, WakeCfg, WakeIn, WakeState, decide, vector_wifi_scenarios
from libretag.wifizone import (ENTER, STAY, WZONE_BASE, ZoneCfg, fingerprint, match, score, vector_wifi_zone,
                               wifi_hash, wscan_from_aps, zone_usable)

VEC = json.loads((Path(__file__).parent / "vectors" / "lt2.json").read_text())
SALT = bytes(16)


def ap(i: int, rssi: int, laa: bool = False) -> WifiAp:
    return WifiAp(bytes([0x02 if laa else 0x00, 0x11, 0x22, 0, i >> 8, i & 0xFF]), rssi)


def test_vectors_match():
    assert VEC["wifi_zone"] == vector_wifi_zone()
    assert VEC["wake_wifi"] == vector_wifi_scenarios()


def test_hash_is_keyed():
    b = bytes.fromhex("0024a5000001")
    assert wifi_hash(SALT, b) != wifi_hash(bytes([1]) + SALT[1:], b)


def test_scan_filtering():
    s = wscan_from_aps(SALT, [ap(1, -60), ap(2, -50, laa=True), ap(1, -55), WifiAp(bytes(6), -40), ap(3, 5)])
    assert [r for _, r in s] == [-1, -55]   # LAA and the zero BSSID dropped, duplicate kept strongest, rssi <= -1
    many = wscan_from_aps(SALT, [ap(i, -40 - i) for i in range(60)])
    assert len(many) == 16 and [r for _, r in many] == [-40 - i for i in range(16)]
    assert not zone_usable(bytes.fromhex("02aabbccddee")) and not zone_usable(bytes.fromhex("01005e000001"))


def test_score_bounds():
    s = wscan_from_aps(SALT, [ap(i, -50 - 5 * i) for i in range(6)])
    f = fingerprint([s, s, s])
    assert score(f, s) == (100, 6)
    other = wscan_from_aps(SALT, [ap(100 + i, -50 - 5 * i) for i in range(6)])
    assert score(f, other) == (0, 0)
    assert score(f, []) == (0, 0)
    assert match(ZoneCfg(), [f], []) == (-1, 0)       # nothing seen is never a zone (fail-open)


def test_fingerprint_drops_rare_aps():
    a = [ap(1, -55), ap(2, -70)]
    scans = [wscan_from_aps(SALT, a + ([ap(9, -60)] if i == 0 else [])) for i in range(4)]
    f = fingerprint(scans)
    assert len(f.ap) == 2 and all(e.seen == 4 for e in f.ap)


def test_hysteresis():
    home = fingerprint([wscan_from_aps(SALT, [ap(1, -55), ap(2, -62), ap(3, -70), ap(4, -80)])] * 3)
    weak = wscan_from_aps(SALT, [ap(1, -66), ap(2, -75), ap(7, -70)])
    sc, m = score(home, weak)
    assert STAY <= sc < ENTER and m == 2
    assert match(ZoneCfg(), [home], weak, -1) == (-1, sc)   # not enough to enter
    assert match(ZoneCfg(), [home], weak, 0) == (0, sc)     # enough to stay


def test_one_ap_zone_needs_that_ap():
    z = fingerprint([wscan_from_aps(SALT, [ap(1, -50)])])
    assert match(ZoneCfg(), [z], wscan_from_aps(SALT, [ap(1, -52)]))[0] == 0
    assert match(ZoneCfg(), [z], wscan_from_aps(SALT, [ap(2, -52)]))[0] == -1


def test_wake_wifi_zone_goes_quiet_and_fix_wins():
    cfg, st = WakeCfg(), WakeState()
    o = decide(cfg, WakeIn(0, wifi_valid=True, wifi_zone=0), st)
    assert o.action == Action.REPORT_QUIET and o.reason == Reason.ZONE and o.zone == WZONE_BASE
    o = decide(cfg, WakeIn(3600, wifi_valid=True, wifi_zone=0), st)
    assert o.action == Action.SILENT
    # a fix outside every GPS zone overrides the Wi-Fi zone
    o = decide(cfg, WakeIn(7200, has_fix=True, lat_e7=487100000, lon_e7=212500000, wifi_valid=True, wifi_zone=0), st)
    assert o.action == Action.REPORT
    # no usable scan: fail-open
    decide(cfg, WakeIn(10800, wifi_valid=True, wifi_zone=0), st)
    o = decide(cfg, WakeIn(14400), st)
    assert o.action == Action.REPORT and not st.quiet


def test_wake_wifi_parked():
    cfg, st = WakeCfg(), WakeState()
    acts = [decide(cfg, WakeIn(i * 3600, wifi_valid=True, wifi_still=i > 0), st).action for i in range(4)]
    assert acts == [Action.REPORT, Action.REPORT, Action.REPORT_QUIET, Action.SILENT]
    assert decide(cfg, WakeIn(5 * 3600, mode=Mode.LOST, wifi_valid=True, wifi_still=True), st).action == Action.REPORT

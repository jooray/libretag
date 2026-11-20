"""Wi-Fi zones: a place as a fingerprint of the access points seen there (docs/design/fw-wifi-esp32.md).

Python mirror of the "Wi-Fi zones" section of firmware/lt_core/lt_core.c (lt_wifi_hash,
lt_wscan_from_aps, lt_wfp_*, lt_wzone_match); the vectors "wifi_zone" and "wake_wifi" in lt2.json pin
both. Integer math throughout, so C and Python agree to the last digit.

A fingerprint keeps, per AP, a 32-bit keyed hash of the BSSID (the BSSID itself is not stored), the
mean RSSI over the snapshot's scans, in how many scans it was seen, and the RSSI spread. A scan is
compared with a weighted Jaccard similarity: an AP weighs clamp(rssi + 95, 0, 60), in the fingerprint
times seen / scans; score = 100 * sum(min) / sum(max) over the union of both AP sets, where an AP of
the scan that the place does not have counts half (new APs appear at home more often than a stranger's
place holds all of ours). Only globally
administered BSSIDs count (a phone hotspot must not carry the zone with it), and of a scan only the 16
strongest.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .lt2 import WifiAp, hmac_sha256

WFP_MAX = 16        # APs kept per fingerprint and taken from a scan
ACC_MAX = 48        # APs tracked while a snapshot is taken
FLOOR_DBM = -95     # weight 0
W_MAX = 60          # weight of -35 dBm and stronger
ENTER = 40          # score to enter a zone
STAY = 30           # score to stay in the zone the tracker is in
MIN_MATCH = 2       # APs in common to enter (fewer when the zone has fewer)
WZONE_BASE = 16     # WakeState.zone of Wi-Fi zone i is WZONE_BASE + i


def wifi_hash(salt: bytes, bssid: bytes) -> int:
    return int.from_bytes(hmac_sha256(salt, bssid)[:4], "little")


def zone_usable(bssid: bytes) -> bool:
    """A BSSID that can describe a place: globally administered, not group, not all zero."""
    return not (bssid[0] & 0x03) and bssid != bytes(6)


def weight(rssi: int) -> int:
    return max(0, min(W_MAX, rssi - FLOOR_DBM))


def _before(a: tuple[int, int], b: tuple[int, int]) -> bool:
    """(hash, rssi): stronger first, ties by hash"""
    return a[1] > b[1] if a[1] != b[1] else a[0] < b[0]


def wscan_from_aps(salt: bytes, aps: list[WifiAp]) -> list[tuple[int, int]]:
    """A scan for zone matching: usable BSSIDs, duplicates merged (the strongest), the 16 strongest,
    hashed, strongest first: a list of (hash, rssi)."""
    allv: list[tuple[int, int]] = []
    for a in aps:
        if not zone_usable(a.bssid):
            continue
        e = (wifi_hash(salt, a.bssid), min(a.rssi, -1))
        k = next((i for i, x in enumerate(allv) if x[0] == e[0]), None)
        if k is not None:
            if e[1] > allv[k][1]:
                allv[k] = (allv[k][0], e[1])
            continue
        if len(allv) < ACC_MAX:
            allv.append(e)
        else:
            w = 0
            for i in range(1, len(allv)):
                if _before(allv[w], allv[i]):
                    w = i
            if _before(e, allv[w]):
                allv[w] = e
    allv.sort(key=lambda x: (-x[1], x[0]))
    return allv[:WFP_MAX]


@dataclass
class FpAp:
    h: int
    rssi: int    # mean dBm
    seen: int    # scans of the snapshot that saw it
    spread: int  # max - min dBm


@dataclass
class Fingerprint:
    scans: int = 0
    ap: list[FpAp] = field(default_factory=list)  # heaviest first

    def ap_weight(self, e: FpAp) -> int:
        return weight(e.rssi) * e.seen // self.scans if self.scans else 0


@dataclass
class _AccAp:
    h: int
    sum: int = 0
    min: int = 0
    max: int = 0
    seen: int = 0


@dataclass
class Snapshot:
    """Accumulates the scans of a snapshot (`wzone snap`)."""

    scans: int = 0
    ap: list[_AccAp] = field(default_factory=list)

    def add(self, s: list[tuple[int, int]]) -> None:
        if self.scans == 255:
            return
        self.scans += 1
        for h, r in s:
            e = next((x for x in self.ap if x.h == h), None)
            if e is None:
                if len(self.ap) == ACC_MAX:
                    continue
                e = _AccAp(h, 0, r, r, 0)
                self.ap.append(e)
            e.sum += r
            e.seen += 1
            e.min = min(e.min, r)
            e.max = max(e.max, r)

    def finish(self) -> Fingerprint:
        """APs seen in at least half of the scans, the 16 heaviest."""
        f = Fingerprint(scans=self.scans)
        es = []
        for a in self.ap:
            if 2 * a.seen < self.scans:
                continue
            es.append(FpAp(a.h, -((-a.sum + a.seen // 2) // a.seen), a.seen, a.max - a.min))
        es.sort(key=lambda e: (-f.ap_weight(e), e.h))
        f.ap = es[:WFP_MAX]
        return f


def fingerprint(scans: list[list[tuple[int, int]]]) -> Fingerprint:
    s = Snapshot()
    for x in scans:
        s.add(x)
    return s.finish()


def score(f: Fingerprint, s: list[tuple[int, int]]) -> tuple[int, int]:
    """(similarity 0..100, APs in common)"""
    s = s[:WFP_MAX]
    used = [False] * len(s)
    num = den = m = 0
    for e in f.ap[:WFP_MAX]:
        wf, ws = f.ap_weight(e), 0
        for k, (h, r) in enumerate(s):
            if not used[k] and h == e.h:
                used[k] = True
                ws = weight(r)
                if ws > 0 and wf > 0:
                    m += 1
                break
        num += min(wf, ws)
        den += max(wf, ws)
    den += sum(weight(r) // 2 for k, (_, r) in enumerate(s) if not used[k])
    return (100 * num // den if den else 0), m


@dataclass
class ZoneCfg:
    enter: int = ENTER
    stay: int = STAY
    min_match: int = MIN_MATCH


def match(c: ZoneCfg, zones: list[Fingerprint], s: list[tuple[int, int]], prev: int = -1) -> tuple[int, int]:
    """(zone index or -1, score): the zone the tracker was in while its score stays at or above
    `stay`, else the best zone at or above `enter` with enough APs in common. An empty scan is never
    a zone (fail-open, as without a fix). The score is that of the result, or the best one seen."""
    if s and 0 <= prev < len(zones):
        sc, m = score(zones[prev], s)
        if sc >= c.stay and m >= 1:
            return prev, sc
    best, best_sc, top = -1, 0, 0
    for i, z in enumerate(zones if s else []):
        need = min(len(z.ap), c.min_match)
        sc, m = score(z, s)
        top = max(top, sc)
        if sc >= c.enter and m >= need and m >= 1 and (best < 0 or sc > best_sc):
            best, best_sc = i, sc
    return best, (best_sc if best >= 0 else top)


# ---------------------------------------------------------------- vectors

VECTOR_SALT = bytes(range(16))


def _ap(i: int, rssi: int, ssid: str = "", laa: bool = False) -> WifiAp:
    """Synthetic BSSID 00:24:a5:00:00:i (or 02:… for a locally administered one)."""
    return WifiAp(bytes([0x02 if laa else 0x00, 0x24, 0xA5, 0, 0, i]), rssi, ssid)


def vector_scans() -> dict[str, list[list[WifiAp]]]:
    """Synthetic scans shaped like the ones measured on the bench (fw-wifi-esp32.md): a few strong
    APs that never miss, weak ones that come and go, a phone hotspot, a duplicate BSSID."""
    home = [
        [_ap(1, -52), _ap(2, -64), _ap(3, -71), _ap(4, -83), _ap(5, -90), _ap(9, -60, laa=True)],
        [_ap(1, -55), _ap(2, -62), _ap(3, -74), _ap(4, -85), _ap(6, -91)],
        [_ap(1, -51), _ap(2, -66), _ap(3, -70), _ap(4, -82), _ap(5, -89), _ap(2, -61)],
    ]
    return {
        "home": home,
        "inside": [[_ap(1, -54), _ap(2, -63), _ap(3, -72), _ap(4, -86)],
                   [_ap(1, -58), _ap(3, -77), _ap(7, -88)],
                   [_ap(2, -60), _ap(5, -88)]],
        "outside": [[_ap(1, -84), _ap(10, -60), _ap(11, -66), _ap(12, -70)],
                    [_ap(10, -55), _ap(11, -62)],
                    [_ap(9, -50, laa=True)],
                    []],
    }


def vector_wifi_zone() -> dict:
    sc = vector_scans()
    salt = VECTOR_SALT
    home_ws = [wscan_from_aps(salt, s) for s in sc["home"]]
    f = fingerprint(home_ws)
    one = fingerprint([home_ws[0]])
    c = ZoneCfg()
    cases = []
    for kind in ("inside", "outside"):
        for s in sc[kind]:
            ws = wscan_from_aps(salt, s)
            sc_h, m_h = score(f, ws)
            sc_1, m_1 = score(one, ws)
            z_new, s_new = match(c, [one, f], ws, -1)
            z_in, s_in = match(c, [one, f], ws, 1)
            cases.append({"kind": kind, "aps": [[a.bssid.hex(), a.rssi] for a in s],
                          "ws": [[h, r] for h, r in ws], "score": sc_h, "matched": m_h,
                          "score_one": sc_1, "matched_one": m_1,
                          "match_new": [z_new, s_new], "match_prev1": [z_in, s_in]})

    def fp(x: Fingerprint) -> dict:
        return {"scans": x.scans, "ap": [[e.h, e.rssi, e.seen, e.spread] for e in x.ap]}

    return {"salt": salt.hex(), "cfg": [c.enter, c.stay, c.min_match],
            "hash": [[b.hex(), wifi_hash(salt, b)] for b in (bytes.fromhex("0024a5000001"),
                                                               bytes.fromhex("c8d719000003"))],
            "snapshot": [[[a.bssid.hex(), a.rssi] for a in s] for s in sc["home"]],
            "snapshot_ws": [[[h, r] for h, r in ws] for ws in home_ws],
            "fingerprint": fp(f), "fingerprint_one": fp(one), "cases": cases}

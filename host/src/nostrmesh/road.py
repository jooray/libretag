"""Roadbeacon / Roadstr compact frames (kinds 1315/1316), data_type 0x0150 on #roadstr.

Written from roadbeacon/nips/road-events.md; checked against its conformance vectors.
"""
from __future__ import annotations

import re
import struct

from .errors import FrameError
from .nostr import Event, is_lower_hex

KIND_REPORT = 1315
KIND_VOTE = 1316
REPORT_BASE = 117
VOTE_LEN = 134
MAX_COMMENT_ENCODE = 46
MAX_COMMENT_DECODE = 48

TYPES = {
    "police": 0, "speed_camera": 1, "traffic_jam": 2, "accident": 3, "road_closure": 4,
    "construction": 5, "hazard": 6, "road_condition": 7, "pothole": 8, "fog": 9, "ice": 10,
    "animal": 11, "police_station": 12, "surveillance_camera": 13, "other": 255,
}
TYPE_NAMES = {v: k for k, v in TYPES.items()}
LIFETIME = {
    "police": 4 * 3600, "speed_camera": 30 * 86400, "traffic_jam": 3600, "accident": 4 * 3600,
    "road_closure": 24 * 3600, "construction": 15 * 86400, "hazard": 4 * 3600,
    "road_condition": 15 * 86400, "pothole": 7 * 86400, "fog": 4 * 3600, "ice": 4 * 3600,
    "animal": 3600, "police_station": 30 * 86400, "surveillance_camera": 30 * 86400,
    "other": 4 * 3600,
}
STATUS = {"no_longer_there": 0, "still_there": 1}
STATUS_NAMES = {v: k for k, v in STATUS.items()}
BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
_COORD = re.compile(r"^-?(0|[1-9][0-9]*)\.[0-9]{6}$")
_UINT = re.compile(r"^(0|[1-9][0-9]*)$")


def fmt_e6(v: int) -> str:
    sign = "-" if v < 0 else ""
    m = abs(v)
    return f"{sign}{m // 1_000_000}.{m % 1_000_000:06d}"


def parse_e6(s: str) -> int:
    if not _COORD.match(s):
        raise FrameError("NON_CANONICAL", f"coordinate {s!r}")
    neg = s.startswith("-")
    a, b = s.lstrip("-").split(".")
    v = int(a) * 1_000_000 + int(b)
    if neg and v == 0:
        raise FrameError("NON_CANONICAL", "negative zero")
    return -v if neg else v


def geohash(lat: float, lon: float, precision: int) -> str:
    lat_r, lon_r = [-90.0, 90.0], [-180.0, 180.0]
    out, bit, ch, is_lon = [], 0, 0, True
    while len(out) < precision:
        rng, v = (lon_r, lon) if is_lon else (lat_r, lat)
        mid = (rng[0] + rng[1]) / 2
        if v >= mid:
            ch |= 1 << (4 - bit)
            rng[0] = mid
        else:
            rng[1] = mid
        is_lon = not is_lon
        bit += 1
        if bit == 5:
            out.append(BASE32[ch])
            bit, ch = 0, 0
    return "".join(out)


def check_comment(b: bytes, limit: int, encoding: bool) -> str:
    try:
        s = b.decode("utf-8")
    except UnicodeDecodeError:
        raise FrameError("NON_CANONICAL", "comment is not UTF-8") from None
    if any(ord(c) < 0x20 or ord(c) == 0x7F for c in s):
        raise FrameError("NON_CANONICAL", "control character in comment")
    if len(b) > limit:
        raise FrameError("COMMENT_TOO_LONG" if encoding else "BAD_LENGTH")
    return s


def report_tags(lat_e6: int, lon_e6: int, type_name: str, expiration: int, maxspeed: int = 0):
    lat, lon = lat_e6 / 1e6, lon_e6 / 1e6
    tags = [["lat", fmt_e6(lat_e6)], ["lon", fmt_e6(lon_e6)]]
    tags += [["g", geohash(lat, lon, p)] for p in (4, 5, 6)]
    tags += [["t", type_name], ["expiration", str(expiration)]]
    if maxspeed:
        tags.append(["maxspeed", str(maxspeed)])
    return tags


# ---------------------------------------------------------------- decode


def decode(frame: bytes) -> Event:
    """Frame -> event (id filled in, signature NOT yet verified)."""
    if not frame:
        raise FrameError("BAD_LENGTH")
    flags = frame[0]
    if flags >> 4:
        raise FrameError("BAD_VERSION")
    if flags & 0x03:
        raise FrameError("RESERVED_BITS")
    is_vote = bool(flags & 0x08)
    if is_vote:
        if len(frame) != VOTE_LEN:
            raise FrameError("BAD_LENGTH")
        status = frame[69]
        if status not in STATUS_NAMES:
            raise FrameError("BAD_STATUS")
        ev = Event(frame[1:33].hex(), struct.unpack_from(">I", frame, 33)[0], KIND_VOTE,
                   [["e", frame[37:69].hex()], ["status", STATUS_NAMES[status]]], "",
                   sig=frame[70:134].hex())
    else:
        if len(frame) < REPORT_BASE:
            raise FrameError("BAD_LENGTH")
        n = frame[52]
        if n > MAX_COMMENT_DECODE or len(frame) != REPORT_BASE + n:
            raise FrameError("BAD_LENGTH")
        created_at, lat, lon = struct.unpack_from(">Iii", frame, 33)
        typ = frame[45]
        expiration, maxspeed = struct.unpack_from(">IH", frame, 46)
        if typ not in TYPE_NAMES:
            raise FrameError("UNKNOWN_TYPE")
        if not (-90_000_000 <= lat <= 90_000_000 and -180_000_000 <= lon <= 180_000_000):
            raise FrameError("COORDINATE_RANGE")
        if maxspeed and not 5 <= maxspeed <= 300:
            raise FrameError("BAD_SPEED_LIMIT")
        comment = check_comment(frame[53:53 + n], MAX_COMMENT_DECODE, False)
        ev = Event(frame[1:33].hex(), created_at, KIND_REPORT,
                   report_tags(lat, lon, TYPE_NAMES[typ], expiration, maxspeed), comment,
                   sig=frame[53 + n:].hex())
    ev.id = ev.compute_id()
    return ev


# ---------------------------------------------------------------- encode


def _str_tags(ev: Event) -> bool:
    return isinstance(ev.tags, list) and all(
        isinstance(t, list) and t and all(isinstance(x, str) for x in t) for t in ev.tags)


def encode(ev: Event, flags: int = 0) -> bytes:
    if ev.kind not in (KIND_REPORT, KIND_VOTE):
        raise FrameError("WRONG_KIND")
    if not (_str_tags(ev) and isinstance(ev.content, str) and isinstance(ev.created_at, int)
            and is_lower_hex(ev.pubkey, 32) and is_lower_hex(ev.sig, 64)):
        raise FrameError("MALFORMED_EVENT")
    if not 0 <= ev.created_at < 2**32:
        raise FrameError("TIMESTAMP_RANGE")
    head = bytes.fromhex(ev.pubkey) + struct.pack(">I", ev.created_at)
    if ev.kind == KIND_VOTE:
        if ev.content:
            raise FrameError("NON_EMPTY_CONTENT")
        st = next((t for t in ev.tags if t[0] == "status"), None)
        if st is None or len(st) != 2 or st[1] not in STATUS:
            raise FrameError("BAD_STATUS")
        if len(ev.tags) != 2 or ev.tags[0][0] != "e" or len(ev.tags[0]) != 2 \
                or not is_lower_hex(ev.tags[0][1], 32) or ev.tags[1] is not st:
            raise FrameError("NON_CANONICAL")
        frame = bytes([0x08 | flags]) + head + bytes.fromhex(ev.tags[0][1]) + bytes([STATUS[st[1]]]) \
            + bytes.fromhex(ev.sig)
    else:
        tag = {t[0]: t for t in ev.tags}
        t = tag.get("t")
        if t is not None and len(t) == 2 and t[1] not in TYPES:
            raise FrameError("UNKNOWN_TYPE")
        ms = tag.get("maxspeed")
        maxspeed = 0
        if ms is not None:
            if len(ms) != 2 or not _UINT.match(ms[1]) or not 5 <= int(ms[1]) <= 300:
                raise FrameError("BAD_SPEED_LIMIT")
            maxspeed = int(ms[1])
        ex = tag.get("expiration")
        if ex is None or len(ex) != 2 or not _UINT.match(ex[1]):
            raise FrameError("NON_CANONICAL", "expiration")
        expiration = int(ex[1])
        if expiration >= 2**32:
            raise FrameError("TIMESTAMP_RANGE")
        lat_t, lon_t = tag.get("lat"), tag.get("lon")
        if not lat_t or not lon_t or len(lat_t) != 2 or len(lon_t) != 2 or t is None or len(t) != 2:
            raise FrameError("NON_CANONICAL", "missing tag")
        lat, lon = parse_e6(lat_t[1]), parse_e6(lon_t[1])
        if not (-90_000_000 <= lat <= 90_000_000 and -180_000_000 <= lon <= 180_000_000):
            raise FrameError("COORDINATE_RANGE")
        comment = ev.content.encode()
        check_comment(comment, MAX_COMMENT_ENCODE, True)
        if ev.tags != report_tags(lat, lon, t[1], expiration, maxspeed):
            raise FrameError("NON_CANONICAL", "tag layout")
        frame = bytes([flags]) + head + struct.pack(">ii", lat, lon) + bytes([TYPES[t[1]]]) \
            + struct.pack(">IH", expiration, maxspeed) + bytes([len(comment)]) + comment \
            + bytes.fromhex(ev.sig)
    back = decode(frame)
    if back.id != ev.compute_id() or not ev.verify():
        raise FrameError("SIGNATURE_INVALID")
    return frame

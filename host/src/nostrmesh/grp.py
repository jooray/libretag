"""MeshCore GRP_DATA on hashtag channels, for adapters that see raw packets (KISS)."""
from __future__ import annotations

import hashlib
import hmac
import struct

from libretag.lt2 import _ecb, channel_key, channel_wrap, hmac_sha256

__all__ = ["channel_key", "channel_hash", "wrap", "unwrap"]


def channel_hash(key: bytes) -> int:
    return hashlib.sha256(key).digest()[0]


def wrap(key: bytes, data_type: int, data: bytes) -> bytes:
    return channel_wrap(data, key, data_type)


def unwrap(key: bytes, payload: bytes) -> tuple[int, bytes] | None:
    if len(payload) < 19 or (len(payload) - 3) % 16 or payload[0] != channel_hash(key):
        return None
    ct = payload[3:]
    if not hmac.compare_digest(hmac_sha256(key + b"\x00" * 16, ct)[:2], payload[1:3]):
        return None
    pt = _ecb(key, ct, decrypt=True)
    dt, ln = struct.unpack_from("<HB", pt)
    if 3 + ln > len(pt):
        return None
    return dt, pt[3:3 + ln]

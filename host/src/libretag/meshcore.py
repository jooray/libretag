"""Minimal MeshCore v1 packet parsing/building (see MeshCore docs/packet_format.md)."""

from __future__ import annotations

import math
from dataclasses import dataclass, field

ROUTE_TRANSPORT_FLOOD = 0x00
ROUTE_FLOOD = 0x01
ROUTE_DIRECT = 0x02
ROUTE_TRANSPORT_DIRECT = 0x03

PAYLOAD_REQ = 0x00
PAYLOAD_RESPONSE = 0x01
PAYLOAD_TXT_MSG = 0x02
PAYLOAD_ACK = 0x03
PAYLOAD_ADVERT = 0x04
PAYLOAD_GRP_TXT = 0x05
PAYLOAD_GRP_DATA = 0x06
PAYLOAD_ANON_REQ = 0x07
PAYLOAD_PATH = 0x08
PAYLOAD_RAW_CUSTOM = 0x0F

MAX_PATH_SIZE = 64
MAX_PACKET_PAYLOAD = 184


class PacketError(ValueError):
    pass


@dataclass
class Packet:
    route_type: int
    payload_type: int
    payload: bytes
    path: list[bytes] = field(default_factory=list)  # one entry per hop
    path_hash_size: int = 1
    transport_codes: bytes = b""  # 4 bytes for TRANSPORT_* routes
    payload_version: int = 0

    @property
    def is_flood(self) -> bool:
        return self.route_type in (ROUTE_FLOOD, ROUTE_TRANSPORT_FLOOD)

    @property
    def header(self) -> int:
        return (
            (self.route_type & 0x03)
            | ((self.payload_type & 0x0F) << 2)
            | ((self.payload_version & 0x03) << 6)
        )

    def to_bytes(self) -> bytes:
        if self.path_hash_size not in (1, 2, 3):
            raise PacketError("bad path hash size")
        if any(len(h) != self.path_hash_size for h in self.path):
            raise PacketError("path entry size mismatch")
        if len(self.path) > 63 or len(self.path) * self.path_hash_size > MAX_PATH_SIZE:
            raise PacketError("path too long")
        if len(self.payload) > MAX_PACKET_PAYLOAD:
            raise PacketError("payload too long")
        out = bytearray([self.header])
        if self.route_type in (ROUTE_TRANSPORT_FLOOD, ROUTE_TRANSPORT_DIRECT):
            if len(self.transport_codes) != 4:
                raise PacketError("transport route needs 4 bytes of transport codes")
            out += self.transport_codes
        out.append(((self.path_hash_size - 1) << 6) | len(self.path))
        for h in self.path:
            out += h
        out += self.payload
        return bytes(out)

    @classmethod
    def from_bytes(cls, raw: bytes) -> "Packet":
        if len(raw) < 2:
            raise PacketError("too short")
        hdr = raw[0]
        route = hdr & 0x03
        i = 1
        tc = b""
        if route in (ROUTE_TRANSPORT_FLOOD, ROUTE_TRANSPORT_DIRECT):
            tc = raw[i : i + 4]
            i += 4
            if len(tc) != 4:
                raise PacketError("truncated transport codes")
        if i >= len(raw):
            raise PacketError("missing path_len")
        pl = raw[i]
        i += 1
        hsz = (pl >> 6) + 1
        if hsz == 4:
            raise PacketError("reserved path hash size")
        count = pl & 0x3F
        plen = hsz * count
        if plen > MAX_PATH_SIZE or i + plen > len(raw):
            raise PacketError("bad path")
        path = [raw[i + k * hsz : i + (k + 1) * hsz] for k in range(count)]
        i += plen
        payload = raw[i:]
        if len(payload) > MAX_PACKET_PAYLOAD:
            raise PacketError("payload too long")
        return cls(
            route_type=route,
            payload_type=(hdr >> 2) & 0x0F,
            payload_version=(hdr >> 6) & 0x03,
            payload=payload,
            path=path,
            path_hash_size=hsz,
            transport_codes=tc,
        )


def lora_airtime_s(
    n_bytes: int, sf: int = 8, bw_hz: float = 62500, cr: int = 8, preamble: int = 16
) -> float:
    """Semtech LoRa time-on-air (explicit header, CRC on). cr is 5..8 (4/5..4/8)."""
    tsym = (2**sf) / bw_hz
    de = 1 if tsym > 0.016 else 0
    num = 8 * n_bytes - 4 * sf + 28 + 16
    payload_sym = 8 + max(math.ceil(num / (4 * (sf - 2 * de))) * cr, 0)
    return (preamble + 4.25) * tsym + payload_sym * tsym

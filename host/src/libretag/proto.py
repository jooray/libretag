"""LT2 on MeshCore packets (PROTOCOL.md 2 "Doprava", 3). The crypto lives in lt2.py; this module only
puts LT2 payloads into GRP_DATA packets and takes them out again."""

from __future__ import annotations

from .lt2 import (CHANNEL_DATA_TYPE, CHANNEL_NAME, CMD_P_LEN, REPORT_P_LEN, REPORT_P_LEN_STEALTH, Command, Keys,
                  Mode, Op, Report, ReportWindow, channel_key, channel_unwrap, channel_wrap, epoch, open_command,
                  open_report, seal_command, seal_report, seal_report_keys)
from .meshcore import (PAYLOAD_GRP_DATA, ROUTE_DIRECT, ROUTE_FLOOD, ROUTE_TRANSPORT_DIRECT, ROUTE_TRANSPORT_FLOOD,
                       Packet)

__all__ = [
    "CHANNEL_DATA_TYPE", "CHANNEL_NAME", "CMD_P_LEN", "REPORT_P_LEN", "REPORT_P_LEN_STEALTH", "Command", "Keys",
    "Mode", "Op", "Report", "ReportWindow", "channel_key", "channel_unwrap", "channel_wrap", "epoch",
    "open_command", "open_report", "seal_command", "seal_report", "report_packet", "report_payload",
    "command_packet", "command_payload", "REPORT_GRP_LEN",
]

REPORT_GRP_LEN = 51  # channel datagram (1 + 2 + 48) and stealth P have the same size


def report_packet(keys: Keys, n: int, j: int, report: Report, stealth: bool = False,
                  transport_codes: bytes = b"", path_hash_size: int = 1) -> Packet:
    """Tracker side: report n as a flood GRP_DATA, on #libretag or stealth (no channel envelope)."""
    p = seal_report_keys(keys, n, j, report, stealth=stealth)
    payload = p if stealth else channel_wrap(p)
    route = ROUTE_TRANSPORT_FLOOD if transport_codes else ROUTE_FLOOD
    return Packet(route, PAYLOAD_GRP_DATA, payload, path_hash_size=path_hash_size, transport_codes=transport_codes)


def report_payload(pkt: Packet) -> bytes | None:
    """Receiver side: the LT2 report P carried by a flood GRP_DATA (channel or stealth), or None."""
    if pkt.payload_type != PAYLOAD_GRP_DATA or not pkt.is_flood or len(pkt.payload) != REPORT_GRP_LEN:
        return None
    inner = channel_unwrap(pkt.payload)
    if inner is not None:
        return inner if len(inner) == REPORT_P_LEN else None
    return pkt.payload  # stealth: P itself, x[0] plays the channel hash


def command_packet(pc: bytes, report_pkt: Packet, flood: bool = False) -> Packet:
    """A sealed command Pc on #libretag in reply to `report_pkt`: direct along the reversed path of the
    report (zero-hop when it was heard directly), or flood when `flood`."""
    payload = channel_wrap(pc)
    if flood:
        route = ROUTE_TRANSPORT_FLOOD if report_pkt.transport_codes else ROUTE_FLOOD
        return Packet(route, PAYLOAD_GRP_DATA, payload, path_hash_size=report_pkt.path_hash_size,
                      transport_codes=report_pkt.transport_codes)
    route = ROUTE_TRANSPORT_DIRECT if report_pkt.transport_codes else ROUTE_DIRECT
    return Packet(route, PAYLOAD_GRP_DATA, payload, path=list(reversed(report_pkt.path)),
                  path_hash_size=report_pkt.path_hash_size, transport_codes=report_pkt.transport_codes)


def command_payload(pkt: Packet) -> bytes | None:
    """Tracker side: Pc from a GRP_DATA on #libretag (any route; a direct one must have reached us)."""
    if pkt.payload_type != PAYLOAD_GRP_DATA:
        return None
    if pkt.route_type in (ROUTE_DIRECT, ROUTE_TRANSPORT_DIRECT) and pkt.path:
        return None  # still travelling
    inner = channel_unwrap(pkt.payload)
    return inner if inner is not None and len(inner) == CMD_P_LEN else None

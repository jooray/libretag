"""Base station: receive LT2 reports, store/forward them, reply with queued commands (SPEC 6.2)."""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass

from . import traccar
from .lt2 import Command, Keys, Mode, Op, Report, ReportWindow, epoch, seal_command
from .meshcore import Packet, PacketError
from .proto import command_packet, report_payload
from .store import Store
from .transport import Transport

log = logging.getLogger(__name__)

FRESH_S = 40.0  # a command goes out only this soon after the report was heard (tracker RX window is 60 s)


@dataclass
class Received:
    tracker: str
    n: int
    j: int
    report: Report
    packet: Packet
    command_sent: Command | None
    late: bool = False


def _is_lost(rep: Report, cmd: Command) -> bool:
    return rep.mode == Mode.LOST or (cmd.op == Op.SET_MODE and cmd.args[:1] == bytes([Mode.LOST]))


class BaseStation:
    def __init__(self, store: Store, transport: Transport | None = None, traccar_url: str | None = None,
                 clock=time.time):
        self.store = store
        self.transport = transport
        self.traccar_url = traccar_url
        self.clock = clock
        self.windows: dict[str, ReportWindow] = {}
        self.reload()

    def reload(self) -> None:
        self.windows = {t.name: ReportWindow(Keys(t.k0), t.last_n, t.seen) for t in self.store.trackers()}

    def handle_raw(self, raw: bytes, rx_time: float | None = None) -> Received | None:
        """`rx_time`: when the packet was heard (default now). Replies only to a fresh report."""
        try:
            pkt = Packet.from_bytes(raw)
        except PacketError:
            return None
        p = report_payload(pkt)
        if p is None:
            return None
        for name, win in self.windows.items():
            prev_last = win.last_n
            hit = win.open(p)
            if hit is None:
                continue
            n, j, rep = hit
            return self._on_report(name, win, n, j, rep, pkt, late=n <= prev_last,
                                   rx_time=self.clock() if rx_time is None else rx_time)
        return None

    def _on_report(self, name: str, win: ReportWindow, n: int, j: int, rep: Report, pkt: Packet, late: bool,
                   rx_time: float) -> Received:
        self.store.set_window(name, win.last_n, win.seen)
        self.store.add_position(name, n, j, rep, len(pkt.path), "".join(h.hex() for h in pkt.path), rx_time)
        age = rx_time - rep.fix_time if rep.fix_time else float("nan")
        log.info("%s n=%d j=%d%s fix=%s %.6f,%.6f batt=%s mode=%s hops=%d age=%.0fs nb=%d", name, n, j,
                 " (late)" if late else "", rep.has_fix, rep.lat, rep.lon, rep.battery_pct, rep.mode.name,
                 len(pkt.path), age, rep.nb_total)
        if self.store.ack_commands(name, n, rep):
            log.info("%s acknowledged command seq&0xF=%d", name, rep.cmd_seq)

        if self.traccar_url and rep.has_fix and not late:
            t = self.store.get_tracker(name)
            traccar.send(self.traccar_url, t.traccar_id or name, rep)

        sent = None
        pending = self.store.pending_command(name, self.clock())
        # Only a new report heard just now opens the tracker's RX window; a late copy or a stale one does not.
        if pending and self.transport is not None and not late and self.clock() - rx_time <= FRESH_S:
            sent = pending.command
            pc = seal_command(win.keys.cmd(epoch(n)), n, sent)
            flood = _is_lost(rep, sent) and len(pkt.path) > 0  # heard directly: zero-hop reaches it anyway
            reply = command_packet(pc, pkt, flood=flood)
            self.transport.send(reply.to_bytes())
            self.store.mark_sent(pending.id, n, self.clock())
            how = "flood" if flood else "zero-hop" if not reply.path else f"direct {len(reply.path)} hops"
            log.info("%s -> command %s seq=%d %s (try %d)", name, sent.op.name, sent.seq, how, pending.sends + 1)
        return Received(name, n, j, rep, pkt, sent, late)

    def run(self) -> None:
        assert self.transport is not None
        for raw in self.transport.receive():
            self.handle_raw(raw)

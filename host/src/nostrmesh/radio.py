"""Radio adapters. Each one delivers channel datagrams and sends them: flood (`send`, optionally in a
region scope) and, where the adapter can (`supports_direct`), along a path (`send_direct`, an empty path
= zero-hop).

Every datagram says how it arrived (PROTOCOL.md 3.4, R36): `route_type` is one of flood, tflood (scoped
flood), direct, tdirect, or None when the adapter cannot tell. The hop count is known only for a flood
(the length of its path); a DIRECT packet that reached us has an empty path whether it was sent zero-hop
or came along a path, so its hop count is None. `heard_direct` is a DIRECT arrival or a flood with 0 hops.

- CompanionSerial: a MeshCore/ZephCore companion node on USB serial (frames '<'/'>' + LE16 length).
  The node decrypts the channels; the bridge sees only channels the node has. A received frame gives the
  hop count of a flood (path length byte) or 0xFF for a DIRECT packet (checked against ZephCore
  `onChannelDataRecv`), never the path itself: zero-hop sends, no path sends, no routed ACKs.
- KissRadio: a node in KISS modem mode; the host wraps/unwraps GRP_DATA itself and sees the path and the
  transport code.
- LocalApiRadio (localapi.py): MeshCore Open / meshcored Local API; paths when the server advertises them.
- MemoryRadio: in-process, for tests and simulations.
"""
from __future__ import annotations

import hashlib
import logging
import queue
import struct
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass
from typing import Callable, Protocol

from . import grp

log = logging.getLogger("nostrmesh.radio")


ROUTE_TYPES = ("flood", "tflood", "direct", "tdirect")


@dataclass
class Datagram:
    channel: str
    data_type: int
    payload: bytes
    hops: int | None = None          # floods only; None for a DIRECT arrival (unknown) or when not reported
    snr: float | None = None
    path: list[bytes] | None = None  # flood path as heard (KISS, Local API rx_path): first hop first
    path_hash_size: int = 1
    rssi: float | None = None
    route_type: str | None = None    # flood | tflood | direct | tdirect when the adapter knows
    rx_time: float | None = None     # unix s the radio heard it, when known (replayed frames are old)
    stale: bool = False              # drained from the node's queue after a (re)connect: heard at an
                                     # unknown earlier time; published, but never triggers a command
    rx_time_min: float | None = None  # stale frame: heard no earlier than this (last queue drain), if known
    queued: bool = False             # the node service says it held the frame (Local API rx_queued)
    transport_code: int | None = None  # code[0] of a scoped flood (KISS, Local API rx_scope)
    scope: str | None = None         # the scope name the node resolved (Local API rx_scope)
    meta: dict | None = None         # extra receive fields for the field log

    @property
    def route(self) -> str | None:
        """How it arrived: the adapter's route type, else "flood" when it reported a hop count."""
        if self.route_type in ROUTE_TYPES:
            return self.route_type
        return "flood" if self.hops is not None else None

    @property
    def is_direct(self) -> bool:
        return self.route in ("direct", "tdirect")

    @property
    def heard_direct(self) -> bool:
        """A DIRECT arrival, or a flood with 0 hops (PROTOCOL.md 3.4)."""
        return self.is_direct or (self.route in ("flood", "tflood") and self.hops == 0)


class RadioError(RuntimeError):
    """A send or request the radio did not take. `code` says why when known: `not_connected`, `timeout`,
    `budget`, `unsupported`, a node service's error code; derived from a "code: message" text otherwise."""

    def __init__(self, msg: str = "", code: str = ""):
        super().__init__(msg)
        head = msg.split(":", 1)[0] if ":" in msg else ""
        self.code = code or (head if head.replace("_", "").isalpha() and head.islower() else "")


class Radio(Protocol):
    max_payload: int
    supports_direct: bool  # send_direct works (path given, or empty = zero-hop)
    supports_path: bool    # send_direct along a non-empty path works and received floods carry their path

    def start(self, on_rx: Callable[[Datagram], None]) -> None: ...
    def send(self, channel: str, data_type: int, payload: bytes, region: str | None = None) -> None: ...
    def send_direct(self, channel: str, data_type: int, payload: bytes, route: list[bytes],
                    path_hash_size: int = 1) -> None: ...
    def close(self) -> None: ...


# ---------------------------------------------------------------- companion protocol

CMD_APP_START = 0x01
CMD_GET_DEVICE_TIME = 0x05
CMD_SET_DEVICE_TIME = 0x06
CMD_REBOOT = 0x13
CMD_SYNC_NEXT_MESSAGE = 0x0A
CMD_GET_CHANNEL = 0x1F
CMD_SET_CHANNEL = 0x20
CMD_SEND_CHANNEL_DATA = 0x3E
RESP_OK, RESP_ERR, RESP_SELF_INFO, RESP_NO_MORE = 0x00, 0x01, 0x05, 0x0A
RESP_CHANNEL_INFO, RESP_CHANNEL_DATA = 0x12, 0x1B
PUSH_MSG_WAITING = 0x83
OUT_PATH_UNKNOWN = 0xFF
MAX_CHANNELS_PROBE = 40


def companion_frame(payload: bytes) -> bytes:
    return b"<" + struct.pack("<H", len(payload)) + payload


class CompanionDecoder:
    def __init__(self):
        self._buf = bytearray()

    def feed(self, chunk: bytes) -> list[bytes]:
        self._buf += chunk
        out = []
        while True:
            start = self._buf.find(b">")
            if start < 0:
                self._buf.clear()
                return out
            del self._buf[:start]
            if len(self._buf) < 3:
                return out
            ln = struct.unpack_from("<H", self._buf, 1)[0]
            if ln == 0 or ln > 512:
                del self._buf[:1]
                continue
            if len(self._buf) < 3 + ln:
                return out
            out.append(bytes(self._buf[3:3 + ln]))
            del self._buf[:3 + ln]


def parse_channel_data(frame: bytes) -> tuple[int, int, int, bytes, float, int] | None:
    """PACKET_CHANNEL_DATA_RECV -> (channel_idx, hops|-1, data_type, data, snr, hash size). The path length
    byte is the flood's (hash size bits and count) or 0xFF for a packet that was not a flood (ZephCore
    `onChannelDataRecv`: `pkt->isRouteFlood() ? pkt->path_len : OUT_PATH_UNKNOWN`): a DIRECT arrival, -1."""
    if len(frame) < 9 or frame[0] != RESP_CHANNEL_DATA:
        return None
    snr = struct.unpack_from("b", frame, 1)[0] / 4
    idx, path_len = frame[4], frame[5]
    dt = frame[6] | frame[7] << 8
    ln = frame[8]
    data = frame[9:9 + ln]
    if len(data) != ln:
        return None
    if path_len == OUT_PATH_UNKNOWN:
        return idx, -1, dt, data, snr, 1
    return idx, path_len & 0x3F, dt, data, snr, (path_len >> 6) + 1


class CompanionSerial:
    """Companion node over USB. Use a node dedicated to the bridge: draining the message
    queue here also consumes chat messages meant for a phone.

    A read or write error, or `max_timeouts` unanswered requests in a row, marks the link broken;
    the sync thread then reopens the port and repeats the handshake with a growing pause
    (`reopen_backoff_s`). The first drain after every (re)connect delivers frames the node queued
    while nobody listened: they are marked `stale` (heard at an unknown time, no earlier than the
    last complete drain).

    Sends: a flood (path length 0xFF) in the node's own flood scope, or zero-hop (path length with
    count 0), which ZephCore's `CMD_SEND_CHANNEL_DATA` sends with `sendDirect`. A path send works on the
    wire too, but received frames never carry a path, so the bridge has none to use: no routed ACKs."""

    max_payload = 165
    supports_direct = True   # zero-hop (and path) sends through CMD_SEND_CHANNEL_DATA's path argument
    supports_path = False    # PACKET_CHANNEL_DATA_RECV gives the hop count of a flood, never its path
    reopen_backoff_s = (1, 2, 5, 10, 30, 60)
    max_timeouts = 3

    def __init__(self, port: str, channels: list[str], baud: int = 115200, add_channels: bool = False,
                 poll_s: float = 20.0, ser=None, ser_factory=None):
        self.port, self.baud = port, baud
        self.channels = channels
        self.add_channels = add_channels
        self.poll_s = poll_s
        self._ser = ser
        self._ser_factory = ser_factory
        self._broken = False
        self._ready = False
        self._stale_drain = True
        self._timeouts = 0
        self.last_activity = 0.0     # unix s of the last frame from the node
        self.last_drained: float | None = None  # unix s the node's queue was last seen empty
        self.reconnects = 0
        self._dec = CompanionDecoder()
        self._resp: queue.Queue[bytes] = queue.Queue()
        self._req_lock = threading.Lock()
        self._wake = threading.Event()
        self._stop = threading.Event()
        self.idx_by_name: dict[str, int] = {}
        self.name_by_idx: dict[int, str] = {}
        self._on_rx: Callable[[Datagram], None] | None = None

    # -- radio health (radio_health.py): the node is the radio, so its own answers count
    @property
    def radio_connected(self) -> bool:
        return self._ready and not self._broken

    def probe(self) -> dict:
        """One request the node must answer (GET_DEVICE_TIME); raises RadioError when it does not."""
        r = self.request(bytes([CMD_GET_DEVICE_TIME]), timeout=5.0)
        return {"connected": True, "answer": r[0]}

    def reboot(self) -> str:
        """CMD_REBOOT while the node still answers (ZephCore: `0x13 "reboot"`, OK before it resets)."""
        r = self.request(bytes([CMD_REBOOT]) + b"reboot", timeout=5.0)
        self._mark_broken("rebooted by the bridge")
        return "ok" if r[0] == RESP_OK else f"refused {r[0]:#04x}"

    def reopen(self) -> str:
        self._mark_broken("reopen requested")
        return "requested"

    def hard_reset(self) -> str:
        """RTS and DTR pulse on the open port (the ESP32 and nRF52 USB bridges reset on it)."""
        ser = self._ser
        if ser is None or not hasattr(ser, "rts"):
            return "unsupported"
        try:
            ser.dtr = False
            ser.rts = True
            time.sleep(0.1)
            ser.rts = False
        except Exception as e:  # noqa: BLE001
            return f"failed: {e}"
        self._mark_broken("hard reset")
        return "ok"

    # -- low level
    def _open(self, reopen: bool = False):
        if self._ser_factory is not None and (reopen or self._ser is None):
            self._ser = self._ser_factory()
        elif self._ser is None or reopen:
            import serial

            self._ser = serial.Serial(self.port, self.baud, timeout=0.1)
        self._dec = CompanionDecoder()

    def _mark_broken(self, why):
        if not self._broken:
            log.error("companion link lost: %s", why)
        self._broken = True
        self._wake.set()

    def _reader(self):
        while not self._stop.is_set():
            if self._broken:
                time.sleep(0.05)
                continue
            try:
                chunk = self._ser.read(256)
                for fr in self._dec.feed(chunk or b""):
                    self.last_activity = time.time()
                    if fr[0] >= 0x80:
                        if fr[0] == PUSH_MSG_WAITING:
                            self._wake.set()
                    else:
                        self._resp.put(fr)
            except Exception as e:  # noqa: BLE001  (pyserial raises SerialException / OSError on unplug)
                if self._stop.is_set():
                    return
                self._mark_broken(f"read: {e}")

    def request(self, payload: bytes, timeout: float = 5.0) -> bytes:
        with self._req_lock:
            if self._broken:
                raise RadioError("not_connected: companion link is down")
            while not self._resp.empty():
                self._resp.get_nowait()
            try:
                self._ser.write(companion_frame(payload))
            except Exception as e:  # noqa: BLE001
                self._mark_broken(f"write: {e}")
                raise RadioError(f"not_connected: serial write failed: {e}") from None
            try:
                r = self._resp.get(timeout=timeout)
            except queue.Empty:
                self._timeouts += 1
                if self._timeouts >= self.max_timeouts:
                    self._mark_broken(f"{self._timeouts} requests unanswered")
                raise RadioError(f"timeout: no response to command {payload[0]:#04x}") from None
            self._timeouts = 0
            return r

    # -- setup
    def _handshake(self):
        r = self.request(bytes([CMD_APP_START, 3]) + bytes(6) + b"nostrmesh")
        if r[0] != RESP_SELF_INFO:
            raise RadioError(f"APP_START answered {r[0]:#04x}")
        self.request(bytes([CMD_SET_DEVICE_TIME]) + struct.pack("<I", int(time.time())))
        slots = {}
        for i in range(MAX_CHANNELS_PROBE):
            r = self.request(bytes([CMD_GET_CHANNEL, i]))
            if r[0] != RESP_CHANNEL_INFO or len(r) < 50:
                break
            name = r[2:34].split(b"\x00")[0].decode(errors="replace")
            slots[i] = (name, r[34:50])
        for i, (name, secret) in slots.items():
            if name.startswith("#") and secret == grp.channel_key(name):
                self.idx_by_name[name] = i
        for ch in self.channels:
            if ch in self.idx_by_name:
                continue
            free = next((i for i, (n, s) in slots.items() if not n and not any(s)), None)
            if not self.add_channels or free is None:
                raise RadioError(f"node has no {ch} channel (add it in the app, or run with --add-channels)")
            body = ch.encode().ljust(32, b"\x00")
            r = self.request(bytes([CMD_SET_CHANNEL, free]) + body + grp.channel_key(ch))
            if r[0] != RESP_OK:
                raise RadioError(f"could not add {ch} to slot {free}")
            slots[free] = (ch, grp.channel_key(ch))
            self.idx_by_name[ch] = free
            log.info("added %s to channel slot %d", ch, free)
        self.name_by_idx = {i: n for n, i in self.idx_by_name.items()}
        self._ready = True
        log.info("companion ready, channels %s", self.idx_by_name)

    def _reconnect(self) -> bool:
        """Reopen the port and repeat the handshake; True when the link is back."""
        self._ready = False
        try:
            try:
                if self._ser is not None:
                    self._ser.close()
            except Exception:  # noqa: BLE001
                pass
            self._open(reopen=True)
            self._broken = False
            self._timeouts = 0
            self._handshake()
        except Exception as e:  # noqa: BLE001
            self._broken = True
            log.warning("companion reopen failed: %s", e)
            return False
        self.reconnects += 1
        self._stale_drain = True  # what the node queued meanwhile was heard at an unknown time
        log.info("companion link back (reconnect %d)", self.reconnects)
        return True

    def _syncer(self):
        backoff = 0
        while not self._stop.is_set():
            if self._broken:
                delay = self.reopen_backoff_s[min(backoff, len(self.reopen_backoff_s) - 1)]
                if self._stop.wait(delay):
                    return
                backoff = 0 if self._reconnect() else backoff + 1
                continue
            self._wake.wait(self.poll_s)
            self._wake.clear()
            if self._broken or self._stop.is_set():
                continue
            try:
                self._drain()
            except RadioError as e:
                log.warning("sync: %s", e)
            except Exception:  # noqa: BLE001  (never let the sync thread die)
                log.exception("sync failed")

    def _drain(self):
        stale = self._stale_drain
        for _ in range(256):
            r = self.request(bytes([CMD_SYNC_NEXT_MESSAGE]))
            if r[0] == RESP_NO_MORE:
                self._stale_drain = False
                self.last_drained = time.time()
                return
            cd = parse_channel_data(r)
            if cd is None:
                continue  # chat messages and other queued items: not ours
            idx, hops, dt, data, snr, hs = cd
            name = self.name_by_idx.get(idx)
            if name and self._on_rx:
                # queued: no rx_time; the first drain after a (re)connect is stale
                if hops < 0:
                    dg = Datagram(name, dt, data, None, snr, path=[], route_type="direct", stale=stale,
                                  rx_time_min=self.last_drained if stale else None)
                else:
                    dg = Datagram(name, dt, data, hops, snr, path_hash_size=hs, route_type="flood", stale=stale,
                                  rx_time_min=self.last_drained if stale else None)
                self._on_rx(dg)

    # -- Radio
    def start(self, on_rx):
        self._on_rx = on_rx
        self._open()
        threading.Thread(target=self._reader, daemon=True, name="companion-rx").start()
        self._handshake()
        threading.Thread(target=self._syncer, daemon=True, name="companion-sync").start()
        self._wake.set()

    def _send_frame(self, channel: str, data_type: int, payload: bytes, path_len: int, path: bytes = b"") -> None:
        if len(payload) > self.max_payload:
            raise RadioError("payload too long")
        idx = self.idx_by_name.get(channel)
        if idx is None:
            raise RadioError(f"no channel {channel}")
        r = self.request(bytes([CMD_SEND_CHANNEL_DATA, idx, path_len]) + path + struct.pack("<H", data_type) + payload)
        if r[0] != RESP_OK:
            raise RadioError(f"send refused (code {r[1] if len(r) > 1 else '?'})")

    def send(self, channel: str, data_type: int, payload: bytes, region: str | None = None) -> None:
        """A flood in the node's own flood scope (the companion protocol has no scope per send)."""
        self._send_frame(channel, data_type, payload, OUT_PATH_UNKNOWN)

    def send_direct(self, channel: str, data_type: int, payload: bytes, route: list[bytes],
                    path_hash_size: int = 1) -> None:
        """DIRECT along `route` (next hop first); an empty route is zero-hop (path length = hash size
        bits with count 0, no path bytes)."""
        if path_hash_size not in (1, 2, 3) or any(len(h) != path_hash_size for h in route) or len(route) > 63:
            raise RadioError("bad path")
        self._send_frame(channel, data_type, payload, (path_hash_size - 1) << 6 | len(route), b"".join(route))

    def close(self):
        self._stop.set()
        self._wake.set()
        if self._ser:
            self._ser.close()


# ---------------------------------------------------------------- KISS


class KissRadio:
    """Node in KISS modem mode. Sees raw packets: unwraps GRP_DATA for the configured channels, keeps the
    route type (a DIRECT packet with path entries left is on its way to someone else and is skipped),
    the flood path and the transport code of a scoped flood. Floods in a region go out as
    TRANSPORT_FLOOD with code[0] for that scope (PROTOCOL.md 2.7)."""

    max_payload = 165
    supports_direct = True
    supports_path = True

    reopen_backoff_s = (1, 2, 5, 10, 30, 60)

    def __init__(self, port: str, channels: list[str], baud: int = 115200, link=None, link_factory=None):
        from libretag.transport import KissSerial

        self.keys = {ch: grp.channel_key(ch) for ch in channels}
        if link is None and link_factory is None:
            link_factory = lambda: KissSerial(port, baud)  # noqa: E731
        self._link_factory = link_factory
        self._link = link or link_factory()
        self.last_activity = 0.0
        self.reconnects = 0
        self._seen: OrderedDict[bytes, None] = OrderedDict()
        self._stop = threading.Event()
        self._link_ok = True

    @property
    def radio_connected(self) -> bool:
        return self._link_ok

    def reopen(self) -> str:
        self._reopen()
        return "ok" if self._link_ok else "failed"

    def handle_raw(self, raw: bytes) -> Datagram | None:
        from libretag.meshcore import (PAYLOAD_GRP_DATA, ROUTE_TRANSPORT_DIRECT, ROUTE_TRANSPORT_FLOOD, Packet,
                                       PacketError)

        try:
            pkt = Packet.from_bytes(raw)
        except PacketError:
            return None
        if pkt.payload_type != PAYLOAD_GRP_DATA:
            return None
        if not pkt.is_flood and pkt.path:
            return None  # DIRECT, still on its way along a path: not for us (yet)
        h = hashlib.sha256(pkt.payload).digest()[:8]
        if h in self._seen:
            return None
        self._seen[h] = None
        if len(self._seen) > 512:
            self._seen.popitem(last=False)
        tc = struct.unpack_from("<H", pkt.transport_codes)[0] if len(pkt.transport_codes) == 4 else None
        for ch, key in self.keys.items():
            got = grp.unwrap(key, pkt.payload)
            if got:
                if pkt.is_flood:
                    rt = "tflood" if pkt.route_type == ROUTE_TRANSPORT_FLOOD else "flood"
                    return Datagram(ch, got[0], got[1], len(pkt.path), path=list(pkt.path),
                                    path_hash_size=pkt.path_hash_size, route_type=rt, rx_time=time.time(),
                                    transport_code=tc)
                rt = "tdirect" if pkt.route_type == ROUTE_TRANSPORT_DIRECT else "direct"
                return Datagram(ch, got[0], got[1], None, path=[], path_hash_size=pkt.path_hash_size,
                                route_type=rt, rx_time=time.time())
        return None

    def start(self, on_rx):
        def loop():
            backoff = 0
            while not self._stop.is_set():
                try:
                    for raw in self._link.receive(timeout=1.0):
                        self.last_activity = time.time()
                        dg = self.handle_raw(raw)
                        if dg:
                            on_rx(dg)
                    backoff = 0
                    self._link_ok = True
                except Exception as e:  # noqa: BLE001  (unplugged port, a bad frame: never let the thread die)
                    if self._stop.is_set():
                        return
                    self._link_ok = False
                    delay = self.reopen_backoff_s[min(backoff, len(self.reopen_backoff_s) - 1)]
                    log.error("KISS link: %s; reopening in %d s", e, delay)
                    backoff += 1
                    if self._stop.wait(delay):
                        return
                    self._reopen()

        threading.Thread(target=loop, daemon=True, name="kiss-rx").start()

    def _reopen(self):
        if self._link_factory is None:
            return  # an injected link (tests): nothing to reopen
        try:
            close = getattr(getattr(self._link, "_ser", None), "close", None)
            if close:
                close()
            self._link = self._link_factory()
            self.reconnects += 1
        except Exception as e:  # noqa: BLE001
            log.warning("KISS reopen failed: %s", e)

    def packet(self, channel: str, data_type: int, payload: bytes, route: list[bytes] | None = None,
               path_hash_size: int = 1, region: str | None = None) -> bytes:
        """Flood (in `region` when given), or direct along `route` (next hop first) when given."""
        from libretag.meshcore import PAYLOAD_GRP_DATA, ROUTE_DIRECT, ROUTE_FLOOD, Packet
        from libretag.quietmesh import scoped_flood

        wrapped = grp.wrap(self.keys[channel], data_type, payload)
        self._seen[hashlib.sha256(wrapped).digest()[:8]] = None  # don't hear our own packet back
        if route is None:
            if region:
                return scoped_flood(wrapped, region, 1).to_bytes()
            return Packet(ROUTE_FLOOD, PAYLOAD_GRP_DATA, wrapped).to_bytes()
        return Packet(ROUTE_DIRECT, PAYLOAD_GRP_DATA, wrapped, path=list(route),
                      path_hash_size=path_hash_size).to_bytes()

    def send_direct(self, channel: str, data_type: int, payload: bytes, route: list[bytes],
                    path_hash_size: int = 1) -> None:
        """Direct-routed GRP_DATA: repeaters forward direct packets whatever their type, so only
        the repeaters on `route` transmit instead of the whole flood area. Empty route = zero-hop."""
        if len(payload) > self.max_payload:
            raise RadioError("payload too long")
        if channel not in self.keys:
            raise RadioError(f"no channel {channel}")
        self._link.send(self.packet(channel, data_type, payload, route, path_hash_size))

    def send(self, channel: str, data_type: int, payload: bytes, region: str | None = None) -> None:
        if len(payload) > self.max_payload:
            raise RadioError("payload too long")
        if channel not in self.keys:
            raise RadioError(f"no channel {channel}")
        self._link.send(self.packet(channel, data_type, payload, region=region))

    def close(self):
        self._stop.set()


# ---------------------------------------------------------------- in memory


class Ether:
    """Shared air for MemoryRadios: a send reaches every other radio."""

    def __init__(self):
        self.radios: list[MemoryRadio] = []
        self.log: list[Datagram] = []


class MemoryRadio:
    max_payload = 165
    supports_direct = False
    supports_path = False

    def __init__(self, ether: Ether | None = None, channels: list[str] | None = None):
        self.ether = ether
        self.channels = channels
        self.sent: list[Datagram] = []
        self._on_rx = None
        if ether:
            ether.radios.append(self)

    def start(self, on_rx):
        self._on_rx = on_rx

    def inject(self, dg: Datagram):
        if self._on_rx and (self.channels is None or dg.channel in self.channels):
            self._on_rx(dg)

    def send(self, channel: str, data_type: int, payload: bytes, region: str | None = None) -> None:
        if len(payload) > self.max_payload:
            raise RadioError("payload too long")
        dg = Datagram(channel, data_type, payload, 0, route_type="flood", scope=region)
        self.sent.append(dg)
        self._air(dg)

    def _air(self, dg: Datagram):
        if self.ether:
            self.ether.log.append(dg)
            for r in self.ether.radios:
                if r is not self:
                    r.inject(dg)

    def close(self):
        pass


class MemoryDirectRadio(MemoryRadio):
    """MemoryRadio that also takes direct sends (like KissRadio); records the route used."""

    supports_direct = True
    supports_path = True

    def send_direct(self, channel: str, data_type: int, payload: bytes, route: list[bytes],
                    path_hash_size: int = 1) -> None:
        if len(payload) > self.max_payload:
            raise RadioError("payload too long")
        dg = Datagram(channel, data_type, payload, None, path=list(route), path_hash_size=path_hash_size,
                      route_type="direct")
        self.sent.append(dg)
        self._air(dg)

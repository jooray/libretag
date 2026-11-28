"""The tracker's USB console from a laptop (PROTOCOL.md 6): status, the unlock, commands.

A locked tracker answers only `help`, `status`, `version`, `selftest`, `challenge`, `auth`, `unpair`
and `buzz`. With the tag's usb_key (the owner's K0, or a full-access share stored by
`libretag share import`) `unlock` runs the challenge and the console takes settings for this
connection (10 minutes at most; closing the port ends it).
"""
from __future__ import annotations

import re
import time
from dataclasses import dataclass

from . import lt2

ID = re.compile(r"LIBRETAG ID ([0-9a-fA-F]{16})")
CHALLENGE = re.compile(r"CHALLENGE ([0-9a-fA-F]{32})")
LOCKED = re.compile(r"(^|\s)locked\b")

# USB vendor ids of the tags' consoles: Adafruit nRF52 bootloader / Seeed / Silicon Labs CP210x (Heltec)
TAG_VIDS = {0x239A, 0x2886, 0x10C4}


@dataclass
class Status:
    lines: list[str]
    id: str | None
    locked: bool


def parse_status(lines: list[str]) -> Status:
    tid = next((m.group(1).lower() for l in lines if (m := ID.search(l))), None)
    return Status(lines, tid, any(LOCKED.search(l.strip()) for l in lines))


class Console:
    """Line console over anything with read(n) / write(b) (pyserial or a test double)."""

    def __init__(self, port, quiet_s: float = 0.4):
        self.port = port
        self.quiet_s = quiet_s

    def command(self, cmd: str, quiet_s: float | None = None, max_s: float = 3.0) -> list[str]:
        quiet = self.quiet_s if quiet_s is None else quiet_s
        self._drain()
        self.port.write((cmd + "\r").encode())
        buf, start, last = b"", time.monotonic(), None
        while time.monotonic() - start < max_s:
            b = self.port.read(256)
            if b:
                buf += b
                last = time.monotonic()
            elif last is not None and time.monotonic() - last >= quiet:
                break
            else:
                time.sleep(0.02)
        lines = [l.strip("\r ") for l in buf.decode(errors="replace").split("\n")]
        lines = [l for l in lines if l]
        if lines and lines[0] == cmd:  # the USB console echoes the line (Bluetooth does not)
            lines = lines[1:]
        return lines

    def command_until(self, cmd: str, end_prefix: str, max_s: float = 600.0, idle_s: float = 10.0) -> list[str]:
        """A long answer (fieldlog dump): lines until one starts with end_prefix, or idle_s of silence."""
        self._drain()
        self.port.write((cmd + "\r").encode())
        buf, lines, start, last = b"", [], time.monotonic(), time.monotonic()
        while time.monotonic() - start < max_s and time.monotonic() - last < idle_s:
            b = self.port.read(4096)
            if not b:
                time.sleep(0.01)
                continue
            last = time.monotonic()
            buf += b
            *done, buf = buf.split(b"\n")
            for l in done:
                l = l.decode(errors="replace").strip("\r ")
                if l and l != cmd:
                    lines.append(l)
                    if l.startswith(end_prefix):
                        return lines
        raise TimeoutError(f"{cmd}: no {end_prefix!r} line ({len(lines)} lines read)")

    def _drain(self) -> None:
        while self.port.read(256):
            pass

    def status(self) -> Status:
        return parse_status(self.command("status"))

    def unlock(self, usb_key: bytes) -> bool:
        ch = next((m.group(1) for l in self.command("challenge") if (m := CHALLENGE.search(l))), None)
        if ch is None:
            return False
        resp = lt2.usb_auth(usb_key, bytes.fromhex(ch))
        return any(l.startswith("OK unlocked") for l in self.command("auth " + resp.hex()))


def find_port() -> str:
    """The one serial port that looks like a tag."""
    from serial.tools import list_ports
    ports = [p for p in list_ports.comports() if p.vid in TAG_VIDS]
    if len(ports) == 1:
        return ports[0].device
    if not ports:
        raise LookupError("no tag on a USB port (plug it in, or pass --port)")
    raise LookupError("several tags on USB: " + ", ".join(p.device for p in ports) + "; pass --port")


def open_serial(path: str, esp: bool = False):
    """115200 8N1. The nRF console (CDC ACM) sends only to a host that raised DTR; on an ESP32's
    CP2102 DTR and RTS drive reset and boot, so with esp=True both stay low."""
    import serial
    s = serial.Serial()
    s.port, s.baudrate, s.timeout = path, 115200, 0.1
    s.dtr, s.rts = (False, False) if esp else (True, False)
    s.open()
    return s

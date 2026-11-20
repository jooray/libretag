"""Raw packet transports: KISS serial modem (MeshCore kiss_modem firmware) and hex lines on stdio."""

from __future__ import annotations

import sys
from typing import Iterator, Protocol

FEND, FESC, TFEND, TFESC = 0xC0, 0xDB, 0xDC, 0xDD
CMD_DATA = 0x00


def kiss_encode(data: bytes, port: int = 0, cmd: int = CMD_DATA) -> bytes:
    out = bytearray([FEND, ((port & 0x0F) << 4) | (cmd & 0x0F)])
    for b in data:
        if b == FEND:
            out += bytes([FESC, TFEND])
        elif b == FESC:
            out += bytes([FESC, TFESC])
        else:
            out.append(b)
    out.append(FEND)
    return bytes(out)


class KissDecoder:
    """Incremental KISS deframer. feed() returns complete (type, data) frames."""

    def __init__(self) -> None:
        self._buf = bytearray()
        self._in_frame = False
        self._esc = False

    def feed(self, chunk: bytes) -> list[tuple[int, bytes]]:
        frames = []
        for b in chunk:
            if b == FEND:
                if self._in_frame and self._buf:
                    frames.append((self._buf[0], bytes(self._buf[1:])))
                self._buf = bytearray()
                self._in_frame = True
                self._esc = False
                continue
            if not self._in_frame:
                continue
            if self._esc:
                self._buf.append(FEND if b == TFEND else FESC if b == TFESC else b)
                self._esc = False
            elif b == FESC:
                self._esc = True
            else:
                self._buf.append(b)
        return frames


class Transport(Protocol):
    def send(self, raw: bytes) -> None: ...
    def receive(self, timeout: float | None = None) -> Iterator[bytes]: ...


class KissSerial:
    def __init__(self, port: str, baud: int = 115200):
        import serial  # pyserial

        self._ser = serial.Serial(port, baud, timeout=0.2)
        self._dec = KissDecoder()

    def send(self, raw: bytes) -> None:
        self._ser.write(kiss_encode(raw))
        self._ser.flush()

    def receive(self, timeout: float | None = None) -> Iterator[bytes]:
        import time

        deadline = None if timeout is None else time.monotonic() + timeout
        while deadline is None or time.monotonic() < deadline:
            chunk = self._ser.read(256)
            if not chunk:
                continue
            for typ, data in self._dec.feed(chunk):
                if typ & 0x0F == CMD_DATA:
                    yield data


class HexStdio:
    """One packet per line as hex. Lets you pipe `libretag sim --hex | libretag base --hex`."""

    def __init__(self, inp=None, out=None):
        self._in = inp or sys.stdin
        self._out = out or sys.stdout

    def send(self, raw: bytes) -> None:
        print(raw.hex(), file=self._out, flush=True)

    def receive(self, timeout: float | None = None) -> Iterator[bytes]:
        for line in self._in:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                yield bytes.fromhex(line)
            except ValueError:
                continue

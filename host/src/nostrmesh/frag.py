"""Application-level fragmentation inside GRP_DATA (docs/nostr-mesh-bridge.md 3).

Fragment: 0x3E | msg_id (SHA-256(frame)[0:2]) | idx<<4 | n | data. idx 15 = XOR parity.
"""
from __future__ import annotations

import hashlib
import itertools
from collections import OrderedDict

from .errors import FrameError
from .frames import FRAG, MAX_RADIO

HEADER = 4
MAX_FRAGMENTS = 4
PARITY_IDX = 15


def msg_id(frame: bytes) -> bytes:
    return hashlib.sha256(frame).digest()[:2]


def _xor(chunks: list[bytes], size: int) -> bytes:
    out = bytearray(size)
    for c in chunks:
        for i, b in enumerate(c):
            out[i] ^= b
    return bytes(out)


def split(frame: bytes, max_len: int = MAX_RADIO, parity: bool = False) -> list[bytes]:
    room = max_len - HEADER
    n = -(-len(frame) // room)
    if n < 2:
        raise FrameError("BAD_LENGTH", "fits in one frame, do not fragment")
    if n > MAX_FRAGMENTS:
        raise FrameError("BAD_LENGTH", f"needs {n} fragments, limit {MAX_FRAGMENTS}")
    mid = msg_id(frame)
    chunks = [frame[i * room:(i + 1) * room] for i in range(n)]
    out = [bytes([FRAG]) + mid + bytes([(i << 4) | n]) + c for i, c in enumerate(chunks)]
    if parity:
        out.append(bytes([FRAG]) + mid + bytes([(PARITY_IDX << 4) | n]) + _xor(chunks, room))
    return out


class Reassembler:
    """Collects fragments; keeps at most 2 variants per (msg_id, idx) so one forged copy
    cannot kill a message. Bounded: `max_messages` in progress, `timeout_s` each."""

    def __init__(self, timeout_s: float = 60.0, max_messages: int = 32):
        self.timeout_s = timeout_s
        self.max_messages = max_messages
        self._msgs: OrderedDict[bytes, dict] = OrderedDict()

    def feed(self, frag: bytes, now: float) -> bytes | None:
        if len(frag) <= HEADER or frag[0] != FRAG:
            raise FrameError("BAD_LENGTH", "not a fragment")
        mid, idx, n = frag[1:3], frag[3] >> 4, frag[3] & 0x0F
        if not 2 <= n <= MAX_FRAGMENTS or (idx >= n and idx != PARITY_IDX):
            raise FrameError("BAD_LENGTH", "fragment index")
        self._expire(now)
        key = mid + bytes([n])
        m = self._msgs.get(key)
        if m is None:
            m = {"t0": now, "n": n, "parts": {}}
            self._msgs[key] = m
            while len(self._msgs) > self.max_messages:
                self._msgs.popitem(last=False)
        variants = m["parts"].setdefault(idx, [])
        data = frag[HEADER:]
        if data in variants:
            return None
        if len(variants) >= 2:
            return None
        variants.append(data)
        frame = self._try(mid, m)
        if frame is not None:
            del self._msgs[key]
        return frame

    def _try(self, mid: bytes, m: dict) -> bytes | None:
        n, parts = m["n"], m["parts"]
        have = [i for i in range(n) if i in parts]
        if len(have) == n:
            for combo in itertools.product(*(parts[i] for i in range(n))):
                frame = b"".join(combo)
                if msg_id(frame) == mid:
                    return frame
        if len(have) == n - 1 and PARITY_IDX in parts:
            missing = next(i for i in range(n) if i not in parts)
            for combo in itertools.product(*(parts[i] for i in have)):
                for par in parts[PARITY_IDX]:
                    rebuilt = _xor(list(combo) + [par], len(par))
                    seq = dict(zip(have, combo))
                    # the last fragment is the only short one; try trimming it when it is the missing one
                    # (only zero padding can be trimmed, the real chunk is a prefix of `rebuilt`)
                    cands = [rebuilt]
                    if missing == n - 1:
                        stripped = rebuilt.rstrip(b"\x00")
                        cands = [rebuilt[:k] for k in range(len(rebuilt), len(stripped) - 1, -1) if k > 0]
                    for c in cands:
                        seq[missing] = c
                        frame = b"".join(seq[i] for i in range(n))
                        if msg_id(frame) == mid:
                            return frame
        return None

    def _expire(self, now: float) -> None:
        for k in [k for k, m in self._msgs.items() if now - m["t0"] > self.timeout_s]:
            del self._msgs[k]

    def __len__(self) -> int:
        return len(self._msgs)

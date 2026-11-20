"""Nostr Event Frame: signed Nostr events in one MeshCore channel datagram (docs/nostr-mesh-bridge.md).

Frame byte 0 = version (bits 7-6, 0) | profile (bits 5-0). Integers big-endian. Every profile
rebuilds the event byte for byte; the encoder refuses anything that would not.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Callable, Iterable

from . import road
from .errors import FrameError
from .nostr import Event, is_lower_hex, nip04_content, nip04_parse

DATA_TYPE_NOSTR = 0xFF4E  # dev range; ask for an allocation next to 0x0150 once validated on air
DATA_TYPE_ROAD = 0x0150
CHANNEL_NOSTR = "#nostr"
CHANNEL_ROAD = "#roadstr"
CHANNEL_LIBRETAG = "#libretag"   # LT2 reports and commands (PROTOCOL.md 2-4), handled by bridge.py, not frames
DATA_TYPE_LIBRETAG = 0xFF4C

MAX_RADIO = 165       # MAX_GROUP_DATA_LENGTH, serial/KISS
MAX_COMPANION_BLE = 163  # BLE companion link and the MeshCore Open local API

# 0x04 was libretag.public (LT1 isomorphic report); retired with LT2, reserved, decodes as UNKNOWN_PROFILE.
GENERIC, NOTE, ROAD_REPORT, ROAD_VOTE = 0x00, 0x01, 0x02, 0x03
DM4_FULL, DM4_REF, FRAG = 0x10, 0x11, 0x3E
# dm4x: byte-identical to dm4, but the event carries a NIP-40 expiration at created_at + DM4X_TTL_S
# (implicit, 0 B on air). Used by the tracker's low-battery DM, which is useless after two weeks.
DM4X_FULL, DM4X_REF = 0x12, 0x13
DM4X_TTL_S = 14 * 86400
DM_PROFILES = (DM4_FULL, DM4_REF, DM4X_FULL, DM4X_REF)
REF_PROFILES = (DM4_REF, DM4X_REF)
PROFILE_NAMES = {
    GENERIC: "generic", NOTE: "note", ROAD_REPORT: "road.report", ROAD_VOTE: "road.vote",
    DM4_FULL: "dm4.full", DM4_REF: "dm4.ref", DM4X_FULL: "dm4x.full", DM4X_REF: "dm4x.ref", FRAG: "frag",
}
PROFILE_IDS = {v: k for k, v in PROFILE_NAMES.items()}
# Policy (uplink/downlink switches, max age) treats a dm4x frame like its dm4 twin.
POLICY_NAME = {**{v: v for v in PROFILE_NAMES.values()}, "dm4x.full": "dm4.full", "dm4x.ref": "dm4.ref"}

# A sleeping device announces "I am listening now" with an empty signed generic event of this
# (ephemeral) kind; bridges flush the DMs they hold for it and never publish the poll itself.
INBOX_POLL_KIND = 21694
MAX_GENERIC_TAGS = 15

# pk_ref (4 B) -> candidate full pubkeys (32 B). Several on a collision; the signature decides.
Resolver = Callable[[bytes], Iterable[bytes]]


@dataclass
class Decoded:
    profile: int
    event: Event
    data_type: int = DATA_TYPE_NOSTR

    @property
    def profile_name(self) -> str:
        return PROFILE_NAMES[self.profile]

    @property
    def policy_name(self) -> str:
        return POLICY_NAME[self.profile_name]


@dataclass
class Encoded:
    profile: int
    frame: bytes
    data_type: int = DATA_TYPE_NOSTR

    @property
    def channel(self) -> str:
        return CHANNEL_ROAD if self.data_type == DATA_TYPE_ROAD else CHANNEL_NOSTR


# ---------------------------------------------------------------- text rules (1.3 item 4)


def _text(b: bytes) -> str:
    try:
        s = b.decode("utf-8")
    except UnicodeDecodeError:
        raise FrameError("NON_CANONICAL", "not UTF-8") from None
    for c in s:
        o = ord(c)
        if (o < 0x20 and c not in "\n\t") or o == 0x7F:
            raise FrameError("NON_CANONICAL", "control character")
    return s


def _u32(b: bytes, off: int) -> int:
    return struct.unpack_from(">I", b, off)[0]


def _need(frame: bytes, n: int, exact: bool = False) -> None:
    if len(frame) < n or (exact and len(frame) != n):
        raise FrameError("BAD_LENGTH")


# ---------------------------------------------------------------- decode


MAX_REASSEMBLED = 4 * 161  # 4 fragments; only the DM profiles may be fragmented


def decode(frame: bytes, data_type: int = DATA_TYPE_NOSTR, resolve: Resolver | None = None,
           verify: bool = True, max_len: int = MAX_RADIO) -> Decoded:
    """Frame -> verified event. Raises FrameError with a stable code.

    `max_len` above MAX_RADIO is for reassembled fragments and admits only the DM profiles."""
    if data_type == DATA_TYPE_ROAD:
        ev = road.decode(frame)
        prof = ROAD_VOTE if ev.kind == road.KIND_VOTE else ROAD_REPORT
        return _finish(Decoded(prof, ev, DATA_TYPE_ROAD), verify)
    if data_type != DATA_TYPE_NOSTR:
        raise FrameError("UNKNOWN_PROFILE", f"data_type {data_type:#06x}")
    if not frame:
        raise FrameError("BAD_LENGTH")
    if len(frame) > max_len or (len(frame) > MAX_RADIO and (frame[0] & 0x3F) not in DM_PROFILES):
        raise FrameError("BAD_LENGTH")
    if frame[0] >> 6:
        raise FrameError("BAD_VERSION")
    prof = frame[0] & 0x3F

    if prof == NOTE:
        _need(frame, 102)
        m = frame[37]
        _need(frame, 102 + m, exact=True)
        ev = Event(frame[1:33].hex(), _u32(frame, 33), 1, [], _text(frame[38:38 + m]),
                   sig=frame[38 + m:].hex())
    elif prof == GENERIC:
        ev = _decode_generic(frame)
    elif prof in (ROAD_REPORT, ROAD_VOTE):
        flags = 0x08 if prof == ROAD_VOTE else 0x00
        ev = road.decode(bytes([flags]) + frame[1:])
    elif prof in (DM4_FULL, DM4X_FULL):
        _need(frame, 149 + 16)
        ln = len(frame) - 149
        if ln % 16:
            raise FrameError("BAD_LENGTH")
        ev = _dm(frame[1:33], _u32(frame, 33), frame[37:69], frame[69:85], frame[85:85 + ln], frame[85 + ln:],
                 prof == DM4X_FULL)
    elif prof in (DM4_REF, DM4X_REF):
        _need(frame, 93 + 16)
        ln = len(frame) - 93
        if ln % 16:
            raise FrameError("BAD_LENGTH")
        if resolve is None:
            raise FrameError("UNKNOWN_REF")
        senders, rcpts = list(resolve(frame[1:5])), list(resolve(frame[9:13]))
        if not senders or not rcpts:
            raise FrameError("UNKNOWN_REF")
        ca, iv, ct, sig = _u32(frame, 5), frame[13:29], frame[29:29 + ln], frame[29 + ln:]
        last = None
        for s in senders:
            for r in rcpts:
                ev = _dm(s, ca, r, iv, ct, sig, prof == DM4X_REF)
                last = Decoded(prof, ev)
                if not verify or ev.verify():
                    return last
        raise FrameError("SIGNATURE_INVALID")
    elif prof == FRAG:
        raise FrameError("FRAGMENT", "reassemble first")
    else:
        raise FrameError("UNKNOWN_PROFILE", f"profile {prof:#04x}")
    ev.id = ev.compute_id()
    return _finish(Decoded(prof, ev), verify)


def _finish(d: Decoded, verify: bool) -> Decoded:
    if verify and not d.event.verify():
        raise FrameError("SIGNATURE_INVALID")
    return d


def dm4_tags(rcpt_hex: str, created_at: int, expiring: bool) -> list[list[str]]:
    tags = [["p", rcpt_hex]]
    if expiring:
        tags.append(["expiration", str(created_at + DM4X_TTL_S)])
    return tags


def dm4_shape(ev: Event) -> bool | None:
    """None if the kind 4 event has no dm4 form; else whether it is the expiring (dm4x) shape."""
    t = ev.tags
    if not t or len(t[0]) != 2 or t[0][0] != "p" or not is_lower_hex(t[0][1], 32):
        return None
    if len(t) == 1:
        return False
    if len(t) == 2 and t[1] == ["expiration", str(ev.created_at + DM4X_TTL_S)]:
        return True
    return None


def _dm(sender: bytes, ca: int, rcpt: bytes, iv: bytes, ct: bytes, sig: bytes, expiring: bool = False) -> Event:
    ev = Event(sender.hex(), ca, 4, dm4_tags(rcpt.hex(), ca, expiring), nip04_content(ct, iv), sig=sig.hex())
    ev.id = ev.compute_id()
    return ev


# generic item types
IT_STR, IT_HEX32, IT_U32, IT_CHAR = 0x00, 0x01, 0x02, 0x04


def _decode_generic(frame: bytes) -> Event:
    _need(frame, 1 + 2 + 32 + 4 + 1 + 1 + 64)
    kind = struct.unpack_from(">H", frame, 1)[0]
    pk, ca = frame[3:35].hex(), _u32(frame, 35)
    ntags = frame[39]
    if ntags > MAX_GENERIC_TAGS:
        raise FrameError("BAD_LENGTH", "too many tags")
    i = 40
    end = len(frame) - 64
    tags = []
    try:
        for _ in range(ntags):
            nitems = frame[i]
            i += 1
            if nitems == 0:
                raise FrameError("NON_CANONICAL", "empty tag")
            tag = []
            for _ in range(nitems):
                t = frame[i]
                i += 1
                if t == IT_STR:
                    ln = frame[i]
                    s = _text(frame[i + 1:i + 1 + ln])
                    if i + 1 + ln > end:
                        raise FrameError("BAD_LENGTH")
                    tag.append(s)
                    i += 1 + ln
                elif t == IT_HEX32:
                    if i + 32 > end:
                        raise FrameError("BAD_LENGTH")
                    tag.append(frame[i:i + 32].hex())
                    i += 32
                elif t == IT_U32:
                    tag.append(str(_u32(frame, i)))
                    i += 4
                elif t == IT_CHAR:
                    c = frame[i]
                    if not 0x21 <= c <= 0x7E:
                        raise FrameError("NON_CANONICAL", "tag char")
                    tag.append(chr(c))
                    i += 1
                else:
                    raise FrameError("NON_CANONICAL", f"item type {t}")
            tags.append(tag)
        m = frame[i]
        if i + 1 + m != end:
            raise FrameError("BAD_LENGTH")
        content = _text(frame[i + 1:i + 1 + m])
    except (IndexError, struct.error):
        raise FrameError("BAD_LENGTH") from None
    return Event(pk, ca, kind, tags, content, sig=frame[end:].hex())


# ---------------------------------------------------------------- encode


def _check_event(ev: Event) -> None:
    ok = (isinstance(ev.tags, list) and all(isinstance(t, list) and all(isinstance(x, str) for x in t)
                                             for t in ev.tags)
          and isinstance(ev.content, str) and isinstance(ev.created_at, int) and isinstance(ev.kind, int)
          and is_lower_hex(ev.pubkey, 32) and is_lower_hex(ev.sig, 64))
    if not ok:
        raise FrameError("MALFORMED_EVENT")
    if not 0 <= ev.created_at < 2**32:
        raise FrameError("TIMESTAMP_RANGE")


def _item(s: str) -> bytes:
    if len(s) == 1 and 0x21 <= ord(s) <= 0x7E:
        return bytes([IT_CHAR, ord(s)])
    if is_lower_hex(s, 32):
        return bytes([IT_HEX32]) + bytes.fromhex(s)
    if s.isascii() and s.isdigit() and (s == "0" or s[0] != "0") and int(s) < 2**32:
        return bytes([IT_U32]) + struct.pack(">I", int(s))
    b = s.encode()
    _text(b)
    if len(b) > 255:
        raise FrameError("BAD_LENGTH", "tag item too long")
    return bytes([IT_STR, len(b)]) + b


def choose_profile(ev: Event, can_ref: Callable[[bytes], bool] | None = None) -> int:
    if ev.kind == road.KIND_REPORT:
        return ROAD_REPORT
    if ev.kind == road.KIND_VOTE:
        return ROAD_VOTE
    if ev.kind == 4:
        x = dm4_shape(ev)
        if x is not None and can_ref and can_ref(bytes.fromhex(ev.pubkey)) and can_ref(bytes.fromhex(ev.tags[0][1])):
            return DM4X_REF if x else DM4_REF
        return DM4X_FULL if x else DM4_FULL
    if ev.kind == 1 and not ev.tags:
        return NOTE
    return GENERIC


def encode(ev: Event, profile: int | None = None, can_ref: Callable[[bytes], bool] | None = None,
           max_len: int | None = MAX_RADIO) -> Encoded:
    """Event -> frame. Road events come back as the 0x0150 alias frame for #roadstr.

    `max_len=None` skips the size check (the caller fragments)."""
    if profile is None:
        profile = choose_profile(ev, can_ref)
    if profile in (ROAD_REPORT, ROAD_VOTE):
        frame = road.encode(ev)
        if max_len is not None and len(frame) > max_len:
            raise FrameError("BAD_LENGTH")
        return Encoded(profile, frame, DATA_TYPE_ROAD)
    _check_event(ev)
    pk, ca = bytes.fromhex(ev.pubkey), struct.pack(">I", ev.created_at)
    sig = bytes.fromhex(ev.sig)
    if profile == NOTE:
        if ev.kind != 1 or ev.tags:
            raise FrameError("WRONG_KIND")
        c = ev.content.encode()
        _text(c)
        if len(c) > 255:
            raise FrameError("BAD_LENGTH")
        frame = bytes([NOTE]) + pk + ca + bytes([len(c)]) + c + sig
    elif profile == GENERIC:
        if not 0 <= ev.kind <= 0xFFFF:
            raise FrameError("WRONG_KIND")
        if len(ev.tags) > MAX_GENERIC_TAGS or any(not t or len(t) > 255 for t in ev.tags):
            raise FrameError("BAD_LENGTH", "tags")
        body = b"".join(bytes([len(t)]) + b"".join(_item(x) for x in t) for t in ev.tags)
        c = ev.content.encode()
        _text(c)
        if len(c) > 255:
            raise FrameError("BAD_LENGTH")
        frame = bytes([GENERIC]) + struct.pack(">H", ev.kind) + pk + ca + bytes([len(ev.tags)]) + body \
            + bytes([len(c)]) + c + sig
    elif profile in DM_PROFILES:
        if ev.kind != 4:
            raise FrameError("WRONG_KIND")
        x = dm4_shape(ev)
        if x is None or x != (profile in (DM4X_FULL, DM4X_REF)):
            raise FrameError("NON_CANONICAL", "kind 4 needs one p tag (dm4) or p + expiration at +14 d (dm4x)")
        parsed = nip04_parse(ev.content)
        if parsed is None:
            raise FrameError("NON_CANONICAL_BASE64")
        ct, iv = parsed
        rcpt = bytes.fromhex(ev.tags[0][1])
        if profile in REF_PROFILES:
            frame = bytes([profile]) + pk[:4] + ca + rcpt[:4] + iv + ct + sig
        else:
            frame = bytes([profile]) + pk + ca + rcpt + iv + ct + sig
    else:
        raise FrameError("UNKNOWN_PROFILE")

    if (max_len is not None and len(frame) > max_len) or \
            (len(frame) > MAX_RADIO and profile not in DM_PROFILES):
        raise FrameError("BAD_LENGTH", f"{len(frame)} B")
    # Isomorphism check: the frame must rebuild exactly this event.
    resolver = None
    if profile in REF_PROFILES:
        known = {pk[:4]: [pk], rcpt[:4]: [rcpt]}
        if pk[:4] == rcpt[:4]:
            known[pk[:4]] = [pk, rcpt]
        resolver = lambda r: known.get(r, [])  # noqa: E731
    try:
        back = decode(frame, DATA_TYPE_NOSTR, resolver, verify=False, max_len=len(frame)).event
    except FrameError as e:
        raise FrameError("NON_CANONICAL", f"does not rebuild: {e.code}") from None
    if (back.pubkey, back.created_at, back.kind, back.tags, back.content) != \
            (ev.pubkey, ev.created_at, ev.kind, ev.tags, ev.content):
        raise FrameError("NON_CANONICAL", "does not rebuild")
    if not ev.verify():
        raise FrameError("SIGNATURE_INVALID")
    return Encoded(profile, frame)

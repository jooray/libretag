"""npub/nsec <-> hex (NIP-19, bech32)."""
from __future__ import annotations

CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def _polymod(values):
    gen = [0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3]
    chk = 1
    for v in values:
        b = chk >> 25
        chk = (chk & 0x1FFFFFF) << 5 ^ v
        for i in range(5):
            chk ^= gen[i] if (b >> i) & 1 else 0
    return chk


def _hrp_expand(hrp):
    return [ord(x) >> 5 for x in hrp] + [0] + [ord(x) & 31 for x in hrp]


def _convert(data, frm, to, pad=True):
    acc = bits = 0
    out = []
    maxv = (1 << to) - 1
    for v in data:
        acc = (acc << frm) | v
        bits += frm
        while bits >= to:
            bits -= to
            out.append((acc >> bits) & maxv)
    if pad and bits:
        out.append((acc << (to - bits)) & maxv)
    elif not pad and (bits >= frm or ((acc << (to - bits)) & maxv)):
        raise ValueError("bad padding")
    return out


def encode(hrp: str, data: bytes) -> str:
    d = _convert(data, 8, 5)
    chk = _polymod(_hrp_expand(hrp) + d + [0] * 6) ^ 1
    return hrp + "1" + "".join(CHARSET[x] for x in d + [(chk >> 5 * (5 - i)) & 31 for i in range(6)])


def decode(s: str) -> tuple[str, bytes]:
    s = s.lower()
    pos = s.rfind("1")
    hrp, data = s[:pos], [CHARSET.index(c) for c in s[pos + 1:]]
    if _polymod(_hrp_expand(hrp) + data) != 1:
        raise ValueError("bad bech32 checksum")
    return hrp, bytes(_convert(data[:-6], 5, 8, pad=False))


def to_hex(key: str) -> str:
    """Accepts hex, npub1… or nsec1…; returns 64-char lowercase hex."""
    key = key.strip()
    if key.startswith(("npub1", "nsec1")):
        _, b = decode(key)
        return b.hex()
    b = bytes.fromhex(key)
    if len(b) != 32:
        raise ValueError("key must be 32 bytes")
    return b.hex()

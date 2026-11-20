"""NIP-01 events (serialization, id, BIP-340) and NIP-04 content encryption."""
from __future__ import annotations

import base64
import hashlib
import json
import os
from dataclasses import dataclass, field

from . import secp


@dataclass
class Event:
    pubkey: str
    created_at: int
    kind: int
    tags: list[list[str]] = field(default_factory=list)
    content: str = ""
    id: str = ""
    sig: str = ""

    def serialize(self) -> bytes:
        return serialize(self.pubkey, self.created_at, self.kind, self.tags, self.content)

    def compute_id(self) -> str:
        return hashlib.sha256(self.serialize()).hexdigest()

    def verify(self) -> bool:
        """True when the id matches the content and the signature is valid."""
        try:
            if self.compute_id() != self.id:
                return False
            return secp.verify(bytes.fromhex(self.id), bytes.fromhex(self.pubkey), bytes.fromhex(self.sig))
        except ValueError:
            return False

    def to_dict(self) -> dict:
        return {"id": self.id, "pubkey": self.pubkey, "created_at": self.created_at, "kind": self.kind,
                "tags": self.tags, "content": self.content, "sig": self.sig}

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), separators=(",", ":"), ensure_ascii=False)

    @classmethod
    def from_dict(cls, d: dict) -> "Event":
        return cls(pubkey=d["pubkey"], created_at=d["created_at"], kind=d["kind"], tags=d["tags"],
                   content=d["content"], id=d.get("id", ""), sig=d.get("sig", ""))


def serialize(pubkey: str, created_at: int, kind: int, tags, content: str) -> bytes:
    # json.dumps escapes exactly what NIP-01 escapes for the characters our profiles allow
    # (\n \t \" \\); profiles reject every other control character before we get here.
    return json.dumps([0, pubkey, created_at, kind, tags, content], separators=(",", ":"),
                      ensure_ascii=False).encode()


def sign_event(seckey: bytes, created_at: int, kind: int, tags, content: str,
               aux_rand: bytes = bytes(32)) -> Event:
    ev = Event(secp.pubkey(seckey).hex(), created_at, kind, tags, content)
    ev.id = ev.compute_id()
    ev.sig = secp.sign(bytes.fromhex(ev.id), seckey, aux_rand).hex()
    return ev


def is_lower_hex(s: str, nbytes: int) -> bool:
    return len(s) == 2 * nbytes and all(c in "0123456789abcdef" for c in s)


# ---------------------------------------------------------------- NIP-04


def nip04_key(seckey: bytes, peer_pubkey: bytes) -> bytes:
    return secp.ecdh_x(seckey, peer_pubkey)


def _cbc(key: bytes, iv: bytes, data: bytes, decrypt: bool) -> bytes:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

    c = Cipher(algorithms.AES(key), modes.CBC(iv))
    op = c.decryptor() if decrypt else c.encryptor()
    return op.update(data) + op.finalize()


def nip04_encrypt_raw(shared: bytes, plaintext: str, iv: bytes | None = None) -> tuple[bytes, bytes]:
    """Returns (ciphertext, iv); PKCS#7 padded AES-256-CBC."""
    iv = iv or os.urandom(16)
    m = plaintext.encode()
    pad = 16 - len(m) % 16
    return _cbc(shared, iv, m + bytes([pad]) * pad, False), iv


def nip04_content(ct: bytes, iv: bytes) -> str:
    return base64.b64encode(ct).decode() + "?iv=" + base64.b64encode(iv).decode()


def nip04_parse(content: str) -> tuple[bytes, bytes] | None:
    """Canonical NIP-04 content -> (ct, iv), or None if it would not rebuild byte for byte."""
    if content.count("?iv=") != 1:
        return None
    a, b = content.split("?iv=")
    try:
        ct, iv = base64.b64decode(a, validate=True), base64.b64decode(b, validate=True)
    except ValueError:
        return None
    if len(iv) != 16 or not ct or len(ct) % 16 or nip04_content(ct, iv) != content:
        return None
    return ct, iv


def nip04_decrypt(shared: bytes, content: str) -> str | None:
    parsed = nip04_parse(content)
    if not parsed:
        return None
    ct, iv = parsed
    pt = _cbc(shared, iv, ct, True)
    pad = pt[-1]
    if not 1 <= pad <= 16 or pt[-pad:] != bytes([pad]) * pad:
        return None
    try:
        return pt[:-pad].decode()
    except UnicodeDecodeError:
        return None


def nip04_dm(seckey: bytes, recipient: bytes, text: str, created_at: int, iv: bytes | None = None,
             aux_rand: bytes | None = None, expiration: int | None = None) -> Event:
    ct, iv = nip04_encrypt_raw(nip04_key(seckey, recipient), text, iv)
    tags = [["p", recipient.hex()]] + ([["expiration", str(expiration)]] if expiration is not None else [])
    return sign_event(seckey, created_at, 4, tags, nip04_content(ct, iv),
                      aux_rand if aux_rand is not None else os.urandom(32))

"""Device register for dm4.ref (docs/nostr-mesh-bridge.md 5.5). Local, sensitive (chmod 600).

    [[device]]
    name = "sensor1"
    pubkey = "<hex or npub>"
    owner = "<hex or npub>"
    allow_from = ["<hex or npub>"]
    rendezvous = true        # sleeps: DMs wait for its next uplink

The bridge never holds a LibreTag key: LT2 trackers are reached through the command mailbox (kind 4244).
"""
from __future__ import annotations

import tomllib
from dataclasses import dataclass, field
from pathlib import Path

from .bech32 import to_hex


@dataclass
class Device:
    name: str
    pubkey: bytes
    owner: bytes | None = None
    allow_from: list[bytes] = field(default_factory=list)
    rendezvous: bool = True


class Registry:
    def __init__(self, devices: list[Device] | None = None):
        self.devices = devices or []
        self._extra: set[bytes] = set()  # keys learned from dm4.full / generic frames
        self._index()

    def _index(self) -> None:
        self.by_pubkey = {d.pubkey: d for d in self.devices}
        keys = set(self.by_pubkey) | self._extra
        for d in self.devices:
            if d.owner:
                keys.add(d.owner)
            keys.update(d.allow_from)
        self._by_ref: dict[bytes, list[bytes]] = {}
        for k in keys:
            self._by_ref.setdefault(k[:4], []).append(k)

    @classmethod
    def load(cls, path: str | Path) -> "Registry":
        data = tomllib.loads(Path(path).read_text())
        devs = []
        for d in data.get("device", []):
            owner = bytes.fromhex(to_hex(d["owner"])) if d.get("owner") else None
            allow = [bytes.fromhex(to_hex(x)) for x in d.get("allow_from", [])]
            if owner and not allow:
                allow = [owner]
            devs.append(Device(d["name"], bytes.fromhex(to_hex(d["pubkey"])), owner, allow,
                               d.get("rendezvous", True)))
        return cls(devs)

    def resolve(self, ref: bytes) -> list[bytes]:
        return self._by_ref.get(ref, [])

    def knows(self, pubkey: bytes) -> bool:
        return pubkey in self._by_ref.get(pubkey[:4], [])

    def learn(self, pubkey: bytes) -> None:
        if not self.knows(pubkey):
            self._extra.add(pubkey)
            self._index()

    def device(self, pubkey: bytes) -> Device | None:
        return self.by_pubkey.get(pubkey)

#!/usr/bin/env python3
"""strfry write policy for LibreTag mailbox events (PROTOCOL.md 4.1, "Mailbox size"; bridge-market.md Q4).

Each source (IP) may write at most PER_MINUTE kind 4244 events per rolling minute. Since the signed
mailbox (PROTOCOL.md 4.2, 2026-11-18) entries carry no proof of work, so MIN_BITS is 0; an operator who
still wants committed NIP-13 work on 4244 can raise it. Every other event is accepted unchanged. Stdlib only.

strfry.conf:
    relay { writePolicy { plugin = "/usr/local/bin/strfry-mailbox-policy.py" } }

Not deployed by this repo: the relay's operator installs it.
"""
from __future__ import annotations

import json
import sys
import time
from collections import defaultdict, deque

KIND = 4244
MIN_BITS = 0
PER_MINUTE = 30


def leading_zero_bits(event_id: str) -> int:
    n = 0
    for c in event_id:
        v = int(c, 16)
        if v == 0:
            n += 4
            continue
        return n + 4 - v.bit_length()
    return n


def committed(tags) -> int | None:
    for t in tags if isinstance(tags, list) else ():
        if isinstance(t, list) and len(t) >= 3 and t[0] == "nonce":
            return int(t[2]) if isinstance(t[2], str) and t[2].isdigit() and len(t[2]) <= 3 else None
    return None


class Policy:
    def __init__(self, min_bits: int = MIN_BITS, per_minute: int = PER_MINUTE):
        self.min_bits, self.per_minute = min_bits, per_minute
        self.hits: dict[str, deque] = defaultdict(deque)

    def decide(self, req: dict, now: float) -> dict:
        ev = req.get("event") or {}
        out = {"id": ev.get("id", "")}
        if req.get("type") != "new" or ev.get("kind") != KIND:
            return {**out, "action": "accept"}
        eid = ev.get("id")
        try:
            d = leading_zero_bits(eid) if isinstance(eid, str) else 0
        except ValueError:
            d = 0
        target = committed(ev.get("tags"))
        if self.min_bits and (d < self.min_bits or target is None or target < self.min_bits):
            return {**out, "action": "reject", "msg": f"pow: kind {KIND} needs {self.min_bits} committed bits"}
        src = str(req.get("sourceInfo") or req.get("sourceType") or "?")
        q = self.hits[src]
        while q and now - q[0] >= 60:
            q.popleft()
        if len(q) >= self.per_minute:
            return {**out, "action": "reject", "msg": "rate-limited: too many mailbox events"}
        q.append(now)
        return {**out, "action": "accept"}


def main() -> None:
    pol = Policy()
    for line in sys.stdin:
        try:
            req = json.loads(line)
        except ValueError:
            continue
        sys.stdout.write(json.dumps(pol.decide(req, time.monotonic())) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()

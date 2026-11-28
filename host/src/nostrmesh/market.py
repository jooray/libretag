"""Bridge identity, announcements, receipts and targeted mailbox entries (PROTOCOL.md 4.1).

Pure functions shared by the bridge (lt2svc.py) and the vectors (host/tests/vectors/mailbox-v2.json, copied
to android-bridge and the app):

- format 2 entries (["v", "2"]), the only format: binding route, ["p", bridge] targeting, ["r", network];
- the claim proof SHA-256(P || pubkey) that only a bridge which heard report P can make in time;
- the receipt hop class and the announcement (kind 38244) tags.
"""
from __future__ import annotations

import hashlib
import json
import re

RECEIPT_KIND = 4245
ANNOUNCE_KIND = 38244
FORMAT2 = "2"
ROUTES = ("near", "direct", "flood", "both")  # "both" (0.10.0): flood, and the fallback after a zero-hop send
MAX_P = 4            # bridges named per entry that count
MAX_P_TAGS = 8       # more p tags than this: the entry is invalid
NETWORK = re.compile(r"^[a-z0-9-]{1,32}$")
RECEIPT_TTL_S = 48 * 3600
ANNOUNCE_TTL_S = 7 * 86400
DIRECT_MAX_HOPS = 2
RADIO_STATES = ("up", "down")


def is_network(s) -> bool:
    return isinstance(s, str) and bool(NETWORK.match(s))


def _lower_hex(s, n) -> bool:
    return isinstance(s, str) and len(s) == 2 * n and all(c in "0123456789abcdef" for c in s)


class Targeting:
    """What a 4244 event's tags say about who may send it and how (format 1 or 2)."""

    def __init__(self, fmt: int, route: str, p: list[str], r: str | None):
        self.fmt, self.route, self.p, self.r = fmt, route, p, r

    @property
    def open(self) -> bool:
        return not self.p

    def p_index(self, pubkey: str | None) -> int | None:
        return self.p.index(pubkey) if pubkey in self.p else None

    def to_dict(self) -> dict:
        return {"format": self.fmt, "route": self.route, "p": self.p, "r": self.r}


def targeting(tags) -> Targeting | None:
    """None: malformed (no ["v", "2"], bad p or r, too many p tags, unknown route). Format 1 (no "v") is
    gone since the signed mailbox (2026-11-18)."""
    tags = [t for t in tags if isinstance(t, list) and len(t) >= 2 and isinstance(t[1], str)]
    if not any(t[0] == "v" and t[1] == FORMAT2 for t in tags):
        return None
    route = next((t[1] for t in tags if t[0] == "route"), None)
    if route is None:
        route = "flood"
    if route not in ROUTES:
        return None
    ps = [t[1] for t in tags if t[0] == "p"]
    if len(ps) > MAX_P_TAGS or not all(_lower_hex(p, 32) for p in ps):
        return None
    rs = [t[1] for t in tags if t[0] == "r"]
    if len(rs) > 1 or not all(is_network(r) for r in rs):
        return None
    return Targeting(2, route, list(dict.fromkeys(ps))[:MAX_P], rs[0] if rs else None)


def claim_proof(p: bytes, pubkey_hex: str) -> str:
    """Hearing proof on an untimed claim: only a bridge that heard report P can make it before P is on the relays."""
    return hashlib.sha256(p + bytes.fromhex(pubkey_hex)).hexdigest()


def hops_class(hops: int | None) -> str:
    if hops is None:
        return "?"
    if hops == 0:
        return "0"
    return "1-3" if hops <= 3 else "4+"


def receipt_tags(x: str, direct: bool, heard_at: int) -> list[list[str]]:
    """A receipt is only for a report heard directly (PROTOCOL.md 4.1, 0.10.0): on a DIRECT route, where the
    hop count is unknown (["route", "direct"]), or as a flood with 0 hops (["hops", "0"])."""
    how = ["route", "direct"] if direct else ["hops", "0"]
    return [["y", x], how, ["expiration", str(heard_at + RECEIPT_TTL_S)]]


def announce_tags(network: str, now: int, *, regions=(), preset=None, relays=(),
                  accept_open: bool = True, receipts: bool = True, anchor_lead_s: float = 60,
                  late_max_s: float = 40, radio: tuple[str, int] | None = None) -> list:
    """Kind 38244 tags: one ["region", …] per scope served, narrow first; ["radio", up|down, since] the
    state of the radio itself (SPEC 3.2 "Radio health")."""
    tags = [["d", network], ["k", "4244"]]
    tags += [["region", r] for r in regions if r]
    if radio is not None:
        tags.append(["radio", radio[0], str(int(radio[1]))])
    if preset:
        tags.append(["preset", *[str(int(v)) for v in preset]])
    tags += [["relay", u] for u in relays]
    tags += [["signed", str(int(anchor_lead_s)), str(int(late_max_s))],
             ["open", "1" if accept_open else "0"], ["receipts", "1" if receipts else "0"],
             ["expiration", str(now + ANNOUNCE_TTL_S)]]
    return tags


def parse_announcement(ev, now: float) -> dict | None:
    """A valid, unexpired announcement's fields (signature checked by the caller), or None."""
    if getattr(ev, "kind", None) != ANNOUNCE_KIND or not isinstance(ev.tags, list):
        return None
    tags = [t for t in ev.tags if isinstance(t, list) and len(t) >= 2 and all(isinstance(v, str) for v in t)]
    get = {}
    for t in tags:
        get.setdefault(t[0], t)
    if get.get("k", [None, None])[1] != "4244" or "d" not in get:
        return None
    exp = get.get("expiration", [None, ""])[1]
    if not exp.isdigit() or int(exp) <= now:
        return None
    d = get["d"][1]
    if d and not is_network(d):
        return None
    sg = get.get("signed", [])
    signed = [int(v) for v in sg[1:3] if v.isdigit() and len(v) <= 6] if sg else []
    rd = get.get("radio", [])
    radio = (rd[1], int(rd[2])) if len(rd) >= 3 and rd[1] in RADIO_STATES and rd[2].isdigit() else None
    return {"pubkey": ev.pubkey, "network": d, "region": get.get("region", [None, None])[1],
            "regions": [t[1] for t in tags if t[0] == "region"], "radio": radio,
            "signed": signed if len(signed) == 2 else None, "open": get.get("open", ["", "1"])[1] == "1",
            "receipts": get.get("receipts", ["", "0"])[1] == "1", "expires": int(exp)}


def vectors() -> dict:
    """host/tests/vectors/mailbox-v2.json: targeting, claim proofs, hop classes, and the signed mailbox
    (PROTOCOL.md 4.2): which keys a heard report locks, and events a bridge stores or turns down."""
    import base64

    from libretag import lt2
    from libretag.lt2 import Keys, Report

    from . import secp
    from .nostr import sign_event

    def key(i):
        return secp.seckey_from_bytes(hashlib.sha256(f"mailbox v2 vector {i}".encode()).digest())

    t0 = 1_763_200_000
    bridge = secp.pubkey(key(100)).hex()
    other = secp.pubkey(key(101)).hex()
    out = {
        "note": "LibreTag mailbox (PROTOCOL.md 4.1, 4.2). Only format 2 (['v','2']) is valid. proof = "
                "sha256(P || pubkey). hops class 0, 1-3, 4+, ? (unknown). lock: c = sha256('LT2-mbc' || pk || "
                "P[0:4] || P[7:45])[0:3] must equal P[4:7]; the tag keys are lt2.json's (k0 = 00..1f).",
        "bridge_pubkey": bridge,
        "proof": [],
        "hops_class": [{"hops": h, "class": hops_class(h)} for h in (None, 0, 1, 3, 4, 12)],
        "targeting": [],
        "lock": [],
        "events": [],
    }
    for note, tags in [
        ("two bridges, network sk", [["v", "2"], ["route", "flood"], ["p", bridge], ["p", other], ["r", "sk"]]),
        ("without route = flood", [["v", "2"], ["p", bridge]]),
        ("open, near", [["v", "2"], ["route", "near"]]),
        ("no v tag (format 1): invalid", [["route", "near"], ["p", bridge], ["r", "sk"]]),
        ("unknown route: invalid", [["v", "2"], ["route", "far"]]),
        ("bad network: invalid", [["v", "2"], ["r", "SK"]]),
        ("two r tags: invalid", [["v", "2"], ["r", "sk"], ["r", "cz"]]),
        ("bad p: invalid", [["v", "2"], ["p", "abc"]]),
    ]:
        t = targeting(tags)
        out["targeting"].append({"note": note, "tags": tags, "valid": t is not None,
                                 **({"parsed": t.to_dict()} if t else {})})
    p = bytes(range(45))
    for pk in (bridge, other):
        out["proof"].append({"p": p.hex(), "pubkey": pk, "proof": claim_proof(p, pk)})

    tag_keys = Keys(bytes(range(32)))
    rep = Report(has_fix=True, fix_time=t0, lat=48.7, lon=21.25, battery_pct=80)
    for n, j in ((70, 0), (70, 1), (200, 0)):
        pn = lt2.seal_report_keys(tag_keys, n, j, rep)
        ce = tag_keys.cmd(lt2.epoch(n))
        cands = [("pk_n", lt2.mailbox_pk(ce, n).hex()), ("pk of n + 1", lt2.mailbox_pk(ce, n + 1).hex()),
                 ("stranger", secp.pubkey(key(7)).hex()),
                 ("view key as a secret", secp.pubkey(lt2.mailbox_sk(tag_keys.view(lt2.epoch(n)), n)).hex())]
        out["lock"].append({"n": n, "j": j, "p": pn.hex(), "x": pn[:4].hex(),
                            "keys": [{"note": nt, "pubkey": k, "fits": lt2.commit_matches(bytes.fromhex(k), pn)}
                                     for nt, k in cands]})

    pc = base64.b64encode(bytes(range(29))).decode()
    n = 70
    ve, ce = tag_keys.view(lt2.epoch(n)), tag_keys.cmd(lt2.epoch(n))
    ys = [["y", lt2.report_tag(ve, n, 0).hex()], ["y", lt2.report_tag(ve, n, 1).hex()]]
    exp = ["expiration", str(t0 + 6 * 3600)]
    sk_n = lt2.mailbox_sk(ce, n)

    def add(note, sk, tags, content, valid):
        ev = sign_event(sk, t0, 4244, tags, content)
        out["events"].append({"note": note, "event": ev.to_dict(), "n": n, "valid": valid,
                              "signed_by_pk_n": ev.pubkey == lt2.mailbox_pk(ce, n).hex(),
                              "for_bridge": bool(valid and (targeting(ev.tags).open or bridge in targeting(ev.tags).p))})

    add("anchor for report 70 at the bridge", sk_n, [["v", "2"], *ys, ["p", bridge], exp], "", True)
    add("untimed command, flood, at the bridge", sk_n,
        [["v", "2"], *ys, ["route", "flood"], ["p", bridge], ["g", "0123456789abcdef"], exp], pc, True)
    add("open untimed command", sk_n, [["v", "2"], *ys, ["route", "flood"], ["g", "0123456789abcdef"], exp], pc, True)
    add("the same tags under a stranger's key: stored, never locks", key(7),
        [["v", "2"], *ys, ["route", "flood"], ["p", bridge], exp], pc, True)
    add("at another bridge", sk_n, [["v", "2"], *ys, ["route", "flood"], ["p", other], exp], pc, True)
    add("format 1 (no v): refused", sk_n, [*ys, ["route", "flood"], exp], pc, False)
    add("timed entry, via report 70", sk_n, [["v", "2"], ["y", lt2.command_tag(ce, n, 1).hex()], ["route", "flood"],
                                             ["p", bridge], ["at", str(t0 + 3600)], ["via", ys[0][1]],
                                             ["g", "0123456789abcdef"], exp], pc, True)
    return out


if __name__ == "__main__":
    import sys

    if sys.argv[1:] == ["vectors"]:
        print(json.dumps(vectors(), indent=2))

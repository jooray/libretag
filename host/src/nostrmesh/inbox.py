"""Inbox relays of a DM recipient: NIP-65 (kind 10002, read or unmarked `r` tags) and NIP-17 (kind 10050,
`relay` tags). A DM the bridge publishes also goes there, so the recipient's client finds it.

Only lists with a valid signature by the recipient count (a relay could hand out forged ones), and a
host name must resolve to public addresses only before the bridge dials it (`public_only`), so a list
cannot point the bridge at a machine on its own network through DNS."""
from __future__ import annotations

import asyncio
import ipaddress
import logging
from urllib.parse import urlsplit

from .nostr import Event

log = logging.getLogger("nostrmesh.inbox")

KINDS = (10002, 10050)
MAX_EXTRA = 6
TTL_S = 6 * 3600
RETRY_S = 600  # nothing found (relays offline, no list): ask again after this
_LOCAL_SUFFIXES = (".local", ".lan", ".internal", ".localhost", ".home.arpa")


def safe_url(url) -> str | None:
    """Normalised wss:// URL, or None for anything a bridge must not dial (plain ws, local, private)."""
    if not isinstance(url, str) or len(url) > 256:
        return None
    try:
        u = urlsplit(url.strip())
        host = (u.hostname or "").lower()
        port = u.port
    except ValueError:
        return None
    if u.scheme != "wss" or not host or u.username or u.password:
        return None
    if host == "localhost" or host.endswith(_LOCAL_SUFFIXES) or "." not in host and ":" not in host:
        return None
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        ip = None
    if ip is not None and not ip.is_global:
        return None
    path = u.path.rstrip("/")
    return f"wss://{host}{f':{port}' if port else ''}{path}"


def parse(events: list[dict], pubkey: str) -> list[str]:
    """Inbox relays from the newest kind 10002 and 10050 of `pubkey` (10050 first: it is the DM list)."""
    newest: dict[int, dict] = {}
    for e in events:
        if not isinstance(e, dict) or e.get("pubkey") != pubkey or e.get("kind") not in KINDS:
            continue
        if not isinstance(e.get("tags"), list) or not isinstance(e.get("created_at"), int):
            continue
        try:
            if not Event.from_dict(e).verify():
                continue
        except (KeyError, TypeError, ValueError, AttributeError):
            continue
        k = e["kind"]
        if k not in newest or e["created_at"] > newest[k]["created_at"]:
            newest[k] = e
    out: list[str] = []
    for k in (10050, 10002):
        for t in newest.get(k, {}).get("tags", []):
            if not isinstance(t, list) or len(t) < 2:
                continue
            if k == 10050 and t[0] != "relay":
                continue
            if k == 10002 and (t[0] != "r" or (len(t) > 2 and t[2] != "read")):
                continue
            u = safe_url(t[1])
            if u and u not in out:
                out.append(u)
    return out


def _public_ip(addr: str) -> bool:
    try:
        return ipaddress.ip_address(addr.split("%")[0]).is_global
    except ValueError:
        return False


async def public_only(urls: list[str], getaddrinfo, timeout: float = 5.0) -> list[str]:
    """The URLs whose host resolves, and only to public addresses. `getaddrinfo(host, port)` is the
    event loop's (or a fake in tests); a literal address was already checked by `safe_url`."""
    out = []
    for u in urls:
        sp = urlsplit(u)
        host = sp.hostname or ""
        try:
            ipaddress.ip_address(host)
            out.append(u)
            continue
        except ValueError:
            pass
        try:
            infos = await asyncio.wait_for(getaddrinfo(host, sp.port or 443), timeout)
        except Exception as e:  # noqa: BLE001  (unresolvable: the bridge could not dial it anyway)
            log.info("inbox relay %s does not resolve: %s", host, e)
            continue
        addrs = [i[4][0] for i in infos if i and len(i) >= 5 and i[4]]
        if addrs and all(_public_ip(a) for a in addrs):
            out.append(u)
        else:
            log.warning("inbox relay %s resolves to a non-public address, skipped", host)
    return out


def extra(inbox: list[str], configured: list[str], limit: int = MAX_EXTRA) -> list[str]:
    """Inbox relays not already in the bridge's publish set, at most `limit`."""
    have = {safe_url(u) or u for u in configured}
    return [u for u in inbox if u not in have][:limit]


class InboxCache:
    def __init__(self):
        self._c: dict[str, tuple[list[str], float]] = {}

    def get(self, pubkey: str, now: float) -> list[str] | None:
        hit = self._c.get(pubkey)
        if hit is None:
            return None
        urls, at = hit
        return urls if now - at < (TTL_S if urls else RETRY_S) else None

    def put(self, pubkey: str, urls: list[str], now: float):
        if len(self._c) > 1000:
            self._c.pop(next(iter(self._c)))
        self._c[pubkey] = (urls, now)

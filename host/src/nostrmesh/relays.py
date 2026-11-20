"""Minimal Nostr relay pool (NIP-01): publish with OK handling and retry, subscriptions.

NIP-42 (optional): with an `auth_signer(challenge, relay_url) -> signed kind 22242 dict` the relay
answers every ["AUTH", challenge] at once, re-sends its subscriptions once the relay accepts it, and
retries a publish the relay refused with `auth-required:` once. Without a signer AUTH is ignored."""
from __future__ import annotations

import asyncio
import json
import logging
import secrets
from typing import Awaitable, Callable

log = logging.getLogger("nostrmesh.relays")

FINAL_REJECT = ("invalid:", "pow:", "blocked:", "restricted:", "auth-required:")
OnEvent = Callable[[dict, str], Awaitable[None] | None]
AUTH_KIND = 22242


def auth_event_signer(seckey: bytes, clock=None):
    """NIP-42 signer for `Relay`: kind 22242 with the relay URL and the challenge."""
    import os
    import time

    from .nostr import sign_event

    def sign(challenge: str, url: str) -> dict:
        now = int((clock or time.time)())
        return sign_event(seckey, now, AUTH_KIND, [["relay", url], ["challenge", challenge]], "",
                          os.urandom(32)).to_dict()

    return sign


class Relay:
    def __init__(self, url: str, on_event: OnEvent | None = None, connect=None, auth_signer=None):
        self.url = url
        self.auth_signer = auth_signer
        self.authed = asyncio.Event()
        self.on_event = on_event
        self.subs: dict[str, list[dict]] = {}
        self._ws = None
        self._connected = asyncio.Event()
        self._oks: dict[str, asyncio.Future] = {}
        self._connect = connect
        self._task: asyncio.Task | None = None
        self._queries: dict[str, tuple[list, asyncio.Future]] = {}

    def start(self):
        self._task = asyncio.create_task(self._run())

    async def stop(self):
        if self._task:
            self._task.cancel()
        if self._ws:
            await self._ws.close()

    async def _run(self):
        if self._connect is None:
            from websockets.asyncio.client import connect

            self._connect = connect
        backoff = 2
        while True:
            try:
                async with self._connect(self.url, open_timeout=15, ping_interval=30, max_size=2**20) as ws:
                    self._ws = ws
                    self._connected.set()
                    backoff = 2
                    log.info("connected %s", self.url)
                    for sid, filters in self.subs.items():
                        await ws.send(json.dumps(["REQ", sid, *filters]))
                    async for raw in ws:
                        await self._handle(raw)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001
                log.warning("%s: %s", self.url, e)
            self._connected.clear()
            self.authed.clear()
            self._ws = None
            for f in self._oks.values():
                if not f.done():
                    f.set_result((False, "error: disconnected"))
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 120)

    async def _handle(self, raw):
        try:
            msg = json.loads(raw)
        except ValueError:
            return
        if not isinstance(msg, list) or not msg:
            return
        if msg[0] == "AUTH" and len(msg) >= 2 and isinstance(msg[1], str):
            await self._auth(msg[1])
        elif msg[0] == "OK" and len(msg) >= 3:
            f = self._oks.pop(msg[1], None)
            if f and not f.done():
                f.set_result((bool(msg[2]), msg[3] if len(msg) > 3 else ""))
        elif msg[0] in ("EVENT", "EOSE", "CLOSED") and len(msg) >= 2 and msg[1] in self._queries:
            got, fut = self._queries[msg[1]]
            if msg[0] == "EVENT" and len(msg) >= 3 and isinstance(msg[2], dict):
                got.append(msg[2])
            elif not fut.done():
                fut.set_result(None)
        elif msg[0] == "EVENT" and len(msg) >= 3 and isinstance(msg[2], dict) and self.on_event:
            r = self.on_event(msg[2], self.url)
            if asyncio.iscoroutine(r):
                await r
        elif msg[0] in ("NOTICE", "CLOSED"):
            log.info("%s %s", self.url, msg)

    async def _auth(self, challenge: str):
        if self.auth_signer is None or self._ws is None or len(challenge) > 256:
            return
        ev = self.auth_signer(challenge, self.url)
        fut = asyncio.get_running_loop().create_future()
        self._oks[ev["id"]] = fut

        async def wait_ok():
            try:
                ok, msg = await asyncio.wait_for(fut, 10)
            except asyncio.TimeoutError:
                self._oks.pop(ev["id"], None)
                return
            if ok:
                self.authed.set()
                log.info("%s: authenticated (NIP-42)", self.url)
                for sid, filters in self.subs.items():  # a relay may have CLOSED them with auth-required
                    if self._ws is not None:
                        await self._ws.send(json.dumps(["REQ", sid, *filters]))
            else:
                log.warning("%s: AUTH refused: %s", self.url, msg)

        try:
            await self._ws.send(json.dumps(["AUTH", ev], separators=(",", ":")))
        except Exception as e:  # noqa: BLE001
            log.warning("%s AUTH failed: %s", self.url, e)
            return
        asyncio.create_task(wait_ok())

    async def publish(self, event: dict, timeout: float = 15.0) -> tuple[bool, str]:
        res = await self._publish(event, timeout)
        if not res[0] and res[1].startswith("auth-required:") and self.auth_signer is not None:
            try:
                await asyncio.wait_for(self.authed.wait(), 10)
            except asyncio.TimeoutError:
                return res
            res = await self._publish(event, timeout)
        return res

    async def _publish(self, event: dict, timeout: float = 15.0) -> tuple[bool, str]:
        try:
            await asyncio.wait_for(self._connected.wait(), timeout)
        except asyncio.TimeoutError:
            return False, "error: not connected"
        fut = asyncio.get_running_loop().create_future()
        self._oks[event["id"]] = fut
        try:
            await self._ws.send(json.dumps(["EVENT", event], separators=(",", ":"), ensure_ascii=False))
            return await asyncio.wait_for(fut, timeout)
        except Exception as e:  # noqa: BLE001
            self._oks.pop(event["id"], None)
            return False, f"error: {e}"

    async def query(self, filters: list[dict], timeout: float = 6.0) -> list[dict]:
        """One-shot REQ on the live connection: events until EOSE (or timeout), then CLOSE."""
        try:
            await asyncio.wait_for(self._connected.wait(), timeout)
        except asyncio.TimeoutError:
            return []
        sid = "nm-q" + secrets.token_hex(4)
        got: list = []
        fut = asyncio.get_running_loop().create_future()
        self._queries[sid] = (got, fut)
        try:
            await self._ws.send(json.dumps(["REQ", sid, *filters]))
            await asyncio.wait_for(fut, timeout)
        except Exception:  # noqa: BLE001
            pass
        finally:
            self._queries.pop(sid, None)
            try:
                if self._ws is not None:
                    await self._ws.send(json.dumps(["CLOSE", sid]))
            except Exception:  # noqa: BLE001
                pass
        return got

    async def subscribe(self, sid: str, filters: list[dict]):
        self.subs[sid] = filters
        if self._ws is not None:
            try:
                await self._ws.send(json.dumps(["REQ", sid, *filters]))
            except Exception as e:  # noqa: BLE001
                log.warning("%s REQ failed: %s", self.url, e)


class RelayPool:
    def __init__(self, publish: list[str], subscribe: list[str] | None = None, on_event: OnEvent | None = None,
                 retry_delays=(5, 30, 120, 600), connect=None, auth_signer=None):
        self._connect = connect
        self.auth_signer = auth_signer
        urls = list(dict.fromkeys(list(publish) + list(subscribe or [])))
        self.relays = {u: Relay(u, on_event, connect, auth_signer) for u in urls}
        self.publish_urls = list(publish)
        self.subscribe_urls = list(subscribe or [])
        self.retry_delays = retry_delays

    def start(self):
        for r in self.relays.values():
            r.start()

    async def stop(self):
        for r in self.relays.values():
            await r.stop()

    async def publish(self, event: dict, retry: bool = True) -> dict[str, tuple[bool, str]]:
        """Publish to every publish relay; retries transient failures in place (not with retry=False,
        for ephemeral events that are useless late). `duplicate:` is success."""

        async def one(url):
            relay = self.relays[url]
            res = (False, "")
            for delay in (0, *self.retry_delays) if retry else (0,):
                if delay:
                    await asyncio.sleep(delay)
                res = await relay.publish(event)
                ok, msg = res
                if ok or msg.startswith("duplicate:"):
                    return True, msg
                if msg.startswith(FINAL_REJECT):
                    return res
            return res

        results = await asyncio.gather(*(one(u) for u in self.publish_urls))
        return dict(zip(self.publish_urls, results))

    async def query(self, filters: list[dict], timeout: float = 6.0) -> list[dict]:
        """Events matching `filters` from every subscribe relay (deduplicated by id)."""
        res = await asyncio.gather(*(self.relays[u].query(filters, timeout) for u in self.subscribe_urls))
        seen, out = set(), []
        for evs in res:
            for e in evs:
                if isinstance(e, dict) and e.get("id") not in seen:
                    seen.add(e.get("id"))
                    out.append(e)
        return out

    async def publish_once(self, event: dict, urls: list[str], timeout: float = 15.0) -> dict[str, tuple[bool, str]]:
        """Publish to relays outside the pool over a short-lived connection each, no retry."""

        async def one(url):
            r = Relay(url, connect=self._connect, auth_signer=self.auth_signer)
            r.start()
            try:
                ok, msg = await r.publish(event, timeout)
                return (ok or msg.startswith("duplicate:")), msg
            finally:
                await r.stop()

        res = await asyncio.gather(*(one(u) for u in urls), return_exceptions=True)
        return {u: (r if isinstance(r, tuple) else (False, f"error: {r}")) for u, r in zip(urls, res)}

    async def subscribe(self, sid: str, filters: list[dict]):
        for u in self.subscribe_urls:
            await self.relays[u].subscribe(sid, filters)

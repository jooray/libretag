"""Nostr <-> MeshCore bridge (docs/nostr-mesh-bridge.md 5). One bridge for every project.

mesh -> relays (on by default): signed frames (dm4, Roadbeacon, notes) are decoded, verified, deduped
by id and published unchanged; LibreTag LT2 reports on #libretag go out as kind 4243 under a fresh key
(lt2svc.py, variant B).
relays -> mesh: LT2 commands from the kind 4244 mailbox, only right after the matching report, zero-hop
or direct, or at a given time along the stored path of an earlier report (0.7 checks) (lt2svc.py);
road events inside a geofence and DMs to registered devices (off by default), after a random hold,
only if the event was not heard on the radio meanwhile, within rate limits.
Every own transmission counts against an airtime budget (default 0.1 % per rolling hour): #libretag
has its own (`[libretag] airtime_pct`), so a flood of LibreTag commands cannot silence Roadbeacon or
DMs, and both live in the ledger, so a restart does not reset them.

Reliability: an event no relay accepted waits in the ledger's outbox and is retried until it expires
(14 d); the radio's own health (radio_health.py, R37) is watched, announced and recovered without
exiting; an hourly heartbeat goes to the log and to `[status] path` (`nostrmesh status`).
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import math
import os
import random
import time
from collections import Counter, defaultdict
from dataclasses import dataclass, field

from libretag.lt2 import NOSTR_CLAIM_KIND, NOSTR_COMMAND_KIND, NOSTR_REPORT_KIND
from libretag.meshcore import lora_airtime_s

from . import fieldlog, frag, frames, inbox, road
from .config import MAX_AGE_S, Config
from .errors import FrameError
from .ledger import Ledger
from .lt2svc import LibreTagService
from .nostr import Event
from .policy import AirtimeBudget, RateLimit
from .radio import Datagram, Radio, RadioError
from .radio_health import TICK_S as HEALTH_TICK_S, RadioHealth
from .registry import Registry

log = logging.getLogger("nostrmesh.bridge")

LT_DATA_TYPES = (frames.DATA_TYPE_LIBRETAG, 0xFF6C)  # 0xFF6C: a command's fallback send (PROTOCOL.md 3.4)
ACCEPT = {(frames.CHANNEL_NOSTR, frames.DATA_TYPE_NOSTR), (frames.CHANNEL_ROAD, frames.DATA_TYPE_ROAD)}
LT2_KINDS = {NOSTR_REPORT_KIND, NOSTR_COMMAND_KIND, NOSTR_CLAIM_KIND, 5,  # 5: NIP-09 deletion of a 4244
             38244}  # bridge announcements (PROTOCOL.md 4.1)


def grp_data_len(datagram_len: int) -> int:
    """MeshCore GRP_DATA payload for a channel datagram: hash 1 + MAC 2 + (type 2 + len 1 + data) in 16 B blocks."""
    return 3 + -(-(3 + datagram_len) // 16) * 16


def haversine_km(a_lat, a_lon, b_lat, b_lon) -> float:
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dp, dl = p2 - p1, math.radians(b_lon - a_lon)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(h))


def geohash_cover(lat: float, lon: float, radius_km: float, precision: int = 4) -> list[str]:
    """Geohash cells of `precision` touching the circle (sampled every ~2.5 km)."""
    cells = set()
    step = 2.5
    n = int(radius_km / step) + 2
    for i in range(-n, n + 1):
        for j in range(-n, n + 1):
            dlat = i * step / 111.0
            dlon = j * step / (111.0 * max(math.cos(math.radians(lat)), 0.01))
            p_lat, p_lon = lat + dlat, lon + dlon
            if -90 <= p_lat <= 90 and haversine_km(lat, lon, p_lat, p_lon) <= radius_km + step:
                cells.add(road.geohash(p_lat, ((p_lon + 180) % 360) - 180, precision))
    return sorted(cells)


@dataclass
class Outgoing:
    event_id: str
    author: str
    channel: str
    data_type: int
    payloads: list[bytes]
    is_dm: bool = False
    device: bytes | None = None
    expires: float = 0.0


@dataclass
class Stats:
    c: Counter = field(default_factory=Counter)

    def inc(self, key: str, n: int = 1):
        self.c[key] += n


class Bridge:
    def __init__(self, cfg: Config, radio: Radio, pool, ledger: Ledger | None = None,
                 registry: Registry | None = None, clock=time.time, rng: random.Random | None = None):
        self.cfg = cfg
        self.radio = radio
        self.pool = pool
        self.ledger = ledger or Ledger()
        self.registry = registry or Registry()
        self.clock = clock
        self.rng = rng or random.Random()
        self.stats = Stats()
        self.bridge_id = hashlib.sha256(str(id(self)).encode() + self.rng.randbytes(8)).digest()[:8]
        self.reassembler = frag.Reassembler()
        up, dn = cfg.uplink, cfg.downlink
        self.rx_global = RateLimit(up.rate_global_per_min, 60)
        self.rx_pk_min = RateLimit(up.rate_per_pubkey_per_min, 60)
        self.rx_pk_hour = RateLimit(up.rate_per_pubkey_per_hour, 3600)
        self.tx_global = RateLimit(dn.rate_global_per_hour, 3600)
        self.tx_author = RateLimit(dn.rate_per_author_per_hour, 3600)
        self.tx_dm = RateLimit(dn.dm_per_hour, 3600)
        self.tx_dm_dev = RateLimit(dn.dm_per_device_per_hour, 3600)
        now = clock()
        self.airtime = AirtimeBudget(cfg.airtime.budget_pct / 100, cfg.airtime.window_s,
                                     on_spend=lambda t, s: self.ledger.airtime_add("main", t, s))
        self.lt_airtime = AirtimeBudget(cfg.libretag.airtime_pct / 100, cfg.airtime.window_s,
                                        on_spend=lambda t, s: self.ledger.airtime_add("libretag", t, s))
        self.airtime.restore(self.ledger.airtime_load("main", now - cfg.airtime.window_s), now)
        self.lt_airtime.restore(self.ledger.airtime_load("libretag", now - cfg.airtime.window_s), now)
        self.started_at = now
        self.last_rx = now           # last datagram from the radio (any channel)
        self.fatal: asyncio.Event | None = None
        self.fatal_reason = ""
        self._last_heartbeat = now
        self._heartbeat_base: dict[str, float] = {}
        self.resolver = None         # async (host, port) -> addrinfo list; None = the loop's getaddrinfo
        self.health = RadioHealth(self, now)
        self.lt2 = LibreTagService(self)
        self.last_tx = 0.0
        self.last_send_error: RadioError | None = None
        self.tx_lock = asyncio.Lock()
        self.device_queue: dict[bytes, list[Outgoing]] = defaultdict(list)
        self.road_positions: dict[str, tuple[float, float]] = {}  # report id -> (lat, lon)
        self.routes: dict[bytes, tuple[list[bytes], int, float]] = {}  # pubkey -> (route, hash size, when)
        self._vote_ids_subscribed: frozenset = frozenset()
        self._subscribed: set[str] = set()
        self.tasks: set[asyncio.Task] = set()
        self.loop: asyncio.AbstractEventLoop | None = None
        self._periodic_task: asyncio.Task | None = None
        self._timed_task: asyncio.Task | None = None
        self._health_task: asyncio.Task | None = None
        self.inbox_cache = inbox.InboxCache()
        self._preset_warned: tuple | None = None

    # ------------------------------------------------------------ plumbing

    def _spawn(self, coro):
        t = asyncio.create_task(coro)
        self.tasks.add(t)
        t.add_done_callback(self.tasks.discard)
        return t

    def on_radio_thread(self, dg: Datagram):
        """Radio adapters call this from their own thread."""
        self.loop.call_soon_threadsafe(lambda: self._spawn(self.on_datagram(dg)))

    async def start(self):
        self.loop = asyncio.get_running_loop()
        self.fatal = asyncio.Event()
        await self.loop.run_in_executor(None, self.radio.start, self.on_radio_thread)
        self.last_rx = self.clock()
        await self.refresh_subscriptions()
        if self.lt2.key and self.cfg.libretag.announce:
            self._spawn(self._announce_when_ready())  # PROTOCOL.md 4.1, again every 24 h from tick()
        self.write_status()
        self._periodic_task = asyncio.create_task(self._periodic())
        if self.cfg.libretag.mailbox and self.cfg.libretag.timed_commands:
            self._timed_task = asyncio.create_task(self.lt2.timed_loop())
        self.health.last_traffic = self.health.last_probe = self.clock()
        self._health_task = asyncio.create_task(self._health_loop())

    async def _health_loop(self):
        while True:
            await asyncio.sleep(HEALTH_TICK_S)
            try:
                await self.health.tick(self.clock())
            except Exception:  # noqa: BLE001
                log.exception("radio health")

    def on_radio_state(self, state: str, reason: str, now: float) -> None:
        """radio_health: the radio went down or came back. Announce it at once, tell the operator."""
        self._spawn(self.health.alert(state, reason))
        self.lt2.on_radio_state(state, now)
        self.write_status(now)

    def regions(self) -> list[str]:
        """Flood scopes this bridge serves, narrow first: the node's checked list (Local API), else [radio]."""
        r = getattr(self.radio, "regions", None)
        return list(r) if isinstance(r, list) else list(self.cfg.radio.regions)

    async def _announce_when_ready(self, wait_s: float = 20.0):
        """The announcement says what the radio can do (zero-hop, preset), which a Local API node tells only
        after its hello and radio.stats: wait for those, at most `wait_s`."""
        r = self.radio
        for _ in range(int(wait_s / 0.5)):
            if not hasattr(r, "capabilities") or (r.capabilities and (r.preset or "radio_stats" not in r.capabilities)
                                                  and (getattr(r, "freq_hz", None) or "radio_stats" not in r.capabilities)):
                break
            await asyncio.sleep(0.5)
        self.lt2.announce(self.clock())

    async def stop(self):
        for t in (self._periodic_task, self._timed_task, self._health_task):
            if t:
                t.cancel()
        for t in list(self.tasks):
            t.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        await asyncio.get_running_loop().run_in_executor(None, self.radio.close)

    async def _periodic(self, every_s: float = 60.0):
        while True:
            await asyncio.sleep(every_s)
            try:
                await self.tick()
            except Exception:  # noqa: BLE001  (housekeeping must not die)
                log.exception("periodic")

    async def tick(self):
        """Once a minute: subscriptions, pruning, outbox, watchdog, heartbeat."""
        await self.refresh_subscriptions()
        now = self.clock()
        self.ledger.prune(now, self.cfg.retention_days)
        self.lt2.prune(now)
        for dev, q in self.device_queue.items():
            q[:] = [o for o in q if o.expires > now]
        drained = getattr(self.radio, "last_drained", None)
        if drained:
            self.ledger.kv_set("radio_last_drained", str(drained))
        await self.retry_outbox(now)
        if now - self._last_heartbeat >= self.cfg.status.interval_s:
            self.heartbeat(now)

    # ------------------------------------------------------------ watchdog and heartbeat

    def radio_silence_s(self, now: float) -> float:
        """Since the last traffic of the radio itself (a packet or a probe answer), not of the session."""
        return now - max(self.last_rx, self.health.last_traffic)

    HEARD = ("rx_lt2_report",)
    DROPPED = ("rx_rate_limited", "lt_rx_rate_limited", "lt_rx_first_hop_limited", "tx_airtime_exhausted", "tx_radio_budget", "dn_rate_limited",
               "up_rate_limited_pubkey", "outbox_expired", "publish_failed")

    def summary(self) -> dict[str, float]:
        c = self.stats.c
        heard = sum(v for k, v in c.items() if k.startswith("rx_") and k[3:] in frames.PROFILE_IDS)             + sum(c[k] for k in self.HEARD)
        return {
            "heard": heard,
            "published": c["published"] + c["lt_published"] + c["outbox_published"],
            "dropped": sum(c[k] for k in self.DROPPED),
            "spam_rejected": sum(v for k, v in c.items() if k.startswith("lt_spam_")),
            "sent_to_radio": c["dn_sent"] + sum(v for k, v in c.items() if k.startswith("lt_cmd_sent_")),
            "airtime_s": c["tx_airtime_ms"] / 1000,
        }

    def status(self, now: float | None = None) -> dict:
        now = self.clock() if now is None else now
        s = self.summary()
        return {
            "at": int(now), "started_at": int(self.started_at), "interval_s": self.cfg.status.interval_s,
            "total": s,
            "last_hour": {k: v - self._heartbeat_base.get(k, 0) for k, v in s.items()},
            "airtime_window_s": {"main": round(self.airtime.used(now), 3), "libretag": round(self.lt_airtime.used(now), 3)},
            "airtime_cap_s": {"main": self.airtime.cap_s, "libretag": self.lt_airtime.cap_s},
            "radio_silence_s": round(self.radio_silence_s(now), 1),
            "radio": {"state": self.health.state, "since": int(self.health.since), "reason": self.health.reason or None,
                      "degraded": self.health.degraded},
            "mailbox_tags": self.ledger.mailbox_size(),
            "outbox": self.ledger.outbox_size(),
            "counters": dict(sorted(self.stats.c.items())),
        }

    def write_status(self, now: float | None = None) -> dict:
        st = self.status(now)
        path = self.cfg.status.path
        if path:
            try:
                d = os.path.dirname(os.path.expanduser(path)) or "."
                os.makedirs(d, exist_ok=True)
                tmp = os.path.expanduser(path) + ".tmp"
                with open(tmp, "w") as f:
                    json.dump(st, f, indent=1)
                os.replace(tmp, os.path.expanduser(path))
            except OSError as e:
                log.warning("status file: %s", e)
        return st

    def heartbeat(self, now: float) -> dict:
        st = self.write_status(now)
        h = st["last_hour"]
        log.info("heartbeat heard=%d published=%d dropped=%d spam_rejected=%d sent=%d airtime=%.1fs "
                 "(window main %.1f/%.1f s, libretag %.1f/%.1f s) mailbox=%d outbox=%d",
                 h["heard"], h["published"], h["dropped"], h["spam_rejected"], h["sent_to_radio"], h["airtime_s"],
                 st["airtime_window_s"]["main"], st["airtime_cap_s"]["main"], st["airtime_window_s"]["libretag"],
                 st["airtime_cap_s"]["libretag"], st["mailbox_tags"], st["outbox"])
        self._heartbeat_base = dict(st["total"])
        self._last_heartbeat = now
        return st

    # ------------------------------------------------------------ durable publishing

    async def publish_durable(self, ev: dict, ledger_key: str, stat: str, expires: float | None = None) -> bool:
        """Publish; if no relay takes it, it stays in the outbox and is retried until it expires."""
        now = self.clock()
        exp = now + self.cfg.outbox_ttl_s
        if expires is not None:
            exp = min(exp, expires)
        self.ledger.outbox_put(ev, ledger_key, stat, exp, now)
        res = await self.pool.publish(ev)
        if any(ok for ok, _ in res.values()):
            self.ledger.outbox_done(ev["id"])
            self.ledger.mark_published(ledger_key, self.clock())
            self.stats.inc(stat)
            return True
        self.stats.inc("publish_deferred")
        log.warning("publish %s failed, kept in the outbox: %s", ev["id"][:12], res)
        return False

    async def retry_outbox(self, now: float) -> None:
        for ev, key, stat in self.ledger.outbox_due(now):
            res = await self.pool.publish(ev, retry=False)
            if any(ok for ok, _ in res.values()):
                self.ledger.outbox_done(ev["id"])
                self.ledger.mark_published(key, self.clock())
                self.stats.inc(stat)
                self.stats.inc("outbox_published")
            else:
                self.ledger.outbox_failed(ev["id"], now)

    async def drain(self):
        """Wait until every spawned task (publishes, holds, sends) is done."""
        while self.tasks:
            pending = [t for t in self.tasks if not t.done()]
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)
            # A finished task leaves the set through its done callback (tasks.discard), which runs on a
            # later loop iteration. gather() over finished tasks returns at once without yielding
            # (Python 3.12+), so without this yield drain() spins forever and the callback never runs.
            await asyncio.sleep(0)

    # ------------------------------------------------------------ own transmissions

    def preset(self) -> tuple[int, float, int]:
        """LoRa preset for the airtime formula: the node's own (Local API `radio.stats`), else [airtime]."""
        a = self.cfg.airtime
        cfg = (a.sf, float(a.bw_hz), a.cr)
        node = getattr(self.radio, "preset", None)
        if not node:
            return cfg
        node = (int(node[0]), float(node[1]), int(node[2]))
        if node != cfg and node != self._preset_warned:
            self._preset_warned = node
            log.warning("the node runs SF%d / %.1f kHz / CR4/%d but [airtime] says SF%d / %.1f kHz / CR4/%d; "
                        "using the node's preset", node[0], node[1] / 1000, node[2], cfg[0], cfg[1] / 1000, cfg[2])
        return node

    def airtime_s(self, datagram_len: int, route_len: int = 0, path_hash_size: int = 1,
                  region: str | None = None) -> float:
        sf, bw, cr = self.preset()
        n = 1 + 1 + route_len * path_hash_size + grp_data_len(datagram_len)
        if region:
            n += 4  # transport codes of a scoped flood
        return lora_airtime_s(n, sf, bw, cr, self.cfg.airtime.preamble)

    def budget_for(self, channel: str) -> AirtimeBudget:
        return self.lt_airtime if channel == frames.CHANNEL_LIBRETAG else self.airtime

    async def radio_send(self, channel: str, data_type: int, payload: bytes, route: list[bytes] | None = None,
                         path_hash_size: int = 1, budget_checked: bool = False, region: str | None = None,
                         spend: bool = True, priority_now: bool = False, log_fields: dict | None = None) -> bool:
        """Flood (route None; in `region` when given) or direct/zero-hop (route list). Refuses when the airtime
        budget is spent. `spend` False: counted in the stats, not in a budget (ACKs have their own cap).
        On a radio error `last_send_error` holds it. `log_fields` go on the field log's `tx` line."""
        now = self.clock()
        secs = self.airtime_s(len(payload), len(route or ()), path_hash_size, region if route is None else None)
        budget = self.budget_for(channel)
        lf = dict(log_fields or {})
        how = "flood" if route is None else "path" if route else "zero-hop"
        base = dict(channel=channel, dtype=data_type, len=len(payload), how=how, path=route,
                    phs=path_hash_size if route else None, region=region if route is None else None, airtime_s=secs)
        if "xa" not in lf and "xc" not in lf:
            base["xc"] = payload[:4]
        if spend and not budget_checked and not budget.allow(now, secs):
            self.stats.inc("tx_airtime_exhausted")
            log.warning("airtime budget for %s spent (%.1f s in the window), staying silent", channel, budget.used(now))
            fieldlog.ev("tx", **base, **lf, ok=False, error="airtime")
            return False
        self.last_send_error = None
        try:
            if route is None:
                await self.loop.run_in_executor(None, lambda: self.radio.send(channel, data_type, payload, region))
            elif priority_now and getattr(self.radio, "supports_priority", False):
                await self.loop.run_in_executor(None, lambda: self.radio.send_direct(
                    channel, data_type, payload, route, path_hash_size, priority_now=True))
            else:
                await self.loop.run_in_executor(None, self.radio.send_direct, channel, data_type, payload,
                                                route, path_hash_size)
        except RadioError as e:
            self.last_send_error = e
            self.health.on_send_error(e, self.clock())
            self.stats.inc("tx_radio_budget" if type(e).__name__ == "BudgetExceeded" else "dn_radio_error")
            log.warning("radio send failed: %s", e)
            fieldlog.ev("tx", **base, **lf, ok=False, error=str(e)[:160])
            return False
        self.health.on_send_ok(self.clock())
        fieldlog.ev("tx", **base, **lf, ok=True, result=getattr(self.radio, "last_tx_result", None))
        if spend:
            budget.spend(now, secs)
        self.stats.inc("tx_airtime_ms", int(secs * 1000))
        return True

    # ------------------------------------------------------------ mesh -> relays

    def _resolve(self, ref: bytes):
        return self.registry.resolve(ref)

    def _rendezvous(self, pk: bytes, dg: Datagram, now: float):
        """Device `pk` just transmitted and now listens: remember the route, hand over its DMs."""
        if dg.path is not None:
            # direct route back to the device: the flood path reversed
            self.routes[pk] = (list(reversed(dg.path)), dg.path_hash_size, now)
        if self.device_queue.get(pk):
            self._flush_device(pk)

    async def on_datagram(self, dg: Datagram) -> Event | None:
        lt = dg.channel == frames.CHANNEL_LIBRETAG and dg.data_type in LT_DATA_TYPES
        if not lt and (dg.channel, dg.data_type) not in ACCEPT:
            self.last_rx = self.clock()
            self.health.on_rx(self.last_rx)
            self.stats.inc("rx_ignored")
            return None
        now = self.clock()
        self.last_rx = now
        self.health.on_rx(now)
        if lt:  # own limit (lt2svc), so a #libretag flood does not use up the other channels' share
            self.lt2.on_datagram(dg, now)
            return None
        if not self.rx_global.hit(now):
            self.stats.inc("rx_rate_limited")
            return None
        payload = dg.payload
        if dg.data_type == frames.DATA_TYPE_NOSTR and payload and payload[0] & 0x3F == frames.FRAG:
            try:
                payload = self.reassembler.feed(payload, now)
            except FrameError as e:
                self.stats.inc(f"rx_bad_{e.code}")
                return None
            if payload is None:
                self.stats.inc("rx_fragment")
                return None
            prof = payload[0] & 0x3F
            dm = prof in frames.DM_PROFILES
            if not dm and not (self.cfg.uplink.allow_fragments and prof in (frames.NOTE, frames.GENERIC)):
                self.stats.inc("rx_fragment_not_allowed")
                return None
        try:
            d = frames.decode(payload, dg.data_type, self._resolve,
                              max_len=frames.MAX_REASSEMBLED if len(payload) > frames.MAX_RADIO else frames.MAX_RADIO)
        except FrameError as e:
            self.stats.inc(f"rx_bad_{e.code}")
            log.debug("dropped frame: %s", e)
            return None
        ev, name = d.event, d.profile_name
        if not self._fresh(ev, d.policy_name, now):
            self.stats.inc("rx_stale")
            return None
        if d.profile == frames.ROAD_REPORT:
            self._remember_report(ev)
        pk = bytes.fromhex(ev.pubkey)
        # rendezvous: a device we hold DMs for just spoke and listens for a moment
        if not dg.stale and now - ev.created_at <= self.cfg.downlink.rendezvous_max_age_s and self.registry.device(pk):
            self._rendezvous(pk, dg, now)
        if ev.kind == frames.INBOX_POLL_KIND:
            self.stats.inc("rx_inbox_poll")
            return ev
        if not self.ledger.add(ev.id, "radio", now, name):
            self.ledger.mark_heard(ev.id, now)
            self.stats.inc("rx_duplicate")
            return ev
        self.ledger.mark_heard(ev.id, now)
        self.stats.inc(f"rx_{name}")
        if not self._uplink_allowed(d, now):
            return ev
        self._spawn(self._publish(ev, dm=d.profile in frames.DM_PROFILES))
        return ev

    def _fresh(self, ev: Event, profile: str, now: float) -> bool:
        if ev.created_at > now + self.cfg.uplink.future_s:
            return False
        if profile == "road.report":
            t = next((x[1] for x in ev.tags if x[0] == "t"), "other")
            exp = int(next(x[1] for x in ev.tags if x[0] == "expiration"))
            return now <= min(ev.created_at + road.LIFETIME.get(t, 4 * 3600), exp)
        return now - ev.created_at <= MAX_AGE_S.get(profile, 86400)

    def _uplink_allowed(self, d: frames.Decoded, now: float) -> bool:
        up = self.cfg.uplink
        name = d.policy_name
        if not up.enabled or not up.profiles.get(name, False):
            self.stats.inc(f"up_disabled_{name}")
            return False
        pk = bytes.fromhex(d.event.pubkey)
        if d.profile in frames.DM_PROFILES:
            rcpt = bytes.fromhex(d.event.tags[0][1])
            expiring = d.profile in (frames.DM4X_FULL, frames.DM4X_REF) and up.unregistered_expiring_dm
            if not (self.registry.knows(pk) or self.registry.knows(rcpt) or up.unregistered_dm or expiring):
                self.stats.inc("up_dm_unregistered")
                return False
        if not (self.rx_pk_min.allow(now, pk) and self.rx_pk_hour.allow(now, pk)):
            self.stats.inc("up_rate_limited_pubkey")
            return False
        self.rx_pk_min.hit(now, pk)
        self.rx_pk_hour.hit(now, pk)
        return True

    async def inbox_relays(self, pubkey: str) -> list[str]:
        """The recipient's inbox relays (kind 10050 / 10002 read), cached; [] if unknown."""
        now = self.clock()
        hit = self.inbox_cache.get(pubkey, now)
        if hit is not None:
            return hit
        query = getattr(self.pool, "query", None)
        urls: list[str] = []
        if query is not None:
            try:
                evs = await query([{"kinds": list(inbox.KINDS), "authors": [pubkey], "limit": 4}])
                urls = await inbox.public_only(inbox.parse(evs, pubkey), self.resolver or self.loop.getaddrinfo)
            except Exception as e:  # noqa: BLE001
                log.warning("inbox lookup %s: %s", pubkey[:12], e)
        self.inbox_cache.put(pubkey, urls, now)
        return urls

    async def _publish(self, ev: Event, dm: bool = False):
        d = ev.to_dict()
        exp = next((t[1] for t in ev.tags if len(t) >= 2 and t[0] == "expiration" and str(t[1]).isdigit()), None)
        jobs = [self.publish_durable(d, ev.id, "published", float(exp) if exp else None)]
        if dm and self.cfg.uplink.dm_inbox_relays and getattr(self.pool, "publish_once", None):
            rcpt = ev.tags[0][1]
            extra = inbox.extra(await self.inbox_relays(rcpt), self.pool.publish_urls,
                                self.cfg.uplink.dm_inbox_max)
            if extra:
                self.stats.inc("dm_inbox_relays", len(extra))
                jobs.append(self.pool.publish_once(d, extra))
        await asyncio.gather(*jobs)

    # ------------------------------------------------------------ relays -> mesh

    def _remember_report(self, ev: Event):
        t = {x[0]: x[1] for x in ev.tags}
        try:
            self.road_positions[ev.id] = (float(t["lat"]), float(t["lon"]))
        except (KeyError, ValueError):
            return
        if len(self.road_positions) > 2000:
            self.road_positions.pop(next(iter(self.road_positions)))

    def _in_fence(self, lat: float, lon: float) -> bool:
        g = self.cfg.downlink.geofence
        return g is not None and haversine_km(g.lat, g.lon, lat, lon) <= g.radius_km

    def subscriptions(self) -> dict[str, list[dict]]:
        dn = self.cfg.downlink
        since = int(self.clock()) - 60
        subs: dict[str, list[dict]] = self.lt2.subscriptions(self.clock())
        if not dn.enabled:
            return subs
        if dn.profiles.get("dm4.ref") and self.registry.devices:
            subs["nm-dm"] = [{"kinds": [4], "#p": [d.pubkey.hex() for d in self.registry.devices], "since": since}]
        if dn.profiles.get("road.report") and dn.geofence:
            g = dn.geofence
            subs["nm-road"] = [{"kinds": [road.KIND_REPORT], "#g": geohash_cover(g.lat, g.lon, g.radius_km),
                                "since": since}]
        if dn.profiles.get("road.vote") and self.road_positions:
            ids = [i for i, (la, lo) in self.road_positions.items() if self._in_fence(la, lo)][-200:]
            if ids:
                subs["nm-vote"] = [{"kinds": [road.KIND_VOTE], "#e": ids, "since": since}]
        return subs

    async def refresh_subscriptions(self):
        for sid, filters in self.subscriptions().items():
            if sid == "nm-vote":
                ids = frozenset(filters[0]["#e"])
                if ids == self._vote_ids_subscribed:
                    continue
                self._vote_ids_subscribed = ids
            elif sid in self._subscribed:
                continue
            self._subscribed.add(sid)
            await self.pool.subscribe(sid, filters)

    async def on_relay_event(self, raw: dict, url: str = "") -> Outgoing | None:
        dn = self.cfg.downlink
        try:
            ev = Event.from_dict(raw)
        except (KeyError, TypeError):
            return None
        now = self.clock()
        if ev.kind in LT2_KINDS:
            if isinstance(ev.id, str) and isinstance(ev.tags, list):
                self.lt2.on_relay_event(ev, url, now)
            return None
        if not dn.enabled:
            return None
        if not isinstance(ev.id, str) or self.ledger.on_radio(ev.id):
            self.stats.inc("dn_already_on_radio")
            return None
        if not ev.verify():
            self.stats.inc("dn_bad_signature")
            return None
        if ev.created_at > now + 300:
            return None
        if not self.ledger.add(ev.id, "relay", now):
            self.stats.inc("dn_duplicate")  # same event from a second relay
            return None
        try:
            out = self._plan(ev, now)
        except FrameError as e:
            self.stats.inc(f"dn_unencodable_{e.code}")
            return None
        if out is None:
            return None
        if out.is_dm:
            dev = self.registry.device(out.device)
            if dev and dev.rendezvous:
                q = self.device_queue[out.device]
                if len(q) >= dn.queue_per_device:
                    q.pop(0)
                q.append(out)
                self.stats.inc("dn_dm_queued")
                return out
        self._spawn(self._transmit(out, self._hold(out)))
        return out

    def _plan(self, ev: Event, now: float) -> Outgoing | None:
        dn = self.cfg.downlink
        if ev.kind == 4:
            if not dn.profiles.get("dm4.ref"):
                return None
            x = frames.dm4_shape(ev)
            if x is None:
                return None
            rcpt, author = bytes.fromhex(ev.tags[0][1]), bytes.fromhex(ev.pubkey)
            dev = self.registry.device(rcpt)
            if dev is None or author not in dev.allow_from:
                self.stats.inc("dn_dm_not_allowed")
                return None
            if now - ev.created_at > MAX_AGE_S["dm4.ref"]:
                return None
            enc = frames.encode(ev, frames.DM4X_REF if x else frames.DM4_REF, max_len=None)
            parts = [enc.frame] if len(enc.frame) <= self.radio.max_payload else \
                frag.split(enc.frame, self.radio.max_payload)
            return Outgoing(ev.id, ev.pubkey, frames.CHANNEL_NOSTR, frames.DATA_TYPE_NOSTR, parts, True, rcpt,
                            now + dn.queue_ttl_s)
        if ev.kind == road.KIND_REPORT:
            if not dn.profiles.get("road.report") or not self._fresh(ev, "road.report", now):
                return None
            enc = frames.encode(ev, max_len=self.radio.max_payload)
            self._remember_report(ev)
            la, lo = self.road_positions.get(ev.id, (999, 999))
            if not self._in_fence(la, lo):
                self.stats.inc("dn_outside_fence")
                return None
            return Outgoing(ev.id, ev.pubkey, enc.channel, enc.data_type, [enc.frame])
        if ev.kind == road.KIND_VOTE:
            if not dn.profiles.get("road.vote") or not self._fresh(ev, "road.vote", now):
                return None
            enc = frames.encode(ev, max_len=self.radio.max_payload)
            pos = self.road_positions.get(ev.tags[0][1])
            if pos is None or not self._in_fence(*pos):
                self.stats.inc("dn_outside_fence")
                return None
            return Outgoing(ev.id, ev.pubkey, enc.channel, enc.data_type, [enc.frame])
        return None

    def _route(self, out: Outgoing, now: float):
        if not (out.is_dm and out.device and self.cfg.downlink.direct_replies
                and getattr(self.radio, "supports_direct", False)):
            return None
        r = self.routes.get(out.device)
        if r is None or now - r[2] > self.cfg.downlink.route_max_age_s:
            return None
        return r

    def _hold(self, out: Outgoing) -> float:
        dn = self.cfg.downlink
        h = self.rng.uniform(dn.hold_min_s, dn.hold_max_s)
        if out.is_dm and dn.dm_jitter_s > 0:
            x = int.from_bytes(hashlib.sha256(self.bridge_id + bytes.fromhex(out.event_id)).digest()[:4], "big")
            h += (x % int(dn.dm_jitter_s * 1000)) / 1000
        return h

    def _flush_device(self, pk: bytes):
        q, self.device_queue[pk] = self.device_queue.pop(pk, []), []
        now = self.clock()
        for out in q:
            if out.expires > now:
                # the device listens right after its uplink: short hold, no long wait
                self._spawn(self._transmit(out, self.rng.uniform(0.5, 2.0)))

    async def _transmit(self, out: Outgoing, hold: float):
        dn = self.cfg.downlink
        await asyncio.sleep(hold)
        async with self.tx_lock:
            now = self.clock()
            if self.ledger.on_radio(out.event_id):
                self.stats.inc("dn_heard_meanwhile")
                return
            gap = dn.min_gap_s - (now - self.last_tx)
            if gap > 0 and self.last_tx:
                await asyncio.sleep(gap)
                now = self.clock()
                if self.ledger.on_radio(out.event_id):
                    self.stats.inc("dn_heard_meanwhile")
                    return
            checks = [(self.tx_global, None), (self.tx_author, out.author)]
            if out.is_dm:
                checks += [(self.tx_dm, None), (self.tx_dm_dev, out.device)]
            if not all(lim.allow(now, key) for lim, key in checks):
                self.stats.inc("dn_rate_limited")
                return
            route = self._route(out, now)
            total = sum(self.airtime_s(len(p), len(route[0]) if route else 0, route[1] if route else 1)
                        for p in out.payloads)
            if not self.budget_for(out.channel).allow(now, total):
                self.stats.inc("tx_airtime_exhausted")
                return
            for lim, key in checks:
                lim.hit(now, key)
            for i, p in enumerate(out.payloads):
                if i:
                    await asyncio.sleep(dn.fragment_gap_s)
                if route is not None:
                    if not await self.radio_send(out.channel, out.data_type, p, route[0], route[1], True):
                        return
                    self.stats.inc("dn_sent_direct")
                elif not await self.radio_send(out.channel, out.data_type, p, budget_checked=True):
                    return
            self.last_tx = self.clock()
            self.ledger.mark_sent(out.event_id, self.last_tx)
            self.stats.inc("dn_sent")
            log.info("sent %s (%d frame%s) to radio", out.event_id[:12], len(out.payloads),
                     "s" if len(out.payloads) > 1 else "")

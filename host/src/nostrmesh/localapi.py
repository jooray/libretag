"""MeshCore Local API adapter: the bridge shares a radio with MeshCore Open or meshcored instead of owning
a node. The protocol is defined at https://github.com/jooray/meshcore-local-api (SPEC.md, protocol 1);
this module is a thin layer over that project's Python client, `meshcore_localapi.LocalApiClient`.

Run it on the phone (Termux) against ws://127.0.0.1:8787/api/v1, on a PC through
`adb forward tcp:8787 tcp:8787`, or next to meshcored. The client:

- pairs with a P-256 key kept in `identity_path` (the host app shows "Allow nostrmesh (AB12 CD34 …)?"
  the first time) and signs only `meshcore-local-api-pair-v1:<nonce>`;
- keeps one `channel.data` subscription per channel, asks once per connection with `channels.join` when
  a channel is missing (the user answers a prompt, up to 2 min), and replays what arrived while the
  bridge was away from a cursor stored in the same file;
- reconnects with backoff; close 4003 (not approved or revoked) is retried every 2 minutes.

Capabilities are strict (SPEC sections 8 and 13): a feature is used only if `hello.capabilities` lists
it. Here: `rx_path` / `rx_meta` for the received path and signal, `tx_path_data` for zero-hop and direct
downlinks (without it only floods go out), `regions` for the flood scope and its check against the node's
catalogue, `radio_stats` for the node's LoRa preset, `tx_result` for the last send's result, `duty_cap`
for the `budget` refusal.
"""
from __future__ import annotations

import logging
import threading
from importlib.metadata import version
from pathlib import Path

from meshcore_localapi import (READY, REFUSED, JOINING, PAIRING, JsonFileStore, LocalApiClient, LocalApiError,
                               event_path, event_payload, load_or_create_key, parse_time)

from .radio import Datagram, RadioError

log = logging.getLogger("nostrmesh.localapi")

DEFAULT_URL = "ws://127.0.0.1:8787/api/v1"
DEFAULT_IDENTITY = Path.home() / ".config" / "nostrmesh" / "meshcore-open.json"
VERSION = version("libretag")


class BudgetExceeded(RadioError):
    """The server refused to transmit: its duty-cycle cap is reached (`duty_cap`, error `budget`)."""


class Identity:
    """The bridge's key and replay cursors in one JSON file (mode 0600):
    `{"private": "<base64 PKCS#8 DER>", "cursor": {"#nostr": {"receivedAt": …, "ids": […]}}}`.
    The key is the one earlier versions wrote, so an existing pairing is kept."""

    def __init__(self, path: Path | str = DEFAULT_IDENTITY):
        self.path = Path(path)
        self.store = JsonFileStore(self.path)
        self.key = load_or_create_key(self.store)

    @property
    def public_raw(self) -> bytes:
        return self.key.public_raw

    @property
    def fingerprint(self) -> str:
        return self.key.fingerprint

    def cursor(self, channel: str) -> str | None:
        c = self.store.get_cursor(channel)
        return c.received_at if c else None


def parse_preset(result) -> tuple[int, float, int] | None:
    """`radio.stats` result -> (sf, bw Hz, cr 5..8), or None (same rule as the Android bridge)."""
    p = result.get("preset") if isinstance(result, dict) else None
    if not isinstance(p, dict):
        return None
    sf, bw, cr = p.get("sf"), p.get("bwHz"), p.get("cr")
    if not (isinstance(sf, int) and isinstance(bw, (int, float)) and isinstance(cr, int)):
        return None
    if 1 <= cr <= 4:
        cr += 4
    if not (5 <= sf <= 12 and bw > 0 and 5 <= cr <= 8):
        return None
    return sf, float(bw), cr


def region_names(result) -> set[str] | None:
    """`regions.list` result -> the names in the node's catalogue, or None (unknown)."""
    regs = result.get("regions") if isinstance(result, dict) else None
    if not isinstance(regs, list):
        return None
    return {x["name"] for x in regs if isinstance(x, dict) and isinstance(x.get("name"), str)}


class LocalApiRadio:
    def __init__(self, url: str, channels: dict[str, list[int]], identity: Identity | None = None,
                 name: str = "nostrmesh", region: str | None = None, connect=None):
        self.channels = channels  # channel -> accepted data types
        self.identity = identity or Identity()
        self.name = name
        self.region = region
        self.region_checked = False
        self.preset: tuple[int, float, int] | None = None  # (sf, bw Hz, cr 5..8) from radio.stats
        self.freq_hz: int | None = None
        self.last_tx_result: dict | None = None
        self._on_rx = None
        self._region_noted = False
        self.client = LocalApiClient(url, key=self.identity.key, name=name, version=VERSION,
                                     cursor_store=self.identity.store, on_event=self._event,
                                     on_hello=self._on_hello, connect=connect)
        for ch, types in channels.items():
            self.client.subscribe({"events": ["channel.data"], "channel": ch, "dataTypes": types},
                                  cursor_key=ch, join=True)

    # -- what the bridge reads
    @property
    def capabilities(self) -> frozenset[str]:
        return self.client.capabilities

    @property
    def supports_direct(self) -> bool:
        return "tx_path_data" in self.capabilities

    @property
    def max_payload(self) -> int:
        return self.client.max_payload

    @property
    def last_activity(self) -> float:
        return self.client.last_activity

    @property
    def radio_connected(self) -> bool:
        return self.client.radio_connected

    @property
    def subscribed(self) -> set[str]:
        return {s.channel for s in self.client.subscriptions if s.active}

    @property
    def state(self) -> str:
        s, detail = self.client.state, self.client.state_detail
        if s == PAIRING:
            return f"waiting for approval in the host app ({self.identity.fingerprint})"
        if s == JOINING:
            return f"waiting for you to allow {detail} in the host app"
        if s == REFUSED:
            return "not approved in the host app"
        if s == READY:
            return "connected" if self.subscribed else "paired"
        return s

    # -- after hello (reader thread: requests go to their own threads)
    def _on_hello(self, hello: dict):
        self._read_freq()
        if "radio_stats" in self.capabilities:
            threading.Thread(target=self._fetch_preset, daemon=True).start()
        if self.region and not self.region_checked and "regions" in self.capabilities:
            threading.Thread(target=self._check_region, daemon=True).start()

    def _read_freq(self):
        """The frequency, for the bridge announcement (PROTOCOL.md 4.1)."""
        radio = (self.client.device or {}).get("radio")
        f = radio.get("freqHz") if isinstance(radio, dict) else None
        if isinstance(f, (int, float)) and f > 0:
            self.freq_hz = int(f)

    def _fetch_preset(self):
        try:
            stats = self.client.request("radio.stats", {})
        except LocalApiError as e:
            log.warning("radio.stats: %s", e.code)
            return
        pr = parse_preset(stats)
        if not self.freq_hz:
            # hello.device may leave the radio out (meshcored): radio.stats' preset, else device.info
            f = ((stats or {}).get("preset") or {}).get("freqHz") if isinstance(stats, dict) else None
            if not (isinstance(f, (int, float)) and f > 0):
                try:
                    info = self.client.request("device.info", {})
                    f = ((info or {}).get("radio") or {}).get("freqHz") if isinstance(info, dict) else None
                except LocalApiError:
                    f = None
            if isinstance(f, (int, float)) and f > 0:
                self.freq_hz = int(f)
        if pr is not None:
            self.preset = pr
            log.info("radio preset from the node: SF%d / %.1f kHz / CR4/%d%s", pr[0], pr[1] / 1000, pr[2],
                     f" / {self.freq_hz / 1e6:.3f} MHz" if self.freq_hz else "")

    def _check_region(self):
        """The configured flood scope must be in the node's catalogue: the node refuses every send with
        an unknown one (`unknown_region`), so drop it and flood in the node's default scope instead."""
        try:
            names = region_names(self.client.request("regions.list", {}))
        except LocalApiError:
            return
        if names is None:
            return
        self.region_checked = True
        if self.region in names:
            log.info("flood region %s is in the node's catalogue", self.region)
        else:
            log.warning("flood region %r is not in the node's catalogue (%s): flooding in the node's default "
                        "scope", self.region, ", ".join(sorted(names)) or "empty")
            self.region = None

    def _event(self, ev: dict, sub=None):
        if ev.get("type") != "channel.data" or ev.get("outgoing"):
            return
        ch, dt = ev.get("channel"), ev.get("dataType")
        if ch not in self.channels or dt not in self.channels[ch]:
            return
        payload = event_payload(ev)
        if payload is None:
            return
        t = parse_time(ev.get("receivedAt"))
        dg = Datagram(ch, dt, payload, ev.get("hops") if isinstance(ev.get("hops"), int) else None,
                      ev.get("snrDb") if isinstance(ev.get("snrDb"), (int, float)) else None,
                      rx_time=t.timestamp() if t else None)
        if "rx_meta" in self.capabilities:
            dg.rssi = ev.get("rssiDbm") if isinstance(ev.get("rssiDbm"), (int, float)) else None
            rt = ev.get("routeType")
            dg.route_type = rt if rt in ("flood", "direct") else None
        if "rx_path" in self.capabilities:
            p = event_path(ev)
            if p is not None and (dg.hops is None or dg.hops == len(p[0])):
                dg.path, dg.path_hash_size = p
                dg.hops = len(p[0])
        if self._on_rx:
            self._on_rx(dg)

    # -- Radio
    def start(self, on_rx):
        self._on_rx = on_rx
        self.client.start()

    def _send(self, channel: str, data_type: int, payload: bytes, **kw) -> None:
        if len(payload) > self.max_payload:
            raise RadioError("payload too long")
        try:
            r = self.client.send_data(channel, data_type, payload, **kw)
        except LocalApiError as e:
            if e.code == "budget":
                raise BudgetExceeded("channel.sendData refused: budget") from e
            raise RadioError(f"channel.sendData refused: {e.code}") from e
        if "tx_result" in self.capabilities:
            self.last_tx_result = r or None

    def send(self, channel: str, data_type: int, payload: bytes) -> None:
        """Flood (no `path`), in the configured region when the server takes one (`regions`)."""
        region = self.region
        if region and "regions" not in self.capabilities:
            if not self._region_noted:
                self._region_noted = True
                log.warning("the server does not advertise regions: flooding in its default scope, not %s", region)
            region = None
        self._send(channel, data_type, payload, region=region or None)

    def send_direct(self, channel: str, data_type: int, payload: bytes, route: list[bytes],
                    path_hash_size: int = 1) -> None:
        """Direct along `route` (next hop first), zero-hop when empty. Needs `tx_path_data`: a server
        without it would ignore the path and flood, so this refuses."""
        if not self.supports_direct:
            raise RadioError("server does not advertise tx_path_data")
        if any(len(h) != path_hash_size for h in route):
            raise RadioError("path entry size mismatch")
        self._send(channel, data_type, payload, path=list(route), path_hash_size=path_hash_size)

    def close(self):
        self.client.close()

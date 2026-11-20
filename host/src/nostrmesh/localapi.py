"""MeshCore Open local API adapter (jooray/meshcore-open, documentation/LOCAL_API.md).

Lets the bridge share a phone's radio with MeshCore Open instead of owning a node: run it on the
phone (Termux) against ws://127.0.0.1:8787/api/v1, or on a PC through `adb forward tcp:8787 tcp:8787`.
Behaviour follows Roadbeacon/LibreTag's Kotlin client (MeshApiSession):

- pairing: a P-256 key kept in `identity_path`; MeshCore Open shows "Allow nostrmesh (AB12 CD34 …)?"
  the first time; the client signs `meshcore-open-pair-v1:<nonce>` once per connection;
- after `hello`: subscribe per channel while the radio is connected; `unknown_channel` -> one
  `channels.join` per connection (the user answers a prompt in MeshCore Open, up to 2 min);
- `device` messages re-subscribe a connection that holds no subscription; the replay cursor
  (`receivedAt`) is persisted so a restart does not lose or repeat frames;
- close 4003 = not approved / revoked: retry every 2 minutes.

Extensions (SPEC 6.2, names agreed with the meshcored daemon) are used only when `hello.capabilities`
(a top-level array of strings) lists them:

- `rx_path`: received events carry `path` (hex, first hop first) and `pathHashSize`;
- `rx_meta`: `hops`, `routeType`, `snrDb`, `rssiDbm`;
- `tx_path_data`: `channel.sendData` takes `path` (hex) and `pathHashSize`: missing = flood,
  "" = zero-hop, non-empty = direct. Without it the bridge sends no downlink except an allowed flood
  (an older server ignores `path` and would flood);
- `tx_result`, `airtime`, `duty_cap` (error code `budget`), `noise_floor`, `raw_rx`, `raw_tx`,
  `tx_path_text`: recorded, not needed by the bridge yet.
"""
from __future__ import annotations

import base64
import hashlib
import itertools
import json
import logging
import os
import re
import threading
import time
from pathlib import Path

from datetime import datetime

from .radio import Datagram, RadioError

log = logging.getLogger("nostrmesh.localapi")

PAIRING_PREFIX = "meshcore-open-pair-v1:"
NONCE_SHAPE = re.compile(r"^[A-Za-z0-9_-]{16,128}={0,2}$")
CLOSE_REFUSED = 4003
DEFAULT_URL = "ws://127.0.0.1:8787/api/v1"
DEFAULT_IDENTITY = Path.home() / ".config" / "nostrmesh" / "meshcore-open.json"
CAPABILITIES = ("tx_path_data", "tx_path_text", "rx_path", "rx_meta", "raw_rx", "raw_tx", "tx_result",
                "airtime", "duty_cap", "noise_floor")


class BudgetExceeded(RadioError):
    """The server refused to transmit: its duty-cycle cap is reached (`duty_cap`, error `budget`)."""


def _parse_time(v) -> float | None:
    if not isinstance(v, str):
        return None
    try:
        return datetime.fromisoformat(v.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


class Identity:
    def __init__(self, path: Path | str = DEFAULT_IDENTITY):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec

        self.path = Path(path)
        self.state: dict = {}
        if self.path.exists():
            self.state = json.loads(self.path.read_text())
        if "private" in self.state:
            self.key = serialization.load_der_private_key(base64.b64decode(self.state["private"]), None)
        else:
            self.key = ec.generate_private_key(ec.SECP256R1())
            der = self.key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                                         serialization.NoEncryption())
            self.state["private"] = base64.b64encode(der).decode()
            self.save()
        nums = self.key.public_key().public_numbers()
        self.public_raw = nums.x.to_bytes(32, "big") + nums.y.to_bytes(32, "big")

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.state))
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)

    @property
    def fingerprint(self) -> str:
        h = hashlib.sha256(self.public_raw).digest()[:8].hex().upper()
        return " ".join(h[i:i + 4] for i in range(0, 16, 4))

    def sign(self, message: str) -> str:
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec

        return base64.b64encode(self.key.sign(message.encode(), ec.ECDSA(hashes.SHA256()))).decode()

    def cursor(self, channel: str) -> str | None:
        return self.state.get("cursor", {}).get(channel)

    def set_cursor(self, channel: str, value: str):
        self.state.setdefault("cursor", {})[channel] = value
        self.save()


class LocalApiRadio:
    max_payload = 163  # BLE companion link

    def __init__(self, url: str, channels: dict[str, list[int]], identity: Identity | None = None,
                 name: str = "nostrmesh", region: str | None = None, connect=None):
        if not url.startswith(("ws://127.0.0.1", "ws://localhost", "ws://[::1]")):
            raise ValueError("the local API is loopback only")
        self.url = url
        self.channels = channels  # channel -> accepted data types
        self.identity = identity or Identity()
        self.name = name
        self.region = region
        self._connect = connect
        self._ws = None
        self._ids = itertools.count(1)
        self._pending: dict[int, list] = {}
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._on_rx = None
        self.subscribed: set[str] = set()
        self.radio_connected = False
        self.state = "disconnected"
        self._cursor_dirty = 0.0
        self.capabilities: frozenset[str] = frozenset()
        self.last_tx_result: dict | None = None
        self.last_activity = 0.0  # unix s of the last message from the server (watchdog)
        self.preset: tuple[int, float, int] | None = None  # (sf, bw Hz, cr 5..8) from radio.stats
        self.freq_hz: int | None = None
        self.region_checked = False

    @property
    def supports_direct(self) -> bool:
        return "tx_path_data" in self.capabilities

    # -- plumbing
    def _send_json(self, obj):
        with self._lock:
            if self._ws is None:
                raise RadioError("local API not connected")
            self._ws.send(json.dumps(obj, separators=(",", ":")))

    def request(self, method: str, params: dict, timeout: float = 15.0) -> dict | None:
        rid = next(self._ids)
        slot = [threading.Event(), None]
        self._pending[rid] = slot
        try:
            self._send_json({"id": rid, "method": method, "params": params})
            slot[0].wait(timeout)
            return slot[1]
        finally:
            self._pending.pop(rid, None)

    @staticmethod
    def _ok(r) -> bool:
        return bool(r and r.get("ok") is True)

    @staticmethod
    def _code(r) -> str | None:
        return (r or {}).get("error", {}).get("code") if isinstance((r or {}).get("error"), dict) else None

    @staticmethod
    def parse_preset(r) -> tuple[int, float, int] | None:
        """`radio.stats` answer -> (sf, bw Hz, cr 5..8), or None (same rule as the Android bridge)."""
        if not (isinstance(r, dict) and r.get("ok") is True and isinstance(r.get("result"), dict)):
            return None
        p = r["result"].get("preset")
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

    def _fetch_preset(self):
        try:
            pr = self.parse_preset(self.request("radio.stats", {}))
        except RadioError:
            return
        try:  # the frequency, for the bridge announcement (PROTOCOL.md 4.1)
            info = (self.request("device.info", {}) or {}).get("result") or {}
            f = (info.get("radio") or {}).get("freqHz") if isinstance(info, dict) else None
            if isinstance(f, (int, float)) and f > 0:
                self.freq_hz = int(f)
        except RadioError:
            pass
        if pr is not None:
            self.preset = pr
            log.info("radio preset from the node: SF%d / %.1f kHz / CR4/%d%s", pr[0], pr[1] / 1000, pr[2],
                     f" / {self.freq_hz / 1e6:.3f} MHz" if self.freq_hz else "")

    @staticmethod
    def region_names(r) -> set[str] | None:
        """`regions.list` answer -> the names in the node's catalogue, or None (unknown)."""
        if not (isinstance(r, dict) and r.get("ok") is True and isinstance(r.get("result"), dict)):
            return None
        regs = r["result"].get("regions")
        if not isinstance(regs, list):
            return None
        return {x["name"] for x in regs if isinstance(x, dict) and isinstance(x.get("name"), str)}

    def _check_region(self):
        """The configured flood scope must be in the node's catalogue: the node refuses every send with
        an unknown one (`unknown_region`), so drop it and flood in the node's default scope instead."""
        try:
            names = self.region_names(self.request("regions.list", {}))
        except RadioError:
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

    # -- session
    def _subscribe_all(self):
        threading.Thread(target=self._subscribe_worker, daemon=True).start()

    def _subscribe_worker(self):
        for ch, types in self.channels.items():
            if ch in self.subscribed:
                continue
            params = {"events": ["channel.data"], "channel": ch, "dataTypes": types}
            if self.identity.cursor(ch):
                params["since"] = self.identity.cursor(ch)
            r = self.request("events.subscribe", params)
            if not self._ok(r) and self._code(r) == "unknown_channel" and self.radio_connected:
                self.state = f"waiting for you to allow {ch} in MeshCore Open"
                log.info("asking MeshCore Open to add %s", ch)
                j = self.request("channels.join", {"name": ch}, timeout=150)
                if self._ok(j):
                    r = self.request("events.subscribe", params)
                else:
                    log.warning("channels.join %s: %s", ch, self._code(j))
            if self._ok(r):
                self.subscribed.add(ch)
                log.info("subscribed %s", ch)
            else:
                log.warning("subscribe %s refused: %s", ch, self._code(r))
        if self.subscribed:
            self.state = "connected"

    def _handle(self, msg: dict):
        t = msg.get("type")
        if t == "pairing.challenge":
            nonce = msg.get("nonce", "")
            if not NONCE_SHAPE.match(nonce):
                raise RadioError("malformed pairing challenge")
            self.state = f"waiting for approval in MeshCore Open ({self.identity.fingerprint})"
            log.info("approve '%s' in MeshCore Open, fingerprint %s", self.name, self.identity.fingerprint)
            self._send_json({"type": "pair", "signature": self.identity.sign(PAIRING_PREFIX + nonce)})
            return
        if t == "hello":
            caps = msg.get("capabilities")
            self.capabilities = frozenset(c for c in caps if isinstance(c, str)) if isinstance(caps, list) \
                else frozenset()
            self.radio_connected = (msg.get("device") or {}).get("connected", True) is not False
            log.info("local API capabilities: %s", ", ".join(sorted(self.capabilities)) or "none")
            log.info("local API hello, radio %s", "connected" if self.radio_connected else "away")
            if "airtime" in self.capabilities:
                threading.Thread(target=self._fetch_preset, daemon=True).start()
            if self.region and not self.region_checked:
                threading.Thread(target=self._check_region, daemon=True).start()
            if self.radio_connected:
                self._subscribe_all()
            return
        if t == "device":
            self.radio_connected = (msg.get("device") or {}).get("connected", True) is not False
            if self.radio_connected and set(self.channels) - self.subscribed:
                self._subscribe_all()
            return
        if t == "event":
            self._event(msg.get("event") or {})
            return
        rid = msg.get("id")
        if isinstance(rid, int) and rid in self._pending:
            slot = self._pending[rid]
            slot[1] = msg
            slot[0].set()

    def _event(self, ev: dict):
        if ev.get("type") != "channel.data" or ev.get("outgoing"):
            return
        ch, dt = ev.get("channel"), ev.get("dataType")
        if ch not in self.channels or dt not in self.channels[ch]:
            return
        try:
            payload = base64.b64decode(ev.get("payload", ""), validate=True)
        except ValueError:
            return
        dg = Datagram(ch, dt, payload, ev.get("hops") if isinstance(ev.get("hops"), int) else None,
                      ev.get("snrDb") if isinstance(ev.get("snrDb"), (int, float)) else None,
                      rx_time=_parse_time(ev.get("receivedAt")))
        if "rx_meta" in self.capabilities:
            dg.rssi = ev.get("rssiDbm") if isinstance(ev.get("rssiDbm"), (int, float)) else None
            rt = ev.get("routeType")
            dg.route_type = rt if rt in ("flood", "direct") else None
        if "rx_path" in self.capabilities and isinstance(ev.get("path"), str):
            hs = ev.get("pathHashSize", 1)
            try:
                raw = bytes.fromhex(ev["path"])
            except ValueError:
                raw = None
            if raw is not None and hs in (1, 2, 3) and len(raw) % hs == 0:
                path = [raw[i:i + hs] for i in range(0, len(raw), hs)]
                if dg.hops is None or dg.hops == len(path):
                    dg.path, dg.path_hash_size = path, hs
                    dg.hops = len(path)
        if self._on_rx:
            self._on_rx(dg)
        if ev.get("receivedAt"):
            self.identity.state.setdefault("cursor", {})[ch] = ev["receivedAt"]
            if time.monotonic() - self._cursor_dirty > 10:
                self._cursor_dirty = time.monotonic()
                self.identity.save()

    def _run(self):
        if self._connect is None:
            from websockets.sync.client import connect

            self._connect = connect
        backoff = 5
        while not self._stop.is_set():
            code = None
            try:
                with self._connect(self.url, open_timeout=10) as ws:
                    self._ws = ws
                    self.subscribed.clear()
                    ws.send(json.dumps({"type": "hello", "client": {
                        "name": self.name, "version": "0.1",
                        "publicKey": base64.b64encode(self.identity.public_raw).decode()}}))
                    for raw in ws:
                        try:
                            msg = json.loads(raw)
                        except ValueError:
                            continue
                        self.last_activity = time.time()
                        if isinstance(msg, dict):
                            try:
                                self._handle(msg)
                            except Exception:  # noqa: BLE001  (one bad message must not drop the link)
                                log.exception("local API message")
                        backoff = 5
                    code = getattr(ws, "close_code", None)
            except Exception as e:  # noqa: BLE001
                code = getattr(getattr(e, "rcvd", None), "code", None)
                log.warning("local API: %s", e)
            finally:
                self._ws = None
                self.subscribed.clear()
                self.identity.save()
            if self._stop.is_set():
                break
            if code == CLOSE_REFUSED:
                self.state = "not approved in MeshCore Open"
                log.warning("MeshCore Open refused or revoked this client; retrying in 2 min")
                self._stop.wait(120)
            else:
                self.state = "disconnected"
                self._stop.wait(backoff)
                backoff = min(backoff * 2, 60)

    # -- Radio
    def start(self, on_rx):
        self._on_rx = on_rx
        threading.Thread(target=self._run, daemon=True, name="localapi").start()

    def _send_data(self, params: dict) -> None:
        r = self.request("channel.sendData", params)
        if not self._ok(r):
            code = self._code(r)
            if code == "budget":
                raise BudgetExceeded("channel.sendData refused: budget")
            raise RadioError(f"channel.sendData refused: {code or 'timeout'}")
        if "tx_result" in self.capabilities:
            self.last_tx_result = r.get("result") if isinstance(r.get("result"), dict) else None

    def send(self, channel: str, data_type: int, payload: bytes) -> None:
        """Flood (no `path` parameter)."""
        if len(payload) > self.max_payload:
            raise RadioError("payload too long")
        params = {"channel": channel, "dataType": data_type, "payload": base64.b64encode(payload).decode()}
        if self.region:
            params["region"] = self.region
        self._send_data(params)

    def send_direct(self, channel: str, data_type: int, payload: bytes, route: list[bytes],
                    path_hash_size: int = 1) -> None:
        """Direct along `route` (next hop first), zero-hop when empty. Needs `tx_path_data`: an older
        server would ignore the path and flood, so without it this refuses."""
        if not self.supports_direct:
            raise RadioError("server does not advertise tx_path_data")
        if len(payload) > self.max_payload:
            raise RadioError("payload too long")
        if any(len(h) != path_hash_size for h in route):
            raise RadioError("path entry size mismatch")
        self._send_data({"channel": channel, "dataType": data_type, "payload": base64.b64encode(payload).decode(),
                         "path": b"".join(route).hex(), "pathHashSize": path_hash_size})

    def close(self):
        self._stop.set()
        ws = self._ws
        if ws is not None:
            try:
                ws.close()
            except Exception:  # noqa: BLE001
                pass

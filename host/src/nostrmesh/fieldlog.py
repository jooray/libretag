"""Field-test log: one JSON object per line, for `libretag fieldeval` (docs/design/field-test-logs.md, quiet mesh
plan 9.9).

Every line has `t` (unix ms, as in the app's log), `src` ("bridge"), `bridge` (the [bridge] name) and `ev`. Join
keys across components: `x` (8 hex) for a report copy, `xc` for a command, `xa` for an ACK. No keys and no
plaintext. The events:

- `start` / `stop`: version, adapter, `capabilities` (tx_path_data, rx_path, rx_meta, rx_scope, rx_queued,
  tx_now, ...), regions, zero_hop, path; the stop reason.
- `rx`: every #libretag packet: `x`, `len`, `kind` (report | ble | cmd | ack | other), `route` (flood, tflood,
  direct, tdirect), `hops` (absent for a DIRECT packet: unknown), `path`, `phs`, `scope` or `transport`, `snr`,
  `rssi`, `rx_time`, `stale` or `queued`, `first` (first hearing or a duplicate), `heard_direct`, and `result`:
  the counters the packet moved (rx_lt2_report, lt_rx_duplicate, lt_lock, lt_rx_route_*, lt_rx_hops_*, ...).
- `lock`: `x`, `keys` (count), `new`, `lead_s` (how long before the report the key was known).
- `report_pub`: `x`, event `id`, `created_at`, `heard_at`, `delay_s`, `approx`, `receipt`, per relay `ok`;
  or `skipped` when enough relays already had it. `publish`: one relay's answer to one event (id, kind, url,
  ok, msg, ms, tries).
- `mailbox`: a kind 4244 new to this bridge: `id`, `kind` (anchor | cmd | late | timed | ack), `y`, `via`, `at`,
  `route`, `fb`, `g`, `p`, `blind`, `created_at`, `known_at`, `result` (lt_mailbox_added, lt_ack_added, or the
  refusal counter).
- `claim`: `y`, `own` or a rival's `pubkey`, `hops`, `won`, `wait_ms`. `ack_note`: an "ack sent" note seen.
- `tx`: every own transmission: `xc` or `xa`, `what` (cmd | ack_near | ack_routed | fallback; absent for other
  channels), `how` (zero-hop | path | flood), `path`, `region`, `dtype`, `id`, `at`, `late_ms` (send minus
  `at`), `airtime_s`, `ok`, `error`, `tries`.
- `tx_skip`: the same ids and `reason`: dup_on_air, via_gap, hourly_cap, airtime, missed, no_path, not_direct,
  cancelled, group_done, radio, lost_claim, ack_note, late, blocked; `miss` true when a miss note went out.
- `fallback`: `id`, `xc`, `state` (armed | cancelled | fired | failed), the cancelling `x`, `ms` from the send.
- `answer_seen`: the report `x` that answered a send, `ms` after it.
- `path_use` (B6): `x`, `via`, `hops`, `what` (cmd | ack_routed), `confirmed`.
- `radio_up`, `radio_down` (`reason`: api_says_down, send_failed, rx_silence, probe_timeout), `radio_degraded`
  (late_answers), `recover` (`step`: reboot, reopen, reset, hook, person; `result`, `round`), `hook`.
- `relay_up` / `relay_down`: relay connection changes (url, why).
- `clock`: hourly median offset (`offset_ms`) of the bridge clock against the created_at of live ephemeral
  events from the relays, `n` samples.

Files: `<dir>/bridge-YYYYMMDD.jsonl`, UTC day; files older than `keep_days` are deleted at the
change of day. Logging never raises: a full disk loses lines, not the bridge.
"""
from __future__ import annotations

import json
import logging
import threading
import time
from pathlib import Path

log = logging.getLogger("nostrmesh.fieldlog")

_lock = threading.Lock()
_dir: Path | None = None
_name = ""
_keep_days = 30
_day = ""
_fh = None


def configure(directory: str | Path | None, name: str = "", keep_days: int = 30) -> None:
    """Turn the log on (directory) or off (None)."""
    global _dir, _name, _keep_days, _day, _fh
    with _lock:
        if _fh:
            try:
                _fh.close()
            except OSError:
                pass
        _fh, _day = None, ""
        _dir = Path(directory).expanduser() if directory else None
        _name, _keep_days = name, keep_days
        if _dir:
            _dir.mkdir(parents=True, exist_ok=True)


def enabled() -> bool:
    return _dir is not None


def _clean(v):
    if isinstance(v, (bytes, bytearray)):
        return bytes(v).hex()
    if isinstance(v, (list, tuple, set, frozenset)):
        return [_clean(x) for x in v]
    if isinstance(v, dict):
        return {str(k): _clean(x) for k, x in v.items()}
    if isinstance(v, float):
        return round(v, 3)
    return v


def ev(event: str, t: float | None = None, **fields) -> None:
    """`t` is ignored: lines always carry the wall clock (the bridge's clock is injectable in tests)."""
    if _dir is None:
        return
    now = time.time()
    rec = {"t": int(now * 1000), "src": "bridge", "bridge": _name, "ev": event}
    rec.update({k: _clean(v) for k, v in fields.items() if v is not None})
    try:
        line = json.dumps(rec, separators=(",", ":"), default=str)
    except (TypeError, ValueError):
        return
    with _lock:
        try:
            _write(now, line)
        except OSError as e:  # noqa: PERF203
            log.warning("field log: %s", e)


def _write(now: float, line: str) -> None:
    global _day, _fh
    day = time.strftime("%Y%m%d", time.gmtime(now))
    if day != _day or _fh is None:
        if _fh:
            _fh.close()
        _day = day
        _fh = open(_dir / f"bridge-{day}.jsonl", "a", buffering=1)
        _prune(now)
    _fh.write(line + "\n")


def _prune(now: float) -> None:
    cutoff = time.strftime("%Y%m%d", time.gmtime(now - _keep_days * 86400))
    for f in _dir.glob("bridge-*.jsonl"):
        if f.stem.removeprefix("bridge-") < cutoff:
            try:
                f.unlink()
            except OSError:
                pass


def counters_moved(before: dict, after: dict) -> list[str]:
    """Stats keys that grew between two snapshots (what a packet or an event did)."""
    return sorted(k for k, v in after.items() if v != before.get(k, 0))

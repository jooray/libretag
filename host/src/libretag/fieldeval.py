"""Field-test evaluation: merge the tag's field log, bridge logs and app logs into one timeline.

Inputs (JSON lines, any mix, files or directories; quiet-mesh plan 9.9 and docs/design/field-test-logs.md):

- tag: `libretag fieldlog` output (records with `type`, `seq`, `n`, `j`, `x` when the CLI had the keys, ISO `t`, `pos`; firmware
  0.10.0 also `wake`, one per reporting wake-up with `n`, `why`, `copies`, `link_before`/`link_after`,
  `ack_form`, `ack_ms`, `cmd_*`, `lvl`, `rep_ms`, and `chk`, one per slot window with `slot_k`, `rx_ms`,
  `rx_open_ms`);
- bridge: `bridge-YYYYMMDD.jsonl` from nostrmesh (`src` "bridge") or the Android bridge;
- app: `fieldlog/app-YYYYMMDD.jsonl` from the LibreTag app (`src` "app").

Join keys: a report copy is `x` (its first 4 bytes, the `y` tag of its kind 4243 event). Bridges know
only `x`. A tag TX record has `x` when `libretag fieldlog` had the tag's keys; otherwise the app's
`open` lines give `x` with `n` and `j`, which ties the tag's own records (by `n` and `j`) to what the
bridges heard. Without an app log a tag record is matched to a bridge hearing
by time (within `match_s`).

Quiet mesh (0.10.0) joins: the tag's `wake` by `n` to the app's `open`/`report` (`n`, `j`, `x`) and `ack`
(`n`, `xa`); the bridge's `tx` of an ACK by `xa`, of a command by `xc` and mailbox `id`; a timed send to the
tag's `chk` window it fell into, by time.

Output: a summary (reports heard by whom, hop counts, latency per stage, delivery rate, floods per
hour, commands, the quiet-mesh section: confirmations by route, hello-to-ACK time, floods by reason and
level, link changes, slot timing, fallbacks, path sends, radio state; and a coverage summary by distance
band without coordinates) and, on request, the
merged timeline, one JSON object per report copy. The timeline holds positions when the inputs do:
write it to the scratchpad, never into the repository.
"""
from __future__ import annotations

import datetime as _dt
import json
import math
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

FLOOD_ROUTES = {"flood", "tflood"}


# ---------------------------------------------------------------- reading


def _t_ms(v) -> float | None:
    """Milliseconds from unix ms, unix s or an ISO string."""
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v) if v > 1e11 else float(v) * 1000
    if isinstance(v, str):
        try:
            return _dt.datetime.fromisoformat(v.replace("Z", "+00:00")).timestamp() * 1000
        except ValueError:
            return None
    return None


def source(rec: dict) -> str:
    if rec.get("src") in ("app", "bridge"):
        return rec["src"]
    if "type" in rec and "seq" in rec:
        return "tag"
    return "?"


def read(paths: Iterable[str | Path]) -> list[dict]:
    """Every JSON object of the given files (directories: their *.jsonl), with `_src` and `_t` (ms)."""
    out = []
    for p in paths:
        p = Path(p)
        files = sorted(p.rglob("*.jsonl")) if p.is_dir() else [p]
        for f in files:
            for line in f.read_text(errors="replace").splitlines():
                line = line.strip()
                if not line.startswith("{"):
                    continue
                try:
                    r = json.loads(line)
                except ValueError:
                    continue
                if not isinstance(r, dict):
                    continue
                r["_src"] = source(r)
                r["_t"] = _t_ms(r.get("t"))
                out.append(r)
    out.sort(key=lambda r: (r["_t"] is None, r["_t"] or 0))
    return out


# ---------------------------------------------------------------- merging


@dataclass
class Copy:
    """One report copy (n, j) or, when n is unknown, one x."""
    x: str | None = None
    n: int | None = None
    j: int | None = None
    tag: str | None = None
    tag_tx: list[dict] = field(default_factory=list)       # tag tx records of this copy
    bridge_rx: dict[str, dict] = field(default_factory=dict)  # bridge -> first rx line
    bridge_dups: Counter = field(default_factory=Counter)    # bridge -> further hearings
    pub: dict | None = None                                 # first report_pub with an id
    pub_relays: dict[str, dict] = field(default_factory=dict)  # url -> publish line
    app_relay: dict[str, float] = field(default_factory=dict)  # url -> first arrival at the app (ms)
    created_at: int | None = None
    app_open: dict | None = None
    app_report: dict | None = None
    wake: dict | None = None                                # the tag's wake summary of report n (0.10.0)
    pos: tuple[float, float] | None = None

    @property
    def key(self):
        return (self.n, self.j) if self.n is not None else ("x", self.x)

    def t_tx(self) -> float | None:
        return min((r["_t"] for r in self.tag_tx if r["_t"] is not None), default=None)

    def t_heard(self) -> float | None:
        ts = [_t_ms(r.get("rx_time")) or r["_t"] for r in self.bridge_rx.values()]
        if self.app_open and self.app_open.get("via") == "node":
            ts.append(_t_ms(self.app_open.get("rx_ms")) or self.app_open["_t"])
        ts = [t for t in ts if t is not None]
        return min(ts) if ts else None


def merge(recs: list[dict], match_s: float = 30.0) -> list[Copy]:
    by_nj: dict[tuple, Copy] = {}
    by_x: dict[str, Copy] = {}
    x_of_nj: dict[tuple, str] = {}

    # the app's open lines: n, j and x together
    for r in recs:
        if r["_src"] == "app" and r.get("ev") == "open" and r.get("x") and r.get("n") is not None:
            if r.get("j") is not None:
                x_of_nj[(r["n"], r["j"])] = r["x"]

    def copy_for(x=None, n=None, j=None) -> Copy:
        if n is not None and j is not None:
            x = x or x_of_nj.get((n, j))
            c = by_nj.get((n, j))
            if c is None and x and x in by_x and by_x[x].n is None:
                c = by_x[x]
                c.n, c.j = n, j
            if c is None:
                c = Copy(x=x, n=n, j=j)
            by_nj[(n, j)] = c
            if x:
                c.x = c.x or x
                by_x[x] = c
            return c
        c = by_x.get(x)
        if c is None:
            c = by_x[x] = Copy(x=x)
        return c

    pub_ids: dict[str, Copy] = {}
    for r in recs:
        src, ev = r["_src"], r.get("ev")
        if src == "tag" and r.get("type") == "tx" and r.get("kind") == "report" and r.get("n") is not None:
            c = copy_for(x=r.get("x"), n=r["n"], j=r.get("j"))
            c.tag_tx.append(r)
            if r.get("pos") and c.pos is None:
                c.pos = (r["pos"]["lat"], r["pos"]["lon"])
        elif src == "bridge" and ev == "rx" and r.get("kind") == "report" and r.get("x"):
            c = copy_for(x=r["x"])
            b = r.get("bridge") or "?"
            if b in c.bridge_rx:
                c.bridge_dups[b] += 1
            else:
                c.bridge_rx[b] = r
        elif src == "bridge" and ev == "report_pub" and r.get("x") and r.get("id"):
            c = copy_for(x=r["x"])
            if c.pub is None:
                c.pub = r
            pub_ids[r["id"]] = c
            if r.get("created_at") is not None and c.created_at is None:
                c.created_at = int(r["created_at"])
        elif src == "app" and ev == "open" and r.get("x"):
            c = copy_for(x=r["x"], n=r.get("n"), j=r.get("j"))
            c.tag = c.tag or r.get("tag")
            if c.app_open is None:
                c.app_open = r
            if r.get("created_at") is not None and c.created_at is None:
                c.created_at = int(r["created_at"])
        elif src == "app" and ev == "relay_event" and r.get("kind") == 4243:
            for y in r.get("y") or ():
                c = by_x.get(y)
                if c is None:
                    continue  # someone else's report
                url = r.get("url") or "?"
                if url not in c.app_relay:
                    c.app_relay[url] = r["_t"]
                if r.get("created_at") is not None and c.created_at is None:
                    c.created_at = int(r["created_at"])
    # second pass: relay answers to our report events, the app's decoded reports
    for r in recs:
        if r["_src"] == "bridge" and r.get("ev") == "publish" and r.get("id") in pub_ids:
            c = pub_ids[r["id"]]
            c.pub_relays.setdefault(r.get("url") or "?", r)
        elif r["_src"] == "app" and r.get("ev") == "report" and r.get("n") is not None:
            for c in by_nj.values():
                if c.n == r["n"] and (c.tag is None or c.tag == r.get("tag")) and c.app_open is not None:
                    if c.app_report is None:
                        c.app_report = r
                    if c.pos is None and r.get("lat") is not None:
                        c.pos = (r["lat"], r["lon"])
    # relay_event lines that came before the bridge's rx line was read (order by time already handles
    # most); a second sweep for x first seen at the bridge
    for r in recs:
        if r["_src"] == "app" and r.get("ev") == "relay_event" and r.get("kind") == 4243:
            for y in r.get("y") or ():
                c = by_x.get(y)
                if c is not None:
                    c.app_relay.setdefault(r.get("url") or "?", r["_t"])
    copies = list({id(c): c for c in [*by_nj.values(), *by_x.values()]}.values())
    wakes = {r["n"]: r for r in recs if r["_src"] == "tag" and r.get("type") == "wake" and r.get("n") is not None}
    for c in copies:
        if c.n is not None and c.n in wakes:
            c.wake = wakes[c.n]
    _match_by_time(copies, match_s)
    copies.sort(key=lambda c: c.t_tx() or c.t_heard() or 0)
    return copies


def _match_by_time(copies: list[Copy], match_s: float) -> None:
    """Without the app's open lines a tag record (n, j, no x) and a bridge hearing (x, no n) of the same
    copy stay apart: pair them when exactly one unpaired hearing lies within match_s after the tx."""
    tag_only = [c for c in copies if c.x is None and c.tag_tx]
    heard_only = [c for c in copies if c.n is None and c.bridge_rx]
    used = set()
    for c in tag_only:
        t = c.t_tx()
        if t is None:
            continue
        near = [h for h in heard_only if id(h) not in used and (h.t_heard() or 0) - t >= -2000
                and (h.t_heard() or 0) - t <= match_s * 1000]
        if len(near) == 1:
            h = near[0]
            used.add(id(h))
            c.x, c.bridge_rx, c.bridge_dups, c.pub, c.pub_relays = h.x, h.bridge_rx, h.bridge_dups, h.pub, h.pub_relays
            c.app_relay, c.created_at = h.app_relay, c.created_at or h.created_at
            copies.remove(h)


# ---------------------------------------------------------------- per copy


def _dist_m(a, b) -> float:
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371000.0 * math.asin(math.sqrt(h))


def timeline_row(c: Copy) -> dict:
    t_tx = c.t_tx()
    first_rx = min(((_t_ms(r.get("rx_time")) or r["_t"], b, r) for b, r in c.bridge_rx.items()),
                   default=None, key=lambda v: v[0] or 0)
    app_t = None
    if c.app_open is not None:
        app_t = c.app_open["_t"]
    relay_first = min(c.app_relay.values(), default=None)
    pub_t = c.pub["_t"] if c.pub else None
    acks = {u: r.get("ms") for u, r in c.pub_relays.items() if r.get("ok")}
    t0 = t_tx or (first_rx[0] if first_rx else None)
    stages = {
        "mesh_ms": (first_rx[0] - t_tx) if first_rx and t_tx else None,
        "bridge_publish_ms": (pub_t - first_rx[0]) if pub_t and first_rx else None,
        "relay_ok_ms": min(acks.values()) if acks else None,
        "relay_to_app_ms": (relay_first - pub_t) if relay_first and pub_t else None,
        "created_to_app_ms": (relay_first - c.created_at * 1000) if relay_first and c.created_at else None,
        "end_to_end_ms": (app_t - t0) if app_t and t0 else None,
    }
    tx = c.tag_tx[0] if c.tag_tx else {}
    return {
        "x": c.x, "n": c.n, "j": c.j, "tag": c.tag,
        "t_tx": t_tx, "tx_route": tx.get("route"), "tx_scoped": tx.get("scoped"), "tx_region": tx.get("region"),
        "tx_copies": len(c.tag_tx),
        "heard_by": sorted(c.bridge_rx) + (["node"] if c.app_open and c.app_open.get("via") == "node" else []),
        "bridges": {b: {"hops": r.get("hops"), "route": r.get("route"), "path": r.get("path"), "snr": r.get("snr"),
                        "rssi": r.get("rssi"), "dups": c.bridge_dups.get(b, 0)} for b, r in c.bridge_rx.items()},
        "published": bool(c.pub), "relays_ok": sorted(acks), "relays_at_app": sorted(c.app_relay),
        "app": c.app_open.get("via") if c.app_open else None,
        "delivered": c.app_open is not None,
        **{k: (round(v) if v is not None else None) for k, v in stages.items()},
        "why": c.wake.get("why") if c.wake else None,
        "link_after": c.wake.get("link_after") if c.wake else None,
        "tag_acked": c.wake.get("ack_form") if c.wake else None,
        "pos": list(c.pos) if c.pos else None,
    }


# ---------------------------------------------------------------- summary


def _pct(vals: list[float], q: float) -> float | None:
    if not vals:
        return None
    s = sorted(vals)
    i = min(len(s) - 1, max(0, math.ceil(q * len(s)) - 1))
    return s[i]


def _stat(vals) -> dict:
    vals = [v for v in vals if v is not None]
    return {"count": len(vals), "p50": _pct(vals, 0.5), "p90": _pct(vals, 0.9), "max": max(vals) if vals else None}


def hop_bucket(h) -> str:
    if h is None:
        return "unknown"
    return str(h) if h < 3 else "3+"


def commands(recs: list[dict]) -> list[dict]:
    """Per command (tag, seq): queued, sends by path, bridge transmissions, confirmation."""
    by: dict[tuple, dict] = {}
    for r in recs:
        if r["_src"] != "app" or r.get("ev") not in ("cmd_queued", "cmd_sent", "cmd_confirmed"):
            continue
        key = (r.get("tag"), r.get("seq"))
        if r["ev"] == "cmd_queued":
            by[key] = {"tag": key[0], "seq": key[1], "kind": r.get("kind"), "queued": r["_t"], "sends": Counter(),
                       "first_send": None, "confirmed": None, "mailbox_ids": set()}
            continue
        c = by.get(key)
        if c is None and r["ev"] == "cmd_sent" and key[1] is None:  # BLE adverts carry no seq
            c = next((v for v in reversed(list(by.values())) if v["tag"] == key[0] and v["confirmed"] is None), None)
        if c is None:
            continue
        if r["ev"] == "cmd_sent" and r.get("ok", True):
            c["sends"][r.get("path") or "?"] += 1
            c["first_send"] = c["first_send"] or r["_t"]
            if r.get("id"):
                c["mailbox_ids"].add(r["id"])
        elif r["ev"] == "cmd_confirmed" and c["confirmed"] is None:
            c["confirmed"] = r["_t"]
    bridge_tx: dict[str, list[dict]] = defaultdict(list)
    for r in recs:
        if r["_src"] == "bridge" and r.get("ev") in ("cmd_tx", "tx", "tx_skip") and r.get("id"):
            bridge_tx[r["id"]].append(r)
    out = []
    for c in by.values():
        txs = [r for i in c["mailbox_ids"] for r in bridge_tx.get(i, ())]
        out.append({
            "tag": c["tag"], "seq": c["seq"], "kind": c["kind"], "sends": dict(c["sends"]),
            "bridge_tx": [{"bridge": r.get("bridge"), "how": r.get("how"), "ok": r.get("ok"), "skip": r.get("reason"),
                           "late_ms": r.get("late_ms")} for r in txs],
            "queued_to_confirmed_s": round((c["confirmed"] - c["queued"]) / 1000) if c["confirmed"] else None,
            "confirmed": c["confirmed"] is not None,
        })
    return out


def floods_per_hour(recs: list[dict]) -> dict:
    tag, bridge = Counter(), Counter()
    for r in recs:
        if r["_t"] is None:
            continue
        hour = _dt.datetime.fromtimestamp(r["_t"] / 1000, _dt.timezone.utc).strftime("%Y-%m-%dT%H")
        if r["_src"] == "tag" and r.get("type") == "tx" and r.get("route") in FLOOD_ROUTES:
            tag[hour] += 1
        elif r["_src"] == "bridge" and r.get("ev") == "tx" and r.get("how") == "flood" and r.get("ok"):
            bridge[hour] += 1
    hours = sorted(set(tag) | set(bridge))
    return {h: {"tag": tag.get(h, 0), "bridge": bridge.get(h, 0)} for h in hours}


def coverage(rows: list[dict], ref: tuple[float, float] | None, band_m: float = 500.0) -> list[dict]:
    """By distance band from `ref` (default: the first position): copies sent, heard by a bridge, heard
    by the owner's node, delivered to the app. No coordinates in the output."""
    pts = [r for r in rows if r["pos"]]
    if not pts:
        return []
    ref = ref or tuple(pts[0]["pos"])
    bands: dict[int, Counter] = defaultdict(Counter)
    for r in pts:
        b = int(_dist_m(ref, r["pos"]) // band_m)
        c = bands[b]
        c["copies"] += 1
        c["bridge"] += any(h != "node" for h in r["heard_by"])
        c["node"] += "node" in r["heard_by"]
        c["delivered"] += r["delivered"]
        for name, info in r["bridges"].items():
            c[f"hops_{hop_bucket(info['hops'])}"] += 1
    return [{"from_m": int(b * band_m), "to_m": int((b + 1) * band_m), **dict(v)} for b, v in sorted(bands.items())]


def summarize(recs: list[dict], copies: list[Copy], ref=None, band_m: float = 500.0) -> dict:
    rows = [timeline_row(c) for c in copies]
    sent = [r for r in rows if r["tx_copies"]]
    base = sent or rows
    heard_hops = Counter()
    routes = Counter()
    for r in rows:
        for info in r["bridges"].values():
            heard_hops[hop_bucket(info["hops"])] += 1
            routes[info["route"] or "unknown"] += 1
    by_n: dict = defaultdict(list)
    for r in rows:
        if r["n"] is not None:
            by_n[(r["tag"], r["n"])].append(r)
    reports_delivered = sum(1 for v in by_n.values() if any(x["delivered"] for x in v))
    relay_latency = [r["created_to_app_ms"] for r in rows if r["created_to_app_ms"] is not None]
    return {
        "inputs": dict(Counter(r["_src"] for r in recs)),
        "copies": len(rows),
        "copies_sent_by_tag": len(sent),
        "reports": len(by_n),
        "reports_delivered": reports_delivered,
        "delivery_rate": round(sum(r["delivered"] for r in base) / len(base), 3) if base else None,
        "heard_by_bridge_rate": round(sum(1 for r in base if any(h != "node" for h in r["heard_by"])) / len(base), 3)
        if base else None,
        "heard_by_node_rate": round(sum(1 for r in base if "node" in r["heard_by"]) / len(base), 3) if base else None,
        "heard_by": dict(Counter(h for r in rows for h in r["heard_by"])),
        "bridge_hops": dict(sorted(heard_hops.items())),
        "bridge_routes": dict(routes),
        "latency_ms": {k: _stat([r[k] for r in rows]) for k in
                       ("mesh_ms", "bridge_publish_ms", "relay_ok_ms", "relay_to_app_ms", "created_to_app_ms",
                        "end_to_end_ms")},
        "relay_delay_over_60s": round(sum(v > 60_000 for v in relay_latency) / len(relay_latency), 3)
        if relay_latency else None,
        "floods_per_hour": floods_per_hour(recs),
        "commands": commands(recs),
        "coverage": coverage(rows, ref, band_m),
        "relay_events": _relay_health(recs),
        "quiet_mesh": quiet_mesh(recs),
    }


# ---------------------------------------------------------------- quiet mesh (0.10.0)

FLOOD_COPIES = {0, 1, 10}


def _copies(w: dict) -> list[tuple[int, int]]:
    """(j, ms after the first copy) of a wake summary's `copies` ("9@0,0@12040,10@20100")."""
    out = []
    for part in (w.get("copies") or "").split(","):
        if "@" in part:
            j, ms = part.split("@", 1)
            out.append((int(j), int(ms)))
    return out


def tag_wakes(recs: list[dict]) -> dict:
    """The tag's own view, from its `wake` records: why it reported, how, and what came back."""
    wakes = [r for r in recs if r["_src"] == "tag" and r.get("type") == "wake"]
    if not wakes:
        return {}
    why, first, links, forms, lvls = Counter(), Counter(), Counter(), Counter(), defaultdict(Counter)
    hello_ack, ack_ms, floods_by_why = [], [], Counter()
    floods = copy10 = routed = early = quick = budget = stalls = txfail = 0
    for w in wakes:
        cs = _copies(w)
        why[w.get("why")] += 1
        first[cs[0][0] if cs else None] += 1
        if w.get("link_before") != w.get("link_after"):
            links[f"{w.get('link_before')}>{w.get('link_after')}"] += 1
        forms[w.get("ack_form") or "none"] += 1
        flooded = any(j in FLOOD_COPIES for j, _ in cs)
        floods += flooded
        if flooded:
            floods_by_why[w.get("why")] += 1
        copy10 += any(j == 10 for j, _ in cs)
        routed += any(j == 11 for j, _ in cs)
        if w.get("lvl") is not None:
            lv = lvls[str(w["lvl"])]
            lv["floods"] += 1
            lv["repeated"] += w.get("rep_ms") is not None
        if w.get("ack_ms") is not None:
            ack_ms.append(w["ack_ms"])
            if cs and cs[0][0] == 9 and w.get("ack_k") == 0:
                hello_ack.append(w["ack_ms"])
        early += bool(w.get("early_sleep"))
        quick += bool(w.get("quick_answer"))
        budget += bool(w.get("budget_hit"))
        stalls += w.get("stall") or 0
        txfail += w.get("txfail") or 0
    return {
        "wakes": len(wakes), "why": dict(why), "first_copy": {str(k): v for k, v in first.items()},
        "floods": floods, "floods_by_why": dict(floods_by_why), "copies_10": copy10, "copies_11": routed,
        "flood_levels": {k: dict(v) for k, v in sorted(lvls.items())},
        "link_changes": dict(links), "acked": dict(forms),
        "ack_ms": _stat(ack_ms), "hello_to_ack_ms": _stat(hello_ack),
        "hello_ack_under_9s": round(sum(v < 9000 for v in hello_ack) / len(hello_ack), 3) if hello_ack else None,
        "early_sleep": early, "quick_answers": quick, "budget_hit": budget, "stalls": stalls, "txfail": txfail,
        "floods_24h_max": max((w.get("floods_24h") or 0 for w in wakes), default=0),
    }


def _windows(recs: list[dict]) -> list[dict]:
    """The tag's slot windows: (open ms, close ms, t_k ms, record). `chk` is written when the window
    closes; `rx_ms` is how long the receiver was on, `rx_open_ms` how long before t_k it opened."""
    out = []
    for r in recs:
        if r["_src"] == "tag" and r.get("type") == "chk" and r["_t"] is not None and r.get("rx_ms") is not None:
            close = r["_t"]
            opened = close - r["rx_ms"]
            out.append({"open": opened, "close": close, "t_k": opened + (r.get("rx_open_ms") or 0), "rec": r})
    return out


def slot_timing(recs: list[dict]) -> dict:
    """Timed sends (a bridge `tx` with `at`, the app's own-node `cmd_sent` or `ack` with `at`) against the
    tag's windows: seconds after t_k when the tag had one open, or `outside` (the tag was not listening)."""
    wins = _windows(recs)
    if not wins:
        return {}
    into, outside, sends = [], 0, 0
    for r in recs:
        timed = ((r["_src"] == "bridge" and r.get("ev") == "tx" and r.get("at") is not None and r.get("ok")) or
                 (r["_src"] == "app" and r.get("ev") in ("cmd_sent", "ack") and r.get("path") == "node"
                  and r.get("at") is not None and r.get("ok")))
        if not timed or r["_t"] is None:
            continue
        sends += 1
        t = _t_ms(r.get("sent_at")) or r["_t"]
        w = next((w for w in wins if w["open"] <= t <= w["close"]), None)
        if w is None:
            outside += 1
        else:
            into.append((t - w["t_k"]) / 1000)
    return {"windows": len(wins), "timed_sends": sends, "outside_window": outside,
            "after_t_k_s": _stat(into),
            "in_0_5_to_10s": round(sum(0.5 <= v <= 10 for v in into) / len(into), 3) if into else None,
            "windows_with_cmd": sum(1 for w in wins if w["rec"].get("cmd")),
            "windows_with_ack": sum(1 for w in wins if w["rec"].get("acked"))}


def acks(recs: list[dict]) -> dict:
    """ACKs from the app (by path and form), bridge transmissions of them (by `xa`), and whether the tag
    took them (its wake record of that `n`)."""
    app = [r for r in recs if r["_src"] == "app" and r.get("ev") == "ack" and r.get("xa")]
    if not app:
        return {}
    tx_by_xa: dict[str, list[dict]] = defaultdict(list)
    for r in recs:
        if r["_src"] == "bridge" and r.get("ev") == "tx" and r.get("xa"):
            tx_by_xa[r["xa"]].append(r)
    wakes = {r["n"]: r for r in recs if r["_src"] == "tag" and r.get("type") == "wake" and r.get("n") is not None}
    by = Counter()
    sent_by = Counter()
    landed = known = 0
    note_ms = []
    for a in app:
        key = f"{a.get('path')}:{a.get('form')}:{'timed' if a.get('timed') else 'live'}"
        by[key] += 1
        for t in tx_by_xa.get(a["xa"], ()):
            if t.get("ok"):
                sent_by[t.get("what") or t.get("how")] += 1
        w = wakes.get(a.get("n"))
        if w is not None:
            known += 1
            landed += bool(w.get("acked"))
    for r in recs:
        if r["_src"] == "app" and r.get("ev") == "ack_note" and r.get("note_ms") is not None:
            note_ms.append(r["note_ms"])
    return {"app_acks": dict(by), "bridge_sent": dict(sent_by),
            "taken_by_tag": f"{landed}/{known}" if known else None, "ack_note_ms": _stat(note_ms)}


def bridge_quiet(recs: list[dict]) -> dict:
    """The bridges' side: what they sent and skipped, fallbacks, path sends, radio state."""
    br = [r for r in recs if r["_src"] == "bridge"]
    if not br:
        return {}
    tx, skip, fb, radio, claims = Counter(), Counter(), Counter(), Counter(), Counter()
    path_ok = path_all = 0
    answered: set = set()
    for r in br:
        ev = r.get("ev")
        if ev == "tx":
            tx[f"{r.get('what') or 'other'}:{r.get('how')}:{'ok' if r.get('ok') else 'fail'}"] += 1
        elif ev == "tx_skip":
            skip[r.get("reason")] += 1
        elif ev == "fallback":
            fb[r.get("state")] += 1
        elif ev == "answer_seen":
            answered.add(r.get("id"))
        elif ev == "path_use":
            path_all += 1
            path_ok += bool(r.get("confirmed"))
        elif ev in ("radio_up", "radio_down", "radio_degraded", "recover"):
            radio[f"{ev}:{r.get('reason') or r.get('step') or ''}".rstrip(":")] += 1
        elif ev == "claim":
            claims["won" if r.get("won") else "lost"] += 1
    # a fallback fired for a send that was answered after all (by the fallback or late) was needed only
    # if no answer came: answered ids against fired ids
    fired = {r.get("id") for r in br if r.get("ev") == "fallback" and r.get("state") == "fired"}
    return {"tx": dict(tx), "tx_skip": dict(skip), "fallback": dict(fb),
            "fallback_fired_then_answered": len(fired & answered), "claims": dict(claims),
            "path_sends_confirmed": f"{path_ok}/{path_all}" if path_all else None, "radio": dict(radio)}


def app_quiet(recs: list[dict]) -> dict:
    app = [r for r in recs if r["_src"] == "app"]
    if not app:
        return {}
    miss, anchors, plans = Counter(), Counter(), Counter()
    for r in app:
        ev = r.get("ev")
        if ev == "miss_seen":
            miss[r.get("reason")] += 1
        elif ev == "anchor":
            anchors["known" if r.get("known") else "unknown"] += 1
        elif ev == "cmd_plan":
            plans[r.get("route") or "?"] += 1
    reports = [r for r in app if r.get("ev") == "report" and not r.get("second_sighting")]
    sync = sum(1 for r in reports if r.get("sync"))
    by_j = Counter(str(r.get("j")) for r in reports)
    return {"miss_notes": dict(miss), "anchor_states": dict(anchors), "cmd_plans": dict(plans),
            "reports_by_copy": dict(by_j), "reports_with_sync": sync}


def quiet_mesh(recs: list[dict]) -> dict:
    out = {"tag": tag_wakes(recs), "slots": slot_timing(recs), "acks": acks(recs), "bridges": bridge_quiet(recs),
           "app": app_quiet(recs)}
    return {k: v for k, v in out.items() if v}


def _relay_health(recs: list[dict]) -> dict:
    out: dict[str, Counter] = defaultdict(Counter)
    for r in recs:
        if r.get("ev") in ("relay_up", "relay_down"):
            out[f"{r['_src']}:{r.get('bridge') or ''}:{r.get('url')}"][r["ev"]] += 1
        elif r.get("ev") == "publish":
            out[f"{r['_src']}:{r.get('bridge') or ''}:{r.get('url')}"]["publish_ok" if r.get("ok") else "publish_fail"] += 1
    return {k: dict(v) for k, v in sorted(out.items())}


def render(s: dict) -> str:
    def ms(v):
        return "-" if v is None else f"{v / 1000:.1f} s"

    lines = [f"inputs: {s['inputs']}",
             f"copies {s['copies']} (sent by the tag {s['copies_sent_by_tag']}), reports {s['reports']}, "
             f"delivered {s['reports_delivered']}",
             f"delivery rate {s['delivery_rate']}, heard by a bridge {s['heard_by_bridge_rate']}, "
             f"by the owner's node {s['heard_by_node_rate']}",
             f"heard by: {s['heard_by']}",
             f"bridge hops: {s['bridge_hops']}  routes: {s['bridge_routes']}",
             "latency (p50 / p90 / max, count):"]
    for k, v in s["latency_ms"].items():
        lines.append(f"  {k:<20} {ms(v['p50'])} / {ms(v['p90'])} / {ms(v['max'])}  ({v['count']})")
    lines.append(f"relay delay over 60 s: {s['relay_delay_over_60s']}")
    if s["floods_per_hour"]:
        lines.append("floods per hour (tag / bridge):")
        lines += [f"  {h}  {v['tag']} / {v['bridge']}" for h, v in s["floods_per_hour"].items()]
    if s["commands"]:
        lines.append("commands:")
        for c in s["commands"]:
            lines.append(f"  {c['tag']} seq {c['seq']} {c['kind']}: sends {c['sends']}, bridge tx {len(c['bridge_tx'])}, "
                         + (f"confirmed after {c['queued_to_confirmed_s']} s" if c["confirmed"] else "not confirmed"))
    if s["coverage"]:
        lines.append("coverage by distance from the reference (copies, bridge, node, delivered):")
        for b in s["coverage"]:
            lines.append(f"  {b['from_m']:>6}-{b['to_m']:<6} m  {b.get('copies', 0):>4} {b.get('bridge', 0):>4} "
                         f"{b.get('node', 0):>4} {b.get('delivered', 0):>4}")
    if s["relay_events"]:
        lines.append("relays:")
        lines += [f"  {k}: {v}" for k, v in s["relay_events"].items()]
    for part, v in (s.get("quiet_mesh") or {}).items():
        lines.append(f"quiet mesh, {part}:")
        lines += [f"  {k}: {x}" for k, x in v.items()]
    return "\n".join(lines)


def run(paths, timeline: str | None = None, as_json: bool = False, ref: str | None = None,
        band_m: float = 500.0, since: float | None = None, until: float | None = None, match_s: float = 30.0) -> str:
    recs = read(paths)
    if since is not None or until is not None:
        lo, hi = (since or 0) * 1000, (until or 1e18) * 1000
        recs = [r for r in recs if r["_t"] is not None and lo <= r["_t"] <= hi]
    copies = merge(recs, match_s)
    refpt = tuple(float(v) for v in ref.split(",")) if ref else None
    s = summarize(recs, copies, refpt, band_m)
    if timeline:
        with open(timeline, "w") as f:
            for c in copies:
                f.write(json.dumps(timeline_row(c), separators=(",", ":")) + "\n")
    return json.dumps(s, indent=1, default=str) if as_json else render(s)

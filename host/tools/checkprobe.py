#!/usr/bin/env python3
"""checkprobe: field test of command delivery at 0.7 checks through the Nostr timed mailbox.

Watches a tracker's reports on Nostr (kind 4243), and before an upcoming check publishes one timed
mailbox entry (kind 4244 with `at`/`via`, PROTOCOL.md 3 and 4) carrying a harmless BLE_SCAN (8 s,
passive, 1 packet). A bridge that caught the report transmits it at `at`; if the tracker takes it, it
answers with report n + 1 (CMD_ACK, our cmd_seq nibble) and a BLE packet (j = 5). The daemon records
when the bridge claimed and sent (kind 24244 claims, optionally the bridge's log over read-only ssh),
when the ack arrived, and misses, as JSON lines plus a running summary; at a set local time it sends
one summary message through the command in CHECKPROBE_NOTIFY_CMD (stdin), if set.

The check schedule comes from the reference code (libretag.wake.check_offsets, wake_reports), the
same rules the app uses (LibreTagApp.checkPlanFor): t0 = receive time of report n, checks before
next_in, sender time t_k + 3 s. Report wake-ups (every report_every-th) are not probed.

The tracker secret is read from a file given on the command line and only used in memory: it is
never printed, logged, written or sent. Run with `--dry-run` to print the plan without publishing.

    uv run --project host python host/tools/checkprobe.py --k0 k0.hex --dir probe [--dry-run]
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import datetime as dt
import json
import logging
import os
import random
import re
import signal
import statistics
import subprocess
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

from libretag.lt2 import (BLE_P_LEN, F_CMD_ACK, J_BLE, J_HOME, NOSTR_CLAIM_KIND, NOSTR_COMMAND_KIND,
                          NOSTR_REPORT_KIND, REPORT_P_LEN, REPORT_P_LEN_STEALTH, BlePacket, Command, Keys, Mode, Op,
                          Report, ReportWindow, command_tag, epoch, report_tag, seal_command)
from libretag.wake import HEARTBEAT_S, SEND_AFTER_S, check_guard_ms, check_offsets, wake_reports
from nostrmesh import secp
from nostrmesh.nostr import Event, sign_event

log = logging.getLogger("checkprobe")

RELAYS = ["wss://nostr.cypherpunk.today", "wss://nos.lol", "wss://relay.primal.net"]
DELETION_KIND = 5
ENTRY_TTL_S = 3600          # expiration of a probe entry after its `at` (well under 47 h)
MAX_TTL_S = 47 * 3600
BACKFILL_S = 48 * 3600      # how far back to look for the latest report at start
RESCAN_S = 1800             # full (unfiltered) look at recent 4243 events, catches jumps outside the #y set
BRIDGE_LOG_AFTER_S = (60, 600, 1800)  # look for the send in the bridge's log this long after `at`
BLE_WAIT_S = 900            # keep collecting BLE packets after an ack this long


# ---------------------------------------------------------------- schedule glue (pure, tested)

@dataclass
class Slot:
    k: int
    offset_s: int        # t_k - t0
    t_k: float           # unix s (t0 + offset)
    at: int              # unix s when senders transmit: t_k + 3 s
    guard_ms: int
    report_wakeup: bool  # every report_every-th wake-up: GPS, Bluetooth, may report; not probed


def plan_until_s(rep: Report, base_s: int, every: int) -> int | None:
    """Horizon of the checks after a report (LibreTagApp.checkPlanFor): next_in, else the default
    period. None when the report announces no checks the app could use."""
    if not rep.checks:
        return None
    between = not rep.quiet and rep.mode == Mode.IDLE and every > 1
    if not rep.quiet and not between:
        return None
    if rep.next_in_s is not None:
        return rep.next_in_s
    return base_s * every if between else HEARTBEAT_S


def check_plan(cmd_e: bytes, n: int, rep: Report, t0: float, base_s: int, every: int,
               max_checks: int = 200) -> list[Slot]:
    """Checks k = 1, 2, ... after report n (received at t0) before its next report, as the app sees them."""
    until = plan_until_s(rep, base_s, every)
    if until is None:
        return []
    offs = check_offsets(cmd_e, n, base_s, max_checks, until_s=until)
    return [Slot(k, off, t0 + off, int(round(t0 + off)) + SEND_AFTER_S, check_guard_ms(off), wake_reports(k, every))
            for k, off in enumerate(offs, start=1)]


def choose_slot(plan: list[Slot], now: float, last_probe_tk: float | None, min_gap_s: float,
                min_lead_s: float, after_k: int = 0) -> Slot | None:
    """First listen-only check after `after_k` that is far enough ahead to publish for and far
    enough from the previous probe."""
    for s in plan:
        if s.k <= after_k or s.report_wakeup:
            continue
        if s.at - now < min_lead_s:
            continue
        if last_probe_tk is not None and s.t_k - last_probe_tk < min_gap_s:
            continue
        return s
    return None


def next_seq(prev: int | None, lo: int, hi: int, start: int, avoid_full: int | None,
             avoid_nibble: int | None) -> int:
    """The next cmd_seq: prev + 1 wrapping within lo..hi; never the last acknowledged seq, and not
    one whose low nibble equals the nibble the tracker reports now (so an ack is unambiguous)."""
    span = hi - lo + 1
    s = start if prev is None else lo + ((prev + 1 - lo) % span)
    for _ in range(span):
        if s != avoid_full and (avoid_nibble is None or (s & 0x0F) != avoid_nibble):
            return s
        s = lo + ((s + 1 - lo) % span)
    raise ValueError("no usable cmd_seq")


def probe_command(seq: int) -> Command:
    cmd = Command.ble_scan(seq, seconds=8, active=False, packets=1)
    assert cmd.op == Op.BLE_SCAN  # never anything that makes noise
    return cmd


def build_probe(keys: Keys, n: int, k: int, at: int, seq: int, created_at: int, sk: bytes,
                ttl_s: int = ENTRY_TTL_S) -> tuple[Event, str, str]:
    """Kind 4244 timed entry for check k after report n: (event, y, via)."""
    e = epoch(n)
    ce, ve = keys.cmd(e), keys.view(e)
    pc = seal_command(ce, n, probe_command(seq), k)
    y = command_tag(ce, n, k).hex()
    via = report_tag(ve, n, J_HOME).hex()
    exp = min(at + ttl_s, created_at + MAX_TTL_S)
    tags = [["y", y], ["route", "near"], ["expiration", str(exp)], ["at", str(at)], ["via", via]]
    ev = sign_event(sk, created_at, NOSTR_COMMAND_KIND, tags, base64.b64encode(pc).decode(), os.urandom(32))
    return ev, y, via


def build_deletion(ev: Event, sk: bytes, created_at: int) -> Event:
    exp = next((t[1] for t in ev.tags if t[0] == "expiration"), str(created_at + ENTRY_TTL_S))
    return sign_event(sk, created_at, DELETION_KIND, [["e", ev.id], ["k", str(NOSTR_COMMAND_KIND)], ["expiration", exp]],
                      "", os.urandom(32))


def ack_matches(rep: Report, seq: int) -> bool:
    return rep.cmd_ack and rep.cmd_seq == (seq & 0x0F)


def sub_tags(keys: Keys, last_n: int) -> list[str]:
    """#y tags for the live subscription: the next reports (both copies), BLE packets of the
    latest and next reports, and the start of the next two epochs (n jumps after a long quiet)."""
    out: list[str] = []

    def add(n, j):
        t = report_tag(keys.view(epoch(n)), n, j).hex()
        if t not in out:
            out.append(t)

    base = max(last_n, 0)
    for n in range(base + 1, base + 9):
        add(n, 0)
        add(n, 1)
    for n in range(base, base + 3):
        for j in range(J_BLE, J_BLE + 4):
            add(n, j)
    e1 = (epoch(base) + 1) * 64
    for start in (e1, e1 + 64):
        for n in range(start, start + 4):
            add(n, 0)
            add(n, 1)
    return out


LOG_RE = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d),(\d{3}) .*LT2 command for ([0-9a-f]{8}) sent (\S+)(.*)$")


def parse_bridge_log(lines: list[str], y: str) -> tuple[float, str] | None:
    """First 'LT2 command for <y> sent <how>' line of the bridge log (local time): (unix s, how)."""
    for ln in lines:
        m = LOG_RE.match(ln.strip())
        if m and m.group(3) == y:
            t = dt.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S").timestamp() + int(m.group(2)) / 1000
            return t, (m.group(4) + m.group(5)).strip()
    return None


def _stats(xs: list[float]) -> dict:
    if not xs:
        return {"n": 0}
    return {"n": len(xs), "median": round(statistics.median(xs), 1), "max": round(max(xs), 1),
            "min": round(min(xs), 1)}


def summarize(probes: list[dict]) -> dict:
    """Totals over finished probes (status hit / miss); superseded and unpublished ones are counted apart."""
    done = [p for p in probes if p.get("status") in ("hit", "miss")]
    hits = [p for p in done if p["status"] == "hit"]
    send = [p["bridge_sent"] - p["t_k"] for p in done if p.get("bridge_sent")]
    claim = [p["claims"][0]["created_at"] - p["t_k"] for p in done if p.get("claims")]
    ack = [p["ack_recv"] - p["t_k"] for p in hits if p.get("ack_recv")]
    ble = [p["ble"][0]["recv"] - p["t_k"] for p in hits if p.get("ble")]
    return {
        "probes": len(done), "hits": len(hits), "misses": len(done) - len(hits),
        "superseded": sum(1 for p in probes if p.get("status") == "superseded"),
        "publish_failed": sum(1 for p in probes if p.get("status") == "publish_failed"),
        "bridge_sent": len(send), "claimed": len(claim), "ble_after_hit": sum(1 for p in hits if p.get("ble")),
        "bridge_send_minus_tk_s": _stats(send), "claim_minus_tk_s": _stats(claim),
        "ack_recv_minus_tk_s": _stats(ack), "ble_recv_minus_tk_s": _stats(ble),
        "failures": [{"n": p["n"], "k": p["k"], "seq": p["seq"], "why": p.get("why", "")}
                     for p in done if p["status"] == "miss"],
    }


def next_summary_time(started: float, at: str) -> float:
    """The first `at` (HH:MM local) strictly after `started`; later days follow via last_summary_date."""
    hh, mm = map(int, at.split(":"))
    d = dt.datetime.fromtimestamp(started).replace(hour=hh, minute=mm, second=0, microsecond=0)
    if d.timestamp() <= started:
        d += dt.timedelta(days=1)
    return d.timestamp()


def _hm(t: float) -> str:
    return dt.datetime.fromtimestamp(t).strftime("%H:%M")


def summary_text_sk(s: dict, since: float, until: float) -> str:
    """The morning message (Slovak, markdown). No positions, no keys."""
    def d(st):
        return "–" if not st.get("n") else f"medián {st['median']:+.0f} s, max {st['max']:+.0f} s"

    lines = [f"**LibreTag: nočný test checkov** ({_hm(since)}–{_hm(until)})", "",
             f"Sondy: {s['probes']}, prišiel ACK: {s['hits']}, bez ACK: {s['misses']}"]
    if s["superseded"] or s["publish_failed"]:
        lines.append(f"Zrušené (prišiel skorší report): {s['superseded']}, zlyhalo publikovanie: {s['publish_failed']}")
    lines += [f"Most poslal (log na moste): {s['bridge_sent']}/{s['probes']}, oproti t_k {d(s['bridge_send_minus_tk_s'])}",
              f"ACK report prijatý oproti t_k: {d(s['ack_recv_minus_tk_s'])}",
              f"BLE paket po ACK: {s['ble_after_hit']}/{s['hits']}"]
    if s["failures"]:
        lines += ["", "Bez ACK:"]
        lines += [f"- n={f['n']} k={f['k']} seq={f['seq']}: {f['why']}" for f in s["failures"][:8]]
        if len(s["failures"]) > 8:
            lines.append(f"- … a ďalšie ({len(s['failures']) - 8})")
    return "\n".join(lines)


# ---------------------------------------------------------------- daemon

@dataclass
class Latest:
    n: int
    recv: float           # our receive time (t0 of the plan)
    created_at: int       # bridge publish time of the first copy we saw
    j: int
    flags: dict = field(default_factory=dict)


def report_flags(r: Report) -> dict:
    return {"quiet": r.quiet, "quiet_owner": r.quiet_owner, "parked": r.quiet_parked, "checks": r.checks,
            "cmd_ack": r.cmd_ack, "cmd_seq_nibble": r.cmd_seq, "mode": r.mode.name, "next_in_s": r.next_in_s,
            "battery": r.battery_pct, "has_fix": r.has_fix}


class Probe:
    def __init__(self, args, keys: Keys):
        self.a = args
        self.keys = keys
        self.dir = Path(args.dir)
        self.state_path = self.dir / "state.json"
        self.records_path = self.dir / "probes.jsonl"
        self.window: ReportWindow | None = None
        self.latest: Latest | None = None
        self.latest_report: Report | None = None
        self.plan: list[Slot] = []
        self.seen_ids: set[str] = set()
        self.st = {"last_seq": None, "last_acked_seq": None, "last_n": -1, "probes": [], "last_summary_date": None,
                   "since": time.time()}
        self.active: dict | None = None     # the probe waiting for its slot or its ack
        self.pool = None
        self.tags: list[str] = []
        self.started = time.time()
        self.readonly = bool(getattr(args, "dry_run", False))

    # ---- persistence (no tracker secret in here; the event keys of pending probes, to cancel them)

    def load(self) -> None:
        if self.state_path.exists():
            self.st.update(json.loads(self.state_path.read_text()))
        for p in self.st["probes"]:
            if p.get("status") in ("scheduled", "published"):
                self.active = p

    def save(self) -> None:
        if self.readonly:
            return
        tmp = self.state_path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(self.st, f, indent=1)
        os.replace(tmp, self.state_path)
        s = summarize(self.st["probes"])
        s["updated"] = dt.datetime.now().isoformat(timespec="seconds")
        s["latest"] = asdict(self.latest) if self.latest else None
        s["active"] = {k: v for k, v in (self.active or {}).items() if k not in ("sk", "event")} or None
        (self.dir / "summary.json").write_text(json.dumps(s, indent=1))

    def record(self, kind: str, **kw) -> None:
        if self.readonly:
            return
        row = {"ts": round(time.time(), 3), "iso": dt.datetime.now().isoformat(timespec="seconds"), "type": kind, **kw}
        with open(self.records_path, "a") as f:
            f.write(json.dumps(row) + "\n")

    # ---- reports

    def _window(self) -> ReportWindow:
        if self.window is None:
            self.window = ReportWindow(self.keys, last_n=self.st.get("last_n", -1))
        return self.window

    def handle_event(self, ev: dict, url: str, live: bool = True) -> None:
        eid = ev.get("id")
        if not isinstance(eid, str) or eid in self.seen_ids:
            return
        self.seen_ids.add(eid)
        kind = ev.get("kind")
        if kind == NOSTR_REPORT_KIND:
            self._on_4243(ev, live)
        elif kind == NOSTR_CLAIM_KIND:
            self._on_claim(ev)

    def _on_4243(self, ev: dict, live: bool) -> None:
        try:
            p = base64.b64decode(ev.get("content", ""), validate=True)
        except Exception:  # noqa: BLE001
            return
        now = time.time()
        ca = ev.get("created_at") if isinstance(ev.get("created_at"), int) else int(now)
        # a stored event (backfill, or replayed on reconnect) gets the bridge's publish time
        recv = now if live and now - ca < 30 else float(ca)
        w = self._window()
        if len(p) in (REPORT_P_LEN, REPORT_P_LEN_STEALTH):
            hit = w.open(p)
            if hit:
                self._on_report(*hit, recv=recv, created_at=ca)
        elif len(p) >= BLE_P_LEN:
            hit = w.open_extra(p)
            if hit and isinstance(hit[2], BlePacket):
                self._on_ble(hit[0], hit[1], recv, ca)

    def _on_report(self, n: int, j: int, r: Report, recv: float, created_at: int) -> None:
        if self.latest is not None and n <= self.latest.n:
            return
        self.latest = Latest(n, recv, created_at, j, report_flags(r))
        self.latest_report = r
        self.st["last_n"] = n
        ce = self.keys.cmd(epoch(n))
        self.plan = check_plan(ce, n, r, recv, self.a.interval_min * 60, self.a.report_every)
        from libretag.wake import report_next_in
        expect = report_next_in(ce, n, self.a.interval_min * 60, self.a.report_every) if self.a.report_every > 1 else None
        consistent = None if r.quiet or expect is None or r.next_in_s is None else abs(r.next_in_s - expect) <= 30
        log.info("report n=%d j=%d recv=%s (bridge %s) quiet=%s owner=%s parked=%s checks=%s ack=%s seq&15=%d "
                 "next_in=%s report_every_ok=%s checks_planned=%d", n, j, _hm(recv), _hm(created_at), r.quiet,
                 r.quiet_owner, r.quiet_parked, r.checks, r.cmd_ack, r.cmd_seq, r.next_in_s, consistent, len(self.plan))
        self.record("report", n=n, j=j, recv=round(recv, 3), created_at=created_at, flags=report_flags(r),
                    next_in_matches_report_every=consistent,
                    plan=[{"k": s.k, "t_k": round(s.t_k), "at": s.at, "report_wakeup": s.report_wakeup}
                          for s in self.plan[:12]])
        self._resolve_on_report(n, r, recv)
        if self.pool is not None and not self.readonly:
            asyncio.get_running_loop().create_task(self._resubscribe())
        self.save()

    def _on_ble(self, n: int, j: int, recv: float, created_at: int) -> None:
        for p in reversed(self.st["probes"]):
            if p.get("status") == "hit" and p["n"] + 1 == n and recv - p.get("ack_recv", recv) < BLE_WAIT_S:
                p.setdefault("ble", []).append({"j": j, "recv": round(recv, 3), "created_at": created_at})
                log.info("BLE packet n=%d j=%d for probe seq=%d (%+.0f s after t_k)", n, j, p["seq"], recv - p["t_k"])
                self.record("ble", probe=p["id"], n=n, j=j, recv=round(recv, 3), created_at=created_at,
                            minus_tk=round(recv - p["t_k"], 1))
                self.save()
                return
        log.info("BLE packet n=%d j=%d (no probe of ours)", n, j)

    def _on_claim(self, ev: dict) -> None:
        tags = {t[0]: t[1] for t in ev.get("tags", []) if isinstance(t, list) and len(t) >= 2}
        y = tags.get("y")
        for p in reversed(self.st["probes"]):
            if p.get("y") == y and p.get("status") in ("published", "hit", "miss"):
                c = {"created_at": ev.get("created_at"), "recv": round(time.time(), 3), "hops": tags.get("hops"),
                     "pubkey": str(ev.get("pubkey", ""))[:12]}
                p.setdefault("claims", []).append(c)
                log.info("claim for probe seq=%d y=%s hops=%s (created %+.0f s vs t_k)", p["seq"], y, c["hops"],
                         (c["created_at"] or 0) - p["t_k"])
                self.record("claim", probe=p["id"], **c, minus_tk=round((c["created_at"] or 0) - p["t_k"], 1))
                self.save()
                return

    def _resolve_on_report(self, n: int, r: Report, recv: float) -> None:
        p = self.active
        if p is None or n <= p["n"]:
            return
        if p["status"] == "scheduled":
            p["status"] = "dropped"
            p["why"] = f"report n={n} came before publishing"
            self.record("dropped", probe=p["id"], why=p["why"])
            self.active = None
            return
        if n == p["n"] + 1 and recv >= p["at"] - 10 and ack_matches(r, p["seq"]):
            p.update(status="hit", ack_n=n, ack_recv=round(recv, 3), ack_minus_tk=round(recv - p["t_k"], 1))
            self.st["last_acked_seq"] = p["seq"]
            log.info("HIT probe seq=%d n=%d k=%d: ack report n=%d %+.0f s after t_k", p["seq"], p["n"], p["k"], n,
                     recv - p["t_k"])
            self.record("hit", probe=p["id"], ack_n=n, ack_recv=round(recv, 3), minus_tk=p["ack_minus_tk"])
            self.active = None
        elif recv < p["at"]:
            p.update(status="superseded", why=f"report n={n} before at")
            self.record("superseded", probe=p["id"], n_new=n)
            log.info("probe seq=%d superseded by report n=%d before its slot: cancelling", p["seq"], n)
            asyncio.get_running_loop().create_task(self._cancel(p))
            self.active = None
        else:
            why = (f"report n={n} without our ack (ack={r.cmd_ack}, seq&15={r.cmd_seq})" if n == p["n"] + 1
                   else f"next report seen is n={n}, not n+1")
            self._miss(p, why)

    def _miss(self, p: dict, why: str) -> None:
        p.update(status="miss", why=why)
        log.info("MISS probe seq=%d n=%d k=%d: %s", p["seq"], p["n"], p["k"], why)
        self.record("miss", probe=p["id"], why=why)
        self.active = None

    # ---- relays

    async def _resubscribe(self) -> None:
        tags = sub_tags(self.keys, self.latest.n if self.latest else self.st.get("last_n", -1))
        if tags != self.tags:
            self.tags = tags
            await self.pool.subscribe("cp-rep", [{"kinds": [NOSTR_REPORT_KIND], "#y": tags,
                                                  "since": int(time.time()) - 600}])

    async def backfill(self, since_s: int = BACKFILL_S) -> None:
        evs = await self.pool.query([{"kinds": [NOSTR_REPORT_KIND], "since": int(time.time()) - since_s}], timeout=15)
        for ev in sorted(evs, key=lambda e: e.get("created_at", 0)):
            self.handle_event(ev, "backfill", live=False)

    async def _publish(self, ev: Event, what: str) -> dict:
        res = await self.pool.publish(ev.to_dict(), retry=True)
        ok = {u: r[0] for u, r in res.items()}
        log.info("%s %s: %s", what, ev.id[:12], {u.split("//")[1]: (r[0], r[1][:40]) for u, r in res.items()})
        return ok

    async def _cancel(self, p: dict) -> None:
        if not p.get("event") or not p.get("sk"):
            return
        ev = Event.from_dict(p["event"])
        de = build_deletion(ev, bytes.fromhex(p["sk"]), int(time.time()))
        ok = await self._publish(de, "deletion")
        self.record("cancel", probe=p["id"], ok=ok)
        p.pop("sk", None)
        self.save()

    # ---- probes

    def _schedule_next(self, now: float) -> None:
        if self.active is not None or self.latest is None or not self.plan:
            return
        prev = [p for p in self.st["probes"] if p.get("status") in ("published", "hit", "miss")]
        last_tk = max((p["t_k"] for p in prev), default=None)
        after_k = max((p["k"] for p in self.st["probes"] if p["n"] == self.latest.n), default=0)
        s = choose_slot(self.plan, now, last_tk, self.a.min_gap_s, self.a.min_lead_s, after_k)
        if s is None:
            return
        seq = next_seq(self.st["last_seq"], self.a.seq_lo, self.a.seq_hi, self.a.seq_start,
                       self.st["last_acked_seq"], self.latest.flags.get("cmd_seq_nibble"))
        self.st["last_seq"] = seq
        lead = random.uniform(self.a.lead_min_s, self.a.lead_max_s)
        nxt = next((x for x in self.plan if x.k > s.k), None)
        until = plan_until_s(self.latest_report, self.a.interval_min * 60, self.a.report_every) or 0
        deadline = (nxt.t_k if nxt else self.latest.recv + until) + 120
        p = {"id": f"{self.latest.n}-{s.k}-{seq}", "n": self.latest.n, "k": s.k, "seq": seq,
             "t0": round(self.latest.recv, 3), "t_k": round(s.t_k, 3), "at": s.at, "guard_ms": s.guard_ms,
             "publish_at": round(max(now, s.at - lead), 3), "deadline": round(max(deadline, s.at + 300), 3),
             "status": "scheduled", "quiet": self.latest.flags.get("quiet")}
        self.st["probes"].append(p)
        self.active = p
        log.info("probe scheduled: n=%d k=%d seq=%d t_k=%s at=%s publish at %s", p["n"], p["k"], seq, _hm(s.t_k),
                 _hm(s.at), _hm(p["publish_at"]))
        self.record("scheduled", probe=p["id"], n=p["n"], k=p["k"], seq=seq, t_k=p["t_k"], at=p["at"],
                    publish_at=p["publish_at"], deadline=p["deadline"])
        self.save()

    async def _publish_probe(self, p: dict) -> None:
        sk = secp.seckey_from_bytes(os.urandom(32))
        now = int(time.time())
        ev, y, via = build_probe(self.keys, p["n"], p["k"], p["at"], p["seq"], now, sk)
        p.update(status="published", y=y, via=via, event=ev.to_dict(), sk=sk.hex(), published=now)
        self.save()
        ok = await self._publish(ev, f"probe n={p['n']} k={p['k']} seq={p['seq']} y={y}")
        p["relays_ok"] = ok
        if not any(ok.values()):
            p.update(status="publish_failed", why="no relay accepted the entry")
            self.active = None
        self.record("published", probe=p["id"], y=y, via=via, event_id=ev.id, created_at=now, at=p["at"],
                    t_k=p["t_k"], relays_ok=ok)
        self.save()

    async def _bridge_log(self, p: dict) -> None:
        if not self.a.bridge_host or p.get("bridge_sent"):
            return
        cmd = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", self.a.bridge_host,
               f"grep -F 'LT2 command for {p['y']} sent' {self.a.bridge_log}"]
        try:
            proc = await asyncio.create_subprocess_exec(*cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            out, _ = await asyncio.wait_for(proc.communicate(), 40)
        except Exception as e:  # noqa: BLE001
            log.warning("bridge log: %s", e)
            return
        hit = parse_bridge_log(out.decode(errors="replace").splitlines(), p["y"])
        p["bridge_log_checked"] = int(time.time())
        if hit:
            p["bridge_sent"], p["bridge_how"] = round(hit[0], 3), hit[1]
            p["bridge_minus_tk"] = round(hit[0] - p["t_k"], 1)
            log.info("bridge sent probe seq=%d %s at %s (%+.1f s vs t_k)", p["seq"], hit[1],
                     dt.datetime.fromtimestamp(hit[0]).strftime("%H:%M:%S"), p["bridge_minus_tk"])
            self.record("bridge_sent", probe=p["id"], t=p["bridge_sent"], how=hit[1], minus_tk=p["bridge_minus_tk"])
        self.save()

    async def _morning(self, now: float) -> None:
        loc = dt.datetime.fromtimestamp(now)
        today = loc.date().isoformat()
        if now < next_summary_time(self.started, self.a.summary_at) or self.st.get("last_summary_date") == today:
            return  # only when the clock passes summary_at after the daemon started, once a day
        since = self.st.get("summary_since") or self.st.get("since") or self.started
        probes = [p for p in self.st["probes"] if p.get("published", p.get("publish_at", 0)) >= since]
        s = summarize(probes)
        text = summary_text_sk(s, since, now)
        self.st["last_summary_date"] = today
        self.st["summary_since"] = now
        self.save()
        (self.dir / f"summary-{today}.md").write_text(text + "\n")
        self.record("morning_summary", summary=s)
        cmd = os.environ.get("CHECKPROBE_NOTIFY_CMD", "")
        if self.a.no_message or not cmd:
            log.info("morning summary written (message off)")
            return
        try:
            # any command that reads the message on stdin, e.g. a mail or chat CLI
            r = subprocess.run(cmd.split(), input=text, text=True, capture_output=True, timeout=120)
            log.info("morning summary sent: rc=%d %s", r.returncode, (r.stdout + r.stderr).strip()[:200])
        except Exception as e:  # noqa: BLE001
            log.warning("morning summary: %s", e)

    async def tick(self) -> None:
        now = time.time()
        p = self.active
        if p is not None:
            if p["status"] == "scheduled":
                if self.latest is None or self.latest.n != p["n"]:
                    p.update(status="dropped", why="newer report")
                    self.active = None
                elif now >= p["publish_at"]:
                    if p["at"] - now < 20:
                        p.update(status="dropped", why="too late to publish")
                        self.record("dropped", probe=p["id"], why=p["why"])
                        self.active = None
                    else:
                        await self._publish_probe(p)
            elif p["status"] == "published":
                if now > p["deadline"]:
                    sent = "bridge sent it" if p.get("bridge_sent") else \
                        "a bridge claimed it, no send in the bridge log" if p.get("claims") else "no claim, not sent"
                    self._miss(p, f"no ack by the next check ({sent})")
                    self.save()
        for q in self.st["probes"]:
            tries = q.get("bridge_log_tries", 0)
            if q.get("y") and not q.get("bridge_sent") and tries < len(BRIDGE_LOG_AFTER_S) \
                    and now >= q["at"] + BRIDGE_LOG_AFTER_S[tries]:
                q["bridge_log_tries"] = tries + 1
                await self._bridge_log(q)
        self._schedule_next(now)
        await self._morning(now)

    async def run(self) -> None:
        from nostrmesh.relays import RelayPool

        self.pool = RelayPool(self.a.relays, self.a.relays, on_event=lambda ev, url: self.handle_event(ev, url))
        self.pool.start()
        await self.pool.subscribe("cp-claim", [{"kinds": [NOSTR_CLAIM_KIND], "since": int(time.time()) - 60}])
        await self.backfill()
        await self._resubscribe()
        if self.latest is None:
            log.warning("no report of this tracker in the last %d h yet; waiting", BACKFILL_S // 3600)
        self.save()
        last_rescan = time.time()
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, stop.set)
        while not stop.is_set():
            try:
                await self.tick()
                if time.time() - last_rescan > RESCAN_S:
                    last_rescan = time.time()
                    await self.backfill(2 * RESCAN_S)
            except Exception:  # noqa: BLE001
                log.exception("tick failed")
            try:
                await asyncio.wait_for(stop.wait(), 2)
            except asyncio.TimeoutError:
                pass
        p = self.active
        if p is not None and p["status"] == "published" and p["at"] > time.time():
            log.info("stopping: cancelling the pending probe seq=%d", p["seq"])
            p.update(status="cancelled", why="daemon stopped")
            await self._cancel(p)
        self.save()
        await self.pool.stop()
        log.info("stopped")


def print_plan(pr: Probe, hours: float) -> None:
    now = time.time()
    L = pr.latest
    if L is None:
        print("No report of this tracker found on the relays in the last 48 h.")
        return
    f = L.flags
    print(f"Latest report n={L.n} received {dt.datetime.fromtimestamp(L.recv):%Y-%m-%d %H:%M:%S} "
          f"(bridge created_at {dt.datetime.fromtimestamp(L.created_at):%H:%M:%S}), mode={f['mode']} quiet={f['quiet']} "
          f"owner={f['quiet_owner']} parked={f['parked']} checks={f['checks']} cmd_ack={f['cmd_ack']} "
          f"seq&15={f['cmd_seq_nibble']} next_in={f['next_in_s']} s")
    until = plan_until_s(pr.latest_report, pr.a.interval_min * 60, pr.a.report_every)
    if until:
        print(f"Next report expected by {dt.datetime.fromtimestamp(L.recv + until):%H:%M:%S} (t0 + next_in)")
    print(f"interval {pr.a.interval_min} min, report_every {pr.a.report_every}, min gap {pr.a.min_gap_s} s\n")
    print(" k   t_k       at (t_k+3)  guard   kind          probe")
    last_tk, seq = None, pr.st["last_seq"]
    for s in pr.plan:
        if s.t_k > now + hours * 3600:
            break
        kind = "report wake-up" if s.report_wakeup else "check"
        pick = ""
        if s.t_k >= now and not s.report_wakeup and s.at - now >= pr.a.min_lead_s and \
                (last_tk is None or s.t_k - last_tk >= pr.a.min_gap_s):
            seq = next_seq(seq, pr.a.seq_lo, pr.a.seq_hi, pr.a.seq_start, pr.st["last_acked_seq"], f["cmd_seq_nibble"])
            pick = f"PROBE seq={seq} (publish ~{_hm(s.at - (pr.a.lead_min_s + pr.a.lead_max_s) / 2)})"
            last_tk = s.t_k
        elif s.t_k < now:
            pick = "(past)"
        print(f"{s.k:2d}  {dt.datetime.fromtimestamp(s.t_k):%H:%M:%S}  {dt.datetime.fromtimestamp(s.at):%H:%M:%S}"
              f"    {s.guard_ms / 1000:4.1f} s  {kind:<13} {pick}")
    if not pr.plan:
        print("(no checks after this report: it announces none the app could use)")
    print("\nA hit makes the tracker send report n+1 at once; the plan then restarts from it (next probe ~1 h later).")
    print("If the probe misses, the next listen-only check of the same report is tried.")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--k0", required=True, help="file with the tracker's K0 in hex (read only, never shown)")
    ap.add_argument("--dir", required=True, help="output directory (logs, records, state)")
    ap.add_argument("--interval-min", type=int, default=60)
    ap.add_argument("--report-every", type=int, default=3)
    ap.add_argument("--relays", nargs="+", default=RELAYS)
    ap.add_argument("--min-gap-s", type=int, default=3000, help="at most one probe per wake-up interval")
    ap.add_argument("--min-lead-s", type=int, default=60, help="skip a check closer than this when planning")
    ap.add_argument("--lead-min-s", type=int, default=120)
    ap.add_argument("--lead-max-s", type=int, default=300)
    ap.add_argument("--seq-start", type=int, default=210)
    ap.add_argument("--seq-lo", type=int, default=200)
    ap.add_argument("--seq-hi", type=int, default=250)
    ap.add_argument("--bridge-host", default=os.environ.get("LIBRETAG_BRIDGE_HOST", ""),
                    help="ssh host of the bridge (read-only grep of its log); default env LIBRETAG_BRIDGE_HOST; '' = off")
    ap.add_argument("--bridge-log", default="~/Library/Logs/nostrmesh.log")
    ap.add_argument("--summary-at", default="07:30", help="local time of the daily summary message")
    ap.add_argument("--no-message", action="store_true", help="write the summary file only, do not send it")
    ap.add_argument("--dry-run", action="store_true", help="print the plan and exit; publishes nothing")
    ap.add_argument("--hours", type=float, default=6)
    a = ap.parse_args(argv)

    raw = Path(a.k0).read_text().strip()
    keys = Keys(bytes.fromhex(raw))
    del raw
    if len(keys.k0) != 32:
        print("K0 must be 32 bytes", file=sys.stderr)
        return 2
    Path(a.dir).mkdir(parents=True, exist_ok=True)
    handlers = [logging.StreamHandler()]
    if not a.dry_run:
        handlers.append(logging.FileHandler(Path(a.dir) / "daemon.log"))
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s",
                        handlers=handlers)
    logging.getLogger("nostrmesh.relays").setLevel(logging.WARNING)
    pr = Probe(a, keys)
    pr.load()

    if a.dry_run:
        async def dry():
            from nostrmesh.relays import RelayPool
            pr.pool = RelayPool(a.relays, a.relays)
            pr.pool.start()
            pr.st["probes"] = []
            pr.active = None
            await pr.backfill()
            await pr.pool.stop()
        logging.getLogger().setLevel(logging.WARNING)
        asyncio.run(dry())
        print_plan(pr, a.hours)
        return 0

    pid = Path(a.dir) / "checkprobe.pid"
    pid.write_text(f"{os.getpid()}\n")
    try:
        log.info("checkprobe up: interval %d min, report_every %d, relays %s, bridge log %s", a.interval_min,
                 a.report_every, a.relays, a.bridge_host or "off")
        asyncio.run(pr.run())
    finally:
        pid.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

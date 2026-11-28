"""The tracker's field-test log (firmware 0.9.8, `fieldlog dump`): 48-byte records to JSON lines.

Firmware 0.10.0 adds a summary per reporting wake-up in three records (types 6 to 8, the same `n`),
merged here into one `wake` line with the field names of docs/design/quiet-mesh.md ("Tag field log"),
and one `chk` record per slot window (type 9). The other agents' logs join on `n`, `x`, `xc`, `xa`.

Every record that names a report counter calls it `n` (TX, check windows, `chk`, `wake`, events).
`x` (the first 4 bytes of the packet, lowercase hex) is not in the tag's records; it needs the view
key `V_e(n)`. `to_jsonl(lines, view)` adds `x` to each TX record of a report copy or extra packet when
`view(epoch)` gives that key (the CLI passes the owner's K0 or a stored share). Without it, records
join the other logs on `n` and `j` through the app's `open` lines.

Record layout: firmware/zephcore-patches (main_tracker.cpp, "field log (0.9.8)"). Every record
starts with magic 0xF1, type, phase, mode, u32 seq, u32 uptime ms, u32 unix s (0 = clock unknown).
Times: `t` is the tag's clock when it had one; otherwise it is estimated from another record of
the same boot that had the clock (`t_est`: true). Each TX and RX record also gets the last fix
logged before it (`pos`, with its age), so coverage can be mapped from TX/RX alone.

The log holds positions. Keep dumps out of the repository.
"""
from __future__ import annotations

import datetime as _dt
import json
import struct
from typing import Callable, Iterable, Iterator

REC = 48
MAGIC = 0xF1

TYPES = {1: "tx", 2: "rx", 3: "win", 4: "gps", 5: "evt", 6: "sum1", 7: "sum2", 8: "sum3", 9: "chk"}
PHASES = ["sleep", "gps", "tx", "listen", "blescan", "check"]
MODES = ["idle", "track", "lost", "listen"]
TX_KINDS = {1: "report", 2: "extra", 3: "nostr", 4: "discover", 6: "scope"}  # 0.10.0: no 8 s retx
ROUTES = {0: "tflood", 1: "flood", 2: "direct", 3: "tdirect"}
PTYPES = {0: "req", 1: "resp", 2: "txt", 3: "ack", 4: "advert", 5: "grp_txt", 6: "grp_data",
          7: "anon_req", 8: "path", 9: "trace", 10: "multipart", 11: "control", 15: "raw"}
MATCH = {0: None, 1: "own", 4: "discover", 5: "advert"}
WINS = {1: "wake", 2: "listen", 3: "check", 4: "discover"}
GPS = {0: "start", 1: "fix", 2: "timeout", 3: "stopped"}
EVTS = ["?", "boot", "alive", "quiet", "active", "mode", "net", "still", "moving", "txfail", "txfloor",
        "cap", "clock", "reboot", "blecon", "cmd", "stall", "txdrop", "link", "lvl"]
# 0.10.0 (lt_core.h, lt_link.c)
WHYS = ["period", "moved", "hold", "heartbeat", "nofix", "boot", "cmd", "low", "mode", "console", "budget_last",
        "parked", "track", "again"]
LINKS = ["far", "near", "routed"]
EV_BITS = [(1, "boot"), (2, "cmd"), (4, "low"), (8, "mode"), (16, "console")]
BOOT_CAUSES = ["none", "pin", "power", "soft", "debug", "brownout", "watchdog", "crash"]


def _hdr(b: int) -> dict:
    return {"route": ROUTES[b & 3], "ptype": PTYPES.get((b >> 2) & 0x0F, (b >> 2) & 0x0F), "ver": b >> 6}


def _path(plen: int) -> tuple[int, int]:
    """(hash size, hops) of a path-length byte."""
    return (plen >> 6) + 1, plen & 0x3F


def decode(rec: bytes) -> dict:
    if len(rec) != REC or rec[0] != MAGIC:
        raise ValueError("not a field log record")
    typ, ph, md = rec[1], rec[2], rec[3]
    seq, up_ms, unix_s = struct.unpack_from("<III", rec, 4)
    d: dict = {"seq": seq, "type": TYPES.get(typ, typ), "up_ms": up_ms, "unix": unix_s or None,
               "phase": PHASES[ph] if ph < len(PHASES) else ph, "mode": MODES[md] if md < len(MODES) else md}
    if typ == 1:
        n, kind, j, hdr, plen, ln, fl, air, mv, floods, sats, h, age, tc = struct.unpack_from(
            "<IBBBBBBHHBBIHH", rec, 16)
        hs, hops = _path(plen)
        d.update(n=n, kind=TX_KINDS.get(kind, kind), j=j, **_hdr(hdr), hash_size=hs, path_len=hops,
                 len=ln, payload_len=ln - 2 - hs * hops - (4 if hdr & 3 in (0, 3) else 0),
                 alt_net=bool(fl & 1), scoped=bool(fl & 2), usb=bool(fl & 4), over_cap=bool(fl & 8),
                 fix_state="this_wake" if fl & 16 else "older" if fl & 32 else "none",
                 airtime_ms=air, batt_mv=mv, floods_24h=floods, sats=sats, hash=f"{h:08x}",
                 fix_age_s=None if age == 0xFFFF else age, tcode=tc,
                 region=rec[40:48].split(b"\0")[0].decode(errors="replace") or None)
    elif typ == 2:
        hdr, plen, ln, m = rec[16], rec[17], rec[18], rec[19]
        snr4 = struct.unpack_from("<b", rec, 20)[0]
        tc, h = struct.unpack_from("<HI", rec, 22)
        hs, hops = _path(plen)
        pb = hs * hops
        raw = rec[28:40]
        if m & 0x80:
            hashes_first = [raw[i:i + hs].hex() for i in range(0, 9 - 9 % hs, hs)]
            path = hashes_first + ["..", raw[12 - hs:12].hex()]
        else:
            path = [raw[i:i + hs].hex() for i in range(0, pb, hs)]
        d.update(**_hdr(hdr), hash_size=hs, path_len=hops, path=path, len=ln, snr=snr4 / 4, rssi=-rec[21],
                 match=MATCH.get(m & 0x3F, m & 0x3F), alt_radio=bool(m & 0x40), tcode=tc, hash=f"{h:08x}")
        if m & 0x3F == 4:
            d.update(key=rec[40:44].hex(), their_snr=struct.unpack_from("<b", rec, 44)[0] / 4)
        elif m & 0x3F == 5:
            d.update(key=rec[40:44].hex(), adv_type=rec[44])
        if hops:
            d["last_hop"] = path[-1]
    elif typ == 3:
        kind, op = rec[16], rec[17]
        dur, a, b = struct.unpack_from("<III", rec, 20)
        d.update(win=WINS.get(kind, kind), open=bool(op))
        if kind == 1:
            if op:
                d.update(k=None if a == 0xFFFF else a, heartbeat=a == 0xFFFF, listen_only=bool(b & 1),
                         forced=bool(b & 2), low=bool(b & 4), keyed=bool(b & 8))
            else:
                d.update(dur_ms=dur, rx=a, own_copies=b)
        elif kind == 2:
            d.update(listen_s=a)
            if not op:
                d.update(dur_ms=dur, relayed=bool(b))
        elif kind == 3:
            d.update(dur_ms=dur, k=a, n=b)
        elif kind == 4:
            d.update(**({"seconds": a} if op else {"dur_ms": dur}))
    elif typ == 4:
        res, sats, cold = rec[16], rec[17], rec[18]
        ms, lat, lon, alt, _, ft, to = struct.unpack_from("<IiihHIH", rec, 20)
        d.update(gps=GPS.get(res, res), sats=sats, cold=bool(cold), search_ms=ms)
        if res == 0:
            d["timeout_s"] = to
        if res == 1:
            d.update(lat=lat / 1e7, lon=lon / 1e7, alt_m=alt, fix_time=ft or None)
    elif typ == 5:
        et, a, b, q = rec[16], rec[17], rec[18], rec[19]
        c, = struct.unpack_from("<H", rec, 20)
        v, n, mv = struct.unpack_from("<IIH", rec, 24)
        d.update(evt=EVTS[et] if et < len(EVTS) else et, a=a, b=b, c=c, v=v, quiet=q, flags=rec[22], n=n,
                 batt_mv=mv)
    elif typ == 6:
        n, why, lk, lvl, fl, search_s, hacc, ncop = struct.unpack_from("<IBBBBHBB", rec, 16)
        cops = []
        for i in range(min(ncop, 4)):
            j, ms = struct.unpack_from("<BH", rec, 28 + 3 * i)
            cops.append(f"{j}@{ms}")
        ft, mv_m, floods, left = struct.unpack_from("<IHBB", rec, 40)
        d.update(n=n, why=_name(WHYS, why), link_before=_name(LINKS, lk & 0x0F), link_after=_name(LINKS, lk >> 4),
                 lvl=None if lvl == 0xFF else lvl, fix=bool(fl & 1), acked=bool(fl & 2),
                 ack_form=("routed" if fl & 4 else "near") if fl & 2 else None, early_sleep=bool(fl & 8),
                 sync=bool(fl & 16), quick_answer=bool(fl & 32), parked=bool(fl & 64), budget_hit=bool(fl & 128),
                 search_s=search_s, hacc_m=None if hacc == 255 else hacc, n_copies=ncop, copies=",".join(cops),
                 fix_utc=_iso(ft) if ft else None, moved_m=mv_m, floods_24h=floods, idle_budget_left=left)
    elif typ == 7:
        n, rep_ms = struct.unpack_from("<IH", rec, 16)
        ack_k, ack_ms, ack_snr4, op, cseq, cfl, ck, cms, an, prev, nib, lms, sk, evs, stalls = struct.unpack_from(
            "<BHbBBBHHIBBHHBB", rec, 25)
        d.update(n=n, rep_ms=None if rep_ms == 0xFFFF else rep_ms,
                 rep_hash=rec[22:25].hex() if rep_ms != 0xFFFF else None,
                 ack_k=None if ack_k == 0xFF else ack_k, ack_ms=None if ack_ms == 0xFFFF else ack_ms,
                 ack_snr=ack_snr4 / 4 if ack_ms != 0xFFFF else None,
                 anchor_n=None if an == 0xFFFFFFFF else an, prev_slots_left=prev, next_in=nib, listen_ms=lms,
                 slot_k=sk, events=[nm for b, nm in EV_BITS if evs & b], stall=stalls)
        if cfl & 1:
            d.update(cmd_op=op, cmd_seq=cseq, cmd_k=ck, cmd_ms=cms, cmd_dup=bool(cfl & 2),
                     cmd_route="ble" if cfl & 16 else "direct" if cfl & 4 else "flood",
                     cmd_type=None if cfl & 16 else "6C" if cfl & 8 else "4C")
    elif typ == 8:
        n, pl = struct.unpack_from("<IB", rec, 16)
        cnt, hs = pl & 0x0F, pl >> 4
        bpct, page, mv, mah, cause, resets, txf = struct.unpack_from("<BIHHBBB", rec, 27)
        nis, = struct.unpack_from("<I", rec, 40)
        path = [rec[21 + i * hs:21 + (i + 1) * hs].hex() for i in range(cnt)] if hs and cnt * hs <= 6 else []
        d.update(n=n, path=path, path_hs=hs or None, path_age_s=None if page == 0xFFFFFFFF else page,
                 batt_pct=None if bpct == 255 else bpct, batt_mv=mv, energy_mah=mah,
                 boot_cause=_name(BOOT_CAUSES, cause), boot_resets=resets, txfail=txf, next_in_s=nis)
    elif typ == 9:
        n, k, fl, prev, an, rx_ms, early, mv, lk = struct.unpack_from("<IHBBIIiHB", rec, 16)
        d.update(n=n, slot_k=k, grid="prev" if fl & 2 and not fl & 1 else "anchor" if fl & 1 else None,
                 acked=bool(fl & 4), cmd=bool(fl & 8), pending=bool(fl & 16), discover=bool(fl & 32),
                 listen_only=bool(fl & 64), prev_slots_left=prev, anchor_n=None if an == 0xFFFFFFFF else an,
                 rx_ms=rx_ms, rx_open_ms=early, batt_mv=mv, link=_name(LINKS, lk))
    return d


def _name(names: list[str], i: int):
    return names[i] if i < len(names) else i


WAKE_KEYS = ("seq", "up_ms", "unix", "mode")


def merge_wakes(recs: Iterable[dict]) -> list[dict]:
    """sum1, sum2, sum3 of one wake-up (consecutive, the same n) into one `wake` record; `up_s` from sum1.
    A summary with a part missing (a dump cut in the middle) keeps what it has."""
    out: list[dict] = []
    cur: dict | None = None
    for r in recs:
        t = r["type"]
        if t in ("sum1", "sum2", "sum3"):
            if t == "sum1" or cur is None or cur.get("n") != r["n"]:
                cur = {"type": "wake", **{k: r[k] for k in WAKE_KEYS}, "up_s": round(r["up_ms"] / 1000, 3),
                       "phase": r["phase"]}
                out.append(cur)
            for k, v in r.items():
                if k not in ("type", "phase") and k not in WAKE_KEYS:
                    cur[k] = v
            continue
        cur = None
        out.append(r)
    return out


def _iso(t: float) -> str:
    return _dt.datetime.fromtimestamp(t, _dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def annotate(recs: Iterable[dict]) -> list[dict]:
    """ISO times (estimated within a boot from any record with the clock) and the last fix."""
    recs = list(recs)
    boots: list[list[dict]] = []
    prev_up = None
    for r in recs:
        if prev_up is None or r["up_ms"] < prev_up or (r["type"] == "evt" and r.get("evt") == "boot"):
            boots.append([])
        boots[-1].append(r)
        prev_up = r["up_ms"]
    for b in boots:
        off = next((x["unix"] - x["up_ms"] / 1000 for x in b if x["unix"]), None)
        for x in b:
            if x["unix"]:
                x["t"], x["t_est"] = _iso(x["unix"]), False  # the tag's clock, whole seconds
            elif off is not None:
                x["t"], x["t_est"] = _iso(off + x["up_ms"] / 1000), True
            else:
                x["t"], x["t_est"] = None, None
    last = None
    for x in recs:
        if x["type"] == "gps" and x.get("gps") == "fix":
            last = x
        elif x["type"] in ("tx", "rx") and last is not None:
            age = (x["up_ms"] - last["up_ms"]) / 1000 if x["up_ms"] >= last["up_ms"] else None
            x["pos"] = {"lat": last["lat"], "lon": last["lon"], "age_s": age}
    return recs


def parse_dump(lines: Iterable[str]) -> Iterator[bytes]:
    for l in lines:
        l = l.strip()
        if l.startswith("FL "):
            b = bytes.fromhex(l[3:])
            if len(b) == REC and b[0] == MAGIC:
                yield b


# copies and packets whose x is HMAC(V_e(n), "LT2-x" || u32(n) || u8(j))[0:4] (PROTOCOL.md 2)
X_KINDS = ("report", "extra")


def add_x(recs: Iterable[dict], view: Callable[[int], bytes | None]) -> list[dict]:
    """`x` (lowercase hex) on every TX record of a report copy or extra packet whose view key is known."""
    from .lt2 import epoch, report_tag
    recs = list(recs)
    for r in recs:
        if r["type"] == "tx" and r.get("kind") in X_KINDS and r.get("j") is not None:
            ve = view(epoch(r["n"]))
            if ve is not None:
                r["x"] = report_tag(ve, r["n"], r["j"]).hex()
    return recs


def to_jsonl(lines: Iterable[str], view: Callable[[int], bytes | None] | None = None) -> list[str]:
    recs = merge_wakes(decode(b) for b in parse_dump(lines))
    if view is not None:
        recs = add_x(recs, view)
    return [json.dumps(r, separators=(",", ":")) for r in annotate(recs)]

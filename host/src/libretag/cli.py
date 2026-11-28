"""libretag command line."""

from __future__ import annotations

import argparse
import logging
import random
import sys
import time
from pathlib import Path

from . import energy
from . import lt2
from .base import BaseStation
from .lt2 import Command, Keys, Mode
from .sim import run_on_air
from .store import Store
from .transport import HexStdio, KissSerial


def _transport(args):
    if getattr(args, "kiss", None):
        return KissSerial(args.kiss, args.baud)
    return HexStdio()


def cmd_pair(args) -> None:
    """K0 comes from the tracker (USB console `status` -> LIBRETAG KEY <K0>, PROTOCOL.md 6)."""
    try:
        k0 = bytes.fromhex(args.k0)
    except ValueError:
        sys.exit("K0 must be hex")
    if len(k0) != lt2.KEY_SIZE:
        sys.exit("K0 must be 32 bytes (64 hex characters)")
    st = Store(args.db)
    t = st.add_tracker(args.name, k0, args.traccar_id)
    print(f"tracker: {t.name}")
    print(f"id:      {Keys(t.k0).id.hex()}  (the tracker prints LIBRETAG ID <id> once locked)")
    print("next: send `lock` on the tracker's USB console so it erases K0")


def cmd_list(args) -> None:
    st = Store(args.db)
    for t in st.trackers():
        print(f"{t.name}: id={Keys(t.k0).id.hex()} last_n={t.last_n} cmd_seq={t.cmd_seq} traccar_id={t.traccar_id}")
        for n, rx, fix_t, has_fix, lat, lon, batt, mode, hops in st.positions(t.name, args.limit):
            when = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(rx))
            if has_fix:
                pos = f"{lat:.6f},{lon:.6f}"
            elif fix_t:
                pos = f"last {lat:.5f},{lon:.5f} {int(rx - fix_t) // 60} min"
            else:
                pos = "no fix yet"
            print(f"  #{n:<5d} {when} {pos:30s} batt={batt} mode={Mode(mode).name} hops={hops}")
        for seq, body, created, sends, for_n, acked, failed in st.command_log(t.name, 3):
            state = "acked" if acked else "given up" if failed else f"sent {sends}x" if sends else "queued"
            print(f"  cmd seq={seq} {Command.decode(body).op.name} {state}")


def cmd_base(args) -> None:
    st = Store(args.db)
    base = BaseStation(st, _transport(args), args.traccar)
    logging.info("base station up, %d tracker(s)", len(base.windows))
    base.run()


def cmd_cmd(args) -> None:
    st = Store(args.db)
    builders = {
        "track": lambda s: Command.set_mode(s, Mode.TRACK, args.interval, args.duration),
        "listen": lambda s: Command.set_mode(s, Mode.LISTEN, 0, args.duration),
        "lost": lambda s: Command.set_mode(s, Mode.LOST, args.interval, args.duration),
        "idle": lambda s: Command.set_mode(s, Mode.IDLE),
        "set-idle": lambda s: Command.set_idle(s, args.interval, args.listen),
        "buzz": lambda s: Command.buzz(s, args.seconds),
    }
    seq = st.queue_command(args.name, builders[args.what])
    print(f"queued {args.what} seq={seq} for {args.name}; it goes out right after the next report heard here")


def cmd_sim(args) -> None:
    st = Store(args.db)
    t = st.get_tracker(args.name)
    n = args.n if args.n is not None else t.last_n + 1
    fix = None if args.no_fix else {"lat": args.lat, "lon": args.lon, "hacc": 5}
    res = run_on_air(_transport(args), t.k0, n, fix, args.battery, args.listen)
    out = sys.stderr if not args.kiss else sys.stdout
    if res["command"]:
        c = res["command"]
        print(f"sim: report n={n} -> got {c.op.name} seq={c.seq} after {res['rtt_s']:.1f} s", file=out)
    else:
        print(f"sim: report n={n} sent, no command within {args.listen} s", file=out)


def cmd_energy(args) -> None:
    if args.device:
        dev = energy.DEVICES[args.device]
        if args.sleep_ua is not None:
            from dataclasses import replace

            dev = replace(dev, sleep_ua=args.sleep_ua)
        sc = energy.Scenario(
            report_interval_min=args.interval, listen_s=args.listen, fix_s=args.fix,
            sf=args.sf, bw_hz=args.bw, cr=args.cr, check_interval_min=args.check_every,
            check_listen_s=args.check_listen,
        )
        p = energy.daily_mah(dev, sc)
        print(f"preset     {energy.preset_label(sc.sf, sc.bw_hz, sc.cr)}")
        for k, v in p.items():
            if k not in ("sf", "bw_hz", "cr"):
                print(f"{k:10s} {v:10.3f}")
    elif args.report_every:
        print(energy.report_every_table())
    else:
        print(energy.default_table())


def cmd_demo(args) -> None:
    """Self-contained end-to-end run with an in-memory DB and a simulated 3-hop mesh."""
    from .meshcore import Packet
    from .sim import TrackerState

    class Loop:
        def __init__(self):
            self.sent: list[bytes] = []

        def send(self, raw):
            self.sent.append(raw)

        def receive(self, timeout=None):
            return iter(())

    st = Store(":memory:")
    k0 = bytes(random.randrange(256) for _ in range(32))
    st.add_tracker("demo", k0)
    radio = Loop()
    base = BaseStation(st, radio)
    tracker = TrackerState(k0=k0)
    hops = [bytes([random.randrange(256)]) for _ in range(3)]
    now = time.time()

    def flood(raw: bytes) -> bytes:
        p = Packet.from_bytes(raw)
        p.path = list(hops)  # three repeaters appended their hashes
        return p.to_bytes()

    def direct(raw: bytes) -> bytes:
        p = Packet.from_bytes(raw)
        assert p.path == list(reversed(hops)), "command must follow reversed report path"
        p.path = []  # each repeater strips itself
        return p.to_bytes()

    lat, lon = 48.7000, 21.2500
    for step in range(6):
        if step == 2:
            st.queue_command("demo", lambda s: Command.set_mode(s, Mode.TRACK, 120, 30))
            print("  [owner] queued TRACK (2 min, 30 min)")
        fix = {"time": now, "lat": lat, "lon": lon, "hacc": 4}
        pkt = tracker.make_report(fix, 80 - step, now)
        raw = flood(pkt.to_bytes())
        print(f"  [tracker] report n={tracker.n - 1} ({len(raw)} B, mode {tracker.mode.name})")
        radio.sent.clear()
        got = base.handle_raw(raw)
        print(f"  [base] decoded n={got.n} {got.report.lat:.5f},{got.report.lon:.5f} hops={len(got.packet.path)}")
        for out in radio.sent:
            cmd = tracker.try_command(direct(out), now)
            print(f"  [base] sent {len(out)} B command -> tracker applied {cmd.op.name if cmd else None}")
        if not radio.sent:
            print("  [base] nothing queued, stays silent")
        now += tracker.next_interval_s(now)
        lat += 0.01
    print("tracker log:", tracker.log)
    print("pending command after ack:", st.pending_command("demo"))


def cmd_vectors(args) -> None:
    import json

    path = Path(args.out)
    path.write_text(json.dumps(lt2.vectors(), indent=1) + "\n")
    print(f"wrote {path}")


def cmd_share_import(args) -> None:
    """A share link from the app (Share > QR code or DM). Prints what came, never the keys."""
    from . import share
    text = sys.stdin.read() if args.link == "-" else args.link
    sh = share.parse_link(text)
    if sh is None:
        sys.exit("not a LibreTag share link (libretag://share?...)")
    path = share.save(sh, Path(args.config) if args.config else None)
    level = {"full": "full access (console key)", "control": "view and control", "view": "view only"}[sh.level]
    print(f"tag:   {sh.name} ({sh.type})")
    print(f"level: {level}, from epoch {sh.epoch}")
    if sh.id:
        print(f"id:    {sh.id}")
    print(f"saved: {path} (mode 600)")
    if sh.level == "full":
        print(f"next:  libretag console --tag {sh.id} -c status")


def cmd_share_list(args) -> None:
    from . import share
    for path, sh in share.saved(Path(args.config) if args.config else None):
        print(f"{sh.name}: level={sh.level} id={sh.id or '-'} epoch={sh.epoch} file={path}")


def _console_key(args) -> tuple[bytes, str | None]:
    """usb_key and the id it belongs to: --k0-file (the owner) or a stored full-access share."""
    if args.k0_file:
        k = Keys(bytes.fromhex(Path(args.k0_file).read_text().strip()))
        return k.usb_key, k.id.hex()
    from . import share
    try:
        sh = share.find(args.tag, Path(args.config) if args.config else None)
    except LookupError as e:
        sys.exit(str(e))
    return bytes.fromhex(sh.usb_key), sh.id


def _view_of(args):
    """epoch -> V_e for `x` in the field log: from --k0-file or a stored share (any level), else None.
    Only x goes into the output; no key does."""
    from .lt2 import chain
    if args.k0_file:
        k = Keys(bytes.fromhex(Path(args.k0_file).read_text().strip()))
        return k.view
    from . import share
    try:
        sh = share.find(args.tag, Path(args.config) if args.config else None, need_console=False)
    except LookupError:
        return None
    base, e0 = bytes.fromhex(sh.view), sh.epoch
    return lambda e: chain(base, e - e0) if e >= e0 else None


def cmd_console(args) -> None:
    """The tag's USB console, unlocked with the stored key; -c runs lines, else lines from stdin."""
    from . import console
    usb_key, want_id = _console_key(args)
    try:
        path = args.port or console.find_port()
    except LookupError as e:
        sys.exit(str(e))
    port = console.open_serial(path, esp=args.esp)
    try:
        c = console.Console(port)
        st = c.status()
        if st.id is None and not st.locked:
            sys.exit(f"{path}: no answer to status, or the tag isn't paired (no LIBRETAG ID)")
        if want_id and st.id and st.id != want_id:
            sys.exit(f"{path}: this is tag {st.id}, not {want_id}")
        if st.locked and not c.unlock(usb_key):
            sys.exit("unlock refused: the key doesn't fit this tag (re-keyed since it was shared?)")
        print(f"# {path}: tag {st.id or '?'} unlocked", file=sys.stderr)

        def run(line: str) -> None:
            out = c.command(line, quiet_s=args.quiet, max_s=args.max)
            if any(l.startswith("ERR locked") for l in out) and c.unlock(usb_key):  # the unlock lasts 10 min
                out = c.command(line, quiet_s=args.quiet, max_s=args.max)
            print("\n".join(out), flush=True)

        if args.c:
            for line in args.c:
                run(line)
        else:
            for line in sys.stdin:
                if line.strip():
                    run(line.strip())
    finally:
        port.close()


def cmd_fieldeval(args) -> None:
    from . import fieldeval

    print(fieldeval.run(args.paths, args.timeline, args.json, args.ref, args.band, args.since, args.until, args.match))


def cmd_fieldlog(args) -> None:
    """Pull the tag's field-test log over USB (or decode a saved dump) into JSON lines."""
    from . import console, fieldlog
    if args.decode:
        lines = Path(args.decode).read_text().splitlines()
    else:
        usb_key, want_id = _console_key(args)
        try:
            path = args.port or console.find_port()
        except LookupError as e:
            sys.exit(str(e))
        port = console.open_serial(path, esp=args.esp)
        try:
            c = console.Console(port)
            st = c.status()
            if want_id and st.id and st.id != want_id:
                sys.exit(f"{path}: this is tag {st.id}, not {want_id}")
            if st.locked and not c.unlock(usb_key):
                sys.exit("unlock refused")
            lines = c.command_until(f"fieldlog dump {args.since}", "END ", max_s=args.max)
            if args.clear:
                print("\n".join(c.command("fieldlog clear")), file=sys.stderr)
        finally:
            port.close()
        if args.raw:
            Path(args.raw).write_text("\n".join(lines) + "\n")
    out = fieldlog.to_jsonl(lines, _view_of(args))
    end = next((l for l in lines if l.startswith("END ")), None)
    if args.out:
        Path(args.out).write_text("".join(l + "\n" for l in out))
    else:
        for l in out:
            print(l)
    print(f"# {len(out)} records{'' if end is None else ' (' + end + ')'}", file=sys.stderr)


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(prog="libretag")
    p.add_argument("--db", help="SQLite path (default ~/.local/share/libretag/libretag.db or $LIBRETAG_DB)")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)

    def radio(sp):
        sp.add_argument("--kiss", help="serial port of a MeshCore node in KISS modem mode; default hex on stdio")
        sp.add_argument("--baud", type=int, default=115200)

    s = sub.add_parser("pair", help="add a tracker by its K0 (read from its USB console before `lock`)")
    s.add_argument("name")
    s.add_argument("k0", help="32-byte K0 as hex (LIBRETAG KEY ...)")
    s.add_argument("--traccar-id")
    s.set_defaults(func=cmd_pair)

    s = sub.add_parser("list", help="trackers and recent positions")
    s.add_argument("--limit", type=int, default=10)
    s.set_defaults(func=cmd_list)

    s = sub.add_parser("base", help="run the base station")
    radio(s)
    s.add_argument("--traccar", help="Traccar OsmAnd URL, e.g. http://localhost:5055")
    s.set_defaults(func=cmd_base)

    s = sub.add_parser("cmd", help="queue a command for a tracker")
    s.add_argument("name")
    s.add_argument("what", choices=["track", "listen", "lost", "idle", "set-idle", "buzz"])
    s.add_argument("--interval", type=int, default=120, help="track: seconds; set-idle: minutes")
    s.add_argument("--duration", type=int, default=120, help="minutes")
    s.add_argument("--listen", type=int, default=90, help="set-idle: RX window seconds")
    s.add_argument("--seconds", type=int, default=5, help="buzz")
    s.set_defaults(func=cmd_cmd)

    s = sub.add_parser("sim", help="act as the tracker once: send a report, listen for a command")
    radio(s)
    s.add_argument("name")
    s.add_argument("--n", type=int, help="report counter (default: base DB last_n + 1)")
    s.add_argument("--lat", type=float, default=48.7000)
    s.add_argument("--lon", type=float, default=21.2500)
    s.add_argument("--no-fix", action="store_true")
    s.add_argument("--battery", type=int, default=90)
    s.add_argument("--listen", type=int, default=0, help="RX window seconds")
    s.set_defaults(func=cmd_sim)

    s = sub.add_parser("energy", help="battery life estimate")
    s.add_argument("--device", choices=sorted(energy.DEVICES))
    s.add_argument("--interval", type=float, default=60, help="minutes between reports")
    s.add_argument("--listen", type=float, default=90)
    s.add_argument("--fix", type=float, default=15)
    s.add_argument("--sleep-ua", type=float)
    s.add_argument("--sf", type=int, default=energy.LIVE_SF, help="default: the live preset SF7/62.5 kHz/CR4/5")
    s.add_argument("--bw", type=float, default=energy.LIVE_BW_HZ)
    s.add_argument("--cr", type=int, default=energy.LIVE_CR)
    s.add_argument("--check-every", type=float, help="0.7.3: minutes between wake-ups that only listen")
    s.add_argument("--check-listen", type=float, default=34, help="receiver seconds per listen-only wake-up")
    s.add_argument("--report-every", action="store_true", help="table: report every 1 h vs 3 h (0.7.3)")
    s.set_defaults(func=cmd_energy)

    s = sub.add_parser("demo", help="end-to-end run without hardware")
    s.set_defaults(func=cmd_demo)

    s = sub.add_parser("vectors", help="write the LT2 test vectors (JSON, shared by C, Python and Kotlin)")
    s.add_argument("--out", default="tests/vectors/lt2.json")
    s.set_defaults(func=cmd_vectors)

    s = sub.add_parser("share", help="shared tags: import a share link from the app, list them")
    ssub = s.add_subparsers(dest="share_cmd", required=True)
    si = ssub.add_parser("import", help="store a libretag://share link (keys go to a mode 600 file, never stdout)")
    si.add_argument("link", nargs="?", default="-", help="the link, or - to read it from stdin (default; keeps it out of shell history)")
    si.add_argument("--config", help="config directory (default ~/.config/libretag or $LIBRETAG_CONFIG)")
    si.set_defaults(func=cmd_share_import)
    sl = ssub.add_parser("list", help="stored shared tags (no keys)")
    sl.add_argument("--config")
    sl.set_defaults(func=cmd_share_list)

    s = sub.add_parser("console", help="the tag's USB console, unlocked with its usb_key")
    s.add_argument("--tag", help="stored share: name or id prefix (default: the only one with full access)")
    s.add_argument("--k0-file", help="the owner's K0 (64 hex) instead of a stored share")
    s.add_argument("--config")
    s.add_argument("--port", help="serial port (default: the one tag found on USB)")
    s.add_argument("--esp", action="store_true", help="ESP32 tag (Heltec): leave DTR and RTS low so it doesn't reset")
    s.add_argument("-c", action="append", help="a console line (repeat); without -c lines come from stdin")
    s.add_argument("--quiet", type=float, default=0.6, help="an answer ends after this many quiet seconds")
    s.add_argument("--max", type=float, default=15, help="the longest one answer may take (s)")
    s.set_defaults(func=cmd_console)

    s = sub.add_parser("fieldlog", help="pull the tag's field-test log (firmware 0.9.8) as JSON lines")
    s.add_argument("--tag")
    s.add_argument("--k0-file")
    s.add_argument("--config")
    s.add_argument("--port")
    s.add_argument("--esp", action="store_true")
    s.add_argument("--out", help="JSONL file (default stdout); it holds positions, keep it out of the repo")
    s.add_argument("--raw", help="also save the raw dump lines here")
    s.add_argument("--decode", help="decode a saved raw dump instead of reading the tag")
    s.add_argument("--since", type=int, default=0, help="only records with seq >= this")
    s.add_argument("--clear", action="store_true", help="clear the log on the tag after a complete dump")
    s.add_argument("--max", type=float, default=900, help="longest the dump may take (s)")
    s.set_defaults(func=cmd_fieldlog)

    s = sub.add_parser("fieldeval", help="merge tag, bridge and app field logs into one timeline and summary")
    s.add_argument("paths", nargs="+", help="JSONL files or directories (tag fieldlog, bridge-*.jsonl, app-*.jsonl)")
    s.add_argument("--timeline", help="write the merged timeline (JSONL, one line per report copy; holds positions)")
    s.add_argument("--json", action="store_true", help="summary as JSON")
    s.add_argument("--ref", help="lat,lon for the coverage distance bands (default: the first position)")
    s.add_argument("--band", type=float, default=500.0, help="coverage band width in metres")
    s.add_argument("--since", type=float, help="unix s")
    s.add_argument("--until", type=float, help="unix s")
    s.add_argument("--match", type=float, default=30.0, help="pair a tag tx with a bridge hearing within this many s")
    s.set_defaults(func=cmd_fieldeval)

    args = p.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        stream=sys.stderr,
    )
    args.func(args)


if __name__ == "__main__":
    main()
